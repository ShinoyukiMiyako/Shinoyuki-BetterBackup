package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.store.Hash;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 维护 "自上次 snapshot 以来 BAS Listener fire 过的所有条目 + 当前 hash".
 * <p>
 * 三个独立 map (chunk / entityChunk / savedData) + 一个 atomic ref (levelDat).
 * SnapshotCreator (Phase 2) 调 {@link #drainAndClear()} 拿出本次 snapshot 内容,
 * BackupWorker 在 fire 后 putChunk/putEntityChunk/putSavedData 写入.
 *
 * <p><b>线程安全</b>: ConcurrentHashMap 保证 putXxx + drain 并发安全. drain 用
 * forEach + remove(k, v) CAS-style 删除, 期间新 put 同 key 不同 value 时 remove
 * 失败保留新值, 下次 drain pickup, 不丢数据.
 *
 * <p><b>覆盖语义</b>: putChunk(key, hash) 是覆盖式 (Map.put), 同一 chunk 在两次
 * snapshot 间被 BAS save 多次时 (REQUEUE_DIRTY 路径) 仅最新 hash 进 manifest.
 * 中间版本通过 BackupWorker 已经写入 ChunkStore, 但 unreferenced, 下次增量 GC
 * 清掉 (DESIGN §2.6).
 */
public final class CurrentSnapshotState {

    private final Map<DimChunkKey, Hash> dirtyChunks = new ConcurrentHashMap<>();
    private final Map<DimChunkKey, Hash> dirtyEntityChunks = new ConcurrentHashMap<>();
    private final Map<String, Hash> dirtySavedData = new ConcurrentHashMap<>();
    private final AtomicReference<Hash> dirtyLevelDat = new AtomicReference<>();

    public void putChunk(String dimensionId, long packedPos, Hash hash) {
        dirtyChunks.put(new DimChunkKey(dimensionId, packedPos), hash);
    }

    public void putEntityChunk(String dimensionId, long packedPos, Hash hash) {
        dirtyEntityChunks.put(new DimChunkKey(dimensionId, packedPos), hash);
    }

    /**
     * 登记一个 SavedData 条目。{@code key} 是该 .dat 相对 worldRoot 的路径 (含维度子目录,
     * 如 {@code data/raids.dat}、{@code DIM1/data/raids_end.dat}), 由 SavedDataBackupTask 计算 ——
     * 维度隐含在 key 里, restore 据此落回原维度。
     */
    public void putSavedData(String key, Hash hash) {
        dirtySavedData.put(key, hash);
    }

    public void putLevelDat(Hash hash) {
        dirtyLevelDat.set(hash);
    }

    /**
     * 该 chunk 是否已在本周期 dirty map 中 (BAS 活跃路径已采或 baseline 已采过).
     * baseline 全量扫描用此判定跳过已入队/已处理的 chunk: 以 CurrentSnapshotState
     * 为准, 活跃 dirty 路径采的版本是磁盘最新状态, baseline 不该用旧字节覆盖它.
     */
    public boolean containsChunk(String dimensionId, long packedPos) {
        return dirtyChunks.containsKey(new DimChunkKey(dimensionId, packedPos));
    }

    /** 同 {@link #containsChunk} 但针对 entity chunk 通道. */
    public boolean containsEntityChunk(String dimensionId, long packedPos) {
        return dirtyEntityChunks.containsKey(new DimChunkKey(dimensionId, packedPos));
    }

    /**
     * 当前所有 dirty 条目的 hash 值快照 (chunk + entityChunk + savedData + levelDat).
     * 增量 GC 用它排除"已登记但尚未进本次 manifest"的 hash, 防止误删 baseline / 活跃
     * dirty 路径在本次 drain 之后并发登记的 chunk 字节 (其登记还在等下一份 manifest 引用).
     * 见 SnapshotCreator.runIncrementalGc 的并发安全说明. 返回独立副本.
     */
    public Set<Hash> pendingHashes() {
        Set<Hash> out = new HashSet<>();
        out.addAll(dirtyChunks.values());
        out.addAll(dirtyEntityChunks.values());
        out.addAll(dirtySavedData.values());
        Hash level = dirtyLevelDat.get();
        if (level != null) {
            out.add(level);
        }
        return out;
    }

    public int size() {
        return dirtyChunks.size() + dirtyEntityChunks.size() + dirtySavedData.size()
                + (dirtyLevelDat.get() != null ? 1 : 0);
    }

    public int chunkCount() {
        return dirtyChunks.size();
    }

    public int entityChunkCount() {
        return dirtyEntityChunks.size();
    }

    public int savedDataCount() {
        return dirtySavedData.size();
    }

    /**
     * 拷出当前所有 dirty 条目并清空. 用 CAS remove(k, v) 防 race: 拷出过程中如果
     * 同 key 被 put 新 value, remove 失败保留新值, 下次 drain pickup.
     */
    public Drained drainAndClear() {
        Map<DimChunkKey, Hash> chunkSnap = new HashMap<>();
        dirtyChunks.forEach((k, v) -> {
            chunkSnap.put(k, v);
            dirtyChunks.remove(k, v);
        });
        Map<DimChunkKey, Hash> entitySnap = new HashMap<>();
        dirtyEntityChunks.forEach((k, v) -> {
            entitySnap.put(k, v);
            dirtyEntityChunks.remove(k, v);
        });
        Map<String, Hash> savedSnap = new HashMap<>();
        dirtySavedData.forEach((k, v) -> {
            savedSnap.put(k, v);
            dirtySavedData.remove(k, v);
        });
        Hash levelSnap = dirtyLevelDat.getAndSet(null);
        return new Drained(chunkSnap, entitySnap, savedSnap, levelSnap);
    }

    /**
     * 把一次 drain 出来但未能成功落盘的快照回灌 dirty map, 供下次快照重试。SnapshotCreator 在
     * drainAndClear 之后的失败分支 (玩家数据采集 / manifest 写盘) 调用 —— 此时 dirty 已被清空,
     * 不回灌这些条目就永久丢出备份 (BAS 活跃路径采的版本除本 state 外无第二份记录)。
     *
     * <p>用 putIfAbsent / compareAndSet(null, ..) 而非 put: 若该 key 在失败窗口内已被新 fire
     * 覆盖 (dirty 里已有更新的 hash), 保留更新值, 不让回灌的旧 hash 造成版本回退。
     */
    public void reinject(Drained drained) {
        drained.chunks().forEach(dirtyChunks::putIfAbsent);
        drained.entityChunks().forEach(dirtyEntityChunks::putIfAbsent);
        drained.savedData().forEach(dirtySavedData::putIfAbsent);
        Hash level = drained.levelDat();
        if (level != null) {
            dirtyLevelDat.compareAndSet(null, level);
        }
    }

    /**
     * drain 出来的快照. 不持任何对底层 ConcurrentHashMap 的引用,
     * SnapshotCreator 处理它时跟 putXxx 调用方互不干扰.
     */
    public record Drained(
            Map<DimChunkKey, Hash> chunks,
            Map<DimChunkKey, Hash> entityChunks,
            Map<String, Hash> savedData,
            Hash levelDat) {

        public int totalSize() {
            return chunks.size() + entityChunks.size() + savedData.size() + (levelDat != null ? 1 : 0);
        }
    }
}
