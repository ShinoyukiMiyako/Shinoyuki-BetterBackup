package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.nbt.NbtCompound;
import com.shinoyuki.betterbackup.nbt.NbtList;
import com.shinoyuki.betterbackup.nbt.NbtReader;
import com.shinoyuki.betterbackup.nbt.NbtType;
import com.shinoyuki.betterbackup.nbt.NbtWriter;
import com.shinoyuki.betterbackup.store.Hash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单 snapshot 的清单. 内容是各类备份单元 ({@code (dim, x, z) -> Hash} 等) 到 store
 * 内 hash 的引用映射. 不存任何字节数据本身, 数据在 ChunkStore 里按 hash 寻址.
 *
 * <p><b>序列化格式</b>: gzip + NBT, 由独立最小编解码 {@link NbtWriter}/{@link NbtReader}
 * 写读, 与 vanilla {@code NbtIo.writeCompressed} 输出字节互读 (PLAN Phase E 硬约束:
 * 离线 CLI 代码路径零 net.minecraft import, 故不能用 NbtIo). 大服 100k chunk ×
 * 16 byte hash ≈ 1.6 MB raw, gzip 压缩约 1 MB.
 *
 * <p><b>原子写</b>: 写到 {@code <id>.manifest.tmp} → fsync → rename 到
 * {@code <id>.manifest}. 跟 ChunkStore put 同模式, 保证 kill -9 不会留半写文件
 * 让其他 snapshot 受影响.
 *
 * <p>schema:
 * <pre>
 * {
 *     version: int,
 *     snapshotId: string,
 *     createdAtMillis: long,
 *     worldGameTime: long,
 *     chunks: { dimensionId: ListTag of {pos: long, hash: byte[]} },
 *     entityChunks: { dimensionId: ListTag of {pos: long, hash: byte[]} },
 *     savedData: { fileName: byte[] hash },
 *     levelDat: byte[] hash (可省略),
 *     totalUniqueBytes: long,
 *     deltaBytes: long,
 *     baselineComplete: byte (0/1, 缺字段按 0=false 读, 兼容旧 manifest),
 *     files: { 相对路径: {h: byte[] hash, s: byte 1 (suspect 才写)} } (Phase D, 缺字段=空),
 * }
 * </pre>
 */
public record SnapshotManifest(
        int version,
        String snapshotId,
        long createdAtMillis,
        long worldGameTime,
        Map<String, Map<Long, Hash>> chunks,
        Map<String, Map<Long, Hash>> entityChunks,
        Map<String, Hash> savedData,
        Hash levelDat,
        long totalUniqueBytes,
        long deltaBytes,
        boolean baselineComplete,
        FileManifest files) {

    public static final int SCHEMA_VERSION = 1;

    private static final String K_VERSION = "version";
    private static final String K_SNAPSHOT_ID = "snapshotId";
    private static final String K_CREATED_AT = "createdAtMillis";
    private static final String K_WORLD_GAME_TIME = "worldGameTime";
    private static final String K_CHUNKS = "chunks";
    private static final String K_ENTITY_CHUNKS = "entityChunks";
    private static final String K_SAVED_DATA = "savedData";
    private static final String K_LEVEL_DAT = "levelDat";
    private static final String K_TOTAL_BYTES = "totalUniqueBytes";
    private static final String K_DELTA_BYTES = "deltaBytes";
    private static final String K_BASELINE_COMPLETE = "baselineComplete";
    private static final String K_FILES = "files";
    private static final String K_POS = "pos";
    private static final String K_HASH = "hash";

    public SnapshotManifest {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(entityChunks, "entityChunks");
        Objects.requireNonNull(savedData, "savedData");
        Objects.requireNonNull(files, "files");
        // levelDat 允许 null (snapshot 没含 level.dat 时)
    }

    public static SnapshotManifest empty(String snapshotId, long worldGameTime) {
        return new SnapshotManifest(
                SCHEMA_VERSION,
                snapshotId,
                System.currentTimeMillis(),
                worldGameTime,
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                null,
                0L,
                0L,
                false,
                FileManifest.empty());
    }

    public NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        root.putInt(K_VERSION, version);
        root.putString(K_SNAPSHOT_ID, snapshotId);
        root.putLong(K_CREATED_AT, createdAtMillis);
        root.putLong(K_WORLD_GAME_TIME, worldGameTime);
        root.put(K_CHUNKS, dimMapToNbt(chunks));
        root.put(K_ENTITY_CHUNKS, dimMapToNbt(entityChunks));
        root.put(K_SAVED_DATA, savedDataToNbt(savedData));
        if (levelDat != null) {
            root.putByteArray(K_LEVEL_DAT, levelDat.bytes());
        }
        root.putLong(K_TOTAL_BYTES, totalUniqueBytes);
        root.putLong(K_DELTA_BYTES, deltaBytes);
        root.putBoolean(K_BASELINE_COMPLETE, baselineComplete);
        root.put(K_FILES, files.toNbt());
        return root;
    }

    public static SnapshotManifest fromNbt(NbtCompound root) {
        int v = root.getInt(K_VERSION);
        if (v != SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported manifest schema version " + v);
        }
        Hash levelDat = root.contains(K_LEVEL_DAT, NbtType.BYTE_ARRAY)
                ? new Hash(root.getByteArray(K_LEVEL_DAT))
                : null;
        // baselineComplete: 旧 manifest 无此字段, getBoolean 缺键返回 false,
        // 正好等于"未完成 baseline"的安全默认 (装 mod 早期的快照本就不该放行 restore).
        return new SnapshotManifest(
                v,
                root.getString(K_SNAPSHOT_ID),
                root.getLong(K_CREATED_AT),
                root.getLong(K_WORLD_GAME_TIME),
                dimMapFromNbt(root.getCompound(K_CHUNKS)),
                dimMapFromNbt(root.getCompound(K_ENTITY_CHUNKS)),
                savedDataFromNbt(root.getCompound(K_SAVED_DATA)),
                levelDat,
                root.getLong(K_TOTAL_BYTES),
                root.getLong(K_DELTA_BYTES),
                root.getBoolean(K_BASELINE_COMPLETE),
                // files: 旧 manifest 无此键, getCompound 返回空 compound, 解出空 FileManifest,
                // 等于"该快照没有玩家数据通道", restore 时不回装文件 (跟旧行为一致).
                FileManifest.fromNbt(root.getCompound(K_FILES)));
    }

    /** 写到磁盘 (atomic: tmp + fsync + rename). */
    public void writeTo(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            NbtWriter.writeCompressed(toNbt(), out);
        }
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ)) {
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static SnapshotManifest readFrom(Path source) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            return fromNbt(NbtReader.readCompressed(in));
        }
    }

    private static NbtCompound dimMapToNbt(Map<String, Map<Long, Hash>> dimMap) {
        NbtCompound tag = new NbtCompound();
        for (Map.Entry<String, Map<Long, Hash>> dimEntry : dimMap.entrySet()) {
            NbtList list = new NbtList();
            for (Map.Entry<Long, Hash> chunkEntry : dimEntry.getValue().entrySet()) {
                NbtCompound chunkTag = new NbtCompound();
                chunkTag.putLong(K_POS, chunkEntry.getKey());
                chunkTag.putByteArray(K_HASH, chunkEntry.getValue().bytes());
                list.add(chunkTag);
            }
            tag.put(dimEntry.getKey(), list);
        }
        return tag;
    }

    private static Map<String, Map<Long, Hash>> dimMapFromNbt(NbtCompound tag) {
        Map<String, Map<Long, Hash>> result = new LinkedHashMap<>();
        for (String dim : tag.keySet()) {
            NbtList list = tag.getList(dim, NbtType.COMPOUND);
            Map<Long, Hash> chunkMap = new HashMap<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                NbtCompound chunkTag = (NbtCompound) list.get(i);
                chunkMap.put(chunkTag.getLong(K_POS), new Hash(chunkTag.getByteArray(K_HASH)));
            }
            result.put(dim, chunkMap);
        }
        return result;
    }

    private static NbtCompound savedDataToNbt(Map<String, Hash> savedData) {
        NbtCompound tag = new NbtCompound();
        for (Map.Entry<String, Hash> entry : savedData.entrySet()) {
            tag.putByteArray(entry.getKey(), entry.getValue().bytes());
        }
        return tag;
    }

    private static Map<String, Hash> savedDataFromNbt(NbtCompound tag) {
        Map<String, Hash> result = new HashMap<>();
        for (String key : tag.keySet()) {
            result.put(key, new Hash(tag.getByteArray(key)));
        }
        return result;
    }
}
