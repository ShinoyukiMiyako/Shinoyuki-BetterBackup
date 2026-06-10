package com.shinoyuki.betterbackup.baseline;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.TornReadException;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * BAS 降级窗口补采重扫 (PLAN Phase F). 上一次运行 BAS 降级期间, listener 停 fire,
 * 活跃 dirty 路径失明, 该窗口内 vanilla 同步写盘的 chunk 没进 CurrentSnapshotState,
 * 也就没进快照. 本类在下次启动检测到 {@code degraded-session} 标志时跑一次:
 * 对 mtime 晚于上次完整快照创建时刻的 region/entities 文件逐 slot 读 raw bytes 入 store
 * 并登记到 {@link CurrentSnapshotState}, 由下一次快照统一纳入, 补齐降级窗口的缺口.
 *
 * <p><b>复用 Phase C baseline 机制</b>: 单 slot 的"读 raw bytes -> hash -> store.put ->
 * 登记 state"与错误处理逻辑与 {@link BaselineScanner#scanRegionFile} 同源 (同一
 * {@link RegionFileSlotReader} + 同样的 TornReadException 不入库语义 + 同样对单 slot 的
 * 结构性 IOException per-slot catch 后 continue 跳过坏块继续扫 + 同样以
 * CurrentSnapshotState 命中即跳过)。区别仅在选区: baseline 扫全量 region 一次, 本类只
 * 扫 mtime 晚于 cutoff 的 region (降级窗口可能变更的子集), 不持久化逐 region 进度
 * (一次性补采, 不续传)。
 *
 * <p><b>cutoff 选取</b>: 上次"完整"快照 (manifest.incomplete=false) 的 createdAtMillis.
 * 任何 mtime > cutoff 的 region 文件都可能在降级窗口被改过, 全部重扫。取不到完整快照时
 * cutoff=0, 退化为全量重扫 (保守: 宁可多扫不可漏采)。
 *
 * <p><b>与活跃 dirty 路径并发安全</b>: 重扫在 server start 阶段单线程跑, 此时 BAS 已是新
 * 进程 (重启后未必降级)。若新进程活跃路径已采过某 chunk (CurrentSnapshotState 命中) 则
 * 跳过, 以活跃路径的最新字节为准, 不用磁盘旧字节覆盖。
 */
public final class DegradedRescan {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private static final int REGION_SHIFT = 5;
    private static final int REGION_SIZE = 1 << REGION_SHIFT;
    private static final int SLOTS_PER_REGION = REGION_SIZE * REGION_SIZE;

    private final ChunkStore store;
    private final CurrentSnapshotState state;
    private final WorldPaths paths;
    private final HashFunction hashFunction;
    private final Set<Hash> writtenThisWindow;

    public DegradedRescan(ChunkStore store,
                          CurrentSnapshotState state,
                          WorldPaths paths,
                          HashFunction hashFunction,
                          Set<Hash> writtenThisWindow) {
        this.store = store;
        this.state = state;
        this.paths = paths;
        this.hashFunction = hashFunction;
        this.writtenThisWindow = writtenThisWindow;
    }

    /**
     * 重扫 mtime 晚于 cutoff 的所有 region/entities 文件, 把其中尚未被活跃路径采过的 chunk
     * 登记进 CurrentSnapshotState 并入 store. 返回本次补采的统计.
     *
     * @param cutoffMillis 上次完整快照创建时刻; mtime 严格大于它的 region 才重扫.
     *                     传 0 退化为全量重扫.
     */
    public Result rescan(long cutoffMillis) throws IOException {
        List<String> dimensions = paths.discoverDimensions();
        LOGGER.info("[BetterBackup] degraded-window rescan starting: cutoffMillis={} dimensions={}",
                cutoffMillis, dimensions);

        long recovered = 0;
        long deduped = 0;
        long skipped = 0;
        int regionsScanned = 0;

        for (String dim : dimensions) {
            for (boolean entityChannel : new boolean[]{false, true}) {
                Path dir = entityChannel ? paths.entitiesDir(dim) : paths.regionDir(dim);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                for (Path mca : listRegionFiles(dir)) {
                    if (Files.getLastModifiedTime(mca).toMillis() <= cutoffMillis) {
                        continue;
                    }
                    regionsScanned++;
                    RegionResult rr = rescanRegionFile(dim, entityChannel, mca);
                    recovered += rr.recovered();
                    deduped += rr.deduped();
                    skipped += rr.skipped();
                }
            }
        }

        LOGGER.info("[BetterBackup] degraded-window rescan complete: regions={} recovered={} deduped={} skipped(active)={}",
                regionsScanned, recovered, deduped, skipped);
        return new Result(recovered, deduped, skipped, regionsScanned);
    }

    private RegionResult rescanRegionFile(String dim, boolean entityChannel, Path mca) throws IOException {
        RegionFileSlotReader.RegionCoords rc = RegionFileSlotReader.parseRegionCoords(mca);
        long recovered = 0;
        long deduped = 0;
        long skipped = 0;

        for (int slot = 0; slot < SLOTS_PER_REGION; slot++) {
            int localX = slot & (REGION_SIZE - 1);
            int localZ = (slot >> REGION_SHIFT) & (REGION_SIZE - 1);
            int chunkX = (rc.rx() << REGION_SHIFT) + localX;
            int chunkZ = (rc.rz() << REGION_SHIFT) + localZ;
            long packedPos = ChunkPos.asLong(chunkX, chunkZ);

            boolean alreadyCaptured = entityChannel
                    ? state.containsEntityChunk(dim, packedPos)
                    : state.containsChunk(dim, packedPos);
            if (alreadyCaptured) {
                skipped++;
                continue;
            }

            byte[] rawBytes;
            try {
                rawBytes = RegionFileSlotReader.readSlot(mca, localX, localZ);
            } catch (TornReadException e) {
                // 撕裂读: 不入库混合字节. 该 slot 正被 vanilla 原地重写, 是高频写的已加载
                // chunk, 新进程的活跃 dirty 路径终会采到它. 补采放过即可, 不入库垃圾.
                continue;
            } catch (IOException e) {
                // 单 slot 结构损坏 / 截断 (非撕裂的硬错误). 与 BaselineScanner.scanRegionFile
                // 同源: 不让一个坏 chunk 中止整个降级窗口补采, 否则该 region 之后所有 region
                // 的窗口 chunk 全部漏采, 且 degraded-session 标志保留导致下次启动重撞同一坏块
                // 死循环. 记 ERROR 不吞细节, 跳过该 slot 继续扫其余 slot/region. 不入库残缺字节.
                LOGGER.error("[BetterBackup] degraded-window rescan failed to read chunk slot ({},{}) dim={} entityChannel={} in {}",
                        chunkX, chunkZ, dim, entityChannel, mca, e);
                continue;
            }
            if (rawBytes == null) {
                continue; // 空 slot, 跳过
            }

            Hash hash = hashFunction.hash(rawBytes);
            boolean wrote = store.put(hash, rawBytes);
            if (wrote) {
                writtenThisWindow.add(hash);
                recovered++;
            } else {
                deduped++;
            }
            if (entityChannel) {
                state.putEntityChunk(dim, packedPos, hash);
            } else {
                state.putChunk(dim, packedPos, hash);
            }
        }
        return new RegionResult(recovered, deduped, skipped);
    }

    private static List<Path> listRegionFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> result = new ArrayList<>();
            files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("r.") && n.endsWith(".mca") && Files.isRegularFile(p);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(result::add);
            return result;
        }
    }

    /** 补采结果. recovered=本次新入库 chunk 数, deduped=已在 store, skipped=活跃路径已采. */
    public record Result(long recovered, long deduped, long skippedActive, int regionsScanned) {
    }

    private record RegionResult(long recovered, long deduped, long skipped) {
    }
}
