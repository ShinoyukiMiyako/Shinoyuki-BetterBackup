package com.shinoyuki.betterbackup.baseline;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.TornReadException;
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
 * baseline 全量扫描 (PLAN Phase C). 一次性遍历每个维度 {@code region/} 与
 * {@code entities/} 下所有 .mca 文件, 逐 slot 用 Phase B 加固后的 reader 读 raw bytes
 * 入 store 并登记到 {@link CurrentSnapshotState}, 让装 mod 后从未被加载过的 chunk 也
 * 进入快照. 这消灭了"早期快照 restore 丢世界"的 P0.
 *
 * <p><b>断点续传</b>: 进度按 region 文件粒度持久化 ({@link BaselineProgress}), 已扫完的
 * region 文件下次启动直接跳过, 不重复入库.
 *
 * <p><b>与活跃 dirty 路径并发</b>: 扫描期间 BAS listener 仍在 fire, 把已加载 chunk 的
 * 最新字节登记进 CurrentSnapshotState. baseline 读盘前先查 CurrentSnapshotState, 该
 * chunk 已被活跃路径采过就跳过 -- 活跃路径拿的是磁盘最新状态, baseline 不该用扫描时刻
 * 的旧字节去覆盖它 (覆盖语义下后写为准会回退). 以 CurrentSnapshotState 为准.
 *
 * <p><b>撕裂读处理</b>: reader 命中撕裂读抛 {@link TornReadException} 时, 该 slot 正被
 * vanilla 原地重写. baseline 不入库混合字节, 而是把该 region 标为本轮未完成, 留到下一轮
 * 重扫; 多轮后仍撕裂的 chunk 必是高频写的已加载 chunk, 活跃 dirty 路径终会采到它,
 * 届时 CurrentSnapshotState 命中即跳过. 这样既不入库垃圾, 也不会永久漏采.
 *
 * <p><b>限速</b>: 每读一个 chunk slot 调一次 {@link RateLimiter}, 默认实现按
 * scanChunksPerSecond 节流, 避免全量扫描在玩家在线时打满磁盘 IO.
 */
public final class BaselineScanner {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private static final int REGION_SHIFT = 5;
    private static final int REGION_SIZE = 1 << REGION_SHIFT;
    private static final int SLOTS_PER_REGION = REGION_SIZE * REGION_SIZE;

    /**
     * 全量扫描的最大重扫轮数. 第一轮把所有未撕裂的 region 扫完, 后续轮仅重试上一轮因
     * 撕裂读未完成的 region. 给 8 轮裕量后仍未完成的 region 直接放弃标完成 (其残余
     * 撕裂 chunk 必是高频写的已加载 chunk, 由活跃 dirty 路径兜底), 防永久阻塞 baseline.
     */
    static final int MAX_PASSES = 8;

    private final ChunkStore store;
    private final CurrentSnapshotState state;
    private final com.shinoyuki.betterbackup.io.WorldPaths paths;
    private final HashFunction hashFunction;
    private final Set<Hash> writtenThisWindow;
    private final BaselineProgress progress;
    private final RateLimiter rateLimiter;
    private volatile boolean stopRequested;

    /**
     * 扫描遍历完所有 pass 后的收尾回调. 不再由 scanner 直接写 complete 标记 (那会脱离
     * "登记已进 manifest"的事务关系), 而是请求一次快照: BetterBackupMod 把此回调 wire 成
     * "置 scanFinished 标志 + creator.create('baseline-complete')". 快照写盘成功后由
     * SnapshotCreator 晋升最后一批 scanned region 为 committed 并在全部 committed 时写
     * complete 标记. 已 complete 时不调.
     */
    private final Runnable onScanFinished;

    public BaselineScanner(ChunkStore store,
                           CurrentSnapshotState state,
                           com.shinoyuki.betterbackup.io.WorldPaths paths,
                           HashFunction hashFunction,
                           Set<Hash> writtenThisWindow,
                           BaselineProgress progress,
                           RateLimiter rateLimiter,
                           Runnable onScanFinished) {
        this.store = store;
        this.state = state;
        this.paths = paths;
        this.hashFunction = hashFunction;
        this.writtenThisWindow = writtenThisWindow;
        this.progress = progress;
        this.rateLimiter = rateLimiter;
        this.onScanFinished = onScanFinished;
    }

    /**
     * 跑完整次全量扫描 (含已加载进度的续传). 已 complete 则直接返回不重扫.
     * 遍历完所有 region 后不再直接写 complete 标记, 而是调收尾回调 {@link #onScanFinished}
     * 请求一次快照, 由 SnapshotCreator 在快照写盘成功后晋升 scanned->committed 并在全部
     * committed 时写 complete 标记 (登记进 manifest 与标完成之间从此有事务关系, 消灭崩溃
     * 窗口). 返回本次实际入库 (写盘) 的 chunk 数 / 被 dirty 路径跳过的 chunk 数, 以及回调
     * 跑完后 baseline 是否已 complete (供日志与测试断言).
     */
    /**
     * 请求停止扫描 (关服路径调用). 扫描在下一个 chunk slot 边界退出: 不调收尾回调、
     * 不标记被中断的半扫 region; 已 scanned 的 region 进度已持久化, 下次启动续传.
     */
    public void requestStop() {
        stopRequested = true;
    }

    public Result scan() throws IOException {
        progress.load();
        if (progress.isComplete()) {
            LOGGER.info("[BetterBackup] baseline already complete, skipping scan");
            return new Result(0, 0, 0, true);
        }

        List<String> dimensions = paths.discoverDimensions();
        LOGGER.info("[BetterBackup] baseline scan starting: dimensions={}", dimensions);

        long stored = 0;
        long deduped = 0;
        long skipped = 0;
        boolean allRegionsClean = true;

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            PassResult pr = runPass(dimensions, pass == MAX_PASSES - 1);
            stored += pr.stored();
            deduped += pr.deduped();
            skipped += pr.skipped();
            if (pr.allRegionsDone()) {
                allRegionsClean = pr.noTornReads();
                break;
            }
            LOGGER.warn("[BetterBackup] baseline pass {} left regions incomplete (torn reads), retrying", pass);
        }

        if (stopRequested) {
            // 关服中断: 不调收尾回调 (关服快照由 onServerStopping 自己创建, 这里再触发
            // 一次会与之竞争), 已 scanned 的 region 进度已持久化, 下次启动续传.
            LOGGER.info("[BetterBackup] baseline scan stopped before completion: stored={} so far, "
                    + "progress persisted, resumes next start", stored);
            return new Result(stored, deduped, skipped, false);
        }

        // 扫描收尾: 请求一次快照晋升最后一批 scanned region 并写 complete 标记.
        // 回调跑完后 progress.isComplete() 才反映真实完成状态.
        onScanFinished.run();
        boolean complete = progress.isComplete();
        LOGGER.info("[BetterBackup] baseline scan swept: stored={} deduped={} skipped(dirty)={} clean={} complete={}",
                stored, deduped, skipped, allRegionsClean, complete);
        return new Result(stored, deduped, skipped, complete);
    }

    /** 跑一轮: 遍历所有 dim 的 region/entities, 跳过已 committed 的 region 文件. */
    private PassResult runPass(List<String> dimensions, boolean lastPass) throws IOException {
        long stored = 0;
        long deduped = 0;
        long skipped = 0;
        boolean allDone = true;
        boolean tornSeen = false;

        for (String dim : dimensions) {
            for (String channel : List.of(BaselineProgress.CHANNEL_REGION, BaselineProgress.CHANNEL_ENTITIES)) {
                Path dir = channel.equals(BaselineProgress.CHANNEL_REGION)
                        ? paths.regionDir(dim) : paths.entitiesDir(dim);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                boolean entityChannel = channel.equals(BaselineProgress.CHANNEL_ENTITIES);
                for (Path mca : listRegionFiles(dir)) {
                    String mcaName = mca.getFileName().toString();
                    // 续传只跳过已 committed (登记已进 manifest) 的 region. scanned-未提交的
                    // 必须重扫: store.put 幂等去重, 重扫只是把登记重新放进 state 等下次快照
                    // 晋升, 代价低; 换来的是崩溃窗口里丢失的登记被重新建立, 自动治愈.
                    if (progress.isRegionCommitted(channel, dim, mcaName)) {
                        continue;
                    }
                    RegionResult rr = scanRegionFile(dim, channel, entityChannel, mca);
                    stored += rr.stored();
                    deduped += rr.deduped();
                    skipped += rr.skipped();
                    // 停服中断: 本 region 可能只扫了一半, 绝不能进下面的标记分支 -- lastPass
                    // 会把半扫 region 标 scanned, 晋升 committed 后缺的 slot 永不重扫 (撕裂读
                    // 场景有"活跃路径兜底"的理由, 停服中断的是任意未加载 chunk, 没有兜底).
                    if (stopRequested) {
                        return new PassResult(stored, deduped, skipped, false, !tornSeen);
                    }
                    if (rr.clean() || lastPass) {
                        // 扫完记 scanned (待提交), 不立即标完成. 登记 (state.putChunk) 已在
                        // scanRegionFile 内完成, 等下次快照 drain 进 manifest 后才晋升 committed.
                        progress.markRegionScanned(channel, dim, mcaName);
                        tornSeen |= !rr.clean();
                    } else {
                        allDone = false;
                        tornSeen = true;
                    }
                }
            }
        }
        return new PassResult(stored, deduped, skipped, allDone, !tornSeen);
    }

    /**
     * 扫单个 .mca 文件的 1024 个 slot. 返回入库 / dedup / 跳过计数 + 是否 clean
     * (无撕裂读残留). 撕裂读的 slot 不入库, 标记本文件 unclean 留待下一轮; 但若该 slot
     * 已在 CurrentSnapshotState (dirty 路径已采) 则视为已捕获, 不影响 clean.
     */
    private RegionResult scanRegionFile(String dim, String channel, boolean entityChannel, Path mca)
            throws IOException {
        RegionFileSlotReader.RegionCoords rc = RegionFileSlotReader.parseRegionCoords(mca);
        long stored = 0;
        long deduped = 0;
        long skipped = 0;
        boolean clean = true;

        for (int slot = 0; slot < SLOTS_PER_REGION; slot++) {
            if (stopRequested) {
                return new RegionResult(stored, deduped, skipped, false);
            }
            int localX = slot & (REGION_SIZE - 1);
            int localZ = (slot >> REGION_SHIFT) & (REGION_SIZE - 1);
            int chunkX = (rc.rx() << REGION_SHIFT) + localX;
            int chunkZ = (rc.rz() << REGION_SHIFT) + localZ;
            long packedPos = ChunkPos.asLong(chunkX, chunkZ);

            // 并发跳过: 活跃 dirty 路径已采过此 chunk → 以 CurrentSnapshotState 为准.
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
                // 撕裂读: 不入库混合字节. 标本文件 unclean 留下一轮重扫. 但若期间 dirty
                // 路径已把它采进 state, 重查命中即不算漏 (clean 仍可能在下一轮恢复).
                clean = false;
                continue;
            } catch (IOException e) {
                // 单 slot 结构损坏 / 截断 (非撕裂的硬错误). 不让一个坏 chunk 中止整个全量
                // 扫描 -- baseline 必须能跑完并续传. 记 ERROR 不吞细节, 标 unclean 留重扫;
                // 该 chunk 若被加载过仍会由活跃 dirty 路径采到. 不入库残缺字节.
                LOGGER.error("[BetterBackup] baseline failed to read chunk slot ({},{}) dim={} channel={} in {}",
                        chunkX, chunkZ, dim, channel, mca, e);
                clean = false;
                continue;
            }
            if (rawBytes == null) {
                continue; // 空 slot (chunk 没生成过), 正常跳过
            }

            rateLimiter.acquire();

            Hash hash = hashFunction.hash(rawBytes);
            boolean wrote = store.put(hash, rawBytes);
            // 顺序要点 (GC 并发安全): 先登记进 state, 再 add 进 writtenThisWindow.
            // 增量 GC 在 drain 后读 writtenThisWindow 快照再读 state pending 集排除误删,
            // 此 happens-before 顺序保证凡进入 GC 快照的 hash 其 state 登记必已先行落入,
            // 必被 pending 捕获, 不会被误删 (见 SnapshotCreator.runIncrementalGc).
            if (entityChannel) {
                state.putEntityChunk(dim, packedPos, hash);
            } else {
                state.putChunk(dim, packedPos, hash);
            }
            if (wrote) {
                writtenThisWindow.add(hash);
                stored++;
            } else {
                deduped++;
            }
        }
        return new RegionResult(stored, deduped, skipped, clean);
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

    /** baseline 限速钩子. 每读一个非空 chunk slot 调一次 acquire. */
    @FunctionalInterface
    public interface RateLimiter {
        void acquire();

        /** 不限速 (测试或 scanChunksPerSecond 极大时). */
        RateLimiter NONE = () -> { };
    }

    /** 全量扫描结果. */
    public record Result(long stored, long deduped, long skippedDirty, boolean complete) {
    }

    private record PassResult(long stored, long deduped, long skipped, boolean allRegionsDone,
                              boolean noTornReads) {
    }

    private record RegionResult(long stored, long deduped, long skipped, boolean clean) {
    }
}
