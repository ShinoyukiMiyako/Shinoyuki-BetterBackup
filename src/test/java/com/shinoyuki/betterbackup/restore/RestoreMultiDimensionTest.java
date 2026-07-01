package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多维度恢复: region 与 entities 两个通道, 跨 overworld / 下界(DIM-1) / 末地(DIM1) /
 * modded(dimensions/&lt;ns&gt;/&lt;path&gt;) 四种磁盘布局, 验证 {@link RestoreFlow} 把每个维度的
 * chunk 落到正确物理路径且字节级一致。
 *
 * <p>判定标准: 把 {@link WorldPaths#dimRoot} 的下界/末地/modded 映射改错, 对应维度的
 * {@code readChunk} 比对必挂 (chunk 落到了错误目录, 原位读不到正确字节)。
 */
class RestoreMultiDimensionTest {

    @Test
    void restore_rebuilds_chunks_across_all_dimension_layouts(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);

        int cx = 3;
        int cz = 9;
        String[] dims = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "mymod:custom"};

        // region 通道: 每个维度一个独立字节的 chunk. 入 store 的是 RegionFile slot 真实字节
        // (含 length header / padding), 保证 restore 写回再读出的往返一致.
        Map<String, byte[]> expectedRegion = new HashMap<>();
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        for (int i = 0; i < dims.length; i++) {
            byte[] slotBytes = seedSlotBytes(root.resolve("seed-region-" + i), "region", hashFn, store,
                    cx, cz, ChunkPayloadFixtures.zlibPayload(randomBytes(5000, 100 + i)));
            expectedRegion.put(dims[i], slotBytes);
            Map<Long, Hash> slots = new HashMap<>();
            slots.put(ChunkPos.asLong(cx, cz), hashFn.hash(slotBytes));
            chunks.put(dims[i], slots);
        }

        // entities 通道: 下界放一个, 覆盖 entitiesDir 映射 (与 region 不同子目录)
        byte[] entBytes = seedSlotBytes(root.resolve("seed-ent"), "entities", hashFn, store,
                cx, cz, ChunkPayloadFixtures.zlibPayload(randomBytes(4000, 200)));
        Map<String, Map<Long, Hash>> entityChunks = new HashMap<>();
        Map<Long, Hash> entSlots = new HashMap<>();
        entSlots.put(ChunkPos.asLong(cx, cz), hashFn.hash(entBytes));
        entityChunks.put("minecraft:the_nether", entSlots);

        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-dims", System.currentTimeMillis(), 0L,
                chunks, entityChunks, new HashMap<>(), null, 0L, 0L, true, FileManifest.empty());
        manifest.writeTo(snapshotsDir.resolve("snap-dims.manifest"));

        RestoreFlow.RestoreResult result = new RestoreFlow(store, paths, snapshotsDir).restore("snap-dims");
        assertEquals(4, result.chunkSlotsRestored(), "all four dimension region chunks restored");
        assertEquals(1, result.entitySlotsRestored(), "nether entities chunk restored");

        // 每个维度的 region chunk 字节级还原, 且落在各自布局路径
        for (String dim : dims) {
            assertArrayEquals(expectedRegion.get(dim), RegionFileSlotReader.readChunk(paths.regionDir(dim), cx, cz),
                    "dimension " + dim + " region chunk must restore byte-exact under its own layout path");
        }

        // 物理路径映射正确性 (布局没串维度)
        assertTrue(paths.regionDir("minecraft:the_nether").startsWith(world.resolve("DIM-1")),
                "nether region must land under DIM-1/region");
        assertTrue(paths.regionDir("minecraft:the_end").startsWith(world.resolve("DIM1")),
                "end region must land under DIM1/region");
        assertTrue(paths.regionDir("mymod:custom").startsWith(world.resolve("dimensions").resolve("mymod")),
                "modded region must land under dimensions/<ns>/<path>/region");

        // entities 通道落到 entitiesDir 而非 regionDir
        assertArrayEquals(entBytes, RegionFileSlotReader.readChunk(paths.entitiesDir("minecraft:the_nether"), cx, cz),
                "nether entities chunk must restore byte-exact under DIM-1/entities");
    }

    /** 把 payload 写进一个临时 region/entities 文件读回 slot 真实字节, 入 store, 返回该字节. */
    private static byte[] seedSlotBytes(Path seedRoot, String channel, Xxh128HashFunction hashFn, ChunkStore store,
                                        int cx, int cz, byte[] payload) throws IOException {
        Path channelDir = seedRoot.resolve(channel);
        Files.createDirectories(channelDir);
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(channelDir.resolve("r.0.0.mca"))) {
            w.writeChunk(cx & 31, cz & 31, payload);
        }
        byte[] slotBytes = RegionFileSlotReader.readChunk(channelDir, cx, cz);
        store.put(hashFn.hash(slotBytes), slotBytes);
        return slotBytes;
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
