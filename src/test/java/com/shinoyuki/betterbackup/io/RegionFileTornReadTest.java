package com.shinoyuki.betterbackup.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B 撕裂读防御 + .mcc 外置 chunk 测试.
 *
 * <p>三类断言:
 * <ol>
 *   <li>撕裂读注入: 把 slot 内 zlib 压缩字节原地改坏 (模拟 vanilla IOWorker 在同扇区
 *       原地重写产生的新旧混合字节), {@link RegionFileSlotReader#readSlot} 必须抛
 *       {@link TornReadException}, 垃圾不得返回</li>
 *   <li>inflate 校验零误杀: 正常 zlib chunk 读回必须成功且字节级一致</li>
 *   <li>.mcc round-trip: external store 对象经 writer 写 stub + .mcc, 再 reader 读回,
 *       store 对象逐字节一致; 且磁盘上的 .mca slot 与 .mcc 文件布局符合 vanilla</li>
 * </ol>
 */
class RegionFileTornReadTest {

    private static final int SECTOR_BYTES = 4096;
    private static final int LENGTH_HEADER_BYTES = 4;

    @Test
    void valid_zlib_chunk_passes_inflate_check_no_false_positive(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        byte[] plaintext = new byte[20000];
        new Random(7).nextBytes(plaintext);
        byte[] payload = ChunkPayloadFixtures.zlibPayload(plaintext);

        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(3, 9, payload);
        }

        byte[] read = assertDoesNotThrow(() -> RegionFileSlotReader.readSlot(mca, 3, 9));
        assertArrayEquals(payload, read, "valid zlib chunk must round-trip byte-exact");
    }

    @Test
    void torn_read_corrupted_zlib_payload_is_rejected(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        byte[] plaintext = new byte[16000];
        new Random(11).nextBytes(plaintext);
        byte[] payload = ChunkPayloadFixtures.zlibPayload(plaintext);

        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(0, 0, payload);
        }

        // 原地破坏 zlib 压缩流尾部若干字节, 不改 location entry / length header.
        // 这精确模拟 vanilla IOWorker 在同扇区原地重写 (size 不变) 时, worker 读到
        // 新旧字节混合: location entry 前后一致 (size 没变), 只有 inflate 校验和能抓出.
        corruptCompressedTail(mca, /*localX*/0, /*localZ*/0, payload.length);

        TornReadException ex = assertThrows(TornReadException.class,
                () -> RegionFileSlotReader.readSlot(mca, 0, 0),
                "corrupted zlib payload must be rejected as torn read, not returned as garbage");
        assertTrue(ex.getMessage().contains("inflate"),
                "should be flagged by inflate integrity check, msg=" + ex.getMessage());
    }

    @Test
    void torn_read_relocated_location_entry_is_rejected(@TempDir Path tempDir) throws IOException {
        // 模拟 "两步读之间 chunk 搬迁": 直接构造一个 .mca, 然后用一个会在 payload 读后
        // 改写 location entry 的并发写手, 验证前后比对生效. 这里用确定性手法:
        // 写两个 chunk 占不同扇区, 读 slot A 时把 A 的 location entry 指向 B 的扇区前
        // 不变, 读后改成 0 -> 前后不一致触发 relocation 检测.
        Path mca = tempDir.resolve("r.0.0.mca");
        byte[] payloadA = ChunkPayloadFixtures.zlibPayload("chunk-a-bytes".getBytes());
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(1, 1, payloadA);
        }

        // 用一条并发写线程, 在 reader 读 payload 期间持续翻转 location entry. 读 slot
        // 反复跑直到命中前后不一致 (或确认正常读). 由于翻转高频, 极大概率某次读被拦.
        // 为保证确定性, 改用直接断言: relocation 检测的逻辑由 location entry 前后值决定,
        // 这里构造一个读后值变化的场景靠手工改盘做不到单线程内注入, 故用并发探测.
        int localX = 1;
        int localZ = 1;
        int headerOffset = 4 * (localX + localZ * 32);
        int original = readLocationEntryRaw(mca, headerOffset);
        assertTrue(original != 0, "slot must be occupied");

        boolean[] stop = {false};
        boolean[] caught = {false};
        Thread flipper = new Thread(() -> {
            try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
                while (!stop[0]) {
                    // 在 original 与 0 之间高频翻转 location entry, 制造 reader 两步读不一致
                    writeLocationEntryRaw(ch, headerOffset, 0);
                    writeLocationEntryRaw(ch, headerOffset, original);
                }
            } catch (IOException ignored) {
                // 写线程 IO 异常不影响断言, 主线程负责判定
            }
        });
        flipper.setDaemon(true);
        flipper.start();
        try {
            // 多次尝试读: relocation 命中即抛 TornReadException, 视为防御生效.
            // valid 读也可能偶发成功 (翻转窗口外), 不成功就再试.
            for (int i = 0; i < 5000 && !caught[0]; i++) {
                try {
                    RegionFileSlotReader.readSlot(mca, localX, localZ);
                } catch (TornReadException e) {
                    caught[0] = true;
                } catch (IOException structural) {
                    // entry=0 时 readSlot 返回 null 不抛; 其他结构异常忽略, 继续探测
                }
            }
        } finally {
            stop[0] = true;
            try {
                flipper.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(caught[0], "concurrent location-entry relocation must be caught as torn read");
    }

    @Test
    void external_mcc_chunk_round_trips_byte_exact(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        // external store 对象 = "(compType|0x80) byte + .mcc 内容". 用真实 zlib 内容
        // 让 reader 拼回后 inflate 校验也能过.
        byte[] mccContent = ChunkPayloadFixtures.deflate(bigPlaintext());
        byte compressionByte = (byte) (ChunkPayloadFixtures.COMPRESSION_ZLIB | 0x80);
        byte[] storeObject = new byte[1 + mccContent.length];
        storeObject[0] = compressionByte;
        System.arraycopy(mccContent, 0, storeObject, 1, mccContent.length);

        int localX = 12;
        int localZ = 20;
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(localX, localZ, storeObject);
        }

        // 磁盘布局校验: slot 内只有 1 byte stub (in-slot length=1), .mcc 文件存在且内容
        // == mccContent (raw, 无 length 无 compression byte).
        assertSlotStubLayout(mca, localX, localZ, compressionByte);
        Path mcc = RegionFileSlotReader.mccPathFor(mca, localX, localZ);
        assertTrue(Files.isRegularFile(mcc), "external .mcc file must be written: " + mcc);
        assertArrayEquals(mccContent, Files.readAllBytes(mcc), ".mcc raw content must match exactly");

        // reader 读回完整 store 对象, 逐字节比对.
        byte[] read = RegionFileSlotReader.readSlot(mca, localX, localZ);
        assertNotNull(read);
        assertArrayEquals(storeObject, read, "external chunk store object must round-trip byte-exact");
    }

    @Test
    void external_mcc_uses_absolute_chunk_coords_in_filename(@TempDir Path tempDir) throws IOException {
        // region r.1.2 -> chunk (1*32+5, 2*32+7) = (37, 71); .mcc 必须叫 c.37.71.mcc
        Path mca = tempDir.resolve("r.1.2.mca");
        byte[] mccContent = ChunkPayloadFixtures.deflate("external-coords".getBytes());
        byte[] storeObject = new byte[1 + mccContent.length];
        storeObject[0] = (byte) (ChunkPayloadFixtures.COMPRESSION_ZLIB | 0x80);
        System.arraycopy(mccContent, 0, storeObject, 1, mccContent.length);

        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(5, 7, storeObject);
        }

        Path expectedMcc = tempDir.resolve("c.37.71.mcc");
        assertTrue(Files.isRegularFile(expectedMcc),
                "external file must use absolute chunk coords: expected " + expectedMcc);
        assertArrayEquals(storeObject, RegionFileSlotReader.readSlot(mca, 5, 7));
    }

    @Test
    void external_stub_with_missing_mcc_file_throws(@TempDir Path tempDir) throws IOException {
        Path mca = tempDir.resolve("r.0.0.mca");
        byte[] mccContent = ChunkPayloadFixtures.deflate("payload".getBytes());
        byte[] storeObject = new byte[1 + mccContent.length];
        storeObject[0] = (byte) (ChunkPayloadFixtures.COMPRESSION_ZLIB | 0x80);
        System.arraycopy(mccContent, 0, storeObject, 1, mccContent.length);

        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(0, 0, storeObject);
        }
        // 删掉外置文件, 模拟备份只存了 stub (Phase B 之前的 bug). reader 必须报错而非
        // 静默返回残缺数据.
        Path mcc = RegionFileSlotReader.mccPathFor(mca, 0, 0);
        Files.delete(mcc);

        IOException ex = assertThrows(IOException.class, () -> RegionFileSlotReader.readSlot(mca, 0, 0));
        assertTrue(ex.getMessage().contains("external chunk file missing"),
                "msg=" + ex.getMessage());
    }

    // ---- helpers ----

    private static byte[] bigPlaintext() {
        byte[] data = new byte[40000];
        new Random(99).nextBytes(data);
        return data;
    }

    /**
     * 把指定 slot 的 zlib 压缩流尾部 8 字节翻转 (XOR 0xFF), 不动 location entry / length.
     * inflate 到末段会因 Adler-32 校验和不符抛 ZipException.
     */
    private static void corruptCompressedTail(Path mca, int localX, int localZ, int payloadLen) throws IOException {
        int headerOffset = 4 * (localX + localZ * 32);
        int locationEntry = readLocationEntryRaw(mca, headerOffset);
        int sectorOffset = locationEntry >>> 8;
        long payloadStart = (long) sectorOffset * SECTOR_BYTES + LENGTH_HEADER_BYTES;
        // 压缩流在 compression type byte (payloadStart) 之后; 末尾即 Adler-32 区.
        long corruptAt = payloadStart + payloadLen - 8;
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(8);
            ch.read(buf, corruptAt);
            buf.flip();
            byte[] b = new byte[8];
            buf.get(b);
            for (int i = 0; i < b.length; i++) {
                b[i] ^= (byte) 0xFF;
            }
            ch.write(ByteBuffer.wrap(b), corruptAt);
        }
    }

    private static int readLocationEntryRaw(Path mca, int headerOffset) throws IOException {
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(4);
            ch.read(buf, headerOffset);
            buf.flip();
            return buf.getInt();
        }
    }

    private static void writeLocationEntryRaw(FileChannel ch, int headerOffset, int value) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(value);
        buf.flip();
        ch.write(buf, headerOffset);
    }

    /** 校验 slot 内 in-slot payload 恰为 1 byte stub = compressionByte. */
    private static void assertSlotStubLayout(Path mca, int localX, int localZ, byte compressionByte) throws IOException {
        int headerOffset = 4 * (localX + localZ * 32);
        int locationEntry = readLocationEntryRaw(mca, headerOffset);
        int sectorOffset = locationEntry >>> 8;
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(5);
            ch.read(buf, (long) sectorOffset * SECTOR_BYTES);
            buf.flip();
            int inSlotLength = buf.getInt();
            byte stub = buf.get();
            assertEquals(1, inSlotLength, "external stub in-slot length must be 1");
            assertEquals(compressionByte, stub, "stub byte must be (compType|0x80)");
        }
    }
}
