package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;

import java.util.Set;
import java.util.concurrent.BlockingQueue;

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
 *
 * <p>{@code retryQueue}: BackupWorker 消费的同一个 task 队列. chunk/entity task 读盘
 * 命中撕裂读 ({@code TornReadException}) 时, 把自己以 attempt+1 重新 offer 回队尾,
 * 延后到 vanilla IOWorker 完成原地重写后再读. 这就是 "重新标 dirty 延后重试" 的落地:
 * 队列即 dirty 通道 (listener bridge 也是往这个队列 offer). 不静默丢弃, 也不入库垃圾.
 */
public record BackupContext(
        ChunkStore store,
        CurrentSnapshotState state,
        WorldPaths paths,
        HashFunction hashFunction,
        Set<Hash> writtenThisWindow,
        BetterBackupMetrics metrics,
        BlockingQueue<BackupTask> retryQueue) {
}
