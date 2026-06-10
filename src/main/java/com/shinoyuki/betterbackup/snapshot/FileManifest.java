package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.store.Hash;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * manifest 的 files 段: 玩家数据通道 (playerdata/ stats/ advancements/ poi/) 等整文件
 * 备份单元的 {@code 相对路径 -> 内容 hash} 映射, 外加 suspect 标记集合 (PLAN Phase D).
 *
 * <p>chunk 通道走 slot 级 raw bytes, 这些目录下的文件没有 region 那样的 slot 结构,
 * 按整文件字节 hash 入同一 {@link com.shinoyuki.betterbackup.store.ChunkStore} 共享 dedup。
 *
 * <p><b>相对路径约定</b>: 相对 worldRoot, 一律 forward-slash 分隔 (如
 * {@code playerdata/<uuid>.dat}), 跨平台可移植, restore 时按 {@code /} 拆回子路径落到
 * worldRoot 下原位。
 *
 * <p><b>suspect 语义</b>: 采集时连续读两次字节 hash 不一致 (撕裂读), 重试 3 次仍不一致的
 * 条目仍入库其最后一次读到的字节, 但在此标 suspect=true。restore 回装该条目时 WARN 提示
 * 字节可能不一致 -- 不静默存疑似坏数据, 也不丢失该文件 (有总比没有强, 由用户知情决定)。
 *
 * <p><b>NBT 表示</b>: CompoundTag, key=相对路径, value=CompoundTag{ h: byte[] hash,
 * s: byte 1 (suspect 时才写, 缺省=非 suspect) }, 兼容旧 manifest (无 files 段时为空)。
 */
public record FileManifest(Map<String, Hash> hashes, Set<String> suspect) {

    private static final String K_HASH = "h";
    private static final String K_SUSPECT = "s";

    public FileManifest {
        Objects.requireNonNull(hashes, "hashes");
        Objects.requireNonNull(suspect, "suspect");
    }

    public static FileManifest empty() {
        return new FileManifest(new HashMap<>(), new HashSet<>());
    }

    public boolean isEmpty() {
        return hashes.isEmpty();
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Hash> entry : hashes.entrySet()) {
            CompoundTag fileTag = new CompoundTag();
            fileTag.put(K_HASH, new ByteArrayTag(entry.getValue().bytes()));
            if (suspect.contains(entry.getKey())) {
                fileTag.putByte(K_SUSPECT, (byte) 1);
            }
            tag.put(entry.getKey(), fileTag);
        }
        return tag;
    }

    public static FileManifest fromNbt(CompoundTag tag) {
        Map<String, Hash> hashes = new LinkedHashMap<>();
        Set<String> suspect = new HashSet<>();
        for (String relativePath : tag.getAllKeys()) {
            CompoundTag fileTag = tag.getCompound(relativePath);
            hashes.put(relativePath, new Hash(fileTag.getByteArray(K_HASH)));
            if (fileTag.getByte(K_SUSPECT) == (byte) 1) {
                suspect.add(relativePath);
            }
        }
        return new FileManifest(hashes, suspect);
    }
}
