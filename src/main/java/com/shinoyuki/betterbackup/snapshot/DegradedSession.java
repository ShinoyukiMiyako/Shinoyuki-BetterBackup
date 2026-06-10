package com.shinoyuki.betterbackup.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * BAS 降级会话标志, 持久化到 store 目录 (PLAN Phase F).
 *
 * <p>BAS 管线进入 degraded mode 后, listener 停止 fire, 本进程的活跃 dirty 路径
 * 失明. 此时落一个 {@code <storeRoot>/degraded-session} 标志文件, 内容是降级发生
 * 时刻 (毫秒, 单行 UTF-8). 标志存在即"上一次运行经历过降级且尚未补采".
 *
 * <p>下次启动 BetterBackupMod 检测到该标志后, 对 mtime 晚于上次完整快照的 region
 * 文件做增量重扫 ({@link com.shinoyuki.betterbackup.baseline.DegradedRescan}) 补采
 * 降级窗口内变更的 chunk, 完成后 {@link #clear()} 删标志.
 *
 * <p><b>原子写</b>: tmp + rename, 防 status / 启动检测读到截断内容.
 *
 * <p><b>幂等</b>: 降级在单进程内是单向闩锁 (BAS 侧 onDegraded 每进程最多 fire 一次),
 * 故 {@link #mark} 单进程最多调一次; 但多次调用也安全 (覆盖写同一时刻语义).
 */
public final class DegradedSession {

    private static final String MARKER_FILENAME = "degraded-session";

    private final Path markerFile;

    public DegradedSession(Path storeRoot) {
        Objects.requireNonNull(storeRoot, "storeRoot");
        this.markerFile = storeRoot.resolve(MARKER_FILENAME);
    }

    /** 标志是否存在 == 上一次 (或本次) 运行经历过降级且尚未补采重扫. */
    public boolean exists() {
        return Files.exists(markerFile);
    }

    /**
     * 落降级标志. atomic rename 防半写.
     *
     * @param atMillis 降级发生时刻 (毫秒)
     */
    public void mark(long atMillis) throws IOException {
        Files.createDirectories(markerFile.getParent());
        Path tmp = markerFile.resolveSibling(MARKER_FILENAME + ".tmp");
        Files.write(tmp, Long.toString(atMillis).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, markerFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, markerFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 读出降级发生时刻. 标志不存在或内容无法解析返回 empty (内容损坏不该掩盖成 0,
     * 让调用方据 isPresent 决定走重扫还是跳过).
     */
    public OptionalLong markedAtMillis() throws IOException {
        if (!Files.exists(markerFile)) {
            return OptionalLong.empty();
        }
        String content = Files.readString(markerFile, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(content));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** 清除标志 (降级窗口重扫完成后调用). 标志不存在不报错. */
    public void clear() throws IOException {
        Files.deleteIfExists(markerFile);
    }

    public Path markerFile() {
        return markerFile;
    }
}
