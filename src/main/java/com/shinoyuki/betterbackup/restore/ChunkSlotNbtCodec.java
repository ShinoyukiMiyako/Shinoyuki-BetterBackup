package com.shinoyuki.betterbackup.restore;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 把 ChunkStore 的 slot store 对象字节还原成 vanilla {@link CompoundTag}.
 *
 * <p>这是<b>在线</b>回退专用 (有 vanilla 依赖), 故落在 mod 侧 {@code restore} 包,
 * 不进零依赖离线 CLI —— 离线回退是字节级整 slot 直写 .mca, 从不解到 NBT, 因此 CLI
 * 路径必须保持零 net.minecraft import (PLAN Phase E 硬约束). 在线回退要把字节交给
 * BAS {@code SaveCoordination.restoreChunkLive} 反序列化成活 chunk, 该 API 吃的是
 * {@code CompoundTag}, 故此处必须先解到 NBT.
 *
 * <p>store 对象布局权威定义见 {@link com.shinoyuki.betterbackup.io.ChunkPayloadCodec}:
 * 第 0 字节 = compression type byte (最高位 0x80 = external flag), 压缩 payload 从第 1
 * 字节起 (inline 与 external 还原后一致). external 形态下 store 对象已由采集侧
 * (RegionFileSlotReader.resolveExternal) 拼成 "stub byte + .mcc 全部内容", 故本类只需
 * 剥 0x80 取 base type 再按整段 payload 解压, 无需关心 .mcc 是否外置.
 *
 * <p>compression type 编号与 vanilla {@code RegionFileVersion} 对齐: 1=gzip, 2=zlib,
 * 3=none. 此处复刻这套编号而非引 ChunkPayloadCodec 的包级常量, 是因为那些常量是 package
 * private (跨包不可见); 数值是 vanilla 钉死的协议常量, 不会漂移, 复刻无语义重复风险.
 */
public final class ChunkSlotNbtCodec {

    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_ZLIB = 2;
    private static final int COMPRESSION_NONE = 3;
    private static final int EXTERNAL_FLAG = 0x80;

    private ChunkSlotNbtCodec() {
    }

    /**
     * 解码一个完整 store 对象字节为 vanilla NBT.
     *
     * @param storeObject ChunkStore.get(hash) 的返回值 (external 已拼全)
     * @return 该 chunk 的根 CompoundTag
     * @throws IOException              压缩流损坏 / NBT 解析失败 (撕裂或版本不符), 自然冒泡
     * @throws IllegalArgumentException store 对象为空或 compression type 非法
     */
    public static CompoundTag decode(byte[] storeObject) throws IOException {
        if (storeObject == null || storeObject.length < 1) {
            throw new IllegalArgumentException("store object empty, cannot decode chunk NBT");
        }
        byte compressionByte = storeObject[0];
        int baseType = compressionByte & ~EXTERNAL_FLAG & 0xFF;
        // payload 从第 1 字节起 (compression byte 之后), inline / external 还原后一致.
        InputStream payload = new ByteArrayInputStream(storeObject, 1, storeObject.length - 1);
        InputStream decompressed = switch (baseType) {
            case COMPRESSION_GZIP -> new GZIPInputStream(payload);
            case COMPRESSION_ZLIB -> new InflaterInputStream(payload);
            case COMPRESSION_NONE -> payload;
            default -> throw new IllegalArgumentException("invalid compression type " + baseType
                    + " (raw byte=0x" + Integer.toHexString(compressionByte & 0xFF) + ")");
        };
        // NbtIo.read(DataInput) 单参重载内部用 NbtAccounter.UNLIMITED, 不对 chunk NBT 设
        // 堆配额 (vanilla 自身读 chunk 也是无界), 与采集侧字节 round-trip 一致.
        try (DataInputStream in = new DataInputStream(decompressed)) {
            return NbtIo.read(in);
        }
    }
}
