package com.shinoyuki.betterbackup.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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

    /**
     * 扫描磁盘发现所有存在 {@code region/} 或 {@code entities/} 子目录的维度, 返回各自的
     * canonical dimensionId. baseline 全量扫描用此枚举要遍历的维度.
     *
     * <p>vanilla 不在 level.dat 里登记自定义维度列表 (维度由 mod datapack 注册, 运行时
     * 才知道), 唯一可靠来源是磁盘上已落盘的目录. 这里反向解析三个固定维度 (overworld /
     * DIM-1 / DIM1) 加 {@code dimensions/<namespace>/<path>/} 下每个含 region|entities
     * 的叶子目录. 返回的 id 保证能往回喂给 {@link #regionDir}/{@link #entitiesDir} 拼出
     * 同一磁盘路径, 这样 RestoreFlow 按 manifest 重建时落到原位.
     */
    public List<String> discoverDimensions() throws IOException {
        List<String> result = new ArrayList<>();
        if (hasChunkDir(worldRoot)) {
            result.add(OVERWORLD);
        }
        if (hasChunkDir(worldRoot.resolve("DIM-1"))) {
            result.add(NETHER);
        }
        if (hasChunkDir(worldRoot.resolve("DIM1"))) {
            result.add(END);
        }
        Path dimensionsRoot = worldRoot.resolve("dimensions");
        if (Files.isDirectory(dimensionsRoot)) {
            discoverModdedDimensions(dimensionsRoot, result);
        }
        return result;
    }

    private static void discoverModdedDimensions(Path dimensionsRoot, List<String> out) throws IOException {
        try (Stream<Path> namespaces = Files.list(dimensionsRoot)) {
            for (Path nsDir : (Iterable<Path>) namespaces.filter(Files::isDirectory)::iterator) {
                String namespace = nsDir.getFileName().toString();
                try (Stream<Path> paths = Files.list(nsDir)) {
                    for (Path pathDir : (Iterable<Path>) paths.filter(Files::isDirectory)::iterator) {
                        if (hasChunkDir(pathDir)) {
                            out.add(namespace + ":" + pathDir.getFileName());
                        }
                    }
                }
            }
        }
    }

    private static boolean hasChunkDir(Path dimRoot) {
        return Files.isDirectory(dimRoot.resolve("region"))
                || Files.isDirectory(dimRoot.resolve("entities"));
    }
}
