package com.shinoyuki.betterbackup;

import com.mojang.logging.LogUtils;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterbackup.baseline.BaselineProgress;
import com.shinoyuki.betterbackup.baseline.BaselineScanner;
import com.shinoyuki.betterbackup.baseline.DegradedRescan;
import com.shinoyuki.betterbackup.baseline.ThrottlingRateLimiter;
import com.shinoyuki.betterbackup.command.BetterBackupCommand;
import com.shinoyuki.betterbackup.config.BetterBackupConfig;
import com.shinoyuki.betterbackup.config.ConfigSpec;
import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterbackup.diagnostic.PrometheusExporter;
import com.shinoyuki.betterbackup.gc.StoreSizeGuard;
import com.shinoyuki.betterbackup.integration.BackupListenerBridge;
import com.shinoyuki.betterbackup.integration.PipelineDegradedHandler;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.log.BackupLog;
import com.shinoyuki.betterbackup.log.Slf4jLogSink;
import com.shinoyuki.betterbackup.restore.PendingRestoreFlag;
import com.shinoyuki.betterbackup.restore.RestoreFlow;
import com.shinoyuki.betterbackup.safety.StoreLocationCheck;
import com.shinoyuki.betterbackup.schedule.IntervalScheduler;
import com.shinoyuki.betterbackup.schedule.ManualScheduler;
import com.shinoyuki.betterbackup.schedule.SnapshotScheduler;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.DegradedSession;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // baseline 扫描线程的关服停止句柄. 不进 BetterBackupCore: 扫描是一次性启动期任务,
    // 不是常驻组件, install 签名不为它扩列. volatile 因 startBaselineScan (server 线程)
    // 与 onServerStopping (server 线程) 之间隔着 daemon 线程的生命周期.
    private static volatile BaselineScanner activeBaselineScanner;
    private static volatile Thread activeBaselineThread;

    public BetterBackupMod() {
        // 游戏内: 把 BackupLog 门面桥回 slf4j, 让已迁到门面的 CLI 可达类 (ChunkStore 等)
        // 仍走 Forge 的 slf4j -> log4j2 管线, 服主日志观感不变。裸 JRE CLI 不走这里, 门面留默认
        // System.err sink。安装放构造最前: store 在 onServerStarting 才用, 此时 sink 早已就位。
        BackupLog.install(new Slf4jLogSink());

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
     * <p><b>失败即中止启动</b>: restore 在 moveCurrentWorldToBackup 之后、回写中途盘错时,
     * worldRoot 会停在半重建态。此时绝不能放行 vanilla loadLevel —— 否则 vanilla 把缺失
     * region 当新地形生成、autosave 写回, 污染现场且不可逆。故任何失败 (flag 读 / store init /
     * 重建中途) 一律抛异常中止本次启动: 旧世界完好留在 {@code <world>.bak-*}, flag 保留,
     * 服主看 log 修复后重启重试 (亦可手工从 .bak 复原)。
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
        Path storeRoot = resolveStoreRoot(BetterBackupConfig.backupDirectory());
        RestoreFlow.RestoreResult result;
        try {
            result = runPendingRestore(worldRoot, storeRoot);
        } catch (IOException e) {
            // worldRoot 可能停在半重建态: 中止启动而非放行 vanilla 加载半成品, .bak 与 flag 均保留。
            throw new IllegalStateException(
                    "[BetterBackup] pending restore FAILED; aborting server start to avoid loading a "
                            + "half-restored world. The pre-restore world is preserved in <world>.bak-*, the "
                            + "restore flag is retained for retry. Fix the underlying error and restart.", e);
        }
        if (result != null) {
            LOGGER.info("[BetterBackup] restore complete: chunks={} entity={} savedData={} files={} levelDat={} backupDir={}",
                    result.chunkSlotsRestored(), result.entitySlotsRestored(),
                    result.savedDataFilesRestored(), result.playerDataFilesRestored(),
                    result.levelDatRestored(), result.worldBackupDir());
        }
    }

    /**
     * 执行 pending restore (若有): 检测 flag, 命中则跑 RestoreFlow 重建世界, 成功后删 flag。
     * 任何失败 (flag 读 / store init / 重建中途) 都抛 IOException 冒泡, 绝不吞 —— 调用方据此
     * 中止服务器启动, 防止 vanilla 加载半重建的 worldRoot。flag 仅在 restore 完整成功后才清,
     * 失败时保留供重试。
     *
     * @return 执行了 restore 返回非空结果; 无 pending flag 返回 null。
     */
    static RestoreFlow.RestoreResult runPendingRestore(Path worldRoot, Path storeRoot) throws IOException {
        Optional<String> pendingId = PendingRestoreFlag.read(worldRoot);
        if (pendingId.isEmpty()) {
            return null;
        }
        String snapshotId = pendingId.get();
        LOGGER.warn("[BetterBackup] pending restore detected: {} - rebuilding world before vanilla load",
                snapshotId);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        // 临时 restore store 用完即关: 否则它与 onServerStarting 再开的常驻 store 会在同一
        // storeRoot 上并存, Windows 下 mmap pack 索引互锁。close 失败仅降级为 warn, 不掩盖
        // restore 本身的失败异常 (后者才是调用方中止启动的依据)。
        try {
            Path snapshotsDir = storeRoot.resolve("snapshots");
            WorldPaths paths = new WorldPaths(worldRoot);
            RestoreFlow flow = new RestoreFlow(store, paths, snapshotsDir);
            RestoreFlow.RestoreResult result = flow.restore(snapshotId);
            PendingRestoreFlag.clear(worldRoot);
            return result;
        } finally {
            try {
                store.close();
            } catch (IOException closeEx) {
                LOGGER.warn("[BetterBackup] failed to close temporary restore store at {}", storeRoot, closeEx);
            }
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
            // orphan .tmp 后台清扫的时间闸 (见下方 verifyOnStartup 块): 此刻 worker 尚未启动,
            // 无本次运行的在途 .tmp, 故 cutoff=此刻只清上次运行崩溃残留的孤儿, 不误删活跃 worker 的在途 .tmp。
            long startMillis = System.currentTimeMillis();
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
                // orphan .tmp 全树扫描是 O(store 文件数), store 大时 (百万级 chunk) 可达数十秒。
                // 绝不能在 server 主线程同步跑: ServerStartingEvent 在主线程同步 dispatch, 阻塞它
                // 就推迟 ServerStarted / 登录门, 玩家被挡在 "server is still starting" 数十秒。改后台
                // daemon 线程跑 (与下方 baseline 扫描同款)。cutoff=startMillis 只清上次运行的孤儿,
                // 不碰本次 worker 在途 .tmp, 故可与活跃 worker 并发无 race。失败仅 warn (清扫不影响备份正确性)。
                final long cleanupCutoff = startMillis;
                Thread cleanupThread = new Thread(() -> {
                    try {
                        int cleaned = store.cleanupOrphanTmpFiles(cleanupCutoff);
                        if (cleaned > 0) {
                            LOGGER.warn("[BetterBackup] cleaned {} orphan .tmp files from store (background)", cleaned);
                        }
                    } catch (IOException e) {
                        LOGGER.warn("[BetterBackup] orphan .tmp cleanup failed (non-fatal, retries next start)", e);
                    }
                }, "BetterBackup-Tmp-Cleanup");
                cleanupThread.setDaemon(true);
                cleanupThread.start();
                LOGGER.info("[BetterBackup] orphan .tmp cleanup dispatched to background");
            }

            // maxStoreSizeGB 软阈值自检: store 体积超阈值则先 retention 淘汰 (删超期 manifest) 再
            // full GC 回收死对象. 与 .tmp 清扫同款后台 daemon 线程——算体积要 walk 整个 store, 大 store
            // (百万对象 + 未排空旧树) 可达数十秒, 绝不能在 server 主线程同步跑, 否则推迟 ServerStarted /
            // 登录门. 失败仅 warn 不中止启动 (自检不影响备份正确性). snapshotsDir 与 SnapshotCreator /
            // StoreGc 用的同为 storeRoot/snapshots.
            long maxStoreBytes = (long) BetterBackupConfig.maxStoreSizeGB() * 1024 * 1024 * 1024;
            Path snapshotsDir = storeRoot.resolve("snapshots");
            dispatchStoreSizeCheck(store, snapshotsDir, worldRoot, maxStoreBytes);

            CurrentSnapshotState snapshotState = new CurrentSnapshotState();
            WorldPaths paths = new WorldPaths(worldRoot);
            HashFunction hashFunction = new Xxh128HashFunction();
            BetterBackupMetrics metrics = new BetterBackupMetrics();
            Set<Hash> writtenThisWindow = ConcurrentHashMap.newKeySet();

            // baseline 进度先于 creator 创建: creator 在每份 manifest 写盘成功后晋升
            // scanned->committed 并据 baselineProgress.isComplete() 盖 baselineComplete 戳.
            // load() 读已记录进度供续传. 旧格式行按 scanned 读入 (向后兼容, 重扫后自动晋升).
            BaselineProgress baselineProgress = new BaselineProgress(storeRoot);
            baselineProgress.load();
            // baseline 扫描收尾标志: scanner 跑完所有 pass 后置 true, 然后请求一次
            // "baseline-complete" 快照. SnapshotCreator 仅在 scanFinished=true 且全部 region
            // committed 时才写 complete 标记, 防止扫描半途误开 restore 门禁.
            AtomicBoolean baselineScanFinished = new AtomicBoolean(false);
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
                    baselineProgress, baselineScanFinished::get);
            SnapshotScheduler scheduler = createScheduler();
            scheduler.start(creator);

            // BAS 降级感知: 注册 PipelineStateListener. BAS 管线降级时暂停 scheduler +
            // 标后续快照 incomplete + 持久化 degraded-session 标志供下次启动补采.
            DegradedSession degradedSession = new DegradedSession(storeRoot);
            PipelineDegradedHandler degradedHandler =
                    new PipelineDegradedHandler(creator, scheduler, degradedSession);
            SaveListenerRegistry.registerPipelineState(degradedHandler);
            BetterBackupCore.setPipelineDegradedHandler(degradedHandler);

            DiagnosticLogger diagnosticLogger = new DiagnosticLogger();
            MinecraftForge.EVENT_BUS.register(diagnosticLogger);

            BetterBackupCore.install(store, snapshotState, context, queue, workers, workerThreads,
                    bridge, creator, scheduler, diagnosticLogger, metrics, baselineProgress);

            startBaselineScan(store, snapshotState, paths, hashFunction, writtenThisWindow, baselineProgress,
                    creator, baselineScanFinished);

            // 上一次运行经历过 BAS 降级时, 补采降级窗口内变更的 chunk. 在 baseline 之后跑:
            // 二者都把 chunk 登记进 CurrentSnapshotState 等下次快照 drain, 互不冲突 (state
            // 已采的 chunk 双方都按 contains 跳过).
            runDegradedRescanIfNeeded(store, snapshotState, paths, hashFunction, writtenThisWindow,
                    storeRoot, creator);

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

        // 尽早请求停止 baseline 扫描, 让它在 worker drain 期间并行收尾; join 在关服快照
        // 之前 (见下), 防止扫描线程在快照 drain 之后继续标 scanned 半扫 region.
        BaselineScanner baselineScanner = activeBaselineScanner;
        if (baselineScanner != null) {
            baselineScanner.requestStop();
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

        PipelineDegradedHandler degradedHandler = BetterBackupCore.pipelineDegradedHandler();
        if (degradedHandler != null) {
            SaveListenerRegistry.unregisterPipelineState(degradedHandler);
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

        // baseline 线程必须在关服快照前停稳: 它对 progress/state 的写入与快照的
        // drain + 晋升语义竞争. requestStop 已在前面发出, 这里只等退出.
        Thread baselineThread = activeBaselineThread;
        if (baselineThread != null && baselineThread.isAlive()) {
            try {
                baselineThread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("[BetterBackup] interrupted joining baseline scan thread");
            }
            if (baselineThread.isAlive()) {
                LOGGER.warn("[BetterBackup] baseline scan thread did not stop within {}ms",
                        SHUTDOWN_JOIN_TIMEOUT_MS);
            } else {
                LOGGER.info("[BetterBackup] baseline scan stopped for shutdown");
            }
        }
        activeBaselineScanner = null;
        activeBaselineThread = null;

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
     * 派后台 daemon 线程跑 maxStoreSizeGB 软阈值自检 ({@link StoreSizeGuard}). 未越阈值时纯读一次
     * 体积即返回 (零副作用); 越阈值则先 retention 淘汰再 full GC, 并把 before / prune 份数 / 回收字节 /
     * after 打进日志.
     *
     * <p><b>软阈值不是硬配额</b>: gcAll 回收量被"受保留策略保护的活数据"封顶. 若 after 仍超阈值,
     * 显式 WARN 让服主看到"上限没兜住"的真相——要真控上限得调 retention / 缩短保留窗口 + 定期重启,
     * 而非指望本自检把体积压到阈值以下.
     *
     * <p>daemon=true 且失败仅 warn: 自检不影响活跃备份路径, 崩了下次启动重试. 绝不阻塞启动.
     */
    private static void dispatchStoreSizeCheck(ChunkStore store, Path snapshotsDir, Path worldRoot,
                                               long maxStoreBytes) {
        StoreSizeGuard guard = new StoreSizeGuard(store, snapshotsDir, worldRoot, maxStoreBytes);
        Thread thread = new Thread(() -> {
            try {
                StoreSizeGuard.Result r = guard.checkAndReclaim();
                if (!r.triggered()) {
                    LOGGER.info("[BetterBackup] store size check: {} bytes, under maxStoreSizeGB threshold ({} bytes); no GC",
                            r.beforeBytes(), maxStoreBytes);
                    return;
                }
                LOGGER.info("[BetterBackup] store size check: over threshold; before={} bytes, prune deleted {} manifest(s), "
                                + "gcAll freed {} bytes, after={} bytes (threshold={} bytes)",
                        r.beforeBytes(), r.prunedManifests(), r.gcBytesFreed(), r.afterBytes(), maxStoreBytes);
                if (r.stillOver()) {
                    // 软阈值兜不住: 回收量被活数据封顶. 如实告知, 别让服主误以为体积已降到阈值以下.
                    LOGGER.warn("[BetterBackup] store still exceeds maxStoreSizeGB after GC: reclaimed {} bytes leaves {} bytes "
                                    + "(threshold {} bytes). The remainder is live data protected by the retention policy. "
                                    + "maxStoreSizeGB is a soft startup trigger, not a hard disk quota; to lower the ceiling, "
                                    + "reduce retention (hourly/daily/weekly/monthly) or shorten the retention window and restart, "
                                    + "or manually delete snapshots.",
                            r.gcBytesFreed(), r.afterBytes(), maxStoreBytes);
                }
            } catch (IOException e) {
                LOGGER.warn("[BetterBackup] store size check failed (non-fatal, retries next start)", e);
            }
        }, "BetterBackup-StoreSize-Check");
        thread.setDaemon(true);
        thread.start();
        LOGGER.info("[BetterBackup] store size check dispatched to background (maxStoreSizeGB={})",
                BetterBackupConfig.maxStoreSizeGB());
    }

    /**
     * 启动 baseline 全量扫描后台线程. 扫描限速 (默认 50 chunk/s) 跑时间可能很长, 必须
     * 在独立线程跑, 不阻塞 server 启动. 扫描把每个 chunk 登记进 CurrentSnapshotState 并
     * 把所在 region 记为 scanned (待提交); 由下一次定时 / 关服快照统一 drain 进 manifest
     * 并晋升 committed.
     *
     * <p>扫描遍历完所有 pass 后调收尾回调: 先置 baselineScanFinished, 再
     * {@code creator.create("baseline-complete")} 请求一次快照把最后一批 scanned region
     * 晋升 committed; 当全部 region committed 时 SnapshotCreator 写 complete 标记, 此后
     * 快照 baselineComplete=true, restore 门禁放行. 不再由 scanner 直接写 complete 标记 ——
     * 这正是消灭"标完成但登记丢"崩溃窗口的关键: 标完成与登记进 manifest 现在同源于一次
     * 成功的快照写盘.
     *
     * <p>关服路径: onServerStopping 在关服快照前 requestStop + join, 扫描在 slot 边界
     * 干净退出 (被中断的半扫 region 不标 scanned). daemon=true 仅是兜底: join 超时或
     * 异常路径下随 JVM 退出, 进度已按 region 持久化, 下次启动续传 (scanned-未提交的
     * region 重扫). 扫描异常只 log, 不影响活跃 dirty 路径备份继续工作.
     */
    private static void startBaselineScan(ChunkStore store,
                                          CurrentSnapshotState state,
                                          WorldPaths paths,
                                          HashFunction hashFunction,
                                          Set<Hash> writtenThisWindow,
                                          BaselineProgress baselineProgress,
                                          SnapshotCreator creator,
                                          AtomicBoolean baselineScanFinished) {
        if (baselineProgress.isComplete()) {
            LOGGER.info("[BetterBackup] baseline already complete, full scan skipped");
            return;
        }
        int rate = BetterBackupConfig.baselineScanChunksPerSecond();
        // 收尾回调: 置 scanFinished 后请求一次快照. 快照写盘成功 -> SnapshotCreator 晋升
        // 最后一批 scanned region 并 (全部 committed 时) 写 complete 标记. 顺序要点: 必须
        // 先 set(true) 再 create, 否则 create 内读到的 scanFinished 还是 false 不写标记.
        Runnable onScanFinished = () -> {
            baselineScanFinished.set(true);
            creator.create("baseline-complete");
        };
        BaselineScanner scanner = new BaselineScanner(store, state, paths, hashFunction,
                writtenThisWindow, baselineProgress, new ThrottlingRateLimiter(rate), onScanFinished);
        Thread thread = new Thread(() -> {
            try {
                scanner.scan();
            } catch (IOException e) {
                LOGGER.error("[BetterBackup] baseline scan failed (progress persisted, will resume next start)", e);
            }
        }, "BetterBackup-Baseline-Scan");
        thread.setDaemon(true);
        activeBaselineScanner = scanner;
        activeBaselineThread = thread;
        thread.start();
        LOGGER.info("[BetterBackup] baseline full scan started (rate={} chunk/s)", rate);
    }

    /**
     * 上一次运行经历过 BAS 降级时, 补采降级窗口内变更的 chunk (PLAN Phase F).
     *
     * <p>检测 {@code degraded-session} 标志: 不存在直接返回. 存在则以上次完整快照
     * (manifest.incomplete=false) 的 createdAtMillis 为 cutoff, 对 mtime 晚于 cutoff 的
     * region/entities 文件做增量重扫 ({@link DegradedRescan}), 把降级窗口内 vanilla 同步
     * 写盘但没进快照的 chunk 登记进 CurrentSnapshotState, 由下次快照纳入. 完成后清标志.
     *
     * <p>同步跑 (非后台线程): 只扫 mtime 变化的子集, 远小于全量 baseline; 且必须在下次
     * 快照前补完才有意义. 重扫失败保留标志 (下次启动重试), 不清, 不 throw 中止启动.
     */
    private static void runDegradedRescanIfNeeded(ChunkStore store,
                                                  CurrentSnapshotState state,
                                                  WorldPaths paths,
                                                  HashFunction hashFunction,
                                                  Set<Hash> writtenThisWindow,
                                                  Path storeRoot,
                                                  SnapshotCreator creator) {
        DegradedSession session = new DegradedSession(storeRoot);
        if (!session.exists()) {
            return;
        }
        long cutoff = lastCompleteSnapshotMillis(creator.snapshotsDir());
        LOGGER.warn("[BetterBackup] degraded-session flag present from a prior run; "
                + "rescanning region files modified after lastCompleteSnapshotMillis={} to backfill the degraded window",
                cutoff);
        DegradedRescan rescan = new DegradedRescan(store, state, paths, hashFunction, writtenThisWindow);
        try {
            DegradedRescan.Result result = rescan.rescan(cutoff);
            session.clear();
            LOGGER.info("[BetterBackup] degraded-window backfill done: recovered={} deduped={} skipped(active)={} regions={}; flag cleared",
                    result.recovered(), result.deduped(), result.skippedActive(), result.regionsScanned());
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] degraded-window rescan failed, flag retained for retry next start", e);
        }
    }

    /**
     * 上次"完整"快照 (manifest.incomplete=false) 的 createdAtMillis. 取不到 (无快照 / 全是
     * 降级期间的 incomplete 快照) 返回 0, 让 DegradedRescan 退化为全量重扫 (保守, 宁多勿漏).
     */
    private static long lastCompleteSnapshotMillis(Path snapshotsDir) {
        if (!Files.isDirectory(snapshotsDir)) {
            return 0L;
        }
        try (Stream<Path> files = Files.list(snapshotsDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .map(BetterBackupMod::tryReadManifestForCutoff)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(m -> !m.incomplete())
                    .map(SnapshotManifest::createdAtMillis)
                    .max(Comparator.naturalOrder())
                    .orElse(0L);
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] failed to scan snapshots for degraded-rescan cutoff, using 0 (full rescan)", e);
            return 0L;
        }
    }

    private static Optional<SnapshotManifest> tryReadManifestForCutoff(Path path) {
        try {
            return Optional.of(SnapshotManifest.readFrom(path));
        } catch (IOException e) {
            LOGGER.warn("[BetterBackup] failed to read manifest {} for degraded-rescan cutoff, ignoring", path, e);
            return Optional.empty();
        }
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
