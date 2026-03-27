package mrc.codec;

import mrc.graph.CycleDetector;
import mrc.graph.TransitionGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

/**
 * JUnit 5 parameterized round-trip tests for MRC encoder/decoder.
 *
 * Verifies that encode(decode(data)) == data for various input patterns.
 */
public class RoundTripTest {

    /**
     * Parameterized test: verify round-trip for all provided streams.
     */
    @ParameterizedTest
    @MethodSource("streamProvider")
    void roundTrip_decodedMatchesOriginal(byte[] input) throws MrcFormatException {
        // Build graph from input
        TransitionGraph graph = new TransitionGraph();
        graph.observe(input);

        // Find cycles
        CycleDetector detector = new CycleDetector(graph, 8);
        detector.findAllCycles();

        // Encode
        MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
        CompressionResult result = encoder.encode(input);
        byte[] compressed = result.compressedData();

        // Decode
        MrcDecoder decoder = new MrcDecoder();
        byte[] decoded = decoder.decode(compressed);

        // Verify round-trip
        assertArrayEquals(input, decoded, "Decoded data should match original");

        // Log ratio
        System.out.println(String.format("Input size: %d bytes, compressed: %d bytes, ratio: %.4f",
                input.length, compressed.length, result.ratio()));
    }

    /**
     * Provide various test streams for round-trip testing.
     */
    private static Stream<byte[]> streamProvider() {
        return Stream.of(
                allZeros(),
                allSameValue(),
                counting0To255Repeated4x(),
                random1024Bytes(),
                alternatingTwoValues(),
                sineWave512Bytes()
        );
    }

    private static byte[] allZeros() {
        byte[] data = new byte[256];
        Arrays.fill(data, (byte) 0);
        return data;
    }

    private static byte[] allSameValue() {
        byte[] data = new byte[256];
        Arrays.fill(data, (byte) 137);
        return data;
    }

    private static byte[] counting0To255Repeated4x() {
        byte[] data = new byte[256 * 4];
        for (int rep = 0; rep < 4; rep++) {
            for (int i = 0; i < 256; i++) {
                data[rep * 256 + i] = (byte) i;
            }
        }
        return data;
    }

    private static byte[] random1024Bytes() {
        byte[] data = new byte[1024];
        new Random(0).nextBytes(data);
        return data;
    }

    private static byte[] alternatingTwoValues() {
        byte[] data = new byte[512];
        for (int i = 0; i < 512; i++) {
            data[i] = (byte) ((i & 1) == 0 ? 50 : 100);
        }
        return data;
    }

    private static byte[] sineWave512Bytes() {
        byte[] data = new byte[512];
        for (int i = 0; i < 512; i++) {
            double sine = 127.5 + 127.5 * Math.sin(2 * Math.PI * 0.05 * i);
            data[i] = (byte) ((int) Math.round(sine) & 0xFF);
        }
        return data;
    }

    @Test
    void roundTrip_emptyInput() throws MrcFormatException {
        byte[] input = new byte[0];

        TransitionGraph graph = new TransitionGraph();
        graph.observe(input);

        CycleDetector detector = new CycleDetector(graph, 8);
        MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
        CompressionResult result = encoder.encode(input);

        MrcDecoder decoder = new MrcDecoder();
        byte[] decoded = decoder.decode(result.compressedData());

        assertArrayEquals(input, decoded);
    }

    @Test
    void roundTrip_singleByte() throws MrcFormatException {
        byte[] input = {42};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(input);

        CycleDetector detector = new CycleDetector(graph, 8);
        MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
        CompressionResult result = encoder.encode(input);

        MrcDecoder decoder = new MrcDecoder();
        byte[] decoded = decoder.decode(result.compressedData());

        assertArrayEquals(input, decoded);
    }

    @Test
    void roundTrip_twoBytes() throws MrcFormatException {
        byte[] input = {10, 20};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(input);

        CycleDetector detector = new CycleDetector(graph, 8);
        MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
        CompressionResult result = encoder.encode(input);

        MrcDecoder decoder = new MrcDecoder();
        byte[] decoded = decoder.decode(result.compressedData());

        assertArrayEquals(input, decoded);
    }
}
