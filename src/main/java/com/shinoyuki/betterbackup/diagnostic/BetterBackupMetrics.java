package com.shinoyuki.betterbackup.diagnostic;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Phase 5: BetterBackup 流水线运行指标聚合点.
 *
 * <p>跟 BAS {@code SaveMetrics} 同款架构: counter 用 {@link LongAdder} (写多读少
 * 场景下 lock-free, 比 AtomicLong 减少 CAS 竞争), gauge 用 {@link AtomicLong}
 * (单值随时被覆盖, 不需要分槽累加).
 *
 * <p><b>三类计数轴</b>: chunk / entity / savedData 各 4 个 counter (received /
 * unique / deduped / failed), 反映 BAS 事件 -> BetterBackup pipeline -> 内容寻址
 * 存储这条链路上的 dedup 命中率与失败率.
 *
 * <p>{@code received} = listener bridge 上报数 (BAS 派发了多少); {@code unique} =
 * ChunkStore 实际新写盘的对象数; {@code deduped} = 命中已有 hash 跳过写盘的数.
 * 三者关系应有 received = unique + deduped + failed (含 in-flight 时短暂偏差).
 *
 * <p><b>v0.1 MVP 不做 histogram</b>: BAS v0.9 histogram 主要给 IOWorker / NBT
 * build 等热路径分布观测, BetterBackup 当前阶段重点是吞吐与去重命中率正确性,
 * 延迟分布优先级不高. 推到 v0.2 再加 (Histogram + bucket 上界 + percentile).
 *
 * <p><b>Snapshot 一致性</b>: {@link #snapshot()} 不加锁, 各字段独立读取, 因此
 * counter 跨字段可能存在 ns 级偏差 (例: {@code received=10001} 但
 * {@code unique+deduped+failed=10000}). Prometheus 抓取场景下可接受, 因为
 * 客户端会按时间序列做 rate 计算自然平滑.
 */
public final class BetterBackupMetrics {

    // chunk 路径 (4)
    private final LongAdder chunksReceived = new LongAdder();
    private final LongAdder chunksUnique = new LongAdder();
    private final LongAdder chunksDeduped = new LongAdder();
    private final LongAdder chunksFailed = new LongAdder();

    // entity 路径 (4)
    private final LongAdder entitiesReceived = new LongAdder();
    private final LongAdder entitiesUnique = new LongAdder();
    private final LongAdder entitiesDeduped = new LongAdder();
    private final LongAdder entitiesFailed = new LongAdder();

    // savedData 路径 (4)
    private final LongAdder savedDataReceived = new LongAdder();
    private final LongAdder savedDataUnique = new LongAdder();
    private final LongAdder savedDataDeduped = new LongAdder();
    private final LongAdder savedDataFailed = new LongAdder();

    // snapshot 生命周期 (2)
    private final LongAdder snapshotsCreated = new LongAdder();
    private final LongAdder snapshotsFailed = new LongAdder();

    // GC 累计 (3)
    private final LongAdder gcRunsTotal = new LongAdder();
    private final LongAdder gcDeletedTotal = new LongAdder();
    private final LongAdder gcBytesFreedTotal = new LongAdder();

    // gauge (5) — store* / snapshotCount 由 GC 末段刷新, queue/dirty 由 worker 实时刷新
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong dirtyMapSize = new AtomicLong();
    private final AtomicLong storeBytes = new AtomicLong();
    private final AtomicLong storeUniqueCount = new AtomicLong();
    private final AtomicLong snapshotCount = new AtomicLong();

    public void recordChunkReceived() {
        chunksReceived.increment();
    }

    public void recordChunkUnique() {
        chunksUnique.increment();
    }

    public void recordChunkDeduped() {
        chunksDeduped.increment();
    }

    public void recordChunkFailed() {
        chunksFailed.increment();
    }

    public void recordEntityReceived() {
        entitiesReceived.increment();
    }

    public void recordEntityUnique() {
        entitiesUnique.increment();
    }

    public void recordEntityDeduped() {
        entitiesDeduped.increment();
    }

    public void recordEntityFailed() {
        entitiesFailed.increment();
    }

    public void recordSavedDataReceived() {
        savedDataReceived.increment();
    }

    public void recordSavedDataUnique() {
        savedDataUnique.increment();
    }

    public void recordSavedDataDeduped() {
        savedDataDeduped.increment();
    }

    public void recordSavedDataFailed() {
        savedDataFailed.increment();
    }

    public void recordSnapshotCreated() {
        snapshotsCreated.increment();
    }

    public void recordSnapshotFailed() {
        snapshotsFailed.increment();
    }

    public void recordGcRun() {
        gcRunsTotal.increment();
    }

    public void recordGcDeleted(long n) {
        gcDeletedTotal.add(n);
    }

    public void recordGcBytesFreed(long bytes) {
        gcBytesFreedTotal.add(bytes);
    }

    public void setQueueDepth(long depth) {
        queueDepth.set(depth);
    }

    public void setDirtyMapSize(long size) {
        dirtyMapSize.set(size);
    }

    public void setStoreBytes(long bytes) {
        storeBytes.set(bytes);
    }

    public void setStoreUniqueCount(long count) {
        storeUniqueCount.set(count);
    }

    public void setSnapshotCount(long count) {
        snapshotCount.set(count);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                chunksReceived.sum(),
                chunksUnique.sum(),
                chunksDeduped.sum(),
                chunksFailed.sum(),
                entitiesReceived.sum(),
                entitiesUnique.sum(),
                entitiesDeduped.sum(),
                entitiesFailed.sum(),
                savedDataReceived.sum(),
                savedDataUnique.sum(),
                savedDataDeduped.sum(),
                savedDataFailed.sum(),
                snapshotsCreated.sum(),
                snapshotsFailed.sum(),
                gcRunsTotal.sum(),
                gcDeletedTotal.sum(),
                gcBytesFreedTotal.sum(),
                queueDepth.get(),
                dirtyMapSize.get(),
                storeBytes.get(),
                storeUniqueCount.get(),
                snapshotCount.get()
        );
    }

    public record Snapshot(
            long chunksReceived,
            long chunksUnique,
            long chunksDeduped,
            long chunksFailed,
            long entitiesReceived,
            long entitiesUnique,
            long entitiesDeduped,
            long entitiesFailed,
            long savedDataReceived,
            long savedDataUnique,
            long savedDataDeduped,
            long savedDataFailed,
            long snapshotsCreated,
            long snapshotsFailed,
            long gcRunsTotal,
            long gcDeletedTotal,
            long gcBytesFreedTotal,
            long queueDepth,
            long dirtyMapSize,
            long storeBytes,
            long storeUniqueCount,
            long snapshotCount
    ) {
    }
}
