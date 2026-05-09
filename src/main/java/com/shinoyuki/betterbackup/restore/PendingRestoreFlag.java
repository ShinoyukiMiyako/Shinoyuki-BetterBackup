package com.shinoyuki.betterbackup.restore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Restore flag 文件: {@code <worldRoot>/.shinoyuki-pending-restore}.
 *
 * <p>{@code /betterbackup restore <id>} 命令在服务端运行中下达时不立即执行 restore,
 * 而是写一个 flag 文件提示玩家手动停服. 下次服务端启动时 BetterBackupMod
 * onServerAboutToStart 检测到 flag 自动跑 RestoreFlow, 完成后删 flag.
 *
 * <p>flag 内容是 snapshotId (单行 UTF-8), 没多余 metadata.
 *
 * <p><b>为什么不直接在线 restore</b>: 在线 restore 需要 unload 所有 chunks /
 * 暂停 vanilla autosave / 防止玩家访问被替换的 region 文件, 复杂且容易出 bug.
 * 离线 restore 简单可靠 (vanilla 没启动 world load, 直接覆盖文件即可).
 */
public final class PendingRestoreFlag {

    private static final String FILE_NAME = ".shinoyuki-pending-restore";

    private PendingRestoreFlag() {
    }

    public static Path pathFor(Path worldRoot) {
        return worldRoot.resolve(FILE_NAME);
    }

    public static boolean exists(Path worldRoot) {
        return Files.exists(pathFor(worldRoot));
    }

    public static void write(Path worldRoot, String snapshotId) throws IOException {
        Files.createDirectories(worldRoot);
        Files.writeString(pathFor(worldRoot), snapshotId);
    }

    public static Optional<String> read(Path worldRoot) throws IOException {
        Path file = pathFor(worldRoot);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String content = Files.readString(file).trim();
        if (content.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    public static void clear(Path worldRoot) throws IOException {
        Files.deleteIfExists(pathFor(worldRoot));
    }
}
