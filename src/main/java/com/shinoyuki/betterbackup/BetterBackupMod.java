package com.shinoyuki.betterbackup;

import com.mojang.logging.LogUtils;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterbackup.baseline.BaselineProgress;
import com.shinoyuki.betterbackup.baseline.BaselineScanner;
import com.shinoyuki.betterbackup.baseline.ThrottlingRateLimiter;
import com.shinoyuki.betterbackup.command.BetterBackupCommand;
import com.shinoyuki.betterbackup.config.BetterBackupConfig;
import com.shinoyuki.betterbackup.config.ConfigSpec;
import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterbackup.diagnostic.PrometheusExporter;
import com.shinoyuki.betterbackup.integration.BackupListenerBridge;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.restore.PendingRestoreFlag;
import com.shinoyuki.betterbackup.restore.RestoreFlow;
import com.shinoyuki.betterbackup.safety.StoreLocationCheck;
import com.shinoyuki.betterbackup.schedule.IntervalScheduler;
import com.shinoyuki.betterbackup.schedule.ManualScheduler;
import com.shinoyuki.betterbackup.schedule.SnapshotScheduler;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import com.shinoyuki.betterbackup.worker.BackupContext;
import com.shinoyuki.betterbackup.worker.BackupTask;
import com.shinoyuki.betterbackup.worker.BackupWorker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Phase 1 commit 12: 真正接通 BAS Listener → BackupWorker 管线.
 *
 * <p>onServerStarting 顺序:
 * <ol>
 *   <li>解析 worldRoot + storeRoot → 创建 ChunkStore</li>
 *   <li>实例化 CurrentSnapshotState / WorldPaths / Xxh128HashFunction / BackupContext</li>
 *   <li>实例化 BlockingQueue + N 个 BackupWorker + 启动 thread</li>
 *   <li>实例化 BackupListenerBridge 注册到 BAS 三 channel</li>
 *   <li>install 到 BetterBackupCore</li>
 * </ol>
 *
 * <p>onServerStopping (LOW priority):
 * <ol>
 *   <li>从 BAS unregister bridge (此时 BAS 已经 drain, 不会再 fire)</li>
 *   <li>requestStop 全部 worker</li>
 *   <li>join thread (受 shutdownTimeoutSeconds 限制)</li>
 *   <li>uninstall</li>
 * </ol>
 */
@Mod(BetterBackupMod.MOD_ID)
public final class BetterBackupMod {

    public static final String MOD_ID = "shinoyuki_betterbackup";

    /** 跟 BAS 共享 config 父目录. */
    public static final String SERIES_CONFIG_DIR = "Shinoyuki-Optimize";

    public static final Logger LOGGER = LogUtils.getLogger();

    /** 关服 worker join 总超时 (毫秒). 写死 30s, 后续可考虑接入 BetterBackupConfig. */
    private static final long SHUTDOWN_JOIN_TIMEOUT_MS = 30_000L;

    public BetterBackupMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(BetterBackupConfig::onLoad);
        modBus.addListener(BetterBackupConfig::onReload);

        Path configRoot = FMLPaths.CONFIGDIR.get().resolve(SERIES_CONFIG_DIR).resolve(MOD_ID);
        try {
            Files.createDirectories(configRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create config directory " + configRoot, e);
        }
        String configRelative = SERIES_CONFIG_DIR + "/" + MOD_ID + "/common.toml";
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ConfigSpec.SPEC, configRelative);

        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BetterBackupCommand.register(event.getDispatcher());
    }

    /**
     * 离线 restore 触发点: 在 vanilla loadLevel 之前 fire. 检测 .pending-restore
     * flag, 有的话临时 new ChunkStore + WorldPaths 跑 RestoreFlow 重建 region/
     * entities/ data/ + level.dat. 跑完删 flag, vanilla 接着正常 init world.
     *
     * <p>失败 → log ERROR 不 throw, 不 abort vanilla 启动 (server 起来用户能进
     * 游戏看 log 排查). flag 不删, 用户修复后重启会再跑一次.
     *
     * <p>EventPriority.HIGHEST: 别的 mod 也 hook ServerAboutToStartEvent 时,
     * BetterBackup 必须先跑保证 region 文件已重建.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!BetterBackupConfig.enabled()) {
            return;
        }
        Path worldRoot = event.getServer().getWorldPath(LevelResource.ROOT);

        Optional<String> pendingId;
        try {
            pendingId = PendingRestoreFlag.read(worldRoot);
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] failed to read pending restore flag", e);
            return;
        }
        if (pendingId.isEmpty()) {
            return;
        }
        String snapshotId = pendingId.get();
        LOGGER.warn("[BetterBackup] pending restore detected: {} - rebuilding world before vanilla load",
                snapshotId);

        Path storeRoot = resolveStoreRoot(BetterBackupConfig.backupDirectory());
        ChunkStore store = new ChunkStore(storeRoot);
        try {
            store.initialize();
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] cannot initialize store for restore at {}", storeRoot, e);
            return;
        }
        Path snapshotsDir = storeRoot.resolve("snapshots");
        WorldPaths paths = new WorldPaths(worldRoot);
        RestoreFlow flow = new RestoreFlow(store, paths, snapshotsDir);
        try {
            RestoreFlow.RestoreResult result = flow.restore(snapshotId);
            PendingRestoreFlag.clear(worldRoot);
            LOGGER.info("[BetterBackup] restore complete: chunks={} entity={} savedData={} files={} levelDat={} backupDir={}",
                    result.chunkSlotsRestored(), result.entitySlotsRestored(),
                    result.savedDataFilesRestored(), result.playerDataFilesRestored(),
                    result.levelDatRestored(), result.worldBackupDir());
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] restore FAILED for snapshot {}, flag retained for retry, server will start with current world state",
                    snapshotId, e);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        if (!BetterBackupConfig.enabled()) {
            LOGGER.info("[BetterBackup] disabled in config, skipping startup");
            return;
        }
        LOGGER.info("[BetterBackup] starting for {}", event.getServer().name());

        try {
            Path worldRoot = event.getServer().getWorldPath(LevelResource.ROOT);
            Path storeRoot = resolveStoreRoot(BetterBackupConfig.backupDirectory());

            // store 套娃防护: store 在 world 内会被备份递归吞掉, 且 restore 时
            // moveCurrentWorldToBackup 会连 store 一起搬走自毁备份. 命中告警不 abort,
            // 决策权留给服主, 但必须让其在日志里可见.
            if (StoreLocationCheck.isNestedInWorld(storeRoot, worldRoot)) {
                LOGGER.error("[BetterBackup] backupDirectory ({}) is INSIDE the world directory ({}). "
                                + "This is unsafe: backups will recursively include the store, and a restore "
                                + "will move the store away with the old world. Move backupDirectory outside world/.",
                        storeRoot, worldRoot);
            }

            ChunkStore store = new ChunkStore(storeRoot);
            store.initialize();

            // 启动时清 kill -9 留下的孤儿 .tmp (DESIGN §8 store 文件写一半断电场景).
            // 实际 atomic put (tmp + fsync + rename) 已经防止半写文件被引用, 但
            // 进程被强杀时 tmp 文件可能残留在磁盘, 这里启动时一次性清掉避免占空间.
            if (BetterBackupConfig.verifyOnStartup()) {
                int cleaned = store.cleanupOrphanTmpFiles();
                if (cleaned > 0) {
                    LOGGER.warn("[BetterBackup] cleaned {} orphan .tmp files from store on startup", cleaned);
                }
            }

            CurrentSnapshotState snapshotState = new CurrentSnapshotState();
            WorldPaths paths = new WorldPaths(worldRoot);
            HashFunction hashFunction = new Xxh128HashFunction();
            BetterBackupMetrics metrics = new BetterBackupMetrics();
            Set<Hash> writtenThisWindow = ConcurrentHashMap.newKeySet();

            // baseline 进度先于 creator 创建: creator 需要 baselineCompleteSupplier 给每份
            // manifest 盖 baselineComplete 戳, scanner 跑完才置位. load() 读已记录进度供续传.
            BaselineProgress baselineProgress = new BaselineProgress(storeRoot);
            baselineProgress.load();
            // queue 先于 context 创建: context 持有同一个 queue 引用, chunk/entity task
            // 命中撕裂读时把自己重 offer 回这个 queue 延后重试 (BackupContext.retryQueue).
            BlockingQueue<BackupTask> queue = new LinkedBlockingQueue<>();
            BackupContext context = new BackupContext(store, snapshotState, paths, hashFunction,
                    writtenThisWindow, metrics, queue);

            int threadCount = BetterBackupConfig.backupWorkerThreads();
            List<BackupWorker> workers = new ArrayList<>();
            List<Thread> workerThreads = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                BackupWorker worker = new BackupWorker("BetterBackup-Worker-" + (i + 1), queue, context);
                Thread thread = new Thread(worker, worker.name());
                // daemon=false 跟 BAS 一致, JVM exit 前必须 join 才能确保 in-flight task 不丢.
                thread.setDaemon(false);
                workers.add(worker);
                workerThreads.add(thread);
                thread.start();
            }

            BackupListenerBridge bridge = new BackupListenerBridge(queue);
            SaveListenerRegistry.registerChunk(bridge);
            SaveListenerRegistry.registerEntityChunk(bridge);
            SaveListenerRegistry.registerSavedData(bridge);

            MinecraftServer server = event.getServer();
            SnapshotCreator creator = new SnapshotCreator(store, snapshotState, paths, hashFunction,
                    storeRoot, () -> overworldGameTime(server), writtenThisWindow, metrics,
                    baselineProgress::isComplete);
            SnapshotScheduler scheduler = createScheduler();
            scheduler.start(creator);

            DiagnosticLogger diagnosticLogger = new DiagnosticLogger();
            MinecraftForge.EVENT_BUS.register(diagnosticLogger);

            BetterBackupCore.install(store, snapshotState, context, queue, workers, workerThreads,
                    bridge, creator, scheduler, diagnosticLogger, metrics, baselineProgress);

            startBaselineScan(store, snapshotState, paths, hashFunction, writtenThisWindow, baselineProgress);

            if (BetterBackupConfig.prometheusEnabled()) {
                String bind = BetterBackupConfig.prometheusBindAddress();
                int port = BetterBackupConfig.prometheusPort();
                PrometheusExporter exporter = new PrometheusExporter(metrics, bind, port);
                try {
                    exporter.start();
                    BetterBackupCore.setExporter(exporter);
                } catch (IOException e) {
                    LOGGER.error("[BetterBackup] Prometheus exporter failed to start at {}:{}; disabled this run",
                            bind, port, e);
                }
            }

            LOGGER.info("[BetterBackup]   |- worldRoot: {}", worldRoot);
            LOGGER.info("[BetterBackup]   |- storeRoot: {}", storeRoot);
            LOGGER.info("[BetterBackup]   |- hash: {} compress: {}",
                    hashFunction.name(),
                    BetterBackupConfig.compressionAlgorithm());
            LOGGER.info("[BetterBackup]   |- workers: {} thread(s)", threadCount);
            LOGGER.info("[BetterBackup]   |- schedule: {} (interval={}min)",
                    BetterBackupConfig.scheduleMode(),
                    BetterBackupConfig.intervalMinutes());
            LOGGER.info("[BetterBackup]   `- config: {}/{}/common.toml", SERIES_CONFIG_DIR, MOD_ID);
            LOGGER.info("[BetterBackup] pipeline installed (BAS Listener -> BackupWorker queue + scheduler)");
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] startup failed, mod degraded (no backups will be created)", e);
        }
    }

    /**
     * LOW priority: BAS 默认 NORMAL, 让 BAS 先跑 onServerStopping (drainPending +
     * joinWorkers). 期间 BAS fire 的最后一批 listener 事件 BetterBackup queue
     * 仍能接收. BAS 跑完后 BetterBackup 才 drain 自己的 queue + join 自己的 worker.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onServerStopping(ServerStoppingEvent event) {
        if (!BetterBackupCore.isInstalled()) {
            return;
        }
        LOGGER.info("[BetterBackup] server stopping (LOW priority, after BAS drain)");

        // 先停 exporter (避免抓取期间读半 drain 状态), 再停 scheduler 防新定时.
        PrometheusExporter exporter = BetterBackupCore.exporter();
        if (exporter != null) {
            exporter.stop();
        }
        SnapshotScheduler scheduler = BetterBackupCore.scheduler();
        if (scheduler != null) {
            scheduler.stop();
        }

        DiagnosticLogger diagnosticLogger = BetterBackupCore.diagnosticLogger();
        if (diagnosticLogger != null) {
            MinecraftForge.EVENT_BUS.unregister(diagnosticLogger);
        }

        BackupListenerBridge bridge = BetterBackupCore.bridge();
        if (bridge != null) {
            SaveListenerRegistry.unregisterChunk(bridge);
            SaveListenerRegistry.unregisterEntityChunk(bridge);
            SaveListenerRegistry.unregisterSavedData(bridge);
        }

        List<BackupWorker> workers = BetterBackupCore.workers();
        if (workers != null) {
            for (BackupWorker worker : workers) {
                worker.requestStop();
            }
        }

        List<Thread> threads = BetterBackupCore.workerThreads();
        if (threads != null) {
            long deadline = System.currentTimeMillis() + SHUTDOWN_JOIN_TIMEOUT_MS;
            for (Thread thread : threads) {
                long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                try {
                    thread.join(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("[BetterBackup] interrupted joining worker {}", thread.getName());
                    break;
                }
                if (thread.isAlive()) {
                    LOGGER.warn("[BetterBackup] worker {} did not join within {}ms",
                            thread.getName(), SHUTDOWN_JOIN_TIMEOUT_MS);
                }
            }
        }

        BlockingQueue<BackupTask> queue = BetterBackupCore.queue();
        int leftover = queue != null ? queue.size() : 0;
        if (leftover > 0) {
            LOGGER.warn("[BetterBackup] {} task(s) remained un-processed in queue at shutdown",
                    leftover);
        }

        // 关服 final snapshot: 最后一次抓取本周期内 BAS fire 过的 chunk.
        // SnapshotCreator.create 用 synchronized 串行, 跟 scheduler 已停后无 race.
        SnapshotCreator creator = BetterBackupCore.creator();
        if (creator != null) {
            try {
                creator.create("shutdown");
            } catch (Throwable t) {
                LOGGER.error("[BetterBackup] final shutdown snapshot failed", t);
            }
        }

        BetterBackupCore.uninstall();
        LOGGER.info("[BetterBackup] uninstalled");
    }

    /**
     * 启动 baseline 全量扫描后台线程. 扫描限速 (默认 50 chunk/s) 跑时间可能很长, 必须
     * 在独立线程跑, 不阻塞 server 启动. 扫描把每个 chunk 登记进 CurrentSnapshotState,
     * 由下一次定时 / 关服快照统一 drain 进 manifest, 跑完置 baselineProgress.complete,
     * 此后快照 baselineComplete=true, restore 门禁放行.
     *
     * <p>daemon=true: 关服时若扫描还没跑完直接随 JVM 退出, 进度已按 region 持久化,
     * 下次启动续传. 扫描异常只 log, 不影响活跃 dirty 路径备份继续工作.
     */
    private static void startBaselineScan(ChunkStore store,
                                          CurrentSnapshotState state,
                                          WorldPaths paths,
                                          HashFunction hashFunction,
                                          Set<Hash> writtenThisWindow,
                                          BaselineProgress baselineProgress) {
        if (baselineProgress.isComplete()) {
            LOGGER.info("[BetterBackup] baseline already complete, full scan skipped");
            return;
        }
        int rate = BetterBackupConfig.baselineScanChunksPerSecond();
        BaselineScanner scanner = new BaselineScanner(store, state, paths, hashFunction,
                writtenThisWindow, baselineProgress, new ThrottlingRateLimiter(rate));
        Thread thread = new Thread(() -> {
            try {
                scanner.scan();
            } catch (IOException e) {
                LOGGER.error("[BetterBackup] baseline scan failed (progress persisted, will resume next start)", e);
            }
        }, "BetterBackup-Baseline-Scan");
        thread.setDaemon(true);
        thread.start();
        LOGGER.info("[BetterBackup] baseline full scan started (rate={} chunk/s)", rate);
    }

    private static SnapshotScheduler createScheduler() {
        return switch (BetterBackupConfig.scheduleMode()) {
            case INTERVAL -> new IntervalScheduler(BetterBackupConfig.intervalMinutes());
            case MANUAL, AFTER_AUTOSAVE -> new ManualScheduler();
            // AFTER_AUTOSAVE 推到 v0.2 (需要 BAS 加 autosave-end 事件钩子), MVP fallback to manual.
        };
    }

    /** overworld 当前 game time. 给 SnapshotManifest.worldGameTime 用 (诊断元数据). */
    private static long overworldGameTime(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld != null ? overworld.getGameTime() : 0L;
    }

    /** backupDirectory: 绝对路径直接用, 相对路径 resolve to FMLPaths.GAMEDIR (server root). */
    private static Path resolveStoreRoot(String configValue) {
        Path raw = Paths.get(configValue);
        if (raw.isAbsolute()) {
            return raw;
        }
        return FMLPaths.GAMEDIR.get().resolve(raw).normalize();
    }
}
