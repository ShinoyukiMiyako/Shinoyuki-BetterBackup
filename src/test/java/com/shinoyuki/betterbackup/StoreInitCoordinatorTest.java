package com.shinoyuki.betterbackup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * store 后台初始化与关停的握手状态机契约 (issue #3 异步化的核心竞态防线).
 *
 * <p>判定标准: 把 completeInit 里的状态检查删掉 (无条件跑 arm), stop_before_init 组必挂;
 * 把锁删掉, 竞态压测组必挂 (arm 与 stop 并发观测到双赢或 arm 发生在 stop 之后).
 */
class StoreInitCoordinatorTest {

    @Test
    void init_then_stop_runs_arm_exactly_once_and_reports_ready() {
        StoreInitCoordinator c = new StoreInitCoordinator();
        AtomicInteger armRuns = new AtomicInteger();

        assertFalse(c.isReady(), "not ready before init completes");
        assertTrue(c.completeInit(armRuns::incrementAndGet), "first completion wins");
        assertEquals(1, armRuns.get(), "arm ran exactly once");
        assertTrue(c.isReady());
        assertEquals(StoreInitCoordinator.State.READY, c.state());

        assertEquals(StoreInitCoordinator.State.READY, c.beginStop(),
                "stop after ready must report READY so shutdown runs the final snapshot");
        assertFalse(c.isReady(), "stopped is not ready");
        assertFalse(c.completeInit(armRuns::incrementAndGet), "re-arm after stop is rejected");
        assertEquals(1, armRuns.get(), "arm must not run again");
    }

    @Test
    void stop_before_init_rejects_arm_and_failure_teardown() {
        StoreInitCoordinator c = new StoreInitCoordinator();
        AtomicInteger armRuns = new AtomicInteger();
        AtomicInteger teardownRuns = new AtomicInteger();

        assertEquals(StoreInitCoordinator.State.INITIALIZING, c.beginStop(),
                "stop during init must report INITIALIZING so shutdown skips the final snapshot");
        assertFalse(c.completeInit(armRuns::incrementAndGet), "late init completion must not arm");
        assertFalse(c.failInit(teardownRuns::incrementAndGet), "late init failure must not double-teardown");
        assertEquals(0, armRuns.get());
        assertEquals(0, teardownRuns.get());
        assertEquals(StoreInitCoordinator.State.STOPPED, c.state());
    }

    @Test
    void fail_then_stop_runs_teardown_once_and_stop_sees_failed() {
        StoreInitCoordinator c = new StoreInitCoordinator();
        AtomicInteger teardownRuns = new AtomicInteger();

        assertTrue(c.failInit(teardownRuns::incrementAndGet), "failure before stop owns the teardown");
        assertEquals(1, teardownRuns.get());
        assertEquals(StoreInitCoordinator.State.FAILED, c.state());
        assertFalse(c.isReady());

        assertEquals(StoreInitCoordinator.State.FAILED, c.beginStop(),
                "stop after failure must report FAILED (no final snapshot)");
    }

    @Test
    void arm_exception_leaves_state_initializing_for_shutdown_teardown() {
        StoreInitCoordinator c = new StoreInitCoordinator();
        RuntimeException boom = new RuntimeException("arm exploded");
        try {
            c.completeInit(() -> {
                throw boom;
            });
        } catch (RuntimeException e) {
            assertEquals(boom, e);
        }
        assertEquals(StoreInitCoordinator.State.INITIALIZING, c.state(),
                "failed arm must not report READY; shutdown treats it as not-ready");
        assertFalse(c.isReady());
    }

    /**
     * 竞态压测: init 线程与关停线程同时开跑. 不变量: (1) arm 与 "stop 观测到 INITIALIZING"
     * 恰好互斥 —— stop 抢先则 arm 永不执行, arm 赢则 stop 必然观测到 READY; (2) arm 至多一次.
     */
    @Test
    void concurrent_stop_and_init_never_arm_after_stop() throws InterruptedException {
        int rounds = 500;
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            StoreInitCoordinator c = new StoreInitCoordinator();
            AtomicInteger armRuns = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger armWon = new AtomicInteger(-1);
            AtomicInteger stopSaw = new AtomicInteger(-1);

            Thread init = new Thread(() -> {
                await(go);
                armWon.set(c.completeInit(armRuns::incrementAndGet) ? 1 : 0);
            });
            Thread stop = new Thread(() -> {
                await(go);
                stopSaw.set(c.beginStop() == StoreInitCoordinator.State.READY ? 1 : 0);
            });
            init.start();
            stop.start();
            go.countDown();
            init.join(5000);
            stop.join(5000);

            if (armRuns.get() > 1) {
                violations.add("round " + i + ": arm ran " + armRuns.get() + " times");
            }
            if (armWon.get() != stopSaw.get()) {
                // arm 赢 (1) 则 stop 必须看到 READY (1); arm 输 (0) 则 stop 必须看到非 READY (0).
                violations.add("round " + i + ": armWon=" + armWon.get() + " stopSawReady=" + stopSaw.get());
            }
            if (armWon.get() == 0 && armRuns.get() != 0) {
                violations.add("round " + i + ": arm executed despite losing to stop");
            }
        }
        assertTrue(violations.isEmpty(), "invariant violations: " + violations);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
