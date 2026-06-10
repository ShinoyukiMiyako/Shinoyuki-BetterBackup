package com.shinoyuki.betterbackup.safety;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 备份 / 恢复两侧的磁盘空间预检 (Advanced Backups #128 教训: 磁盘写满时
 * 继续写会产出截断的备份或半覆盖的世界, 比直接拒绝更糟).
 *
 * <p>策略: 在动手写盘前查询目标路径所在文件系统的可用字节数, 低于硬性下限即抛
 * {@link InsufficientSpaceException} 让调用方放弃本次操作. 下限取保守固定值而非
 * 按数据量精算 -- snapshot 写入量在备份窗口内由 BackupWorker 增量产生, 创建
 * manifest 时无法预知; restore 回写量等于快照引用的全部 store 字节, 同样难以
 * 在不重扫 store 的情况下精确估算. 固定下限以"留出足够余量让 vanilla autosave +
 * 本次操作都不会把盘写到 0"为目标, 是成本最低且最稳妥的防线.
 */
public final class DiskSpaceCheck {

    /**
     * 硬性可用空间下限 (字节). 低于此值拒绝备份 / 恢复.
     *
     * <p>512 MiB: 单个 region 文件上限约 256 MiB (.mca 1024 slot * 255 sector *
     * 4 KiB 理论上界, 实际远小), 留两倍余量保证至少一个 region 的写入 + vanilla
     * 自身 save 不会在操作中途耗尽磁盘. 既不会因太小而失去保护意义, 也不会因太大
     * 而误拒小硬盘上的正常服务器.
     */
    public static final long MIN_FREE_BYTES = 512L * 1024 * 1024;

    private DiskSpaceCheck() {
    }

    /**
     * 检查 {@code target} 所在文件系统可用空间是否达到 {@code minFreeBytes}.
     *
     * <p>{@code target} 不必已存在: 向上回溯到第一个存在的祖先目录再查所在 FileStore,
     * 因为待创建的 store / world 目录所在盘符跟其父目录一致.
     *
     * @param target       即将写入的目录或文件路径
     * @param minFreeBytes 要求的最小可用字节数
     * @param operation    操作名 (用于异常消息, 如 "snapshot" / "restore")
     * @throws InsufficientSpaceException 可用空间低于阈值
     * @throws IOException                查询 FileStore 失败 (路径非法 / 无权限)
     */
    public static void require(Path target, long minFreeBytes, String operation) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        Path existing = firstExistingAncestor(target);
        if (existing == null) {
            throw new IOException("no existing ancestor for path: " + target);
        }
        FileStore fs = Files.getFileStore(existing);
        long usable = fs.getUsableSpace();
        if (usable < minFreeBytes) {
            throw new InsufficientSpaceException(operation, target, usable, minFreeBytes);
        }
    }

    private static Path firstExistingAncestor(Path target) {
        Path p = target.toAbsolutePath();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        return p;
    }

    /** 磁盘空间不足, 操作被预检拒绝. 自然冒泡到 SnapshotCreator / RestoreFlow 调用层. */
    public static final class InsufficientSpaceException extends IOException {

        private final long usableBytes;
        private final long requiredBytes;

        InsufficientSpaceException(String operation, Path target, long usableBytes, long requiredBytes) {
            super("disk space precheck failed for " + operation + " at " + target
                    + ": usable=" + usableBytes + "B required>=" + requiredBytes + "B");
            this.usableBytes = usableBytes;
            this.requiredBytes = requiredBytes;
        }

        public long usableBytes() {
            return usableBytes;
        }

        public long requiredBytes() {
            return requiredBytes;
        }
    }
}
