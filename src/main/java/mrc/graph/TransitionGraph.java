package mrc.graph;

import mrc.core.OperatorLibrary;
import mrc.core.Transition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Directed multigraph representing transitions between 8-bit values.
 *
 * Built by observing pairwise transitions in a data stream. Stores only the
 * cheapest (most compressing) edge per (from, to) pair and tracks observation
 * frequency for weight calculation.
 */
public class TransitionGraph {
    private final Map<Integer, List<TransitionEdge>> adjacency;
    private final long[][] frequencyMatrix;
    private final OperatorLibrary lib;

    /**
     * Construct an empty TransitionGraph.
     */
    public TransitionGraph() {
        this.adjacency = new HashMap<>();
        this.frequencyMatrix = new long[256][256];
        this.lib = OperatorLibrary.getInstance();
    }

    /**
     * Observe transitions in a data stream and build the graph.
     *
     * Walks the stream pairwise, updating the frequency matrix, then calls
     * buildEdges() to construct the edge list.
     *
     * @param dataStream the input byte array
     */
    public void observe(byte[] dataStream) {
        if (dataStream == null || dataStream.length < 2) {
            return;
        }

        // Record pairwise transitions
        for (int i = 0; i < dataStream.length - 1; i++) {
            int from = dataStream[i] & 0xFF;
            int to = dataStream[i + 1] & 0xFF;
            frequencyMatrix[from][to]++;
        }

        // Build edges from frequency matrix
        buildEdges();
    }

    /**
     * Build the edge list from the frequency matrix.
     *
     * For each (from, to) pair with frequency > 0, find the cheapest operator
     * (regardless of compression) and create a TransitionEdge.
     *
     * This includes non-compressing edges because they can be valuable as part of CYCLE encoding.
     */
    private void buildEdges() {
        for (int from = 0; from < 256; from++) {
            for (int to = 0; to < 256; to++) {
                long freq = frequencyMatrix[from][to];
                if (freq > 0) {
                    // Use findCheapest to include all operators, not just compressing ones
                    Optional<Transition> optTrans = Transition.findCheapest(from, to, lib);
                    if (optTrans.isPresent()) {
                        Transition trans = optTrans.get();
                        // Weight = bits saved (may be negative for non-compressing)
                        double gain = 8 - trans.costBits();
                        double weight = freq * gain;
                        TransitionEdge edge = new TransitionEdge(
                                from, to, trans.op(), trans.costBits(), freq, weight
                        );
                        addEdge(edge);
                    }
                }
            }
        }
    }

    /**
     * Add an edge to the graph, keeping only the cheapest per (from, to) pair.
     */
    private void addEdge(TransitionEdge edge) {
        List<TransitionEdge> edges = adjacency.computeIfAbsent(edge.fromNode(), k -> new ArrayList<>());

        // Check if we already have an edge for this (from, to) pair
        for (int i = 0; i < edges.size(); i++) {
            TransitionEdge existing = edges.get(i);
            if (existing.fromNode() == edge.fromNode() && existing.toNode() == edge.toNode()) {
                if (edge.costBits() < existing.costBits()) {
                    edges.set(i, edge);
                }
                return;
            }
        }

        edges.add(edge);
    }

    /**
     * Get the best (cheapest) edge from one node to another.
     *
     * @param from source node (0..255)
     * @param to target node (0..255)
     * @return Optional containing the edge, or empty if no edge exists
     */
    public Optional<TransitionEdge> bestEdge(int from, int to) {
        List<TransitionEdge> edges = adjacency.getOrDefault(from, Collections.emptyList());
        return edges.stream()
                .filter(e -> e.toNode() == to)
                .findFirst();
    }

    /**
     * Get all edges from a given node.
     *
     * @param from source node (0..255)
     * @return list of edges (empty if no outgoing edges)
     */
    public List<TransitionEdge> edgesFrom(int from) {
        return adjacency.getOrDefault(from, Collections.emptyList());
    }

    /**
     * Get all edges to a given node.
     *
     * @param to target node (0..255)
     * @return list of edges
     */
    public List<TransitionEdge> edgesTo(int to) {
        List<TransitionEdge> result = new ArrayList<>();
        for (List<TransitionEdge> edges : adjacency.values()) {
            for (TransitionEdge edge : edges) {
                if (edge.toNode() == to) {
                    result.add(edge);
                }
            }
        }
        return result;
    }

    /**
     * Get the number of nodes with outgoing edges.
     *
     * @return node count
     */
    public int nodeCount() {
        return adjacency.size();
    }

    /**
     * Get the total number of edges.
     *
     * @return edge count
     */
    public int edgeCount() {
        return adjacency.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Calculate the average weight of all edges.
     *
     * @return average weight
     */
    public double averageWeight() {
        if (edgeCount() == 0) {
            return 0.0;
        }
        double totalWeight = adjacency.values().stream()
                .flatMap(List::stream)
                .mapToDouble(TransitionEdge::weight)
                .sum();
        return totalWeight / edgeCount();
    }

    /**
     * Export the graph to Graphviz DOT format for visualization.
     *
     * TODO: Implement DOT export.
     *
     * @param outputPath the path where the .dot file will be written
     * @throws IOException if the write fails
     */
    public void exportDot(Path outputPath) throws IOException {
        // TODO: Implement Graphviz DOT export
    }
}
