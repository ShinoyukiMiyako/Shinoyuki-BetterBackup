package com.shinoyuki.betterbackup.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void put_atomic_no_orphan_tmp_after_normal_completion(@TempDir Path tempDir) throws IOException {
        ChunkStore store = new ChunkStore(tempDir.resolve("backup-store"));
        store.initialize();
        Hash hash = Hash.fromHex("22222222222222222222222222222222");
        store.put(hash, new byte[]{1});

        // .tmp 文件不应该残留
        Path target = store.pathFor(hash);
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".tmp")));
        assertTrue(Files.exists(target));
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
