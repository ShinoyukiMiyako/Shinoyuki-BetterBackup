package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import com.shinoyuki.betterbackup.worker.BackupContext;
import com.shinoyuki.betterbackup.worker.BackupTask;
import com.shinoyuki.betterbackup.worker.SavedDataBackupTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1: SavedData 必须带维度往返 —— 采集端按文件实际所在维度记 worldRoot 相对路径 key, restore
 * 端据此落回原维度的 data/, 而非一律塞进 overworld data/。例如 {@code raids_end.dat} 采自末地
 * DIM1, 恢复后必须回到 {@code DIM1/data/} 让 End 的 RaidManager 读得到。
 *
 * <p>判定标准:
 * <ul>
 *   <li>把 SavedDataBackupTask 的 key 改回裸名 -> collection 用例的 DIM1 key 断言必挂;</li>
 *   <li>把 rebuildSavedData 改回硬编 overworld dataDir -> restore 用例的 DIM1/data 落位断言必挂。</li>
 * </ul>
 */
class SavedDataDimensionRoundTripTest {

    @Test
    void collection_records_worldroot_relative_key_per_dimension(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Files.createDirectories(world);

        byte[] sbBytes = "overworld scoreboard".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("data"));
        Files.write(world.resolve("data").resolve("scoreboard.dat"), sbBytes);
        byte[] endBytes = "raids in the end".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("DIM1").resolve("data"));
        Files.write(world.resolve("DIM1").resolve("data").resolve("raids_end.dat"), endBytes);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        CurrentSnapshotState state = new CurrentSnapshotState();
        BlockingQueue<BackupTask> queue = new LinkedBlockingQueue<>();
        BackupContext ctx = new BackupContext(store, state, new WorldPaths(world), hashFn,
                ConcurrentHashMap.newKeySet(), new BetterBackupMetrics(), queue);

        new SavedDataBackupTask("scoreboard").execute(ctx);
        new SavedDataBackupTask("raids_end").execute(ctx);

        CurrentSnapshotState.Drained drained = state.drainAndClear();
        assertEquals(2, drained.savedData().size(), "both SavedData entries collected");
        assertEquals(hashFn.hash(sbBytes), drained.savedData().get("data/scoreboard.dat"),
                "overworld SavedData must be keyed by its worldRoot-relative path data/scoreboard.dat");
        assertEquals(hashFn.hash(endBytes), drained.savedData().get("DIM1/data/raids_end.dat"),
                "end SavedData must record DIM1/data/raids_end.dat (its real dimension), not a bare name");
    }

    @Test
    void restore_places_savedData_in_recorded_dimension_and_falls_back_for_legacy_keys(@TempDir Path root)
            throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        byte[] sbBytes = "overworld scoreboard".getBytes(StandardCharsets.UTF_8);
        byte[] endBytes = "end raids".getBytes(StandardCharsets.UTF_8);
        byte[] legacyBytes = "legacy bare-name raids".getBytes(StandardCharsets.UTF_8);
        Hash sbHash = hashFn.hash(sbBytes);
        Hash endHash = hashFn.hash(endBytes);
        Hash legacyHash = hashFn.hash(legacyBytes);
        store.put(sbHash, sbBytes);
        store.put(endHash, endBytes);
        store.put(legacyHash, legacyBytes);

        Map<String, Hash> savedData = new HashMap<>();
        savedData.put("data/scoreboard.dat", sbHash);       // 新版 overworld 相对路径 key
        savedData.put("DIM1/data/raids_end.dat", endHash);  // 新版 end 相对路径 key
        savedData.put("raids", legacyHash);                 // 旧 manifest 的裸 SavedData 名
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-sd", System.currentTimeMillis(), 0L,
                new HashMap<>(), new HashMap<>(), savedData, null, 0L, 0L, true, FileManifest.empty());
        manifest.writeTo(snapshotsDir.resolve("snap-sd.manifest"));

        WorldPaths paths = new WorldPaths(world);
        RestoreFlow.RestoreResult result = new RestoreFlow(store, paths, snapshotsDir).restore("snap-sd");

        assertEquals(3, result.savedDataFilesRestored(), "all three savedData entries restored");
        // M1 修复核心: end SavedData 落回 DIM1/data, 而非 overworld data/
        assertArrayEquals(endBytes,
                Files.readAllBytes(world.resolve("DIM1").resolve("data").resolve("raids_end.dat")),
                "end SavedData must restore under DIM1/data, not overworld data/");
        assertArrayEquals(sbBytes,
                Files.readAllBytes(world.resolve("data").resolve("scoreboard.dat")),
                "overworld SavedData restores under data/");
        assertArrayEquals(legacyBytes,
                Files.readAllBytes(world.resolve("data").resolve("raids.dat")),
                "legacy bare-name key falls back to overworld data/<name>.dat");
    }
}
