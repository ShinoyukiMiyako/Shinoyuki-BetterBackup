package com.shinoyuki.betterbackup.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotFailureMarkerTest {

    @Test
    void absent_marker_reads_empty(@TempDir Path storeRoot) throws IOException {
        SnapshotFailureMarker marker = new SnapshotFailureMarker(storeRoot);
        assertFalse(marker.exists());
        assertTrue(marker.read().isEmpty());
    }

    @Test
    void write_then_read_round_trips_timestamp_and_reason(@TempDir Path storeRoot) throws IOException {
        SnapshotFailureMarker marker = new SnapshotFailureMarker(storeRoot);
        marker.write(1_700_000_123_456L, "disk space precheck failed");

        assertTrue(marker.exists());
        Optional<SnapshotFailureMarker.Failure> f = marker.read();
        assertTrue(f.isPresent());
        assertEquals(1_700_000_123_456L, f.get().atMillis());
        assertEquals("disk space precheck failed", f.get().reason());
    }

    @Test
    void clear_removes_marker(@TempDir Path storeRoot) throws IOException {
        SnapshotFailureMarker marker = new SnapshotFailureMarker(storeRoot);
        marker.write(1L, "boom");
        assertTrue(marker.exists());

        marker.clear();
        assertFalse(marker.exists());
        assertTrue(marker.read().isEmpty());
    }

    @Test
    void clear_on_absent_marker_is_noop(@TempDir Path storeRoot) throws IOException {
        SnapshotFailureMarker marker = new SnapshotFailureMarker(storeRoot);
        marker.clear(); // 不抛
        assertFalse(marker.exists());
    }

    @Test
    void write_overwrites_previous_failure(@TempDir Path storeRoot) throws IOException {
        SnapshotFailureMarker marker = new SnapshotFailureMarker(storeRoot);
        marker.write(1L, "first");
        marker.write(2L, "second");

        SnapshotFailureMarker.Failure f = marker.read().orElseThrow();
        assertEquals(2L, f.atMillis());
        assertEquals("second", f.reason());
    }

    @Test
    void reason_newline_and_tab_are_flattened_to_keep_single_line(@TempDir Path storeRoot)
            throws IOException {
        SnapshotFailureMarker marker = new SnapshotFailureMarker(storeRoot);
        marker.write(5L, "line1\nline2\tcol2");

        // 物理文件必须是单行: tab 是字段分隔符, 原因里的 tab/newline 已被压成空格
        String raw = Files.readString(marker.markerFile(), StandardCharsets.UTF_8);
        long tabCount = raw.chars().filter(c -> c == '\t').count();
        assertEquals(1, tabCount, "only the field-separator tab may remain");
        assertFalse(raw.contains("\n"), "no embedded newline in marker");

        SnapshotFailureMarker.Failure f = marker.read().orElseThrow();
        assertEquals("line1 line2 col2", f.reason());
    }

    @Test
    void malformed_marker_without_tab_keeps_whole_line_as_reason(@TempDir Path storeRoot)
            throws IOException {
        // 手工改坏 / 旧格式: 没有 tab 分隔. 不静默吞, 整行当原因, 时间戳记 0.
        SnapshotFailureMarker marker = new SnapshotFailureMarker(storeRoot);
        Files.write(marker.markerFile(), "legacy reason without tab".getBytes(StandardCharsets.UTF_8));

        SnapshotFailureMarker.Failure f = marker.read().orElseThrow();
        assertEquals(0L, f.atMillis());
        assertEquals("legacy reason without tab", f.reason());
    }
}
