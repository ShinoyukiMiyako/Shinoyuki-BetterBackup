package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.store.Hash;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
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
 * <p><b>序列化格式</b>: vanilla {@link NbtIo#writeCompressed} (gzip + NBT). 复用
 * Minecraft 已有工具链, 不引入新依赖. 大服 100k chunk × 16 byte hash ≈ 1.6 MB raw,
 * gzip 压缩约 1 MB.
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
        long deltaBytes) {

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
    private static final String K_POS = "pos";
    private static final String K_HASH = "hash";

    public SnapshotManifest {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(entityChunks, "entityChunks");
        Objects.requireNonNull(savedData, "savedData");
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
                0L);
    }

    public CompoundTag toNbt() {
        CompoundTag root = new CompoundTag();
        root.putInt(K_VERSION, version);
        root.putString(K_SNAPSHOT_ID, snapshotId);
        root.putLong(K_CREATED_AT, createdAtMillis);
        root.putLong(K_WORLD_GAME_TIME, worldGameTime);
        root.put(K_CHUNKS, dimMapToNbt(chunks));
        root.put(K_ENTITY_CHUNKS, dimMapToNbt(entityChunks));
        root.put(K_SAVED_DATA, savedDataToNbt(savedData));
        if (levelDat != null) {
            root.put(K_LEVEL_DAT, new ByteArrayTag(levelDat.bytes()));
        }
        root.putLong(K_TOTAL_BYTES, totalUniqueBytes);
        root.putLong(K_DELTA_BYTES, deltaBytes);
        return root;
    }

    public static SnapshotManifest fromNbt(CompoundTag root) {
        int v = root.getInt(K_VERSION);
        if (v != SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported manifest schema version " + v);
        }
        Hash levelDat = root.contains(K_LEVEL_DAT, Tag.TAG_BYTE_ARRAY)
                ? new Hash(root.getByteArray(K_LEVEL_DAT))
                : null;
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
                root.getLong(K_DELTA_BYTES));
    }

    /** 写到磁盘 (atomic: tmp + fsync + rename). */
    public void writeTo(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        NbtIo.writeCompressed(toNbt(), tmp.toFile());
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ)) {
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static SnapshotManifest readFrom(Path source) throws IOException {
        CompoundTag root = NbtIo.readCompressed(source.toFile());
        return fromNbt(root);
    }

    private static CompoundTag dimMapToNbt(Map<String, Map<Long, Hash>> dimMap) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Map<Long, Hash>> dimEntry : dimMap.entrySet()) {
            ListTag list = new ListTag();
            for (Map.Entry<Long, Hash> chunkEntry : dimEntry.getValue().entrySet()) {
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putLong(K_POS, chunkEntry.getKey());
                chunkTag.put(K_HASH, new ByteArrayTag(chunkEntry.getValue().bytes()));
                list.add(chunkTag);
            }
            tag.put(dimEntry.getKey(), list);
        }
        return tag;
    }

    private static Map<String, Map<Long, Hash>> dimMapFromNbt(CompoundTag tag) {
        Map<String, Map<Long, Hash>> result = new LinkedHashMap<>();
        for (String dim : tag.getAllKeys()) {
            ListTag list = tag.getList(dim, Tag.TAG_COMPOUND);
            Map<Long, Hash> chunkMap = new HashMap<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag chunkTag = list.getCompound(i);
                chunkMap.put(chunkTag.getLong(K_POS), new Hash(chunkTag.getByteArray(K_HASH)));
            }
            result.put(dim, chunkMap);
        }
        return result;
    }

    private static CompoundTag savedDataToNbt(Map<String, Hash> savedData) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Hash> entry : savedData.entrySet()) {
            tag.put(entry.getKey(), new ByteArrayTag(entry.getValue().bytes()));
        }
        return tag;
    }

    private static Map<String, Hash> savedDataFromNbt(CompoundTag tag) {
        Map<String, Hash> result = new HashMap<>();
        for (String key : tag.getAllKeys()) {
            result.put(key, new Hash(tag.getByteArray(key)));
        }
        return result;
    }
}
