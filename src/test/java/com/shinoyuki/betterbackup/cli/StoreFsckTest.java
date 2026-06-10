package com.shinoyuki.betterbackup.cli;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN Phase E commit 12/13: store 扫描校验 (重 hash 对比文件名 + zlib 完整性) 与从 store +
 * manifests 重建快照索引。
 *
 * <p>判定标准: 把 {@link StoreFsck#verifyStore} 里的重 hash 比对删掉, {@code detects_hash_mismatch}
 * 必挂 (篡改字节后文件名仍是旧 hash, 不重 hash 就查不出)。把 zlib 校验删掉,
 * {@code detects_zlib_corruption} 必挂。
 */
class StoreFsckTest {

    @Test
    void verify_passes_clean_store(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        byte[] obj = ChunkPayloadFixtures.zlibPayload(randomBytes(3000, 1));
        store.put(hashFn.hash(obj), obj);
        byte[] obj2 = ChunkPayloadFixtures.zlibPayload(randomBytes(1500, 2));
        store.put(hashFn.hash(obj2), obj2);

        StoreFsck fsck = new StoreFsck(store, storeRoot.resolve("snapshots"), hashFn);
        StoreFsck.VerifyResult r = fsck.verifyStore();
        assertEquals(2, r.scanned());
        assertEquals(2, r.ok());
        assertTrue(r.clean(), "clean store must verify clean");
    }

    @Test
    void detects_hash_mismatch_after_content_tamper(@TempDir Path root) throws IOException {
        Path storeRoot = root.resolve("store");
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        byte[] obj = ChunkPayloadFixtures.zlibPayload(randomBytes(3000, 7));
        Hash h = hashFn.hash(obj);
        store.put(h, obj);

        // 篡改字节但保留文件名 (模拟位翻转 / 外部改写): 内容 hash != 文件名 hash
        Path stored = store.pathFor(h);
        byte[] tampered = obj.clone();
        tampered[tampered.length - 1] ^= 0x5A;
        Files.write(stored, tampered);

        StoreFsck fsck = new StoreFsck(store, storeRoot.resolve("snapshots"), hashFn);
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

        StoreFsck fsck = new StoreFsck(store, storeRoot.resolve("snapshots"), hashFn);
        StoreFsck.VerifyResult r = fsck.verifyStore();
        assertEquals(1, r.scanned());
        assertEquals(0, r.ok(), "corrupt zlib must not count as ok");
        assertEquals(0, r.hashMismatch().size(), "hash is self-consistent, must NOT be a hash mismatch");
        assertEquals(1, r.corrupt().size(), "truncated zlib stream must be flagged corrupt");
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

    private static void writeManifest(Path snapshotsDir, String id, Hash levelDatHash, boolean baselineComplete)
            throws IOException {
        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, id, System.currentTimeMillis(), 0L,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), levelDatHash, 0L, 0L,
                baselineComplete, FileManifest.empty());
        m.writeTo(snapshotsDir.resolve(id + ".manifest"));
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
