# Shinoyuki-BetterBackup v0.1 收尾计划

| 字段 | 值 |
|---|---|
| 状态 | 计划 (2026-06-10), 基于对当前 main (Phase 5 已完成) 的实现审计 |
| 取代 | 本文件上一版 (greenfield 13 周计划, 写于未察觉开发已进行时, 见 git 历史 4e4002a) |
| 评审基础 | 三轮评审 (BAS 代码核对 / NBT 与 dedup 技术调研 / 33 个备份方案市场调研) + 本次实现审计 |
| 估期 | 约 5 周至 v0.1.0 发布门槛 |

## 一. 实现现状审计 (2026-06-10)

已实现 (Phase 0-5, 32 commits): BAS 三通道 listener 标 dirty (CurrentSnapshotState) ->
BackupWorker 从 region 文件读回 chunk slot raw bytes -> xxh128 -> ChunkStore (二级分桶,
atomic put, 并发 race 已修) -> SnapshotCreator (drain + level.dat + manifest overlay,
atomic rename) -> IntervalScheduler / 手动命令 -> RetentionPolicy + StoreGc (全量 + 增量)
-> RestoreFlow (PendingRestoreFlag 停服恢复 + RegionFileSlotWriter) -> DiagnosticLogger +
PrometheusExporter -> 用户文档。

### 1.1 raw-bytes 架构与评审 P0 的关系

实现没有走"canonical NBT 序列化"路线, 而是 hash 磁盘上已落盘的 chunk slot 原始字节
(listener 只标 dirty, worker 读回)。这个选择**同时解决了评审 P0-1 并消化了 P0-2**:

- HashMap 序列化非确定性 (原 P0-1): 不存在了。hash 对象是 vanilla 写盘的字节,
  对同一磁盘状态天然确定; restore 是字节级保真回写, 无需回注任何字段
- LastUpdate 杀 dedup (原 P0-2): 有意识接受。已加载 chunk 每次 save 产生新 hash,
  由 StoreGc.gcIncremental 在每次 snapshot 后清理中间版本孤儿, 防 store 膨胀。
  dedup 收益全部来自未加载 chunk -- 与调研结论一致 (成熟大服 95%+ chunk 不加载,
  增量天然小; 小服收益低, 文档须分档披露)
- 额外收益: 无 tag 生命周期问题, 无 BAS 线程上的拷贝/序列化开销, store 内容是
  vanilla zlib 字节无需二次压缩

上一版计划中 canonical NBT / 字段剥离回注的全部处方**作废**, 降级为 v0.2 dedup 率
优化候选 (见 §5)。xxh128 替代 sha256 同样保持: 非对抗场景下碰撞概率可忽略, 速度优势明显。

### 1.2 仍然成立的缺口

逐项核对源码确认 (非推测):

| # | 缺口 | 证据 | 后果 | 定级 |
|---|---|---|---|---|
| 1 | baseline 全量扫描缺失 | SnapshotCreator 只 drain dirtyMap + level.dat + overlay 上一份 manifest, 全库无 baseline/全量扫描代码 | 装 mod 后未被加载过的 chunk 永远不进 manifest; 早期快照 restore = 丢失大部分世界 (静默数据丢失) | P0, 发布阻断 |
| 2 | playerdata / stats / advancements / poi 不入快照 | RestoreFlow 注释明示"不动 playerdata / stats / advancements"; SnapshotCreator 仅采 level.dat | 回滚后玩家背包与世界不同步 = 刷物品/丢物品 (FTB Backups 2 #95 同类事故) | P0, 发布阻断 |
| 3 | 离线恢复 CLI 缺失 | 全库无 main 入口 | 服务端起不来时自定义 store 格式 = 数据绑架 (市场调研最大教训) | P0, 发布阻断 |
| 4 | fsck / 索引重建缺失 | 无 fsck; manifest 为单点 (store 本身自描述: 文件名 = hash, 可重建) | manifest 损坏即快照不可用 (QuickBackupMulti #51 同类) | P1 |
| 5 | BAS degraded 感知缺失 | BetterBackup 只有自身启动失败的 degraded; BAS 的 SnapshotPipeline.degraded 是内部单向闩锁且无 API 暴露 | BAS 降级 (worker 死亡) 后 listener 停 fire, BetterBackup 静默失明仍产出"成功"快照 | P1, 需 BAS 侧 API |

### 1.3 待核实项 (Phase A 审计完成)

以下评审条目未逐一核对实现, Phase A 补查, 缺则在对应 Phase 补齐:
恢复/备份两侧磁盘预检 (Advanced Backups #128); GC 三纪律 (hash 键引用计数 /
永不删最新 / 先写后删) 在 StoreGc 的落实程度; 快照失败可见性 (incomplete 标记是否
对 status 可见); store 套娃防护 (store 在 world 内时的检测)。

## 二. 剩余工作计划 (约 5 周)

### Phase A: 审计补全 (0.5 周)

1. chore(audit): 核对 §1.3 四项, 产出差距清单, 小缺口随手修 (单独 commit)

### Phase B: baseline 全量扫描 (1.5 周)

2. feat(baseline): 限速遍历全维度 region/entities mca (默认 50 chunk/s 可配),
   逐 slot 读 raw bytes 入 store, 进度按 region 文件粒度持久化, 断点续传
3. feat(baseline): manifest 增加 baselineComplete 标志; 未完成时 restore 命令拒绝
   并提示进度; status 展示扫描进度
4. test(baseline): 断点续传 + 与活跃 dirty 路径并发时跳过已入队 chunk

### Phase C: 玩家数据通道 (1 周)

5. feat(files): playerdata/ stats/ advancements/ poi/ 在 snapshot 创建时与 chunk
   同代采集 (整文件 hash 入同一 store), manifest 增加 files 段; 撕裂读重试 3 次,
   仍不一致标 suspect
6. feat(restore): RestoreFlow 回装 files 段, 与 region 同一原子边界
7. test(files): 同快照一致性 round-trip (FTB2 #95 回归防护) + suspect 标记路径

### Phase D: 离线 CLI + fsck (1.5 周)

8. feat(cli): java -jar 双模式入口, list/info/verify/restore 四命令;
   CLI 代码路径零 net.minecraft/forge import (RegionFileSlotReader/Writer 已独立, 直接复用)
9. feat(fsck): store 扫描校验 (重 hash 对比文件名) + 从 store 重建快照索引
10. feat(cli): 接入 fsck 子命令 (--rebuild-index)
11. test(cli): 备份目录拷贝到干净环境, CLI restore round-trip 逐 slot 字节比对

### Phase E: BAS degraded 感知 (0.5 周, 跨仓库)

12. BAS: feat(api): PipelineStateListener (onDegraded), triggerDegraded 时 fire
    (BAS degraded 是单向闩锁无复位路径, 不设 onRecovered, 恢复语义 = 重启)
13. BAS: chore(release): 发布含该 API 的版本; BetterBackup bas_version 升至该版本
14. feat(lifecycle): onDegraded -> 暂停快照 + 后续快照标 incomplete + 会话标志持久化;
    下次启动对 mtime 晚于上次完整快照的 region 做增量重扫 (复用 baseline 机制) 补采
15. test(lifecycle): 降级窗口 chunk 经重扫进入下一快照; 降级期间快照带 incomplete

## 三. v0.1.0 发布门槛 (Definition of Done)

1. baseline 完成前 restore 被拒绝; 完成后早期世界可完整恢复
2. playerdata 与 region 同快照采集、同边界恢复, 一致性测试通过
3. CLI 五命令在无 MC 运行时环境可用, fsck 能从纯 store 重建索引
4. BAS degraded 不再静默: 暂停 + incomplete + 重启后补采, 测试覆盖
5. §1.3 待核实项全部闭环 (落实或明确记为已知限制)
6. 文档: dedup 率按服务器规模分档披露; 已知限制 (绕过 vanilla save 路径的 mod
   不被感知; 已加载 chunk 无跨 save dedup)

## 四. 明确接受的取舍 (记录在案, 不再反复)

1. 已加载 chunk 跨 save 无 dedup (LastUpdate 在 raw bytes 内): 接受, gcIncremental
   兜底 store 膨胀, 收益主体在未加载 chunk
2. xxh128 非加密 hash: 接受, 非对抗场景
3. entity chunk 无特殊处理: 同 raw-bytes 路径, 接受其 dedup 率更低
4. 备份时机与 vanilla autosave 不协调 (partial-snapshot 风险): 沿用 DESIGN.md
   附录 C #4 的结论, v0.2 再议

## 五. v0.2 候选 (按用户反馈排序)

1. NBT 字段感知 dedup 第二层: 在 raw-bytes 架构上叠加 "canonical 重 hash"
   (解压 slot -> 剥 LastUpdate/InhabitedTime -> 排序序列化 -> 第二 hash 做等价判定,
   易变字段存 manifest 回注)。把已加载 chunk 的 dedup 从 0 拉回, 是 dedup 率的
   主要优化空间; 上一版计划 §2.1/2.2 的设计细节在此复用
2. 单 chunk / 单维度恢复 (调研显示是 AromaBackup 最受欢迎特性, Textile #10 求了六年)
3. protected 快照 + JSON 索引导出接第三方恢复 GUI
