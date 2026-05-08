# Shinoyuki-BetterBackup 设计文档 (草稿)

| 字段 | 值 |
|---|---|
| 状态 | 草稿 (2026-05-08) |
| 关联 mod | Shinoyuki-BetterAutoSave (硬依赖) |
| 目标版本 | Minecraft 1.20.1 / Forge 47.3+ / Java 17 |
| 第一个 minor | v0.1 (MVP), 时间表见 §10 |

## 0. 目标 / 非目标

### 目标

- **快速增量备份**: 第二次起备份只处理自上次以来真实变化的 chunk, 时间复杂度 O(变化 chunk 数), 不是 O(world 大小)
- **存储 dedup**: 利用 Minecraft 世界数据高度冗余特性, content-addressed 存储 chunk 字节, 重复数据天然去重
- **主线程零阻塞**: 备份动作 100% 在 worker 线程, 复用 BAS 已有 worker 池架构, 不引入新主线程开销
- **数据完整性**: 备份后可校验, 启动时可自检 dedup store 一致性, kill -9 边界明确
- **可恢复**: 全量 / 单维度 / 单 chunk 三档 granularity, 命令行驱动

### 非目标 (永远不做)

- **远程上传** (S3 / SFTP / minio): 留给运维层 (rsync `backup-store/` 即可, dedup 后体积小)
- **跨服备份合并 / 多服共享 store**: scope creep, 会引入 hash 命名空间冲突 + manifest 同步问题
- **Web UI / 图形面板**: CLI 命令足够
- **mod 配置数据备份** (`config/`): 那是普通文件, 用 rsync, 不在本 mod scope
- **客户端单人世界备份**: server-only mod
- **备份时停服**: vanilla 全量备份方案的常见做法, 我们直接 piggyback 在 save 路径上, 用户感知不到备份在跑

### 暂定不做 (MVP 之外, 视用户反馈再考虑)

- **NBT 字段感知 dedup** (剥离 `LastUpdate` / `InhabitedTime` 等易变字段后再 hash): 提升 dedup 率但实现复杂, MVP 用全字节 hash 接受 70-80% dedup, v0.2+ 再考虑
- **时间点回滚** (rollback to snapshot): MVP 仅支持完整快照恢复, 不支持"恢复到 N 分钟前"
- **增量恢复** (restore single chunk only without touching others): MVP 写整个 region file
- **自动远程同步**: 见上

## 1. 当前 Forge 1.20.1 备份生态问题

| 主流方案 | 工作方式 | 主要问题 |
|---|---|---|
| FTB Backups 2 | 全量复制 world 目录 + 单 zip | 200GB 世界 = 200GB × N 份, 备份时主线程冻结 30s-5min |
| AromaBackup | 类似 FTB | 同上 |
| 服主自建 cron + rsync | 文件系统级, 不感知 chunk | 可能撞上 vanilla autosave 写一半的 region file 导致备份不一致 |
| 服主自建 cron + zip | 同上 + 整 zip 二次压缩 | region file 内 chunk 已经 zlib 压缩, 整 zip 是无效压缩 |

**根本问题**: 都没有用上 Minecraft 世界数据的核心特性:
1. Region file 内 90%+ chunk 在两次备份间没变
2. chunk 数据本身已经 zlib 压缩过, 整文件 zip 再压一次几乎无收益
3. 备份时机跟 vanilla autosave 没协调
4. 没有跨快照 dedup, 重复存储相同 chunk 数据

**BetterBackup 的差异化**: 把这四个问题一次性解决。

## 2. 核心机制: Content-Addressed Chunk Dedup

### 2.1 整体数据流

```
BAS 完成 chunk save (worker 线程):
    ChunkSaveTask.assemble() -> CompoundTag tag
        |
        +-> IOWorker.store(pos, tag)  [vanilla, 写 region file]
        |
        +-> BetterBackup.onChunkSaved(pos, dim, tag)  [BAS 新增 callback]
                |
                +-> BackupWorker 线程异步:
                    1. nbtBytes = NbtIo.write(tag) [uncompressed]
                    2. hash = sha256(nbtBytes)
                    3. if (!store.has(hash)) store.put(hash, compress(nbtBytes))
                    4. currentSnapshot.manifest.put((dim, x, z), hash)
```

**关键点**:
- BAS 的 worker 线程做完 NBT 拼装 → 调 IOWorker (vanilla 写盘) **+** 调 BetterBackup callback
- BetterBackup 自己有 worker 池, hash + 写 dedup store 是独立异步过程
- IOWorker 跟 BetterBackup 互不阻塞
- 主线程视角: 没变化, 跟纯 BAS 一样

### 2.2 Backup Store 目录结构

```
<world>/.shinoyuki-backup/
├── chunks/
│   ├── 00/             (sha256 前 2 hex char 分桶, 防单目录文件过多)
│   │   ├── 0012ab.../  (前 6 hex char 子桶)
│   │   │   ├── 0012ab34cdef...   (chunk 压缩字节, 文件名 = sha256 全 hex)
│   │   │   ├── ...
│   │   ├── ...
│   ├── ff/
│   │   └── ...
├── snapshots/
│   ├── 2026-05-08T19-00-00.manifest        (per-snapshot manifest, 见 §2.3)
│   ├── 2026-05-08T19-00-00.meta            (元数据: 创建时间 / chunk 总数 / dedup store 大小等)
│   ├── 2026-05-08T20-00-00.manifest
│   ├── ...
├── snapshots.idx                            (snapshots 索引文件, 用于快速列出)
├── store.lock                               (启动时持有, 防多 instance 同写)
└── version.txt                              (BetterBackup 版本号 + store schema 版本)
```

**设计理由**:
- **两级分桶 (00/0012ab.../...)**: sha256 前 2 hex 第一级 = 256 子目录, 前 6 hex 第二级 = 16M 子目录。3 千万 chunk 分布到 16M 桶 = 平均每桶 ≤2 文件, 文件系统友好
- **chunks/ 跟 snapshots/ 分离**: dedup store 跨快照共享, snapshot manifest 是轻量索引
- **store.lock**: 文件锁避免两个 server 同时写一个 store 目录
- **version.txt**: schema 升级时知道兼容性

### 2.3 Manifest 格式

每个 snapshot 一个 manifest 文件, 二进制格式 (NBT or 自定义紧凑):

```
SnapshotManifest {
    int version;                      // schema 版本
    String snapshotId;                // ISO 8601 timestamp
    long createdAtMillis;
    long worldGameTime;
    Map<DimensionId, Map<ChunkPos, Hash>> chunks;
    Map<String, Hash> savedData;      // v0.2+, MVP 留 null
    long totalUniqueBytes;            // store 大小快照
    long deltaBytes;                  // 自上一份 manifest 新增字节
}
```

格式选择: **NBT (CompoundTag)**, 复用 vanilla NbtIo 已有的 read/write/compressed 工具链, 不引入新依赖. 大型服 100k chunks × (8 byte pos + 32 byte hash) ≈ 4 MB manifest, NBT 压缩后约 1-2 MB, 可接受.

### 2.4 增量备份语义

**关键: BetterBackup 不主动扫描 world, 它被动接收 BAS 的 save 事件**:

- 当 BAS 调 `onChunkSaved(pos, dim, tag)` → 该 chunk 进入"待备份"状态
- 备份触发 (定时 / 手动 / 阈值): 把"自上次快照以来收到过 onChunkSaved 的所有 chunk + 其当前 hash" 收集成 manifest
- 没收到 onChunkSaved 的 chunk → 沿用上一份快照的 hash (manifest 复制旧条目)

伪码:

```java
class CurrentSnapshotState {
    private final Map<DimChunkKey, Hash> dirtySinceLastSnapshot = new ConcurrentHashMap<>();
    private SnapshotManifest lastSnapshot;  // 上一份 (基线)

    void onChunkSaved(DimChunkKey k, Hash h) {
        dirtySinceLastSnapshot.put(k, h);
    }

    SnapshotManifest createSnapshot(long timestamp) {
        var manifest = lastSnapshot.copy();   // 浅拷贝旧 manifest
        manifest.chunks.putAll(dirtySinceLastSnapshot);  // overlay 变化
        manifest.snapshotId = format(timestamp);
        manifest.createdAtMillis = System.currentTimeMillis();
        dirtySinceLastSnapshot.clear();
        lastSnapshot = manifest;
        return manifest;
    }
}
```

**注意**: 该结构假设 chunk 数据**只能通过 BAS 路径变化**。如果有 mod 直接写 region file 绕过 vanilla save 路径 (极罕见), BetterBackup 会漏检测。该约束写在用户文档里。

### 2.5 实测预期

按生产服 80mod / 60p / 7 天保留, 2 小时一备 (84 份):

| 维度 | vanilla 全量方案 (FTB Backups) | BetterBackup |
|---|---|---|
| 第 1 份备份大小 | ~200 GB (整 world zip) | ~140 GB (chunk 已压缩, 全部 unique) |
| 平均增量 (份 N>1) | ~200 GB (全量复制) | ~3-15 GB (变化 chunk 字节) |
| 7×24=168h × 84 份总占用 | ~16.8 TB | ~250-500 GB |
| 单次备份耗时 (主线程视角) | 30s-5min (主线程冻结) | 0ms (主线程无开销) |
| 单次备份耗时 (壁钟) | 30s-5min | 1-30s (worker 处理变化 chunk) |
| Restore 完整世界 | 解压 zip | 按 manifest 重建 region 文件 |

**dedup 率假设**: 70-80% (按全字节 hash, 没有 NBT 字段感知). 真实数据驱动, 上线后会观测调整.

## 3. BAS 集成

### 3.1 依赖关系

`mods.toml`:

```toml
[[dependencies.shinoyuki_betterbackup]]
modId="shinoyuki_betterautosave"
mandatory=true
versionRange="[0.7.0,)"   # 暂定 v0.7 起 BAS 暴露 callback API
ordering="AFTER"
side="SERVER"
```

不装 BAS → BetterBackup 拒绝加载, 启动期 ERROR + crash, 不允许半残运行.

### 3.2 Hook 点 (BAS 侧需要新增的扩展点)

BAS 当前 `ChunkSaveTask` 的工作流:

```
ChunkSaveTask.run()
  |- ChunkNbtAssembler.assemble(snapshot) -> CompoundTag tag
  |- worker.store(pos, tag)  // IOWorker
  |- onIoComplete callback   // 状态机推进
```

需要在 `worker.store(pos, tag)` 前后加一个**外部订阅点**:

```java
// BAS API (新增, 暴露在 BetterAutoSaveCore)
public interface ChunkSaveListener {
    void onChunkSaved(ChunkPos pos, ResourceKey<Level> dim, CompoundTag tag);
    void onEntityChunkSaved(ChunkPos pos, ResourceKey<Level> dim, CompoundTag tag);  // v0.6 entity 路径
}

public final class BetterAutoSaveCore {
    public static void registerSaveListener(ChunkSaveListener listener);
    public static void unregisterSaveListener(ChunkSaveListener listener);
}
```

**BAS 侧改动**: 一个 commit. 新增 `ChunkSaveListener` 接口 + `SaveListenerRegistry` (CopyOnWriteArrayList) + 在 `ChunkSaveTask.run()` / `EntitySaveTask.run()` 适当位置 fire 事件. 工作量 < 100 行, 主路径零开销 (空 listener list 时只一次 size==0 检查).

### 3.3 通信机制选型

| 方案 | 优点 | 缺点 | 决策 |
|---|---|---|---|
| **BAS 公开 listener API jar** | 编译期类型安全, 性能最好 | BAS 需独立 API jar 工件 (额外构建复杂度) | **首选** |
| Forge IMC | 无编译期耦合 | 字符串 key + Object payload, 类型不安全, 每事件 boxing | 备选 |
| Forge ServiceLoader | meta-inf/services 自动发现 | 启动期反射, 绑定时机不可控 | 否 |
| Mixin (BetterBackup 直接 mixin BAS) | 最灵活 | 维护噩梦, mod 间 mixin 撞车 | 否 |

**结论**: BAS 内部可以分一个 `betterautosave-api` 子模块 (gradle subproject) 暴露 listener 接口, 主 mod jar 跟 api jar 都发布. BetterBackup 编译期依赖 api jar, 运行时依赖 BAS 主 mod.

如果太麻烦, 第一阶段先用 IMC, 第二阶段再切 API jar. IMC 实现 30 行代码可起步.

### 3.4 SavedData / Entity 备份扩展点 (v0.7+)

- BAS v0.7 SavedData 路径接管后, 自然加 `SavedDataSaveListener.onSavedDataWritten(filename, tag)` callback
- BetterBackup 监听该事件, 把 `mtr_train_data.dat` 等也纳入快照

完整快照内容 (v0.7 后):

```
SnapshotManifest {
    chunks: { (dim, x, z) -> hash },          // BAS chunk 路径
    entityChunks: { (dim, x, z) -> hash },    // BAS v0.6 entity 路径
    savedData: { filename -> hash },          // BAS v0.7 SavedData 路径
    levelDat: hash,                           // 单独处理 (vanilla 自己写)
}
```

`level.dat` 由 vanilla autosave 写, 不在 BAS 路径上. MVP 阶段 BetterBackup 在备份触发时主动读 `level.dat` + hash + 入 store, 一次性 IO 不昂贵.

## 4. 恢复 (Restore) 设计

### 4.1 全量恢复

```
/betterbackup restore <snapshot-id> [--dry-run]
```

流程:
1. 加载 manifest
2. 校验 dedup store 完整性 (每个 referenced hash 都在 store 内)
3. **要求服务端处于 STOPPED 状态** (启动时检测有 pending restore, 否则拒绝执行 — 不允许在线恢复)
4. 删除 / 移动现有 `world/region/` `world/entities/` 目录到 `world.bak-<timestamp>/`
5. 按 manifest 遍历: 每个 (dim, x, z) → hash, 从 store 读 NBT bytes, 写入对应 region file 的正确 chunk slot
6. 写 `level.dat` 等其他文件
7. 启动服务端

**关键**: region file 是 32×32 chunks 的二进制布局, 需要正确实现 region file 写器. 复用 vanilla `RegionFile` 类是首选 (mixin 不行因为是 restore 阶段, 这时 mod 没加载). 单独写一个独立的 `RegionFileWriter` 工具类 (~200 行).

### 4.2 单 chunk / 单维度恢复 (调试用)

```
/betterbackup restore-chunk <snapshot-id> <dim> <x> <z>
/betterbackup restore-dim <snapshot-id> <dim>
```

支持在线运行 (服务端不停), 但要求目标 chunk 当前已 unloaded. 用法:
- 玩家挖坏地形误删建筑, 单 chunk 恢复
- 单维度损坏 (the_end 数据错误) 时, 单维度恢复

实现: 直接写对应 region file, 然后 vanilla 下次 load 时读到恢复的数据. 风险:
- 如果 chunk 当前 loaded, vanilla 内存版本会覆盖恢复版本 → 必须先 unload
- BAS 状态机需要被通知该 chunk 状态被外部修改, 防止 BAS 把内存中的旧版本写回

**这条线 MVP 不做**, v0.2 后实现. MVP 仅支持全量 (停服) 恢复.

### 4.3 时间点回滚

不在 MVP. 即使做也只能回滚到已存在的 snapshot 时间点, 不是任意 tick.

## 5. 保留策略 (Retention Policy)

### 5.1 滚动保留

配置示例:

```toml
[retention]
hourly = 24      # 保留最近 24 份小时备份
daily = 7        # 保留最近 7 份每日备份 (00:00 那份)
weekly = 4       # 保留最近 4 份每周备份 (周一 00:00)
monthly = 12     # 保留最近 12 份每月备份 (1 号 00:00)
```

挑选规则 (按时间从新到旧):
- 第 1-24 份: 按 hourly 保留
- 第 25 份起: 按 daily 保留, 跳过非 00:00 的
- 第 25+7=32 份起: 按 weekly 保留
- 等等

总保留份数上限 = 24+7+4+12 = 47 份. 超过部分自动删除 manifest, 触发 store GC (§5.2).

### 5.2 GC dedup store

manifest 删除后, 它引用的 chunk hash 可能仍被其他 manifest 引用, 也可能成为孤儿. GC 流程:

```
1. 扫描所有 surviving manifest 的 chunk hash, 累成 referencedHashes set
2. ls chunks/ 下所有文件, 不在 referencedHashes 的 → 删除
```

**触发时机**:
- 手动 `/betterbackup gc`
- 自动: 每次删除 manifest 后异步触发 (worker 线程, 不阻塞 BAS)
- 启动时: 如果 store 大小 > 配置阈值 (例如 500GB), 自动 GC

**GC 安全性**:
- GC 期间持有 `store.lock`
- 如果备份正在写 store, GC 等其完成
- GC 跟 dedup write 之间有 race window: GC 检测 hash 没被引用 → 此时一个新 backup 引用该 hash → GC 删了 → 新 backup 失败 → 重新计算 + 写入。该 race 用 GC 阶段 lock 解决, 或新 backup 写 hash 前持有 GC 互斥锁.

**实现复杂度**: 1 周, 并发正确性是大头.

### 5.3 自动 / 手动触发

| 模式 | 配置 | 说明 |
|---|---|---|
| 定时 | `schedule.cron = "0 */2 * * *"` | 每 2 小时备份, 用简单 cron 表达式解析器 |
| autosave 触发 | `schedule.afterAutosave = true` | vanilla autosave 周期完成后自动 snapshot |
| 阈值触发 | `schedule.dirtyChunkThreshold = 5000` | 累积变化 chunk 超过 5k 自动 snapshot |
| 手动 | `/betterbackup snapshot create` | 服主主动触发 |

MVP 仅做定时 + 手动. autosave 触发 + 阈值触发是 v0.2.

## 6. 命令套件

```
/betterbackup snapshot create [name]              MVP, 创建快照
/betterbackup snapshot list                       MVP, 列出所有快照
/betterbackup snapshot info <id>                  MVP, 显示某快照元数据
/betterbackup snapshot delete <id>                MVP, 删除快照, 触发 GC
/betterbackup gc [--dry-run]                      MVP, 手动 GC
/betterbackup status                              MVP, 显示 store 大小 / 总快照数 / 上次备份时间
/betterbackup restore <id>                        MVP, 全量恢复 (要求停服)
/betterbackup restore-chunk <id> <dim> <x> <z>    v0.2
/betterbackup verify <id>                         v0.2, 验证某快照所有 hash 在 store 中
```

权限: op level 2.

## 7. 配置项 (草案)

`config/Shinoyuki-Optimize/shinoyuki_betterbackup/common.toml`:

```toml
[general]
enabled = true                                # 总开关
backupDirectory = "world/.shinoyuki-backup"   # 备份 store 路径

[storage]
hashAlgorithm = "sha256"                      # MVP 仅 sha256, blake3 v0.2+
compressionAlgorithm = "zstd"                 # zstd / zlib
compressionLevel = 6                          # zstd 1-22, zlib 1-9
maxStoreSizeGB = 500                          # 超过自动触发 GC

[schedule]
mode = "interval"                             # interval | afterAutosave | manual
intervalMinutes = 120                         # mode=interval 时
dirtyChunkThreshold = 5000                    # mode=afterAutosave 时, 累积变化超过此值才创建

[retention]
hourly = 24
daily = 7
weekly = 4
monthly = 12

[workers]
backupWorkerThreads = 1                       # 备份 worker 池大小, 默认 1 足够 (IO 密集不是 CPU 密集)

[safety]
verifyOnStartup = true                        # 启动时扫 store 一致性
verifyOnSnapshot = false                      # 每次备份后校验所有 hash (慢, 默认关)
panicOnHashMismatch = false                   # hash 不一致时是否 crash
```

## 8. 关键风险 + 缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| sha256 hash 碰撞 | 10^-77 量级 | 数据错乱 | 不缓解, 概率比硬盘 bit flip 还低 |
| dedup store 文件被外部修改 / 损坏 | 中 | 该 hash 引用的 chunk 恢复时崩 | startup 校验扫文件大小 + 抽样 hash, mismatch 时 quarantine 并 ERROR |
| manifest 写一半断电 | 低 | 该快照不可用, 但 store 没污染 | manifest 写临时文件 + atomic rename, 写一半的临时文件 startup 时清理 |
| GC 跟 dedup write race | 中 | 新备份引用刚被 GC 删的 hash | GC 阶段持 store-wide write lock, 或用引用计数防漏删 |
| 用户在恢复中途中断 | 中 | world 目录半残 | restore 写到 `world.tmp/`, 完成后才 atomic move |
| BAS 升级后 listener API 改了 | 高 (单独 mod 风险) | BetterBackup 不兼容 | 严格 semver, listener API 进 betterautosave-api 子模块, 跟 mod jar 分离, 升级窗口长 |
| 备份过程异常导致 worker 死 | 低 | 后续备份不工作 | worker 异常 → degraded mode (类似 BAS), DiagnosticLogger 报警, 不 crash 服务端 |
| chunk 字节包含易变字段 (LastUpdate / InhabitedTime) 导致 dedup 率低 | 高 | dedup 率从理论 95% 跌到 70-80% | 接受。MVP 不做 NBT 字段感知, v0.2+ 再考虑 |
| 服主同时跑 FTB Backups 等老 mod | 中 | 双备份冲突 (磁盘空间 / 锁竞争) | 在 README 警告, 不主动检测 |
| store 路径在 NFS / SMB 远程文件系统 | 中 | 性能差 + 锁语义不一致 | 启动时检测 store 路径 mount type, NFS/SMB 时 WARN |
| kill -9 时正在备份 | 中 | manifest 半写 / store 漏文件 | manifest atomic rename 已防一半, store 文件 fsync 后才写 manifest, 引用尚未 fsync 的 hash 自动重写 |

## 9. MVP 范围

### 在 MVP (v0.1.0)

- chunk 路径备份 + content-addressed dedup (BAS chunk pipeline 集成)
- entity 路径备份 (BAS v0.6 entity pipeline 集成)
- snapshot manifest + 二级分桶 store
- 定时备份 (cron-style) + 手动 `/betterbackup snapshot create`
- 滚动保留策略 (hourly/daily/weekly/monthly)
- store GC (手动 + 删 manifest 后自动)
- 全量恢复 (停服)
- 启动时 store 一致性自检
- 基础诊断: `/betterbackup status / list / info`

### 不在 MVP (后续版本)

- SavedData 路径备份 (v0.2, 等 BAS v0.7 落定)
- 单 chunk / 单维度恢复 (v0.2)
- NBT 字段感知 hash (v0.2+)
- 时间点回滚 (v0.3+)
- 远程 store (v0.3+, 但更可能永远不做)
- Web UI (永远不做)

## 10. 阶段计划与 commit 列表

### Phase 0: 项目骨架 + BAS API 暴露 (1 周)

BAS 仓库 (1 commit):
- `feat(api): 暴露 ChunkSaveListener / EntitySaveListener + SaveListenerRegistry`

BetterBackup 仓库 (3-4 commits):
1. `chore: 项目骨架 (forge mdk + gradle + mods.toml + 依赖 BAS)`
2. `feat(config): BetterBackupConfig + common.toml schema`
3. `feat(lifecycle): BetterBackupCore 启动 / 停止 / shutdownMode 钩子`
4. `feat(api): registerSaveListener 跟 BAS 通信打通 hello world`

### Phase 1: 核心 dedup + manifest (2 周)

5. `feat(store): ChunkStore content-addressed 二级分桶 + atomic put / has / get`
6. `feat(snapshot): SnapshotManifest NBT 序列化 + atomic rename 写入`
7. `feat(snapshot): CurrentSnapshotState 维护 dirtySinceLastSnapshot`
8. `feat(worker): BackupWorker 单线程 hash + compress + store.put`
9. `feat(integration): BAS save 事件入 BackupWorker queue`
10. `test(store): ChunkStore 单元测试 (put / has / get / dedup)`
11. `test(snapshot): manifest 写读 round-trip + 损坏文件检测`

### Phase 2: 调度 + 命令 (1.5 周)

12. `feat(schedule): IntervalScheduler cron 解析器`
13. `feat(command): /betterbackup snapshot create / list / info`
14. `feat(command): /betterbackup status`
15. `feat(diag): DiagnosticLogger 输出 backup 段 (类似 BAS 风格)`

### Phase 3: 恢复 (2 周)

16. `feat(restore): RegionFileWriter 独立工具 (不依赖 vanilla mod 加载)`
17. `feat(restore): 全量恢复流程 (停服检测 / world.tmp / atomic move)`
18. `feat(command): /betterbackup restore <id>`
19. `test(restore): round-trip 测试 (create snapshot -> restore -> 比对 region 文件)`

### Phase 4: 保留 + GC (1 周)

20. `feat(retention): RetentionPolicy 选择保留 manifest`
21. `feat(gc): StoreGc 扫描 + 删孤儿 chunk`
22. `feat(command): /betterbackup gc / snapshot delete`
23. `test(gc): GC 跟 dedup write race 测试`

### Phase 5: 稳定性 + 文档 (1.5 周)

24. `feat(safety): startup verify + panicOnHashMismatch 配置`
25. `feat(diag): degraded mode (worker 异常时停止备份, 不 crash)`
26. `test(stress): kill -9 中途备份测试`
27. `docs(readme): 用户文档 + 已知限制`
28. `docs(api): BAS listener API 使用文档`

**总工作量**: 9 周单人开发 (含测试). 比网络包 16-25 周显著小, 比 BAS v0.6 大约 2 倍.

## 11. 后续版本路线 (v0.2+)

| 版本 | 核心特性 | 触发条件 |
|---|---|---|
| v0.1.0 | MVP (上述 §9) | 第一次发布 |
| v0.1.x | 生产 bug 修复 + 用户反馈 | 上线后 |
| v0.2.0 | SavedData 备份 + 单 chunk 恢复 | BAS v0.7 落定 |
| v0.2.x | NBT 字段感知 hash (提升 dedup 率) | 用户反馈 dedup 率不够时 |
| v0.3.0 | blake3 hash + zstd-22 压缩 + verify 命令 | 性能优化阶段 |
| v0.4.0 | (待定, 看用户反馈) | n/a |

## 12. 跟其他 mod / 工具的边界

| 工具 | 关系 | 边界 |
|---|---|---|
| BAS | 硬依赖 | BetterBackup 通过 BAS listener API 拿 save 事件; 不存在 BAS 时 BetterBackup 不工作 |
| FTB Backups 2 | 替代关系 | 用户应卸载 FTB Backups, 二选一. 同装会双份备份, 浪费磁盘 |
| Forge worldgen mod | 透明 | BetterBackup 只看 chunk NBT 字节, 不感知是哪个 mod 生成的 |
| 服主自建 rsync `world/` | 兼容 | rsync 不影响 BAS / BetterBackup. rsync `backup-store/` 反而是推荐做法 (异地备份) |
| 服主自建 cron + zip | 替代关系 | 不需要了 |

## 附录 A: 相关项目参考

- **borg / restic**: 内容寻址备份的成熟实现, BetterBackup 的核心算法本质是 borg 的简化版 (chunk 已经天然分块, 不需要 rolling hash)
- **Git LFS**: large file dedup 思路, manifest = git tree object
- **ZFS 快照**: block-level dedup, 我们做 chunk-level 是更高粒度
- **Minecraft Region File 格式**: https://minecraft.wiki/w/Region_file_format (RegionFileWriter 实现参考)

## 附录 B: 命名 / 版本 / 仓库元信息

- mod ID: `shinoyuki_betterbackup`
- mod 显示名: `Shinoyuki BetterBackup`
- 仓库: `Shinoyuki-BetterBackup` (GitHub)
- 包名: `com.shinoyuki.betterbackup`
- 主类: `com.shinoyuki.betterbackup.BetterBackupMod`
- 第一个 release: `0.1.0` (MVP 完成后)

## 附录 C: 待决问题 (开发期间需要拍板)

1. **BAS API jar 还是 IMC**: §3.3, 推荐前者, 但 MVP 阶段 IMC 也可
2. **manifest 格式 NBT 还是自定义二进制**: §2.3, 倾向 NBT (简单), 性能不够再换
3. **store 默认放 world/ 目录还是独立目录**: 默认 `world/.shinoyuki-backup/`, 但允许配置外置 (避免 world 目录被整体备份时把 store 也带进去)
4. **vanilla autosave 时机协调**: 是否在 vanilla autosave 周期内主动触发 snapshot, 保证一致性? MVP 阶段不协调, 接受 partial-snapshot 风险 (个别 chunk 在 snapshot 启动到完成期间又变化, 该 chunk 用新 hash, 跟其他 chunk 时间点不一致). v0.2 考虑加锁保证 atomicity
5. **客户端兼容**: BetterBackup 是 server-only mod, 客户端不需要装. mods.toml 声明 `side="SERVER"`. 但需要测试客户端连服时 mods sync 不报 missing
