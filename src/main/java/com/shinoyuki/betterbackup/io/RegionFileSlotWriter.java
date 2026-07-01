package com.shinoyuki.betterbackup.io;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写 vanilla .mca region file 里 chunk slot 的 raw 压缩字节, 跟 {@link RegionFileSlotReader} 反向配对.
 *
 * <p><b>session 模式而非 single-call</b>: 一个 .mca 持有 1024 个 chunk 共享同一份 location table,
 * 如果每写一个 chunk 都重写整 header, 每次都要 fsync, 性能差且原子性弱. session 模式在内存累积所有
 * chunk 的 location table 与 sector data, 仅在 close() 时一次落盘 (tmp + fsync + atomic rename),
 * 同 {@link com.shinoyuki.betterbackup.store.ChunkStore#put} 的 crash 安全模型.
 *
 * <p><b>简化的 sector 分配</b>: 本 writer 只服务 restore 阶段, 调用方保证 mcaFile 不存在或为空,
 * 不处理 fragmentation / overwrite / sector reuse. {@code nextFreeSector} 从 2 起 (跳过 8 KiB header),
 * 每写一个 chunk 单调递增. 不维护 free list, 不做 GC. 这是 MVP 决策, 因为 restore 永远是
 * "从 manifest 重建空 region", 不存在中途修改.
 *
 * <p><b>线程安全</b>: 单 session 实例不要求 thread-safe, 调用方自行同步.
 * 跨 .mca 多 session 并发安全 (各自写独立 tmp 文件).
 */
public final class RegionFileSlotWriter implements Closeable {

    private static final int SECTOR_BYTES = 4096;
    private static final int LENGTH_HEADER_BYTES = 4;
    private static final int LOCATION_TABLE_BYTES = SECTOR_BYTES;
    private static final int TIMESTAMP_TABLE_BYTES = SECTOR_BYTES;
    private static final int HEADER_SECTOR_COUNT = 2;
    private static final int MAX_SECTOR_COUNT_PER_CHUNK = 0xFF;
    private static final int LOCATION_ENTRY_COUNT = 1024;

    private final Path mcaFile;
    private final int[] locations;
    private final ByteArrayOutputStream chunkData;
    // external chunk 的 .mcc 写入推迟到 close(): mcc 文件路径 -> 外置内容. 用 LinkedHashMap
    // 保证写盘顺序确定, 便于诊断. close() 把这些 .mcc 落盘排在 .mca atomic rename **之前**,
    // 保证 .mca 提交时其 stub 指向的 .mcc 必已存在 (修崩溃窗口, 见 close() 注释).
    private final Map<Path, byte[]> pendingExternal;
    private int nextFreeSector;
    private boolean closed;

    private RegionFileSlotWriter(Path mcaFile) {
        this.mcaFile = mcaFile;
        this.locations = new int[LOCATION_ENTRY_COUNT];
        this.chunkData = new ByteArrayOutputStream(SECTOR_BYTES * 16);
        this.pendingExternal = new LinkedHashMap<>();
        this.nextFreeSector = HEADER_SECTOR_COUNT;
        this.closed = false;
    }

    /**
     * 开启一个写 session. 调用方负责保证 mcaFile 父目录存在, 文件本身可不存在 (close 时会创建).
     * 若 mcaFile 已存在, close 时会 atomic 覆盖 (REPLACE_EXISTING).
     */
    public static RegionFileSlotWriter open(Path mcaFile) throws IOException {
        Files.createDirectories(mcaFile.getParent());
        return new RegionFileSlotWriter(mcaFile);
    }

    /**
     * 写入一个 chunk 的 raw slot 字节. rawSlotBytes 格式 = "1 byte compression type + zlib payload",
     * 跟 {@link RegionFileSlotReader#readSlot} 返回值严格一致, 不含 4 byte length header
     * (length header 是 region file 内部 layout, 由本方法自己写入).
     *
     * <p><b>external (.mcc) 还原</b>: rawSlotBytes 首字节高位 0x80 置位时 (见
     * {@link ChunkPayloadCodec}), 该 chunk 原本是超大 chunk, 数据外置在 .mcc 文件.
     * 本方法把 1 byte stub ("(compType|0x80)") 写入 slot, 把剩余字节作为 .mcc 内容
     * 在 close() 时落盘, 还原 vanilla 的 stub + 外置文件两段布局, round-trip 字节级一致.
     *
     * @param localX        0-31, region 内 local x (= chunkX &amp; 31)
     * @param localZ        0-31, region 内 local z (= chunkZ &amp; 31)
     * @param rawSlotBytes  非空, sectorCount &lt;= 255 (vanilla format 限制单 chunk 不超过 1 MiB)
     * @throws IllegalArgumentException 坐标越界或 rawSlotBytes 为空
     * @throws IOException              sectorCount 超 255 (vanilla format 上限) 或 session 已 close
     */
    public void writeChunk(int localX, int localZ, byte[] rawSlotBytes) throws IOException {
        if (closed) {
            throw new IOException("writer already closed: " + mcaFile);
        }
        if (localX < 0 || localX > 31 || localZ < 0 || localZ > 31) {
            throw new IllegalArgumentException("local coords out of range: " + localX + "," + localZ);
        }
        if (rawSlotBytes == null || rawSlotBytes.length == 0) {
            throw new IllegalArgumentException("rawSlotBytes empty for slot " + localX + "," + localZ);
        }

        byte[] inSlotPayload = rawSlotBytes;
        if (ChunkPayloadCodec.isExternal(rawSlotBytes[0])) {
            // external store 对象 = "(compType|0x80) byte + .mcc 内容". slot 内只放
            // 1 byte stub, 剩余字节排队写到外置文件. 退化为 1-byte external store 对象
            // (.mcc 空) 不合法: vanilla 永远把至少一段压缩数据写进 .mcc.
            if (rawSlotBytes.length < 2) {
                throw new IllegalArgumentException("external chunk store object too short ("
                        + rawSlotBytes.length + " bytes) for slot " + localX + "," + localZ);
            }
            Path mccFile = RegionFileSlotReader.mccPathFor(mcaFile, localX, localZ);
            byte[] external = new byte[rawSlotBytes.length - 1];
            System.arraycopy(rawSlotBytes, 1, external, 0, external.length);
            pendingExternal.put(mccFile, external);
            inSlotPayload = new byte[]{rawSlotBytes[0]};
        }

        int payloadLen = inSlotPayload.length;
        int totalSlotBytes = LENGTH_HEADER_BYTES + payloadLen;
        // ceil(totalSlotBytes / SECTOR_BYTES), 不用浮点避免精度坑
        int sectorCount = (totalSlotBytes + SECTOR_BYTES - 1) / SECTOR_BYTES;
        if (sectorCount > MAX_SECTOR_COUNT_PER_CHUNK) {
            throw new IOException("chunk " + localX + "," + localZ + " too large: "
                    + totalSlotBytes + " bytes (sectorCount=" + sectorCount + " > 255)");
        }

        int entryIndex = localX + localZ * 32;
        // location entry: high 24 bit = sector offset, low 8 bit = sector count
        locations[entryIndex] = (nextFreeSector << 8) | (sectorCount & 0xFF);

        // 写 4 byte BE length + payload + zero padding 到 sector 边界
        ByteBuffer lenBuf = ByteBuffer.allocate(LENGTH_HEADER_BYTES);
        lenBuf.putInt(payloadLen);
        chunkData.write(lenBuf.array(), 0, LENGTH_HEADER_BYTES);
        chunkData.write(inSlotPayload, 0, payloadLen);
        int paddedSlotBytes = sectorCount * SECTOR_BYTES;
        int paddingBytes = paddedSlotBytes - totalSlotBytes;
        for (int i = 0; i < paddingBytes; i++) {
            chunkData.write(0);
        }

        nextFreeSector += sectorCount;
    }

    /**
     * 把 location table + timestamps (全 0) + chunkData 一次性写到 tmp 文件,
     * fsync 后 atomic rename 到 mcaFile. 跟 ChunkStore.put 同 crash 安全模型.
     *
     * <p>幂等: 重复 close 是 no-op, 不抛异常.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        Path tmp = mcaFile.resolveSibling(mcaFile.getFileName() + ".tmp");
        // 整块 try: 任一步失败都清掉 .mca tmp 不留孤儿 (已 atomic rename 的 .mcc 留下, 见下方分析为无害)
        try {
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {

                // 1. location table: 1024 个 BE int
                ByteBuffer locBuf = ByteBuffer.allocate(LOCATION_TABLE_BYTES);
                for (int i = 0; i < LOCATION_ENTRY_COUNT; i++) {
                    locBuf.putInt(locations[i]);
                }
                locBuf.flip();
                writeFully(ch, locBuf);

                // 2. timestamps: MVP 写全 0 (vanilla 仅作 dirty 检测, restore 出来的 region 全新无意义)
                ByteBuffer tsBuf = ByteBuffer.allocate(TIMESTAMP_TABLE_BYTES);
                // ByteBuffer 默认 0 填充, 直接 flip 到 limit 即可
                tsBuf.position(TIMESTAMP_TABLE_BYTES);
                tsBuf.flip();
                writeFully(ch, tsBuf);

                // 3. 累积的 chunk data sectors
                byte[] data = chunkData.toByteArray();
                if (data.length > 0) {
                    writeFully(ch, ByteBuffer.wrap(data));
                }

                // fsync: 字节落盘后才能 rename, kill -9 重启不会出现 rename 完但内容半空
                ch.force(true);
            }

            // external chunk 的 .mcc 落盘排在 .mca rename **之前** (修崩溃窗口): .mca 一旦原子
            // 提交, 它 external slot 的 stub 指向的 .mcc 必已存在, 不会出现"新 .mca 指向尚未落盘
            // 的 .mcc"导致该 chunk 读不出 / 丢失的窗口。本 writer 只服务 restore: 非目标 chunk 的
            // .mcc 是同字节回写 (no-op), 目标 chunk 的 .mcc 即恢复内容, 故 rename 之前的窗口
            // (旧 .mca + 新 .mcc) 读到的都是一致或恢复值, 不产生不一致。崩在 rename 前: .mca tmp
            // 被 catch 清掉, 已对齐的 .mcc 留下无害 (旧 .mca inline chunk 视其为孤儿; 旧 .mca
            // external chunk 视其为同字节/恢复值)。每个 .mcc 仍走 tmp + fsync + atomic rename。
            for (Map.Entry<Path, byte[]> entry : pendingExternal.entrySet()) {
                writeExternalAtomic(entry.getKey(), entry.getValue());
            }

            Files.move(tmp, mcaFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static void writeExternalAtomic(Path mccFile, byte[] content) throws IOException {
        Path tmp = mccFile.resolveSibling(mccFile.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeFully(ch, ByteBuffer.wrap(content));
            ch.force(true);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, mccFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.write(buf);
            if (n < 0) {
                throw new IOException("unexpected negative write count");
            }
        }
    }
}
