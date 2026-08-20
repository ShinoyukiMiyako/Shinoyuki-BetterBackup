package com.shinoyuki.betterbackup.baseline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ThrottlingRateLimiter 节流逻辑测试. 用假时钟 + 假 sleeper 断言具体 sleep 量, 不真睡.
 * 标准: 删掉 acquire 里的 wait 计算, 这些断言必挂.
 */
class ThrottlingRateLimiterTest {

    /** 假时钟: sleep 时把消耗的纳秒计入 now, 模拟时间真实流逝, 让后续 acquire 不重复等待. */
    private static final class FakeClock {
        final AtomicLong now = new AtomicLong();
        final List<Long> sleeps = new ArrayList<>();

        long nano() {
            return now.get();
        }

        void sleep(long nanos) {
            sleeps.add(nanos);
            now.addAndGet(nanos);
        }

        void advance(long nanos) {
            now.addAndGet(nanos);
        }
    }

    @Test
    void rejects_non_positive_rate() {
        assertThrows(IllegalArgumentException.class, () -> new ThrottlingRateLimiter(0));
        assertThrows(IllegalArgumentException.class, () -> new ThrottlingRateLimiter(-5));
    }

    @Test
    void rejects_non_positive_rate_from_supplier_at_construction() {
        // 配置没加载 (值为 0) 这类接线错误必须在构造时响亮失败, 不能退化成 1 chunk/s 静默扫.
        assertThrows(IllegalArgumentException.class, () -> new ThrottlingRateLimiter(() -> 0));
    }

    @Test
    void illegal_live_rate_falls_back_to_the_construction_time_value() {
        FakeClock clock = new FakeClock();
        AtomicInteger rate = new AtomicInteger(50); // 20ms 间隔
        ThrottlingRateLimiter rl = new ThrottlingRateLimiter(rate::get, clock::nano, clock::sleep);

        rl.acquire(); // prime
        rl.acquire();
        rate.set(0);  // 只可能是接线出错; 热路径上不得因此炸掉扫描线程
        rl.acquire();

        assertEquals(2, clock.sleeps.size());
        assertEquals(20_000_000L, clock.sleeps.get(1), "非法速率必须退回构造期已校验的值, 而不是除零");
    }

    @Test
    void rate_change_applies_to_the_next_acquire() {
        FakeClock clock = new FakeClock();
        AtomicInteger rate = new AtomicInteger(50); // 20ms 间隔
        ThrottlingRateLimiter rl = new ThrottlingRateLimiter(rate::get, clock::nano, clock::sleep);

        rl.acquire(); // 首次 prime, 不 sleep
        rl.acquire(); // 按 50/s 补满 20ms
        rate.set(200); // 200/s -> 5ms 间隔
        rl.acquire(); // 必须立即按新速率走, 不能再按旧间隔多拖一轮

        assertEquals(2, clock.sleeps.size());
        assertEquals(20_000_000L, clock.sleeps.get(0));
        assertEquals(5_000_000L, clock.sleeps.get(1), "改 config 后下一次 acquire 就该按新速率");
    }

    @Test
    void steady_calls_with_no_elapsed_time_sleep_full_interval() {
        FakeClock clock = new FakeClock();
        // 10 chunk/s -> 最小间隔 100ms = 100_000_000 ns
        ThrottlingRateLimiter rl = new ThrottlingRateLimiter(10, clock::nano, clock::sleep);

        rl.acquire(); // 首次 prime, 不 sleep
        rl.acquire(); // 距上次 0ns, 需补满 100ms
        rl.acquire(); // 同上

        assertEquals(2, clock.sleeps.size(), "first acquire primes (no sleep), next two each sleep");
        assertEquals(100_000_000L, clock.sleeps.get(0));
        assertEquals(100_000_000L, clock.sleeps.get(1));
    }

    @Test
    void elapsed_time_reduces_required_sleep() {
        FakeClock clock = new FakeClock();
        ThrottlingRateLimiter rl = new ThrottlingRateLimiter(10, clock::nano, clock::sleep); // 100ms interval

        rl.acquire();                 // prime at t=0, nextAllowed=100ms
        clock.advance(40_000_000L);   // 实际处理一个 chunk 已耗 40ms
        rl.acquire();                 // 只需再等 60ms

        assertEquals(1, clock.sleeps.size());
        assertEquals(60_000_000L, clock.sleeps.get(0), "must subtract already-elapsed 40ms from the 100ms interval");
    }

    @Test
    void slow_consumer_never_sleeps() {
        FakeClock clock = new FakeClock();
        ThrottlingRateLimiter rl = new ThrottlingRateLimiter(10, clock::nano, clock::sleep); // 100ms interval

        rl.acquire();                  // prime, nextAllowed=100ms
        clock.advance(250_000_000L);   // 处理一个 chunk 耗时 250ms > 间隔
        rl.acquire();                  // 已超过下次放行点, 不该 sleep

        assertTrue(clock.sleeps.isEmpty(), "consumer slower than the rate limit must never be throttled");
    }

    @Test
    void higher_rate_means_shorter_interval() {
        FakeClock clock = new FakeClock();
        // 50 chunk/s -> 20ms 间隔
        ThrottlingRateLimiter rl = new ThrottlingRateLimiter(50, clock::nano, clock::sleep);
        rl.acquire();
        rl.acquire();
        assertEquals(20_000_000L, clock.sleeps.get(0), "1s / 50 = 20ms interval");
    }
}
