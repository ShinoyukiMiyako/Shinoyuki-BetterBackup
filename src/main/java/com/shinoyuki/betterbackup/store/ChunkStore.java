package com.shinoyuki.betterbackup.store;

import com.shinoyuki.betterbackup.log.BackupLog;
import com.shinoyuki.betterbackup.store.pack.PackStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 内容寻址 dedup store 门面. 自 pack 改造起, 新写入一律进 append-only {@link PackStore}
 * (机械盘适配, 根治百万小文件的随机寻道与每对象 fsync). 历史"一对象一文件"布局
 * ({@code chunks/<2>/<6>/<full>}) 转为<b>只读 + 惰性漏水</b>: 不再有新文件写进去, 旧对象
 * 留在原地供读, 随其所属快照被保留策略淘汰而由 GC 按对象删除, 在保留窗口内自然排空。
 *
 * <p><b>惰性双读</b> ({@link #has}/{@link #get}): 先查 pack, 命中即走 pack; 否则回退旧文件树。
 * 写入 ({@link #put}) 命中 pack 或旧树即 dedup 跳过, 否则只写 pack —— 旧对象绝不复制进 pack,
 * 避免迁移期数据翻倍。
 *
 * <p><b>atomic / 崩溃安全</b>: 字节落盘语义下放给 {@link PackStore} —— put 仅顺序追加,
 * 持久化由 {@link #flushAndSync} 屏障 (SnapshotCreator 写 manifest 前调一次) 完成, 把每对象
 * fsync 压成每快照 1 次。manifest 落盘即其引用对象落盘的不变量保持。
 *
 * <p>走内部门面 {@link BackupLog} 而非 slf4j: store 被离线 CLI (裸 JRE, 无 slf4j) 复用,
 * slf4j 的 static LoggerFactory 会让本类一加载就 NoClassDefFoundError。
 */
public final class ChunkStore {

    private static final String LOGGER_NAME = ChunkStore.class.getName();

    /** xxh128 = 16 字节. 当前唯一落地的 hash 算法 (SHA256/BLAKE3 未实现), pack store.meta 据此校验. */
    private static final int DEFAULT_HASH_LENGTH = 16;

    private final Path storeRoot;
    private final Path chunksDir;
    private final PackStore packStore;

    public ChunkStore(Path storeRoot) {
        this(storeRoot, DEFAULT_HASH_LENGTH, PackStore.DEFAULT_TARGET_PACK_SIZE_BYTES);
    }

    public ChunkStore(Path storeRoot, int hashLength, long targetPackSizeBytes) {
        this.storeRoot = storeRoot;
        this.chunksDir = storeRoot.resolve("chunks");
        this.packStore = new PackStore(storeRoot, hashLength, targetPackSizeBytes);
    }

    public void initialize() throws IOException {
        // 仍建旧 chunks 目录: GC/fsck 与旧 store 兼容路径据其存在与否判断; 新 store 下保持空目录无害.
        Files.createDirectories(chunksDir);
        packStore.initialize();
        BackupLog.info(LOGGER_NAME, "[BetterBackup] store initialized at {} (pack objects={})",
                storeRoot.toAbsolutePath(), packStore.objectCount());
    }

    public Path storeRoot() {
        return storeRoot;
    }

    public Path chunksDir() {
        return chunksDir;
    }

    /** 底层 pack store. GC 压实 / fsck 扫描用. */
    public PackStore packStore() {
        return packStore;
    }

    /** 关服索引 checkpoint (有界 tryLock, 失败仅 warn). 见 {@link PackStore#checkpointOnShutdown}. */
    public void checkpointOnShutdown() {
        packStore.checkpointOnShutdown();
    }

    /**
     * store 磁盘体积近似 = pack 目录字节 ({@link PackStore#totalPackBytes}, 廉价, store 主体)
     * + 旧文件树 ({@code chunks/}) 求和. 迁移完成的新 store 旧树为空, 此项为 0; 迁移期旧树
     * 还没排空则计入, 反映真实占用.
     *
     * <p><b>近似而非精确</b>: 不计 snapshots/manifest 与 index/ 元数据 (相对对象体积可忽略),
     * 只量对象数据本体, 用于 maxStoreSizeGB 软阈值判定. 旧树 walk 是 O(旧树文件数), 迁移完成后
     * 为一次空目录 walk (廉价); 迁移期旧树大时可能贵, 故调用方 (启动超阈值自检) 必须在后台线程跑,
     * 绝不在主线程算.
     */
    public long approxStoreBytes() throws IOException {
        long total = packStore.totalPackBytes();
        if (!Files.isDirectory(chunksDir)) {
            return total;
        }
        try (Stream<Path> walk = Files.walk(chunksDir)) {
            // 旧树对象文件 (非 .tmp): 一对象一文件, 累加其字节. .tmp 是在途/孤儿, 不计入体积.
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                if (p.getFileName().toString().endsWith(".tmp")) {
                    continue;
                }
                total += Files.size(p);
            }
        }
        return total;
    }

    /** 旧布局二级分桶路径: chunks/<前2hex>/<前6hex>/<32hex全名>. 旧对象读 / GC 旧树删 / fsck 旧树扫用. */
    public Path pathFor(Hash hash) {
        String hex = hash.toHex();
        if (hex.length() < 6) {
            throw new IllegalArgumentException("hash hex too short for two-level bucket: " + hex);
        }
        return chunksDir.resolve(hex.substring(0, 2)).resolve(hex.substring(0, 6)).resolve(hex);
    }

    public boolean has(Hash hash) {
        return packStore.has(hash) || Files.exists(pathFor(hash));
    }

    public byte[] get(Hash hash) throws IOException {
        if (packStore.has(hash)) {
            return packStore.get(hash);
        }
        // 旧树回退: 不存在时 readAllBytes 抛 NoSuchFileException, 与原 get 缺失语义一致.
        return Files.readAllBytes(pathFor(hash));
    }

    /**
     * 写入. 命中 pack 或旧树即 dedup 跳过; 否则只写 pack (旧对象不复制进 pack).
     * 返回 true = 实际写入 (新 unique), false = 已存在跳过.
     */
    public boolean put(Hash hash, byte[] data) throws IOException {
        if (packStore.has(hash)) {
            return false;
        }
        if (Files.exists(pathFor(hash))) {
            return false; // 已在旧树, 留在原地, 不复制进 pack
        }
        return packStore.put(hash, data);
    }

    /** fsync 提交屏障. SnapshotCreator 写 manifest 前调一次, 把每对象 fsync 压成每快照 1 次. */
    public void flushAndSync() throws IOException {
        packStore.flushAndSync();
    }

    public void close() throws IOException {
        packStore.close();
    }

    /**
     * 删全部旧树 tmp 孤儿 (历史"一对象一文件"布局 kill -9 残留). 离线 CLI / fsck / 测试用:
     * 无并发 writer 时全删安全. 返回清掉的文件数. (pack 布局无此类 tmp, 本方法只清旧树。)
     */
    public int cleanupOrphanTmpFiles() throws IOException {
        return cleanupOrphanTmpFiles(Long.MAX_VALUE);
    }

    /**
     * 只删 lastModified 早于 {@code deleteOlderThanMillis} 的旧树 tmp 孤儿.
     *
     * <p>{@link java.util.stream.Stream} 是 {@link java.io.Closeable}, try-with-resources 关闭
     * 防文件句柄泄漏 (Windows 下泄漏句柄会阻止后续删除/重命名 chunksDir).
     *
     * @param deleteOlderThanMillis 仅删 lastModified 严格小于此值 (epoch ms) 的 .tmp.
     * @return 实际删除的文件数.
     */
    public int cleanupOrphanTmpFiles(long deleteOlderThanMillis) throws IOException {
        if (!Files.exists(chunksDir)) {
            return 0;
        }
        int[] count = {0};
        try (Stream<Path> walk = Files.walk(chunksDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> {
                try {
                    if (deleteOlderThanMillis != Long.MAX_VALUE
                            && Files.getLastModifiedTime(p).toMillis() >= deleteOlderThanMillis) {
                        return; // 本次运行的在途 .tmp, 留给 put 自己管理, 不碰
                    }
                    if (Files.deleteIfExists(p)) {
                        count[0]++;
                        BackupLog.warn(LOGGER_NAME, "[BetterBackup] removed orphan tmp file {}", p);
                    }
                } catch (IOException e) {
                    BackupLog.error(LOGGER_NAME, "[BetterBackup] failed to remove orphan tmp {}", p, e);
                }
            });
        }
        return count[0];
    }
}
