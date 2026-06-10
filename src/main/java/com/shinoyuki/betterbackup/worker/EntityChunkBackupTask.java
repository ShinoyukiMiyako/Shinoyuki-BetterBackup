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
 * entity chunk 路径备份 (BAS EntityChunkSaveListener fire 时入 queue).
 * 读 entities/r.x.z.mca 里指定 chunk slot raw bytes → hash → store → state.
 *
 * <p>逻辑跟 {@link ChunkBackupTask} 同, 仅 region 子目录从 region/ 换成 entities/.
 * 撕裂读重试语义见 {@link ChunkBackupTask} 的类注释.
 */
public record EntityChunkBackupTask(String dimensionId, long packedPos, int retryAttempt) implements BackupTask {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    public EntityChunkBackupTask(String dimensionId, long packedPos) {
        this(dimensionId, packedPos, 0);
    }

    @Override
    public String taskName() {
        return "entity@" + ChunkPos.getX(packedPos) + "," + ChunkPos.getZ(packedPos)
                + "/" + dimensionId + (retryAttempt > 0 ? " (retry " + retryAttempt + ")" : "");
    }

    @Override
    public void execute(BackupContext ctx) throws IOException {
        ctx.metrics().recordEntityReceived();
        int chunkX = ChunkPos.getX(packedPos);
        int chunkZ = ChunkPos.getZ(packedPos);
        Path entitiesDir = ctx.paths().entitiesDir(dimensionId);
        byte[] rawBytes;
        try {
            rawBytes = RegionFileSlotReader.readChunk(entitiesDir, chunkX, chunkZ);
        } catch (TornReadException e) {
            handleTornRead(ctx, chunkX, chunkZ, e);
            return;
        } catch (IOException e) {
            ctx.metrics().recordEntityFailed();
            throw e;
        }
        if (rawBytes == null) {
            LOGGER.warn("[BetterBackup] entity slot empty after BAS fire: pos=({},{}) dim={}",
                    chunkX, chunkZ, dimensionId);
            ctx.metrics().recordEntityFailed();
            return;
        }
        Hash hash = ctx.hashFunction().hash(rawBytes);
        boolean wrote = ctx.store().put(hash, rawBytes);
        // 先登记 state 再 add writtenThisWindow (GC 并发安全, 见 ChunkBackupTask 同址注释).
        ctx.state().putEntityChunk(dimensionId, packedPos, hash);
        if (wrote) {
            ctx.writtenThisWindow().add(hash);
            ctx.metrics().recordEntityUnique();
        } else {
            ctx.metrics().recordEntityDeduped();
        }
    }

    /**
     * 撕裂读处理: 未超重试上限 → 以 attempt+1 重新 offer 回队列 (延后重试, 不入库);
     * 超上限 → 记失败放弃本轮, 不把混合字节入库, 该 chunk 靠下次 BAS save 再采.
     */
    private void handleTornRead(BackupContext ctx, int chunkX, int chunkZ, TornReadException e) {
        if (retryAttempt < ChunkBackupTask.MAX_RETRY_ATTEMPTS) {
            LOGGER.warn("[BetterBackup] torn read on entity chunk ({},{}) dim={} attempt={}, requeue: {}",
                    chunkX, chunkZ, dimensionId, retryAttempt, e.getMessage());
            ctx.retryQueue().offer(new EntityChunkBackupTask(dimensionId, packedPos, retryAttempt + 1));
        } else {
            LOGGER.error("[BetterBackup] torn read on entity chunk ({},{}) dim={} exceeded {} retries, "
                            + "skipping this round (will be re-captured on next save): {}",
                    chunkX, chunkZ, dimensionId, ChunkBackupTask.MAX_RETRY_ATTEMPTS, e.getMessage());
            ctx.metrics().recordEntityFailed();
        }
    }
}
