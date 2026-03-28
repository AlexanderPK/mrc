package mrc.bench;

import mrc.codec.CompressionResult;
import mrc.codec.MrcDecoder;
import mrc.codec.MrcEncoder;
import mrc.codec.MrcFormatException;
import mrc.graph.CycleDetector;
import mrc.graph.TransitionGraph;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Random;

/**
 * Validation test suite that runs various baseline tests on the MRC encoder/decoder.
 *
 * Tests include:
 * 1. Uniform random data (≤ 2% overhead expected)
 * 2. Linear congruential generator
 * 3. Mersenne Twister (java.util.Random)
 * 4. Gaussian quantized
 * 5. Arithmetic sequence (highly compressible)
 * 6. Sine wave (periodic structure)
 *
 * Each test encodes, decodes, verifies round-trip, and reports ratio.
 */
public class RandomBaselineSuite {

    private static final Path DEFAULT_EXPORT_DIR = Path.of(".tmp/xyz");

    /**
     * Run all baseline tests and report results.
     *
     * Exports a DOT graph for each test to {@code .tmp/xyz}.
     *
     * @param out the output stream for results
     */
    public static void runAll(PrintStream out) {
        runAll(out, DEFAULT_EXPORT_DIR);
    }

    /**
     * Run all baseline tests and report results, exporting DOT graphs to the given directory.
     *
     * @param out       the output stream for results
     * @param exportDir directory where DOT files are written (created if absent)
     */
    public static void runAll(PrintStream out, Path exportDir) {
        out.println("=== MRC Phase 1 Baseline Test Suite ===");
        out.println("Export dir: " + exportDir.toAbsolutePath());
        out.println();

        try {
            java.nio.file.Files.createDirectories(exportDir);
        } catch (IOException e) {
            out.println("WARNING: could not create export dir: " + e.getMessage());
        }

        try {
            testUniformRandom(out, 4096, exportDir);
            testLcgStream(out, 4096, exportDir);
            testMersenneTwister(out, 4096, exportDir);
            testGaussianQuantized(out, 4096, exportDir);
            testArithmeticSequence(out, 1024, 1, exportDir);
            testSineQuantized(out, 1024, 0.05, exportDir);
        } catch (MrcFormatException e) {
            out.println("ERROR: " + e.getMessage());
            e.printStackTrace(out);
        }

        out.println();
        out.println("=== All tests completed ===");
    }

    /**
     * Test uniform random data (should have minimal compression, ≤ 2% overhead).
     */
    private static void testUniformRandom(PrintStream out, int length, Path exportDir) throws MrcFormatException {
        out.println("Test 1: Uniform Random");
        byte[] data = new byte[length];
        new SecureRandom().nextBytes(data);
        runCompressionTest(out, data, "uniform_random_" + length, 1.02, exportDir);
    }

    /**
     * Test linear congruential generator (algebraic structure detectable).
     */
    private static void testLcgStream(PrintStream out, int length, Path exportDir) throws MrcFormatException {
        out.println("Test 2: LCG Stream");
        byte[] data = generateLcgStream(length);
        runCompressionTest(out, data, "lcg_" + length, 0.85, exportDir);
    }

    /**
     * Test Mersenne Twister (java.util.Random).
     */
    private static void testMersenneTwister(PrintStream out, int length, Path exportDir) throws MrcFormatException {
        out.println("Test 3: Mersenne Twister");
        byte[] data = new byte[length];
        Random rng = new Random(42);
        for (int i = 0; i < length; i++) {
            data[i] = (byte) rng.nextInt(256);
        }
        runCompressionTest(out, data, "mersenne_" + length, 1.02, exportDir);
    }

    /**
     * Test Gaussian quantized data (clustered transitions).
     */
    private static void testGaussianQuantized(PrintStream out, int length, Path exportDir) throws MrcFormatException {
        out.println("Test 4: Gaussian Quantized");
        byte[] data = generateGaussianQuantized(length, 128, 30);
        runCompressionTest(out, data, "gaussian_" + length, 0.75, exportDir);
    }

    /**
     * Test arithmetic sequence (highly compressible).
     */
    private static void testArithmeticSequence(PrintStream out, int length, int delta, Path exportDir) throws MrcFormatException {
        out.println("Test 5: Arithmetic Sequence (delta=" + delta + ")");
        byte[] data = generateArithmeticSequence(length, delta);
        runCompressionTest(out, data, "arithmetic_" + length + "_d" + delta, 0.30, exportDir);
    }

    /**
     * Test sine wave (periodic structure).
     */
    private static void testSineQuantized(PrintStream out, int length, double freq, Path exportDir) throws MrcFormatException {
        out.println("Test 6: Sine Quantized (freq=" + freq + ")");
        byte[] data = generateSineQuantized(length, freq);
        runCompressionTest(out, data, "sine_" + length + "_f" + freq, 0.50, exportDir);
    }

    /**
     * Run a compression test: encode, decode, verify, report.
     *
     * TODO: Implement full test with round-trip verification and assertion checks.
     */
    private static void runCompressionTest(PrintStream out, byte[] data, String testName,
                                           double expectedMaxRatio, Path exportDir) throws MrcFormatException {
        try {
            // Build graph
            TransitionGraph graph = new TransitionGraph();
            graph.observe(data);
            graph.setExportDir(exportDir);

            // Export DOT graph
            try {
                Path dotFile = exportDir.resolve(testName + ".dot");
                graph.exportDot(dotFile);
            } catch (IOException e) {
                out.println("  [WARN] DOT export failed: " + e.getMessage());
            }

            // Detect cycles
            CycleDetector detector = new CycleDetector(graph, 8);
            detector.findAllCycles();

            // Encode
            MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
            CompressionResult result = encoder.encode(data);
            byte[] compressed = result.compressedData();

            // Decode
            MrcDecoder decoder = new MrcDecoder();
            byte[] decoded = decoder.decode(compressed);

            // Verify round-trip
            if (!java.util.Arrays.equals(data, decoded)) {
                out.println("  [FAIL] Round-trip verification failed!");
                return;
            }

            // Report
            double ratio = result.ratio();
            double saving = result.spaceSaving() * 100;
            String status = ratio <= expectedMaxRatio ? "PASS" : "WARN";
            out.println(String.format("  [%s] ratio=%.4f, saving=%.2f%%, expected <= %.4f",
                    status, ratio, saving, expectedMaxRatio));
        } catch (Exception e) {
            out.println("  [ERROR] " + e.getMessage());
            e.printStackTrace(out);
        }
    }

    private static byte[] generateLcgStream(int length) {
        byte[] data = new byte[length];
        long x = 1;  // seed
        for (int i = 0; i < length; i++) {
            x = (1664525L * x + 1013904223L) & 0xFFFFFFFFL;
            data[i] = (byte) (x & 0xFF);
        }
        return data;
    }

    private static byte[] generateGaussianQuantized(int length, int mean, int stdDev) {
        byte[] data = new byte[length];
        Random rng = new Random(42);
        for (int i = 0; i < length; i++) {
            double gauss = rng.nextGaussian() * stdDev + mean;
            int quantized = (int) Math.round(gauss);
            data[i] = (byte) (Math.max(0, Math.min(255, quantized)) & 0xFF);
        }
        return data;
    }

    private static byte[] generateArithmeticSequence(int length, int delta) {
        byte[] data = new byte[length];
        int value = 0;
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (value & 0xFF);
            value = (value + delta) & 0xFF;
        }
        return data;
    }

    private static byte[] generateSineQuantized(int length, double freq) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            double sine = 127.5 + 127.5 * Math.sin(2 * Math.PI * freq * i);
            data[i] = (byte) ((int) Math.round(sine) & 0xFF);
        }
        return data;
    }
}
