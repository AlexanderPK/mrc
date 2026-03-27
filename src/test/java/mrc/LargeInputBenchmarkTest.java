package mrc;

import mrc.codec.CompressionResult;
import mrc.codec.MrcDecoder;
import mrc.codec.MrcEncoder;
import mrc.codec.MrcFormatException;
import mrc.graph.CycleDetector;
import mrc.graph.TransitionGraph;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark compression on 1 MB+ test input files.
 *
 * Tests real compression performance on various data patterns:
 * - Random (incompressible)
 * - Arithmetic sequence (highly compressible)
 * - Text-like (moderately compressible)
 * - Repetitive (maximally compressible)
 */
public class LargeInputBenchmarkTest {

    /**
     * Test compression on random bytes (incompressible).
     */
    @Test
    public void testRandomBytes() throws IOException, MrcFormatException {
        byte[] input = TestInputs.randomBytes();
        testCompression("Random Bytes", input, 0.99, 1.10);
    }

    /**
     * Test compression on arithmetic sequence (highly compressible).
     */
    @Test
    public void testArithmeticSequence() throws IOException, MrcFormatException {
        byte[] input = TestInputs.arithmeticSequence();
        testCompression("Arithmetic Sequence", input, 0.20, 0.50);
    }

    /**
     * Test compression on text-like data (moderately compressible).
     */
    @Test
    public void testTextLike() throws IOException, MrcFormatException {
        byte[] input = TestInputs.textLike();
        testCompression("Text-Like Data", input, 0.40, 0.90);
    }

    /**
     * Test compression on repetitive data (maximally compressible).
     */
    @Test
    public void testRepetitive() throws IOException, MrcFormatException {
        byte[] input = TestInputs.repetitive();
        testCompression("Repetitive Data", input, 0.01, 0.15);
    }

    /**
     * Run compression test with round-trip verification and performance reporting.
     */
    private void testCompression(String name, byte[] input, double minRatio, double maxRatio)
            throws IOException, MrcFormatException {
        System.out.println("\n=== " + name + " ===");
        System.out.println("Input size: " + formatBytes(input.length));

        // Build graph and detect cycles
        TransitionGraph graph = new TransitionGraph();
        graph.observe(input);

        CycleDetector detector = new CycleDetector(graph, 8);
        detector.findAllCycles();

        // Encode
        long encodeStart = System.nanoTime();
        MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
        CompressionResult result = encoder.encode(input);
        long encodeTime = (System.nanoTime() - encodeStart) / 1_000_000;

        byte[] compressed = result.compressedData();
        System.out.println("Compressed size: " + formatBytes(compressed.length));
        System.out.println("Compression ratio: " + String.format("%.4f", result.ratio()));
        System.out.println("Space saving: " + String.format("%.2f%%", result.spaceSaving() * 100));

        // Verify ratio is within expected range
        assertTrue(result.ratio() >= minRatio && result.ratio() <= maxRatio,
                String.format("Compression ratio %.4f outside expected range [%.4f, %.4f]",
                        result.ratio(), minRatio, maxRatio));

        // Decode and verify round-trip
        long decodeStart = System.nanoTime();
        MrcDecoder decoder = new MrcDecoder();
        byte[] decoded = decoder.decode(compressed);
        long decodeTime = (System.nanoTime() - decodeStart) / 1_000_000;

        System.out.println("Encode time: " + encodeTime + " ms");
        System.out.println("Decode time: " + decodeTime + " ms");

        // Verify round-trip
        assertTrue(Arrays.equals(input, decoded),
                "Decoded output should match original input");

        System.out.println("✓ Round-trip successful");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
