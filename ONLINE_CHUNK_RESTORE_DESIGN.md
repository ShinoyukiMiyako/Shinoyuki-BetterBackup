# 在线单 chunk 回退 — 最终实现文档

| 字段 | 值 |
|---|---|
| 状态 | 设计定稿, 待实现 (BAS 单独分支 + 自己发版, 不塞进异步加载 beta) |
| 目标 | 活服运行中, 把**已加载**(玩家视距内)的单个 chunk 回退到某快照态; 纯服务端, 玩家**不装** mod; **不卡主线程** |
| 依据 | 真实反编译 1.20.1 (forge 47.3.22 / parchment 2023.09.03) + WorldEdit 可行性参照 + BAS load 路径现有机器 |
| 硬约束 | 主线程不得逐方块 setBlockState (WorldEdit 卡顿之源); 贵的活全部下沉 worker/光照线程 |

## 0. 一句话方案

worker 线程反序列化快照 NBT -> 主线程**原地整段替换** live `LevelChunk` 的 sections/方块实体/高度图 (O(1 个 chunk) 引用替换, 不逐方块) -> 异步光照 -> 整块重发给跟踪玩家 -> 标 unsaved 交 vanilla 正常存盘。**不 evict、不写盘直改、不撕裂、不卡。**

之前那套 BAS `evict/suppressSave/reload` 重协议是为「未加载 chunk 写盘+重载」设计的; 对「玩家盯着的已加载 chunk」(唯一有意义的场景) **不需要它**。本文档取代那套。

## 1. 为什么不卡 (你最在意的)

| | WorldEdit (卡) | 本方案 (不卡) |
|---|---|---|
| 主线程工作量 | O(百万方块) `setBlockState` + 逐块邻居更新 | O(1 个 chunk) 引用替换 + 发包 ≈ vanilla 加载一个 chunk |
| 反序列化 (贵, DFU/Codec) | 主线程 | **worker 线程** (复用 BAS `ChunkLoadTask`) |
| 光照传播 (贵) | 部分同步 | **`ThreadedLevelLightEngine` 异步** (独立邮箱, `ThreadedLevelLightEngine:46-48`) |

单 chunk 回退主线程开销 ≈ vanilla 平时加载一个 chunk (每秒一堆, 不卡)。大面积回退 = 每 tick 装 N 个 chunk 摊开, 不堆尖峰。

## 2. 端到端流程

```
BB: /betterbackup restore-chunk-live <id> <dim> <x> <z>   (命令, 主线程入口)
 └─ BB: store.get(hash) -> 还原 slot 完整字节 -> 按 compType 解压 -> NbtIo.read -> vanilla CompoundTag
     └─ BB: server.execute(() -> BAS.SaveCoordination.restoreChunkLive(level, pos, restoredTag))
         └─ BAS [worker]: ChunkSerializer.read(level, poi, pos, tag) -> ImposterProtoChunk (反序列化, 贵, 离主线程)
             └─ BAS [main, future hop]: LevelChunkMixin.replaceTerrainFrom(source) (原地替换, 廉价)
                 ├─ 异步: ThreadedLevelLightEngine 重算光照
                 ├─ 重发: ChunkMap.getPlayers(pos) 每人 ClientboundLevelChunkWithLightPacket + BE data
                 └─ 标 chunk.unsaved=true -> vanilla 正常存盘 (经 BAS save 管线, 串行不撕)
                     └─ 返回 CompletableFuture<ChunkRestoreResult> -> BB 据 outcome 反馈玩家
```

---

# 3. BAS 侧要改什么

> 双加载器: 以下 forge 树为准, neoforge 树做对应孪生 (api 同名、mixin 等价)。

## 3.1 新 API (`api` 包 — 新类别: 主动写 + 有返回 + 暴露失败)

现有 api 全是 swallow-and-log 的观察者总线 (无返回)。restore 是新类别, 新增:

```java
// api/SaveCoordination.java  (静态门面, 与 SaveListenerRegistry 并列)
public final class SaveCoordination {
    /**
     * 活服把已加载 chunk 的地形回退到 restoredTag。必须在 server 主线程调 (或内部 marshal 到主线程)。
     * 贵的反序列化在 BAS load worker, 主线程只做廉价安装 + 发包。
     */
    public static CompletableFuture<ChunkRestoreResult> restoreChunkLive(
            ServerLevel level, ChunkPos pos, CompoundTag restoredTag);
}

public enum ChunkRestoreOutcome {
    OK,
    REJECT_DISABLED,    // BAS 未装 / load.enabled=false / FULL 兼容模式
    REJECT_DEGRADED,    // 管线降级期, 拒绝在线写
    REJECT_NOT_LOADED,  // pos 当前未加载 (见 §3.7: 未加载走轻量孪生, 不在本 API)
    PARSE_FAILED,       // ChunkSerializer.read 抛 (NBT 损坏 / 版本不符)
    INSTALL_FAILED      // 主线程安装中途异常 (已回滚)
}

public record ChunkRestoreResult(ChunkRestoreOutcome outcome, ChunkPos pos, Throwable cause) {}
```

设计取舍 (已定): **高层单操作, 不暴露 evict/suppress 等原语。** 顺序正确性归拥有不变量的一方 (BAS); BB 只给一个事务性动词「把这份字节变成活区块」。

## 3.2 `LevelChunk` mixin — 原地地形替换 (核心)

**为什么不能换整个 LevelChunk 对象 (方案 A 死路)**: `ChunkHolder` 的 `fullChunkFuture/tickingChunkFuture` 已 `complete` 锁死对旧 `LevelChunk` 实例的引用; 换字段无用。**方案 B 保对象 identity、原地改内容**, 这些 future 自然继续指向同一对象 (新内容)。

新增 `mixin/LevelChunkRestoreMixin.java` (`@Mixin(LevelChunk.class)`, `@Shadow` 出私有字段), 加方法 (主线程调用):

```java
// 伪代码; 实际在 mixin 内可直接访问 LevelChunk 私有字段
public ChunkRestoreOutcome betterautosave$replaceTerrainFrom(ChunkAccess source) {
    // 0. 等高守卫: source 与 this 的 section 数必须相等 (同世界天然满足, 不等即拒)
    if (source.getSectionsCount() != this.getSectionsCount()) return INSTALL_FAILED;
    try {
        // 1. 标光照失效 (替换期间)
        this.setLightCorrect(false);                                  // ChunkAccess:378-383
        // 2. 解绑旧方块实体 (onChunkUnloaded + setRemoved + 解绑 ticker)
        this.clearAllBlockEntities();                                 // LevelChunk:574-582  [风险 R1]
        this.betterautosave$clearBlockEntityTickers();                // 清 tickersInLevel + game event listeners  [风险 R2/R5]
        // 3. 整段替换 sections (引用拷贝, 非逐方块)
        System.arraycopy(source.getSections(), 0, this.sections, 0, this.sections.length); // ChunkAccess:81,94
        // 4. 替换高度图 (深拷贝 long[] 原始数据)
        for (Heightmap.Types t : Heightmap.Types.values())
            this.setHeightmap(t, source.getHeightmap(t).getRawData().clone());             // ChunkAccess:163-165  [风险 R3]
        // 5. 替换 tick 容器 (先注销旧, 换, 再注册新)
        this.betterautosave$swapTickContainers(source);              // unregister->swap->register  [风险 R4]
        // 6. 装新方块实体 + pending BE NBT, 重挂 ticker / game event listener / 提升 pending
        this.betterautosave$installBlockEntities(source);            // setBlockEntity + setBlockEntityNbt
        this.registerAllBlockEntitiesAfterLevelLoad();               // LevelChunk:584-594  [风险 R2/R4]
        // 7. 触发光照重算 (异步引擎; 镜像 vanilla load 路径, 不逐方块 checkBlock)
        this.betterautosave$requestLightForChunk();                  // updateSectionStatus per section + propagateLightSources(pos) + skyLightSources.fillFrom  [风险 R6]
        // 8. 标 unsaved -> 交 vanilla 正常存盘持久化
        this.setUnsaved(true);                                        // ChunkAccess:254-256
        return OK;
    } catch (Throwable t) {
        // 安装中途失败: 尽力回滚 (见 §3.6), 返回 INSTALL_FAILED
        return INSTALL_FAILED;
    }
}
```

注意 (源码核实): `ChunkSerializer.read` 返回的是 `ImposterProtoChunk` 包着的内容 (`ChunkSerializer.java:217`), 取 `getSections()/getHeightmap()/getBlockEntities()/pendingBlockEntities` 即可; 这些字段在 `ChunkAccess` 是 `protected`。

## 3.3 复用 `ChunkLoadTask` 在 worker 反序列化

`ChunkLoadTask` 本就在 load worker 上跑 `ChunkSerializer.read(level, poi, pos, tag)` (`ChunkLoadTask.java:99`), 从不读盘。`restoreChunkLive` 内部:
- 构造一个 `ChunkLoadTask`(或等价精简任务) 喂入 `pipeline.loadWorkerQueue()`, tag = restoredTag。
- 它的 `result()` (CompletableFuture<LoadResult>) 经 `thenApplyAsync(mainThreadExecutor)` 续上 §3.2 的主线程安装 (与现有 `ChunkMapLoadMixin` replay-stage 同模式)。
- 解析期间截走的 POI/光照/事件副作用 (`LoadDeferredActions`) 在主线程回放, 与正常 load 一致。

## 3.4 主线程安装后: 重发给原版客户端 (纯服务端)

源码核实可行: 给**已跟踪**该 chunk 的 `ServerPlayer` 再发一次 `ClientboundLevelChunkWithLightPacket`, 原版客户端整块覆盖、不报错 (无 packet 级去重, 仅覆盖语义)。

```java
LevelLightEngine le = level.getChunkSource().getLightEngine();
ClientboundLevelChunkWithLightPacket pkt =
        new ClientboundLevelChunkWithLightPacket(liveChunk, le, null, null); // 构造时自带光照, :17-23
for (ServerPlayer p : chunkMap.getPlayers(pos, false)) {                     // ChunkMap.java:1155
    p.connection.send(pkt);
    // 补发方块实体数据 (full chunk 包里 BE 是构造那刻快照, 容器/比较器需补)  [风险 R-net]
    for (BlockEntity be : liveChunk.getBlockEntities().values()) {
        Packet<?> up = be.getUpdatePacket();
        if (up != null) p.connection.send(up);
    }
}
// 若 biome 变了: 补发 ClientboundChunksBiomesPacket (ChunkMap.java:1280)  [一般 restore 不变, 可跳]
// 失效 ServerChunkCache 的 4-slot LRU, 防下次 getChunk 拿旧引用 (本方案对象 identity 不变, 内容已改, 此步保险)
level.getChunkSource().clearCache();                                          // ServerChunkCache.java:191-195
```

不要用 `playerLoadedChunk()` 整套 (它会重跑实体追踪 -> 重复 AddEntity 包, `ChunkMap:1294-1306`); 直接 `connection.send(pkt)` + BE 包即可。

## 3.5 门禁 + busy 检查 (语义反转: 返回原因枚举, 不静默 no-op)

`restoreChunkLive` 开头 (主线程):
- 复用 `ChunkMapLoadMixin:87-94` 同款门禁: `isInstalled / loadEnabled / !FULL / !degraded / loadPoolActive` -> 任一不过返回 `REJECT_DISABLED`/`REJECT_DEGRADED`。
- null 守卫 (`:95-99` 的 metrics/server) 防 NPE。
- `level.getChunkSource().getChunkNow(x, z)` 为 null -> pos 未加载 -> `REJECT_NOT_LOADED` (走 §3.7)。

## 3.6 失败回滚 (原子性: 要么完整生效要么不生效)

§3.2 安装是多步 mutate, 中途抛要尽力回滚到「旧内容仍在内存」:
- 在 clear/swap 之前对 live chunk 现状做一次轻量快照 (sections 引用 + BE map + heightmaps 引用), catch 内还原。
- 实务上更稳: **安装序列设计成「先全部算好新值, 最后一次性赋值」**, 把可抛点 (反序列化) 全留在 worker 阶段; 主线程安装只剩纯赋值 + 队列入队, 极难抛。这样 INSTALL_FAILED 近乎不可能, 回滚是兜底。

## 3.7 持久化 + 未加载孪生

- **持久化**: §3.2 标 `unsaved=true`, 交 vanilla 正常 autosave (经 BAS save 管线串行落盘, 不撕裂)。**不需要** suppressSave 四点协同 (那是旧「直写盘」方案的负担; 本方案不直写盘, in-memory = restored 即真理, 正常存盘自然写 restored)。
  - 残留窗口 (诚实): 若回退前该 chunk 已有一份 in-flight 旧版存盘在途, 它可能先把旧版落盘, 下个 save 周期才被 restored 覆盖。可选加固: 安装后对该 pos 触发一次立即 save (BAS save 管线的单 chunk 提交), 把 restored 钉到盘上。
- **未加载孪生** (`REJECT_NOT_LOADED` 之后 BB 怎么办): 活服上 BB **绝不能**自己直写 `.mca` (与 IOWorker 撕裂)。未加载 chunk 的活服回退给一个轻量孪生 `SaveCoordination.writeChunkLive(level, pos, tag)`: 仅经 BAS 的 `AsyncIoBridge.storeChunk` 串行写盘 (无 evict/无内存安装), 下次自然冷加载读到 restored。或第一版先不支持「未加载 + 活服」, 让 BB 对未加载 chunk 走离线 CLI / 重启路径。

## 3.8 测试 (诚实: 现状无 GameTest)

- **L1 纯逻辑单测** (现有 JUnit + Bootstrap 即可): restoredTag -> ChunkSerializer.read -> 断言 sections/BE/heightmap 字段正确。
- **L2 ASM 闸门回归**: mixin 注入点存在性 (防 mapping 漂移后静默失效)。
- **GT 端到端** (**需新建 harness**, forge 模块当前无): `forge/build.gradle` 加 gametest sourceSet + `gameTestServer` run。用例 GT-1: 装世界 -> 改某 chunk 方块/箱子 -> `restoreChunkLive(旧tag)` -> 断言方块+容器内容回滚、`isLightCorrect` 最终 true、无 BE ticker 泄漏。GT-2: 回滚后强制该 chunk 卸载再自然加载, 断言读盘仍是 restored (证明持久化真生效)。

---

# 4. BetterBackup 侧要改什么

## 4.1 快照 slot 字节 -> vanilla `CompoundTag` (新, mod 侧)

BB 快照存的是 `.mca` slot 原始字节 = `compType byte + 压缩 chunk NBT` (external 则 stub + .mcc, BB store 对象已含完整字节)。新增 mod 侧工具 (用 vanilla `NbtIo`):

```java
// 还原成 vanilla CompoundTag
byte[] obj = store.get(hash);                 // BB 已有 (external 已拼全)
int compType = obj[0] & 0x7f;                 // 剥 0x80 external 位
InputStream in = decompressByType(compType, obj, 1);  // 2=zlib(Inflater), 1=gzip(GZIP), 3=none
CompoundTag tag = NbtIo.read(new DataInputStream(in));
```

注意: 这是 mod 侧 (有 vanilla 依赖), 不在零依赖离线 CLI 里。

## 4.2 `ChunkRestoreFlow` (新, mod 侧, restore/ 包)

- 输入: snapshotId, dimId, chunkX, chunkZ。
- 读 manifest -> 取该 (dim, packedPos) 的 hash -> `store.has` 校验 -> §4.1 还原 CompoundTag。
- 主线程: `server.execute(() -> SaveCoordination.restoreChunkLive(level, pos, tag).whenComplete(...))`。
- 据 `ChunkRestoreResult.outcome` 反馈玩家 (见 §4.4)。
- 未采集 (manifest 无此 chunk) -> 明确报错, 不静默。

## 4.3 命令 `/betterbackup restore-chunk-live <id> <dim> <x>,<z>` (新)

- 与现有 `/betterbackup restore <id>` (全量, 写 PendingRestoreFlag 重启执行) 并列。
- 与离线 CLI `restore --chunk` 分流: 在线命令走 ChunkRestoreFlow; 离线 CLI 仍走 OfflineRestore (停服直写, 已交付)。
- baseline 门禁: 在线单 chunk 不要求 baselineComplete (只取已采集的那一个), 与离线部分回退一致。

## 4.4 `REJECT_*` 的 UX (BB 担, 不下沉 BAS)

- `REJECT_DEGRADED` -> 「BAS 降级中, 在线回退不可用, 请走离线/重启回退」。
- `REJECT_NOT_LOADED` -> 「该 chunk 当前未加载」-> BB 自动改调 `writeChunkLive` 孪生 (§3.7), 或提示用离线。
- `PARSE_FAILED`/`INSTALL_FAILED` -> 报原因 + 不留半个 chunk 的保证说明。
- (注: 本方案**不再有** REJECT_BUSY —— in-memory 替换不需要 chunk 未被 ticket 钉死, 这正是它相对 evict 方案的根本优势, 解决了「玩家在附近就退不了」。)

## 4.5 依赖与版本

- `gradle.properties`: `bas_version` 升到带 `SaveCoordination` 的 BAS 版本。
- 编译期 link 新 api (`com.shinoyuki.betterautosave.api.SaveCoordination` 等), 运行期 mods.toml 已 mandatory 依赖 BAS。

## 4.6 测试 (BB 侧)

- §4.1 字节->CompoundTag 还原: 单测 (round-trip: 已知 chunk NBT -> 压成 slot 字节 -> 还原 -> 字段相等)。
- ChunkRestoreFlow 的 manifest 解析 / 未采集报错 / outcome 分发: 单测 (mock SaveCoordination)。
- 端到端 (装载真世界回退): 落在 BAS 的 GameTest (§3.8), BB 侧不重复造 server harness。

---

# 5. 语义边界 (文档钉死)

- **回滚**: chunk-NBT 内的一切 —— 方块、方块实体 (箱子/告示牌/modded BE 全部 NBT + ForgeCaps)、高度图、光照、结构引用。
- **不回滚**: 自由实体 (怪/掉落物/物品展示框, 1.17+ 在独立 `entities/`); playerdata (在线不动玩家存档); POI (下次 load 的 `checkConsistencyWithBlocks` 从方块自愈)。
- **原子粒度**: 单 chunk。一次回一片 = 多个独立单 chunk 事务 (每 tick 摊 N 个)。
- **副作用语义**: 原地换 section **绕过 setBlockState**, 故不触发邻居/红石/流体更新 —— 对「精确回到快照态」是**想要的** (不级联); 代价是回出来的状态若与邻居不自洽 (浮空沙/断红石), 等玩家碰一下才结算。反破坏场景无所谓。
- 这套语义正适合**反破坏 / 修坏块**: 地形+容器回档, 不复活怪、不消失掉落物。

# 6. 风险清单 (workflow 核实 + 缓解)

| ID | 风险 | 级 | 缓解 |
|---|---|---|---|
| R1 | `clearAllBlockEntities()` 调旧 BE 的 onChunkUnloaded/setRemoved, mod 回调可能有副作用 (持久化/日志) | major | 替换前若需保旧态先快照; 或评估常见 BE 回调无害, 接受 |
| R2 | ticker 重挂遗漏 / 新旧 BE ticker 类型不同 (箱子->熔炉) 重绑失败 | major | clear 时同步清 `tickersInLevel`; 装完 `registerAllBlockEntitiesAfterLevelLoad()`; chunk 必须 isInLevel=true 时调 |
| R3 | heightmap 与新方块不一致 | major | 用 source 的 raw long[] 深拷贝; 或 `Heightmap.primeHeightmaps` 重建 |
| R4 | pending BE 未提升 / tick 容器注册混乱 | major | `registerAllBlockEntitiesAfterLevelLoad` 提升 pending; tick 容器 unregister->swap->register |
| R5 | game event listener 泄漏/双注册 | minor | clear 后 register 重注册 (LevelChunk:589) |
| R6 | 光照引擎残留旧 section 光数据 / 天空光源跨 section 遮挡漏 | major | 镜像 vanilla load 光照路径 (NBT 光 + initializeLightSources); 必要时 `skyLightSources.fillFrom` 整列 |
| R7 | 世界等高不同 -> section 数不匹配 | blocker(理论) | 同世界天然相等; 安装前等高守卫, 不等即 INSTALL_FAILED |
| R-net | 重发后 BE 数据不同步 (容器 GUI/比较器) | major | 重发 full chunk 包后逐 BE 补 `getUpdatePacket()` |
| R-cache | ServerChunkCache 4-slot LRU 拿旧引用 | minor | 安装后 `clearCache()` (本方案对象 identity 不变, 此步保险) |
| R-persist | in-flight 旧版存盘在残留窗口先落盘 | minor | 安装后触发一次该 pos 立即 save 把 restored 钉盘 |

# 7. 落地次序

1. **BAS 分支**: api `SaveCoordination`+枚举/record (双树) -> `LevelChunkRestoreMixin` (原地替换 9 步 + 私有字段 shadow) -> `restoreChunkLive` 编排 (worker 解析 + 主线程安装 + 重发 + 门禁/回滚) -> GameTest harness + GT-1/GT-2 -> 发带新 API 的 BAS 版本。
2. **BB**: `bas_version` 升 -> §4.1 字节->CompoundTag + 单测 -> `ChunkRestoreFlow` -> 命令 `restore-chunk-live` + outcome UX -> 与离线部分回退分流。

> 工作量诚实评估: 一个完整 minor 特性 (碰 LevelChunk mixin + 新 api + 新 GameTest harness + BB 对接)。建议 BAS 独立分支独立发版, 不并入异步加载 beta。
