package mrc.selector;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.Chromosome;
import mrc.evolution.ChromosomeFactory;
import mrc.snapshot.SnapshotSerializer;
import mrc.snapshotdb.FileSnapshotStore;
import mrc.snapshotdb.SnapshotStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.DisplayName.class)
class SelectorTest {

    static ExtendedOperatorLibrary lib;

    @BeforeAll
    static void setUpLib() {
        lib = ExtendedOperatorLibrary.getInstance();
    }

    // ─── DataFingerprint ─────────────────────────────────────────────────

    @Test
    @DisplayName("DataFingerprint: constant sequence != arithmetic sequence")
    void fingerprint_constantNotEqualArithmetic() {
        // Constant: all zeros — entropy 0, all deltas 0
        byte[] constant = new byte[256];
        // Arithmetic: 0,1,2,...,255 — entropy ~8, all deltas 1
        byte[] arithmetic = new byte[256];
        for (int i = 0; i < 256; i++) arithmetic[i] = (byte) (i & 0xFF);

        DataFingerprint fpConst = DataFingerprint.of(constant);
        DataFingerprint fpArith = DataFingerprint.of(arithmetic);

        // Entropy differs: constant has 0, arithmetic has ~1.0 (normalised)
        assertNotEquals(fpConst.features()[0], fpArith.features()[0], 1e-6,
                "Constant and arithmetic sequences must differ in entropy");
    }

    @Test
    @DisplayName("DataFingerprint: empty array returns zero vector")
    void fingerprint_emptyArrayReturnsZeroVector() {
        DataFingerprint fp = DataFingerprint.of(new byte[0]);
        for (double v : fp.features()) {
            assertEquals(0.0, v, 1e-12);
        }
    }

    @Test
    @DisplayName("DataFingerprint: single byte returns zero vector")
    void fingerprint_singleByteReturnsZeroVector() {
        DataFingerprint fp = DataFingerprint.of(new byte[]{42});
        // No deltas possible → most features zero, only entropy non-zero
        for (int i = 1; i < DataFingerprint.SIZE; i++) {
            assertEquals(0.0, fp.features()[i], 1e-12, "Feature " + i + " must be 0 for single byte");
        }
    }

    @Test
    @DisplayName("DataFingerprint: arithmetic sequence has high arith-run density")
    void fingerprint_arithmeticSequenceHighRunDensity() {
        byte[] arith = new byte[256];
        for (int i = 0; i < 256; i++) arith[i] = (byte) (i & 0xFF);
        DataFingerprint fp = DataFingerprint.of(arith);
        // All deltas are 1, so arith-run density (f[1]) should be ~1
        assertTrue(fp.features()[1] > 0.9, "Arithmetic sequence must have high arith-run density, got " + fp.features()[1]);
    }

    @Test
    @DisplayName("DataFingerprint: SIZE is 12")
    void fingerprint_size() {
        assertEquals(12, DataFingerprint.SIZE);
    }

    @Test
    @DisplayName("DataFingerprint: cosine similarity of identical vectors is 1.0")
    void fingerprint_cosineSimilarityIdentical() {
        byte[] data = new byte[100];
        new Random(1).nextBytes(data);
        DataFingerprint fp = DataFingerprint.of(data);
        assertEquals(1.0, fp.cosineSimilarity(fp), 1e-9);
    }

    @Test
    @DisplayName("DataFingerprint: cosine similarity of zero vectors is 0.0")
    void fingerprint_cosineSimilarityZeroVectors() {
        DataFingerprint fp = DataFingerprint.of(new byte[0]);
        assertEquals(0.0, fp.cosineSimilarity(fp));
    }

    // ─── SnapshotRanker ──────────────────────────────────────────────────

    @Test
    @DisplayName("SnapshotRanker: picks higher-similarity snapshot from 2 candidates")
    void ranker_picksHigherSimilarityCandidate(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);

        // Publish an "arith" snapshot and a "random" snapshot
        String arithId = store.publish(writeSnap(tmp, "arith", 1));
        String randomId = store.publish(writeSnap(tmp, "random", 2));

        // Sample is arithmetic (constant step)
        byte[] arithSample = new byte[256];
        for (int i = 0; i < 256; i++) arithSample[i] = (byte) (i * 2 & 0xFF);

        SnapshotRanker ranker = new SnapshotRanker();
        List<SnapshotRanker.RankedSnapshot> ranked = ranker.rank(
                arithSample, List.of(arithId, randomId), store);

        assertFalse(ranked.isEmpty(), "Ranked list must not be empty");
        assertEquals(arithId, ranked.get(0).snapshotId(),
                "Arithmetic sample must rank arith snapshot higher than random");
    }

    @Test
    @DisplayName("SnapshotRanker: empty candidate list returns empty result")
    void ranker_emptyListReturnsEmpty(@TempDir Path storeRoot) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        SnapshotRanker ranker = new SnapshotRanker();
        List<SnapshotRanker.RankedSnapshot> result = ranker.rank(new byte[100], List.of(), store);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("SnapshotRanker: scores are in [0, 1]")
    void ranker_scoresInRange(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        store.publish(writeSnap(tmp, "audio", 1));
        store.publish(writeSnap(tmp, "arith", 2));

        SnapshotRanker ranker = new SnapshotRanker();
        byte[] sample = new byte[128];
        new Random(99).nextBytes(sample);
        List<SnapshotRanker.RankedSnapshot> ranked = ranker.rank(
                sample, store.listByDomain("audio"), store);

        for (SnapshotRanker.RankedSnapshot r : ranked) {
            assertTrue(r.score() >= 0.0 && r.score() <= 1.0,
                    "Score must be in [0,1], got " + r.score());
        }
    }

    // ─── SelectorServer ──────────────────────────────────────────────────

    @Test
    @DisplayName("SelectorServer: /health returns 200 with status ok")
    void server_healthEndpoint(@TempDir Path storeRoot) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        SelectorServer server = SelectorServer.start(0, store);
        try {
            URL url = new URI("http://localhost:" + server.port() + "/health").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            String body = new String(conn.getInputStream().readAllBytes());
            assertTrue(body.contains("ok"), "Health response must contain 'ok': " + body);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("SelectorServer: /select with no snapshots returns 404")
    void server_selectNoSnapshots(@TempDir Path storeRoot) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        SelectorServer server = SelectorServer.start(0, store);
        try {
            URL url = new URI("http://localhost:" + server.port() + "/select").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.getOutputStream().write(new byte[]{1, 2, 3});
            assertEquals(404, conn.getResponseCode());
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("SelectorServer: /select returns 200 with snapshot_id when snapshots available")
    void server_selectReturnsSnapshotId(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        store.publish(writeSnap(tmp, "arith", 1));

        SelectorServer server = SelectorServer.start(0, store);
        try {
            URL url = new URI("http://localhost:" + server.port() + "/select").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            byte[] sample = new byte[256];
            for (int i = 0; i < 256; i++) sample[i] = (byte) i;
            conn.getOutputStream().write(sample);
            assertEquals(200, conn.getResponseCode());
            String body = new String(conn.getInputStream().readAllBytes());
            assertTrue(body.contains("snapshot_id"), "Response must contain snapshot_id: " + body);
        } finally {
            server.stop();
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    static Path writeSnap(Path dir, String domainTag, int generation) throws Exception {
        String name = "snap-" + generation + "-" + domainTag + ".snap";
        Path out = dir.resolve(name);
        Chromosome best = new ChromosomeFactory(lib, new Random(42)).createRandom(10);
        new SnapshotSerializer(lib).serialize(out, generation, null, null, best, null, domainTag);
        return out;
    }
}
