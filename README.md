# Shinoyuki-BetterBackup

Minecraft 1.20.1 Forge 服务端**增量备份** mod。第二次备份起只处理变化的 chunk，84 份备份占用从 vanilla 方案的 16.8 TB 降到约 150-300 GB（98% 节省）。备份在后台 worker 线程跑，主线程零开销。跟 [Shinoyuki-BetterAutoSave](https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave) (BAS) 深度集成。

## 目录

- [它解决什么问题](#它解决什么问题)
- [跟现有备份 mod 的区别](#跟现有备份-mod-的区别)
- [安装](#安装)
- [配置](#配置)
- [运行时命令](#运行时命令)
- [恢复流程](#恢复流程)
- [性能预期](#性能预期)
- [数据安全](#数据安全)
- [已知限制](#已知限制)
- [构建](#构建)
- [致谢](#致谢)

## 它解决什么问题

现在主流 1.20.1 备份 mod（FTB Backups Z / AromaBackup 等）的两个痛点：

1. **占用爆炸**：每次备份都把 world 整个 zip 一份，84 份 = 84 × world 大小。500 MB 世界一夜膨胀到 4 GB，200 GB 世界一年累积超过 16 TB
2. **主线程冻结**：备份开始时 server 卡住几十秒到几分钟，玩家被踢

BetterBackup 的解法：
- **只备份变化的 chunk**：vanilla 一次备份要复制全世界，BetterBackup 只处理 BAS 刚保存过的 chunk（其他 chunk 跨快照引用同一份字节，不重复存）
- **整套在 worker 线程**：BAS 的 chunk save 完成后通知 BetterBackup，备份操作在后台 worker 跑，主线程感知不到

## 跟现有备份 mod 的区别

| | FTB Backups Z | fastback (git) | BetterBackup |
|---|---|---|---|
| 算法 | 整 zip 全量 | git object 文件级 | **chunk 级内容寻址** |
| 大世界 (>5 GB) | 占用爆炸 | git GC 翻车 5+ 分钟 | OK |
| 主线程冻结 | 30s - 5min | 数秒 | **0ms** |
| 跟存档 mod 集成 | 无 | 无 | **BAS listener** |
| 84 份占用 (200 GB 世界) | ~16.8 TB | 不可控 | **~150-300 GB** |

[ChronoVault](https://github.com/Catt1eyaa/ChronoVault)（NeoForge 1.21.1）已经在 NeoForge 上验证过 chunk slot 内容寻址的思路，BetterBackup 把这个范式带到 Forge 1.20.1 并跟 BAS 集成做增量。详见 [致谢](#致谢)。

## 安装

依赖：
- Minecraft 1.20.1
- Minecraft Forge 47.3.22+
- Java 17 (Eclipse Temurin)
- **必装**：[Shinoyuki-BetterAutoSave](https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave) v0.16.2+（版本下限与 mods.toml 的依赖 versionRange 一致，低于此版本 Forge 会拒绝加载 BetterBackup）。BetterBackup 通过 BAS Listener API 接收 chunk save 事件；在线单 chunk 回退（`restore-chunk-live`）还依赖 BAS v0.16.2 才具备的 `SaveCoordination` / `ChunkRestoreOutcome` / `ChunkRestoreResult` API，装更低版本 BAS 时该功能所需接口不存在

把 `shinoyuki_betterbackup-0.2.0-all.jar`（带 `-all` 后缀的，包内嵌了哈希库）放进 `mods/`，启动后配置文件生成在 `config/Shinoyuki-Optimize/shinoyuki_betterbackup/common.toml`。

> 不要装不带 `-all` 后缀的 jar，那个缺哈希库会启动报 `ClassNotFoundException`。

## 配置

`config/Shinoyuki-Optimize/shinoyuki_betterbackup/common.toml`：

| 字段 | 默认 | 说明 |
|---|---|---|
| general.enabled | true | 总开关。改 false 后 mod 仍加载但不再备份 |
| general.backupDirectory | "backup-store" | 备份目录，相对 server root（跟 world/ 同级）。绝对路径也支持 |
| storage.hashAlgorithm | XXH128 | 哈希算法。XXH128 比 SHA256 快 5-10 倍，碰撞概率仍低于硬盘位翻转。完整性敏感场景可切 SHA256 |
| storage.maxStoreSizeGB | 500 | 超过此阈值启动时自动跑全量 GC |
| schedule.mode | INTERVAL | 备份模式：INTERVAL（定时）/ MANUAL（仅命令） |
| schedule.intervalMinutes | 120 | INTERVAL 模式下的备份间隔 |
| retention.enabled | false | 滚动保留淘汰总开关。默认关闭，所有快照永久保留、不淘汰任何历史。关闭时下面四个配额被忽略。开启前建议先跑 `/betterbackup retention preview` 看清一次淘汰会删哪些快照 |
| retention.hourly | 24 | 保留最近 24 份每小时备份（仅在 retention.enabled=true 时生效） |
| retention.daily | 7 | 每日（仅在 retention.enabled=true 时生效） |
| retention.weekly | 4 | 每周（仅在 retention.enabled=true 时生效） |
| retention.monthly | 12 | 每月（仅在 retention.enabled=true 时生效） |
| workers.backupWorkerThreads | 2 | 备份 worker 线程数。CPU 密集（哈希计算），大服可调到 4 |
| safety.verifyOnStartup | true | 启动时清理孤儿 .tmp 文件（kill -9 后残留） |
| prometheus.enabled | false | 开启 Prometheus 监控 HTTP 接口 |
| prometheus.bindAddress | "0.0.0.0" | 监控接口绑定地址。公网服请用防火墙挡 9451 端口或改 127.0.0.1 |
| prometheus.port | 9451 | 监控接口端口（避开 BAS 的 9450） |

## 运行时命令

权限 OP level 2。

| 命令 | 作用 |
|---|---|
| `/betterbackup status` | 看运行状态、队列深度、dirty 计数 |
| `/betterbackup snapshot create [name]` | 立即创建一份备份（异步），name 可选 |
| `/betterbackup snapshot list` | 列出最近 20 份备份 |
| `/betterbackup snapshot info <id>` | 看某份备份的详细信息（chunk 数 / 维度 / level.dat 状态等） |
| `/betterbackup snapshot delete <id>` | 删除一份备份的引用（不立即清磁盘，跑 gc 才清）。命中"最新一份 / 最新 baseline 完整快照 / 待恢复目标"三门禁时拒绝，防误删唯一恢复点 |
| `/betterbackup restore <id>` | 准备恢复（写 flag 文件 + 提示停服）。**重启后自动恢复** |
| `/betterbackup restore-chunk-live [<id>] [<radius>]` | **在线**单 chunk / 区域回退，不停服即时执行。玩家执行时省略参数即回退站位所在区块；`<id>` 可填快照 id 或 `latest`（默认最新）；`<radius>` 0-8，以站位为中心 (2r+1)² 块。控制台 / RCON 用显式形态 `restore-chunk-live <id> <dim> <x> <z> [radius]`。超过 9 块（radius≥2）需 30 秒内 `confirm` 二次确认 |
| `/betterbackup confirm` | 确认上一条待确认的区域在线回退（30 秒内有效） |
| `/betterbackup gc` | 全量 GC：扫所有 hash 文件，删没被任何备份引用的孤儿 |
| `/betterbackup retention preview` | dry-run 预览：按当前配额 + 三门禁算出滚动淘汰"将删 / 将留"清单，不真删。启用 retention 前先看一眼 |

**自动 GC**：每次 snapshot 创建后会自动跑增量 GC，清掉本周期内 worker 写入但 manifest 没引用的中间版本 hash。store 大小稳态 ≈ 当前所有 manifest 引用的 unique hash × 平均字节，**不会随时间线性增长**。

## 恢复流程

恢复是**离线模式**：命令不立即执行，写一个 flag 文件提示玩家手动停服，重启时自动跑恢复。这样安全（vanilla 还没 load world 时直接覆盖文件不会冲突）。

```
玩家在游戏里:
  /betterbackup restore 2026-05-09T23-32-57Z
  → "Restore prepared. STOP THE SERVER NOW..."

玩家停服 → 重启

启动日志:
  [BetterBackup] pending restore detected: 2026-05-09T23-32-57Z - rebuilding world before vanilla load
  [BetterBackup] restore complete: chunks=20036 entity=1403 savedData=4 levelDat=true backupDir=.../world.bak-1715293567000
  [BetterAutoSave] pipeline starting ...
  [其他 mod 正常启动]
```

恢复时**会保留你当前 world**：把 `region/`, `entities/`, `data/`, `level.dat`, `playerdata/` 等移到 `<worldRoot>.bak-<timestamp>/`，按备份重建。玩家背包/成就/统计跟世界回到**同一份快照**，不会出现回档后背包跟世界对不上的刷物品问题。如果恢复出问题可以从 `.bak` 目录手动复原。

恢复失败（备份 store 数据缺失等）log ERROR 但**不阻止服务端启动**，flag 不删，玩家修复后重启会再跑一次。

**首次基线扫描**：装上 mod 后第一次启动会在后台限速扫描整个现有世界（默认 50 chunk/s），把已有地图全部纳入基线。基线完成前 restore 会被拒绝（否则恢复出来的世界缺一大块），`/betterbackup status` 能看扫描进度。

### 服务端起不来时：离线 CLI

mod jar 可以直接当命令行工具跑（不需要 Minecraft / Forge，裸 JRE 17 即可），服务端瘫痪时照样能从备份自救：

```bash
java -jar shinoyuki_betterbackup-0.2.0-all.jar list    --store <备份目录>
java -jar shinoyuki_betterbackup-0.2.0-all.jar info    --store <备份目录> --id <快照ID>
java -jar shinoyuki_betterbackup-0.2.0-all.jar verify  --store <备份目录> [--id <快照ID>]
java -jar shinoyuki_betterbackup-0.2.0-all.jar restore --store <备份目录> --id <快照ID> --world <world目录>
java -jar shinoyuki_betterbackup-0.2.0-all.jar fsck    --store <备份目录> [--rebuild-index]
```

`restore` 默认整份快照全量回退；也支持只回地形的**部分回退**（其余数据不动）：

```bash
# 单 chunk（显式点名，未采集会报错）
java -jar shinoyuki_betterbackup-0.2.0-all.jar restore --store <备份目录> --id <快照ID> --world <world目录> \
    --dim <维度ID> --chunk <x>,<z>
# 矩形区域（以 center 为中心，边长 2r+1，区域内未采集的 chunk 自动跳过）
java -jar shinoyuki_betterbackup-0.2.0-all.jar restore --store <备份目录> --id <快照ID> --world <world目录> \
    --dim <维度ID> --center <x>,<z> --radius <r>
```

`--dim` 是维度 id（形如 `minecraft:overworld`），`--chunk` / `--center` 是 chunk 坐标。CLI restore 只能在服务端**已停止**时使用：全量回退先把现有 world 移到 `.bak-<时间戳>/` 再按备份重建。`fsck --rebuild-index` 用于快照索引损坏时从 store 重建。

## 性能预期

实战参考（按 60 玩家中型服 + 200 万 chunk + 7 天保留 + 2 小时一备 = 84 份）：

| 维度 | FTB Backups Z | BetterBackup |
|---|---|---|
| 第一次备份 | ~200 GB | ~140 GB |
| 单次增量 | ~200 GB（全量） | ~1-5 GB（实测 ~1%） |
| 84 份总占用 | ~16.8 TB | ~150-300 GB |
| 单次备份耗时（主线程） | 30s - 5min（卡服） | 0ms |
| 单次备份耗时（壁钟） | 30s - 5min | 5-30s（worker） |

dedup 率取决于"已加载 chunk / 总世界"比例：
- 成熟大服（200 万 chunk，1-2 万加载）：**~98%+**
- 中型服（50 万，5k-1 万加载）：~95-98%
- 新服（5 万，5k 加载）：~70-90%
- 小型私服（2 万，2k 加载）：50-90%

## 数据安全

- **备份不动 world/**：备份期间整套是只读读取 `.mca` 文件 + 写入 `backup-store/`
- **只有 restore 会写 world**：游戏内 restore 需要写 flag + 手动停服后重启才执行；离线 CLI restore 直接重建 world（先把现有 world 移到 `.bak`），仅限停服状态使用
- **坏数据进不了备份**：从磁盘读 chunk 时做完整性校验，撞上正在写入的半截数据会被拦下重试，不会把垃圾字节存进备份
- **BAS 降级不静默**：BAS 后台 worker 异常降级时自动暂停备份、把后续快照标记为不完整，重启后自动补采降级窗口内的变化
- **关服自动备份**：服务端正常停止时会跑一次 final snapshot，确保关服前所有变化都进备份
- **kill -9 容错**：所有写入用 `tmp + fsync + atomic rename`，进程被强杀不会留半写文件污染 store；启动时自动清理 `.tmp` 残留
- **损坏 manifest 不会误删 store**：全量 GC 遇到读不出的 manifest 直接 abort，不会把它引用的 hash 当孤儿删掉

## 已知限制

- **必须装 BAS**：BetterBackup 通过 BAS 的 Listener API 接收 chunk save 事件。卸载 BAS 后 BetterBackup 启动失败
- **跟其他备份 mod 共存浪费空间**：理论上能并行跑，但 backup-store 跟 zip 备份会同时占用，建议二选一
- **modded 自定义维度的 SavedData 覆盖不全**：BAS Listener 不带 dimension 信息（API 限制），BetterBackup 按 overworld → nether → end 顺序探测每个 `.dat` 实际落在哪个维度的 `data/`，并记录维度限定路径，restore 时按维度落回原维度的 `data/`。vanilla 三维度已覆盖；把 SavedData 写进自定义维度 `data/` 的 mod（极少见）目前不完全覆盖
- **已加载 chunk 没有跨备份去重**：活跃区域的 chunk 每次保存字节都会变（vanilla 会更新时间戳字段），每份快照都会存一个新版本。节省空间的大头来自未加载区域——这就是上面性能表按服务器规模分档的原因
- **绕过 vanilla 保存路径的 mod 不被感知**：极少数直接写 region 文件的 mod，其改动不会进备份
- **非主世界的 poi 不在备份范围**：下界/末地的 `poi/`（村民工作点位缓存）恢复后由 vanilla 自动重建，不影响玩法数据
- **store 路径在 NFS / SMB 上未测**：理论上能跑但锁语义不一致，性能差，建议本地磁盘

## 构建

```bash
# 1. BAS 仓库先 publish (出 mod jar 到本地 maven)
cd Shinoyuki-BetterAutoSave
./gradlew publish

# 2. BetterBackup 仓库 build
cd Shinoyuki-BetterBackup
./gradlew build

# 产物在 build/libs/:
#   shinoyuki_betterbackup-0.2.0-all.jar    <- 给用户装这个
#   shinoyuki_betterbackup-0.2.0.jar         <- 缺哈希库, 别用
```

测试：`./gradlew test`（store / 快照 / GC / 撕裂读 / baseline / 玩家数据 / CLI / 降级补采等共 266 个单元测试）。

dev server 测试（不需要装 client）：`./gradlew runServer`，进 server console 跑 `/op <你的名字>` 然后用 client 连 localhost。

## 致谢

BetterBackup 的核心 dedup 算法 — 直接对 region file (`.mca`) 里的 chunk slot raw 字节做哈希，不经过内存 NBT — 借鉴自 [ChronoVault](https://github.com/Catt1eyaa/ChronoVault) (NeoForge 1.21.1)。

这个思路工程上漂亮地绕开了 vanilla `CompoundTag` 序列化的字节不稳定问题（`HashMap` iteration 顺序 / `tag.merge` / data fixer 重建 tag 都会让同语义内容产生不同字节），跨 JVM 字节恒等保证 dedup 率稳定。ChronoVault 在 NeoForge 1.21.1 上验证了可行性，BetterBackup 把这个范式带到 Forge 1.20.1，并跟 BAS 的 listener API 集成做增量备份（只处理 BAS 刚 save 的 chunk，不主动扫整个世界）。

## 许可

AGPL-3.0-or-later，附整合包分发例外（[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md)）：官方发布的未修改 jar 可原样收录进整合包 / 服务端包，保留项目名与仓库链接即可，无额外义务。修改后的版本不适用例外，仍受 AGPL 全部条款约束（含第 13 条网络条款）。
