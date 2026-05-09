package com.shinoyuki.betterbackup.snapshot;

import java.util.Objects;

/**
 * (dimensionId, packedPos) 二元组作为 chunk-级 map key.
 * <p>
 * dimensionId 直接用 vanilla {@code ResourceLocation.toString()} 输出
 * (如 "minecraft:overworld"). packedPos 是 vanilla {@code ChunkPos.toLong()},
 * 高 32 位 z, 低 32 位 x.
 */
public record DimChunkKey(String dimensionId, long packedPos) {

    public DimChunkKey {
        Objects.requireNonNull(dimensionId, "dimensionId");
    }
}
