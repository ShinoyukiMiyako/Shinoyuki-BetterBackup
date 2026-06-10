package com.shinoyuki.betterbackup.cli;

import com.shinoyuki.betterbackup.io.ChunkPayloadCodec;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * store 扫描校验引擎 + 从 store + manifests 重建快照索引 (PLAN Phase E commit 12)。
 *
 * <p><b>为什么 store 能自校验</b>: 文件名 = 内容 hash (content-addressed)。fsck 逐对象重 hash
 * 再跟文件名比对 -- 不符 = 位翻转/截断/外部篡改 (hash 与字节失配, GC/restore 都查不出, 只在
 * restore 时炸)。再叠一层 zlib/gzip inflate 完整性校验 (复用 {@link ChunkPayloadCodec}),
 * 抓 hash 自洽但压缩流损坏的对象。
 *
 * <p><b>快照索引重建</b>: manifest 是单点 (QuickBackupMulti #51 同类: manifest 损坏即快照不可用)。
 * 本类扫 manifests 目录, 逐快照核对其引用 hash 是否全在 store, 产出一份 {@code index.txt}
 * 文本目录, 标注每个快照 OK / 缺失对象数。损坏 (读不出) 的 manifest 被列为 CORRUPT 而非
 * 让整个扫描中止 -- CLI 场景下用户正是来排查损坏的, 必须看到全貌而非第一处就崩。
 *
 * <p>整条调用链零 net.minecraft / net.minecraftforge 依赖 (与 {@link OfflineRestore} 同)。
 */
public final class StoreFsck {

    /** index.txt 文件名: 重建出的快照索引落在 snapshotsDir 下。 */
    public static final String INDEX_FILE_NAME = "index.txt";

    private final ChunkStore store;
    private final Path snapshotsDir;
    private final HashFunction hashFunction;

    public StoreFsck(ChunkStore store, Path snapshotsDir, HashFunction hashFunction) {
        this.store = store;
        this.snapshotsDir = snapshotsDir;
        this.hashFunction = hashFunction;
    }

    /**
     * 逐 store 对象重 hash 对比文件名 + zlib 完整性校验。
     *
     * @return 校验结果 (扫描数 / OK 数 / hash 不符列表 / 压缩损坏列表)
     */
    public VerifyResult verifyStore() throws IOException {
        long scanned = 0;
        long ok = 0;
        List<String> hashMismatch = new ArrayList<>();
        List<String> corrupt = new ArrayList<>();

        if (!Files.isDirectory(store.chunksDir())) {
            return new VerifyResult(0, 0, hashMismatch, corrupt);
        }

        try (Stream<Path> walk = Files.walk(store.chunksDir())) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    // .tmp 孤儿是 cleanupOrphanTmpFiles 的责任, 不是损坏对象, 跳过不计。
                    continue;
                }
                Hash expected;
                try {
                    expected = Hash.fromHex(name);
                } catch (IllegalArgumentException e) {
                    // 文件名不是合法 hex = 脏文件 / 人工误放, 算损坏让用户知道, 不静默。
                    corrupt.add(name + " (filename is not valid hex)");
                    scanned++;
                    continue;
                }
                scanned++;
                byte[] bytes = Files.readAllBytes(file);

                // 重 hash 对比: 文件名声称的 hash 必须等于内容实际 hash。
                Hash actual = hashFunction.hash(bytes);
                if (!actual.equals(expected)) {
                    hashMismatch.add(name + " (content hashes to " + actual.toHex() + ")");
                    continue;
                }

                // zlib/gzip 完整性: hash 自洽但压缩流可能损坏 (例如 inflate 提前 EOF)。
                try {
                    ChunkPayloadCodec.verifyStoreObject(bytes);
                } catch (IOException e) {
                    corrupt.add(name + " (" + e.getMessage() + ")");
                    continue;
                }
                ok++;
            }
        }
        return new VerifyResult(scanned, ok, hashMismatch, corrupt);
    }

    /**
     * 从 store + manifests 重建快照索引。扫 snapshotsDir 下每个 {@code *.manifest},
     * 核对其引用 hash 是否全在 store, 把每个快照的状态写进 {@link #INDEX_FILE_NAME}
     * (atomic: tmp + rename), 返回各快照条目。
     *
     * <p>损坏 manifest 标 CORRUPT 继续 (不中止), 这是 fsck 与 GC 的关键区别: GC 损坏即抛
     * (误删风险), fsck 是只读诊断, 要的是完整全貌。
     */
    public RebuildResult rebuildIndex() throws IOException {
        List<SnapshotEntry> entries = new ArrayList<>();
        if (Files.isDirectory(snapshotsDir)) {
            List<Path> manifests;
            try (Stream<Path> stream = Files.list(snapshotsDir)) {
                manifests = stream
                        .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            }
            for (Path m : manifests) {
                entries.add(inspectManifest(m));
            }
        }
        writeIndexFile(entries);
        return new RebuildResult(entries);
    }

    private SnapshotEntry inspectManifest(Path manifestFile) {
        String fileName = manifestFile.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".manifest".length());
        SnapshotManifest manifest;
        try {
            manifest = SnapshotManifest.readFrom(manifestFile);
        } catch (IOException | RuntimeException e) {
            return SnapshotEntry.corrupt(id, e.getMessage());
        }

        Set<Hash> referenced = new HashSet<>();
        manifest.chunks().values().forEach(map -> referenced.addAll(map.values()));
        manifest.entityChunks().values().forEach(map -> referenced.addAll(map.values()));
        referenced.addAll(manifest.savedData().values());
        referenced.addAll(manifest.files().hashes().values());
        if (manifest.levelDat() != null) {
            referenced.add(manifest.levelDat());
        }
        long missing = referenced.stream().filter(h -> !store.has(h)).count();
        int totalChunks = manifest.chunks().values().stream().mapToInt(java.util.Map::size).sum();
        return new SnapshotEntry(id, false, manifest.baselineComplete(), referenced.size(),
                missing, totalChunks, manifest.createdAtMillis(), null);
    }

    private void writeIndexFile(List<SnapshotEntry> entries) throws IOException {
        Files.createDirectories(snapshotsDir);
        StringBuilder sb = new StringBuilder();
        sb.append("# BetterBackup store index (rebuilt by fsck --rebuild-index)\n");
        sb.append("# columns: snapshotId status baselineComplete referencedObjects missingObjects chunks createdAtMillis\n");
        for (SnapshotEntry e : entries) {
            if (e.corrupt()) {
                sb.append(e.id()).append(' ').append("CORRUPT").append(' ')
                        .append("- - - - -").append(" reason=").append(e.corruptReason()).append('\n');
            } else {
                String status = e.missingObjects() == 0 ? "OK" : "INCOMPLETE";
                sb.append(e.id()).append(' ')
                        .append(status).append(' ')
                        .append(e.baselineComplete()).append(' ')
                        .append(e.referencedObjects()).append(' ')
                        .append(e.missingObjects()).append(' ')
                        .append(e.chunks()).append(' ')
                        .append(e.createdAtMillis()).append('\n');
            }
        }
        Path index = snapshotsDir.resolve(INDEX_FILE_NAME);
        Path tmp = snapshotsDir.resolve(INDEX_FILE_NAME + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** store 校验结果. hashMismatch / corrupt 各是诊断字符串列表 (文件名 + 原因)。 */
    public record VerifyResult(long scanned, long ok, List<String> hashMismatch, List<String> corrupt) {

        public boolean clean() {
            return hashMismatch.isEmpty() && corrupt.isEmpty();
        }
    }

    /** 索引重建结果. */
    public record RebuildResult(List<SnapshotEntry> entries) {
    }

    /**
     * 单快照索引条目。corrupt=true 时其余数值字段无意义 (manifest 读不出)。
     */
    public record SnapshotEntry(
            String id,
            boolean corrupt,
            boolean baselineComplete,
            int referencedObjects,
            long missingObjects,
            int chunks,
            long createdAtMillis,
            String corruptReason) {

        static SnapshotEntry corrupt(String id, String reason) {
            return new SnapshotEntry(id, true, false, 0, 0, 0, 0L, reason);
        }

        public boolean restorable() {
            return !corrupt && missingObjects == 0;
        }
    }
}
