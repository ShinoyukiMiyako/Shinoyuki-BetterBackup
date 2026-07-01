package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.store.Hash;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 HashMap 索引. 简单、零持久 (每次 load 由 PackStore 顺序扫 pack 重建)。小 store / 测试用;
 * 百万对象生产应换 mmap 实现以避免 ~每对象数十字节的堆占用。
 *
 * <p>{@link #tryLoad} 恒返回 false (无持久化), 故 PackStore 总是顺序扫 pack 重建 ——
 * 与 pack 改造初版行为一致。{@link #checkpoint} 是 no-op。
 */
final class InMemoryPackIndex implements PackIndex {

    private final Map<Hash, PackLocation> index = new ConcurrentHashMap<>();

    @Override
    public PackLocation get(Hash hash) {
        return index.get(hash);
    }

    @Override
    public boolean contains(Hash hash) {
        return index.containsKey(hash);
    }

    @Override
    public void put(Hash hash, PackLocation loc) {
        index.put(hash, loc);
    }

    @Override
    public void remove(Hash hash) {
        index.remove(hash);
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public void clear() {
        index.clear();
    }

    @Override
    public boolean tryLoad(long packSetFingerprint) {
        return false; // 无持久化, 总是让 PackStore 重扫 pack 重建
    }

    @Override
    public void checkpoint(long packSetFingerprint) {
        // no-op: 内存索引不落盘
    }

    @Override
    public void close() {
        // no-op
    }
}
