package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.gc.StoreGc;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.safety.DiskSpaceCheck;
import com.shinoyuki.betterbackup.safety.SnapshotFailureMarker;
import com.shinoyuki.betterbackup.schedule.SnapshotTrigger;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * 创建 snapshot 的核心流程 (DESIGN §3.5).
 *
 * <p>每次触发 (定时 / 命令 / 关服) 跑:
 * <ol>
 *   <li>drain CurrentSnapshotState.dirtyMap → 拿到本周期 BAS fire 过的所有条目</li>
 *   <li>主动读 level.dat 文件字节 hash 入 store (BAS 不接管 level.dat 路径)</li>
 *   <li>读上一份 manifest (snapshots/ 字典序最大的) 作为 base</li>
 *   <li>base 上 overlay drain 出来的 diff → new manifest</li>
 *   <li>atomic 写 snapshots/&lt;snapshotId&gt;.manifest</li>
 * </ol>
 *
 * <p>实现 {@link SnapshotTrigger} 接口给 IntervalScheduler / ManualScheduler /
 * 命令直接调.
 *
 * <p>线程安全: create() 用 synchronized 串行化, 防多个 trigger 同时跑导致 race
 * (例如定时跟命令同时触发). drain 内部已是 atomic, manifest 写盘 atomic rename.
 */
public final class SnapshotCreator implements SnapshotTrigger {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private static final DateTimeFormatter SNAPSHOT_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private final ChunkStore store;
    private final CurrentSnapshotState state;
    private final WorldPaths paths;
    private final HashFunction hashFunction;
    private final Path snapshotsDir;
    private final LongSupplier gameTimeSupplier;
    private final Set<Hash> writtenThisWindow;
    private final BetterBackupMetrics metrics;
    private final StoreGc gc;
    private final Path storeRoot;
    private final SnapshotFailureMarker failureMarker;

    public SnapshotCreator(ChunkStore store,
                           CurrentSnapshotState state,
                           WorldPaths paths,
                           HashFunction hashFunction,
                           Path storeRoot,
                           LongSupplier gameTimeSupplier,
                           Set<Hash> writtenThisWindow,
                           BetterBackupMetrics metrics) {
        this.store = store;
        this.state = state;
        this.paths = paths;
        this.hashFunction = hashFunction;
        this.snapshotsDir = storeRoot.resolve("snapshots");
        this.gameTimeSupplier = gameTimeSupplier;
        this.writtenThisWindow = writtenThisWindow;
        this.metrics = metrics;
        this.gc = new StoreGc(store, this.snapshotsDir);
        this.storeRoot = storeRoot;
        this.failureMarker = new SnapshotFailureMarker(storeRoot);
    }

    public Path snapshotsDir() {
        return snapshotsDir;
    }

    /** 给 /betterbackup status 读最近一次快照失败标记 (.incomplete). */
    public SnapshotFailureMarker failureMarker() {
        return failureMarker;
    }

    @Override
    public synchronized void create(String reason) {
        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] failed to create snapshots dir {}", snapshotsDir, e);
            recordFailure("snapshots dir creation failed: " + e.getMessage());
            return;
        }

        // 磁盘预检在 drainAndClear 之前: 预检不过时 dirty 状态保留, 留给下一次重试,
        // 否则 drain 已清空 dirtyMap, 这一窗口标过 dirty 的 chunk 会永远丢出快照.
        try {
            DiskSpaceCheck.require(storeRoot, DiskSpaceCheck.MIN_FREE_BYTES, "snapshot");
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] snapshot aborted by disk space precheck", e);
            recordFailure("disk space precheck failed: " + e.getMessage());
            return;
        }

        CurrentSnapshotState.Drained drained = state.drainAndClear();
        Hash levelDatHash = hashAndStoreLevelDat();

        Optional<SnapshotManifest> previous = findLatestManifest();
        SnapshotManifest newManifest = build(previous.orElse(null), drained, levelDatHash);

        Path target = snapshotsDir.resolve(newManifest.snapshotId() + ".manifest");
        try {
            newManifest.writeTo(target);
            metrics.recordSnapshotCreated();
            clearFailureMarker();
            int chunkCount = newManifest.chunks().values().stream().mapToInt(Map::size).sum();
            int entityCount = newManifest.entityChunks().values().stream().mapToInt(Map::size).sum();
            LOGGER.info(
                    "[BetterBackup] snapshot created: {} ({}) chunks={} entity={} savedData={} levelDat={}",
                    newManifest.snapshotId(), reason,
                    chunkCount, entityCount,
                    newManifest.savedData().size(),
                    newManifest.levelDat() != null);

            runIncrementalGc(newManifest);
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] snapshot write failed: {}", target, e);
            recordFailure("manifest write failed: " + e.getMessage());
        }
    }

    /**
     * 记录一次快照失败: counter + 落 .incomplete 标记让 /betterbackup status 可见.
     * 标记写盘自身再失败只 log, 不掩盖原始失败 (原始失败已由调用处 log).
     */
    private void recordFailure(String reason) {
        metrics.recordSnapshotFailed();
        try {
            failureMarker.write(System.currentTimeMillis(), reason);
        } catch (IOException markerError) {
            LOGGER.error("[BetterBackup] failed to write snapshot failure marker", markerError);
        }
    }

    /** 快照成功后清除上一次失败的 .incomplete 标记. 清除失败只 log, 不影响快照成功语义. */
    private void clearFailureMarker() {
        try {
            failureMarker.clear();
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] failed to clear snapshot failure marker", e);
        }
    }

    /**
     * snapshot 写盘成功后立即清"本周期 BackupWorker 写入但 manifest 未引用的中间版本 hash"
     * (DESIGN §2.6). 不接通会让 store 单调增长 (大服 ~24 GB/天).
     *
     * <p>scope = writtenThisWindow - referenced(manifest), 远小于全量 GC, 秒级完成.
     * 失败 log error 不抛 — snapshot 已经写盘, GC 是 polish, 下次 snapshot 会再清.
     */
    private void runIncrementalGc(SnapshotManifest manifest) {
        Set<Hash> referenced = new HashSet<>();
        manifest.chunks().values().forEach(m -> referenced.addAll(m.values()));
        manifest.entityChunks().values().forEach(m -> referenced.addAll(m.values()));
        referenced.addAll(manifest.savedData().values());
        if (manifest.levelDat() != null) {
            referenced.add(manifest.levelDat());
        }

        Set<Hash> windowSnapshot = new HashSet<>(writtenThisWindow);
        writtenThisWindow.clear();

        try {
            StoreGc.GcResult result = gc.gcIncremental(windowSnapshot, referenced);
            if (result.deleted() > 0) {
                LOGGER.info("[BetterBackup] incremental gc: deleted={} freed={}KiB",
                        result.deleted(), result.bytesFreed() / 1024);
                metrics.recordGcRun();
                metrics.recordGcDeleted(result.deleted());
                metrics.recordGcBytesFreed(result.bytesFreed());
            }
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] incremental gc failed (snapshot already written, store may grow until next snapshot)", e);
        }
    }

    private SnapshotManifest build(SnapshotManifest previous,
                                   CurrentSnapshotState.Drained drained,
                                   Hash levelDatHash) {
        Map<String, Map<Long, Hash>> chunks = deepCopyDimMap(previous != null ? previous.chunks() : Map.of());
        Map<String, Map<Long, Hash>> entityChunks =
                deepCopyDimMap(previous != null ? previous.entityChunks() : Map.of());
        Map<String, Hash> savedData = new HashMap<>(previous != null ? previous.savedData() : Map.of());

        for (Map.Entry<DimChunkKey, Hash> e : drained.chunks().entrySet()) {
            chunks.computeIfAbsent(e.getKey().dimensionId(), x -> new HashMap<>())
                    .put(e.getKey().packedPos(), e.getValue());
        }
        for (Map.Entry<DimChunkKey, Hash> e : drained.entityChunks().entrySet()) {
            entityChunks.computeIfAbsent(e.getKey().dimensionId(), x -> new HashMap<>())
                    .put(e.getKey().packedPos(), e.getValue());
        }
        savedData.putAll(drained.savedData());

        // levelDat: drained 里有 (BAS 不会 fire 但留 hook) 优先, 否则用主动读盘的, 否则继承上一份
        Hash level = drained.levelDat() != null ? drained.levelDat()
                : (levelDatHash != null ? levelDatHash
                : (previous != null ? previous.levelDat() : null));

        String snapshotId = SNAPSHOT_ID_FORMAT.format(Instant.now());

        return new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                snapshotId,
                System.currentTimeMillis(),
                gameTimeSupplier.getAsLong(),
                chunks,
                entityChunks,
                savedData,
                level,
                0L,  // totalUniqueBytes: Phase 5 metrics commit 接入
                0L); // deltaBytes: 同上
    }

    private Optional<SnapshotManifest> findLatestManifest() {
        if (!Files.isDirectory(snapshotsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(snapshotsDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(this::tryReadManifest)
                    .filter(Optional::isPresent)
                    .map(Optional::get);
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] failed to list snapshots dir {}", snapshotsDir, e);
            return Optional.empty();
        }
    }

    private Optional<SnapshotManifest> tryReadManifest(Path path) {
        try {
            return Optional.of(SnapshotManifest.readFrom(path));
        } catch (IOException e) {
            LOGGER.warn("[BetterBackup] failed to read manifest {}, ignoring", path, e);
            return Optional.empty();
        }
    }

    private Hash hashAndStoreLevelDat() {
        Path levelDat = paths.levelDat();
        if (!Files.exists(levelDat)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(levelDat);
            Hash hash = hashFunction.hash(bytes);
            store.put(hash, bytes);
            return hash;
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] failed to read level.dat for snapshot", e);
            return null;
        }
    }

    private static Map<String, Map<Long, Hash>> deepCopyDimMap(Map<String, Map<Long, Hash>> src) {
        Map<String, Map<Long, Hash>> result = new HashMap<>(src.size());
        for (Map.Entry<String, Map<Long, Hash>> e : src.entrySet()) {
            result.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return result;
    }
}
