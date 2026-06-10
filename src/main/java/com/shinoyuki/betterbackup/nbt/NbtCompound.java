package com.shinoyuki.betterbackup.nbt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * TAG_Compound: 命名 tag 的字典. 流布局 = 重复 (1 byte type + UTF key + payload) 直到
 * 遇到 END(0)。manifest 的根与各级嵌套都是它。
 *
 * <p>内部用 {@link LinkedHashMap} 保留插入顺序, 让本编解码写出的字节对同一逻辑内容确定
 * (vanilla CompoundTag 底层是 HashMap, key 顺序不定; NBT compound 语义本就与 key 顺序
 * 无关, 故 vanilla 仍能读本类输出, interop 不受影响)。
 *
 * <p>getter 缺键时返回类型的零值 (getInt→0 / getByte→0 / getString→"" / getByteArray→空),
 * 跟 vanilla CompoundTag 一致。manifest 的 fromNbt 依赖此行为兼容旧 schema (缺字段=默认值)。
 * 业务必需而不可缺的键由 manifest 层自己校验, 不在此层吞。
 */
public final class NbtCompound extends NbtTag {

    private final Map<String, NbtTag> entries = new LinkedHashMap<>();

    public void put(String key, NbtTag tag) {
        entries.put(key, tag);
    }

    public void putInt(String key, int value) {
        entries.put(key, new NbtInt(value));
    }

    public void putLong(String key, long value) {
        entries.put(key, new NbtLong(value));
    }

    public void putByte(String key, byte value) {
        entries.put(key, new NbtByte(value));
    }

    public void putBoolean(String key, boolean value) {
        entries.put(key, new NbtByte((byte) (value ? 1 : 0)));
    }

    public void putString(String key, String value) {
        entries.put(key, new NbtString(value));
    }

    public void putByteArray(String key, byte[] value) {
        entries.put(key, new NbtByteArray(value));
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    /** key 存在且其 tag type id == 期望 type 才返回 true (跟 vanilla contains(key, id) 一致)。 */
    public boolean contains(String key, int typeId) {
        NbtTag tag = entries.get(key);
        return tag != null && tag.typeId() == typeId;
    }

    public NbtTag remove(String key) {
        return entries.remove(key);
    }

    public Set<String> keySet() {
        return entries.keySet();
    }

    public NbtTag get(String key) {
        return entries.get(key);
    }

    public int getInt(String key) {
        return entries.get(key) instanceof NbtInt t ? t.value() : 0;
    }

    public long getLong(String key) {
        return entries.get(key) instanceof NbtLong t ? t.value() : 0L;
    }

    public byte getByte(String key) {
        return entries.get(key) instanceof NbtByte t ? t.value() : 0;
    }

    public boolean getBoolean(String key) {
        return getByte(key) != 0;
    }

    public String getString(String key) {
        return entries.get(key) instanceof NbtString t ? t.value() : "";
    }

    public byte[] getByteArray(String key) {
        return entries.get(key) instanceof NbtByteArray t ? t.value() : new byte[0];
    }

    /** 缺键或非 compound 时返回新空 compound (跟 vanilla 一致, 让上层"缺段=空"自然成立)。 */
    public NbtCompound getCompound(String key) {
        return entries.get(key) instanceof NbtCompound t ? t : new NbtCompound();
    }

    /**
     * 取一个 list, 仅当其元素 type == 期望 type (或 list 为空) 时返回, 否则返回空 list。
     * 跟 vanilla getList(key, id) 语义一致 (类型不符返回空, 不抛)。
     */
    public NbtList getList(String key, int elementTypeId) {
        if (entries.get(key) instanceof NbtList list
                && (list.size() == 0 || list.elementType() == elementTypeId)) {
            return list;
        }
        return new NbtList();
    }

    @Override
    public int typeId() {
        return NbtType.COMPOUND;
    }
}
