package com.shinoyuki.betterbackup.snapshot;

import com.shinoyuki.betterbackup.store.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnapshotManifestTest {

    private static Hash hash(int seed) {
        byte[] b = new byte[16];
        b[0] = (byte) seed;
        b[15] = (byte) (seed * 7);
        return new Hash(b);
    }

    @Test
    void empty_manifest_round_trip_via_nbt() {
        SnapshotManifest m = SnapshotManifest.empty("2026-05-10T19-00-00", 12345L);
        SnapshotManifest restored = SnapshotManifest.fromNbt(m.toNbt());
        assertEquals(m.snapshotId(), restored.snapshotId());
        assertEquals(m.worldGameTime(), restored.worldGameTime());
        assertEquals(0, restored.chunks().size());
        assertEquals(0, restored.entityChunks().size());
        assertEquals(0, restored.savedData().size());
        assertNull(restored.levelDat());
    }

    @Test
    void chunks_round_trip_preserves_dim_and_pos() {
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        Map<Long, Hash> ow = new HashMap<>();
        ow.put(100L, hash(1));
        ow.put(200L, hash(2));
        chunks.put("minecraft:overworld", ow);
        Map<Long, Hash> nether = new HashMap<>();
        nether.put(300L, hash(3));
        chunks.put("minecraft:the_nether", nether);

        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                "test-id", 1000L, 99L,
                chunks, new HashMap<>(), new HashMap<>(),
                null, 0L, 0L);

        SnapshotManifest restored = SnapshotManifest.fromNbt(m.toNbt());
        assertEquals(2, restored.chunks().size());
        assertEquals(hash(1), restored.chunks().get("minecraft:overworld").get(100L));
        assertEquals(hash(2), restored.chunks().get("minecraft:overworld").get(200L));
        assertEquals(hash(3), restored.chunks().get("minecraft:the_nether").get(300L));
    }

    @Test
    void saved_data_round_trip() {
        Map<String, Hash> savedData = new HashMap<>();
        savedData.put("raids", hash(10));
        savedData.put("mtr_train_data", hash(20));

        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                "test-id", 1000L, 99L,
                new HashMap<>(), new HashMap<>(), savedData,
                null, 0L, 0L);

        SnapshotManifest restored = SnapshotManifest.fromNbt(m.toNbt());
        assertEquals(2, restored.savedData().size());
        assertEquals(hash(10), restored.savedData().get("raids"));
        assertEquals(hash(20), restored.savedData().get("mtr_train_data"));
    }

    @Test
    void level_dat_round_trip_when_present() {
        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                "test-id", 1000L, 99L,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                hash(99), 0L, 0L);

        SnapshotManifest restored = SnapshotManifest.fromNbt(m.toNbt());
        assertNotNull(restored.levelDat());
        assertEquals(hash(99), restored.levelDat());
    }

    @Test
    void level_dat_null_omitted_in_nbt_and_round_trips_to_null() {
        SnapshotManifest m = SnapshotManifest.empty("test-id", 0L);
        // levelDat is null
        SnapshotManifest restored = SnapshotManifest.fromNbt(m.toNbt());
        assertNull(restored.levelDat());
    }

    @Test
    void disk_round_trip_via_atomic_write_and_read(@TempDir Path tempDir) throws IOException {
        Map<String, Map<Long, Hash>> chunks = new HashMap<>();
        Map<Long, Hash> ow = new HashMap<>();
        ow.put(100L, hash(7));
        chunks.put("minecraft:overworld", ow);

        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                "2026-05-10T19-00-00", 1234567890L, 42L,
                chunks, new HashMap<>(), new HashMap<>(),
                hash(8), 1024L, 256L);

        Path manifestFile = tempDir.resolve("snapshots").resolve("2026-05-10T19-00-00.manifest");
        m.writeTo(manifestFile);

        SnapshotManifest read = SnapshotManifest.readFrom(manifestFile);
        assertEquals(m.snapshotId(), read.snapshotId());
        assertEquals(m.createdAtMillis(), read.createdAtMillis());
        assertEquals(m.worldGameTime(), read.worldGameTime());
        assertEquals(m.totalUniqueBytes(), read.totalUniqueBytes());
        assertEquals(m.deltaBytes(), read.deltaBytes());
        assertEquals(hash(7), read.chunks().get("minecraft:overworld").get(100L));
        assertEquals(hash(8), read.levelDat());
    }

    @Test
    void unsupported_schema_version_rejected() {
        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                "test", 0L, 0L,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                null, 0L, 0L);
        var nbt = m.toNbt();
        nbt.putInt("version", 999); // 模拟未来 schema

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> SnapshotManifest.fromNbt(nbt));
    }
}
