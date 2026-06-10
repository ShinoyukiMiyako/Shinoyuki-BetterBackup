# Shinoyuki-BetterBackup v0.2 计划

| 字段 | 值 |
|---|---|
| 状态 | 已批准 (2026-06-10), 等 v0.1.0 发版后开工 |
| 决策记录 | NBT dedup 走方案 B (引用回退); 保留策略全保 (hourly 336, 14 天); manifest 差分因全保入选 |
| 估期 | 5-6 周单人 |

## 〇. v0.1.0 发版前置 (本计划开工的前提)

1. retention.hourly 上限 168 -> 2000 (已修, 见 ConfigSpec)
2. baseline 全量扫描跑完 + 下一份快照 baselineComplete=true
3. 数据校验: 离线 CLI verify 对最新快照全量校验通过
4. BAS 0.10.0 正式发版 (PipelineStateListener + 诊断间隔默认值) -> BetterBackup v0.1.0 发版
5. 生产配置定稿: intervalMinutes=60, retention.hourly=336, daily/weekly/monthly=0

## 一. 主线一: NBT 感知 dedup 第二层 (方案 B, 约 2 周)

动机 (生产实测): 每小时快照的增量约九成是"已加载 chunk 仅 LastUpdate/InhabitedTime
变化"的伪变更 (25-75MB/活跃小时)。第二层等价判定把它砍到 3-8MB, 14 天全保窗口的
增量占用从 +5-30G 缩到 +1-3G。

设计 (方案 B, 引用回退):

1. raw-bytes 第一层不动: 字节保真仍是 store 与恢复的基础语义
2. worker 在 hash 入库前增加等价判定: 解压 slot -> 剥 LastUpdate/InhabitedTime ->
   递归 key 排序 -> canonical 序列化 -> 第二 hash (canonicalHash)
3. store 维护 canonicalHash -> rawHash 的映射索引 (新文件, 可由 fsck 重建);
   命中时不入库新字节, manifest 条目直接指向已存对象的 rawHash
4. 恢复语义: 命中 dedup 的 chunk 写回的是基准对象的原始字节, 其 LastUpdate/
   InhabitedTime 回退到基准点。接受理由: LastUpdate 回退 vanilla 容忍;
   InhabitedTime 回退数小时 = 局部难度漂移, 玩家不可感知。不做字段回注,
   不做重压缩, round-trip 字节保真性质对"存进 store 的对象"依然成立
5. 剥离字段表沿用 PLAN.md 旧版 §2.1/2.2 的 canonical 设计 (含版本化: 字段表
   变更须升 canonical 版本号, 不同版本映射不混用)
6. 性能预算: 解压 + 排序序列化 + 二次 hash 每 chunk 约百微秒级, worker 线程
   消化, 主线程零变化; 提供 storage.nbtAwareDedup 开关 (默认开, 出问题可退)

测试: canonical 等价判定 (乱序 HashMap 随机化必须同 hash, 真实方块变化必须不同
hash, 删排序逻辑必挂); 引用回退恢复语义 (恢复后 chunk 除两个时间戳字段外与
快照时逐字段相等); 映射索引损坏后 fsck 重建。

## 二. 主线二: 部分恢复离线版 (约 1 周)

动机: 市场调研中被渴求六年的能力 (AromaBackup 单 chunk 恢复是其最受欢迎特性,
Textile #10), chunk 级索引的差异化兑现。

范围 (离线 CLI, 停服执行):

1. restore --dim <id> 单维度恢复
2. restore --dim <id> --chunk <x>,<z> 单 chunk
3. restore --dim <id> --center <x>,<z> --radius <r> 矩形区域
4. 部分恢复不动 playerdata/files 段 (只回滚地形), 文档明示
5. 在线版 (要求 chunk unloaded + 通知 BAS 状态机) 推 v0.3

测试: 部分恢复后目标 slot 字节与 manifest 一致、非目标 slot 与恢复前一致。

## 三. 主线三: manifest 差分 (约 1.5 周, 因全保 336 入选)

动机: 336 份 x ~30MB 全量 manifest = ~10G 纯元数据; 差分后每份 <1MB, 总量 <1.5G。

设计:

1. manifest 链 = 基准 full + 后续 delta (只记相对上一份的变更条目);
   每 24 份强制落一个新 full, 链长上限 24
2. 事故防御 (Simple Backups #39 删链头毁全链的教训): GC 与保留策略把
   "被任何 delta 引用的 full" 视为不可删; delta 解析失败时 fsck 能从最近 full
   重放; 删除快照时若其为链中 full 且仍有后继 delta, 自动先物化后继为新 full
3. 旧格式 full manifest 继续可读 (向后兼容), 新写入走链式

测试: 链重放 round-trip; 删链头防御 (删除请求触发物化而非破链); fsck 修复。

## 四. 清账 (约 1 周)

1. baseline 批量读: 按 region 文件持句柄整读, 吞吐目标 10x (实测单线程逐 slot
   开句柄卡在 1.2 万 chunk/s)
2. worker 侧磁盘预检 (入库路径低水位拒写 + 标 incomplete, 补 Phase A 的窗口)
3. status 的 dirty 计数分离 baseline 与真实变更两个数字
4. CLI gc 子命令 (--prune-to <days>): NAS 长尾归档副本的离线修剪
5. type-3 (未压缩) chunk 撕裂读补 NBT 结构校验
6. 非主世界 poi 纳入 files 段
7. AFTER_AUTOSAVE + dirtyChunkThreshold 触发模式启用与测试 (代码已有骨架)
8. BaselineProgress 跨线程计数改 ConcurrentHashMap.newKeySet

## 五. 顺序

```
v0.1.0 发版 -> 生产泡一周 + 48h 数据校准
  -> 主线一 (NBT dedup B) -> 清账 1/2/3 -> 主线三 (manifest 差分)
  -> 主线二 (部分恢复) -> 清账其余 -> v0.2.0 发版
```

主线一最先: 它直接决定全保 336 的磁盘账, 越早上线越早受益; 主线三依赖
全保配置真实跑起来后的 manifest 体积实测数据。

## 六. 不在 v0.2

在线单 chunk 恢复 (v0.3); NeoForge 1.21.1 移植 (独立 track, 先做 1.21.x save
路径侦察); zstd / blake3 (无生产需求佐证, 搁置); Web UI / 远程上传 (永不)。
