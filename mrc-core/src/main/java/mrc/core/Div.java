package mrc.core;

/**
 * Integer division operator: x / operand (guarded against division by zero).
 * Note: Div(0) is never registered in OperatorLibrary.
 */
public record Div(int operand) implements Operator {

    public Div {
        if (operand == 0) {
            throw new ArithmeticException("Division by zero is not allowed");
        }
    }

    @Override
    public int apply(int x) {
        if (operand == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return (x / operand) & 0xFF;
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
        return varName + " / " + operand;
    }
}
