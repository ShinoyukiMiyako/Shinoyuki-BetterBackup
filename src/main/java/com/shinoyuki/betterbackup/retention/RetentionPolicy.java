package com.shinoyuki.betterbackup.retention;

import com.shinoyuki.betterbackup.config.BetterBackupConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 滚动保留策略 (DESIGN §5.1).
 *
 * <p>给定按字典序倒序 (= 时间倒序, 最新在前) 的 snapshot id 列表, 挑选哪些
 * 应该保留下来。挑选维度包括 hourly / daily / weekly / monthly 四档, 每档独立
 * 选 N 份, 最终保留集 = 四档选中的并集 (一份 snapshot 同时满足多档时只算一次)。
 *
 * <p>分组键的选择原因:
 * <ul>
 *   <li>daily: 按 UTC 日历日 group, 因为 snapshot id 已经是 UTC, 跨时区也保持稳定语义</li>
 *   <li>weekly: 按 ISO week (year + week-of-year). ISO week 周一为一周开始, 同一日历周
 *       跨年时 ISO week 仍连续 (例如 2026-01-01 周四属于 2025-W53), 避免跨年碎片化</li>
 *   <li>monthly: 按 UTC YearMonth, 跨年自然递增</li>
 *   <li>hourly: 不分组, 直接按列表顺序取最近 N 份。简化版语义忽略小时是否对齐, 实践
 *       中 BetterBackup 默认 2h 一次定时备份, 用户期望 "最近 24 份" 而非 "整点 24 个槽"</li>
 * </ul>
 *
 * <p>同一组内 (例如同一天) 选 "时间最近的 1 份", 因列表已倒序所以遍历到该组时第一个
 * 出现的就是该组最新的, 之后同组的跳过。这避免了对每组维护额外排序结构, O(n) 即可。
 *
 * <p>无效 snapshot id (无法 parse) 直接 IllegalArgumentException 抛出, 由调用方决定
 * 跳过 / 修复 / 隔离。静默跳过会让损坏 manifest 永远滞留 store, 违反 fail-fast 原则。
 */
public final class RetentionPolicy {

    private static final DateTimeFormatter ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private final int hourly;
    private final int daily;
    private final int weekly;
    private final int monthly;

    public RetentionPolicy(int hourly, int daily, int weekly, int monthly) {
        if (hourly < 0 || daily < 0 || weekly < 0 || monthly < 0) {
            throw new IllegalArgumentException(
                    "retention counts must be non-negative: hourly=" + hourly
                            + " daily=" + daily + " weekly=" + weekly + " monthly=" + monthly);
        }
        this.hourly = hourly;
        this.daily = daily;
        this.weekly = weekly;
        this.monthly = monthly;
    }

    /** 工厂: 用 BetterBackupConfig 当前 retention 配置构造 (含 opt-in 主开关)。 */
    public static RetentionPolicy fromConfig() {
        return fromConfig(
                BetterBackupConfig.retentionEnabled(),
                BetterBackupConfig.retentionHourly(),
                BetterBackupConfig.retentionDaily(),
                BetterBackupConfig.retentionWeekly(),
                BetterBackupConfig.retentionMonthly());
    }

    /**
     * 纯逻辑工厂: {@code enabled=false} (retention 未开启, 默认值) 返回全零 policy —— 其
     * {@link #retainsNothing()} 为 true, 淘汰执行器据此保留全部快照。这让升级用户在显式开启前
     * 首跑不会批量、不可逆地淘汰历史快照。opt-in gate 在此收口, 所有消费者 (SnapshotCreator /
     * StoreSizeGuard / 命令 preview) 统一经 {@link #fromConfig()} 生效。
     */
    static RetentionPolicy fromConfig(boolean enabled, int hourly, int daily, int weekly, int monthly) {
        if (!enabled) {
            return new RetentionPolicy(0, 0, 0, 0);
        }
        return new RetentionPolicy(hourly, daily, weekly, monthly);
    }

    /**
     * 四档配额是否全为 0。淘汰执行器据此把"全零"当作"保留策略未启用 / 未配置"处理: 保留全部快照,
     * 不淘汰任何一份。理由: 备份 mod 绝不能把"每档都留 0 份"曲解为"删到只剩门禁兜底的最小集";
     * 全零几乎必然是 config 未加载 (静态字段默认 0) 或用户笔误, 而非"我要删光历史"的真实意图。
     * 单档为 0 (例如只 hourly=0 其余非 0) 不算全零, 仍照常按其余档淘汰 (门禁另行兜底最新一份)。
     */
    public boolean retainsNothing() {
        return hourly == 0 && daily == 0 && weekly == 0 && monthly == 0;
    }

    /**
     * 从 snapshot id 列表挑出该保留的子集。
     *
     * @param snapshotIds 字典序倒序 (最新在前), 格式 "yyyy-MM-dd'T'HH-mm-ss'Z'"
     * @return 该保留的 ID 集合, 顺序未定义
     * @throws IllegalArgumentException 列表中任何 ID 无法 parse 为合法 Instant
     */
    public Set<String> select(List<String> snapshotIds) {
        Objects.requireNonNull(snapshotIds, "snapshotIds");
        Set<String> keep = new LinkedHashSet<>();
        if (snapshotIds.isEmpty()) {
            return keep;
        }

        // hourly: 直接拿前 N 份, 不 group
        int hourlyTake = Math.min(hourly, snapshotIds.size());
        for (int i = 0; i < hourlyTake; i++) {
            keep.add(snapshotIds.get(i));
        }

        // daily / weekly / monthly: group by 对应日期键, 每组取首个 (= 最新, 因列表倒序)
        if (daily > 0) {
            collectByGroup(snapshotIds, keep, daily, RetentionPolicy::dailyKey);
        }
        if (weekly > 0) {
            collectByGroup(snapshotIds, keep, weekly, RetentionPolicy::weeklyKey);
        }
        if (monthly > 0) {
            collectByGroup(snapshotIds, keep, monthly, RetentionPolicy::monthlyKey);
        }
        return keep;
    }

    /**
     * 反义查询: 输入 - select() = 该删除的子集。
     *
     * <p>提取出来作为独立 API 是为了让 GC 调用方语义更直白 (调用 selectForDeletion
     * 而非自己手动算差集), 也便于将来扩展 (例如加上 "永远不删 latest" 的安全网)。
     */
    public Set<String> selectForDeletion(List<String> snapshotIds) {
        Objects.requireNonNull(snapshotIds, "snapshotIds");
        Set<String> keep = select(snapshotIds);
        Set<String> doomed = new HashSet<>();
        for (String id : snapshotIds) {
            if (!keep.contains(id)) {
                doomed.add(id);
            }
        }
        return doomed;
    }

    private static void collectByGroup(List<String> ids,
                                       Set<String> keep,
                                       int limit,
                                       java.util.function.Function<Instant, Object> keyFn) {
        Set<Object> seenKeys = new HashSet<>();
        for (String id : ids) {
            if (seenKeys.size() >= limit) {
                break;
            }
            Instant t = parseId(id);
            Object key = keyFn.apply(t);
            if (seenKeys.add(key)) {
                keep.add(id);
            }
        }
    }

    private static LocalDate dailyKey(Instant t) {
        return t.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * ISO week 键 = (week-based-year, week-of-week-based-year)。注意 weekBasedYear
     * 不等于普通 year — 例如 2026-01-01 (周四) 落在 2025-W53, 用 weekBasedYear=2025
     * 才能正确归到 2025 年最后一周, 用 calendar year 会误归 2026-W?。
     */
    private static String weeklyKey(Instant t) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        int weekYear = z.get(IsoFields.WEEK_BASED_YEAR);
        int week = z.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return weekYear + "-W" + week;
    }

    private static YearMonth monthlyKey(Instant t) {
        return YearMonth.from(t.atZone(ZoneOffset.UTC));
    }

    /**
     * 校验单个 snapshot id 可 parse 为合法 Instant, 否则抛 {@link IllegalArgumentException}。
     *
     * <p>给淘汰执行器 ({@link RetentionPruner}) 做删除前的无条件预检: {@link #select} 只在
     * daily/weekly/monthly 档 (>0 时) 才 parse id, 纯 hourly 配置下不会触碰非法 id, 故 select
     * 抛异常的 fail-fast 依赖于配额档位。删数据前必须与配额无关地拒绝任何非法 id (否则可能删掉
     * 好快照却把非法 id 的那份留下), 独立暴露此校验供调用方对全体 id 逐一预检。
     */
    public static void requireValidId(String id) {
        parseId(id);
    }

    private static Instant parseId(String id) {
        try {
            return Instant.from(ID_FORMAT.parse(id));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid snapshot id: " + id, e);
        }
    }
}
