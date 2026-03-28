package mrc.codec.v3;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.Chromosome;
import mrc.evolution.ChromosomeFactory;
import mrc.snapshot.SnapshotSerializer;
import mrc.snapshotdb.FileSnapshotStore;
import mrc.snapshotdb.SnapshotStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.DisplayName.class)
class SnapshotAwareCodecTest {

    static ExtendedOperatorLibrary lib;

    @BeforeAll
    static void setUpLib() {
        lib = ExtendedOperatorLibrary.getInstance();
    }

    // ─── V3Header ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("V3Header: write then read round-trips mode and ID")
    void header_writeReadRoundTrip() throws Exception {
        byte[] id = new byte[32];
        new Random(7).nextBytes(id);
        V3Header original = new V3Header(V3Header.MODE_CONNECTED, id, 4096);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        original.write(baos);

        V3Header restored = V3Header.read(new java.io.ByteArrayInputStream(baos.toByteArray()));
        assertEquals(original.mode(), restored.mode());
        assertArrayEquals(original.snapshotIdBytes(), restored.snapshotIdBytes());
        assertEquals(original.bodyLength(), restored.bodyLength());
    }

    @Test
    @DisplayName("V3Header: snapshotId() returns 64-char lowercase hex")
    void header_snapshotIdHex() throws Exception {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) 0xAB);
        V3Header header = new V3Header(V3Header.MODE_STANDALONE, id, 0);
        String hexId = header.snapshotId();
        assertEquals(64, hexId.length());
        assertEquals("ab".repeat(32), hexId);
    }

    @Test
    @DisplayName("V3Header: fixed size is 41 bytes")
    void header_fixedSize() throws Exception {
        byte[] id = new byte[32];
        V3Header header = new V3Header(V3Header.MODE_CONNECTED, id, 0);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        header.write(baos);
        assertEquals(V3Header.FIXED_SIZE, baos.size());
    }

    @Test
    @DisplayName("V3Header: read throws on wrong magic bytes")
    void header_wrongMagicThrows() {
        byte[] bad = new byte[50];
        bad[0] = 0x00; bad[1] = 0x00; bad[2] = 0x00; bad[3] = 0x00;
        assertThrows(MrcV3FormatException.class,
                () -> V3Header.read(new java.io.ByteArrayInputStream(bad)));
    }

    // ─── Connected Mode Round-Trip ─────────────────────────────────────────

    @Test
    @DisplayName("connected: encode then decode produces identical bytes")
    void connected_roundTrip(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(writeSnap(tmp, "arith", 1));

        byte[] input = arithmeticData(4096);
        SnapshotAwareEncoder encoder = new SnapshotAwareEncoder(lib);
        byte[] compressed = encoder.encodeConnected(input, id, store);

        SnapshotAwareDecoder decoder = new SnapshotAwareDecoder();
        byte[] decoded = decoder.decodeConnected(compressed, store);

        assertArrayEquals(input, decoded, "Connected round-trip must produce identical bytes");
    }

    @Test
    @DisplayName("connected: compressed output starts with MRC3 magic")
    void connected_hasMagicBytes(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(writeSnap(tmp, null, 1));

        byte[] compressed = new SnapshotAwareEncoder(lib)
                .encodeConnected(new byte[]{1, 2, 3}, id, store);

        assertEquals((byte) 0x4D, compressed[0]);
        assertEquals((byte) 0x52, compressed[1]);
        assertEquals((byte) 0x43, compressed[2]);
        assertEquals((byte) 0x33, compressed[3]);
    }

    @Test
    @DisplayName("connected: header embeds correct snapshot ID")
    void connected_headerHasSnapshotId(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(writeSnap(tmp, null, 1));

        byte[] compressed = new SnapshotAwareEncoder(lib)
                .encodeConnected(new byte[]{42}, id, store);

        String readId = new SnapshotAwareDecoder().readSnapshotId(compressed);
        assertEquals(id, readId, "Snapshot ID in header must match published ID");
    }

    // ─── Standalone Mode Round-Trip ────────────────────────────────────────

    @Test
    @DisplayName("standalone: encode then decode without store produces identical bytes")
    void standalone_roundTripWithoutStore(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(writeSnap(tmp, "arith", 1));

        byte[] input = arithmeticData(2048);
        byte[] compressed = new SnapshotAwareEncoder(lib).encodeStandalone(input, id, store);

        // Decode without providing a store
        byte[] decoded = new SnapshotAwareDecoder().decodeStandalone(compressed);
        assertArrayEquals(input, decoded, "Standalone round-trip must produce identical bytes");
    }

    @Test
    @DisplayName("standalone: mode byte is 0x00")
    void standalone_modeByte(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(writeSnap(tmp, null, 1));
        byte[] compressed = new SnapshotAwareEncoder(lib)
                .encodeStandalone(new byte[]{1, 2, 3}, id, store);
        assertEquals(V3Header.MODE_STANDALONE, compressed[4], "Mode byte must be 0x00 for standalone");
    }

    @Test
    @DisplayName("connected: mode byte is 0x01")
    void connected_modeByte(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(writeSnap(tmp, null, 1));
        byte[] compressed = new SnapshotAwareEncoder(lib)
                .encodeConnected(new byte[]{1, 2, 3}, id, store);
        assertEquals(V3Header.MODE_CONNECTED, compressed[4], "Mode byte must be 0x01 for connected");
    }

    @Test
    @DisplayName("standalone: extractEmbeddedSnapshot returns valid snapshot file")
    void standalone_extractSnapshot(@TempDir Path storeRoot, @TempDir Path tmp) throws Exception {
        SnapshotStore store = new FileSnapshotStore(storeRoot);
        String id = store.publish(writeSnap(tmp, "extract-test", 1));

        byte[] compressed = new SnapshotAwareEncoder(lib)
                .encodeStandalone(new byte[]{10, 20, 30}, id, store);

        Path extracted = new SnapshotAwareDecoder().extractEmbeddedSnapshot(compressed);
        assertTrue(Files.exists(extracted), "Extracted snapshot file must exist");
        assertTrue(Files.size(extracted) > 0, "Extracted snapshot file must be non-empty");
        Files.deleteIfExists(extracted);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    static byte[] arithmeticData(int length) {
        byte[] data = new byte[length];
        int val = 0;
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (val & 0xFF);
            val = (val + 3) & 0xFF;
        }
        return data;
    }

    static Path writeSnap(Path dir, String domainTag, int generation) throws Exception {
        String name = "snap-" + generation + (domainTag != null ? "-" + domainTag : "") + ".snap";
        Path out = dir.resolve(name);
        Chromosome best = new ChromosomeFactory(lib, new Random(42)).createRandom(10);
        new SnapshotSerializer(lib).serialize(out, generation, null, null, best, null, domainTag);
        return out;
    }
}
