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
 * BaselineScanner 全量扫描测试. 不打桩 store / reader / WorldPaths, 用真实 .mca 文件
 * (RegionFileSlotWriter + 真实 zlib payload), 断言具体业务结果:
 * <ul>
 *   <li>每个磁盘上的 chunk slot 都按字节入 store, 并登记进 CurrentSnapshotState</li>
 *   <li>断点续传: 已记录完成的 region 不重复入库 (用 store.put 计数核对)</li>
 *   <li>并发跳过: 已在 CurrentSnapshotState 的 chunk (模拟活跃 dirty 路径) baseline 跳过</li>
 *   <li>撕裂读: 坏 zlib 的 slot 不入库, 该 region 多轮重扫后兜底标完成</li>
 *   <li>完成后写 complete 标记</li>
 * </ul>
 *
 * <p>判定标准: 删掉 scanRegionFile 的入库逻辑或跳过判定, 对应断言必挂.
 */
class BaselineScannerTest {

    private static final int SECTOR_BYTES = 4096;
    private static final int LENGTH_HEADER_BYTES = 4;

    private BaselineScanner newScanner(Path storeRoot, Path worldRoot,
                                       CurrentSnapshotState state, Set<Hash> written,
                                       BaselineProgress progress) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        HashFunction hashFunction = new Xxh128HashFunction();
        return new BaselineScanner(store, state, paths, hashFunction, written, progress,
                BaselineScanner.RateLimiter.NONE);
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
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress);

        BaselineScanner.Result result = scanner.scan();

        assertTrue(result.complete());
        assertEquals(3, result.stored(), "all 3 occupied slots stored");
        // 每个 chunk 都登记进 state, 且 store 里按 hash 取回的字节 == 写入的 raw payload
        ChunkStore readBack = new ChunkStore(storeRoot);
        HashFunction hf = new Xxh128HashFunction();
        assertEquals(3, state.chunkCount(), "every scanned chunk registered in CurrentSnapshotState");
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            assertTrue(state.containsChunk("minecraft:overworld", e.getKey()),
                    "state must contain chunk packed=" + e.getKey());
            Hash h = hf.hash(e.getValue());
            assertTrue(readBack.has(h), "store must contain hash for chunk packed=" + e.getKey());
            assertArrayEquals(e.getValue(), readBack.get(h), "stored bytes must equal raw slot payload");
        }
        assertTrue(progress.isComplete(), "complete marker written after full sweep");
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
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress);

        BaselineScanner.Result result = scanner.scan();

        assertEquals(2, state.chunkCount(), "region channel chunks registered");
        assertEquals(1, state.entityChunkCount(), "entities channel chunks registered");
        assertEquals(3, result.stored());
    }

    @Test
    void resume_does_not_rescan_completed_regions(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        // 两个 region 文件, 各两个 chunk
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "a".getBytes(), 1, "b".getBytes()));
        writeRegion(worldRoot, "region", 1, 0, Map.of(0, "c".getBytes(), 1, "d".getBytes()));

        // 模拟"上次扫描已完成 r.0.0.mca 后中断": 预置进度记录该 region 完成, 但不写 complete
        BaselineProgress pre = new BaselineProgress(storeRoot);
        pre.load();
        pre.markRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca");

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress);

        BaselineScanner.Result result = scanner.scan();

        // 续传: 只扫 r.1.0.mca 的两个 chunk, r.0.0 整文件跳过不重新读取入库.
        // 若忽略 isRegionDone, r.0.0 的 chunk 会被重新登记进 state, chunkCount 变 4.
        assertEquals(2, result.stored());
        assertEquals(2, state.chunkCount(), "only the resumed region's chunks registered this run");
        // r.1.0.mca 的 chunk 是 (32,0) 与 (33,0)
        assertTrue(state.containsChunk("minecraft:overworld", ChunkPos.asLong(32, 0)));
        assertTrue(state.containsChunk("minecraft:overworld", ChunkPos.asLong(33, 0)));
        assertFalse(state.containsChunk("minecraft:overworld", ChunkPos.asLong(0, 0)),
                "already-done region's chunk must not be re-registered");
        assertTrue(progress.isComplete());
    }

    @Test
    void already_dirty_chunk_is_skipped_by_baseline(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "disk-bytes".getBytes(), 1, "other".getBytes()));

        CurrentSnapshotState state = new CurrentSnapshotState();
        // 活跃 dirty 路径已采过 chunk (0,0), 登记了一个跟磁盘不同的 hash (模拟更新的字节)
        Hash dirtyHash = new Hash(new byte[]{(byte) 0xAB, 1, 2, 3});
        state.putChunk("minecraft:overworld", ChunkPos.asLong(0, 0), dirtyHash);

        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress);

        BaselineScanner.Result result = scanner.scan();

        // (0,0) 被跳过: 只入库 (1,0), 且 (0,0) 在 state 里仍是 dirty 路径的 hash 不被覆盖
        assertEquals(1, result.stored(), "dirty chunk must be skipped, only the other chunk stored");
        assertEquals(1, result.skippedDirty());
        CurrentSnapshotState.Drained drained = state.drainAndClear();
        assertEquals(dirtyHash,
                drained.chunks().get(new com.shinoyuki.betterbackup.snapshot.DimChunkKey(
                        "minecraft:overworld", ChunkPos.asLong(0, 0))),
                "baseline must not overwrite the active dirty path's newer hash");
    }

    @Test
    void torn_read_slot_not_stored_region_still_completes(@TempDir Path base) throws IOException {
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
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress);

        BaselineScanner.Result result = scanner.scan();

        // 撕裂的 slot 不入库 (垃圾不入), 只有 good chunk 进 store + state
        assertEquals(1, result.stored(), "only the non-torn chunk is stored, garbage rejected");
        assertEquals(1, state.chunkCount());
        long goodPacked = ChunkPos.asLong(0, 0);
        assertTrue(state.containsChunk("minecraft:overworld", goodPacked));
        assertFalse(state.containsChunk("minecraft:overworld", ChunkPos.asLong(1, 0)),
                "corrupted chunk must not be registered");
        // 多轮重扫后兜底: 仍标 complete (撕裂 chunk 留给活跃 dirty 路径), 不永久阻塞 baseline
        assertTrue(result.complete(), "scan must finish even with a persistently torn slot");
        assertTrue(progress.isComplete());
    }

    @Test
    void already_complete_scan_is_a_noop(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "x".getBytes()));

        BaselineProgress pre = new BaselineProgress(storeRoot);
        pre.load();
        pre.markComplete();

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BaselineProgress progress = new BaselineProgress(storeRoot);
        BaselineScanner scanner = newScanner(storeRoot, worldRoot, state, written, progress);

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
