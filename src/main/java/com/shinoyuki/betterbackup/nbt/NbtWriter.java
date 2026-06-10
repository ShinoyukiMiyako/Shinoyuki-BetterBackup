package com.shinoyuki.betterbackup.nbt;

import java.io.BufferedOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 把 {@link NbtCompound} 写成 gzip 压缩 NBT, 字节布局复刻 vanilla
 * {@code NbtIo.writeCompressed(tag, outputStream)}:
 * <pre>
 * GZIP( DataOutputStream(
 *   byte rootType (=COMPOUND 10),
 *   UTF rootName (=空串 ""),
 *   rootPayload
 * ))
 * </pre>
 *
 * <p>各 tag payload 与 vanilla {@code Tag.write} 一致:
 * <ul>
 *   <li>Byte: {@code writeByte}</li>
 *   <li>Int: {@code writeInt} (big-endian)</li>
 *   <li>Long: {@code writeLong} (big-endian)</li>
 *   <li>ByteArray: {@code writeInt(len)} + 原始字节</li>
 *   <li>String: {@code writeUTF} (modified UTF-8, 2 byte 长度前缀)</li>
 *   <li>List: {@code writeByte(elemType)} + {@code writeInt(size)} + 各元素 payload</li>
 *   <li>Compound: 重复 [{@code writeByte(type)} + {@code writeUTF(key)} + payload] + {@code writeByte(END)}</li>
 * </ul>
 *
 * <p>GZIPOutputStream 用 java.util.zip 默认参数, 与 vanilla 相同, round-trip 互读由
 * mod 测试源集的 vanilla NbtIo interop 测试钉死。
 */
public final class NbtWriter {

    private NbtWriter() {
    }

    /** gzip + NBT 写到 out (不关闭 out 的责任在调用方, 但会 finish gzip)。 */
    public static void writeCompressed(NbtCompound root, OutputStream out) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(out)))) {
            writeNamedRoot(root, dos);
        }
    }

    private static void writeNamedRoot(NbtCompound root, DataOutput out) throws IOException {
        out.writeByte(NbtType.COMPOUND);
        out.writeUTF("");
        writePayload(root, out);
    }

    private static void writePayload(NbtTag tag, DataOutput out) throws IOException {
        if (tag instanceof NbtByte t) {
            out.writeByte(t.value());
        } else if (tag instanceof NbtInt t) {
            out.writeInt(t.value());
        } else if (tag instanceof NbtLong t) {
            out.writeLong(t.value());
        } else if (tag instanceof NbtByteArray t) {
            byte[] bytes = t.value();
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (tag instanceof NbtString t) {
            out.writeUTF(t.value());
        } else if (tag instanceof NbtList t) {
            writeListPayload(t, out);
        } else if (tag instanceof NbtCompound t) {
            writeCompoundPayload(t, out);
        } else {
            throw new IOException("unsupported NBT tag for write: " + tag.getClass());
        }
    }

    private static void writeListPayload(NbtList list, DataOutput out) throws IOException {
        out.writeByte(list.elementType());
        out.writeInt(list.size());
        for (int i = 0; i < list.size(); i++) {
            writePayload(list.get(i), out);
        }
    }

    private static void writeCompoundPayload(NbtCompound compound, DataOutput out) throws IOException {
        for (String key : compound.keySet()) {
            NbtTag value = compound.get(key);
            out.writeByte(value.typeId());
            out.writeUTF(key);
            writePayload(value, out);
        }
        out.writeByte(NbtType.END);
    }
}
