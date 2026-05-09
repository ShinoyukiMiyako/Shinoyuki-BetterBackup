package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.store.Hash;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentSnapshotStateTest {

    private static Hash hash(int i) {
        byte[] b = new byte[16];
        b[0] = (byte) i;
        return new Hash(b);
    }

    @Test
    void empty_state_drains_to_empty_snapshot() {
        CurrentSnapshotState state = new CurrentSnapshotState();
        assertEquals(0, state.size());
        CurrentSnapshotState.Drained drained = state.drainAndClear();
        assertEquals(0, drained.totalSize());
        assertNull(drained.levelDat());
    }

    @Test
    void put_chunk_then_drain_returns_entries() {
        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putChunk("minecraft:overworld", 100L, hash(1));
        state.putChunk("minecraft:overworld", 101L, hash(2));
        state.putEntityChunk("minecraft:the_nether", 200L, hash(3));
        state.putSavedData("raids", hash(4));
        state.putLevelDat(hash(5));

        assertEquals(5, state.size());

        CurrentSnapshotState.Drained drained = state.drainAndClear();
        assertEquals(2, drained.chunks().size());
        assertEquals(1, drained.entityChunks().size());
        assertEquals(1, drained.savedData().size());
        assertNotNull(drained.levelDat());
        assertEquals(hash(5), drained.levelDat());
    }

    @Test
    void drain_clears_state() {
        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putChunk("minecraft:overworld", 100L, hash(1));
        state.putSavedData("raids", hash(4));
        state.putLevelDat(hash(5));

        state.drainAndClear();
        assertEquals(0, state.size());
    }

    @Test
    void put_same_key_overwrites_previous_value_keep_latest() {
        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putChunk("minecraft:overworld", 100L, hash(1));
        state.putChunk("minecraft:overworld", 100L, hash(99));
        assertEquals(1, state.chunkCount(), "覆盖语义, 同 key 仅一条");

        CurrentSnapshotState.Drained drained = state.drainAndClear();
        assertEquals(hash(99), drained.chunks().get(new DimChunkKey("minecraft:overworld", 100L)));
    }

    @Test
    void concurrent_put_and_drain_does_not_lose_entries() throws InterruptedException {
        CurrentSnapshotState state = new CurrentSnapshotState();
        final int threads = 4;
        final int writesPerThread = 1000;
        AtomicInteger drainedCount = new AtomicInteger();

        // start producers
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Thread[] producers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            int producerId = i;
            producers[i] = new Thread(() -> {
                try {
                    ready.await();
                    for (int j = 0; j < writesPerThread; j++) {
                        long key = (long) producerId * writesPerThread + j;
                        state.putChunk("minecraft:overworld", key, hash(producerId));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            producers[i].start();
        }

        // drainer thread
        Thread drainer = new Thread(() -> {
            try {
                ready.await();
                while (done.getCount() > 0 || state.size() > 0) {
                    CurrentSnapshotState.Drained d = state.drainAndClear();
                    drainedCount.addAndGet(d.chunks().size());
                    Thread.sleep(1);
                }
                // final drain
                CurrentSnapshotState.Drained d = state.drainAndClear();
                drainedCount.addAndGet(d.chunks().size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        drainer.start();

        ready.countDown();
        done.await();
        drainer.join();

        // 全部 unique keys (跨 producer 不重复) 必须被 drain 出来一次
        assertEquals(threads * writesPerThread, drainedCount.get(),
                "all writes must be drained exactly once");
        assertEquals(0, state.size(), "state must be empty after final drain");
    }

    @Test
    void level_dat_drain_is_atomic() {
        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putLevelDat(hash(1));
        CurrentSnapshotState.Drained d1 = state.drainAndClear();
        assertEquals(hash(1), d1.levelDat());
        // 二次 drain 应该 null (清空了)
        CurrentSnapshotState.Drained d2 = state.drainAndClear();
        assertNull(d2.levelDat());
    }

    @Test
    void counts_break_down_by_channel() {
        CurrentSnapshotState state = new CurrentSnapshotState();
        state.putChunk("minecraft:overworld", 1L, hash(1));
        state.putChunk("minecraft:overworld", 2L, hash(2));
        state.putEntityChunk("minecraft:overworld", 3L, hash(3));
        state.putSavedData("raids", hash(4));

        assertEquals(2, state.chunkCount());
        assertEquals(1, state.entityChunkCount());
        assertEquals(1, state.savedDataCount());
        assertTrue(state.size() == 4);
    }
}
