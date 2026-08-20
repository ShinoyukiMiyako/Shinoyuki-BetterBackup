package com.shinoyuki.betterbackup.diagnostic;

import com.shinoyuki.betterbackup.baseline.DirtyWaterMarkGate;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.worker.BackupContext;
import com.shinoyuki.betterbackup.worker.BackupTask;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dirty 水位与队列深度必须真的推给 metrics: 排查积压时 Prometheus 的 bbb_dirty_map_size /
 * bbb_queue_depth 是唯一的外部证据, 恒为 0 等于没有证据.
 *
 * <p>判定标准: 删掉 publishGauges 里的两行 setter, 两个 gauge 停在初值 0, 断言必挂.
 */
class DiagnosticGaugesTest {

    private static BackupTask dummyTask() {
        return new BackupTask() {
            @Override
            public String taskName() {
                return "dummy";
            }

            @Override
            public void execute(BackupContext ctx) {
                // 只作为队列填充物, 从不执行.
            }
        };
    }

    private static Hash hash(int seed) {
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return new Hash(bytes);
    }

    @Test
    void publish_gauges_reports_real_dirty_size_and_queue_depth() {
        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putChunk("minecraft:overworld", 1L, hash(1));
        state.putChunk("minecraft:overworld", 2L, hash(2));
        state.putChunk("minecraft:the_nether", 3L, hash(3));
        state.putEntityChunk("minecraft:overworld", 1L, hash(4));
        state.putEntityChunk("minecraft:overworld", 2L, hash(5));
        state.putSavedData("data/raids.dat", hash(6));

        BlockingQueue<BackupTask> queue = new LinkedBlockingQueue<>();
        for (int i = 0; i < 4; i++) {
            queue.add(dummyTask());
        }
        BetterBackupMetrics metrics = new BetterBackupMetrics();

        DiagnosticLogger.publishGauges(state, queue, metrics);

        assertEquals(6L, metrics.snapshot().dirtyMapSize());
        assertEquals(4L, metrics.snapshot().queueDepth());

        // drain 之后水位必须跟着回落, 不是只涨不落的计数器.
        state.drainAndClear();
        queue.clear();
        DiagnosticLogger.publishGauges(state, queue, metrics);

        assertEquals(0L, metrics.snapshot().dirtyMapSize());
        assertEquals(0L, metrics.snapshot().queueDepth());
    }

    @Test
    void heartbeat_still_fires_while_the_baseline_scan_is_backpressured() {
        // 背压期间三个计数与队列深度全都静止, 没有这条放行服主就一行线索都看不到.
        assertFalse(DiagnosticLogger.shouldLog(7L, 3L, 1L, 0, false, 7L, 3L, 1L),
                "完全没变化时不该打, 否则空跑期间刷屏");
        assertTrue(DiagnosticLogger.shouldLog(7L, 3L, 1L, 0, true, 7L, 3L, 1L),
                "被闸住时即使读数不动也必须打");
    }

    @Test
    void backpressure_field_distinguishes_no_scan_from_a_scan_that_is_not_blocked() {
        assertEquals("n/a", DiagnosticLogger.describeBackpressure(null),
                "没有扫描线程时不得冒充成一个未触发的闸门");

        DirtyWaterMarkGate idle = new DirtyWaterMarkGate(() -> 0, () -> 1000);
        assertEquals("off(paused 0s total)", DiagnosticLogger.describeBackpressure(idle));

        // 水位恒在阈值之上, 在另一条线程上把闸门真正闸住, 主线程读它的对外读数.
        java.util.concurrent.atomic.AtomicBoolean abort = new java.util.concurrent.atomic.AtomicBoolean();
        DirtyWaterMarkGate blocked = new DirtyWaterMarkGate(() -> 10_000, () -> 1_000);
        Thread blockedScan = new Thread(() -> blocked.awaitRoom(abort::get), "gate-probe");
        blockedScan.setDaemon(true);
        blockedScan.start();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!blocked.isBlocked() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(blocked.isBlocked(), "闸门应已进入阻塞段");
            assertTrue(DiagnosticLogger.describeBackpressure(blocked).startsWith("blocked(paused "),
                    "阻塞中必须报 blocked");
        } finally {
            abort.set(true);
            try {
                blockedScan.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
