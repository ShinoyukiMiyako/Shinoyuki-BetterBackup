package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.store.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MmapPackIndex 与 InMemoryPackIndex 的<b>契约等价</b>: 同一组 put/remove/relocate/checkpoint
 * 序列同时喂给两者, 每一步对全体探针 hash 断言 get/contains/size 完全一致。InMemory 已被 PackStore
 * 全套 187 测试间接证明正确, 故它是 mmap 实现的 oracle —— 任何 base/delta/tombstone/归并 的 bug
 * 都会让某个探针在某一步偏离 oracle 而挂。外加持久化往返与指纹失配拒载两条专项。
 */
class MmapPackIndexTest {

    private static final int HASH_LEN = 16;

    @Test
    void mmap_matches_in_memory_through_puts_removes_relocates_and_checkpoints(@TempDir Path root)
            throws IOException {
        PackIndex oracle = new InMemoryPackIndex();
        MmapPackIndex subject = new MmapPackIndex(root.resolve("index"), HASH_LEN);

        List<Hash> probes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            probes.add(hash(i));
        }
        for (int i = 1000; i < 1005; i++) {
            probes.add(hash(i)); // 一直不存在的探针
        }

        // 1. put 50 个
        for (int i = 0; i < 50; i++) {
            put(oracle, subject, i, i);
        }
        assertEquivalent(oracle, subject, probes);
        assertEquals(50, subject.size());

        // 2. checkpoint -> 全进 base, delta 清空
        checkpoint(oracle, subject, 111L);
        assertTrue(Files.isRegularFile(root.resolve("index").resolve("base.idx")), "base.idx must be written");
        assertEquivalent(oracle, subject, probes);

        // 3. 删 10 个 (打 base 里的对象 -> tombstone)
        for (int i = 0; i < 10; i++) {
            remove(oracle, subject, i);
        }
        assertEquivalent(oracle, subject, probes);
        assertEquals(40, subject.size());

        // 4. put 5 个全新 (delta 新增) + 重定位 5 个已存在 (base 被 delta 影子覆盖, size 不变)
        for (int i = 50; i < 55; i++) {
            put(oracle, subject, i, i);
        }
        for (int i = 20; i < 25; i++) {
            put(oracle, subject, i, i + 1000); // 同 hash 不同 location
        }
        assertEquivalent(oracle, subject, probes);
        assertEquals(45, subject.size());

        // 5. checkpoint 归并 (base 去 tombstone/影子 + delta) -> 再次全等价
        checkpoint(oracle, subject, 222L);
        assertEquivalent(oracle, subject, probes);
        assertEquals(45, subject.size());

        // 6. 重定位后的对象必须读出新 location
        assertEquals(loc(1020), subject.get(hash(20)), "relocated object must read the new location");
        assertEquals(loc(1024), subject.get(hash(24)));
        assertEquals(loc(11), subject.get(hash(11)), "untouched base object keeps its original location");

        subject.close();
    }

    @Test
    void checkpoint_persists_and_reloads_when_fingerprint_matches(@TempDir Path root) throws IOException {
        Path indexDir = root.resolve("index");
        MmapPackIndex first = new MmapPackIndex(indexDir, HASH_LEN);
        for (int i = 0; i < 30; i++) {
            first.put(hash(i), loc(i));
        }
        first.remove(hash(5));
        first.checkpoint(777L);
        first.close();

        MmapPackIndex reopened = new MmapPackIndex(indexDir, HASH_LEN);
        assertTrue(reopened.tryLoad(777L), "matching fingerprint must load from base.idx");
        assertEquals(29, reopened.size(), "removed object must stay gone after reload");
        assertFalse(reopened.contains(hash(5)), "removed object must not reappear");
        for (int i = 0; i < 30; i++) {
            if (i == 5) {
                continue;
            }
            assertEquals(loc(i), reopened.get(hash(i)), "object " + i + " must round-trip through base.idx");
        }
        reopened.close();
    }

    @Test
    void try_load_rejects_stale_fingerprint(@TempDir Path root) throws IOException {
        Path indexDir = root.resolve("index");
        MmapPackIndex first = new MmapPackIndex(indexDir, HASH_LEN);
        for (int i = 0; i < 10; i++) {
            first.put(hash(i), loc(i));
        }
        first.checkpoint(100L);
        first.close();

        MmapPackIndex reopened = new MmapPackIndex(indexDir, HASH_LEN);
        assertFalse(reopened.tryLoad(200L), "stale fingerprint (pack set changed) must NOT load");
        assertEquals(0, reopened.size(), "rejected load must leave the index empty (PackStore will rebuild)");
        reopened.close();
    }

    @Test
    void try_load_false_when_no_base_file(@TempDir Path root) throws IOException {
        MmapPackIndex idx = new MmapPackIndex(root.resolve("index"), HASH_LEN);
        assertFalse(idx.tryLoad(1L), "no base.idx -> tryLoad false");
        assertEquals(0, idx.size());
        idx.close();
    }

    // ---- helpers ----

    private static void put(PackIndex oracle, PackIndex subject, int hashSeed, int locSeed) {
        oracle.put(hash(hashSeed), loc(locSeed));
        subject.put(hash(hashSeed), loc(locSeed));
    }

    private static void remove(PackIndex oracle, PackIndex subject, int hashSeed) {
        oracle.remove(hash(hashSeed));
        subject.remove(hash(hashSeed));
    }

    private static void checkpoint(PackIndex oracle, PackIndex subject, long fingerprint) throws IOException {
        oracle.checkpoint(fingerprint);
        subject.checkpoint(fingerprint);
    }

    private static void assertEquivalent(PackIndex oracle, PackIndex subject, List<Hash> probes) {
        assertEquals(oracle.size(), subject.size(), "size must match oracle");
        for (Hash h : probes) {
            assertEquals(oracle.contains(h), subject.contains(h), "contains mismatch for " + h);
            assertEquals(oracle.get(h), subject.get(h), "location mismatch for " + h);
        }
    }

    private static Hash hash(int seed) {
        byte[] b = new byte[HASH_LEN];
        new Random(seed * 2654435761L).nextBytes(b);
        return new Hash(b);
    }

    private static PackLocation loc(int seed) {
        return new PackLocation(seed % 5, seed * 100L, seed + 1);
    }
}
