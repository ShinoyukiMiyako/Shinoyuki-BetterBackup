package com.shinoyuki.betterbackup.cli;

/**
 * chunk 坐标 packed long 的纯 Java 编解码, 复刻 vanilla {@code net.minecraft.world.level.ChunkPos}
 * 的位布局: 低 32 位 = x, 高 32 位 = z。
 *
 * <p><b>为什么不直接用 ChunkPos</b>: manifest 里 chunk key 是 {@code ChunkPos.asLong} 的输出
 * (mod 侧 BackupListenerBridge / RestoreFlow 用 vanilla ChunkPos 算)。离线 CLI 的硬约束是
 * 代码路径零 net.minecraft import, 故 CLI 的 restore 不能复用 {@code RestoreFlow} (它 import
 * ChunkPos), 必须用本类把 packed long 拆回 (x, z)。位布局与 vanilla 完全一致, 因此 CLI 拆出的
 * 坐标与 mod 侧写入 manifest 时的坐标恒等。
 */
public final class ChunkPosCodec {

    private ChunkPosCodec() {
    }

    public static int getX(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    public static int getZ(long packed) {
        return (int) ((packed >>> 32) & 0xFFFFFFFFL);
    }

    public static long asLong(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }
}
