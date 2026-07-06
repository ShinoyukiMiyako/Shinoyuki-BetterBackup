package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.safety.DiskSpaceCheck;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全量 restore 实际执行者. 由 BetterBackupMod onServerAboutToStart 在检测到
 * PendingRestoreFlag 时调用. vanilla 此时还没 load world, 安全直接覆盖文件.
 *
 * <p>流程 (DESIGN §4.1):
 * <ol>
 *   <li>读 manifest</li>
 *   <li>verify store 完整性 (referenced hash 全在 store)</li>
 *   <li>atomic rename world 内 chunk-related 子目录 + 玩家数据目录 + level.dat 到
 *       {@code <worldRoot>.bak-<ts>/}</li>
 *   <li>按 manifest group by (dim, region) 用 RegionFileSlotWriter 重建 .mca 文件</li>
 *   <li>重建 SavedData .dat 文件 (从 store.get(hash) 写)</li>
 *   <li>重建 files 段 (playerdata/ stats/ advancements/ poi/ 整文件, 从 store.get(hash) 写)</li>
 *   <li>重建 level.dat</li>
 * </ol>
 *
 * <p><b>原子边界</b>: region 与 files (玩家数据) 在同一边界内 -- 一起进 .bak, 一起回写。
 * 回滚后玩家背包 (playerdata) 与世界 (region) 必定同时回到同一快照态, 杜绝
 * FTB Backups 2 #95 的刷物品/丢物品事故 (只回 region 不回 playerdata 时玩家手里还留着
 * 回滚点之后才拿到的物品)。suspect 文件 (采集时撕裂读未稳定) 回装时 WARN 提示。
 *
 * <p><b>失败处理</b>: 任何 step 抛 IOException 整体 abort, world.bak 仍保留给用户
 * 做兜底 (用户可手动从 .bak 复原). flag 不删, 用户看 log 修复后重启会再跑一次.
 */
public final class RestoreFlow {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private final ChunkStore store;
    private final WorldPaths paths;
    private final Path snapshotsDir;

    public RestoreFlow(ChunkStore store, WorldPaths paths, Path snapshotsDir) {
        this.store = store;
        this.paths = paths;
        this.snapshotsDir = snapshotsDir;
    }

    /** 执行 restore. 调用前 PendingRestoreFlag 应已存在; 成功后调用方应清 flag. */
    public RestoreResult restore(String snapshotId) throws IOException {
        Path manifestFile = snapshotsDir.resolve(snapshotId + ".manifest");
        if (!Files.exists(manifestFile)) {
            throw new IOException("manifest not found: " + manifestFile);
        }
        SnapshotManifest manifest = SnapshotManifest.readFrom(manifestFile);

        verifyStoreCompleteness(manifest);

        // 磁盘预检在移动旧世界之前: restore 把整套快照引用的 store 字节回写进 world,
        // 盘满会产出半覆盖的世界. 预检不过直接抛, 旧世界原封不动 (Advanced Backups #128).
        DiskSpaceCheck.require(paths.worldRoot(), DiskSpaceCheck.MIN_FREE_BYTES, "restore");

        Path backupDir = new WorldBackupMover().moveToBackup(paths.worldRoot());

        long chunkSlots = rebuildChunkPath(manifest.chunks(), false);
        long entitySlots = rebuildChunkPath(manifest.entityChunks(), true);
        long savedDataFiles = rebuildSavedData(manifest.savedData());
        long playerDataFiles = rebuildFiles(manifest.files());
        boolean levelDatRebuilt = rebuildLevelDat(manifest.levelDat());

        LOGGER.info("[BetterBackup] restore complete: snapshot={} chunks={} entity={} savedData={} files={} levelDat={} backup={}",
                snapshotId, chunkSlots, entitySlots, savedDataFiles, playerDataFiles, levelDatRebuilt, backupDir);
        return new RestoreResult(snapshotId, chunkSlots, entitySlots, savedDataFiles, playerDataFiles,
                levelDatRebuilt, backupDir);
    }

    /** verify referenced hash 全在 store; 缺失抛 IOException, 不动文件. */
    private void verifyStoreCompleteness(SnapshotManifest manifest) throws IOException {
        Set<Hash> referenced = new HashSet<>();
        manifest.chunks().values().forEach(m -> referenced.addAll(m.values()));
        manifest.entityChunks().values().forEach(m -> referenced.addAll(m.values()));
        referenced.addAll(manifest.savedData().values());
        referenced.addAll(manifest.files().hashes().values());
        if (manifest.levelDat() != null) {
            referenced.add(manifest.levelDat());
        }
        List<Hash> missing = referenced.stream().filter(h -> !store.has(h)).toList();
        if (!missing.isEmpty()) {
            throw new IOException("store incomplete: " + missing.size() + " hash(es) missing, first="
                    + missing.get(0).toHex());
        }
    }

    /**
     * 重建 chunk 路径 .mca 文件. group by (dim, region rx/rz) 一组开一个
     * RegionFileSlotWriter session.
     *
     * @param entityPath false → region/, true → entities/
     */
    private long rebuildChunkPath(Map<String, Map<Long, Hash>> dimMap, boolean entityPath) throws IOException {
        long total = 0;
        for (Map.Entry<String, Map<Long, Hash>> dimEntry : dimMap.entrySet()) {
            String dim = dimEntry.getKey();
            Path regionDir = entityPath ? paths.entitiesDir(dim) : paths.regionDir(dim);
            Files.createDirectories(regionDir);

            // group by region: regionKey -> { localSlotIndex -> hash }
            Map<Long, Map<Integer, Hash>> byRegion = new HashMap<>();
            for (Map.Entry<Long, Hash> chunk : dimEntry.getValue().entrySet()) {
                long packed = chunk.getKey();
                int chunkX = ChunkPos.getX(packed);
                int chunkZ = ChunkPos.getZ(packed);
                int rx = chunkX >> 5;
                int rz = chunkZ >> 5;
                int localX = chunkX & 31;
                int localZ = chunkZ & 31;
                int slotIndex = localX + localZ * 32;
                long regionKey = ChunkPos.asLong(rx, rz);
                byRegion.computeIfAbsent(regionKey, k -> new HashMap<>()).put(slotIndex, chunk.getValue());
            }

            for (Map.Entry<Long, Map<Integer, Hash>> regionEntry : byRegion.entrySet()) {
                long regionKey = regionEntry.getKey();
                int rx = ChunkPos.getX(regionKey);
                int rz = ChunkPos.getZ(regionKey);
                Path mcaFile = regionDir.resolve("r." + rx + "." + rz + ".mca");
                List<Map.Entry<Integer, Hash>> sortedSlots = regionEntry.getValue().entrySet().stream()
                        .sorted(Comparator.comparingInt(Map.Entry::getKey))
                        .toList();
                try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mcaFile)) {
                    for (Map.Entry<Integer, Hash> slot : sortedSlots) {
                        int slotIndex = slot.getKey();
                        int localX = slotIndex & 31;
                        int localZ = (slotIndex >> 5) & 31;
                        byte[] rawBytes = store.get(slot.getValue());
                        writer.writeChunk(localX, localZ, rawBytes);
                    }
                }
                total += sortedSlots.size();
            }
        }
        return total;
    }

    /**
     * 重建 SavedData .dat 文件. key 是相对 worldRoot 的路径 (含维度子目录), 落回原维度 data/。
     * 兼容旧 manifest 的裸 SavedData 名 (无 "/"): 当年未记维度, 退回 overworld data/<name>.dat。
     */
    private long rebuildSavedData(Map<String, Hash> savedData) throws IOException {
        long total = 0;
        for (Map.Entry<String, Hash> entry : savedData.entrySet()) {
            byte[] bytes = store.get(entry.getValue());
            Path target = resolveSavedDataTarget(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            total++;
        }
        return total;
    }

    /**
     * 把 manifest 里的 savedData key 解析成落盘目标。新版 key 是含维度子目录的 worldRoot 相对
     * 路径 (必含 "/", 如 {@code DIM1/data/raids_end.dat}) -> 直接 resolve; 旧版是裸 SavedData 名
     * (无 "/") -> 退回 overworld {@code data/<name>.dat} (老快照未记维度, 尽力而为)。
     */
    private Path resolveSavedDataTarget(String key) {
        if (key.indexOf('/') >= 0) {
            return paths.worldRoot().resolve(key);
        }
        return paths.dataDir("minecraft:overworld").resolve(key + ".dat");
    }

    /**
     * 重建 files 段 (玩家数据通道). 相对路径相对 worldRoot, 一律 forward-slash, 拆回子路径
     * 落到 worldRoot 下原位 (manifest 写时已用 RegionFileSlotWriter 同款 atomic-ish 直写;
     * 此处旧文件已整目录进 .bak, 这里是从空目录全新落盘, 不存在覆盖半写问题)。
     *
     * <p>suspect 文件 (采集时撕裂读 3 次仍不稳定): 仍照常回写其入库字节 (有总比没有强),
     * 但 WARN 提示该文件字节可能不一致, 让管理员知情 -- 不静默回装疑似坏数据。
     */
    private long rebuildFiles(com.shinoyuki.betterbackup.snapshot.FileManifest files) throws IOException {
        long total = 0;
        for (Map.Entry<String, Hash> entry : files.hashes().entrySet()) {
            String relativePath = entry.getKey();
            Path target = paths.worldRoot().resolve(relativePath);
            byte[] bytes = store.get(entry.getValue());
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            if (files.suspect().contains(relativePath)) {
                LOGGER.warn("[BetterBackup] restored suspect player-data file (bytes may be inconsistent, "
                        + "captured during a torn read): {}", relativePath);
            }
            total++;
        }
        return total;
    }

    private boolean rebuildLevelDat(Hash levelDatHash) throws IOException {
        if (levelDatHash == null) {
            return false;
        }
        byte[] bytes = store.get(levelDatHash);
        Path target = paths.levelDat();
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return true;
    }

    public record RestoreResult(
            String snapshotId,
            long chunkSlotsRestored,
            long entitySlotsRestored,
            long savedDataFilesRestored,
            long playerDataFilesRestored,
            boolean levelDatRestored,
            Path worldBackupDir) {
    }
}
