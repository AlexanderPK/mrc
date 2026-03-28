package mrc.core.extended;

import mrc.core.Operator;

/**
 * Level 1 extended operators: mathematical functions on 8-bit values.
 *
 * Each implementation maps a byte to a byte via a specific mathematical function.
 * OpIds 32..39 are reserved for FunctionOperator subtypes.
 *
 * All results are masked to 0xFF (unsigned 8-bit).
 */
public sealed interface FunctionOperator extends Operator
        permits FunctionOperator.Polynomial,
                FunctionOperator.LinearCongruential,
                FunctionOperator.TableLookup,
                FunctionOperator.RotateLeft,
                FunctionOperator.RotateRight,
                FunctionOperator.BitReverse,
                FunctionOperator.NibbleSwap,
                FunctionOperator.DeltaOfDelta {

    @Override
    default int operandBits() { return 8; }

    // -------------------------------------------------------------------------
    // Polynomial: (a*x^2 + b) mod 256
    // -------------------------------------------------------------------------

    /**
     * Polynomial function: (a*x*x + b) & 0xFF
     *
     * @param a quadratic coefficient (0..15, 4 bits)
     * @param b constant offset (0..15, 4 bits)
     */
    record Polynomial(int a, int b) implements FunctionOperator {
        @Override public int apply(int x) { return (a * x * x + b) & 0xFF; }
        @Override public byte opId()      { return 32; }
        @Override public String toExpression(String v) { return a + "*" + v + "^2 + " + b; }
    }

    // -------------------------------------------------------------------------
    // LinearCongruential: (a*x + c) mod 256
    // -------------------------------------------------------------------------

    /**
     * Linear congruential generator step: (a*x + c) & 0xFF
     *
     * @param a multiplier (0..255)
     * @param c increment (0..255)
     */
    record LinearCongruential(int a, int c) implements FunctionOperator {
        @Override public int apply(int x) { return (a * x + c) & 0xFF; }
        @Override public byte opId()      { return 33; }
        @Override public String toExpression(String v) { return a + "*" + v + " + " + c; }
    }

    // -------------------------------------------------------------------------
    // TableLookup: substitution table indexed by x
    // -------------------------------------------------------------------------

    /**
     * Fixed substitution table (256-entry S-box).
     *
     * @param table the 256-byte substitution table
     */
    record TableLookup(byte[] table) implements FunctionOperator {
        @Override public int apply(int x) { return table[x & 0xFF] & 0xFF; }
        @Override public byte opId()      { return 34; }
        @Override public String toExpression(String v) { return "table[" + v + "]"; }
    }

    // -------------------------------------------------------------------------
    // RotateLeft: circular left shift by n bits
    // -------------------------------------------------------------------------

    /**
     * Circular left rotation of 8-bit value by n bits.
     *
     * @param bits rotation amount (1..7)
     */
    record RotateLeft(int bits) implements FunctionOperator {
        @Override
        public int apply(int x) {
            int v = x & 0xFF;
            return ((v << bits) | (v >>> (8 - bits))) & 0xFF;
        }
        @Override public byte opId()      { return 35; }
        @Override public int operandBits() { return 3; }
        @Override public String toExpression(String v) { return "rotl(" + v + ", " + bits + ")"; }
    }

    // -------------------------------------------------------------------------
    // RotateRight: circular right shift by n bits
    // -------------------------------------------------------------------------

    /**
     * Circular right rotation of 8-bit value by n bits.
     *
     * @param bits rotation amount (1..7)
     */
    record RotateRight(int bits) implements FunctionOperator {
        @Override
        public int apply(int x) {
            int v = x & 0xFF;
            return ((v >>> bits) | (v << (8 - bits))) & 0xFF;
        }
        @Override public byte opId()      { return 36; }
        @Override public int operandBits() { return 3; }
        @Override public String toExpression(String v) { return "rotr(" + v + ", " + bits + ")"; }
    }

    // -------------------------------------------------------------------------
    // BitReverse: reverse all 8 bits
    // -------------------------------------------------------------------------

    /**
     * Reverses the bit order of an 8-bit value (bit 7 ↔ bit 0, etc.).
     */
    record BitReverse() implements FunctionOperator {
        @Override
        public int apply(int x) {
            int v = x & 0xFF;
            int r = 0;
            for (int i = 0; i < 8; i++) {
                r = (r << 1) | (v & 1);
                v >>= 1;
            }
            return r;
        }
        @Override public byte opId()      { return 37; }
        @Override public int operandBits() { return 0; }
        @Override public String toExpression(String v) { return "bitrev(" + v + ")"; }
    }

    // -------------------------------------------------------------------------
    // NibbleSwap: swap high and low nibbles
    // -------------------------------------------------------------------------

    /**
     * Swaps the high nibble (bits 4-7) and low nibble (bits 0-3).
     */
    record NibbleSwap() implements FunctionOperator {
        @Override
        public int apply(int x) {
            int v = x & 0xFF;
            return ((v & 0x0F) << 4) | ((v >> 4) & 0x0F);
        }
        @Override public byte opId()      { return 38; }
        @Override public int operandBits() { return 0; }
        @Override public String toExpression(String v) { return "nibswap(" + v + ")"; }
    }

    // -------------------------------------------------------------------------
    // DeltaOfDelta: second-order difference (requires context = previous delta)
    // -------------------------------------------------------------------------

    /**
     * Second-order difference operator: x - context (mod 256), where context is
     * the previous first-order delta.
     *
     * This is a binary operator — use apply(x, prevDelta) for correct results.
     * apply(x) treats prevDelta=0 (degenerate case).
     */
    record DeltaOfDelta() implements FunctionOperator {
        @Override public int apply(int x)               { return x & 0xFF; }
        @Override public int apply(int x, int context)  { return (x - context) & 0xFF; }
        @Override public byte opId()                    { return 39; }
        @Override public int operandBits()              { return 0; }
        @Override public String toExpression(String v)  { return "Δ²(" + v + ")"; }
    }
}
