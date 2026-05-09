package com.shinoyuki.betterbackup.worker;

import java.io.IOException;

/**
 * BackupWorker queue 里的工作单元. 三类实现: ChunkBackupTask / EntityChunkBackupTask /
 * SavedDataBackupTask, 跟 BAS 三类 listener channel 对应.
 *
 * <p>execute 阶段: 读盘 raw bytes → hash → store.put → state.putXxx. 抛
 * IOException 时由 BackupWorker catch + 计 failure metric (Phase 5 接入), 不
 * 传播到 BAS listener.
 */
public interface BackupTask {

    /** 短描述, 给诊断 log 用. */
    String taskName();

    void execute(BackupContext ctx) throws IOException;
}
