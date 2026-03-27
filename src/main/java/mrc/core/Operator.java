package mrc.core;

/**
 * Sealed interface representing an algebraic operator that transforms 8-bit unsigned integers.
 *
 * Each operator has:
 * - A unique 5-bit ID (0..30)
 * - An operand encoding cost (0 for Not, 3 for shifts, 8 for arithmetic)
 * - A functional implementation that applies to 8-bit values (masked to 0xFF)
 */
public sealed interface Operator permits Add, Sub, Mul, Div, Mod, XorOp, AndOp, OrOp, ShiftLeft, ShiftRight, Not {

    /**
     * Apply this operator to an 8-bit value.
     *
     * @param x the input value (0..255)
     * @return the result masked to 0xFF
     */
    int apply(int x);

    /**
     * Get the unique 5-bit ID for this operator instance.
     *
     * @return the opId (0..30)
     */
    byte opId();

    /**
     * Get the number of bits needed to encode the operand in the bitstream.
     *
     * @return operand bits (0 for Not, 3 for shifts, 8 for arithmetic)
     */
    int operandBits();

    /**
     * Get a human-readable expression for this operator.
     *
     * @param varName the variable name to use (e.g., "x")
     * @return an expression like "x + 47" or "x << 3"
     */
    String toExpression(String varName);
}
