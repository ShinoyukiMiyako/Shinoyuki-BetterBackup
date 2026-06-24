package com.shinoyuki.betterbackup.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * chunk slot payload 的编码常量与完整性校验. 跟 vanilla
 * {@code RegionFileVersion} 的 compression type 编号严格对齐.
 *
 * <p><b>store 对象布局</b> (本类是这套布局的唯一权威定义):
 * store 里每个 chunk 对象 = "1 byte compression type + 压缩数据". compression type
 * 的最高位 0x80 是 vanilla 定义的 external flag:
 * <ul>
 *   <li>0x80 清零 (inline): 后续字节是 slot 内的 zlib/gzip 压缩 NBT. 这是 v0.1
 *       已落盘对象的格式, 不破坏向后可读性</li>
 *   <li>0x80 置位 (external): 该 chunk 是超大 chunk, vanilla 把压缩数据写到同目录
 *       外置文件 c.&lt;chunkX&gt;.&lt;chunkZ&gt;.mcc, slot 内只留 1 byte stub. 此时
 *       store 对象 = "(compressionType|0x80) byte + .mcc 文件原始内容". restore 时
 *       据 0x80 还原 stub + .mcc 两段布局, round-trip 字节级一致</li>
 * </ul>
 */
public final class ChunkPayloadCodec {

    /** vanilla RegionFileVersion: 1=gzip, 2=zlib, 3=none. */
    static final int COMPRESSION_GZIP = 1;
    static final int COMPRESSION_ZLIB = 2;
    static final int COMPRESSION_NONE = 3;

    /** compression type 字节高位: 置位表示数据在外置 .mcc 文件 (vanilla format). */
    static final int EXTERNAL_FLAG = 0x80;

    private ChunkPayloadCodec() {
    }

    /**
     * 校验一个 store 对象的压缩完整性 (fsck 用). 复用 {@link #validateIntegrity} 的
     * inflate 校验逻辑, 不另写一套 zlib 解压。校验通过无返回, 损坏抛 {@link TornReadException}
     * (撕裂读语义在 fsck 上下文等同 "store 对象压缩损坏"), 其余 IO 错误抛 IOException。
     *
     * <p>对外暴露 (public) 是因为离线 CLI 的 fsck 在 {@code cli} 包, 跨包复用本类的校验,
     * 避免重复实现 gzip/zlib inflate 逻辑导致两份语义漂移。
     */
    public static void verifyStoreObject(byte[] storeObject) throws IOException {
        validateIntegrity(storeObject);
    }

    /** 该 compression type 字节是否标记 external (.mcc) 布局. (部分恢复据此清理过期 .mcc) */
    public static boolean isExternal(byte compressionByte) {
        return (compressionByte & EXTERNAL_FLAG) != 0;
    }

    /** 剥掉 external flag, 取回真实 compression type (1/2/3). */
    static int baseCompressionType(byte compressionByte) {
        return compressionByte & ~EXTERNAL_FLAG & 0xFF;
    }

    /**
     * 对 "compression type byte + 压缩数据" 做完整性校验: 按 compression type 选解压器,
     * 把压缩流读到 EOF. 撕裂读 (新旧字节混合) 会让 zlib/gzip 在 inflate 末段抛
     * {@link ZipException} (Adler-32/CRC 校验和不符或 stream 提前结束), 据此判定损坏.
     *
     * <p>本方法只校验不返回解压结果 -- store 存的是压缩字节本身, 不需要解压后的 NBT.
     *
     * @param storeObject 完整 store 对象 (含 external 形态时的拼接结果)
     * @throws TornReadException 解压失败 (撕裂读) 或 compression type 非法
     * @throws IOException       其他 IO 错误
     */
    static void validateIntegrity(byte[] storeObject) throws IOException {
        if (storeObject.length < 1) {
            throw new TornReadException("store object empty, cannot validate compression");
        }
        byte compressionByte = storeObject[0];
        int type = baseCompressionType(compressionByte);
        // external 与 inline 的压缩数据起点都在第 1 字节 (compression byte 之后).
        InputStream compressed = new ByteArrayInputStream(storeObject, 1, storeObject.length - 1);
        InputStream decoder;
        switch (type) {
            case COMPRESSION_GZIP:
                decoder = wrapGzip(compressed);
                break;
            case COMPRESSION_ZLIB:
                decoder = new InflaterInputStream(compressed);
                break;
            case COMPRESSION_NONE:
                // 未压缩 chunk 无 inflate 校验和, 撕裂读只能靠 header entry 前后比对兜底.
                // 这里至少确认有 1 byte 数据, 不做 inflate.
                return;
            default:
                throw new TornReadException("invalid compression type " + type
                        + " (raw byte=0x" + Integer.toHexString(compressionByte & 0xFF) + ")");
        }
        try {
            drainToEnd(decoder);
        } catch (ZipException e) {
            throw new TornReadException("inflate integrity check failed (likely torn read): "
                    + e.getMessage(), e);
        }
    }

    private static InputStream wrapGzip(InputStream compressed) throws IOException {
        try {
            return new GZIPInputStream(compressed);
        } catch (ZipException e) {
            // GZIPInputStream 构造时即读 header, 撕裂读可能在此就炸.
            throw new TornReadException("gzip header invalid (likely torn read): " + e.getMessage(), e);
        }
    }

    private static void drainToEnd(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        while (in.read(buf) >= 0) {
            // 仅消费, inflate 末段的校验和不符会在此循环内抛 ZipException
        }
    }
}
