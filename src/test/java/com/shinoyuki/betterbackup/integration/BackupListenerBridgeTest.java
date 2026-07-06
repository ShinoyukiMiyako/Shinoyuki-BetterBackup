package com.shinoyuki.betterbackup.integration;

import com.shinoyuki.betterbackup.worker.BackupTask;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BackupListenerBridge 有界队列反压/降级契约。
 *
 * <p>用 SavedData 通道 (只需 String + CompoundTag, 不构造 ResourceKey) 驱动. 反压超时注入 0
 * 使降级路径确定性触发不阻塞测试。判定标准: 队列满时新 task 被丢弃而非无上限入队, 且丢弃计数
 * 精确 +1; 把有界队列换回无界 (bug) 或删掉降级计数逻辑, 断言必挂。
 */
class BackupListenerBridgeTest {

    @Test
    void enqueues_without_dropping_when_capacity_available() {
        BlockingQueue<BackupTask> queue = new LinkedBlockingQueue<>(10);
        BackupListenerBridge bridge = new BackupListenerBridge(queue, 0L);

        for (int i = 0; i < 5; i++) {
            bridge.onSavedDataWritten("f" + i + ".dat", new CompoundTag());
        }

        assertEquals(5, queue.size(), "容量充足时全部入队");
        assertEquals(0L, bridge.droppedTaskCount(), "容量充足时不丢任何 task");
    }

    @Test
    void drops_and_counts_when_queue_full_after_backpressure() {
        BlockingQueue<BackupTask> queue = new LinkedBlockingQueue<>(2);
        BackupListenerBridge bridge = new BackupListenerBridge(queue, 0L);

        bridge.onSavedDataWritten("a.dat", new CompoundTag());
        bridge.onSavedDataWritten("b.dat", new CompoundTag());
        assertEquals(2, queue.size(), "容量内两个 task 正常入队");
        assertEquals(0L, bridge.droppedTaskCount());

        // 队列已满: 第三个 task 反压超时 (0ms) 后降级丢弃, 不越界增长.
        bridge.onSavedDataWritten("c.dat", new CompoundTag());
        assertEquals(2, queue.size(), "队列满时新 task 被丢弃, 队列不越界增长");
        assertEquals(1L, bridge.droppedTaskCount(), "丢弃计数精确 +1");

        // 再满再丢, 计数继续累加.
        bridge.onSavedDataWritten("d.dat", new CompoundTag());
        assertEquals(2, queue.size());
        assertEquals(2L, bridge.droppedTaskCount(), "第二次丢弃计数累加到 2");
    }
}
