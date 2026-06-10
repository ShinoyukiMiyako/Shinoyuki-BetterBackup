package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SnapshotCreator 降级闩锁 -> manifest.incomplete 戳的传导 (PLAN Phase F).
 *
 * <p>不打桩, 用真实 store + manifest 写盘 + readFrom 回读, 断言落盘的具体布尔值。
 * 判定标准: 把 build() 里 degraded.get() 改成写死 false, "degraded 后 incomplete=true"
 * 用例必挂; markDegraded 不接通同样必挂。
 */
class SnapshotCreatorDegradedTest {

    private SnapshotCreator newCreator(Path storeRoot, Path worldRoot, CurrentSnapshotState state)
            throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        return new SnapshotCreator(store, state, paths, new Xxh128HashFunction(), storeRoot,
                () -> 0L, written, new BetterBackupMetrics(), () -> true);
    }

    private SnapshotManifest latestManifest(Path snapshotsDir) throws IOException {
        try (Stream<Path> s = Files.list(snapshotsDir)) {
            List<Path> manifests = s
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .sorted()
                    .toList();
            return SnapshotManifest.readFrom(manifests.get(manifests.size() - 1));
        }
    }

    @Test
    void snapshot_before_degraded_is_not_incomplete(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        SnapshotCreator creator = newCreator(storeRoot, worldRoot, new CurrentSnapshotState());
        assertFalse(creator.isDegraded());
        creator.create("interval");

        assertFalse(latestManifest(creator.snapshotsDir()).incomplete(),
                "降级前的正常快照不该标 incomplete");
    }

    @Test
    void snapshot_after_degraded_is_marked_incomplete(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        SnapshotCreator creator = newCreator(storeRoot, worldRoot, new CurrentSnapshotState());

        creator.markDegraded();
        assertTrue(creator.isDegraded());
        creator.create("shutdown");

        assertTrue(latestManifest(creator.snapshotsDir()).incomplete(),
                "降级期间 (关服最终快照) 产出的快照必须标 incomplete");
    }

    @Test
    void degraded_latch_persists_across_multiple_creates(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        SnapshotCreator creator = newCreator(storeRoot, worldRoot, new CurrentSnapshotState());
        creator.markDegraded();

        // 多次 create 后闩锁不复位, 最新快照仍 incomplete (snapshotId 秒级粒度, 同秒内
        // 同名覆盖, 不依赖产出多份文件; 只断言闩锁无复位语义).
        creator.create("manual-1");
        creator.create("manual-2");

        assertTrue(creator.isDegraded(), "降级闩锁多次 create 后不复位");
        assertTrue(latestManifest(creator.snapshotsDir()).incomplete(),
                "降级后每次 create 产出的快照都标 incomplete");
    }
}
