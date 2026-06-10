package com.shinoyuki.betterbackup.baseline;

import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * baseline 扫描 + 快照提交的事务性全链路测试 (P0 崩溃窗口修复). 用真实 BaselineScanner +
 * SnapshotCreator + ChunkStore + BaselineProgress, 验证:
 * <ol>
 *   <li>崩溃窗口: 扫完 (scanned 已持久化) 后崩 (不创建快照), 全新对象图重启续传必须重扫,
 *       之后创建快照 manifest 必须含全部 chunk</li>
 *   <li>优雅路径: 扫完 -> 快照晋升 committed -> 重启续传跳过 -> complete 标记存在且
 *       后续快照 baselineComplete=true</li>
 *   <li>晋升时序: 快照只晋升 drain 前已 scanned 的 region; drain 后才扫完的留给下次</li>
 *   <li>旧格式迁移: 旧 3 列行读入为 scanned, 重扫后正常晋升</li>
 *   <li>GC 并发安全: 扫描线程与快照线程并发, 最终 manifest 引用的 hash 不被增量 GC 误删</li>
 * </ol>
 */
class BaselineCommitTest {

    private static final String DIM = "minecraft:overworld";

    // ---- world / store fixture helpers ----

    private static Map<Long, byte[]> writeRegion(Path worldRoot, String channel, int rx, int rz,
                                                 Map<Integer, byte[]> slotPlaintext) throws IOException {
        Path dir = worldRoot.resolve(channel);
        Files.createDirectories(dir);
        Path mca = dir.resolve("r." + rx + "." + rz + ".mca");
        Map<Long, byte[]> expectedByPacked = new HashMap<>();
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            for (Map.Entry<Integer, byte[]> e : slotPlaintext.entrySet()) {
                int slot = e.getKey();
                int localX = slot & 31;
                int localZ = (slot >> 5) & 31;
                byte[] payload = ChunkPayloadFixtures.zlibPayload(e.getValue());
                writer.writeChunk(localX, localZ, payload);
                long packed = ChunkPos.asLong((rx << 5) + localX, (rz << 5) + localZ);
                expectedByPacked.put(packed, payload);
            }
        }
        return expectedByPacked;
    }

    private static SnapshotManifest latestManifest(Path snapshotsDir) throws IOException {
        try (Stream<Path> s = Files.list(snapshotsDir)) {
            List<Path> manifests = s
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            assertFalse(manifests.isEmpty(), "expected at least one manifest");
            return SnapshotManifest.readFrom(manifests.get(manifests.size() - 1));
        }
    }

    private static int chunkCount(SnapshotManifest m) {
        return m.chunks().values().stream().mapToInt(Map::size).sum();
    }

    /** 一组贯穿一次"进程生命周期"的协作对象 (store/state/written 共享同一 storeRoot). */
    private static final class Rig {
        final ChunkStore store;
        final CurrentSnapshotState state;
        final Set<Hash> written;
        final BaselineProgress progress;
        final SnapshotCreator creator;
        final AtomicBoolean scanFinished;
        final BaselineScanner scanner;

        Rig(Path storeRoot, Path worldRoot) throws IOException {
            this.store = new ChunkStore(storeRoot);
            store.initialize();
            this.state = new CurrentSnapshotState();
            this.written = ConcurrentHashMap.newKeySet();
            this.progress = new BaselineProgress(storeRoot);
            progress.load();
            WorldPaths paths = new WorldPaths(worldRoot);
            HashFunction hf = new Xxh128HashFunction();
            this.scanFinished = new AtomicBoolean(false);
            this.creator = new SnapshotCreator(store, state, paths, hf, storeRoot,
                    () -> 0L, written, new BetterBackupMetrics(), progress, scanFinished::get);
            Runnable onScanFinished = () -> {
                scanFinished.set(true);
                creator.create("baseline-complete");
            };
            this.scanner = new BaselineScanner(store, state, paths, hf, written, progress,
                    BaselineScanner.RateLimiter.NONE, onScanFinished);
        }
    }

    // ---- test 1: crash window ----

    @Test
    void crash_after_scanned_before_snapshot_is_healed_by_rescan_on_restart(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Map<Long, byte[]> expected = writeRegion(worldRoot, "region", 0, 0,
                Map.of(0, "alpha".getBytes(), 1, "beta".getBytes(), 33, "gamma".getBytes()));

        // 进程 1: 扫描把 region 记成 scanned 并登记进 state, 但模拟在创建快照之前就崩 ——
        // 用 NO_FINISH 回调跑扫描 (不触发快照), 然后丢弃整个对象图.
        {
            ChunkStore store = new ChunkStore(storeRoot);
            store.initialize();
            CurrentSnapshotState state = new CurrentSnapshotState();
            Set<Hash> written = ConcurrentHashMap.newKeySet();
            BaselineProgress progress = new BaselineProgress(storeRoot);
            progress.load();
            WorldPaths paths = new WorldPaths(worldRoot);
            BaselineScanner scanner = new BaselineScanner(store, state, paths, new Xxh128HashFunction(),
                    written, progress, BaselineScanner.RateLimiter.NONE, () -> { });
            scanner.scan();
            // region 已 scanned 持久化, 但没有 committed, 没有 complete 标记, 没有 manifest.
            assertEquals(1, progress.completedRegionCount());
            assertEquals(0, progress.committedRegionCount());
            assertFalse(progress.isComplete());
        }
        Path snapshotsDir = storeRoot.resolve("snapshots");
        assertFalse(Files.isDirectory(snapshotsDir) && hasManifest(snapshotsDir),
                "no snapshot was created before the simulated crash");

        // 进程 2 (重启): 全新对象图. 续传必须重扫 scanned-未提交的 region 把登记重建进
        // 全新空 state, scan 收尾回调创建的快照把它们 drain 进 manifest -> manifest 必须含
        // 全部 3 个 chunk. 若续传错误地把 scanned 当 committed 跳过, 全新 state 仍为空, 提交
        // 快照的 manifest 会是 0 chunk, 下面的 chunkCount==3 断言挂 (核心逻辑保护).
        Rig rig = new Rig(storeRoot, worldRoot);
        rig.scanner.scan();
        assertTrue(rig.progress.isComplete(), "rescan + commit snapshot completes the baseline");

        SnapshotManifest m = latestManifest(rig.creator.snapshotsDir());
        assertEquals(3, chunkCount(m), "manifest must contain every chunk after restart rescan + snapshot");
        Map<Long, Hash> dimChunks = m.chunks().get(DIM);
        assertNotNull(dimChunks, "overworld chunks present in manifest");
        HashFunction hf = new Xxh128HashFunction();
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            Hash expectedHash = hf.hash(e.getValue());
            assertEquals(expectedHash, dimChunks.get(e.getKey()),
                    "manifest must reference the correct stored hash for packed=" + e.getKey());
            assertTrue(rig.store.has(expectedHash), "store must still hold the referenced bytes");
        }
    }

    // ---- test 2: graceful path ----

    @Test
    void graceful_path_promotes_commits_then_resume_skips_and_completes(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "a".getBytes(), 1, "b".getBytes()));

        // 进程 1: 扫完 -> 收尾回调触发快照 -> 晋升 committed + 写 complete 标记.
        Rig rig1 = new Rig(storeRoot, worldRoot);
        rig1.scanner.scan();

        assertEquals(1, rig1.progress.committedRegionCount(), "scan + commit snapshot promotes the region");
        assertTrue(rig1.progress.isComplete(), "all committed + scan finished => complete marker written");

        // 完成本基线的那份快照在 build 时标记尚未写, 故其 baselineComplete=false; 取一份新
        // 快照才盖 true (这正是 restore 门禁的保守语义: 等扫完再取新快照恢复).
        rig1.creator.create("fresh-after-complete");
        assertTrue(latestManifest(rig1.creator.snapshotsDir()).baselineComplete(),
                "a fresh snapshot after completion is stamped baselineComplete=true");

        // 进程 2 (重启): complete 标记存在 -> 扫描直接跳过, 不重扫.
        Rig rig2 = new Rig(storeRoot, worldRoot);
        BaselineScanner.Result r2 = rig2.scanner.scan();
        assertEquals(0, r2.stored(), "completed baseline must not rescan on restart");
        assertEquals(0, rig2.state.chunkCount(), "no chunk re-registered when baseline already complete");
        assertTrue(r2.complete());
    }

    // ---- test 3: promotion timing (drain-before capture) ----

    @Test
    void snapshot_only_promotes_regions_scanned_before_its_drain(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "a".getBytes()));
        writeRegion(worldRoot, "region", 1, 0, Map.of(0, "b".getBytes()));

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        progress.load();
        WorldPaths paths = new WorldPaths(worldRoot);
        HashFunction hf = new Xxh128HashFunction();
        SnapshotCreator creator = new SnapshotCreator(store, state, paths, hf, storeRoot,
                () -> 0L, written, new BetterBackupMetrics(), progress, () -> false);

        // 手动登记 region A 的 chunk + 标 scanned (模拟扫描线程扫完 A), 然后取快照.
        byte[] aPayload = ChunkPayloadFixtures.zlibPayload("a".getBytes());
        Hash aHash = hf.hash(aPayload);
        store.put(aHash, aPayload);
        state.putChunk(DIM, ChunkPos.asLong(0, 0), aHash);
        progress.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");

        creator.create("snapshot-1");

        // 快照 1 在 drain 前捕获到 A 已 scanned -> A 晋升 committed.
        assertTrue(progress.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"),
                "region scanned before the snapshot's drain is promoted to committed");

        // 现在 (快照 1 之后) 才扫完 region B: 标 scanned + 登记. B 没赶上快照 1 的捕获集.
        byte[] bPayload = ChunkPayloadFixtures.zlibPayload("b".getBytes());
        Hash bHash = hf.hash(bPayload);
        store.put(bHash, bPayload);
        state.putChunk(DIM, ChunkPos.asLong(32, 0), bHash);
        progress.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.1.0.mca");

        // B 必须仍是 scanned-未提交 (没被快照 1 晋升).
        assertFalse(progress.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.1.0.mca"),
                "a region scanned only AFTER snapshot-1's drain must NOT be promoted by snapshot-1");

        // 下一次快照才把 B 晋升; 且其登记进 manifest 2 (overlay 继承 A).
        creator.create("snapshot-2");
        assertTrue(progress.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.1.0.mca"),
                "the next snapshot promotes the later-scanned region");
        SnapshotManifest m2 = latestManifest(creator.snapshotsDir());
        assertEquals(aHash, m2.chunks().get(DIM).get(ChunkPos.asLong(0, 0)), "manifest 2 inherits A (overlay)");
        assertEquals(bHash, m2.chunks().get(DIM).get(ChunkPos.asLong(32, 0)), "manifest 2 adds B");
    }

    // ---- test 4: legacy format migration ----

    @Test
    void legacy_progress_file_is_rescanned_and_promoted(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Map<Long, byte[]> expected = writeRegion(worldRoot, "region", 0, 0,
                Map.of(0, "old".getBytes(), 1, "world".getBytes()));

        // 预置旧格式 progress (3 列, 无 status). 升级后必须按 scanned 读入 -> 重扫 -> 晋升.
        Path baselineDir = storeRoot.resolve("baseline");
        Files.createDirectories(baselineDir);
        String legacyLine = BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.0.0.mca"
                + System.lineSeparator();
        Files.write(baselineDir.resolve("progress"), legacyLine.getBytes());

        Rig rig = new Rig(storeRoot, worldRoot);
        // 升级读入: 该 region 是 scanned (未提交), 不是 committed.
        assertFalse(rig.progress.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"));

        rig.scanner.scan();

        // 旧 region 被重扫 (因为只是 scanned) -> 登记进 state -> 收尾快照晋升 committed + complete.
        assertTrue(rig.progress.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"),
                "rescanned legacy region is promoted to committed by the commit snapshot");
        assertTrue(rig.progress.isComplete(), "baseline completes after the legacy region is committed");

        SnapshotManifest m = latestManifest(rig.creator.snapshotsDir());
        // manifest 含全部 2 个 chunk 即证明旧 region 确实被重扫 (而非按 committed 跳过).
        assertEquals(2, chunkCount(m), "every legacy chunk made it into the manifest (window healed)");
        HashFunction hf = new Xxh128HashFunction();
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            assertEquals(hf.hash(e.getValue()), m.chunks().get(DIM).get(e.getKey()));
        }
    }

    // ---- test 5: GC concurrency audit (incremental GC must not delete a referenced hash) ----

    @Test
    void concurrent_scan_and_snapshots_never_leave_a_referenced_hash_gced(@TempDir Path base) throws Exception {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        // 多个 region, 每个塞满若干 slot, 让扫描持续往 writtenThisWindow + state 写入,
        // 与快照线程的 drain + 增量 GC 制造交错.
        for (int rx = 0; rx < 6; rx++) {
            Map<Integer, byte[]> slots = new HashMap<>();
            for (int slot = 0; slot < 24; slot++) {
                slots.put(slot, ("r" + rx + "-s" + slot).getBytes());
            }
            writeRegion(worldRoot, "region", rx, 0, slots);
        }

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        progress.load();
        WorldPaths paths = new WorldPaths(worldRoot);
        HashFunction hf = new Xxh128HashFunction();
        SnapshotCreator creator = new SnapshotCreator(store, state, paths, hf, storeRoot,
                () -> 0L, written, new BetterBackupMetrics(), progress, () -> false);

        AtomicReference<Throwable> scanError = new AtomicReference<>();
        AtomicBoolean scanDone = new AtomicBoolean(false);
        BaselineScanner scanner = new BaselineScanner(store, state, paths, hf, written, progress,
                BaselineScanner.RateLimiter.NONE, () -> { });
        Thread scanThread = new Thread(() -> {
            try {
                scanner.scan();
            } catch (Throwable t) {
                scanError.set(t);
            } finally {
                scanDone.set(true);
            }
        }, "test-baseline-scan");
        scanThread.start();

        // 扫描进行期间高频取快照, 每次都跑增量 GC. 误删 race 若存在, 某次 GC 会删掉一个
        // 紧接着被下一份 manifest 引用的 hash.
        List<SnapshotManifest> manifests = new ArrayList<>();
        while (!scanDone.get()) {
            creator.create("interval");
            manifests.add(latestManifest(creator.snapshotsDir()));
        }
        scanThread.join(30_000);
        assertFalse(scanThread.isAlive(), "scan thread must finish");
        if (scanError.get() != null) {
            throw new AssertionError("baseline scan threw", scanError.get());
        }
        // 收尾再取一份, 把扫描线程最后登记的 chunk 全部纳入.
        creator.create("final");
        manifests.add(latestManifest(creator.snapshotsDir()));

        // 不变式: 任意一份产出的 manifest 引用的每个 hash, 其 store 字节此刻必须仍在 ——
        // 增量 GC 不得删掉任何"被某份已写盘 manifest 引用"的 hash. 删掉 runIncrementalGc 的
        // pending 排除, 并发 race 会让某 manifest 引用一个已被 GC 删除的 hash, 此断言挂.
        Set<Hash> allReferenced = new HashSet<>();
        for (SnapshotManifest m : manifests) {
            m.chunks().values().forEach(map -> allReferenced.addAll(map.values()));
            m.entityChunks().values().forEach(map -> allReferenced.addAll(map.values()));
            allReferenced.addAll(m.savedData().values());
            if (m.levelDat() != null) {
                allReferenced.add(m.levelDat());
            }
        }
        assertFalse(allReferenced.isEmpty(), "snapshots must reference some chunk hashes");
        for (Hash h : allReferenced) {
            assertTrue(store.has(h),
                    "referenced hash " + h.toHex() + " was deleted by incremental GC (dangling reference)");
        }

        // 最终一份 manifest 必须覆盖磁盘上全部 chunk (6 region x 24 slot = 144).
        SnapshotManifest finalManifest = manifests.get(manifests.size() - 1);
        assertEquals(144, chunkCount(finalManifest), "final manifest must cover every scanned chunk");
    }

    private static boolean hasManifest(Path snapshotsDir) throws IOException {
        try (Stream<Path> s = Files.list(snapshotsDir)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".manifest"));
        }
    }
}
