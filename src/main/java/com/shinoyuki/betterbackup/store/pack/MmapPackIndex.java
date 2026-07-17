package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.store.Hash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 零 (JVM) 堆的紧凑有序索引: 主体是排好序的 {@code index/base.idx}, 整体读进一块 <b>off-heap</b>
 * direct ByteBuffer (不占 -Xmx 堆, 不给 GC 施压; OS 仍缓存底层文件), 查询走二分。会话内的新写入
 * 落在小的内存 delta, 压实回收的对象记进 tombstone, {@link #checkpoint} 把 base 与 delta/tombstone
 * 流式归并成新 base.idx 并清空 delta/tombstone。
 *
 * <p><b>为什么用 direct ByteBuffer 整读而非 MappedByteBuffer</b>: Java 在 Windows 上无法可靠
 * unmap MappedByteBuffer, 映射存活期间文件被锁, checkpoint 重写/替换 base.idx 会失败。整读进
 * direct buffer 没有文件映射锁问题, 替换 base.idx 只是 atomic rename + 换一块新 buffer (旧的随
 * GC 回收), 跨平台稳妥; 内存占用与 mmap 同量级 (3M 对象 ~96MB off-heap, 远小于内存 HashMap 的
 * ~180MB on-heap), 二分查询同样是内存访问速度。
 *
 * <p><b>record 布局 v2</b> (entry 宽度 = hashLength + 16):
 * <pre>
 *   header(32B): [8B magic "BBIDX2\0\0"][8B reserved][4B count][4B hashLength][4B packCount][4B reserved]
 *   entry:       [hashLength B hash][4B packId][8B offset][4B length]   (按 hash 字节升序)
 *   manifest:    packCount 条 [4B packId][8B packBytes]                 (按 id 升序, 尾段)
 * </pre>
 *
 * <p><b>per-pack 差分</b>: 尾段 manifest 记录写盘当时每个 pack 的 (id, 字节数)。pack 封口后不可变,
 * 故 {@link #tryLoad} 与当前磁盘 pack 集逐 pack 比对: 未变 pack 的条目从 base 线性过滤保留 (过滤
 * 保持 hash 有序, 二分不破), 只把变化/新增 pack 交还 PackStore 重扫 —— 崩溃恢复通常只有最后一个
 * 在写 pack 尺寸变化, 重扫从 O(全部 pack) 降到 O(1 pack) (issue #3 的重扫放大根因)。旧 v1 格式
 * ("BBIDX1", 单 long 指纹) 无 per-pack 清单, 读到即退化为全量重扫, 首次 checkpoint 升格 v2。
 *
 * <p><b>并发</b>: PackStore 保证所有 mutation (put/remove/clear/checkpoint/tryLoad) 串行 (put 在
 * append 锁内, remove/checkpoint 在压实写锁内, load 单线程); get/contains 与 put 并发。delta /
 * tombstone 用并发容器, base 是只读 buffer (绝对索引 get 并发安全), 故 get 与 put 并发安全。
 */
final class MmapPackIndex implements PackIndex {

    private static final byte[] MAGIC_V1 = "BBIDX1\0\0".getBytes(StandardCharsets.US_ASCII); // 8 字节
    private static final byte[] MAGIC_V2 = "BBIDX2\0\0".getBytes(StandardCharsets.US_ASCII); // 8 字节
    private static final int HEADER_BYTES = 32;
    private static final int LOC_BYTES = 16; // packId(4) + offset(8) + length(4)
    private static final int PACK_META_BYTES = 12; // manifest 尾段每条: packId(4) + packBytes(8)

    private final Path baseFile;
    private final Path baseTmpFile;
    private final int hashLength;
    private final int entryWidth;

    // base: 只读 off-heap 有序索引 (可空 = 尚无 base)。绝对索引读, 并发安全。
    private volatile ByteBuffer base;
    private volatile int baseCount;

    private final Map<Hash, PackLocation> delta = new ConcurrentHashMap<>();
    private final Set<Hash> tombstones = ConcurrentHashMap.newKeySet();
    private final AtomicInteger liveSize = new AtomicInteger();

    MmapPackIndex(Path indexDir, int hashLength) {
        this.baseFile = indexDir.resolve("base.idx");
        this.baseTmpFile = indexDir.resolve("base.idx.tmp");
        this.hashLength = hashLength;
        this.entryWidth = hashLength + LOC_BYTES;
    }

    @Override
    public PackLocation get(Hash hash) {
        PackLocation d = delta.get(hash);
        if (d != null) {
            return d;
        }
        if (tombstones.contains(hash)) {
            return null;
        }
        return baseSearch(hash.bytes());
    }

    @Override
    public boolean contains(Hash hash) {
        return get(hash) != null;
    }

    @Override
    public void put(Hash hash, PackLocation loc) {
        boolean wasLive = isLive(hash);
        tombstones.remove(hash);
        delta.put(hash, loc);
        if (!wasLive) {
            liveSize.incrementAndGet();
        }
    }

    @Override
    public void remove(Hash hash) {
        boolean inDelta = delta.remove(hash) != null;
        boolean inBase = baseSearch(hash.bytes()) != null;
        boolean wasTombstoned = tombstones.contains(hash);
        boolean wasLive = inDelta || (inBase && !wasTombstoned);
        if (inBase) {
            tombstones.add(hash); // 影子删除 base 条目, checkpoint 时落地
        }
        if (wasLive) {
            liveSize.decrementAndGet();
        }
    }

    @Override
    public int size() {
        return liveSize.get();
    }

    @Override
    public void clear() {
        delta.clear();
        tombstones.clear();
        base = null;
        baseCount = 0;
        liveSize.set(0);
    }

    @Override
    public Set<Integer> tryLoad(SortedMap<Integer, Long> currentPacks) throws IOException {
        clear();
        Set<Integer> fullRescan = new TreeSet<>(currentPacks.keySet());
        if (!Files.isRegularFile(baseFile)) {
            return fullRescan;
        }
        long size = Files.size(baseFile);
        if (size < HEADER_BYTES) {
            return fullRescan;
        }
        ByteBuffer buf = ByteBuffer.allocateDirect((int) size);
        try (FileChannel ch = FileChannel.open(baseFile, StandardOpenOption.READ)) {
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) {
                    return fullRescan; // 文件比声称的短, 视为无效
                }
            }
        }
        buf.flip();
        if (!magicMatches(buf, MAGIC_V2)) {
            // v1 (单 long 指纹, 无 per-pack 清单) 或未知格式: 无法差分, 退化为全量重扫,
            // 重扫后的 checkpoint 会把 sidecar 升格为 v2.
            return fullRescan;
        }
        int count = buf.getInt(16);
        int storedHashLength = buf.getInt(20);
        int packCount = buf.getInt(24);
        if (storedHashLength != hashLength || count < 0 || packCount < 0) {
            return fullRescan;
        }
        if (size != (long) HEADER_BYTES + (long) count * entryWidth + (long) packCount * PACK_META_BYTES) {
            return fullRescan; // 大小与 count/packCount 不符, 损坏
        }

        // 读 manifest 尾段, 与当前磁盘 pack 集差分.
        Map<Integer, Long> stored = new HashMap<>(packCount * 2);
        int manifestBase = HEADER_BYTES + count * entryWidth;
        for (int k = 0; k < packCount; k++) {
            int metaPos = manifestBase + k * PACK_META_BYTES;
            stored.put(buf.getInt(metaPos), buf.getLong(metaPos + 4));
        }
        Set<Integer> unchanged = new HashSet<>();
        Set<Integer> toScan = new TreeSet<>();
        for (Map.Entry<Integer, Long> e : currentPacks.entrySet()) {
            Long storedBytes = stored.get(e.getKey());
            if (storedBytes != null && storedBytes.longValue() == e.getValue()) {
                unchanged.add(e.getKey());
            } else {
                toScan.add(e.getKey()); // 新增或尺寸变化
            }
        }
        boolean anyGone = false;
        for (Integer id : stored.keySet()) {
            if (!currentPacks.containsKey(id)) {
                anyGone = true; // 清单有、磁盘无: 其条目必须被过滤掉
                break;
            }
        }

        if (toScan.isEmpty() && !anyGone) {
            // 全命中: 整块 buffer 直接作 base (含 manifest 尾段无妨, 二分以 baseCount 限界).
            this.base = buf;
            this.baseCount = count;
            this.liveSize.set(count);
            return Set.of();
        }

        // 部分失效: 线性过滤 base, 只保留 packId ∈ 未变集 的条目. 过滤保持原有 hash 升序,
        // 二分不破; 变化 pack 的条目丢弃后由 PackStore 重扫灌回 delta, 消失 pack 的条目就此消亡.
        ByteBuffer filtered = ByteBuffer.allocateDirect(HEADER_BYTES + count * entryWidth);
        filtered.position(HEADER_BYTES); // 与 base 同款寻址 (entry 从 HEADER_BYTES 起), 头部留零
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int pos = HEADER_BYTES + i * entryWidth;
            int packId = buf.getInt(pos + hashLength);
            if (unchanged.contains(packId)) {
                for (int k = 0; k < entryWidth; k++) {
                    filtered.put(buf.get(pos + k));
                }
                kept++;
            }
        }
        this.base = filtered;
        this.baseCount = kept;
        this.liveSize.set(kept);
        return toScan;
    }

    @Override
    public void checkpoint(SortedMap<Integer, Long> currentPacks) throws IOException {
        Files.createDirectories(baseFile.getParent());
        int liveCount = liveSize.get();
        int packCount = currentPacks.size();
        ByteBuffer out = ByteBuffer.allocateDirect(
                HEADER_BYTES + liveCount * entryWidth + packCount * PACK_META_BYTES);
        out.put(MAGIC_V2);
        out.putLong(0L); // reserved (v1 在此存单 long 指纹, v2 由尾段 manifest 取代)
        out.putInt(liveCount);
        out.putInt(hashLength);
        out.putInt(packCount);
        out.putInt(0); // reserved (补满 HEADER_BYTES=32)

        int written = mergeInto(out);
        if (written != liveCount) {
            throw new IOException("pack index checkpoint inconsistency: wrote " + written
                    + " entries but liveSize=" + liveCount);
        }
        for (Map.Entry<Integer, Long> e : currentPacks.entrySet()) {
            out.putInt(e.getKey());
            out.putLong(e.getValue()); // manifest 尾段, 按 id 升序 (SortedMap 迭代序)
        }
        out.flip();

        writeAndSync(out.duplicate(), baseTmpFile);
        Files.move(baseTmpFile, baseFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // 换上刚写好的 buffer 作新 base, 清空 delta/tombstone (它们已并入)
        this.base = out;
        this.baseCount = liveCount;
        delta.clear();
        tombstones.clear();
        // liveSize 不变 (归并保存活集计数)
    }

    @Override
    public void close() {
        base = null;
    }

    // ---- internals ----

    private static boolean magicMatches(ByteBuffer buf, byte[] magic) {
        for (int k = 0; k < magic.length; k++) {
            if (buf.get(k) != magic[k]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把 (base − tombstone − 被 delta 影子) 与 delta 按 hash 升序归并写进 {@code out}。两路均已
     * 有序 (base 本就排序; delta 排序后), 双指针归并, 热循环只比较/拷贝字节不分配 Hash。
     *
     * @return 实际写入的 entry 数
     */
    private int mergeInto(ByteBuffer out) {
        // 先算出被 tombstone / delta 影子覆盖的 base 索引集 (二者都小, 各做一次二分定位)
        Set<Integer> skip = new HashSet<>();
        for (Hash t : tombstones) {
            int idx = baseSearchIndex(t.bytes());
            if (idx >= 0) {
                skip.add(idx);
            }
        }
        List<Map.Entry<Hash, PackLocation>> sortedDelta = new ArrayList<>(delta.entrySet());
        sortedDelta.sort(Comparator.comparing(e -> e.getKey().bytes(), MmapPackIndex::compareBytes));
        for (Map.Entry<Hash, PackLocation> e : sortedDelta) {
            int idx = baseSearchIndex(e.getKey().bytes());
            if (idx >= 0) {
                skip.add(idx); // delta 提供该 hash 的最新位置, 跳过 base 旧的
            }
        }

        int written = 0;
        int i = 0;
        int j = 0;
        while (i < baseCount || j < sortedDelta.size()) {
            // 推进 base 指针越过被跳过的索引
            while (i < baseCount && skip.contains(i)) {
                i++;
            }
            boolean takeBase;
            if (i >= baseCount) {
                takeBase = false;
            } else if (j >= sortedDelta.size()) {
                takeBase = true;
            } else {
                int pos = HEADER_BYTES + i * entryWidth;
                takeBase = compareBaseHashToBytes(pos, sortedDelta.get(j).getKey().bytes()) < 0;
            }
            if (takeBase) {
                int pos = HEADER_BYTES + i * entryWidth;
                for (int k = 0; k < entryWidth; k++) {
                    out.put(base.get(pos + k));
                }
                i++;
            } else {
                Map.Entry<Hash, PackLocation> e = sortedDelta.get(j++);
                byte[] hb = e.getKey().bytes();
                PackLocation loc = e.getValue();
                out.put(hb, 0, hashLength);
                out.putInt(loc.packId());
                out.putLong(loc.dataOffset());
                out.putInt(loc.length());
            }
            written++;
        }
        return written;
    }

    /** 二分查 base, 返回位置或 null. */
    private PackLocation baseSearch(byte[] target) {
        int idx = baseSearchIndex(target);
        if (idx < 0) {
            return null;
        }
        int pos = HEADER_BYTES + idx * entryWidth;
        int packId = base.getInt(pos + hashLength);
        long offset = base.getLong(pos + hashLength + 4);
        int length = base.getInt(pos + hashLength + 12);
        return new PackLocation(packId, offset, length);
    }

    /** 二分查 base, 返回 entry 索引或 -1. */
    private int baseSearchIndex(byte[] target) {
        ByteBuffer b = base;
        if (b == null) {
            return -1;
        }
        int lo = 0;
        int hi = baseCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compareBaseHashToBytes(HEADER_BYTES + mid * entryWidth, target);
            if (cmp == 0) {
                return mid;
            } else if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    /** 比较 base 内 entryPos 处的 hash 与 target (无符号字节序)。 */
    private int compareBaseHashToBytes(int entryPos, byte[] target) {
        ByteBuffer b = base;
        for (int k = 0; k < hashLength; k++) {
            int x = b.get(entryPos + k) & 0xFF;
            int y = target[k] & 0xFF;
            if (x != y) {
                return x - y;
            }
        }
        return 0;
    }

    private boolean isLive(Hash hash) {
        if (delta.containsKey(hash)) {
            return true;
        }
        if (tombstones.contains(hash)) {
            return false;
        }
        return baseSearchIndex(hash.bytes()) >= 0;
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int k = 0; k < n; k++) {
            int x = a[k] & 0xFF;
            int y = b[k] & 0xFF;
            if (x != y) {
                return x - y;
            }
        }
        return a.length - b.length;
    }

    private static void writeAndSync(ByteBuffer buf, Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
    }
}
