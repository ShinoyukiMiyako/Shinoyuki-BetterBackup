package com.shinoyuki.betterbackup.nbt;

import java.util.ArrayList;
import java.util.List;

/**
 * TAG_List: 同质元素序列. 流布局 = 1 byte 元素 type id + 4 byte int size + 各元素 payload。
 * manifest 用它存 {@code chunks[dim]} 的 {pos, hash} compound 序列。
 *
 * <p>vanilla 约定: 空 list 的元素 type 写 END(0)。本类沿用 -- 加首元素时锁定 type,
 * 后续元素 type 不一致直接抛 (NBT list 必须同质, 不静默接受混合元素)。
 */
public final class NbtList extends NbtTag {

    private final List<NbtTag> elements;
    private int elementType;

    public NbtList() {
        this.elements = new ArrayList<>();
        this.elementType = NbtType.END;
    }

    /** reader 路径用: 已知元素 type + 已读好的元素列表直接构造。 */
    NbtList(int elementType, List<NbtTag> elements) {
        this.elementType = elementType;
        this.elements = elements;
    }

    public void add(NbtTag tag) {
        int t = tag.typeId();
        if (elements.isEmpty()) {
            elementType = t;
        } else if (t != elementType) {
            throw new IllegalArgumentException("NBT list is homogeneous: expected type "
                    + elementType + " but got " + t);
        }
        elements.add(tag);
    }

    public int size() {
        return elements.size();
    }

    public NbtTag get(int index) {
        return elements.get(index);
    }

    public int elementType() {
        return elementType;
    }

    @Override
    public int typeId() {
        return NbtType.LIST;
    }
}
