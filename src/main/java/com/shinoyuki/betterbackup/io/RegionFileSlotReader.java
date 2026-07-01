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
 *
 * <p><b>撕裂读防御</b>: worker 读 slot 时可能撞上 vanilla IOWorker 对同一扇区的
 * 原地重写, 读到新旧混合字节. 垃圾入库后 hash 自洽, verify 查不出, 只在 restore 时炸.
 * 必须在采集点拦截, 见 {@link #readSlot} 的双重防御 (header entry 前后比对 +
 * inflate 完整性校验), 命中抛 {@link TornReadException}.
 *
 * <p><b>超大 chunk (.mcc)</b>: compression type 字节高位 0x80 置位 = 数据外置在同
 * 目录 c.&lt;chunkX&gt;.&lt;chunkZ&gt;.mcc, slot 内只有 1 byte stub. reader 识别后
 * 读外置文件拼成完整 store 对象, 见 {@link ChunkPayloadCodec} 的布局定义.
 */
public final class RegionFileSlotReader {

    private static final int SECTOR_BYTES = 4096;
    private static final int LENGTH_HEADER_BYTES = 4;
    private static final int REGION_SHIFT = 5;
    private static final int REGION_SIZE = 1 << REGION_SHIFT;

    private RegionFileSlotReader() {
    }

    /**
     * 给 ChunkPos 直接定位 .mca 文件路径. region 命名: r.&lt;rx&gt;.&lt;rz&gt;.mca,
     * 其中 rx = chunkX &gt;&gt; 5, rz = chunkZ &gt;&gt; 5 (一个 region = 32x32 chunks).
     */
    public static Path mcaPathFor(Path regionDir, int chunkX, int chunkZ) {
        int rx = chunkX >> REGION_SHIFT;
        int rz = chunkZ >> REGION_SHIFT;
        return regionDir.resolve("r." + rx + "." + rz + ".mca");
    }

    /**
     * 给 .mca 文件 + region 内 local 坐标算回外置 .mcc 文件路径.
     * .mcc 用绝对 chunk 坐标命名 (vanilla: c.&lt;chunkX&gt;.&lt;chunkZ&gt;.mcc),
     * 绝对坐标由 .mca 文件名里的 region 坐标 + local 坐标推回.
     */
    public static Path mccPathFor(Path mcaFile, int localX, int localZ) {
        long rxrz = regionCoordsFromName(mcaFile);
        int rx = (int) (rxrz >> 32);
        int rz = (int) rxrz;
        int chunkX = (rx << REGION_SHIFT) + localX;
        int chunkZ = (rz << REGION_SHIFT) + localZ;
        return mcaFile.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }

    /**
     * 解析 r.&lt;rx&gt;.&lt;rz&gt;.mca 文件名为 region 坐标 (rx, rz). baseline 全量扫描枚举
     * region 文件后用它把 region 内 local slot 还原成绝对 chunk 坐标.
     *
     * @throws IllegalArgumentException 文件名不符合 region 命名约定
     */
    public static RegionCoords parseRegionCoords(Path mcaFile) {
        long packed = regionCoordsFromName(mcaFile);
        return new RegionCoords((int) (packed >> 32), (int) packed);
    }

    /** region 文件坐标. */
    public record RegionCoords(int rx, int rz) {
    }

    /** 从 r.&lt;rx&gt;.&lt;rz&gt;.mca 文件名解析 region 坐标, 打包成 (rx&lt;&lt;32)|rz. */
    private static long regionCoordsFromName(Path mcaFile) {
        String name = mcaFile.getFileName().toString();
        if (!name.startsWith("r.") || !name.endsWith(".mca")) {
            throw new IllegalArgumentException("not a region file name: " + name);
        }
        String mid = name.substring(2, name.length() - 4);
        int dot = mid.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("malformed region file name: " + name);
        }
        int rx = Integer.parseInt(mid.substring(0, dot));
        int rz = Integer.parseInt(mid.substring(dot + 1));
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** 用 chunk world 坐标读 (会内部计算 .mca 路径 + local x/z). */
    public static byte[] readChunk(Path regionDir, int chunkX, int chunkZ) throws IOException {
        Path mcaFile = mcaPathFor(regionDir, chunkX, chunkZ);
        if (!Files.exists(mcaFile)) {
            return null;
        }
        return readSlot(mcaFile, chunkX & (REGION_SIZE - 1), chunkZ & (REGION_SIZE - 1));
    }

    /**
     * 读 .mca 文件里指定 (localX, localZ) 的 chunk slot. 返回 raw "compression type +
     * 压缩 payload" 字节 (external 形态下含 .mcc 内容), 或 null 当 slot 为空 (chunk 没生成过).
     *
     * <p>撕裂读双重防御:
     * <ol>
     *   <li>读 payload 前后各读一次 4 byte location entry, 不一致 = 期间 chunk 被搬迁
     *       (vanilla 重写换了扇区), 整次读作废抛 {@link TornReadException}</li>
     *   <li>对组装好的 store 对象做 inflate 完整性校验, 校验和不符 = 新旧字节混合,
     *       抛 {@link TornReadException}</li>
     * </ol>
     *
     * @param localX 0-31
     * @param localZ 0-31
     * @throws TornReadException 检出撕裂读 (调用方应延后重试, 不得入库)
     */
    public static byte[] readSlot(Path mcaFile, int localX, int localZ) throws IOException {
        return readSlotInternal(mcaFile, localX, localZ, true);
    }

    /**
     * 宽容读: 跟 {@link #readSlot} 一样还原 external (.mcc) 形态, 但<b>跳过撕裂读重定位检测与
     * inflate 完整性校验</b>。离线场景 (无并发 writer, 撕裂读不会发生) 保留 region 内非目标 chunk
     * 时用 —— 一个邻居 chunk 字节损坏不该阻断对健康目标 chunk 的部分回退; 损坏字节原样透传保留,
     * 既不就地恶化也不放大。结构性损坏 (sector 越界 / 长度非法 / external .mcc 物理缺失) 仍抛
     * IOException, 由调用方按 slot 粒度容错 (跳过该 slot 并告警)。
     */
    public static byte[] readSlotLenient(Path mcaFile, int localX, int localZ) throws IOException {
        return readSlotInternal(mcaFile, localX, localZ, false);
    }

    private static byte[] readSlotInternal(Path mcaFile, int localX, int localZ, boolean validate)
            throws IOException {
        if (localX < 0 || localX > 31 || localZ < 0 || localZ > 31) {
            throw new IllegalArgumentException("local coords out of range: " + localX + "," + localZ);
        }
        try (FileChannel ch = FileChannel.open(mcaFile, StandardOpenOption.READ)) {
            int headerByteOffset = 4 * (localX + localZ * REGION_SIZE);

            int locationEntry = readLocationEntry(ch, headerByteOffset);
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

            // 撕裂读防御 step 1 (仅 validate): 读 payload 后再读一次 location entry. vanilla 原地
            // 重写一个 chunk 时会改 location table (新扇区分配), 前后不一致即证明读期间发生搬迁,
            // 读到的 payload 可能横跨新旧两份数据. 离线宽容读跳过 (无并发 writer)。
            if (validate) {
                int locationEntryAfter = readLocationEntry(ch, headerByteOffset);
                if (locationEntryAfter != locationEntry) {
                    throw new TornReadException("chunk slot relocated during read at " + mcaFile
                            + " slot=(" + localX + "," + localZ + "): location entry "
                            + Integer.toHexString(locationEntry) + " -> " + Integer.toHexString(locationEntryAfter));
                }
            }

            byte[] storeObject = resolveExternal(mcaFile, localX, localZ, payload);

            // 撕裂读防御 step 2 (仅 validate): inflate 完整性校验. header entry 没变但 vanilla 在同
            // 扇区原地覆写部分字节 (size 不变的更新) 仍会让我们读到新旧混合 payload, 只有 zlib/gzip
            // 校验和能抓出来. 离线宽容读跳过 —— 损坏邻居原样保留而非阻断目标回退。
            if (validate) {
                ChunkPayloadCodec.validateIntegrity(storeObject);
            }

            return storeObject;
        }
    }

    /**
     * 识别 external (.mcc) 形态. payload 首字节高位 0x80 置位时, slot 内只有 1 byte
     * stub, 真实压缩数据在同目录 c.&lt;x&gt;.&lt;z&gt;.mcc. 读外置文件拼成完整 store
     * 对象 = "stub byte + .mcc 内容". inline 形态直接返回 payload 不变.
     */
    private static byte[] resolveExternal(Path mcaFile, int localX, int localZ, byte[] payload) throws IOException {
        if (!ChunkPayloadCodec.isExternal(payload[0])) {
            return payload;
        }
        // external stub 的 in-slot payload 必须恰好 1 byte (vanilla 写 length=1).
        // 长度不符说明 slot 同时含 internal + external 数据 = 损坏.
        if (payload.length != 1) {
            throw new IOException("external chunk stub has unexpected in-slot length " + payload.length
                    + " in " + mcaFile + " slot=(" + localX + "," + localZ + ")");
        }
        Path mccFile = mccPathFor(mcaFile, localX, localZ);
        if (!Files.isRegularFile(mccFile)) {
            throw new IOException("external chunk file missing: " + mccFile
                    + " (slot stub present in " + mcaFile + ")");
        }
        byte[] external = Files.readAllBytes(mccFile);
        byte[] storeObject = new byte[1 + external.length];
        storeObject[0] = payload[0];
        System.arraycopy(external, 0, storeObject, 1, external.length);
        return storeObject;
    }

    /** 读单个 4 byte big-endian location entry. EOF 视为 0 (空 slot). */
    private static int readLocationEntry(FileChannel ch, int headerByteOffset) throws IOException {
        ByteBuffer headerBuf = ByteBuffer.allocate(4);
        int headerRead = readFully(ch, headerBuf, headerByteOffset);
        if (headerRead < 4) {
            return 0;
        }
        headerBuf.flip();
        return headerBuf.getInt();
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
