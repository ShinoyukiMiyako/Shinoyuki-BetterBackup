package com.shinoyuki.betterbackup.nbt;

import java.util.Objects;

/** TAG_String: modified-UTF-8 (Java DataOutput.writeUTF). manifest 用它存 snapshotId. */
public final class NbtString extends NbtTag {

    private final String value;

    public NbtString(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String value() {
        return value;
    }

    @Override
    public int typeId() {
        return NbtType.STRING;
    }
}
