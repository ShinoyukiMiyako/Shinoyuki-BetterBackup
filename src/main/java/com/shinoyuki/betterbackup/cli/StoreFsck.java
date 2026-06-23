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
 * restore 时炸)。重 hash 即权威完整性判据。
 *
 * <p><b>对象分类 (为什么不能对所有对象跑压缩校验)</b>: store 自 Phase D 起混放两类对象 ——
 * chunk slot payload (chunks / entityChunks 段引用, 形态是 "compression-type byte + zlib/gzip
 * 流") 与 opaque 整文件 (files / savedData / levelDat 段引用, 形态是 poi 的 .mca 整文件 /
 * playerdata、level.dat、SavedData 的 gzip NBT / stats、advancements 的 JSON 原始字节)。后者
 * 首字节并非 compression-type, 强行跑压缩校验会把 poi 稀疏位置表开头的 0x00、gzip 魔数 0x1f、
 * JSON 的 0x7b 全部误判为 "invalid compression type" CORRUPT (299 万对象真实 store 实测 1620
 * 个误报, HASH-MISMATCH 为 0 = 数据本体无损)。因此 fsck 先扫 manifests 把 hash 分类:
 * <ul>
 *   <li>chunk payload 类: 重 hash + zlib/gzip 压缩流校验 (复用 {@link ChunkPayloadCodec},
 *       抓 hash 自洽但压缩流损坏的对象)</li>
 *   <li>opaque 文件类: 仅重 hash (内容寻址自校验, 整文件字节无统一压缩头可校)</li>
 *   <li>orphan (未被任何 manifest 引用): 仅重 hash, 单独归类 ORPHAN 报告 —— orphan 是 GC
 *       时机问题不是损坏, 不算 CORRUPT, 不影响退出码</li>
 * </ul>
 * 同一 hash 同时被 chunk 段与 files 段引用时按 chunk 类处理 (更严格者优先)。
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
     * 逐 store 对象重 hash 对比文件名, 仅对 chunk payload 类对象额外做 zlib/gzip 完整性校验。
     * 分类依据是 manifests 引用: chunks/entityChunks 段引用的 hash 才是 chunk payload,
     * files/savedData/levelDat 段引用的 hash 是 opaque 整文件 (仅重 hash), 未被任何 manifest
     * 引用的是 orphan (仅重 hash, 单独归类)。详见类级 javadoc 的"对象分类"。
     *
     * @return 校验结果 (按 chunk / file / orphan 分类计数 + hash 不符列表 + 压缩损坏列表 + orphan 列表)
     */
    public VerifyResult verifyStore() throws IOException {
        ReferenceIndex refs = collectReferences();
        Counters c = new Counters();

        // 1. pack 对象: 顺序扫每个 pack (机械盘友好), 记录内联 hash 即 expected。
        store.packStore().forEachObject((storedHash, bytes) ->
                classifyObject(storedHash.toHex(), storedHash, bytes, refs, c));

        // 2. 旧文件树残留对象 (迁移前 "一对象一文件" 布局): 文件名即 expected hash。
        if (Files.isDirectory(store.chunksDir())) {
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
                        c.corrupt.add(name + " (filename is not valid hex)");
                        c.scanned++;
                        continue;
                    }
                    classifyObject(name, expected, Files.readAllBytes(file), refs, c);
                }
            }
        }
        return new VerifyResult(c.scanned, c.ok, c.chunkObjects, c.fileObjects,
                c.hashMismatch, c.corrupt, c.orphans);
    }

    /**
     * 单对象分类校验 (pack 与旧树共用). expected = 对象声称的 hash (pack 内联 / 旧树文件名)。
     * 重 hash 对比是所有类型共通的权威完整性判据; chunk payload 类额外做压缩流校验; opaque 整文件
     * 仅重 hash; 未被任何 manifest 引用的归 orphan (GC 残留, 非损坏)。
     */
    private void classifyObject(String label, Hash expected, byte[] bytes, ReferenceIndex refs, Counters c) {
        c.scanned++;
        Hash actual = hashFunction.hash(bytes);
        if (!actual.equals(expected)) {
            c.hashMismatch.add(label + " (content hashes to " + actual.toHex() + ")");
            return;
        }
        boolean isChunkPayload = refs.chunkPayload().contains(expected);
        boolean isReferenced = isChunkPayload || refs.opaqueFile().contains(expected);
        if (!isReferenced) {
            c.orphans.add(label);
            return;
        }
        if (isChunkPayload) {
            // chunk payload 才有 "compression-type byte + zlib/gzip 流" 布局, 才能 inflate 完整性校验。
            c.chunkObjects++;
            try {
                ChunkPayloadCodec.verifyStoreObject(bytes);
            } catch (IOException e) {
                c.corrupt.add(label + " (" + e.getMessage() + ")");
                return;
            }
        } else {
            // opaque 整文件 (poi / playerdata、level.dat、SavedData 的 gzip NBT / JSON): 重 hash 即判据。
            c.fileObjects++;
        }
        c.ok++;
    }

    /** verifyStore 跨 pack / 旧树累计计数的可变载体. */
    private static final class Counters {
        long scanned;
        long ok;
        long chunkObjects;
        long fileObjects;
        final List<String> hashMismatch = new ArrayList<>();
        final List<String> corrupt = new ArrayList<>();
        final List<String> orphans = new ArrayList<>();
    }

    /**
     * 单趟扫 snapshotsDir 下全部 manifest, 把引用 hash 分进两个集合: chunks/entityChunks 段 ->
     * chunkPayload (走压缩流校验), files/savedData/levelDat 段 -> opaqueFile (仅重 hash)。
     *
     * <p><b>为什么一趟全收集而非逐对象按需查</b>: 299 万对象的生产 store 上, 99.9% 是 chunk
     * (落 chunkPayload 集合直接命中)。若对每个 opaque/orphan 对象都重新逐 manifest 解析查引用,
     * 是 O(对象数 × manifest 数 × 解析) -- 多快照大服会慢到不可用。一趟收集后查集合是 O(1)。
     *
     * <p>读不出的 manifest 跳过其引用 (它的损坏由 {@link #rebuildIndex} 单独报 CORRUPT),
     * 不让单个坏 manifest 影响其余对象的分类; 它引用的对象会退化为 orphan 归类。
     */
    private ReferenceIndex collectReferences() throws IOException {
        Set<Hash> chunkPayload = new HashSet<>();
        Set<Hash> opaqueFile = new HashSet<>();
        if (!Files.isDirectory(snapshotsDir)) {
            return new ReferenceIndex(chunkPayload, opaqueFile);
        }
        List<Path> manifests;
        try (Stream<Path> stream = Files.list(snapshotsDir)) {
            manifests = stream
                    .filter(p -> p.getFileName().toString().endsWith(".manifest"))
                    .toList();
        }
        for (Path m : manifests) {
            SnapshotManifest manifest;
            try {
                manifest = SnapshotManifest.readFrom(m);
            } catch (IOException | RuntimeException e) {
                // 坏 manifest 不阻断分类 (其 CORRUPT 由 rebuildIndex 报); 它引用的对象退化为 orphan。
                // 不吞业务异常: manifest 读失败是预期的诊断输入, 不是程序 bug, 跳过该 manifest 即可。
                continue;
            }
            manifest.chunks().values().forEach(map -> chunkPayload.addAll(map.values()));
            manifest.entityChunks().values().forEach(map -> chunkPayload.addAll(map.values()));
            opaqueFile.addAll(manifest.files().hashes().values());
            opaqueFile.addAll(manifest.savedData().values());
            if (manifest.levelDat() != null) {
                opaqueFile.add(manifest.levelDat());
            }
        }
        return new ReferenceIndex(chunkPayload, opaqueFile);
    }

    /**
     * manifest 引用 hash 的分类索引。chunkPayload 与 opaqueFile 可能交集 (同一对象既被 chunk 段
     * 又被 files 段引用); 调用方先查 chunkPayload, 命中即按 chunk 类处理 (更严格者优先)。
     */
    private record ReferenceIndex(Set<Hash> chunkPayload, Set<Hash> opaqueFile) {
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

    /**
     * store 校验结果.
     *
     * @param scanned      扫描的对象总数 (含被判 hashMismatch / corrupt / orphan 的)
     * @param ok           重 hash 通过且 (chunk 类) 压缩校验通过的对象数, 不含 orphan
     * @param chunkObjects 被分类为 chunk payload 并校验通过的对象数 (ok 的子集)
     * @param fileObjects  被分类为 opaque 整文件并校验通过的对象数 (ok 的子集)
     * @param hashMismatch 重 hash 与文件名不符的对象诊断 (文件名 + 实际 hash)
     * @param corrupt      hash 自洽但压缩流损坏 / 文件名非法 hex 的对象诊断
     * @param orphans      重 hash 通过但未被任何 manifest 引用的对象文件名 (GC 残留, 非损坏)
     */
    public record VerifyResult(long scanned, long ok, long chunkObjects, long fileObjects,
                               List<String> hashMismatch, List<String> corrupt, List<String> orphans) {

        /**
         * 退出码语义: 仅当存在 hash 不符或真损坏时才算"不干净" (非 0 退出)。orphan 是 GC 时机
         * 问题不是数据损坏, 不影响 clean (不拉非 0 退出码)。
         */
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
