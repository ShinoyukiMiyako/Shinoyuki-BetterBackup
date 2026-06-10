package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.baseline.BaselineProgress;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * baseline 门禁的底层依据: SnapshotCreator 给每份 manifest 盖的 baselineComplete 戳
 * 必须等于 baselineProgress.isComplete() 当下取值 (complete 标记是否存在). restore 命令
 * 读这个戳决定是否放行, 所以这是门禁正确性的根。
 *
 * <p>不打桩, 用真实 store + 真实 BaselineProgress + manifest 写盘 + readFrom 回读,
 * 断言落盘的具体布尔值。判定标准: 把 build() 里 baselineCompleteSupplier.getAsBoolean()
 * 改成写死 false, "true" 用例必挂。
 */
class SnapshotCreatorBaselineTest {

    private SnapshotCreator newCreator(Path storeRoot, Path worldRoot, CurrentSnapshotState state,
                                       BaselineProgress baselineProgress) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        // baselineScanFinished=false: 本测试只验证 build 阶段读 complete 标记盖戳的传导,
        // 不让 create() 自己触发晋升 / 写标记 (那由 BaselineCommitTest 覆盖).
        return new SnapshotCreator(store, state, paths, new Xxh128HashFunction(), storeRoot,
                () -> 0L, written, new BetterBackupMetrics(), baselineProgress, () -> false);
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

        // complete 标记不存在 -> isComplete()=false
        BaselineProgress progress = new BaselineProgress(storeRoot);
        progress.load();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, new CurrentSnapshotState(), progress);
        creator.create("test");

        assertFalse(onlyManifest(creator.snapshotsDir()).baselineComplete(),
                "snapshot taken while baseline incomplete must be stamped baselineComplete=false");
    }

    @Test
    void complete_baseline_stamps_true(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        // 预置 complete 标记 (无任何 region -> 全部 committed 空集成立, scanFinished=true 即写)
        // -> isComplete()=true
        BaselineProgress progress = new BaselineProgress(storeRoot);
        progress.load();
        assertTrue(progress.markCompleteIfAllCommitted(true), "no regions => marker written");
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, new CurrentSnapshotState(), progress);
        creator.create("test");

        assertTrue(onlyManifest(creator.snapshotsDir()).baselineComplete(),
                "snapshot taken after baseline complete must be stamped baselineComplete=true");
    }
}
