package com.shinoyuki.betterbackup.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * baseline 取值范围的守卫. scanChunksPerSecond 的上限是安全上限: 限速器只能压低吞吐,
 * 配得比磁盘真实单 slot 服务速率还高时它一次都不会 sleep, 等同不限速 —— 那段取值必须够不到.
 *
 * <p>判定标准: 把上限放回 100000, 第一条断言 (5000 被 clamp 成 2000) 必挂.
 */
class ConfigSpecRangeTest {

    private static CommentedConfig corrected(String key, int value) {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set(key, value);
        ConfigSpec.SPEC.correct(config);
        return config;
    }

    @Test
    void scan_rate_above_ceiling_is_clamped_to_the_ceiling() {
        assertEquals(2_000, corrected("baseline.scanChunksPerSecond", 5_000)
                .getInt("baseline.scanChunksPerSecond"));
    }

    @Test
    void scan_rate_inside_range_is_left_alone() {
        assertEquals(800, corrected("baseline.scanChunksPerSecond", 800)
                .getInt("baseline.scanChunksPerSecond"));
    }

    @Test
    void dirty_high_water_mark_defaults_and_clamps_at_its_floor() {
        // 缺键时取默认值.
        CommentedConfig empty = CommentedConfig.inMemory();
        ConfigSpec.SPEC.correct(empty);
        assertEquals(500_000, empty.getInt("baseline.dirtyHighWaterMark"));
        // 下界不提供"关掉背压"的语义: 配得再小也会被抬回 10000.
        assertEquals(10_000, corrected("baseline.dirtyHighWaterMark", 500)
                .getInt("baseline.dirtyHighWaterMark"));
    }
}
