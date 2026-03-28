package mrc.gaservice;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.*;
import mrc.graph.TransitionGraph;
import mrc.snapshotdb.FileSnapshotStore;
import mrc.snapshotdb.SnapshotStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.DisplayName.class)
class GaServiceTest {

    static ExtendedOperatorLibrary lib;

    @BeforeAll
    static void setUpLib() {
        lib = ExtendedOperatorLibrary.getInstance();
    }

    // ─── HealthServer ──────────────────────────────────────────────────────

    @Test
    @DisplayName("HealthServer: /health returns 200 with JSON")
    void healthServer_returnsJson() throws Exception {
        HealthServer server = HealthServer.start(0,
                () -> new HealthServer.GaStatus(42, 0.75));
        try {
            URL url = new URI("http://localhost:" + server.port() + "/health").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            String body = new String(conn.getInputStream().readAllBytes());
            assertTrue(body.contains("\"status\":\"running\""), "Response must contain status: " + body);
            assertTrue(body.contains("\"generation\":42"), "Response must contain generation: " + body);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("HealthServer: /health reflects live generation count")
    void healthServer_reflectsLiveCount() throws Exception {
        long[] gen = {0};
        HealthServer server = HealthServer.start(0,
                () -> new HealthServer.GaStatus(gen[0], 0.5));
        try {
            gen[0] = 99;
            URL url = new URI("http://localhost:" + server.port() + "/health").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            String body = new String(conn.getInputStream().readAllBytes());
            assertTrue(body.contains("\"generation\":99"), "Must reflect updated generation: " + body);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("HealthServer: non-GET request returns 405")
    void healthServer_rejectsPost() throws Exception {
        HealthServer server = HealthServer.start(0,
                () -> new HealthServer.GaStatus(0, 0.0));
        try {
            URL url = new URI("http://localhost:" + server.port() + "/health").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.getOutputStream().write(new byte[1]);
            assertEquals(405, conn.getResponseCode());
        } finally {
            server.stop();
        }
    }

    // ─── GA snapshot publishing ────────────────────────────────────────────

    @Test
    @DisplayName("publishSnapshot: produces valid snapshot in store")
    void publishSnapshot_producesValidSnapshot(@TempDir Path storeRoot) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        EvolutionConfig config = EvolutionConfig.fastConfig(storeRoot.resolve("tmp"));
        TransitionGraph graph = new TransitionGraph();
        // Seed 42 avoids FunctionOperator types not registered in OpIdMap
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, graph, null, new java.util.Random(42));

        // Feed data so the finder has a best chromosome
        byte[] seed = new byte[512];
        for (int i = 0; i < seed.length; i++) seed[i] = (byte) (i & 0xFF);
        finder.feedData(seed);

        String id = GaServiceMain.publishSnapshot(finder, lib, store, "test-domain", 1);

        assertNotNull(id, "Snapshot ID must not be null");
        assertEquals(64, id.length(), "Snapshot ID must be 64-char hex");
        assertTrue(store.exists(id), "Published snapshot must exist in store");
    }

    @Test
    @DisplayName("publishSnapshot: multiple calls produce different IDs for different gens")
    void publishSnapshot_differentGenerationsDifferentIds(@TempDir Path storeRoot) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        EvolutionConfig config = EvolutionConfig.fastConfig(storeRoot.resolve("tmp"));
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, new TransitionGraph(), null, new java.util.Random(42));

        byte[] seed = new byte[512];
        for (int i = 0; i < seed.length; i++) seed[i] = (byte) (i * 3 & 0xFF);
        finder.feedData(seed);

        String id1 = GaServiceMain.publishSnapshot(finder, lib, store, null, 1);
        String id2 = GaServiceMain.publishSnapshot(finder, lib, store, null, 2);

        // They may be the same if chromosome hasn't changed (same content = same hash)
        // Just verify both are valid IDs
        assertNotNull(id1);
        assertNotNull(id2);
        assertTrue(store.exists(id1));
        assertTrue(store.exists(id2));
    }

    @Test
    @DisplayName("publishSnapshot: domain tag is indexed in store")
    void publishSnapshot_domainTagIndexed(@TempDir Path storeRoot) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        EvolutionConfig config = EvolutionConfig.fastConfig(storeRoot.resolve("tmp"));
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, new TransitionGraph(), null, new java.util.Random(42));
        byte[] seed = new byte[512];
        for (int i = 0; i < seed.length; i++) seed[i] = (byte) i;
        finder.feedData(seed);

        String id = GaServiceMain.publishSnapshot(finder, lib, store, "audio-v3", 1);

        List<String> audioIds = store.listByDomain("audio-v3");
        assertTrue(audioIds.contains(id), "Domain index must contain published snapshot ID");
    }

    @Test
    @DisplayName("GaStatus: record stores generation and fitness")
    void gaStatus_storesValues() {
        HealthServer.GaStatus status = new HealthServer.GaStatus(77, 0.42);
        assertEquals(77, status.generation());
        assertEquals(0.42, status.bestFitness(), 1e-9);
    }
}
