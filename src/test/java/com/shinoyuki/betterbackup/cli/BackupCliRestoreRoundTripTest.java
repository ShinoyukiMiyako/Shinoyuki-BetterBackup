package com.shinoyuki.betterbackup.cli;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN Phase E commit 14: 把一个小型备份目录拷到干净环境, 经 CLI restore 后逐 slot 字节比对。
 *
 * <p>这是离线 CLI 的端到端验收: 在不启动 Minecraft 的前提下, 仅凭 store + manifest 把世界
 * region/playerdata/level.dat 字节级还原。判定标准: 把 {@link OfflineRestore#rebuildChunkPath}
 * (或 BackupCli 的 restore 分发) 删掉, {@code restore_*} 用例必挂 (恢复目标仍是篡改值)。
 */
class BackupCliRestoreRoundTripTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void cli_restore_rebuilds_region_playerdata_leveldat_byte_exact(@TempDir Path root) throws IOException {
        // 1. 在 source world 建立"快照态": 一个 chunk + 一个 playerdata 文件 + level.dat
        Path sourceWorld = root.resolve("source-world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(sourceWorld);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths sourcePaths = new WorldPaths(sourceWorld);

        int chunkX = 5;
        int chunkZ = 7;
        byte[] chunkObject = ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 11));
        Path sourceRegionDir = sourcePaths.regionDir(DIM);
        Files.createDirectories(sourceRegionDir);
        Path sourceMca = sourceRegionDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(sourceMca)) {
            w.writeChunk(chunkX & 31, chunkZ & 31, chunkObject);
        }
        // 读回真实磁盘字节 (含 RegionFile 4-byte length header + padding) 作为快照基准
        byte[] snapshotChunkBytes = RegionFileSlotReader.readChunk(sourceRegionDir, chunkX, chunkZ);

        byte[] snapshotPlayerBytes = "inventory: 1 diamond".getBytes(StandardCharsets.UTF_8);
        byte[] snapshotLevelDat = randomBytes(2048, 22);

        // 2. 入 store + 组 manifest (baselineComplete=true 才放行 restore)
        Hash chunkHash = hashFn.hash(snapshotChunkBytes);
        store.put(chunkHash, snapshotChunkBytes);
        Hash playerHash = hashFn.hash(snapshotPlayerBytes);
        store.put(playerHash, snapshotPlayerBytes);
        Hash levelHash = hashFn.hash(snapshotLevelDat);
        store.put(levelHash, snapshotLevelDat);

        Map<Long, Hash> chunkSlots = new HashMap<>();
        chunkSlots.put(ChunkPosCodec.asLong(chunkX, chunkZ), chunkHash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, chunkSlots);

        Map<String, Hash> fileHashes = new HashMap<>();
        fileHashes.put("playerdata/p1.dat", playerHash);
        FileManifest files = new FileManifest(fileHashes, new java.util.HashSet<>());

        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-cli", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), levelHash, 0L, 0L, true, files);
        manifest.writeTo(snapshotsDir.resolve("snap-cli.manifest"));

        // 3. 干净恢复目标: 一个空 target world (模拟"服务端起不来, 世界目录待重建")
        Path targetWorld = root.resolve("target-world");
        Files.createDirectories(targetWorld);
        // 放一个篡改的 chunk + player 文件, 确认 restore 真的覆盖而非碰巧相等
        WorldPaths targetPaths = new WorldPaths(targetWorld);
        Files.createDirectories(targetPaths.regionDir(DIM));
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(
                targetPaths.regionDir(DIM).resolve("r.0.0.mca"))) {
            w.writeChunk(chunkX & 31, chunkZ & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 99)));
        }
        byte[] tamperedChunk = RegionFileSlotReader.readChunk(targetPaths.regionDir(DIM), chunkX, chunkZ);
        assertFalse(java.util.Arrays.equals(snapshotChunkBytes, tamperedChunk),
                "precondition: target chunk must differ before restore");

        // 4. 经 CLI 入口 restore (走 main 分发, 不直接 new OfflineRestore)
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        BackupCli cli = new BackupCli(new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        int code = cli.run(new String[]{
                "restore",
                "--store", storeRoot.toString(),
                "--id", "snap-cli",
                "--world", targetWorld.toString()});
        assertEquals(0, code, "CLI restore must exit 0; stderr=" + errBuf);

        // 5. 逐 slot 字节比对: 恢复出的 region slot 必须 == 快照基准字节
        byte[] restoredChunk = RegionFileSlotReader.readChunk(targetPaths.regionDir(DIM), chunkX, chunkZ);
        assertArrayEquals(snapshotChunkBytes, restoredChunk,
                "restored region chunk slot must be byte-exact the snapshot");

        // playerdata 与 level.dat 同样字节级还原
        assertArrayEquals(snapshotPlayerBytes,
                Files.readAllBytes(targetWorld.resolve("playerdata").resolve("p1.dat")),
                "restored playerdata must be byte-exact");
        assertArrayEquals(snapshotLevelDat,
                Files.readAllBytes(targetWorld.resolve("level.dat")),
                "restored level.dat must be byte-exact");
    }

    @Test
    void cli_restore_refuses_snapshot_with_incomplete_baseline(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        new ChunkStore(storeRoot).initialize();

        // baselineComplete=false 的 manifest (空也行, 关键是 baseline 标志)
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-incomplete", System.currentTimeMillis(), 0L,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), null, 0L, 0L, false, FileManifest.empty());
        manifest.writeTo(snapshotsDir.resolve("snap-incomplete.manifest"));

        Path targetWorld = root.resolve("target-world");
        Files.createDirectories(targetWorld);

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        BackupCli cli = new BackupCli(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        int code = cli.run(new String[]{
                "restore",
                "--store", storeRoot.toString(),
                "--id", "snap-incomplete",
                "--world", targetWorld.toString()});

        assertEquals(1, code, "restore of incomplete-baseline snapshot must fail");
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("baselineComplete"),
                "error must explain the baseline gate; stderr=" + errBuf);
        // 关键安全断言: 拒绝时不得动恢复目标 (target world 仍为空, 没被 move 到 .bak)
        assertTrue(Files.list(targetWorld).findAny().isEmpty(),
                "refused restore must leave target world untouched (no .bak move)");
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
