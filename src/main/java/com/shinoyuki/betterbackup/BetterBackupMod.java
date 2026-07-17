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
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
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

    /**
     * BackupTask 队列容量上限. 稳态下队列很浅 (worker 消费快于 vanilla 存盘), 但压实 / 手动 GC
     * 长时间持 store 写锁阻塞消费时会堆积. 有界 + bridge 满时反压后降级, 防无上限增长致堆压力.
     */
    private static final int TASK_QUEUE_CAPACITY = 100_000;

    // baseline 扫描线程的关服停止句柄. 不进 BetterBackupCore: 扫描是一次性启动期任务,
    // 不是常驻组件, install 签名不为它扩列. volatile 因 startBaselineScan (server 线程)
    // 与 onServerStopping (server 线程) 之间隔着 daemon 线程的生命周期.
    private static volatile BaselineScanner activeBaselineScanner;
    private static volatile Thread activeBaselineThread;
    // 降级窗口补采 (DegradedRescan) 的关服停止句柄. 与 baseline 同款: 后台 daemon 跑, 关服
    // requestStop + join. 不进 BetterBackupCore (一次性启动期任务, 非常驻组件).
    private static volatile DegradedRescan activeDegradedRescan;
    private static volatile Thread activeDegradedThread;
    // maxStoreSizeGB 自检 (StoreSizeGuard.gcAll) 的关服 join 句柄. gcAll 持 store 写锁, 关服
    // 前先 join 它让写锁尽量释放, 免 final snapshot 撞其写锁久等 (gcAll 不可中断, 只能 join).
    private static volatile Thread activeStoreSizeCheckThread;
    // store 后台初始化 (issue #3 异步化) 的握手状态机. 关服据 beginStop 返回的状态决定走完整
    // 拆线 (含 final snapshot) 还是未就绪拆线 (跳过 final snapshot + 落 degraded-session 补采
    // 标志). init 线程是 daemon 且不被 join —— 等它扫完正是 issue #3 要消灭的启动阻塞.
    private static volatile StoreInitCoordinator activeStoreInitCoordinator;

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
        ModLoadingContext modLoadingContext = ModLoadingContext.get();
        modLoadingContext.registerConfig(ModConfig.Type.COMMON, ConfigSpec.SPEC, configRelative);

        // 声明服务端专用: 客户端 (含 vanilla) 无需安装即可连接, 服务器列表 ping 不标红、加入不被要求安装。
        modLoadingContext.registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                        () -> NetworkConstants.IGNORESERVERONLY,
                        (remoteVersion, isFromServer) -> true));

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
            // orphan .tmp 后台清扫的时间闸 (dispatchTmpCleanup): cutoff=本方法进入时刻, 只清
            // 上次运行崩溃残留的孤儿; 本次 worker 的在途 .tmp mtime 必晚于它, 并发无 race。
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

            // phase 1 (主线程): 只做廉价接线. store 构造不触盘; 真正的 initialize() (索引失配时
            // O(pack 集) 重扫, issue #3 的 120s Watchdog 阻塞点) 甩到下方 BetterBackup-Store-Init
            // 后台线程, 完成后经 StoreInitCoordinator 与关停互斥地启动 worker/scheduler (arm).
            ChunkStore store = new ChunkStore(storeRoot);

            CurrentSnapshotState snapshotState = new CurrentSnapshotState();
            WorldPaths paths = new WorldPaths(worldRoot);
            HashFunction hashFunction = new Xxh128HashFunction();
            BetterBackupMetrics metrics = new BetterBackupMetrics();
            Set<Hash> writtenThisWindow = ConcurrentHashMap.newKeySet();

            // maxStoreSizeGB 软阈值自检参数. 自检的 gcAll 求值在途保护集 (pendingHashes ∪
            // writtenThisWindow), 排除启动期 worker / baseline 已 put 但未进 manifest 的对象,
            // 不封口在写 pack —— 否则会物理删掉这些对象致下份快照悬空引用. 派发本身在 arm 阶段
            // (store 就绪后), 算体积要 walk 整个 store, 未初始化时跑既无意义也撞硬闸.
            long maxStoreBytes = (long) BetterBackupConfig.maxStoreSizeGB() * 1024 * 1024 * 1024;
            Path snapshotsDir = storeRoot.resolve("snapshots");
            Supplier<Set<Hash>> inFlightProtect = () -> {
                Set<Hash> protect = new HashSet<>(snapshotState.pendingHashes());
                protect.addAll(writtenThisWindow);
                return protect;
            };

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
            // 有界: 消费被长时压实写锁阻塞时防无上限堆积 (bridge 满时反压后降级并 WARN);
            // 撕裂读重试走非阻塞 offer, 队满时丢弃该轮重试 (chunk 下次存盘再 fire), 不与消费者死锁.
            BlockingQueue<BackupTask> queue = new LinkedBlockingQueue<>(TASK_QUEUE_CAPACITY);
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
                // 不在此启动: worker 消费即 store.put, 必须等后台 initialize() 完成 (arm 阶段).
                // 在那之前 bridge 入队的 task 安静堆在有界 queue 里, 满则 bridge 降级丢弃 + WARN
                // (与稳态压实堵塞同语义), 丢掉的 chunk 下次存盘再 fire.
            }

            BackupListenerBridge bridge = new BackupListenerBridge(queue);
            SaveListenerRegistry.registerChunk(bridge);
            SaveListenerRegistry.registerEntityChunk(bridge);
            SaveListenerRegistry.registerSavedData(bridge);

            MinecraftServer server = event.getServer();
            SnapshotCreator creator = new SnapshotCreator(store, snapshotState, paths, hashFunction,
                    storeRoot, () -> overworldGameTime(server), writtenThisWindow, metrics,
                    baselineProgress, baselineScanFinished::get);
            // scheduler 不在此启动: 定时快照会 store.put/flushAndSync, 等 arm 阶段.
            SnapshotScheduler scheduler = createScheduler();

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

            // phase 2 (arm): 全部依赖 store 已 initialize 的组件在此启动, 由 init 线程在
            // StoreInitCoordinator 锁内执行, 与关停拆线互斥. worker 最先启动以尽快排空
            // init 窗口内 bridge 攒下的 queue 积压.
            StoreInitCoordinator coordinator = new StoreInitCoordinator();
            final long cleanupCutoff = startMillis;
            Runnable armPipeline = () -> {
                for (Thread thread : workerThreads) {
                    thread.start();
                }
                if (BetterBackupConfig.verifyOnStartup()) {
                    dispatchTmpCleanup(store, cleanupCutoff);
                }
                dispatchStoreSizeCheck(store, snapshotsDir, worldRoot, maxStoreBytes, inFlightProtect);
                scheduler.start(creator);
                startBaselineScan(store, snapshotState, paths, hashFunction, writtenThisWindow, baselineProgress,
                        creator, baselineScanFinished);
                // 上一次运行经历过 BAS 降级时, 补采降级窗口内变更的 chunk. 在 baseline 之后跑:
                // 二者都把 chunk 登记进 CurrentSnapshotState 等下次快照 drain, 互不冲突 (state
                // 已采的 chunk 双方都按 contains 跳过).
                runDegradedRescanIfNeeded(store, snapshotState, paths, hashFunction, writtenThisWindow,
                        storeRoot, creator);
                BetterBackupCore.setStoreReady(true);
            };
            Runnable failureTeardown = () ->
                    teardownAfterInitFailure(bridge, degradedHandler, diagnosticLogger, store);
            activeStoreInitCoordinator = coordinator;
            Thread initThread = new Thread(
                    () -> runStoreInit(coordinator, store, armPipeline, failureTeardown),
                    "BetterBackup-Store-Init");
            initThread.setDaemon(true);
            initThread.start();

            LOGGER.info("[BetterBackup]   |- worldRoot: {}", worldRoot);
            LOGGER.info("[BetterBackup]   |- storeRoot: {}", storeRoot);
            LOGGER.info("[BetterBackup]   |- hash: {}", hashFunction.name());
            LOGGER.info("[BetterBackup]   |- workers: {} thread(s)", threadCount);
            LOGGER.info("[BetterBackup]   |- schedule: {} (interval={}min)",
                    BetterBackupConfig.scheduleMode(),
                    BetterBackupConfig.intervalMinutes());
            LOGGER.info("[BetterBackup]   `- config: {}/{}/common.toml", SERIES_CONFIG_DIR, MOD_ID);
            LOGGER.info("[BetterBackup] wiring complete; store initializing in background "
                    + "(listener events buffer in queue, pipeline arms when init finishes)");
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] startup failed, mod degraded (no backups will be created)", e);
        }
    }

    /**
     * BetterBackup-Store-Init 后台线程主体: 跑重 IO 的 store.initialize() (持久索引失配时
     * O(pack 集) 全量重扫, issue #3 的原主线程 Watchdog 阻塞点), 完成后经 coordinator 与
     * 关停互斥地 arm 管线; 关停抢先则安静收尾不 arm. arm 自身抛异常时状态停留在未就绪,
     * 关停按未就绪拆线 (对已部分启动的组件 requestStop/stop 幂等安全).
     */
    private static void runStoreInit(StoreInitCoordinator coordinator, ChunkStore store,
                                     Runnable armPipeline, Runnable failureTeardown) {
        long t0 = System.currentTimeMillis();
        try {
            store.initialize();
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] store initialization failed; backup pipeline will not arm "
                    + "(mod degraded, no backups this run)", e);
            if (!coordinator.failInit(failureTeardown)) {
                LOGGER.info("[BetterBackup] store init failure raced server shutdown; shutdown path owns teardown");
            }
            return;
        }
        long elapsed = System.currentTimeMillis() - t0;
        boolean armed;
        try {
            armed = coordinator.completeInit(armPipeline);
        } catch (Throwable t) {
            LOGGER.error("[BetterBackup] pipeline arm failed after store init ({} ms); state stays not-ready, "
                    + "shutdown will tear down any partially started components", elapsed, t);
            return;
        }
        if (armed) {
            LOGGER.info("[BetterBackup] store initialized in {} ms ({} objects); backup pipeline armed",
                    elapsed, store.packStore().objectCount());
        } else {
            LOGGER.info("[BetterBackup] store initialized in {} ms but server is already stopping; pipeline not armed",
                    elapsed);
            try {
                store.close();
            } catch (IOException e) {
                LOGGER.warn("[BetterBackup] failed to close store after late init completion", e);
            }
        }
    }

    /**
     * store 后台初始化失败后的拆线: phase 1 已注册的 bridge/handler/diagnostic/exporter 全部
     * 摘除, worker/scheduler 从未启动无需 join. 在 StoreInitCoordinator 锁内执行, 与
     * ServerStopping 拆线互斥, 二者恰跑其一. 摘除后 uninstall, 此后命令/关停按未安装处理.
     */
    private static void teardownAfterInitFailure(BackupListenerBridge bridge,
                                                 PipelineDegradedHandler degradedHandler,
                                                 DiagnosticLogger diagnosticLogger,
                                                 ChunkStore store) {
        SaveListenerRegistry.unregisterChunk(bridge);
        SaveListenerRegistry.unregisterEntityChunk(bridge);
        SaveListenerRegistry.unregisterSavedData(bridge);
        SaveListenerRegistry.unregisterPipelineState(degradedHandler);
        MinecraftForge.EVENT_BUS.unregister(diagnosticLogger);
        PrometheusExporter exporter = BetterBackupCore.exporter();
        if (exporter != null) {
            exporter.stop();
        }
        try {
            store.close();
        } catch (IOException e) {
            LOGGER.warn("[BetterBackup] failed to close store after init failure", e);
        }
        BetterBackupCore.uninstall();
        LOGGER.error("[BetterBackup] pipeline torn down after store init failure; no backups will be created this run");
    }

    /**
     * 启动时清 kill -9 留下的孤儿 .tmp (DESIGN §8 store 文件写一半断电场景). atomic put
     * (tmp + fsync + rename) 已防止半写文件被引用, 但强杀残留的 tmp 占空间, 后台 daemon 线程
     * 一次性清掉 —— 全树扫描 O(store 文件数), 绝不在主线程跑. cutoff = 本次 onServerStarting
     * 进入时刻: 只清上次运行的孤儿, 本次 worker 在途 .tmp 的 mtime 必晚于 cutoff, 并发无 race.
     * 失败仅 warn (清扫不影响备份正确性).
     */
    private static void dispatchTmpCleanup(ChunkStore store, long cleanupCutoff) {
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

        // 先关初始化握手: STOPPED 之后 init 线程绝不 arm (锁内互斥, 进行中的 arm 会先跑完).
        // init 线程本身是 daemon 且不 join —— 等它扫完正是 issue #3 要消灭的阻塞; 它发现
        // STOPPED 后只安静 close store. 未就绪时跳过 final snapshot (store 不可读写) 并在
        // 下方落 degraded-session 补采标志.
        StoreInitCoordinator initCoordinator = activeStoreInitCoordinator;
        StoreInitCoordinator.State initState =
                initCoordinator != null ? initCoordinator.beginStop() : StoreInitCoordinator.State.READY;
        if (initState != StoreInitCoordinator.State.READY) {
            LOGGER.warn("[BetterBackup] store init did not complete before shutdown (state={}); "
                    + "tearing down wired pipeline without final snapshot", initState);
        }

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

        // 降级窗口补采同样尽早请求停止, 与 baseline 并行收尾, join 在关服快照之前.
        DegradedRescan degradedRescan = activeDegradedRescan;
        if (degradedRescan != null) {
            degradedRescan.requestStop();
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

        // 降级补采线程也必须在关服快照前停稳: 它对 state 的登记与快照 drain 竞争.
        Thread degradedThread = activeDegradedThread;
        if (degradedThread != null && degradedThread.isAlive()) {
            try {
                degradedThread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("[BetterBackup] interrupted joining degraded rescan thread");
            }
            if (degradedThread.isAlive()) {
                LOGGER.warn("[BetterBackup] degraded rescan thread did not stop within {}ms",
                        SHUTDOWN_JOIN_TIMEOUT_MS);
            } else {
                LOGGER.info("[BetterBackup] degraded rescan stopped for shutdown");
            }
        }
        activeDegradedRescan = null;
        activeDegradedThread = null;

        // 启动期 store 体积自检若仍在跑 gcAll (持 store 写锁), 先 join 它 (有界超时), 让写锁在关服
        // 快照拿读锁之前尽量释放, 避免 final snapshot 撞其写锁久等. gcAll 不支持中断, 只能等它跑完.
        Thread storeSizeThread = activeStoreSizeCheckThread;
        if (storeSizeThread != null && storeSizeThread.isAlive()) {
            try {
                storeSizeThread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("[BetterBackup] interrupted joining store size check thread");
            }
            if (storeSizeThread.isAlive()) {
                LOGGER.warn("[BetterBackup] store size check (gcAll) did not finish within {}ms; "
                        + "final shutdown snapshot may block on the store lock until it does",
                        SHUTDOWN_JOIN_TIMEOUT_MS);
            }
        }
        activeStoreSizeCheckThread = null;

        // 关服 final snapshot: 最后一次抓取本周期内 BAS fire 过的 chunk.
        // SnapshotCreator.create 用 synchronized 串行, 跟 scheduler 已停后无 race.
        // 仅在 store 就绪时跑: 未就绪的 store 读写会撞 PackStore 硬闸 (响亮失败但无意义).
        SnapshotCreator creator = BetterBackupCore.creator();
        if (creator != null && initState == StoreInitCoordinator.State.READY) {
            try {
                creator.create("shutdown");
            } catch (Throwable t) {
                LOGGER.error("[BetterBackup] final shutdown snapshot failed", t);
            }
            // 索引关服 checkpoint: 记下本会话的 per-pack 清单, 下次启动零重扫. 没有它, 每个
            // 写过新对象的会话都让下次启动付一遍重扫 (issue #3 稳定复现的直接原因之一).
            ChunkStore readyStore = BetterBackupCore.store();
            if (readyStore != null) {
                readyStore.checkpointOnShutdown();
            }
        } else if (initState != StoreInitCoordinator.State.READY) {
            // init 窗口内 bridge 已入队但从未被 worker 持久化的存盘事件随进程消失. 落
            // degraded-session 标志, 下次启动按 mtime 补采该窗口 (与 BAS 降级窗口同一套
            // 机制), 这些 chunk 不会静默停留在旧版本.
            ChunkStore store = BetterBackupCore.store();
            if (store != null && leftover > 0) {
                try {
                    new DegradedSession(store.storeRoot()).mark(System.currentTimeMillis());
                    LOGGER.warn("[BetterBackup] {} queued backup task(s) were never persisted (store init "
                            + "incomplete); degraded-session flag written for next-start backfill", leftover);
                } catch (IOException e) {
                    LOGGER.error("[BetterBackup] failed to write degraded-session flag for init-window backlog", e);
                }
            }
        }

        activeStoreInitCoordinator = null;
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
     *
     * @param inFlightProtect 在途保护集供应器 (pendingHashes ∪ writtenThisWindow), gcAll 时求值,
     *                        保护并发写入尚未进 manifest 的对象不被误删
     */
    private static void dispatchStoreSizeCheck(ChunkStore store, Path snapshotsDir, Path worldRoot,
                                               long maxStoreBytes, Supplier<Set<Hash>> inFlightProtect) {
        StoreSizeGuard guard = new StoreSizeGuard(store, snapshotsDir, worldRoot, maxStoreBytes, inFlightProtect);
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
        activeStoreSizeCheckThread = thread;
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
     * <p>后台 daemon 线程跑 + ThrottlingRateLimiter 限速 (与 baseline 同款): cutoff=0 退化为
     * 全量重扫, 若同步跑在 ServerStartingEvent 主线程会阻塞 ServerStarted / 登录门 (历史 62s
     * 假启动同类症状), 且补采读盘量可能很大不该在玩家在线时打满磁盘. 补采本就是"下次快照前补齐"
     * 语义, 异步跑不影响正确性. 关服路径 requestStop + join 防半扫与关服快照 drain 竞争; 被中断
     * 或失败时保留 degraded-session 标志 (下次启动重试), 不清, 不 throw 中止启动.
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
        int rate = BetterBackupConfig.baselineScanChunksPerSecond();
        DegradedRescan rescan = new DegradedRescan(store, state, paths, hashFunction, writtenThisWindow,
                new ThrottlingRateLimiter(rate));
        Thread thread = new Thread(() -> {
            try {
                DegradedRescan.Result result = rescan.rescan(cutoff);
                if (result.stopped()) {
                    // 关服中断: backfill 未跑完, 保留标志下次启动重试, 不清.
                    LOGGER.info("[BetterBackup] degraded-window backfill stopped for shutdown: recovered={} so far, "
                            + "flag retained, resumes next start", result.recovered());
                    return;
                }
                session.clear();
                LOGGER.info("[BetterBackup] degraded-window backfill done: recovered={} deduped={} skipped(active)={} regions={}; flag cleared",
                        result.recovered(), result.deduped(), result.skippedActive(), result.regionsScanned());
            } catch (IOException e) {
                LOGGER.error("[BetterBackup] degraded-window rescan failed, flag retained for retry next start", e);
            }
        }, "BetterBackup-Degraded-Rescan");
        thread.setDaemon(true);
        activeDegradedRescan = rescan;
        activeDegradedThread = thread;
        thread.start();
        LOGGER.info("[BetterBackup] degraded-window backfill started in background (rate={} chunk/s, cutoffMillis={})",
                rate, cutoff);
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
