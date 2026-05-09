package com.shinoyuki.betterbackup.integration;

import com.shinoyuki.betterautosave.api.ChunkSaveListener;
import com.shinoyuki.betterautosave.api.EntityChunkSaveListener;
import com.shinoyuki.betterautosave.api.SavedDataSaveListener;
import com.shinoyuki.betterbackup.worker.BackupTask;
import com.shinoyuki.betterbackup.worker.ChunkBackupTask;
import com.shinoyuki.betterbackup.worker.EntityChunkBackupTask;
import com.shinoyuki.betterbackup.worker.SavedDataBackupTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.concurrent.BlockingQueue;

/**
 * BAS Listener 桥到 BetterBackup BackupWorker queue.
 *
 * <p>一个实例同时实现 BAS 三类 listener 接口, 注册到 SaveListenerRegistry 后:
 * <ul>
 *   <li>BAS 在 BAS worker 线程 fire</li>
 *   <li>本 listener 立即把 ChunkPos+dim / fileName 包成 BackupTask offer 入 queue</li>
 *   <li>BAS worker 线程立即返回 (offer 是 atomic 不阻塞)</li>
 *   <li>BetterBackup worker 线程消费 queue 跑 hash + store.put</li>
 * </ul>
 *
 * <p>本 listener 不读 {@code tag} 参数 — DESIGN §2.1 方案 A 直接读 .mca 磁盘字节,
 * 不依赖 BAS 给的内存 NBT.
 */
public final class BackupListenerBridge
        implements ChunkSaveListener, EntityChunkSaveListener, SavedDataSaveListener {

    private final BlockingQueue<BackupTask> queue;

    public BackupListenerBridge(BlockingQueue<BackupTask> queue) {
        this.queue = queue;
    }

    @Override
    public void onChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag) {
        queue.offer(new ChunkBackupTask(dimension.location().toString(), pos.toLong()));
    }

    @Override
    public void onEntityChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag) {
        queue.offer(new EntityChunkBackupTask(dimension.location().toString(), pos.toLong()));
    }

    @Override
    public void onSavedDataWritten(String fileName, CompoundTag tag) {
        queue.offer(new SavedDataBackupTask(fileName));
    }
}
