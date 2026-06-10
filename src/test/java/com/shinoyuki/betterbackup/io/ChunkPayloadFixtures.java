package com.shinoyuki.betterbackup.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;

/**
 * 测试用 chunk slot payload 构造工具. 生成真实 zlib 压缩字节, 让 payload 能通过
 * {@link RegionFileSlotReader} 的 inflate 完整性校验 (compression type 2 = zlib).
 *
 * <p>vanilla chunk slot payload = "1 byte compression type + zlib 压缩 NBT".
 * 这里用 {@link DeflaterOutputStream} 复刻 vanilla 写盘时的 zlib 编码 (跟
 * {@code RegionFileVersion} 的 type 2 一致), 保证 reader 解压不报撕裂读.
 */
public final class ChunkPayloadFixtures {

    public static final int COMPRESSION_ZLIB = 2;

    private ChunkPayloadFixtures() {
    }

    /** 把明文 deflate 成 zlib 流, 前缀 compression type 2, 组成合法 inline slot payload. */
    public static byte[] zlibPayload(byte[] plaintext) {
        byte[] compressed = deflate(plaintext);
        byte[] out = new byte[compressed.length + 1];
        out[0] = (byte) COMPRESSION_ZLIB;
        System.arraycopy(compressed, 0, out, 1, compressed.length);
        return out;
    }

    public static byte[] deflate(byte[] plaintext) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream def = new DeflaterOutputStream(bos)) {
            def.write(plaintext);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory deflate must not fail", e);
        }
        return bos.toByteArray();
    }
}
