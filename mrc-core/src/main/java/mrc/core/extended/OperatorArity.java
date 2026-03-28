package mrc.core.extended;

/**
 * Classifies operators by how many input values they require.
 *
 * Level 0 (base) operators are UNARY — they transform a single byte.
 * Extended operators can be BINARY (need previous + current) or higher.
 */
public enum OperatorArity {
    /** Takes one input: apply(x) */
    UNARY,
    /** Takes two inputs: apply(x, context) — e.g. delta-of-delta needs prior delta */
    BINARY
}
