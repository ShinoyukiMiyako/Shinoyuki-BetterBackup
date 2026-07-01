package com.shinoyuki.betterbackup.retention;

import com.shinoyuki.betterbackup.BetterBackupMod;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 滚动保留策略的执行器: 把 {@link RetentionPolicy} 算出的 doomed 集经 {@link RetentionGuard}
 * 三门禁过滤后, 对剩下的**只删 manifest 文件** (release reference)。
 *
 * <p><b>为什么只删 manifest</b>: store 是内容寻址 append-only, 对象由 {@link com.shinoyuki.betterbackup.gc.StoreGc}
 * 以"所有存活 manifest 引用的 hash"为存活根做延迟压实回收。删掉一份 manifest 后, 它独占引用
 * (不被任何其他存活 manifest 引用) 的对象立即变成死对象, 由既有 runIncrementalGc 攒够阈值的
 * 渐进压实 / 手动 {@code /betterbackup gc} 物理回收。故本执行器不在此路径同步跑 gcAll —— 删
 * manifest 是 O(份数) 轻操作, 物理回收是重活, 解耦。
 *
 * <p><b>fail-fast abort 铁律</b>: 备份 mod 删数据必须极保守。以下任一情况整次淘汰中止、一份不删:
 * <ul>
 *   <li>allSnapshotIds 含无法 parse 的 id ({@link RetentionPolicy#select} 抛
 *       {@link IllegalArgumentException})</li>
 *   <li>{@link RetentionGuard#protectedIds()} 读某份 manifest 损坏 / 列目录失败 (抛 IOException)</li>
 * </ul>
 * 绝不"删好的留坏的": 看不清全貌 (有一份 manifest 读不出) 时无法证明 doomed 里某个 id 不是唯一
 * baselineComplete, 只能全体退避, 交由用户离线修复损坏 manifest 后重试。这与
 * {@link com.shinoyuki.betterbackup.gc.StoreGc} 对损坏 manifest 一律 abort 的纪律一致。
 *
 * <p><b>幂等</b>: 连续跑两次, 第二次 doomed 里的 id 对应 manifest 已不存在 (delete 用
 * {@link Files#deleteIfExists}), 删 0 份。
 */
public final class RetentionPruner {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private static final String MANIFEST_SUFFIX = ".manifest";

    private final Path snapshotsDir;
    private final RetentionGuard guard;
    private final RetentionPolicy policy;

    /** 生产入口: 用当前 config 配额构造 policy (pruner 短命, 每次淘汰新建, 故构造期读 config 即当下值)。 */
    public RetentionPruner(Path snapshotsDir, Path worldRoot) {
        this(snapshotsDir, worldRoot, RetentionPolicy.fromConfig());
    }

    /** 测试入口: 注入显式 policy, 不依赖静态 config (config 未加载时四档默认 0 会把全部判 doomed)。 */
    RetentionPruner(Path snapshotsDir, Path worldRoot, RetentionPolicy policy) {
        this.snapshotsDir = Objects.requireNonNull(snapshotsDir, "snapshotsDir");
        this.guard = new RetentionGuard(snapshotsDir, Objects.requireNonNull(worldRoot, "worldRoot"));
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** dry-run 预览结果: 将删 / 将留两个集合, 不含任何副作用。 */
    public record Preview(Set<String> toDelete, Set<String> toKeep) {
        public Preview {
            toDelete = Collections.unmodifiableSet(new TreeSet<>(toDelete));
            toKeep = Collections.unmodifiableSet(new TreeSet<>(toKeep));
        }
    }

    /** 一次淘汰的结果: 实删的 id 集与被三门禁救回 (本会 doomed 但受保护) 的 id 集。 */
    public record PruneResult(Set<String> deleted, Set<String> savedByGuard) {
        public PruneResult {
            deleted = Collections.unmodifiableSet(new TreeSet<>(deleted));
            savedByGuard = Collections.unmodifiableSet(new TreeSet<>(savedByGuard));
        }
    }

    /**
     * 计算 dry-run 预览: policy.selectForDeletion 减去三门禁保护集 = 将删; 其余 = 将留。
     * 不真删。与 {@link #prune()} 走同一套判定, 保证 preview 所示即 prune 所为。
     *
     * @throws IllegalArgumentException allSnapshotIds 含非法 id (fail-fast, 不产出半套预览)
     * @throws IOException              读 manifest / pending flag 失败
     */
    public Preview preview() throws IOException {
        List<String> all = listSnapshotIds();
        // 全零配额 = 保留策略未启用: 预览"将删空集、全部将留", 与 prune 的短路一致。
        if (policy.retainsNothing()) {
            return new Preview(Set.of(), new HashSet<>(all));
        }
        all.forEach(RetentionPolicy::requireValidId);
        Set<String> doomed = policy.selectForDeletion(all);
        Set<String> protectedIds = guard.protectedIds();

        Set<String> toDelete = new HashSet<>(doomed);
        toDelete.removeAll(protectedIds);

        Set<String> toKeep = new HashSet<>(all);
        toKeep.removeAll(toDelete);
        return new Preview(toDelete, toKeep);
    }

    /**
     * 执行淘汰: 算 doomed → 减门禁保护 → 只删 manifest 文件。
     *
     * <p>三门禁保护集与 doomed 都在删除任何文件**之前**全部算完 (二者任一抛异常都不会已经删了
     * 一半), 保证 fail-fast abort 一份不删的原子语义。
     *
     * @return 本次实删 id 与被门禁救回 id
     * @throws IllegalArgumentException allSnapshotIds 含非法 id (fail-fast abort, 一份不删)
     * @throws IOException              读 manifest / pending flag / 删文件失败
     */
    public PruneResult prune() throws IOException {
        List<String> all = listSnapshotIds();

        // 全零配额 = 保留策略未启用 (config 未加载默认 0 / 用户笔误): 一份不删, 保留全部快照。
        // 备份 mod 绝不能把"全零"曲解为"删到只剩门禁兜底". 单档为 0 不触发此短路 (照常按其余档淘汰)。
        if (policy.retainsNothing()) {
            return new PruneResult(Set.of(), Set.of());
        }

        // 无条件预检: 逐一 parse 所有 id, 任一非法立即抛 (与配额档位无关)。纯 hourly 配置下
        // policy.select 不会 parse id, 若不预检则非法 id 那份可能被留下而好快照被删 —— 删数据前
        // 必须先证明全体 id 合法。此步在任何删除之前, 抛出即 abort 一份不删。
        all.forEach(RetentionPolicy::requireValidId);

        // 再把两侧判定全部算完才动手删: selectForDeletion 遇非法 id 抛 IllegalArgumentException,
        // protectedIds 遇损坏 manifest 抛 IOException, 任一抛出时尚未删任何文件 => abort 一份不删。
        Set<String> doomed = policy.selectForDeletion(all);
        Set<String> protectedIds = guard.protectedIds();

        Set<String> savedByGuard = new HashSet<>(doomed);
        savedByGuard.retainAll(protectedIds);

        List<String> toDelete = new ArrayList<>(doomed);
        toDelete.removeAll(protectedIds);
        Collections.sort(toDelete);

        Set<String> deleted = new HashSet<>();
        for (String id : toDelete) {
            Path manifestFile = snapshotsDir.resolve(id + MANIFEST_SUFFIX);
            // deleteIfExists: 幂等 (第二次跑时已删的不报错), 且并发 GC 不会因此 abort。
            if (Files.deleteIfExists(manifestFile)) {
                deleted.add(id);
            }
        }

        LOGGER.info("[BetterBackup] retention prune: deleted {} snapshot(s) {} (saved by guard: {} {})",
                deleted.size(), new TreeSet<>(deleted), savedByGuard.size(), new TreeSet<>(savedByGuard));
        return new PruneResult(deleted, savedByGuard);
    }

    /**
     * 列 snapshotsDir 下所有 {@code .manifest} 的 id (去后缀), **按字典序倒序 (最新在前)**。
     * {@link RetentionPolicy#select} 的契约要求输入即为此序 (它靠"列表倒序 => 每组首个即最新"
     * 的假设 O(n) 选代表), 故这里必须排好序再喂, 否则 hourly 会取错前 N 份、daily/weekly/monthly
     * 会把非最新那份选成组代表。{@code Files.list} 的返回顺序未定义, 绝不能直接透传。
     */
    private List<String> listSnapshotIds() throws IOException {
        if (!Files.isDirectory(snapshotsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(snapshotsDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(MANIFEST_SUFFIX))
                    .map(name -> name.substring(0, name.length() - MANIFEST_SUFFIX.length()))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }
}
