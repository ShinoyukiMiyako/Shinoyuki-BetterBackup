package com.shinoyuki.betterbackup.schedule;

import com.shinoyuki.betterbackup.BetterBackupMod;
import org.slf4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 固定 minutes 周期触发 SnapshotTrigger. 单 daemon 线程 ScheduledExecutorService,
 * 第一次触发 = startup + intervalMinutes (不是 startup 立刻触发, 让服务器先稳定).
 *
 * <p><b>线程模型</b>: scheduler 单线程派发, trigger.create() 是 blocking call (在
 * scheduler 线程跑). 如果 SnapshotCreator 跑很久 (drain dirtyMap + write manifest +
 * fsync), 下一次周期会被 "fixed delay" 推后. 这是预期 — DESIGN §2.5 单次 snapshot
 * 5-30s 远小于默认 120 min interval.
 *
 * <p>异常: trigger.create() 抛任何异常都被 ScheduledExecutorService catch + log,
 * 不会中断后续周期.
 */
public final class IntervalScheduler implements SnapshotScheduler {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private final long intervalMinutes;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;

    public IntervalScheduler(long intervalMinutes) {
        if (intervalMinutes < 1) {
            throw new IllegalArgumentException("intervalMinutes must be >= 1");
        }
        this.intervalMinutes = intervalMinutes;
    }

    @Override
    public synchronized void start(SnapshotTrigger trigger) {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BetterBackup-Scheduler");
            t.setDaemon(true);
            return t;
        });
        future = executor.scheduleAtFixedRate(() -> {
            try {
                trigger.create("interval");
            } catch (Throwable t) {
                LOGGER.error("[BetterBackup] scheduled snapshot creation failed", t);
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        LOGGER.info("[BetterBackup] interval scheduler started ({}min period)", intervalMinutes);
    }

    @Override
    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            executor = null;
            LOGGER.info("[BetterBackup] interval scheduler stopped");
        }
    }
}
