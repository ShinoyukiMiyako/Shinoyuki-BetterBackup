package com.shinoyuki.betterbackup.diagnostic;

import com.shinoyuki.betterbackup.BetterBackupCore;
import com.shinoyuki.betterbackup.BetterBackupMod;
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
        long chunkCount = state.chunkCount();
        long entityCount = state.entityChunkCount();
        long savedDataCount = state.savedDataCount();
        int queueDepth = queue.size();

        // 仅在状态有变化时 log, 避免空跑期间日志洪流.
        boolean changed = chunkCount != lastChunkCount
                || entityCount != lastEntityCount
                || savedDataCount != lastSavedDataCount
                || queueDepth > 0;
        if (!changed) {
            return;
        }
        LOGGER.info("[BetterBackup] dirty: chunks={} entity={} savedData={} | queue: {}",
                chunkCount, entityCount, savedDataCount, queueDepth);
        lastChunkCount = chunkCount;
        lastEntityCount = entityCount;
        lastSavedDataCount = savedDataCount;
    }
}
