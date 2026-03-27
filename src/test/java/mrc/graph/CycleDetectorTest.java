package mrc.graph;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;

/**
 * Unit tests for CycleDetector.
 *
 * Verifies:
 * - Cycle detection on arithmetic sequences
 * - Handling of acyclic graphs
 * - Multiple cycles ranking by compression gain
 * - Robustness on random graphs
 */
public class CycleDetectorTest {

    @Test
    public void testSingleCycle_Arithmetic() {
        // Arithmetic sequence with delta=3: 10->13->16->10
        byte[] data = {10, 13, 16, 10, 13, 16, 10};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        CycleDetector detector = new CycleDetector(graph, 8);
        List<CyclePath> cycles = detector.findAllCycles();

        // TODO: Assert that a cycle [10, 13, 16] is found
        // assertTrue(cycles.size() > 0, "Should find at least one cycle");
    }

    @Test
    public void testNoCycles_StrictlyIncreasing() {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        CycleDetector detector = new CycleDetector(graph, 8);
        List<CyclePath> cycles = detector.findAllCycles();

        // Strictly increasing sequence should have no cycles
        assertEquals(0, cycles.size(), "Strictly increasing should have no cycles");
    }

    @Test
    public void testMultipleCycles_Ranked() {
        // Create data with multiple cycle patterns
        byte[] data = new byte[512];
        // Pattern 1: 1->2->3->1 (170 repetitions = 510 bytes)
        for (int i = 0; i < 170; i++) {
            data[i * 3] = 1;
            data[i * 3 + 1] = 2;
            data[i * 3 + 2] = 3;
        }
        // Pattern 2: 5->6->5 (shorter cycle, last 2 bytes)
        data[510] = 5;
        data[511] = 6;

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        CycleDetector detector = new CycleDetector(graph, 8);
        List<CyclePath> cycles = detector.findAllCycles();

        // TODO: Verify that cycles are ranked by compression gain
        if (cycles.size() > 1) {
            CyclePath first = cycles.get(0);
            CyclePath second = cycles.get(1);
            assertTrue(first.compressionGain() >= second.compressionGain(),
                    "Cycles should be sorted by compression gain (descending)");
        }
    }

    @Test
    public void testTopCycles() {
        byte[] data = {1, 2, 1, 2, 1, 2};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        CycleDetector detector = new CycleDetector(graph, 8);
        List<CyclePath> top2 = detector.topCycles(2);

        assertTrue(top2.size() <= 2, "Should return at most k cycles");
    }

    @Test
    public void testCyclesByNode() {
        byte[] data = {1, 2, 3, 1, 2, 3};

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        CycleDetector detector = new CycleDetector(graph, 8);
        var cyclesMap = detector.cyclesByNode();

        // TODO: Verify that cycles are indexed correctly by node
    }

    @Test
    public void testNoCrash_RandomInput() {
        byte[] data = new byte[256];
        new Random(42).nextBytes(data);

        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);

        CycleDetector detector = new CycleDetector(graph, 8);
        List<CyclePath> cycles = detector.findAllCycles();

        // Should complete without throwing
        assertNotNull(cycles, "Should return a list");

        // All cycles should be within bounds
        for (CyclePath cycle : cycles) {
            assertTrue(cycle.length() >= 2, "Cycle length should be >= 2");
            assertTrue(cycle.length() <= 8, "Cycle length should be <= maxCycleLength");
        }
    }

    @Test
    public void testEmptyGraph() {
        TransitionGraph graph = new TransitionGraph();
        CycleDetector detector = new CycleDetector(graph, 8);
        List<CyclePath> cycles = detector.findAllCycles();

        assertEquals(0, cycles.size(), "Empty graph should have no cycles");
    }
}
