package mrc.snapshot;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.Chromosome;
import mrc.evolution.ChromosomeFactory;
import mrc.evolution.GenerationResult;
import mrc.graph.TransitionGraph;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the snapshot module: write, read, validate, and round-trip.
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
class SnapshotIntegrationTest {

    static ExtendedOperatorLibrary lib;

    @BeforeAll
    static void setUpLib() {
        lib = ExtendedOperatorLibrary.getInstance();
    }

    // ========================= SnapshotVersion =========================

    @Test
    @DisplayName("SnapshotVersion: CURRENT is major 2")
    void snapshotVersion_currentIsMajor2() {
        assertEquals(2, SnapshotVersion.CURRENT.major());
        assertEquals(0, SnapshotVersion.CURRENT.minor());
    }

    @Test
    @DisplayName("SnapshotVersion: same major with different minor is compatible")
    void snapshotVersion_isCompatible_sameMajor() {
        SnapshotVersion v = new SnapshotVersion(2, 5);
        assertTrue(v.isCompatible(SnapshotVersion.CURRENT));
        assertTrue(SnapshotVersion.CURRENT.isCompatible(v));
    }

    @Test
    @DisplayName("SnapshotVersion: different major is incompatible")
    void snapshotVersion_isCompatible_differentMajor() {
        SnapshotVersion v = new SnapshotVersion(1, 0);
        assertFalse(v.isCompatible(SnapshotVersion.CURRENT));
        assertFalse(SnapshotVersion.CURRENT.isCompatible(v));
    }

    @Test
    @DisplayName("SnapshotVersion: toBytes/fromBytes round-trip preserves values")
    void snapshotVersion_toBytesRoundTrip() {
        SnapshotVersion original = new SnapshotVersion(2, 7);
        byte[] bytes = original.toBytes();
        // fromBytes reads from a header array at offset
        byte[] header = new byte[6];
        header[4] = bytes[0];
        header[5] = bytes[1];
        SnapshotVersion restored = SnapshotVersion.fromBytes(header, 4);
        assertEquals(original.major(), restored.major());
        assertEquals(original.minor(), restored.minor());
    }

    // ========================= SnapshotManifest =========================

    @Test
    @DisplayName("SnapshotManifest: readBack matches written values")
    void manifest_readBack_matchesWrittenValues(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("test.snap");
        Chromosome best = buildValidChromosome();
        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(snapFile, 42, null, null, best, null, "test-domain");

        SnapshotManifest manifest = SnapshotManifest.read(snapFile);

        assertEquals(42, manifest.generation());
        assertEquals(SnapshotVersion.CURRENT, manifest.version());
        assertTrue(manifest.hasChromosome());
        assertEquals("test-domain", manifest.domainTag());
    }

    @Test
    @DisplayName("SnapshotManifest: read throws on invalid magic bytes")
    void manifest_read_invalidMagicThrows(@TempDir Path tmp) throws Exception {
        Path badFile = tmp.resolve("bad.snap");
        // Write invalid magic bytes
        byte[] data = new byte[64];
        data[0] = 0x00;
        data[1] = 0x00;
        data[2] = 0x00;
        data[3] = 0x00;
        Files.write(badFile, data);
        assertThrows(MrcSnapshotException.class, () -> SnapshotManifest.read(badFile));
    }

    @Test
    @DisplayName("SnapshotManifest: printSummary writes Version and Generation to stream")
    void manifest_printSummary_writesToStream(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("test.snap");
        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(snapFile, 99, null, null, null, null, null);

        SnapshotManifest manifest = SnapshotManifest.read(snapFile);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        manifest.printSummary(new PrintStream(baos));
        String output = baos.toString();

        assertTrue(output.contains("Version"), "printSummary must include Version: " + output);
        assertTrue(output.contains("Generation"), "printSummary must include Generation: " + output);
    }

    // ========================= CRC Validation =========================

    @Test
    @DisplayName("validateCrc: freshly written snapshot validates")
    void validateCrc_freshSnapshot(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("fresh.snap");
        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(snapFile, 1, null, null, null, null, null);

        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        assertTrue(deser.validateCrc(snapFile), "Fresh snapshot CRC must validate");
    }

    @Test
    @DisplayName("validateCrc: flipped byte fails validation")
    void validateCrc_corruptedFileFails(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("corrupt.snap");
        Chromosome best = buildValidChromosome();
        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(snapFile, 1, null, null, best, null, null);

        // Flip the last byte (in chromosome data area, well past the manifest section table)
        byte[] bytes = Files.readAllBytes(snapFile);
        bytes[bytes.length - 1] ^= 0xFF;
        Files.write(snapFile, bytes);

        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        assertFalse(deser.validateCrc(snapFile), "Corrupted snapshot CRC must fail");
    }

    // ========================= Full Snapshot Round-Trip =========================

    @Test
    @DisplayName("fullSnapshot: loadAll restores history with correct generation and fitness")
    void fullSnapshot_loadAll_restoresHistory(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("history.snap");
        Chromosome best = buildValidChromosome().withFitness(0.75);
        List<GenerationResult> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Chromosome c = best.withFitness(i * 0.1);
            history.add(GenerationResult.of(i, c, List.of(c), 512L * i, Instant.now()));
        }

        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(snapFile, 5, null, null, best, history, null);

        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        SnapshotDeserializer.FullSnapshot snap = deser.loadAll(snapFile);

        assertNotNull(snap.history());
        assertEquals(5, snap.history().size());
        assertEquals(0, snap.history().get(0).generation());
        assertEquals(4, snap.history().get(4).generation());
    }

    @Test
    @DisplayName("fullSnapshot: history capped at 1000 entries")
    void fullSnapshot_historyCapAt1000(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("large_history.snap");
        Chromosome best = buildValidChromosome().withFitness(0.5);
        List<GenerationResult> history = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            history.add(GenerationResult.of(i, best, List.of(best), 512L, Instant.now()));
        }

        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(snapFile, 1500, null, null, best, history, null);

        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        SnapshotDeserializer.FullSnapshot snap = deser.loadAll(snapFile);

        assertTrue(snap.history().size() <= 1000,
                "History must be capped at 1000 entries, got: " + snap.history().size());
    }

    @Test
    @DisplayName("fullSnapshot: null chromosome accepted without exception")
    void fullSnapshot_nullChromosome(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("no_chromosome.snap");
        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        assertDoesNotThrow(() -> serializer.serialize(snapFile, 0, null, null, null, null, null));

        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        SnapshotDeserializer.FullSnapshot snap = deser.loadAll(snapFile);
        assertNull(snap.chromosome());
        assertFalse(snap.manifest().hasChromosome());
    }

    @Test
    @DisplayName("fullSnapshot: deserialized chromosome passes operator consistency check")
    void validateOperatorConsistency_validChromosome(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("chromosome_check.snap");
        Chromosome best = buildValidChromosome();

        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(snapFile, 1, null, null, best, null, null);

        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        SnapshotDeserializer.FullSnapshot snap = deser.loadAll(snapFile);

        assertNotNull(snap.chromosome(), "Chromosome must be deserialized");
        assertTrue(deser.validateOperatorConsistency(snap),
                "Deserialized chromosome must pass operator consistency check");
    }

    @Test
    @DisplayName("fullSnapshot: domain tag round-trips correctly")
    void snapshotWithDomainTag_roundTrips(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("domain.snap");
        String domainTag = "audio-v3";

        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(snapFile, 10, null, null, null, null, domainTag);

        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        SnapshotManifest manifest = deser.readManifest(snapFile);

        assertEquals(domainTag, manifest.domainTag());
    }

    @Test
    @DisplayName("fullSnapshot: null graph accepted gracefully")
    void snapshot_withNullGraph_doesNotThrow(@TempDir Path tmp) throws Exception {
        Path snapFile = tmp.resolve("no_graph.snap");
        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        assertDoesNotThrow(() -> serializer.serialize(snapFile, 5, null, null, null, null, null));

        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        SnapshotDeserializer.FullSnapshot snap = deser.loadAll(snapFile);
        assertNotNull(snap.graph(), "Graph must be non-null (empty TransitionGraph)");
    }

    // ========================= Helper Methods =========================

    static Chromosome buildValidChromosome() {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        return factory.createRandom(10);
    }
}
