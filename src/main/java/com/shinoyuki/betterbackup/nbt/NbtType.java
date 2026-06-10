package com.shinoyuki.betterbackup.nbt;

/**
 * vanilla NBT tag type id 常量. 跟 {@code net.minecraft.nbt.Tag} 的 ID_* 严格对齐,
 * 保证本独立编解码与 vanilla {@code NbtIo} 写出的字节互读 (PLAN Phase E commit 11
 * 硬约束: CLI 代码路径零 net.minecraft import, 所以 manifest 序列化不能用 NbtIo).
 *
 * <p>只声明 manifest 实际用到的类型 (End/Byte/Int/Long/ByteArray/String/List/Compound)。
 * manifest schema (见 {@code SnapshotManifest} javadoc) 不含 Short/Float/Double/
 * IntArray/LongArray, 留空即可 -- 真碰到未知 type id 由 reader 显式抛错, 不静默吞。
 */
public final class NbtType {

    public static final int END = 0;
    public static final int BYTE = 1;
    public static final int INT = 3;
    public static final int LONG = 4;
    public static final int BYTE_ARRAY = 7;
    public static final int STRING = 8;
    public static final int LIST = 9;
    public static final int COMPOUND = 10;

    private NbtType() {
    }
}
