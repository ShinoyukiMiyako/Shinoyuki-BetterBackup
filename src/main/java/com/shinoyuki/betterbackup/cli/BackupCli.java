package com.shinoyuki.betterbackup.cli;

import com.shinoyuki.betterbackup.io.WorldPaths;
import com.shinoyuki.betterbackup.snapshot.SnapshotManifest;
import com.shinoyuki.betterbackup.store.ChunkStore;
import com.shinoyuki.betterbackup.store.HashFunction;
import com.shinoyuki.betterbackup.store.Xxh128HashFunction;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 离线 CLI 入口 (PLAN Phase E commit 11/13)。{@code java -jar shinoyuki_betterbackup.jar <子命令>}
 * 在裸 JRE 上跑, 用于服务端起不来时直接读 store + manifest 做诊断与恢复, 杜绝"自定义 store
 * 格式 = 数据绑架"(市场调研最大教训)。
 *
 * <p><b>双模式</b>: 同一个 jar 既是 Forge mod (由 FML 按 mods.toml 加载, 不看 Main-Class)
 * 又是可执行 jar (manifest 的 Main-Class 指向本类)。两条路径互不干扰。
 *
 * <p><b>零 MC 依赖硬约束</b>: 本类及其调用到的全部类型 ({@link ChunkStore} / {@link WorldPaths} /
 * {@link SnapshotManifest} / {@link OfflineRestore} / {@link StoreFsck} / NBT 编解码) 均不
 * import net.minecraft / net.minecraftforge, 因此本入口在没有 Minecraft / Forge jar 的
 * classpath 下也能跑。该约束由测试 {@code BackupCliNoMinecraftDepTest} 用类加载断言钉死。
 *
 * <p>子命令:
 * <pre>
 * list    --store &lt;dir&gt;
 * info    --store &lt;dir&gt; --id &lt;snapshotId&gt;
 * verify  --store &lt;dir&gt; [--id &lt;snapshotId&gt;]
 * restore --store &lt;dir&gt; --id &lt;snapshotId&gt; --world &lt;worldRoot&gt;
 * fsck    --store &lt;dir&gt; [--rebuild-index]
 * </pre>
 */
public final class BackupCli {

    private static final String SNAPSHOTS_SUBDIR = "snapshots";
    private static final String MANIFEST_SUFFIX = ".manifest";

    private final PrintStream out;
    private final PrintStream err;

    public BackupCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        int code = new BackupCli(System.out, System.err).run(args);
        System.exit(code);
    }

    /** @return 进程退出码 (0=成功, 非 0=失败, 供 CI / 脚本判定)。 */
    public int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 2;
        }
        String command = args[0];
        Args parsed = Args.parse(args, 1);
        try {
            return switch (command) {
                case "list" -> cmdList(parsed);
                case "info" -> cmdInfo(parsed);
                case "verify" -> cmdVerify(parsed);
                case "restore" -> cmdRestore(parsed);
                case "fsck" -> cmdFsck(parsed);
                case "help", "--help", "-h" -> {
                    printUsage();
                    yield 0;
                }
                default -> {
                    err.println("unknown command: " + command);
                    printUsage();
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            // 参数错误 (缺 --store / --id 等): 报错 + usage, 退出码 2 区别于运行时失败。
            err.println("error: " + e.getMessage());
            printUsage();
            return 2;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private int cmdList(Args args) throws IOException {
        Path snapshotsDir = requireStore(args).resolve(SNAPSHOTS_SUBDIR);
        List<String> ids = listSnapshotIds(snapshotsDir);
        if (ids.isEmpty()) {
            out.println("no snapshots found in " + snapshotsDir);
            return 0;
        }
        out.println("snapshots (" + ids.size() + ", newest first):");
        for (String id : ids) {
            out.println("  " + id);
        }
        return 0;
    }

    private int cmdInfo(Args args) throws IOException {
        Path snapshotsDir = requireStore(args).resolve(SNAPSHOTS_SUBDIR);
        String id = args.require("id");
        Path manifestFile = snapshotsDir.resolve(id + MANIFEST_SUFFIX);
        if (!Files.exists(manifestFile)) {
            err.println("snapshot not found: " + id);
            return 1;
        }
        SnapshotManifest m = SnapshotManifest.readFrom(manifestFile);
        int totalChunks = m.chunks().values().stream().mapToInt(Map::size).sum();
        int totalEntity = m.entityChunks().values().stream().mapToInt(Map::size).sum();
        out.println("snapshot " + id);
        out.println("  schema version:    " + m.version());
        out.println("  createdAtMillis:   " + m.createdAtMillis());
        out.println("  worldGameTime:     " + m.worldGameTime());
        out.println("  baselineComplete:  " + m.baselineComplete());
        out.println("  chunks:            " + totalChunks + " across " + m.chunks().size() + " dim(s)");
        out.println("  entityChunks:      " + totalEntity + " across " + m.entityChunks().size() + " dim(s)");
        out.println("  savedData files:   " + m.savedData().size());
        out.println("  player-data files: " + m.files().hashes().size());
        out.println("  levelDat:          " + (m.levelDat() != null ? "present" : "absent"));
        for (var dimEntry : m.chunks().entrySet()) {
            out.println("    " + dimEntry.getKey() + ": " + dimEntry.getValue().size() + " chunks");
        }
        return 0;
    }

    /**
     * 校验快照的 store 完整性: 该快照引用的每个 hash 是否都还在 store。给 {@code --id <id>}
     * 只校验单个, 否则校验全部快照。回答的是"我手上的 store 能不能完整 restore 出这个快照",
     * 与 {@code fsck} (逐对象重 hash + zlib 深度校验) 是不同粒度。
     */
    private int cmdVerify(Args args) throws IOException {
        Path storeRoot = requireStore(args);
        Path snapshotsDir = storeRoot.resolve(SNAPSHOTS_SUBDIR);
        ChunkStore store = openStore(storeRoot);
        List<String> ids = args.optional("id") != null
                ? List.of(args.require("id"))
                : listSnapshotIds(snapshotsDir);
        if (ids.isEmpty()) {
            out.println("no snapshots to verify in " + snapshotsDir);
            return 0;
        }
        int failures = 0;
        for (String id : ids) {
            Path manifestFile = snapshotsDir.resolve(id + MANIFEST_SUFFIX);
            if (!Files.exists(manifestFile)) {
                out.println("MISSING  " + id + " (manifest not found)");
                failures++;
                continue;
            }
            SnapshotManifest m;
            try {
                m = SnapshotManifest.readFrom(manifestFile);
            } catch (IOException e) {
                out.println("CORRUPT  " + id + " (" + e.getMessage() + ")");
                failures++;
                continue;
            }
            long missing = countMissingReferences(store, m);
            if (missing == 0) {
                out.println("OK       " + id);
            } else {
                out.println("INCOMPLETE " + id + " missing=" + missing + " object(s)");
                failures++;
            }
        }
        return failures == 0 ? 0 : 1;
    }

    /** 该快照引用但 store 里缺失的 hash 数量。 */
    private static long countMissingReferences(ChunkStore store, SnapshotManifest m) {
        java.util.Set<com.shinoyuki.betterbackup.store.Hash> referenced = new java.util.HashSet<>();
        m.chunks().values().forEach(map -> referenced.addAll(map.values()));
        m.entityChunks().values().forEach(map -> referenced.addAll(map.values()));
        referenced.addAll(m.savedData().values());
        referenced.addAll(m.files().hashes().values());
        if (m.levelDat() != null) {
            referenced.add(m.levelDat());
        }
        return referenced.stream().filter(h -> !store.has(h)).count();
    }

    private int cmdRestore(Args args) throws IOException {
        Path storeRoot = requireStore(args);
        String id = args.require("id");
        Path worldRoot = Paths.get(args.require("world"));
        ChunkStore store = openStore(storeRoot);
        WorldPaths paths = new WorldPaths(worldRoot);
        OfflineRestore restore = new OfflineRestore(store, paths, storeRoot.resolve(SNAPSHOTS_SUBDIR), out);
        restore.restore(id);
        return 0;
    }

    /**
     * 深度自检: 逐 store 对象重 hash + zlib 完整性校验。{@code --rebuild-index} 时额外从
     * store + manifests 重建快照索引落 index.txt。退出码非 0 当存在任何损坏 / 不完整 / 缺失。
     */
    private int cmdFsck(Args args) throws IOException {
        Path storeRoot = requireStore(args);
        Path snapshotsDir = storeRoot.resolve(SNAPSHOTS_SUBDIR);
        ChunkStore store = openStore(storeRoot);
        StoreFsck fsck = new StoreFsck(store, snapshotsDir, hashFunction());

        StoreFsck.VerifyResult verify = fsck.verifyStore();
        out.println("fsck store: scanned=" + verify.scanned() + " ok=" + verify.ok()
                + " chunkObjects=" + verify.chunkObjects()
                + " fileObjects=" + verify.fileObjects()
                + " orphans=" + verify.orphans().size()
                + " hashMismatch=" + verify.hashMismatch().size()
                + " corrupt=" + verify.corrupt().size());
        for (String s : verify.hashMismatch()) {
            out.println("  HASH-MISMATCH " + s);
        }
        for (String s : verify.corrupt()) {
            out.println("  CORRUPT " + s);
        }
        // orphan 单独归类: 是 GC 未回收的残留对象 (manifest 不再引用), 不是损坏, 不拉非 0 退出码。
        for (String s : verify.orphans()) {
            out.println("  ORPHAN " + s);
        }

        int failures = verify.clean() ? 0 : 1;
        if (args.flag("rebuild-index")) {
            StoreFsck.RebuildResult rebuild = fsck.rebuildIndex();
            out.println("rebuilt index: " + rebuild.entries().size() + " snapshot(s) -> "
                    + snapshotsDir.resolve(StoreFsck.INDEX_FILE_NAME));
            for (StoreFsck.SnapshotEntry e : rebuild.entries()) {
                if (e.corrupt()) {
                    out.println("  CORRUPT  " + e.id() + " (" + e.corruptReason() + ")");
                    failures = 1;
                } else if (e.missingObjects() > 0) {
                    out.println("  INCOMPLETE " + e.id() + " missing=" + e.missingObjects()
                            + "/" + e.referencedObjects());
                    failures = 1;
                } else {
                    out.println("  OK       " + e.id() + " objects=" + e.referencedObjects()
                            + " chunks=" + e.chunks() + " baselineComplete=" + e.baselineComplete());
                }
            }
        }
        return failures;
    }

    private List<String> listSnapshotIds(Path snapshotsDir) throws IOException {
        if (!Files.isDirectory(snapshotsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(snapshotsDir)) {
            return new ArrayList<>(stream
                    .filter(p -> p.getFileName().toString().endsWith(MANIFEST_SUFFIX))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - MANIFEST_SUFFIX.length());
                    })
                    .sorted(Comparator.reverseOrder())
                    .toList());
        }
    }

    private static Path requireStore(Args args) {
        return Paths.get(args.require("store"));
    }

    private static ChunkStore openStore(Path storeRoot) throws IOException {
        ChunkStore store = new ChunkStore(storeRoot);
        store.initialize();
        return store;
    }

    private static HashFunction hashFunction() {
        // v0.1 store 唯一算法是 xxh128 (gradle.properties / ConfigSpec 默认)。多算法切换是
        // v0.2 候选, 届时本入口从 store 元数据读算法再实例化, 当前直用 xxh128。
        return new Xxh128HashFunction();
    }

    private void printUsage() {
        err.println("Shinoyuki BetterBackup offline CLI");
        err.println("usage: java -jar shinoyuki_betterbackup.jar <command> [options]");
        err.println("commands:");
        err.println("  list    --store <dir>");
        err.println("  info    --store <dir> --id <snapshotId>");
        err.println("  verify  --store <dir> [--id <snapshotId>]");
        err.println("  restore --store <dir> --id <snapshotId> --world <worldRoot>");
        err.println("  fsck    --store <dir> [--rebuild-index]");
    }

    /**
     * 极简长选项解析: {@code --key value} 进 map, {@code --flag} (后面不是 value 或到末尾)
     * 进 flag set。不引第三方解析库 (CLI 必须零额外依赖在裸 JRE 跑)。
     */
    static final class Args {

        private final Map<String, String> options;
        private final java.util.Set<String> flags;

        private Args(Map<String, String> options, java.util.Set<String> flags) {
            this.options = options;
            this.flags = flags;
        }

        static Args parse(String[] argv, int from) {
            Map<String, String> options = new java.util.HashMap<>();
            java.util.Set<String> flags = new java.util.HashSet<>();
            int i = from;
            while (i < argv.length) {
                String token = argv[i];
                if (!token.startsWith("--")) {
                    throw new IllegalArgumentException("expected --option, got: " + token);
                }
                String key = token.substring(2);
                if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    options.put(key, argv[i + 1]);
                    i += 2;
                } else {
                    flags.add(key);
                    i += 1;
                }
            }
            return new Args(options, flags);
        }

        String require(String key) {
            String v = options.get(key);
            if (v == null) {
                throw new IllegalArgumentException("missing required option --" + key);
            }
            return v;
        }

        String optional(String key) {
            return options.get(key);
        }

        boolean flag(String key) {
            return flags.contains(key);
        }
    }
}
