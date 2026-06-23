package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.baseline.BaselineProgress;
import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import com.shinoyuki.betterbackup.worker.BackupContext;
import com.shinoyuki.betterbackup.worker.BackupTask;
import com.shinoyuki.betterbackup.worker.ChunkBackupTask;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端"究竟能不能回退"验收: 不手搓 manifest, 而是用真实 worker ({@link ChunkBackupTask})
 * 采集 + 真实 {@link SnapshotCreator} 产出 manifest, 再经 {@link RestoreFlow} 恢复, 逐字节比对。
 *
 * <p>两个核心场景:
 * <ol>
 *   <li>全链路单快照往返: 证明生产路径产出的 manifest 自洽可恢复 (而非测试构造的特例)。</li>
 *   <li><b>多快照历史回退</b>: 建 S1(V1) -> 改 -> S2(V2), 恢复 <b>旧份 S1</b> 必须拿回 V1
 *       而非最新 V2。这是"回退"的本义, 同时钉死"旧份 unique 对象在 S2 增量 GC 后存活"
 *       这一前提 (overlay 物化 x 增量 GC 不误删旧份 的三方交互)。</li>
 * </ol>
 *
 * <p>判定标准: 把 {@link RestoreFlow} 的 region 重建删掉, 或让增量 GC 误删旧份对象, 用例必挂。
 */
class FullPipelineRestoreTest {

    private static final String DIM = "minecraft:overworld";

    private static final DateTimeFormatter SNAPSHOT_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    @Test
    void full_pipeline_capture_snapshot_restore_round_trip(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Files.createDirectories(world);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BetterBackupMetrics metrics = new BetterBackupMetrics();

        BaselineProgress progress = new BaselineProgress(storeRoot);
        progress.load();
        progress.markCompleteIfAllCommitted(true); // baselineComplete=true (生产 baseline 跑完后的态)

        // 1. 建立快照态: region 一个 chunk + playerdata 一个文件 + level.dat (均为原始字节)
        int cx = 5;
        int cz = 7;
        Files.createDirectories(paths.regionDir(DIM));
        Path mca = paths.regionDir(DIM).resolve("r.0.0.mca");
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(cx & 31, cz & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 1)));
        }
        byte[] snapshotChunkBytes = RegionFileSlotReader.readChunk(paths.regionDir(DIM), cx, cz);

        byte[] playerBytes = "inventory: 1 diamond".getBytes(StandardCharsets.UTF_8);
        Path playerFile = world.resolve("playerdata").resolve("p1.dat");
        Files.createDirectories(playerFile.getParent());
        Files.write(playerFile, playerBytes);

        byte[] levelDat = randomBytes(2048, 2);
        Files.write(paths.levelDat(), levelDat);

        // 2. 真实 worker 采集 chunk (读 .mca slot -> hash -> store.put -> state)
        captureChunk(store, state, paths, hashFn, written, metrics, DIM, cx, cz);

        // 3. 真实 SnapshotCreator 产出 manifest (内部主动读 level.dat + 跑 PlayerDataCollector)
        SnapshotCreator creator = new SnapshotCreator(store, state, paths, hashFn, storeRoot,
                () -> 0L, written, metrics, progress, () -> true);
        creator.create("auto");
        String snapId = onlySnapshotId(creator.snapshotsDir());

        // 4. 篡改世界三个通道, restore 必须主动覆盖回快照态 (否则后面断言假阳性)
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(cx & 31, cz & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 99)));
        }
        Files.write(playerFile, "duped: 64 diamonds".getBytes(StandardCharsets.UTF_8));
        Files.write(paths.levelDat(), randomBytes(2048, 77));
        assertFalse(Arrays.equals(snapshotChunkBytes, RegionFileSlotReader.readChunk(paths.regionDir(DIM), cx, cz)),
                "precondition: chunk must be tampered before restore");

        // 5. 恢复 + 逐字节比对三通道
        RestoreFlow.RestoreResult result = new RestoreFlow(store, paths, creator.snapshotsDir()).restore(snapId);
        assertEquals(1, result.chunkSlotsRestored());
        assertEquals(1, result.playerDataFilesRestored());
        assertTrue(result.levelDatRestored());

        assertArrayEquals(snapshotChunkBytes, RegionFileSlotReader.readChunk(paths.regionDir(DIM), cx, cz),
                "pipeline-produced manifest must restore the chunk slot byte-exact");
        assertArrayEquals(playerBytes, Files.readAllBytes(playerFile),
                "playerdata must restore byte-exact");
        assertArrayEquals(levelDat, Files.readAllBytes(paths.levelDat()),
                "level.dat must restore byte-exact");
    }

    @Test
    void rollback_to_older_snapshot_recovers_earlier_state(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Files.createDirectories(world);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);
        CurrentSnapshotState state = new CurrentSnapshotState();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BetterBackupMetrics metrics = new BetterBackupMetrics();

        BaselineProgress progress = new BaselineProgress(storeRoot);
        progress.load();
        progress.markCompleteIfAllCommitted(true);

        SnapshotCreator creator = new SnapshotCreator(store, state, paths, hashFn, storeRoot,
                () -> 0L, written, metrics, progress, () -> true);

        int cx = 5;
        int cz = 7;
        Files.createDirectories(paths.regionDir(DIM));
        Path mca = paths.regionDir(DIM).resolve("r.0.0.mca");

        // S1: chunk = V1
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(cx & 31, cz & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 1)));
        }
        byte[] v1 = RegionFileSlotReader.readChunk(paths.regionDir(DIM), cx, cz);
        Hash v1Hash = hashFn.hash(v1);
        captureChunk(store, state, paths, hashFn, written, metrics, DIM, cx, cz);
        creator.create("s1");
        String s1Id = onlySnapshotId(creator.snapshotsDir());

        awaitNextSnapshotSecond(s1Id); // 让 S2 的 id (秒级) 严格大于 S1, 不撞文件名 / 保证 overlay 取 S1 为基

        // S2: 同一 slot 改成 V2
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(mca)) {
            w.writeChunk(cx & 31, cz & 31, ChunkPayloadFixtures.zlibPayload(randomBytes(6000, 2)));
        }
        byte[] v2 = RegionFileSlotReader.readChunk(paths.regionDir(DIM), cx, cz);
        Hash v2Hash = hashFn.hash(v2);
        captureChunk(store, state, paths, hashFn, written, metrics, DIM, cx, cz);
        creator.create("s2");

        List<String> ids = allSnapshotIds(creator.snapshotsDir());
        assertEquals(2, ids.size(), "expected two distinct snapshots; ids=" + ids);
        assertNotEquals(v1Hash, v2Hash, "V1 and V2 payloads must differ");

        // 回退前提: 旧份 S1 的 unique 对象必须挺过 S2 的增量 GC
        assertTrue(store.has(v1Hash), "older snapshot's unique object must survive S2 incremental gc");
        assertTrue(store.has(v2Hash), "latest snapshot's object must be present");

        // 回退到旧份 S1 -> 必须拿回 V1, 而不是最新 V2
        Path target1 = root.resolve("restore-s1");
        Files.createDirectories(target1);
        WorldPaths p1 = new WorldPaths(target1);
        new RestoreFlow(store, p1, creator.snapshotsDir()).restore(s1Id);
        byte[] restoredS1 = RegionFileSlotReader.readChunk(p1.regionDir(DIM), cx, cz);
        assertArrayEquals(v1, restoredS1,
                "restoring the OLDER snapshot must reconstruct V1 (rollback in time)");
        assertFalse(Arrays.equals(v2, restoredS1),
                "rolled-back world must NOT contain the later V2");

        // 最新份 S2 -> V2 (latest 也成立, 排除"只能恢复某一份"的退化)
        String s2Id = ids.stream().max(String::compareTo).orElseThrow();
        Path target2 = root.resolve("restore-s2");
        Files.createDirectories(target2);
        WorldPaths p2 = new WorldPaths(target2);
        new RestoreFlow(store, p2, creator.snapshotsDir()).restore(s2Id);
        assertArrayEquals(v2, RegionFileSlotReader.readChunk(p2.regionDir(DIM), cx, cz),
                "restoring the latest snapshot must reconstruct V2");
    }

    /** 真实 worker 采集一个 chunk slot (与生产 ChunkBackupTask 同路径). 返回入库字节的 hash. */
    private static Hash captureChunk(ChunkStore store, CurrentSnapshotState state, WorldPaths paths,
                                     Xxh128HashFunction hashFn, Set<Hash> written, BetterBackupMetrics metrics,
                                     String dim, int cx, int cz) throws IOException {
        BlockingQueue<BackupTask> retry = new LinkedBlockingQueue<>();
        BackupContext ctx = new BackupContext(store, state, paths, hashFn, written, metrics, retry);
        new ChunkBackupTask(dim, ChunkPos.asLong(cx, cz)).execute(ctx);
        return hashFn.hash(RegionFileSlotReader.readChunk(paths.regionDir(dim), cx, cz));
    }

    private static String onlySnapshotId(Path snapshotsDir) throws IOException {
        List<String> ids = allSnapshotIds(snapshotsDir);
        assertEquals(1, ids.size(), "exactly one snapshot expected; got " + ids);
        return ids.get(0);
    }

    private static List<String> allSnapshotIds(Path snapshotsDir) throws IOException {
        try (Stream<Path> s = Files.list(snapshotsDir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".manifest"))
                    .map(n -> n.substring(0, n.length() - ".manifest".length()))
                    .sorted()
                    .toList();
        }
    }

    /** 自旋等到墙钟秒翻过 afterId (秒级), 保证下一次 create() 生成的 id 严格更大. 上限 ~1s. */
    private static void awaitNextSnapshotSecond(String afterId) {
        while (SNAPSHOT_ID_FORMAT.format(Instant.now()).compareTo(afterId) <= 0) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for snapshot-second roll", e);
            }
        }
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
