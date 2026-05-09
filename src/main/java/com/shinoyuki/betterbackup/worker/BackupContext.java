package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;

import java.util.Set;

/**
 * 给 BackupTask 用的运行时上下文 (worker 不可变共享).
 *
 * <p>{@code writtenThisWindow}: 本 snapshot 周期内 BackupWorker 实际写入 store 的 hash
 * (store.put 返回 true 时 add). SnapshotCreator 在 create() 完成后用 writtenThisWindow
 * 跟 manifest 引用集做 diff 跑 StoreGc.gcIncremental, 立即清掉 "本周期写入但 manifest
 * 没引用的中间版本 hash" (DESIGN §2.6 增量 GC). 不接通 store 会单调增长.
 *
 * <p>{@code metrics}: BetterBackupMetrics 计数器 + gauge, BackupTask 在 store.put 后
 * 按 unique/deduped 区分 record.
 */
public record BackupContext(
        ChunkStore store,
        CurrentSnapshotState state,
        WorldPaths paths,
        HashFunction hashFunction,
        Set<Hash> writtenThisWindow,
        BetterBackupMetrics metrics) {
}
