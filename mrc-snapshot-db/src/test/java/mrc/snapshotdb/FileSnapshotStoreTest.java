package mrc.snapshotdb;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.Chromosome;
import mrc.evolution.ChromosomeFactory;
import mrc.snapshot.SnapshotSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.DisplayName.class)
class FileSnapshotStoreTest {

    static ExtendedOperatorLibrary lib;

    @BeforeAll
    static void setUpLib() {
        lib = ExtendedOperatorLibrary.getInstance();
    }

    // ─── publish / exists / load ──────────────────────────────────────────

    @Test
    @DisplayName("publish: returns 64-char hex ID")
    void publish_returns64CharHexId(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        Path snap = writeSnap(tmp, "domain-a");
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(snap);
        assertEquals(64, id.length(), "SHA-256 hex must be 64 chars");
        assertTrue(id.matches("[0-9a-f]{64}"), "ID must be lowercase hex");
    }

    @Test
    @DisplayName("publish: same file twice returns same ID, one file on disk")
    void publish_dedup_sameIdOneFile(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        Path snap = writeSnap(tmp, "domain-a");
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);

        String id1 = store.publish(snap);
        String id2 = store.publish(snap);

        assertEquals(id1, id2, "Same content must produce same ID");

        // Count .snap files in store
        long count = Files.walk(storeRoot)
                .filter(p -> p.toString().endsWith(".snap"))
                .count();
        assertEquals(1, count, "Only one .snap file must exist for deduplicated content");
    }

    @Test
    @DisplayName("exists: returns true after publish, false for unknown ID")
    void exists_trueAfterPublish(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        Path snap = writeSnap(tmp, null);
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(snap);

        assertTrue(store.exists(id));
        assertFalse(store.exists("a".repeat(64)));
    }

    @Test
    @DisplayName("load: returns readable path after publish")
    void load_returnsReadablePath(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        Path snap = writeSnap(tmp, "test-domain");
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(snap);

        Path loaded = store.load(id);
        assertTrue(Files.exists(loaded), "Loaded path must exist");
        assertTrue(Files.size(loaded) > 0, "Loaded file must be non-empty");
    }

    @Test
    @DisplayName("load: throws SnapshotNotFoundException for unknown ID")
    void load_throwsForUnknownId(@TempDir Path storeRoot) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);
        assertThrows(SnapshotNotFoundException.class,
                () -> store.load("b".repeat(64)));
    }

    @Test
    @DisplayName("load: SnapshotNotFoundException carries the requested ID")
    void load_exceptionCarriesId(@TempDir Path storeRoot) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);
        String badId = "c".repeat(64);
        SnapshotNotFoundException ex = assertThrows(SnapshotNotFoundException.class,
                () -> store.load(badId));
        assertEquals(badId, ex.snapshotId());
    }

    // ─── listByDomain ─────────────────────────────────────────────────────

    @Test
    @DisplayName("listByDomain: returns IDs matching domain tag")
    void listByDomain_returnsMatchingIds(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);

        String id1 = store.publish(writeSnapGeneration(tmp, "audio", 1));
        String id2 = store.publish(writeSnapGeneration(tmp, "audio", 2));
        store.publish(writeSnapGeneration(tmp, "video", 3));

        List<String> audioIds = store.listByDomain("audio");
        assertEquals(2, audioIds.size(), "Must return 2 audio snapshots");
        assertTrue(audioIds.contains(id1));
        assertTrue(audioIds.contains(id2));
    }

    @Test
    @DisplayName("listByDomain: returns empty list for unknown domain")
    void listByDomain_emptyForUnknownDomain(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);
        store.publish(writeSnap(tmp, "audio"));

        List<String> result = store.listByDomain("video");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("listByDomain: null domain tag returns no-domain snapshots")
    void listByDomain_nullDomain(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(storeRoot);
        store.publish(writeSnap(tmp, null)); // no domain

        List<String> result = store.listByDomain(null);
        assertEquals(1, result.size());
    }

    // ─── index persistence ────────────────────────────────────────────────

    @Test
    @DisplayName("index persists across store instances")
    void index_persistsAcrossInstances(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        FileSnapshotStore store1 = new FileSnapshotStore(storeRoot);
        String id = store1.publish(writeSnap(tmp, "persist-domain"));

        // New store instance reading same directory
        FileSnapshotStore store2 = new FileSnapshotStore(storeRoot);
        List<String> ids = store2.listByDomain("persist-domain");
        assertTrue(ids.contains(id), "Index must survive across store instances");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    static Path writeSnap(Path dir, String domainTag) throws Exception {
        return writeSnapGeneration(dir, domainTag, 1);
    }

    static Path writeSnapGeneration(Path dir, String domainTag, int generation) throws Exception {
        String name = "snap-" + generation + (domainTag != null ? "-" + domainTag : "") + ".snap";
        Path out = dir.resolve(name);
        // Use seed 42 + size 10 — same pattern as SnapshotIntegrationTest (avoids extended-operator
        // types that are not registered in OpIdMap)
        Chromosome best = new ChromosomeFactory(lib, new java.util.Random(42)).createRandom(10);
        new SnapshotSerializer(lib).serialize(out, generation, null, null, best, null, domainTag);
        return out;
    }
}
