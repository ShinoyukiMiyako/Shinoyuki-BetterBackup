package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.store.Hash;

import java.util.HashMap;
import java.util.Map;
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

    public void putSavedData(String fileName, Hash hash) {
        dirtySavedData.put(fileName, hash);
    }

    public void putLevelDat(Hash hash) {
        dirtyLevelDat.set(hash);
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
