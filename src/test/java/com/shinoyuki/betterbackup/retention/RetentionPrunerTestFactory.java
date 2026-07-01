package com.shinoyuki.betterbackup.retention;

import java.nio.file.Path;

/**
 * 测试专用工厂: 把 {@link RetentionPruner} 的包级私有 policy 注入构造暴露给其他包的测试
 * (如 {@code gc.StoreSizeGuardTest}), 让它们注入确定 {@link RetentionPolicy} 而不依赖静态 config
 * (config 未加载时四档默认 0 会走 retainsNothing 短路). 仅存在于 test 源集, 不进生产 jar,
 * 不改动已提交的 {@link RetentionPruner} 生产类可见性.
 */
public final class RetentionPrunerTestFactory {

    private RetentionPrunerTestFactory() {
    }

    public static RetentionPruner withPolicy(Path snapshotsDir, Path worldRoot, RetentionPolicy policy) {
        return new RetentionPruner(snapshotsDir, worldRoot, policy);
    }
}
