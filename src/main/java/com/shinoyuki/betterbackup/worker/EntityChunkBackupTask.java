package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
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
 */
public record EntityChunkBackupTask(String dimensionId, long packedPos) implements BackupTask {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    @Override
    public String taskName() {
        return "entity@" + ChunkPos.getX(packedPos) + "," + ChunkPos.getZ(packedPos)
                + "/" + dimensionId;
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
        if (wrote) {
            ctx.writtenThisWindow().add(hash);
            ctx.metrics().recordEntityUnique();
        } else {
            ctx.metrics().recordEntityDeduped();
        }
        ctx.state().putEntityChunk(dimensionId, packedPos, hash);
    }
}
