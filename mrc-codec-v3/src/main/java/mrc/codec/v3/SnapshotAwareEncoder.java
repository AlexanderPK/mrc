package mrc.codec.v3;

import mrc.codec.CompressionResult;
import mrc.codec.MrcEncoder;
import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.Chromosome;
import mrc.graph.ArithmeticPattern;
import mrc.graph.SequenceDetector;
import mrc.graph.TransitionGraph;
import mrc.snapshot.MrcSnapshotException;
import mrc.snapshot.SnapshotDeserializer;
import mrc.snapshot.SnapshotManifest;
import mrc.snapshotdb.SnapshotStore;

import java.io.*;
import java.nio.file.Path;
import java.util.List;

/**
 * Encodes bytes to MRC v0x03 format using a snapshot-derived chromosome to
 * guide operator selection.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>connected</b> — snapshot ID is embedded in the header; the decoder
 *       fetches the snapshot from a shared {@link SnapshotStore}.</li>
 *   <li><b>standalone</b> — the full snapshot bytes are appended after the
 *       encoded body so the decoder needs no external store.</li>
 * </ul>
 */
public class SnapshotAwareEncoder {

    private final ExtendedOperatorLibrary lib;

    public SnapshotAwareEncoder(ExtendedOperatorLibrary lib) {
        this.lib = lib;
    }

    /**
     * Encode {@code input} in connected mode.
     * The snapshot referenced by {@code snapshotId} must be available in {@code store}.
     */
    public byte[] encodeConnected(byte[] input, String snapshotId, SnapshotStore store)
            throws IOException, MrcSnapshotException {
        Path snapshotPath = store.load(snapshotId);
        byte[] snapshotIdBytes = hexToBytes(snapshotId);
        return encode(input, snapshotIdBytes, snapshotPath, V3Header.MODE_CONNECTED, null);
    }

    /**
     * Encode {@code input} in standalone mode.
     * The snapshot bytes are appended to the output so decoding needs no store.
     */
    public byte[] encodeStandalone(byte[] input, String snapshotId, SnapshotStore store)
            throws IOException, MrcSnapshotException {
        Path snapshotPath = store.load(snapshotId);
        byte[] snapshotIdBytes = hexToBytes(snapshotId);
        byte[] snapshotBytes = java.nio.file.Files.readAllBytes(snapshotPath);
        return encode(input, snapshotIdBytes, snapshotPath, V3Header.MODE_STANDALONE, snapshotBytes);
    }

    // ─── core encode ───────────────────────────────────────────────────────

    private byte[] encode(byte[] input, byte[] snapshotIdBytes, Path snapshotPath,
                           byte mode, byte[] embeddedSnapshot)
            throws IOException, MrcSnapshotException {

        // Load chromosome from snapshot and apply rules to a fresh graph
        SnapshotDeserializer deser = new SnapshotDeserializer(lib);
        SnapshotManifest manifest = deser.readManifest(snapshotPath);
        TransitionGraph graph = new TransitionGraph();

        if (manifest.hasChromosome()) {
            Chromosome chromosome = deser.loadChromosome(snapshotPath, manifest);
            for (Chromosome.OperatorRule rule : chromosome.rules()) {
                graph.setEdge(rule.fromValue(), rule.toValue(), rule.assignedOperator());
            }
        }

        // Detect arithmetic patterns and encode body using v0x02 tier
        SequenceDetector detector = new SequenceDetector();
        List<ArithmeticPattern> patterns = detector.detect(input);
        CompressionResult result = new MrcEncoder(patterns).encode(input);

        // Build output stream
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Header
        V3Header header = new V3Header(mode, snapshotIdBytes, result.compressedData().length);
        header.write(baos);

        // Standalone: embed snapshot after body_length field, before body
        if (mode == V3Header.MODE_STANDALONE && embeddedSnapshot != null) {
            writeInt(baos, embeddedSnapshot.length);
            baos.write(embeddedSnapshot);
        }

        // Body
        baos.write(result.compressedData());

        return baos.toByteArray();
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static byte[] hexToBytes(String hex) {
        if (hex.length() != 64) throw new IllegalArgumentException("Snapshot ID must be 64 hex chars");
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) Integer.parseInt(hex, i * 2, i * 2 + 2, 16);
        }
        return bytes;
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>>  8) & 0xFF);
        out.write(value & 0xFF);
    }
}
