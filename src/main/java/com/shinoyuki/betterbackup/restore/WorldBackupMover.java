package com.shinoyuki.betterbackup.restore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * restore 前把当前世界搬进 {@code <worldRoot>.bak-<ts>/} 的整体事务。在线 ({@code RestoreFlow})
 * 与离线 ({@code OfflineRestore}) 两条 restore 路径共用一份实现。
 *
 * <p><b>为什么不整目录 rename worldRoot</b>: 单次 rename 天然原子, 但会把嵌套在 worldRoot 内的
 * dedup store 一并搬走自毁备份 (启动期已对 store 套娃告警)。故只搬 chunk 相关子目录 + 玩家数据 +
 * level.dat 这些明确的世界内容, 逐子目录 atomic rename。
 *
 * <p><b>整体事务 (rollback on failure)</b>: 逐子目录 atomic rename 不是天然的整体事务 —— 若前几个
 * 子目录已搬走、某个后续子目录 move 抛异常 (权限 / 句柄占用), 世界会停在"一半在 .bak、一半在 world"
 * 的拆散态; 重试再用新时间戳建 .bak 会把原世界跨两个 .bak 目录。故这里记录已搬走的 (src,dst),
 * 任一 move 失败即逆序把它们搬回 worldRoot 原位并删空本次 .bak, 再抛出原异常 —— worldRoot 回到
 * 搬动前状态, 重试从干净态起产出单一 .bak, 不把原世界拆散到多处。
 */
public final class WorldBackupMover {

    /** worldRoot 下参与 restore 前搬移的内容 (chunk 相关 + 玩家数据 + level.dat)。嵌套 store 不在内。 */
    private static final List<String> WORLD_SUBPATHS = List.of(
            "region", "entities", "data", "playerdata", "stats", "advancements",
            "poi", "DIM-1", "DIM1", "dimensions", "level.dat", "level.dat_old");

    /** 单个子路径的搬移操作。生产恒为 atomic rename; 测试注入模拟中途失败以验证 rollback。 */
    @FunctionalInterface
    interface DirMover {
        void move(Path src, Path dst) throws IOException;
    }

    private DirMover mover = (src, dst) -> Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);

    /** 测试注入点: 替换搬移操作以模拟中途 IO 失败。仅测试调用。 */
    void setMoverForTest(DirMover mover) {
        this.mover = mover;
    }

    /**
     * 把 worldRoot 内的世界内容作为整体事务搬到 {@code <worldRoot>.bak-<ts>/} 并返回该目录。
     * 任一子目录搬移失败即逆序 rollback 到搬动前状态并删空 .bak, 再抛出原异常。
     */
    public Path moveToBackup(Path worldRoot) throws IOException {
        String backupName = worldRoot.getFileName() + ".bak-" + System.currentTimeMillis();
        Path backupDir = worldRoot.resolveSibling(backupName);
        Files.createDirectories(backupDir);

        List<Path[]> moved = new ArrayList<>();
        try {
            for (String sub : WORLD_SUBPATHS) {
                Path src = worldRoot.resolve(sub);
                if (!Files.exists(src)) {
                    continue;
                }
                Path dst = backupDir.resolve(sub);
                Files.createDirectories(dst.getParent());
                mover.move(src, dst);
                moved.add(new Path[]{src, dst});
            }
        } catch (IOException e) {
            rollback(moved, backupDir, e);
            throw e;
        }
        return backupDir;
    }

    /** 逆序把已搬走的子目录搬回 worldRoot 原位并删空 .bak。搬回 / 删除自身失败挂 suppressed 保留现场。 */
    private static void rollback(List<Path[]> moved, Path backupDir, IOException cause) {
        for (int i = moved.size() - 1; i >= 0; i--) {
            Path src = moved.get(i)[0];
            Path dst = moved.get(i)[1];
            try {
                Files.move(dst, src, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException rollbackEx) {
                cause.addSuppressed(rollbackEx);
            }
        }
        try {
            Files.deleteIfExists(backupDir);
        } catch (IOException deleteEx) {
            cause.addSuppressed(deleteEx);
        }
    }
}
