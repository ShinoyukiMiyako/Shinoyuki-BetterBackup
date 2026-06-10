package com.shinoyuki.betterbackup.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Content-addressed dedup store. 文件名 = hash hex 全长, 二级分桶 ({@code chunks/<2>/<6>/<full>})
 * 防单目录文件过多.
 *
 * <p>每条 store entry 是一个 .mca chunk slot 的 raw 压缩字节 (vanilla zlib).
 * SavedData / level.dat 等其他类型 payload 也复用此 store, 跟 chunk 共享 dedup pool.
 *
 * <p><b>atomic put</b>: 先写 {@code <hash>.tmp} → {@code FileChannel.force(true)}
 * (fsync 保证字节落盘) → atomic rename 到 {@code <hash>}. kill -9 发生在 fsync
 * 前: 启动时孤立 .tmp 由 verify 路径清理 (后续 commit). kill -9 在 rename 前: 数据
 * 已 fsync, 但 manifest 尚未引用此 hash, 增量 GC 会清掉.
 *
 * <p><b>线程安全</b>: 多线程 put 同一 hash 时, atomic_move 在已存在 target 上抛
 * {@code FileAlreadyExistsException}, catch 后删 .tmp 即可 (内容相同, 哪个先到都行).
 * has / get 是只读, 跟 put 并发安全 (目标文件 atomic 出现).
 */
public final class ChunkStore {

    // 直接 SLF4J 而非 BetterBackupMod.LOGGER: store 是 content-addressed 持久层, 被离线
    // CLI (零 net.minecraft 依赖) 复用, 不能经 BetterBackupMod 把 Minecraft 运行时拽进来。
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkStore.class);

    private final Path storeRoot;
    private final Path chunksDir;

    public ChunkStore(Path storeRoot) {
        this.storeRoot = storeRoot;
        this.chunksDir = storeRoot.resolve("chunks");
    }

    public void initialize() throws IOException {
        Files.createDirectories(chunksDir);
        LOGGER.info("[BetterBackup] store initialized at {}", storeRoot.toAbsolutePath());
    }

    public Path storeRoot() {
        return storeRoot;
    }

    public Path chunksDir() {
        return chunksDir;
    }

    /** 二级分桶路径: chunks/<前2hex>/<前6hex>/<32hex全名>. */
    public Path pathFor(Hash hash) {
        String hex = hash.toHex();
        if (hex.length() < 6) {
            throw new IllegalArgumentException("hash hex too short for two-level bucket: " + hex);
        }
        return chunksDir.resolve(hex.substring(0, 2)).resolve(hex.substring(0, 6)).resolve(hex);
    }

    public boolean has(Hash hash) {
        return Files.exists(pathFor(hash));
    }

    public byte[] get(Hash hash) throws IOException {
        return Files.readAllBytes(pathFor(hash));
    }

    /**
     * 写入. 若该 hash 已存在 → 跳过 (idempotent, dedup 命中).
     * 返回 true = 实际写入 (新 unique), false = 已存在跳过.
     */
    public boolean put(Hash hash, byte[] data) throws IOException {
        Path target = pathFor(hash);
        if (Files.exists(target)) {
            return false;
        }
        Files.createDirectories(target.getParent());
        // tmp 文件名带 UUID 让多线程并发 put 同一 hash 时各自用独立 tmp, 避免 race:
        // 之前共享 <hash>.tmp 时 A 写 + rename 完后 B 还在用同一路径, B 的 fsync /
        // move 会找不到文件 (NoSuchFileException). UUID 隔离后 A/B 互不干扰,
        // 第一个成功 rename 进 target, 第二个被 FileAlreadyExistsException 接住
        // 删自己的 tmp.
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.write(tmp, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        // fsync: 确保字节落盘后再 rename, kill -9 重启时不会出现 rename 完但内容半空的文件.
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ)) {
            ch.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileAlreadyExistsException raceWin) {
            // 别的线程同时 put 同 hash, 它先 rename. 删自己的 tmp 即可.
            Files.deleteIfExists(tmp);
            return false;
        } catch (NoSuchFileException e) {
            // 极端 race: 没用 UUID 隔离时会撞 (此版本已修). 防御性兜底, 不应发生.
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * 删 tmp 孤儿 (kill -9 前 fsync / rename 留下的 .tmp). 启动时 verify 调用.
     * 返回清掉的文件数.
     */
    public int cleanupOrphanTmpFiles() throws IOException {
        if (!Files.exists(chunksDir)) {
            return 0;
        }
        int[] count = {0};
        Files.walk(chunksDir).filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> {
            try {
                Files.deleteIfExists(p);
                count[0]++;
                LOGGER.warn("[BetterBackup] removed orphan tmp file {}", p);
            } catch (IOException e) {
                LOGGER.error("[BetterBackup] failed to remove orphan tmp {}", p, e);
            }
        });
        return count[0];
    }
}
