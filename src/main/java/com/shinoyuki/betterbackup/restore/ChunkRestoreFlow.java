package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 在线单 chunk 回退的 BB 侧编排前半段: 把 (snapshotId, dimId, chunkX, chunkZ) 解析成
 * 一份可交给 BAS 安装的 vanilla {@link CompoundTag}.
 *
 * <p>本类<b>只负责快照解析 + 字节还原</b> (重 IO, 可在 daemon 线程跑), 不碰 server /
 * ServerLevel / 主线程 —— 那部分 (调 BAS {@code SaveCoordination.restoreChunkLive} +
 * outcome 反馈) 在命令层做, 因为只有命令上下文持有 server. 这样切分让本类可被单测覆盖
 * (无 net.minecraft.server 依赖, 只用 ChunkPos / CompoundTag).
 *
 * <p>与离线回退的关系: 全量重启回退走 {@link RestoreFlow}; 离线停服部分回退走
 * {@code cli.OfflineRestore.restorePartial}; 本类是第三条路 —— 活服即时单 chunk 回退,
 * 不写 PendingRestoreFlag, 不停服, 不直写 .mca (直写会与 vanilla IOWorker 撕裂), 而是
 * 把 restored NBT 交 BAS 在内存里原地替换活 chunk.
 *
 * <p>门禁: 与离线部分回退一致, 在线单 chunk <b>不</b>要求 baselineComplete —— 只取已
 * 采集的那一个目标 chunk, 不会因 baseline 未跑完而丢失从未加载的世界部分.
 */
public final class ChunkRestoreFlow {

    private final ChunkStore store;
    private final Path snapshotsDir;

    public ChunkRestoreFlow(ChunkStore store, Path snapshotsDir) {
        this.store = store;
        this.snapshotsDir = snapshotsDir;
    }

    /**
     * 把目标 chunk 在指定快照里的字节还原成 vanilla NBT.
     *
     * @param snapshotId 快照 id (manifest 文件名去掉 .manifest)
     * @param dimId      canonical 维度 id, 必须与采集侧写入一致 (即 ResourceKey&lt;Level&gt;
     *                   的 {@code location().toString()}, 如 "minecraft:overworld"),
     *                   否则 manifest 取空当作未采集
     * @param chunkX     chunk x 坐标
     * @param chunkZ     chunk z 坐标
     * @return 还原结果 (找到则带 CompoundTag, 未采集则标 captured=false 并说明原因)
     * @throws IOException manifest 缺失 / 读失败 / store 字节缺失 / 解压或 NBT 解析失败,
     *                     一律自然冒泡 (异常必须痛, 不在业务层吞)
     */
    public ResolvedChunk resolve(String snapshotId, String dimId, int chunkX, int chunkZ) throws IOException {
        Path manifestFile = snapshotsDir.resolve(snapshotId + ".manifest");
        if (!Files.exists(manifestFile)) {
            throw new IOException("snapshot manifest not found: " + manifestFile);
        }
        SnapshotManifest manifest = SnapshotManifest.readFrom(manifestFile);

        long packed = ChunkPos.asLong(chunkX, chunkZ);
        Map<Long, Hash> dimChunks = manifest.chunks().getOrDefault(dimId, Map.of());
        Hash hash = dimChunks.get(packed);
        if (hash == null) {
            // 未采集: manifest 没有该 (dim, chunk). 明确返回未采集 (调用方报错给玩家),
            // 不静默当成空 chunk —— 把一个从没采过的 chunk 当空区块回写会抹掉真实世界.
            String reason = manifest.chunks().containsKey(dimId)
                    ? "chunk (" + chunkX + "," + chunkZ + ") was not captured in dimension " + dimId
                    : "dimension " + dimId + " has no captured chunks in this snapshot";
            return ResolvedChunk.notCaptured(reason);
        }

        if (!store.has(hash)) {
            // manifest 引用了 hash 但 store 里没有: store 损坏 / 被 GC 误删. 让它痛.
            throw new IOException("store is missing referenced object " + hash.toHex()
                    + " for chunk (" + chunkX + "," + chunkZ + ") in " + dimId);
        }

        byte[] storeObject = store.get(hash);
        CompoundTag tag = ChunkSlotNbtCodec.decode(storeObject);
        return ResolvedChunk.captured(new ChunkPos(chunkX, chunkZ), tag);
    }

    /**
     * resolve 的结果. captured=true 时 pos / tag 非空; false 时 tag 为 null 且 reason
     * 说明为何未采集 (维度无采集 / 该 chunk 无采集).
     */
    public record ResolvedChunk(boolean captured, ChunkPos pos, CompoundTag tag, String reason) {

        static ResolvedChunk captured(ChunkPos pos, CompoundTag tag) {
            return new ResolvedChunk(true, pos, tag, null);
        }

        static ResolvedChunk notCaptured(String reason) {
            return new ResolvedChunk(false, null, null, reason);
        }
    }
}
