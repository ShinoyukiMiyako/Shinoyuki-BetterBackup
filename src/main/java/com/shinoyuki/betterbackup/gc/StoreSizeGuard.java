package com.shinoyuki.betterbackup.gc;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.retention.RetentionPruner;
import com.shinoyuki.betterbackup.store.ChunkStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * maxStoreSizeGB 软阈值自检的可测编排逻辑. 启动期 (由 {@link BetterBackupMod} 在后台 daemon
 * 线程) 调 {@link #checkAndReclaim}: 若 store 体积超阈值, <b>先</b> {@link RetentionPruner#prune}
 * (删超期 manifest 让独占对象变死) <b>再</b> {@link StoreGc#gcAll} (回收死对象).
 *
 * <p><b>为什么 prune 必须先于 gcAll</b>: store 是内容寻址 append-only, gcAll 的存活根 =
 * "所有存活 manifest 引用的 hash". 单跑 gcAll 而没有先淘汰任何 manifest, 则没有任何对象从"被引用"
 * 变成"死", gcAll 只能回收本就无 manifest 引用的在途碎片 (几乎为 0)——纯空转. 只有先 prune 删掉
 * 超期 manifest, 那些 manifest 独占引用的对象才变死, gcAll 才有东西可回收. 这个顺序是本功能能
 * 真正降体积的唯一前提, 由 {@link #checkAndReclaim} 的调用序 + 空转回归测试共同钉死.
 *
 * <p><b>软阈值, 非硬配额</b>: 回收量被"受保留策略保护的活数据"封顶. 即便 gcAll 跑完, 体积仍可能
 * 高于阈值 (retention 窗口内的活快照撑住体积), 此时 {@link Result#stillOver} 为 true, 调用方据此
 * WARN 提示用户调 retention / 缩短保留窗口. 这不是环形缓冲也不是磁盘 quota——上限兜不住时如实告知.
 *
 * <p><b>纯逻辑</b>: 不建线程、不 sleep、不阻塞. 后台化由调用方负责 (本类只被后台线程调用),
 * 故本类可脱离 MC 启动侧直接单测.
 */
public final class StoreSizeGuard {

    private final ChunkStore store;
    private final Path snapshotsDir;
    private final RetentionPruner pruner;
    private final long maxBytes;

    /**
     * 生产入口: 用当前 config 的 retention 配额构造 pruner (经 snapshotsDir + worldRoot).
     *
     * @param store        dedup store, 供算体积 + gcAll 回收
     * @param snapshotsDir manifest 目录, gcAll 的存活根来源 + pruner 删除目标
     * @param worldRoot    世界根, {@link RetentionPruner} 三门禁 (pending-restore flag 等) 需要
     * @param maxBytes     体积软阈值 (字节); store 超过它才触发 prune+gcAll
     */
    public StoreSizeGuard(ChunkStore store, Path snapshotsDir, Path worldRoot, long maxBytes) {
        this(store, snapshotsDir, new RetentionPruner(
                Objects.requireNonNull(snapshotsDir, "snapshotsDir"),
                Objects.requireNonNull(worldRoot, "worldRoot")), maxBytes);
    }

    /**
     * 测试入口: 注入显式 pruner (通常带确定 policy), 不依赖静态 config. 供空转回归 (retainsNothing
     * 的 pruner) 与"触发且降下来"两类测试注入不同 policy.
     */
    StoreSizeGuard(ChunkStore store, Path snapshotsDir, RetentionPruner pruner, long maxBytes) {
        this.store = Objects.requireNonNull(store, "store");
        this.snapshotsDir = Objects.requireNonNull(snapshotsDir, "snapshotsDir");
        this.pruner = Objects.requireNonNull(pruner, "pruner");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        this.maxBytes = maxBytes;
    }

    /**
     * 算体积; 超阈值则先 prune 再 gcAll, 返回结果 record. 未超阈值直接返回 {@code triggered=false}
     * 且不调 pruner / gcAll (体积算完即止, 零副作用).
     *
     * <p>异常自然冒泡 (体积 walk / prune / gcAll 任一失败): 调用方 (后台线程) 只 WARN 不中止启动.
     * 本类不吞异常.
     *
     * @return 本次自检结果 (before/触发/prune 删几份/gcAll 回收字节/after/是否仍超阈值)
     * @throws IOException 算体积 / prune / gcAll 的文件系统操作失败
     */
    public Result checkAndReclaim() throws IOException {
        long before = store.approxStoreBytes();
        if (before <= maxBytes) {
            return new Result(before, false, 0, 0L, before, false);
        }

        // 触发: 先 prune 删超期 manifest (让独占对象变死), 再 gcAll 回收死对象. 顺序不可交换 (见类注释).
        RetentionPruner.PruneResult pruneResult = pruner.prune();
        int prunedManifests = pruneResult.deleted().size();

        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult gcResult = gc.gcAll();

        long after = store.approxStoreBytes();
        boolean stillOver = after > maxBytes;
        return new Result(before, true, prunedManifests, gcResult.bytesFreed(), after, stillOver);
    }

    /**
     * store 体积自检结果.
     *
     * @param beforeBytes      触发前 store 体积 (字节)
     * @param triggered        是否越过阈值触发了 prune+gcAll
     * @param prunedManifests  prune 实删的 manifest 份数 (未触发为 0)
     * @param gcBytesFreed     gcAll 物理回收字节数 (未触发为 0)
     * @param afterBytes       gcAll 后 store 体积 (未触发 == beforeBytes)
     * @param stillOver        gcAll 后仍高于阈值 (回收被活数据封顶); 调用方据此 WARN
     */
    public record Result(long beforeBytes, boolean triggered, int prunedManifests,
                         long gcBytesFreed, long afterBytes, boolean stillOver) {
    }
}
