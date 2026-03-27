package mrc.bench;

import mrc.codec.MrcDecoder;
import mrc.codec.MrcEncoder;
import mrc.codec.MrcFormatException;
import mrc.graph.CycleDetector;
import mrc.graph.TransitionGraph;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark suite for the MRC encoder and decoder.
 *
 * Measures throughput (MB/s) for various workloads:
 * - Encoding uniform random data
 * - Encoding arithmetic sequences
 * - Encoding sine-quantized periodic data
 * - Graph building
 * - Cycle detection
 *
 * Run with: mvn clean test -DskipTests && mvn exec:java -Dexec.mainClass="mrc.bench.CompressionBenchmark"
 * (Note: requires JMH plugin configuration)
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class CompressionBenchmark {

    private byte[] uniformRandomData;
    private byte[] arithmeticSequenceData;
    private byte[] sineQuantizedData;

    @Setup
    public void setup() {
        // Generate 1MB test data for each category
        uniformRandomData = generateUniformRandom(1024 * 1024);
        arithmeticSequenceData = generateArithmeticSequence(1024 * 1024, 1);
        sineQuantizedData = generateSineQuantized(1024 * 1024, 0.05);
    }

    /**
     * Benchmark encoding of uniform random data.
     */
    @Benchmark
    public byte[] encodeUniformRandom_1MB() throws MrcFormatException {
        TransitionGraph graph = new TransitionGraph();
        graph.observe(uniformRandomData);
        CycleDetector detector = new CycleDetector(graph, 8);
        MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
        return encoder.encode(uniformRandomData).compressedData();
    }

    /**
     * Benchmark encoding of arithmetic sequence.
     */
    @Benchmark
    public byte[] encodeArithmeticSequence_1MB() throws MrcFormatException {
        TransitionGraph graph = new TransitionGraph();
        graph.observe(arithmeticSequenceData);
        CycleDetector detector = new CycleDetector(graph, 8);
        MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
        return encoder.encode(arithmeticSequenceData).compressedData();
    }

    /**
     * Benchmark encoding of sine-quantized data.
     */
    @Benchmark
    public byte[] encodeSineQuantized_1MB() throws MrcFormatException {
        TransitionGraph graph = new TransitionGraph();
        graph.observe(sineQuantizedData);
        CycleDetector detector = new CycleDetector(graph, 8);
        MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
        return encoder.encode(sineQuantizedData).compressedData();
    }

    /**
     * Benchmark TransitionGraph building on 10MB data.
     */
    @Benchmark
    public TransitionGraph graphBuild_10MB() {
        byte[] data = generateUniformRandom(10 * 1024 * 1024);
        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);
        return graph;
    }

    /**
     * Benchmark cycle detection on a fully-built graph.
     */
    @Benchmark
    public void cycleDetection_fullGraph() {
        TransitionGraph graph = new TransitionGraph();
        graph.observe(arithmeticSequenceData);
        CycleDetector detector = new CycleDetector(graph, 8);
        detector.findAllCycles();
    }

    private byte[] generateUniformRandom(int length) {
        byte[] data = new byte[length];
        new Random(42).nextBytes(data);
        return data;
    }

    private byte[] generateArithmeticSequence(int length, int delta) {
        byte[] data = new byte[length];
        int value = 0;
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (value & 0xFF);
            value = (value + delta) & 0xFF;
        }
        return data;
    }

    private byte[] generateSineQuantized(int length, double freq) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            double sine = 127.5 + 127.5 * Math.sin(2 * Math.PI * freq * i);
            data[i] = (byte) ((int) Math.round(sine) & 0xFF);
        }
        return data;
    }
}
