package com.shinoyuki.betterbackup;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Phase 0 骨架: 仅注册 @Mod 入口, 验证 mods.toml 解析与 BAS 依赖加载.
 *
 * <p>后续 Phase 0 commit 会陆续接入:
 * <ul>
 *   <li>BetterBackupConfig + ConfigSpec (toml schema)</li>
 *   <li>BetterBackupCore lifecycle 钩子 (ServerStarting / Stopping)</li>
 *   <li>SaveListenerRegistry hello-world chunk listener</li>
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
        LOGGER.info("[BetterBackup] mod loaded (Phase 0 skeleton, MVP in progress)");
    }
}
