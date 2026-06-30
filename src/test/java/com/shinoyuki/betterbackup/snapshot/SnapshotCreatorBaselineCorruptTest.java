package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.baseline.BaselineProgress;
import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2: 最新 manifest 损坏时不得 fail-open 退空基线 (那会把"最新快照"覆盖成只含本窗口 drain 的近空
 * manifest, 丢掉历史累积的全部未变 chunk 引用), 而应中止本次快照并保留 dirty 留待下次重试; 仅
 * "首次无任何 manifest"才以空基线继续。
 *
 * <p>判定标准: 把 loadPreviousBaseline 改回吞异常返回 empty (旧 fail-open 行为) -> corrupt_latest
 * 用例必挂 —— 会多写一份近空 manifest (manifest 计数变 3) 且 dirty 被 drain 清空 (chunkCount 变 0)。
 */
class SnapshotCreatorBaselineCorruptTest {

    private static SnapshotCreator newCreator(Path storeRoot, Path worldRoot,
                                              CurrentSnapshotState state, BetterBackupMetrics metrics)
            throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        return new SnapshotCreator(store, state, paths, new Xxh128HashFunction(), storeRoot,
                () -> 0L, ConcurrentHashMap.newKeySet(), metrics, new BaselineProgress(storeRoot), () -> false);
    }

    @Test
    void corrupt_latest_manifest_aborts_preserves_dirty_and_writes_no_new_manifest(@TempDir Path base)
            throws IOException {
        Path storeRoot = base.resolve("backup-store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        // 一份合法的旧 manifest (字典序较小) + 一份损坏的最新 manifest (字典序较大): 即便有可用的
        // 次新 manifest, 最新一份损坏也必须中止而非静默回退到旧基线。
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        Hash oldChunkHash = hashFn.hash("old-chunk".getBytes(StandardCharsets.UTF_8));
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put("minecraft:overworld", new HashMap<>(Map.of(ChunkPos.asLong(3, 7), oldChunkHash)));
        SnapshotManifest older = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "2020-01-01T00-00-00Z", 1L, 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty());
        Path olderPath = snapshotsDir.resolve("2020-01-01T00-00-00Z.manifest");
        older.writeTo(olderPath);
        byte[] olderBytes = Files.readAllBytes(olderPath);

        Path corruptLatest = snapshotsDir.resolve("2999-12-31T23-59-59Z.manifest");
        Files.write(corruptLatest, "this is not a valid compressed NBT manifest".getBytes(StandardCharsets.UTF_8));

        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putChunk("minecraft:overworld", ChunkPos.asLong(10, 20),
                hashFn.hash("new-chunk".getBytes(StandardCharsets.UTF_8)));
        state.putSavedData("scoreboard", hashFn.hash("sb".getBytes(StandardCharsets.UTF_8)));
        int chunksBefore = state.chunkCount();
        int savedBefore = state.savedDataCount();

        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, state, metrics);

        creator.create("test");

        assertEquals(1, metrics.snapshot().snapshotsFailed(), "corrupt latest manifest must fail the snapshot");
        assertEquals(0, metrics.snapshot().snapshotsCreated(), "no snapshot may be created from a corrupt baseline");
        assertEquals(chunksBefore, state.chunkCount(),
                "dirty chunks must be preserved for retry, not drained away on a corrupt-baseline abort");
        assertEquals(savedBefore, state.savedDataCount(), "dirty savedData must be preserved for retry");
        assertTrue(creator.failureMarker().exists(), ".incomplete marker must be written on corrupt-baseline abort");
        assertTrue(creator.failureMarker().read().orElseThrow().reason().contains("latest manifest unreadable"),
                "marker reason must point at the unreadable latest manifest");
        try (Stream<Path> s = Files.list(snapshotsDir)) {
            long manifests = s.filter(p -> p.getFileName().toString().endsWith(".manifest")).count();
            assertEquals(2, manifests, "no new near-empty manifest may be written when aborting on a corrupt baseline");
        }
        assertArrayEquals(olderBytes, Files.readAllBytes(olderPath),
                "the older valid manifest must be left byte-for-byte untouched");
    }

    @Test
    void first_snapshot_with_no_manifest_proceeds_from_empty_baseline(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("backup-store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        long packedPos = ChunkPos.asLong(1, 2);
        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putChunk("minecraft:overworld", packedPos,
                hashFn.hash("first-chunk".getBytes(StandardCharsets.UTF_8)));

        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, state, metrics);

        creator.create("test");

        assertEquals(1, metrics.snapshot().snapshotsCreated(),
                "first-time (no manifest) must still produce a snapshot from an empty baseline");
        assertEquals(0, metrics.snapshot().snapshotsFailed());
        assertEquals(0, state.chunkCount(), "dirty must be drained on a successful snapshot");
        Path manifestPath;
        try (Stream<Path> s = Files.list(creator.snapshotsDir())) {
            manifestPath = s.filter(p -> p.getFileName().toString().endsWith(".manifest")).findFirst().orElseThrow();
        }
        SnapshotManifest written = SnapshotManifest.readFrom(manifestPath);
        assertTrue(written.chunks().getOrDefault("minecraft:overworld", Map.of()).containsKey(packedPos),
                "the first snapshot must contain this window's dirty chunk");
    }
}
