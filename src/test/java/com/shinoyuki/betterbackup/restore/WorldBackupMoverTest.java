package com.shinoyuki.betterbackup.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldBackupMover 整体事务契约: 逐子目录 atomic rename 中途失败必须 rollback 到搬动前状态,
 * 不把原世界拆散到 .bak 与 world 两处。
 *
 * <p>判定标准: 删掉 rollback 逻辑, {@code mid_move_failure_rolls_back} 必挂 (已搬走的子目录
 * 留在 .bak 里、worldRoot 缺子目录, 断言必挂)。
 */
class WorldBackupMoverTest {

    private static void writeDirWithFile(Path dir, String fileName, byte[] content) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve(fileName), content);
    }

    private static Path bakDir(Path worldRoot) throws IOException {
        try (var s = Files.list(worldRoot.getParent())) {
            return s.filter(p -> p.getFileName().toString().startsWith(worldRoot.getFileName() + ".bak-"))
                    .findFirst().orElse(null);
        }
    }

    @Test
    void happy_path_moves_world_content_into_a_single_bak_dir(@TempDir Path base) throws IOException {
        Path worldRoot = base.resolve("world");
        byte[] regionBytes = "region-bytes".getBytes();
        writeDirWithFile(worldRoot.resolve("region"), "r.0.0.mca", regionBytes);
        writeDirWithFile(worldRoot.resolve("entities"), "r.0.0.mca", "ent".getBytes());
        Files.write(worldRoot.resolve("level.dat"), "leveldat".getBytes());

        Path backupDir = new WorldBackupMover().moveToBackup(worldRoot);

        // 世界内容整体搬走: worldRoot 下不再有这些子路径, backupDir 里有且字节保持.
        assertFalse(Files.exists(worldRoot.resolve("region")), "region moved out of worldRoot");
        assertFalse(Files.exists(worldRoot.resolve("entities")));
        assertFalse(Files.exists(worldRoot.resolve("level.dat")));
        assertArrayEquals(regionBytes, Files.readAllBytes(backupDir.resolve("region").resolve("r.0.0.mca")),
                "region bytes preserved in the .bak dir");
        assertTrue(Files.exists(backupDir.resolve("entities").resolve("r.0.0.mca")));
        assertArrayEquals("leveldat".getBytes(), Files.readAllBytes(backupDir.resolve("level.dat")));
    }

    @Test
    void mid_move_failure_rolls_back_to_pre_move_state_leaving_no_bak(@TempDir Path base) throws IOException {
        Path worldRoot = base.resolve("world");
        byte[] regionBytes = "region-bytes".getBytes();
        byte[] entityBytes = "entity-bytes".getBytes();
        byte[] dataBytes = "data-bytes".getBytes();
        // WORLD_SUBPATHS 顺序: region(1) -> entities(2) -> data(3) -> ...; 让第 3 个搬移失败.
        writeDirWithFile(worldRoot.resolve("region"), "r.0.0.mca", regionBytes);
        writeDirWithFile(worldRoot.resolve("entities"), "r.0.0.mca", entityBytes);
        writeDirWithFile(worldRoot.resolve("data"), "raids.dat", dataBytes);

        WorldBackupMover mover = new WorldBackupMover();
        AtomicInteger moves = new AtomicInteger();
        mover.setMoverForTest((src, dst) -> {
            if (moves.incrementAndGet() == 3) {
                throw new IOException("simulated move failure on the 3rd subdir");
            }
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        });

        assertThrows(IOException.class, () -> mover.moveToBackup(worldRoot));

        // rollback: 已搬走的 region/entities 必须搬回 worldRoot, data 从未动; 三者都在原位且字节完好.
        assertArrayEquals(regionBytes, Files.readAllBytes(worldRoot.resolve("region").resolve("r.0.0.mca")),
                "region rolled back into worldRoot intact");
        assertArrayEquals(entityBytes, Files.readAllBytes(worldRoot.resolve("entities").resolve("r.0.0.mca")),
                "entities rolled back into worldRoot intact");
        assertArrayEquals(dataBytes, Files.readAllBytes(worldRoot.resolve("data").resolve("raids.dat")),
                "data (the failing subdir) never left worldRoot");
        // 本次失败的 .bak 目录已删空, 不遗留半套世界.
        assertEquals(null, bakDir(worldRoot), "the failed .bak dir must be removed, not left with a partial world");
    }
}
