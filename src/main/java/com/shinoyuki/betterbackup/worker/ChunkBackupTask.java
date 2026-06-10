package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.TornReadException;
import com.shinoyuki.betterbackup.store.Hash;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * chunk 路径备份 (BAS ChunkSaveListener fire 时入 queue).
 * 读 region/r.x.z.mca 里指定 chunk slot raw bytes → hash → store → state.
 *
 * <p><b>撕裂读重试</b>: {@link RegionFileSlotReader#readSlot} 命中撕裂读抛
 * {@link TornReadException} 时 (worker 读 slot 撞上 vanilla IOWorker 原地重写),
 * 本 task 把自己以 retryAttempt+1 重新 offer 回队列延后重试, 而不是把混合垃圾字节
 * 入库 (垃圾 hash 自洽, verify 查不出, restore 时炸). 超过 {@link #MAX_RETRY_ATTEMPTS}
 * 仍撕裂则记失败放弃本轮 — 该 chunk 下次 BAS save 会再 fire 重新入队, 不静默吞数据.
 */
public record ChunkBackupTask(String dimensionId, long packedPos, int retryAttempt) implements BackupTask {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    /**
     * 撕裂读重试上限. vanilla IOWorker 原地重写一个 slot 在毫秒级完成, 重 offer 回队尾
     * 一般一两次即避开. 给 5 次裕量防极端持续写, 超限说明该 slot 被高频写, 放弃本轮更稳妥.
     */
    static final int MAX_RETRY_ATTEMPTS = 5;

    public ChunkBackupTask(String dimensionId, long packedPos) {
        this(dimensionId, packedPos, 0);
    }

    @Override
    public String taskName() {
        return "chunk@" + ChunkPos.getX(packedPos) + "," + ChunkPos.getZ(packedPos)
                + "/" + dimensionId + (retryAttempt > 0 ? " (retry " + retryAttempt + ")" : "");
    }

    @Override
    public void execute(BackupContext ctx) throws IOException {
        ctx.metrics().recordChunkReceived();
        int chunkX = ChunkPos.getX(packedPos);
        int chunkZ = ChunkPos.getZ(packedPos);
        Path regionDir = ctx.paths().regionDir(dimensionId);
        byte[] rawBytes;
        try {
            rawBytes = RegionFileSlotReader.readChunk(regionDir, chunkX, chunkZ);
        } catch (TornReadException e) {
            handleTornRead(ctx, chunkX, chunkZ, e);
            return;
        } catch (IOException e) {
            ctx.metrics().recordChunkFailed();
            throw e;
        }
        if (rawBytes == null) {
            // BAS fire 后 chunk slot 应该已经写入 .mca, 没读到说明 BAS 跟 vanilla
            // IOWorker 之间有时序差或 chunk 后来被删. 不报 ERROR, log WARN 跳过.
            LOGGER.warn("[BetterBackup] chunk slot empty after BAS fire: pos=({},{}) dim={}",
                    chunkX, chunkZ, dimensionId);
            ctx.metrics().recordChunkFailed();
            return;
        }
        Hash hash = ctx.hashFunction().hash(rawBytes);
        boolean wrote = ctx.store().put(hash, rawBytes);
        if (wrote) {
            ctx.writtenThisWindow().add(hash);
            ctx.metrics().recordChunkUnique();
        } else {
            ctx.metrics().recordChunkDeduped();
        }
        ctx.state().putChunk(dimensionId, packedPos, hash);
    }

    /**
     * 撕裂读处理: 未超重试上限 → 以 attempt+1 重新 offer 回队列 (延后重试, 不入库);
     * 超上限 → 记失败放弃本轮, 不把混合字节入库, 该 chunk 靠下次 BAS save 再采.
     */
    private void handleTornRead(BackupContext ctx, int chunkX, int chunkZ, TornReadException e) {
        if (retryAttempt < MAX_RETRY_ATTEMPTS) {
            LOGGER.warn("[BetterBackup] torn read on chunk ({},{}) dim={} attempt={}, requeue: {}",
                    chunkX, chunkZ, dimensionId, retryAttempt, e.getMessage());
            ctx.retryQueue().offer(new ChunkBackupTask(dimensionId, packedPos, retryAttempt + 1));
        } else {
            LOGGER.error("[BetterBackup] torn read on chunk ({},{}) dim={} exceeded {} retries, "
                            + "skipping this round (will be re-captured on next save): {}",
                    chunkX, chunkZ, dimensionId, MAX_RETRY_ATTEMPTS, e.getMessage());
            ctx.metrics().recordChunkFailed();
        }
    }
}
