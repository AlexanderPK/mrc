package mrc.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class that maps operator types to their unique type-level opIds.
 *
 * Each operator type (Add, Sub, Mul, etc.) has a unique 5-bit opId (0..10).
 * The bitstream uses the opId to identify the operator type, with operand bits
 * encoded separately. This enables the decoder to know operandBits() from the
 * opId alone.
 */
public class OpIdMap {
    private static final Map<Class<?>, Byte> TYPE_TO_ID = new HashMap<>();

    static {
        TYPE_TO_ID.put(Add.class, (byte) 0);
        TYPE_TO_ID.put(Sub.class, (byte) 1);
        TYPE_TO_ID.put(Mul.class, (byte) 2);
        TYPE_TO_ID.put(Div.class, (byte) 3);
        TYPE_TO_ID.put(Mod.class, (byte) 4);
        TYPE_TO_ID.put(XorOp.class, (byte) 5);
        TYPE_TO_ID.put(AndOp.class, (byte) 6);
        TYPE_TO_ID.put(OrOp.class, (byte) 7);
        TYPE_TO_ID.put(ShiftLeft.class, (byte) 8);
        TYPE_TO_ID.put(ShiftRight.class, (byte) 9);
        TYPE_TO_ID.put(Not.class, (byte) 10);
    }

    /**
     * Get the opId for an operator instance.
     *
     * @param op the operator instance
     * @return the type-level opId (0..10)
     */
    public static byte getOpId(Operator op) {
        Byte id = TYPE_TO_ID.get(op.getClass());
        if (id == null) {
            throw new IllegalArgumentException("Unknown operator type: " + op.getClass().getName());
        }
        return id;
    }

    /**
     * Get the opId for an operator class.
     *
     * @param cls the operator class
     * @return the type-level opId (0..10)
     */
    public static byte getOpId(Class<?> cls) {
        Byte id = TYPE_TO_ID.get(cls);
        if (id == null) {
            throw new IllegalArgumentException("Unknown operator class: " + cls.getName());
        }
        return id;
    }

    /**
     * Get the operator class name for an opId.
     *
     * @param opId the opId (0..10)
     * @return the class name
     */
    public static String getName(byte opId) {
        return switch (opId) {
            case 0 -> "Add";
            case 1 -> "Sub";
            case 2 -> "Mul";
            case 3 -> "Div";
            case 4 -> "Mod";
            case 5 -> "XorOp";
            case 6 -> "AndOp";
            case 7 -> "OrOp";
            case 8 -> "ShiftLeft";
            case 9 -> "ShiftRight";
            case 10 -> "Not";
            default -> "Unknown(" + opId + ")";
        };
    }

    /**
     * Get the operand bit count for an operator type.
     *
     * @param opId the opId (0..10)
     * @return the operand bit count (0, 3, or 8)
     */
    public static int getOperandBits(byte opId) {
        return switch (opId) {
            case 0, 1, 2, 3, 4, 5, 6, 7 -> 8;  // Add through OrOp
            case 8, 9 -> 3;                      // Shift operators
            case 10 -> 0;                        // Not (unary)
            default -> throw new IllegalArgumentException("Unknown opId: " + opId);
        };
    }
}
