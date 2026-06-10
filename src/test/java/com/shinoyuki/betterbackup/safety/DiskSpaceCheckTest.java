package com.shinoyuki.betterbackup.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskSpaceCheckTest {

    @Test
    void passes_when_min_is_one_byte_on_real_temp_dir(@TempDir Path tempDir) throws IOException {
        // 真实 @TempDir 所在盘几乎不可能只剩 1 字节, 阈值 1 必过. 不抛即通过.
        DiskSpaceCheck.require(tempDir, 1L, "snapshot");
    }

    @Test
    void throws_when_required_exceeds_total_disk(@TempDir Path tempDir) {
        // 要求 8 EiB 远超任何真实磁盘容量, 必然触发不足.
        long impossible = Long.MAX_VALUE;
        DiskSpaceCheck.InsufficientSpaceException ex = assertThrows(
                DiskSpaceCheck.InsufficientSpaceException.class,
                () -> DiskSpaceCheck.require(tempDir, impossible, "restore"));
        assertEquals(impossible, ex.requiredBytes());
        assertTrue(ex.usableBytes() >= 0, "usable bytes must be reported");
        assertTrue(ex.usableBytes() < impossible, "usable must be below the impossible requirement");
        assertTrue(ex.getMessage().contains("restore"), "operation name surfaced in message");
    }

    @Test
    void resolves_filestore_via_nonexistent_target_using_existing_ancestor(@TempDir Path tempDir) {
        // 目标目录尚不存在 (store / world 首次创建场景): 回溯到存在的祖先查 FileStore.
        Path notYetCreated = tempDir.resolve("store").resolve("chunks").resolve("ab");
        assertThrows(DiskSpaceCheck.InsufficientSpaceException.class,
                () -> DiskSpaceCheck.require(notYetCreated, Long.MAX_VALUE, "snapshot"));
    }

    @Test
    void insufficient_space_exception_is_an_ioexception() {
        // 类型契约: 必须是 IOException 子类, 才能从 SnapshotCreator / RestoreFlow 自然冒泡.
        DiskSpaceCheck.InsufficientSpaceException ex = assertThrows(
                DiskSpaceCheck.InsufficientSpaceException.class,
                () -> DiskSpaceCheck.require(Path.of(System.getProperty("java.io.tmpdir")),
                        Long.MAX_VALUE, "snapshot"));
        IOException asIo = ex;
        assertSame(ex, asIo);
    }
}
