package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.log.BackupLog;
import com.shinoyuki.betterbackup.store.Hash;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Append-only pack 存储: 把众多内容寻址对象顺序追加进少量大 pack 文件, 替代"一对象一文件"的
 * 布局, 根治机械盘上百万小文件的随机寻道与每对象 fsync.
 *
 * <p><b>pack 记录格式</b> (自描述, 内联 hash 让 fsck 顺序扫即可重建索引):
 * <pre>
 *   [hashLength 字节: 内容 hash][4 字节 BE: 数据长度 L][L 字节: 原始对象字节]
 * </pre>
 * 多条记录在一个 {@code &lt;packId&gt;.pack} 内顺序排列. pack 超过 {@code targetPackSizeBytes}
 * 即封口换下一个, 封口的 pack 不可变.
 *
 * <p><b>fsync 模型</b>: {@link #put} 只顺序追加 (字节进 OS page cache, reader 即时可见),
 * <b>不</b>每对象 fsync. 持久化由 {@link #flushAndSync} 屏障一次性完成 —— 调用方 (SnapshotCreator)
 * 在写 manifest 之前调一次, 把"manifest 落盘即其引用对象落盘"的不变量压成每快照 1 次 force,
 * 让机械盘电梯合并写回. 窗口中途崩溃丢失的是尚未被任何 manifest 引用的对象, 安全.
 *
 * <p><b>崩溃恢复</b>: 内存索引不持久 (Step 1), 每次 {@link #load} 顺序扫所有 pack 重建.
 * 扫描遇到写一半的尾部记录 (header 完整但数据不足, 或尾部残缺) 即把该 pack 截断到上一条完整
 * 记录边界 —— 只有最后一个 pack 可能出现 torn tail.
 *
 * <p><b>线程安全</b>: {@link #put} 的追加在 {@code writeLock} 内串行 (机械盘上串行顺序写优于
 * 并发抖磁头). {@link #has}/{@link #get} 走 ConcurrentHashMap 索引 + 各自只读 channel, 与
 * put 并发安全. 读 channel 按 packId 缓存.
 */
public final class PackStore {

    private static final String LOGGER_NAME = PackStore.class.getName();

    /** 默认封口大小 256 MiB: 顺序写一个 pack 的量级, 兼顾 pack 数量与压实粒度. */
    public static final long DEFAULT_TARGET_PACK_SIZE_BYTES = 256L * 1024 * 1024;

    private static final int LEN_FIELD_BYTES = 4;
    private static final String PACK_SUFFIX = ".pack";

    /** scanPack 流式读缓冲. 8 MiB: 足以让机械盘保持顺序吞吐, 又不至于单 pack 扫描占用过多堆. */
    private static final int SCAN_BUFFER_BYTES = 8 * 1024 * 1024;

    /** store.meta 魔数 + 格式版本 (尾字节 '1' = 格式 v1). hashLength 写在其后 4 字节 BE. */
    private static final byte[] META_MAGIC = "BBPACK1".getBytes(StandardCharsets.US_ASCII);

    private final Path packsDir;
    private final Path metaFile;
    private final int hashLength;
    private final long targetPackSizeBytes;

    private final PackIndex index;
    private final Map<Integer, FileChannel> readChannels = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    // 压实排他锁: put/get/flushAndSync 持读锁 (彼此并发), compact 持写锁 (独占, 阻塞读写),
    // 以便压实安全地重写/删除封口 pack 而不与活跃 worker 的 put/get 撞车.
    private final ReentrantReadWriteLock storeLock = new ReentrantReadWriteLock();

    // 写状态 (writeLock 保护)
    private int nextPackId;
    private int currentWritePackId = -1;
    private FileChannel writeChannel;
    private long writeOffset;

    // 硬闸: initialize()/load() 完成前拒绝一切对象读写. 未初始化时写状态停留在 nextPackId=0
    // 的假象上, 此时 put 会打开既有 store 的 0000000000.pack 从偏移 0 覆盖写, 静默损坏历史
    // 备份数据 —— store 初始化已移到后台线程 (issue #3), 任何漏过上层就绪门控的调用都必须在
    // 这里响亮失败, 而不是损坏数据.
    private volatile boolean initialized;

    // 在写 pack 的通道工厂. 生产用真 FileChannel.open; 测试可注入模拟写中途 IO 失败的假通道,
    // 验证失败后 put 能恢复 position==writeOffset 不变量.
    private volatile WriteChannelOpener writeChannelOpener =
            p -> FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

    // 测试观测点: 每次 scanPack 回调 packId, 用于断言增量重扫只扫了变化 pack. 生产恒 null.
    private volatile IntConsumer scanObserverForTest;

    public PackStore(Path storeRoot, int hashLength, long targetPackSizeBytes) {
        if (hashLength <= 0) {
            throw new IllegalArgumentException("hashLength must be positive: " + hashLength);
        }
        if (targetPackSizeBytes <= 0) {
            throw new IllegalArgumentException("targetPackSizeBytes must be positive: " + targetPackSizeBytes);
        }
        this.packsDir = storeRoot.resolve("packs");
        this.metaFile = packsDir.resolve("store.meta");
        this.hashLength = hashLength;
        this.targetPackSizeBytes = targetPackSizeBytes;
        // 紧凑有序 off-heap 索引 (零 JVM 堆): 百万对象生产的默认. 无持久 base.idx / 指纹失配时
        // PackStore.load 自动顺序扫 pack 重建.
        this.index = new MmapPackIndex(storeRoot.resolve("index"), hashLength);
    }

    public Path packsDir() {
        return packsDir;
    }

    /**
     * 初始化: 建 packs 目录, 写 (或校验) store.meta 头, 然后 {@link #load} 重建索引.
     * 已存在的 store.meta 若 hashLength 与本次不符直接抛 —— 防止在已有 store 上换 hash 算法
     * 导致 dedup 与解析全错.
     */
    public void initialize() throws IOException {
        Files.createDirectories(packsDir);
        writeOrVerifyMeta();
        load();
        BackupLog.info(LOGGER_NAME, "[BetterBackup] pack store initialized at {} (objects={})",
                packsDir.toAbsolutePath(), index.size());
    }

    private void writeOrVerifyMeta() throws IOException {
        if (Files.exists(metaFile)) {
            byte[] actual = Files.readAllBytes(metaFile);
            ByteBuffer expected = ByteBuffer.allocate(META_MAGIC.length + 4)
                    .put(META_MAGIC).putInt(hashLength);
            if (!java.util.Arrays.equals(actual, expected.array())) {
                throw new IOException("pack store.meta mismatch at " + metaFile
                        + " (different hash algorithm or corrupt header?)");
            }
            return;
        }
        ByteBuffer meta = ByteBuffer.allocate(META_MAGIC.length + 4).put(META_MAGIC).putInt(hashLength);
        Path tmp = metaFile.resolveSibling("store.meta.tmp");
        Files.write(tmp, meta.array(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, metaFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * 顺序扫所有 pack 重建内存索引. 重置写状态: 本次会话的新写入落到 maxPackId+1 的新 pack,
     * 不续写历史 pack (简化 + 避免重开已封口 pack 的边界问题; 碎 pack 由后续压实合并).
     */
    public synchronized void load() throws IOException {
        loadInternal();
        initialized = true;
    }

    private void loadInternal() throws IOException {
        index.clear();
        nextPackId = 0;
        currentWritePackId = -1;
        writeChannel = null;
        writeOffset = 0;
        if (!Files.isDirectory(packsDir)) {
            return;
        }
        List<Integer> ids = listPackIds();
        int maxId = -1;
        for (int id : ids) {
            maxId = Math.max(maxId, id);
        }
        nextPackId = maxId + 1;

        // 持久索引按 pack 粒度差分: 未变 pack 的条目直接保留, 只重扫变化/新增 pack.
        // 崩溃恢复通常只有最后一个在写 pack 尺寸变化, 重扫从 O(全部 pack) 降到 O(1 pack)
        // (issue #3 的重扫放大根因); 全命中则零重扫.
        Set<Integer> toScan = index.tryLoad(packSizes(ids));
        if (toScan.isEmpty()) {
            return;
        }
        if (toScan.size() < ids.size()) {
            BackupLog.info(LOGGER_NAME, "[BetterBackup] incremental index rebuild: rescanning {} of {} pack(s)",
                    toScan.size(), ids.size());
        } else {
            BackupLog.info(LOGGER_NAME, "[BetterBackup] full index rebuild: scanning {} pack(s)", ids.size());
        }
        for (int id : toScan) {
            scanPack(id);
        }
        // checkpoint 的清单必须在扫描 (可能截断改了 pack 大小) 之后重算, 否则记的是截断前的
        // 过期尺寸, 下次 load 又判失配白白重扫.
        index.checkpoint(packSizes(listPackIds()));
    }

    /** 当前 pack 集清单: packId -> 文件字节数 (按 id 升序). 持久索引差分与 checkpoint 用. */
    private SortedMap<Integer, Long> packSizes(List<Integer> ids) throws IOException {
        TreeMap<Integer, Long> sizes = new TreeMap<>();
        for (int id : ids) {
            sizes.put(id, Files.size(packPath(id)));
        }
        return sizes;
    }

    /**
     * 写入一个对象. 命中索引即 dedup 跳过 (不触盘 stat). 返回 true = 实际新写, false = 已存在.
     *
     * <p>仅顺序追加, 不 fsync —— 持久化由 {@link #flushAndSync} 屏障负责.
     */
    public boolean put(Hash hash, byte[] data) throws IOException {
        requireInitialized();
        if (hash.length() != hashLength) {
            throw new IllegalArgumentException("hash length " + hash.length()
                    + " != store hashLength " + hashLength);
        }
        if (index.contains(hash)) {
            return false;
        }
        storeLock.readLock().lock();
        try {
            synchronized (writeLock) {
                if (index.contains(hash)) {
                    return false;
                }
                ensureWriter();
                long recordStart = writeOffset;
                ByteBuffer buf = ByteBuffer.allocate(hashLength + LEN_FIELD_BYTES + data.length);
                buf.put(hash.bytes());
                buf.putInt(data.length);
                buf.put(data);
                buf.flip();
                try {
                    writeFully(writeChannel, buf);
                } catch (IOException e) {
                    // 部分写把通道位置推到 recordStart+k, 而 writeOffset 仍是 recordStart. 不修复则
                    // 下次 put 从错误位置续写, 本会话此后所有对象索引偏移错位, get 读回混合垃圾字节.
                    // 截断回 recordStart + 重开写通道定位 recordStart, 强制重建
                    // channel.position()==writeOffset 不变量, 再让异常冒泡 (worker 记 ERROR).
                    repairAfterFailedWrite(recordStart, e);
                    throw e;
                }
                long dataOffset = recordStart + hashLength + LEN_FIELD_BYTES;
                writeOffset += hashLength + LEN_FIELD_BYTES + data.length;
                index.put(hash, new PackLocation(currentWritePackId, dataOffset, data.length));
                return true;
            }
        } finally {
            storeLock.readLock().unlock();
        }
    }

    public boolean has(Hash hash) {
        requireInitialized();
        return index.contains(hash);
    }

    /** 读出对象原始字节. 不在 store 抛 {@link NoSuchFileException} (与 ChunkStore.get 缺失语义一致). */
    public byte[] get(Hash hash) throws IOException {
        requireInitialized();
        storeLock.readLock().lock();
        try {
            PackLocation loc = index.get(hash);
            if (loc == null) {
                throw new NoSuchFileException("object not in pack store: " + hash.toHex());
            }
            FileChannel ch = readChannel(loc.packId());
            ByteBuffer buf = ByteBuffer.allocate(loc.length());
            readFully(ch, buf, loc.dataOffset());
            return buf.array();
        } finally {
            storeLock.readLock().unlock();
        }
    }

    /** fsync 提交屏障: force 当前在写 pack. 封口的 pack 在换页时已各自 force, 无需重做. */
    public void flushAndSync() throws IOException {
        requireInitialized();
        storeLock.readLock().lock();
        try {
            synchronized (writeLock) {
                if (writeChannel != null) {
                    writeChannel.force(true);
                }
            }
        } finally {
            storeLock.readLock().unlock();
        }
    }

    /** 当前索引中的对象数. */
    public int objectCount() {
        return index.size();
    }

    /**
     * 所有 pack 文件的磁盘字节总和 (含每对象内联帧头 + torn tail 残尾, 即 pack 目录实占). 这是
     * store 体积的主体 —— 新写入一律进 pack, 旧文件树只读排空. 廉价: 只对每个 pack 做一次
     * {@code Files.size} (stat), 不读内容, 与 {@link #objectCount} 的量级相当.
     *
     * <p>持读锁防压实 (compact 删/重写 pack) 与本统计并发导致 {@code Files.size} 命中已删 pack.
     */
    public long totalPackBytes() throws IOException {
        storeLock.readLock().lock();
        try {
            long total = 0;
            for (int packId : listPackIds()) {
                total += Files.size(packPath(packId));
            }
            return total;
        } finally {
            storeLock.readLock().unlock();
        }
    }

    /**
     * 顺序遍历所有 pack 的每个对象 (内联 storedHash + 原始字节). fsck 完整性校验用 ——
     * 按 pack 顺序读, 机械盘友好. 持读锁防压实并发改 pack.
     */
    public void forEachObject(ObjectVisitor visitor) throws IOException {
        requireInitialized();
        storeLock.readLock().lock();
        try {
            for (int packId : listPackIds()) {
                for (Rec r : readRecords(packId)) {
                    ByteBuffer buf = ByteBuffer.allocate(r.length());
                    readFully(readChannel(packId), buf, r.dataOffset());
                    visitor.visit(r.hash(), buf.array());
                }
            }
        } finally {
            storeLock.readLock().unlock();
        }
    }

    /** {@link #forEachObject} 的访问回调. storedHash = pack 内联记录的 hash. */
    @FunctionalInterface
    public interface ObjectVisitor {
        void visit(Hash storedHash, byte[] data) throws IOException;
    }

    /**
     * 关服 checkpoint: 尽力把内存索引连同当前 per-pack 清单落盘, 让下次启动 tryLoad 全命中
     * 零重扫 —— 没有它, 每个写过新对象的会话都会让下次启动付一遍 (增量) 重扫. 有界 tryLock:
     * gcAll/压实仍持写锁时直接跳过 (增量差分使代价只是下次重扫活跃 pack, 秒级), 绝不让关服
     * 在 store 锁上无界等待 (关服卡死是本系列 mod 的历史事故线, 见 BAS shutdown-hang).
     */
    public void checkpointOnShutdown() {
        if (!initialized) {
            return;
        }
        boolean locked = false;
        try {
            locked = storeLock.writeLock().tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            BackupLog.warn(LOGGER_NAME, "[BetterBackup] shutdown index checkpoint skipped: store lock busy "
                    + "(compaction still running); next start rescans only changed packs");
            return;
        }
        try {
            synchronized (writeLock) {
                if (writeChannel != null) {
                    writeChannel.force(true);
                }
            }
            index.checkpoint(packSizes(listPackIds()));
        } catch (IOException e) {
            BackupLog.warn(LOGGER_NAME, "[BetterBackup] shutdown index checkpoint failed (non-fatal): "
                    + "next start rescans changed packs", e);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    public void close() throws IOException {
        storeLock.writeLock().lock();
        try {
            if (writeChannel != null) {
                writeChannel.force(true);
                writeChannel.close();
                writeChannel = null;
            }
            if (initialized) {
                // checkpoint 索引: pack 文件此刻已最终化, 记下当前 per-pack 清单, 下次 load 全命中免重扫.
                // 未初始化的 store 绝不 checkpoint: 空索引 + 匹配清单会让下次 load 误判 "store 为空",
                // 既有对象全部不可达.
                index.checkpoint(packSizes(listPackIds()));
            }
            initialized = false;
            index.close();
            for (FileChannel ch : readChannels.values()) {
                try {
                    ch.close();
                } catch (IOException e) {
                    BackupLog.warn(LOGGER_NAME, "[BetterBackup] failed to close pack read channel", e);
                }
            }
            readChannels.clear();
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    /**
     * 压实: 物理回收死字节. {@code live} 是存活对象 hash 集 (manifest 引用 ∪ 在途待引用),
     * 不在其中的对象即死. 对每个封口 pack:
     * <ul>
     *   <li>全死 → 删整个 pack 文件 + 摘除其全部索引条目</li>
     *   <li>死字节占比 &gt; {@code deadRatioThreshold} → 把存活对象重打包进新 pack, 删旧 pack</li>
     *   <li>否则跳过 (死字节不够多, 重写不划算)</li>
     * </ul>
     * 当前在写 pack 不参与 (还在追加). 持写锁独占, 阻塞所有 put/get, 保证重写/删除安全.
     *
     * @param live              存活对象 hash 集; 不在其中的物理回收
     * @param deadRatioThreshold 死字节占比超过此值才重打包 (0.0~1.0); 全死 pack 无视阈值直接删
     */
    public CompactResult compact(Set<Hash> live, double deadRatioThreshold) throws IOException {
        return compact(live, deadRatioThreshold, false);
    }

    /**
     * 同 {@link #compact(Set, double)}, 但可选先封口在写 pack 使其也参与压实.
     *
     * @param sealActiveWritePack true = 先 force+封口当前在写 pack 再压实 (全量 GC: 连最新写入也
     *                            一并回收死字节, 下次 put 开新 pack); false = 跳过在写 pack
     *                            (自动压实: 不打扰活跃 worker 的在途追加)
     */
    public CompactResult compact(Set<Hash> live, double deadRatioThreshold, boolean sealActiveWritePack)
            throws IOException {
        requireInitialized();
        storeLock.writeLock().lock();
        try {
            if (sealActiveWritePack && writeChannel != null) {
                writeChannel.force(true);
                writeChannel.close();
                writeChannel = null;
                currentWritePackId = -1; // 已封口, 不再排除; 下次 put 开新 pack
            }
            long objectsRemoved = 0;
            long bytesReclaimed = 0;
            int packsDeleted = 0;
            int packsRewritten = 0;
            int activeWritePack = currentWritePackId;
            for (int packId : listPackIds()) {
                if (packId == activeWritePack) {
                    continue; // 在写 pack 不动
                }
                List<Rec> recs = readRecords(packId);
                if (recs.isEmpty()) {
                    continue;
                }
                long total = Files.size(packPath(packId));
                long deadBytes = 0;
                List<Rec> liveRecs = new ArrayList<>();
                for (Rec r : recs) {
                    if (live.contains(r.hash())) {
                        liveRecs.add(r);
                    } else {
                        deadBytes += hashLength + LEN_FIELD_BYTES + (long) r.length();
                    }
                }
                if (liveRecs.isEmpty()) {
                    for (Rec r : recs) {
                        index.remove(r.hash());
                    }
                    deletePackResources(packId);
                    objectsRemoved += recs.size();
                    bytesReclaimed += total;
                    packsDeleted++;
                    continue;
                }
                if ((double) deadBytes / total <= deadRatioThreshold) {
                    continue;
                }
                rewritePackKeepingLive(packId, liveRecs);
                for (Rec r : recs) {
                    if (!live.contains(r.hash())) {
                        index.remove(r.hash());
                    }
                }
                deletePackResources(packId);
                objectsRemoved += recs.size() - liveRecs.size();
                bytesReclaimed += deadBytes;
                packsRewritten++;
            }
            if (packsDeleted > 0 || packsRewritten > 0) {
                BackupLog.info(LOGGER_NAME,
                        "[BetterBackup] pack compact: objectsRemoved={} bytesReclaimed={} packsDeleted={} packsRewritten={}",
                        objectsRemoved, bytesReclaimed, packsDeleted, packsRewritten);
                // pack 集已变, checkpoint 索引让下次 load 清单匹配即可 mmap 命中.
                index.checkpoint(packSizes(listPackIds()));
            }
            return new CompactResult(objectsRemoved, bytesReclaimed, packsDeleted, packsRewritten);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    /** 把 {@code liveRecs} 从旧 pack 读出重写进一个新 pack, 同步更新这些对象在索引中的位置. */
    private void rewritePackKeepingLive(int oldPackId, List<Rec> liveRecs) throws IOException {
        int newPackId = nextPackId++;
        Path newPath = packPath(newPackId);
        FileChannel in = readChannel(oldPackId);
        try (FileChannel out = FileChannel.open(newPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long offset = 0;
            for (Rec r : liveRecs) {
                ByteBuffer data = ByteBuffer.allocate(r.length());
                readFully(in, data, r.dataOffset());
                ByteBuffer record = ByteBuffer.allocate(hashLength + LEN_FIELD_BYTES + r.length());
                record.put(r.hash().bytes());
                record.putInt(r.length());
                record.put(data.array());
                record.flip();
                writeFully(out, record);
                long newDataOffset = offset + hashLength + LEN_FIELD_BYTES;
                index.put(r.hash(), new PackLocation(newPackId, newDataOffset, r.length()));
                offset += hashLength + LEN_FIELD_BYTES + r.length();
            }
            out.force(true);
        }
    }

    /** 关闭并摘除某 pack 的读 channel, 删除其文件. */
    private void deletePackResources(int packId) throws IOException {
        FileChannel ch = readChannels.remove(packId);
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException e) {
                BackupLog.warn(LOGGER_NAME, "[BetterBackup] failed to close read channel for pack {}", packId, e);
            }
        }
        Files.deleteIfExists(packPath(packId));
    }

    /** 顺序扫一个 pack 读出全部记录 (不动索引). 遇 torn tail 停在上一条完整记录边界. */
    private List<Rec> readRecords(int packId) throws IOException {
        List<Rec> recs = new ArrayList<>();
        Path p = packPath(packId);
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            long size = ch.size();
            long pos = 0;
            while (pos + hashLength + LEN_FIELD_BYTES <= size) {
                ByteBuffer header = ByteBuffer.allocate(hashLength + LEN_FIELD_BYTES);
                readFully(ch, header, pos);
                header.flip();
                byte[] hashBytes = new byte[hashLength];
                header.get(hashBytes);
                int dataLength = header.getInt();
                long dataStart = pos + hashLength + LEN_FIELD_BYTES;
                if (dataLength < 0 || dataStart + dataLength > size) {
                    break;
                }
                recs.add(new Rec(new Hash(hashBytes), dataStart, dataLength));
                pos = dataStart + dataLength;
            }
        }
        return recs;
    }

    /** 压实统计. */
    public record CompactResult(long objectsRemoved, long bytesReclaimed, int packsDeleted, int packsRewritten) {
    }

    /** pack 内一条记录的 hash + 数据位置 (压实/扫描内部用). */
    private record Rec(Hash hash, long dataOffset, int length) {
    }

    // ---- internals ----

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("pack store not initialized: initialize()/load() has not "
                    + "completed; refusing object access to avoid overwriting existing pack files");
        }
    }

    private void ensureWriter() throws IOException {
        if (writeChannel != null && writeOffset < targetPackSizeBytes) {
            return;
        }
        if (writeChannel != null) {
            // 当前 pack 已达封口大小: force 落盘后封口, 换新 pack.
            writeChannel.force(true);
            writeChannel.close();
            writeChannel = null;
        }
        currentWritePackId = nextPackId++;
        writeChannel = writeChannelOpener.open(packPath(currentWritePackId));
        writeChannel.position(0);
        writeOffset = 0;
    }

    /**
     * 写记录中途 IO 失败 (磁盘满 / 网络盘瞬断 / 杀软锁) 后恢复现场: 把在写 pack 物理截断回
     * {@code recordStart} (= 本次失败记录的起点, writeOffset 尚未推进) 并重开写通道定位到
     * {@code recordStart}, 使 {@code channel.position()==writeOffset} 不变量在失败后被强制重建.
     * 调用方随后 rethrow 原始 IOException.
     *
     * <p>截断 / 重开自身再失败 (store 写侧彻底不可用) 时, 把写通道置空并将修复异常挂到原始
     * {@code cause} 上一并冒泡: 下一次 put 的 {@link #ensureWriter} 见 null 会开一个全新 pack
     * 从 offset 0 起写, 绝不复用已失步的通道继续追加.
     */
    private void repairAfterFailedWrite(long recordStart, IOException cause) {
        if (writeChannel != null) {
            try {
                writeChannel.close();
            } catch (IOException closeEx) {
                cause.addSuppressed(closeEx);
            }
            writeChannel = null;
        }
        try {
            FileChannel reopened = writeChannelOpener.open(packPath(currentWritePackId));
            reopened.truncate(recordStart);
            reopened.force(true);
            reopened.position(recordStart);
            writeChannel = reopened;
        } catch (IOException repairEx) {
            cause.addSuppressed(repairEx);
            writeChannel = null;
        }
    }

    /** 在写 pack 通道工厂. 生产恒为 {@code FileChannel.open(CREATE, WRITE)}; 测试注入模拟失败通道. */
    @FunctionalInterface
    interface WriteChannelOpener {
        FileChannel open(Path path) throws IOException;
    }

    /** 测试注入点: 替换在写 pack 的通道工厂以模拟写中途 IO 失败. 仅测试调用. */
    void setWriteChannelOpenerForTest(WriteChannelOpener opener) {
        this.writeChannelOpener = opener;
    }

    /** 测试观测点: scanPack 计数回调, 断言增量重扫只扫变化 pack. 仅测试调用. */
    void setScanObserverForTest(IntConsumer observer) {
        this.scanObserverForTest = observer;
    }

    private FileChannel readChannel(int packId) throws IOException {
        FileChannel ch = readChannels.get(packId);
        if (ch != null && ch.isOpen()) {
            return ch;
        }
        synchronized (readChannels) {
            ch = readChannels.get(packId);
            if (ch != null && ch.isOpen()) {
                return ch;
            }
            ch = FileChannel.open(packPath(packId), StandardOpenOption.READ);
            readChannels.put(packId, ch);
            return ch;
        }
    }

    /**
     * 扫一个 pack 文件重建索引条目. 尾部 torn 记录 (header 不全 / 数据不足) 截断到上一条完整
     * 记录边界. 只有最后写入的 pack 可能出现 torn tail, 但此处对任意 pack 都做防御性截断.
     *
     * <p>大缓冲流式顺序读: 逐记录 20 字节 pread 在百万记录 store 上是百万次系统调用, 机械盘上
     * 线性放大到分钟级 (issue #3 崩溃现场卡的就是 pread); 流式读把系统调用数压到 O(size/缓冲).
     */
    private void scanPack(int packId) throws IOException {
        IntConsumer observer = scanObserverForTest;
        if (observer != null) {
            observer.accept(packId);
        }
        Path p = packPath(packId);
        long validEnd;
        long size;
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            size = ch.size();
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Channels.newInputStream(ch), SCAN_BUFFER_BYTES));
            long pos = 0;
            while (pos + hashLength + LEN_FIELD_BYTES <= size) {
                byte[] hashBytes = new byte[hashLength];
                in.readFully(hashBytes);
                int dataLength = in.readInt(); // BE, 与写侧 putInt 一致
                long dataStart = pos + hashLength + LEN_FIELD_BYTES;
                if (dataLength < 0 || dataStart + dataLength > size) {
                    break; // torn tail: header 完整但数据越界, 停在 pos
                }
                in.skipNBytes(dataLength);
                index.put(new Hash(hashBytes), new PackLocation(packId, dataStart, dataLength));
                pos = dataStart + dataLength;
            }
            validEnd = pos;
        }
        if (validEnd < size) {
            BackupLog.warn(LOGGER_NAME, "[BetterBackup] truncating torn tail of pack {} ({} -> {} bytes)",
                    packId, size, validEnd);
            try (FileChannel wch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                wch.truncate(validEnd);
                wch.force(true);
            }
        }
    }

    private List<Integer> listPackIds() throws IOException {
        List<Integer> ids = new ArrayList<>();
        if (!Files.isDirectory(packsDir)) {
            return ids;
        }
        try (Stream<Path> list = Files.list(packsDir)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(PACK_SUFFIX)) {
                    continue;
                }
                String idPart = name.substring(0, name.length() - PACK_SUFFIX.length());
                try {
                    ids.add(Integer.parseInt(idPart));
                } catch (NumberFormatException ignore) {
                    // 非 <digits>.pack 命名的文件 (用户手放 / 其他): 不当 pack, 跳过.
                }
            }
        }
        ids.sort(Integer::compareTo);
        return ids;
    }

    private Path packPath(int packId) {
        return packsDir.resolve(String.format("%010d%s", packId, PACK_SUFFIX));
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p);
            if (n < 0) {
                throw new EOFException("unexpected EOF reading pack at offset " + p);
            }
            p += n;
        }
    }
}
