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
BAS chunk IO 完成 (BAS worker 线程, future.whenComplete callback):
    SaveListenerRegistry.fireChunkSaved(pos, dim, tag)
        |
        +-> BetterBackup.ChunkSaveListener.onChunkSaved(pos, dim, tag)
                |
                +-> 入 BackupWorker queue (仅 ChunkPos + dimension, 不持 tag 引用)
                        |
                        +-> BackupWorker 线程异步 (与 BAS worker 解耦):
                            1. 打开对应 .mca 文件 (region file)
                            2. 读 chunk slot raw 压缩字节 (vanilla zlib)
                            3. hash = xxh128(rawBytes)
                            4. if (!store.has(hash)) store.put(hash, rawBytes)
                            5. currentSnapshot.dirtyMap.put((dim, x, z), hash)
```

**关键点**:

1. **fire 时机 (BAS v0.7+ 实际行为)**:
   - BAS 在 `ChunkSaveTask.execute()` 的 `future.whenComplete` 内 fire, 即 **IO 完成后** (chunk 字节已写入 .mca 文件)
   - 仅 `CLEAN_LANDED` / `REQUEUE_DIRTY` 路径 fire, `FAILED_TERMINAL` 不 fire — BetterBackup 只接收已成功落盘的 chunk, 跟 vanilla 备份语义一致
   - REQUEUE_DIRTY 路径下同一 chunk 在 BAS worker 周期内可能多次 fire, BackupWorker 收到后用 dirtyMap 覆盖语义保留最新 hash

2. **不依赖 in-memory CompoundTag**:
   - BackupWorker 不读 listener 给的 `tag` 引用, 只用 `pos + dim` 定位 .mca 文件
   - 完全绕开 vanilla `CompoundTag.tags`(`HashMap`) 的 iteration 顺序 / `tag.merge` / data fixer 重建 tag 引发的 NBT 字节不确定性问题
   - 同语义内容跨 JVM 重启字节恒等 (vanilla 已经把 chunk 写到 .mca 文件了, 我们读盘的字节就是最终落盘的字节)

3. **dedup 单元 = .mca 文件里的单 chunk slot 压缩字节**:
   - vanilla 已经用 zlib 压缩了 chunk NBT, BackupWorker 不再二次压缩 (避免无效压缩)
   - 这跟 NeoForge 的 ChronoVault 同构 — 已被验证可行的工程范式

4. **主线程视角**: 多一次 listener 触发 (≤ 1us, CopyOnWriteArrayList), BAS 主线程开销不变

5. **时序保证**: BAS fire 时 IOWorker 已 future.complete → vanilla `RegionFile.write` 已 sync → .mca 文件包含本次写入。BackupWorker 读到的字节即为本次落盘内容

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

### 2.5 dedup 率机制与预期

#### dedup 率的真正来源

**dedup 不是来自 chunk 内字节稳定性, 而是来自跨快照的 chunk 引用复用**:

- BAS 只在 chunk 实际被 save 时 fire `onChunkSaved`. 玩家不访问的 chunk → BAS 不接收 → 无新事件 → manifest 直接复制上一份的 hash entry, 跨快照引用同一个 chunk slot store 文件
- 加载且活动的 chunk 才会有新 hash 进 store. 这部分占比由"已加载 chunk / 总世界 chunk"决定
- 即便 chunk 内 NBT 字段因玩家活动微变 (LastUpdate / InhabitedTime / block_ticks 等), 只要它属于"未加载 chunk"集合就 100% dedup

#### 按服规模分档

| 服规模 | 总 chunk | 加载 chunk | 单次快照 diff | 实际 dedup 率 |
|---|---|---|---|---|
| 成熟大服 | 200 万 | 1-2 万 | ~1% | **~98%+** |
| 中型服 | 50 万 | 5k-1 万 | ~2% | ~95-98% |
| 新服 (跑图阶段) | 5 万 | 5k | ~10-30% | ~70-90% |
| 小型私服 | 2 万 | 2k | ~10-50% | 50-90% |

**结论**: 大服越成熟 dedup 率越高 (跑图过期后大量 chunk 永久不再 save). 小型私服因加载比例高 dedup 率较低, 但绝对存储成本也低 (2 万 chunk × ~3 KB × 47 份保留 ≈ 3 GB), 仍可接受.

#### 实测预期 (生产服 80mod / 60p / 7 天保留, 2 小时一备 = 84 份)

| 维度 | vanilla 全量方案 (FTB Backups Z) | BetterBackup |
|---|---|---|
| 第 1 份备份大小 | ~200 GB (整 world zip) | ~140 GB (chunk 已压缩, 全部 unique) |
| 平均增量 (份 N>1) | ~200 GB (全量复制) | ~1-5 GB (变化 chunk 字节, 大服 ~1%) |
| 84 份总占用 | ~16.8 TB | ~150-300 GB (节省 50-100x) |
| 单次备份耗时 (主线程视角) | 30s-5min (主线程冻结) | 0ms (主线程无开销) |
| 单次备份耗时 (壁钟) | 30s-5min | 5-30s (worker 处理变化 chunk) |
| Restore 完整世界 | 解压 zip | 按 manifest 重建 region 文件 |

**算法选择**: 直接对 .mca 文件里 chunk slot raw 压缩字节做 hash, 不解 NBT, 不剥字段, 跨 JVM 字节恒等. 详见 §2.7.

### 2.6 store 临时膨胀与增量 GC

加载且活动的 chunk 在两次快照之间会被 BAS save 多次 (autosave 5min 一次, 2 小时间隔 = 24 次). 每次都进 BackupWorker → 每次都生成新 hash 写 store. 但 manifest 只引用最后一次 (dirtyMap.put 覆盖语义), 中间 23 次写入 store 后就成 unreferenced 孤儿.

**膨胀估算 (大服)**: 1 万加载 chunk × 24 次 / 2h × 3 KB ≈ 700 MB / 2h 临时数据等 GC.

§5.2 原本的 GC 触发点只覆盖删 manifest 后扫全库, 不覆盖这种"snapshot 间临时孤儿". 必须加第四个触发点:

- **每次创建 snapshot 后立即增量 GC**: 仅扫本次 BackupWorker 处理过、但未被 manifest 引用的 hash. scope 远小于全量 GC, 秒级完成. 防止 store 在两次 snapshot 间膨胀几 GB.

实施细节: BackupWorker 维护 thread-local `Set<Hash>` 记录本次窗口写入的 hash, snapshot 创建时跟 manifest.values 求差集, 直接删除. 不需要扫整个 store 目录.

### 2.7 算法选择: 为什么不走 NBT 字段感知 hash

候选方案对比:

| 方案 | 实施 | 跨 JVM 稳定 | dedup 率 | 同行先例 |
|---|---|---|---|---|
| **A. .mca chunk slot raw bytes hash (本方案)** | 简单 | 是 (绕开内存对象) | 大服 ~98%+ | NeoForge ChronoVault |
| B. NBT 字段剥离 (LastUpdate / InhabitedTime) 后 sort + serialize 再 hash | 复杂 (递归 NBT 树) | 是 | 略高几个百分点 | **没人做过** |
| C. 直接对 NbtIo.write(in-memory tag) 字节 hash | 最简单 | **否** | 局部归零 | 不安全 |

**为什么不选 B**: vanilla `CompoundTag.tags` 是 `HashMap`, NbtIo 按 iteration 顺序写入. Java 17+ HashMap 是确定性纯函数 (JEP 180 已移除 hash randomization), 但 `tag.merge` / `tag.copy` / data fixer 重建 tag 时 put 顺序变化, 同语义产生不同字节. 方案 B 用递归 sort keys 能解决, 但实施复杂度高于 A, 边际收益 (几个百分点) 不值得.

**为什么不选 C**: 见上 — put 顺序敏感, mod 改 NBT 后 dedup 局部归零, 高负载场景隐患大.

**方案 A 的风险点**: 已加载 chunk 因 LastUpdate 等字段每次 save 字节都变, 该 chunk 在 store 里产生很多 unique hash. 但因为这些只占总 chunk 的 1-2%, 不影响整体 dedup 率, 且增量 GC (§2.6) 会清掉 unreferenced 中间版本.

## 3. BAS 集成

### 3.1 依赖关系

`mods.toml`:

```toml
[[dependencies.shinoyuki_betterbackup]]
modId="shinoyuki_betterautosave"
mandatory=true
versionRange="[0.9.0,)"   # BAS v0.7 起暴露 SaveListenerRegistry, v0.9 起补全工具化, 锁下界 0.9.0
ordering="AFTER"
side="SERVER"
```

不装 BAS → BetterBackup 拒绝加载, 启动期 ERROR + crash, 不允许半残运行.

### 3.2 BAS Listener API (实际签名, BAS v0.7+ 已上线)

BAS 已经在 v0.7 暴露三类独立 listener 接口, BetterBackup 直接 import 使用, 无需 BAS 侧再加扩展点.

实际接口 (来自 `com.shinoyuki.betterautosave.api`):

```java
public interface ChunkSaveListener {
    void onChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag);
}

public interface EntityChunkSaveListener {
    void onEntityChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag);
}

public interface SavedDataSaveListener {
    void onSavedDataWritten(String fileName, CompoundTag tag);
}

public final class SaveListenerRegistry {
    public static void registerChunk(ChunkSaveListener listener);
    public static void unregisterChunk(ChunkSaveListener listener);
    public static void registerEntityChunk(EntityChunkSaveListener listener);
    public static void unregisterEntityChunk(EntityChunkSaveListener listener);
    public static void registerSavedData(SavedDataSaveListener listener);
    public static void unregisterSavedData(SavedDataSaveListener listener);
}
```

**关键约束** (BAS 实际行为, BetterBackup 实施时必须遵守):

- listener 在 BAS worker 线程被 fire (不是主线程), 必须线程安全
- 仅 `CLEAN_LANDED` / `REQUEUE_DIRTY` 路径 fire (chunk 已成功落盘), `FAILED_TERMINAL` 不 fire
- `tag` 在 fire 后 BAS 不再修改 (effectively immutable). 但本方案 §2.1 不读 tag, 直接读 .mca 磁盘字节, 该约束跟备份 dedup 无关
- listener 异常被 Registry try-catch + log 不传播; BetterBackup 自己的 worker queue 应再加 try-catch 防降级

### 3.3 集成方式 (BetterBackup 编译期依赖 BAS jar)

**BetterBackup 编译期依赖 BAS jar**, 不需要独立 API jar 子模块. BAS API package (`com.shinoyuki.betterautosave.api`) 已经稳定公开, 直接 link 即可.

`build.gradle`:

```gradle
dependencies {
    implementation fg.deobf("...:shinoyuki_betterautosave:0.9.0")
}
```

`mods.toml`:

```toml
[[dependencies.shinoyuki_betterbackup]]
modId = "shinoyuki_betterautosave"
mandatory = true
versionRange = "[0.9.0,)"
ordering = "AFTER"
side = "SERVER"
```

不装 BAS → BetterBackup 拒绝加载, 启动期 ERROR + crash, 不允许半残运行.

(原备选 IMC / ServiceLoader 方案不再需要 — API package 公开稳定后直接 link 最简单.)

### 3.4 三类 listener 在备份里的角色

| listener | 触发时机 | 备份动作 |
|---|---|---|
| `ChunkSaveListener` | chunk 已写入 .mca 文件 | BackupWorker 读对应 .mca chunk slot raw bytes, hash, 入 store, 写 dirtyMap |
| `EntityChunkSaveListener` | entity .2dm 已写入 entity region file | 同上 (entity region file 在 `entities/` 子目录) |
| `SavedDataSaveListener` | .dat 文件已写盘 (含 `mtr_train_data.dat` 等) | BackupWorker 直接读 .dat 文件字节 (单文件不分 slot) hash 入 store |

完整快照 manifest 结构:

```
SnapshotManifest {
    chunks:       { (dim, x, z) -> hash },     // BAS ChunkSaveListener
    entityChunks: { (dim, x, z) -> hash },     // BAS EntityChunkSaveListener
    savedData:    { fileName -> hash },        // BAS SavedDataSaveListener
    levelDat:     hash,                        // 独立处理, 见 §3.5
}
```

### 3.5 level.dat 处理 (BAS 不接管)

`level.dat` 由 vanilla `MinecraftServer.saveEverything → ServerLevel.save → LevelStorageAccess.saveDataTag` 写盘, **BAS 不接管该路径**, 没有对应 listener.

MVP 处理方案 (零 mixin):

- `ServerStoppingEvent` (`EventPriority.LOW`) handler 完成后, vanilla 已经写完 level.dat. BetterBackup 在 `ServerStoppedEvent` 时直接读 `<world>/level.dat` 文件字节 hash 入 store
- 定时备份 (server 运行中) 触发时同样直接读盘 — vanilla 上次 autosave 已写 level.dat, 读到的是最新 (有几秒延迟可接受, level.dat 内容变化频率本身低)
- 单文件几十 KB, 一次性 IO 不昂贵

**v0.2+ 可选**: BetterBackup 自己加 mixin 拦 `LevelStorageAccess.saveDataTag` HEAD 在写盘瞬间捕获 tag, 实现"任意时间点都有最新 level.dat 备份". MVP 不做.

### 3.6 BetterBackup 自身 lifecycle (跟 BAS 关服顺序对齐)

BAS `BetterAutoSaveMod.onServerStopping` 实际顺序:

```
1. exporter.stop()         (Prometheus)
2. enterShutdownMode()     (BAS scheduler 不再接管新 save)
3. drainPending()          (等 BAS worker queue 清空)
4. joinWorkers()           (等 BAS chunk/entity/savedData worker 线程退出)
```

BetterBackup 必须保证 **BAS 在第 3-4 步 fire 的最后一批 listener 事件能被消费**, 否则数据丢:

- BetterBackup `ServerStoppingEvent` handler 用 `EventPriority.LOW` (BAS 默认 NORMAL), 让 BAS 先跑完 onServerStopping
- BAS 关服期间 fire 的 listener 事件 → BetterBackup queue 接收 (BetterBackup worker 此时仍活着)
- BAS joinWorkers 完成后 BetterBackup `ServerStoppingEvent(LOW)` handler 才执行: drain 自己 queue → 创建 final snapshot → join 自己 worker
- `ServerStoppedEvent` 时 vanilla saveEverything 已完成 (含 level.dat), BetterBackup 收尾复制 level.dat

时序:

```
T0: ServerStoppingEvent fire (NORMAL) → BAS handler 开始
T1: BAS exporter.stop / enterShutdownMode / drainPending / joinWorkers
    (这期间 BAS 可能 fire 最后一批 chunk listener, BetterBackup queue 接收)
T2: BAS handler 返回
T3: ServerStoppingEvent fire (LOW) → BetterBackup handler 开始
T4: BetterBackup drainOwnQueue + createFinalSnapshot + joinOwnWorker
T5: BetterBackup handler 返回
T6: ServerStoppedEvent (vanilla saveEverything 完成, level.dat 已写)
T7: BetterBackup ServerStoppedEvent 收尾 (复制 level.dat 到 store)
```

### 3.7 metrics (BetterBackup 自己开 Prometheus 端口)

BAS v0.9 `PrometheusExporter` 不开放第三方注册 (`PrometheusFormatter` 是 private static, 无 plugin API). BetterBackup **自己开端口** (默认 9451 避开 BAS 9450).

暴露 metrics (跟 BAS 同风格, 用 `bbb_` 前缀避免命名冲突):

- `bbb_chunks_dedup_total` (counter): 命中已有 hash 的 chunk 数
- `bbb_chunks_unique_total` (counter): 写入 store 新 hash 的 chunk 数
- `bbb_store_bytes` (gauge): store 当前总字节
- `bbb_store_unique_count` (gauge): store unique hash 数
- `bbb_snapshot_count` (gauge): 当前保留的 snapshot 数
- `bbb_backup_worker_queue_depth` (gauge)
- `bbb_backup_io_seconds` (histogram): 单 chunk read+hash+store 耗时
- `bbb_gc_seconds` (histogram): 增量 / 全量 GC 耗时
- `bbb_dedup_ratio_recent` (gauge): 最近一次 snapshot 的 dedup 命中率

实现复用 BAS 同款思路 (JDK HttpServer + 自己写 exposition formatter). 配置:

```toml
[prometheus]
enabled = false
bindAddress = "0.0.0.0"
port = 9451
```

未来系列 mod 都有 metrics 后再考虑做统一 dashboard, 跟 BAS ROADMAP 中 "WebUI 推迟到系列上线后" 对齐.

## 4. 恢复 (Restore) 设计

### 4.1 全量恢复

```
/betterbackup restore <snapshot-id> [--dry-run]
```

服务端运行中下达命令时不立即执行, 写一个 `.pending-restore` flag 文件, 提示玩家手动停服, 下次启动时自动跑 restore.

流程:

**A. 命令阶段 (server 运行中)**:
1. 加载 manifest
2. 校验 dedup store 完整性 (每个 referenced hash 都在 store 内)
3. 写 `<world>/.shinoyuki-pending-restore` flag 文件, 内容为 snapshot-id + 校验结果
4. 命令返回 "restore prepared, please stop server now"

**B. 启动阶段 (server 重启后)**:
5. `BetterBackupMod.onServerAboutToStart` 检测到 `.shinoyuki-pending-restore` flag
6. 此时 vanilla 还没开始 chunk loading, mod 已加载可用 vanilla classes
7. 删除 / 移动现有 `world/region/` `world/entities/` `world/data/` `world/level.dat` 到 `world.bak-<timestamp>/`
8. 按 manifest 遍历每个 (dim, x, z) → hash:
   - 从 store 读 raw chunk bytes (vanilla zlib 压缩格式)
   - 用 vanilla `RegionFile.getChunkOutputStream(ChunkPos)` 写入对应 .mca slot
9. 同样恢复 entityChunks / savedData / level.dat
10. 删除 `.shinoyuki-pending-restore` flag
11. 服务端继续正常启动流程

**RegionFile 复用策略**: vanilla `net.minecraft.world.level.chunk.storage.RegionFile` 已经是 self-contained 的工具类 — `getChunkOutputStream(ChunkPos)` 是 public, ctor 只要 `Path` (不需要 MinecraftServer 实例). BetterBackup mod 加载状态下能直接 link vanilla RegionFile, 不需要重新实现 `RegionFileWriter`. 节省 ~400 行代码 + 跟 vanilla format 自动同步.

**关于"mod 没加载"的疑虑**: restore 阶段 mod 已加载 (`onServerAboutToStart` 在 mod 加载后), 不需要 standalone 工具. 这跟原草案的"mixin 不行因为 mod 没加载"前提不同 — 我们走 mod 加载后的 lifecycle 钩子, 不走外部工具.

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
- **每次创建 snapshot 后立即增量 GC** (新增, 见 §2.6): 仅扫本次 BackupWorker 写入但未被 manifest 引用的 hash. scope 远小于全量 GC, 秒级完成. 防止 store 在两次 snapshot 间膨胀 (大服可达几 GB / 2h)

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
# 默认外置 (跟 world/ 同级), 避免 rsync world/ 时把 store 也带走形成套娃.
# 路径相对 server root.
backupDirectory = "backup-store"

[storage]
# 默认 xxh128 (跟 PrimeBackup 一致, 比 sha256 快 5-10x).
# 实际 hash 单元是 .mca 文件里的 chunk slot raw bytes, 不解 NBT.
# sha256 留作可选 (verify / 完整性敏感场景).
hashAlgorithm = "xxh128"                      # xxh128 | sha256 | blake3
# .mca chunk slot 已经是 vanilla zlib 压缩, 不再二次压缩 (避免无效压缩).
# 此项保留给 SavedData / level.dat 等未压缩文件.
compressionAlgorithm = "none"                 # none | zstd
compressionLevel = 0                          # zstd 1-22 (compressionAlgorithm=zstd 时生效)
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
# CPU 密集 (hash + 可选压缩), 不是 IO 密集.
# 大服 autosave 周期内 BAS 可能一次性派发几千 chunk, 单线程会成 bottleneck.
# 默认按 CPU 核心数推算, 1 给小机, 2-4 给大服.
backupWorkerThreads = 2                       # 备份 worker 池大小

[safety]
verifyOnStartup = true                        # 启动时扫 store 一致性
verifyOnSnapshot = false                      # 每次备份后校验所有 hash (慢, 默认关)
panicOnHashMismatch = false                   # hash 不一致时是否 crash

[prometheus]
enabled = false                               # 默认 opt-in
bindAddress = "0.0.0.0"                       # 公网服请用防火墙挡端口或改 127.0.0.1
port = 9451                                   # 避开 BAS v0.9 的 9450
```

## 8. 关键风险 + 缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| xxh128 hash 碰撞 | 10^-19 量级 | 数据错乱 | 不缓解, 概率比硬盘 bit flip 仍低数个数量级. 完整性敏感场景可切 sha256 |
| dedup store 文件被外部修改 / 损坏 | 中 | 该 hash 引用的 chunk 恢复时崩 | startup 校验扫文件大小 + 抽样 hash, mismatch 时 quarantine 并 ERROR |
| manifest 写一半断电 | 低 | 该快照不可用, 但 store 没污染 | manifest 写临时文件 + atomic rename, 写一半的临时文件 startup 时清理 |
| store 文件写一半断电 | 低 | 部分 hash 文件半写, 启动 verify 失败 | store 文件先写 `<hash>.tmp` + fsync + atomic rename `<hash>`, 启动时清理孤立 .tmp |
| GC 跟 dedup write race | 中 | 新备份引用刚被 GC 删的 hash | GC 阶段持 store-wide write lock, 或用引用计数防漏删 |
| 用户在恢复中途中断 | 中 | world 目录半残 | restore 写到 `world.tmp/`, 完成后才 atomic move |
| BAS 升级后 listener API 改了 | 中 | BetterBackup 不兼容 | BAS API package 已稳定公开 (v0.7+), 严格 semver, mods.toml 用 versionRange 锁定下界 |
| 备份过程异常导致 worker 死 | 低 | 后续备份不工作 | worker 异常 → degraded mode (类似 BAS), DiagnosticLogger 报警, 不 crash 服务端 |
| .mca chunk slot 字节因 BAS 重写而变 (LastUpdate / InhabitedTime 字段) | 高 | 已加载 chunk 在 store 里产生很多 unique hash, 中间版本 unreferenced | 接受 — 已加载 chunk 仅占 ~1% (大服), 整体 dedup 率 98%+ 不受影响. 增量 GC (§2.6) 清掉中间版本 |
| chunk 内存 NBT put 顺序变化 (mod 改 NBT / data fixer / tag.merge) | 高 | 如果对内存 NBT hash 会让 dedup 局部归零 | **方案 A 已规避**: 不读内存 NBT, 直接读 .mca 磁盘字节, 跨 JVM 字节恒等 |
| 服主同时跑 FTB Backups 等老 mod | 中 | 双备份冲突 (磁盘空间 / 锁竞争) | 在 README 警告, 不主动检测 |
| store 路径在 NFS / SMB 远程文件系统 | 中 | 性能差 + 锁语义不一致 | 启动时检测 store 路径 mount type, NFS/SMB 时 WARN |
| kill -9 时正在备份 | 中 | manifest 半写 / store 漏文件 | manifest atomic rename 已防一半, store 文件 fsync 后才写 manifest, 引用尚未 fsync 的 hash 自动重写 |
| BackupWorker 跨 mod 读 .mca 文件跟 vanilla IOWorker 写竞争 | 中 | 读到 partial 写或撞 lock | BackupWorker 用 read-only FileChannel 打开 .mca, 跨进程在 OS 层级 vanilla 写 sector atomic; 实施时验证 |

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

1. ~~**BAS API jar 还是 IMC**~~: 已决, BAS v0.7+ API package 公开稳定, 直接 link (§3.3)
2. **manifest 格式 NBT 还是自定义二进制**: §2.3, 倾向 NBT (简单), 性能不够再换
3. ~~**store 默认放 world/ 目录还是独立目录**~~: 已决, 默认外置 `backup-store/` 跟 `world/` 同级 (§7)
4. **vanilla autosave 时机协调**: 是否在 vanilla autosave 周期内主动触发 snapshot, 保证一致性? MVP 阶段不协调, 接受 partial-snapshot 风险 (个别 chunk 在 snapshot 启动到完成期间又变化, 该 chunk 用新 hash, 跟其他 chunk 时间点不一致). v0.2 考虑加锁保证 atomicity
5. **客户端兼容**: BetterBackup 是 server-only mod, 客户端不需要装. mods.toml 声明 `side="SERVER"`. 但需要测试客户端连服时 mods sync 不报 missing
6. **BackupWorker 读 .mca 跟 vanilla IOWorker 写的并发**: §8 已列为风险, 实施 MVP Phase 1 时优先验证. 如果 vanilla `RegionFile` 内部 lock 保证 sector atomic write, BackupWorker read-only FileChannel 可安全读; 否则需要短暂等待 IOWorker 完成 (BAS fire 时已经完成, 但同 .mca 文件其他 chunk 仍可能在写)
7. **Entity region file 与 SavedData 文件路径**: vanilla 1.20.1 entity 是 `entities/r.x.z.mca`, SavedData 是 `data/<name>.dat` 或 `DIM-1/data/<name>.dat`. BackupWorker 需要从 listener 给的 dimension/fileName 推路径, 实施 MVP Phase 1 整理路径映射表
