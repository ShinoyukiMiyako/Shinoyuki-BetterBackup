package com.shinoyuki.betterbackup.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PLAN Phase E commit 14 硬约束验证: CLI 类路径无 MC 依赖。
 *
 * <p>用一个会拒绝加载任何 {@code net.minecraft*} / {@code net.minecraftforge*} 类的自定义
 * ClassLoader 作为 CLI 全图的 defining loader, 然后实际跑 CLI 子命令 (list / verify /
 * fsck --rebuild-index / restore)。任何 CLI 可达类在 link/执行期引用到 MC 类型, JVM 都会向
 * 本 loader 求解该类型, 本 loader 抛 ClassNotFoundException, 测试即失败并点名违规类。
 *
 * <p>这比 jdeps 静态扫描更强: 它验证的是运行时真正被加载、链接、执行的那条路径零 MC, 而非
 * 仅看 import 列表 (import 可能被死代码消除或仅用于 javadoc)。
 *
 * <p>判定标准: 把 {@link OfflineRestore} 改回 import net.minecraft.world.level.ChunkPos
 * 并用它, 本测试的 restore 分支必挂 (loader 拒绝加载 ChunkPos)。
 *
 * <p><b>本测试防不了打包缺口 (历史教训)</b>: 它跑在 Gradle 测试 classpath 内, slf4j 与
 * openhft 都在该 classpath 上, 且自定义 loader 把这两类基础设施委托给父 loader (见
 * {@code McForbiddenClassLoader} 委托分支)。因此当 CLI 可达类在类初始化期硬依赖 slf4j、或
 * openhft 仅以 jarJar 嵌套形式存在 (对 {@code java -jar} 的应用类加载器不可见) 时, 这些缺口
 * 在本测试里全是假阴性 -- 生产服一 {@code java -jar} 即 NoClassDefFoundError。真正复现裸 JRE
 * 运行环境、覆盖打包缺口的是进程级的 {@code BackupCliAllJarProcessTest}。本测试的职责被刻意
 * 收窄为单一不变量: CLI 可达图零 {@code net.minecraft*} / {@code net.minecraftforge*} 依赖。
 */
class BackupCliNoMinecraftDepTest {

    @Test
    void cli_command_graph_loads_without_minecraft_classes(@TempDir Path root) throws Exception {
        Path storeRoot = root.resolve("store");
        Path snapshotsDir = storeRoot.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        try (McForbiddenClassLoader loader = new McForbiddenClassLoader(
                BackupCli.class.getClassLoader())) {

            // 通过禁 MC 的 loader 加载 CLI 入口, 其整个引用图都会用本 loader 求解
            Class<?> cliClass = Class.forName(
                    "com.shinoyuki.betterbackup.cli.BackupCli", true, loader);
            assertEquals(loader, cliClass.getClassLoader(),
                    "BackupCli must be defined by the MC-forbidden loader");

            Object cli = cliClass.getConstructor(PrintStream.class, PrintStream.class)
                    .newInstance(nullStream(), nullStream());
            Method run = cliClass.getMethod("run", String[].class);

            // list: 走 ChunkStore + 目录扫描 (空 store)
            invokeRun(run, cli, new String[]{"list", "--store", storeRoot.toString()});

            // verify / fsck: 走 StoreFsck + ChunkPayloadCodec
            invokeRun(run, cli, new String[]{"verify", "--store", storeRoot.toString()});
            invokeRun(run, cli, new String[]{"fsck", "--store", storeRoot.toString(), "--rebuild-index"});

            // restore: 走 OfflineRestore + WorldPaths + RegionFileSlotWriter + SnapshotManifest + NBT 编解码.
            // 用一个 baselineComplete=true 但引用全缺的 manifest, 触发 OfflineRestore 全部链路
            // (读 manifest -> verifyStoreCompleteness)。这里期望它因 store incomplete 失败 (退出码 1),
            // 关键是过程中不能加载任何 MC 类。
            writeMinimalManifestViaCodec(loader, snapshotsDir);
            Path targetWorld = root.resolve("target");
            Files.createDirectories(targetWorld);
            invokeRun(run, cli, new String[]{"restore", "--store", storeRoot.toString(),
                    "--id", "snap-x", "--world", targetWorld.toString()});
        }
    }

    /**
     * 用被测 loader 加载 SnapshotManifest 写一个最小 manifest (baselineComplete=true, 引用一个
     * 不存在的 hash)。这条写盘路径本身就经过 NBT 编解码, 顺带验证编解码无 MC 依赖。
     */
    private static void writeMinimalManifestViaCodec(ClassLoader loader, Path snapshotsDir) throws Exception {
        Class<?> manifestClass = Class.forName(
                "com.shinoyuki.betterbackup.snapshot.SnapshotManifest", true, loader);
        Class<?> fileManifestClass = Class.forName(
                "com.shinoyuki.betterbackup.snapshot.FileManifest", true, loader);
        Class<?> hashClass = Class.forName(
                "com.shinoyuki.betterbackup.store.Hash", true, loader);

        Object emptyFiles = fileManifestClass.getMethod("empty").invoke(null);
        byte[] hashBytes = new byte[16];
        hashBytes[0] = 0x42;
        Object levelHash = hashClass.getConstructor(byte[].class).newInstance((Object) hashBytes);

        int schema = manifestClass.getField("SCHEMA_VERSION").getInt(null);
        Object manifest = manifestClass.getConstructor(
                int.class, String.class, long.class, long.class,
                java.util.Map.class, java.util.Map.class, java.util.Map.class,
                hashClass, long.class, long.class, boolean.class, fileManifestClass)
                .newInstance(schema, "snap-x", 1L, 0L,
                        new java.util.HashMap<>(), new java.util.HashMap<>(), new java.util.HashMap<>(),
                        levelHash, 0L, 0L, true, emptyFiles);

        Path target = snapshotsDir.resolve("snap-x.manifest");
        manifestClass.getMethod("writeTo", Path.class).invoke(manifest, target);
        assertTrue(Files.isRegularFile(target), "manifest must be written via the MC-free codec");
    }

    private static void invokeRun(Method run, Object cli, String[] argv) throws Exception {
        try {
            run.invoke(cli, (Object) argv);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (isMinecraftLinkage(cause)) {
                fail("CLI command " + argv[0] + " pulled in a Minecraft/Forge class: " + cause);
            }
            throw e;
        }
    }

    private static boolean isMinecraftLinkage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if ((c instanceof NoClassDefFoundError || c instanceof ClassNotFoundException
                    || c instanceof LinkageError) && msg != null
                    && (msg.contains("net/minecraft") || msg.contains("net.minecraft")
                        || msg.contains("net/minecraftforge") || msg.contains("net.minecraftforge"))) {
                return true;
            }
        }
        return false;
    }

    private static PrintStream nullStream() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    /**
     * 拒绝加载 net.minecraft* / net.minecraftforge* 的 ClassLoader。自己 define
     * com.shinoyuki.betterbackup.* 的类 (从父 loader 取 bytecode), 其余委托父 loader。
     * 这样被测类的符号引用都经本 loader 求解, 一旦引用 MC 类即抛。
     */
    private static final class McForbiddenClassLoader extends ClassLoader implements AutoCloseable {

        McForbiddenClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                    || name.startsWith("com.mojang.")) {
                throw new ClassNotFoundException("Minecraft/Forge class forbidden on CLI path: " + name);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> already = findLoadedClass(name);
                if (already != null) {
                    return already;
                }
                // 自己 define 项目类, 让它们的引用图都经本 loader
                if (name.startsWith("com.shinoyuki.betterbackup.")) {
                    Class<?> c = defineFromParentResource(name);
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
                // java.* / slf4j / openhft 等基础设施委托父 loader
                return super.loadClass(name, resolve);
            }
        }

        private Class<?> defineFromParentResource(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        @Override
        public void close() {
            // 无原生资源, 仅满足 try-with-resources
        }
    }
}
