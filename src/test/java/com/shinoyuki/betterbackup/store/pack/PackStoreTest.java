package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void compact_physically_reclaims_dead_objects_and_survives_reopen(@TempDir Path root) throws IOException {
        List<Hash> hashes = new ArrayList<>();
        List<byte[]> objs = new ArrayList<>();
        PackStore store = new PackStore(root, HASH_LEN, 1200); // 小封口 -> 多 pack
        store.initialize();
        for (int i = 0; i < 8; i++) {
            byte[] o = randomBytes(500, 300 + i);
            Hash h = hashFn.hash(o);
            store.put(h, o);
            hashes.add(h);
            objs.add(o);
        }
        store.flushAndSync();
        store.close();

        // 重开后无在写 pack, 所有 pack 都可压实. 只保留偶数下标对象存活.
        PackStore store2 = new PackStore(root, HASH_LEN, 1200);
        store2.initialize();
        Set<Hash> live = new HashSet<>();
        for (int i = 0; i < 8; i += 2) {
            live.add(hashes.get(i));
        }
        PackStore.CompactResult r = store2.compact(live, 0.0); // 阈值 0 -> 任何死字节都触发回收
        assertEquals(4, r.objectsRemoved(), "4 odd-indexed dead objects reclaimed");
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 0) {
                assertArrayEquals(objs.get(i), store2.get(hashes.get(i)), "live object " + i + " survives compaction");
            } else {
                assertFalse(store2.has(hashes.get(i)), "dead object " + i + " physically reclaimed");
            }
        }
        store2.flushAndSync();
        store2.close();

        // 回收必须跨重启保持 (物理删除, 非逻辑墓碑)
        PackStore reopened = new PackStore(root, HASH_LEN, 1200);
        reopened.initialize();
        assertEquals(4, reopened.objectCount(), "only live objects remain after compaction + reopen");
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 0) {
                assertArrayEquals(objs.get(i), reopened.get(hashes.get(i)));
            } else {
                assertFalse(reopened.has(hashes.get(i)), "reclaimed object must stay gone after reopen");
            }
        }
        reopened.close();
    }

    @Test
    void compact_never_touches_the_active_write_pack(@TempDir Path root) throws IOException {
        // 小封口下连写 4 个 ~520B 对象: 前 3 个落已封口的 pack0, 第 4 个落在写 pack1.
        PackStore store = new PackStore(root, HASH_LEN, 1200);
        store.initialize();
        List<Hash> hashes = new ArrayList<>();
        List<byte[]> objs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            byte[] o = randomBytes(500, 700 + i);
            Hash h = hashFn.hash(o);
            store.put(h, o);
            hashes.add(h);
            objs.add(o);
        }
        store.flushAndSync();

        // 全部标死. 封口 pack0 应被删, 在写 pack1 必须跳过 (其对象存活).
        PackStore.CompactResult r = store.compact(new HashSet<>(), 0.0);
        assertEquals(1, r.packsDeleted(), "the sealed all-dead pack is deleted");
        for (int i = 0; i < 3; i++) {
            assertFalse(store.has(hashes.get(i)), "object " + i + " in sealed pack reclaimed");
        }
        assertArrayEquals(objs.get(3), store.get(hashes.get(3)),
                "object in the active write pack must survive compaction (pack still being appended)");
        store.close();
    }

    @Test
    void total_pack_bytes_is_zero_for_empty_store(@TempDir Path root) throws IOException {
        PackStore store = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        store.initialize();
        assertEquals(0L, store.totalPackBytes(), "fresh store has no pack files, so zero bytes");
        store.close();
    }

    @Test
    void total_pack_bytes_equals_sum_of_pack_file_sizes(@TempDir Path root) throws IOException {
        // 写若干对象后, totalPackBytes 必须精确等于 packs/ 下所有 .pack 的 Files.size 之和.
        PackStore store = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        store.initialize();
        for (int i = 0; i < 15; i++) {
            byte[] o = randomBytes(300 + i * 7, 500 + i);
            store.put(hashFn.hash(o), o);
        }
        store.flushAndSync();

        long expected = sumPackFileSizes(root);
        assertTrue(expected > 0, "precondition: some pack bytes were written");
        assertEquals(expected, store.totalPackBytes(),
                "totalPackBytes must equal the exact sum of Files.size over every .pack file");
        store.close();
    }

    @Test
    void total_pack_bytes_sums_across_multiple_rolled_packs(@TempDir Path root) throws IOException {
        // 小封口 -> 多 pack; totalPackBytes 必须跨所有 pack 累加, 而非只算当前在写 pack.
        PackStore store = new PackStore(root, HASH_LEN, 1200);
        store.initialize();
        for (int i = 0; i < 10; i++) {
            byte[] o = randomBytes(500, 200 + i);
            store.put(hashFn.hash(o), o);
        }
        store.flushAndSync();

        long packCount;
        try (Stream<Path> s = Files.list(root.resolve("packs"))) {
            packCount = s.filter(p -> p.getFileName().toString().endsWith(".pack")).count();
        }
        assertTrue(packCount > 1, "precondition: multiple packs rolled, got " + packCount);
        assertEquals(sumPackFileSizes(root), store.totalPackBytes(),
                "totalPackBytes must sum every rolled pack, not just the active write pack");
        store.close();
    }

    @Test
    void put_recovers_index_alignment_after_mid_write_io_failure(@TempDir Path root) throws IOException {
        PackStore store = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        store.initialize();

        byte[] a = randomBytes(100, 11);
        byte[] b = randomBytes(200, 22);
        byte[] c = randomBytes(150, 33);
        Hash ha = hashFn.hash(a);
        Hash hb = hashFn.hash(b);
        Hash hc = hashFn.hash(c);

        // A 记录整长 = 16 + 4 + 100 = 120; 让写在 B 记录中途 (120 + 50 字节处) 抛 IOException.
        long failAt = (HASH_LEN + 4 + a.length) + 50;
        AtomicInteger opens = new AtomicInteger();
        store.setWriteChannelOpenerForTest(p -> {
            FileChannel real = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return opens.getAndIncrement() == 0 ? new FailAfterNBytesChannel(real, failAt) : real;
        });

        assertTrue(store.put(ha, a), "A writes fully");
        assertThrows(IOException.class, () -> store.put(hb, b), "B fails mid-write");
        assertFalse(store.has(hb), "failed put must not leave B in the index");

        // C 写在恢复后的通道: 若 position/writeOffset 失步, C 落错位, get 读回混合垃圾 -> 断言必挂.
        assertTrue(store.put(hc, c), "C writes after recovery");
        assertArrayEquals(c, store.get(hc), "C round-trips byte-exact after recovery");
        assertArrayEquals(a, store.get(ha), "A survives intact");
        store.close();

        // 重开顺序扫: 索引一致, A/C 在, B 的 torn 部分被截断不留幻影.
        PackStore reopened = new PackStore(root, HASH_LEN, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
        reopened.initialize();
        assertEquals(2, reopened.objectCount(), "only A and C survive; no phantom B");
        assertArrayEquals(a, reopened.get(ha));
        assertArrayEquals(c, reopened.get(hc));
        assertFalse(reopened.has(hb));
        reopened.close();
    }

    /**
     * 委托真 FileChannel, 但累计写满 failAtByte 字节后, 在下一次 write 里先写完剩余额度再抛
     * IOException, 模拟磁盘满 / 网络盘瞬断的写中途失败 (部分写). 失败一次后不再拦截.
     */
    private static final class FailAfterNBytesChannel extends FileChannel {
        private final FileChannel real;
        private final long failAtByte;
        private long written;
        private boolean failed;

        FailAfterNBytesChannel(FileChannel real, long failAtByte) {
            this.real = real;
            this.failAtByte = failAtByte;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (failed) {
                return real.write(src);
            }
            long allowance = failAtByte - written;
            if (src.remaining() <= allowance) {
                int n = real.write(src);
                written += n;
                return n;
            }
            int savedLimit = src.limit();
            src.limit(src.position() + (int) allowance);
            int n = real.write(src);
            written += n;
            src.limit(savedLimit);
            failed = true;
            throw new IOException("simulated disk-full after " + written + " bytes");
        }

        @Override public int read(ByteBuffer dst) throws IOException { return real.read(dst); }
        @Override public long read(ByteBuffer[] dsts, int off, int len) throws IOException { return real.read(dsts, off, len); }
        @Override public long write(ByteBuffer[] srcs, int off, int len) throws IOException { return real.write(srcs, off, len); }
        @Override public long position() throws IOException { return real.position(); }
        @Override public FileChannel position(long p) throws IOException { real.position(p); return this; }
        @Override public long size() throws IOException { return real.size(); }
        @Override public FileChannel truncate(long s) throws IOException { real.truncate(s); return this; }
        @Override public void force(boolean metaData) throws IOException { real.force(metaData); }
        @Override public long transferTo(long p, long count, WritableByteChannel target) throws IOException { return real.transferTo(p, count, target); }
        @Override public long transferFrom(ReadableByteChannel src, long p, long count) throws IOException { return real.transferFrom(src, p, count); }
        @Override public int read(ByteBuffer dst, long p) throws IOException { return real.read(dst, p); }
        @Override public int write(ByteBuffer src, long p) throws IOException { return real.write(src, p); }
        @Override public MappedByteBuffer map(MapMode mode, long p, long s) throws IOException { return real.map(mode, p, s); }
        @Override public FileLock lock(long p, long s, boolean shared) throws IOException { return real.lock(p, s, shared); }
        @Override public FileLock tryLock(long p, long s, boolean shared) throws IOException { return real.tryLock(p, s, shared); }
        @Override protected void implCloseChannel() throws IOException { real.close(); }
    }

    private static long sumPackFileSizes(Path root) throws IOException {
        try (Stream<Path> s = Files.list(root.resolve("packs"))) {
            long sum = 0;
            for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().endsWith(".pack"))::iterator) {
                sum += Files.size(p);
            }
            return sum;
        }
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
