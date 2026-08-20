package com.shinoyuki.betterbackup.baseline;

import com.shinoyuki.betterbackup.BetterBackupMod;
import org.slf4j.Logger;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 按 dirty 登记水位背压 baseline 取样的闸门. 水位涨到 {@code highWaterMark} 就阻塞扫描
 * 线程, 回落到一半才放行.
 *
 * <p><b>为什么阻塞而不是停扫</b>: 扫描线程是 daemon, 阻塞期间不持任何锁、不占任何已读
 * 字节, 进度按 region 文件持久化, 停在那里的代价是零. 反过来, 若做成"等太久就永久停扫"
 * 的闸刀, 一旦误判就没有任何自愈路径 —— baseline 扫不完则 complete 标记写不下, 全量恢复
 * 门禁永久关闭. 阻塞语义下服主手动跑一次 {@code /betterbackup snapshot create} 就能 drain
 * 掉水位让扫描自己恢复.
 *
 * <p><b>为什么轮询而不是 park/notify</b>: 唯一能让水位下降的动作是
 * {@code CurrentSnapshotState.drainAndClear}, 它是三段 forEach 加一次 getAndSet 的非原子
 * 操作, 且写入侧完全不受快照的 synchronized 保护. 任何"判断水位然后通知"都存在丢通知窗口,
 * 丢一次就是扫描线程永久挂起、关服 join 超时. 定时轮询把最坏唤醒延迟钉成一个常数, 不依赖
 * 任何跨线程通知的正确性; 代价是每 200ms 三次 {@code ConcurrentHashMap.size()} (与元素数
 * 无关).
 *
 * <p><b>滞回</b>: 水位是弱一致估计值, 触顶即拦、回落到一半才放, 避免在阈值附近每个 slot
 * 都抖进阻塞循环.
 *
 * <p>时钟与等待经构造器注入, 让测试用假时钟断言等待轮次而不真睡.
 */
public final class DirtyWaterMarkGate implements BaselineScanner.BackpressureGate {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    /** 阻塞期间的水位复查间隔. 同时是关服请求的最坏响应延迟. */
    static final long POLL_INTERVAL_MS = 200L;

    /** 放行水位 = 阈值 x 本比例. */
    static final double RESUME_RATIO = 0.5d;

    /** 阻塞期间重复告警的最小间隔, 防止长时间等待刷屏. */
    static final long WARN_INTERVAL_MS = 5L * 60 * 1000;

    private final IntSupplier dirtyLevel;
    private final IntSupplier highWaterMark;
    private final LongSupplier nanoTime;
    private final Waiter waiter;

    /** 只由扫描线程写, 诊断线程读. */
    private volatile boolean blocked;
    private volatile long finishedBlockedMillis;
    private volatile long blockedSinceNanos;

    private long lastWarnNanos;
    private boolean warnedOnce;

    public DirtyWaterMarkGate(IntSupplier dirtyLevel, IntSupplier highWaterMark) {
        this(dirtyLevel, highWaterMark, System::nanoTime, DirtyWaterMarkGate::sleepMillis);
    }

    DirtyWaterMarkGate(IntSupplier dirtyLevel, IntSupplier highWaterMark,
                       LongSupplier nanoTime, Waiter waiter) {
        this.dirtyLevel = dirtyLevel;
        this.highWaterMark = highWaterMark;
        this.nanoTime = nanoTime;
        this.waiter = waiter;
    }

    /** 扫描当前是否被闸住 (诊断用). */
    public boolean isBlocked() {
        return blocked;
    }

    /**
     * 累计被闸住的毫秒数 (诊断用). 含正在进行中的那一段 —— 长时间阻塞正是这个读数唯一有用
     * 的时刻, 只统计已结束的段会让心跳日志在整段阻塞期间打出 "blocked / 0s".
     */
    public long blockedMillisTotal() {
        long total = finishedBlockedMillis;
        if (blocked) {
            total += (nanoTime.getAsLong() - blockedSinceNanos) / 1_000_000L;
        }
        return total;
    }

    @Override
    public void awaitRoom(BooleanSupplier abort) {
        if (dirtyLevel.getAsInt() < currentHighWaterMark()) {
            return;
        }

        long startNanos = nanoTime.getAsLong();
        blockedSinceNanos = startNanos;
        blocked = true;
        try {
            maybeWarn(startNanos, dirtyLevel.getAsInt(), currentHighWaterMark());
            while (true) {
                if (abort.getAsBoolean()) {
                    return;
                }
                int high = currentHighWaterMark();
                int level = dirtyLevel.getAsInt();
                if (level <= (int) (high * RESUME_RATIO)) {
                    LOGGER.info("[BetterBackup] baseline sampling resumed: dirty={} (resume mark {} of {})",
                            level, (int) (high * RESUME_RATIO), high);
                    return;
                }
                maybeWarn(nanoTime.getAsLong(), level, high);
                if (!waiter.await(POLL_INTERVAL_MS)) {
                    // 被中断: 只把中断位还给调用线程供上层察觉, 不再等下去. 扫描线程的停机
                    // 语义走 requestStop 而非中断, 不会据此退出.
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            finishedBlockedMillis += (nanoTime.getAsLong() - startNanos) / 1_000_000L;
            blocked = false;
        }
    }

    private int currentHighWaterMark() {
        return Math.max(1, highWaterMark.getAsInt());
    }

    private void maybeWarn(long now, int level, int high) {
        if (warnedOnce && now - lastWarnNanos < WARN_INTERVAL_MS * 1_000_000L) {
            return;
        }
        warnedOnce = true;
        lastWarnNanos = now;
        LOGGER.warn("[BetterBackup] baseline sampling paused: dirty={} reached baseline.dirtyHighWaterMark={}; "
                        + "waiting for the next snapshot to drain it (resumes at {}). If this persists, check "
                        + "schedule.mode / schedule.intervalMinutes, check /betterbackup status for repeated "
                        + "snapshot failures (a failed snapshot puts its entries back), or run "
                        + "/betterbackup snapshot create once.",
                level, high, (int) (high * RESUME_RATIO));
    }

    private static boolean sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** 等待抽象, 让测试注入假实现统计轮次而不真睡. 返回 false 表示等待被中断. */
    @FunctionalInterface
    interface Waiter {
        boolean await(long millis);
    }
}
