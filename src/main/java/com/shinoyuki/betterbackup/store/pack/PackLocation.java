package com.shinoyuki.betterbackup.store.pack;

/**
 * 一个对象在 pack 中的物理位置.
 *
 * @param packId     所在 pack 文件 id (对应 {@code <packId>.pack})
 * @param dataOffset 对象原始字节在 pack 文件内的起始偏移 (已跳过 [hash][len] 头)
 * @param length     对象原始字节长度
 */
public record PackLocation(int packId, long dataOffset, int length) {
}
