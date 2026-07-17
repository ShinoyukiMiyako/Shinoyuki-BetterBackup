package com.shinoyuki.betterbackup;

import com.shinoyuki.betterbackup.baseline.BaselineProgress;
import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterbackup.diagnostic.PrometheusExporter;
import com.shinoyuki.betterbackup.integration.BackupListenerBridge;
import com.shinoyuki.betterbackup.integration.PipelineDegradedHandler;
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
    private static volatile DiagnosticLogger DIAGNOSTIC_LOGGER;
    private static volatile BetterBackupMetrics METRICS;
    private static volatile PrometheusExporter EXPORTER;
    private static volatile BaselineProgress BASELINE_PROGRESS;
    private static volatile PipelineDegradedHandler PIPELINE_DEGRADED_HANDLER;
    // store 后台初始化 (issue #3 异步化) 完成且 worker/scheduler 已启动后才置 true.
    // isInstalled() 只表示 "已接线, 关停需要拆"; isReady() 才表示 "store 可安全读写".
    private static volatile boolean STORE_READY;

    public static void install(ChunkStore store,
                               CurrentSnapshotState snapshotState,
                               BackupContext context,
                               BlockingQueue<BackupTask> queue,
                               List<BackupWorker> workers,
                               List<Thread> workerThreads,
                               BackupListenerBridge bridge,
                               SnapshotCreator creator,
                               SnapshotScheduler scheduler,
                               DiagnosticLogger diagnosticLogger,
                               BetterBackupMetrics metrics,
                               BaselineProgress baselineProgress) {
        STORE = store;
        SNAPSHOT_STATE = snapshotState;
        CONTEXT = context;
        QUEUE = queue;
        WORKERS = workers;
        WORKER_THREADS = workerThreads;
        BRIDGE = bridge;
        CREATOR = creator;
        SCHEDULER = scheduler;
        DIAGNOSTIC_LOGGER = diagnosticLogger;
        METRICS = metrics;
        BASELINE_PROGRESS = baselineProgress;
    }

    public static void uninstall() {
        STORE_READY = false;
        STORE = null;
        SNAPSHOT_STATE = null;
        CONTEXT = null;
        QUEUE = null;
        WORKERS = null;
        WORKER_THREADS = null;
        BRIDGE = null;
        CREATOR = null;
        SCHEDULER = null;
        DIAGNOSTIC_LOGGER = null;
        METRICS = null;
        EXPORTER = null;
        BASELINE_PROGRESS = null;
        PIPELINE_DEGRADED_HANDLER = null;
    }

    public static void setExporter(PrometheusExporter exporter) {
        EXPORTER = exporter;
    }

    public static void setPipelineDegradedHandler(PipelineDegradedHandler handler) {
        PIPELINE_DEGRADED_HANDLER = handler;
    }

    public static PipelineDegradedHandler pipelineDegradedHandler() {
        return PIPELINE_DEGRADED_HANDLER;
    }

    public static boolean isInstalled() {
        return STORE != null;
    }

    /** store 后台初始化完成、worker/scheduler 已启动. 触碰 store 对象数据的入口据此门控. */
    public static boolean isReady() {
        return STORE != null && STORE_READY;
    }

    public static void setStoreReady(boolean ready) {
        STORE_READY = ready;
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

    public static DiagnosticLogger diagnosticLogger() {
        return DIAGNOSTIC_LOGGER;
    }

    public static BetterBackupMetrics metrics() {
        return METRICS;
    }

    public static PrometheusExporter exporter() {
        return EXPORTER;
    }

    public static BaselineProgress baselineProgress() {
        return BASELINE_PROGRESS;
    }

    private BetterBackupCore() {
    }
}
