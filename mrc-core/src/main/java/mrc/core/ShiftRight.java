package mrc.core;

/**
 * Right shift operator: x >> bits
 * Bits parameter is in range [1..7] for 3-bit encoding.
 */
public record ShiftRight(int bits) implements Operator {

    public ShiftRight {
        if (bits < 1 || bits > 7) {
            throw new IllegalArgumentException("ShiftRight bits must be in [1..7], got " + bits);
        }
    }

    @Override
    public int apply(int x) {
        return (x >> bits) & 0xFF;
    }

    @Override
    public byte opId() {
        return OpIdMap.getOpId(this);
    }

    @Override
    public int operandBits() {
        return 3;  // 3 bits to encode shift amount [1..7]
    }

    @Override
    public String toExpression(String varName) {
        return varName + " >> " + bits;
    }
}
