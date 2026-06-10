package com.shinoyuki.betterbackup.integration;

import com.shinoyuki.betterautosave.api.PipelineStateListener;
import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.schedule.SnapshotScheduler;
import com.shinoyuki.betterbackup.snapshot.DegradedSession;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Objects;

/**
 * BAS 降级信号处理 (PLAN Phase F). 注册到 BAS SaveListenerRegistry 的
 * {@link PipelineStateListener} 通道, BAS 管线首次进入 degraded mode 时回调一次:
 * <ol>
 *   <li>暂停快照创建: stop scheduler, 之后不再有 interval 定时触发 (关服最终快照仍会跑,
 *       但会被标 incomplete)</li>
 *   <li>后续快照标 incomplete: {@link SnapshotCreator#markDegraded()} 置闩锁, 此后本进程
 *       产出的每份快照 manifest.incomplete=true (BAS 已停 fire, 活跃 dirty 路径失明)</li>
 *   <li>持久化降级会话标志: {@link DegradedSession#mark} 落标志到 store 目录, 下次启动
 *       据此对降级窗口变更的 region 做补采重扫</li>
 * </ol>
 *
 * <p><b>调用次数</b>: BAS 侧 degraded 是单向闩锁, firePipelineDegraded 每进程最多一次,
 * 故 onDegraded 单进程最多回调一次. 即便多次回调, 三步操作均幂等.
 *
 * <p><b>异常处理</b>: 持久化标志失败只 log error 不抛 — SaveListenerRegistry.fire 会
 * catch listener 异常, 但更重要的是不能让标志写盘失败掩盖掉 markDegraded / stop 这两个
 * 内存操作 (它们已先执行, 当前进程的快照仍会被正确标 incomplete). 标志写失败的代价是
 * 下次启动不补采, 由日志告警让服主可见.
 */
public final class PipelineDegradedHandler implements PipelineStateListener {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private final SnapshotCreator creator;
    private final SnapshotScheduler scheduler;
    private final DegradedSession degradedSession;

    public PipelineDegradedHandler(SnapshotCreator creator,
                                   SnapshotScheduler scheduler,
                                   DegradedSession degradedSession) {
        this.creator = Objects.requireNonNull(creator, "creator");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.degradedSession = Objects.requireNonNull(degradedSession, "degradedSession");
    }

    @Override
    public void onDegraded() {
        LOGGER.error("[BetterBackup] BAS pipeline entered DEGRADED mode: pausing scheduled snapshots, "
                + "marking subsequent snapshots incomplete, persisting degraded-session flag for next-start rescan");
        // 顺序: 先置内存闩锁与停 scheduler (不可失败), 再持久化标志 (可能 IOException).
        creator.markDegraded();
        scheduler.stop();
        try {
            degradedSession.mark(System.currentTimeMillis());
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] failed to persist degraded-session flag; "
                    + "next start will NOT rescan the degraded window (current snapshots are still marked incomplete)", e);
        }
    }
}
