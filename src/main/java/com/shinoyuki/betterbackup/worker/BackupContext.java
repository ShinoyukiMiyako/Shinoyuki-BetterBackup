package com.shinoyuki.betterbackup.worker;

import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.HashFunction;

/**
 * 给 BackupTask 用的运行时上下文 (worker 不可变共享).
 */
public record BackupContext(
        ChunkStore store,
        CurrentSnapshotState state,
        WorldPaths paths,
        HashFunction hashFunction) {
}
