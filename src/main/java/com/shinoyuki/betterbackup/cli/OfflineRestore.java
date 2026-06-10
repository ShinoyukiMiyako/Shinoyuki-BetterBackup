package com.shinoyuki.betterbackup.cli;

import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线 restore 引擎 (CLI 专用). 与 mod 侧 {@code RestoreFlow} 同一套重建逻辑, 但:
 * <ul>
 *   <li>用 {@link ChunkPosCodec} 拆 packed long, 不 import net.minecraft.world.level.ChunkPos</li>
 *   <li>诊断走传入的 {@link PrintStream}, 不依赖 SLF4J / BetterBackupMod</li>
 * </ul>
 * 因此本类整条调用链 (含 {@link ChunkStore} / {@link RegionFileSlotWriter} /
 * {@link WorldPaths} / {@link SnapshotManifest}) 零 net.minecraft / net.minecraftforge 依赖,
 * 满足 PLAN Phase E "服务端起不来时也能在裸 JRE 上恢复" 的核心目标。
 *
 * <p>重建顺序与原子边界跟 RestoreFlow 一致: 先把当前 world 的 chunk/玩家数据目录整体
 * atomic rename 到 {@code <worldRoot>.bak-<ts>/}, 再从 store 全新落盘。任一步抛 IOException
 * 整体 abort, .bak 保留给用户兜底。
 */
public final class OfflineRestore {

    private final ChunkStore store;
    private final WorldPaths paths;
    private final Path snapshotsDir;
    private final PrintStream out;

    public OfflineRestore(ChunkStore store, WorldPaths paths, Path snapshotsDir, PrintStream out) {
        this.store = store;
        this.paths = paths;
        this.snapshotsDir = snapshotsDir;
        this.out = out;
    }

    public RestoreResult restore(String snapshotId) throws IOException {
        Path manifestFile = snapshotsDir.resolve(snapshotId + ".manifest");
        if (!Files.exists(manifestFile)) {
            throw new IOException("manifest not found: " + manifestFile);
        }
        SnapshotManifest manifest = SnapshotManifest.readFrom(manifestFile);

        if (!manifest.baselineComplete()) {
            // 与 mod 侧 restore 门禁一致: baseline 未完成的快照 restore 出来会丢大量未加载
            // chunk, 直接拒绝而不是产出残缺世界。
            throw new IOException("snapshot " + snapshotId + " has baselineComplete=false; "
                    + "restoring it would lose chunks never captured by the baseline scan. Refusing.");
        }

        verifyStoreCompleteness(manifest);

        Path backupDir = moveCurrentWorldToBackup();

        long chunkSlots = rebuildChunkPath(manifest.chunks(), false);
        long entitySlots = rebuildChunkPath(manifest.entityChunks(), true);
        long savedDataFiles = rebuildSavedData(manifest.savedData());
        long playerDataFiles = rebuildFiles(manifest.files());
        boolean levelDatRebuilt = rebuildLevelDat(manifest.levelDat());

        out.printf("restore complete: snapshot=%s chunks=%d entity=%d savedData=%d files=%d levelDat=%b backup=%s%n",
                snapshotId, chunkSlots, entitySlots, savedDataFiles, playerDataFiles, levelDatRebuilt, backupDir);
        return new RestoreResult(snapshotId, chunkSlots, entitySlots, savedDataFiles, playerDataFiles,
                levelDatRebuilt, backupDir);
    }

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

    private Path moveCurrentWorldToBackup() throws IOException {
        Path worldRoot = paths.worldRoot();
        String backupName = worldRoot.getFileName() + ".bak-" + System.currentTimeMillis();
        Path backupDir = worldRoot.resolveSibling(backupName);
        Files.createDirectories(backupDir);

        moveIfExists(worldRoot.resolve("region"), backupDir.resolve("region"));
        moveIfExists(worldRoot.resolve("entities"), backupDir.resolve("entities"));
        moveIfExists(worldRoot.resolve("data"), backupDir.resolve("data"));
        moveIfExists(worldRoot.resolve("playerdata"), backupDir.resolve("playerdata"));
        moveIfExists(worldRoot.resolve("stats"), backupDir.resolve("stats"));
        moveIfExists(worldRoot.resolve("advancements"), backupDir.resolve("advancements"));
        moveIfExists(worldRoot.resolve("poi"), backupDir.resolve("poi"));
        moveIfExists(worldRoot.resolve("DIM-1"), backupDir.resolve("DIM-1"));
        moveIfExists(worldRoot.resolve("DIM1"), backupDir.resolve("DIM1"));
        moveIfExists(worldRoot.resolve("dimensions"), backupDir.resolve("dimensions"));
        moveIfExists(worldRoot.resolve("level.dat"), backupDir.resolve("level.dat"));
        moveIfExists(worldRoot.resolve("level.dat_old"), backupDir.resolve("level.dat_old"));
        return backupDir;
    }

    private static void moveIfExists(Path src, Path dst) throws IOException {
        if (Files.exists(src)) {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private long rebuildChunkPath(Map<String, Map<Long, Hash>> dimMap, boolean entityPath) throws IOException {
        long total = 0;
        for (Map.Entry<String, Map<Long, Hash>> dimEntry : dimMap.entrySet()) {
            String dim = dimEntry.getKey();
            Path regionDir = entityPath ? paths.entitiesDir(dim) : paths.regionDir(dim);
            Files.createDirectories(regionDir);

            Map<Long, Map<Integer, Hash>> byRegion = new HashMap<>();
            for (Map.Entry<Long, Hash> chunk : dimEntry.getValue().entrySet()) {
                long packed = chunk.getKey();
                int chunkX = ChunkPosCodec.getX(packed);
                int chunkZ = ChunkPosCodec.getZ(packed);
                int rx = chunkX >> 5;
                int rz = chunkZ >> 5;
                int localX = chunkX & 31;
                int localZ = chunkZ & 31;
                int slotIndex = localX + localZ * 32;
                long regionKey = ChunkPosCodec.asLong(rx, rz);
                byRegion.computeIfAbsent(regionKey, k -> new HashMap<>()).put(slotIndex, chunk.getValue());
            }

            for (Map.Entry<Long, Map<Integer, Hash>> regionEntry : byRegion.entrySet()) {
                long regionKey = regionEntry.getKey();
                int rx = ChunkPosCodec.getX(regionKey);
                int rz = ChunkPosCodec.getZ(regionKey);
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

    private long rebuildSavedData(Map<String, Hash> savedData) throws IOException {
        if (savedData.isEmpty()) {
            return 0;
        }
        Path dataDir = paths.dataDir("minecraft:overworld");
        Files.createDirectories(dataDir);
        long total = 0;
        for (Map.Entry<String, Hash> entry : savedData.entrySet()) {
            byte[] bytes = store.get(entry.getValue());
            Path target = dataDir.resolve(entry.getKey() + ".dat");
            Files.write(target, bytes);
            total++;
        }
        return total;
    }

    private long rebuildFiles(FileManifest files) throws IOException {
        long total = 0;
        for (Map.Entry<String, Hash> entry : files.hashes().entrySet()) {
            String relativePath = entry.getKey();
            Path target = paths.worldRoot().resolve(relativePath);
            byte[] bytes = store.get(entry.getValue());
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            if (files.suspect().contains(relativePath)) {
                out.println("WARN restored suspect player-data file (bytes may be inconsistent, "
                        + "captured during a torn read): " + relativePath);
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
