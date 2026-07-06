package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.baseline.BaselineProgress;
import com.shinoyuki.betterbackup.config.BetterBackupConfig;
import com.shinoyuki.betterbackup.diagnostic.BetterBackupMetrics;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * verifyOnSnapshot 写入期自检 (safety.verifyOnSnapshot). 复验本窗口 writtenThisWindow 里新写入的
 * unique 对象: store.get 重取字节 -> 重算 hash -> 与原 hash 比对, 失配走 degraded-only 处置
 * (.incomplete + snapshotsFailed++, 绝不 markDegraded)。
 *
 * <p>不打桩 store (无 Mockito), 用真实 ChunkStore + @TempDir。制造失配的手段: store.put 不校验
 * hash==内容, 故把字节存进一个故意错的 hash 下 (fabricatedHash != hashFunction.hash(bytes)),
 * store.get(fabricatedHash) 取回原字节但重算 hash 必不等 —— 精确复现位翻转, 不碰 pack 内部。
 *
 * <p>config verifyOnSnapshot 是无 setter 的 static volatile, 测试用反射置位并在 @AfterEach 复位,
 * 避免跨用例全局污染。
 *
 * <p>判定标准 (删核心逻辑必挂): 删掉 create() 里的 verifyOnSnapshot() 调用 ->
 * corrupt / unreadable 用例的 snapshotsFailed / .incomplete 断言必挂; 删掉 config 开关判断
 * (always run) -> disabled 用例的 "无失败" 断言必挂; 把复验源从 writtenThisWindow 换成全 store
 * -> dedup 用例的 "老对象不被复验" 断言必挂。
 */
class SnapshotCreatorVerifyTest {

    @AfterEach
    void restoreConfig() throws Exception {
        setVerifyOnSnapshot(false);
    }

    private static void setVerifyOnSnapshot(boolean value) throws Exception {
        Field f = BetterBackupConfig.class.getDeclaredField("verifyOnSnapshot");
        f.setAccessible(true);
        f.setBoolean(null, value);
    }

    private SnapshotCreator newCreator(Path storeRoot, Path worldRoot, ChunkStore store,
                                       HashFunction hashFunction, Set<Hash> writtenThisWindow,
                                       BetterBackupMetrics metrics) {
        WorldPaths paths = new WorldPaths(worldRoot);
        return new SnapshotCreator(store, new CurrentSnapshotState(), paths, hashFunction, storeRoot,
                () -> 0L, writtenThisWindow, metrics, new BaselineProgress(storeRoot), () -> false);
    }

    private ChunkStore openStore(Path storeRoot) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        return store;
    }

    @Test
    void corrupt_written_object_marks_this_snapshot_failed_without_degraded(@TempDir Path base)
            throws Exception {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = openStore(storeRoot);
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // 存字节到一个"错误"的 hash 下: get 取回原字节, 重算 hash 必不等 -> 位翻转失配.
        byte[] payload = "verify-corrupt-payload".getBytes(StandardCharsets.UTF_8);
        Hash fabricated = hashFn.hash("some-other-content".getBytes(StandardCharsets.UTF_8));
        assertFalse(fabricated.equals(hashFn.hash(payload)), "fixture: fabricated hash must not match payload");
        assertTrue(store.put(fabricated, payload), "fixture: fabricated object is new unique");

        Set<Hash> written = ConcurrentHashMap.newKeySet();
        written.add(fabricated);
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, store, hashFn, written, metrics);

        setVerifyOnSnapshot(true);
        creator.create("interval");

        assertEquals(1, metrics.snapshot().snapshotsFailed(), "content mismatch marks this snapshot failed");
        assertTrue(creator.failureMarker().exists(), ".incomplete written on verify mismatch");
        assertTrue(creator.failureMarker().read().orElseThrow().reason().contains("content mismatch"),
                "marker reason distinguishes content mismatch");
        assertFalse(creator.isDegraded(), "verify mismatch must NOT trip the process-level degraded latch");
    }

    @Test
    void intact_written_objects_pass_verify_with_no_failure(@TempDir Path base) throws Exception {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = openStore(storeRoot);
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // 正确内容寻址: hash == hashFunction.hash(bytes), 复验必通过.
        byte[] a = "intact-a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "intact-b".getBytes(StandardCharsets.UTF_8);
        Hash ha = hashFn.hash(a);
        Hash hb = hashFn.hash(b);
        store.put(ha, a);
        store.put(hb, b);

        Set<Hash> written = ConcurrentHashMap.newKeySet();
        written.add(ha);
        written.add(hb);
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, store, hashFn, written, metrics);

        setVerifyOnSnapshot(true);
        creator.create("interval");

        assertEquals(1, metrics.snapshot().snapshotsCreated(), "snapshot succeeds");
        assertEquals(0, metrics.snapshot().snapshotsFailed(), "intact bytes: no verify failure");
        assertFalse(creator.failureMarker().exists(), "no .incomplete when all verified bytes intact");
        assertFalse(creator.isDegraded());
    }

    @Test
    void verify_disabled_by_default_does_not_flag_a_corrupt_object(@TempDir Path base) throws Exception {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = openStore(storeRoot);
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // 一个会被复验判失配的对象, 但 config 关 -> 根本不复验, 不该有任何失败信号.
        byte[] payload = "would-be-corrupt".getBytes(StandardCharsets.UTF_8);
        Hash fabricated = hashFn.hash("mismatch-key".getBytes(StandardCharsets.UTF_8));
        store.put(fabricated, payload);

        Set<Hash> written = ConcurrentHashMap.newKeySet();
        written.add(fabricated);
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, store, hashFn, written, metrics);

        // 不置位 config (默认 false, @AfterEach 也复位保证).
        creator.create("interval");

        assertEquals(1, metrics.snapshot().snapshotsCreated());
        assertEquals(0, metrics.snapshot().snapshotsFailed(),
                "verify off: corrupt object in writtenThisWindow must not be checked");
        assertFalse(creator.failureMarker().exists(), "verify off: no .incomplete");
    }

    @Test
    void dedup_hit_object_not_in_window_is_not_verified(@TempDir Path base) throws Exception {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = openStore(storeRoot);
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // dedup 命中语义: 对象在 store 里且损坏, 但因是老对象 (store.put 返回 false) 不进
        // writtenThisWindow. 复验覆盖面就是 writtenThisWindow, 故这个损坏老对象不被碰.
        byte[] payload = "old-corrupt-dedup".getBytes(StandardCharsets.UTF_8);
        Hash fabricated = hashFn.hash("old-key".getBytes(StandardCharsets.UTF_8));
        store.put(fabricated, payload);

        // writtenThisWindow 里放一个"干净"对象证明复验确实跑了 (只是没碰老对象).
        byte[] clean = "clean-this-window".getBytes(StandardCharsets.UTF_8);
        Hash cleanHash = hashFn.hash(clean);
        store.put(cleanHash, clean);

        Set<Hash> written = ConcurrentHashMap.newKeySet();
        written.add(cleanHash);   // 只有本窗口新写入的干净对象进复验集
        // fabricated (损坏老对象) 故意不加入 -> 模拟 dedup 命中不进 window
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, store, hashFn, written, metrics);

        setVerifyOnSnapshot(true);
        creator.create("interval");

        assertEquals(1, metrics.snapshot().snapshotsCreated());
        assertEquals(0, metrics.snapshot().snapshotsFailed(),
                "dedup-hit corrupt object outside writtenThisWindow must not be verified/flagged");
        assertFalse(creator.failureMarker().exists());
    }

    @Test
    void object_unreadable_counts_as_mismatch_and_marks_failed(@TempDir Path base) throws Exception {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = openStore(storeRoot);
        Xxh128HashFunction hashFn = new Xxh128HashFunction();

        // writtenThisWindow 里挂一个从未写进 store 的 hash -> store.get 抛 NoSuchFileException
        // = 更严重的写入丢失, 也算失配.
        Hash phantom = hashFn.hash("never-written".getBytes(StandardCharsets.UTF_8));
        Set<Hash> written = ConcurrentHashMap.newKeySet();
        written.add(phantom);
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, store, hashFn, written, metrics);

        setVerifyOnSnapshot(true);
        creator.create("interval");

        assertEquals(1, metrics.snapshot().snapshotsFailed(), "unreadable object marks this snapshot failed");
        assertTrue(creator.failureMarker().exists());
        assertTrue(creator.failureMarker().read().orElseThrow().reason().contains("unreadable"),
                "marker reason distinguishes unreadable/write-lost from content mismatch");
        assertFalse(creator.isDegraded(), "unreadable must NOT trip degraded latch");
    }

    @Test
    void verify_self_fault_is_isolated_and_does_not_mismark_a_written_snapshot(@TempDir Path base)
            throws Exception {
        Path storeRoot = base.resolve("store");
        Path worldRoot = base.resolve("world");
        Files.createDirectories(worldRoot);
        ChunkStore store = openStore(storeRoot);
        Xxh128HashFunction realHash = new Xxh128HashFunction();

        byte[] payload = "verify-self-fault".getBytes(StandardCharsets.UTF_8);
        Hash h = realHash.hash(payload);
        store.put(h, payload);

        // hashFunction 在复验重算时抛异常 = verify 步骤自身故障 (非内容失配). 快照 manifest 已落盘,
        // 该异常必须被失败隔离吞掉: 快照仍记 created, 不额外记 "manifest write failed" 失败.
        HashFunction throwingOnVerify = new HashFunction() {
            @Override
            public Hash hash(byte[] input) {
                throw new IllegalStateException("simulated verify recompute fault");
            }

            @Override
            public String name() {
                return "throwing";
            }

            @Override
            public int outputLength() {
                return realHash.outputLength();
            }
        };

        Set<Hash> written = ConcurrentHashMap.newKeySet();
        written.add(h);
        BetterBackupMetrics metrics = new BetterBackupMetrics();
        SnapshotCreator creator = newCreator(storeRoot, worldRoot, store, throwingOnVerify, written, metrics);

        setVerifyOnSnapshot(true);
        creator.create("interval");

        assertEquals(1, metrics.snapshot().snapshotsCreated(),
                "snapshot manifest already written: still counted created");
        assertEquals(0, metrics.snapshot().snapshotsFailed(),
                "verify self-fault must be isolated, not re-marked as manifest write failure");
        assertFalse(creator.failureMarker().exists(),
                "verify self-fault must not drop a .incomplete on an already-written snapshot");
        assertFalse(creator.isDegraded());
    }
}
