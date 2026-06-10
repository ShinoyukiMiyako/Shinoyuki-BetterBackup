package com.shinoyuki.betterbackup.baseline;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BaselineScanner 全量扫描 *机制* 测试 (入库 / 登记 state / 续传跳过 committed / 撕裂读 /
 * dirty 跳过). 不打桩 store / reader / WorldPaths, 用真实 .mca 文件 + 真实 zlib payload.
 *
 * <p>这些用例只验证"扫描行为", 不验证"提交 / 写 complete 标记" (那是事务性提交语义, 由
 * {@link BaselineCommitTest} 用 scanner + SnapshotCreator 全链路覆盖). 故 onScanFinished
 * 注入 no-op, scan() 不触发快照 drain, 扫描后 state 仍可被本测试直接断言.
 *
 * <p>判定标准: 删掉 scanRegionFile 的入库逻辑或"续传只跳过 committed"判定, 对应断言必挂。
 */
class BaselineScannerTest {

    private static final int SECTOR_BYTES = 4096;
    private static final int LENGTH_HEADER_BYTES = 4;
    private static final String DIM = "minecraft:overworld";

    /** no-op 收尾回调: 本测试只看扫描机制, 不让 scan() 触发快照把 state drain 掉. */
    private static final Runnable NO_FINISH = () -> { };

    private BaselineScanner newScanner(Path storeRoot, Path worldRoot,
                                       CurrentSnapshotState state, Set<Hash> written,
                                       BaselineProgress progress, Runnable onScanFinished) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        HashFunction hashFunction = new Xxh128HashFunction();
        return new BaselineScanner(store, state, paths, hashFunction, written, progress,
                BaselineScanner.RateLimiter.NONE, onScanFinished);
    }

    /** 在 dim 的 region/ 写一个 region 文件, slot (localX,localZ) -> 明文. 返回写入的 raw payload. */
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

    @Test
    void full_scan_stores_every_chunk_and_registers_in_state(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Map<Integer, byte[]> slots = Map.of(
                0, "chunk-0-0".getBytes(),
                33, "chunk-1-1".getBytes(),
                1023, "chunk-31-31".getBytes());
        Map<Long, byte[]> expected = writeRegion(worldRoot, "region", 0, 0, slots);

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress, NO_FINISH);

        BaselineScanner.Result result = scanner.scan();

        assertEquals(3, result.stored(), "all 3 occupied slots stored");
        // 每个 chunk 都登记进 state, 且 store 里按 hash 取回的字节 == 写入的 raw payload
        ChunkStore readBack = new ChunkStore(storeRoot);
        HashFunction hf = new Xxh128HashFunction();
        assertEquals(3, state.chunkCount(), "every scanned chunk registered in CurrentSnapshotState");
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            assertTrue(state.containsChunk(DIM, e.getKey()),
                    "state must contain chunk packed=" + e.getKey());
            Hash h = hf.hash(e.getValue());
            assertTrue(readBack.has(h), "store must contain hash for chunk packed=" + e.getKey());
            assertArrayEquals(e.getValue(), readBack.get(h), "stored bytes must equal raw slot payload");
        }
        // 扫完只记 scanned (待提交), 不立即 committed -- 提交要等快照 drain 进 manifest.
        assertEquals(0, progress.committedRegionCount(), "region only scanned, not yet committed");
        assertFalse(progress.isComplete(), "no complete marker without a committing snapshot");
    }

    @Test
    void region_and_entities_channels_both_scanned(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "r".getBytes(), 1, "r2".getBytes()));
        writeRegion(worldRoot, "entities", 0, 0, Map.of(5, "e".getBytes()));

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress, NO_FINISH);

        BaselineScanner.Result result = scanner.scan();

        assertEquals(2, state.chunkCount(), "region channel chunks registered");
        assertEquals(1, state.entityChunkCount(), "entities channel chunks registered");
        assertEquals(3, result.stored());
    }

    @Test
    void resume_rescans_scanned_but_skips_committed_regions(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        // 两个 region 文件, 各两个 chunk
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "a".getBytes(), 1, "b".getBytes()));
        writeRegion(worldRoot, "region", 1, 0, Map.of(0, "c".getBytes(), 1, "d".getBytes()));

        // 模拟"上次扫描已 COMMITTED r.0.0.mca 后中断": 预置 scanned 再晋升 committed.
        // 只有 committed 的 region 续传才跳过; 若 r.0.0 只是 scanned-未提交, 它会被重扫.
        BaselineProgress pre = new BaselineProgress(storeRoot);
        pre.load();
        pre.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");
        pre.promoteScannedToCommitted(Set.of(BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.0.0.mca"));

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress, NO_FINISH);

        BaselineScanner.Result result = scanner.scan();

        // 续传: 只扫 r.1.0.mca 的两个 chunk, r.0.0 (committed) 整文件跳过不重新读取入库.
        // 若把跳过判定改成 isRegionScanned 或忽略 committed 区分, r.0.0 的 chunk 会被重新
        // 登记进 state, chunkCount 变 4.
        assertEquals(2, result.stored());
        assertEquals(2, state.chunkCount(), "only the resumed region's chunks registered this run");
        // r.1.0.mca 的 chunk 是 (32,0) 与 (33,0)
        assertTrue(state.containsChunk(DIM, ChunkPos.asLong(32, 0)));
        assertTrue(state.containsChunk(DIM, ChunkPos.asLong(33, 0)));
        assertFalse(state.containsChunk(DIM, ChunkPos.asLong(0, 0)),
                "already-committed region's chunk must not be re-registered");
    }

    @Test
    void scanned_but_uncommitted_region_is_rescanned_on_resume(@TempDir Path base) throws IOException {
        // 崩溃窗口的核心治愈路径 (单元粒度): 上次只把 region 记成 scanned (未提交) 就崩了,
        // 续传 (全新 state) 必须重扫它, 把登记重新放回 state, 否则该 region 的 chunk 永远丢.
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "x".getBytes(), 1, "y".getBytes()));

        BaselineProgress pre = new BaselineProgress(storeRoot);
        pre.load();
        pre.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"); // 只 scanned, 没晋升

        CurrentSnapshotState freshState = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, freshState, written, progress, NO_FINISH);

        BaselineScanner.Result result = scanner.scan();

        assertEquals(2, result.stored(), "scanned-but-uncommitted region must be rescanned, not skipped");
        assertEquals(2, freshState.chunkCount(), "rescan re-registers the lost chunks into fresh state");
        assertTrue(freshState.containsChunk(DIM, ChunkPos.asLong(0, 0)));
        assertTrue(freshState.containsChunk(DIM, ChunkPos.asLong(1, 0)));
    }

    @Test
    void requestStop_halts_at_slot_boundary_without_marking_partial_region(@TempDir Path base) throws IOException {
        // 关服路径: 扫描中段被 requestStop, 必须 (a) 不调收尾回调 (防与关服快照竞争),
        // (b) 半扫 region 不标 scanned (否则晋升 committed 后缺的 slot 永不重扫),
        // (c) 登记停在 slot 边界, (d) 下次启动续传把两个 region 完整扫回.
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(
                0, "a".getBytes(), 1, "b".getBytes(), 2, "c".getBytes(), 3, "d".getBytes()));
        writeRegion(worldRoot, "region", 1, 0, Map.of(0, "e".getBytes()));

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);

        java.util.concurrent.atomic.AtomicBoolean finishCalled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger acquires = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<BaselineScanner> self = new java.util.concurrent.atomic.AtomicReference<>();
        // 限速钩子在第 2 个 chunk 处理中发停止请求, 模拟关服打断扫描中段
        BaselineScanner.RateLimiter stopper = () -> {
            if (acquires.incrementAndGet() == 2) {
                self.get().requestStop();
            }
        };
        BaselineScanner scanner = new BaselineScanner(store, state, paths, new Xxh128HashFunction(),
                written, progress, stopper, () -> finishCalled.set(true));
        self.set(scanner);

        BaselineScanner.Result result = scanner.scan();

        assertFalse(result.complete(), "stopped scan must not report complete");
        assertFalse(finishCalled.get(), "stopped scan must not fire the baseline-complete callback");
        assertEquals(2, state.chunkCount(),
                "registration must halt at the slot boundary right after the stop request");
        assertTrue(progress.snapshotScannedKeys().isEmpty(),
                "interrupted region must not be marked scanned");
        assertEquals(0, progress.committedRegionCount());

        // 续传 (模拟下次启动): 全新 state + 重新加载 progress, 两个 region 必须完整扫回
        CurrentSnapshotState resumedState = new CurrentSnapshotState();
        BaselineProgress reloaded = new BaselineProgress(storeRoot);
        BaselineScanner resumed = newScanner(storeRoot, worldRoot, resumedState,
                ConcurrentHashMap.newKeySet(), reloaded, NO_FINISH);
        BaselineScanner.Result resumeResult = resumed.scan();

        assertEquals(3, resumeResult.stored(), "resume stores the 3 chunks missed by the stopped run");
        assertEquals(2, resumeResult.deduped(), "the 2 chunks ingested before the stop dedup");
        assertEquals(5, resumedState.chunkCount(), "all 5 chunks registered after resume");
    }

    @Test
    void already_dirty_chunk_is_skipped_by_baseline(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "disk-bytes".getBytes(), 1, "other".getBytes()));

        CurrentSnapshotState state = new CurrentSnapshotState();
        // 活跃 dirty 路径已采过 chunk (0,0), 登记了一个跟磁盘不同的 hash (模拟更新的字节)
        Hash dirtyHash = new Hash(new byte[]{(byte) 0xAB, 1, 2, 3});
        state.putChunk(DIM, ChunkPos.asLong(0, 0), dirtyHash);

        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress, NO_FINISH);

        BaselineScanner.Result result = scanner.scan();

        // (0,0) 被跳过: 只入库 (1,0), 且 (0,0) 在 state 里仍是 dirty 路径的 hash 不被覆盖
        assertEquals(1, result.stored(), "dirty chunk must be skipped, only the other chunk stored");
        assertEquals(1, result.skippedDirty());
        CurrentSnapshotState.Drained drained = state.drainAndClear();
        assertEquals(dirtyHash,
                drained.chunks().get(new com.shinoyuki.betterbackup.snapshot.DimChunkKey(
                        DIM, ChunkPos.asLong(0, 0))),
                "baseline must not overwrite the active dirty path's newer hash");
    }

    @Test
    void torn_read_slot_not_stored_region_still_reaches_scanned(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        // 两个 chunk: 一个正常, 一个写完后破坏 zlib 尾部制造撕裂读. 损坏的 chunk 用较大
        // 明文 (16000 byte), 让破坏末 8 字节命中 Adler-32 校验区抛 ZipException ->
        // TornReadException (小 payload 破坏会提前 EOF, 走 RegionFileTornReadTest 同手法).
        byte[] goodPlain = "good-chunk".getBytes();
        byte[] tornPlain = new byte[16000];
        new java.util.Random(11).nextBytes(tornPlain);
        Map<Integer, byte[]> slots = new HashMap<>();
        slots.put(0, goodPlain);
        slots.put(1, tornPlain);
        writeRegion(worldRoot, "region", 0, 0, slots);
        Path mca = worldRoot.resolve("region").resolve("r.0.0.mca");
        byte[] corruptedPayload = ChunkPayloadFixtures.zlibPayload(tornPlain);
        corruptCompressedTail(mca, 1, 0, corruptedPayload.length);

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress, NO_FINISH);

        BaselineScanner.Result result = scanner.scan();

        // 撕裂的 slot 不入库 (垃圾不入), 只有 good chunk 进 store + state
        assertEquals(1, result.stored(), "only the non-torn chunk is stored, garbage rejected");
        assertEquals(1, state.chunkCount());
        long goodPacked = ChunkPos.asLong(0, 0);
        assertTrue(state.containsChunk(DIM, goodPacked));
        assertFalse(state.containsChunk(DIM, ChunkPos.asLong(1, 0)),
                "corrupted chunk must not be registered");
        // 多轮重扫后兜底: 持续撕裂的 region 仍在最后一轮被记为 scanned (留给活跃 dirty 路径),
        // 不永久阻塞 baseline; 但它只是 scanned-待提交, 不会越过提交直接 committed.
        assertEquals(1, progress.completedRegionCount(), "persistently torn region still reaches scanned");
        assertEquals(0, progress.committedRegionCount());
    }

    @Test
    void already_complete_scan_is_a_noop(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "x".getBytes()));

        BaselineProgress pre = new BaselineProgress(storeRoot);
        pre.load();
        // 无 region 时 markCompleteIfAllCommitted(true) 写下标记 (空集全 committed 成立)
        assertTrue(pre.markCompleteIfAllCommitted(true));

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress, NO_FINISH);

        BaselineScanner.Result result = scanner.scan();

        assertEquals(0, result.stored(), "already-complete baseline must not rescan anything");
        assertEquals(0, state.chunkCount(), "no chunk registered when scan is skipped");
        assertTrue(result.complete());
    }

    // ---- helpers ----

    /**
     * 破坏指定 slot 的 zlib 压缩流尾部 8 字节 (XOR 0xFF), 不动 location entry / length.
     * inflate 到末段 Adler-32 校验失败抛, RegionFileSlotReader 据此判撕裂读.
     * 复刻 RegionFileTornReadTest 的注入手法.
     */
    private static void corruptCompressedTail(Path mca, int localX, int localZ, int payloadLen) throws IOException {
        int headerOffset = 4 * (localX + localZ * 32);
        int locationEntry;
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(4);
            ch.read(buf, headerOffset);
            buf.flip();
            locationEntry = buf.getInt();
        }
        int sectorOffset = locationEntry >>> 8;
        long payloadStart = (long) sectorOffset * SECTOR_BYTES + LENGTH_HEADER_BYTES;
        long corruptAt = payloadStart + payloadLen - 8;
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(8);
            ch.read(buf, corruptAt);
            buf.flip();
            byte[] b = new byte[8];
            buf.get(b);
            for (int i = 0; i < b.length; i++) {
                b[i] ^= (byte) 0xFF;
            }
            ch.write(ByteBuffer.wrap(b), corruptAt);
        }
    }
}
