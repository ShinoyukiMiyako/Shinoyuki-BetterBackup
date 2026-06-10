package com.shinoyuki.betterbackup.baseline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BaselineProgress 的断点续传记录持久化测试. 断言具体业务结果: 记录哪些 region 完成,
 * 跨实例 (模拟重启) load 后能正确续传, complete 标记驱动门禁.
 */
class BaselineProgressTest {

    @Test
    void fresh_progress_has_nothing_done_and_is_not_complete(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        assertFalse(p.isComplete());
        assertEquals(0, p.completedRegionCount());
        assertFalse(p.isRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca"));
    }

    @Test
    void marked_region_is_reported_done_and_survives_reload(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca");
        p.markRegionDone(BaselineProgress.CHANNEL_ENTITIES, "minecraft:the_nether", "r.-1.2.mca");

        assertEquals(2, p.completedRegionCount());
        assertTrue(p.isRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca"));

        // 新实例 (模拟重启) load 同一目录, 必须读回已记录的两条
        BaselineProgress reloaded = new BaselineProgress(storeRoot);
        reloaded.load();
        assertEquals(2, reloaded.completedRegionCount());
        assertTrue(reloaded.isRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca"));
        assertTrue(reloaded.isRegionDone(BaselineProgress.CHANNEL_ENTITIES, "minecraft:the_nether", "r.-1.2.mca"));
        assertFalse(reloaded.isRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.1.0.mca"));
    }

    @Test
    void channel_and_dimension_disambiguate_same_mca_name(@TempDir Path storeRoot) throws IOException {
        // 同名 r.0.0.mca 在 region 与 entities 两通道是两份独立文件, 不能互相误判完成
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca");

        assertTrue(p.isRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca"));
        assertFalse(p.isRegionDone(BaselineProgress.CHANNEL_ENTITIES, "minecraft:overworld", "r.0.0.mca"));
        assertFalse(p.isRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:the_nether", "r.0.0.mca"));
    }

    @Test
    void mark_done_is_idempotent_no_duplicate_lines(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca");
        p.markRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca");
        p.markRegionDone(BaselineProgress.CHANNEL_REGION, "minecraft:overworld", "r.0.0.mca");

        assertEquals(1, p.completedRegionCount());
        // 磁盘上 progress 文件只应有一行 (幂等去重)
        Path progressFile = storeRoot.resolve("baseline").resolve("progress");
        long lines = Files.readAllLines(progressFile).stream().filter(l -> !l.isBlank()).count();
        assertEquals(1, lines, "duplicate markRegionDone must not append duplicate lines");
    }

    @Test
    void mark_complete_sets_flag_and_survives_reload(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        assertFalse(p.isComplete());
        p.markComplete();
        assertTrue(p.isComplete());

        BaselineProgress reloaded = new BaselineProgress(storeRoot);
        reloaded.load();
        assertTrue(reloaded.isComplete(), "complete marker must persist across restart");
    }
}
