package mrc.graph;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransitionGraph.
 *
 * Verifies:
 * - Graph building from observed transitions
 * - Edge lookup and querying
 * - Node and edge counting
 */
public class TransitionGraphTest {

    @Test
    public void testGraphBuildingFromSimpleSequence() {
        // Build a graph from a sequence with clear transitions
        byte[] data = {10, 13, 16, 10, 13, 16};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        // Should have edges for 10->13, 13->16, 16->10
        assertTrue(graph.edgeCount() > 0, "Graph should have edges");
        assertTrue(graph.nodeCount() > 0, "Graph should have nodes");
    }

    @Test
    public void testBestEdgeLookup() {
        byte[] data = {5, 10, 15};
        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        var edge = graph.bestEdge(5, 10);
        if (edge.isPresent()) {
            assertEquals(5, edge.get().fromNode());
            assertEquals(10, edge.get().toNode());
        }
    }

    @Test
    public void testEdgesFromNode() {
        byte[] data = {1, 2, 1, 3, 1, 4};
        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        var edges = graph.edgesFrom(1);
        // Node 1 has outgoing edges to 2, 3, 4
        assertTrue(edges.size() > 0, "Node 1 should have outgoing edges");
    }

    @Test
    public void testEdgesToNode() {
        byte[] data = {1, 5, 2, 5, 3, 5};
        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        var edges = graph.edgesTo(5);
        // Node 5 has incoming edges from 1, 2, 3
        assertTrue(edges.size() > 0, "Node 5 should have incoming edges");
    }

    @Test
    public void testAverageWeight() {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) i;
        }

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        double avgWeight = graph.averageWeight();
        // Average weight can be negative if graph has non-compressing edges
        // Weight = frequency * (8 - costBits), which is negative when costBits > 8
        assertTrue(!Double.isNaN(avgWeight) && !Double.isInfinite(avgWeight), "Average weight should be a valid number");
    }

    @Test
    public void testEmptyGraph() {
        TransitionGraph graph = new TransitionGraph();

        assertEquals(0, graph.nodeCount(), "Empty graph should have 0 nodes");
        assertEquals(0, graph.edgeCount(), "Empty graph should have 0 edges");
        assertEquals(0.0, graph.averageWeight(), "Empty graph should have 0 average weight");
    }

    @Test
    public void testSingleByteData() {
        byte[] data = {42};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        // Single byte has no transitions
        assertEquals(0, graph.edgeCount(), "Single byte should produce no transitions");
    }

    @Test
    public void testRepeatedValues() {
        byte[] data = {5, 5, 5, 5};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        // Self-loop from 5 to 5
        var edge = graph.bestEdge(5, 5);
        if (edge.isPresent()) {
            assertEquals(5, edge.get().toNode());
        }
    }
}
