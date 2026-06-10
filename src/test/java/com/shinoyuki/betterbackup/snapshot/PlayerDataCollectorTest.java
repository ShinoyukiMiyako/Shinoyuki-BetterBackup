package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家数据通道采集 (PLAN Phase D commit 8) 的单元断言: 四个目录递归扫描、整文件字节入
 * 同一 store、相对路径 forward-slash 归一、suspect 标记路径。
 *
 * <p>判定标准: 删掉 collectFile 里 suspect 标记的那行, {@code unstable_*} 用例必挂;
 * 删掉 collect 里 store.put 这行, {@code *_stored_byte_exact} 用例必挂。
 */
class PlayerDataCollectorTest {

    private static byte[] write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, bytes);
        return bytes;
    }

    @Test
    void collects_all_four_dirs_and_stores_bytes_exact(@TempDir Path base) throws IOException {
        Path worldRoot = base.resolve("world");
        Path storeRoot = base.resolve("store");
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        HashFunction hashFn = new Xxh128HashFunction();
        Set<Hash> written = ConcurrentHashMap.newKeySet();

        byte[] playerBytes = write(worldRoot.resolve("playerdata").resolve("uuid-a.dat"), "player-a-inventory");
        byte[] statsBytes = write(worldRoot.resolve("stats").resolve("uuid-a.json"), "{\"stat.mined\":42}");
        byte[] advBytes = write(worldRoot.resolve("advancements").resolve("uuid-a.json"), "{\"adv\":true}");
        byte[] poiBytes = write(worldRoot.resolve("poi").resolve("r.0.0.mca"), "poi-region-bytes");
        // 不在四目录内的文件不得被采集 (level.dat / region 走别的通道)
        write(worldRoot.resolve("region").resolve("r.0.0.mca"), "region-not-player-data");

        PlayerDataCollector collector = new PlayerDataCollector(store, new WorldPaths(worldRoot), hashFn, written);
        FileManifest manifest = collector.collect();

        assertEquals(4, manifest.hashes().size(), "exactly the four player-data files must be captured");
        assertTrue(manifest.suspect().isEmpty(), "stable files must not be suspect");
        // 相对路径必须 forward-slash 归一 (跨平台 / restore 拆回原位的契约)
        assertTrue(manifest.hashes().containsKey("playerdata/uuid-a.dat"));
        assertTrue(manifest.hashes().containsKey("stats/uuid-a.json"));
        assertTrue(manifest.hashes().containsKey("advancements/uuid-a.json"));
        assertTrue(manifest.hashes().containsKey("poi/r.0.0.mca"));
        assertFalse(manifest.hashes().containsKey("region/r.0.0.mca"),
                "files outside the four player-data dirs must not be captured");

        // 入库字节必须与原文件逐字节一致 (content-addressed: get(hash) 回原字节)
        assertArrayEquals(playerBytes, store.get(manifest.hashes().get("playerdata/uuid-a.dat")));
        assertArrayEquals(statsBytes, store.get(manifest.hashes().get("stats/uuid-a.json")));
        assertArrayEquals(advBytes, store.get(manifest.hashes().get("advancements/uuid-a.json")));
        assertArrayEquals(poiBytes, store.get(manifest.hashes().get("poi/r.0.0.mca")));
        assertTrue(written.contains(manifest.hashes().get("playerdata/uuid-a.dat")),
                "newly stored hash must be recorded in writtenThisWindow for incremental gc");
    }

    @Test
    void missing_dirs_are_skipped_without_error(@TempDir Path base) throws IOException {
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        Path storeRoot = base.resolve("store");
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();

        // worldRoot 下只有 playerdata, 缺 stats/advancements/poi -- 不应抛, 只采到 playerdata
        write(worldRoot.resolve("playerdata").resolve("uuid-a.dat"), "only-player");

        PlayerDataCollector collector = new PlayerDataCollector(
                store, new WorldPaths(worldRoot), new Xxh128HashFunction(), ConcurrentHashMap.newKeySet());
        FileManifest manifest = collector.collect();

        assertEquals(1, manifest.hashes().size());
        assertTrue(manifest.hashes().containsKey("playerdata/uuid-a.dat"));
    }

    /**
     * 撕裂读路径: 用每次调用都返回不同 hash 的 HashFunction 模拟"两次读字节 hash 不一致"
     * (采集期间文件被持续改写). collectFile 重试 MAX_RETRY 次仍不一致, 必须入库最后字节并
     * 标 suspect, 而非静默丢弃或入库时不标记。
     */
    @Test
    void unstable_file_is_marked_suspect_but_still_stored(@TempDir Path base) throws IOException {
        Path worldRoot = base.resolve("world");
        Path storeRoot = base.resolve("store");
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Set<Hash> written = ConcurrentHashMap.newKeySet();

        write(worldRoot.resolve("playerdata").resolve("uuid-a.dat"), "moving-target");

        // 每次 hash 调用返回递增的唯一值 -> 任意两次读的 hash 永不相等 -> 撕裂读判定恒成立
        AtomicInteger counter = new AtomicInteger();
        HashFunction everChanging = new HashFunction() {
            @Override
            public Hash hash(byte[] input) {
                byte[] b = new byte[16];
                int n = counter.incrementAndGet();
                b[0] = (byte) n;
                b[1] = (byte) (n >> 8);
                return new Hash(b);
            }

            @Override
            public String name() {
                return "ever-changing";
            }

            @Override
            public int outputLength() {
                return 16;
            }
        };

        PlayerDataCollector collector = new PlayerDataCollector(store, new WorldPaths(worldRoot), everChanging, written);
        FileManifest manifest = collector.collect();

        assertEquals(1, manifest.hashes().size());
        assertTrue(manifest.suspect().contains("playerdata/uuid-a.dat"),
                "file whose hash never stabilizes after retries must be marked suspect");
        Hash storedHash = manifest.hashes().get("playerdata/uuid-a.dat");
        assertTrue(store.has(storedHash),
                "suspect file must still be stored (data preserved, not silently dropped)");
        // 重试上限: 初次 hash 1 次 + MAX_RETRY 轮各 reread 1 次 = 1 + 3 = 4 次 hash 调用
        assertEquals(1 + PlayerDataCollector.MAX_RETRY, counter.get(),
                "must retry exactly MAX_RETRY times before giving up and marking suspect");
    }
}
