package com.shinoyuki.betterbackup.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreLocationCheckTest {

    @Test
    void store_directly_inside_world_is_nested(@TempDir Path base) {
        Path world = base.resolve("world");
        Path store = world.resolve("backup-store");
        assertTrue(StoreLocationCheck.isNestedInWorld(store, world));
    }

    @Test
    void store_deeply_inside_world_is_nested(@TempDir Path base) {
        Path world = base.resolve("world");
        Path store = world.resolve("region").resolve("sub").resolve("store");
        assertTrue(StoreLocationCheck.isNestedInWorld(store, world));
    }

    @Test
    void store_equal_to_world_is_nested(@TempDir Path base) {
        Path world = base.resolve("world");
        assertTrue(StoreLocationCheck.isNestedInWorld(world, world));
    }

    @Test
    void store_sibling_of_world_is_safe(@TempDir Path base) {
        Path world = base.resolve("world");
        Path store = base.resolve("backup-store");
        assertFalse(StoreLocationCheck.isNestedInWorld(store, world));
    }

    @Test
    void dotdot_escaping_world_prefix_is_not_a_false_positive(@TempDir Path base) {
        // 形式上以 world 开头但 .. 跳出到 sibling: normalize 后必须判定为安全.
        Path world = base.resolve("world");
        Path store = world.resolve("..").resolve("backup-store");
        assertFalse(StoreLocationCheck.isNestedInWorld(store, world));
    }

    @Test
    void sibling_with_world_name_prefix_is_not_nested(@TempDir Path base) {
        // "world-backup" 字符串以 "world" 开头但不是 world/ 的子路径, 不得误报.
        Path world = base.resolve("world");
        Path store = base.resolve("world-backup");
        assertFalse(StoreLocationCheck.isNestedInWorld(store, world));
    }
}
