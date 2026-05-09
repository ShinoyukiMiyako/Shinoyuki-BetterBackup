package com.shinoyuki.betterbackup.store;

import net.openhft.hashing.LongTupleHashFunction;

import java.nio.ByteBuffer;

/**
 * xxh128 实现 (OpenHFT zero-allocation-hashing). 纯 Java, 跨 JVM 字节恒等输出.
 * 跟 PrimeBackup 同款选型, 比 SHA256 快 5-10x, 碰撞概率 10^-19 量级 (实际比硬盘
 * bit flip 还低数个数量级).
 */
public final class Xxh128HashFunction implements HashFunction {

    private final LongTupleHashFunction xxh128 = LongTupleHashFunction.xx128();

    @Override
    public Hash hash(byte[] input) {
        long[] tuple = new long[2];
        xxh128.hashBytes(input, tuple);
        byte[] out = new byte[16];
        ByteBuffer.wrap(out).putLong(tuple[0]).putLong(tuple[1]);
        return new Hash(out);
    }

    @Override
    public String name() {
        return "xxh128";
    }

    @Override
    public int outputLength() {
        return 16;
    }
}
