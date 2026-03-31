package mrc.core;

/**
 * Decrement operator: (x - 1) & 0xFF — unary, no operand bits.
 *
 * Produces a 7-bit relational token (2-bit flags + 5-bit opId), saving 2 bits
 * versus a 9-bit literal for every x→x-1 transition.
 */
public record Dec() implements Operator {

    @Override
    public int apply(int x) {
        return (x - 1) & 0xFF;
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
        return varName + "-1";
    }
}
