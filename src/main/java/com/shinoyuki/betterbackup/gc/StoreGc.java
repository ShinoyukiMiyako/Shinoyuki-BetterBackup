package com.shinoyuki.betterbackup.gc;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
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
 * dedup store 垃圾回收器. 提供两种模式:
 *
 * <p><b>1. 全量 GC ({@link #gcAll()})</b> — DESIGN §5.2:
 * 扫所有存活 manifest 的 referenced hash, 累成 set, 然后 walk 整个 chunks/ 目录,
 * 不在 set 的物理文件即被删除. 适合: manifest 被删除后 (retention 淘汰 / 用户手动
 * delete) 清理孤儿; 或启动时 store 大小超阈值的自检兜底.
 *
 * <p><b>2. 增量 GC ({@link #gcIncremental(Set, Set)})</b> — DESIGN §2.6:
 * snapshot 创建完毕后立即清理"本次 BackupWorker 窗口内写入但未被本次 manifest
 * 引用"的 hash. 这是已加载 chunk 因 LastUpdate 等字段每次 save 都生成新 hash 但
 * dirtyMap 只保留最后一次而产生的中间版本孤儿. scope 是 candidate set 大小,
 * 远小于全量 GC, 秒级完成, 防止 store 在两次 snapshot 间临时膨胀几 GB.
 *
 * <p><b>损坏 manifest 处理</b>: gcAll 期间任何 manifest 读失败 → 直接 throw
 * IOException 让调用方处理. 不静默跳过, 因为跳过等于把损坏 manifest 引用的 hash
 * 错误地视为 unreferenced 进而误删 — 那些 hash 可能仍被其他存活 manifest 引用,
 * 但单 manifest 损坏就触发误删是不可接受的破坏性行为. 用户应先离线修复或删除
 * 损坏 manifest, 再重新 GC.
 *
 * <p><b>线程安全</b> (MVP scope): 假设调用方串行触发 (启动 verify / 命令 / snapshot
 * 创建后回调). 不保证跟 BackupWorker 写 store 并发安全 — 那需要 store-wide 写锁,
 * 留给后续 commit. 当前 race window: GC 删 hash X → BackupWorker 此后引用 X →
 * BackupWorker 此时 has(X)=false 会重写, 不丢数据但白做工. 增量 GC 因为 candidate
 * set 是同一 worker 上一窗口写入的 hash, 在 manifest 写完后立即跑, 不会跟下一窗口
 * 写入冲突.
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
     * 全量 GC: 收集所有存活 manifest 的 referenced hash, 删除 chunks/ 下所有
     * 不在该集合的文件. 损坏 manifest 直接抛 IOException, 不删任何文件.
     *
     * @return 本次 GC 统计
     * @throws IOException 任意 manifest 读失败 / 文件系统操作失败
     */
    public GcResult gcAll() throws IOException {
        Set<Hash> referencedHashes = collectReferencedFromAllManifests();

        // chunksDir 不存在 = store 还没 initialize, 没东西可删
        if (!Files.isDirectory(store.chunksDir())) {
            return new GcResult(0, 0, 0, 0);
        }

        long scanned = 0;
        long retained = 0;
        long deleted = 0;
        long bytesFreed = 0;

        try (Stream<Path> walk = Files.walk(store.chunksDir())) {
            // toList 一次取齐避免在 walk stream 内做 IO 删除引发 ClosedDirectoryStream
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String name = file.getFileName().toString();
                // .tmp 孤儿是 ChunkStore.cleanupOrphanTmpFiles 的责任, 不在 GC scope
                if (name.endsWith(".tmp")) {
                    continue;
                }
                Hash h;
                try {
                    h = Hash.fromHex(name);
                } catch (IllegalArgumentException e) {
                    // 文件名不是合法 hex (脏文件 / 用户手放) → 不动它, 留给人决定
                    LOGGER.warn("[BetterBackup] GC skipped non-hex file in chunks dir: {}", file);
                    continue;
                }
                scanned++;
                if (referencedHashes.contains(h)) {
                    retained++;
                } else {
                    long size = Files.size(file);
                    Files.delete(file);
                    deleted++;
                    bytesFreed += size;
                }
            }
        }
        LOGGER.info("[BetterBackup] gcAll done: scanned={}, retained={}, deleted={}, bytesFreed={}",
                scanned, retained, deleted, bytesFreed);
        return new GcResult(scanned, retained, deleted, bytesFreed);
    }

    /**
     * 增量 GC: 删除 (writtenThisWindow - referencedThisSnapshot) 集合中实际存在
     * 于 store 的文件. 用于 snapshot 创建后清理 BackupWorker 本窗口产生但 manifest
     * 未引用的中间版本孤儿.
     *
     * <p>candidate hash 不在 store 里 (例如已被先前 GC 删除 / 写入失败) 不抛异常,
     * deleted 计数仅反映本次实际删除的物理文件数.
     *
     * @param writtenThisWindow      本次 BackupWorker 窗口写入的所有 hash
     * @param referencedThisSnapshot 本次 manifest 实际引用的 hash
     * @return scanned = candidate 数, retained 恒为 0 (不区分 retained/skipped),
     *         deleted = 实际删除文件数, bytesFreed = 删除文件总字节数
     */
    public GcResult gcIncremental(Set<Hash> writtenThisWindow, Set<Hash> referencedThisSnapshot)
            throws IOException {
        Objects.requireNonNull(writtenThisWindow, "writtenThisWindow");
        Objects.requireNonNull(referencedThisSnapshot, "referencedThisSnapshot");

        Set<Hash> candidates = new HashSet<>(writtenThisWindow);
        candidates.removeAll(referencedThisSnapshot);

        long deleted = 0;
        long bytesFreed = 0;
        for (Hash h : candidates) {
            Path p = store.pathFor(h);
            if (Files.exists(p)) {
                long size = Files.size(p);
                Files.delete(p);
                deleted++;
                bytesFreed += size;
            }
        }
        long scanned = candidates.size();
        LOGGER.info("[BetterBackup] gcIncremental done: candidates={}, deleted={}, bytesFreed={}",
                scanned, deleted, bytesFreed);
        return new GcResult(scanned, 0, deleted, bytesFreed);
    }

    /**
     * 扫 snapshotsDir 下所有 *.manifest, 累加 referenced hash. 任意 manifest 读
     * 失败立即抛, 调用方负责语义.
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
                    // 损坏 manifest: 抛 IOException 让调用方决定, 绝不静默跳过
                    // 跳过会让该 manifest 引用的 hash 被错误归类为 unreferenced 而误删
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
        if (manifest.levelDat() != null) {
            out.add(manifest.levelDat());
        }
    }

    /**
     * GC 执行结果统计.
     *
     * @param scanned    扫描总数 (gcAll: chunks/ 下合法 hex 文件总数; gcIncremental: candidate 总数)
     * @param retained   保留数 (gcAll: 被 manifest 引用而保留; gcIncremental 恒 0)
     * @param deleted    实际删除文件数
     * @param bytesFreed 删除文件累计字节数
     */
    public record GcResult(long scanned, long retained, long deleted, long bytesFreed) {}
}
