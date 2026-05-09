package com.shinoyuki.betterbackup.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 读 vanilla .mca region file 里单个 chunk slot 的 raw 压缩字节.
 *
 * <p><b>为什么不用 vanilla {@code RegionFile.getChunkInputStream(ChunkPos)}</b>:
 * vanilla API 返回的是 {@code InflaterInputStream}, 已经解压成 NBT 字节流.
 * BetterBackup 需要的是 zlib 压缩字节本身 (作为 dedup 单元), 否则跨 JVM
 * 重新 inflate + deflate 字节会变. 所以我们自己 parse .mca header (~50 行,
 * format 公开稳定 since alpha).
 *
 * <p><b>.mca 格式</b> (region file format wiki):
 * <ul>
 *   <li>0..4096   bytes: 1024 chunk locations (4 byte each: 3 byte sector offset + 1 byte sector count)</li>
 *   <li>4096..8192 bytes: 1024 timestamps (4 byte each, 仅 dirty 检测用, 我们不读)</li>
 *   <li>8192..    bytes: chunk data sectors, 每 sector = 4 KiB</li>
 *   <li>每个 chunk 的第一个 sector 起始: 4 byte big-endian length, 然后 length 字节
 *       payload (1 byte compression type + 已 zlib 压缩的 NBT)</li>
 * </ul>
 *
 * <p>本 reader 返回的 byte[] 是 "1 byte compression type + zlib 压缩 NBT". 不含
 * 4 byte length header (那是 region file 内部 layout, 不是 chunk 内容). 跟 ChronoVault
 * 同模式, 跨 JVM 字节恒等做 dedup 主键.
 */
public final class RegionFileSlotReader {

    private static final int SECTOR_BYTES = 4096;
    private static final int LENGTH_HEADER_BYTES = 4;

    private RegionFileSlotReader() {
    }

    /**
     * 给 ChunkPos 直接定位 .mca 文件路径. region 命名: r.&lt;rx&gt;.&lt;rz&gt;.mca,
     * 其中 rx = chunkX &gt;&gt; 5, rz = chunkZ &gt;&gt; 5 (一个 region = 32x32 chunks).
     */
    public static Path mcaPathFor(Path regionDir, int chunkX, int chunkZ) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        return regionDir.resolve("r." + rx + "." + rz + ".mca");
    }

    /** 用 chunk world 坐标读 (会内部计算 .mca 路径 + local x/z). */
    public static byte[] readChunk(Path regionDir, int chunkX, int chunkZ) throws IOException {
        Path mcaFile = mcaPathFor(regionDir, chunkX, chunkZ);
        if (!Files.exists(mcaFile)) {
            return null;
        }
        return readSlot(mcaFile, chunkX & 31, chunkZ & 31);
    }

    /**
     * 读 .mca 文件里指定 (localX, localZ) 的 chunk slot. 返回 raw "compression type +
     * 压缩 payload" 字节, 或 null 当 slot 为空 (chunk 没生成过).
     *
     * @param localX 0-31
     * @param localZ 0-31
     */
    public static byte[] readSlot(Path mcaFile, int localX, int localZ) throws IOException {
        if (localX < 0 || localX > 31 || localZ < 0 || localZ > 31) {
            throw new IllegalArgumentException("local coords out of range: " + localX + "," + localZ);
        }
        try (FileChannel ch = FileChannel.open(mcaFile, StandardOpenOption.READ)) {
            int headerByteOffset = 4 * (localX + localZ * 32);

            ByteBuffer headerBuf = ByteBuffer.allocate(4);
            int headerRead = readFully(ch, headerBuf, headerByteOffset);
            if (headerRead < 4) {
                return null;
            }
            headerBuf.flip();
            int locationEntry = headerBuf.getInt();
            if (locationEntry == 0) {
                return null;
            }

            int sectorOffset = (locationEntry >>> 8);
            int sectorCount = locationEntry & 0xFF;
            if (sectorOffset == 0 || sectorCount == 0) {
                return null;
            }

            long sectorByteOffset = (long) sectorOffset * SECTOR_BYTES;
            int sectorByteLen = sectorCount * SECTOR_BYTES;
            if (sectorByteLen < LENGTH_HEADER_BYTES + 1) {
                throw new IOException("sector slot too small in " + mcaFile + ": " + sectorByteLen);
            }

            ByteBuffer dataBuf = ByteBuffer.allocate(sectorByteLen);
            int dataRead = readFully(ch, dataBuf, sectorByteOffset);
            if (dataRead < LENGTH_HEADER_BYTES + 1) {
                throw new IOException("EOF reading chunk slot at " + mcaFile + " sector=" + sectorOffset);
            }
            dataBuf.flip();

            int actualLength = dataBuf.getInt();
            if (actualLength <= 0 || actualLength > sectorByteLen - LENGTH_HEADER_BYTES) {
                throw new IOException("invalid chunk slot length " + actualLength + " in " + mcaFile
                        + " (sectorByteLen=" + sectorByteLen + ")");
            }

            byte[] payload = new byte[actualLength];
            dataBuf.get(payload);
            return payload;
        }
    }

    /** 把 buf 填满, 或者直到 EOF. 返回实际读到的字节数. */
    private static int readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        int total = 0;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                break;
            }
            pos += n;
            total += n;
        }
        return total;
    }
}
