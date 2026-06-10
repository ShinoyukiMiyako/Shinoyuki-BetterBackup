package com.shinoyuki.betterbackup.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link BackupLog} 转回 slf4j 的桥接 sink。仅由 mod 侧 ({@link
 * com.shinoyuki.betterbackup.BetterBackupMod}) 在游戏内启动时安装, 让 store 等已迁到门面的
 * CLI 可达类在游戏内仍走 Forge 提供的 slf4j -> log4j2 管线, 服主日志观感不变。
 *
 * <p><b>不在 CLI 可达图上</b>: 本类 import slf4j, 只有 BetterBackupMod 引用它。裸 JRE CLI
 * 不安装它, 因此 CLI 路径永远碰不到 slf4j, 这正是门面拆分要守的边界。
 *
 * <p>按 loggerName 缓存 {@link Logger} 实例 (LoggerFactory 自身也缓存, 这里再缓存一层省去
 * 每条日志的 map 查找), 还原迁移前 {@code LoggerFactory.getLogger(Class)} 的具名 logger。
 */
public final class Slf4jLogSink implements BackupLog.Sink {

    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public void log(BackupLog.Level level, String loggerName, String message, Throwable error) {
        Logger logger = loggers.computeIfAbsent(loggerName, LoggerFactory::getLogger);
        switch (level) {
            case INFO -> {
                if (error != null) {
                    logger.info(message, error);
                } else {
                    logger.info(message);
                }
            }
            case WARN -> {
                if (error != null) {
                    logger.warn(message, error);
                } else {
                    logger.warn(message);
                }
            }
            case ERROR -> {
                if (error != null) {
                    logger.error(message, error);
                } else {
                    logger.error(message);
                }
            }
        }
    }
}
