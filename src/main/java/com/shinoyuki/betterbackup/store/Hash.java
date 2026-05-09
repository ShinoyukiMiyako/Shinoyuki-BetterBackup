package com.shinoyuki.betterbackup.store;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable byte[] wrapper 实现 equals/hashCode, 给 Set/Map 当 key.
 * <p>
 * 默认 xxh128 = 16 bytes (128 bits). 也支持 sha256 (32 bytes) 等其他算法长度.
 * <p>
 * 内部 bytes 是构造时的 clone 副本, bytes() getter 返回 clone, 保证不可变.
 */
public final class Hash {

    private final byte[] bytes;
    private final int hashCode;

    public Hash(byte[] bytes) {
        Objects.requireNonNull(bytes, "hash bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("hash cannot be empty");
        }
        this.bytes = bytes.clone();
        this.hashCode = Arrays.hashCode(this.bytes);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    /** lowercase hex 表示, 跟 store 文件名一致. */
    public String toHex() {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static Hash fromHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("hex length must be even, got " + hex.length());
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex char in " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return new Hash(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Hash other)) {
            return false;
        }
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return toHex();
    }
}
