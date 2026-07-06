package com.shinoyuki.betterbackup.retention;

import com.shinoyuki.betterbackup.restore.PendingRestoreFlag;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 保留策略删除侧的安全网: 三条与配额无关的硬门禁, 计算出"无论如何都不可删"的 snapshot id 集,
 * 供自动淘汰 ({@link RetentionPruner}) 与手动 {@code /betterbackup snapshot delete} 共用。
 *
 * <p>这是备份 mod 删数据前的最后一道兜底。滚动保留策略 {@link RetentionPolicy} 只按 hourly /
 * daily / weekly / monthly 配额算"该留谁", 但配额是用户可配的 (例如 hourly=0), 极端配置下策略
 * 可能把最后一份能恢复的快照判成 doomed。三条门禁与配额正交叠加, 保证任何配置下都留得住恢复点:
 *
 * <ul>
 *   <li><b>门禁 A — 永不删最新一份</b>: snapshotsDir 下 {@code .manifest} 文件名字典序最大者
 *       (id = {@code yyyy-MM-dd'T'HH-mm-ss'Z'}, 字典序 == 时间序, 故字典序最大 == 时间最新)。
 *       无条件, 与配额无关。哪怕用户配 hourly=daily=weekly=monthly=0, 也至少留住最新这一份,
 *       避免"淘汰把 store 清空"。</li>
 *   <li><b>门禁 B — 永不删最新的那一份 baselineComplete</b>: 遍历所有 manifest 读
 *       {@link SnapshotManifest#baselineComplete()}, 若存在 >=1 份 true, 保护其中字典序最大
 *       (最新) 的那一份。这保证永远至少留一个可 restore 点 (离线 restore 命令有 baselineComplete
 *       门禁, 只有 baselineComplete 的快照才放行恢复)。一份 baselineComplete 都没有时此门禁不救
 *       任何 id (无恢复点可保, 交由门禁 A 保最新)。</li>
 *   <li><b>门禁 C — 永不删 pending-restore flag 指向的 id</b>: {@link PendingRestoreFlag#read}
 *       返回的 id (若有)。用户已下达 restore 正等停服重启, 此刻删掉目标 manifest 会让重启时
 *       RestoreFlow 找不到快照。</li>
 * </ul>
 *
 * <p>门禁 A/B 叠加而非二选一: 最新一份永远留 (A) 且最新 baselineComplete 永远留 (B), 二者可能是
 * 同一份 (最新恰好 baselineComplete) 也可能不同 (最新是 incomplete 的近期快照, 最新 baselineComplete
 * 是更早那份), 两种情况都各自保住。
 *
 * <p><b>损坏 manifest 处理</b>: 计算受保护集期间任何 manifest 读失败直接抛 IOException (对齐
 * {@link com.shinoyuki.betterbackup.gc.StoreGc} 的 abort 语义)。绝不静默跳过损坏 manifest ——
 * 跳过等于在"不知道它是不是唯一 baselineComplete"的情况下允许删别的, 违反 fail-fast。调用方
 * ({@link RetentionPruner}) 据此整次淘汰中止一份不删。
 */
public final class RetentionGuard {

    private static final String MANIFEST_SUFFIX = ".manifest";

    private final Path snapshotsDir;
    private final Path worldRoot;

    public RetentionGuard(Path snapshotsDir, Path worldRoot) {
        this.snapshotsDir = Objects.requireNonNull(snapshotsDir, "snapshotsDir");
        this.worldRoot = Objects.requireNonNull(worldRoot, "worldRoot");
    }

    /**
     * 计算受三条门禁保护、无论如何都不可删的 snapshot id 集。
     *
     * @return 受保护 id 集 (可能为空: 空目录 / 无 baselineComplete / 无 pending flag 时门禁 B/C
     *         不救任何 id, 但只要目录里有 >=1 份 manifest 门禁 A 必保住最新一份)
     * @throws IOException 列目录失败 / 任意 manifest 读失败 / 读 pending flag 失败 —— 一律硬失败,
     *                     调用方据此中止淘汰, 不在"看不清全貌"时删任何东西
     */
    public Set<String> protectedIds() throws IOException {
        Set<String> protectedIds = new HashSet<>();

        List<String> ids = listSnapshotIds();
        if (!ids.isEmpty()) {
            // 门禁 A: 最新一份 (字典序最大 == 时间最新), 无条件保护。
            String latest = ids.stream().max(Comparator.naturalOrder()).orElseThrow();
            protectedIds.add(latest);

            // 门禁 B: 最新的一份 baselineComplete (若存在)。读每份 manifest 的 baselineComplete()。
            String latestBaseline = latestBaselineCompleteId(ids);
            if (latestBaseline != null) {
                protectedIds.add(latestBaseline);
            }
        }

        // 门禁 C: pending-restore flag 指向的 id (若有), 与目录是否有 manifest 无关。
        Optional<String> pendingTarget = PendingRestoreFlag.read(worldRoot);
        pendingTarget.ifPresent(protectedIds::add);

        return protectedIds;
    }

    /** {@code id} 是否可删 (未命中任何门禁)。语义等价于 {@code !protectedIds().contains(id)}。 */
    public boolean isDeletable(String id) throws IOException {
        Objects.requireNonNull(id, "id");
        return !protectedIds().contains(id);
    }

    /**
     * 遍历所有 manifest, 返回字典序最大 (最新) 的一份 baselineComplete==true 的 id;
     * 无任何 baselineComplete 时返回 null。损坏 manifest 抛出 (不静默跳过)。
     */
    private String latestBaselineCompleteId(List<String> ids) throws IOException {
        String best = null;
        for (String id : ids) {
            Path manifestFile = snapshotsDir.resolve(id + MANIFEST_SUFFIX);
            SnapshotManifest manifest;
            try {
                manifest = SnapshotManifest.readFrom(manifestFile);
            } catch (IOException | RuntimeException e) {
                throw new IOException("retention guard aborted: failed to read manifest " + manifestFile, e);
            }
            if (manifest.baselineComplete() && (best == null || id.compareTo(best) > 0)) {
                best = id;
            }
        }
        return best;
    }

    /** 列 snapshotsDir 下所有 {@code .manifest} 的 id (去后缀), 顺序未定义。目录不存在返回空。 */
    private List<String> listSnapshotIds() throws IOException {
        if (!Files.isDirectory(snapshotsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(snapshotsDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(MANIFEST_SUFFIX))
                    .map(name -> name.substring(0, name.length() - MANIFEST_SUFFIX.length()))
                    .toList();
        }
    }
}
