package com.shinoyuki.betterbackup;

import com.shinoyuki.betterbackup.api.BackupHelloListener;

/**
 * BetterBackup 静态注册中心 (跟 BAS BetterAutoSaveCore 同款模式).
 *
 * <p>Phase 0 持 INSTALLED 标志 + hello listener 引用 (用于关服 unregister).
 * 后续 Phase 会逐步加:
 * <ul>
 *   <li>Phase 1: ChunkStore / BackupWorker / SnapshotManifest 引用</li>
 *   <li>Phase 2: SnapshotScheduler 引用</li>
 *   <li>Phase 5: PrometheusExporter / BetterBackupMetrics 引用</li>
 * </ul>
 */
public final class BetterBackupCore {

    private static volatile boolean INSTALLED;
    private static volatile BackupHelloListener HELLO_LISTENER;

    public static void install(BackupHelloListener helloListener) {
        HELLO_LISTENER = helloListener;
        INSTALLED = true;
    }

    public static void uninstall() {
        INSTALLED = false;
        HELLO_LISTENER = null;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static BackupHelloListener helloListener() {
        return HELLO_LISTENER;
    }

    private BetterBackupCore() {
    }
}
