package mrc.core.extended;

import mrc.core.Operator;

/**
 * Break-even analysis utilities for operator encoding cost vs. literal savings.
 *
 * A relational token is worth emitting only when its bit cost is less than the
 * 8-bit literal it replaces (9 bits in v0x02, 8 bits in raw).
 *
 * Cost model:
 *   - Each token has a 1-bit type flag overhead
 *   - Base operators: 5-bit opId + operandBits (0, 3, or 8)
 *   - FunctionOperator: fixed 8-bit operand (Rotate/Bit/Nibble are 0 or 3 bits)
 *   - CompositeOperator: sum of constituent costs
 *   - SuperfunctionOperator: no direct bitstream encoding (used analytically)
 *
 * Break-even: token saves bits when totalCost < literalCost.
 */
public final class OperatorCostModel {

    private OperatorCostModel() {}

    /** Bit cost of a raw 8-bit literal (no flag). */
    public static final int LITERAL_BITS = 8;

    /** Bit cost of a v0x02 LITERAL token (1 flag + 8 data). */
    public static final int LITERAL_TOKEN_BITS = 9;

    /**
     * Compute the bit cost of encoding a relational token for the given operator.
     *
     * Cost = 1 (flag) + 5 (opId) + operandBits
     *
     * @param op the operator to cost
     * @return total bits needed for the relational token
     */
    public static int relationalTokenCost(Operator op) {
        return 1 + 5 + op.operandBits();
    }

    /**
     * Returns true if emitting a relational token for op saves bits vs. a literal.
     *
     * @param op the operator
     * @return true if relationalTokenCost(op) < LITERAL_BITS
     */
    public static boolean isBetterThanLiteral(Operator op) {
        return relationalTokenCost(op) < LITERAL_BITS;
    }

    /**
     * Returns true if emitting a relational token saves bits vs. a v0x02 LITERAL token.
     *
     * @param op the operator
     * @return true if relationalTokenCost(op) < LITERAL_TOKEN_BITS
     */
    public static boolean isBetterThanLiteralToken(Operator op) {
        return relationalTokenCost(op) < LITERAL_TOKEN_BITS;
    }

    /**
     * Break-even run length: how many consecutive bytes must be covered by
     * an arithmetic run before the ARITH_RUN token (33 bits) beats raw literals.
     *
     * ARITH_RUN token = 1 + 8 + 8 + 16 = 33 bits.
     * Raw literals = runLen * 8 bits.
     * Break-even: 33 < runLen * 9  →  runLen > 3.67  →  runLen >= 4.
     *
     * @return minimum run length for an ARITH_RUN token to be beneficial
     */
    public static int arithRunBreakEven() {
        return 4; // 33 / 9 = 3.67 → need at least 4 bytes
    }

    /**
     * Estimate bit savings from encoding a run of `runLen` bytes as ARITH_RUN.
     *
     * Savings = (runLen * LITERAL_TOKEN_BITS) - 33
     *
     * @param runLen length of the arithmetic run
     * @return bit savings (may be negative for short runs)
     */
    public static long estimatedArithRunSavings(int runLen) {
        return (long) runLen * LITERAL_TOKEN_BITS - 33L;
    }
}
