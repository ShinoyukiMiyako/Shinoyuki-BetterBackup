package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.io.WorldPaths;
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
import java.util.Map;
import java.util.Optional;
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

    public SnapshotCreator(ChunkStore store,
                           CurrentSnapshotState state,
                           WorldPaths paths,
                           HashFunction hashFunction,
                           Path storeRoot,
                           LongSupplier gameTimeSupplier) {
        this.store = store;
        this.state = state;
        this.paths = paths;
        this.hashFunction = hashFunction;
        this.snapshotsDir = storeRoot.resolve("snapshots");
        this.gameTimeSupplier = gameTimeSupplier;
    }

    public Path snapshotsDir() {
        return snapshotsDir;
    }

    @Override
    public synchronized void create(String reason) {
        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] failed to create snapshots dir {}", snapshotsDir, e);
            return;
        }

        CurrentSnapshotState.Drained drained = state.drainAndClear();
        Hash levelDatHash = hashAndStoreLevelDat();

        Optional<SnapshotManifest> previous = findLatestManifest();
        SnapshotManifest newManifest = build(previous.orElse(null), drained, levelDatHash);

        Path target = snapshotsDir.resolve(newManifest.snapshotId() + ".manifest");
        try {
            newManifest.writeTo(target);
            LOGGER.info(
                    "[BetterBackup] snapshot created: {} ({}) chunks={} entity={} savedData={} levelDat={}",
                    newManifest.snapshotId(), reason,
                    newManifest.chunks().values().stream().mapToInt(Map::size).sum(),
                    newManifest.entityChunks().values().stream().mapToInt(Map::size).sum(),
                    newManifest.savedData().size(),
                    newManifest.levelDat() != null);
        } catch (IOException e) {
            LOGGER.error("[BetterBackup] snapshot write failed: {}", target, e);
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
