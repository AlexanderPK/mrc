package mrc.codec;

import mrc.core.*;
import mrc.graph.ArithmeticPattern;
import mrc.graph.CyclePath;
import mrc.graph.SequenceDetector;
import mrc.graph.TransitionEdge;
import mrc.graph.TransitionGraph;

import java.util.*;

/**
 * Lossless encoder for the MRC compression format.
 *
 * Supports two format versions:
 * - v0x01: LITERAL + RELATIONAL tiers, cycle table in header (original)
 * - v0x02: LITERAL + ARITH_RUN tiers, arithmetic step table in header
 *
 * Use {@link #MrcEncoder(TransitionGraph, List)} for v0x01 (cycle-based).
 * Use {@link #MrcEncoder(List)} for v0x02 (arithmetic run-based).
 */
public class MrcEncoder {

    // ---- v0x01 fields ----
    private final TransitionGraph graph;
    private final List<CyclePath> cycles;
    private final Map<Integer, List<CyclePath>> cyclesByNode;
    private final OperatorLibrary lib;

    // ---- v0x02 fields ----
    private final List<ArithmeticPattern> patterns;
    /** Map from step value (0..255) → index in patterns list (for ARITH_RUN tokens). */
    private final Map<Integer, Integer> stepToIndex;

    private final boolean useV2;

    /**
     * Construct a v0x01 encoder with a graph and its detected cycles.
     */
    public MrcEncoder(TransitionGraph graph, List<CyclePath> cycles) {
        this.graph = graph;
        this.cycles = cycles;
        this.cyclesByNode = buildCycleIndex(cycles);
        this.lib = OperatorLibrary.getInstance();
        this.patterns = Collections.emptyList();
        this.stepToIndex = Collections.emptyMap();
        this.useV2 = false;
    }

    /**
     * Construct a v0x02 encoder with a list of arithmetic patterns.
     *
     * @param patterns arithmetic run patterns to encode (up to 255)
     */
    public MrcEncoder(List<ArithmeticPattern> patterns) {
        this.graph = null;
        this.cycles = Collections.emptyList();
        this.cyclesByNode = Collections.emptyMap();
        this.lib = OperatorLibrary.getInstance();
        this.patterns = patterns.size() > 255 ? patterns.subList(0, 255) : patterns;
        this.stepToIndex = buildStepIndex(this.patterns);
        this.useV2 = true;
    }

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

    private Map<Integer, Integer> buildStepIndex(List<ArithmeticPattern> patterns) {
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < patterns.size(); i++) {
            index.put(patterns.get(i).step(), i);
        }
        return index;
    }

    /**
     * Encode the input data and return compression metrics.
     */
    public CompressionResult encode(byte[] input) {
        return useV2 ? encodeV2(input) : encodeV1(input);
    }

    // -------------------------------------------------------------------------
    // v0x01 encoding
    // -------------------------------------------------------------------------

    private CompressionResult encodeV1(byte[] input) {
        long startTime = System.nanoTime();
        BitStreamWriter writer = new BitStreamWriter();
        writeHeaderV1(writer, input.length);

        Map<EncodingTier, Long> tierCounts = new HashMap<>();
        int lastValue = 0;

        for (int i = 0; i < input.length; i++) {
            int currentValue = input[i] & 0xFF;

            // Try CYCLE tier first (if we have matching cycles and data to repeat)
            CycleMatch cycleMatch = tryCycle(input, i, currentValue);
            if (cycleMatch != null) {
                int cycleIndex = findCycleIndex(cycleMatch.cycle);
                if (cycleIndex >= 0) {
                    writeCycleToken(writer, cycleIndex, cycleMatch.repeatCount);
                    tierCounts.merge(EncodingTier.CYCLE, 1L, Long::sum);
                    // Skip through the matched cycle data
                    int cycleLen = cycleMatch.cycle.nodes().size();
                    i += (cycleLen * cycleMatch.repeatCount) - 1;
                    lastValue = input[Math.min(i, input.length - 1)] & 0xFF;
                    continue;
                }
            }

            // Fall back to RELATIONAL
            if (i > 0 && tryRelational(writer, lastValue, currentValue)) {
                tierCounts.merge(EncodingTier.RELATIONAL, 1L, Long::sum);
            } else {
                writeLiteralTokenV1(writer, currentValue);
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

        return new CompressionResult(input, compressed, originalBits, compressedBits,
                ratio, 1.0 - ratio, tierCounts, new ArrayList<>(),
                endTime - startTime, 0L);
    }

    /**
     * Helper class to return cycle match results.
     */
    private static class CycleMatch {
        final CyclePath cycle;
        final int repeatCount;

        CycleMatch(CyclePath cycle, int repeatCount) {
            this.cycle = cycle;
            this.repeatCount = repeatCount;
        }
    }

    /**
     * Try to match a repeating cycle starting at the given position.
     *
     * @param input the input data
     * @param startPos current position in input
     * @param currentValue the value at startPos
     * @return a CycleMatch if a cycle matches and repeats at least once, or null
     */
    private CycleMatch tryCycle(byte[] input, int startPos, int currentValue) {
        List<CyclePath> matchingCycles = cyclesByNode.get(currentValue);
        if (matchingCycles == null || matchingCycles.isEmpty()) {
            return null;
        }

        for (CyclePath cycle : matchingCycles) {
            List<Integer> nodes = cycle.nodes();
            int cycleLen = nodes.size();

            // Check if the next (cycleLen * 2) bytes match at least 2 complete cycles
            int repeatCount = 0;
            int pos = startPos;
            while (repeatCount < 65535 && pos + (cycleLen * (repeatCount + 1)) <= input.length) {
                boolean matches = true;
                for (int j = 0; j < cycleLen; j++) {
                    int expectedNode = nodes.get(j);
                    int actualValue = input[pos + (repeatCount * cycleLen) + j] & 0xFF;
                    if (expectedNode != actualValue) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    repeatCount++;
                } else {
                    break;
                }
            }

            // Only use CYCLE tier if we have at least 2 repeats (cost saving)
            if (repeatCount >= 2) {
                int indexBits = cycles.isEmpty() ? 1
                        : Math.max(1, 32 - Integer.numberOfLeadingZeros(cycles.size() - 1));
                int cycleCost = 3 + indexBits + 16; // flag + cycleIndex + repeatCount
                int directCost = cycleLen * repeatCount * 9; // worst case: all LITERAL (9 bits each)
                if (cycleCost < directCost) {
                    return new CycleMatch(cycle, repeatCount);
                }
            }
        }
        return null;
    }

    /**
     * Find the index of a cycle in the cycles list.
     */
    private int findCycleIndex(CyclePath target) {
        for (int i = 0; i < cycles.size(); i++) {
            if (cycles.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Write a CYCLE token: flag=1, flag2=1, flag3=0, then cycleIndex and repeatCount.
     */
    private void writeCycleToken(BitStreamWriter writer, int cycleIndex, int repeatCount) {
        writer.writeBit(1);
        writer.writeBit(1);
        writer.writeBit(0);
        int indexBits = cycles.isEmpty() ? 1
                : Math.max(1, 32 - Integer.numberOfLeadingZeros(cycles.size() - 1));
        writer.writeBits(cycleIndex, indexBits);
        writer.writeBits(repeatCount, 16);
    }

    private boolean tryRelational(BitStreamWriter writer, int from, int to) {
        // Check graph edge first (chromosome-assigned operator), then fall back to library default
        Operator bestOp = null;
        int bestCost = Integer.MAX_VALUE;

        if (graph != null) {
            Optional<TransitionEdge> graphEdge = graph.bestEdge(from, to);
            if (graphEdge.isPresent()) {
                Operator op = graphEdge.get().op();
                int cost = 5 + op.operandBits();
                if (cost < bestCost) {
                    bestCost = cost;
                    bestOp = op;
                }
            }
        }

        Optional<Operator> libOp = lib.findShortest(from, to);
        if (libOp.isPresent()) {
            int cost = 5 + libOp.get().operandBits();
            if (cost < bestCost) {
                bestCost = cost;
                bestOp = libOp.get();
            }
        }

        if (bestOp != null && bestCost < 8) {
            writeRelationalToken(writer, bestOp);
            return true;
        }
        return false;
    }

    private void writeLiteralTokenV1(BitStreamWriter writer, int value) {
        writer.writeBit(0);
        writer.writeByte(value & 0xFF);
    }

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

    private int extractOperand(Operator op) {
        return switch (op) {
            case Add a    -> a.operand();
            case Sub s    -> s.operand();
            case Mul m    -> m.operand();
            case Div d    -> d.operand();
            case Mod m    -> m.operand();
            case XorOp x  -> x.operand();
            case AndOp a  -> a.operand();
            case OrOp o   -> o.operand();
            case ShiftLeft sl  -> sl.bits();
            case ShiftRight sr -> sr.bits();
            case Not n      -> 0;
            case Inc i      -> 0;
            case Dec d      -> 0;
            case Identity id -> 0;
            default         -> 0;
        };
    }

    private void writeHeaderV1(BitStreamWriter writer, int originalLength) {
        writer.writeByte(0x4D); // 'M'
        writer.writeByte(0x52); // 'R'
        writer.writeByte(0x43); // 'C'
        writer.writeByte(0x01); // version

        int cycleCount = Math.min(cycles.size(), 255);
        writer.writeByte(cycleCount);

        for (int i = 0; i < cycleCount; i++) {
            CyclePath cycle = cycles.get(i);
            List<Integer> nodes = cycle.nodes();
            List<TransitionEdge> edges = cycle.edges();
            writer.writeByte(nodes.size());
            for (int node : nodes) writer.writeByte(node);
            for (TransitionEdge edge : edges) {
                byte opId = OpIdMap.getOpId(edge.op());
                writer.writeByte(opId);
                // Write operand bits for this operator
                int operandBits = OpIdMap.getOperandBits(opId);
                if (operandBits > 0) {
                    int operand = extractOperand(edge.op());
                    writer.writeBits(operand & ((1 << operandBits) - 1), operandBits);
                }
            }
        }

        writer.writeByte((originalLength >> 24) & 0xFF);
        writer.writeByte((originalLength >> 16) & 0xFF);
        writer.writeByte((originalLength >> 8) & 0xFF);
        writer.writeByte(originalLength & 0xFF);
    }

    // -------------------------------------------------------------------------
    // v0x02 encoding
    // -------------------------------------------------------------------------

    private CompressionResult encodeV2(byte[] input) {
        long startTime = System.nanoTime();
        BitStreamWriter writer = new BitStreamWriter();
        writeHeaderV2(writer, input.length);

        Map<EncodingTier, Long> tierCounts = new HashMap<>();

        int i = 0;
        while (i < input.length) {
            int runLen = 0;
            int runStep = -1;

            if (i + 1 < input.length) {
                int step = ((input[i + 1] & 0xFF) - (input[i] & 0xFF)) & 0xFF;
                if (stepToIndex.containsKey(step)) {
                    // Measure run length
                    int j = i + 1;
                    while (j + 1 < input.length
                            && (((input[j + 1] & 0xFF) - (input[j] & 0xFF)) & 0xFF) == step) {
                        j++;
                    }
                    int len = j - i + 1;
                    if (len >= SequenceDetector.MIN_RUN) {
                        runLen = len;
                        runStep = step;
                    }
                }
            }

            if (runLen >= SequenceDetector.MIN_RUN) {
                // Emit one or more ARITH_RUN tokens (split at 65535)
                int stepIdx = stepToIndex.get(runStep);
                int startVal = input[i] & 0xFF;
                int remaining = runLen;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, 65535);
                    writeArithRunToken(writer, stepIdx, startVal, chunk);
                    tierCounts.merge(EncodingTier.ARITH_RUN, 1L, Long::sum);
                    startVal = (int) ((startVal + (long) chunk * runStep) & 0xFF);
                    remaining -= chunk;
                }
                i += runLen;
            } else {
                writeLiteralTokenV2(writer, input[i] & 0xFF);
                tierCounts.merge(EncodingTier.LITERAL, 1L, Long::sum);
                i++;
            }
        }

        writer.flush();
        byte[] compressed = writer.toByteArray();
        long endTime = System.nanoTime();

        int originalBits = input.length * 8;
        int compressedBits = compressed.length * 8;
        double ratio = (double) compressedBits / originalBits;

        return new CompressionResult(input, compressed, originalBits, compressedBits,
                ratio, 1.0 - ratio, tierCounts, new ArrayList<>(),
                endTime - startTime, 0L);
    }

    /** v0x02: LITERAL is flag=0 + 8 bits. */
    private void writeLiteralTokenV2(BitStreamWriter writer, int value) {
        writer.writeBit(0);
        writer.writeByte(value & 0xFF);
    }

    /** v0x02: ARITH_RUN is flag=1 + 8-bit stepIdx + 8-bit startVal + 16-bit runLen. */
    private void writeArithRunToken(BitStreamWriter writer, int stepIdx, int startVal, int runLen) {
        writer.writeBit(1);
        writer.writeByte(stepIdx & 0xFF);
        writer.writeByte(startVal & 0xFF);
        writer.writeBits(runLen, 16);
    }

    private void writeHeaderV2(BitStreamWriter writer, int originalLength) {
        writer.writeByte(0x4D); // 'M'
        writer.writeByte(0x52); // 'R'
        writer.writeByte(0x43); // 'C'
        writer.writeByte(0x02); // version

        int stepCount = patterns.size(); // already capped at 255
        writer.writeByte(stepCount);
        for (ArithmeticPattern p : patterns) {
            writer.writeByte(p.step() & 0xFF);
        }

        writer.writeByte((originalLength >> 24) & 0xFF);
        writer.writeByte((originalLength >> 16) & 0xFF);
        writer.writeByte((originalLength >> 8) & 0xFF);
        writer.writeByte(originalLength & 0xFF);
    }
}
