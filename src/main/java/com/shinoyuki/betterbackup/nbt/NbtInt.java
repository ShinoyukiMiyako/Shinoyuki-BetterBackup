package com.shinoyuki.betterbackup.nbt;

/** TAG_Int: 32-bit big-endian. manifest 用它表示 schema version. */
public final class NbtInt extends NbtTag {

    private final int value;

    public NbtInt(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    @Override
    public int typeId() {
        return NbtType.INT;
    }
}
