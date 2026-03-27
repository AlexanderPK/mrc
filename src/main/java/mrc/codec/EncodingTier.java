package mrc.codec;

/**
 * Enum representing the three encoding strategies in the MRC bitstream.
 *
 * Each tier has a unique prefix-free flag:
 * - LITERAL: raw 8-bit value, flag = 0 (1 bit)
 * - RELATIONAL: operator-applied transition, flag = 10 (2 bits)
 * - CYCLE: repeated cycle traversal, flag = 110 (3 bits)
 *
 * The prefix-free property ensures unambiguous decoding without lookahead.
 */
public enum EncodingTier {
    /**
     * Raw 8-bit literal value.
     * Flag: 0 (1 bit)
     * Payload: 8 bits (the value itself)
     */
    LITERAL(0, 1),

    /**
     * Operator-applied transition from previous value.
     * Flag: 10 (2 bits)
     * Payload: 5 bits (opId) + operand bits
     */
    RELATIONAL(2, 2),

    /**
     * Repeated cycle traversal (cycle encoding).
     * Flag: 110 (3 bits)
     * Payload: cycle index bits + 16-bit repeat count
     */
    CYCLE(6, 3);

    private final int flagBits;
    private final int flagBitCount;

    EncodingTier(int flagBits, int flagBitCount) {
        this.flagBits = flagBits;
        this.flagBitCount = flagBitCount;
    }

    /**
     * Get the bit pattern for this tier's flag.
     *
     * @return the flag bits (0, 2, or 6)
     */
    public int flagBits() {
        return flagBits;
    }

    /**
     * Get the number of bits in this tier's flag.
     *
     * @return flag bit count (1, 2, or 3)
     */
    public int flagBitCount() {
        return flagBitCount;
    }
}
