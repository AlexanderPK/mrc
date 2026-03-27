package mrc.graph;

import java.util.List;

/**
 * Record representing a simple cycle detected in the TransitionGraph.
 *
 * A cycle is a path through the graph that returns to its starting node,
 * with edges representing operator transitions. The compression gain is
 * the total bits saved per complete cycle traversal.
 */
public record CyclePath(
        List<Integer> nodes,
        List<TransitionEdge> edges,
        int length,
        double totalWeight,
        double compressionGain
) {
    /**
     * Get the phase index of a value within this cycle.
     *
     * The phase is the position in the cycle's node sequence.
     *
     * @param value the value to locate
     * @return the index (0..length-1) if the value is in the cycle, or -1
     */
    public int phaseOf(int value) {
        return nodes.indexOf(value);
    }

    /**
     * Check if a value is part of this cycle.
     *
     * @param value the value to test
     * @return true if the value is in nodes
     */
    public boolean containsNode(int value) {
        return nodes.contains(value);
    }

    /**
     * Get a human-readable expression for the cycle.
     *
     * Example: "37 --(+3)--> 40 --(+3)--> 43 --(+3)--> 37"
     *
     * TODO: Implement expression formatting.
     *
     * @return human-readable cycle expression
     */
    public String toExpression() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(nodes.get(i));
            TransitionEdge edge = edges.get(i);
            sb.append(" --(").append(edge.op().toExpression("x")).append(")--> ");
        }
        sb.append(nodes.get(0));
        return sb.toString();
    }
}
