package com.shinoyuki.betterbackup.restore;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChunkSlotNbtCodec} round-trip 测试: 把已知 chunk NBT 按 .mca slot store 对象
 * 格式 (compType byte + 压缩 payload) 压成字节, 再用 codec 解回, 断言字段逐一相等.
 *
 * <p>覆盖三种 vanilla compression type (1=gzip / 2=zlib / 3=none) 与 external flag
 * (0x80 置位) 的剥离. external 形态在 codec 看来与 inline 唯一区别就是 compType 高位置位
 * 而 base type 相同, 故构造一个 base=zlib|0x80 的对象验证高位被正确剥掉.
 *
 * <p>判定标准: 把 {@link ChunkSlotNbtCodec#decode} 的 payload 偏移改成从第 0 字节起 (漏剥
 * compType byte), 或把 0x80 剥除逻辑去掉 (导致 baseType 算成 0x82 落入 default 抛错), 本测试
 * 对应用例必挂.
 */
class ChunkSlotNbtCodecTest {

    private static final int GZIP = 1;
    private static final int ZLIB = 2;
    private static final int NONE = 3;
    private static final int EXTERNAL_FLAG = 0x80;

    /** 构造一个覆盖嵌套结构的 chunk NBT (模拟 vanilla chunk 根 compound 的若干典型字段). */
    private static CompoundTag sampleChunkTag() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3465);
        root.putInt("xPos", 5);
        root.putInt("zPos", 7);
        root.putString("Status", "minecraft:full");
        root.putLong("InhabitedTime", 1234567L);
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) -4);
        sections.add(section);
        root.put("sections", sections);
        return root;
    }

    private static byte[] uncompressedNbt(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            NbtIo.write(tag, dos);
        }
        return bos.toByteArray();
    }

    /** 按 store 对象格式拼 "compType byte + 压缩 payload". */
    private static byte[] buildStoreObject(int compTypeByte, CompoundTag tag) throws IOException {
        byte[] raw = uncompressedNbt(tag);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(compTypeByte);
        int baseType = compTypeByte & ~EXTERNAL_FLAG & 0xFF;
        switch (baseType) {
            case GZIP -> {
                try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                    gz.write(raw);
                }
            }
            case ZLIB -> {
                try (DeflaterOutputStream df = new DeflaterOutputStream(bos, new Deflater())) {
                    df.write(raw);
                }
            }
            case NONE -> bos.write(raw);
            default -> throw new IllegalArgumentException("test compType " + baseType);
        }
        return bos.toByteArray();
    }

    private static void assertSampleFields(CompoundTag decoded) {
        assertEquals(3465, decoded.getInt("DataVersion"));
        assertEquals(5, decoded.getInt("xPos"));
        assertEquals(7, decoded.getInt("zPos"));
        assertEquals("minecraft:full", decoded.getString("Status"));
        assertEquals(1234567L, decoded.getLong("InhabitedTime"));
        ListTag sections = decoded.getList("sections", Tag.TAG_COMPOUND);
        assertEquals(1, sections.size());
        assertEquals((byte) -4, sections.getCompound(0).getByte("Y"));
    }

    @Test
    void zlib_inline_round_trips() throws IOException {
        byte[] storeObject = buildStoreObject(ZLIB, sampleChunkTag());
        assertSampleFields(ChunkSlotNbtCodec.decode(storeObject));
    }

    @Test
    void gzip_inline_round_trips() throws IOException {
        byte[] storeObject = buildStoreObject(GZIP, sampleChunkTag());
        assertSampleFields(ChunkSlotNbtCodec.decode(storeObject));
    }

    @Test
    void none_inline_round_trips() throws IOException {
        byte[] storeObject = buildStoreObject(NONE, sampleChunkTag());
        assertSampleFields(ChunkSlotNbtCodec.decode(storeObject));
    }

    @Test
    void external_flag_is_stripped_before_picking_decompressor() throws IOException {
        // external 形态: compType 高位 0x80 置位, base type 仍是 zlib. codec 必须先剥 0x80
        // 再按 base=zlib 解, 否则 baseType 算成 0x82 落入 default 抛 IllegalArgumentException.
        byte[] storeObject = buildStoreObject(ZLIB | EXTERNAL_FLAG, sampleChunkTag());
        assertSampleFields(ChunkSlotNbtCodec.decode(storeObject));
    }

    @Test
    void hash_byte_array_field_round_trips() throws IOException {
        // chunk NBT 里常含 byte[] (如 block states / heightmaps 的 long[] 这里用 byte[] 代验
        // codec 不破坏二进制 payload). 边界含 0x00 / 0xFF / 0x80.
        CompoundTag tag = sampleChunkTag();
        byte[] blob = new byte[]{0x00, (byte) 0xFF, (byte) 0x80, 0x7F, 0x01, (byte) 0xAB};
        tag.putByteArray("blob", blob);
        byte[] storeObject = buildStoreObject(ZLIB, tag);
        CompoundTag decoded = ChunkSlotNbtCodec.decode(storeObject);
        assertArrayEquals(blob, decoded.getByteArray("blob"));
    }

    @Test
    void empty_store_object_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> ChunkSlotNbtCodec.decode(new byte[0]));
    }

    @Test
    void invalid_compression_type_is_rejected() {
        // compType=4 (非 1/2/3), 不带 external flag, 必须显式抛而非静默返回空 tag.
        assertThrows(IllegalArgumentException.class,
                () -> ChunkSlotNbtCodec.decode(new byte[]{0x04, 0x00, 0x00}));
    }
}
