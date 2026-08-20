package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在线区域回退的 BB 侧编排前半段: 把 (snapshotId, dimId, 一批 chunk 坐标) 解析成
 * 若干份可交给 BAS 安装的 vanilla {@link CompoundTag}.
 *
 * <p>本类<b>只负责快照解析 + 字节还原</b> (重 IO, 可在 daemon 线程跑), 不碰 server /
 * ServerLevel / 主线程 —— 那部分 (调 BAS {@code SaveCoordination.restoreChunkLive} +
 * outcome 反馈) 在命令层做, 因为只有命令上下文持有 server. 这样切分让本类可被单测覆盖
 * (无 net.minecraft.server 依赖, 只用 ChunkPos / CompoundTag).
 *
 * <p><b>批量入口是唯一入口</b>: 整批目标由 {@link #resolveArea} 一次处理, manifest 在
 * 循环之外只加载一次, 逐块只做内存查表 + store 取字节 + 解压. 单块解析
 * ({@link #resolve}) 拿不到 snapshotId, 因此不可能在按块路径上重新读盘 —— manifest 体积
 * 随世界增长, 按块加载会让整次回退退化成 O(块数 x manifest).
 *
 * <p>与离线回退的关系: 全量重启回退走 {@link RestoreFlow}; 离线停服部分回退走
 * {@code cli.OfflineRestore.restorePartial}; 本类是第三条路 —— 活服即时区域回退,
 * 不写 PendingRestoreFlag, 不停服, 不直写 .mca (直写会与 vanilla IOWorker 撕裂), 而是
 * 把 restored NBT 交 BAS 在内存里原地替换活 chunk.
 *
 * <p>门禁: 与离线部分回退一致, 在线回退<b>不</b>要求 baselineComplete —— 只取已采集的
 * 目标 chunk, 不会因 baseline 未跑完而丢失从未加载的世界部分.
 */
public final class ChunkRestoreFlow {

    private final ChunkStore store;
    private final Path snapshotsDir;
    private final ManifestLoader manifestLoader;

    public ChunkRestoreFlow(ChunkStore store, Path snapshotsDir) {
        this(store, snapshotsDir, SnapshotManifest::readFrom);
    }

    ChunkRestoreFlow(ChunkStore store, Path snapshotsDir, ManifestLoader manifestLoader) {
        this.store = store;
        this.snapshotsDir = snapshotsDir;
        this.manifestLoader = manifestLoader;
    }

    /**
     * 解析一批目标 chunk. manifest 只加载一次, 之后逐块解析.
     *
     * <p>失败分类是调用方文案的依据, 两类不能混: <b>未采集</b> (manifest 里没有该 chunk)
     * 是正常业务分支, 只计数; <b>逐块解析失败</b> (store 缺对象 / 解压或 NBT 解析失败) 收进
     * {@code failures} 并带上原始异常, 个别坏块不拖垮整批. manifest 级失败 (文件缺失 /
     * 读不出) 直接抛出中止整批 —— 那种情况下一块都解析不出来, 逐块重试没有意义.
     *
     * <p>{@code Error} 不在逐块捕获范围内, 直接冒泡: JVM 级故障下继续把剩余块解析出来交给
     * 主线程原地覆盖活 chunk 是危险的.
     *
     * @param snapshotId 快照 id (manifest 文件名去掉 .manifest)
     * @param dimId      canonical 维度 id, 必须与采集侧写入一致 (即 ResourceKey&lt;Level&gt;
     *                   的 {@code location().toString()}, 如 "minecraft:overworld"),
     *                   否则 manifest 取空当作未采集
     * @param targets    目标 chunk 坐标, 顺序即解析顺序
     * @throws IOException manifest 缺失 / 读失败, 整批中止 (异常必须痛, 不在业务层吞)
     */
    public ResolvedArea resolveArea(String snapshotId, String dimId, List<ChunkPos> targets) throws IOException {
        SnapshotManifest manifest = loadManifest(snapshotId);

        List<ResolvedTarget> resolved = new ArrayList<>();
        List<ResolveFailure> failures = new ArrayList<>();
        int notCaptured = 0;
        for (ChunkPos pos : targets) {
            ResolvedChunk r;
            try {
                r = resolve(manifest, dimId, pos.x, pos.z);
            } catch (Exception e) {
                failures.add(new ResolveFailure(pos, e));
                continue;
            }
            if (!r.captured()) {
                notCaptured++;
                continue;
            }
            resolved.add(new ResolvedTarget(pos, r.tag()));
        }
        return new ResolvedArea(List.copyOf(resolved), notCaptured, List.copyOf(failures));
    }

    private SnapshotManifest loadManifest(String snapshotId) throws IOException {
        Path manifestFile = snapshotsDir.resolve(snapshotId + ".manifest");
        if (!Files.exists(manifestFile)) {
            throw new IOException("snapshot manifest not found: " + manifestFile);
        }
        return manifestLoader.load(manifestFile);
    }

    /**
     * 把目标 chunk 在已加载的快照清单里的字节还原成 vanilla NBT.
     *
     * @param manifest 已加载的快照清单, 整批共享同一实例, 本方法只读; 不得写它
     *                 {@code chunks()} 返回的 map (那是清单内部的可变 map, 写入会污染整批)
     * @param dimId    canonical 维度 id
     * @param chunkX   chunk x 坐标
     * @param chunkZ   chunk z 坐标
     * @return 还原结果 (找到则带 CompoundTag, 未采集则标 captured=false 并说明原因)
     * @throws IOException store 字节缺失 / 解压或 NBT 解析失败
     */
    ResolvedChunk resolve(SnapshotManifest manifest, String dimId, int chunkX, int chunkZ) throws IOException {
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

    /** manifest 加载钩子. 生产实现是 {@code SnapshotManifest::readFrom}, 测试注入可计数实现. */
    @FunctionalInterface
    interface ManifestLoader {
        SnapshotManifest load(Path manifestFile) throws IOException;
    }

    /**
     * 单块 resolve 的结果. captured=true 时 pos / tag 非空; false 时 tag 为 null 且 reason
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

    /** 一块已还原、可交给 BAS 安装的目标. */
    public record ResolvedTarget(ChunkPos pos, CompoundTag tag) {
    }

    /** 一块解析失败的目标. cause 是原始异常, 供调用方原样记录. */
    public record ResolveFailure(ChunkPos pos, Exception cause) {
    }

    /** 整批解析结果. resolved 可直接安装; notCaptured 是未采集计数; failures 是逐块失败明细. */
    public record ResolvedArea(List<ResolvedTarget> resolved, int notCaptured, List<ResolveFailure> failures) {
    }
}
