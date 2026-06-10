package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.WorldPaths;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B: external (.mcc) chunk 走完整 store -> RestoreFlow -> 磁盘 -> reader 读回的
 * 端到端字节级 round-trip. 证明 RestoreFlow 不改一行代码即透明支持 external 布局
 * (writer 据 0x80 flag 还原 stub + .mcc), 且跟 inline chunk 同一原子恢复路径.
 */
class RestoreFlowExternalChunkTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void external_and_inline_chunks_restore_byte_exact(@TempDir Path root) throws IOException {
        Path worldRoot = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(worldRoot);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // external chunk store 对象: (compType|0x80) + .mcc 内容 (真实 zlib)
        byte[] externalMcc = ChunkPayloadFixtures.deflate(randomBytes(45000, 1));
        byte[] externalObject = new byte[1 + externalMcc.length];
        externalObject[0] = (byte) (ChunkPayloadFixtures.COMPRESSION_ZLIB | 0x80);
        System.arraycopy(externalMcc, 0, externalObject, 1, externalMcc.length);

        // inline chunk store 对象: 普通 zlib payload
        byte[] inlineObject = ChunkPayloadFixtures.zlibPayload(randomBytes(8000, 2));

        Hash externalHash = hashFn.hash(externalObject);
        Hash inlineHash = hashFn.hash(inlineObject);
        store.put(externalHash, externalObject);
        store.put(inlineHash, inlineObject);

        // 两个 chunk 落在同一 region (r.0.0): external 在 (2,3), inline 在 (10,11)
        long externalPos = ChunkPos.asLong(2, 3);
        long inlinePos = ChunkPos.asLong(10, 11);
        Map<Long, Hash> chunkSlots = new HashMap<>();
        chunkSlots.put(externalPos, externalHash);
        chunkSlots.put(inlinePos, inlineHash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, chunkSlots);

        com.shinoyuki.betterbackup.snapshot.SnapshotManifest manifest =
                new com.shinoyuki.betterbackup.snapshot.SnapshotManifest(
                        com.shinoyuki.betterbackup.snapshot.SnapshotManifest.SCHEMA_VERSION,
                        "snap-ext",
                        System.currentTimeMillis(),
                        0L,
                        chunks,
                        new HashMap<>(),
                        new HashMap<>(),
                        null,
                        externalObject.length + inlineObject.length,
                        0L,
                        true);
        manifest.writeTo(snapshotsDir.resolve("snap-ext.manifest"));

        WorldPaths paths = new WorldPaths(worldRoot);
        RestoreFlow flow = new RestoreFlow(store, paths, snapshotsDir);
        RestoreFlow.RestoreResult result = flow.restore("snap-ext");
        assertTrue(result.chunkSlotsRestored() == 2, "both chunk slots must be restored");

        // 从恢复出的 region 逐个读回, 字节级比对 store 对象.
        byte[] readExternal = RegionFileSlotReader.readChunk(paths.regionDir(DIM), 2, 3);
        byte[] readInline = RegionFileSlotReader.readChunk(paths.regionDir(DIM), 10, 11);
        assertArrayEquals(externalObject, readExternal,
                "restored external chunk must round-trip byte-exact through stub + .mcc");
        assertArrayEquals(inlineObject, readInline,
                "restored inline chunk must round-trip byte-exact");

        // 磁盘上 external chunk 的 .mcc 文件必须存在且内容 == 原始 .mcc raw 内容.
        Path mca = RegionFileSlotReader.mcaPathFor(paths.regionDir(DIM), 2, 3);
        Path mcc = RegionFileSlotReader.mccPathFor(mca, 2, 3);
        assertTrue(Files.isRegularFile(mcc), "external .mcc must be materialized on restore: " + mcc);
        assertArrayEquals(externalMcc, Files.readAllBytes(mcc),
                "restored .mcc raw content must match original exactly");
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
