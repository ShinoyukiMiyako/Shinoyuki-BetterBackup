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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkRestoreFlow} 的快照解析 / 未采集报错路径单测 (DESIGN §4.6). 不碰
 * server / ServerLevel / 主线程, 只验证 "从 (snapshotId, dim, 目标块集) 解析到 CompoundTag"
 * 这一段的正确性与边界行为.
 *
 * <p>判定标准: 把 resolve 的 "未采集返回 notCaptured" 改成静默返回空 tag, captured=false
 * 用例必挂; 把 store.has 校验删掉, missing-store 用例不再抛 IOException 必挂; 把 manifest
 * 加载挪回按块循环内, "整批只加载一次" 用例的计数断言必挂.
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

    private static SnapshotManifest readManifest(Path snapshotsDir, String id) throws IOException {
        return SnapshotManifest.readFrom(snapshotsDir.resolve(id + ".manifest"));
    }

    /** 以 center 为中心、半径 radius 的 (2r+1)^2 块目标, 顺序与命令层一致. */
    private static List<ChunkPos> area(int centerX, int centerZ, int radius) {
        List<ChunkPos> targets = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                targets.add(new ChunkPos(centerX + dx, centerZ + dz));
            }
        }
        return targets;
    }

    /** 计数用 manifest 加载器: 仍走真实 readFrom, 只旁路记录被加载了几次、加载的是哪个文件. */
    private static ChunkRestoreFlow.ManifestLoader counting(AtomicInteger loads, List<Path> loadedPaths) {
        return p -> {
            loads.incrementAndGet();
            loadedPaths.add(p);
            return SnapshotManifest.readFrom(p);
        };
    }

    /** 把整批目标全部采集进 store + manifest. */
    private static void captureAll(ChunkStore store, Path snapshotsDir, String id, List<ChunkPos> targets)
            throws IOException {
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        Map<Long, Hash> dimChunks = new HashMap<>();
        for (ChunkPos pos : targets) {
            byte[] storeObject = storeObjectFor(chunkTag(pos.x, pos.z));
            Hash hash = hashFn.hash(storeObject);
            store.put(hash, storeObject);
            dimChunks.put(ChunkPos.asLong(pos.x, pos.z), hash);
        }
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        writeManifest(snapshotsDir, id, chunks);
    }

    private static ChunkStore newStore(Path root) throws IOException {
        ChunkStore store = new ChunkStore(root.resolve("store"));
        store.initialize();
        return store;
    }

    private static Path newSnapshotsDir(Path root) throws IOException {
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        return snapshotsDir;
    }

    @Test
    void resolves_captured_chunk_to_decoded_tag(@TempDir Path root) throws IOException {
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);
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
        ChunkRestoreFlow.ResolvedChunk resolved =
                flow.resolve(readManifest(snapshotsDir, "snap-a"), DIM, x, z);

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
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);
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
        ChunkRestoreFlow.ResolvedChunk resolved =
                flow.resolve(readManifest(snapshotsDir, "snap-b"), DIM, 100, 100);

        assertFalse(resolved.captured(), "chunk not in manifest must be reported not-captured");
        assertNull(resolved.tag());
        assertTrue(resolved.reason().contains("100"), "reason should name the missing chunk coords");
    }

    @Test
    void uncaptured_dimension_returns_not_captured(@TempDir Path root) throws IOException {
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);
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
        ChunkRestoreFlow.ResolvedChunk resolved =
                flow.resolve(readManifest(snapshotsDir, "snap-c"), OTHER_DIM, 5, 7);

        assertFalse(resolved.captured());
        assertTrue(resolved.reason().contains(OTHER_DIM), "reason should name the missing dimension");
    }

    @Test
    void missing_store_object_for_referenced_hash_throws(@TempDir Path root) throws IOException {
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);

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
        SnapshotManifest manifest = readManifest(snapshotsDir, "snap-d");
        assertThrows(IOException.class, () -> flow.resolve(manifest, DIM, 5, 7));
    }

    @Test
    void area_restore_loads_manifest_exactly_once_for_all_targets(@TempDir Path root) throws IOException {
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);
        List<ChunkPos> targets = area(0, 0, 3);
        assertEquals(49, targets.size());
        captureAll(store, snapshotsDir, "snap-area", targets);

        AtomicInteger loads = new AtomicInteger();
        List<Path> loadedPaths = new ArrayList<>();
        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir, counting(loads, loadedPaths));
        ChunkRestoreFlow.ResolvedArea resolvedArea = flow.resolveArea("snap-area", DIM, targets);

        assertEquals(1, loads.get(), "整批区域回退必须只加载一次 manifest");
        assertEquals(1, loadedPaths.size());
        assertEquals(snapshotsDir.resolve("snap-area.manifest"), loadedPaths.get(0),
                "加载的必须是目标快照的 manifest");
        // 计数断言不能靠"提前 return 空结果"虚假通过: 整批必须真的全解析出来.
        assertEquals(49, resolvedArea.resolved().size());
        assertEquals(0, resolvedArea.notCaptured());
        assertTrue(resolvedArea.failures().isEmpty());
    }

    @Test
    void area_restore_with_production_loader_decodes_every_target(@TempDir Path root) throws IOException {
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);
        List<ChunkPos> targets = area(0, 0, 3);
        captureAll(store, snapshotsDir, "snap-prod", targets);

        // 生产构造器 (默认加载器 = SnapshotManifest::readFrom) 的端到端路径, 不注入任何桩:
        // 默认加载器接线写错时这条必挂.
        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir);
        ChunkRestoreFlow.ResolvedArea resolvedArea = flow.resolveArea("snap-prod", DIM, targets);

        assertEquals(49, resolvedArea.resolved().size());
        assertEquals(0, resolvedArea.notCaptured());
        assertTrue(resolvedArea.failures().isEmpty());
        // 共享同一份 manifest 后每块仍须解析到自己那份字节, 不能串块.
        for (ChunkRestoreFlow.ResolvedTarget t : resolvedArea.resolved()) {
            assertNotNull(t.tag());
            assertEquals(t.pos().x, t.tag().getInt("xPos"));
            assertEquals(t.pos().z, t.tag().getInt("zPos"));
            assertEquals("minecraft:full", t.tag().getString("Status"));
        }
    }

    @Test
    void area_restore_never_puts_uncaptured_chunk_into_install_list(@TempDir Path root) throws IOException {
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);
        List<ChunkPos> targets = area(0, 0, 3);
        // 49 块目标里只采集 3 块, 其余 46 块 manifest 里根本没有条目.
        List<ChunkPos> captured = List.of(new ChunkPos(0, 0), new ChunkPos(1, 2), new ChunkPos(-2, -1));
        captureAll(store, snapshotsDir, "snap-sparse", captured);

        AtomicInteger loads = new AtomicInteger();
        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir, counting(loads, new ArrayList<>()));
        ChunkRestoreFlow.ResolvedArea resolvedArea = flow.resolveArea("snap-sparse", DIM, targets);

        assertEquals(3, resolvedArea.resolved().size());
        Set<ChunkPos> installable = new HashSet<>();
        for (ChunkRestoreFlow.ResolvedTarget t : resolvedArea.resolved()) {
            installable.add(t.pos());
            // 未采集绝不能被塞成 null / 空 tag 占位交给 BAS 覆盖活 chunk.
            assertNotNull(t.tag());
            assertEquals(t.pos().x, t.tag().getInt("xPos"));
        }
        assertEquals(new HashSet<>(captured), installable);
        assertEquals(46, resolvedArea.notCaptured());
        assertTrue(resolvedArea.failures().isEmpty(), "未采集是正常业务分支, 不得混进失败明细");
        assertEquals(1, loads.get(), "大量未采集不改变加载次数");
    }

    @Test
    void area_restore_isolates_single_dangling_hash_and_keeps_the_rest(@TempDir Path root) throws IOException {
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        List<ChunkPos> targets = area(0, 0, 1);
        assertEquals(9, targets.size());

        Hash danglingHash = new Hash(new byte[]{
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10});
        ChunkPos broken = new ChunkPos(1, 1);
        Map<Long, Hash> dimChunks = new HashMap<>();
        for (ChunkPos pos : targets) {
            if (pos.equals(broken)) {
                dimChunks.put(ChunkPos.asLong(pos.x, pos.z), danglingHash);
                continue;
            }
            byte[] storeObject = storeObjectFor(chunkTag(pos.x, pos.z));
            Hash hash = hashFn.hash(storeObject);
            store.put(hash, storeObject);
            dimChunks.put(ChunkPos.asLong(pos.x, pos.z), hash);
        }
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, dimChunks);
        writeManifest(snapshotsDir, "snap-dangling", chunks);

        AtomicInteger loads = new AtomicInteger();
        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir, counting(loads, new ArrayList<>()));
        ChunkRestoreFlow.ResolvedArea resolvedArea = flow.resolveArea("snap-dangling", DIM, targets);

        assertEquals(8, resolvedArea.resolved().size(), "个别块 store 损坏不得拖垮整批");
        for (ChunkRestoreFlow.ResolvedTarget t : resolvedArea.resolved()) {
            assertFalse(broken.equals(t.pos()), "损坏块不得进入可安装列表");
        }
        assertEquals(1, resolvedArea.failures().size());
        assertEquals(broken, resolvedArea.failures().get(0).pos());
        assertTrue(resolvedArea.failures().get(0).cause() instanceof IOException);
        assertTrue(resolvedArea.failures().get(0).cause().getMessage().contains(danglingHash.toHex()),
                "失败原因必须如实带出缺失的 hash");
        // store 损坏与 "未采集" 的运维含义相反, 不得互相归类.
        assertEquals(0, resolvedArea.notCaptured());
        assertEquals(1, loads.get());
    }

    @Test
    void missing_manifest_aborts_whole_batch_before_touching_targets(@TempDir Path root) throws IOException {
        Path snapshotsDir = newSnapshotsDir(root);
        ChunkStore store = newStore(root);

        AtomicInteger loads = new AtomicInteger();
        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, snapshotsDir, counting(loads, new ArrayList<>()));
        List<ChunkPos> targets = area(0, 0, 2);
        IOException ex = assertThrows(IOException.class,
                () -> flow.resolveArea("no-such-snapshot", DIM, targets));

        // 钉死自定义消息本身: 底层 NoSuchFileException 的 message 只有路径, 没有这个前缀.
        assertTrue(ex.getMessage().startsWith("snapshot manifest not found: "),
                "manifest 缺失必须由本类的存在性检查报出: " + ex.getMessage());
        assertEquals(0, loads.get(), "存在性检查先失败, 不得再去打开文件");
    }
}
