package com.shinoyuki.betterbackup;

/**
 * store 后台初始化与服务器生命周期之间的握手状态机.
 *
 * <p>store.initialize() 在大 store / 崩溃恢复时是 O(pack 集) 的重扫, 不能在
 * ServerStartingEvent 主线程同步跑 (issue #3: 超 max-tick-time 被 ServerHangWatchdog
 * 强杀, 且中途被杀写不出索引 checkpoint, 下次启动又全量重扫, 形成崩溃循环). 初始化甩到
 * 后台线程后, "初始化完成 -&gt; 启动 worker/scheduler (arm)" 与 "服务器关停 -&gt; 拆线"
 * 之间存在竞态: arm 绝不能发生在关停开始之后, 否则 worker/scheduler 被启动在已拆线的
 * 管线上, 线程泄漏且可能在 uninstall 之后触发快照. 本类用单锁把三个转换串行化:
 * arm 与关停拆线互斥, 且 stop 之后的 arm/fail 必然被拒绝.
 *
 * <p>线程模型: {@link #completeInit}/{@link #failInit} 由 BetterBackup-Store-Init
 * 线程调用, {@link #beginStop} 由 server 线程 (ServerStoppingEvent) 调用. 动作在锁内
 * 执行, 关停会等待进行中的 arm 完成后再继续拆线.
 *
 * <p>动作抛异常时状态停留在 INITIALIZING: isReady 恒 false, 关停路径按 "未就绪" 拆线
 * (对已部分启动的组件, requestStop/join/stop 均幂等安全).
 */
public final class StoreInitCoordinator {

    public enum State {
        INITIALIZING,
        READY,
        FAILED,
        STOPPED
    }

    private final Object lock = new Object();
    private State state = State.INITIALIZING;

    /**
     * 初始化成功: 仍处 INITIALIZING 则在锁内执行 {@code arm} 并转 READY, 返回 true;
     * 关停已抢先则不执行 arm, 返回 false (调用方只需安静收尾).
     */
    public boolean completeInit(Runnable arm) {
        synchronized (lock) {
            if (state != State.INITIALIZING) {
                return false;
            }
            arm.run();
            state = State.READY;
            return true;
        }
    }

    /**
     * 初始化失败: 仍处 INITIALIZING 则在锁内执行 {@code teardown} 并转 FAILED, 返回 true;
     * 关停已抢先则返回 false, 拆线由关停路径完成 (二者互斥, 不会重复拆).
     */
    public boolean failInit(Runnable teardown) {
        synchronized (lock) {
            if (state != State.INITIALIZING) {
                return false;
            }
            teardown.run();
            state = State.FAILED;
            return true;
        }
    }

    /**
     * 关停: 无条件转 STOPPED, 返回转换前状态. 返回后 completeInit/failInit 必然拒绝,
     * arm 不可能再发生; 调用方据返回值决定走完整拆线 (READY) 还是未就绪拆线.
     */
    public State beginStop() {
        synchronized (lock) {
            State prev = state;
            state = State.STOPPED;
            return prev;
        }
    }

    public boolean isReady() {
        synchronized (lock) {
            return state == State.READY;
        }
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }
}
