package com.shinoyuki.betterbackup;

import com.mojang.logging.LogUtils;
import com.shinoyuki.betterbackup.config.BetterBackupConfig;
import com.shinoyuki.betterbackup.config.ConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
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
 * Phase 0 第 2 步: 注册 ForgeConfigSpec, 监听 config load / reload 事件.
 *
 * <p>后续 Phase 0 commit 接入:
 * <ul>
 *   <li>BetterBackupCore lifecycle 钩子 (ServerStarting / Stopping)</li>
 *   <li>SaveListenerRegistry hello-world chunk listener (验证 BAS API 调通)</li>
 * </ul>
 */
@Mod(BetterBackupMod.MOD_ID)
public final class BetterBackupMod {

    public static final String MOD_ID = "shinoyuki_betterbackup";

    /**
     * 跟 BAS 共享 config 父目录, 整套 Shinoyuki 系列优化 mod 集中在一处便于服主管理.
     * BAS = config/Shinoyuki-Optimize/shinoyuki_betterautosave/
     * BetterBackup = config/Shinoyuki-Optimize/shinoyuki_betterbackup/
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

        LOGGER.info("[BetterBackup] mod loaded (Phase 0 step 2: config registered)");
    }
}
