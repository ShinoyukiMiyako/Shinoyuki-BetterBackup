package com.shinoyuki.betterbackup.retention;

import com.shinoyuki.betterbackup.restore.PendingRestoreFlag;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetentionGuard 三门禁单元测试。断言具体 id 集合, 删掉门禁逻辑测试必挂。
 */
class RetentionGuardTest {

    /** 写一份最小 manifest (无引用) 到 snapshotsDir/&lt;id&gt;.manifest, baselineComplete 由参数决定。 */
    private static void writeManifest(Path snapshotsDir, String id, boolean baselineComplete) throws IOException {
        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                id,
                System.currentTimeMillis(),
                0L,
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                null,
                0L,
                0L,
                baselineComplete,
                FileManifest.empty());
        m.writeTo(snapshotsDir.resolve(id + ".manifest"));
    }

    @Test
    void empty_dir_no_pending_protects_nothing(@TempDir Path tempDir) throws IOException {
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        RetentionGuard guard = new RetentionGuard(snapshotsDir, worldRoot);
        assertEquals(Set.of(), guard.protectedIds());
    }

    @Test
    void gate_a_protects_latest_even_when_quota_would_doom_it(@TempDir Path tempDir) throws IOException {
        // 门禁 A: 最新一份无条件保护, 与配额无关. 造 3 份全 incomplete, hourly=0 会把全部判 doomed.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeManifest(snapshotsDir, "2026-05-10T10-00-00Z", false);
        writeManifest(snapshotsDir, "2026-05-10T20-00-00Z", false);  // latest
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);

        RetentionGuard guard = new RetentionGuard(snapshotsDir, worldRoot);
        Set<String> protectedIds = guard.protectedIds();
        // 无 baselineComplete, 无 pending: 只门禁 A 生效, 恰保住字典序最大者.
        assertEquals(Set.of("2026-05-10T20-00-00Z"), protectedIds);
        assertFalse(guard.isDeletable("2026-05-10T20-00-00Z"), "latest must be protected");
        assertTrue(guard.isDeletable("2026-05-10T10-00-00Z"), "non-latest incomplete is deletable");
    }

    @Test
    void gate_b_protects_latest_baseline_complete_distinct_from_latest(@TempDir Path tempDir) throws IOException {
        // 门禁 B: 最新一份是 incomplete (门禁 A 保它), 但唯一 baselineComplete 是更早那份 -> B 单独保住它.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeManifest(snapshotsDir, "2026-05-08T12-00-00Z", true);   // baselineComplete, 较早
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", false);  // latest, incomplete

        RetentionGuard guard = new RetentionGuard(snapshotsDir, worldRoot);
        Set<String> protectedIds = guard.protectedIds();
        // A 保最新 incomplete, B 保唯一 baselineComplete, 两份不同都受保护.
        assertEquals(Set.of("2026-05-10T12-00-00Z", "2026-05-08T12-00-00Z"), protectedIds);
    }

    @Test
    void gate_b_protects_newest_among_multiple_baseline_complete(@TempDir Path tempDir) throws IOException {
        // 多份 baselineComplete 时门禁 B 只保最新那份 (字典序最大), 更早的 baselineComplete 可删.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeManifest(snapshotsDir, "2026-05-08T12-00-00Z", true);
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", true);   // newest baselineComplete
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", false);  // latest overall

        RetentionGuard guard = new RetentionGuard(snapshotsDir, worldRoot);
        Set<String> protectedIds = guard.protectedIds();
        assertEquals(Set.of("2026-05-10T12-00-00Z", "2026-05-09T12-00-00Z"), protectedIds);
        assertTrue(guard.isDeletable("2026-05-08T12-00-00Z"), "older baseline-complete is deletable");
    }

    @Test
    void gate_c_protects_pending_restore_target(@TempDir Path tempDir) throws IOException {
        // 门禁 C: pending flag 指向的 id 受保护, 即便它既非最新也非 baselineComplete.
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeManifest(snapshotsDir, "2026-05-08T12-00-00Z", false);  // pending target
        writeManifest(snapshotsDir, "2026-05-09T12-00-00Z", false);
        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", false);  // latest
        PendingRestoreFlag.write(worldRoot, "2026-05-08T12-00-00Z");

        RetentionGuard guard = new RetentionGuard(snapshotsDir, worldRoot);
        Set<String> protectedIds = guard.protectedIds();
        // A 保最新 + C 保 pending 目标.
        assertEquals(Set.of("2026-05-10T12-00-00Z", "2026-05-08T12-00-00Z"), protectedIds);
        assertFalse(guard.isDeletable("2026-05-08T12-00-00Z"), "pending-restore target must be protected");
    }

    @Test
    void corrupt_manifest_aborts_protected_computation(@TempDir Path tempDir) throws IOException {
        // 损坏 manifest -> protectedIds 抛 IOException (fail-fast, 不静默跳过).
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        Path worldRoot = tempDir.resolve("world");
        Files.createDirectories(worldRoot);

        writeManifest(snapshotsDir, "2026-05-10T12-00-00Z", true);
        Files.write(snapshotsDir.resolve("2026-05-09T12-00-00Z.manifest"),
                new byte[]{0x00, 0x01, 0x02, 0x03});

        RetentionGuard guard = new RetentionGuard(snapshotsDir, worldRoot);
        assertThrows(IOException.class, guard::protectedIds);
    }
}
