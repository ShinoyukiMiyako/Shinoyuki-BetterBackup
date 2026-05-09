package com.shinoyuki.betterbackup;

import com.mojang.logging.LogUtils;
import com.shinoyuki.betterbackup.config.BetterBackupConfig;
import com.shinoyuki.betterbackup.config.ConfigSpec;
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

/**
 * Phase 0 第 3 步: 注册 ServerStarting / ServerStopping lifecycle 钩子.
 *
 * <p><b>ServerStoppingEvent 用 {@code EventPriority.LOW}</b>: BAS 默认
 * NORMAL priority, 关服时 BAS 先跑 (drainPending + joinWorkers), 期间 BAS
 * 可能 fire 最后一批 chunk listener. BetterBackup 的 worker 必须在 BAS
 * joinWorkers 之前活着, 否则 listener 事件入空 queue 数据丢失. LOW priority
 * 让 BetterBackup handler 在 BAS handler 之后才执行, 此时 BAS 已经 fire 完
 * 所有 listener, BetterBackup 安全 drain 自己的 queue. 见 DESIGN §3.6.
 *
 * <p>后续 Phase 0 commit 5 会在 onServerStarting 注册 hello-world ChunkSaveListener
 * 到 BAS, 在 onServerStopping 注销.
 */
@Mod(BetterBackupMod.MOD_ID)
public final class BetterBackupMod {

    public static final String MOD_ID = "shinoyuki_betterbackup";

    /**
     * 跟 BAS 共享 config 父目录, 整套 Shinoyuki 系列优化 mod 集中在一处便于服主管理.
     */
    public static final String SERIES_CONFIG_DIR = "Shinoyuki-Optimize";

    public static final Logger LOGGER = LogUtils.getLogger();

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
        BetterBackupCore.install();
        LOGGER.info("[BetterBackup]   |- backupDirectory: {}", BetterBackupConfig.backupDirectory());
        LOGGER.info("[BetterBackup]   |- hash: {} compress: {}",
                BetterBackupConfig.hashAlgorithm(),
                BetterBackupConfig.compressionAlgorithm());
        LOGGER.info("[BetterBackup]   |- workers: {}", BetterBackupConfig.backupWorkerThreads());
        LOGGER.info("[BetterBackup]   |- schedule: {} (interval={}min)",
                BetterBackupConfig.scheduleMode(),
                BetterBackupConfig.intervalMinutes());
        LOGGER.info("[BetterBackup]   `- config: {}/{}/common.toml", SERIES_CONFIG_DIR, MOD_ID);
        LOGGER.info("[BetterBackup] Phase 0 lifecycle installed (worker / store will come in Phase 1)");
    }

    /**
     * LOW priority: BAS 默认 NORMAL, 让 BAS 先跑 onServerStopping (drainPending +
     * joinWorkers). 期间 BAS fire 的最后一批 listener 事件 BetterBackup queue
     * 仍能接收. BAS 跑完后 BetterBackup 才 drain 自己的 queue + 创建 final snapshot
     * + join 自己的 worker.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onServerStopping(ServerStoppingEvent event) {
        if (!BetterBackupCore.isInstalled()) {
            return;
        }
        LOGGER.info("[BetterBackup] server stopping (LOW priority, after BAS drain)");
        BetterBackupCore.uninstall();
        LOGGER.info("[BetterBackup] uninstalled");
    }
}
