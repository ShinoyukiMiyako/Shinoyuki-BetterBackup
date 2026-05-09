package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.BetterBackupMod;
import org.slf4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 备份 worker 线程 (跟 BAS SerializationWorker 同结构).
 *
 * <p>从共享 {@link BlockingQueue} 拉 BackupTask, 在 worker 线程跑 hash + store.put.
 * BAS Listener 在 BAS worker 线程 fire, 把 task 入 queue 立即返回, 不阻塞 BAS.
 *
 * <p><b>异常隔离</b>: task.execute 抛 IOException 仅 log ERROR, 不 propagate
 * (避免单 task 失败 kill worker 线程). Phase 5 加 metrics 时计 failure 计数.
 *
 * <p><b>关服 drain</b>: requestStop() 标记停止, run loop 跑完 queue 剩余 task 后退出.
 * BetterBackupMod onServerStopping 走 join 流程: drainPending → join thread.
 */
public final class BackupWorker implements Runnable {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private final BlockingQueue<BackupTask> queue;
    private final BackupContext context;
    private final String name;
    private volatile boolean running = true;
    private volatile boolean drainedAfterStop;

    public BackupWorker(String name, BlockingQueue<BackupTask> queue, BackupContext context) {
        this.name = name;
        this.queue = queue;
        this.context = context;
    }

    public String name() {
        return name;
    }

    public boolean isDrainedAfterStop() {
        return drainedAfterStop;
    }

    public void requestStop() {
        running = false;
    }

    @Override
    public void run() {
        LOGGER.info("[BetterBackup] worker started: {}", name);
        try {
            while (running || !queue.isEmpty()) {
                BackupTask task;
                try {
                    task = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (task == null) {
                    continue;
                }
                try {
                    task.execute(context);
                } catch (Throwable t) {
                    LOGGER.error("[BetterBackup] worker {} task {} failed",
                            name, task.taskName(), t);
                }
            }
            drainedAfterStop = true;
        } finally {
            LOGGER.info("[BetterBackup] worker stopped: {} (queue={})", name, queue.size());
        }
    }
}
