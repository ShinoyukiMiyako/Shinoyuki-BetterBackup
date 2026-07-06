package com.shinoyuki.betterbackup.integration;

import com.shinoyuki.betterautosave.api.ChunkSaveListener;
import com.shinoyuki.betterautosave.api.EntityChunkSaveListener;
import com.shinoyuki.betterautosave.api.SavedDataSaveListener;
import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.worker.BackupTask;
import com.shinoyuki.betterbackup.worker.ChunkBackupTask;
import com.shinoyuki.betterbackup.worker.EntityChunkBackupTask;
import com.shinoyuki.betterbackup.worker.SavedDataBackupTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BAS Listener 桥到 BetterBackup BackupWorker queue.
 *
 * <p>一个实例同时实现 BAS 三类 listener 接口, 注册到 SaveListenerRegistry 后:
 * <ul>
 *   <li>BAS 在 BAS worker 线程 fire</li>
 *   <li>本 listener 立即把 ChunkPos+dim / fileName 包成 BackupTask 入 queue</li>
 *   <li>BetterBackup worker 线程消费 queue 跑 hash + store.put</li>
 * </ul>
 *
 * <p><b>有界队列的反压/降级</b>: queue 有容量上限. 稳态下 worker 消费快于 vanilla 存盘,
 * offer 立即成功; 但消费被长时压实 / 手动 GC 持 store 写锁阻塞时队列会满. 满时先对 BAS
 * worker 线程施加有界反压 (等消费腾位), 超时仍满则降级丢弃本 task 并节流 WARN —— 丢弃的
 * chunk 会在其下次 vanilla 存盘时重新 fire 被采到, 换取队列不无上限堆积致堆压力 / OOM.
 *
 * <p>本 listener 不读 {@code tag} 参数 — DESIGN §2.1 方案 A 直接读 .mca 磁盘字节,
 * 不依赖 BAS 给的内存 NBT.
 */
public final class BackupListenerBridge
        implements ChunkSaveListener, EntityChunkSaveListener, SavedDataSaveListener {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    /** 队列满时对 BAS worker 线程施加的反压等待上限 (毫秒). 超时仍满则降级丢弃本 task. */
    private static final long DEFAULT_OFFER_BACKPRESSURE_MS = 250L;

    /** 丢弃 task 的 WARN 节流: 首次 + 每累计这么多次打一条, 避免刷屏. */
    private static final long DROP_WARN_INTERVAL = 1_000L;

    private final BlockingQueue<BackupTask> queue;
    private final long offerBackpressureMillis;
    private final AtomicLong droppedTasks = new AtomicLong();

    public BackupListenerBridge(BlockingQueue<BackupTask> queue) {
        this(queue, DEFAULT_OFFER_BACKPRESSURE_MS);
    }

    /** 测试入口: 注入反压超时 (0 = 满即降级不阻塞), 便于确定性验证降级路径而不真等. */
    BackupListenerBridge(BlockingQueue<BackupTask> queue, long offerBackpressureMillis) {
        this.queue = queue;
        this.offerBackpressureMillis = offerBackpressureMillis;
    }

    @Override
    public void onChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag) {
        enqueue(new ChunkBackupTask(dimension.location().toString(), pos.toLong()));
    }

    @Override
    public void onEntityChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag) {
        enqueue(new EntityChunkBackupTask(dimension.location().toString(), pos.toLong()));
    }

    @Override
    public void onSavedDataWritten(String fileName, CompoundTag tag) {
        enqueue(new SavedDataBackupTask(fileName));
    }

    /**
     * 入队一个 BackupTask. 有空位直接放; 满时先有界反压等消费腾位, 超时仍满则降级丢弃并节流 WARN.
     */
    private void enqueue(BackupTask task) {
        if (queue.offer(task)) {
            return;
        }
        try {
            if (queue.offer(task, offerBackpressureMillis, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long dropped = droppedTasks.incrementAndGet();
        if (dropped == 1 || dropped % DROP_WARN_INTERVAL == 0) {
            LOGGER.warn("[BetterBackup] backup task queue full; dropped {} task(s) so far. The backup "
                    + "worker is likely blocked by a long compaction/GC holding the store write lock; "
                    + "dropped chunks will be re-captured on their next save.", dropped);
        }
    }

    /** 测试可见: 累计丢弃的 task 数. */
    long droppedTaskCount() {
        return droppedTasks.get();
    }
}
