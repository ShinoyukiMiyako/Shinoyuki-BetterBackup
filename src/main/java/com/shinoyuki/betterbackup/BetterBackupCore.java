package com.shinoyuki.betterbackup;

/**
 * BetterBackup 静态注册中心 (跟 BAS BetterAutoSaveCore 同款模式).
 *
 * <p>Phase 0 仅持 INSTALLED 标志, 后续 Phase 会逐步加:
 * <ul>
 *   <li>Phase 0 commit 5: ChunkSaveListener 引用 (用于关服时 unregister)</li>
 *   <li>Phase 1: ChunkStore / BackupWorker / SnapshotManifest 引用</li>
 *   <li>Phase 2: SnapshotScheduler 引用</li>
 *   <li>Phase 5: PrometheusExporter / BetterBackupMetrics 引用</li>
 * </ul>
 *
 * <p>install / uninstall 仅在 BetterBackupMod 的 ServerStarting / ServerStopping
 * 钩子中调用. isInstalled() 给命令 / listener 守卫使用.
 */
public final class BetterBackupCore {

    private static volatile boolean INSTALLED;

    public static void install() {
        INSTALLED = true;
    }

    public static void uninstall() {
        INSTALLED = false;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    private BetterBackupCore() {
    }
}
