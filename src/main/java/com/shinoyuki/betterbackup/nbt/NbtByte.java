package com.shinoyuki.betterbackup.nbt;

/** TAG_Byte: 单字节. manifest 用它表示 baselineComplete / suspect 标志 (0/1). */
public final class NbtByte extends NbtTag {

    private final byte value;

    public NbtByte(byte value) {
        this.value = value;
    }

    public byte value() {
        return value;
    }

    @Override
    public int typeId() {
        return NbtType.BYTE;
    }
}
