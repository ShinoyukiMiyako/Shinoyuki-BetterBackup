package com.shinoyuki.betterbackup.baseline;

/**
 * 按固定速率 (chunk slot / 秒) 节流 baseline 扫描的限速器. 每读一个 chunk slot 调
 * 一次 {@link #acquire()}, 内部维持最小间隔 sleep 把吞吐压到 chunksPerSecond.
 *
 * <p><b>为什么不用令牌桶突发</b>: baseline 的目标是平滑占用磁盘 IO 不打满, 突发反而
 * 违背初衷. 这里用最简单的"距上次放行不足 1/rate 秒就 sleep 补足"恒速策略, 单线程
 * 调用无并发竞争.
 *
 * <p><b>速率每次现读</b>: 速率来自 {@code IntSupplier}, 每次 acquire 现读一次, 因此改
 * config 触发 Forge 的 reload 后下一个 slot 就按新速率走, 不必重启. 速率调小时同时把
 * 已排定的放行时刻收紧到新间隔内, 否则上一次按旧 (更长) 间隔算出的等待还会多拖一轮.
 *
 * <p>时钟源用 {@code nanoTimeSupplier} + {@code sleeper} 注入, 让测试用假时钟断言
 * 调用了正确次数的 sleep 而不真睡, 跑测试不引入墙钟等待.
 */
public final class ThrottlingRateLimiter implements BaselineScanner.RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final java.util.function.IntSupplier chunksPerSecondSupplier;
    private final int fallbackChunksPerSecond;
    private final java.util.function.LongSupplier nanoTimeSupplier;
    private final Sleeper sleeper;
    private long nextAllowedNanos;
    private boolean primed;

    public ThrottlingRateLimiter(int chunksPerSecond) {
        this(chunksPerSecond, System::nanoTime, ThrottlingRateLimiter::sleepNanos);
    }

    public ThrottlingRateLimiter(java.util.function.IntSupplier chunksPerSecondSupplier) {
        this(chunksPerSecondSupplier, System::nanoTime, ThrottlingRateLimiter::sleepNanos);
    }

    ThrottlingRateLimiter(int chunksPerSecond, java.util.function.LongSupplier nanoTimeSupplier, Sleeper sleeper) {
        this(() -> chunksPerSecond, nanoTimeSupplier, sleeper);
    }

    ThrottlingRateLimiter(java.util.function.IntSupplier chunksPerSecondSupplier,
                          java.util.function.LongSupplier nanoTimeSupplier,
                          Sleeper sleeper) {
        // 构造期严格校验一次: 配置没加载 (值为 0) 这类接线错误必须在拉起扫描线程时就响亮
        // 失败, 而不是退化成 1 chunk/s 静默扫上十几天.
        int initial = chunksPerSecondSupplier.getAsInt();
        if (initial < 1) {
            throw new IllegalArgumentException("chunksPerSecond must be >= 1, got " + initial);
        }
        this.chunksPerSecondSupplier = chunksPerSecondSupplier;
        this.fallbackChunksPerSecond = initial;
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.sleeper = sleeper;
    }

    @Override
    public void acquire() {
        long minIntervalNanos = NANOS_PER_SECOND / currentChunksPerSecond();
        long now = nanoTimeSupplier.getAsLong();
        if (!primed) {
            primed = true;
            nextAllowedNanos = now + minIntervalNanos;
            return;
        }
        // 速率被调高 (间隔变小) 时, 上一轮按旧间隔排定的放行时刻要收紧, 新速率才立即生效.
        if (nextAllowedNanos - now > minIntervalNanos) {
            nextAllowedNanos = now + minIntervalNanos;
        }
        long waitNanos = nextAllowedNanos - now;
        if (waitNanos > 0) {
            sleeper.sleep(waitNanos);
            now = nextAllowedNanos;
        }
        nextAllowedNanos = now + minIntervalNanos;
    }

    /** 现读速率. 读到非法值 (只可能是接线出错, config 侧有 spec 范围兜底) 时退回构造期已校验的值. */
    private int currentChunksPerSecond() {
        int rate = chunksPerSecondSupplier.getAsInt();
        return rate < 1 ? fallbackChunksPerSecond : rate;
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
