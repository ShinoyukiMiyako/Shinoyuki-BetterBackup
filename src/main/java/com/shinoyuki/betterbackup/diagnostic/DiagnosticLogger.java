package com.shinoyuki.betterbackup.diagnostic;

import com.shinoyuki.betterbackup.BetterBackupCore;
import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.baseline.DirtyWaterMarkGate;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.worker.BackupTask;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.concurrent.BlockingQueue;

/**
 * 周期性输出 backup pipeline 状态到 log. 频率写死 1200 tick = 60s.
 *
 * <p>Phase 2 minimal 版本仅 log dirty 计数 + queue 深度. Phase 5 metrics commit
 * 接入 BetterBackupMetrics 后扩展为 throughput / latency / dedup ratio 等.
 *
 * <p>实例由 BetterBackupMod onServerStarting 创建并注册到 Forge.EVENT_BUS,
 * onServerStopping 时 unregister.
 */
public final class DiagnosticLogger {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;
    private static final long LOG_EVERY_TICKS = 1200L;

    private long tickCounter;
    private long lastChunkCount;
    private long lastEntityCount;
    private long lastSavedDataCount;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        if (tickCounter % LOG_EVERY_TICKS != 0L) {
            return;
        }
        if (!BetterBackupCore.isInstalled()) {
            return;
        }
        CurrentSnapshotState state = BetterBackupCore.snapshotState();
        BlockingQueue<BackupTask> queue = BetterBackupCore.queue();
        if (state == null || queue == null) {
            return;
        }
        publishGauges(state, queue, BetterBackupCore.metrics());

        long chunkCount = state.chunkCount();
        long entityCount = state.entityChunkCount();
        long savedDataCount = state.savedDataCount();
        int queueDepth = queue.size();
        DirtyWaterMarkGate gate = BetterBackupCore.baselineGate();
        boolean backpressured = gate != null && gate.isBlocked();

        boolean changed = shouldLog(chunkCount, entityCount, savedDataCount, queueDepth, backpressured,
                lastChunkCount, lastEntityCount, lastSavedDataCount);
        if (!changed) {
            return;
        }
        LOGGER.info("[BetterBackup] dirty: chunks={} entity={} savedData={} | queue: {} | backpressure: {}",
                chunkCount, entityCount, savedDataCount, queueDepth, describeBackpressure(gate));
        lastChunkCount = chunkCount;
        lastEntityCount = entityCount;
        lastSavedDataCount = savedDataCount;
    }

    /**
     * 把水位与队列深度推给 metrics. Prometheus 侧的 bbb_dirty_map_size / bbb_queue_depth
     * 靠这一步才有真实读数 —— 排查积压时那两条曲线是唯一的外部证据.
     */
    static void publishGauges(CurrentSnapshotState state, BlockingQueue<BackupTask> queue,
                              BetterBackupMetrics metrics) {
        if (metrics == null) {
            return;
        }
        metrics.setDirtyMapSize(state.size());
        metrics.setQueueDepth(queue.size());
    }

    /**
     * 是否输出本轮心跳. 仅在状态有变化时 log, 避免空跑期间日志洪流; 但背压期间水位与队列
     * 都不动, 而那正是最需要看见的时刻, 故单独放行.
     */
    static boolean shouldLog(long chunkCount, long entityCount, long savedDataCount, int queueDepth,
                             boolean backpressured,
                             long lastChunkCount, long lastEntityCount, long lastSavedDataCount) {
        return chunkCount != lastChunkCount
                || entityCount != lastEntityCount
                || savedDataCount != lastSavedDataCount
                || queueDepth > 0
                || backpressured;
    }

    /** 背压字段. gate 为 null 表示当前没有 baseline 扫描线程可闸, 与"闸门未触发"不是一回事. */
    static String describeBackpressure(DirtyWaterMarkGate gate) {
        if (gate == null) {
            return "n/a";
        }
        return (gate.isBlocked() ? "blocked" : "off") + "(paused " + gate.blockedMillisTotal() / 1000L + "s total)";
    }
}
