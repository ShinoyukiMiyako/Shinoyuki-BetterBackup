package com.shinoyuki.betterbackup.nbt;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 读 {@link NbtWriter} (或 vanilla {@code NbtIo.writeCompressed}) 写出的 gzip 压缩 NBT
 * 成 {@link NbtCompound}。流布局见 {@link NbtWriter}。
 *
 * <p>只解析 manifest 用到的 type (End/Byte/Int/Long/ByteArray/String/List/Compound)。
 * 碰到本编解码未覆盖的 type id 显式抛 {@link IOException}, 不静默跳过 -- manifest 内
 * 不会出现这些 type, 真出现说明文件不是本工具写的或已损坏, 必须让调用方知道。
 */
public final class NbtReader {

    private NbtReader() {
    }

    public static NbtCompound readCompressed(InputStream in) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(in)))) {
            return readNamedRoot(dis);
        }
    }

    private static NbtCompound readNamedRoot(DataInput in) throws IOException {
        int rootType = in.readUnsignedByte();
        if (rootType != NbtType.COMPOUND) {
            throw new IOException("NBT root must be a compound (type 10), got type " + rootType);
        }
        in.readUTF(); // root name, manifest 写空串, 读出丢弃
        return readCompoundPayload(in);
    }

    private static NbtTag readPayload(int typeId, DataInput in) throws IOException {
        switch (typeId) {
            case NbtType.BYTE:
                return new NbtByte(in.readByte());
            case NbtType.INT:
                return new NbtInt(in.readInt());
            case NbtType.LONG:
                return new NbtLong(in.readLong());
            case NbtType.BYTE_ARRAY:
                return readByteArrayPayload(in);
            case NbtType.STRING:
                return new NbtString(in.readUTF());
            case NbtType.LIST:
                return readListPayload(in);
            case NbtType.COMPOUND:
                return readCompoundPayload(in);
            default:
                throw new IOException("unsupported NBT tag type id " + typeId
                        + " (not used by manifest schema)");
        }
    }

    private static NbtByteArray readByteArrayPayload(DataInput in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            throw new IOException("negative byte array length " + len);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new NbtByteArray(bytes);
    }

    private static NbtList readListPayload(DataInput in) throws IOException {
        int elementType = in.readUnsignedByte();
        int size = in.readInt();
        if (size < 0) {
            throw new IOException("negative list size " + size);
        }
        // size>0 但 elementType=END(0) 是非法 NBT (元素无 type 无法解析); vanilla 也拒。
        if (size > 0 && elementType == NbtType.END) {
            throw new IOException("non-empty list has END element type");
        }
        List<NbtTag> elements = new ArrayList<>(Math.min(size, 1024));
        for (int i = 0; i < size; i++) {
            elements.add(readPayload(elementType, in));
        }
        return new NbtList(elementType, elements);
    }

    private static NbtCompound readCompoundPayload(DataInput in) throws IOException {
        NbtCompound compound = new NbtCompound();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == NbtType.END) {
                return compound;
            }
            String key = in.readUTF();
            compound.put(key, readPayload(type, in));
        }
    }
}
