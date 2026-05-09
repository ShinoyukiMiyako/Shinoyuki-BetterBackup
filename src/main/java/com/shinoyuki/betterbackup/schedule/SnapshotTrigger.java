package com.shinoyuki.betterbackup.schedule;

/**
 * 由调度器调用的 callback, 真正干活的是后续 commit 的 SnapshotCreator.
 * Phase 2 commit 14 仅定义 interface, commit 15 接入 SnapshotCreator.
 */
@FunctionalInterface
public interface SnapshotTrigger {

    /**
     * @param reason 触发原因, 用于诊断 log (例: "interval", "manual:command", "shutdown")
     */
    void create(String reason);
}
