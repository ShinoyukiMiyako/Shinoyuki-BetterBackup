package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.log.BackupLog;
import com.shinoyuki.betterbackup.store.Hash;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /** store.meta 魔数 + 格式版本 (尾字节 '1' = 格式 v1). hashLength 写在其后 4 字节 BE. */
    private static final byte[] META_MAGIC = "BBPACK1".getBytes(StandardCharsets.US_ASCII);

    private final Path packsDir;
    private final Path metaFile;
    private final int hashLength;
    private final long targetPackSizeBytes;

    private final Map<Hash, PackLocation> index = new ConcurrentHashMap<>();
    private final Map<Integer, FileChannel> readChannels = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    // 写状态 (writeLock 保护)
    private int nextPackId;
    private int currentWritePackId = -1;
    private FileChannel writeChannel;
    private long writeOffset;

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
        index.clear();
        nextPackId = 0;
        currentWritePackId = -1;
        writeChannel = null;
        writeOffset = 0;
        if (!Files.isDirectory(packsDir)) {
            return;
        }
        int maxId = -1;
        for (int id : listPackIds()) {
            scanPack(id);
            maxId = Math.max(maxId, id);
        }
        nextPackId = maxId + 1;
    }

    /**
     * 写入一个对象. 命中索引即 dedup 跳过 (不触盘 stat). 返回 true = 实际新写, false = 已存在.
     *
     * <p>仅顺序追加, 不 fsync —— 持久化由 {@link #flushAndSync} 屏障负责.
     */
    public boolean put(Hash hash, byte[] data) throws IOException {
        if (hash.length() != hashLength) {
            throw new IllegalArgumentException("hash length " + hash.length()
                    + " != store hashLength " + hashLength);
        }
        if (index.containsKey(hash)) {
            return false;
        }
        synchronized (writeLock) {
            if (index.containsKey(hash)) {
                return false;
            }
            ensureWriter();
            long recordStart = writeOffset;
            ByteBuffer buf = ByteBuffer.allocate(hashLength + LEN_FIELD_BYTES + data.length);
            buf.put(hash.bytes());
            buf.putInt(data.length);
            buf.put(data);
            buf.flip();
            writeFully(writeChannel, buf);
            long dataOffset = recordStart + hashLength + LEN_FIELD_BYTES;
            writeOffset += hashLength + LEN_FIELD_BYTES + data.length;
            index.put(hash, new PackLocation(currentWritePackId, dataOffset, data.length));
            return true;
        }
    }

    public boolean has(Hash hash) {
        return index.containsKey(hash);
    }

    /** 读出对象原始字节. 不在 store 抛 {@link NoSuchFileException} (与 ChunkStore.get 缺失语义一致). */
    public byte[] get(Hash hash) throws IOException {
        PackLocation loc = index.get(hash);
        if (loc == null) {
            throw new NoSuchFileException("object not in pack store: " + hash.toHex());
        }
        FileChannel ch = readChannel(loc.packId());
        ByteBuffer buf = ByteBuffer.allocate(loc.length());
        readFully(ch, buf, loc.dataOffset());
        return buf.array();
    }

    /** fsync 提交屏障: force 当前在写 pack. 封口的 pack 在换页时已各自 force, 无需重做. */
    public void flushAndSync() throws IOException {
        synchronized (writeLock) {
            if (writeChannel != null) {
                writeChannel.force(true);
            }
        }
    }

    /** 当前索引中的对象数. */
    public int objectCount() {
        return index.size();
    }

    public void close() throws IOException {
        synchronized (writeLock) {
            if (writeChannel != null) {
                writeChannel.force(true);
                writeChannel.close();
                writeChannel = null;
            }
        }
        for (FileChannel ch : readChannels.values()) {
            try {
                ch.close();
            } catch (IOException e) {
                BackupLog.warn(LOGGER_NAME, "[BetterBackup] failed to close pack read channel", e);
            }
        }
        readChannels.clear();
    }

    // ---- internals ----

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
        writeChannel = FileChannel.open(packPath(currentWritePackId),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        writeChannel.position(0);
        writeOffset = 0;
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
     */
    private void scanPack(int packId) throws IOException {
        Path p = packPath(packId);
        long validEnd;
        long size;
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            size = ch.size();
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
                    break; // torn tail: header 完整但数据越界, 停在 pos
                }
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
