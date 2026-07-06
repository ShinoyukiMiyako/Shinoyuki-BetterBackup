package com.shinoyuki.betterbackup.config;

import com.shinoyuki.betterbackup.BetterBackupMod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

/**
 * BetterBackup config getter wrapper. 跟 BAS 同款 volatile 缓存 + onLoad/onReload refresh.
 * volatile 字段供运行时热路径读取, 避免每次都去 ForgeConfigSpec.<value>.get() 走 lookup.
 */
public final class BetterBackupConfig {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private static volatile boolean enabled;
    private static volatile String backupDirectory;

    private static volatile ConfigSpec.HashAlgorithm hashAlgorithm;
    private static volatile ConfigSpec.CompressionAlgorithm compressionAlgorithm;
    private static volatile int compressionLevel;
    private static volatile int maxStoreSizeGB;

    private static volatile ConfigSpec.ScheduleMode scheduleMode;
    private static volatile int intervalMinutes;
    private static volatile int dirtyChunkThreshold;

    private static volatile boolean retentionEnabled;
    private static volatile int retentionHourly;
    private static volatile int retentionDaily;
    private static volatile int retentionWeekly;
    private static volatile int retentionMonthly;

    private static volatile int backupWorkerThreads;

    private static volatile int baselineScanChunksPerSecond;

    private static volatile boolean verifyOnStartup;
    private static volatile boolean verifyOnSnapshot;

    private static volatile boolean prometheusEnabled;
    private static volatile String prometheusBindAddress;
    private static volatile int prometheusPort;

    public static boolean enabled() {
        return enabled;
    }

    public static String backupDirectory() {
        return backupDirectory;
    }

    public static ConfigSpec.HashAlgorithm hashAlgorithm() {
        return hashAlgorithm;
    }

    public static ConfigSpec.CompressionAlgorithm compressionAlgorithm() {
        return compressionAlgorithm;
    }

    public static int compressionLevel() {
        return compressionLevel;
    }

    public static int maxStoreSizeGB() {
        return maxStoreSizeGB;
    }

    public static ConfigSpec.ScheduleMode scheduleMode() {
        return scheduleMode;
    }

    public static int intervalMinutes() {
        return intervalMinutes;
    }

    public static int dirtyChunkThreshold() {
        return dirtyChunkThreshold;
    }

    public static boolean retentionEnabled() {
        return retentionEnabled;
    }

    public static int retentionHourly() {
        return retentionHourly;
    }

    public static int retentionDaily() {
        return retentionDaily;
    }

    public static int retentionWeekly() {
        return retentionWeekly;
    }

    public static int retentionMonthly() {
        return retentionMonthly;
    }

    public static int backupWorkerThreads() {
        return backupWorkerThreads;
    }

    public static int baselineScanChunksPerSecond() {
        return baselineScanChunksPerSecond;
    }

    public static boolean verifyOnStartup() {
        return verifyOnStartup;
    }

    public static boolean verifyOnSnapshot() {
        return verifyOnSnapshot;
    }

    public static boolean prometheusEnabled() {
        return prometheusEnabled;
    }

    public static String prometheusBindAddress() {
        return prometheusBindAddress;
    }

    public static int prometheusPort() {
        return prometheusPort;
    }

    public static void onLoad(ModConfigEvent.Loading event) {
        refresh();
        LOGGER.info("[BetterBackup] config loaded enabled={} hash={} workers={} schedule={}",
                enabled, hashAlgorithm, backupWorkerThreads, scheduleMode);
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        refresh();
        LOGGER.info("[BetterBackup] config reloaded enabled={} hash={} workers={} schedule={}",
                enabled, hashAlgorithm, backupWorkerThreads, scheduleMode);
    }

    private static void refresh() {
        enabled = ConfigSpec.ENABLED.get();
        backupDirectory = ConfigSpec.BACKUP_DIRECTORY.get();
        hashAlgorithm = ConfigSpec.HASH_ALGORITHM.get();
        compressionAlgorithm = ConfigSpec.COMPRESSION_ALGORITHM.get();
        compressionLevel = ConfigSpec.COMPRESSION_LEVEL.get();
        maxStoreSizeGB = ConfigSpec.MAX_STORE_SIZE_GB.get();
        scheduleMode = ConfigSpec.SCHEDULE_MODE.get();
        intervalMinutes = ConfigSpec.INTERVAL_MINUTES.get();
        dirtyChunkThreshold = ConfigSpec.DIRTY_CHUNK_THRESHOLD.get();
        retentionEnabled = ConfigSpec.RETENTION_ENABLED.get();
        retentionHourly = ConfigSpec.RETENTION_HOURLY.get();
        retentionDaily = ConfigSpec.RETENTION_DAILY.get();
        retentionWeekly = ConfigSpec.RETENTION_WEEKLY.get();
        retentionMonthly = ConfigSpec.RETENTION_MONTHLY.get();
        backupWorkerThreads = ConfigSpec.BACKUP_WORKER_THREADS.get();
        baselineScanChunksPerSecond = ConfigSpec.BASELINE_SCAN_CHUNKS_PER_SECOND.get();
        verifyOnStartup = ConfigSpec.VERIFY_ON_STARTUP.get();
        verifyOnSnapshot = ConfigSpec.VERIFY_ON_SNAPSHOT.get();
        prometheusEnabled = ConfigSpec.PROMETHEUS_ENABLED.get();
        prometheusBindAddress = ConfigSpec.PROMETHEUS_BIND_ADDRESS.get();
        prometheusPort = ConfigSpec.PROMETHEUS_PORT.get();
    }

    private BetterBackupConfig() {
    }
}
