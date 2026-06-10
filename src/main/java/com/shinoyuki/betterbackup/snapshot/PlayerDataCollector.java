package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.BetterBackupMod;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.HashFunction;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 玩家数据通道采集 (PLAN Phase D commit 8). 在 snapshot 创建时与 chunk 同代采集
 * worldRoot 下 {@code playerdata/ stats/ advancements/ poi/} 四个目录的全部文件, 整文件
 * 字节 hash 入同一 {@link ChunkStore}, 返回 {@link FileManifest} (相对路径 -> hash + suspect)。
 *
 * <p><b>为何整文件而非 slot</b>: 这些目录下的文件 (玩家 .dat / stats .json / advancements
 * .json / poi 的 r.x.z.mca) 没有 chunk region 那样供 BAS listener 标 dirty 的 slot 钩子,
 * 也不走 vanilla IOWorker 的 slot 重写. 整文件 hash 入 store 跟 region 共享 dedup pool,
 * restore 时整文件回写。回滚后玩家背包与世界同步, 消除 FTB Backups 2 #95 的刷物品事故。
 *
 * <p><b>撕裂读防御</b>: 这些文件 vanilla 用 "写临时文件 + rename" 落盘, 理论上 snapshot
 * 期间不会读到半写. 但保守起见仍做双读校验 -- 读字节 hash, 再读再 hash, 不一致重试最多
 * {@link #MAX_RETRY} 次. 仍不一致说明采集期间该文件持续被改写, 入库最后一次读到的字节并标
 * suspect=true (而非静默丢弃或入库疑似坏数据), WARN 提示, 由 restore/用户知情决定。
 */
public final class PlayerDataCollector {

    private static final Logger LOGGER = BetterBackupMod.LOGGER;

    /**
     * 撕裂读双读不一致的重试上限 (PLAN Phase D 指定 3 次). 这些文件走 vanilla rename 落盘,
     * 极少撕裂; 3 次仍不一致只可能是采集期间被高频改写, 标 suspect 比无限重试或丢数据稳妥。
     */
    static final int MAX_RETRY = 3;

    /** worldRoot 下需整文件采集的玩家数据目录 (forward-slash, 同时是相对路径前缀). */
    static final List<String> PLAYER_DATA_DIRS =
            List.of("playerdata", "stats", "advancements", "poi");

    private final ChunkStore store;
    private final WorldPaths paths;
    private final HashFunction hashFunction;
    private final Set<Hash> writtenThisWindow;

    public PlayerDataCollector(ChunkStore store,
                               WorldPaths paths,
                               HashFunction hashFunction,
                               Set<Hash> writtenThisWindow) {
        this.store = store;
        this.paths = paths;
        this.hashFunction = hashFunction;
        this.writtenThisWindow = writtenThisWindow;
    }

    /**
     * 采集四个目录的全部文件. 目录不存在 (新世界 / 未生成 poi) 跳过, 不报错。
     * 返回的 FileManifest 的相对路径相对 worldRoot, 一律 forward-slash。
     */
    public FileManifest collect() throws IOException {
        Path worldRoot = paths.worldRoot();
        Map<String, Hash> hashes = new HashMap<>();
        Set<String> suspect = new HashSet<>();
        for (String dirName : PLAYER_DATA_DIRS) {
            Path dir = worldRoot.resolve(dirName);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (Path file : listFilesRecursively(dir)) {
                String relativePath = toRelativePath(worldRoot, file);
                CollectedFile collected = collectFile(file, relativePath);
                hashes.put(relativePath, collected.hash());
                if (collected.suspect()) {
                    suspect.add(relativePath);
                }
            }
        }
        return new FileManifest(hashes, suspect);
    }

    /**
     * 双读校验单个文件并入库. 两次读字节 hash 一致即直接入库返回; 不一致重试最多 MAX_RETRY
     * 次. 仍不一致入库最后一次读到的字节并标 suspect。入库用 store.put (内容寻址 dedup)。
     */
    private CollectedFile collectFile(Path file, String relativePath) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Hash hash = hashFunction.hash(bytes);
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            byte[] reread = Files.readAllBytes(file);
            Hash rehash = hashFunction.hash(reread);
            if (rehash.equals(hash)) {
                storePayload(hash, bytes);
                return new CollectedFile(hash, false);
            }
            LOGGER.warn("[BetterBackup] player-data file changed during read, retry {}: {}",
                    attempt + 1, relativePath);
            bytes = reread;
            hash = rehash;
        }
        // MAX_RETRY 次双读仍不一致: 入库最后一次读到的字节 (bytes 当前指向最后一次 reread)
        // 并标 suspect. 不丢这个文件 (有总比没有强), 也不静默 -- restore 时 WARN, 用户知情。
        LOGGER.warn("[BetterBackup] player-data file unstable after {} retries, marking suspect: {}",
                MAX_RETRY, relativePath);
        storePayload(hash, bytes);
        return new CollectedFile(hash, true);
    }

    private void storePayload(Hash hash, byte[] bytes) throws IOException {
        if (store.put(hash, bytes)) {
            writtenThisWindow.add(hash);
        }
    }

    /** worldRoot 相对路径, OS 分隔符归一为 forward-slash, 跨平台可移植且 restore 可拆回。 */
    private static String toRelativePath(Path worldRoot, Path file) {
        return worldRoot.relativize(file).toString().replace('\\', '/');
    }

    private static List<Path> listFilesRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> result = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(result::add);
            return result;
        }
    }

    private record CollectedFile(Hash hash, boolean suspect) {
    }
}
