package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PackStore 核心契约: 内容寻址 put/get/dedup, 重开后由 pack 顺序扫重建索引, 封口换 pack,
 * torn tail 截断, store.meta 头校验。
 *
 * <p>判定标准: 把 {@link PackStore#load} 的 pack 扫描删掉, {@code survives_reopen} 必挂
 * (重开后 get 不到对象); 把 torn-tail 截断删掉, {@code torn_tail} 必挂 (脏字节残留)。
 */
class PackStoreTest {

    private static final int HASH_LEN = 16; // xxh128

    private final Xxh128HashFunction hashFn = new Xxh128HashFunction();

    @Test
    void put_get_round_trips_and_dedups(@TempDir Path root) throws IOException {
        PackStore store = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        store.initialize();

        byte[] a = randomBytes(4000, 1);
        byte[] b = randomBytes(50, 2);
        Hash ha = hashFn.hash(a);
        Hash hb = hashFn.hash(b);

        assertTrue(store.put(ha, a), "first put of a is a new write");
        assertTrue(store.put(hb, b), "first put of b is a new write");
        assertFalse(store.put(ha, a), "second put of a is a dedup hit (no new write)");
        assertEquals(2, store.objectCount(), "dedup must not grow object count");

        assertArrayEquals(a, store.get(ha));
        assertArrayEquals(b, store.get(hb));
        assertTrue(store.has(ha));
        store.close();
    }

    @Test
    void get_absent_throws_and_has_is_false(@TempDir Path root) throws IOException {
        PackStore store = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        store.initialize();
        Hash missing = hashFn.hash(randomBytes(10, 7));
        assertFalse(store.has(missing));
        assertThrows(NoSuchFileException.class, () -> store.get(missing));
        store.close();
    }

    @Test
    void survives_reopen_rebuilding_index_from_packs(@TempDir Path root) throws IOException {
        List<byte[]> objects = new ArrayList<>();
        List<Hash> hashes = new ArrayList<>();
        PackStore store = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        store.initialize();
        for (int i = 0; i < 20; i++) {
            byte[] o = randomBytes(300 + i, 100 + i);
            Hash h = hashFn.hash(o);
            store.put(h, o);
            objects.add(o);
            hashes.add(h);
        }
        store.flushAndSync();
        store.close();

        // 全新实例, 只靠 pack 顺序扫重建索引
        PackStore reopened = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        reopened.initialize();
        assertEquals(20, reopened.objectCount(), "all objects must be recovered by pack scan");
        for (int i = 0; i < objects.size(); i++) {
            assertArrayEquals(objects.get(i), reopened.get(hashes.get(i)),
                    "object " + i + " must round-trip byte-exact after reopen");
        }
        reopened.close();
    }

    @Test
    void rolls_packs_at_target_size(@TempDir Path root) throws IOException {
        // 小封口: 每个对象 ~500B, 封口 1200B -> 多个 pack
        PackStore store = new PackStore(root, HASH_LEN, 1200);
        store.initialize();
        List<byte[]> objects = new ArrayList<>();
        List<Hash> hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] o = randomBytes(500, 200 + i);
            Hash h = hashFn.hash(o);
            store.put(h, o);
            objects.add(o);
            hashes.add(h);
        }
        store.flushAndSync();
        store.close();

        long packCount;
        try (Stream<Path> s = Files.list(root.resolve("packs"))) {
            packCount = s.filter(p -> p.getFileName().toString().endsWith(".pack")).count();
        }
        assertTrue(packCount > 1, "expected packs to roll into multiple files, got " + packCount);

        PackStore reopened = new PackStore(root, HASH_LEN, 1200);
        reopened.initialize();
        assertEquals(10, reopened.objectCount());
        for (int i = 0; i < objects.size(); i++) {
            assertArrayEquals(objects.get(i), reopened.get(hashes.get(i)),
                    "object " + i + " across rolled packs must round-trip");
        }
        reopened.close();
    }

    @Test
    void torn_tail_is_truncated_on_load(@TempDir Path root) throws IOException {
        PackStore store = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        store.initialize();
        byte[] a = randomBytes(400, 1);
        byte[] b = randomBytes(400, 2);
        Hash ha = hashFn.hash(a);
        Hash hb = hashFn.hash(b);
        store.put(ha, a);
        store.put(hb, b);
        store.flushAndSync();
        store.close();

        Path pack = root.resolve("packs").resolve("0000000000.pack");
        long validSize = Files.size(pack);

        // 追加一条 torn 记录: 完整 header (16B hash + 4B 长度=99999) 但无数据 -> 越界, load 应截断
        java.nio.ByteBuffer torn = java.nio.ByteBuffer.allocate(HASH_LEN + 4);
        torn.put(new byte[HASH_LEN]);
        torn.putInt(99999);
        Files.write(pack, torn.array(), java.nio.file.StandardOpenOption.APPEND);
        assertTrue(Files.size(pack) > validSize, "precondition: torn bytes appended");

        PackStore reopened = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        reopened.initialize();
        assertEquals(2, reopened.objectCount(), "torn tail must not yield a phantom object");
        assertArrayEquals(a, reopened.get(ha), "valid records before torn tail must survive");
        assertArrayEquals(b, reopened.get(hb));
        assertEquals(validSize, Files.size(pack), "pack must be truncated back to last valid record boundary");
        reopened.close();
    }

    @Test
    void meta_rejects_hash_length_mismatch(@TempDir Path root) throws IOException {
        PackStore store = new PackStore(root, 16, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        store.initialize();
        store.close();

        PackStore wrongLen = new PackStore(root, 32, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        assertThrows(IOException.class, wrongLen::initialize,
                "opening an existing store with a different hash length must be rejected");
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
