package com.shinoyuki.betterbackup.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkStoreTest {

    @Test
    void initialize_creates_chunks_directory(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        assertTrue(Files.isDirectory(store.chunksDir()));
    }

    @Test
    void path_for_uses_two_level_bucket(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Hash hash = Hash.fromHex("0012abcdef0123456789abcdef012345");
        Path expected = store.chunksDir().resolve("00").resolve("0012ab")
                .resolve("0012abcdef0123456789abcdef012345");
        assertEquals(expected, store.pathFor(hash));
    }

    @Test
    void put_then_has_then_get_round_trip(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Hash hash = Hash.fromHex("aabbccddeeff00112233445566778899");
        byte[] data = new byte[]{1, 2, 3, 4, 5};

        assertFalse(store.has(hash));
        boolean wrote = store.put(hash, data);
        assertTrue(wrote, "first put returns true");
        assertTrue(store.has(hash));
        assertArrayEquals(data, store.get(hash));
    }

    @Test
    void put_dedup_returns_false_when_hash_exists(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Hash hash = Hash.fromHex("11111111111111111111111111111111");
        byte[] data = new byte[]{42};

        assertTrue(store.put(hash, data));
        assertFalse(store.put(hash, data), "second put with same hash returns false (dedup hit)");
        assertFalse(store.put(hash, new byte[]{99}), "even with different bytes (won't be checked)");
        assertArrayEquals(data, store.get(hash), "store keeps first data, ignores subsequent");
    }

    @Test
    void put_persists_object_without_leaving_tmp(@TempDir Path tempDir) throws IOException {
        Path storeRoot = tempDir.resolve("backup-store");
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Hash hash = Hash.fromHex("22222222222222222222222222222222");
        byte[] data = {1, 2, 3};
        assertTrue(store.put(hash, data));

        // 对象可读回, 且 store 目录树里不残留任何 .tmp (pack append 无 per-object tmp)
        assertArrayEquals(data, store.get(hash));
        try (Stream<Path> walk = Files.walk(storeRoot)) {
            assertFalse(walk.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "no orphan .tmp anywhere under the store after a normal put");
        }
    }

    @Test
    void cleanup_orphan_tmp_files_removes_dot_tmp(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        // 模拟 kill -9 留下的孤儿 .tmp
        Path bucket = store.chunksDir().resolve("ab").resolve("abcdef");
        Files.createDirectories(bucket);
        Path orphanTmp = bucket.resolve("abcdef0000000000000000000000000.tmp");
        Files.write(orphanTmp, new byte[]{0});
        assertTrue(Files.exists(orphanTmp));

        int cleaned = store.cleanupOrphanTmpFiles();
        assertEquals(1, cleaned);
        assertFalse(Files.exists(orphanTmp));
    }

    @Test
    void cleanup_orphan_tmp_files_leaves_real_entries_alone(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Hash hash = Hash.fromHex("33333333333333333333333333333333");
        store.put(hash, new byte[]{7});

        store.cleanupOrphanTmpFiles();
        // 真实 entry 不动
        assertTrue(store.has(hash));
    }

    @Test
    void cleanup_with_cutoff_deletes_old_orphan_keeps_fresh_inflight(@TempDir Path tempDir) throws IOException {
        // 在线后台清扫的 race 根除: cutoff=本进程启动时刻, 只清上次运行崩溃残留的孤儿,
        // 绝不碰本次运行 worker 正在写的在途 .tmp。
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path bucket = store.chunksDir().resolve("ab").resolve("abcdef");
        Files.createDirectories(bucket);

        // 上次运行崩溃残留的孤儿: mtime 远早于 cutoff
        Path oldOrphan = bucket.resolve("abcdef0000000000000000000000001.tmp");
        Files.write(oldOrphan, new byte[]{0});
        Files.setLastModifiedTime(oldOrphan, FileTime.fromMillis(1_000L));

        // 本次运行 worker 正在写的在途 .tmp: mtime 晚于 cutoff
        Path freshInflight = bucket.resolve("abcdef0000000000000000000000002.tmp");
        Files.write(freshInflight, new byte[]{0});
        Files.setLastModifiedTime(freshInflight, FileTime.fromMillis(9_000_000_000_000L));

        int cleaned = store.cleanupOrphanTmpFiles(1_000_000L); // cutoff 介于两者之间

        assertEquals(1, cleaned, "只删早于 cutoff 的上次运行孤儿");
        assertFalse(Files.exists(oldOrphan), "上次运行的孤儿 .tmp 被清");
        assertTrue(Files.exists(freshInflight),
                "本次运行在途 .tmp 必须保留 —— 绝不能误删 worker 正在写的 .tmp (race 根除)");
    }

    @Test
    void cleanup_no_arg_deletes_all_tmp_regardless_of_mtime(@TempDir Path tempDir) throws IOException {
        // 无参重载 = 全删 (Long.MAX_VALUE), 离线 CLI / fsck 用 (无并发 writer): mtime 不设防。
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Path bucket = store.chunksDir().resolve("cd").resolve("cdef01");
        Files.createDirectories(bucket);
        Path freshTmp = bucket.resolve("cdef010000000000000000000000001.tmp");
        Files.write(freshTmp, new byte[]{0});
        Files.setLastModifiedTime(freshTmp, FileTime.fromMillis(9_000_000_000_000L));

        int cleaned = store.cleanupOrphanTmpFiles();

        assertEquals(1, cleaned, "无参全删不看 mtime");
        assertFalse(Files.exists(freshTmp));
    }

    @Test
    void short_hash_rejected_for_path(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        // 5 hex chars = 2.5 bytes, 凑不出 6 hex 二级分桶
        Hash tooShort = new Hash(new byte[]{0x01, 0x02});
        // 4 hex 不够 6 长
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.pathFor(tooShort));
    }
}
