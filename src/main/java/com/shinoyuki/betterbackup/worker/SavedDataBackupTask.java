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
 * <p>BAS 给的 fileName 是 SavedData 的 name (例如 "raids", "mtr_train_data")。BAS listener
 * 不带 dim 字段, 故按 overworld -> nether -> end 顺序探测该 .dat 实际落在哪个维度的 data/
 * 目录, 命中即用; 登记进 state 的 key 是该文件相对 worldRoot 的路径 (含维度子目录, 如
 * {@code data/raids.dat}、{@code DIM1/data/raids_end.dat}), 让 restore 能把各维度 SavedData
 * 落回原维度的 data/ 而非一律塞进 overworld。
 */
public record SavedDataBackupTask(String fileName) implements BackupTask {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    @Override
    public String taskName() {
        return "savedData@" + fileName;
    }

    @Override
    public void execute(BackupContext ctx) throws IOException {
        ctx.metrics().recordSavedDataReceived();
        // BAS SavedDataSaveListener 不带 dim 信息, 但 vanilla 1.20+ 多 dim SavedData
        // 命名带后缀 (raids / raids_nether / raids_end), 实际文件落在各自 dim 的
        // data/ 目录. 按"最常见放在 overworld → nether → end"顺序试 3 个 vanilla
        // dim 路径. 找到第一个 match 就用. modded dim 注册自定义 SavedData 推到
        // v0.2 跟随 BAS API 升级 (那时 listener 应该带 dim 字段).
        Path datFile = findSavedDataFile(ctx);
        if (datFile == null) {
            LOGGER.warn("[BetterBackup] savedData file not found after BAS fire (tried overworld/nether/end): {}.dat",
                    fileName);
            ctx.metrics().recordSavedDataFailed();
            return;
        }
        byte[] rawBytes;
        try {
            rawBytes = Files.readAllBytes(datFile);
        } catch (IOException e) {
            ctx.metrics().recordSavedDataFailed();
            throw e;
        }
        Hash hash = ctx.hashFunction().hash(rawBytes);
        boolean wrote = ctx.store().put(hash, rawBytes);
        // key 用该 .dat 相对 worldRoot 的路径 (含维度子目录, 探测命中哪个维度就记哪个), 而非裸
        // SavedData 名 -- 维度隐含在路径里, restore 据此落回原维度的 data/。先登记 state 再 add
        // writtenThisWindow (GC 并发安全, 见 ChunkBackupTask 同址注释).
        String relativeKey = ctx.paths().worldRoot().relativize(datFile).toString().replace('\\', '/');
        ctx.state().putSavedData(relativeKey, hash);
        if (wrote) {
            ctx.writtenThisWindow().add(hash);
            ctx.metrics().recordSavedDataUnique();
        } else {
            ctx.metrics().recordSavedDataDeduped();
        }
    }

    private Path findSavedDataFile(BackupContext ctx) {
        String dotName = fileName + ".dat";
        Path overworld = ctx.paths().dataDir("minecraft:overworld").resolve(dotName);
        if (Files.exists(overworld)) {
            return overworld;
        }
        Path nether = ctx.paths().dataDir("minecraft:the_nether").resolve(dotName);
        if (Files.exists(nether)) {
            return nether;
        }
        Path end = ctx.paths().dataDir("minecraft:the_end").resolve(dotName);
        if (Files.exists(end)) {
            return end;
        }
        return null;
    }
}
