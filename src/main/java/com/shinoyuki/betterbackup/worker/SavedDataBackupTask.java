package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.store.Hash;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SavedData 路径备份 (BAS SavedDataSaveListener fire 时入 queue).
 *
 * <p>SavedData 不是 region file 格式, 是直接的 .dat 文件 (vanilla NbtIo.writeCompressed
 * 写盘). 直接读整个文件字节 hash 即可. 文件小 (典型 < 1 MB, 大型 mod 如 MTR 可达数 MB).
 *
 * <p>BAS 给的 fileName 是 SavedData 的 name (例如 "raids", "mtr_train_data"),
 * 通过 ServerLevel 的 dimensionId 推断目录. MVP 阶段不带 dim 信息, 假设 SavedData
 * 在 overworld 的 data/ 目录. v0.2+ BAS Listener API 加 dimension 字段时再细化.
 */
public record SavedDataBackupTask(String fileName) implements BackupTask {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    @Override
    public String taskName() {
        return "savedData@" + fileName;
    }

    @Override
    public void execute(BackupContext ctx) throws IOException {
        // BAS SavedDataSaveListener 只给 fileName, 没 dim 信息. MVP 假设 overworld
        // (vanilla 大多数 SavedData 如 raids / scoreboard 都在 overworld data/).
        // mod 可能在其他 dim 注册 SavedData (例如 nether-only mod), 那种 case 要
        // BAS 升级 listener 接口加 dim 字段, 在 v0.2+ 处理.
        Path datFile = ctx.paths().dataDir("minecraft:overworld").resolve(fileName + ".dat");
        if (!Files.exists(datFile)) {
            LOGGER.warn("[BetterBackup] savedData file not found after BAS fire: {}", datFile);
            return;
        }
        byte[] rawBytes = Files.readAllBytes(datFile);
        Hash hash = ctx.hashFunction().hash(rawBytes);
        ctx.store().put(hash, rawBytes);
        ctx.state().putSavedData(fileName, hash);
    }
}
