package mrc.core.extended;

import mrc.core.Operator;
import mrc.core.OperatorLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extends the base OperatorLibrary with Level 1 FunctionOperator instances.
 *
 * Adds all valid FunctionOperator variants (excluding TableLookup which requires
 * an external table) to the searchable operator pool.
 *
 * Usage:
 * <pre>
 *   ExtendedOperatorLibrary lib = ExtendedOperatorLibrary.getInstance();
 *   Optional&lt;Operator&gt; op = lib.findShortest(from, to);
 * </pre>
 *
 * Note: This is a separate singleton from OperatorLibrary to keep the base
 * library lightweight. The extended library is only needed for Phase 2 encoding.
 */
public class ExtendedOperatorLibrary extends OperatorLibrary {

    private static volatile ExtendedOperatorLibrary extInstance;

    private final List<Operator> extendedOperators;

    protected ExtendedOperatorLibrary() {
        super();
        this.extendedOperators = new ArrayList<>();
        buildExtendedLibrary();
    }

    /**
     * Get the singleton ExtendedOperatorLibrary instance.
     */
    public static ExtendedOperatorLibrary getInstance() {
        if (extInstance == null) {
            synchronized (ExtendedOperatorLibrary.class) {
                if (extInstance == null) {
                    extInstance = new ExtendedOperatorLibrary();
                }
            }
        }
        return extInstance;
    }

    private void buildExtendedLibrary() {
        // Polynomial: a in 0..15, b in 0..15
        for (int a = 0; a <= 15; a++) {
            for (int b = 0; b <= 15; b++) {
                extendedOperators.add(new FunctionOperator.Polynomial(a, b));
            }
        }

        // LinearCongruential: a in 1..255 (odd for full period), c in 0..255
        for (int a = 1; a <= 255; a += 2) {
            for (int c = 0; c <= 255; c++) {
                extendedOperators.add(new FunctionOperator.LinearCongruential(a, c));
            }
        }

        // RotateLeft: 1..7 bits
        for (int bits = 1; bits <= 7; bits++) {
            extendedOperators.add(new FunctionOperator.RotateLeft(bits));
        }

        // RotateRight: 1..7 bits
        for (int bits = 1; bits <= 7; bits++) {
            extendedOperators.add(new FunctionOperator.RotateRight(bits));
        }

        // BitReverse: single instance
        extendedOperators.add(new FunctionOperator.BitReverse());

        // NibbleSwap: single instance
        extendedOperators.add(new FunctionOperator.NibbleSwap());

        // DeltaOfDelta: single instance
        extendedOperators.add(new FunctionOperator.DeltaOfDelta());
    }

    /**
     * Return all extended (Level 1) operators.
     */
    public List<Operator> extendedOperators() {
        return List.copyOf(extendedOperators);
    }

    /**
     * Find the cheapest operator (base or extended) mapping from → to.
     *
     * Searches base library first, then extended operators.
     *
     * @param from source value (0..255)
     * @param to target value (0..255)
     * @return Optional of cheapest operator, or empty if none found
     */
    public Optional<Operator> findShortestExtended(int from, int to) {
        Optional<Operator> base = findShortest(from, to);

        Operator best = base.orElse(null);
        int bestCost = best != null ? OperatorCostModel.relationalTokenCost(best) : Integer.MAX_VALUE;

        for (Operator op : extendedOperators) {
            if ((op.apply(from) & 0xFF) == (to & 0xFF)) {
                int cost = OperatorCostModel.relationalTokenCost(op);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = op;
                }
            }
        }

        return Optional.ofNullable(best);
    }
}
