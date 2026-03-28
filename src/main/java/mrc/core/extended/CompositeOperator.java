package mrc.core.extended;

import mrc.core.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Level 2 extended operator: a chain of 2-4 base operators applied in sequence.
 *
 * CompositeOperator(f1, f2, ..., fn)(x) = fn(...(f2(f1(x)))...)
 *
 * OpId 40 is reserved for CompositeOperator (all instances share the same type id;
 * the operand encodes step count and indices into a registered table).
 *
 * The {@link #optimized()} method applies algebraic simplification rules to
 * collapse or reduce the chain where possible.
 */
public final class CompositeOperator implements Operator {

    private final List<Operator> steps;

    /**
     * Construct a composite from a list of 2-4 operators.
     *
     * @param steps operators to chain (in order); must have 2..4 elements
     * @throws IllegalArgumentException if steps count is out of range
     */
    public CompositeOperator(List<Operator> steps) {
        if (steps.size() < 2 || steps.size() > 4) {
            throw new IllegalArgumentException("CompositeOperator requires 2..4 steps, got " + steps.size());
        }
        this.steps = List.copyOf(steps);
    }

    /** Convenience varargs constructor. */
    public CompositeOperator(Operator... steps) {
        this(Arrays.asList(steps));
    }

    @Override
    public int apply(int x) {
        int v = x & 0xFF;
        for (Operator op : steps) v = op.apply(v) & 0xFF;
        return v;
    }

    @Override
    public byte opId() { return 40; }

    @Override
    public int operandBits() {
        // Cost is the sum of each constituent step's cost
        int total = 0;
        for (Operator op : steps) total += 5 + op.operandBits();
        return total;
    }

    @Override
    public String toExpression(String varName) {
        String expr = varName;
        for (Operator op : steps) expr = op.toExpression(expr);
        return expr;
    }

    /** The ordered list of constituent operators. */
    public List<Operator> steps() { return steps; }

    // -------------------------------------------------------------------------
    // Algebraic simplification
    // -------------------------------------------------------------------------

    /**
     * Return a simplified version of this composite, applying algebraic rules:
     *
     * - Add(a) ∘ Add(b)  → Add((a+b) & 0xFF)
     * - Sub(a) ∘ Sub(b)  → Sub((a+b) & 0xFF)
     * - Add(a) ∘ Sub(b)  → simplify net offset
     * - Not ∘ Not        → identity (eliminated)
     * - XorOp(a) ∘ XorOp(b) → XorOp(a^b)
     * - AndOp(a) ∘ AndOp(b) → AndOp(a&b)
     * - OrOp(a) ∘ OrOp(b)   → OrOp(a|b)
     * - ShiftLeft(a) ∘ ShiftLeft(b) → ShiftLeft(a+b) if ≤7, else zero
     * - ShiftRight(a) ∘ ShiftRight(b) → ShiftRight(a+b) if ≤7, else zero
     *
     * If the chain reduces to a single operator, returns that operator directly.
     * If fully cancelled (identity), returns Add(0) as identity.
     *
     * @return simplified Operator (may be a CompositeOperator or a base operator)
     */
    public Operator optimized() {
        List<Operator> chain = new ArrayList<>(steps);
        boolean changed = true;
        while (changed && chain.size() > 1) {
            changed = false;
            for (int i = 0; i < chain.size() - 1; i++) {
                Operator result = tryFuse(chain.get(i), chain.get(i + 1));
                if (result != null) {
                    chain.remove(i + 1);
                    chain.set(i, result);
                    changed = true;
                    break;
                }
            }
        }
        // Remove identity Add(0) from the chain unless it's the only element
        chain.removeIf(op -> op instanceof Add a && a.operand() == 0 && chain.size() > 1);

        if (chain.isEmpty()) return new Add(0);
        if (chain.size() == 1) return chain.get(0);
        if (chain.size() == steps.size()) return this; // no change
        return new CompositeOperator(chain);
    }

    /**
     * Attempt to fuse two consecutive operators into one.
     *
     * @return fused operator, or null if no rule applies
     */
    private static Operator tryFuse(Operator a, Operator b) {
        return switch (a) {
            case Add aa -> switch (b) {
                case Add ab -> new Add((aa.operand() + ab.operand()) & 0xFF);
                case Sub sb -> {
                    int net = (aa.operand() - sb.operand()) & 0xFF;
                    yield net == 0 ? new Add(0) : new Add(net);
                }
                default -> null;
            };
            case Sub sa -> switch (b) {
                case Sub sb -> new Sub((sa.operand() + sb.operand()) & 0xFF);
                case Add ab -> {
                    int net = (ab.operand() - sa.operand()) & 0xFF;
                    yield net == 0 ? new Add(0) : new Add(net);
                }
                default -> null;
            };
            case Not ignored1 -> switch (b) {
                case Not ignored2 -> new Add(0); // Not ∘ Not = identity
                default -> null;
            };
            case XorOp xa -> switch (b) {
                case XorOp xb -> new XorOp(xa.operand() ^ xb.operand());
                default -> null;
            };
            case AndOp aa -> switch (b) {
                case AndOp ab -> new AndOp(aa.operand() & ab.operand());
                default -> null;
            };
            case OrOp oa -> switch (b) {
                case OrOp ob -> new OrOp(oa.operand() | ob.operand());
                default -> null;
            };
            case ShiftLeft sla -> switch (b) {
                case ShiftLeft slb -> {
                    int total = sla.bits() + slb.bits();
                    yield total <= 7 ? new ShiftLeft(total) : new AndOp(0); // shifted out
                }
                default -> null;
            };
            case ShiftRight sra -> switch (b) {
                case ShiftRight srb -> {
                    int total = sra.bits() + srb.bits();
                    yield total <= 7 ? new ShiftRight(total) : new AndOp(0); // shifted out
                }
                default -> null;
            };
            default -> null;
        };
    }
}
