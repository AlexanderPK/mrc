package mrc.graph;

import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for printing human-readable analysis of a TransitionGraph
 * and its detected cycles.
 *
 * Generates reports on edge statistics, cycle statistics, and coverage estimates.
 */
public class GraphProfiler {

    private GraphProfiler() {
        // Utility class, not instantiable
    }

    /**
     * Print a comprehensive report about the graph and cycles.
     *
     * Generates reporting on:
     * 1. Graph statistics (nodes, edges, cost distribution)
     * 2. Cycle statistics (count, length distribution, top-10 cycles)
     * 3. Coverage estimate (fraction covered by top-K cycles, any compressing edge)
     *
     * @param graph the TransitionGraph to analyze
     * @param cycles the list of detected cycles
     * @param out the output stream
     */
    public static void report(TransitionGraph graph, List<CyclePath> cycles, PrintStream out) {
        out.println("=== TransitionGraph Profile ===");
        out.println();

        // Graph statistics
        out.println("Graph Statistics:");
        out.println("  Nodes with outgoing edges: " + graph.nodeCount());
        out.println("  Total edges: " + graph.edgeCount());
        out.println("  Average edge weight: " + String.format("%.2f", graph.averageWeight()));
        out.println();

        // Edge cost distribution histogram
        reportEdgeCostDistribution(graph, out);

        // Top-10 edges by weight
        reportTopEdges(graph, out);

        // Cycle statistics
        out.println("Cycle Statistics:");
        out.println("  Total cycles found: " + cycles.size());
        out.println();

        // Cycle length distribution
        if (!cycles.isEmpty()) {
            reportCycleLengthDistribution(cycles, out);

            // Top-10 cycles by compression gain
            reportTopCycles(cycles, out);
        } else {
            out.println("  (No cycles found)");
            out.println();
        }

        // Coverage estimate
        out.println("Coverage Estimate:");
        reportCoverage(graph, cycles, out);
        out.println();
    }

    /**
     * Report edge cost distribution.
     */
    private static void reportEdgeCostDistribution(TransitionGraph graph, PrintStream out) {
        Map<Integer, Long> costDistribution = new HashMap<>();

        // Collect all edges and their costs
        for (int i = 0; i < 256; i++) {
            for (TransitionEdge edge : graph.edgesFrom(i)) {
                costDistribution.merge(edge.costBits(), 1L, Long::sum);
            }
        }

        out.println("Edge Cost Distribution:");
        costDistribution.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(e -> {
                    int cost = e.getKey();
                    long count = e.getValue();
                    out.println(String.format("  %2d bits: %3d edges", cost, count));
                });
        out.println();
    }

    /**
     * Report top-10 edges by weight.
     */
    private static void reportTopEdges(TransitionGraph graph, PrintStream out) {
        List<TransitionEdge> allEdges = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            allEdges.addAll(graph.edgesFrom(i));
        }

        out.println("Top-10 Edges by Weight:");
        allEdges.stream()
                .sorted(Comparator.comparingDouble(TransitionEdge::weight).reversed())
                .limit(10)
                .forEach(edge -> {
                    out.println(String.format("  %3d -> %3d: weight=%.2f (%s)",
                            edge.fromNode(), edge.toNode(),
                            edge.weight(),
                            edge.op().toExpression("x")));
                });
        out.println();
    }

    /**
     * Report cycle length distribution.
     */
    private static void reportCycleLengthDistribution(List<CyclePath> cycles, PrintStream out) {
        Map<Integer, Long> lengthDistribution = cycles.stream()
                .collect(Collectors.groupingBy(CyclePath::length, Collectors.counting()));

        out.println("Cycle Length Distribution:");
        lengthDistribution.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(e -> {
                    int length = e.getKey();
                    long count = e.getValue();
                    out.println(String.format("  Length %d: %d cycles", length, count));
                });
        out.println();
    }

    /**
     * Report top-10 cycles by compression gain.
     */
    private static void reportTopCycles(List<CyclePath> cycles, PrintStream out) {
        out.println("Top-10 Cycles by Compression Gain:");
        int count = 0;
        for (CyclePath cycle : cycles) {
            if (count >= 10) break;
            count++;
            out.println(String.format("  Cycle %d: length=%d, gain=%.2f bits",
                    count, cycle.length(), cycle.compressionGain()));
            out.println("    Nodes: " + cycle.nodes());
            out.println("    Expression: " + cycle.toExpression());
        }
        out.println();
    }

    /**
     * Report coverage estimate.
     */
    private static void reportCoverage(TransitionGraph graph, List<CyclePath> cycles, PrintStream out) {
        if (cycles.isEmpty()) {
            out.println("  (No cycles available for coverage analysis)");
            return;
        }

        // Calculate how many nodes are covered by cycles
        Set<Integer> cycleNodes = new HashSet<>();
        for (CyclePath cycle : cycles) {
            cycleNodes.addAll(cycle.nodes());
        }

        int totalNodes = graph.nodeCount();
        int coveredNodes = 0;
        for (int i = 0; i < 256; i++) {
            if (cycleNodes.contains(i) && !graph.edgesFrom(i).isEmpty()) {
                coveredNodes++;
            }
        }

        double nodeCoverage = totalNodes > 0 ? (100.0 * coveredNodes / totalNodes) : 0;

        // Count compressing edges
        long compressingEdges = 0;
        long totalEdges = 0;
        for (int i = 0; i < 256; i++) {
            for (TransitionEdge edge : graph.edgesFrom(i)) {
                totalEdges++;
                if (edge.costBits() < 8) {
                    compressingEdges++;
                }
            }
        }

        double compressingRatio = totalEdges > 0 ? (100.0 * compressingEdges / totalEdges) : 0;

        out.println(String.format("  Nodes in cycles: %d / %d (%.1f%%)", coveredNodes, totalNodes, nodeCoverage));
        out.println(String.format("  Compressing edges: %d / %d (%.1f%%)", compressingEdges, totalEdges, compressingRatio));
    }
}
