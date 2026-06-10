package com.shinoyuki.betterbackup.log;

import java.io.PrintStream;
import java.time.Instant;

/**
 * 极小内部日志门面。存在的唯一理由: 离线 CLI 在裸 JRE 上跑, classpath 没有 slf4j;
 * 而 store 等 CLI 可达类原先在类初始化期 {@code LoggerFactory.getLogger(...)} 硬拽 slf4j,
 * 一加载就 {@link NoClassDefFoundError}。本门面的类初始化绝不触碰 slf4j (默认 sink 直写
 * System.err), 把 slf4j 的接入收敛成 mod 启动时可选安装的一个 {@link Sink} 桥接器。
 *
 * <p><b>名字保持</b>: 每条日志带 logger name ({@code error/warn/info(String name, ...)}),
 * mod 侧桥接 sink 据此 {@code LoggerFactory.getLogger(name)} 还原成原 logger, 服主的日志
 * 观感 (logger 名 / 格式 / 级别过滤) 与迁移前一致。
 *
 * <p><b>占位符</b>: 跟 slf4j 一致用 {@code {}} 顺序替换, 末尾多出的一个 {@link Throwable}
 * 参数被当作堆栈打印 (slf4j 的同款约定), 例如
 * {@code error(name, "failed at {}", path, ex)}。
 */
public final class BackupLog {

    /** 日志接收端。mod 侧装 slf4j 桥接, 裸 JRE / 测试默认走 {@link StderrSink}。 */
    public interface Sink {
        void log(Level level, String loggerName, String message, Throwable error);
    }

    public enum Level {
        INFO, WARN, ERROR
    }

    // volatile: 安装发生在 mod 构造 (主线程), 读发生在 worker / 扫描 / CLI 各线程。
    private static volatile Sink sink = new StderrSink();

    private BackupLog() {
    }

    /**
     * 替换全局 sink。mod 启动最早期 (BetterBackupMod 构造) 调一次装 slf4j 桥接。
     * 幂等且无锁: 只是换一个引用。
     */
    public static void install(Sink newSink) {
        if (newSink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        sink = newSink;
    }

    public static void info(String loggerName, String pattern, Object... args) {
        emit(Level.INFO, loggerName, pattern, args);
    }

    public static void warn(String loggerName, String pattern, Object... args) {
        emit(Level.WARN, loggerName, pattern, args);
    }

    public static void error(String loggerName, String pattern, Object... args) {
        emit(Level.ERROR, loggerName, pattern, args);
    }

    private static void emit(Level level, String loggerName, String pattern, Object[] args) {
        Throwable error = extractTrailingThrowable(pattern, args);
        int substitutable = error != null ? args.length - 1 : args.length;
        String message = format(pattern, args, substitutable);
        sink.log(level, loggerName, message, error);
    }

    /**
     * slf4j 约定: 若实参个数比 {@code {}} 占位符多 1 且末位是 Throwable, 末位不参与占位
     * 替换, 而是作为异常堆栈。返回该 Throwable, 否则 null。
     */
    private static Throwable extractTrailingThrowable(String pattern, Object[] args) {
        if (args.length == 0 || !(args[args.length - 1] instanceof Throwable)) {
            return null;
        }
        int placeholders = countPlaceholders(pattern);
        // 占位符 < 实参数: 末位 Throwable 是"多出来的那个", 当异常处理。
        // 占位符 >= 实参数: 末位 Throwable 本就要填进 {}, 不夺走 (与 slf4j 一致)。
        if (placeholders < args.length) {
            return (Throwable) args[args.length - 1];
        }
        return null;
    }

    private static int countPlaceholders(String pattern) {
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = pattern.indexOf("{}", from)) >= 0) {
            count++;
            from = idx + 2;
        }
        return count;
    }

    /** 把前 {@code substitutable} 个实参顺序填进 {@code {}}; 多余的占位符原样留下。 */
    private static String format(String pattern, Object[] args, int substitutable) {
        if (substitutable == 0) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        int from = 0;
        int argIndex = 0;
        int idx;
        while (argIndex < substitutable && (idx = pattern.indexOf("{}", from)) >= 0) {
            sb.append(pattern, from, idx);
            sb.append(args[argIndex++]);
            from = idx + 2;
        }
        sb.append(pattern.substring(from));
        return sb.toString();
    }

    /**
     * 默认 sink: 写 System.err, 带时间戳 / 级别 / logger 名。裸 JRE CLI 下生效。
     * 故意只依赖 java.* (绝不 import slf4j), 这是整个门面存在的根本约束。
     */
    public static final class StderrSink implements Sink {
        @Override
        public void log(Level level, String loggerName, String message, Throwable error) {
            PrintStream stream = System.err;
            stream.println(Instant.now() + " [" + level + "] " + loggerName + " - " + message);
            if (error != null) {
                error.printStackTrace(stream);
            }
        }
    }
}
