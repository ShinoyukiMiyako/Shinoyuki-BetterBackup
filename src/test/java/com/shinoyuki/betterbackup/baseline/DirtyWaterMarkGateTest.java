package com.shinoyuki.betterbackup.baseline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DirtyWaterMarkGate} 的阻塞 / 滞回 / 中止语义测试. 用假等待器统计轮次并推进假时钟,
 * 不真睡.
 *
 * <p>判定标准: 删掉 awaitRoom 的阻塞循环, "触顶必须等待"的轮次断言必挂; 把放行条件从
 * "回落到一半" 改成 "低于阈值", 滞回用例必挂; 删掉循环最前的 abort 检查, 中止用例会打满
 * 假等待器的轮次上限抛异常。
 */
class DirtyWaterMarkGateTest {

    /** 假等待器: 记录每轮等待时长并推进假时钟, 每轮回调可改水位. 超上限抛异常, 防真死循环. */
    private static final class FakeWaiter implements DirtyWaterMarkGate.Waiter {

        private static final int MAX_ROUNDS = 10_000;

        final List<Long> waits = new ArrayList<>();
        final AtomicLong nanos = new AtomicLong();
        final AtomicInteger nanoReads = new AtomicInteger();
        private final IntConsumer perRound;
        private int interruptAtRound = -1;

        FakeWaiter(IntConsumer perRound) {
            this.perRound = perRound;
        }

        void interruptAtRound(int round) {
            this.interruptAtRound = round;
        }

        @Override
        public boolean await(long millis) {
            waits.add(millis);
            nanos.addAndGet(millis * 1_000_000L);
            if (waits.size() > MAX_ROUNDS) {
                throw new IllegalStateException("gate never released after " + MAX_ROUNDS + " rounds");
            }
            perRound.accept(waits.size());
            return interruptAtRound < 0 || waits.size() != interruptAtRound;
        }

        long nano() {
            nanoReads.incrementAndGet();
            return nanos.get();
        }
    }

    private static DirtyWaterMarkGate gate(AtomicInteger level, AtomicInteger high, FakeWaiter waiter) {
        return new DirtyWaterMarkGate(level::get, high::get, waiter::nano, waiter);
    }

    @Test
    void below_high_water_never_waits() {
        AtomicInteger level = new AtomicInteger(1_000);
        AtomicInteger high = new AtomicInteger(500_000);
        FakeWaiter waiter = new FakeWaiter(round -> { });
        DirtyWaterMarkGate gate = gate(level, high, waiter);

        gate.awaitRoom(() -> false);

        assertTrue(waiter.waits.isEmpty(), "水位低于阈值必须走零开销快路径");
        // 判别快路径本身: 快路径在读时钟之前就 return, 一次 nanoTime 都不该发生.
        // 只断言 waits 为空是不够的 —— 删掉快路径后循环首轮就放行, waits 同样为空.
        assertEquals(0, waiter.nanoReads.get(), "快路径不得进入计时段");
        assertFalse(gate.isBlocked());
        assertEquals(0L, gate.blockedMillisTotal());
    }

    @Test
    void above_high_water_blocks_until_level_drops_to_resume_mark() {
        AtomicInteger level = new AtomicInteger(600_000);
        AtomicInteger high = new AtomicInteger(500_000);
        // 第 3 轮把水位降到放行线 (250000) 以下.
        FakeWaiter waiter = new FakeWaiter(round -> {
            if (round == 3) {
                level.set(200_000);
            }
        });
        DirtyWaterMarkGate gate = gate(level, high, waiter);

        gate.awaitRoom(() -> false);

        assertEquals(3, waiter.waits.size(), "触顶后必须一直等到水位回落");
        for (long w : waiter.waits) {
            assertEquals(DirtyWaterMarkGate.POLL_INTERVAL_MS, w);
        }
        assertFalse(gate.isBlocked(), "放行后必须复位, 否则下一个 slot 不走快路径");
        assertEquals(3 * DirtyWaterMarkGate.POLL_INTERVAL_MS, gate.blockedMillisTotal());
    }

    @Test
    void hysteresis_keeps_blocking_between_resume_and_high_water() {
        AtomicInteger level = new AtomicInteger(600_000);
        AtomicInteger high = new AtomicInteger(500_000);
        FakeWaiter waiter = new FakeWaiter(round -> {
            if (round == 2) {
                level.set(400_000); // 低于阈值但高于放行线, 仍须继续等
            } else if (round == 6) {
                level.set(250_000); // 恰好到放行线
            }
        });
        DirtyWaterMarkGate gate = gate(level, high, waiter);

        gate.awaitRoom(() -> false);

        assertEquals(6, waiter.waits.size(),
                "放行线是阈值的一半, 只掉回阈值以下不得放行 (否则会在阈值附近每个 slot 抖动)");
    }

    @Test
    void abort_returns_immediately_without_further_waits() {
        AtomicInteger level = new AtomicInteger(600_000); // 永不回落
        AtomicInteger high = new AtomicInteger(500_000);
        AtomicBoolean abort = new AtomicBoolean(false);
        FakeWaiter waiter = new FakeWaiter(round -> abort.set(true));
        DirtyWaterMarkGate gate = gate(level, high, waiter);

        gate.awaitRoom(abort::get);

        assertEquals(1, waiter.waits.size(), "收到停止请求后最多再等一轮就必须返回");
        assertFalse(gate.isBlocked());
    }

    @Test
    void interrupted_wait_stops_blocking_and_restores_interrupt_flag() {
        AtomicInteger level = new AtomicInteger(600_000); // 永不回落
        AtomicInteger high = new AtomicInteger(500_000);
        FakeWaiter waiter = new FakeWaiter(round -> { });
        waiter.interruptAtRound(1);
        DirtyWaterMarkGate gate = gate(level, high, waiter);

        gate.awaitRoom(() -> false);

        assertEquals(1, waiter.waits.size(), "等待被中断后不得继续空转");
        assertFalse(gate.isBlocked());
        assertTrue(Thread.interrupted(), "中断位必须还给调用线程 (顺带清掉, 不污染后续用例)");
    }

    @Test
    void raising_the_high_water_mark_releases_a_blocked_scan() {
        AtomicInteger level = new AtomicInteger(600_000);
        AtomicInteger high = new AtomicInteger(500_000);
        // 阈值改大 (放行线随之抬到 2500000) 后, 同一水位立即够格放行 —— 阈值是每轮现读的.
        FakeWaiter waiter = new FakeWaiter(round -> {
            if (round == 2) {
                high.set(5_000_000);
            }
        });
        DirtyWaterMarkGate gate = gate(level, high, waiter);

        gate.awaitRoom(() -> false);

        assertEquals(2, waiter.waits.size(), "阈值必须每轮现读, 改 config 无需重启即可解闸");
    }
}
