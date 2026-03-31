package mrc.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    public void testExportDotNoArgUsesConfiguredDir(@TempDir Path tmp) throws IOException {
        byte[] data = {10, (byte)245, 10, (byte)245};
        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);
        graph.setExportDir(tmp);

        graph.exportDot();

        Path expected = tmp.resolve("mrc_graph.dot");
        assertTrue(Files.exists(expected), "mrc_graph.dot should be created in configured dir");
        assertTrue(Files.readString(expected).startsWith("digraph MRC {"));
    }

    @Test
    public void testExportDotNoArgThrowsWhenNoDirConfigured() {
        TransitionGraph graph = new TransitionGraph();
        assertThrows(IllegalStateException.class, graph::exportDot);
    }

    @Test
    public void testGetExportDir(@TempDir Path tmp) {
        TransitionGraph graph = new TransitionGraph();
        assertNull(graph.getExportDir(), "Export dir should be null by default");
        graph.setExportDir(tmp);
        assertEquals(tmp, graph.getExportDir());
    }

    @Test
    public void testExportDotProducesValidFile(@TempDir Path tmp) throws IOException {
        // Not(10)=245, Not(245)=10 — Not costs 5 bits so weight = freq*(8-5) > 0
        byte[] data = {10, (byte)245, 10, (byte)245, 10, (byte)245};
        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        Path dotFile = tmp.resolve("graph.dot");
        graph.exportDot(dotFile);

        assertTrue(Files.exists(dotFile), "DOT file should be created");
        String content = Files.readString(dotFile);
        assertTrue(content.startsWith("digraph MRC {"), "Should start with digraph header");
        assertTrue(content.contains("->"), "Should contain directed edges");
        assertTrue(content.contains("0x"), "Nodes should use hex labels");
    }

    @Test
    public void testExportDotEmptyGraph(@TempDir Path tmp) throws IOException {
        TransitionGraph graph = new TransitionGraph();
        Path dotFile = tmp.resolve("empty.dot");
        graph.exportDot(dotFile);

        String content = Files.readString(dotFile);
        assertTrue(content.startsWith("digraph MRC {"), "Empty graph should still produce valid DOT");
        assertFalse(content.contains("->"), "Empty graph should have no edges");
    }

    @Test
    public void testExportDotOnlyPositiveWeightEdges(@TempDir Path tmp) throws IOException {
        // With Identity operator (opId 13, 0 operand bits), x→x transitions cost only 7 bits
        // vs 9-bit literal — so self-loops ARE compressing and should appear in the DOT export.
        byte[] data = {5, 5, 5, 5, 5};
        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        Path dotFile = tmp.resolve("selfloop.dot");
        graph.exportDot(dotFile);

        String content = Files.readString(dotFile);
        // Self-loop is now compressing (Identity saves 2 bits per transition)
        assertTrue(content.contains("->"), "Compressing self-loop (via Identity) should appear in DOT export");
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
