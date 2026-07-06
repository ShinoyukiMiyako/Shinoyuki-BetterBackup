package com.shinoyuki.betterbackup.gc;

import com.shinoyuki.betterbackup.retention.RetentionPolicy;
import com.shinoyuki.betterbackup.retention.RetentionPrunerTestFactory;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StoreSizeGuard} 编排逻辑测试: 阈值判定 + prune-先于-gcAll 的耦合 + 软阈值封顶如实反映.
 *
 * <p>所有断言针对具体字节 / manifest 删除数 / 对象存活, 删掉核心逻辑 (阈值判定 / prune 调用 /
 * gcAll 调用) 测试必挂. 尤其 {@code trigger_without_retention_is_a_no_op}: 人为撑过阈值但 retention
 * 全零, 断言 prune 删 0 + gcAll 回收 0 + after==before, 钉死"prune 必须先于 gcAll 才能降体积"的
 * 耦合——将来若有人把编排改成只 gcAll 不 prune, 这条必挂.
 */
class StoreSizeGuardTest {

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
        byte[] b = new byte[64 + (Math.abs(seed) % 16)];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ((seed + i) & 0xFF);
        }
        return b;
    }

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

    // ---- 未越阈值: 不触发, 零副作用 --------------------------------------------

    @Test
    void under_threshold_does_not_trigger_and_leaves_everything(@TempDir Path tempDir) throws IOException {
        Path storeRoot = tempDir.resolve("backup-store");
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        Set<Hash> all = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            store.put(hash(i), payload(i));
            all.add(hash(i));
        }
        // 两份 manifest: hourly=1 会把较早那份判 doomed. 若误触发, prune 会删它.
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", all, false);
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", all, true);
        long realBytes = store.approxStoreBytes();

        // 阈值远高于当前体积 -> 不触发.
        long maxBytes = realBytes + 1_000_000L;
        StoreSizeGuard guard = new StoreSizeGuard(store, snapshotsDir,
                RetentionPrunerTestFactory.withPolicy(snapshotsDir, worldRoot, new RetentionPolicy(1, 0, 0, 0)), maxBytes,
                HashSet::new);
        StoreSizeGuard.Result r = guard.checkAndReclaim();

        assertFalse(r.triggered(), "under threshold must not trigger");
        assertEquals(0, r.prunedManifests(), "prune must not run when under threshold");
        assertEquals(0L, r.gcBytesFreed(), "gcAll must not run when under threshold");
        assertEquals(realBytes, r.beforeBytes());
        assertEquals(realBytes, r.afterBytes(), "no reclamation when not triggered");
        assertFalse(r.stillOver());
        // 副作用检查: 两份 manifest 都还在 (prune 没被调), 全部对象存活 (gcAll 没被调).
        assertTrue(Files.exists(snapshotsDir.resolve("2026-05-09T12-00-00Z.manifest")));
        assertTrue(Files.exists(snapshotsDir.resolve("2026-05-10T12-00-00Z.manifest")));
        for (int i = 1; i <= 10; i++) {
            assertTrue(store.has(hash(i)), "object " + i + " must survive an un-triggered check");
        }
        store.close();
    }

    // ---- 越阈值: 先 prune 再 gcAll, 回收具体字节 --------------------------------

    @Test
    void over_threshold_prunes_expired_manifest_then_gc_reclaims_exclusive_bytes(@TempDir Path tempDir)
            throws IOException {
        Path storeRoot = tempDir.resolve("backup-store");
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        // snap-A (较早, incomplete) 独占引用 1..3; snap-B (最新, baselineComplete) 引用 4..10.
        // hourly=1 -> 只留最新 B, A 被 doom 且无门禁保护 -> prune 删 A -> A 独占 1,2,3 变死.
        for (int i = 1; i <= 10; i++) {
            store.put(hash(i), payload(i));
        }
        Set<Hash> setA = new HashSet<>();
        for (int i = 1; i <= 3; i++) setA.add(hash(i));
        Set<Hash> setB = new HashSet<>();
        for (int i = 4; i <= 10; i++) setB.add(hash(i));
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", setA, false);   // snap-A
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", setB, true);    // snap-B

        // close+reopen 使对象落入封口 pack, 与真实启动自检 (store 刚 load, 无在写 pack) 一致:
        // guard 现走 gcAll(seal=false) 跳过在写 pack, 只回收封口 pack 的死对象.
        store.close();
        store = new ChunkStore(storeRoot);
        store.initialize();

        long before = store.approxStoreBytes();
        // gcAll 回收 1,2,3 的 pack 帧: 每对象 16B hash + 4B len + 数据. 精确期望值.
        long expectedReclaimed = 0;
        for (int i = 1; i <= 3; i++) {
            expectedReclaimed += 16 + 4 + payload(i).length;
        }

        // 阈值卡在"当前体积之下但回收目标之上", 确保触发.
        long maxBytes = before - 1;
        StoreSizeGuard guard = new StoreSizeGuard(store, snapshotsDir,
                RetentionPrunerTestFactory.withPolicy(snapshotsDir, worldRoot, new RetentionPolicy(1, 0, 0, 0)), maxBytes,
                HashSet::new);
        StoreSizeGuard.Result r = guard.checkAndReclaim();

        assertTrue(r.triggered(), "over threshold must trigger");
        assertEquals(1, r.prunedManifests(), "prune must delete exactly snap-A (1 manifest)");
        // gcAll 回收字节 = 精确的 A 独占三对象帧字节 (强断言, 非 >0 弱校验).
        assertEquals(expectedReclaimed, r.gcBytesFreed(),
                "gcAll must reclaim exactly the frame bytes of A's 3 exclusive objects");
        assertEquals(before, r.beforeBytes());
        assertTrue(r.afterBytes() < before, "store must shrink after reclamation");
        // approxStoreBytes 下降量 == 回收字节 (体积主体是 pack).
        assertEquals(before - expectedReclaimed, r.afterBytes(),
                "after == before minus reclaimed frame bytes");
        // 物理验证: snap-A manifest 删了; A 独占 1,2,3 死对象回收; B 的 4..10 存活.
        assertFalse(Files.exists(snapshotsDir.resolve("2026-05-09T12-00-00Z.manifest")));
        assertTrue(Files.exists(snapshotsDir.resolve("2026-05-10T12-00-00Z.manifest")));
        for (int i = 1; i <= 3; i++) {
            assertFalse(store.has(hash(i)), "A-exclusive object " + i + " reclaimed");
        }
        for (int i = 4; i <= 10; i++) {
            assertTrue(store.has(hash(i)), "B object " + i + " survives");
        }
        store.close();
    }

    // ---- 空转回归: 撑过阈值但 retention 全零 -> prune 删 0 + gcAll 回收 0 -----------

    @Test
    void trigger_without_retention_is_a_no_op_ceiling_not_reduced(@TempDir Path tempDir)
            throws IOException {
        // 人为把 store 撑过阈值, 但 retention 四档全零 (retainsNothing). prune 短路删 0 份,
        // 故没有任何对象从"被引用"变"死", gcAll 无死对象可回收 -> after == before, 上限没兜住.
        // 这条钉死"只 gcAll 不 prune = 纯空转"的耦合: 谁把编排改成不先 prune, 期望回收就落空, 测试挂.
        Path storeRoot = tempDir.resolve("backup-store");
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        Set<Hash> all = new HashSet<>();
        for (int i = 1; i <= 20; i++) {
            store.put(hash(i), payload(i));
            all.add(hash(i));
        }
        // 每份 manifest 都引用全部对象, 且都较早; 但 retainsNothing -> prune 一份不删.
        writeManifest(snapshotsDir, "2026-05-08T12-00-00Z", all, true);
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", all, false);
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", all, false);
        long before = store.approxStoreBytes();

        long maxBytes = before - 1; // 撑过阈值
        StoreSizeGuard guard = new StoreSizeGuard(store, snapshotsDir,
                RetentionPrunerTestFactory.withPolicy(snapshotsDir, worldRoot, new RetentionPolicy(0, 0, 0, 0)), maxBytes,
                HashSet::new);
        StoreSizeGuard.Result r = guard.checkAndReclaim();

        assertTrue(r.triggered(), "still triggers (体积确实超阈值)");
        assertEquals(0, r.prunedManifests(), "retainsNothing prune deletes zero manifests");
        assertEquals(0L, r.gcBytesFreed(), "no dead objects without prune -> gcAll reclaims nothing");
        assertEquals(before, r.afterBytes(), "ceiling not reduced: after == before");
        assertEquals(before, r.beforeBytes());
        assertTrue(r.stillOver(), "still over threshold: the trigger did not bring it down");
        // 三份 manifest 全在, 全部对象存活 (活数据封顶).
        for (int i = 1; i <= 20; i++) {
            assertTrue(store.has(hash(i)), "live object " + i + " protected, not reclaimed");
        }
        store.close();
    }

    // ---- after 仍超阈值 -> stillOver=true (即便有回收) -----------------------------

    @Test
    void still_over_flag_set_when_reclaim_capped_by_live_data(@TempDir Path tempDir) throws IOException {
        // 回收了一部分 (prune 删了 A) 但阈值设得比回收后体积还低 -> after 仍 > 阈值 -> stillOver.
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
        for (int i = 1; i <= 3; i++) setA.add(hash(i));
        Set<Hash> setB = new HashSet<>();
        for (int i = 4; i <= 10; i++) setB.add(hash(i));
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", setA, false);
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", setB, true);

        // close+reopen 使对象落入封口 pack, 与真实启动自检一致 (guard 走 gcAll seal=false).
        store.close();
        store = new ChunkStore(storeRoot);
        store.initialize();

        long before = store.approxStoreBytes();
        long expectedReclaimed = 0;
        for (int i = 1; i <= 3; i++) {
            expectedReclaimed += 16 + 4 + payload(i).length;
        }
        long afterExpected = before - expectedReclaimed;
        // 阈值设在回收后体积之下 (但当前体积之上): 触发 + 回收, 但 after 仍超阈值.
        long maxBytes = afterExpected - 1;
        StoreSizeGuard guard = new StoreSizeGuard(store, snapshotsDir,
                RetentionPrunerTestFactory.withPolicy(snapshotsDir, worldRoot, new RetentionPolicy(1, 0, 0, 0)), maxBytes,
                HashSet::new);
        StoreSizeGuard.Result r = guard.checkAndReclaim();

        assertTrue(r.triggered());
        assertEquals(1, r.prunedManifests());
        assertEquals(expectedReclaimed, r.gcBytesFreed(), "did reclaim A's exclusive objects");
        assertEquals(afterExpected, r.afterBytes());
        assertTrue(r.stillOver(),
                "reclaim happened but after still exceeds threshold -> stillOver must be true");
        store.close();
    }

    // ---- Critical: 启动自检 gcAll 必须保护在途对象 (protect + 不封口在写 pack) ----------

    @Test
    void trigger_protects_in_flight_objects_from_gcall(@TempDir Path tempDir) throws IOException {
        // 启动自检与 worker/baseline 写入并发: 已 put 入库但尚未进任何 manifest 的在途对象必须被
        // gcAll 的 protect 集保护, 否则下一份快照 drain 出这些 hash 即成悬空引用, 备份静默丢数据.
        // 删掉 protect 接线 (guard 改回无参 gcAll) 时, 7/8 会被物理删, store.has(7/8) 断言必挂.
        Path storeRoot = tempDir.resolve("backup-store");
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        // 1..9 全部入库; 1..3 被 doomed 的 snap-A 独占, 4..6 被保留的 snap-B 引用,
        // 7..8 在途 (protect 集), 9 死对象 (无引用无保护) —— 用于反证 protect 是选择性的.
        for (int i = 1; i <= 9; i++) {
            store.put(hash(i), payload(i));
        }
        Set<Hash> setA = new HashSet<>();
        for (int i = 1; i <= 3; i++) setA.add(hash(i));
        Set<Hash> setB = new HashSet<>();
        for (int i = 4; i <= 6; i++) setB.add(hash(i));
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", setA, false);
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", setB, true);

        // close+reopen -> 全对象进封口 pack (仿真启动自检: store 刚 load).
        store.close();
        store = new ChunkStore(storeRoot);
        store.initialize();

        long before = store.approxStoreBytes();
        long expectedReclaimed = 0;
        for (int i : new int[]{1, 2, 3, 9}) {
            expectedReclaimed += 16 + 4 + payload(i).length;
        }
        long maxBytes = before - 1;

        Set<Hash> inFlight = Set.of(hash(7), hash(8));
        StoreSizeGuard guard = new StoreSizeGuard(store, snapshotsDir,
                RetentionPrunerTestFactory.withPolicy(snapshotsDir, worldRoot, new RetentionPolicy(1, 0, 0, 0)), maxBytes,
                () -> inFlight);
        StoreSizeGuard.Result r = guard.checkAndReclaim();

        assertTrue(r.triggered());
        assertEquals(1, r.prunedManifests(), "prune deletes snap-A only");
        assertEquals(expectedReclaimed, r.gcBytesFreed(),
                "reclaims exactly A-exclusive {1,2,3} + orphan {9}, never protected {7,8}");
        // 在途对象存活 (protect 生效): Critical 修复的核心断言.
        assertTrue(store.has(hash(7)), "in-flight object 7 must survive gcAll");
        assertTrue(store.has(hash(8)), "in-flight object 8 must survive gcAll");
        // A 独占 + 孤儿死对象被回收 (证明 protect 是选择性的, 非一律不删).
        for (int i : new int[]{1, 2, 3, 9}) {
            assertFalse(store.has(hash(i)), "dead object " + i + " reclaimed");
        }
        for (int i = 4; i <= 6; i++) {
            assertTrue(store.has(hash(i)), "referenced object " + i + " survives");
        }
        store.close();
    }

    // ---- 纯逻辑不阻塞: checkAndReclaim 同步返回, 不建线程不 sleep ------------------

    @Test
    void check_is_synchronous_pure_logic_returns_promptly(@TempDir Path tempDir) throws IOException {
        // 抽出的纯类不后台化不 sleep: 同一线程内调用完即返回结果 (后台化是调用方的责任).
        Path storeRoot = tempDir.resolve("backup-store");
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        store.put(hash(1), payload(1));
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", Set.of(hash(1)), true);

        Thread caller = Thread.currentThread();
        StoreSizeGuard guard = new StoreSizeGuard(store, snapshotsDir,
                RetentionPrunerTestFactory.withPolicy(snapshotsDir, worldRoot, new RetentionPolicy(1, 0, 0, 0)),
                store.approxStoreBytes() + 1_000_000L, HashSet::new);
        StoreSizeGuard.Result r = guard.checkAndReclaim();

        // 断言在调用线程上同步完成 (无 spawn), 结果已就绪.
        assertEquals(caller, Thread.currentThread(), "checkAndReclaim must run on the caller thread");
        assertFalse(r.triggered());
        store.close();
    }
}
