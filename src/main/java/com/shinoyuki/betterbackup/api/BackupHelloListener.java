package com.shinoyuki.betterbackup.api;

import com.shinoyuki.betterautosave.api.ChunkSaveListener;
import com.shinoyuki.betterautosave.api.EntityChunkSaveListener;
import com.shinoyuki.betterautosave.api.SavedDataSaveListener;
import com.shinoyuki.betterbackup.BetterBackupMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.concurrent.atomic.LongAdder;

/**
 * Phase 0 commit 5: hello-world listener, 仅 log fire 验证 BAS Listener API 调通.
 * Phase 1 commit 12 会被替换成把事件入 BackupWorker queue 的真正实现.
 *
 * <p>三个接口同时实现, 一个实例 register 到 BAS 三个 channel (chunk / entityChunk
 * / savedData), 不需要三个独立类.
 *
 * <p>计数仅诊断用 (用 LongAdder 避免锁), 命令 / 日志可读. fire 频率高 (chunk save
 * 路径每秒上百次), 不做 INFO log per fire — 只在每 N 次 log 一次 progress.
 */
public final class BackupHelloListener
        implements ChunkSaveListener, EntityChunkSaveListener, SavedDataSaveListener {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;
    private static final long LOG_EVERY = 100L;

    private final LongAdder chunkFires = new LongAdder();
    private final LongAdder entityFires = new LongAdder();
    private final LongAdder savedDataFires = new LongAdder();

    @Override
    public void onChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag) {
        chunkFires.increment();
        long n = chunkFires.sum();
        if (n == 1L || n % LOG_EVERY == 0L) {
            LOGGER.info("[BetterBackup] hello: chunk save fire #{} pos={} dim={}",
                    n, pos, dimension.location());
        }
    }

    @Override
    public void onEntityChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag) {
        entityFires.increment();
        long n = entityFires.sum();
        if (n == 1L || n % LOG_EVERY == 0L) {
            LOGGER.info("[BetterBackup] hello: entity-chunk save fire #{} pos={} dim={}",
                    n, pos, dimension.location());
        }
    }

    @Override
    public void onSavedDataWritten(String fileName, CompoundTag tag) {
        savedDataFires.increment();
        long n = savedDataFires.sum();
        // SavedData 频率低 (per autosave cycle 一次), 每次都 log.
        LOGGER.info("[BetterBackup] hello: savedData fire #{} file={}", n, fileName);
    }

    public long chunkFireCount() {
        return chunkFires.sum();
    }

    public long entityFireCount() {
        return entityFires.sum();
    }

    public long savedDataFireCount() {
        return savedDataFires.sum();
    }
}
