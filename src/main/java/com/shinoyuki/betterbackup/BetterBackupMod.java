package com.shinoyuki.betterbackup;

import com.mojang.logging.LogUtils;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterbackup.config.BetterBackupConfig;
import com.shinoyuki.betterbackup.config.ConfigSpec;
import com.shinoyuki.betterbackup.integration.BackupListenerBridge;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.HashFunction;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import com.shinoyuki.betterbackup.worker.BackupContext;
import com.shinoyuki.betterbackup.worker.BackupTask;
import com.shinoyuki.betterbackup.worker.BackupWorker;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
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
import java.util.concurrent.BlockingQueue;
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
    public void onServerStarting(ServerStartingEvent event) {
        if (!BetterBackupConfig.enabled()) {
            LOGGER.info("[BetterBackup] disabled in config, skipping startup");
            return;
        }
        LOGGER.info("[BetterBackup] starting for {}", event.getServer().name());

        try {
            Path worldRoot = event.getServer().getWorldPath(LevelResource.ROOT);
            Path storeRoot = resolveStoreRoot(BetterBackupConfig.backupDirectory());

            ChunkStore store = new ChunkStore(storeRoot);
            store.initialize();

            CurrentSnapshotState snapshotState = new CurrentSnapshotState();
            WorldPaths paths = new WorldPaths(worldRoot);
            HashFunction hashFunction = new Xxh128HashFunction();
            BackupContext context = new BackupContext(store, snapshotState, paths, hashFunction);

            BlockingQueue<BackupTask> queue = new LinkedBlockingQueue<>();
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

            BetterBackupCore.install(store, snapshotState, context, queue, workers, workerThreads, bridge);

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
            LOGGER.info("[BetterBackup] pipeline installed (BAS Listener -> BackupWorker queue)");
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

        BetterBackupCore.uninstall();
        LOGGER.info("[BetterBackup] uninstalled");
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
