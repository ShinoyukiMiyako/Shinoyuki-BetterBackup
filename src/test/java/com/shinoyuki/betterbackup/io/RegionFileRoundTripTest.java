package com.shinoyuki.betterbackup.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RegionFileSlotWriter + RegionFileSlotReader 互逆测试. 不依赖 vanilla NBT,
 * 只验证 .mca format 的 layout 正确性 (location table / sector allocation /
 * length header / padding).
 *
 * <p><b>compression type 选取</b>: reader 现在对 inline payload 做 inflate 完整性校验.
 * 校验精确 byte size 的 layout 测试用 compression type 3 (none, 跳过 inflate) 才能
 * 自由控制 payload 大小; 需要走 inflate 路径的测试用 {@link ChunkPayloadFixtures}
 * 生成真实 zlib 字节.
 */
class RegionFileRoundTripTest {

    private static final int COMPRESSION_NONE = 3;

    /** compression type 3 (none): reader 不做 inflate, 用于精确 size 的 layout 测试. */
    private static byte[] makePayload(int compressionType, byte[] data) {
        byte[] out = new byte[data.length + 1];
        out[0] = (byte) compressionType;
        System.arraycopy(data, 0, out, 1, data.length);
        return out;
    }

    /** 给精确 size 的 layout 测试: 在原始字节前缀 compression type 3, 总长 = data.length + 1. */
    private static byte[] noneTypePayload(byte[] data) {
        return makePayload(COMPRESSION_NONE, data);
    }

    @Test
    void single_chunk_round_trip(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        byte[] payload = ChunkPayloadFixtures.zlibPayload("hello world".getBytes());

        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(5, 7, payload);
        }

        byte[] read = RegionFileSlotReader.readSlot(mca, 5, 7);
        assertArrayEquals(payload, read);

        // 其他 slot 应该是空 (locationEntry=0)
        assertNull(RegionFileSlotReader.readSlot(mca, 0, 0));
        assertNull(RegionFileSlotReader.readSlot(mca, 31, 31));
    }

    @Test
    void boundary_local_coords_round_trip(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        byte[] p00 = ChunkPayloadFixtures.zlibPayload(new byte[]{0x11});
        byte[] p3131 = ChunkPayloadFixtures.zlibPayload(new byte[]{0x22, 0x33});

        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(0, 0, p00);
            writer.writeChunk(31, 31, p3131);
        }

        assertArrayEquals(p00, RegionFileSlotReader.readSlot(mca, 0, 0));
        assertArrayEquals(p3131, RegionFileSlotReader.readSlot(mca, 31, 31));
    }

    @Test
    void multi_chunk_payloads_of_varied_sizes(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        Random rnd = new Random(42);
        Map<Integer, byte[]> expected = new HashMap<>();
        // 16 个 chunk, 每个不同 size 跨 sector 边界
        int[] sizes = {1, 100, 4090, 4091, 4092, 8000, 12000, 50000, 5, 7, 4093, 4094, 4095, 4096, 30, 60};
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            for (int i = 0; i < sizes.length; i++) {
                int localX = i & 31;
                int localZ = (i >> 1) & 31;
                int slotIdx = localX + localZ * 32;
                if (expected.containsKey(slotIdx)) {
                    continue; // 同 slot 跳过避免 conflict
                }
                byte[] data = new byte[sizes[i]];
                rnd.nextBytes(data);
                // compression type 3 (none): reader 不 inflate, 随机字节可自由控制精确 size
                byte[] payload = noneTypePayload(data);
                writer.writeChunk(localX, localZ, payload);
                expected.put(slotIdx, payload);
            }
        }
        for (Map.Entry<Integer, byte[]> e : expected.entrySet()) {
            int slotIdx = e.getKey();
            int localX = slotIdx & 31;
            int localZ = (slotIdx >> 5) & 31;
            assertArrayEquals(e.getValue(),
                    RegionFileSlotReader.readSlot(mca, localX, localZ),
                    "slot " + slotIdx + " (size=" + e.getValue().length + ")");
        }
    }

    @Test
    void payload_exactly_filling_sector_round_trips(@TempDir Path tempDir) throws IOException {
        // 4 byte length header + 4092 byte payload = 4096 byte = exactly 1 sector
        Path mca = tempDir.resolve("r.0.0.mca");
        // byte[0] = compression type 3 (none), 其余随机, 总长仍 4092 保持精确 sector math
        byte[] payload = new byte[4092];
        new Random(1).nextBytes(payload);
        payload[0] = COMPRESSION_NONE;

        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(10, 10, payload);
        }
        assertArrayEquals(payload, RegionFileSlotReader.readSlot(mca, 10, 10));
    }

    @Test
    void payload_just_over_sector_boundary_uses_two_sectors(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        // length header (4) + payload (4093) = 4097 byte > 1 sector → 2 sectors
        byte[] payload = new byte[4093];
        new Random(2).nextBytes(payload);
        payload[0] = COMPRESSION_NONE;

        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(0, 0, payload);
        }
        assertArrayEquals(payload, RegionFileSlotReader.readSlot(mca, 0, 0));
    }

    @Test
    void invalid_local_coords_rejected(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeChunk(-1, 0, makePayload(2, new byte[]{1})));
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeChunk(32, 0, makePayload(2, new byte[]{1})));
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeChunk(0, -1, makePayload(2, new byte[]{1})));
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeChunk(0, 32, makePayload(2, new byte[]{1})));
        }
    }

    @Test
    void empty_payload_rejected(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeChunk(0, 0, new byte[0]));
        }
    }

    @Test
    void oversized_payload_rejected(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        // 256 sectors × 4096 = 1 MiB; ceil((4 + payload) / 4096) > 255 时拒绝
        // 即 payload + 4 > 255 * 4096 = 1044480 → payload > 1044476
        byte[] huge = new byte[1044477];
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            assertThrows(IOException.class,
                    () -> writer.writeChunk(0, 0, huge));
        }
    }

    @Test
    void mca_path_for_uses_chunk_to_region_arithmetic() {
        // chunk (0, 0) → r.0.0.mca
        // chunk (31, 31) → r.0.0.mca (region 边界内)
        // chunk (32, 0) → r.1.0.mca
        // chunk (-1, 0) → r.-1.0.mca (区分负数 region)
        Path dir = Path.of("/tmp/region");
        org.junit.jupiter.api.Assertions.assertEquals(
                dir.resolve("r.0.0.mca"), RegionFileSlotReader.mcaPathFor(dir, 0, 0));
        org.junit.jupiter.api.Assertions.assertEquals(
                dir.resolve("r.0.0.mca"), RegionFileSlotReader.mcaPathFor(dir, 31, 31));
        org.junit.jupiter.api.Assertions.assertEquals(
                dir.resolve("r.1.0.mca"), RegionFileSlotReader.mcaPathFor(dir, 32, 0));
        org.junit.jupiter.api.Assertions.assertEquals(
                dir.resolve("r.-1.0.mca"), RegionFileSlotReader.mcaPathFor(dir, -1, 0));
    }
}
