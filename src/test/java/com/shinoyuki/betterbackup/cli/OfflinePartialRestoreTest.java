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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单 chunk / 区域 离线部分回退 (PLAN v0.2 主线二)。核心断言是<b>原子边界</b>: 部分回退只把目标
 * chunk 回滚到快照态, 同区域的非目标 chunk、其他区域、playerdata / level.dat 一律逐字节不变,
 * 且不产生 {@code .bak} 目录 (与全量回退"整世界进 .bak"本质不同)。
 *
 * <p>判定标准: 把 {@code rewriteRegionReplacingSlots} 的"读出现有全部 slot 保留"那段删掉,
 * {@code partial_restore_*} 用例里"非目标 chunk 不变"的断言必挂 (非目标 chunk 被抹掉)。
 */
class OfflinePartialRestoreTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void partial_restore_reverts_target_chunk_and_leaves_everything_else_untouched(@TempDir Path root)
            throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);

        // 目标 A=(5,7) 与 邻居 B=(8,9) 同在 r.0.0; C=(40,40) 在另一 region r.1.1。
        int aX = 5;
        int aZ = 7;
        int bX = 8;
        int bZ = 9;
        int cX = 40;
        int cZ = 40;

        // 1. 快照态: A/B/C 各一份"快照字节" 入 store + manifest (baselineComplete=false 也照样能部分回退)
        byte[] snapA = seedSlotBytes(root.resolve("seed-a"), hashFn, store, aX, aZ, 1);
        byte[] snapB = seedSlotBytes(root.resolve("seed-b"), hashFn, store, bX, bZ, 2);
        byte[] snapC = seedSlotBytes(root.resolve("seed-c"), hashFn, store, cX, cZ, 3);

        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPosCodec.asLong(aX, aZ), hashFn.hash(snapA));
        dimChunks.put(ChunkPosCodec.asLong(bX, bZ), hashFn.hash(snapB));
        dimChunks.put(ChunkPosCodec.asLong(cX, cZ), hashFn.hash(snapC));
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-part", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, false, FileManifest.empty());
        manifest.writeTo(snapshotsDir.resolve("snap-part.manifest"));

        // 2. 活动世界 (篡改态): A/B/C 都与快照不同; 外加 playerdata + level.dat
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);
        Path mca00 = regionDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca00)) {
            w.writeChunk(aX & 31, aZ & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 91)));
            w.writeChunk(bX & 31, bZ & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 92)));
        }
        Path mca11 = regionDir.resolve("r.1.1.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca11)) {
            w.writeChunk(cX & 31, cZ & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 93)));
        }
        byte[] liveA = RegionFileSlotReader.readChunk(regionDir, aX, aZ);
        byte[] liveB = RegionFileSlotReader.readChunk(regionDir, bX, bZ);
        byte[] liveC = RegionFileSlotReader.readChunk(regionDir, cX, cZ);

        byte[] playerBytes = "live-player-inventory".getBytes(StandardCharsets.UTF_8);
        Path playerFile = world.resolve("playerdata").resolve("p1.dat");
        Files.createDirectories(playerFile.getParent());
        Files.write(playerFile, playerBytes);
        byte[] levelBytes = "live-level-dat".getBytes(StandardCharsets.UTF_8);
        Files.write(world.resolve("level.dat"), levelBytes);

        // 前置: A/B/C 活动版都与快照版不同 (否则"回滚/不变"断言假阳性)
        assertFalse(Arrays.equals(snapA, liveA), "precondition: A diverged");
        assertFalse(Arrays.equals(snapB, liveB), "precondition: B diverged");
        assertFalse(Arrays.equals(snapC, liveC), "precondition: C diverged");

        // 3. 经 CLI 只回退 A (走 main 分发, 验证 --dim/--chunk 参数面)
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        BackupCli cli = new BackupCli(new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        int code = cli.run(new String[]{
                "restore",
                "--store", storeRoot.toString(),
                "--id", "snap-part",
                "--world", world.toString(),
                "--dim", DIM,
                "--chunk", aX + "," + aZ});
        assertEquals(0, code, "partial restore must exit 0; stderr=" + errBuf);

        // 4. 原子边界断言
        // 4a. 目标 A 回滚到快照态
        assertArrayEquals(snapA, RegionFileSlotReader.readChunk(regionDir, aX, aZ),
                "target chunk A must be reverted to the snapshot bytes");
        // 4b. 同 region 的非目标 B 保持活动态 (虽在快照里, 但未被指定 -> 不动)
        assertArrayEquals(liveB, RegionFileSlotReader.readChunk(regionDir, bX, bZ),
                "neighbour chunk B in the same region must stay at its live value (NOT reverted)");
        // 4c. 另一 region 的 C 完全不动
        assertArrayEquals(liveC, RegionFileSlotReader.readChunk(regionDir, cX, cZ),
                "chunk C in a different region must be untouched");
        // 4d. 玩家数据 / level.dat 不碰
        assertArrayEquals(playerBytes, Files.readAllBytes(playerFile), "playerdata must be untouched");
        assertArrayEquals(levelBytes, Files.readAllBytes(world.resolve("level.dat")), "level.dat must be untouched");
        // 4e. 不产生 .bak (部分回退不整世界搬迁)
        assertFalse(hasBackupDir(root, "world"), "partial restore must NOT create a <world>.bak-* directory");
    }

    @Test
    void area_restore_reverts_all_chunks_in_radius(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);

        // 3x3 区域 center=(10,10) radius=1 -> chunks (9..11, 9..11)
        Map<Long, Hash> dimChunks = new HashMap<>();
        Map<Long, byte[]> snap = new HashMap<>();
        int seed = 0;
        for (int x = 9; x <= 11; x++) {
            for (int z = 9; z <= 11; z++) {
                byte[] s = seedSlotBytes(root.resolve("seed-" + x + "-" + z), hashFn, store, x, z, ++seed);
                snap.put(ChunkPosCodec.asLong(x, z), s);
                dimChunks.put(ChunkPosCodec.asLong(x, z), hashFn.hash(s));
            }
        }
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-area", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty());
        manifest.writeTo(snapshotsDir.resolve("snap-area.manifest"));

        // 活动世界: 9 个 chunk 都篡改 (都落在同一 region r.0.0)
        Path mca = regionDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            int s = 100;
            for (int x = 9; x <= 11; x++) {
                for (int z = 9; z <= 11; z++) {
                    w.writeChunk(x & 31, z & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(5000, ++s)));
                }
            }
        }

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        BackupCli cli = new BackupCli(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        int code = cli.run(new String[]{
                "restore", "--store", storeRoot.toString(), "--id", "snap-area",
                "--world", world.toString(), "--dim", DIM,
                "--center", "10,10", "--radius", "1"});
        assertEquals(0, code, "area restore must exit 0; stderr=" + errBuf);

        for (int x = 9; x <= 11; x++) {
            for (int z = 9; z <= 11; z++) {
                assertArrayEquals(snap.get(ChunkPosCodec.asLong(x, z)), RegionFileSlotReader.readChunk(regionDir, x, z),
                        "chunk (" + x + "," + z + ") in the radius must be reverted to snapshot");
            }
        }
    }

    @Test
    void partial_restore_of_uncaptured_chunk_fails(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);

        // 维度有别的 chunk (9,9) 被采集, 但目标 (5,7) 没有 -> 命中逐 chunk "not captured" 分支
        byte[] otherChunk = seedSlotBytes(root.resolve("seed-other"), hashFn, store, 9, 9, 7);
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPosCodec.asLong(9, 9), hashFn.hash(otherChunk));
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-partialdim", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty());
        manifest.writeTo(snapshotsDir.resolve("snap-partialdim.manifest"));

        OfflineRestore restore = new OfflineRestore(store, paths, snapshotsDir,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        IOException ex = assertThrows(IOException.class, () ->
                restore.restorePartial("snap-partialdim", DIM, Set.of(ChunkPosCodec.asLong(5, 7)), true));
        assertTrue(ex.getMessage().contains("not captured"),
                "must explain the target chunk was not captured; got: " + ex.getMessage());
    }

    @Test
    void area_restore_skips_uncaptured_chunks_and_reverts_the_captured_ones(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);

        // 快照只采集了 3x3 区域里的对角 3 个 chunk; 其余 6 个未采集 (模拟 baseline 未跑完)
        Map<Long, Hash> dimChunks = new HashMap<>();
        Map<Long, byte[]> snap = new HashMap<>();
        int seed = 0;
        for (int x = 9; x <= 11; x++) {
            int z = x; // 对角 (9,9),(10,10),(11,11)
            byte[] s = seedSlotBytes(root.resolve("seed-" + x), hashFn, store, x, z, ++seed);
            snap.put(ChunkPosCodec.asLong(x, z), s);
            dimChunks.put(ChunkPosCodec.asLong(x, z), hashFn.hash(s));
        }
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, "snap-sparse", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, false, FileManifest.empty())
                .writeTo(snapshotsDir.resolve("snap-sparse.manifest"));

        // 活动世界: 全部 9 个 chunk 都有篡改值
        Path mca = regionDir.resolve("r.0.0.mca");
        Map<Long, byte[]> live = new HashMap<>();
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            int s = 100;
            for (int x = 9; x <= 11; x++) {
                for (int z = 9; z <= 11; z++) {
                    w.writeChunk(x & 31, z & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(5000, ++s)));
                }
            }
        }
        for (int x = 9; x <= 11; x++) {
            for (int z = 9; z <= 11; z++) {
                live.put(ChunkPosCodec.asLong(x, z), RegionFileSlotReader.readChunk(regionDir, x, z));
            }
        }

        // 区域回退 center=(10,10) radius=1 -> 覆盖全部 9 个, 但只有对角 3 个被采集
        OfflineRestore restore = new OfflineRestore(store, paths, snapshotsDir,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        java.util.Set<Long> targets = new java.util.HashSet<>();
        for (int x = 9; x <= 11; x++) {
            for (int z = 9; z <= 11; z++) {
                targets.add(ChunkPosCodec.asLong(x, z));
            }
        }
        OfflineRestore.PartialResult r = restore.restorePartial("snap-sparse", DIM, targets, false);
        assertEquals(3, r.chunksRestored(), "only the 3 captured (diagonal) chunks restored");
        assertEquals(6, r.skippedUncaptured(), "the 6 uncaptured chunks skipped, not aborting");

        for (int x = 9; x <= 11; x++) {
            for (int z = 9; z <= 11; z++) {
                long key = ChunkPosCodec.asLong(x, z);
                byte[] got = RegionFileSlotReader.readChunk(regionDir, x, z);
                if (x == z) {
                    assertArrayEquals(snap.get(key), got, "captured diagonal chunk (" + x + "," + z + ") reverted");
                } else {
                    assertArrayEquals(live.get(key), got, "uncaptured chunk (" + x + "," + z + ") kept live value");
                }
            }
        }
    }

    @Test
    void corrupt_inline_neighbour_is_preserved_byte_for_byte_not_dropped(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);

        int aX = 5;
        int aZ = 7;
        int bX = 8;
        int bZ = 9;
        // 目标 A 入 store + manifest
        byte[] snapA = seedSlotBytes(root.resolve("seed-a"), hashFn, store, aX, aZ, 1);
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPosCodec.asLong(aX, aZ), hashFn.hash(snapA));
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, "snap-corrupt", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty())
                .writeTo(snapshotsDir.resolve("snap-corrupt.manifest"));

        // 活动 region: A 篡改 + 邻居 B 是结构合法但 zlib 损坏的 inline slot (首字节=2, 余为乱码, inflate 必失败)
        byte[] corruptB = randomBytes(300, 77);
        corruptB[0] = 2; // compression type zlib, 但后续不是合法 zlib 流
        Path mca = regionDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(aX & 31, aZ & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 99)));
            w.writeChunk(bX & 31, bZ & 31, corruptB);
        }
        // 前置: readSlot (校验) 对损坏 B 抛, readSlotLenient 能读出原字节
        assertArrayEquals(corruptB, RegionFileSlotReader.readSlotLenient(mca, bX & 31, bZ & 31),
                "precondition: lenient read returns the corrupt bytes verbatim");

        OfflineRestore restore = new OfflineRestore(store, paths, snapshotsDir,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        OfflineRestore.PartialResult r = restore.restorePartial(
                "snap-corrupt", DIM, Set.of(ChunkPosCodec.asLong(aX, aZ)), true);
        assertEquals(1, r.chunksRestored());

        // 目标 A 回滚; 损坏邻居 B 逐字节原样保留 (不被丢弃, 不阻断回退)
        assertArrayEquals(snapA, RegionFileSlotReader.readChunk(regionDir, aX, aZ), "target A reverted");
        assertArrayEquals(corruptB, RegionFileSlotReader.readSlotLenient(mca, bX & 31, bZ & 31),
                "corrupt neighbour B preserved byte-for-byte (a broken neighbour must not block precise restore)");
    }

    @Test
    void structurally_broken_external_neighbour_is_skipped_with_warning(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);

        int aX = 5;
        int aZ = 7;
        int bX = 8;
        int bZ = 9;
        byte[] snapA = seedSlotBytes(root.resolve("seed-a"), hashFn, store, aX, aZ, 1);
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPosCodec.asLong(aX, aZ), hashFn.hash(snapA));
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, "snap-brokenext", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty())
                .writeTo(snapshotsDir.resolve("snap-brokenext.manifest"));

        // 活动 region: A + 邻居 B 为 external chunk, 然后删掉 B 的 .mcc (结构损坏, readSlotLenient 也读不出)
        Path mca = regionDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(aX & 31, aZ & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 99)));
            w.writeChunk(bX & 31, bZ & 31, externalObject(ChunkPayloadFixtures.deflate(randomBytes(40000, 5))));
        }
        Path bMcc = RegionFileSlotReader.mccPathFor(mca, bX & 31, bZ & 31);
        assertTrue(Files.deleteIfExists(bMcc), "precondition: B's .mcc existed and is now deleted (broken stub)");

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        OfflineRestore restore = new OfflineRestore(store, paths, snapshotsDir,
                new PrintStream(outBuf, true, StandardCharsets.UTF_8));
        OfflineRestore.PartialResult r = restore.restorePartial(
                "snap-brokenext", DIM, Set.of(ChunkPosCodec.asLong(aX, aZ)), true);

        // 目标 A 仍回滚成功; 损坏的 B 被跳过 (留空) 并告警 —— 不阻断回退
        assertEquals(1, r.chunksRestored(), "broken neighbour must not block the target restore");
        assertArrayEquals(snapA, RegionFileSlotReader.readChunk(regionDir, aX, aZ), "target A reverted");
        assertEquals(null, RegionFileSlotReader.readChunk(regionDir, bX, bZ), "broken B slot left empty (regen)");
        assertTrue(outBuf.toString(StandardCharsets.UTF_8).contains("skipping unreadable neighbour"),
                "must warn about the skipped broken neighbour; out=" + outBuf);
    }

    @Test
    void radius_too_large_is_rejected(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Files.createDirectories(world);
        Files.createDirectories(storeRoot.resolve("snapshots"));
        new ChunkStore(storeRoot).initialize();

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        BackupCli cli = new BackupCli(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        int code = cli.run(new String[]{
                "restore", "--store", storeRoot.toString(), "--id", "x",
                "--world", world.toString(), "--dim", DIM, "--center", "0,0", "--radius", "1000"});
        assertEquals(2, code, "absurd radius must be an argument error (exit 2), not an OOM");
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("too large"),
                "error must explain the radius is too large; stderr=" + errBuf);
    }

    @Test
    void single_call_reverts_chunks_spanning_two_regions(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);

        // 目标跨两个 region: P@(5,7)->r.0.0, Q@(40,40)->r.1.1
        byte[] snapP = seedSlotBytes(root.resolve("seed-p"), hashFn, store, 5, 7, 1);
        byte[] snapQ = seedSlotBytes(root.resolve("seed-q"), hashFn, store, 40, 40, 2);
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPosCodec.asLong(5, 7), hashFn.hash(snapP));
        dimChunks.put(ChunkPosCodec.asLong(40, 40), hashFn.hash(snapQ));
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, "snap-2reg", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty())
                .writeTo(snapshotsDir.resolve("snap-2reg.manifest"));

        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(regionDir.resolve("r.0.0.mca"))) {
            w.writeChunk(5 & 31, 7 & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 91)));
        }
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(regionDir.resolve("r.1.1.mca"))) {
            w.writeChunk(40 & 31, 40 & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 92)));
        }

        OfflineRestore restore = new OfflineRestore(store, paths, snapshotsDir,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        OfflineRestore.PartialResult r = restore.restorePartial(
                "snap-2reg", DIM, Set.of(ChunkPosCodec.asLong(5, 7), ChunkPosCodec.asLong(40, 40)), true);
        assertEquals(2, r.chunksRestored(), "both chunks across two regions restored in one call");
        assertArrayEquals(snapP, RegionFileSlotReader.readChunk(regionDir, 5, 7));
        assertArrayEquals(snapQ, RegionFileSlotReader.readChunk(regionDir, 40, 40));
    }

    @Test
    void negative_coordinate_chunk_restores_to_correct_region(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);

        // 负坐标 (-1,-1) -> region r.-1.-1, slot (31,31)
        int cx = -1;
        int cz = -1;
        byte[] snapN = seedSlotBytes(root.resolve("seed-n"), hashFn, store, cx, cz, 1);
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPosCodec.asLong(cx, cz), hashFn.hash(snapN));
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, "snap-neg", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty())
                .writeTo(snapshotsDir.resolve("snap-neg.manifest"));

        Path negMca = regionDir.resolve("r.-1.-1.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(negMca)) {
            w.writeChunk(cx & 31, cz & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 91)));
        }

        OfflineRestore restore = new OfflineRestore(store, paths, snapshotsDir,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        OfflineRestore.PartialResult r = restore.restorePartial(
                "snap-neg", DIM, Set.of(ChunkPosCodec.asLong(cx, cz)), true);
        assertEquals(1, r.chunksRestored());
        assertTrue(Files.isRegularFile(negMca), "negative-coord chunk must land in r.-1.-1.mca");
        assertArrayEquals(snapN, RegionFileSlotReader.readChunk(regionDir, cx, cz),
                "negative-coordinate chunk must restore byte-exact to its region");
    }

    @Test
    void external_target_round_trips_and_external_neighbour_is_preserved(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        Path regionDir = paths.regionDir(DIM);
        Files.createDirectories(regionDir);

        int aX = 5;
        int aZ = 7;
        int bX = 8;
        int bZ = 9;
        // 目标 A 快照版是 external (超大 chunk); 入 store + manifest
        byte[] snapAexternal = externalObject(ChunkPayloadFixtures.deflate(randomBytes(45000, 1)));
        store.put(hashFn.hash(snapAexternal), snapAexternal);
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPosCodec.asLong(aX, aZ), hashFn.hash(snapAexternal));
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, "snap-ext", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty())
                .writeTo(snapshotsDir.resolve("snap-ext.manifest"));

        // 活动 region: A 为普通 inline (无 .mcc), 邻居 B 为 external (有 .mcc), B 不被回退
        byte[] liveBexternal = externalObject(ChunkPayloadFixtures.deflate(randomBytes(50000, 2)));
        Path mca = regionDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(aX & 31, aZ & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 99)));
            w.writeChunk(bX & 31, bZ & 31, liveBexternal);
        }
        byte[] liveB = RegionFileSlotReader.readChunk(regionDir, bX, bZ);
        assertArrayEquals(liveBexternal, liveB, "precondition: B round-trips as its external object");

        OfflineRestore restore = new OfflineRestore(store, paths, snapshotsDir,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        OfflineRestore.PartialResult r = restore.restorePartial(
                "snap-ext", DIM, Set.of(ChunkPosCodec.asLong(aX, aZ)), true);
        assertEquals(1, r.chunksRestored());

        // 目标 A: inline->external 回退后字节级还原 (stub + 新 .mcc 物化)
        assertArrayEquals(snapAexternal, RegionFileSlotReader.readChunk(regionDir, aX, aZ),
                "external target chunk must round-trip byte-exact through stub + new .mcc");
        assertTrue(Files.isRegularFile(RegionFileSlotReader.mccPathFor(mca, aX & 31, aZ & 31)),
                "target's new .mcc must be materialized");
        // 邻居 B: external chunk 经部分回退原样保留 (stub + .mcc 都在)
        assertArrayEquals(liveBexternal, RegionFileSlotReader.readChunk(regionDir, bX, bZ),
                "external neighbour B must be preserved byte-exact through partial restore");
        assertTrue(Files.isRegularFile(RegionFileSlotReader.mccPathFor(mca, bX & 31, bZ & 31)),
                "neighbour B's .mcc must survive");
    }

    /** external store 对象 = (compType|0x80) + .mcc raw 内容. */
    private static byte[] externalObject(byte[] mccRaw) {
        byte[] obj = new byte[1 + mccRaw.length];
        obj[0] = (byte) (ChunkPayloadFixtures.COMPRESSION_ZLIB | 0x80);
        System.arraycopy(mccRaw, 0, obj, 1, mccRaw.length);
        return obj;
    }

    /** 把 payload 写进临时 region 读回 slot 真实字节, 入 store, 返回该字节. */
    private static byte[] seedSlotBytes(Path seedRoot, Xxh128HashFunction hashFn, ChunkStore store,
                                        int x, int z, int seed) throws IOException {
        Path regionDir = seedRoot.resolve("region");
        Files.createDirectories(regionDir);
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(
                RegionFileSlotReader.mcaPathFor(regionDir, x, z))) {
            w.writeChunk(x & 31, z & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, seed)));
        }
        byte[] slotBytes = RegionFileSlotReader.readChunk(regionDir, x, z);
        store.put(hashFn.hash(slotBytes), slotBytes);
        return slotBytes;
    }

    private static boolean hasBackupDir(Path root, String worldName) throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            return s.anyMatch(p -> Files.isDirectory(p)
                    && p.getFileName().toString().startsWith(worldName + ".bak-"));
        }
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
