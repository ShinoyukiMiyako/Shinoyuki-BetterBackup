package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.store.Hash;

import java.io.IOException;

/**
 * pack 对象索引: hash -> {@link PackLocation}. 抽成接口以便在"内存 HashMap" (简单, 小 store /
 * 测试) 与"mmap 紧凑有序索引 + 内存 delta + Bloom" (零堆, 百万对象生产) 之间切换。
 *
 * <p><b>线程安全契约</b>: {@link #get}/{@link #contains}/{@link #put} 由 PackStore 在读锁下并发调用,
 * 实现必须保证这三者并发安全。{@link #remove}/{@link #clear}/{@link #tryLoad}/{@link #checkpoint}
 * 只在 PackStore 写锁 (压实) 或单线程 load 期间调用, 无需自身并发保护。
 */
interface PackIndex {

    /** 查位置, 不存在返回 null. */
    PackLocation get(Hash hash);

    boolean contains(Hash hash);

    /** 登记一个对象位置 (新对象或重定位)。 */
    void put(Hash hash, PackLocation loc);

    /** 摘除一个对象 (压实回收死对象时)。 */
    void remove(Hash hash);

    /** 当前存活对象数。 */
    int size();

    /** 清空 (load 重建前)。 */
    void clear();

    /**
     * 尝试从磁盘加载已持久化的索引并校验其与当前 pack 集的指纹一致。
     *
     * @param packSetFingerprint PackStore 算出的当前 pack 集指纹 (pack id + 文件大小)
     * @return true = 已加载且指纹匹配 (PackStore 无需重扫 pack); false = 无持久索引 / 指纹不匹配
     *         (PackStore 须顺序扫所有 pack 重建索引, 再 {@link #checkpoint})
     */
    boolean tryLoad(long packSetFingerprint) throws IOException;

    /** 把当前索引持久化到磁盘, 打上 {@code packSetFingerprint} 戳 (close / 压实后调)。 */
    void checkpoint(long packSetFingerprint) throws IOException;

    /** 关闭底层资源 (mmap / 文件句柄)。 */
    void close() throws IOException;
}
