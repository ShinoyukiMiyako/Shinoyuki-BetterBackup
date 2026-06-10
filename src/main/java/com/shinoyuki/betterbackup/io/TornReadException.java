package com.shinoyuki.betterbackup.io;

import java.io.IOException;

/**
 * 读 chunk slot 时检测到撕裂读 (torn read) 的专用异常.
 *
 * <p><b>为什么单独一个异常类型而不是普通 IOException</b>: 撕裂读跟结构性 IO 错误
 * (盘坏 / 文件截断) 语义不同. 撕裂读是瞬态竞态 -- worker 读 slot 时撞上 vanilla
 * IOWorker 对同一扇区的原地重写, 读到新旧混合字节. 这种失败应当延后重试 (vanilla
 * 重写在毫秒级完成), 而真正的 IOException 重试没有意义. {@link RegionFileSlotReader}
 * 用本异常区分两者, 调用方 (ChunkBackupTask / EntityChunkBackupTask) 据此决定
 * "重新标 dirty 延后重试" 还是 "记失败冒泡".
 *
 * <p>检出点 (任一命中即抛):
 * <ul>
 *   <li>读 payload 前后各读一次 4 byte location entry, 不一致 = 期间 chunk 被搬迁</li>
 *   <li>对 payload 做 zlib/gzip inflate 读到 EOF, 校验失败 = 字节损坏 (新旧混合)</li>
 * </ul>
 */
public final class TornReadException extends IOException {

    public TornReadException(String message) {
        super(message);
    }

    public TornReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
