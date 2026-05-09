package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.store.Hash;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * chunk 路径备份 (BAS ChunkSaveListener fire 时入 queue).
 * 读 region/r.x.z.mca 里指定 chunk slot raw bytes → hash → store → state.
 */
public record ChunkBackupTask(String dimensionId, long packedPos) implements BackupTask {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    @Override
    public String taskName() {
        return "chunk@" + ChunkPos.getX(packedPos) + "," + ChunkPos.getZ(packedPos)
                + "/" + dimensionId;
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
}
