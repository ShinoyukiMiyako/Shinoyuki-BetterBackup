package com.shinoyuki.betterbackup.retention;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetentionPolicy 单元测试。
 *
 * <p>所有 snapshot id hardcoded, 严格断言保留集 = 期望具体集合, 不用 size / not-null
 * 等弱校验。这样删掉算法核心逻辑测试必挂, 保证测试有真正的捕错能力。
 */
class RetentionPolicyTest {

    @Test
    void empty_list_returns_empty_set() {
        RetentionPolicy policy = new RetentionPolicy(24, 7, 4, 12);
        Set<String> kept = policy.select(Collections.emptyList());
        assertEquals(Collections.emptySet(), kept);
        Set<String> doomed = policy.selectForDeletion(Collections.emptyList());
        assertEquals(Collections.emptySet(), doomed);
    }

    @Test
    void hourly_only_keeps_first_n() {
        // hourly=24, 其它 0; 24 个连续小时备份全部入选
        List<String> ids = Arrays.asList(
                "2026-05-10T23-00-00Z", "2026-05-10T22-00-00Z", "2026-05-10T21-00-00Z",
                "2026-05-10T20-00-00Z", "2026-05-10T19-00-00Z", "2026-05-10T18-00-00Z",
                "2026-05-10T17-00-00Z", "2026-05-10T16-00-00Z", "2026-05-10T15-00-00Z",
                "2026-05-10T14-00-00Z", "2026-05-10T13-00-00Z", "2026-05-10T12-00-00Z",
                "2026-05-10T11-00-00Z", "2026-05-10T10-00-00Z", "2026-05-10T09-00-00Z",
                "2026-05-10T08-00-00Z", "2026-05-10T07-00-00Z", "2026-05-10T06-00-00Z",
                "2026-05-10T05-00-00Z", "2026-05-10T04-00-00Z", "2026-05-10T03-00-00Z",
                "2026-05-10T02-00-00Z", "2026-05-10T01-00-00Z", "2026-05-10T00-00-00Z");
        RetentionPolicy policy = new RetentionPolicy(24, 0, 0, 0);
        Set<String> kept = policy.select(ids);
        assertEquals(new HashSet<>(ids), kept);
    }

    @Test
    void hourly_truncates_when_list_longer_than_quota() {
        // hourly=3, 5 个 snapshot, 仅前 3 入选 (全在同一天但 hourly 不分组)
        List<String> ids = Arrays.asList(
                "2026-05-10T23-00-00Z", "2026-05-10T22-00-00Z", "2026-05-10T21-00-00Z",
                "2026-05-10T20-00-00Z", "2026-05-10T19-00-00Z");
        RetentionPolicy policy = new RetentionPolicy(3, 0, 0, 0);
        Set<String> kept = policy.select(ids);
        assertEquals(new HashSet<>(Arrays.asList(
                "2026-05-10T23-00-00Z", "2026-05-10T22-00-00Z", "2026-05-10T21-00-00Z")), kept);
    }

    @Test
    void daily_only_picks_newest_per_day_for_n_days() {
        // hourly=0, daily=5; 10 个跨 10 天 snapshot, 仅最近 5 天入选
        List<String> ids = Arrays.asList(
                "2026-05-10T12-00-00Z", "2026-05-09T12-00-00Z", "2026-05-08T12-00-00Z",
                "2026-05-07T12-00-00Z", "2026-05-06T12-00-00Z", "2026-05-05T12-00-00Z",
                "2026-05-04T12-00-00Z", "2026-05-03T12-00-00Z", "2026-05-02T12-00-00Z",
                "2026-05-01T12-00-00Z");
        RetentionPolicy policy = new RetentionPolicy(0, 5, 0, 0);
        Set<String> kept = policy.select(ids);
        assertEquals(new HashSet<>(Arrays.asList(
                "2026-05-10T12-00-00Z", "2026-05-09T12-00-00Z", "2026-05-08T12-00-00Z",
                "2026-05-07T12-00-00Z", "2026-05-06T12-00-00Z")), kept);
    }

    @Test
    void daily_picks_newest_within_day_when_multiple_per_day() {
        // 同一天有多份, 每天保留 "时间最近的 / 字典序最大的" 1 份
        List<String> ids = Arrays.asList(
                "2026-05-10T20-00-00Z", "2026-05-10T10-00-00Z", "2026-05-10T05-00-00Z",
                "2026-05-09T18-00-00Z", "2026-05-09T08-00-00Z");
        RetentionPolicy policy = new RetentionPolicy(0, 2, 0, 0);
        Set<String> kept = policy.select(ids);
        // 5/10 当天三份, 取最新 20:00; 5/9 当天两份, 取最新 18:00
        assertEquals(new HashSet<>(Arrays.asList(
                "2026-05-10T20-00-00Z", "2026-05-09T18-00-00Z")), kept);
    }

    @Test
    void single_snapshot_falls_into_all_categories_counted_once() {
        // 1 个 snapshot, 4 档配额都满足; 结果集仅含该一份, 不重复
        List<String> ids = Collections.singletonList("2026-05-10T12-00-00Z");
        RetentionPolicy policy = new RetentionPolicy(24, 7, 4, 12);
        Set<String> kept = policy.select(ids);
        assertEquals(new HashSet<>(ids), kept);
    }

    @Test
    void hourly_and_daily_union_no_double_quota_consumption() {
        // hourly=2, daily=3, 5 份 snapshot 跨 4 天; 验证 union 大小 <=5 而非 2+3=5 严格
        // 5/10 当天 2 份 (20:00 / 10:00), 5/9 / 5/8 / 5/7 各 1 份 = 4 天
        // hourly: 取前 2 = {5/10@20:00, 5/10@10:00}
        // daily: 取最近 3 个不同日子 = {5/10 (newest 20:00), 5/9, 5/8}
        // union: {5/10@20:00, 5/10@10:00, 5/9@15:00, 5/8@12:00} = 4 项
        List<String> ids = Arrays.asList(
                "2026-05-10T20-00-00Z", "2026-05-10T10-00-00Z",
                "2026-05-09T15-00-00Z", "2026-05-08T12-00-00Z", "2026-05-07T09-00-00Z");
        RetentionPolicy policy = new RetentionPolicy(2, 3, 0, 0);
        Set<String> kept = policy.select(ids);
        assertEquals(new HashSet<>(Arrays.asList(
                "2026-05-10T20-00-00Z", "2026-05-10T10-00-00Z",
                "2026-05-09T15-00-00Z", "2026-05-08T12-00-00Z")), kept);
        assertTrue(kept.size() <= 5, "union 不应超 hourly+daily 上限");
    }

    @Test
    void month_boundary_jan_to_feb() {
        // 跨月: 1/31 跟 2/1 必然属于不同 monthly 组
        // monthly=2: 取最近 2 个不同月 = 2026-02 + 2026-01 = 全部
        // monthly=1: 取最近 1 个月 = 2026-02 (newest = 2/1 12:00)
        List<String> ids = Arrays.asList(
                "2026-02-01T12-00-00Z", "2026-01-31T20-00-00Z", "2026-01-31T08-00-00Z",
                "2026-01-15T12-00-00Z", "2026-01-01T00-00-00Z");
        RetentionPolicy m2 = new RetentionPolicy(0, 0, 0, 2);
        // 2026-02 组: 仅 2/1 一份, 入选; 2026-01 组: 多份 newest = 1/31 20:00
        assertEquals(new HashSet<>(Arrays.asList(
                "2026-02-01T12-00-00Z", "2026-01-31T20-00-00Z")), m2.select(ids));

        RetentionPolicy m1 = new RetentionPolicy(0, 0, 0, 1);
        assertEquals(Collections.singleton("2026-02-01T12-00-00Z"), m1.select(ids));
    }

    @Test
    void iso_week_boundary_sunday_vs_monday() {
        // 2026-01-01 (Thu) 属于 2026-W01? 实际 ISO: 2026-01-01 Thu 在 2026-W01,
        // 但 2025-12-29 Mon 起算的 W01 横跨 2025/2026, 2026-01-04 Sun 是 2026-W01 末尾,
        // 2026-01-05 Mon 起为 2026-W02. 用 1/04 vs 1/05 验证 ISO 周分界。
        List<String> ids = Arrays.asList(
                "2026-01-05T08-00-00Z",   // Mon, 2026-W02
                "2026-01-04T20-00-00Z",   // Sun, 2026-W01
                "2026-01-04T08-00-00Z",   // Sun, 2026-W01 (newer than below 早 12 小时)
                "2025-12-30T12-00-00Z");  // Tue, 2025-W01 (因 2025-01-01 Wed 起 W01 = Mon Dec 30 2024 起,
                                          // 而 2025-12-29 Mon 起为 2026-W01, 故 2025-12-30 落在 2026-W01)
        // weekly=2: 取最近 2 个不同周
        //  group 2026-W02 newest = 1/05@08
        //  group 2026-W01 newest = 1/04@20 (此组横跨 2025-12-29..2026-01-04)
        // 1/04 当天有两份, 仅 newer 那个入选
        RetentionPolicy w2 = new RetentionPolicy(0, 0, 2, 0);
        assertEquals(new HashSet<>(Arrays.asList(
                "2026-01-05T08-00-00Z", "2026-01-04T20-00-00Z")), w2.select(ids));
    }

    @Test
    void select_for_deletion_is_input_minus_select() {
        // selectForDeletion 应严格等于 input - select
        List<String> ids = Arrays.asList(
                "2026-05-10T20-00-00Z", "2026-05-10T10-00-00Z",
                "2026-05-09T15-00-00Z", "2026-05-08T12-00-00Z",
                "2026-05-07T09-00-00Z", "2026-05-06T06-00-00Z");
        RetentionPolicy policy = new RetentionPolicy(2, 2, 0, 0);
        Set<String> kept = policy.select(ids);
        Set<String> doomed = policy.selectForDeletion(ids);

        Set<String> expectedKept = new HashSet<>(Arrays.asList(
                "2026-05-10T20-00-00Z",  // hourly#1
                "2026-05-10T10-00-00Z",  // hourly#2
                "2026-05-09T15-00-00Z"));// daily 第二个不同日 (5/10 已在 kept)
        Set<String> expectedDoomed = new HashSet<>(Arrays.asList(
                "2026-05-08T12-00-00Z", "2026-05-07T09-00-00Z", "2026-05-06T06-00-00Z"));
        assertEquals(expectedKept, kept);
        assertEquals(expectedDoomed, doomed);

        Set<String> union = new HashSet<>(kept);
        union.addAll(doomed);
        assertEquals(new HashSet<>(ids), union, "kept + doomed 应覆盖整个输入");
    }

    @Test
    void invalid_id_throws_illegal_argument() {
        // 损坏 manifest 文件名 fail-fast, 不静默跳过
        List<String> ids = Arrays.asList("2026-05-10T20-00-00Z", "not-a-snapshot-id");
        RetentionPolicy policy = new RetentionPolicy(0, 5, 0, 0);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> policy.select(ids));
        assertTrue(ex.getMessage().contains("not-a-snapshot-id"),
                "异常消息应包含 offending id: " + ex.getMessage());
    }

    @Test
    void negative_count_rejected_by_constructor() {
        // 防御 misconfig: 负数无意义, 直接拒绝
        assertThrows(IllegalArgumentException.class,
                () -> new RetentionPolicy(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RetentionPolicy(0, 0, 0, -5));
    }
}
