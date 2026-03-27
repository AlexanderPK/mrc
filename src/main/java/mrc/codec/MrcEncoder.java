package mrc.codec;

import mrc.core.*;
import mrc.graph.CyclePath;
import mrc.graph.TransitionEdge;
import mrc.graph.TransitionGraph;

import java.util.*;

/**
 * Lossless encoder for the MRC compression format.
 *
 * Uses a per-value state machine to track active cycles and relational chains,
 * encoding each transition as LITERAL, RELATIONAL, or CYCLE tier.
 * Writes a header containing magic bytes, version, and cycle table before data.
 */
public class MrcEncoder {
    private final TransitionGraph graph;
    private final List<CyclePath> cycles;
    private final Map<Integer, List<CyclePath>> cyclesByNode;
    private final OperatorLibrary lib;

    /**
     * Construct an encoder with a graph and its detected cycles.
     *
     * @param graph the TransitionGraph
     * @param cycles the list of detected cycles (ordered by compression gain)
     */
    public MrcEncoder(TransitionGraph graph, List<CyclePath> cycles) {
        this.graph = graph;
        this.cycles = cycles;
        this.cyclesByNode = buildCycleIndex(cycles);
        this.lib = OperatorLibrary.getInstance();
    }

    /**
     * Build an index of cycles by starting node.
     */
    private Map<Integer, List<CyclePath>> buildCycleIndex(List<CyclePath> cycles) {
        Map<Integer, List<CyclePath>> index = new HashMap<>();
        for (CyclePath cycle : cycles) {
            if (!cycle.nodes().isEmpty()) {
                int startNode = cycle.nodes().get(0);
                index.computeIfAbsent(startNode, k -> new ArrayList<>()).add(cycle);
            }
        }
        return index;
    }

    /**
     * Encode the input data and return compression metrics.
     *
     * Implements the encoder state machine:
     * 1. Write header (magic, version, cycle count, cycle table)
     * 2. For each input byte:
     *    a. Check if active cycle continues (same sequence)
     *    b. Try to start a new high-weight cycle
     *    c. Try relational encoding (operator from previous)
     *    d. Fall back to literal
     * 3. Calculate compression gain and tier usage counts
     *
     * @param input the data to compress
     * @return CompressionResult with metrics
     */
    public CompressionResult encode(byte[] input) {
        long startTime = System.nanoTime();

        BitStreamWriter writer = new BitStreamWriter();

        // Write header
        writeHeader(writer, input.length);

        // Encoding state machine (simplified: no cycle support for now)
        Map<EncodingTier, Long> tierCounts = new HashMap<>();
        int lastValue = 0;

        for (int i = 0; i < input.length; i++) {
            int currentValue = input[i] & 0xFF;

            // Try relational encoding first
            if (i > 0 && tryRelational(writer, lastValue, currentValue)) {
                tierCounts.merge(EncodingTier.RELATIONAL, 1L, Long::sum);
            } else {
                // Fall back to literal
                writeLiteralToken(writer, currentValue);
                tierCounts.merge(EncodingTier.LITERAL, 1L, Long::sum);
            }

            lastValue = currentValue;
        }

        writer.flush();
        byte[] compressed = writer.toByteArray();

        long endTime = System.nanoTime();

        int originalBits = input.length * 8;
        int compressedBits = compressed.length * 8;
        double ratio = (double) compressedBits / originalBits;
        double spaceSaving = 1.0 - ratio;

        return new CompressionResult(
                input,
                compressed,
                originalBits,
                compressedBits,
                ratio,
                spaceSaving,
                tierCounts,
                new ArrayList<>(),  // cyclesUsed
                endTime - startTime,
                0L  // decodingNanos not known yet
        );
    }

    /**
     * Try to encode a relational transition and write to stream if successful.
     *
     * @param writer the BitStreamWriter
     * @param from the previous value
     * @param to the target value
     * @return true if relational encoding was possible
     */
    private boolean tryRelational(BitStreamWriter writer, int from, int to) {
        Optional<Operator> optOp = lib.findShortest(from, to);
        if (optOp.isPresent()) {
            Operator op = optOp.get();
            int cost = 5 + op.operandBits();
            if (cost < 8) {  // Only relational if compressing
                writeRelationalToken(writer, op);
                return true;
            }
        }
        return false;
    }

    /**
     * Write a LITERAL tier token: 0 flag + 8-bit value.
     */
    private void writeLiteralToken(BitStreamWriter writer, int value) {
        writer.writeBit(0);
        writer.writeByte(value & 0xFF);
    }

    /**
     * Write a RELATIONAL tier token: 10 flag + 5-bit opId + operand bits.
     */
    private void writeRelationalToken(BitStreamWriter writer, Operator op) {
        writer.writeBit(1);
        writer.writeBit(0);

        byte opId = OpIdMap.getOpId(op);
        writer.writeBits(opId, 5);

        int operandBits = op.operandBits();
        if (operandBits > 0) {
            int operand = extractOperand(op);
            writer.writeBits(operand & ((1 << operandBits) - 1), operandBits);
        }
    }

    /**
     * Extract operand value from an operator using pattern matching.
     */
    private int extractOperand(Operator op) {
        return switch (op) {
            case Add a -> a.operand();
            case Sub s -> s.operand();
            case Mul m -> m.operand();
            case Div d -> d.operand();
            case Mod m -> m.operand();
            case XorOp x -> x.operand();
            case AndOp a -> a.operand();
            case OrOp o -> o.operand();
            case ShiftLeft sl -> sl.bits();
            case ShiftRight sr -> sr.bits();
            case Not n -> 0;
            default -> 0;
        };
    }

    /**
     * Write a CYCLE tier token: 110 flag + cycle index + 16-bit repeat count.
     */
    private void writeCycleToken(BitStreamWriter writer, int cycleIndex, int repeatCount) {
        writer.writeBit(1);
        writer.writeBit(1);
        writer.writeBit(0);

        // Write cycle index
        int indexBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(cycles.size() - 1));
        writer.writeBits(cycleIndex, indexBits);

        // Write 16-bit repeat count
        writer.writeBits(repeatCount, 16);
    }

    /**
     * Write the bitstream header.
     *
     * Format:
     * - 3 magic bytes: 0x4D 0x52 0x43 ("MRC")
     * - 1 version byte: 0x01
     * - 1 cycle count byte (0..255)
     * - For each cycle:
     *   - 1 length byte
     *   - length bytes: node values
     *   - length bytes: opIds (one per edge)
     * - 4 original data length bytes (big-endian int)
     *
     * @param writer the BitStreamWriter
     * @param originalLength the original data length
     */
    private void writeHeader(BitStreamWriter writer, int originalLength) {
        // Magic bytes
        writer.writeByte(0x4D);  // 'M'
        writer.writeByte(0x52);  // 'R'
        writer.writeByte(0x43);  // 'C'

        // Version
        writer.writeByte(0x01);

        // Cycle count (limited to 255)
        int cycleCount = Math.min(cycles.size(), 255);
        writer.writeByte(cycleCount);

        // Write cycle table
        for (int i = 0; i < cycleCount; i++) {
            CyclePath cycle = cycles.get(i);
            List<Integer> nodes = cycle.nodes();
            List<TransitionEdge> edges = cycle.edges();

            // Length byte
            writer.writeByte(nodes.size());

            // Node values
            for (int node : nodes) {
                writer.writeByte(node);
            }

            // OpIds from edges
            for (TransitionEdge edge : edges) {
                Operator op = edge.op();
                byte opId = OpIdMap.getOpId(op);
                writer.writeByte(opId);
            }
        }

        // Original data length (4 bytes, big-endian)
        writer.writeByte((originalLength >> 24) & 0xFF);
        writer.writeByte((originalLength >> 16) & 0xFF);
        writer.writeByte((originalLength >> 8) & 0xFF);
        writer.writeByte(originalLength & 0xFF);
    }
}
