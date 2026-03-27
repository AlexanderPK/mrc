package mrc.core;

/**
 * Add operator: (x + operand) mod 256
 */
public record Add(int operand) implements Operator {

    /**
     * Apply addition, masked to 8 bits.
     */
    @Override
    public int apply(int x) {
        return (x + operand) & 0xFF;
    }

    /**
     * Get the opId for this operator type (type-level ID).
     */
    @Override
    public byte opId() {
        return OpIdMap.getOpId(this);
    }

    /**
     * Operand encoding cost: 8 bits for the operand value.
     */
    @Override
    public int operandBits() {
        return 8;
    }

    /**
     * Human-readable expression.
     */
    @Override
    public String toExpression(String varName) {
        return varName + " + " + operand;
    }
}
