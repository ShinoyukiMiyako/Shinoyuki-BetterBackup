package com.shinoyuki.betterbackup.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DegradedSession 标志持久化 (PLAN Phase F). 断言具体落盘内容与读回值。
 * 判定标准: 删掉 mark 的写盘或 clear 的删除, 对应断言必挂。
 */
class DegradedSessionTest {

    @Test
    void mark_then_exists_and_reads_back_timestamp(@TempDir Path storeRoot) throws IOException {
        DegradedSession session = new DegradedSession(storeRoot);
        assertFalse(session.exists(), "未 mark 时标志不存在");
        assertTrue(session.markedAtMillis().isEmpty());

        session.mark(1_717_000_000_000L);

        assertTrue(session.exists(), "mark 后标志存在");
        OptionalLong at = session.markedAtMillis();
        assertTrue(at.isPresent());
        assertEquals(1_717_000_000_000L, at.getAsLong(), "读回的时刻必须等于 mark 写入的时刻");
    }

    @Test
    void clear_removes_flag(@TempDir Path storeRoot) throws IOException {
        DegradedSession session = new DegradedSession(storeRoot);
        session.mark(123L);
        assertTrue(session.exists());

        session.clear();

        assertFalse(session.exists(), "clear 后标志必须不存在");
        assertTrue(session.markedAtMillis().isEmpty());
    }

    @Test
    void corrupted_content_reads_as_empty_not_zero(@TempDir Path storeRoot) throws IOException {
        DegradedSession session = new DegradedSession(storeRoot);
        Files.createDirectories(storeRoot);
        Files.write(session.markerFile(), "not-a-number".getBytes(StandardCharsets.UTF_8));

        assertTrue(session.exists(), "损坏内容文件仍存在");
        assertTrue(session.markedAtMillis().isEmpty(),
                "无法解析的内容返回 empty, 不掩盖成 0");
    }

    @Test
    void mark_overwrites_prior_timestamp(@TempDir Path storeRoot) throws IOException {
        DegradedSession session = new DegradedSession(storeRoot);
        session.mark(100L);
        session.mark(200L);

        assertEquals(200L, session.markedAtMillis().getAsLong(),
                "再次 mark 覆盖写同一标志的时刻");
    }
}
