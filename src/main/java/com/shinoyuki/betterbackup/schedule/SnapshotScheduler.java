package com.shinoyuki.betterbackup.schedule;

/**
 * 调度器抽象. 三种 mode (DESIGN §7):
 * <ul>
 *   <li>INTERVAL: 固定 minutes 周期触发, 用 {@link IntervalScheduler}</li>
 *   <li>AFTER_AUTOSAVE: vanilla autosave 后累计变化超阈值才创建, 推迟到 v0.2</li>
 *   <li>MANUAL: 仅命令触发, 用 {@link ManualScheduler} (no-op scheduler, 命令直接调 trigger)</li>
 * </ul>
 *
 * <p>cron-utils 库支持 cron 表达式触发, 是 nice-to-have, MVP 不引入. 服主有特殊
 * 调度需求可用 system cron 调 {@code /betterbackup snapshot create}.
 */
public interface SnapshotScheduler {

    void start(SnapshotTrigger trigger);

    void stop();
}
