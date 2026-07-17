package com.shinoyuki.betterbackup.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.shinoyuki.betterautosave.api.ChunkRestoreOutcome;
import com.shinoyuki.betterautosave.api.ChunkRestoreResult;
import com.shinoyuki.betterautosave.api.SaveCoordination;
import com.shinoyuki.betterbackup.BetterBackupCore;
import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.baseline.BaselineProgress;
import com.shinoyuki.betterbackup.config.BetterBackupConfig;
import com.shinoyuki.betterbackup.gc.StoreGc;
import com.shinoyuki.betterbackup.restore.ChunkRestoreFlow;
import com.shinoyuki.betterbackup.restore.ChunkRestoreMessages;
import com.shinoyuki.betterbackup.restore.PendingRestoreFlag;
import com.shinoyuki.betterbackup.retention.RetentionGuard;
import com.shinoyuki.betterbackup.retention.RetentionPruner;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class BetterBackupCommand {

    private static final int OP_LEVEL = 2;

    private static final int MAX_RADIUS = 8;
    // 区域回退确认阈值: 超过该块数 (即 radius>=2, 25 块起) 才要二次 confirm; <=9 块 (radius<=1) 直接执行.
    private static final int CONFIRM_THRESHOLD = 9;
    private static final long CONFIRM_TTL_MS = 30_000L;

    // 每个来源 (玩家 UUID / 控制台) 至多一笔待确认区域回退; confirm 时消费. 内存态, 重启即清.
    private static final Map<String, PendingArea> PENDING_RESTORE = new ConcurrentHashMap<>();

    // <id> 参数 tab 补全: latest 关键字 + 现有快照 (newest first, 上限 20).
    private static final SuggestionProvider<CommandSourceStack> SNAPSHOT_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("latest");
        SnapshotCreator creator = BetterBackupCore.creator();
        if (creator != null) {
            for (String id : listSnapshotIds(creator, 20)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("betterbackup")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(Commands.literal("status").executes(BetterBackupCommand::status))
                        .then(Commands.literal("snapshot")
                                .then(Commands.literal("create")
                                        .executes(ctx -> snapshotCreate(ctx, null))
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> snapshotCreate(ctx,
                                                        StringArgumentType.getString(ctx, "name")))))
                                .then(Commands.literal("list").executes(BetterBackupCommand::snapshotList))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(ctx -> snapshotInfo(ctx,
                                                        StringArgumentType.getString(ctx, "id")))))
                                .then(Commands.literal("delete")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(ctx -> snapshotDelete(ctx,
                                                        StringArgumentType.getString(ctx, "id"))))))
                        .then(Commands.literal("restore")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> restore(ctx,
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("restore-chunk-live")
                                // 零参: 玩家站位 + 最新快照 + 单块
                                .executes(ctx -> restoreChunkLivePlayer(ctx, null, 0))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(SNAPSHOT_SUGGESTIONS)
                                        // <id>: 玩家站位 + 指定快照 (或 latest) + 单块
                                        .executes(ctx -> restoreChunkLivePlayer(ctx,
                                                StringArgumentType.getString(ctx, "id"), 0))
                                        // <id> <radius>: 玩家站位 + 半径. integer 子节点先于 dim(string) 注册,
                                        // 保证纯数字解析为半径而非维度.
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                                                .executes(ctx -> restoreChunkLivePlayer(ctx,
                                                        StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))
                                        // <id> <dim> <x> <z> [radius]: 显式形态 (控制台/RCON, 无玩家位置)
                                        .then(Commands.argument("dim", StringArgumentType.string())
                                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> restoreChunkLiveExplicit(ctx,
                                                                        StringArgumentType.getString(ctx, "id"),
                                                                        StringArgumentType.getString(ctx, "dim"),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"), 0))
                                                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                                                                        .executes(ctx -> restoreChunkLiveExplicit(ctx,
                                                                                StringArgumentType.getString(ctx, "id"),
                                                                                StringArgumentType.getString(ctx, "dim"),
                                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                                IntegerArgumentType.getInteger(ctx, "radius")))))))))
                        .then(Commands.literal("confirm").executes(BetterBackupCommand::confirmPendingRestore))
                        .then(Commands.literal("gc").executes(BetterBackupCommand::gc))
                        .then(Commands.literal("retention")
                                .then(Commands.literal("preview").executes(BetterBackupCommand::retentionPreview)))
        );
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        if (!BetterBackupCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup is not installed"));
            return 0;
        }
        CurrentSnapshotState state = BetterBackupCore.snapshotState();
        ChunkStore store = BetterBackupCore.store();
        SnapshotCreator creator = BetterBackupCore.creator();
        int queueDepth = BetterBackupCore.queue() != null ? BetterBackupCore.queue().size() : 0;
        StringBuilder out = new StringBuilder();
        out.append("=== BetterBackup ===\n");
        out.append("Mode: ").append(BetterBackupConfig.scheduleMode()).append('\n');
        out.append("Hash: ").append(BetterBackupConfig.hashAlgorithm()).append('\n');
        out.append("Workers: ").append(BetterBackupConfig.backupWorkerThreads()).append('\n');
        out.append("Queue depth: ").append(queueDepth).append('\n');
        if (state != null) {
            out.append("Dirty (since last snapshot): chunks=").append(state.chunkCount())
                    .append(" entity=").append(state.entityChunkCount())
                    .append(" savedData=").append(state.savedDataCount()).append('\n');
        }
        if (store != null) {
            out.append("Store: ").append(store.storeRoot());
            if (!BetterBackupCore.isReady()) {
                out.append(" [INITIALIZING - background index rebuild, backups arm when done]");
            }
            out.append('\n');
        }
        // baseline 全量扫描进度: 完成前 restore 被拒, 服主据此判断何时可恢复.
        BaselineProgress baselineProgress = BetterBackupCore.baselineProgress();
        if (baselineProgress != null) {
            out.append("Baseline scan: ")
                    .append(baselineProgress.isComplete() ? "COMPLETE" : "IN PROGRESS")
                    .append(" (region files done=").append(baselineProgress.completedRegionCount())
                    .append(")\n");
        }
        // 快照失败可见性: .incomplete 标记存在 = 最近一次快照失败且此后无成功快照.
        // 读失败也展示给服主 (不静默吞), 因为这正是要暴露的健康信号.
        if (creator != null) {
            try {
                creator.failureMarker().read().ifPresent(f -> out
                        .append("Last snapshot: FAILED (").append(f.reason()).append(")\n"));
            } catch (IOException e) {
                out.append("Last snapshot: marker read error (").append(e.getMessage()).append(")\n");
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
        return 1;
    }

    /**
     * 异步创建 snapshot. 命令立即返回, 派 daemon 线程跑 SnapshotCreator (manifest
     * 写盘 + level.dat hash 可能花几秒, 主线程命令执行不应阻塞).
     */
    private static int snapshotCreate(CommandContext<CommandSourceStack> ctx, String name) {
        // 写路径: store 未就绪时 creator.create 会 put level.dat/玩家数据, 必须过就绪门控.
        if (!checkReady(ctx.getSource())) {
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String reason = name != null ? "manual:" + name : "manual";

        Thread worker = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                creator.create(reason);
                long elapsed = System.currentTimeMillis() - t0;
                server.execute(() -> source.sendSuccess(
                        () -> Component.literal("BetterBackup snapshot created (" + elapsed + "ms)"),
                        true));
            } catch (Throwable t) {
                BetterBackupMod.LOGGER.error("[BetterBackup] command snapshot create failed", t);
                server.execute(() -> source.sendFailure(
                        Component.literal("BetterBackup snapshot failed: " + t.getMessage())));
            }
        }, "BetterBackup-Cmd-Create");
        worker.setDaemon(true);
        worker.start();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "BetterBackup snapshot creation started (async)"), false);
        return 1;
    }

    private static int snapshotList(CommandContext<CommandSourceStack> ctx) {
        if (!BetterBackupCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup is not installed"));
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        if (creator == null) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup creator not initialized"));
            return 0;
        }
        Path snapshotsDir = creator.snapshotsDir();
        if (!Files.isDirectory(snapshotsDir)) {
            ctx.getSource().sendSuccess(() -> Component.literal("No snapshots yet"), false);
            return 1;
        }
        List<String> ids;
        try (Stream<Path> stream = Files.list(snapshotsDir)) {
            ids = stream
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - ".manifest".length());
                    })
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Failed to list snapshots: " + e.getMessage()));
            return 0;
        }
        if (ids.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No snapshots yet"), false);
            return 1;
        }
        StringBuilder out = new StringBuilder();
        out.append("=== Snapshots (").append(ids.size()).append(", newest first) ===\n");
        int max = Math.min(20, ids.size());
        for (int i = 0; i < max; i++) {
            out.append("  ").append(ids.get(i)).append('\n');
        }
        if (ids.size() > max) {
            out.append("  ... ").append(ids.size() - max).append(" more\n");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
        return ids.size();
    }

    private static int snapshotInfo(CommandContext<CommandSourceStack> ctx, String id) {
        if (!BetterBackupCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup is not installed"));
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        if (creator == null) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup creator not initialized"));
            return 0;
        }
        Path manifestFile = creator.snapshotsDir().resolve(id + ".manifest");
        if (!Files.exists(manifestFile)) {
            ctx.getSource().sendFailure(Component.literal("Snapshot not found: " + id));
            return 0;
        }
        SnapshotManifest m;
        try {
            m = SnapshotManifest.readFrom(manifestFile);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Failed to read manifest: " + e.getMessage()));
            return 0;
        }
        StringBuilder out = new StringBuilder();
        out.append("=== Snapshot ").append(id).append(" ===\n");
        out.append("createdAt: ").append(m.createdAtMillis()).append(" ms\n");
        out.append("worldGameTime: ").append(m.worldGameTime()).append('\n');
        out.append("schema version: ").append(m.version()).append('\n');
        int totalChunks = m.chunks().values().stream().mapToInt(Map::size).sum();
        int totalEntity = m.entityChunks().values().stream().mapToInt(Map::size).sum();
        out.append("chunks: ").append(totalChunks)
                .append(" across ").append(m.chunks().size()).append(" dim(s)\n");
        out.append("entityChunks: ").append(totalEntity)
                .append(" across ").append(m.entityChunks().size()).append(" dim(s)\n");
        out.append("savedData files: ").append(m.savedData().size()).append('\n');
        out.append("levelDat: ").append(m.levelDat() != null ? "present" : "absent").append('\n');
        for (var dimEntry : m.chunks().entrySet()) {
            out.append("  ").append(dimEntry.getKey()).append(": ").append(dimEntry.getValue().size())
                    .append(" chunks\n");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
        return 1;
    }

    /**
     * 删 manifest 文件 (release reference). 不立即触发 GC, 用户跑 /betterbackup gc
     * 才真正清磁盘. 这样 delete 是 instant 的, GC 是用户主动决策的批操作.
     *
     * <p>删前过 {@link RetentionGuard} 三门禁 (最新一份 / 最新 baselineComplete / pending-restore
     * 目标), 命中则拒绝并给明确原因 —— 与滚动淘汰共用同一套安全网, 防手动误删掉唯一恢复点。
     */
    private static int snapshotDelete(CommandContext<CommandSourceStack> ctx, String id) {
        if (!BetterBackupCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup is not installed"));
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        if (creator == null) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup creator not initialized"));
            return 0;
        }
        Path manifestFile = creator.snapshotsDir().resolve(id + ".manifest");
        if (!Files.exists(manifestFile)) {
            ctx.getSource().sendFailure(Component.literal("Snapshot not found: " + id));
            return 0;
        }
        Path worldRoot = ctx.getSource().getServer().getWorldPath(LevelResource.ROOT);
        String refusal;
        try {
            refusal = deleteRefusalReason(creator.snapshotsDir(), id, worldRoot);
        } catch (IOException e) {
            // 读 manifest / pending flag 失败: 无法证明该 id 可安全删, 拒绝 (不 fail-open).
            ctx.getSource().sendFailure(Component.literal(
                    "Delete refused: could not verify retention guards for " + id + ": " + e.getMessage()));
            return 0;
        }
        if (refusal != null) {
            ctx.getSource().sendFailure(Component.literal("Delete refused: " + id + " " + refusal));
            return 0;
        }
        try {
            Files.delete(manifestFile);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Failed to delete manifest: " + e.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Snapshot " + id + " deleted (run /betterbackup gc to reclaim disk)"), true);
        return 1;
    }

    /**
     * 命中三门禁时返回具体拒绝原因文案, 未命中返回 null (可删). 逐条判定以给出精确原因,
     * 与 {@link RetentionGuard#protectedIds()} 的门禁集一致 (protectedIds 命中 == 此处非 null)。
     */
    private static String deleteRefusalReason(Path snapshotsDir, String id, Path worldRoot) throws IOException {
        RetentionGuard guard = new RetentionGuard(snapshotsDir, worldRoot);
        if (guard.isDeletable(id)) {
            return null;
        }
        // 门禁 C: pending-restore 目标 (先判, 与 baselineComplete 正交, 给出最贴切的原因).
        if (PendingRestoreFlag.read(worldRoot).map(id::equals).orElse(false)) {
            return "is the pending-restore target (a restore is queued for it); cancel the restore first";
        }
        // 门禁 B: 唯一 / 最新 baselineComplete (最后可恢复点); 门禁 A: 最新一份.
        SnapshotManifest manifest = SnapshotManifest.readFrom(snapshotsDir.resolve(id + ".manifest"));
        if (manifest.baselineComplete()) {
            return "is the only / latest baseline-complete snapshot (the last restorable point); "
                    + "keep it or take a fresh snapshot first";
        }
        return "is the latest snapshot; the newest snapshot is always kept";
    }

    /**
     * 写 PendingRestoreFlag 提示玩家手动停服, 重启时 BetterBackupMod
     * onServerAboutToStart 会检测 flag 跑 RestoreFlow.
     */
    private static int restore(CommandContext<CommandSourceStack> ctx, String id) {
        if (!BetterBackupCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup is not installed"));
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        if (creator == null) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup creator not initialized"));
            return 0;
        }
        Path manifestFile = creator.snapshotsDir().resolve(id + ".manifest");
        if (!Files.exists(manifestFile)) {
            ctx.getSource().sendFailure(Component.literal("Snapshot not found: " + id));
            return 0;
        }

        // baseline 门禁: baseline 未完成的快照只覆盖了被加载过的 chunk, 早期 restore
        // 会丢失从未加载的世界部分 (P0). 拒绝并提示当前扫描进度, 让服主等扫描跑完.
        SnapshotManifest manifest;
        try {
            manifest = SnapshotManifest.readFrom(manifestFile);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Failed to read manifest: " + e.getMessage()));
            return 0;
        }
        if (!manifest.baselineComplete()) {
            BaselineProgress progress = BetterBackupCore.baselineProgress();
            String done = progress != null ? Integer.toString(progress.completedRegionCount()) : "unknown";
            boolean nowComplete = progress != null && progress.isComplete();
            ctx.getSource().sendFailure(Component.literal(
                    "Restore refused: snapshot " + id + " was taken before the baseline full scan finished, "
                            + "so it does not contain chunks that were never loaded. Restoring it would lose "
                            + "world data. Baseline scan: " + (nowComplete ? "now COMPLETE" : "IN PROGRESS")
                            + " (region files done=" + done + "). "
                            + (nowComplete
                                    ? "Take a fresh snapshot, then restore that one."
                                    : "Wait for the scan to finish, then take a fresh snapshot and restore it.")));
            return 0;
        }

        Path worldRoot = ctx.getSource().getServer().getWorldPath(LevelResource.ROOT);
        try {
            PendingRestoreFlag.write(worldRoot, id);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Failed to write restore flag: " + e.getMessage()));
            return 0;
        }
        String snapshotId = id;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Restore prepared for " + snapshotId
                        + ". STOP THE SERVER NOW (saving normally is fine, do NOT delete world/)."
                        + " On next startup BetterBackup will auto-restore before vanilla load."
                        + " A backup of current world subdirs will be at <worldRoot>.bak-<timestamp>/."), true);
        return 1;
    }

    /**
     * 在线区块回退 — 玩家简写入口 (DESIGN §4.2/§4.3). 取执行者当前维度与所在区块, idArg 为
     * null/"latest" 时用最新快照. radius>0 时以站位为中心 (2r+1)^2 块区域. 与 {@link #restore}
     * (全量, 写 PendingRestoreFlag 停服重启) 并列: 本路径活服即时执行, 不写 flag, 不停服.
     *
     * <p>baseline 门禁: 在线回退不要求 baselineComplete (只取已采集的目标块, 不会丢失从未加载的
     * 世界部分), 与离线部分回退一致, 故不复用 restore() 的 baseline 拦截.
     */
    private static int restoreChunkLivePlayer(CommandContext<CommandSourceStack> ctx, String idArg, int radius) {
        CommandSourceStack source = ctx.getSource();
        if (!checkReady(source)) {
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(
                    "该简写需由玩家执行 (取你当前维度与所在区块). 控制台/RCON 请用显式形态: "
                            + "restore-chunk-live <id> <dim> <x> <z> [radius]."));
            return 0;
        }
        String id = resolveSnapshotId(source, idArg);
        if (id == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        String dimId = level.dimension().location().toString();
        return dispatchAreaRestore(source, source.getServer(), level, dimId, id, player.chunkPosition(), radius);
    }

    /**
     * 在线区块回退 — 显式坐标入口 (控制台/RCON 无玩家位置). dimId 必须与采集侧写入的
     * ResourceKey&lt;Level&gt;.location().toString() 一致 (见 BackupListenerBridge), 否则既取不到
     * live level 也对不上 manifest 条目. idArg 同样支持 "latest".
     */
    private static int restoreChunkLiveExplicit(CommandContext<CommandSourceStack> ctx,
                                                String idArg, String dimId, int chunkX, int chunkZ, int radius) {
        CommandSourceStack source = ctx.getSource();
        if (!checkReady(source)) {
            return 0;
        }
        ServerLevel level = resolveLevel(source.getServer(), dimId);
        if (level == null) {
            source.sendFailure(Component.literal(
                    "未知维度 " + dimId + " (当前未加载或 id 不正确). 维度 id 形如 minecraft:overworld."));
            return 0;
        }
        String id = resolveSnapshotId(source, idArg);
        if (id == null) {
            return 0;
        }
        return dispatchAreaRestore(source, source.getServer(), level, dimId, id, new ChunkPos(chunkX, chunkZ), radius);
    }

    /**
     * 区域回退分流: 半径 0/1 (&lt;=9 块) 直接执行; 更大区域先登记待确认, 等 /betterbackup confirm,
     * 防一条命令误回退大片地形. 显式坐标与玩家站位两条入口共用此分流.
     */
    private static int dispatchAreaRestore(CommandSourceStack source, MinecraftServer server,
                                           ServerLevel level, String dimId, String snapshotId,
                                           ChunkPos center, int radius) {
        int count = (2 * radius + 1) * (2 * radius + 1);
        if (count > CONFIRM_THRESHOLD) {
            PENDING_RESTORE.put(sourceKey(source), new PendingArea(
                    snapshotId, dimId, center.x, center.z, radius,
                    System.currentTimeMillis() + CONFIRM_TTL_MS));
            source.sendSuccess(() -> Component.literal(
                    "即将在线回退 " + count + " 块 (中心 " + center.x + "," + center.z + " 半径 " + radius
                            + ", 维度 " + dimId + ", 快照 " + snapshotId + "). "
                            + "30 秒内执行 /betterbackup confirm 确认."), false);
            return 1;
        }
        executeAreaRestore(source, server, level, dimId, snapshotId, center, radius);
        return 1;
    }

    /**
     * 真正执行区域回退. 一条 daemon 线程跑全部目标块的重 IO (manifest/store/解 NBT),
     * 回主线程逐块交 BAS SaveCoordination 廉价安装, 收齐 future 后聚合上报各 outcome 计数.
     * 单块时退化为带细节文案的原行为.
     */
    private static void executeAreaRestore(CommandSourceStack source, MinecraftServer server,
                                           ServerLevel level, String dimId, String snapshotId,
                                           ChunkPos center, int radius) {
        long t0 = System.currentTimeMillis();
        ChunkStore store = BetterBackupCore.store();
        SnapshotCreator creator = BetterBackupCore.creator();
        ChunkRestoreFlow flow = new ChunkRestoreFlow(store, creator.snapshotsDir());

        List<ChunkPos> targets = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                targets.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }

        source.sendSuccess(() -> Component.literal(
                "在线回退已受理: 快照 " + snapshotId + " 维度 " + dimId + " 中心 (" + center.x + "," + center.z
                        + ") 半径 " + radius + " 共 " + targets.size() + " 块; 结果将异步反馈."), false);

        Thread worker = new Thread(() -> {
            long tResolveStart = System.currentTimeMillis();
            List<ResolvedTarget> resolved = new ArrayList<>();
            int notCaptured = 0;
            int resolveFailed = 0;
            for (ChunkPos pos : targets) {
                ChunkRestoreFlow.ResolvedChunk r;
                try {
                    r = flow.resolve(snapshotId, dimId, pos.x, pos.z);
                } catch (Throwable t) {
                    resolveFailed++;
                    BetterBackupMod.LOGGER.error("[BetterBackup] restore-chunk-live resolve failed @{}", pos, t);
                    continue;
                }
                if (!r.captured()) {
                    notCaptured++;
                    continue;
                }
                resolved.add(new ResolvedTarget(pos, r.tag()));
            }
            long resolveMs = System.currentTimeMillis() - tResolveStart;
            int fNotCaptured = notCaptured;
            int fResolveFailed = resolveFailed;
            server.execute(() -> installArea(level, source, server, center, radius,
                    targets.size(), resolved, fNotCaptured, fResolveFailed, t0, resolveMs));
        }, "BetterBackup-Cmd-RestoreChunkLive");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 主线程: 把已解析的各块字节逐一交 BAS, 收齐 future 后回主线程聚合上报. future 的完成线程
     * 不确定 (BAS 可能在 load worker 完成), 故 allOf 回调内统一 server.execute marshal 回主线程.
     */
    private static void installArea(ServerLevel level, CommandSourceStack source, MinecraftServer server,
                                    ChunkPos center, int radius, int total, List<ResolvedTarget> resolved,
                                    int notCaptured, int resolveFailed, long t0, long resolveMs) {
        if (resolved.isEmpty()) {
            source.sendFailure(Component.literal(
                    "在线回退: 无可回退区块 (未采集=" + notCaptured + " 解析异常=" + resolveFailed + ")."));
            return;
        }
        long tSubmit = System.currentTimeMillis();
        List<CompletableFuture<ChunkRestoreResult>> futures = new ArrayList<>();
        for (ResolvedTarget t : resolved) {
            futures.add(SaveCoordination.restoreChunkLive(level, t.pos(), t.tag()));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, err) -> server.execute(() -> {
                    long tDone = System.currentTimeMillis();
                    long basMs = tDone - tSubmit;
                    long totalMs = tDone - t0;
                    // 解析IO = BB worker 读 store/解 NBT (off-main); 安装+光照+重发 = BAS SaveCoordination
                    // 提交到全部 future 完成 (主线程廉价安装 + 异步光照点亮 + 重发包).
                    String timing = " 耗时 " + totalMs + "ms (解析IO " + resolveMs
                            + "ms, 安装+光照+重发 " + basMs + "ms)";
                    EnumMap<ChunkRestoreOutcome, Integer> tally = new EnumMap<>(ChunkRestoreOutcome.class);
                    int installErr = 0;
                    ChunkRestoreResult single = null;
                    for (CompletableFuture<ChunkRestoreResult> f : futures) {
                        ChunkRestoreResult res = f.getNow(null);
                        if (res == null) {
                            installErr++;
                            continue;
                        }
                        tally.merge(res.outcome(), 1, Integer::sum);
                        single = res;
                    }
                    int ok = tally.getOrDefault(ChunkRestoreOutcome.OK, 0);
                    BetterBackupMod.LOGGER.info(
                            "[BetterBackup] restore-chunk-live done: center=({},{}) radius={} target={} OK={} "
                                    + "totalMs={} resolveIoMs={} basInstallLightResendMs={} notCaptured={} resolveFailed={}",
                            center.x, center.z, radius, total, ok, totalMs, resolveMs, basMs, notCaptured, resolveFailed);
                    // 单块且无跳过: 保留原细节文案.
                    if (total == 1 && futures.size() == 1 && single != null
                            && notCaptured == 0 && resolveFailed == 0) {
                        String text = ChunkRestoreMessages.describe(single.outcome(), single.pos(), single.cause())
                                + timing;
                        if (ChunkRestoreMessages.isSuccess(single.outcome())) {
                            source.sendSuccess(() -> Component.literal(text), true);
                        } else {
                            source.sendFailure(Component.literal(text));
                        }
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("区域在线回退完成 (中心 ").append(center.x).append(',').append(center.z)
                            .append(" 半径 ").append(radius).append(", 目标 ").append(total).append(" 块): OK=").append(ok);
                    appendCount(sb, " 未加载=", tally.getOrDefault(ChunkRestoreOutcome.REJECT_NOT_LOADED, 0));
                    appendCount(sb, " 降级拒绝=", tally.getOrDefault(ChunkRestoreOutcome.REJECT_DEGRADED, 0));
                    appendCount(sb, " 不可用=", tally.getOrDefault(ChunkRestoreOutcome.REJECT_DISABLED, 0));
                    appendCount(sb, " 解析失败=", tally.getOrDefault(ChunkRestoreOutcome.PARSE_FAILED, 0));
                    appendCount(sb, " 安装失败=", tally.getOrDefault(ChunkRestoreOutcome.INSTALL_FAILED, 0) + installErr);
                    appendCount(sb, " 未采集=", notCaptured);
                    appendCount(sb, " 解析异常=", resolveFailed);
                    sb.append(timing);
                    String text = sb.toString();
                    if (ok == total) {
                        source.sendSuccess(() -> Component.literal(text), true);
                    } else {
                        source.sendFailure(Component.literal(text));
                    }
                }));
    }

    private static void appendCount(StringBuilder sb, String label, int count) {
        if (count > 0) {
            sb.append(label).append(count);
        }
    }

    /** 消费本来源的待确认区域回退 (超时/不存在则报错). */
    private static int confirmPendingRestore(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!checkReady(source)) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        PendingArea p = PENDING_RESTORE.remove(sourceKey(source));
        if (p == null) {
            source.sendFailure(Component.literal("无待确认的区域回退."));
            return 0;
        }
        if (System.currentTimeMillis() > p.expiryMillis()) {
            source.sendFailure(Component.literal("待确认的区域回退已超时 (30s), 请重新发起."));
            return 0;
        }
        ServerLevel level = resolveLevel(server, p.dimId());
        if (level == null) {
            source.sendFailure(Component.literal("待确认回退的维度 " + p.dimId() + " 当前不可用."));
            return 0;
        }
        executeAreaRestore(source, server, level, p.dimId(), p.id(), new ChunkPos(p.cx(), p.cz()), p.radius());
        return 1;
    }

    /** idArg 为 null 或 "latest" 时取最新快照; 否则校验该快照存在. 失败已发反馈并返回 null. */
    private static String resolveSnapshotId(CommandSourceStack source, String idArg) {
        SnapshotCreator creator = BetterBackupCore.creator();
        if (idArg == null || idArg.equalsIgnoreCase("latest")) {
            String latest = latestSnapshotId(creator);
            if (latest == null) {
                source.sendFailure(Component.literal("没有可用快照, 先 /betterbackup snapshot create."));
                return null;
            }
            return latest;
        }
        Path manifest = creator.snapshotsDir().resolve(idArg + ".manifest");
        if (!Files.exists(manifest)) {
            source.sendFailure(Component.literal(
                    "快照不存在: " + idArg + " (用 /betterbackup snapshot list 查看)."));
            return null;
        }
        return idArg;
    }

    /** 最新快照 id (id 为时间戳格式, 字典序即时间序). 无快照返回 null. */
    private static String latestSnapshotId(SnapshotCreator creator) {
        Path dir = creator.snapshotsDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .map(BetterBackupCommand::stripManifestSuffix)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        } catch (IOException e) {
            BetterBackupMod.LOGGER.error("[BetterBackup] failed to list snapshots dir {}", dir, e);
            return null;
        }
    }

    /** 快照 id 列表, newest first, 上限 limit. 供 &lt;id&gt; 参数 tab 补全. */
    private static List<String> listSnapshotIds(SnapshotCreator creator, int limit) {
        Path dir = creator.snapshotsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .map(BetterBackupCommand::stripManifestSuffix)
                    .sorted(Comparator.reverseOrder())
                    .limit(limit)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String stripManifestSuffix(Path manifestPath) {
        String name = manifestPath.getFileName().toString();
        return name.substring(0, name.length() - ".manifest".length());
    }

    private static boolean checkReady(CommandSourceStack source) {
        if (!BetterBackupCore.isInstalled()) {
            source.sendFailure(Component.literal("BetterBackup is not installed"));
            return false;
        }
        if (!BetterBackupCore.isReady()) {
            // store 初始化在后台跑 (大 store 索引重建可达分钟级), 就绪前写路径会撞 PackStore
            // 硬闸, 读路径会误报 "对象缺失". 统一在此快速失败, 文案指向 status 查进度.
            source.sendFailure(Component.literal(
                    "BetterBackup store is still initializing in the background (index rebuild); "
                            + "try again shortly. Check /betterbackup status for progress."));
            return false;
        }
        if (BetterBackupCore.creator() == null || BetterBackupCore.store() == null) {
            source.sendFailure(Component.literal("BetterBackup not fully initialized"));
            return false;
        }
        return true;
    }

    /** 待确认区域回退的来源键: 玩家用 UUID, 控制台/RCON 用固定键 (各自至多一笔待确认). */
    private static String sourceKey(CommandSourceStack source) {
        return source.getEntity() != null ? source.getEntity().getStringUUID() : "#console#";
    }

    private record PendingArea(String id, String dimId, int cx, int cz, int radius, long expiryMillis) {
    }

    private record ResolvedTarget(ChunkPos pos, CompoundTag tag) {
    }

    /**
     * 由 canonical dimId 字符串解析出活 ServerLevel. dimId 必须与采集侧
     * (BackupListenerBridge) 写入的 ResourceKey&lt;Level&gt;.location().toString() 同源,
     * 这样既能取到 live level 又能对上 manifest 的 chunks() key. 解析不出 (未知维度 /
     * 未加载) 返回 null, 调用方报错.
     */
    private static ServerLevel resolveLevel(MinecraftServer server, String dimId) {
        ResourceLocation loc = ResourceLocation.tryParse(dimId);
        if (loc == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, loc);
        return server.getLevel(key);
    }

    /**
     * 异步全量 GC. 命令立即返回, 派 daemon 线程跑 StoreGc.gcAll() (大型 store
     * 可能 walk 几十万文件, 不能阻塞主线程命令执行).
     */
    private static int gc(CommandContext<CommandSourceStack> ctx) {
        // 写路径: gcAll 压实会重写/删除 pack, store 未就绪时既撞硬闸也语义错误 (空索引 = 全死).
        if (!checkReady(ctx.getSource())) {
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        ChunkStore store = BetterBackupCore.store();
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        StoreGc gc = new StoreGc(store, creator.snapshotsDir());
        CurrentSnapshotState snapshotState = BetterBackupCore.snapshotState();
        Set<Hash> writtenThisWindow = BetterBackupCore.context().writtenThisWindow();

        Thread worker = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                // 活服手动 GC: 传在途保护集 (pendingHashes ∪ writtenThisWindow) 且不封口在写 pack,
                // 玩家在线 / chunk 正存盘时执行不会误删尚未进 manifest 的在途对象. 阈值 0 = 运营
                // 择时的彻底回收 (任何含死字节的 pack 都重打包), 与启动自检的有界阈值区分.
                Set<Hash> protect = new HashSet<>(snapshotState.pendingHashes());
                protect.addAll(writtenThisWindow);
                StoreGc.GcResult r = gc.gcAll(protect, false, 0.0);
                long elapsed = System.currentTimeMillis() - t0;
                server.execute(() -> source.sendSuccess(() -> Component.literal(
                        "GC done in " + elapsed + "ms: scanned=" + r.scanned()
                                + " retained=" + r.retained() + " deleted=" + r.deleted()
                                + " freed=" + (r.bytesFreed() / 1024) + " KiB"), true));
            } catch (IOException e) {
                BetterBackupMod.LOGGER.error("[BetterBackup] gc command failed", e);
                server.execute(() -> source.sendFailure(
                        Component.literal("GC failed: " + e.getMessage())));
            }
        }, "BetterBackup-Cmd-Gc");
        worker.setDaemon(true);
        worker.start();

        ctx.getSource().sendSuccess(() -> Component.literal("GC started (async)"), false);
        return 1;
    }

    /**
     * dry-run 保留策略预览: 按当前 config 配额 + 三门禁算出"将删 / 将留"两个集合并打印, 不真删。
     * 让服主在启用滚动淘汰前先看清一次淘汰会删掉哪些快照, 避免配额误配删掉不该删的。
     */
    private static int retentionPreview(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!checkReady(source)) {
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        RetentionPruner pruner = new RetentionPruner(creator.snapshotsDir(), worldRoot);
        RetentionPruner.Preview preview;
        try {
            preview = pruner.preview();
        } catch (IllegalArgumentException e) {
            // fail-fast: 有非法 snapshot id, 拒绝产出半套预览 (真淘汰也会 abort).
            source.sendFailure(Component.literal(
                    "Retention preview aborted (invalid snapshot id present): " + e.getMessage()));
            return 0;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Retention preview failed: " + e.getMessage()));
            return 0;
        }
        StringBuilder out = new StringBuilder();
        out.append("=== Retention preview (dry-run, nothing deleted) ===\n");
        out.append("Policy: hourly=").append(BetterBackupConfig.retentionHourly())
                .append(" daily=").append(BetterBackupConfig.retentionDaily())
                .append(" weekly=").append(BetterBackupConfig.retentionWeekly())
                .append(" monthly=").append(BetterBackupConfig.retentionMonthly()).append('\n');
        out.append("Would DELETE (").append(preview.toDelete().size()).append("):\n");
        if (preview.toDelete().isEmpty()) {
            out.append("  (none)\n");
        } else {
            for (String id : preview.toDelete()) {
                out.append("  - ").append(id).append('\n');
            }
        }
        out.append("Would KEEP (").append(preview.toKeep().size()).append("):\n");
        if (preview.toKeep().isEmpty()) {
            out.append("  (none)\n");
        } else {
            for (String id : preview.toKeep()) {
                out.append("  + ").append(id).append('\n');
            }
        }
        String text = out.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private BetterBackupCommand() {
    }
}
