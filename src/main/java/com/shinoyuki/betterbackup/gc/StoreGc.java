package com.shinoyuki.betterbackup.gc;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.pack.PackStore;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * dedup store 垃圾回收器 (pack 时代). 存活集 = 所有存活 manifest 引用的 hash;
 * 不在其中的对象即死, 可回收. store 横跨两种布局:
 *
 * <ul>
 *   <li><b>pack</b> (新写入): append-only 删不掉单对象, 由 {@link PackStore#compact} 重打包
 *       存活对象 / 删全死 pack 回收死字节 (延迟压实, restic/borg 模型)</li>
 *   <li><b>旧文件树</b> ({@code chunks/<2>/<6>/<full>}, 迁移前残留): 一对象一文件, 直接按对象删,
 *       随其所属快照被淘汰而在保留窗口内自然排空</li>
 * </ul>
 *
 * <p><b>1. 全量 GC ({@link #gcAll(Set, boolean)})</b>: 排空旧树未引用对象 + 阈值 0 压实所有 pack
 * (回收全部死字节). 重活, 给手动 {@code betterbackup gc} / 启动超阈值自检 / 周期触发用. 活服调用
 * 必须传 {@code protect} 在途集且不封口在写 pack; 无参 {@link #gcAll()} 只给静止 store (离线 CLI).
 *
 * <p><b>2. 阈值压实 ({@link #compactAfterSnapshot(double, Set)})</b>: 只重写死字节占比超阈值
 * 的 pack (有界), 给 SnapshotCreator 攒够死对象后触发的自动压实用. {@code protect} 排除在途
 * 尚未进 manifest 的对象, 双保险 (compact 本就跳过在写 pack).
 *
 * <p><b>损坏 manifest 处理</b>: 收集引用集期间任何 manifest 读失败 → 直接 throw IOException,
 * 在动任何对象之前 abort. 不静默跳过 —— 跳过等于把损坏 manifest 引用的 hash 误判 unreferenced
 * 进而误删, 那些 hash 可能仍被其他存活 manifest 引用. 用户应先离线修复 / 删损坏 manifest 再 GC.
 *
 * <p><b>线程安全</b>: pack 压实持 {@link PackStore} 写锁独占 (阻塞活跃 worker 的 put/get),
 * 且跳过在写 pack —— 在途对象在在写 pack 里, 天然不被回收. 旧树排空假设调用方串行触发.
 */
public final class StoreGc {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    private final ChunkStore store;
    private final Path snapshotsDir;

    public StoreGc(ChunkStore store, Path snapshotsDir) {
        this.store = Objects.requireNonNull(store, "store");
        this.snapshotsDir = Objects.requireNonNull(snapshotsDir, "snapshotsDir");
    }

    /**
     * 静止 store 的全量 GC (离线 CLI / 无并发 writer): 封口在写 pack、无在途保护, 连最新写入也一并
     * 回收死字节. 等价 {@code gcAll(Set.of(), true)}. 有活跃 worker / baseline 并发写入时严禁调此
     * 无参重载 —— 会物理删掉尚未进 manifest 的在途对象; 活服路径改用 {@link #gcAll(Set, boolean)}.
     *
     * @return 本次 GC 统计 (旧树 + pack 合并)
     * @throws IOException 任意 manifest 读失败 / 文件系统操作失败
     */
    public GcResult gcAll() throws IOException {
        return gcAll(Set.of(), true);
    }

    /**
     * 全量 GC: 收集所有存活 manifest 的引用集 (∪ {@code protect}), 排空旧树未引用对象, 阈值 0
     * 压实 pack 回收全部死字节. 损坏 manifest 在动对象之前直接抛 IOException, 不删不压.
     *
     * <p>活服调用 (命令 gc / 启动 {@code StoreSizeGuard} 自检) 必须传
     * {@code protect = pendingHashes ∪ writtenThisWindow} 并 {@code sealActiveWritePack=false}:
     * 在途对象 (已 store.put 入库、已登记 state, 但尚未随任何 manifest 落盘) 不在 manifest 引用集内,
     * 不保护就会被判死物理回收, 下一份快照 drain 出这些 hash 即成悬空引用, 备份静默丢数据.
     * protect 与"跳过在写 pack"两层各挡一类: protect 挡已落入封口 pack 的在途对象; 跳过在写 pack
     * 挡"已 put 但 state / writtenThisWindow 登记尚未可见"的时序窗口.
     *
     * @param protect             额外保护的在途 hash (不在 manifest 引用集但即将被引用)
     * @param sealActiveWritePack  true = 封口在写 pack 使其也参与压实 (仅静止 store 安全);
     *                             false = 跳过在写 pack (活服并发写入时必须)
     * @return 本次 GC 统计 (旧树 + pack 合并)
     * @throws IOException 任意 manifest 读失败 / 文件系统操作失败
     */
    public GcResult gcAll(Set<Hash> protect, boolean sealActiveWritePack) throws IOException {
        Set<Hash> live = collectReferencedFromAllManifests();
        live.addAll(protect);

        LegacyStats legacy = drainLegacy(live);

        long packBefore = store.packStore().objectCount();
        PackStore.CompactResult cr = store.packStore().compact(live, 0.0, sealActiveWritePack);

        long scanned = legacy.scanned() + packBefore;
        long retained = legacy.retained() + (packBefore - cr.objectsRemoved());
        long deleted = legacy.deleted() + cr.objectsRemoved();
        long bytesFreed = legacy.bytesFreed() + cr.bytesReclaimed();
        LOGGER.info("[BetterBackup] gcAll done: scanned={}, retained={}, deleted={}, bytesFreed={} "
                        + "(legacyDeleted={}, packReclaimed={})",
                scanned, retained, deleted, bytesFreed, legacy.deleted(), cr.objectsRemoved());
        return new GcResult(scanned, retained, deleted, bytesFreed);
    }

    /**
     * 阈值压实: 只重写死字节占比超 {@code deadRatioThreshold} 的 pack. 存活集 = manifest 引用
     * ∪ {@code protect}. 不碰旧树 (旧树排空走 {@link #gcAll}). 给每快照后攒够死对象的自动触发用.
     *
     * @param deadRatioThreshold pack 死字节占比超此值才重打包 (0.0~1.0)
     * @param protect            额外保护的在途 hash (尚未进 manifest 但即将被引用)
     */
    public GcResult compactAfterSnapshot(double deadRatioThreshold, Set<Hash> protect) throws IOException {
        Set<Hash> live = collectReferencedFromAllManifests();
        live.addAll(protect);
        long packBefore = store.packStore().objectCount();
        PackStore.CompactResult cr = store.packStore().compact(live, deadRatioThreshold);
        LOGGER.info("[BetterBackup] compactAfterSnapshot done: packReclaimed={}, bytesFreed={}, "
                        + "packsDeleted={}, packsRewritten={}",
                cr.objectsRemoved(), cr.bytesReclaimed(), cr.packsDeleted(), cr.packsRewritten());
        return new GcResult(packBefore, packBefore - cr.objectsRemoved(), cr.objectsRemoved(), cr.bytesReclaimed());
    }

    /**
     * 排空旧文件树: walk chunks/, 删除 hash 不在存活集的文件 (迁移前残留对象). .tmp 孤儿是
     * {@link ChunkStore#cleanupOrphanTmpFiles} 的责任, 不在此 scope.
     */
    private LegacyStats drainLegacy(Set<Hash> live) throws IOException {
        Path chunksDir = store.chunksDir();
        if (!Files.isDirectory(chunksDir)) {
            return new LegacyStats(0, 0, 0, 0);
        }
        long scanned = 0;
        long retained = 0;
        long deleted = 0;
        long bytesFreed = 0;
        try (Stream<Path> walk = Files.walk(chunksDir)) {
            // toList 一次取齐避免在 walk stream 内做删除引发 ClosedDirectoryStream
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    continue;
                }
                Hash h;
                try {
                    h = Hash.fromHex(name);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("[BetterBackup] GC skipped non-hex file in chunks dir: {}", file);
                    continue;
                }
                scanned++;
                if (live.contains(h)) {
                    retained++;
                } else {
                    long size = Files.size(file);
                    Files.delete(file);
                    deleted++;
                    bytesFreed += size;
                }
            }
        }
        return new LegacyStats(scanned, retained, deleted, bytesFreed);
    }

    /**
     * 扫 snapshotsDir 下所有 *.manifest, 累加 referenced hash. 任意 manifest 读失败立即抛,
     * 调用方负责语义.
     */
    private Set<Hash> collectReferencedFromAllManifests() throws IOException {
        Set<Hash> referenced = new HashSet<>();
        if (!Files.isDirectory(snapshotsDir)) {
            return referenced;
        }
        try (Stream<Path> manifests = Files.list(snapshotsDir)) {
            List<Path> files = manifests
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .toList();
            for (Path m : files) {
                SnapshotManifest manifest;
                try {
                    manifest = SnapshotManifest.readFrom(m);
                } catch (IOException | RuntimeException e) {
                    LOGGER.error("[BetterBackup] GC aborted: failed to read manifest {}", m, e);
                    throw new IOException("GC aborted due to corrupt manifest: " + m, e);
                }
                collectReferencedHashes(manifest, referenced);
            }
        }
        return referenced;
    }

    /** 把单个 manifest 的所有引用 hash 收集到 out. levelDat 可空. */
    private static void collectReferencedHashes(SnapshotManifest manifest, Set<Hash> out) {
        manifest.chunks().values().forEach(m -> out.addAll(m.values()));
        manifest.entityChunks().values().forEach(m -> out.addAll(m.values()));
        out.addAll(manifest.savedData().values());
        out.addAll(manifest.files().hashes().values());
        if (manifest.levelDat() != null) {
            out.add(manifest.levelDat());
        }
    }

    /** 旧树排空局部统计. */
    private record LegacyStats(long scanned, long retained, long deleted, long bytesFreed) {
    }

    /**
     * GC 执行结果统计 (旧树 + pack 合并).
     *
     * @param scanned    考量对象总数 (旧树文件 + pack 对象)
     * @param retained   存活保留数
     * @param deleted    回收对象数 (旧树删 + pack 压实摘除)
     * @param bytesFreed 物理回收字节数
     */
    public record GcResult(long scanned, long retained, long deleted, long bytesFreed) {}
}
