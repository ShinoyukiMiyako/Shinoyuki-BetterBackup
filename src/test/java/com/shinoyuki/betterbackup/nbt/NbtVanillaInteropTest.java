package com.shinoyuki.betterbackup.nbt;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 独立最小 NBT 编解码与 vanilla {@code NbtIo} 的互读测试 (PLAN Phase E commit 11 硬约束:
 * round-trip 必须与 vanilla NbtIo 输出互读)。
 *
 * <p>这是离线 CLI 不依赖 Minecraft 读 manifest 的正确性根基: 只要本测试通过, mod 侧用
 * {@link NbtWriter} 写出的 manifest 能被 vanilla 读, vanilla {@code NbtIo} 写出的 NBT 也能被
 * {@link NbtReader} 读, 二者字节级互认。本测试在 mod 测试源集 (有 net.minecraft 依赖) 跑,
 * 是唯一能同时拿到两套实现做对拍的地方。
 *
 * <p>判定标准: 把 {@link NbtWriter#writePayload} 的任一 type 编码改错 (例如 Int 写成 short),
 * vanilla 读出来的值就会变, 对应断言必挂。
 */
class NbtVanillaInteropTest {

    @Test
    void writer_output_is_read_by_vanilla_nbtio() throws IOException {
        // 用本编解码构造一个覆盖 manifest 全部 type 的 compound
        NbtCompound root = new NbtCompound();
        root.putInt("version", 1);
        root.putString("snapshotId", "2026-06-10T19-00-00");
        root.putLong("createdAtMillis", 1_700_000_000_123L);
        root.putByte("baselineComplete", (byte) 1);
        byte[] hashBytes = new byte[]{0x00, 0x11, 0x22, (byte) 0xFF, 0x7E, (byte) 0x80, 0x01, 0x02,
                0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A};
        root.putByteArray("levelDat", hashBytes);

        NbtCompound chunksDim = new NbtCompound();
        NbtList list = new NbtList();
        NbtCompound chunkEntry = new NbtCompound();
        chunkEntry.putLong("pos", 0x0000000700000005L); // z=7, x=5
        chunkEntry.putByteArray("hash", hashBytes);
        list.add(chunkEntry);
        chunksDim.put("minecraft:overworld", list);
        root.put("chunks", chunksDim);

        byte[] written = writeWithCodec(root);

        // vanilla 读本编解码写出的字节
        CompoundTag vanilla;
        try (ByteArrayInputStream in = new ByteArrayInputStream(written)) {
            vanilla = NbtIo.readCompressed(in);
        }

        assertEquals(1, vanilla.getInt("version"));
        assertEquals("2026-06-10T19-00-00", vanilla.getString("snapshotId"));
        assertEquals(1_700_000_000_123L, vanilla.getLong("createdAtMillis"));
        assertEquals((byte) 1, vanilla.getByte("baselineComplete"));
        assertArrayEquals(hashBytes, vanilla.getByteArray("levelDat"));

        ListTag vList = vanilla.getCompound("chunks").getList("minecraft:overworld", Tag.TAG_COMPOUND);
        assertEquals(1, vList.size());
        CompoundTag vEntry = vList.getCompound(0);
        assertEquals(0x0000000700000005L, vEntry.getLong("pos"));
        assertArrayEquals(hashBytes, vEntry.getByteArray("hash"));
    }

    @Test
    void vanilla_nbtio_output_is_read_by_reader() throws IOException {
        // 用 vanilla 构造等价 compound 并 writeCompressed
        CompoundTag root = new CompoundTag();
        root.putInt("version", 42);
        root.putString("snapshotId", "vanilla-written");
        root.putLong("worldGameTime", -987654321L); // 负数验证 long 全 64 位
        root.putByte("flag", (byte) 1);
        byte[] hashBytes = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
                0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, (byte) 0x80, (byte) 0x90, (byte) 0xA0, (byte) 0xB0};
        root.put("levelDat", new ByteArrayTag(hashBytes));

        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putLong("pos", 123456789L);
        entry.put("hash", new ByteArrayTag(hashBytes));
        list.add(entry);
        CompoundTag chunksDim = new CompoundTag();
        chunksDim.put("minecraft:the_nether", list);
        root.put("chunks", chunksDim);

        byte[] written;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(root, bos);
            written = bos.toByteArray();
        }

        // 本编解码读 vanilla 写出的字节
        NbtCompound parsed;
        try (ByteArrayInputStream in = new ByteArrayInputStream(written)) {
            parsed = NbtReader.readCompressed(in);
        }

        assertEquals(42, parsed.getInt("version"));
        assertEquals("vanilla-written", parsed.getString("snapshotId"));
        assertEquals(-987654321L, parsed.getLong("worldGameTime"));
        assertEquals((byte) 1, parsed.getByte("flag"));
        assertArrayEquals(hashBytes, parsed.getByteArray("levelDat"));

        NbtList parsedList = parsed.getCompound("chunks").getList("minecraft:the_nether", NbtType.COMPOUND);
        assertEquals(1, parsedList.size());
        NbtCompound parsedEntry = (NbtCompound) parsedList.get(0);
        assertEquals(123456789L, parsedEntry.getLong("pos"));
        assertArrayEquals(hashBytes, parsedEntry.getByteArray("hash"));
    }

    @Test
    void unicode_string_round_trips_through_both() throws IOException {
        // modified UTF-8 边界: 含非 ASCII (中文/补充平面外) 的字符串两侧都要一致
        String s = "维度-minecraft:overworld-é中";
        NbtCompound root = new NbtCompound();
        root.putString("s", s);
        byte[] written = writeWithCodec(root);

        CompoundTag vanilla;
        try (ByteArrayInputStream in = new ByteArrayInputStream(written)) {
            vanilla = NbtIo.readCompressed(in);
        }
        assertEquals(s, vanilla.getString("s"));

        // 再让 vanilla 写, 本编解码读回
        byte[] vanillaBytes;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(vanilla, bos);
            vanillaBytes = bos.toByteArray();
        }
        NbtCompound parsed;
        try (ByteArrayInputStream in = new ByteArrayInputStream(vanillaBytes)) {
            parsed = NbtReader.readCompressed(in);
        }
        assertEquals(s, parsed.getString("s"));
    }

    @Test
    void reader_rejects_unsupported_tag_type() throws IOException {
        // vanilla 写一个含 Double (本编解码未覆盖的 type) 的 compound, reader 必须显式抛而非静默
        CompoundTag root = new CompoundTag();
        root.putDouble("d", 3.14);
        byte[] written;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(root, bos);
            written = bos.toByteArray();
        }
        assertThrows(IOException.class, () -> {
            try (ByteArrayInputStream in = new ByteArrayInputStream(written)) {
                NbtReader.readCompressed(in);
            }
        });
    }

    private static byte[] writeWithCodec(NbtCompound root) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            NbtWriter.writeCompressed(root, bos);
            return bos.toByteArray();
        }
    }
}
