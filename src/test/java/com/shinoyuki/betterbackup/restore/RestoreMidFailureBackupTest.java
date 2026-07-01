package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterbackup.io.WorldPaths;
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
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恢复中途失败的安全网: {@link RestoreFlow} 在把当前世界 atomic rename 进 {@code <world>.bak-*}
 * 之后、回写过程中失败时, 必须 (1) 让异常冒泡而非静默吞, (2) 让用户恢复前的世界完好留在
 * {@code .bak} 里可手工复原。
 *
 * <p>失败注入: 在 worldRoot 放一个名为 {@code blocked} 的普通文件 (不在 move 列表内, restore
 * 时仍在原地), 让 files 段里 {@code blocked/x.dat} 的 {@code createDirectories(worldRoot/blocked)}
 * 必抛 IOException —— 一次发生在 move 之后、回写中途的真实磁盘失败。
 *
 * <p>判定标准: 把 {@code moveCurrentWorldToBackup} 移 playerdata 那步删掉 (或恢复失败时不保留
 * .bak), 本用例对 {@code .bak/playerdata/p1.dat} 的比对必挂。
 */
class RestoreMidFailureBackupTest {

    @Test
    void restore_failing_midway_preserves_pre_restore_world_in_backup(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = root.resolve("snapshots");
        Files.createDirectories(world);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths paths = new WorldPaths(world);

        // 恢复前世界: 一份 playerdata, 代表用户当前 (可能已损坏) 的存档, 内容刻意区别于快照
        byte[] preRestorePlayer = "pre-restore inventory (user's current world)".getBytes(StandardCharsets.UTF_8);
        Path playerFile = world.resolve("playerdata").resolve("p1.dat");
        Files.createDirectories(playerFile.getParent());
        Files.write(playerFile, preRestorePlayer);

        // store: 一个正常 playerdata 对象 + 一个会触发回写失败的对象 (两者都在 store, verify 放行)
        byte[] goodBytes = "snapshot inventory".getBytes(StandardCharsets.UTF_8);
        Hash goodHash = hashFn.hash(goodBytes);
        store.put(goodHash, goodBytes);
        byte[] evilBytes = "snapshot evil".getBytes(StandardCharsets.UTF_8);
        Hash evilHash = hashFn.hash(evilBytes);
        store.put(evilHash, evilBytes);

        // 占名: worldRoot/blocked 是普通文件 (move 列表不含 -> restore 时仍在), 让下面那条 files
        // 条目回写时 createDirectories(worldRoot/blocked) 必抛 IOException.
        Files.write(world.resolve("blocked"), new byte[]{1, 2, 3});

        Map<String, Hash> fileHashes = new HashMap<>();
        fileHashes.put("playerdata/p1.dat", goodHash);
        fileHashes.put("blocked/x.dat", evilHash); // <- 这条回写必失败 (父级是普通文件)
        FileManifest files = new FileManifest(fileHashes, new HashSet<>());

        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, "snap-fail", System.currentTimeMillis(), 0L,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), null, 0L, 0L, true, files);
        manifest.writeTo(snapshotsDir.resolve("snap-fail.manifest"));

        RestoreFlow flow = new RestoreFlow(store, paths, snapshotsDir);
        assertThrows(IOException.class, () -> flow.restore("snap-fail"),
                "a failure during rebuild must propagate, not be swallowed");

        // 安全网核心: 恢复前的世界被原封移进 <world>.bak-*, 用户可手工复原
        Path backupDir = findBackupDir(root, world.getFileName().toString());
        assertNotNull(backupDir, "current world must be moved to a .bak-* directory before rebuild");
        Path backedUpPlayer = backupDir.resolve("playerdata").resolve("p1.dat");
        assertTrue(Files.isRegularFile(backedUpPlayer), "pre-restore playerdata must be preserved in .bak");
        assertArrayEquals(preRestorePlayer, Files.readAllBytes(backedUpPlayer),
                "the user's pre-restore world must survive a mid-restore failure intact in .bak");
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
