package com.shinoyuki.betterbackup.nbt;

/**
 * 独立最小 NBT tag 模型的公共父类. 子类一一对应 {@link NbtType} 声明的类型,
 * 仅覆盖 manifest 序列化所需 (End/Byte/Int/Long/ByteArray/String/List/Compound)。
 *
 * <p>这是 vanilla {@code net.minecraft.nbt.Tag} 的纯 Java 等价替身, 让 manifest 编解码
 * (以及离线 CLI) 不依赖 Minecraft 运行时。{@link #typeId()} 返回的 id 写入 NBT 流的
 * type 字节, 与 vanilla 编号一致, 因此 {@link NbtWriter} 写出的字节能被 vanilla
 * {@code NbtIo.readCompressed} 读, 反之亦然 (互读由 mod 测试源集的 interop 测试钉死)。
 */
public abstract sealed class NbtTag
        permits NbtByte, NbtInt, NbtLong, NbtByteArray, NbtString, NbtList, NbtCompound {

    /** 该 tag 的 vanilla type id (写入流的 type 字节). */
    public abstract int typeId();
}
