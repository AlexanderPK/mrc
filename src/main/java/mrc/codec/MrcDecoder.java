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
 * Reads the bitstream header to reconstruct the cycle table, then decodes
 * tier-encoded values using prefix-free flag detection.
 */
public class MrcDecoder {
    private final OperatorLibrary lib;

    /**
     * Construct a stateless MrcDecoder.
     */
    public MrcDecoder() {
        this.lib = OperatorLibrary.getInstance();
    }

    /**
     * Decode a compressed bitstream and return the original data.
     *
     * Implements the decoder state machine:
     * 1. Read and validate header (magic, version, cycle count)
     * 2. Read cycle table and reconstruct CyclePath objects
     * 3. Read original data length
     * 4. For each encoded token:
     *    a. Read flag bits to determine tier
     *    b. LITERAL: read 8 bits directly
     *    c. RELATIONAL: read opId + operand, apply to lastValue
     *    d. CYCLE: read cycle index + repeat count, emit cycle nodes
     * 5. Stop when originalDataLength bytes emitted
     *
     * @param compressed the compressed bitstream
     * @return the decoded original data
     * @throws MrcFormatException if the bitstream format is invalid
     */
    public byte[] decode(byte[] compressed) throws MrcFormatException {
        BitStreamReader reader = new BitStreamReader(compressed);

        try {
            // Read and validate header, get cycles and original length
            HeaderData header = readHeaderFull(reader);
            List<CyclePath> cycles = header.cycles;
            int originalLength = header.originalLength;

            // Decode the data
            List<Byte> result = new ArrayList<>();
            int lastValue = 0;

            while (result.size() < originalLength && reader.hasMore()) {
                // Read flag bits to determine tier (prefix-free)
                int flag = reader.readBit();

                if (flag == 0) {
                    // LITERAL: 0 flag + 8 bits
                    int value = reader.readByte();
                    result.add((byte) value);
                    lastValue = value;
                } else {
                    // flag == 1, check second bit
                    int flag2 = reader.readBit();

                    if (flag2 == 0) {
                        // RELATIONAL: 10 flag + 5-bit opId + operand bits
                        byte opId = (byte) reader.readBits(5);
                        int operandBits = OpIdMap.getOperandBits(opId);
                        int operand = 0;
                        if (operandBits > 0) {
                            operand = (int) reader.readBits(operandBits);
                        }

                        // Reconstruct operator and apply
                        Operator op = lib.createOperator(opId, operand);
                        int nextValue = op.apply(lastValue) & 0xFF;
                        result.add((byte) nextValue);
                        lastValue = nextValue;
                    } else {
                        // flag2 == 1, check third bit
                        int flag3 = reader.readBit();

                        if (flag3 == 0) {
                            // CYCLE: 110 flag + index bits + 16-bit repeat count
                            int indexBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(cycles.size() - 1));
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

            // Convert to byte array and trim to original length
            byte[] output = new byte[Math.min(result.size(), originalLength)];
            for (int i = 0; i < output.length; i++) {
                output[i] = result.get(i);
            }

            return output;
        } catch (MrcFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new MrcFormatException("Decoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Helper class to hold header data.
     */
    private static class HeaderData {
        List<CyclePath> cycles;
        int originalLength;

        HeaderData(List<CyclePath> cycles, int originalLength) {
            this.cycles = cycles;
            this.originalLength = originalLength;
        }
    }

    /**
     * Read and validate the bitstream header.
     *
     * Format:
     * - 3 magic bytes: 0x4D 0x52 0x43 ("MRC")
     * - 1 version byte: 0x01
     * - 1 cycle count byte (0..255)
     * - For each cycle:
     *   - 1 length byte
     *   - length bytes: node values
     *   - length bytes: opIds
     * - 4 original data length bytes (big-endian int)
     *
     * @param reader the BitStreamReader
     * @return HeaderData containing cycles and original length
     * @throws MrcFormatException if the header is invalid
     */
    private HeaderData readHeaderFull(BitStreamReader reader) throws MrcFormatException {
        // Magic bytes
        int m = reader.readByte();
        int r = reader.readByte();
        int c = reader.readByte();

        if (m != 0x4D || r != 0x52 || c != 0x43) {
            throw new MrcFormatException("Invalid magic bytes");
        }

        // Version
        int version = reader.readByte();
        if (version != 0x01) {
            throw new MrcFormatException("Unsupported version: " + version);
        }

        // Cycle count
        int cycleCount = reader.readByte();

        // Read cycle table
        List<CyclePath> cycles = new ArrayList<>();
        for (int i = 0; i < cycleCount; i++) {
            // Length byte
            int length = reader.readByte();

            // Node values
            List<Integer> nodes = new ArrayList<>();
            for (int j = 0; j < length; j++) {
                nodes.add(reader.readByte());
            }

            // OpIds and reconstruct edges
            List<TransitionEdge> edges = new ArrayList<>();
            for (int j = 0; j < length; j++) {
                byte opId = (byte) reader.readByte();
                int operandBits = OpIdMap.getOperandBits(opId);

                // Find the operator for this transition
                int from = nodes.get(j);
                int to = nodes.get((j + 1) % length);

                // Reconstruct operator (use a dummy operand with a valid value)
                // For ShiftLeft/ShiftRight, use 1 as the default; for others use 0
                int dummyOperand = (opId == 8 || opId == 9) ? 1 : 0;
                Operator op = lib.createOperator(opId, dummyOperand);

                // Create edge with cost information
                int costBits = 5 + operandBits;
                TransitionEdge edge = new TransitionEdge(from, to, op, costBits, 0, 0);
                edges.add(edge);
            }

            // Calculate compression gain
            int cycleLength = nodes.size();
            double totalCost = edges.stream().mapToDouble(TransitionEdge::costBits).sum();
            double compressionGain = (cycleLength * 8.0) - totalCost;

            CyclePath cycle = new CyclePath(nodes, edges, cycleLength, 0, compressionGain);
            cycles.add(cycle);
        }

        // Original data length
        int len0 = reader.readByte();
        int len1 = reader.readByte();
        int len2 = reader.readByte();
        int len3 = reader.readByte();
        int originalLength = (len0 << 24) | (len1 << 16) | (len2 << 8) | len3;

        return new HeaderData(cycles, originalLength);
    }
}
