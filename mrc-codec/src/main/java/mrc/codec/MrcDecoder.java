package mrc.codec;

import mrc.core.OpIdMap;
import mrc.core.Operator;
import mrc.core.OperatorLibrary;
import mrc.graph.CyclePath;
import mrc.graph.TransitionEdge;

import java.util.*;

/**
 * Lossless decoder for the MRC compression format.
 *
 * Supports:
 * - v0x01: LITERAL + RELATIONAL + CYCLE tiers (original cycle-based format)
 * - v0x02: LITERAL + ARITH_RUN tiers (arithmetic run format)
 */
public class MrcDecoder {
    private final OperatorLibrary lib;

    public MrcDecoder() {
        this.lib = OperatorLibrary.getInstance();
    }

    /**
     * Decode a compressed bitstream and return the original data.
     *
     * @param compressed the compressed bitstream
     * @return the decoded original data
     * @throws MrcFormatException if the bitstream format is invalid
     */
    public byte[] decode(byte[] compressed) throws MrcFormatException {
        BitStreamReader reader = new BitStreamReader(compressed);
        try {
            HeaderData header = readHeader(reader);
            return header.version == 0x02
                    ? decodeV2(reader, header)
                    : decodeV1(reader, header);
        } catch (MrcFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new MrcFormatException("Decoding failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // v0x01 decode
    // -------------------------------------------------------------------------

    private byte[] decodeV1(BitStreamReader reader, HeaderData header) throws MrcFormatException {
        List<CyclePath> cycles = header.cycles;
        int originalLength = header.originalLength;

        List<Byte> result = new ArrayList<>();
        int lastValue = 0;

        while (result.size() < originalLength && reader.hasMore()) {
            int flag = reader.readBit();

            if (flag == 0) {
                int value = reader.readByte();
                result.add((byte) value);
                lastValue = value;
            } else {
                int flag2 = reader.readBit();
                if (flag2 == 0) {
                    // RELATIONAL
                    byte opId = (byte) reader.readBits(5);
                    int operandBits = OpIdMap.getOperandBits(opId);
                    int operand = operandBits > 0 ? (int) reader.readBits(operandBits) : 0;
                    Operator op = lib.createOperator(opId, operand);
                    int nextValue = op.apply(lastValue) & 0xFF;
                    result.add((byte) nextValue);
                    lastValue = nextValue;
                } else {
                    int flag3 = reader.readBit();
                    if (flag3 == 0) {
                        // CYCLE
                        int indexBits = cycles.isEmpty() ? 1
                                : Math.max(1, 32 - Integer.numberOfLeadingZeros(cycles.size() - 1));
                        int cycleIndex = (int) reader.readBits(indexBits);
                        int repeatCount = (int) reader.readBits(16);
                        if (cycleIndex < cycles.size()) {
                            CyclePath cycle = cycles.get(cycleIndex);
                            for (int rep = 0; rep < repeatCount; rep++) {
                                for (int node : cycle.nodes()) {
                                    result.add((byte) node);
                                    lastValue = node;
                                }
                            }
                        }
                    }
                }
            }
        }

        byte[] output = new byte[Math.min(result.size(), originalLength)];
        for (int i = 0; i < output.length; i++) output[i] = result.get(i);
        return output;
    }

    // -------------------------------------------------------------------------
    // v0x02 decode
    // -------------------------------------------------------------------------

    private byte[] decodeV2(BitStreamReader reader, HeaderData header) throws MrcFormatException {
        int[] steps = header.steps;
        int originalLength = header.originalLength;

        byte[] output = new byte[originalLength];
        int pos = 0;

        while (pos < originalLength && reader.hasMore()) {
            int flag = reader.readBit();

            if (flag == 0) {
                // LITERAL: 8 bits
                int value = reader.readByte();
                output[pos++] = (byte) value;
            } else {
                // ARITH_RUN: 8-bit stepIdx + 8-bit startVal + 16-bit runLen
                int stepIdx  = reader.readByte();
                int startVal = reader.readByte();
                int runLen   = (int) reader.readBits(16);

                if (stepIdx >= steps.length) {
                    throw new MrcFormatException("ARITH_RUN stepIdx " + stepIdx
                            + " out of range (table size=" + steps.length + ")");
                }
                int step = steps[stepIdx];

                for (int j = 0; j < runLen && pos < originalLength; j++) {
                    output[pos++] = (byte) ((startVal + (long) j * step) & 0xFF);
                }
            }
        }

        return output;
    }

    // -------------------------------------------------------------------------
    // Header reading
    // -------------------------------------------------------------------------

    private static class HeaderData {
        int version;
        int originalLength;
        // v0x01
        List<CyclePath> cycles;
        // v0x02
        int[] steps;
    }

    private HeaderData readHeader(BitStreamReader reader) throws MrcFormatException {
        int m = reader.readByte();
        int r = reader.readByte();
        int c = reader.readByte();
        if (m != 0x4D || r != 0x52 || c != 0x43) {
            throw new MrcFormatException("Invalid magic bytes");
        }

        int version = reader.readByte();
        HeaderData hd = new HeaderData();
        hd.version = version;

        if (version == 0x01) {
            hd.cycles = readCycleTable(reader);
        } else if (version == 0x02) {
            hd.steps = readStepTable(reader);
        } else {
            throw new MrcFormatException("Unsupported version: " + version);
        }

        int len0 = reader.readByte();
        int len1 = reader.readByte();
        int len2 = reader.readByte();
        int len3 = reader.readByte();
        hd.originalLength = (len0 << 24) | (len1 << 16) | (len2 << 8) | len3;
        return hd;
    }

    private List<CyclePath> readCycleTable(BitStreamReader reader) throws MrcFormatException {
        int cycleCount = reader.readByte();
        List<CyclePath> cycles = new ArrayList<>();
        for (int i = 0; i < cycleCount; i++) {
            int length = reader.readByte();
            List<Integer> nodes = new ArrayList<>();
            for (int j = 0; j < length; j++) nodes.add(reader.readByte());

            List<TransitionEdge> edges = new ArrayList<>();
            for (int j = 0; j < length; j++) {
                byte opId = (byte) reader.readByte();
                int operandBits = OpIdMap.getOperandBits(opId);
                int operand = 0;
                if (operandBits > 0) {
                    operand = (int) reader.readBits(operandBits);
                }
                Operator op = lib.createOperator(opId, operand);
                int costBits = 5 + operandBits;
                TransitionEdge edge = new TransitionEdge(nodes.get(j),
                        nodes.get((j + 1) % length), op, costBits, 0, 0);
                edges.add(edge);
            }

            double totalCost = edges.stream().mapToDouble(TransitionEdge::costBits).sum();
            double gain = (length * 8.0) - totalCost;
            cycles.add(new CyclePath(nodes, edges, length, 0, gain));
        }
        return cycles;
    }

    private int[] readStepTable(BitStreamReader reader) throws MrcFormatException {
        int stepCount = reader.readByte();
        int[] steps = new int[stepCount];
        for (int i = 0; i < stepCount; i++) {
            steps[i] = reader.readByte();
        }
        return steps;
    }
}
