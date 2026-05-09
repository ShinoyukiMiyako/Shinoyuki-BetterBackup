package com.shinoyuki.betterbackup;

import com.shinoyuki.betterbackup.integration.BackupListenerBridge;
import com.shinoyuki.betterbackup.schedule.SnapshotScheduler;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.worker.BackupContext;
import com.shinoyuki.betterbackup.worker.BackupTask;
import com.shinoyuki.betterbackup.worker.BackupWorker;

import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * BetterBackup 静态注册中心 (跟 BAS BetterAutoSaveCore 同款模式).
 *
 * <p>install / uninstall 仅在 BetterBackupMod 的 ServerStarting / ServerStopping
 * 钩子中调用. isInstalled() 给命令 / listener 守卫使用.
 */
public final class BetterBackupCore {

    private static volatile ChunkStore STORE;
    private static volatile CurrentSnapshotState SNAPSHOT_STATE;
    private static volatile BackupContext CONTEXT;
    private static volatile BlockingQueue<BackupTask> QUEUE;
    private static volatile List<BackupWorker> WORKERS;
    private static volatile List<Thread> WORKER_THREADS;
    private static volatile BackupListenerBridge BRIDGE;
    private static volatile SnapshotCreator CREATOR;
    private static volatile SnapshotScheduler SCHEDULER;

    public static void install(ChunkStore store,
                               CurrentSnapshotState snapshotState,
                               BackupContext context,
                               BlockingQueue<BackupTask> queue,
                               List<BackupWorker> workers,
                               List<Thread> workerThreads,
                               BackupListenerBridge bridge,
                               SnapshotCreator creator,
                               SnapshotScheduler scheduler) {
        STORE = store;
        SNAPSHOT_STATE = snapshotState;
        CONTEXT = context;
        QUEUE = queue;
        WORKERS = workers;
        WORKER_THREADS = workerThreads;
        BRIDGE = bridge;
        CREATOR = creator;
        SCHEDULER = scheduler;
    }

    public static void uninstall() {
        STORE = null;
        SNAPSHOT_STATE = null;
        CONTEXT = null;
        QUEUE = null;
        WORKERS = null;
        WORKER_THREADS = null;
        BRIDGE = null;
        CREATOR = null;
        SCHEDULER = null;
    }

    public static boolean isInstalled() {
        return STORE != null;
    }

    public static ChunkStore store() {
        return STORE;
    }

    public static CurrentSnapshotState snapshotState() {
        return SNAPSHOT_STATE;
    }

    public static BackupContext context() {
        return CONTEXT;
    }

    public static BlockingQueue<BackupTask> queue() {
        return QUEUE;
    }

    public static List<BackupWorker> workers() {
        return WORKERS;
    }

    public static List<Thread> workerThreads() {
        return WORKER_THREADS;
    }

    public static BackupListenerBridge bridge() {
        return BRIDGE;
    }

    public static SnapshotCreator creator() {
        return CREATOR;
    }

    public static SnapshotScheduler scheduler() {
        return SCHEDULER;
    }

    private BetterBackupCore() {
    }
}
