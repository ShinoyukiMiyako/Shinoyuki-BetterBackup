package com.shinoyuki.betterbackup;

import com.shinoyuki.betterbackup.restore.PendingRestoreFlag;
import com.shinoyuki.betterbackup.restore.RestoreFlow;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期 pending restore 的失败语义 (C3): {@link BetterBackupMod#runPendingRestore} 是
 * onServerAboutToStart 的可测内核。它在 vanilla loadLevel 之前执行 restore, 而 RestoreFlow 一旦
 * 在 moveCurrentWorldToBackup 之后回写中途盘错, worldRoot 会停在半重建态。
 *
 * <p>本用例锁死三条契约, 防止退回"失败只 log 不中止"的旧行为:
 * <ol>
 *   <li>失败必须让异常冒泡 (调用方据此中止服务器启动, 绝不放行 vanilla 加载半成品);</li>
 *   <li>失败时 flag 保留 (下次启动重试), 旧世界完好留在 {@code <world>.bak-*};</li>
 *   <li>成功后才清 flag 并重建世界; 无 flag 则完全 no-op。</li>
 * </ol>
 *
 * <p>判定标准: 把 runPendingRestore 的失败改回吞异常 -> 用例 1 的 assertThrows 必挂; 把失败分支
 * 误清 flag -> 用例 1 的 flag 保留断言必挂; 把成功分支漏清 flag -> 用例 2 必挂。
 */
class PendingRestoreStartupTest {

    @Test
    void pending_restore_failing_midway_throws_retains_flag_and_preserves_world(@TempDir Path root)
            throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);

        byte[] preRestorePlayer = "pre-restore inventory (user's current world)".getBytes(StandardCharsets.UTF_8);
        Path playerFile = world.resolve("playerdata").resolve("p1.dat");
        Files.createDirectories(playerFile.getParent());
        Files.write(playerFile, preRestorePlayer);

        // 占名: worldRoot/blocked 是普通文件 (不在 move 列表 -> restore 时仍在原地), 让下面那条 files
        // 条目回写时 createDirectories(worldRoot/blocked) 必抛 IOException -- 一次 move 之后的真实磁盘失败。
        Files.write(world.resolve("blocked"), new byte[]{1, 2, 3});

        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        byte[] goodBytes = "snapshot inventory".getBytes(StandardCharsets.UTF_8);
        Hash goodHash = hashFn.hash(goodBytes);
        byte[] evilBytes = "snapshot evil".getBytes(StandardCharsets.UTF_8);
        Hash evilHash = hashFn.hash(evilBytes);
        Map<Hash, byte[]> seed = new LinkedHashMap<>();
        seed.put(goodHash, goodBytes);
        seed.put(evilHash, evilBytes);
        seedStore(storeRoot, seed);

        Map<String, Hash> fileHashes = new HashMap<>();
        fileHashes.put("playerdata/p1.dat", goodHash);
        fileHashes.put("blocked/x.dat", evilHash); // 这条回写必失败 (父级是普通文件)
        writeFilesManifest(snapshotsDir, "snap-fail", new FileManifest(fileHashes, new HashSet<>()));

        PendingRestoreFlag.write(world, "snap-fail");

        assertThrows(IOException.class, () -> BetterBackupMod.runPendingRestore(world, storeRoot),
                "a mid-restore failure must propagate so the caller aborts startup, not be swallowed");

        assertTrue(PendingRestoreFlag.exists(world),
                "restore flag must be retained on failure so the next boot retries");
        Path backupDir = findBackupDir(root, "world");
        assertNotNull(backupDir, "current world must be moved to a .bak-* directory before rebuild");
        assertArrayEquals(preRestorePlayer,
                Files.readAllBytes(backupDir.resolve("playerdata").resolve("p1.dat")),
                "the user's pre-restore world must survive a mid-restore failure intact in .bak");
    }

    @Test
    void successful_pending_restore_clears_flag_and_rebuilds_world(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);

        byte[] preRestorePlayer = "pre-restore inventory".getBytes(StandardCharsets.UTF_8);
        Path playerFile = world.resolve("playerdata").resolve("p1.dat");
        Files.createDirectories(playerFile.getParent());
        Files.write(playerFile, preRestorePlayer);

        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        byte[] snapshotBytes = "snapshot inventory".getBytes(StandardCharsets.UTF_8);
        Hash hash = hashFn.hash(snapshotBytes);
        Map<Hash, byte[]> seed = new LinkedHashMap<>();
        seed.put(hash, snapshotBytes);
        seedStore(storeRoot, seed);

        Map<String, Hash> fileHashes = new HashMap<>();
        fileHashes.put("playerdata/p1.dat", hash);
        writeFilesManifest(snapshotsDir, "snap-ok", new FileManifest(fileHashes, new HashSet<>()));

        PendingRestoreFlag.write(world, "snap-ok");

        RestoreFlow.RestoreResult result = BetterBackupMod.runPendingRestore(world, storeRoot);

        assertNotNull(result, "a performed restore must return a non-null result");
        assertEquals(1L, result.playerDataFilesRestored(), "the single playerdata file must be restored");
        assertFalse(PendingRestoreFlag.exists(world), "flag must be cleared only after a successful restore");
        assertArrayEquals(snapshotBytes, Files.readAllBytes(playerFile),
                "playerdata must be rebuilt to the snapshot bytes (pre-restore content replaced)");
    }

    @Test
    void no_pending_flag_is_a_noop(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Files.createDirectories(world);

        RestoreFlow.RestoreResult result = BetterBackupMod.runPendingRestore(world, storeRoot);

        assertNull(result, "no pending flag must be a no-op returning null");
        assertNull(findBackupDir(root, "world"), "no pending flag must not move the world to .bak");
    }

    private static void seedStore(Path storeRoot, Map<Hash, byte[]> objects) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        try {
            for (Map.Entry<Hash, byte[]> e : objects.entrySet()) {
                store.put(e.getKey(), e.getValue());
            }
            store.flushAndSync();
        } finally {
            store.close();
        }
    }

    private static void writeFilesManifest(Path snapshotsDir, String id, FileManifest files) throws IOException {
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, id, System.currentTimeMillis(), 0L,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, files);
        manifest.writeTo(snapshotsDir.resolve(id + ".manifest"));
    }

    private static Path findBackupDir(Path root, String worldName) throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(worldName + ".bak-"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
