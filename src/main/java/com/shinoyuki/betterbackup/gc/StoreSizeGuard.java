package com.shinoyuki.betterbackup.gc;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.retention.RetentionPruner;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

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

    /**
     * 启动自检的 pack 重写阈值: 只重写死字节占比过半的 pack. 自检是每次越阈值启动都会跑的无人值守
     * 后台任务, 阈值 0 会把任何含一丝死字节的 pack 全部重写 (数百 GB store 的读写风暴, 与启动期
     * 写入争用磁盘, 且仍越阈值的每次启动都重来); 压到"回收划算"的子集后重写 I/O 有界. 全死 pack
     * (retention 淘汰后的主要回收来源) 无视阈值一律直接删除, 不受影响. 阈值 0 的彻底回收保留给
     * 运营手动 {@code /betterbackup gc} 择时执行.
     */
    static final double STARTUP_COMPACT_DEAD_RATIO_THRESHOLD = 0.5;

    private final ChunkStore store;
    private final Path snapshotsDir;
    private final RetentionPruner pruner;
    private final long maxBytes;
    private final Supplier<Set<Hash>> inFlightProtect;

    /**
     * 生产入口: 用当前 config 的 retention 配额构造 pruner (经 snapshotsDir + worldRoot).
     *
     * @param store           dedup store, 供算体积 + gcAll 回收
     * @param snapshotsDir    manifest 目录, gcAll 的存活根来源 + pruner 删除目标
     * @param worldRoot       世界根, {@link RetentionPruner} 三门禁 (pending-restore flag 等) 需要
     * @param maxBytes        体积软阈值 (字节); store 超过它才触发 prune+gcAll
     * @param inFlightProtect 在途保护集供应器 (pendingHashes ∪ writtenThisWindow); gcAll 时求值,
     *                        保护启动期 worker / baseline 已 put 但尚未进 manifest 的对象不被误删
     */
    public StoreSizeGuard(ChunkStore store, Path snapshotsDir, Path worldRoot, long maxBytes,
                          Supplier<Set<Hash>> inFlightProtect) {
        this(store, snapshotsDir, new RetentionPruner(
                Objects.requireNonNull(snapshotsDir, "snapshotsDir"),
                Objects.requireNonNull(worldRoot, "worldRoot")), maxBytes, inFlightProtect);
    }

    /**
     * 测试入口: 注入显式 pruner (通常带确定 policy) 与在途保护集供应器, 不依赖静态 config /
     * BetterBackupCore. 供空转回归 (retainsNothing 的 pruner)、"触发且降下来"、在途对象受保护三类
     * 测试注入不同 policy / protect.
     */
    StoreSizeGuard(ChunkStore store, Path snapshotsDir, RetentionPruner pruner, long maxBytes,
                   Supplier<Set<Hash>> inFlightProtect) {
        this.store = Objects.requireNonNull(store, "store");
        this.snapshotsDir = Objects.requireNonNull(snapshotsDir, "snapshotsDir");
        this.pruner = Objects.requireNonNull(pruner, "pruner");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        this.maxBytes = maxBytes;
        this.inFlightProtect = Objects.requireNonNull(inFlightProtect, "inFlightProtect");
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

        // 活服自检: 传在途保护集 (求值于此刻) 且不封口在写 pack, 与增量压实同源防并发误删——
        // 启动期 worker / baseline 正并发写入尚未进 manifest 的对象. pack 重写用有界阈值 (见常量注释).
        StoreGc gc = new StoreGc(store, snapshotsDir);
        StoreGc.GcResult gcResult = gc.gcAll(inFlightProtect.get(), false, STARTUP_COMPACT_DEAD_RATIO_THRESHOLD);

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
