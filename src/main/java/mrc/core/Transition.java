package mrc.core;

import java.util.Optional;

/**
 * Record representing a single-operator transition from one 8-bit value to another.
 *
 * A transition is "compressing" if its cost in bits (5 for opId + operand bits)
 * is less than 8 bits (the cost of encoding a raw literal).
 */
public record Transition(int from, int to, Operator op, int costBits) {

    /**
     * Check if this transition is compressing (saves bits vs. a literal).
     *
     * @return true if costBits < 8
     */
    public boolean isCompressing() {
        return costBits < 8;
    }

    /**
     * Find the cheapest transition between two values that is compressing.
     *
     * A compressing transition has costBits < 8 (saves bits vs. a literal).
     * Uses OperatorLibrary.findShortest() to locate the operator.
     *
     * @param from the source value (0..255)
     * @param to the target value (0..255)
     * @param lib the OperatorLibrary instance
     * @return Optional containing the transition, or empty if no compressing option exists
     */
    public static Optional<Transition> find(int from, int to, OperatorLibrary lib) {
        Optional<Operator> optOp = lib.findShortest(from, to);
        if (optOp.isPresent()) {
            Operator op = optOp.get();
            int cost = 5 + op.operandBits();
            if (cost < 8) {
                return Optional.of(new Transition(from, to, op, cost));
            }
        }
        return Optional.empty();
    }

    /**
     * Find the cheapest operator between two values, regardless of compression.
     *
     * Unlike find(), this method returns ANY cheapest operator, not just compressing ones.
     * Non-compressing transitions can still be useful as part of CYCLE encoding.
     *
     * @param from the source value (0..255)
     * @param to the target value (0..255)
     * @param lib the OperatorLibrary instance
     * @return Optional containing the cheapest transition, or empty if no operator exists
     */
    public static Optional<Transition> findCheapest(int from, int to, OperatorLibrary lib) {
        Optional<Operator> optOp = lib.findShortest(from, to);
        if (optOp.isPresent()) {
            Operator op = optOp.get();
            int cost = 5 + op.operandBits();
            return Optional.of(new Transition(from, to, op, cost));
        }
        return Optional.empty();
    }
}
