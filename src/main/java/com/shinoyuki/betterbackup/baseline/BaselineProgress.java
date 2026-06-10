package com.shinoyuki.betterbackup.baseline;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * baseline 全量扫描的断点续传记录. 进度按 region 文件粒度持久化到 store 目录,
 * 重启后 scanner 跳过已记录的 region 文件, 不重复入库.
 *
 * <p><b>磁盘布局</b> ({@code <storeRoot>/baseline/}):
 * <ul>
 *   <li>{@code progress}: 已完整扫描的 region 文件清单, 每行一条
 *       {@code <channel>\t<dimensionId>\t<mcaFileName>} (channel = region|entities)</li>
 *   <li>{@code complete}: 全量扫描跑完后写入的标记文件 (空文件), 存在即 baseline 完成</li>
 * </ul>
 *
 * <p><b>为什么 append + 整文件 fsync 而非整表重写</b>: 一次全量扫描可能有几千个
 * region 文件, 每完成一个追加一行比每次重写整个清单省 IO. 单行追加非原子, 但
 * 进程在追加中途被杀最多丢失最后写入的那一行 (该 region 下次重扫, 入库 idempotent
 * 不会出错). complete 标记走 tmp + fsync + atomic rename, 因为它是 restore 门禁
 * 的唯一依据, 不容许半写.
 *
 * <p><b>非线程安全</b>: baseline 扫描是单线程任务, 调用方 (BaselineScanner) 串行调用.
 */
public final class BaselineProgress {

    public static final String CHANNEL_REGION = "region";
    public static final String CHANNEL_ENTITIES = "entities";

    private static final String PROGRESS_FILE = "progress";
    private static final String COMPLETE_MARKER = "complete";

    private final Path baselineDir;
    private final Path progressFile;
    private final Path completeMarker;
    private final Set<String> completedRegions;

    public BaselineProgress(Path storeRoot) {
        this.baselineDir = storeRoot.resolve("baseline");
        this.progressFile = baselineDir.resolve(PROGRESS_FILE);
        this.completeMarker = baselineDir.resolve(COMPLETE_MARKER);
        this.completedRegions = new HashSet<>();
    }

    /**
     * 从磁盘加载已记录的进度. 启动时调一次, 把 progress 文件每行读进内存 set.
     * progress 文件不存在 (从未扫描) 时 set 留空.
     */
    public void load() throws IOException {
        completedRegions.clear();
        if (!Files.exists(progressFile)) {
            return;
        }
        List<String> lines = Files.readAllLines(progressFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (!line.isBlank()) {
                completedRegions.add(line);
            }
        }
    }

    /** baseline 是否已完整跑完 (complete 标记存在). restore 门禁的唯一依据. */
    public boolean isComplete() {
        return Files.exists(completeMarker);
    }

    /** 已记录完成的 region 文件数 (跨 channel 与 dimension 累加). status 命令展示进度用. */
    public int completedRegionCount() {
        return completedRegions.size();
    }

    /** 该 region 文件是否已扫描完 (内存 set 命中). scanner 用它跳过已完成的 region. */
    public boolean isRegionDone(String channel, String dimensionId, String mcaFileName) {
        return completedRegions.contains(key(channel, dimensionId, mcaFileName));
    }

    /**
     * 标记一个 region 文件扫描完成: 追加一行到 progress 文件并 fsync, 同步更新内存 set.
     * 已记录的 region 重复标记是 no-op (不重复追加), 让 scanner 重跑时幂等.
     */
    public void markRegionDone(String channel, String dimensionId, String mcaFileName) throws IOException {
        String key = key(channel, dimensionId, mcaFileName);
        if (!completedRegions.add(key)) {
            return;
        }
        Files.createDirectories(baselineDir);
        byte[] line = (key + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        Files.write(progressFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel ch = FileChannel.open(progressFile, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    /**
     * 写 complete 标记 (atomic: tmp + fsync + rename). 全量扫描遍历完所有 region
     * 文件后调用一次, 此后 restore 门禁放行.
     */
    public void markComplete() throws IOException {
        Files.createDirectories(baselineDir);
        Path tmp = completeMarker.resolveSibling(COMPLETE_MARKER + ".tmp");
        Files.write(tmp, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, completeMarker, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String key(String channel, String dimensionId, String mcaFileName) {
        return channel + "\t" + dimensionId + "\t" + mcaFileName;
    }
}
