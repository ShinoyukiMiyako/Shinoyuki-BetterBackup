package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B: 撕裂读触发 ChunkBackupTask 的 "重新标 dirty 延后重试" 路径.
 *
 * <p>核心断言: 读 slot 命中撕裂读时, task 既不把混合垃圾字节入库, 也不静默丢弃, 而是
 * 把自己以 retryAttempt+1 重新 offer 回队列 (队列即 dirty 通道). 超过重试上限后才
 * 放弃本轮并记失败.
 */
class ChunkBackupTaskTornRetryTest {

    private static final String DIM = "minecraft:overworld";
    private static final int SECTOR_BYTES = 4096;
    private static final int LENGTH_HEADER_BYTES = 4;

    private record Harness(
            BackupContext ctx,
            ChunkStore store,
            BlockingQueue<BackupTask> queue,
            BetterBackupMetrics metrics) {
    }

    private static Harness newHarness(Path root) throws IOException {
        // world 与 store 都放在每个测试独占的 @TempDir 子目录下, 互不污染.
        Path worldRoot = root.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = new ChunkStore(root.resolve("store"));
        store.initialize();
        CurrentSnapshotState state = new CurrentSnapshotState();
        WorldPaths paths = new WorldPaths(worldRoot);
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        BlockingQueue<BackupTask> queue = new LinkedBlockingQueue<>();
        BackupContext ctx = new BackupContext(store, state, paths, new Xxh128HashFunction(),
                written, metrics, queue);
        return new Harness(ctx, store, queue, metrics);
    }

    @Test
    void torn_read_requeues_task_and_does_not_store_garbage(@TempDir Path root) throws IOException {
        Harness h = newHarness(root);
        int chunkX = 3;
        int chunkZ = 4;

        writeValidChunk(h.ctx, chunkX, chunkZ);
        corruptChunk(h.ctx, chunkX, chunkZ);

        long packed = ChunkPos.asLong(chunkX, chunkZ);
        new ChunkBackupTask(DIM, packed).execute(h.ctx);

        // 1) 没有任何对象进 store (混合垃圾不得入库)
        assertEquals(0, countStoreObjects(h.store.chunksDir()),
                "torn read must not write any garbage object to store");
        // 2) 不静默丢弃: 队列里出现一个 attempt=1 的重试 task
        assertEquals(1, h.queue.size(), "torn read must requeue exactly one retry task");
        BackupTask retry = h.queue.poll();
        assertNotNull(retry);
        ChunkBackupTask retryChunk = assertInstanceOf(ChunkBackupTask.class, retry);
        assertEquals(1, retryChunk.retryAttempt(), "retry task must carry incremented attempt");
        assertEquals(packed, retryChunk.packedPos());
        assertEquals(DIM, retryChunk.dimensionId());
        // 3) 本轮未记 chunk 失败 (还在重试中, 不是终态失败)
        assertEquals(0, h.metrics.snapshot().chunksFailed(),
                "in-retry torn read must not count as failure yet");
    }

    @Test
    void torn_read_at_max_attempts_gives_up_without_storing(@TempDir Path root) throws IOException {
        Harness h = newHarness(root);
        int chunkX = 1;
        int chunkZ = 1;
        writeValidChunk(h.ctx, chunkX, chunkZ);
        corruptChunk(h.ctx, chunkX, chunkZ);

        long packed = ChunkPos.asLong(chunkX, chunkZ);
        // 已经达到上限的 task: 不应再 requeue, 应记失败并放弃
        new ChunkBackupTask(DIM, packed, ChunkBackupTask.MAX_RETRY_ATTEMPTS).execute(h.ctx);

        assertEquals(0, countStoreObjects(h.store.chunksDir()),
                "at max attempts garbage still must not be stored");
        assertEquals(0, h.queue.size(), "at max attempts no further retry must be enqueued");
        assertEquals(1, h.metrics.snapshot().chunksFailed(),
                "exhausted torn-read retries must count as one failure");
    }

    @Test
    void valid_chunk_stores_and_does_not_requeue(@TempDir Path root) throws IOException {
        Harness h = newHarness(root);
        int chunkX = 7;
        int chunkZ = 8;
        byte[] expected = writeValidChunk(h.ctx, chunkX, chunkZ);

        long packed = ChunkPos.asLong(chunkX, chunkZ);
        new ChunkBackupTask(DIM, packed).execute(h.ctx);

        assertEquals(0, h.queue.size(), "valid chunk must not be requeued");
        assertEquals(1, countStoreObjects(h.store.chunksDir()), "valid chunk must be stored once");
        // store 里那一个对象内容必须等于读回的 slot 字节
        Hash hash = new Xxh128HashFunction().hash(expected);
        assertTrue(h.store.has(hash), "stored object must be addressable by content hash");
    }

    // ---- helpers ----

    /** 在 region/ 下写一个真实 zlib chunk, 返回写进 slot 的 store 对象字节. */
    private static byte[] writeValidChunk(BackupContext ctx, int chunkX, int chunkZ) throws IOException {
        Path regionDir = ctx.paths().regionDir(DIM);
        Files.createDirectories(regionDir);
        Path mca = RegionFileSlotReader.mcaPathFor(regionDir, chunkX, chunkZ);
        byte[] plaintext = new byte[12000];
        new Random(chunkX * 31L + chunkZ).nextBytes(plaintext);
        byte[] payload = ChunkPayloadFixtures.zlibPayload(plaintext);
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mca)) {
            writer.writeChunk(chunkX & 31, chunkZ & 31, payload);
        }
        return payload;
    }

    /** 原地破坏 zlib 压缩尾部, 不动 location entry / length, 制造撕裂读. */
    private static void corruptChunk(BackupContext ctx, int chunkX, int chunkZ) throws IOException {
        Path regionDir = ctx.paths().regionDir(DIM);
        Path mca = RegionFileSlotReader.mcaPathFor(regionDir, chunkX, chunkZ);
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        int headerOffset = 4 * (localX + localZ * 32);
        int locationEntry;
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ)) {
            ByteBuffer h = ByteBuffer.allocate(4);
            ch.read(h, headerOffset);
            h.flip();
            locationEntry = h.getInt();
        }
        int sectorOffset = locationEntry >>> 8;
        long sectorStart = (long) sectorOffset * SECTOR_BYTES;
        // 读 in-slot length 算压缩尾部位置
        int inSlotLength;
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ)) {
            ByteBuffer lb = ByteBuffer.allocate(LENGTH_HEADER_BYTES);
            ch.read(lb, sectorStart);
            lb.flip();
            inSlotLength = lb.getInt();
        }
        long corruptAt = sectorStart + LENGTH_HEADER_BYTES + inSlotLength - 8;
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

    private static long countStoreObjects(Path chunksDir) throws IOException {
        if (!Files.exists(chunksDir)) {
            return 0;
        }
        try (var stream = Files.walk(chunksDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .count();
        }
    }
}
