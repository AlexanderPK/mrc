package mrc.core;

/**
 * Subtraction operator: (x - operand) mod 256
 */
public record Sub(int operand) implements Operator {

    @Override
    public int apply(int x) {
        return (x - operand) & 0xFF;
    }

    @Override
    public byte opId() {
        return OpIdMap.getOpId(this);
    }

    @Override
    public int operandBits() {
        return 8;
    }

    @Override
    public String toExpression(String varName) {
        return varName + " - " + operand;
    }
}
