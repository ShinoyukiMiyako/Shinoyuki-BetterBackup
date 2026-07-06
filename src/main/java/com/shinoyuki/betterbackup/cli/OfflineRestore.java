package com.shinoyuki.betterbackup.cli;

import com.shinoyuki.betterbackup.io.ChunkPayloadCodec;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
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

        Path backupDir = new com.shinoyuki.betterbackup.restore.WorldBackupMover().moveToBackup(paths.worldRoot());

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

    /**
     * 部分恢复 (PLAN v0.2 主线二): 只把指定维度的若干 chunk 回滚到快照态, <b>只动地形</b>,
     * 不碰 playerdata / level.dat / SavedData, 也不碰其余 chunk 与其余维度。
     *
     * <p><b>原子边界 (与全量恢复的本质区别)</b>: 不把世界整体移进 {@code .bak}。对每个受影响
     * region 文件, 先读出其现有非目标 slot 保留, 用快照字节覆盖目标 slot, 再整 region <b>原子重写</b>
     * (tmp + fsync + rename)。失败发生在 rename 前则活动 region 原封不动; rename 是提交点。其余
     * region / 维度 / 玩家数据 一概不触碰。<b>保真粒度</b>: 非目标 chunk 的 payload (压缩字节, 含
     * external .mcc) 逐字节保留; 但整 region 重写会把 timestamp 表重置为 0 —— vanilla 仅用 timestamp
     * 做 dirty 检测, 不影响 chunk 的存在性/可读性, 故不构成数据丢失。
     *
     * <p><b>损坏邻居容错</b>: 离线 CLI 正是世界已出问题时的兜底, 读非目标 slot 用 {@link
     * RegionFileSlotReader#readSlotLenient} (跳过 inflate 校验, 损坏字节原样保留); 个别非目标 slot
     * 结构性损坏 / external .mcc 缺失则跳过该 slot 并告警, 不阻断对健康目标 chunk 的回退。
     *
     * <p><b>baseline 门禁</b>: 部分恢复不要求 baselineComplete —— 只取已采集的那个 chunk, 即使整库
     * baseline 未跑完也能精确回滚。目标 chunk 未采集时: {@code failOnUncaptured=true} (显式单 chunk)
     * 报错, {@code false} (区域回退) 跳过该 chunk 并计入 skipped (区域里几乎必然有未加载 chunk,
     * 全量中止会让区域回退在 baseline 未完成时不可用)。
     *
     * @param snapshotId       快照 id
     * @param dimId            维度 id (如 {@code "minecraft:overworld"})
     * @param targetChunks     目标 chunk 的 packed long 集合 ({@link ChunkPosCodec#asLong})
     * @param failOnUncaptured true = 任一目标未采集即报错 (单 chunk); false = 跳过未采集 (区域)
     */
    public PartialResult restorePartial(String snapshotId, String dimId, Set<Long> targetChunks,
                                        boolean failOnUncaptured) throws IOException {
        if (targetChunks.isEmpty()) {
            throw new IOException("no target chunks given for partial restore");
        }
        Path manifestFile = snapshotsDir.resolve(snapshotId + ".manifest");
        if (!Files.exists(manifestFile)) {
            throw new IOException("manifest not found: " + manifestFile);
        }
        SnapshotManifest manifest = SnapshotManifest.readFrom(manifestFile);
        Map<Long, Hash> dimChunks = manifest.chunks().getOrDefault(dimId, Map.of());

        // 解析每个目标 chunk 在快照里的 hash; 未采集按 failOnUncaptured 报错或跳过; store 缺对象
        // 一律报错 (那是 store 损坏, 不是"未采集")。
        Map<Long, Hash> targets = new HashMap<>();
        long skipped = 0;
        for (long packed : targetChunks) {
            Hash h = dimChunks.get(packed);
            if (h == null) {
                if (failOnUncaptured) {
                    throw new IOException("chunk (" + ChunkPosCodec.getX(packed) + "," + ChunkPosCodec.getZ(packed)
                            + ") was not captured in snapshot " + snapshotId + " for dimension " + dimId);
                }
                skipped++;
                continue;
            }
            if (!store.has(h)) {
                throw new IOException("store incomplete: object for chunk (" + ChunkPosCodec.getX(packed) + ","
                        + ChunkPosCodec.getZ(packed) + ") missing, hash=" + h.toHex());
            }
            targets.put(packed, h);
        }

        // 按 region 文件分组目标 slot
        Path regionDir = paths.regionDir(dimId);
        Map<Long, Map<Integer, Long>> byRegion = new HashMap<>(); // regionKey -> (slotIndex -> packedPos)
        for (long packed : targets.keySet()) {
            int chunkX = ChunkPosCodec.getX(packed);
            int chunkZ = ChunkPosCodec.getZ(packed);
            int slotIndex = (chunkX & 31) + (chunkZ & 31) * 32;
            long regionKey = ChunkPosCodec.asLong(chunkX >> 5, chunkZ >> 5);
            byRegion.computeIfAbsent(regionKey, k -> new HashMap<>()).put(slotIndex, packed);
        }

        long restored = 0;
        for (Map.Entry<Long, Map<Integer, Long>> regionEntry : byRegion.entrySet()) {
            int rx = ChunkPosCodec.getX(regionEntry.getKey());
            int rz = ChunkPosCodec.getZ(regionEntry.getKey());
            Path mcaFile = regionDir.resolve("r." + rx + "." + rz + ".mca");
            restored += rewriteRegionReplacingSlots(mcaFile, regionEntry.getValue(), targets);
        }

        out.printf("partial restore complete: snapshot=%s dim=%s restored=%d skipped-uncaptured=%d "
                        + "(terrain only; playerdata/level.dat and other chunks untouched)%n",
                snapshotId, dimId, restored, skipped);
        return new PartialResult(snapshotId, dimId, restored, skipped);
    }

    /**
     * 整 region 原子重写: 读出现有非目标 slot 保留, 用快照字节覆盖 {@code targetSlots} 指定的 slot
     * (该 chunk 在快照里若不存在于活动 region 则为新增), 然后整文件原子写回。非目标 slot 走宽容读 +
     * 逐 slot 容错: 个别损坏 / external .mcc 缺失的非目标 slot 跳过保留 (留空, vanilla 重生) 并告警,
     * 不阻断目标回退。
     *
     * @return 实际覆盖/写入的目标 slot 数
     */
    private long rewriteRegionReplacingSlots(Path mcaFile, Map<Integer, Long> targetSlots, Map<Long, Hash> targets)
            throws IOException {
        Files.createDirectories(mcaFile.getParent());

        // 1. 读出现有非目标 slot 保留 (宽容读: 损坏字节原样透传, 不就地恶化; 目标 slot 跳过不读,
        //    其旧值即将被快照字节覆盖, 且活动目标可能本就损坏)
        Map<Integer, byte[]> slots = new HashMap<>();
        if (Files.exists(mcaFile)) {
            for (int slotIndex = 0; slotIndex < 1024; slotIndex++) {
                if (targetSlots.containsKey(slotIndex)) {
                    continue;
                }
                byte[] existing;
                try {
                    existing = RegionFileSlotReader.readSlotLenient(mcaFile, slotIndex & 31, (slotIndex >> 5) & 31);
                } catch (IOException e) {
                    // 非目标邻居 chunk 结构性损坏 / external .mcc 物理缺失: 跳过该 slot (留空, vanilla
                    // 重生), 不阻断对健康目标 chunk 的回退。告警让用户知情。
                    out.println("WARN skipping unreadable neighbour chunk slot (" + (slotIndex & 31) + ","
                            + ((slotIndex >> 5) & 31) + ") in " + mcaFile.getFileName() + ": " + e.getMessage());
                    continue;
                }
                if (existing != null) {
                    slots.put(slotIndex, existing);
                }
            }
        }

        // 2. 用快照字节覆盖目标 slot
        for (Map.Entry<Integer, Long> slot : targetSlots.entrySet()) {
            slots.put(slot.getKey(), store.get(targets.get(slot.getValue())));
        }

        // 3. 整 region 原子重写 (writer close = tmp + fsync + atomic rename), slot 升序保证布局确定
        List<Integer> sortedSlots = slots.keySet().stream().sorted().toList();
        try (RegionFileSlotWriter writer = RegionFileSlotWriter.open(mcaFile)) {
            for (int slotIndex : sortedSlots) {
                writer.writeChunk(slotIndex & 31, (slotIndex >> 5) & 31, slots.get(slotIndex));
            }
        }

        // 4. 目标 chunk 快照版若是 inline 而活动版曾是 external, 清掉过期 .mcc (writer 不会删旧外置文件)
        for (Map.Entry<Integer, Long> slot : targetSlots.entrySet()) {
            int slotIndex = slot.getKey();
            if (!ChunkPayloadCodec.isExternal(slots.get(slotIndex)[0])) {
                Files.deleteIfExists(RegionFileSlotReader.mccPathFor(mcaFile, slotIndex & 31, (slotIndex >> 5) & 31));
            }
        }
        return targetSlots.size();
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
     * savedData key 落盘目标。新版 key 是含维度子目录的 worldRoot 相对路径 (含 "/") -> 直接
     * resolve; 旧版裸 SavedData 名 (无 "/") -> 退回 overworld {@code data/<name>.dat}。与
     * {@link com.shinoyuki.betterbackup.restore.RestoreFlow} 口径一致。
     */
    private Path resolveSavedDataTarget(String key) {
        if (key.indexOf('/') >= 0) {
            return paths.worldRoot().resolve(key);
        }
        return paths.dataDir("minecraft:overworld").resolve(key + ".dat");
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

    /** 部分恢复结果. {@code skippedUncaptured} = 区域回退里跳过的未采集 chunk 数. */
    public record PartialResult(String snapshotId, String dimId, long chunksRestored, long skippedUncaptured) {
    }
}
