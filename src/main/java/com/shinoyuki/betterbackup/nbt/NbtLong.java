package com.shinoyuki.betterbackup.nbt;

/** TAG_Long: 64-bit big-endian. manifest 用它表示 createdAtMillis / worldGameTime / pos / 字节计数. */
public final class NbtLong extends NbtTag {

    private final long value;

    public NbtLong(long value) {
        this.value = value;
    }

    public long value() {
        return value;
    }

    @Override
    public int typeId() {
        return NbtType.LONG;
    }
}
