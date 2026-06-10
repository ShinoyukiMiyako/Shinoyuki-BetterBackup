# Shinoyuki-BetterBackup v0.1 开发计划

| 字段 | 值 |
|---|---|
| 状态 | 计划 (2026-06-10), 取代 DESIGN.md §10 的阶段计划 |
| 前置文档 | DESIGN.md 草案 (2026-05-08) |
| 评审基础 | 三轮评审: BAS v0.9 代码核对 / NBT 与 dedup 技术调研 / 33 个备份方案市场调研 |
| 目标版本 | Minecraft 1.20.1 / Forge 47.3+ / Java 17 |
| BAS 依赖 | 硬依赖, 下限 = Phase 0 发布的含 degraded API 的 BAS 版本 (§2.8) |
| 估期 | 13 周单人开发 (含测试) |

本计划不重复 DESIGN.md 的内容, 只记录评审后的修订与最终执行计划。DESIGN.md 与本文冲突处, 以本文为准。

## 一. 评审结论与设计修订

### 1.1 P0 修订 (不做 MVP 就是错的)

| # | 问题 | 来源 | 修订 |
|---|---|---|---|
| 1 | CompoundTag 内部是 HashMap, NbtIo.write() 字节序不确定, 同一 tag 树两次序列化可能产生不同字节, dedup 失效 | 技术调研 (Bukkit mc-dev 源码) | 新增 canonical 序列化规范 (§2.1), hash 与入库前必须先规范化 |
| 2 | chunk NBT 的 LastUpdate 每次保存必变, InhabitedTime 玩家在场时变; 已加载 chunk 朴素全字节 hash 的 dedup 率约等于 0 | 技术调研 (Minecraft Wiki / MC-128345) | 易变字段 hash 前剥离, 但不丢弃: 降级存入 manifest 条目, 恢复时回注 (§2.2)。草案"v0.2 再考虑字段感知"的说法作废, 这是 MVP 前提 |
| 3 | 被动事件模型下, 第一份快照只含装 mod 后被保存过的 chunk (约占总世界 1-5%), 恢复即丢世界; DESIGN.md §2.4 "不主动扫描" 与 §2.5 "第 1 份 ~140GB 全量" 自相矛盾 | BAS 代码核对推论 | 新增 baseline 全量扫描 (§2.4), baseline 完成前禁止 restore |
| 4 | playerdata/ poi/ level.dat 等非 chunk 数据不走 ChunkSaveListener, 草案只处理了 level.dat; playerdata 与 region 不同步恢复会导致刷物品/丢物品 (FTB Backups 2 #95 实锤事故) | 市场调研 | 新增文件级旁路通道 (§2.5) |
| 5 | 自定义对象 store 没有恢复工具 = 数据绑架; mod 内恢复依赖服务端能启动, 服务端起不来时用户无路可走 | 市场调研 (FTB 系 100M+ 下载零恢复路径) | 离线恢复 CLI 提升为 MVP 发布硬条件 (§2.7) |

### 1.2 P1 修订

| # | 问题 | 来源 | 修订 |
|---|---|---|---|
| 6 | manifest / 索引是单点, 一次损坏全部备份不可恢复 (QuickBackupMulti #51: H2 异常退出清空, 全军覆没) | 市场调研 | blob 自描述 (文件名即 hash, 可独立校验); 提供 fsck 命令从 store 重建索引; manifest 写入用 incomplete 标记 + 成功后 atomic rename |
| 7 | GC 误删好数据是 CAS 系统的高发事故 (Prime Backup #64 CRITICAL / Simple Backups #39 #69 / X Backup 按条目计数误删共享 blob) | 市场调研 | GC 三纪律: 引用计数以 content hash 为键; 永不删最新快照引用的对象; 先写新后删旧 |
| 8 | 静默失败: 备份失败仍报成功, 产出空壳/不完整快照 (FTB1 / HBackUp / Crafty #712) | 市场调研 | 任何 chunk 入库失败即把当前快照标记 incomplete 且对 status 命令可见; 严禁吞异常报成功 |
| 9 | BAS degraded mode (worker 死亡回退 vanilla 保存) 时 listener 不再 fire, BetterBackup 静默失明, 继续产出"看起来成功"的不完整快照 | BAS 代码核对 (SnapshotPipeline.degraded 是 core 内部状态, 未暴露) | BAS 侧新增 degraded 通知 API (§2.8); BetterBackup 收到降级信号暂停快照并标记 incomplete |
| 10 | 上一轮"复用 BAS SaveTask/worker 池抽象"的建议作废: 那些是 core.* 内部类, 跨 mod 依赖内部类 = BAS 每次重构都炸 | BAS 代码核对 | 依赖面收窄到 api/ 四个文件 + degraded API; worker 照抄模式不复用类 (§2.8) |

### 1.3 P2 修订

| # | 问题 | 修订 |
|---|---|---|
| 11 | store 默认在 world/.shinoyuki-backup/, 处于"备份目录套娃"高危区 (FTB2 把备份备进备份, 磁盘无限爆炸) | 默认改为服务端根目录 shinoyuki-backup/, 与 world/ 平级; 启动时检测 store 路径是否在 world 内并 WARN |
| 12 | zstd 不在 vanilla/Forge 运行时, 需 shade zstd-jni (+5MB native) | MVP 默认 zlib (JDK Deflater 零依赖), zstd 推迟到 v0.2 |
| 13 | dedup 率 "70-80%" 的表述误导 | 文档按服务器规模分档: 成熟大服 (50 万+ chunk) 95%+; 新服/小服 60% 以下; 收益来自未加载 chunk 占比, 不来自已加载 chunk |
| 14 | mods.toml versionRange="[0.7.0,)" 写错 | listener API 已在 BAS v0.9 就绪; 最终下限 = 包含 degraded API 的 BAS 版本 |
| 15 | 磁盘空间预检缺失 (Advanced Backups #128: 恢复中途磁盘满, 世界与备份同毁) | 备份与恢复前都做空间预检 |
| 16 | 受保护快照 (protected flag, 不被保留策略清理) | 加入 MVP, 实现成本一个布尔字段 |

### 1.4 市场调研结论摘要

142 个候选方案, 深挖 33 个: 没有任何方案做过 Minecraft chunk 语义级 content-addressed dedup; 没有任何方案处理易变 NBT 字段或序列化确定性。最接近的是 Prime Backup v1.13-rc 的 4 KiB 扇区对齐定长分块 (字节偏移级, 非 chunk 语义级, 且 vanilla 会在 mca 内搬迁 chunk 扇区导致固定偏移比较失效)。差异化窗口真实存在但在收窄。完整调研记录见评审会话, 关键事故案例已转化为上述修订条目。

## 二. 修订后核心设计

### 2.1 Canonical NBT 序列化 (新增规范)

hash 与入库的字节必须是规范形式, 规则:

1. 递归遍历 CompoundTag, 所有层级的 key 按字节序排序后重建
2. 剥离易变字段 (§2.2 的字段表)
3. 用 NbtIo.write(tag, DataOutput) 写出 uncompressed 字节, 此字节即 hash 输入与 store 存储内容 (入库时 zlib 压缩)。严禁误用 writeCompressed: gzip 头携带 mtime, 会直接引入非确定性破坏 dedup

规范版本号写入 store 的 version.txt。任何剥离字段表 / 排序规则的变更都必须升版本号, 不同版本的 canonical 字节不混用 (整 store 按版本隔离, 升版本时旧 store 只读保留)。

实现要点: 排序重建只在 BetterBackup 自己的 worker 线程做, 不在 BAS 线程做 (见 §2.3 线程边界)。

### 2.2 易变字段剥离与回注

MVP 剥离字段表 (chunk 根层级, 1.20.1 格式):

| 字段 | 剥离原因 | 恢复处理 |
|---|---|---|
| LastUpdate | 每次保存必变, dedup 头号杀手 | 存 manifest, 回注 |
| InhabitedTime | 玩家在场时每 tick 递增; 但它驱动局部难度, 丢弃会让恢复后全图难度归零 | 存 manifest, 回注 |

manifest 条目格式相应修订 (取代 DESIGN.md §2.3 的 Map<ChunkPos, Hash>):

```
ChunkEntry {
    long packedPos;        // chunk pos
    byte[32] hash;         // canonical 字节的 sha256
    long lastUpdate;       // 剥离字段降级存储
    long inhabitedTime;
}
```

每 chunk 多 16 字节, 100k chunk 规模 manifest 从 ~4MB 涨到 ~5.6MB, 可接受。

entity chunk 不剥离任何字段: 实体 Pos/Motion/Rotation 是真实状态不是元数据, 没有可剥离项, 接受 entity 通道 dedup 率低 (entity 数据总量远小于 chunk 数据, 影响有限)。

### 2.3 数据流与背压 (修订 DESIGN.md §2.1)

```
BAS worker/IO 线程 (listener 回调内, 必须极轻):
    onChunkSaved(pos, dim, tag)
        |- tag.copy()                          // 深拷贝, 遵循 BAS listener 契约: 回调参数引用不得保留
        |- queue.offer((dim, pos, copiedTag))  // 按 (dim,pos) 键 coalesce 的有界队列
        |- 队列满 -> 不入队, 把 (dim,pos) 记入 dirtyPosSet (落盘标记, 见下)

BetterBackup worker 线程:
    |- 排序重建 + 剥离字段 -> canonical bytes
    |- sha256 -> store.put (zlib 压缩, fsync)
    |- currentSnapshot 登记 (pos, hash, lastUpdate, inhabitedTime)
    |- 周期性 drain dirtyPosSet: 从 region 文件读回该 chunk -> 同样流程
```

背压设计依据: listener 在 IO 完成后才 fire (BAS 语义保证), 所以队列溢出时直接丢 tag、改从 region 文件读回是安全的 -- 磁盘上的数据等于或新于被丢弃的 tag。读回路径与 baseline 扫描 (§2.4) 共用 RegionFileReader, 不是额外代码。

队列按 (dim,pos) coalesce: 同一 chunk 在 worker 消费前被多次保存, 只留最新 tag (语义同 vanilla IOWorker 的 pendingWrites)。队列上限暂定 4096 条, Phase 1 压测后调整。

备份侧磁盘预检: 快照创建前检查 store 所在卷剩余空间, 低于 diskPreflightMinFreeGB 即拒绝创建本轮快照并把原因写入 status (对应 §1.1 之外 P2 #15 的"备份与恢复前都做", 事故原型 Advanced Backups #128)。

### 2.4 Baseline 全量扫描 (新增)

装 mod 后首次启动 (或 store 为空时):

1. 枚举所有维度的 region/ 与 entities/ 目录下 mca 文件
2. worker 线程限速遍历 (默认 50 chunk/s, 可配置), 逐 chunk 解压 -> canonical 化 -> 入 store -> 登记 baseline manifest
3. 进度持久化 (每完成一个 region 文件记录一次), 重启后断点续传
4. 完成前: snapshot 创建照常进行但 manifest 标记 baselineComplete=false, restore 命令拒绝执行并提示进度
5. 完成后: baseline manifest 成为第一条基线, 后续快照在其上 overlay

200GB 世界按 50 chunk/s 约需数小时到一两天, 限速值给出运维文档指导 (夜间可调高)。

与活跃保存的并发: baseline 扫描遇到 dirtyPosSet 或队列里已有的 (dim,pos) 直接跳过 (活跃路径的数据更新)。

### 2.5 旁路通道: 非 chunk 数据 (新增)

不走 BAS listener 的数据, 在每次 snapshot 创建时同步采集 (文件级, 整文件 hash 入同一个 store):

| 数据 | 路径 | 说明 |
|---|---|---|
| 玩家数据 | world/playerdata/*.dat | 与 chunk 不同步 = 刷物品事故, 必须同快照采集 |
| POI | world/poi/ | 村民工作站等, 损坏可重建但恢复不一致会有怪异行为 |
| level.dat | world/level.dat + level.dat_old | vanilla 自己写 |
| 计分板等 | world/data/*.dat | MVP 先整文件备份; BAS v0.7 SavedData listener 落定后切事件路径 (v0.2) |

这些文件总量小 (典型 < 100MB), 整文件 hash + 入 store 的成本一次 snapshot 几秒, 在 worker 线程做。manifest 增加 files: { relativePath -> hash } 段。

已知边界: snapshot 触发瞬间 vanilla 可能正在写这些文件 (撕裂读风险)。MVP 缓解: 读后再 hash 一次, 两次不一致则重读 (最多 3 次), 仍不一致标记该文件条目 suspect。彻底方案 (挂 vanilla save barrier) 留 v0.2。

### 2.6 恢复流程修订 (修订 DESIGN.md §4.1)

在草案基础上增加:

1. 恢复前磁盘空间预检 (需要 >= 恢复目标体积 + 现 world 移走体积)
2. 写入顺序: region/entities 重建 (回注 LastUpdate/InhabitedTime) -> playerdata/poi/level.dat -> 全部完成后 atomic swap
3. 校验失败 (任何 referenced hash 缺失) 时整体拒绝, 不做部分恢复

### 2.7 离线恢复 CLI (新增, MVP 硬条件)

同一个 mod jar 双模式: 被 Forge 加载时是 mod; java -jar 直接运行时进入 CLI (Main-Class 入口, 不依赖 Forge/MC 运行时类, region 文件读写用自带的 RegionFileReader/Writer):

```
java -jar betterbackup.jar list <store>
java -jar betterbackup.jar info <store> <snapshot-id>
java -jar betterbackup.jar verify <store> <snapshot-id>
java -jar betterbackup.jar restore <store> <snapshot-id> <world-dir> [--dry-run]
java -jar betterbackup.jar fsck <store> [--rebuild-index]
```

约束: CLI 代码路径不得 import 任何 net.minecraft / forge 类 (NBT 读写自实现或用独立 NBT 库, 待 Phase 4 定; 倾向自实现最小子集, NBT 格式简单且我们只需读写不需要游戏语义)。

### 2.8 BAS 耦合面 (最终口径)

允许依赖的 BAS 面:

1. api/ 包四个文件: ChunkSaveListener / EntityChunkSaveListener / SavedDataSaveListener / SaveListenerRegistry
2. 新增 (BAS 侧工作, 约 10-20 行): PipelineStateListener { onDegraded(); } + SaveListenerRegistry 注册口, SnapshotPipeline.triggerDegraded 时 fire。注意: BAS 的 degraded 是单向闩锁 (compareAndSet(false,true), 全库无复位路径, 唯一触发源是 worker 线程死亡), 恢复语义只能是重启服务端, 因此不设 onRecovered

禁止依赖: core.* / mixin.* 任何类。BackupWorker / 队列 / 线程工厂照 BAS 模式自写 (~100 行), 不 import。

BetterBackup 对 degraded 的响应: 暂停 snapshot 创建, 后续快照一律标记 incomplete, status 显示 "BAS degraded, 备份暂停", 并把 degraded 会话标志持久化。下次启动时对 mtime 晚于上次完整快照的 region 文件做增量重扫 (复用 baseline 扫描机制), 补采降级窗口内经 vanilla 路径落盘的 chunk, 补采完成后才恢复正常快照语义。

### 2.9 配置修订 (相对 DESIGN.md §7)

```toml
[general]
backupDirectory = "shinoyuki-backup"   # 改: 默认服务端根目录, 与 world 平级 (防套娃)

[storage]
compressionAlgorithm = "zlib"          # 改: MVP 仅 zlib; zstd v0.2
# hashAlgorithm 删除: MVP 锁死 sha256, 减少 store 兼容性矩阵

[baseline]                             # 新增
scanChunksPerSecond = 50

[safety]
diskPreflightMinFreeGB = 10            # 新增
```

其余沿用草案。

## 三. MVP 发布硬条件 (Definition of Done)

1. canonical 序列化 + 字段剥离/回注, 带随机化乱序测试 (§5)
2. baseline 扫描可断点续传, 完成前 restore 被拒绝
3. chunk + entity 事件通道 + playerdata/poi/level.dat 旁路通道
4. 定时 + 手动快照, 滚动保留, GC 三纪律落地
5. 停服全量恢复 + 离线 CLI (list/info/verify/restore/fsck)
6. 失败可见: incomplete 快照标记 + status 展示; BAS degraded 感知 (降级即暂停, 重启后 mtime 增量重扫补采)
7. fsck 能从纯 store 重建索引
8. kill -9 测试通过 (备份中途 / GC 中途 / 恢复中途三场景)
9. 文档: 用户手册 + dedup 率分档说明 + 已知限制 (绕过 vanilla save 路径的 mod 不被感知)

## 四. 阶段计划与 commit 列表 (13 周)

### Phase 0: 骨架 + BAS degraded API (1 周)

BAS 仓库:
1. feat(api): 新增 PipelineStateListener (onDegraded), triggerDegraded 时 fire
2. chore(release): 发布包含 degraded API 的 BAS 新版本 (下文记 vNEXT)。BetterBackup 的依赖声明阻塞于此, 不发布则 Phase 0 无法闭环

BetterBackup 仓库:
3. chore: 项目骨架 (forge mdk + gradle + mods.toml), versionRange 下限 = vNEXT (修正草案遗留的 [0.7.0,), 当前仓库里的 [0.9.0,) 同样指向无 degraded API 的版本, 一并修正)
4. feat(config): BetterBackupConfig + common.toml schema。一次性 front-load §2.9 全部配置键 (含 baseline/safety 段), 接受 schema 先于实现
5. feat(lifecycle): 启动/停止钩子 + listener 注册/注销 + degraded 响应 (暂停 + incomplete 标记 + 会话标志持久化)

### Phase 1: canonical 序列化 + store (3 周)

6. feat(nbt): CanonicalNbtWriter 递归排序序列化 + 易变字段剥离/回注
7. test(nbt): 乱序 HashMap 随机化 round-trip + 剥离回注等价性测试
8. test(nbt): 真实世界存档大样本重复 hash 确定性验证 (canonical 命题的实证关卡, §6 风险表第 1 条的排查预算在此)
9. feat(store): ChunkStore 二级分桶 + atomic put/has/get + fsync 语义
10. feat(snapshot): manifest (ChunkEntry 格式) NBT 序列化 + incomplete 标记 + atomic rename
11. test(store): put/has/get/dedup + 损坏文件检测
12. feat(worker): BackupWorker + coalescing 有界队列 + dirtyPosSet 溢出标记。本阶段仅记录溢出; 读回 drain 依赖 RegionFileReader, 在 Phase 2 闭合
13. feat(integration): BAS chunk + entity listener 接入 (entity 走 no-strip 路径, manifest 单列 entity 段) + 队列压测

### Phase 2: region 读回 + baseline + 旁路通道 (2 周)

14. feat(region): RegionFileReader (mca 解析, 独立于 vanilla 类)
15. feat(worker): dirtyPosSet 读回 drain, 闭合队列溢出回退路径 (§5 测试 7 在此落地)
16. feat(baseline): 限速扫描 + 进度持久化 + 断点续传
17. feat(lifecycle): degraded 会话后启动时按 region mtime 增量重扫补采 (复用 baseline 机制, 闭合 §2.8 恢复语义)
18. feat(files): playerdata/poi/level.dat 旁路采集 + 撕裂读重试
19. test(baseline): 断点续传 + 与活跃保存并发的跳过逻辑

### Phase 3: 调度 + 命令 + 诊断 (1.5 周)

20. feat(schedule): IntervalScheduler + 手动触发
21. feat(safety): 快照创建前备份侧磁盘预检 (diskPreflightMinFreeGB), 不足拒绝创建并记入 status
22. feat(command): /betterbackup snapshot create/list/info + status (含 degraded/incomplete/baseline 进度展示)
23. feat(diag): DiagnosticLogger backup 段 (BAS 风格)

### Phase 4: 恢复 + 离线 CLI (2.5 周)

24. feat(region): RegionFileWriter (复用 Phase 2 的格式层)
25. feat(restore): 全量恢复 (停服检测 / 磁盘预检 / 字段回注 / world.tmp + atomic swap)
26. feat(cli): 独立 CLI 入口 (list/info/verify/restore) + 零 MC 依赖检查。fsck 子命令待 Phase 5 引擎就绪后接入
27. test(restore): round-trip 逐 chunk canonical hash 比对 (含回注字段) + 旁路通道文件同快照一致性恢复

### Phase 5: 保留 + GC + fsck (1.5 周)

28. feat(retention): 滚动保留 + protected 标记
29. feat(gc): mark-and-sweep, hash 键引用计数, 先写后删, 永不删最新
30. feat(fsck): store 自描述校验 + 索引重建引擎
31. feat(cli): 接入 fsck 子命令 (--rebuild-index), 补齐 §2.7 五命令
32. test(gc): GC 与 dedup write race 测试 + 误删防护测试

### Phase 6: 稳定性 + 文档 (1.5 周)

33. feat(safety): startup verify + 套娃检测 + NFS/SMB 检测 WARN
34. test(stress): kill -9 三场景 (备份/GC/恢复中途) + degraded 窗口补采场景
35. docs(readme): 用户手册 + dedup 分档说明 + 已知限制
36. docs(plan): 计划收尾复盘, 更新 v0.2 路线

### 工期对照

草案估 9 周。修订: BAS 侧 listener API 已存在 (-1 周), 新增 baseline 扫描 (+1.5 周)、旁路通道 (+0.5 周)、离线 CLI (+1 周)、canonical 序列化与确定性实证加重 (+2 周; 评审认定 Phase 1 是全计划的 correctness 命门, 真实世界大样本验证单独留 buffer), 合计 13 周。

## 五. 测试策略

按仓库 TDD 纪律, 核心断言必须是业务结果:

1. canonical 等价性: 同一逻辑 tag 树, 人为打乱 HashMap 插入顺序 N 次 (随机化), canonical 字节必须逐字节相等; 删掉排序逻辑该测试必须挂
2. 剥离/回注无损: strip(tag) + manifest 字段 -> reinject 后与原 tag 深度相等 (含 LastUpdate/InhabitedTime 边界值: 0 / Long.MAX_VALUE / 负数)
3. dedup 有效性: 同一 chunk 保存两次 (LastUpdate 不同), store 只产生一个对象
4. restore round-trip: create snapshot -> restore -> 对每个 chunk 重新 canonical 化, hash 必须与 manifest 一致
5. GC 安全: 并发 dedup write + GC, 任何被引用对象不得被删 (随机化调度压测)
6. kill -9: 三场景后重启, store 无污染、incomplete 快照被识别、baseline 进度可续
7. 队列溢出: 灌满队列, 验证 dirtyPosSet 回退路径产出与直通路径相同的 hash (依赖 RegionFileReader, 随 Phase 2 commit 15 落地)
8. 旁路通道一致性: 快照内 playerdata/poi/level.dat 与 chunk 集合必须同代采集, restore 后同时回装 (FTB2 #95 刷物品事故的回归防护); 撕裂读重试 3 次仍不一致必须产出 suspect 标记, 不得静默存入坏数据
9. degraded 补采: 模拟 BAS 降级期间若干 chunk 经 vanilla 路径落盘 -> 重启 -> mtime 增量重扫必须把这些 chunk 纳入下一快照; 降级期间创建的快照必须带 incomplete 标记

## 六. 风险表增补 (相对 DESIGN.md §8)

| 风险 | 概率 | 缓解 |
|---|---|---|
| canonical 序列化有未发现的不确定性源 (如 NBT 浮点格式化 / List 内顺序语义) | 中 | 测试 1 的随机化用例 + Phase 1 在真实世界数据上跑大样本重复 hash 验证 |
| baseline 扫描期间服务器关闭/崩溃循环, 永远扫不完 | 低 | 断点续传粒度到单 region 文件; 进度可查 |
| Prime Backup 等竞品先发 chunk 语义级 dedup | 中 | 无技术缓解; 12 周工期是承诺上限, 不加 scope |
| CLI 自实现 NBT 子集与 vanilla 行为有差异 | 中 | round-trip 测试以 vanilla NbtIo 输出为基准对拍 |
| tag.copy() 在 BAS 线程的开销超预期 | 低 | Phase 1 压测; 超标则改为在回调内直接序列化原始字节、canonical 化移到 worker (方案 B, 已预留) |

## 七. v0.2+ 路线修订

| 版本 | 内容 |
|---|---|
| v0.2 | SavedData 事件通道 (等 BAS SavedData listener) / 单 chunk 与单维度恢复 / zstd / 旁路通道 save barrier / protected 快照管理命令 |
| v0.3 | blake3 / 更多易变字段剥离规则 (字段表版本化已预留) / JSON 索引导出接第三方恢复 GUI |
| 不做 | 远程上传 / Web UI / 跨服共享 store (沿用草案非目标) |
