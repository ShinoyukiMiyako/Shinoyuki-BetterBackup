package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkRestoreFlow} 的快照解析 / 未采集报错路径单测 (DESIGN §4.6). 不碰
 * server / ServerLevel / 主线程, 只验证 "从 (snapshotId, dim, x, z) 解析到 CompoundTag"
 * 这一段的正确性与边界行为.
 *
 * <p>判定标准: 把 resolve 的 "未采集返回 notCaptured" 改成静默返回空 tag, captured=false
 * 用例必挂; 把 store.has 校验删掉, missing-store 用例不再抛 IOException 必挂.
 */
class ChunkRestoreFlowTest {

    private static final String DIM = "minecraft:overworld";
    private static final String OTHER_DIM = "minecraft:the_nether";

    /** 把一个 chunk NBT 压成 zlib slot store 对象 (与采集侧 type 2 一致). */
    private static byte[] storeObjectFor(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            NbtIo.write(tag, dos);
        }
        return ChunkPayloadFixtures.zlibPayload(bos.toByteArray());
    }

    private static CompoundTag chunkTag(int x, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 3465);
        tag.putInt("xPos", x);
        tag.putInt("zPos", z);
        tag.putString("Status", "minecraft:full");
        return tag;
    }

    /** 写一份只含单维度单 chunk 的 manifest, baselineComplete=false (在线单 chunk 不门禁). */
    private static void writeManifest(Path snapshotsDir, String id, Map<String, Map<Long, Hash>> chunks)
            throws IOException {
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                id,
                System.currentTimeMillis(),
                0L,
                chunks,
                new HashMap<>(),
                new HashMap<>(),
                null,
                0L,
                0L,
                false,
                FileManifest.empty());
        manifest.writeTo(snapshotsDir.resolve(id + ".manifest"));
    }

    @Test
    void resolves_captured_chunk_to_decoded_tag(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        int x = 5;
        int z = 7;
        byte[] storeObject = storeObjectFor(chunkTag(x, z));
        Hash hash = hashFn.hash(storeObject);
        store.put(hash, storeObject);

        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPos.asLong(x, z), hash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        writeManifest(snapshotsDir, "snap-a", chunks);

        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir);
        ChunkRestoreFlow.ResolvedChunk resolved = flow.resolve("snap-a", DIM, x, z);

        assertTrue(resolved.captured(), "chunk was captured, must resolve");
        assertEquals(x, resolved.pos().x);
        assertEquals(z, resolved.pos().z);
        // 解码出的 tag 字段必须等于原始 chunk NBT (证明 store -> NBT 还原链全通).
        assertEquals(3465, resolved.tag().getInt("DataVersion"));
        assertEquals(x, resolved.tag().getInt("xPos"));
        assertEquals(z, resolved.tag().getInt("zPos"));
        assertEquals("minecraft:full", resolved.tag().getString("Status"));
    }

    @Test
    void uncaptured_chunk_in_captured_dim_returns_not_captured(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // manifest 只采了 (5,7), 但请求 (100,100) -> 未采集.
        byte[] storeObject = storeObjectFor(chunkTag(5, 7));
        Hash hash = hashFn.hash(storeObject);
        store.put(hash, storeObject);
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPos.asLong(5, 7), hash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        writeManifest(snapshotsDir, "snap-b", chunks);

        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir);
        ChunkRestoreFlow.ResolvedChunk resolved = flow.resolve("snap-b", DIM, 100, 100);

        assertFalse(resolved.captured(), "chunk not in manifest must be reported not-captured");
        assertNull(resolved.tag());
        assertTrue(resolved.reason().contains("100"), "reason should name the missing chunk coords");
    }

    @Test
    void uncaptured_dimension_returns_not_captured(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        byte[] storeObject = storeObjectFor(chunkTag(5, 7));
        Hash hash = hashFn.hash(storeObject);
        store.put(hash, storeObject);
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPos.asLong(5, 7), hash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        writeManifest(snapshotsDir, "snap-c", chunks);

        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir);
        // 请求一个 manifest 完全没采集的维度.
        ChunkRestoreFlow.ResolvedChunk resolved = flow.resolve("snap-c", OTHER_DIM, 5, 7);

        assertFalse(resolved.captured());
        assertTrue(resolved.reason().contains(OTHER_DIM), "reason should name the missing dimension");
    }

    @Test
    void missing_store_object_for_referenced_hash_throws(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        // manifest 引用一个 store 里不存在的 hash (模拟 store 损坏 / GC 误删).
        Hash danglingHash = new Hash(new byte[]{
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10});
        Map<Long, Hash> dimChunks = new HashMap<>();
        dimChunks.put(ChunkPos.asLong(5, 7), danglingHash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        writeManifest(snapshotsDir, "snap-d", chunks);

        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir);
        assertThrows(IOException.class, () -> flow.resolve("snap-d", DIM, 5, 7));
    }

    @Test
    void missing_manifest_throws(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir);
        assertThrows(IOException.class, () -> flow.resolve("no-such-snapshot", DIM, 0, 0));
    }
}
