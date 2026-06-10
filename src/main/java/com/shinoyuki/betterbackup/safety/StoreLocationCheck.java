package com.shinoyuki.betterbackup.safety;

import java.nio.file.Path;
import java.util.Objects;

/**
 * store 套娃防护: 检测 dedup store 是否位于 world 目录内部.
 *
 * <p>store 在 world 内时有两个独立的灾难:
 * <ol>
 *   <li>备份吃自己: BAS / baseline 扫 world 目录会把 store 的 .mca-style 文件也
 *       视作世界数据递归备份, store 越大备份越大, 指数膨胀</li>
 *   <li>restore 抹掉 store: RestoreFlow.moveCurrentWorldToBackup 把 world 子目录
 *       atomic rename 走, store 若在其中会被一并搬到 .bak 目录甚至覆盖, 恢复过程
 *       自毁备份数据</li>
 * </ol>
 *
 * <p>因此 store 必须位于 world 目录之外. 本检测在启动时跑, 命中即告警 (不强制
 * abort: 服主可能有意为之或路径解析有特殊符号链接, 决策权留给人; 但必须让其可见).
 */
public final class StoreLocationCheck {

    private StoreLocationCheck() {
    }

    /**
     * 判断 {@code storeRoot} 是否嵌套在 {@code worldRoot} 内部 (含相等).
     *
     * <p>两路径先 normalize (折叠 {@code .} / {@code ..}) 再做前缀判断, 避免
     * {@code world/../backup-store} 这类形式上含 worldRoot 前缀但实际在外部的误报.
     *
     * @return true = store 在 world 内 (危险), false = store 在 world 外 (安全)
     */
    public static boolean isNestedInWorld(Path storeRoot, Path worldRoot) {
        Objects.requireNonNull(storeRoot, "storeRoot");
        Objects.requireNonNull(worldRoot, "worldRoot");
        Path store = storeRoot.toAbsolutePath().normalize();
        Path world = worldRoot.toAbsolutePath().normalize();
        return store.startsWith(world);
    }
}
