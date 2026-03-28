package mrc.core;

/**
 * Bitwise NOT operator: ~x (unary, no operand)
 */
public record Not() implements Operator {

    @Override
    public int apply(int x) {
        return (~x) & 0xFF;
    }

    @Override
    public byte opId() {
        return OpIdMap.getOpId(this);
    }

    @Override
    public int operandBits() {
        return 0;  // Unary, no operand bits
    }

    @Override
    public String toExpression(String varName) {
        return "~" + varName;
    }
}
