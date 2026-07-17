package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.store.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

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

        // 2. checkpoint -> 全进 base, delta 清空 (清单覆盖 loc(seed) 用到的 packId 0..4)
        checkpoint(oracle, subject, packs(0, 100, 1, 100, 2, 100, 3, 100, 4, 100));
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
        checkpoint(oracle, subject, packs(0, 100, 1, 100, 2, 100, 3, 100, 4, 100));
        assertEquivalent(oracle, subject, probes);
        assertEquals(45, subject.size());

        // 6. 重定位后的对象必须读出新 location
        assertEquals(loc(1020), subject.get(hash(20)), "relocated object must read the new location");
        assertEquals(loc(1024), subject.get(hash(24)));
        assertEquals(loc(11), subject.get(hash(11)), "untouched base object keeps its original location");

        subject.close();
    }

    @Test
    void checkpoint_persists_and_reloads_when_manifest_matches(@TempDir Path root) throws IOException {
        Path indexDir = root.resolve("index");
        SortedMap<Integer, Long> manifest = packs(0, 100, 1, 100, 2, 100, 3, 100, 4, 100);
        MmapPackIndex first = new MmapPackIndex(indexDir, HASH_LEN);
        for (int i = 0; i < 30; i++) {
            first.put(hash(i), loc(i));
        }
        first.remove(hash(5));
        first.checkpoint(manifest);
        first.close();

        MmapPackIndex reopened = new MmapPackIndex(indexDir, HASH_LEN);
        assertEquals(Set.of(), reopened.tryLoad(manifest),
                "identical per-pack manifest must load with zero packs to rescan");
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
    void try_load_partial_keeps_unchanged_packs_and_returns_changed(@TempDir Path root) throws IOException {
        Path indexDir = root.resolve("index");
        MmapPackIndex first = new MmapPackIndex(indexDir, HASH_LEN);
        // 30 个对象显式分布在 pack 0/1/2 (loc(seed) 的 packId = seed % 5, 取 seed<15 保证只落 0..4;
        // 这里改用显式 location 保证分布可控).
        for (int i = 0; i < 30; i++) {
            first.put(hash(i), new PackLocation(i % 3, i * 100L, i + 1));
        }
        first.checkpoint(packs(0, 1000, 1, 2000, 2, 3000));
        first.close();

        // pack 1 尺寸变化 (崩溃恢复典型场景), pack 3 新增, pack 0/2 未变.
        MmapPackIndex reopened = new MmapPackIndex(indexDir, HASH_LEN);
        Set<Integer> toScan = reopened.tryLoad(packs(0, 1000, 1, 2500, 2, 3000, 3, 500));
        assertEquals(Set.of(1, 3), toScan, "only the size-changed and the new pack need rescanning");
        assertEquals(20, reopened.size(), "entries of unchanged packs 0/2 must be retained");
        for (int i = 0; i < 30; i++) {
            if (i % 3 == 1) {
                assertFalse(reopened.contains(hash(i)), "changed-pack entry " + i + " must be dropped for rescan");
            } else {
                assertEquals(new PackLocation(i % 3, i * 100L, i + 1), reopened.get(hash(i)),
                        "unchanged-pack entry " + i + " must survive the partial load");
            }
        }
        reopened.close();

        // 消失 pack: 清单只剩 pack 0 -> pack 1/2 条目全部丢弃, 无需重扫任何 pack.
        MmapPackIndex gone = new MmapPackIndex(indexDir, HASH_LEN);
        assertEquals(Set.of(), gone.tryLoad(packs(0, 1000)),
                "vanished packs need no rescan, their entries just drop");
        assertEquals(10, gone.size(), "only pack-0 entries survive when packs 1/2 vanished");
        for (int i = 0; i < 30; i++) {
            assertEquals(i % 3 == 0, gone.contains(hash(i)));
        }
        gone.close();
    }

    @Test
    void try_load_full_rescan_when_no_base_file_or_v1_sidecar(@TempDir Path root) throws IOException {
        // 无 base.idx -> 全量重扫.
        MmapPackIndex idx = new MmapPackIndex(root.resolve("index"), HASH_LEN);
        assertEquals(Set.of(7, 9), idx.tryLoad(packs(7, 100, 9, 200)), "no base.idx -> rescan everything");
        assertEquals(0, idx.size());
        idx.close();

        // 手写 v1 sidecar (BBIDX1 魔数 + 单 long 指纹, count=0): v2 代码必须整体拒用并退化为
        // 全量重扫, 不崩溃; 随后 checkpoint 升格 v2, 再 tryLoad 即全命中.
        Path v1Dir = root.resolve("index-v1");
        Files.createDirectories(v1Dir);
        ByteBuffer v1 = ByteBuffer.allocate(32);
        v1.put("BBIDX1\0\0".getBytes(StandardCharsets.US_ASCII));
        v1.putLong(12345L);
        v1.putInt(0);
        v1.putInt(HASH_LEN);
        v1.putInt(0);
        v1.putInt(0);
        Files.write(v1Dir.resolve("base.idx"), v1.array());

        MmapPackIndex legacy = new MmapPackIndex(v1Dir, HASH_LEN);
        assertEquals(Set.of(0), legacy.tryLoad(packs(0, 100)), "v1 sidecar -> one-time full rescan");
        legacy.put(hash(1), new PackLocation(0, 20L, 30));
        legacy.checkpoint(packs(0, 100));
        legacy.close();

        MmapPackIndex upgraded = new MmapPackIndex(v1Dir, HASH_LEN);
        assertEquals(Set.of(), upgraded.tryLoad(packs(0, 100)), "after v2 upgrade the reload is a full hit");
        assertEquals(new PackLocation(0, 20L, 30), upgraded.get(hash(1)));
        upgraded.close();
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

    private static void checkpoint(PackIndex oracle, PackIndex subject, SortedMap<Integer, Long> manifest)
            throws IOException {
        oracle.checkpoint(manifest);
        subject.checkpoint(manifest);
    }

    /** (id, size) 交替参数构建 per-pack 清单. */
    private static SortedMap<Integer, Long> packs(long... idSizePairs) {
        TreeMap<Integer, Long> m = new TreeMap<>();
        for (int k = 0; k < idSizePairs.length; k += 2) {
            m.put((int) idSizePairs[k], idSizePairs[k + 1]);
        }
        return m;
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
