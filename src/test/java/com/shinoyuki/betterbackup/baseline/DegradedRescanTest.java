package com.shinoyuki.betterbackup.baseline;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.DimChunkKey;
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
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DegradedRescan 降级窗口补采 (PLAN Phase F, 重启路径). 模拟: 上次完整快照后, 降级窗口内
 * vanilla 同步写盘的 chunk 没经 BAS listener 进过快照 (mtime 晚于 cutoff)。重扫应把这些
 * chunk 按字节入 store 并登记进 CurrentSnapshotState, 由下一次快照 drain 纳入; 而 mtime
 * 早于 cutoff 的 region (上次完整快照已覆盖) 不重扫。
 *
 * <p>不打桩, 用真实 .mca 文件 (RegionFileSlotWriter + 真实 zlib payload) + 真实 store。
 * 判定标准: 删掉 rescanRegionFile 入库/登记逻辑, "窗口 chunk 进下一快照"用例必挂;
 * 删掉 mtime cutoff 过滤, "旧 region 不重扫"用例必挂。
 */
class DegradedRescanTest {

    private static final String OVERWORLD = "minecraft:overworld";

    private DegradedRescan newRescan(Path storeRoot, Path worldRoot, CurrentSnapshotState state,
                                     Set<Hash> written) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        HashFunction hashFunction = new Xxh128HashFunction();
        return new DegradedRescan(store, state, paths, hashFunction, written);
    }

    /** 在 worldRoot/region 写 r.rx.rz.mca, slot->明文, 返回 packedPos->raw zlib payload. */
    private static Map<Long, byte[]> writeRegion(Path worldRoot, String channel, int rx, int rz,
                                                 Map<Integer, byte[]> slotPlaintext) throws IOException {
        Path dir = worldRoot.resolve(channel);
        Files.createDirectories(dir);
        Path mca = dir.resolve("r." + rx + "." + rz + ".mca");
        java.util.HashMap<Long, byte[]> expected = new java.util.HashMap<>();
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            for (Map.Entry<Integer, byte[]> e : slotPlaintext.entrySet()) {
                int slot = e.getKey();
                int localX = slot & 31;
                int localZ = (slot >> 5) & 31;
                byte[] payload = ChunkPayloadFixtures.zlibPayload(e.getValue());
                writer.writeChunk(localX, localZ, payload);
                long packed = ChunkPos.asLong((rx << 5) + localX, (rz << 5) + localZ);
                expected.put(packed, payload);
            }
        }
        return expected;
    }

    private static void setMtime(Path worldRoot, String channel, int rx, int rz, long millis)
            throws IOException {
        Path mca = worldRoot.resolve(channel).resolve("r." + rx + "." + rz + ".mca");
        Files.setLastModifiedTime(mca, FileTime.fromMillis(millis));
    }

    @Test
    void rescan_backfills_window_chunk_into_state_for_next_snapshot(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");

        long cutoff = 1_000_000L;
        // 降级窗口内变更的 region (mtime 晚于 cutoff): 含一个 BAS 没 fire 过的 chunk.
        Map<Long, byte[]> windowChunks = writeRegion(worldRoot, "region", 0, 0,
                Map.of(0, "window-chunk".getBytes()));
        setMtime(worldRoot, "region", 0, 0, cutoff + 5_000L);

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        DegradedRescan rescan = newRescan(storeRoot, worldRoot, state, written);

        DegradedRescan.Result result = rescan.rescan(cutoff);

        assertEquals(1, result.recovered(), "降级窗口内的 chunk 应被补采入库");
        assertEquals(1, result.regionsScanned());

        long packed = ChunkPos.asLong(0, 0);
        assertTrue(state.containsChunk(OVERWORLD, packed),
                "补采的 chunk 必须登记进 CurrentSnapshotState 等下次快照 drain");

        // 模拟下次快照: drain state, 窗口 chunk 在内, 且 store 按 hash 取回的字节 == 原 raw payload
        ChunkStore readBack = new ChunkStore(storeRoot);
        HashFunction hf = new Xxh128HashFunction();
        CurrentSnapshotState.Drained drained = state.drainAndClear();
        Hash h = drained.chunks().get(new DimChunkKey(OVERWORLD, packed));
        assertEquals(hf.hash(windowChunks.get(packed)), h, "drain 出的 hash == 窗口 chunk 字节的 hash");
        assertArrayEquals(windowChunks.get(packed), readBack.get(h),
                "store 内补采字节必须逐字节等于磁盘 region slot raw payload");
    }

    @Test
    void region_older_than_cutoff_is_not_rescanned(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");

        long cutoff = 2_000_000L;
        // 这个 region 在上次完整快照前就稳定了 (mtime 早于 cutoff): 不该被重扫.
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "old-chunk".getBytes()));
        setMtime(worldRoot, "region", 0, 0, cutoff - 5_000L);

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        DegradedRescan rescan = newRescan(storeRoot, worldRoot, state, written);

        DegradedRescan.Result result = rescan.rescan(cutoff);

        assertEquals(0, result.recovered(), "mtime 早于 cutoff 的 region 不补采");
        assertEquals(0, result.regionsScanned(), "旧 region 不进重扫");
        assertFalse(state.containsChunk(OVERWORLD, ChunkPos.asLong(0, 0)),
                "上次完整快照已覆盖的 chunk 不该被重扫重新登记");
    }

    @Test
    void only_newer_region_scanned_when_mixed(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");

        long cutoff = 3_000_000L;
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "old".getBytes()));
        setMtime(worldRoot, "region", 0, 0, cutoff - 1_000L);
        writeRegion(worldRoot, "region", 1, 0, Map.of(0, "new".getBytes()));
        setMtime(worldRoot, "region", 1, 0, cutoff + 1_000L);

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        DegradedRescan rescan = newRescan(storeRoot, worldRoot, state, written);

        DegradedRescan.Result result = rescan.rescan(cutoff);

        assertEquals(1, result.recovered(), "只补采 mtime 晚于 cutoff 的那个 region");
        assertEquals(1, result.regionsScanned());
        // r.1.0.mca 的 (32,0) 进 state, r.0.0.mca 的 (0,0) 不进
        assertTrue(state.containsChunk(OVERWORLD, ChunkPos.asLong(32, 0)));
        assertFalse(state.containsChunk(OVERWORLD, ChunkPos.asLong(0, 0)));
    }

    @Test
    void active_path_chunk_not_overwritten_by_rescan(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");

        long cutoff = 4_000_000L;
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "disk-bytes".getBytes()));
        setMtime(worldRoot, "region", 0, 0, cutoff + 1_000L);

        CurrentSnapshotState state = new CurrentSnapshotState();
        // 重启后的新进程活跃 dirty 路径已采过这个 chunk, 登记了更新的 hash.
        Hash activeHash = new Hash(new byte[]{(byte) 0xCD, 9, 8, 7});
        state.putChunk(OVERWORLD, ChunkPos.asLong(0, 0), activeHash);

        Set<Hash> written = ConcurrentHashMap.newKeySet();
        DegradedRescan rescan = newRescan(storeRoot, worldRoot, state, written);

        DegradedRescan.Result result = rescan.rescan(cutoff);

        assertEquals(0, result.recovered(), "活跃路径已采的 chunk 重扫跳过, 不入库旧字节");
        assertEquals(1, result.skippedActive());
        CurrentSnapshotState.Drained drained = state.drainAndClear();
        assertEquals(activeHash, drained.chunks().get(new DimChunkKey(OVERWORLD, ChunkPos.asLong(0, 0))),
                "重扫不得用磁盘旧字节覆盖活跃路径的较新 hash");
    }

    @Test
    void cutoff_zero_rescans_everything(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");

        // cutoff=0 (取不到完整快照): 退化为全量重扫, 所有 region 都过.
        writeRegion(worldRoot, "region", 0, 0, Map.of(0, "a".getBytes(), 1, "b".getBytes()));
        setMtime(worldRoot, "region", 0, 0, 1L);

        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        DegradedRescan rescan = newRescan(storeRoot, worldRoot, state, written);

        DegradedRescan.Result result = rescan.rescan(0L);

        assertEquals(2, result.recovered(), "cutoff=0 保守全量重扫, 所有 chunk 补采");
        assertEquals(2, state.chunkCount());
    }
}
