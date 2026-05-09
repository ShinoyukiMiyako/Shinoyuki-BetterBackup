package com.shinoyuki.betterbackup.io;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldPathsTest {

    private static final Path WORLD_ROOT = Paths.get("/server/world");

    @Test
    void overworld_uses_world_root_directly() {
        WorldPaths paths = new WorldPaths(WORLD_ROOT);
        assertEquals(WORLD_ROOT.resolve("region"), paths.regionDir("minecraft:overworld"));
        assertEquals(WORLD_ROOT.resolve("entities"), paths.entitiesDir("minecraft:overworld"));
        assertEquals(WORLD_ROOT.resolve("data"), paths.dataDir("minecraft:overworld"));
    }

    @Test
    void nether_uses_dim_minus_one() {
        WorldPaths paths = new WorldPaths(WORLD_ROOT);
        assertEquals(WORLD_ROOT.resolve("DIM-1").resolve("region"),
                paths.regionDir("minecraft:the_nether"));
        assertEquals(WORLD_ROOT.resolve("DIM-1").resolve("entities"),
                paths.entitiesDir("minecraft:the_nether"));
    }

    @Test
    void end_uses_dim_one() {
        WorldPaths paths = new WorldPaths(WORLD_ROOT);
        assertEquals(WORLD_ROOT.resolve("DIM1").resolve("region"),
                paths.regionDir("minecraft:the_end"));
    }

    @Test
    void modded_dim_uses_dimensions_namespace_path() {
        WorldPaths paths = new WorldPaths(WORLD_ROOT);
        assertEquals(WORLD_ROOT.resolve("dimensions").resolve("twilightforest").resolve("twilight_forest")
                        .resolve("region"),
                paths.regionDir("twilightforest:twilight_forest"));
    }

    @Test
    void level_dat_at_world_root() {
        WorldPaths paths = new WorldPaths(WORLD_ROOT);
        assertEquals(WORLD_ROOT.resolve("level.dat"), paths.levelDat());
    }

    @Test
    void invalid_dim_id_throws() {
        WorldPaths paths = new WorldPaths(WORLD_ROOT);
        assertThrows(IllegalArgumentException.class, () -> paths.dimRoot("no_colon_here"));
        assertThrows(IllegalArgumentException.class, () -> paths.dimRoot("trailing_colon:"));
    }
}
