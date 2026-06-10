package com.shinoyuki.betterbackup.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shinoyuki.betterbackup.BetterBackupCore;
import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.config.BetterBackupConfig;
import com.shinoyuki.betterbackup.gc.StoreGc;
import com.shinoyuki.betterbackup.restore.PendingRestoreFlag;
import com.shinoyuki.betterbackup.snapshot.CurrentSnapshotState;
import com.shinoyuki.betterbackup.snapshot.SnapshotCreator;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class BetterBackupCommand {

    private static final int OP_LEVEL = 2;

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
                        .then(Commands.literal("gc").executes(BetterBackupCommand::gc))
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
            out.append("Store: ").append(store.storeRoot()).append('\n');
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
        if (!BetterBackupCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup is not installed"));
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        if (creator == null) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup creator not initialized"));
            return 0;
        }
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
     * 异步全量 GC. 命令立即返回, 派 daemon 线程跑 StoreGc.gcAll() (大型 store
     * 可能 walk 几十万文件, 不能阻塞主线程命令执行).
     */
    private static int gc(CommandContext<CommandSourceStack> ctx) {
        if (!BetterBackupCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup is not installed"));
            return 0;
        }
        SnapshotCreator creator = BetterBackupCore.creator();
        ChunkStore store = BetterBackupCore.store();
        if (creator == null || store == null) {
            ctx.getSource().sendFailure(Component.literal("BetterBackup not fully initialized"));
            return 0;
        }
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        StoreGc gc = new StoreGc(store, creator.snapshotsDir());

        Thread worker = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                StoreGc.GcResult r = gc.gcAll();
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

    private BetterBackupCommand() {
    }
}
