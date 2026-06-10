package com.shinoyuki.betterbackup.baseline;

/**
 * 按固定速率 (chunk slot / 秒) 节流 baseline 扫描的限速器. 每读一个 chunk slot 调
 * 一次 {@link #acquire()}, 内部维持最小间隔 sleep 把吞吐压到 chunksPerSecond.
 *
 * <p><b>为什么不用令牌桶突发</b>: baseline 的目标是平滑占用磁盘 IO 不打满, 突发反而
 * 违背初衷. 这里用最简单的"距上次放行不足 1/rate 秒就 sleep 补足"恒速策略, 单线程
 * 调用无并发竞争.
 *
 * <p>时钟源用 {@code nanoTimeSupplier} + {@code sleeper} 注入, 让测试用假时钟断言
 * 调用了正确次数的 sleep 而不真睡, 跑测试不引入墙钟等待.
 */
public final class ThrottlingRateLimiter implements BaselineScanner.RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long minIntervalNanos;
    private final java.util.function.LongSupplier nanoTimeSupplier;
    private final Sleeper sleeper;
    private long nextAllowedNanos;
    private boolean primed;

    public ThrottlingRateLimiter(int chunksPerSecond) {
        this(chunksPerSecond, System::nanoTime, ThrottlingRateLimiter::sleepNanos);
    }

    ThrottlingRateLimiter(int chunksPerSecond, java.util.function.LongSupplier nanoTimeSupplier, Sleeper sleeper) {
        if (chunksPerSecond < 1) {
            throw new IllegalArgumentException("chunksPerSecond must be >= 1, got " + chunksPerSecond);
        }
        this.minIntervalNanos = NANOS_PER_SECOND / chunksPerSecond;
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.sleeper = sleeper;
    }

    @Override
    public void acquire() {
        long now = nanoTimeSupplier.getAsLong();
        if (!primed) {
            primed = true;
            nextAllowedNanos = now + minIntervalNanos;
            return;
        }
        long waitNanos = nextAllowedNanos - now;
        if (waitNanos > 0) {
            sleeper.sleep(waitNanos);
            now = nextAllowedNanos;
        }
        nextAllowedNanos = now + minIntervalNanos;
    }

    private static void sleepNanos(long nanos) {
        long millis = nanos / 1_000_000L;
        int nanoRemainder = (int) (nanos % 1_000_000L);
        try {
            Thread.sleep(millis, nanoRemainder);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** sleep 抽象, 让测试注入假实现统计调用而不真睡. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos);
    }
}
