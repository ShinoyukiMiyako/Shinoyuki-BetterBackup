package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.PlayerDataCollector;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D commit 10: region (世界) 与 files (玩家数据) 同快照一致性 round-trip,
 * 以及 suspect 标记条目的恢复路径。
 *
 * <p><b>FTB Backups 2 #95 回归防护</b>: 篡改恢复目标的 region 与 playerdata 后 restore,
 * 二者必须同时回到快照态。只回 region 不回 playerdata (或反之) 时, 玩家手里会留着回滚点
 * 之后才拿到的物品 = 刷物品事故。本测试的核心断言: 同一 restore 后两个通道字节都 == 快照。
 *
 * <p>判定标准: 把 RestoreFlow.rebuildFiles 删掉 (或 moveCurrentWorldToBackup 里移
 * playerdata 那几行删掉), {@code region_and_playerdata_*} 用例必挂 (playerdata 仍是篡改值)。
 */
class RestoreFlowPlayerDataTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void region_and_playerdata_restore_to_same_snapshot_state(@TempDir Path root) throws IOException {
        Path worldRoot = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(worldRoot);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(worldRoot);
        Set<Hash> written = ConcurrentHashMap.newKeySet();

        // 1. 建立"快照态": region 写一个 chunk + playerdata 写一个玩家文件
        int chunkX = 5;
        int chunkZ = 7;
        byte[] snapshotChunkObject = ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 1));
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);
        Path mca = regionDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(chunkX & 31, chunkZ & 31, snapshotChunkObject);
        }
        // 读回 region slot 的真实磁盘字节作为快照态基准 (RegionFile header padding 等)
        byte[] snapshotChunkBytes = RegionFileSlotReader.readChunk(regionDir, chunkX, chunkZ);

        byte[] snapshotPlayerBytes = "snapshot-inventory: 1 diamond".getBytes();
        Path playerFile = worldRoot.resolve("playerdata").resolve("p1.dat");
        Files.createDirectories(playerFile.getParent());
        Files.write(playerFile, snapshotPlayerBytes);

        // 2. 同代采集: chunk 入 store + 手工组 chunk 段; files 段用真实 PlayerDataCollector
        Hash chunkHash = hashFn.hash(snapshotChunkBytes);
        store.put(chunkHash, snapshotChunkBytes);
        Map<Long, Hash> chunkSlots = new HashMap<>();
        chunkSlots.put(ChunkPos.asLong(chunkX, chunkZ), chunkHash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, chunkSlots);

        FileManifest files = new PlayerDataCollector(store, paths, hashFn, written).collect();
        assertTrue(files.hashes().containsKey("playerdata/p1.dat"), "player file must be captured");

        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-pd", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, files);
        manifest.writeTo(snapshotsDir.resolve("snap-pd.manifest"));

        // 3. 篡改恢复目标: region slot 写不同字节 + playerdata 改成"刷物品后的状态"
        byte[] tamperedChunkObject = ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 99));
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(chunkX & 31, chunkZ & 31, tamperedChunkObject);
        }
        byte[] tamperedPlayerBytes = "duped-inventory: 64 diamonds".getBytes();
        Files.write(playerFile, tamperedPlayerBytes);
        // 前置确认篡改生效 (否则后面的"回到快照态"断言会假阳性)
        assertFalse(java.util.Arrays.equals(snapshotChunkBytes,
                RegionFileSlotReader.readChunk(regionDir, chunkX, chunkZ)), "chunk must be tampered");
        assertFalse(java.util.Arrays.equals(snapshotPlayerBytes, Files.readAllBytes(playerFile)),
                "player file must be tampered");

        // 4. restore
        RestoreFlow flow = new RestoreFlow(store, paths, snapshotsDir);
        RestoreFlow.RestoreResult result = flow.restore("snap-pd");
        assertEquals(1, result.chunkSlotsRestored());
        assertEquals(1, result.playerDataFilesRestored());

        // 5. 核心断言: region 与 playerdata 必须同时回到快照态
        assertArrayEquals(snapshotChunkBytes, RegionFileSlotReader.readChunk(regionDir, chunkX, chunkZ),
                "region chunk must be back to snapshot state");
        assertArrayEquals(snapshotPlayerBytes, Files.readAllBytes(playerFile),
                "playerdata must be back to snapshot state (FTB2 #95: no duped items survive rollback)");
    }

    @Test
    void suspect_file_is_restored_from_store(@TempDir Path root) throws IOException {
        Path worldRoot = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(worldRoot);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(worldRoot);

        // 直接构造一个 suspect 文件入库 + manifest 标 suspect, 验证 restore 仍照常回写
        byte[] suspectBytes = "unstable-during-capture".getBytes();
        Hash suspectHash = hashFn.hash(suspectBytes);
        store.put(suspectHash, suspectBytes);

        Map<String, Hash> hashes = new HashMap<>();
        hashes.put("playerdata/suspect.dat", suspectHash);
        Set<String> suspect = new java.util.HashSet<>();
        suspect.add("playerdata/suspect.dat");
        FileManifest files = new FileManifest(hashes, suspect);

        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-suspect", System.currentTimeMillis(), 0L,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, files);
        manifest.writeTo(snapshotsDir.resolve("snap-suspect.manifest"));

        RestoreFlow flow = new RestoreFlow(store, paths, snapshotsDir);
        RestoreFlow.RestoreResult result = flow.restore("snap-suspect");

        assertEquals(1, result.playerDataFilesRestored(),
                "suspect file must still be restored (data preserved, not dropped)");
        Path restored = worldRoot.resolve("playerdata").resolve("suspect.dat");
        assertTrue(Files.isRegularFile(restored), "suspect file must materialize on disk");
        assertArrayEquals(suspectBytes, Files.readAllBytes(restored),
                "restored suspect file must be byte-exact the stored snapshot bytes");
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
