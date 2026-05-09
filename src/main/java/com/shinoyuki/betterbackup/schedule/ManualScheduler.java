package com.shinoyuki.betterbackup.schedule;

import com.shinoyuki.betterbackup.BetterBackupMod;
import org.slf4j.Logger;

/**
 * No-op scheduler. 仅命令触发 SnapshotCreator. start / stop 仅 log.
 */
public final class ManualScheduler implements SnapshotScheduler {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    @Override
    public void start(SnapshotTrigger trigger) {
        LOGGER.info("[BetterBackup] manual scheduler: snapshots only via /betterbackup snapshot create");
    }

    @Override
    public void stop() {
        // no-op
    }
}
