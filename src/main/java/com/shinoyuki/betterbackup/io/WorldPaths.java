package com.shinoyuki.betterbackup.io;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 把 dimensionId (如 {@code "minecraft:overworld"}) 映射到 world 内的 region /
 * entities / data 子目录路径.
 *
 * <p>1.20.1 vanilla 路径约定:
 * <ul>
 *   <li>overworld → worldRoot/</li>
 *   <li>the_nether → worldRoot/DIM-1/</li>
 *   <li>the_end → worldRoot/DIM1/</li>
 *   <li>modded → worldRoot/dimensions/&lt;namespace&gt;/&lt;path&gt;/</li>
 * </ul>
 *
 * <p>每个 dim 下有 {@code region/} (chunk save) / {@code entities/} (entity save,
 * v0.6+) / {@code data/} (SavedData) 三个子目录.
 */
public final class WorldPaths {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";

    private final Path worldRoot;

    public WorldPaths(Path worldRoot) {
        this.worldRoot = Objects.requireNonNull(worldRoot, "worldRoot");
    }

    public Path worldRoot() {
        return worldRoot;
    }

    public Path levelDat() {
        return worldRoot.resolve("level.dat");
    }

    public Path regionDir(String dimensionId) {
        return dimRoot(dimensionId).resolve("region");
    }

    public Path entitiesDir(String dimensionId) {
        return dimRoot(dimensionId).resolve("entities");
    }

    public Path dataDir(String dimensionId) {
        return dimRoot(dimensionId).resolve("data");
    }

    public Path dimRoot(String dimensionId) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (OVERWORLD.equals(dimensionId)) {
            return worldRoot;
        }
        if (NETHER.equals(dimensionId)) {
            return worldRoot.resolve("DIM-1");
        }
        if (END.equals(dimensionId)) {
            return worldRoot.resolve("DIM1");
        }
        int colon = dimensionId.indexOf(':');
        if (colon < 0 || colon == dimensionId.length() - 1) {
            throw new IllegalArgumentException("invalid dimension id: " + dimensionId);
        }
        String namespace = dimensionId.substring(0, colon);
        String path = dimensionId.substring(colon + 1);
        return worldRoot.resolve("dimensions").resolve(namespace).resolve(path);
    }
}
