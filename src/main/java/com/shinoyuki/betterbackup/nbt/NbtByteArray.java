package com.shinoyuki.betterbackup.nbt;

/**
 * TAG_Byte_Array: int 长度前缀 + 原始字节. manifest 用它存 hash 字节 (16 byte xxh128)。
 *
 * <p>内部字节是构造时 clone, getter 返回 clone, 保证不可变 (跟 {@code Hash} 同语义)。
 */
public final class NbtByteArray extends NbtTag {

    private final byte[] value;

    public NbtByteArray(byte[] value) {
        this.value = value.clone();
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public int typeId() {
        return NbtType.BYTE_ARRAY;
    }
}
