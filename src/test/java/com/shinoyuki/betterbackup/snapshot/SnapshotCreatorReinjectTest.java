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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1: drainAndClear 之后的失败分支 (玩家数据采集 / manifest 写盘) 必须把已 drain 出的条目回灌
 * dirty map, 否则本窗口 BAS 活跃路径采的 chunk/entity/savedData 永久丢出备份 (除 CurrentSnapshotState
 * 外无第二份记录, build 又是纯 overlay 无全量 diff 自愈)。
 *
 * <p>判定标准: 删掉 CurrentSnapshotState.reinject 的回灌或 create() 失败分支里的 state.reinject(drained)
 * 调用 -> post_drain_player_data_failure 用例的 chunkCount/savedDataCount 断言必挂 (dirty 被 drain 清空后不回填)。
 */
class SnapshotCreatorReinjectTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void reinject_restores_all_four_channels_after_drain(@TempDir Path base) {
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        CurrentSnapshotState state = new CurrentSnapshotState();
        Hash chunkHash = hashFn.hash("chunk".getBytes(StandardCharsets.UTF_8));
        Hash entityHash = hashFn.hash("entity".getBytes(StandardCharsets.UTF_8));
        Hash savedHash = hashFn.hash("saved".getBytes(StandardCharsets.UTF_8));
        Hash levelHash = hashFn.hash("level".getBytes(StandardCharsets.UTF_8));
        state.putChunk(DIM, ChunkPos.asLong(1, 1), chunkHash);
        state.putEntityChunk(DIM, ChunkPos.asLong(2, 2), entityHash);
        state.putSavedData("raids", savedHash);
        state.putLevelDat(levelHash);

        CurrentSnapshotState.Drained drained = state.drainAndClear();
        // drain 后 state 必空
        assertEquals(0, state.size(), "drain must empty the dirty state");

        state.reinject(drained);

        CurrentSnapshotState.Drained again = state.drainAndClear();
        assertEquals(chunkHash, again.chunks().get(new DimChunkKey(DIM, ChunkPos.asLong(1, 1))),
                "reinject must restore the chunk channel");
        assertEquals(entityHash, again.entityChunks().get(new DimChunkKey(DIM, ChunkPos.asLong(2, 2))),
                "reinject must restore the entity-chunk channel");
        assertEquals(savedHash, again.savedData().get("raids"),
                "reinject must restore the savedData channel");
        assertEquals(levelHash, again.levelDat(), "reinject must restore the levelDat channel");
    }

    @Test
    void reinject_does_not_regress_keys_refired_during_the_failure_window() {
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        CurrentSnapshotState state = new CurrentSnapshotState();
        long posA = ChunkPos.asLong(3, 4);
        long posB = ChunkPos.asLong(5, 6);
        Hash a1 = hashFn.hash("a-v1".getBytes(StandardCharsets.UTF_8));
        Hash a2 = hashFn.hash("a-v2".getBytes(StandardCharsets.UTF_8));
        Hash b = hashFn.hash("b".getBytes(StandardCharsets.UTF_8));

        state.putChunk(DIM, posA, a1);
        state.putChunk(DIM, posB, b);
        CurrentSnapshotState.Drained drained = state.drainAndClear();

        // 失败窗口内 posA 被新一次 save 重新 fire (更新的 hash a2)
        state.putChunk(DIM, posA, a2);

        state.reinject(drained);

        CurrentSnapshotState.Drained again = state.drainAndClear();
        assertEquals(a2, again.chunks().get(new DimChunkKey(DIM, posA)),
                "reinject must keep the newer hash (a2) for a key re-fired during the failure window, not regress to a1");
        assertEquals(b, again.chunks().get(new DimChunkKey(DIM, posB)),
                "reinject must restore a key that was not re-fired");
        assertEquals(2, again.chunks().size(), "exactly the two keys present");
    }

    @Test
    void post_drain_player_data_failure_reinjects_dirty_for_retry(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("backup-store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot.resolve("playerdata"));
        // 一个 playerdata 文件让 collect() 走到 store.put; 无 level.dat 故 hashAndStoreLevelDat 不碰 store。
        Files.write(worldRoot.resolve("playerdata").resolve("p1.dat"), "inv".getBytes(StandardCharsets.UTF_8));

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putChunk(DIM, ChunkPos.asLong(5, 5), hashFn.hash("c".getBytes(StandardCharsets.UTF_8)));
        state.putSavedData("raids", hashFn.hash("r".getBytes(StandardCharsets.UTF_8)));
        int chunksBefore = state.chunkCount();
        int savedBefore = state.savedDataCount();

        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = new SnapshotCreator(store, state, paths, hashFn, storeRoot,
                () -> 0L, ConcurrentHashMap.newKeySet(), metrics, new BaselineProgress(storeRoot), () -> false);

        // 模拟 post-drain 磁盘失败: 删掉 packs 目录, collect() 内 store.put 开新 pack (FileChannel.open
        // CREATE) 时父级缺失必抛 IOException, 走 player-data 采集失败分支 (drain 已发生)。
        deleteRecursively(store.packStore().packsDir());

        creator.create("test");

        assertEquals(1, metrics.snapshot().snapshotsFailed(), "a post-drain player-data failure must fail the snapshot");
        assertEquals(0, metrics.snapshot().snapshotsCreated());
        assertEquals(chunksBefore, state.chunkCount(),
                "drained chunks must be reinjected (preserved for retry), not lost on a post-drain failure");
        assertEquals(savedBefore, state.savedDataCount(),
                "drained savedData must be reinjected on a post-drain failure");
        assertTrue(creator.failureMarker().read().orElseThrow().reason().contains("player data collection failed"),
                "the failure marker must describe the post-drain player-data failure");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
