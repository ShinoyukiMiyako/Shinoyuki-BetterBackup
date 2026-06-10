package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.safety.SnapshotFailureMarker;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证快照失败可见性 (.incomplete 标记) 的写入与清除挂接在 SnapshotCreator 上.
 * 不打桩, 用真实 store + WorldPaths + @TempDir, 通过制造 snapshots/ 目录创建失败
 * 来确定性触发失败分支.
 */
class SnapshotCreatorMarkerTest {

    private SnapshotCreator newCreator(Path storeRoot, Path worldRoot,
                                       CurrentSnapshotState state, BetterBackupMetrics metrics)
            throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        HashFunction hashFunction = new Xxh128HashFunction();
        Set<Hash> writtenThisWindow = ConcurrentHashMap.newKeySet();
        return new SnapshotCreator(store, state, paths, hashFunction, storeRoot,
                () -> 0L, writtenThisWindow, metrics, () -> false);
    }

    @Test
    void successful_snapshot_writes_manifest_and_leaves_no_marker(@TempDir Path base)
            throws IOException {
        Path storeRoot = base.resolve("backup-store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        CurrentSnapshotState state = new CurrentSnapshotState();
        BetterBackupMetrics metrics = new BetterBackupMetrics();

        SnapshotCreator creator = newCreator(storeRoot, worldRoot, state, metrics);
        creator.create("test");

        // 成功: 至少一份 manifest 写盘, 失败标记不存在.
        try (var s = Files.list(creator.snapshotsDir())) {
            long manifests = s.filter(p -> p.getFileName().toString().endsWith(".manifest")).count();
            assertEquals(1, manifests, "exactly one manifest written");
        }
        assertFalse(creator.failureMarker().exists(), "no .incomplete after success");
        assertEquals(1, metrics.snapshot().snapshotsCreated());
        assertEquals(0, metrics.snapshot().snapshotsFailed());
    }

    @Test
    void failed_snapshot_writes_incomplete_marker_visible_to_status(@TempDir Path base)
            throws IOException {
        Path storeRoot = base.resolve("backup-store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        // 制造确定性失败: 在 snapshots/ 路径上放一个普通文件, createDirectories 会抛.
        Path snapshotsPath = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsPath.getParent());
        Files.write(snapshotsPath, new byte[]{1});

        CurrentSnapshotState state = new CurrentSnapshotState();
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        WorldPaths paths = new WorldPaths(worldRoot);
        SnapshotCreator creator = new SnapshotCreator(store, state, paths,
                new Xxh128HashFunction(), storeRoot, () -> 0L,
                ConcurrentHashMap.newKeySet(), metrics, () -> false);

        creator.create("test");

        assertTrue(creator.failureMarker().exists(), ".incomplete written on failure");
        SnapshotFailureMarker.Failure f = creator.failureMarker().read().orElseThrow();
        assertTrue(f.reason().contains("snapshots dir creation failed"),
                "marker reason describes the failure, got: " + f.reason());
        assertEquals(1, metrics.snapshot().snapshotsFailed());
        assertEquals(0, metrics.snapshot().snapshotsCreated());
    }

    @Test
    void successful_snapshot_clears_a_stale_marker_from_prior_failure(@TempDir Path base)
            throws IOException {
        Path storeRoot = base.resolve("backup-store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        CurrentSnapshotState state = new CurrentSnapshotState();
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, state, metrics);

        // 预置一个上一轮失败留下的标记
        creator.failureMarker().write(123L, "prior failure");
        assertTrue(creator.failureMarker().exists());

        creator.create("test");

        assertFalse(creator.failureMarker().exists(),
                "successful snapshot must clear the stale .incomplete marker");
    }
}
