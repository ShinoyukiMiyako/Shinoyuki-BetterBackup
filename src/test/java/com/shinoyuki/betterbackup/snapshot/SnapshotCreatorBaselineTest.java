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
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * baseline 门禁的底层依据: SnapshotCreator 给每份 manifest 盖的 baselineComplete 戳
 * 必须等于注入的 baselineCompleteSupplier 当下取值. restore 命令读这个戳决定是否放行,
 * 所以这是门禁正确性的根。
 *
 * <p>不打桩, 用真实 store + manifest 写盘 + readFrom 回读, 断言落盘的具体布尔值。
 * 判定标准: 把 build() 里 baselineCompleteSupplier.getAsBoolean() 改成写死 false,
 * "true" 用例必挂。
 */
class SnapshotCreatorBaselineTest {

    private SnapshotCreator newCreator(Path storeRoot, Path worldRoot, CurrentSnapshotState state,
                                       BooleanSupplier baselineComplete) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        return new SnapshotCreator(store, state, paths, new Xxh128HashFunction(), storeRoot,
                () -> 0L, written, new BetterBackupMetrics(), baselineComplete);
    }

    private SnapshotManifest onlyManifest(Path snapshotsDir) throws IOException {
        try (Stream<Path> s = Files.list(snapshotsDir)) {
            List<Path> manifests = s.filter(p -> p.getFileName().toString().endsWith(".manifest")).toList();
            assertEquals(1, manifests.size(), "exactly one manifest expected");
            return SnapshotManifest.readFrom(manifests.get(0));
        }
    }

    @Test
    void incomplete_baseline_stamps_false(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        SnapshotCreator creator = newCreator(storeRoot, worldRoot, new CurrentSnapshotState(), () -> false);
        creator.create("test");

        assertFalse(onlyManifest(creator.snapshotsDir()).baselineComplete(),
                "snapshot taken while baseline incomplete must be stamped baselineComplete=false");
    }

    @Test
    void complete_baseline_stamps_true(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        SnapshotCreator creator = newCreator(storeRoot, worldRoot, new CurrentSnapshotState(), () -> true);
        creator.create("test");

        assertTrue(onlyManifest(creator.snapshotsDir()).baselineComplete(),
                "snapshot taken after baseline complete must be stamped baselineComplete=true");
    }
}
