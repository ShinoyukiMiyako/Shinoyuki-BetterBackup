package com.shinoyuki.betterbackup.integration;

import com.shinoyuki.betterbackup.baseline.BaselineProgress;
import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.schedule.SnapshotScheduler;
import com.shinoyuki.betterbackup.schedule.SnapshotTrigger;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.DegradedSession;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PipelineDegradedHandler.onDegraded 的三个副作用 (PLAN Phase F):
 * 暂停 scheduler + 标 creator degraded + 持久化 degraded-session 标志。
 * 判定标准: 漏掉任一副作用, 对应断言必挂。
 */
class PipelineDegradedHandlerTest {

    /** 记录 stop 是否被调用的 scheduler 桩. start 是 no-op. */
    private static final class RecordingScheduler implements SnapshotScheduler {
        boolean stopped;

        @Override
        public void start(SnapshotTrigger trigger) {
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    private SnapshotCreator newCreator(Path storeRoot, Path worldRoot) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        WorldPaths paths = new WorldPaths(worldRoot);
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        return new SnapshotCreator(store, state(), paths, new Xxh128HashFunction(), storeRoot,
                () -> 0L, written, new BetterBackupMetrics(), new BaselineProgress(storeRoot), () -> false);
    }

    private CurrentSnapshotState state() {
        return new CurrentSnapshotState();
    }

    @Test
    void on_degraded_pauses_scheduler_marks_creator_and_persists_flag(@TempDir Path base)
            throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        SnapshotCreator creator = newCreator(storeRoot, worldRoot);
        RecordingScheduler scheduler = new RecordingScheduler();
        DegradedSession session = new DegradedSession(storeRoot);

        assertFalse(creator.isDegraded());
        assertFalse(scheduler.stopped);
        assertFalse(session.exists());

        PipelineDegradedHandler handler = new PipelineDegradedHandler(creator, scheduler, session);
        handler.onDegraded();

        assertTrue(creator.isDegraded(), "onDegraded 必须置 creator 降级闩锁");
        assertTrue(scheduler.stopped, "onDegraded 必须暂停 (stop) scheduler");
        assertTrue(session.exists(), "onDegraded 必须持久化 degraded-session 标志");
        assertTrue(session.markedAtMillis().isPresent(), "标志内含降级时刻");
    }

    @Test
    void on_degraded_is_idempotent(@TempDir Path base) throws IOException {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);

        SnapshotCreator creator = newCreator(storeRoot, worldRoot);
        RecordingScheduler scheduler = new RecordingScheduler();
        DegradedSession session = new DegradedSession(storeRoot);
        PipelineDegradedHandler handler = new PipelineDegradedHandler(creator, scheduler, session);

        handler.onDegraded();
        handler.onDegraded();

        assertTrue(creator.isDegraded());
        assertTrue(scheduler.stopped);
        assertTrue(session.exists());
    }
}
