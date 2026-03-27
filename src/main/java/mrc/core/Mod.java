package mrc.core;

/**
 * Modulo operator: x % operand (guarded against modulo by zero).
 */
public record Mod(int operand) implements Operator {

    public Mod {
        if (operand == 0) {
            throw new ArithmeticException("Modulo by zero is not allowed");
        }
    }

    @Override
    public int apply(int x) {
        if (operand == 0) {
            throw new ArithmeticException("Modulo by zero");
        }
        return (x % operand) & 0xFF;
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
        return varName + " % " + operand;
    }
}
