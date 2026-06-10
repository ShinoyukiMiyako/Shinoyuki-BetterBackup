package com.shinoyuki.betterbackup.baseline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BaselineProgress 两阶段断点续传记录测试. 断言具体业务结果: 哪些 region 处于 scanned /
 * committed, 跨实例 (模拟重启) load 后状态正确, 晋升只动捕获集, complete 标记只在全部
 * committed 且扫描收尾时写, 旧格式行迁移按 scanned 读入.
 *
 * <p>判定标准: 删掉"续传只跳过 committed"或"晋升只动捕获集"或"全部 committed 才写
 * complete"任一核心逻辑, 对应断言必挂。
 */
class BaselineProgressTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void fresh_progress_has_nothing_done_and_is_not_complete(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        assertFalse(p.isComplete());
        assertEquals(0, p.completedRegionCount());
        assertEquals(0, p.committedRegionCount());
        assertFalse(p.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"));
    }

    @Test
    void scanned_region_is_not_committed_until_promoted(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");

        // scanned-未提交: 计入 completedRegionCount, 但 committedRegionCount=0, isRegionCommitted=false.
        // 续传规则的核心: scanned 不算 committed, 必须重扫.
        assertEquals(1, p.completedRegionCount());
        assertEquals(0, p.committedRegionCount());
        assertFalse(p.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"),
                "scanned region must NOT count as committed (resume rescans it)");

        String key = BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.0.0.mca";
        p.promoteScannedToCommitted(Set.of(key));
        assertEquals(1, p.committedRegionCount());
        assertTrue(p.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"),
                "after promotion the region is committed (resume skips it)");
    }

    @Test
    void state_survives_reload_with_correct_committed_distinction(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");
        p.markRegionScanned(BaselineProgress.CHANNEL_ENTITIES, "minecraft:the_nether", "r.-1.2.mca");
        // 只晋升 region 通道的那条
        String committedKey = BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.0.0.mca";
        p.promoteScannedToCommitted(Set.of(committedKey));

        BaselineProgress reloaded = new BaselineProgress(storeRoot);
        reloaded.load();
        assertEquals(2, reloaded.completedRegionCount());
        assertEquals(1, reloaded.committedRegionCount(), "exactly the promoted region is committed after reload");
        assertTrue(reloaded.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"));
        assertFalse(reloaded.isRegionCommitted(BaselineProgress.CHANNEL_ENTITIES, "minecraft:the_nether", "r.-1.2.mca"),
                "the still-scanned region must reload as not-committed");
    }

    @Test
    void channel_and_dimension_disambiguate_same_mca_name(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");
        p.promoteScannedToCommitted(Set.of(BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.0.0.mca"));

        assertTrue(p.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"));
        assertFalse(p.isRegionCommitted(BaselineProgress.CHANNEL_ENTITIES, DIM, "r.0.0.mca"));
        assertFalse(p.isRegionCommitted(BaselineProgress.CHANNEL_REGION, "minecraft:the_nether", "r.0.0.mca"));
    }

    @Test
    void mark_scanned_is_idempotent_no_duplicate_lines(@TempDir Path storeRoot) throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");

        assertEquals(1, p.completedRegionCount());
        Path progressFile = storeRoot.resolve("baseline").resolve("progress");
        long lines = Files.readAllLines(progressFile).stream().filter(l -> !l.isBlank()).count();
        assertEquals(1, lines, "duplicate markRegionScanned must not append duplicate lines");
    }

    @Test
    void promote_only_affects_the_captured_snapshot(@TempDir Path storeRoot) throws IOException {
        // 两个 scanned region. 只把其中一个传给晋升, 另一个必须留在 scanned.
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.1.0.mca");

        String onlyOne = BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.0.0.mca";
        p.promoteScannedToCommitted(Set.of(onlyOne));

        assertTrue(p.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"));
        assertFalse(p.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.1.0.mca"),
                "a region not in the captured snapshot must stay scanned, not be promoted");
        assertEquals(1, p.committedRegionCount());
    }

    @Test
    void complete_marker_only_written_when_all_committed_and_scan_finished(@TempDir Path storeRoot)
            throws IOException {
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca");

        // 还有 scanned-未提交 -> 即便 scanFinished=true 也不写标记
        assertFalse(p.markCompleteIfAllCommitted(true), "must not complete while a region is still scanned");
        assertFalse(p.isComplete());

        // 晋升后全部 committed, 但扫描未收尾 -> 仍不写 (后续 pass 可能扫出新 region)
        p.promoteScannedToCommitted(Set.of(BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.0.0.mca"));
        assertFalse(p.markCompleteIfAllCommitted(false), "must not complete before scan is finished");
        assertFalse(p.isComplete());

        // 全部 committed 且扫描收尾 -> 写标记
        assertTrue(p.markCompleteIfAllCommitted(true), "all committed + scan finished => complete marker written");
        assertTrue(p.isComplete());

        BaselineProgress reloaded = new BaselineProgress(storeRoot);
        reloaded.load();
        assertTrue(reloaded.isComplete(), "complete marker must persist across restart");
    }

    @Test
    void legacy_three_column_lines_load_as_scanned_for_self_healing(@TempDir Path storeRoot) throws IOException {
        // 旧格式 progress 文件: 3 列, 无 status 列. 必须按 scanned 读入 (重扫一次自动治愈历史窗口).
        Path baselineDir = storeRoot.resolve("baseline");
        Files.createDirectories(baselineDir);
        Path progressFile = baselineDir.resolve("progress");
        String legacy = BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.0.0.mca" + System.lineSeparator()
                + BaselineProgress.CHANNEL_ENTITIES + "\t" + DIM + "\t" + "r.5.5.mca" + System.lineSeparator();
        Files.write(progressFile, legacy.getBytes(StandardCharsets.UTF_8));

        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        assertEquals(2, p.completedRegionCount());
        assertEquals(0, p.committedRegionCount(), "legacy lines without status must load as scanned, not committed");
        assertFalse(p.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.0.0.mca"),
                "legacy region must be rescanned (treated as scanned, not committed)");
    }

    @Test
    void committed_line_after_scanned_line_wins_on_reload(@TempDir Path storeRoot) throws IOException {
        // append 语义: 同一 region 先写 scanned 行后写 committed 行. load 合并取较高状态.
        BaselineProgress p = new BaselineProgress(storeRoot);
        p.load();
        p.markRegionScanned(BaselineProgress.CHANNEL_REGION, DIM, "r.2.2.mca");
        p.promoteScannedToCommitted(Set.of(BaselineProgress.CHANNEL_REGION + "\t" + DIM + "\t" + "r.2.2.mca"));

        // 磁盘上应有两行 (scanned 一行 + committed 一行)
        Path progressFile = storeRoot.resolve("baseline").resolve("progress");
        List<String> lines = Files.readAllLines(progressFile);
        long nonBlank = lines.stream().filter(l -> !l.isBlank()).count();
        assertEquals(2, nonBlank, "scanned then committed appends two lines for the same region");

        BaselineProgress reloaded = new BaselineProgress(storeRoot);
        reloaded.load();
        assertEquals(1, reloaded.completedRegionCount(), "the two lines collapse to one region");
        assertTrue(reloaded.isRegionCommitted(BaselineProgress.CHANNEL_REGION, DIM, "r.2.2.mca"),
                "committed must win over the earlier scanned line (promotion never regresses)");
    }
}
