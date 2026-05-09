package com.shinoyuki.betterbackup.diagnostic;

import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics.Snapshot;

/**
 * Phase 5: 把 {@link BetterBackupMetrics.Snapshot} 转成 Prometheus exposition
 * format (https://prometheus.io/docs/instrumenting/exposition_formats/), 给
 * {@link PrometheusExporter} HTTP /metrics 端点直接返回字符串.
 *
 * <p>所有 metric 用 {@code bbb_} 前缀 (BetterBackup), 跟 BAS {@code bas_} 区分,
 * 避免两个 mod 共享同一 Prometheus 实例 / 同一 scrape 端点时命名冲突.
 *
 * <p><b>v0.1 MVP 不做 histogram</b>: {@link BetterBackupMetrics} 当前只有
 * counter + gauge, formatter 也对应只有这两种 helper. v0.2 引入 latency
 * 分布观测后再补 histogram (跟 BAS 同款累加 + le 标签 + +Inf overflow 语义).
 */
public final class PrometheusFormatter {

    private PrometheusFormatter() {
    }

    public static String format(Snapshot snap) {
        StringBuilder sb = new StringBuilder(4096);

        // chunk 路径 counter
        counter(sb, "bbb_chunks_received_total",
                "Chunks received from BAS ChunkSaveListener bridge", snap.chunksReceived());
        counter(sb, "bbb_chunks_unique_total",
                "Chunks newly written to ChunkStore (cache miss)", snap.chunksUnique());
        counter(sb, "bbb_chunks_deduped_total",
                "Chunks deduplicated against existing hash (cache hit)", snap.chunksDeduped());
        counter(sb, "bbb_chunks_failed_total",
                "Chunks that failed backup task (hash / write / serialization error)",
                snap.chunksFailed());

        // entity 路径 counter
        counter(sb, "bbb_entities_received_total",
                "Entity chunk sections received from BAS bridge", snap.entitiesReceived());
        counter(sb, "bbb_entities_unique_total",
                "Entity chunk sections newly written", snap.entitiesUnique());
        counter(sb, "bbb_entities_deduped_total",
                "Entity chunk sections deduplicated", snap.entitiesDeduped());
        counter(sb, "bbb_entities_failed_total",
                "Entity chunk section backup failures", snap.entitiesFailed());

        // savedData 路径 counter
        counter(sb, "bbb_saved_data_received_total",
                "SavedData blobs received from BAS bridge", snap.savedDataReceived());
        counter(sb, "bbb_saved_data_unique_total",
                "SavedData blobs newly written", snap.savedDataUnique());
        counter(sb, "bbb_saved_data_deduped_total",
                "SavedData blobs deduplicated", snap.savedDataDeduped());
        counter(sb, "bbb_saved_data_failed_total",
                "SavedData backup failures", snap.savedDataFailed());

        // snapshot 生命周期 counter
        counter(sb, "bbb_snapshots_created_total",
                "Snapshot manifests successfully sealed", snap.snapshotsCreated());
        counter(sb, "bbb_snapshots_failed_total",
                "Snapshot creation failures (manifest write / scheduler error)",
                snap.snapshotsFailed());

        // GC 累计 counter
        counter(sb, "bbb_gc_runs_total",
                "Store GC runs completed", snap.gcRunsTotal());
        counter(sb, "bbb_gc_deleted_total",
                "Total objects deleted by GC across all runs", snap.gcDeletedTotal());
        counter(sb, "bbb_gc_bytes_freed_total",
                "Total bytes freed by GC across all runs", snap.gcBytesFreedTotal());

        // gauge
        gauge(sb, "bbb_queue_depth",
                "BackupWorker pending task queue depth", snap.queueDepth());
        gauge(sb, "bbb_dirty_map_size",
                "Dirty map size (chunks awaiting next snapshot seal)", snap.dirtyMapSize());
        gauge(sb, "bbb_store_bytes",
                "ChunkStore on-disk byte size as of last GC scan", snap.storeBytes());
        gauge(sb, "bbb_store_unique_count",
                "ChunkStore unique object count as of last GC scan", snap.storeUniqueCount());
        gauge(sb, "bbb_snapshot_count",
                "Number of sealed snapshot manifests retained", snap.snapshotCount());

        return sb.toString();
    }

    private static void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }
}
