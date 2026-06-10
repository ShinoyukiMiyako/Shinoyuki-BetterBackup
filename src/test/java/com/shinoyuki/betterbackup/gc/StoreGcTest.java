package com.shinoyuki.betterbackup.gc;

import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreGcTest {

    /**
     * 生成确定性 16-byte hash, seed 不同则 hash 不同. 用于测试构造.
     */
    private static Hash hash(int seed) {
        byte[] b = new byte[16];
        b[0] = (byte) (seed & 0xFF);
        b[1] = (byte) ((seed >> 8) & 0xFF);
        b[2] = (byte) ((seed >> 16) & 0xFF);
        b[3] = (byte) ((seed >> 24) & 0xFF);
        // 后面填非零, 避免误判 / 让 hex 可读
        for (int i = 4; i < 16; i++) {
            b[i] = (byte) ((seed * (i + 31)) & 0xFF);
        }
        return new Hash(b);
    }

    /**
     * 给定字节内容固定模式, 长度由 seed 决定 (8 + seed % 8 byte), 写真 store
     * 让 GC 可以 Files.size + delete.
     */
    private static byte[] payload(int seed) {
        byte[] b = new byte[8 + (Math.abs(seed) % 8)];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ((seed + i) & 0xFF);
        }
        return b;
    }

    /**
     * 写一个引用了若干 hash 的最小 manifest 到 snapshotsDir/<id>.manifest.
     */
    private static Path writeManifest(Path snapshotsDir, String id, Set<Hash> referenced)
            throws IOException {
        Map<Long, Hash> chunkMap = new HashMap<>();
        long pos = 0;
        for (Hash h : referenced) {
            chunkMap.put(pos++, h);
        }
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put("minecraft:overworld", chunkMap);

        SnapshotManifest m = new SnapshotManifest(
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
                com.shinoyuki.betterbackup.snapshot.FileManifest.empty());
        Path target = snapshotsDir.resolve(id + ".manifest");
        m.writeTo(target);
        return target;
    }

    @Test
    void gc_all_empty_store_and_no_snapshots_returns_zero(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult r = gc.gcAll();

        assertEquals(0, r.scanned());
        assertEquals(0, r.retained());
        assertEquals(0, r.deleted());
        assertEquals(0, r.bytesFreed());
    }

    @Test
    void gc_all_retains_all_when_every_hash_referenced(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        // 写 100 个 hash 进 store
        Set<Hash> all = new HashSet<>();
        for (int i = 1; i <= 100; i++) {
            Hash h = hash(i);
            store.put(h, payload(i));
            all.add(h);
        }
        // manifest 引用全部 100 个
        writeManifest(snapshotsDir, "snap-1", all);

        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult r = gc.gcAll();

        assertEquals(100, r.scanned());
        assertEquals(100, r.retained());
        assertEquals(0, r.deleted());
        assertEquals(0, r.bytesFreed());
        // 物理验证: 全部还在
        for (Hash h : all) {
            assertTrue(store.has(h), "hash " + h + " should still exist");
        }
    }

    @Test
    void gc_all_deletes_unreferenced_and_reports_bytes(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        // store 有 100 个 hash, manifest 仅引用前 50 个
        Set<Hash> referenced = new HashSet<>();
        long expectedBytesFreed = 0;
        for (int i = 1; i <= 100; i++) {
            Hash h = hash(i);
            byte[] data = payload(i);
            store.put(h, data);
            if (i <= 50) {
                referenced.add(h);
            } else {
                expectedBytesFreed += data.length;
            }
        }
        writeManifest(snapshotsDir, "snap-1", referenced);

        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult r = gc.gcAll();

        assertEquals(100, r.scanned());
        assertEquals(50, r.retained());
        assertEquals(50, r.deleted());
        assertEquals(expectedBytesFreed, r.bytesFreed());
        // 物理验证: 引用的还在, 未引用的真删了
        for (int i = 1; i <= 100; i++) {
            Hash h = hash(i);
            if (i <= 50) {
                assertTrue(store.has(h), "referenced hash " + i + " should survive");
            } else {
                assertFalse(store.has(h), "unreferenced hash " + i + " should be deleted");
            }
        }
    }

    @Test
    void gc_all_dedups_referenced_hashes_across_manifests(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        // 10 个 hash 全写入 store
        for (int i = 1; i <= 10; i++) {
            store.put(hash(i), payload(i));
        }

        // manifest A 引用 1..7, manifest B 引用 4..10, 共享 4..7
        Set<Hash> setA = new HashSet<>();
        for (int i = 1; i <= 7; i++) setA.add(hash(i));
        Set<Hash> setB = new HashSet<>();
        for (int i = 4; i <= 10; i++) setB.add(hash(i));
        writeManifest(snapshotsDir, "snap-A", setA);
        writeManifest(snapshotsDir, "snap-B", setB);

        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult r = gc.gcAll();

        // referenced 集合 = 1..10 共 10, 共享 hash 不重算
        assertEquals(10, r.scanned());
        assertEquals(10, r.retained());
        assertEquals(0, r.deleted());
        // 共享 hash 物理只一份, 不被 GC 误删
        for (int i = 1; i <= 10; i++) {
            assertTrue(store.has(hash(i)));
        }
    }

    @Test
    void gc_incremental_deletes_written_minus_referenced(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        // 模拟一个 BackupWorker 窗口写了 10 个 hash, 但 manifest 只引用其中 3 个
        Set<Hash> written = new HashSet<>();
        long expectedBytesFreed = 0;
        for (int i = 1; i <= 10; i++) {
            Hash h = hash(i);
            byte[] data = payload(i);
            store.put(h, data);
            written.add(h);
            if (i > 3) {
                // 后 7 个是中间版本, 应该被增量 GC 清掉
                expectedBytesFreed += data.length;
            }
        }
        Set<Hash> referenced = new HashSet<>();
        for (int i = 1; i <= 3; i++) referenced.add(hash(i));

        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult r = gc.gcIncremental(written, referenced);

        assertEquals(7, r.scanned(), "7 candidates = written(10) - referenced(3)");
        assertEquals(0, r.retained());
        assertEquals(7, r.deleted());
        assertEquals(expectedBytesFreed, r.bytesFreed());
        // 物理: 引用的留, 未引用的删
        for (int i = 1; i <= 3; i++) {
            assertTrue(store.has(hash(i)), "referenced hash " + i + " kept");
        }
        for (int i = 4; i <= 10; i++) {
            assertFalse(store.has(hash(i)), "unreferenced hash " + i + " deleted");
        }
    }

    @Test
    void gc_incremental_no_op_when_written_subset_of_referenced(@TempDir Path tempDir)
            throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        // written ⊆ referenced: 全部都被 manifest 引用, 无中间孤儿
        Set<Hash> written = new HashSet<>();
        Set<Hash> referenced = new HashSet<>();
        for (int i = 1; i <= 5; i++) {
            Hash h = hash(i);
            store.put(h, payload(i));
            written.add(h);
            referenced.add(h);
        }
        // referenced 还多包含一些不在 written 里的 hash (例如此前 snapshot 引用的)
        for (int i = 100; i <= 110; i++) {
            referenced.add(hash(i));
        }

        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult r = gc.gcIncremental(written, referenced);

        assertEquals(0, r.scanned());
        assertEquals(0, r.retained());
        assertEquals(0, r.deleted());
        assertEquals(0, r.bytesFreed());
        // 物理: 全部 written hash 还在 store
        for (int i = 1; i <= 5; i++) {
            assertTrue(store.has(hash(i)));
        }
    }

    @Test
    void gc_incremental_tolerates_already_missing_files(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        // 5 个 candidate, 其中 2 个真存在 store, 3 个不存在 (例如已被别处删 / 写失败)
        Set<Hash> written = new HashSet<>();
        long expectedBytes = 0;
        for (int i = 1; i <= 5; i++) {
            Hash h = hash(i);
            written.add(h);
            if (i <= 2) {
                byte[] data = payload(i);
                store.put(h, data);
                expectedBytes += data.length;
            }
            // i >= 3: written 集合里有, 但 store 里没文件
        }
        Set<Hash> referenced = new HashSet<>(); // 空, 全部 candidate

        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult r = gc.gcIncremental(written, referenced);

        // candidates 数永远等于 written - referenced, 跟物理是否存在无关
        assertEquals(5, r.scanned());
        assertEquals(0, r.retained());
        // deleted 只反映实际删了几个物理文件
        assertEquals(2, r.deleted());
        assertEquals(expectedBytes, r.bytesFreed());
    }

    @Test
    void gc_all_aborts_and_keeps_all_files_when_manifest_corrupt(@TempDir Path tempDir)
            throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        // 写 5 个 hash 进 store
        Set<Hash> all = new HashSet<>();
        for (int i = 1; i <= 5; i++) {
            Hash h = hash(i);
            store.put(h, payload(i));
            all.add(h);
        }
        // 一个完好 manifest 引用 1..3
        Set<Hash> goodRef = new HashSet<>();
        for (int i = 1; i <= 3; i++) goodRef.add(hash(i));
        writeManifest(snapshotsDir, "good", goodRef);

        // 一个损坏 manifest: 写非法字节, NbtIo.readCompressed 会失败
        Path corrupt = snapshotsDir.resolve("corrupt.manifest");
        Files.write(corrupt, new byte[]{0x00, 0x01, 0x02, 0x03, 0x04});

        StoreGc gc = new StoreGc(store, snapshotsDir);
        // gcAll 必须 abort, 抛 IOException
        assertThrows(IOException.class, gc::gcAll);

        // 关键不变量: 任何文件都没被删, 包括"看起来 unreferenced"的 hash 4 和 5
        // 原因: 损坏 manifest 可能引用 4 或 5, 我们无法证明可以安全删除
        for (Hash h : all) {
            assertTrue(store.has(h),
                    "hash " + h + " must NOT be deleted when GC aborts on corrupt manifest");
        }
    }
}
