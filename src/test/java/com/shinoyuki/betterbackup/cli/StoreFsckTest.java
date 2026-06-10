package com.shinoyuki.betterbackup.cli;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN Phase E commit 12/13: store 扫描校验 (重 hash 对比文件名 + 按对象分类做 zlib 完整性) 与
 * 从 store + manifests 重建快照索引。
 *
 * <p><b>对象分类回归 (发版阻断 bug)</b>: store 自 Phase D 起混放 chunk payload (chunks/
 * entityChunks 段, 形态 "compression-type byte + zlib/gzip 流") 与 opaque 整文件 (files/
 * savedData/levelDat 段, 形态 poi 的 .mca 整文件 0x00 开头 / gzip NBT 0x1f / JSON 0x7b)。
 * 旧实现对每个对象都跑压缩校验, 把 opaque 文件首字节误判为 "invalid compression type" CORRUPT
 * (299 万对象真实 store 实测 1620 误报)。fsck 现按 manifest 引用分类: chunk 类才跑压缩校验,
 * opaque 文件类仅重 hash, 未引用的归 orphan。
 *
 * <p><b>判定标准 (删核心逻辑测试必挂)</b>:
 * <ul>
 *   <li>删掉分类逻辑 (回到对所有对象跑压缩校验), {@code classifies_chunk_file_orphan_with_zero_false_positive}
 *       必挂: poi 风格 0x00 整文件对象会被误报 CORRUPT</li>
 *   <li>删掉重 hash 比对, {@code detects_hash_mismatch} 必挂</li>
 *   <li>删掉 chunk 类的压缩校验, {@code detects_zlib_corruption} 必挂</li>
 *   <li>把 orphan 归类去掉 (orphan 仍跑压缩校验或当 corrupt), {@code unreferenced_object_is_orphan} 必挂</li>
 * </ul>
 */
class StoreFsckTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void classifies_chunk_file_orphan_with_zero_false_positive(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // chunk payload (chunks 段引用): 合法 zlib slot, 首字节 = compression type 2。
        byte[] chunkObj = ChunkPayloadFixtures.zlibPayload(randomBytes(3000, 1));
        Hash chunkHash = hashFn.hash(chunkObj);
        store.put(chunkHash, chunkObj);

        // poi 风格 .mca 整文件 (files 段引用): 稀疏位置表开头是 0x00, 旧实现按 chunk payload
        // 跑压缩校验会判 "invalid compression type 0" CORRUPT。这是生产 1481/1620 误报的形态。
        byte[] poiObj = mcaWholeFileBytes(2048, 5);
        assertEquals(0x00, poiObj[0] & 0xFF, "poi .mca whole-file must start with 0x00 (sparse table)");
        Hash poiHash = hashFn.hash(poiObj);
        store.put(poiHash, poiObj);

        // gzip 整文件 (savedData 段引用): playerdata/level.dat/SavedData .dat 的 gzip NBT, 魔数 0x1f。
        byte[] gzipObj = gzipBytes("level data payload".getBytes(StandardCharsets.UTF_8));
        assertEquals(0x1f, gzipObj[0] & 0xFF, "gzip whole-file must start with 0x1f magic");
        Hash gzipHash = hashFn.hash(gzipObj);
        store.put(gzipHash, gzipObj);

        // JSON 整文件 (levelDat 段引用 -- 借 levelDat 槽位挂一个 0x7b 开头对象, 复现 stats/advancements
        // 的 JSON 形态; levelDat 段同属 opaque 文件类, 分类规则一致): 首字节 0x7b = '{'。
        byte[] jsonObj = "{\"stat.mined\":42}".getBytes(StandardCharsets.UTF_8);
        assertEquals(0x7b, jsonObj[0] & 0xFF, "JSON whole-file must start with 0x7b '{'");
        Hash jsonHash = hashFn.hash(jsonObj);
        store.put(jsonHash, jsonObj);

        // manifest: chunk 段引用 chunkHash; files 段引用 poiHash; savedData 段引用 gzipHash;
        // levelDat 段引用 jsonHash。四类齐全, 全部被引用 (无 orphan)。
        Map<Long, Hash> chunkSlots = new HashMap<>();
        chunkSlots.put(ChunkPosCodec.asLong(3, 4), chunkHash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, chunkSlots);

        Map<String, Hash> fileHashes = new HashMap<>();
        fileHashes.put("poi/r.0.0.mca", poiHash);
        FileManifest files = new FileManifest(fileHashes, new HashSet<>());

        Map<String, Hash> savedData = new HashMap<>();
        savedData.put("data/raids.dat", gzipHash);

        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-mixed", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), savedData, jsonHash, 0L, 0L, true, files);
        m.writeTo(snapshotsDir.resolve("snap-mixed.manifest"));

        StoreFsck fsck = new StoreFsck(store, snapshotsDir, hashFn);
        StoreFsck.VerifyResult r = fsck.verifyStore();

        assertEquals(4, r.scanned(), "all four store objects scanned");
        assertEquals(4, r.ok(), "chunk + 3 opaque files all verify ok");
        assertEquals(1, r.chunkObjects(), "exactly one object is classified as chunk payload");
        assertEquals(3, r.fileObjects(), "poi + gzip + json are classified as opaque files");
        assertEquals(0, r.orphans().size(), "every object is referenced -> no orphan");
        assertEquals(0, r.hashMismatch().size(), "no bit-flips -> no hash mismatch");
        assertEquals(0, r.corrupt().size(),
                "opaque files (0x00/0x1f/0x7b first byte) must NOT be flagged CORRUPT");
        assertTrue(r.clean(), "mixed store with correct classification must verify clean");
    }

    @Test
    void detects_hash_mismatch_after_content_tamper(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        byte[] obj = ChunkPayloadFixtures.zlibPayload(randomBytes(3000, 7));
        Hash h = hashFn.hash(obj);
        store.put(h, obj);
        writeChunkManifest(snapshotsDir, "snap-tamper", h);

        // 篡改字节但保留文件名 (模拟位翻转 / 外部改写): 内容 hash != 文件名 hash
        Path stored = store.pathFor(h);
        byte[] tampered = obj.clone();
        tampered[tampered.length - 1] ^= 0x5A;
        Files.write(stored, tampered);

        StoreFsck fsck = new StoreFsck(store, snapshotsDir, hashFn);
        StoreFsck.VerifyResult r = fsck.verifyStore();
        assertEquals(1, r.scanned());
        assertEquals(0, r.ok());
        assertEquals(1, r.hashMismatch().size(), "tampered object must be flagged as hash mismatch");
        assertTrue(r.hashMismatch().get(0).contains(h.toHex()), "mismatch report must name the file");
        assertFalse(r.clean());
    }

    @Test
    void detects_zlib_corruption_with_self_consistent_hash(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // 构造一个 "compression type 2 (zlib) + 截断的压缩流" 对象: zlib inflate 会失败,
        // 但用它自己的字节算 hash 入库 -> hash 与文件名自洽, 只有 inflate 校验抓得出。
        byte[] valid = ChunkPayloadFixtures.zlibPayload(randomBytes(4000, 13));
        byte[] truncated = new byte[valid.length - 10]; // 砍掉尾部 (含 Adler-32 校验和)
        System.arraycopy(valid, 0, truncated, 0, truncated.length);
        Hash h = hashFn.hash(truncated);
        store.put(h, truncated);
        // 必须被 chunk 段引用才会触发压缩校验; 否则会被当 opaque 文件仅重 hash, 抓不出 zlib 损坏。
        writeChunkManifest(snapshotsDir, "snap-corrupt", h);

        StoreFsck fsck = new StoreFsck(store, snapshotsDir, hashFn);
        StoreFsck.VerifyResult r = fsck.verifyStore();
        assertEquals(1, r.scanned());
        assertEquals(0, r.ok(), "corrupt zlib must not count as ok");
        assertEquals(0, r.hashMismatch().size(), "hash is self-consistent, must NOT be a hash mismatch");
        assertEquals(1, r.corrupt().size(), "truncated zlib stream of a chunk object must be flagged corrupt");
        assertFalse(r.clean());
    }

    @Test
    void unreferenced_object_is_orphan_not_corrupt(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // 未被任何 manifest 引用的对象, 且首字节 0x00 (poi 风格)。旧实现 (全量压缩校验) 会判它
        // CORRUPT; 新实现按 orphan 归类, 仅重 hash, 不拉非 0 退出码。
        byte[] orphanObj = mcaWholeFileBytes(1024, 23);
        assertEquals(0x00, orphanObj[0] & 0xFF, "orphan fixture must start with 0x00 to prove no compression check");
        Hash orphanHash = hashFn.hash(orphanObj);
        store.put(orphanHash, orphanObj);
        // snapshotsDir 故意不放任何引用此 hash 的 manifest

        StoreFsck fsck = new StoreFsck(store, snapshotsDir, hashFn);
        StoreFsck.VerifyResult r = fsck.verifyStore();
        assertEquals(1, r.scanned());
        assertEquals(0, r.ok(), "orphan is not counted as ok (it is unreferenced)");
        assertEquals(1, r.orphans().size(), "unreferenced object must be reported as orphan");
        assertTrue(r.orphans().get(0).contains(orphanHash.toHex()), "orphan report must name the file");
        assertEquals(0, r.corrupt().size(), "orphan must NOT be flagged corrupt");
        assertEquals(0, r.hashMismatch().size());
        assertTrue(r.clean(), "orphan alone must not flip exit code (clean == true)");
    }

    @Test
    void chunk_hash_wins_when_object_referenced_as_both_chunk_and_file(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // 同一 hash 同时被 chunks 段与 files 段引用。设计要求: 更严格者 (chunk, 跑压缩校验) 优先。
        // 用一个截断的 zlib 流: 若按 chunk 处理 -> 压缩校验失败 -> CORRUPT; 若错按 file 处理 ->
        // 仅重 hash -> 漏过。断言它被判 corrupt 即证明 chunk 类胜出。
        byte[] valid = ChunkPayloadFixtures.zlibPayload(randomBytes(2000, 31));
        byte[] truncated = new byte[valid.length - 8];
        System.arraycopy(valid, 0, truncated, 0, truncated.length);
        Hash h = hashFn.hash(truncated);
        store.put(h, truncated);

        Map<Long, Hash> chunkSlots = new HashMap<>();
        chunkSlots.put(ChunkPosCodec.asLong(0, 0), h);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, chunkSlots);
        Map<String, Hash> fileHashes = new HashMap<>();
        fileHashes.put("poi/shared.mca", h); // 同一 hash 也挂在 files 段
        FileManifest files = new FileManifest(fileHashes, new HashSet<>());

        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-both", System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, files);
        m.writeTo(snapshotsDir.resolve("snap-both.manifest"));

        StoreFsck fsck = new StoreFsck(store, snapshotsDir, hashFn);
        StoreFsck.VerifyResult r = fsck.verifyStore();
        assertEquals(1, r.scanned());
        assertEquals(1, r.corrupt().size(),
                "dual-referenced object must be treated as chunk (stricter) -> truncated zlib flagged corrupt");
        assertEquals(0, r.fileObjects(), "must not be counted as an opaque file");
        assertFalse(r.clean());
    }

    @Test
    void rebuild_index_marks_ok_incomplete_and_corrupt(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // 快照 A: 引用的对象全在 store -> OK
        byte[] objA = ChunkPayloadFixtures.zlibPayload(randomBytes(2000, 3));
        Hash hA = hashFn.hash(objA);
        store.put(hA, objA);
        writeManifest(snapshotsDir, "snap-a", hA, true);

        // 快照 B: 引用一个 store 里没有的 hash -> INCOMPLETE
        Hash missing = hashFn.hash(randomBytes(100, 4));
        writeManifest(snapshotsDir, "snap-b", missing, true);

        // 快照 C: manifest 文件是垃圾 (非合法 gzip NBT) -> CORRUPT
        Files.write(snapshotsDir.resolve("snap-c.manifest"), "not a valid manifest".getBytes(StandardCharsets.UTF_8));

        StoreFsck fsck = new StoreFsck(store, snapshotsDir, hashFn);
        StoreFsck.RebuildResult rebuild = fsck.rebuildIndex();

        Map<String, StoreFsck.SnapshotEntry> byId = new HashMap<>();
        for (StoreFsck.SnapshotEntry e : rebuild.entries()) {
            byId.put(e.id(), e);
        }
        assertEquals(3, byId.size());

        StoreFsck.SnapshotEntry a = byId.get("snap-a");
        assertTrue(a.restorable(), "snap-a references only present objects -> restorable");
        assertEquals(0, a.missingObjects());

        StoreFsck.SnapshotEntry b = byId.get("snap-b");
        assertFalse(b.restorable(), "snap-b references a missing object -> not restorable");
        assertEquals(1, b.missingObjects());

        StoreFsck.SnapshotEntry c = byId.get("snap-c");
        assertTrue(c.corrupt(), "snap-c manifest is garbage -> corrupt");
        assertFalse(c.restorable());

        // 索引文件落盘且内容反映三个状态
        Path index = snapshotsDir.resolve(StoreFsck.INDEX_FILE_NAME);
        assertTrue(Files.isRegularFile(index), "index file must be written");
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("snap-a OK")), "snap-a -> OK line");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("snap-b INCOMPLETE")), "snap-b -> INCOMPLETE line");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("snap-c CORRUPT")), "snap-c -> CORRUPT line");
    }

    /** 写一个仅含 levelDat 引用的 manifest (供 rebuild-index 测试: levelDat 即被引用对象)。 */
    private static void writeManifest(Path snapshotsDir, String id, Hash levelDatHash, boolean baselineComplete)
            throws IOException {
        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, id, System.currentTimeMillis(), 0L,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), levelDatHash, 0L, 0L,
                baselineComplete, FileManifest.empty());
        m.writeTo(snapshotsDir.resolve(id + ".manifest"));
    }

    /** 写一个把给定 hash 挂在 chunks 段的 manifest, 使该对象被分类为 chunk payload (走压缩校验)。 */
    private static void writeChunkManifest(Path snapshotsDir, String id, Hash chunkHash) throws IOException {
        Map<Long, Hash> chunkSlots = new HashMap<>();
        chunkSlots.put(ChunkPosCodec.asLong(0, 0), chunkHash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, chunkSlots);
        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, id, System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, FileManifest.empty());
        m.writeTo(snapshotsDir.resolve(id + ".manifest"));
    }

    /**
     * 构造一个 poi 风格的 .mca 整文件字节: vanilla region 文件头是 4KiB 的 chunk 位置表 +
     * 4KiB 的时间戳表。poi 区域绝大多数 slot 为空时, 位置表开头全是 0x00, 整文件首字节即 0x00。
     * 这正是被旧 fsck 误判 "invalid compression type 0" 的形态。尾部塞随机字节让对象非全零。
     */
    private static byte[] mcaWholeFileBytes(int tailRandom, long seed) {
        byte[] out = new byte[4096 + tailRandom];
        byte[] tail = randomBytes(tailRandom, seed);
        System.arraycopy(tail, 0, out, 4096, tailRandom);
        // out[0..4095] 默认 0x00 (Java 数组初始化), 即稀疏位置表头, out[0] == 0x00。
        return out;
    }

    /** 真实 gzip 压缩字节 (魔数 0x1f 0x8b), 复现 playerdata/level.dat/SavedData 的整文件形态。 */
    private static byte[] gzipBytes(byte[] plaintext) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(plaintext);
        }
        return bos.toByteArray();
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
