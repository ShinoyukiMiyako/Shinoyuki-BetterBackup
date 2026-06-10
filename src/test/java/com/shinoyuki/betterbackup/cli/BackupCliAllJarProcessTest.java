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
        // 3 个 store 对象 (chunk + playerdata + level.dat) 全部重 hash 通过
        assertTrue(r.combined().contains("scanned=3 ok=3 hashMismatch=0 corrupt=0"),
                "fsck must rehash all 3 objects clean (proves openhft xxh128 ran in subprocess); output=\n"
                        + r.combined());
        assertTrue(r.combined().contains("OK       " + fx.snapshotId),
                "fsck rebuild-index must list the snapshot OK; output=\n" + r.combined());
    }

    @Test
    void verify_fails_when_referenced_object_missing(@TempDir Path root) throws Exception {
        Fixture fx = buildFixture(root);

        // 预置健康 store 先确认基线为 0 (排除"环境本来就坏"的伪失败)
        assertEquals(0, runCli("verify", "--store", fx.storeRoot.toString(), "--id", fx.snapshotId).exitCode(),
                "precondition: intact store must verify clean");

        // 损坏: 删掉快照引用的 chunk 对象, store 不再能完整 restore 这个快照
        ChunkStore store = new ChunkStore(fx.storeRoot);
        Path victim = store.pathFor(fx.chunkHash);
        assertTrue(Files.deleteIfExists(victim), "test setup: referenced object must exist before deletion");

        ProcessResult r = runCli("verify", "--store", fx.storeRoot.toString(), "--id", fx.snapshotId);
        assertNotEquals(0, r.exitCode(),
                "verify must exit non-zero after a referenced object is removed; output=\n" + r.combined());
        assertTrue(r.combined().contains("INCOMPLETE " + fx.snapshotId)
                        && r.combined().contains("missing=1"),
                "verify must report the snapshot INCOMPLETE missing=1; output=\n" + r.combined());
    }

    @Test
    void fsck_fails_when_object_bytes_are_corrupted(@TempDir Path root) throws Exception {
        Fixture fx = buildFixture(root);
        assertEquals(0, runCli("fsck", "--store", fx.storeRoot.toString()).exitCode(),
                "precondition: intact store must fsck clean");

        // 损坏: 翻转受引用对象的若干字节, 文件名 (= 内容 hash) 与实际内容失配。verify 只查存在
        // 性查不出这种位翻转, fsck 逐对象重 hash 才能抓。这条断言依赖子进程里 openhft xxh128
        // 真的算出新 hash 并与文件名比对 -- openhft 缺失则子进程在重 hash 前就崩, 拿不到退出码 1。
        ChunkStore store = new ChunkStore(fx.storeRoot);
        Path victim = store.pathFor(fx.chunkHash);
        byte[] bytes = Files.readAllBytes(victim);
        for (int i = 1; i < bytes.length; i += 7) {
            bytes[i] ^= (byte) 0xFF;
        }
        Files.write(victim, bytes);

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
        // playerdata / level.dat 同样存成合法 zlib chunk-payload 形态: fsck 的 verifyStore 对 store
        // 里"每个"对象都做 zlib/gzip inflate 完整性校验 (compression-type byte 前缀), 随便塞裸字节
        // 会被判 CORRUPT。本测试只关心打包可达性 (openhft 能否在裸 JRE 加载), 故让全部对象走得通
        // 校验, 不混入 fsck 语义之外的噪声。
        byte[] playerBytes = ChunkPayloadFixtures.zlibPayload(
                "inventory: 64 cobblestone".getBytes(StandardCharsets.UTF_8));
        byte[] levelDat = ChunkPayloadFixtures.zlibPayload(randomBytes(1536, 19));

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

    private record Fixture(Path storeRoot, String snapshotId, Hash chunkHash) {
    }

    private record ProcessResult(int exitCode, String combined) {
    }
}
