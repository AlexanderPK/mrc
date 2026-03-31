package mrc.core;

/**
 * Identity operator: x & 0xFF — unary, no operand bits.
 *
 * Produces a 7-bit relational token for every x→x (same-value) transition,
 * saving 2 bits versus a 9-bit literal. Particularly effective for repetitive
 * data with long runs of equal bytes.
 */
public record Identity() implements Operator {

    @Override
    public int apply(int x) {
        return x & 0xFF;
    }

    @Override
    public byte opId() {
        return OpIdMap.getOpId(this);
    }

    @Override
    public int operandBits() {
        return 0;
    }

    @Override
    public String toExpression(String varName) {
        return varName;
    }
}
