package mrc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility for loading test input files for compression benchmarking.
 */
public class TestInputs {
    private static final String TEST_INPUT_DIR = "src/test/resources/test-inputs";

    /**
     * Load random bytes test input (500 KB).
     *
     * Completely random bytes - incompressible data.
     *
     * @return byte array of random data
     * @throws IOException if file cannot be read
     */
    public static byte[] randomBytes() throws IOException {
        return loadFile("random-500kb.bin");
    }

    /**
     * Load arithmetic sequence test input (400 KB).
     *
     * Repeating arithmetic sequence (10→13→16→19→...) - highly compressible.
     *
     * @return byte array of arithmetic sequence
     * @throws IOException if file cannot be read
     */
    public static byte[] arithmeticSequence() throws IOException {
        return loadFile("arithmetic-400kb.bin");
    }

    /**
     * Load text-like test input (300 KB).
     *
     * ASCII-like characters simulating natural text - moderately compressible.
     *
     * @return byte array of text-like data
     * @throws IOException if file cannot be read
     */
    public static byte[] textLike() throws IOException {
        return loadFile("text-like-300kb.bin");
    }

    /**
     * Load repetitive test input (100 KB).
     *
     * Single repeated byte - maximally compressible.
     *
     * @return byte array of repetitive data
     * @throws IOException if file cannot be read
     */
    public static byte[] repetitive() throws IOException {
        return loadFile("repetitive-100kb.bin");
    }

    /**
     * Load a test input file by name.
     *
     * @param filename the filename relative to test-inputs directory
     * @return byte array contents
     * @throws IOException if file cannot be read
     */
    public static byte[] loadFile(String filename) throws IOException {
        Path path = Paths.get(TEST_INPUT_DIR, filename);
        if (!Files.exists(path)) {
            throw new IOException("Test input file not found: " + path.toAbsolutePath());
        }
        return Files.readAllBytes(path);
    }

    /**
     * Get size of a test input file in bytes.
     *
     * @param filename the filename relative to test-inputs directory
     * @return file size in bytes
     * @throws IOException if file cannot be accessed
     */
    public static long getFileSize(String filename) throws IOException {
        Path path = Paths.get(TEST_INPUT_DIR, filename);
        if (!Files.exists(path)) {
            throw new IOException("Test input file not found: " + path.toAbsolutePath());
        }
        return Files.size(path);
    }
}
