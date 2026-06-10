package com.shinoyuki.betterbackup.baseline;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * baseline 全量扫描的断点续传记录. 进度按 region 文件粒度持久化到 store 目录,
 * 重启后 scanner 跳过已 <b>committed</b> 的 region 文件, 不重复入库.
 *
 * <p><b>两阶段状态 (修复 P0 崩溃窗口)</b>: 早期实现把"region 扫完"与"该 region 的 chunk
 * 登记进 manifest"两件事解耦 — region 扫完立即持久化为完成, 但登记要等下一次快照 drain
 * 才进 manifest. 若进程在两者之间被 kill -9 / OOM / 断电: 进度说 region 已完成 (重启
 * 永不重扫), 登记却丢了, 这些 chunk 永远进不了任何 manifest, store 字节成无引用孤儿被
 * GC 删除, baseline 仍照写 complete 标记 -> restore 出来的世界凭空缺块, verify/fsck
 * 查不出. 为消灭这个窗口, 进度拆成两状态:
 * <ul>
 *   <li>{@code scanned} (待提交): region 扫完, chunk 已入 store 且登记进内存
 *       CurrentSnapshotState, 但尚未进任何 manifest. 续传 <b>必须重扫</b> (store.put
 *       幂等去重, 重扫只是重新登记, 代价低)</li>
 *   <li>{@code committed} (已提交): 该 region 的登记已随某份成功写盘的 manifest 落地.
 *       续传跳过</li>
 * </ul>
 * SnapshotCreator 在 manifest atomic rename 成功后调 {@link #promoteScannedToCommitted}
 * 把"drain 之前已 scanned"的 region 晋升为 committed. complete 标记只有在所有 region
 * 都 committed 且扫描已收尾时才写 ({@link #markCompleteIfAllCommitted}).
 *
 * <p><b>磁盘布局</b> ({@code <storeRoot>/baseline/}):
 * <ul>
 *   <li>{@code progress}: region 文件清单, 每行
 *       {@code <channel>\t<dimensionId>\t<mcaFileName>\t<status>} (status = scanned|committed,
 *       channel = region|entities). 同一 region 可出现多行 (先 scanned 后 committed),
 *       load 时后出现的行覆盖前者 (晋升语义)</li>
 *   <li>{@code complete}: 全量扫描全部 committed 后写入的标记文件 (空文件),
 *       存在即 baseline 完成</li>
 * </ul>
 *
 * <p><b>向后兼容</b>: 旧格式 progress 行 (只有 3 列, 无 status) 一律按 {@code scanned}
 * 读入 -> 升级后这些 region 重扫一次并在下次快照晋升, 自动治愈历史窗口. 已存在的
 * complete 标记继续按完成处理 (老用户的登记早已随历史快照入 manifest).
 *
 * <p><b>为什么 append + 整文件 fsync 而非整表重写</b>: 一次全量扫描可能有几千个
 * region 文件, 每次状态变更追加一行比每次重写整个清单省 IO. 单行追加非原子, 但进程在
 * 追加中途被杀最多丢失最后写入的那一行 (该 region 下次按其上一已持久化状态处理, 入库
 * idempotent 不会出错). complete 标记走 tmp + fsync + atomic rename, 因为它是 restore
 * 门禁的唯一依据, 不容许半写.
 *
 * <p><b>非线程安全</b>: scanner 串行追加 scanned, SnapshotCreator 在 synchronized
 * create() 内串行晋升 committed. 二者跨线程但调用方保证不并发触碰同一 BaselineProgress
 * 的写方法 (晋升只在快照写盘后跑, 与扫描线程的 markRegionScanned 之间存在内存可见性
 * 依赖, 详见 promoteScannedToCommitted).
 */
public final class BaselineProgress {

    public static final String CHANNEL_REGION = "region";
    public static final String CHANNEL_ENTITIES = "entities";

    private static final String PROGRESS_FILE = "progress";
    private static final String COMPLETE_MARKER = "complete";

    private static final String STATUS_SCANNED = "scanned";
    private static final String STATUS_COMMITTED = "committed";

    /** region 提交状态. SCANNED = 扫完待提交; COMMITTED = 已随 manifest 落地. */
    enum RegionStatus {
        SCANNED,
        COMMITTED
    }

    private final Path baselineDir;
    private final Path progressFile;
    private final Path completeMarker;

    // key (channel\tdim\tmca) -> 当前状态. committed 覆盖 scanned, 不回退.
    private final Map<String, RegionStatus> regionStatus;

    public BaselineProgress(Path storeRoot) {
        this.baselineDir = storeRoot.resolve("baseline");
        this.progressFile = baselineDir.resolve(PROGRESS_FILE);
        this.completeMarker = baselineDir.resolve(COMPLETE_MARKER);
        this.regionStatus = new HashMap<>();
    }

    /**
     * 从磁盘加载已记录的进度. 启动时调一次, 把 progress 文件每行读进内存 map.
     * progress 文件不存在 (从未扫描) 时 map 留空. 同一 region 多行时, committed 优先
     * 于 scanned (晋升不回退); 旧格式 3 列行无 status, 按 scanned 读入 (向后兼容).
     */
    public void load() throws IOException {
        regionStatus.clear();
        if (!Files.exists(progressFile)) {
            return;
        }
        List<String> lines = Files.readAllLines(progressFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            // 行尾追加了 status 列后, 旧 3 列行 split 出 3 段 (无 status), 新 4 列行出 4 段.
            // 用 key = 前 3 段拼回 (channel\tdim\tmca), 第 4 段是 status.
            int lastTab = line.lastIndexOf('\t');
            String key;
            RegionStatus status;
            String maybeStatus = lastTab >= 0 ? line.substring(lastTab + 1) : "";
            if (STATUS_COMMITTED.equals(maybeStatus)) {
                key = line.substring(0, lastTab);
                status = RegionStatus.COMMITTED;
            } else if (STATUS_SCANNED.equals(maybeStatus)) {
                key = line.substring(0, lastTab);
                status = RegionStatus.SCANNED;
            } else {
                // 旧格式: 整行就是 key (channel\tdim\tmca), 无 status 列 -> 当 scanned.
                key = line;
                status = RegionStatus.SCANNED;
            }
            regionStatus.merge(key, status, BaselineProgress::higher);
        }
    }

    /** baseline 是否已完整跑完 (complete 标记存在). restore 门禁的唯一依据. */
    public boolean isComplete() {
        return Files.exists(completeMarker);
    }

    /**
     * 已记录的 region 文件数 (scanned + committed 累加, 跨 channel 与 dimension).
     * status 命令展示进度用.
     */
    public int completedRegionCount() {
        return regionStatus.size();
    }

    /** 已 committed (登记已进 manifest) 的 region 文件数. 诊断 / 测试用. */
    public int committedRegionCount() {
        return (int) regionStatus.values().stream().filter(s -> s == RegionStatus.COMMITTED).count();
    }

    /**
     * 该 region 是否已 committed (登记已随某份 manifest 落地). scanner 续传只跳过
     * committed 的 region; scanned-未提交的必须重扫, 重新登记进 state 等下次快照晋升.
     */
    public boolean isRegionCommitted(String channel, String dimensionId, String mcaFileName) {
        return regionStatus.get(key(channel, dimensionId, mcaFileName)) == RegionStatus.COMMITTED;
    }

    /**
     * 标记一个 region 文件扫描完成 (scanned, 待提交): 追加一行到 progress 文件并 fsync,
     * 同步更新内存 map. 已 committed 的 region 重复标 scanned 是 no-op (不回退状态,
     * 不重复追加); 已 scanned 的也是 no-op (避免重扫时重复追加).
     */
    public void markRegionScanned(String channel, String dimensionId, String mcaFileName) throws IOException {
        String key = key(channel, dimensionId, mcaFileName);
        RegionStatus existing = regionStatus.get(key);
        if (existing != null) {
            // 已 scanned 或已 committed: 都不需要再追加 scanned 行 (committed 不回退).
            return;
        }
        regionStatus.put(key, RegionStatus.SCANNED);
        appendLine(key, STATUS_SCANNED);
    }

    /**
     * 把一批 region 从 scanned 晋升为 committed 并持久化. SnapshotCreator 在 manifest
     * atomic rename 成功后调用, 入参是"该次快照 drain 之前已处于 scanned 状态"的 region
     * 集合快照 — 这些 region 的 chunk 登记已随这份 manifest 落地, 可安全跳过重扫.
     *
     * <p><b>为什么只晋升传入的捕获集而非当前所有 scanned</b>: drain 之后才扫完的 region
     * 其登记没进这份 manifest, 必须留到下一次快照晋升 (overlay 语义保证下份 manifest
     * 继承本份引用 + 叠加新 drain). 调用方负责在 drainAndClear 之前捕获 scanned 快照.
     *
     * <p><b>内存可见性</b>: 扫描线程 markRegionScanned 写入 regionStatus 与本方法读取
     * 之间, 通过 SnapshotCreator.create() 的 synchronized + 调用方先捕获快照集 (HashSet
     * 安全发布) 建立 happens-before. 已 committed 的 key 跳过 (幂等, 不重复追加).
     *
     * @param scannedKeysSnapshot drain 前捕获的 scanned region key 集合 (由
     *                            {@link #snapshotScannedKeys()} 取得)
     */
    public void promoteScannedToCommitted(Set<String> scannedKeysSnapshot) throws IOException {
        for (String key : scannedKeysSnapshot) {
            if (regionStatus.get(key) == RegionStatus.COMMITTED) {
                continue;
            }
            regionStatus.put(key, RegionStatus.COMMITTED);
            appendLine(key, STATUS_COMMITTED);
        }
    }

    /**
     * 当前处于 scanned (待提交) 状态的 region key 集合快照. SnapshotCreator 必须在
     * drainAndClear 之前调用捕获这个集合, 待 manifest 写盘成功后传给
     * {@link #promoteScannedToCommitted} 晋升. 返回独立副本, 调用方持有期间扫描线程
     * 继续 markRegionScanned 不影响这份快照.
     */
    public Set<String> snapshotScannedKeys() {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, RegionStatus> e : regionStatus.entrySet()) {
            if (e.getValue() == RegionStatus.SCANNED) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * 全部 region 都已 committed 时写 complete 标记 (atomic: tmp + fsync + rename).
     * 由 SnapshotCreator 晋升后调用: 只有"扫描已收尾 (scanComplete=true) 且无任何
     * scanned-未提交 region"才写标记, 此后 restore 门禁放行.
     *
     * <p>scanComplete=false (扫描线程还在跑) 时即使当前无 scanned 也不写标记 — 后续
     * pass 还会扫出新 region. 标记一旦写下, restore 立即放行, 不能在扫描半途误放.
     *
     * @param scanComplete 扫描线程是否已遍历完所有 pass (扫描收尾回调置 true)
     * @return 是否实际写下了 complete 标记
     */
    public boolean markCompleteIfAllCommitted(boolean scanComplete) throws IOException {
        if (!scanComplete) {
            return false;
        }
        if (isComplete()) {
            return true;
        }
        for (RegionStatus s : regionStatus.values()) {
            if (s != RegionStatus.COMMITTED) {
                return false;
            }
        }
        Files.createDirectories(baselineDir);
        Path tmp = completeMarker.resolveSibling(COMPLETE_MARKER + ".tmp");
        Files.write(tmp, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, completeMarker, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private void appendLine(String key, String status) throws IOException {
        Files.createDirectories(baselineDir);
        byte[] line = (key + "\t" + status + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        Files.write(progressFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel ch = FileChannel.open(progressFile, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    /** committed 比 scanned 高: load 合并同一 region 多行时取较高状态 (晋升不回退). */
    private static RegionStatus higher(RegionStatus a, RegionStatus b) {
        return (a == RegionStatus.COMMITTED || b == RegionStatus.COMMITTED)
                ? RegionStatus.COMMITTED : RegionStatus.SCANNED;
    }

    private static String key(String channel, String dimensionId, String mcaFileName) {
        return channel + "\t" + dimensionId + "\t" + mcaFileName;
    }
}
