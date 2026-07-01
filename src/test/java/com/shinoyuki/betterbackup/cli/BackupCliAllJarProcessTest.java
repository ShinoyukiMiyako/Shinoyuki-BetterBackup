package com.shinoyuki.betterbackup.cli;

import com.shinoyuki.betterbackup.io.ChunkPayloadFixtures;
import com.shinoyuki.betterbackup.io.RegionFileSlotReader;
import com.shinoyuki.betterbackup.io.RegionFileSlotWriter;
import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.FileManifest;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.Hash;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发版阻断 bug 的回归测试: 离线 CLI 在裸 JRE (classpath 只有 -all jar 本身) 上必须能跑。
 *
 * <p><b>为什么必须是进程级</b>: 历史上 {@link BackupCliNoMinecraftDepTest} 只用自定义
 * ClassLoader 在 Gradle 测试 classpath 内断言"不加载 net.minecraft", 但 slf4j 与 openhft
 * 都在测试 classpath 上, 给了假阴性 -- 生产服 {@code java -jar} 时 ChunkStore 类初始化期
 * 拽 slf4j、Xxh128HashFunction 拽 openhft 双双 NoClassDefFoundError, 测试却全绿。本测试改用
 * {@link ProcessBuilder} 起干净子进程, classpath 仅 {@code -Dbetterbackup.allJar} 指向的
 * 那一个 jar, 真实复现用户的运行环境。
 *
 * <p><b>双覆盖面</b>: verify 子命令的可达图同时经过 {@link ChunkStore} (slf4j 缺口) 与
 * {@link Xxh128HashFunction} -> openhft (打包缺口), 一条命令钉死两个根因。
 *
 * <p><b>判定标准 (删核心逻辑测试必挂)</b>:
 * <ul>
 *   <li>把 {@code BackupLog} 门面退回 {@code LoggerFactory.getLogger}, verify 子进程
 *       退出码即非 0 (ChunkStore.&lt;clinit&gt; NoClassDefFoundError), {@code verify_*} 用例挂</li>
 *   <li>把 build.gradle 的 openhft 平铺退回 jarJar 嵌套, verify 子进程同样炸
 *       (Xxh128HashFunction 找不到 net/openhft/hashing/LongTupleHashFunction)</li>
 *   <li>删掉受损对象不删: {@code verify_fails_when_referenced_object_missing} 拿到退出码 0, 挂</li>
 * </ul>
 */
class BackupCliAllJarProcessTest {

    private static final String DIM = "minecraft:overworld";

    private static Path allJar;

    @BeforeAll
    static void resolveAllJar() {
        String configured = System.getProperty("betterbackup.allJar");
        assertTrue(configured != null && !configured.isBlank(),
                "system property betterbackup.allJar must be set by the Gradle test task");
        allJar = Path.of(configured);
        assertTrue(Files.isRegularFile(allJar),
                "the -all jar must be built before this test runs: " + allJar);
    }

    @Test
    void help_exits_zero_on_clean_jre() throws Exception {
        ProcessResult r = runCli("help");
        assertEquals(0, r.exitCode(),
                "help must exit 0 on a clean JRE (jar-only classpath); output=\n" + r.combined());
        assertTrue(r.combined().contains("offline CLI"),
                "help must print usage banner; output=\n" + r.combined());
    }

    @Test
    void verify_exits_zero_for_complete_store_on_clean_jre(@TempDir Path root) throws Exception {
        Fixture fx = buildFixture(root);

        // verify 可达图: ChunkStore (slf4j 缺口). 不实例化 hashFunction, 故只覆盖 slf4j 根因。
        ProcessResult r = runCli("verify", "--store", fx.storeRoot.toString(), "--id", fx.snapshotId);
        assertEquals(0, r.exitCode(),
                "verify of a complete store must exit 0 on jar-only classpath; output=\n" + r.combined());
        assertTrue(r.combined().contains("OK       " + fx.snapshotId),
                "verify must report the snapshot OK; output=\n" + r.combined());
    }

    @Test
    void fsck_exits_zero_for_complete_store_on_clean_jre(@TempDir Path root) throws Exception {
        Fixture fx = buildFixture(root);

        // fsck 可达图额外经 Xxh128HashFunction -> net.openhft.hashing.* (打包缺口): 逐对象重 hash
        // 比对文件名。这正是 jarJar 嵌套时 java -jar 调它就 NoClassDefFoundError 的命令, 必须由
        // 干净子进程覆盖。退回 openhft 平铺为 jarJar 嵌套, 本用例即挂 (退出码非 0)。
        ProcessResult r = runCli("fsck", "--store", fx.storeRoot.toString(), "--rebuild-index");
        assertEquals(0, r.exitCode(),
                "fsck of a complete store must exit 0 on jar-only classpath; output=\n" + r.combined());
        // 3 个 store 对象全部重 hash 通过, 且按类正确划分: 1 个 chunk payload (chunks 段) +
        // 2 个 opaque 文件 (playerdata poi 风格 .mca 首字节 0x00, level.dat gzip 首字节 0x1f)。
        // playerdata/level.dat 是真实 opaque 形态 (非伪装成 zlib chunk), 子进程仍 0 误报, 正是本次
        // 发版阻断 bug 的进程级回归: 退回全量压缩校验, 0x00 / 0x1f 首字节会被误报 CORRUPT, 退出码非 0。
        assertTrue(r.combined().contains(
                        "scanned=3 ok=3 chunkObjects=1 fileObjects=2 orphans=0 hashMismatch=0 corrupt=0"),
                "fsck must classify 1 chunk + 2 opaque files clean (proves classification + openhft in subprocess);"
                        + " output=\n" + r.combined());
        assertTrue(r.combined().contains("OK       " + fx.snapshotId),
                "fsck rebuild-index must list the snapshot OK; output=\n" + r.combined());
    }

    @Test
    void verify_fails_when_referenced_object_missing(@TempDir Path root) throws Exception {
        Fixture fx = buildFixture(root);

        // 预置健康 store 先确认基线为 0 (排除"环境本来就坏"的伪失败)
        assertEquals(0, runCli("verify", "--store", fx.storeRoot.toString(), "--id", fx.snapshotId).exitCode(),
                "precondition: intact store must verify clean");

        // 损坏: 删掉 pack (快照引用的对象都在里面), store 不再能完整 restore 这个快照
        Path pack = fx.storeRoot.resolve("packs").resolve("0000000000.pack");
        assertTrue(Files.deleteIfExists(pack), "test setup: pack file must exist before deletion");

        ProcessResult r = runCli("verify", "--store", fx.storeRoot.toString(), "--id", fx.snapshotId);
        assertNotEquals(0, r.exitCode(),
                "verify must exit non-zero after referenced objects go missing; output=\n" + r.combined());
        assertTrue(r.combined().contains("INCOMPLETE " + fx.snapshotId),
                "verify must report the snapshot INCOMPLETE; output=\n" + r.combined());
    }

    @Test
    void fsck_fails_when_object_bytes_are_corrupted(@TempDir Path root) throws Exception {
        Fixture fx = buildFixture(root);
        assertEquals(0, runCli("fsck", "--store", fx.storeRoot.toString()).exitCode(),
                "precondition: intact store must fsck clean");

        // 损坏: 翻转受引用对象的若干字节, 文件名 (= 内容 hash) 与实际内容失配。verify 只查存在
        // 性查不出这种位翻转, fsck 逐对象重 hash 才能抓。这条断言依赖子进程里 openhft xxh128
        // 真的算出新 hash 并与文件名比对 -- openhft 缺失则子进程在重 hash 前就崩, 拿不到退出码 1。
        // 翻转 pack 内首条记录 (chunk 对象, buildFixture 第一个 put) 数据区的字节: 内联 hash
        // 与实际内容失配。跳过 [16B hash][4B len] 头, 只动数据区起始几字节 (不碰后续记录的帧)。
        Path pack = fx.storeRoot.resolve("packs").resolve("0000000000.pack");
        byte[] bytes = Files.readAllBytes(pack);
        for (int i = 25; i < 36; i++) {
            bytes[i] ^= (byte) 0xFF;
        }
        Files.write(pack, bytes);

        ProcessResult r = runCli("fsck", "--store", fx.storeRoot.toString());
        assertNotEquals(0, r.exitCode(),
                "fsck must exit non-zero after an object's bytes are corrupted; output=\n" + r.combined());
        assertTrue(r.combined().contains("HASH-MISMATCH"),
                "fsck must report HASH-MISMATCH for the tampered object; output=\n" + r.combined());
    }

    /**
     * 造一个最小但真实的 store + manifest: 一个 zlib chunk slot + 一个 playerdata 文件 + level.dat,
     * baselineComplete=true。复用 {@link ChunkPayloadFixtures} / {@link RegionFileSlotReader} 的既有
     * 测试工具构造真实磁盘字节, 走 {@link ChunkStore#put} 与 {@link SnapshotManifest#writeTo} 落盘
     * (与 mod 侧产出的 store 同格式), 然后由子进程读。
     */
    private static Fixture buildFixture(Path root) throws IOException {
        Path sourceWorld = root.resolve("source-world");
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(sourceWorld);
        Files.createDirectories(snapshotsDir);

        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        Xxh128HashFunction hashFn = new Xxh128HashFunction();
        WorldPaths sourcePaths = new WorldPaths(sourceWorld);

        int chunkX = 3;
        int chunkZ = 4;
        byte[] chunkObject = ChunkPayloadFixtures.zlibPayload(randomBytes(4096, 7));
        Path sourceRegionDir = sourcePaths.regionDir(DIM);
        Files.createDirectories(sourceRegionDir);
        try (RegionFileSlotWriter w = RegionFileSlotWriter.open(sourceRegionDir.resolve("r.0.0.mca"))) {
            w.writeChunk(chunkX & 31, chunkZ & 31, chunkObject);
        }
        byte[] snapshotChunkBytes = RegionFileSlotReader.readChunk(sourceRegionDir, chunkX, chunkZ);
        // playerdata / level.dat 用真实 opaque 整文件形态 (非伪装 zlib chunk): fsck 按 manifest
        // 分类后, files / levelDat 段引用的对象只重 hash 不跑压缩校验, 故首字节可以是任意值。
        // playerBytes 走 poi 风格 .mca (首字节 0x00, 生产 1481/1620 误报形态), levelDat 走真实
        // gzip NBT (首字节 0x1f)。这两个首字节正是旧实现误判 "invalid compression type" 的来源。
        byte[] playerBytes = poiWholeFileBytes(2048, 11);
        byte[] levelDat = gzipBytes(randomBytes(1536, 19));

        Hash chunkHash = hashFn.hash(snapshotChunkBytes);
        store.put(chunkHash, snapshotChunkBytes);
        Hash playerHash = hashFn.hash(playerBytes);
        store.put(playerHash, playerBytes);
        Hash levelHash = hashFn.hash(levelDat);
        store.put(levelHash, levelDat);

        Map<Long, Hash> chunkSlots = new HashMap<>();
        chunkSlots.put(ChunkPosCodec.asLong(chunkX, chunkZ), chunkHash);
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        chunks.put(DIM, chunkSlots);

        Map<String, Hash> fileHashes = new HashMap<>();
        fileHashes.put("playerdata/p1.dat", playerHash);
        FileManifest files = new FileManifest(fileHashes, new java.util.HashSet<>());

        String snapshotId = "snap-proc";
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION, snapshotId, System.currentTimeMillis(), 0L,
                chunks, new HashMap<>(), new HashMap<>(), levelHash, 0L, 0L, true, files);
        manifest.writeTo(snapshotsDir.resolve(snapshotId + ".manifest"));

        return new Fixture(storeRoot, snapshotId, chunkHash);
    }

    private static ProcessResult runCli(String... cliArgs) throws Exception {
        // 裸 JRE 复现: classpath 只放 -all jar 本身。用 java.home 定位 java 可执行文件,
        // Windows 上是 java.exe (CLAUDE 要求), 其余平台是 java。
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path javaBin = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        assertTrue(Files.isRegularFile(javaBin), "java executable not found at " + javaBin);

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaBin.toString());
        cmd.add("-jar");
        cmd.add(allJar.toAbsolutePath().toString());
        cmd.addAll(List.of(cliArgs));

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("CLI subprocess timed out; output so far=\n" + output);
        }
        return new ProcessResult(p.exitValue(), output);
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    /**
     * poi 风格 .mca 整文件: 前 4KiB 是 vanilla region 位置表, poi 稀疏时全 0x00, 整文件首字节
     * 即 0x00。这是 fsck 全量压缩校验时被误判 "invalid compression type 0" 的形态。
     */
    private static byte[] poiWholeFileBytes(int tailRandom, long seed) {
        byte[] out = new byte[4096 + tailRandom];
        System.arraycopy(randomBytes(tailRandom, seed), 0, out, 4096, tailRandom);
        return out; // out[0..4095] 默认 0x00, 即 out[0] == 0x00
    }

    /** 真实 gzip 字节 (魔数 0x1f 0x8b): 复现 level.dat / SavedData 的整文件 gzip NBT 形态。 */
    private static byte[] gzipBytes(byte[] plaintext) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(plaintext);
        }
        return bos.toByteArray();
    }

    private record Fixture(Path storeRoot, String snapshotId, Hash chunkHash) {
    }

    private record ProcessResult(int exitCode, String combined) {
    }
}
