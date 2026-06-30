package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.baseline.BaselineProgress;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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

    /**
     * pack 阈值压实触发: 累计死对象 (本窗口写入但未被任何 manifest 引用的中间版本) 达此值,
     * 下次快照后触发一次阈值压实物理回收死字节. (Step 8 接 config storage.compactTriggerObjects)
     */
    private static final long COMPACT_TRIGGER_DEAD_OBJECTS = 50_000L;

    /** 阈值压实: pack 死字节占比超此值才重打包 (有界, 不全量重写). (Step 8 接 config) */
    private static final double COMPACT_DEAD_RATIO_THRESHOLD = 0.5;

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
    private final BooleanSupplier baselineCompleteSupplier;
    private final PlayerDataCollector playerDataCollector;

    /**
     * baseline 进度记录. 快照成功写盘后, 把"drain 之前已 scanned"的 region 晋升为
     * committed (登记已随本份 manifest 落地), 并在扫描收尾且全部 committed 时写 complete
     * 标记. 这是消灭"扫完登记丢 / store 孤儿被 GC 删 / restore 凭空缺块"P0 窗口的提交侧.
     */
    private final BaselineProgress baselineProgress;

    /**
     * baseline 扫描线程是否已遍历完所有 pass (收尾). scanner 跑完所有 pass 后置位,
     * 然后请求一次 "baseline-complete" 快照. 只有 scanFinished=true 且全部 region 已
     * committed 时才写 complete 标记 — 防止扫描半途误开 restore 门禁.
     */
    private final BooleanSupplier baselineScanFinished;

    /**
     * BAS 降级单向闩锁. PipelineStateListener 收到 onDegraded 后 {@link #markDegraded()}
     * 置位, 此后本 creator 产出的每份快照都盖 incomplete 戳 (BAS 已停 fire, 活跃 dirty
     * 路径失明, 该快照可能漏采降级窗口内变更的 chunk). 跟 BAS 侧 degraded 同为单向,
     * 恢复语义 = 重启 + 下次启动重扫补采.
     */
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    /**
     * 自上次压实以来累计的死对象数 (中间版本). 只在 synchronized create() 内读写, 无并发.
     * 达 {@link #COMPACT_TRIGGER_DEAD_OBJECTS} 触发一次阈值压实并清零.
     */
    private long deadObjectsSinceCompaction;

    public SnapshotCreator(ChunkStore store,
                           CurrentSnapshotState state,
                           WorldPaths paths,
                           HashFunction hashFunction,
                           Path storeRoot,
                           LongSupplier gameTimeSupplier,
                           Set<Hash> writtenThisWindow,
                           BetterBackupMetrics metrics,
                           BaselineProgress baselineProgress,
                           BooleanSupplier baselineScanFinished) {
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
        this.baselineProgress = baselineProgress;
        // manifest 的 baselineComplete 戳直接反映 complete 标记当下状态: scanner 收尾后
        // 本 creator 写下标记, 此后产出的快照 (含定时 / 关服) 才盖 true. 早期快照盖 false,
        // restore 门禁据此拒绝, 让服主等扫描跑完再取一份新快照恢复.
        this.baselineCompleteSupplier = baselineProgress::isComplete;
        this.baselineScanFinished = baselineScanFinished;
        this.playerDataCollector = new PlayerDataCollector(store, paths, hashFunction, writtenThisWindow);
    }

    public Path snapshotsDir() {
        return snapshotsDir;
    }

    /** 给 /betterbackup status 读最近一次快照失败标记 (.incomplete). */
    public SnapshotFailureMarker failureMarker() {
        return failureMarker;
    }

    /**
     * 置降级闩锁. PipelineStateListener 收到 BAS onDegraded 后调用一次. 此后本 creator
     * 产出的快照 manifest.incomplete=true. 单向: 无复位, 恢复靠重启.
     */
    public void markDegraded() {
        degraded.set(true);
    }

    /** 本 creator 是否已收到降级信号. 测试 / 诊断用. */
    public boolean isDegraded() {
        return degraded.get();
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

        // 读上一份 manifest 作 overlay base —— 也必须在 drainAndClear 之前: 若选中的最新 manifest
        // 存在但损坏, 退空基线会把这份"最新快照"写成只含本窗口 drain 的近空 manifest, 丢掉历史累积
        // 的全部未变 chunk 引用; 故损坏时中止并保留 dirty 留给下次重试 (与磁盘预检同样的"留给下次"
        // 语义)。仅当目录里确无任何 manifest (首次) 才以空基线继续。
        SnapshotManifest previous;
        try {
            previous = loadPreviousBaseline().orElse(null);
        } catch (CorruptBaselineException e) {
            LOGGER.error("[BetterBackup] snapshot aborted: latest manifest unreadable; refusing to overwrite "
                    + "it with an empty-baseline snapshot (dirty state preserved for retry)", e);
            recordFailure("latest manifest unreadable: " + e.getMessage());
            return;
        }

        // baseline 晋升时序: 必须在 drainAndClear 之前捕获当前 scanned 集合. 这些 region
        // 的 chunk 登记此刻已在 state 里, 即将被本次 drain 纳入本份 manifest; manifest 写盘
        // 成功后才把这个捕获集晋升为 committed. drain 之后扫描线程再扫完的 region 其登记
        // 没进这份 manifest, 留给下一次快照晋升 (overlay 语义保证下份继承本份引用).
        Set<String> scannedBeforeDrain = baselineProgress.snapshotScannedKeys();

        CurrentSnapshotState.Drained drained = state.drainAndClear();
        Hash levelDatHash = hashAndStoreLevelDat();

        // 玩家数据通道与 chunk 同代采集: 失败则整份快照不写盘 (跟磁盘预检同样的"留给下次"语义),
        // 保证 manifest 里 files 段与 chunk 段始终来自同一时刻, 不出现半套快照.
        FileManifest files;
        try {
            files = playerDataCollector.collect();
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] snapshot aborted: player data collection failed", e);
            recordFailure("player data collection failed: " + e.getMessage());
            return;
        }

        SnapshotManifest newManifest = build(previous, drained, levelDatHash, files);

        Path target = snapshotsDir.resolve(newManifest.snapshotId() + ".manifest");
        try {
            // fsync 提交屏障: 把本窗口写入 pack 的所有对象一次性落盘, 再写 manifest.
            // 保证"manifest 落盘即其引用对象落盘"的不变量, 同时把每对象 fsync 压成每快照 1 次
            // (pack 改造的机械盘核心收益: 让磁盘电梯合并写回而非每对象一次带屏障寻道).
            store.flushAndSync();
            newManifest.writeTo(target);
            metrics.recordSnapshotCreated();
            clearFailureMarker();
            int chunkCount = newManifest.chunks().values().stream().mapToInt(Map::size).sum();
            int entityCount = newManifest.entityChunks().values().stream().mapToInt(Map::size).sum();
            LOGGER.info(
                    "[BetterBackup] snapshot created: {} ({}) chunks={} entity={} savedData={} files={} suspect={} levelDat={} baselineComplete={} incomplete={}",
                    newManifest.snapshotId(), reason,
                    chunkCount, entityCount,
                    newManifest.savedData().size(),
                    newManifest.files().hashes().size(),
                    newManifest.files().suspect().size(),
                    newManifest.levelDat() != null,
                    newManifest.baselineComplete(),
                    newManifest.incomplete());

            // manifest atomic rename 已完成 (writeTo 内部 tmp + fsync + rename), 这些
            // region 的 chunk 登记现在确实在盘上的 manifest 里. 把 drain 前捕获的 scanned
            // 集晋升为 committed, 续传从此跳过它们. 晋升后若扫描已收尾且全部 committed,
            // 写 complete 标记放行 restore 门禁. 晋升 / 标记持久化失败只 log 不抛: manifest
            // 已落地数据不丢, 失败的 region 下次快照按 scanned 重扫一遍重新晋升 (幂等).
            promoteBaselineAfterWrite(scannedBeforeDrain);

            runIncrementalGc(newManifest);
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] snapshot write failed: {}", target, e);
            recordFailure("manifest write failed: " + e.getMessage());
        }
    }

    /**
     * 快照写盘成功后晋升 baseline 进度: 把 drain 前捕获的 scanned region 集晋升为
     * committed, 再在扫描收尾且全部 committed 时写 complete 标记. 持久化失败只 log:
     * manifest 已落地, 这些 region 的 chunk 不丢; 失败的 region 下次快照按 scanned 重扫
     * 重新晋升 (markRegionScanned / promote 均幂等). 不抛, 不掩盖原始快照成功语义.
     */
    private void promoteBaselineAfterWrite(Set<String> scannedBeforeDrain) {
        try {
            baselineProgress.promoteScannedToCommitted(scannedBeforeDrain);
            if (baselineProgress.markCompleteIfAllCommitted(baselineScanFinished.getAsBoolean())) {
                LOGGER.info("[BetterBackup] baseline complete marker written: all regions committed");
            }
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] baseline progress promotion failed (snapshot already written, "
                    + "regions will be rescanned and re-promoted next snapshot)", e);
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
     * snapshot 写盘成功后的 pack 死字节回收 (延迟压实模型). pack append-only 删不掉单对象,
     * 故本周期写入但 manifest 未引用的中间版本不再立即删, 沦为 pack 死字节, 这里累计其数量,
     * 攒够 {@link #COMPACT_TRIGGER_DEAD_OBJECTS} 触发一次有界的阈值压实物理回收.
     *
     * <p>失败 log error 不抛 — snapshot 已写盘, 压实是 polish, 下次累计到阈值会再触发.
     *
     * <p><b>并发安全 (沿用 GC 误删 pending 写入的修复推理)</b>: baseline 扫描线程与本
     * create() 并发跑, drain 之后仍在往 writtenThisWindow.add + state.put 登记 chunk. 这些
     * chunk 没进本份 manifest 却已在 writtenThisWindow 里 — 必须排除"仍登记在 state 里"的
     * pending, 否则会把它们计入死对象 (其实下份 manifest 还要引用). pending 在 windowSnapshot
     * 之后读: 写入点 state.put 先于 writtenThisWindow.add (happens-before), 故 windowSnapshot
     * 里任一 hash 此刻若未被 drain 取走则必仍在 state 中, 必被 pending 捕获排除. 压实本身另用
     * state.pendingHashes() 作 protect 集二次兜底, 且 PackStore.compact 跳过在写 pack
     * (在途对象就在在写 pack 里), 三重保险绝不回收在途对象.
     */
    private void runIncrementalGc(SnapshotManifest manifest) {
        Set<Hash> referenced = new HashSet<>();
        manifest.chunks().values().forEach(m -> referenced.addAll(m.values()));
        manifest.entityChunks().values().forEach(m -> referenced.addAll(m.values()));
        referenced.addAll(manifest.savedData().values());
        referenced.addAll(manifest.files().hashes().values());
        if (manifest.levelDat() != null) {
            referenced.add(manifest.levelDat());
        }

        Set<Hash> windowSnapshot = new HashSet<>(writtenThisWindow);
        // pending 必须在 windowSnapshot 之后读 (见类注释 happens-before).
        Set<Hash> pending = state.pendingHashes();
        windowSnapshot.removeAll(pending);
        // 移除本次纳入考量的 windowSnapshot, 保留 drain 之后并发新增的 hash 给下一窗口.
        writtenThisWindow.removeAll(windowSnapshot);

        // 死对象 = 本窗口写入 - 在途 - 本份引用 = 中间版本. 累计, 攒够阈值触发压实.
        Set<Hash> deadThisWindow = new HashSet<>(windowSnapshot);
        deadThisWindow.removeAll(referenced);
        deadObjectsSinceCompaction += deadThisWindow.size();
        if (deadObjectsSinceCompaction < COMPACT_TRIGGER_DEAD_OBJECTS) {
            return;
        }

        try {
            StoreGc.GcResult result = gc.compactAfterSnapshot(COMPACT_DEAD_RATIO_THRESHOLD, state.pendingHashes());
            deadObjectsSinceCompaction = 0;
            if (result.deleted() > 0) {
                LOGGER.info("[BetterBackup] pack compaction: reclaimed={} freed={}KiB",
                        result.deleted(), result.bytesFreed() / 1024);
                metrics.recordGcRun();
                metrics.recordGcDeleted(result.deleted());
                metrics.recordGcBytesFreed(result.bytesFreed());
            }
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] pack compaction failed (snapshot already written, store may grow until next compaction)", e);
        }
    }

    private SnapshotManifest build(SnapshotManifest previous,
                                   CurrentSnapshotState.Drained drained,
                                   Hash levelDatHash,
                                   FileManifest files) {
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
                0L,  // deltaBytes: 同上
                baselineCompleteSupplier.getAsBoolean(),
                files,
                degraded.get());
    }

    /**
     * 读取作为 overlay base 的上一份 manifest (snapshots/ 字典序最大的). 必须区分两种语义:
     * <ul>
     *   <li>目录里确无任何 .manifest (首次) → {@link Optional#empty()}, 调用方以空基线起算合法;</li>
     *   <li>选中的最新 manifest 存在但读失败 (IOException 截断/坏压缩, 或 fromNbt 对坏 schema
     *       version 抛 IllegalStateException、dimMapFromNbt 强转抛 ClassCastException 等 RuntimeException)
     *       → 抛 {@link CorruptBaselineException}, 调用方据此中止本次快照, 绝不能退空基线把 latest
     *       覆盖成近空 manifest。</li>
     * </ul>
     * 与 {@link StoreGc} 对损坏 manifest 一律硬失败 abort 的纪律一致, 不再静默 fail-open。
     */
    private Optional<SnapshotManifest> loadPreviousBaseline() throws CorruptBaselineException {
        if (!Files.isDirectory(snapshotsDir)) {
            return Optional.empty();
        }
        Optional<Path> latest;
        try (Stream<Path> files = Files.list(snapshotsDir)) {
            latest = files
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            // 列目录失败 != 没有 manifest: 不能 fail-open 退空基线 (可能漏掉真实 latest 并覆盖它)。
            throw new CorruptBaselineException("failed to list snapshots dir " + snapshotsDir, e);
        }
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SnapshotManifest.readFrom(latest.get()));
        } catch (IOException | RuntimeException e) {
            throw new CorruptBaselineException("latest manifest unreadable: " + latest.get(), e);
        }
    }

    /** 选中的最新 manifest 存在却读不出 (损坏). create() 据此中止本次快照并保留 dirty。 */
    private static final class CorruptBaselineException extends Exception {
        CorruptBaselineException(String message, Throwable cause) {
            super(message, cause);
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
