package com.shinoyuki.betterbackup.store.pack;

import com.shinoyuki.betterbackup.store.Hash;

import java.io.IOException;
import java.util.Set;
import java.util.SortedMap;

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
     * 尝试从磁盘加载已持久化的索引, 并与当前 pack 集按 pack 粒度差分。
     *
     * <p>pack 文件封口后不可变 (load 后新写入只进新 pack), 故 (id, 字节数) 是 pack 内容的
     * 可靠身份: 崩溃恢复通常只有最后一个在写 pack 尺寸变化, 差分让重扫从 O(全部 pack) 降到
     * O(变化 pack) (issue #3 的重扫放大根因)。
     *
     * @param currentPacks 当前磁盘 pack 集: packId -&gt; 文件字节数 (按 id 升序)
     * @return 仍需 PackStore {@code scanPack} 的 packId 集合: 空集 = 全命中零重扫;
     *         未变 pack 的条目已保留, 变化/新增 pack 需重扫 (调用方扫完后 {@link #checkpoint});
     *         无持久索引 / 版本或内容无法使用时返回全集 (退化为全量重扫)。
     *         清单里有而磁盘上消失的 pack, 其条目一律丢弃。
     */
    Set<Integer> tryLoad(SortedMap<Integer, Long> currentPacks) throws IOException;

    /** 把当前索引连同 per-pack (id, 字节数) 清单持久化到磁盘 (close / 压实 / load 重扫后调)。 */
    void checkpoint(SortedMap<Integer, Long> currentPacks) throws IOException;

    /** 关闭底层资源 (mmap / 文件句柄)。 */
    void close() throws IOException;
}
