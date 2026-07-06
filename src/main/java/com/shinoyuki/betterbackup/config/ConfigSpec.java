package com.shinoyuki.betterbackup.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * BetterBackup 配置 schema. 跟 BAS 同款 push/pop 段模式 (ForgeConfigSpec.Builder),
 * 段命名跟 DESIGN §7 表对齐.
 */
public final class ConfigSpec {

    public enum HashAlgorithm {
        XXH128,
        SHA256,
        BLAKE3
    }

    public enum CompressionAlgorithm {
        NONE,
        ZSTD
    }

    public enum ScheduleMode {
        INTERVAL,
        AFTER_AUTOSAVE,
        MANUAL
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> BACKUP_DIRECTORY;

    public static final ForgeConfigSpec.EnumValue<HashAlgorithm> HASH_ALGORITHM;
    public static final ForgeConfigSpec.EnumValue<CompressionAlgorithm> COMPRESSION_ALGORITHM;
    public static final ForgeConfigSpec.IntValue COMPRESSION_LEVEL;
    public static final ForgeConfigSpec.IntValue MAX_STORE_SIZE_GB;

    public static final ForgeConfigSpec.EnumValue<ScheduleMode> SCHEDULE_MODE;
    public static final ForgeConfigSpec.IntValue INTERVAL_MINUTES;
    public static final ForgeConfigSpec.IntValue DIRTY_CHUNK_THRESHOLD;

    public static final ForgeConfigSpec.BooleanValue RETENTION_ENABLED;
    public static final ForgeConfigSpec.IntValue RETENTION_HOURLY;
    public static final ForgeConfigSpec.IntValue RETENTION_DAILY;
    public static final ForgeConfigSpec.IntValue RETENTION_WEEKLY;
    public static final ForgeConfigSpec.IntValue RETENTION_MONTHLY;

    public static final ForgeConfigSpec.IntValue BACKUP_WORKER_THREADS;

    public static final ForgeConfigSpec.IntValue BASELINE_SCAN_CHUNKS_PER_SECOND;

    public static final ForgeConfigSpec.BooleanValue VERIFY_ON_STARTUP;
    public static final ForgeConfigSpec.BooleanValue VERIFY_ON_SNAPSHOT;

    public static final ForgeConfigSpec.BooleanValue PROMETHEUS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> PROMETHEUS_BIND_ADDRESS;
    public static final ForgeConfigSpec.IntValue PROMETHEUS_PORT;

    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.comment("BetterBackup common configuration (shared across all worlds)").push("general");

        ENABLED = BUILDER
                .comment("Master switch. When false the mod loads but stops listening to BAS events; no backups are created.")
                .define("enabled", true);

        BACKUP_DIRECTORY = BUILDER
                .comment("Directory holding the dedup store + manifests. Path is relative to the server root.",
                         "Default 'backup-store' is sibling to 'world/' to avoid an rsync of world/ silently",
                         "pulling the entire backup store back into the rsync target.")
                .define("backupDirectory", "backup-store");

        BUILDER.pop();

        BUILDER.comment("Content-addressed store: hash + compression").push("storage");

        HASH_ALGORITHM = BUILDER
                .comment("Hash algorithm for chunk slot raw bytes.",
                         "XXH128 (default): pure Java, ~5-10x faster than SHA256, dedup primary key.",
                         "  Same algorithm PrimeBackup uses by default.",
                         "SHA256: cryptographic, slower, useful when you must satisfy compliance / forensic requirements.",
                         "BLAKE3: future option (not implemented in MVP).")
                .defineEnum("hashAlgorithm", HashAlgorithm.XXH128);

        COMPRESSION_ALGORITHM = BUILDER
                .comment("Compression for store entries.",
                         "NONE (default, recommended for chunk path): chunk slots in .mca are already vanilla zlib",
                         "  compressed. Wrapping with zstd is double compression for marginal gain and waste of CPU.",
                         "ZSTD: useful for SavedData / level.dat which are uncompressed.",
                         "MVP applies this setting uniformly; v0.2+ may split per-payload-type.")
                .defineEnum("compressionAlgorithm", CompressionAlgorithm.NONE);

        COMPRESSION_LEVEL = BUILDER
                .comment("Compression level for ZSTD (1=fast, 22=max). Ignored when compressionAlgorithm=NONE.")
                .defineInRange("compressionLevel", 0, 0, 22);

        MAX_STORE_SIZE_GB = BUILDER
                .comment("Soft, one-shot startup trigger on total store size in GB -- NOT a real-time disk quota.",
                         "When startup detects store > this value, it runs retention prune (drop expired manifests)",
                         "then a full GC (reclaim the now-dead objects), once, in the background. Reclaimable bytes",
                         "are capped by the live data your retention policy still protects, so the store may remain",
                         "above this value after GC (you'll get a WARN when that happens). To actually bound the",
                         "ceiling, tighten retention (hourly/daily/weekly/monthly) or shorten the retention window",
                         "and restart periodically -- this is not a ring buffer or hard cap.")
                .defineInRange("maxStoreSizeGB", 500, 1, 100_000);

        BUILDER.pop();

        BUILDER.comment("Snapshot scheduling").push("schedule");

        SCHEDULE_MODE = BUILDER
                .comment("INTERVAL (default): create a snapshot every intervalMinutes.",
                         "AFTER_AUTOSAVE: create a snapshot after each vanilla autosave cycle once",
                         "  dirtyChunkThreshold accumulated changes are seen.",
                         "MANUAL: only on '/betterbackup snapshot create' command.")
                .defineEnum("mode", ScheduleMode.INTERVAL);

        INTERVAL_MINUTES = BUILDER
                .comment("Minutes between automatic snapshots when mode=INTERVAL.")
                .defineInRange("intervalMinutes", 120, 5, 1440);

        DIRTY_CHUNK_THRESHOLD = BUILDER
                .comment("Minimum number of dirty chunks accumulated before mode=AFTER_AUTOSAVE creates a snapshot.")
                .defineInRange("dirtyChunkThreshold", 5_000, 1, 1_000_000);

        BUILDER.pop();

        BUILDER.comment("Retention policy: how many of each rolling category to keep").push("retention");

        RETENTION_ENABLED = BUILDER
                .comment("Master opt-in switch for rolling retention pruning. Default false: every snapshot is",
                         "kept forever, so upgrading never starts deleting history implicitly. When false the",
                         "hourly/daily/weekly/monthly quotas below are ignored. Pruning is irreversible; before",
                         "turning this on for an existing store run '/betterbackup retention preview' to see",
                         "exactly which snapshots the policy would delete.")
                .define("enabled", false);

        RETENTION_HOURLY = BUILDER
                .comment("Hourly snapshots to retain (most recent N). Applied only when retention.enabled=true.",
                         "336 = full hourly coverage for 14 days; manifest overhead grows ~30MB per retained",
                         "snapshot on 1M+ chunk worlds until manifest delta encoding lands (v0.2).")
                .defineInRange("hourly", 24, 0, 2000);

        RETENTION_DAILY = BUILDER
                .comment("Daily snapshots to retain (the 00:00 snapshot of each day). Applied only when",
                         "retention.enabled=true.")
                .defineInRange("daily", 7, 0, 90);

        RETENTION_WEEKLY = BUILDER
                .comment("Weekly snapshots to retain (Monday 00:00). Applied only when retention.enabled=true.")
                .defineInRange("weekly", 4, 0, 52);

        RETENTION_MONTHLY = BUILDER
                .comment("Monthly snapshots to retain (1st of month 00:00). Applied only when",
                         "retention.enabled=true.")
                .defineInRange("monthly", 12, 0, 120);

        BUILDER.pop();

        BUILDER.comment("Worker thread pool").push("workers");

        BACKUP_WORKER_THREADS = BUILDER
                .comment("Threads dedicated to hashing chunk slot bytes + writing dedup store entries.",
                         "CPU-bound (hash + optional compression). Default 2 covers typical loads.",
                         "Bump to 4 if you run a large server (~60+ players) with frequent autosave bursts.")
                .defineInRange("backupWorkerThreads", 2, 1, 16);

        BUILDER.pop();

        BUILDER.comment("Baseline full scan: one-time sweep of every existing chunk so snapshots taken",
                        "before the mod has seen a chunk load still contain the full world.").push("baseline");

        BASELINE_SCAN_CHUNKS_PER_SECOND = BUILDER
                .comment("Rate limit for the baseline full scan, in chunk slots read per second.",
                         "The scan reads raw bytes off disk for every existing chunk in every dimension's",
                         "region/ and entities/ directories; the limit keeps it from saturating disk IO",
                         "while players are online. Default 50 finishes a ~1M chunk world in roughly 5.5 hours.",
                         "Progress is checkpointed per region file, so a restart resumes where it left off.")
                .defineInRange("scanChunksPerSecond", 50, 1, 100_000);

        BUILDER.pop();

        BUILDER.comment("Safety / integrity").push("safety");

        VERIFY_ON_STARTUP = BUILDER
                .comment("On server start, clean up orphan .tmp files left by a previous crash (kill -9 mid-write).",
                         "Deep integrity checking (per-object rehash + reference completeness) is not done at",
                         "startup; run it offline via the CLI: 'java -jar betterbackup.jar fsck|verify --store <dir>'.")
                .define("verifyOnStartup", true);

        VERIFY_ON_SNAPSHOT = BUILDER
                .comment("After each snapshot, recompute hashes for the entries newly written this snapshot",
                         "(write-path self-check). A mismatch marks only this snapshot failed (.incomplete);",
                         "it does not trip degraded mode. Slow; default off. Covers this snapshot's writes only,",
                         "not the whole store -- use the CLI fsck for a full-store integrity scan.")
                .define("verifyOnSnapshot", false);

        BUILDER.pop();

        BUILDER.comment("Prometheus metrics HTTP exporter (independent from BAS exporter on 9450)").push("prometheus");

        PROMETHEUS_ENABLED = BUILDER
                .comment("Enable Prometheus metrics HTTP exporter. Default false: opt-in.",
                         "When enabled, server starts an HTTP listener at bindAddress:port/metrics.")
                .define("enabled", false);

        PROMETHEUS_BIND_ADDRESS = BUILDER
                .comment("HTTP server bind address. Default 0.0.0.0 (open). For public servers use a firewall",
                         "to restrict the port, or change to 127.0.0.1 for local-only scraping.")
                .define("bindAddress", "0.0.0.0");

        PROMETHEUS_PORT = BUILDER
                .comment("HTTP server port. Default 9451 (avoids 9450 used by BAS).")
                .defineInRange("port", 9451, 1024, 65535);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private ConfigSpec() {
    }
}
