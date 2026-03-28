package mrc;

import mrc.codec.CompressionResult;
import mrc.codec.EncodingTier;
import mrc.codec.MrcDecoder;
import mrc.codec.MrcEncoder;
import mrc.codec.MrcFormatException;
import mrc.graph.ArithmeticPattern;
import mrc.graph.SequenceDetector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark compression on 1 MB+ test input files.
 *
 * Uses the arithmetic run detection (v0x02) encoder.
 */
public class LargeInputBenchmarkTest {

    @Test
    public void testRandomBytes() throws IOException, MrcFormatException {
        byte[] input = TestInputs.randomBytes();
        testCompression("Random Bytes", input, 1.05, 1.20);
    }

    @Test
    public void testArithmeticSequence() throws IOException, MrcFormatException {
        byte[] input = TestInputs.arithmeticSequence();
        testCompression("Arithmetic Sequence", input, 0.00001, 0.15);
    }

    @Test
    public void testTextLike() throws IOException, MrcFormatException {
        byte[] input = TestInputs.textLike();
        testCompression("Text-Like Data", input, 0.50, 1.20);
    }

    @Test
    public void testRepetitive() throws IOException, MrcFormatException {
        byte[] input = TestInputs.repetitive();
        testCompression("Repetitive Data", input, 0.00001, 0.10);
    }

    private void testCompression(String name, byte[] input, double minRatio, double maxRatio)
            throws IOException, MrcFormatException {
        System.out.println("\n=== " + name + " ===");
        System.out.println("Input size:      " + formatBytes(input.length) + " (" + input.length + " bytes)");

        // --- Pattern detection ---
        long detectStart = System.nanoTime();
        SequenceDetector detector = new SequenceDetector();
        List<ArithmeticPattern> allPatterns = detector.detect(input);
        List<ArithmeticPattern> topPatterns = detector.topPatterns(input, 255);
        long detectMs = (System.nanoTime() - detectStart) / 1_000_000;

        System.out.println("Patterns found:  " + allPatterns.size());
        System.out.println("Patterns used:   " + topPatterns.size() + " (top-255)");
        System.out.println("Pattern detect:  " + detectMs + " ms");
        if (!topPatterns.isEmpty()) {
            System.out.println("Top 5 patterns by savings:");
            topPatterns.stream().limit(5).forEach(p ->
                System.out.printf("  %-14s runs=%,d  runBytes=%,d  savings=%,d bits%n",
                        p.label() + ":", p.runCount(), p.totalRunBytes(), p.estimatedSavingBits()));
        }

        // --- Encode ---
        long encodeStart = System.nanoTime();
        MrcEncoder encoder = new MrcEncoder(topPatterns);
        CompressionResult result = encoder.encode(input);
        long encodeMs = (System.nanoTime() - encodeStart) / 1_000_000;

        byte[] compressed = result.compressedData();
        System.out.println("Compressed size: " + formatBytes(compressed.length) + " (" + compressed.length + " bytes)");
        System.out.printf ("Ratio:           %.4f  (expected [%.4f, %.4f])%n", result.ratio(), minRatio, maxRatio);
        System.out.printf ("Space saving:    %.2f%%%n", result.spaceSaving() * 100);
        System.out.println("Encode time:     " + encodeMs + " ms  (" + throughput(input.length, encodeMs) + ")");

        long totalSymbols = result.tierUsageCounts().values().stream().mapToLong(Long::longValue).sum();
        System.out.println("Tier breakdown (symbols):");
        for (EncodingTier tier : EncodingTier.values()) {
            long count = result.tierUsageCounts().getOrDefault(tier, 0L);
            if (count == 0) continue;
            double pct = totalSymbols > 0 ? 100.0 * count / totalSymbols : 0;
            System.out.printf("  %-12s %,8d  (%.1f%%)%n", tier.name() + ":", count, pct);
        }

        // --- Decode ---
        long decodeStart = System.nanoTime();
        MrcDecoder decoder = new MrcDecoder();
        byte[] decoded;
        boolean decodeException = false;
        try {
            decoded = decoder.decode(compressed);
        } catch (Exception e) {
            System.out.println("Decode FAILED with exception: " + e.getMessage());
            decodeException = true;
            decoded = new byte[0];
        }
        long decodeMs = (System.nanoTime() - decodeStart) / 1_000_000;

        System.out.println("Decode time:     " + decodeMs + " ms  (" + throughput(input.length, decodeMs) + ")");

        boolean roundTripOk = !decodeException && Arrays.equals(input, decoded);
        System.out.println("Round-trip:      " + (roundTripOk ? "PASS" : "FAIL"));
        if (!roundTripOk && !decodeException) {
            int firstDiff = firstDiff(input, decoded);
            System.out.printf("  First diff at byte %d: expected 0x%02X, got 0x%02X%n",
                    firstDiff, input[firstDiff] & 0xFF,
                    firstDiff < decoded.length ? decoded[firstDiff] & 0xFF : -1);
            System.out.println("  Decoded length: " + decoded.length + " (expected " + input.length + ")");
        }

        final boolean ratioOk = result.ratio() >= minRatio && result.ratio() <= maxRatio;
        final boolean finalDecodeException = decodeException;
        final boolean finalRoundTripOk = roundTripOk;
        final double finalRatio = result.ratio();
        assertAll(
            () -> assertTrue(ratioOk,
                    String.format("Compression ratio %.4f outside expected range [%.4f, %.4f]",
                            finalRatio, minRatio, maxRatio)),
            () -> assertFalse(finalDecodeException, "Decoder threw an exception"),
            () -> assertTrue(finalRoundTripOk, "Decoded output does not match original input")
        );
    }

    private static int firstDiff(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) return i;
        }
        return len;
    }

    private static String throughput(long bytes, long ms) {
        if (ms <= 0) return "N/A";
        double mbps = (bytes / 1024.0 / 1024.0) / (ms / 1000.0);
        return String.format("%.1f MB/s", mbps);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
