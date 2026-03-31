package mrc.core;

import java.util.*;

/**
 * Singleton registry of all Operator instances.
 *
 * Builds a library of ~2,400 operators (all valid combinations of each operator type
 * with their valid operand ranges) and provides:
 * - Lookup by unique opId (byte, 5-bit)
 * - List of all operators
 * - Finding the shortest (cheapest) transition from one value to another
 *
 * The library is built lazily on first access and cached for performance.
 */
public class OperatorLibrary {
    private static volatile OperatorLibrary instance;

    private final List<Operator> allOperators;
    private final Map<Byte, Operator> byId;
    private final Operator[][] transitionCache;  // [from][to] direct lookup cache (65KB)

    protected OperatorLibrary() {
        this.allOperators = new ArrayList<>();
        this.byId = new HashMap<>();
        this.transitionCache = new Operator[256][256];
        buildOperatorLibrary();
        // Pre-compute all transitions for fast O(1) lookup
        precomputeTransitions();
    }

    /**
     * Get the singleton instance of OperatorLibrary.
     */
    public static OperatorLibrary getInstance() {
        if (instance == null) {
            synchronized (OperatorLibrary.class) {
                if (instance == null) {
                    instance = new OperatorLibrary();
                }
            }
        }
        return instance;
    }

    /**
     * Build the complete library of all valid operators (~2,400 instances).
     *
     * Uses type-level opIds (0..10) where each operator type has a constant ID.
     * Stores one representative per type in byId map for decoder lookups.
     */
    private void buildOperatorLibrary() {
        // Add all Add operators (0..255)
        for (int operand = 0; operand <= 255; operand++) {
            Add op = new Add(operand);
            registerOperator(op);
        }

        // Add all Sub operators (0..255)
        for (int operand = 0; operand <= 255; operand++) {
            Sub op = new Sub(operand);
            registerOperator(op);
        }

        // Add all Mul operators (0..255)
        for (int operand = 0; operand <= 255; operand++) {
            Mul op = new Mul(operand);
            registerOperator(op);
        }

        // Add all Div operators (1..255, exclude 0)
        for (int operand = 1; operand <= 255; operand++) {
            try {
                Div op = new Div(operand);
                registerOperator(op);
            } catch (ArithmeticException e) {
                // Skip Div(0) — never registered
            }
        }

        // Add all Mod operators (1..255, exclude 0)
        for (int operand = 1; operand <= 255; operand++) {
            try {
                Mod op = new Mod(operand);
                registerOperator(op);
            } catch (ArithmeticException e) {
                // Skip Mod(0) — never registered
            }
        }

        // Add all XorOp operators (0..255)
        for (int operand = 0; operand <= 255; operand++) {
            XorOp op = new XorOp(operand);
            registerOperator(op);
        }

        // Add all AndOp operators (0..255)
        for (int operand = 0; operand <= 255; operand++) {
            AndOp op = new AndOp(operand);
            registerOperator(op);
        }

        // Add all OrOp operators (0..255)
        for (int operand = 0; operand <= 255; operand++) {
            OrOp op = new OrOp(operand);
            registerOperator(op);
        }

        // Add all ShiftLeft operators (1..7)
        for (int bits = 1; bits <= 7; bits++) {
            ShiftLeft op = new ShiftLeft(bits);
            registerOperator(op);
        }

        // Add all ShiftRight operators (1..7)
        for (int bits = 1; bits <= 7; bits++) {
            ShiftRight op = new ShiftRight(bits);
            registerOperator(op);
        }

        // Add the single Not operator
        registerOperator(new Not());

        // Add compact 0-operand operators (7-bit tokens, cheaper than 9-bit literals)
        registerOperator(new Inc());
        registerOperator(new Dec());
        registerOperator(new Identity());
    }

    /**
     * Register an operator in the library.
     *
     * Stores all instances in allOperators for findShortest() searching.
     * Stores one representative per type ID in byId for decoder lookups.
     */
    private void registerOperator(Operator op) {
        allOperators.add(op);
        byte typeId = OpIdMap.getOpId(op);
        byId.putIfAbsent(typeId, op);  // Keep one prototype per type
    }

    /**
     * Get an operator by its unique 5-bit ID.
     *
     * @param id the opId
     * @return the Operator, or null if not found
     */
    public Operator byId(byte id) {
        return byId.get(id);
    }

    /**
     * Create an operator instance with the given opId and operand.
     *
     * Used by the decoder to reconstruct operators from encoded opId + operand.
     *
     * @param opId the operator type ID (0..10)
     * @param operand the operand value
     * @return the constructed Operator
     * @throws IllegalArgumentException if opId is invalid
     */
    public Operator createOperator(byte opId, int operand) {
        return switch (opId) {
            case 0 -> new Add(operand);
            case 1 -> new Sub(operand);
            case 2 -> new Mul(operand);
            case 3 -> new Div(operand);
            case 4 -> new Mod(operand);
            case 5 -> new XorOp(operand);
            case 6 -> new AndOp(operand);
            case 7 -> new OrOp(operand);
            case 8 -> new ShiftLeft(operand);
            case 9 -> new ShiftRight(operand);
            case 10 -> new Not();
            case 11 -> new Inc();
            case 12 -> new Dec();
            case 13 -> new Identity();
            default -> throw new IllegalArgumentException("Unknown opId: " + opId);
        };
    }

    /**
     * Get all operators in registration order.
     *
     * @return unmodifiable list of all operators
     */
    public List<Operator> all() {
        return Collections.unmodifiableList(allOperators);
    }

    /**
     * Find the cheapest single operator that maps from one 8-bit value to another.
     *
     * Brute-force search over all operators, finding the one with minimal cost
     * (operandBits + 5) that produces the correct mapping.
     *
     * TODO: Implement full search and caching.
     *
     * @param from the source value (0..255)
     * @param to the target value (0..255)
     * @return Optional containing the cheapest operator, or empty if none exists
     */
    /**
     * Pre-compute the shortest operator for all 256x256 transitions.
     * This is done once during initialization for O(1) lookup performance.
     */
    private void precomputeTransitions() {
        for (int from = 0; from < 256; from++) {
            for (int to = 0; to < 256; to++) {
                Operator best = null;
                int bestCost = Integer.MAX_VALUE;

                for (Operator op : allOperators) {
                    int result = op.apply(from) & 0xFF;
                    if (result == to) {
                        int cost = 5 + op.operandBits();
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = op;
                        }
                    }
                }
                transitionCache[from][to] = best;
            }
        }
    }

    public Optional<Operator> findShortest(int from, int to) {
        from = from & 0xFF;
        to = to & 0xFF;
        Operator op = transitionCache[from][to];
        return Optional.ofNullable(op);
    }
}
