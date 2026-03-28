package mrc.core.extended;

import mrc.core.Operator;

import java.util.Arrays;

/**
 * Level 3 extended operators: higher-order operators that take another operator as argument.
 *
 * OpIds 64..66 are reserved for SuperfunctionOperator subtypes.
 */
public sealed interface SuperfunctionOperator extends Operator
        permits SuperfunctionOperator.Iterated,
                SuperfunctionOperator.FixedPointReach,
                SuperfunctionOperator.Conjugate {

    @Override
    default int operandBits() { return 0; }

    // -------------------------------------------------------------------------
    // Iterated: apply base operator n times
    // -------------------------------------------------------------------------

    /**
     * Iterates a base operator n times: f^n(x) = f(f(f...(x)...))
     *
     * @param base the operator to iterate
     * @param n number of iterations (1..255)
     */
    record Iterated(Operator base, int n) implements SuperfunctionOperator {
        @Override
        public int apply(int x) {
            int v = x & 0xFF;
            for (int i = 0; i < n; i++) v = base.apply(v) & 0xFF;
            return v;
        }
        @Override public byte opId() { return 64; }
        @Override public String toExpression(String varName) {
            return base.toExpression(varName) + "^" + n;
        }
    }

    // -------------------------------------------------------------------------
    // FixedPointReach: iterate until fixed point or max steps
    // -------------------------------------------------------------------------

    /**
     * Applies base operator repeatedly until a fixed point (f(x) == x) is reached
     * or maxSteps is exhausted. Returns the fixed point value.
     *
     * @param base the operator to iterate
     * @param maxSteps safety limit (1..255)
     */
    record FixedPointReach(Operator base, int maxSteps) implements SuperfunctionOperator {
        @Override
        public int apply(int x) {
            int v = x & 0xFF;
            for (int i = 0; i < maxSteps; i++) {
                int next = base.apply(v) & 0xFF;
                if (next == v) return v;
                v = next;
            }
            return v;
        }
        @Override public byte opId() { return 65; }
        @Override public String toExpression(String varName) {
            return "fixpoint(" + base.toExpression(varName) + ", max=" + maxSteps + ")";
        }
    }

    // -------------------------------------------------------------------------
    // Conjugate: h^-1 ∘ f ∘ h (change-of-basis transformation)
    // -------------------------------------------------------------------------

    /**
     * Conjugates operator f by h: applies h(x), then f, then h^-1.
     *
     * conjugate(f, h)(x) = h_inv(f(h(x)))
     *
     * The inverse is precomputed from h over the full 8-bit domain.
     * If h is not bijective, the inverse maps to 0 for unmapped values.
     *
     * @param f the operator to conjugate
     * @param h the change-of-basis operator
     */
    record Conjugate(Operator f, Operator h) implements SuperfunctionOperator {
        // Precomputed inverse lookup table for h
        private static int[] buildInverse(Operator h) {
            int[] inv = new int[256];
            Arrays.fill(inv, 0);
            for (int x = 0; x < 256; x++) {
                int y = h.apply(x) & 0xFF;
                inv[y] = x;
            }
            return inv;
        }

        @Override
        public int apply(int x) {
            int[] inv = buildInverse(h);
            int hx   = h.apply(x & 0xFF) & 0xFF;
            int fhx  = f.apply(hx) & 0xFF;
            return inv[fhx];
        }
        @Override public byte opId() { return 66; }
        @Override public String toExpression(String varName) {
            return "h⁻¹(" + f.toExpression("h(" + varName + ")") + ")";
        }
    }
}
