package com.shinoyuki.betterbackup.retention;

import com.shinoyuki.betterbackup.gc.StoreGc;
import com.shinoyuki.betterbackup.restore.PendingRestoreFlag;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetentionPruner 端到端测试: 三门禁安全网 + 只删 manifest + 幂等 + fail-fast abort +
 * 后置硬不变量。所有断言均针对具体 id / store.has 结果, 删掉核心逻辑测试必挂。
 */
class RetentionPrunerTest {

    private static Hash hash(int seed) {
        byte[] b = new byte[16];
        b[0] = (byte) (seed & 0xFF);
        b[1] = (byte) ((seed >> 8) & 0xFF);
        b[2] = (byte) ((seed >> 16) & 0xFF);
        b[3] = (byte) ((seed >> 24) & 0xFF);
        for (int i = 4; i < 16; i++) {
            b[i] = (byte) ((seed * (i + 31)) & 0xFF);
        }
        return new Hash(b);
    }

    private static byte[] payload(int seed) {
        byte[] b = new byte[8 + (Math.abs(seed) % 8)];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ((seed + i) & 0xFF);
        }
        return b;
    }

    /** 写一份引用 referenced 的 manifest, baselineComplete 由参数决定。 */
    private static void writeManifest(Path snapshotsDir, String id, Set<Hash> referenced,
                                      boolean baselineComplete) throws IOException {
        Map<Long, Hash> chunkMap = new HashMap<>();
        long pos = 0;
        for (Hash h : referenced) {
            chunkMap.put(pos++, h);
        }
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put("minecraft:overworld", chunkMap);
        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, id, System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L,
                baselineComplete, FileManifest.empty());
        m.writeTo(snapshotsDir.resolve(id + ".manifest"));
    }

    private static void writeEmptyManifest(Path snapshotsDir, String id, boolean baselineComplete)
            throws IOException {
        writeManifest(snapshotsDir, id, Set.of(), baselineComplete);
    }

    /** 现存 manifest 的 id 集 (去后缀), 用于断言删/留。 */
    private static Set<String> existingIds(Path snapshotsDir) throws IOException {
        try (Stream<Path> s = Files.list(snapshotsDir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".manifest"))
                    .map(n -> n.substring(0, n.length() - ".manifest".length()))
                    .collect(Collectors.toSet());
        }
    }

    private static boolean baselineCompleteCountAtLeastOne(Path snapshotsDir) throws IOException {
        for (String id : existingIds(snapshotsDir)) {
            if (SnapshotManifest.readFrom(snapshotsDir.resolve(id + ".manifest")).baselineComplete()) {
                return true;
            }
        }
        return false;
    }

    // ---- 门禁安全网 ------------------------------------------------------------

    @Test
    void only_baseline_doomed_by_quota_is_saved_and_survives(@TempDir Path tempDir) throws IOException {
        // 1 份 baselineComplete + 一堆 incomplete; 配额 (hourly=1) 把该 baseline 判 doomed.
        // 断言最终删除集不含它, 且淘汰后仍有 >=1 份 baselineComplete.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-05-08T12-00-00Z", true);   // 唯一 baselineComplete, 较早
        writeEmptyManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", false);  // latest, incomplete

        // hourly=1: policy 只留最新一份, 其余 (含那份 baselineComplete) 全 doomed.
        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        RetentionPruner.PruneResult r = pruner.prune();

        // 该 baselineComplete 被门禁 B 救回, 不在实删集.
        assertFalse(r.deleted().contains("2026-05-08T12-00-00Z"),
                "the only baseline-complete snapshot must not be deleted");
        assertTrue(r.savedByGuard().contains("2026-05-08T12-00-00Z"),
                "the only baseline-complete snapshot must be reported saved-by-guard");
        // 淘汰后仍有 baselineComplete.
        assertTrue(baselineCompleteCountAtLeastOne(snapshotsDir),
                "at least one baseline-complete snapshot must survive");
        // manifest 物理仍在.
        assertTrue(Files.exists(snapshotsDir.resolve("2026-05-08T12-00-00Z.manifest")));
        // 中间那份 incomplete 无门禁保护, 应被删.
        assertEquals(Set.of("2026-05-09T12-00-00Z"), r.deleted());
    }

    @Test
    void all_zero_config_disables_prune_keeps_everything(@TempDir Path tempDir) throws IOException {
        // 四档全 0 = 保留策略未启用 (config 未加载默认 0 / 用户笔误): 一份不删, 保留全部.
        // 这是备份 mod 的 fail-safe: 绝不把"全零"曲解为"删到只剩门禁兜底".
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-05-08T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", false);  // latest
        Set<String> before = existingIds(snapshotsDir);

        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(0, 0, 0, 0));
        RetentionPruner.PruneResult r = pruner.prune();

        assertEquals(Set.of(), r.deleted(), "all-zero quota disables prune, deletes nothing");
        assertEquals(before, existingIds(snapshotsDir), "every snapshot survives");
    }

    @Test
    void pending_restore_target_survives_prune(@TempDir Path tempDir) throws IOException {
        // PendingRestoreFlag 指向的 X (非最新) 恰在 doomed (hourly=1 只留最新) -> 门禁 C 救回 X.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-05-08T12-00-00Z", false);  // pending 目标, 被 hourly=1 doom
        writeEmptyManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", true);   // latest + baseline
        PendingRestoreFlag.write(worldRoot, "2026-05-08T12-00-00Z");

        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        RetentionPruner.PruneResult r = pruner.prune();

        assertTrue(Files.exists(snapshotsDir.resolve("2026-05-08T12-00-00Z.manifest")),
                "pending-restore target manifest must survive");
        assertFalse(r.deleted().contains("2026-05-08T12-00-00Z"));
        assertTrue(r.savedByGuard().contains("2026-05-08T12-00-00Z"));
        // 中间那份无保护应删.
        assertEquals(Set.of("2026-05-09T12-00-00Z"), r.deleted());
        // 后置硬不变量 (3): pending flag 指向的 id 仍在.
        assertTrue(existingIds(snapshotsDir).contains(
                PendingRestoreFlag.read(worldRoot).orElseThrow()));
    }

    // ---- 端到端: 删 manifest + gcAll 回收独占对象 --------------------------------

    @Test
    void deleting_manifest_then_gc_reclaims_exclusive_objects(@TempDir Path tempDir) throws IOException {
        // snap-A 引用 {1..7}, snap-B 引用 {4..10} (共享 4..7). 删 snap-A.manifest 后 gcAll:
        // A 独占 1,2,3 被回收, 4..10 全部存活.
        Path storeRoot = tempDir.resolve("backup-store");
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        for (int i = 1; i <= 10; i++) {
            store.put(hash(i), payload(i));
        }
        Set<Hash> setA = new HashSet<>();
        for (int i = 1; i <= 7; i++) setA.add(hash(i));
        Set<Hash> setB = new HashSet<>();
        for (int i = 4; i <= 10; i++) setB.add(hash(i));
        // A 较早且 incomplete (不受门禁 B 保护), B 最新且 baselineComplete.
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", setA, false);   // snap-A
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", setB, true);    // snap-B (latest + baseline)

        // hourly=1 -> 只留最新 B, A 被 doom 且无门禁保护 (最新是 B, 唯一 baselineComplete 也是 B).
        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        RetentionPruner.PruneResult pr = pruner.prune();
        assertEquals(Set.of("2026-05-09T12-00-00Z"), pr.deleted(), "snap-A manifest deleted");
        assertFalse(Files.exists(snapshotsDir.resolve("2026-05-09T12-00-00Z.manifest")));

        // 淘汰不同步回收物理: 独占对象此刻仍在 store (仅 manifest 引用释放).
        for (int i = 1; i <= 3; i++) {
            assertTrue(store.has(hash(i)), "exclusive object " + i + " still present before gc");
        }
        store.close();

        // 重开跑 gcAll (封口在写 pack), 现在只有 snap-B 存活 -> A 独占 1,2,3 死对象被回收.
        ChunkStore reopened = new ChunkStore(storeRoot);
        reopened.initialize();
        StoreGc gc = new StoreGc(reopened, snapshotsDir);
        StoreGc.GcResult gr = gc.gcAll();

        assertEquals(3, gr.deleted(), "A's 3 exclusive objects reclaimed");
        for (int i = 1; i <= 3; i++) {
            assertFalse(reopened.has(hash(i)), "exclusive object " + i + " reclaimed");
        }
        for (int i = 4; i <= 10; i++) {
            assertTrue(reopened.has(hash(i)), "shared/B object " + i + " survives");
        }
    }

    // ---- 幂等 ------------------------------------------------------------------

    @Test
    void prune_is_idempotent(@TempDir Path tempDir) throws IOException {
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-05-08T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", true);  // latest + baseline

        RetentionPruner pruner1 = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        RetentionPruner.PruneResult r1 = pruner1.prune();
        assertEquals(Set.of("2026-05-08T12-00-00Z", "2026-05-09T12-00-00Z"), r1.deleted());
        Set<String> afterFirst = existingIds(snapshotsDir);

        // 第二次: doomed 里的 id 对应 manifest 已不存在, 删 0, 目录内容不变.
        RetentionPruner pruner2 = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        RetentionPruner.PruneResult r2 = pruner2.prune();
        assertEquals(Set.of(), r2.deleted(), "second prune deletes nothing");
        assertEquals(afterFirst, existingIds(snapshotsDir), "snapshotsDir content unchanged");
    }

    // ---- fail-fast abort -------------------------------------------------------

    @Test
    void invalid_id_aborts_prune_deletes_nothing(@TempDir Path tempDir) throws IOException {
        // 非法 id 文件名 -> selectForDeletion 抛 IllegalArgumentException, 整次 abort 一份不删.
        // 用 daily>0 的 policy 确保每个 id 都会被 parse (hourly 档只取前 N 不 parse).
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", true);
        writeEmptyManifest(snapshotsDir, "not-a-snapshot-id", false);
        Set<String> before = existingIds(snapshotsDir);

        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(0, 5, 0, 0));
        assertThrows(IllegalArgumentException.class, pruner::prune);
        assertEquals(before, existingIds(snapshotsDir), "abort must delete nothing");
    }

    @Test
    void invalid_id_aborts_even_under_hourly_only_config(@TempDir Path tempDir) throws IOException {
        // 纯 hourly 配置 (daily/weekly/monthly=0) 下 policy.select 不会 parse id; 若无预检,
        // 非法 id 那份会被留下而好快照被删. 预检 requireValidId 必须使这种配置也 abort 一份不删.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-05-08T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", true);
        writeEmptyManifest(snapshotsDir, "garbage-id", false);
        Set<String> before = existingIds(snapshotsDir);

        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, pruner::prune);
        assertEquals(before, existingIds(snapshotsDir),
                "hourly-only config must still abort and delete nothing on invalid id");
    }

    @Test
    void corrupt_manifest_aborts_prune_deletes_nothing(@TempDir Path tempDir) throws IOException {
        // 损坏 manifest -> protectedIds 抛 IOException, 整次 abort 一份不删.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-05-08T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", true);  // latest + baseline
        Files.write(snapshotsDir.resolve("2026-05-09T12-00-00Z.manifest"),
                new byte[]{0x00, 0x01, 0x02, 0x03});
        Set<String> before = existingIds(snapshotsDir);

        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        assertThrows(IOException.class, pruner::prune);
        assertEquals(before, existingIds(snapshotsDir), "abort on corrupt manifest deletes nothing");
    }

    // ---- 后置硬不变量 (聚合断言) -----------------------------------------------

    @Test
    void post_conditions_after_prune_across_configs(@TempDir Path tempDir) throws IOException {
        // 造混合快照集, 淘汰前有 baselineComplete + pending flag, 断言淘汰后三条硬不变量全成立.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-04-01T00-00-00Z", true);   // 早期 baselineComplete, pending 目标
        writeEmptyManifest(snapshotsDir, "2026-05-01T00-00-00Z", true);   // 中期 baselineComplete
        writeEmptyManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", false);  // latest, incomplete
        PendingRestoreFlag.write(worldRoot, "2026-04-01T00-00-00Z");

        boolean hadBaselineBefore = baselineCompleteCountAtLeastOne(snapshotsDir);
        String pendingId = PendingRestoreFlag.read(worldRoot).orElseThrow();

        // 激进配额 (hourly=1 只留最新一份) 把其余全 doom, 只剩三门禁救回.
        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        pruner.prune();

        Set<String> after = existingIds(snapshotsDir);
        // (1) >=1 份快照.
        assertTrue(after.size() >= 1, "at least one snapshot survives");
        // (2) 淘汰前有 baselineComplete 则淘汰后仍有.
        if (hadBaselineBefore) {
            assertTrue(baselineCompleteCountAtLeastOne(snapshotsDir),
                    "a baseline-complete snapshot must survive when one existed before");
        }
        // (3) pending flag 指向的 id 仍在.
        assertTrue(after.contains(pendingId), "pending-restore target must survive");
        // 具体: 门禁 A 保最新, 门禁 B 保最新 baselineComplete (2026-05-01), 门禁 C 保 pending (2026-04-01).
        assertEquals(Set.of(
                "2026-05-10T12-00-00Z",   // A
                "2026-05-01T00-00-00Z",   // B
                "2026-04-01T00-00-00Z"),  // C
                after);
    }

    @Test
    void single_snapshot_never_deleted(@TempDir Path tempDir) throws IOException {
        // 边界: 只有 1 份. 激进配额 hourly=1 保它 (它就是最新), 门禁 A 再兜底一层.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", false);

        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        RetentionPruner.PruneResult r = pruner.prune();
        assertEquals(Set.of(), r.deleted(), "the only snapshot is never deleted");
        assertEquals(Set.of("2026-05-10T12-00-00Z"), existingIds(snapshotsDir));
    }

    @Test
    void preview_matches_prune(@TempDir Path tempDir) throws IOException {
        // dry-run preview 的将删/将留必须与真 prune 一致.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-05-08T12-00-00Z", true);
        writeEmptyManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeEmptyManifest(snapshotsDir, "2026-05-10T12-00-00Z", false);

        RetentionPruner previewer = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        RetentionPruner.Preview preview = previewer.preview();
        // 门禁 A 保最新 05-10, 门禁 B 保唯一 baselineComplete 05-08, 中间 05-09 将删.
        assertEquals(Set.of("2026-05-09T12-00-00Z"), preview.toDelete());
        assertEquals(Set.of("2026-05-10T12-00-00Z", "2026-05-08T12-00-00Z"), preview.toKeep());

        // 预览不改盘.
        assertEquals(Set.of("2026-05-08T12-00-00Z", "2026-05-09T12-00-00Z", "2026-05-10T12-00-00Z"),
                existingIds(snapshotsDir), "preview must not delete anything");

        // 真 prune 应删同一集合.
        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(1, 0, 0, 0));
        RetentionPruner.PruneResult r = pruner.prune();
        assertEquals(preview.toDelete(), r.deleted(), "prune deletes exactly what preview showed");
    }

    @Test
    void cross_month_and_week_quota_keeps_representatives(@TempDir Path tempDir) throws IOException {
        // 边界: 跨月跨周; monthly=1 只留最新月代表, 门禁不额外救 (最新恰是月代表且 baselineComplete).
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeEmptyManifest(snapshotsDir, "2026-01-15T12-00-00Z", true);
        writeEmptyManifest(snapshotsDir, "2026-01-31T20-00-00Z", true);
        writeEmptyManifest(snapshotsDir, "2026-02-01T12-00-00Z", true);   // latest, 2 月唯一, 月代表

        // monthly=1: keep = {2026-02-01 (最新月代表)}; doomed = 两份 1 月.
        // 门禁 A 保 02-01 (最新), 门禁 B 保 02-01 (最新 baselineComplete). 两份 1 月无保护 -> 删.
        RetentionPruner pruner = new RetentionPruner(snapshotsDir, worldRoot,
                new RetentionPolicy(0, 0, 0, 1));
        RetentionPruner.PruneResult r = pruner.prune();
        assertEquals(Set.of("2026-01-15T12-00-00Z", "2026-01-31T20-00-00Z"), r.deleted());
        assertEquals(Set.of("2026-02-01T12-00-00Z"), existingIds(snapshotsDir));
        assertTrue(baselineCompleteCountAtLeastOne(snapshotsDir));
    }
}
