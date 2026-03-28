package mrc.graph;

import mrc.core.Operator;

/**
 * Record representing a directed edge in the TransitionGraph.
 *
 * Combines an operator, its encoding cost, the observed frequency of this
 * transition, and the calculated compression gain weight.
 */
public record TransitionEdge(
        int fromNode,
        int toNode,
        Operator op,
        int costBits,
        long frequency,
        double weight
) {
    /**
     * Check if this edge dominates another edge for the same (from, to) pair.
     *
     * An edge dominates another if it has lower cost (more compressing) for
     * the same source and target nodes.
     *
     * @param other the other edge
     * @return true if this edge has lower costBits for the same (from, to) pair
     */
    public boolean dominates(TransitionEdge other) {
        if (fromNode != other.fromNode || toNode != other.toNode) {
            return false;
        }
        return costBits < other.costBits;
    }
}
