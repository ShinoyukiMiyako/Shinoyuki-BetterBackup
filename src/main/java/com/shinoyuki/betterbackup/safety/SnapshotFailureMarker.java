package com.shinoyuki.betterbackup.safety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

/**
 * 快照失败可见性标记 (.incomplete). 解决"快照失败只进 log / Prometheus counter,
 * 服主在游戏内 /betterbackup status 看不到, 误以为备份正常"的失明问题.
 *
 * <p>语义: 一次快照尝试失败 (磁盘预检不过 / manifest 写盘 IOException) 时调用
 * {@link #write} 落一个标记文件; 下一次快照成功时调用 {@link #clear} 删除它.
 * 因此标记存在 == "最近一次快照尝试失败且此后没有成功的快照", status 命令读它即可
 * 把失败暴露给服主.
 *
 * <p>标记文件存于 storeRoot 顶层 (不在 snapshots/ 内, 避免被 manifest 列举 /
 * retention 误扫). 内容是单行 "时间戳毫秒\t失败原因", 供 status 展示与人工排查.
 */
public final class SnapshotFailureMarker {

    private static final String MARKER_FILENAME = "snapshot.incomplete";

    private final Path markerFile;

    public SnapshotFailureMarker(Path storeRoot) {
        Objects.requireNonNull(storeRoot, "storeRoot");
        this.markerFile = storeRoot.resolve(MARKER_FILENAME);
    }

    /**
     * 写 / 覆盖失败标记. atomic rename 落盘, 防写一半被 status 读到截断内容.
     *
     * @param atMillis 失败发生时间 (毫秒)
     * @param reason   失败原因, 用于人工排查
     */
    public void write(long atMillis, String reason) throws IOException {
        Objects.requireNonNull(reason, "reason");
        Files.createDirectories(markerFile.getParent());
        String line = atMillis + "\t" + reason.replace('\n', ' ').replace('\t', ' ');
        Path tmp = markerFile.resolveSibling(MARKER_FILENAME + ".tmp");
        Files.write(tmp, line.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, markerFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, markerFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 清除失败标记 (快照成功时调用). 标记不存在不报错. */
    public void clear() throws IOException {
        Files.deleteIfExists(markerFile);
    }

    /** 标记是否存在 == 最近一次快照失败且此后无成功快照. */
    public boolean exists() {
        return Files.exists(markerFile);
    }

    /**
     * 读出标记内容供 status 展示. 标记不存在返回 empty.
     *
     * @return 失败记录 (时间戳 + 原因), 或 empty
     * @throws IOException 标记文件读失败
     */
    public Optional<Failure> read() throws IOException {
        if (!Files.exists(markerFile)) {
            return Optional.empty();
        }
        String content = Files.readString(markerFile, StandardCharsets.UTF_8).trim();
        int tab = content.indexOf('\t');
        if (tab < 0) {
            // 旧格式 / 手工改坏: 整行当原因, 时间戳记 0 而非吞掉
            return Optional.of(new Failure(0L, content));
        }
        long atMillis = parseMillis(content.substring(0, tab));
        String reason = content.substring(tab + 1);
        return Optional.of(new Failure(atMillis, reason));
    }

    private static long parseMillis(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public Path markerFile() {
        return markerFile;
    }

    /** 一条失败记录. */
    public record Failure(long atMillis, String reason) {
    }
}
