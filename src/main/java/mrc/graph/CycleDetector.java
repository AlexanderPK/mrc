package mrc.graph;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects all simple cycles in a TransitionGraph using Johnson's algorithm.
 *
 * Restricted to cycles of bounded length and sorted by compression gain.
 * Uses Tarjan's SCC (Strongly Connected Components) as a prerequisite step
 * to identify cycle-bearing subgraphs.
 */
public class CycleDetector {
    private static final int MAX_CYCLES = 1_000_000;
    private static final int N_THREADS = Runtime.getRuntime().availableProcessors();

    private final TransitionGraph graph;
    private final int maxCycleLength;
    private List<CyclePath> allCycles;

    /**
     * Construct a CycleDetector for a graph with maximum cycle length.
     *
     * @param graph the transition graph to analyze
     * @param maxCycleLength the maximum cycle length to detect (default 8 for Phase 1)
     */
    public CycleDetector(TransitionGraph graph, int maxCycleLength) {
        this.graph = graph;
        this.maxCycleLength = maxCycleLength;
        this.allCycles = null;
    }

    /**
     * Find all simple cycles of length 2..maxCycleLength in the graph.
     *
     * Implements Johnson's algorithm:
     * 1. Run Tarjan's SCC to identify cycle-bearing subgraphs
     * 2. For each SCC with more than one node, apply cycle-finding
     * 3. Track visited cycles to avoid duplicates
     * 4. Sort result by compressionGain (descending)
     *
     * @return list of all simple cycles, sorted by compression gain
     */
    public List<CyclePath> findAllCycles() {
        if (allCycles != null) {
            return allCycles;
        }

        allCycles = new ArrayList<>();
        Set<Long> seenHashes = new HashSet<>();

        // Run Tarjan to find SCCs
        TarjanSCC tarjan = new TarjanSCC(graph);
        List<Set<Integer>> sccs = tarjan.compute();

        // For each SCC with more than one node, find cycles
        for (Set<Integer> scc : sccs) {
            if (scc.size() > 1) {
                findCyclesInSCC(scc, seenHashes, allCycles);
            }
        }

        // allCycles is unsorted; topCycles() does partial top-K sort
        return allCycles;
    }

    /**
     * Find all cycles within a single SCC using DFS.
     *
     * @param scc the strongly connected component
     * @param seenCycleNodeSets set of already-found cycle node sets (for deduplication)
     * @param allCycles list to accumulate found cycles
     */
    private void findCyclesInSCC(Set<Integer> scc, Set<Long> seenHashes, List<CyclePath> allCycles) {
        List<Integer> startNodes = new ArrayList<>(scc);
        int nBatches = Math.min(N_THREADS, startNodes.size());
        AtomicInteger totalCount = new AtomicInteger(allCycles.size());

        // Partition start nodes into nBatches groups — one task per thread
        List<Future<List<CyclePath>>> futures = new ArrayList<>(nBatches);
        ExecutorService pool = Executors.newFixedThreadPool(nBatches);
        try {
            int batchSize = (startNodes.size() + nBatches - 1) / nBatches;
            for (int b = 0; b < nBatches; b++) {
                int from = b * batchSize;
                if (from >= startNodes.size()) break;
                int to   = Math.min(from + batchSize, startNodes.size());
                List<Integer> batch = startNodes.subList(from, to);
                futures.add(pool.submit(() -> {
                    List<CyclePath> localCycles = new ArrayList<>();
                    Set<Long> localSeen = new HashSet<>();
                    for (int start : batch) {
                        if (totalCount.get() >= MAX_CYCLES) break;
                        List<Integer> path = new ArrayList<>();
                        path.add(start);
                        dfsForCycles(start, start, path, new ArrayList<>(), scc, localSeen, localCycles, totalCount);
                    }
                    return localCycles;
                }));
            }

            // Merge results with cross-batch deduplication (hash-based, no object allocation)
            for (Future<List<CyclePath>> future : futures) {
                for (CyclePath cycle : future.get()) {
                    if (allCycles.size() >= MAX_CYCLES) return;
                    if (seenHashes.add(nodeSetHash(cycle.nodes()))) {
                        allCycles.add(cycle);
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Cycle detection interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Depth-first search to find cycles from a starting node within an SCC.
     *
     * @param start the starting node (where we're looking to return)
     * @param current the current node in the search
     * @param path the current path from start to current
     * @param pathEdges the edges traversed in the current path
     * @param scc the SCC being searched (stays within this)
     * @param seenCycleNodeSets deduplication set
     * @param allCycles output list
     */
    private void dfsForCycles(int start, int current, List<Integer> path, List<TransitionEdge> pathEdges,
                             Set<Integer> scc, Set<Long> localSeen, List<CyclePath> localCycles,
                             AtomicInteger totalCount) {
        if (path.size() > maxCycleLength || totalCount.get() >= MAX_CYCLES) {
            return;
        }

        List<TransitionEdge> outgoing = graph.edgesFrom(current);
        for (TransitionEdge edge : outgoing) {
            if (totalCount.get() >= MAX_CYCLES) return;
            int next = edge.toNode();

            if (!scc.contains(next)) {
                continue; // Only explore within the SCC
            }

            if (next == start && path.size() > 1) {
                // Found a cycle back to start
                if (localSeen.add(nodeSetHash(path))) {
                    localCycles.add(buildCyclePath(path, pathEdges, edge));
                    int count = totalCount.incrementAndGet();
                    if (count % (MAX_CYCLES/10) == 0) {
                        System.out.printf("[CycleDetector] %,d / %,d cycles found (%.1f%%)%n",
                                count, MAX_CYCLES, 100.0 * count / MAX_CYCLES);
                    }
                }
            } else if (!path.contains(next) && path.size() < maxCycleLength) {
                // Continue DFS - only if we haven't visited this node in this path
                path.add(next);
                pathEdges.add(edge);
                dfsForCycles(start, next, path, pathEdges, scc, localSeen, localCycles, totalCount);
                path.remove(path.size() - 1);
                pathEdges.remove(pathEdges.size() - 1);
            }
        }
    }

    /**
     * Build a CyclePath record from a found cycle.
     *
     * @param path the nodes in the cycle (starting node first)
     * @param pathEdges the edges traversed to build the path
     * @param closingEdge the final edge that closes the cycle back to start
     * @return the constructed CyclePath
     */
    private CyclePath buildCyclePath(List<Integer> path, List<TransitionEdge> pathEdges, TransitionEdge closingEdge) {
        List<Integer> cycleNodes = new ArrayList<>(path);
        List<TransitionEdge> cycleEdges = new ArrayList<>(pathEdges);
        cycleEdges.add(closingEdge);

        int length = cycleNodes.size();
        double totalWeight = cycleEdges.stream().mapToDouble(TransitionEdge::weight).sum();
        double compressionGain = (length * 8.0) - cycleEdges.stream().mapToDouble(TransitionEdge::costBits).sum();

        return new CyclePath(cycleNodes, cycleEdges, length, totalWeight, compressionGain);
    }

    /**
     * Get the top-K cycles by compression gain.
     *
     * @param k the number of top cycles to return
     * @return list of top-k cycles
     */
    public List<CyclePath> topCycles(int k) {
        List<CyclePath> all = findAllCycles();
        // Only consider cycles that actually save bits
        // Partial sort: min-heap of size k keeps the k largest gains — O(n log k)
        PriorityQueue<CyclePath> heap = new PriorityQueue<>(k,
                Comparator.comparingDouble(CyclePath::compressionGain));
        for (CyclePath c : all) {
            if (c.compressionGain() <= 0) continue;
            if (heap.size() < k) {
                heap.offer(c);
            } else if (c.compressionGain() > heap.peek().compressionGain()) {
                heap.poll();
                heap.offer(c);
            }
        }
        List<CyclePath> result = new ArrayList<>(heap);
        result.sort((a, b) -> Double.compare(b.compressionGain(), a.compressionGain()));
        return result;
    }

    /** Order-independent hash of a node list — no object allocation. */
    private static long nodeSetHash(List<Integer> nodes) {
        long h = 0;
        for (int n : nodes) {
            // XOR with a position-independent mixing function
            h ^= n * 0x9e3779b97f4a7c15L;
        }
        return h;
    }

    /**
     * Build a map of cycles indexed by node value.
     *
     * For each node, returns the list of cycles that pass through that node.
     * Used by the encoder for fast cycle lookup.
     *
     * @return map from node value to list of cycles containing that node
     */
    public Map<Integer, List<CyclePath>> cyclesByNode() {
        Map<Integer, List<CyclePath>> result = new HashMap<>();
        List<CyclePath> cycles = findAllCycles();

        for (CyclePath cycle : cycles) {
            for (int node : cycle.nodes()) {
                result.computeIfAbsent(node, k -> new ArrayList<>()).add(cycle);
            }
        }

        return result;
    }

    /**
     * Internal implementation of Tarjan's SCC algorithm.
     *
     * Finds strongly connected components in a directed graph using Tarjan's algorithm.
     * Time complexity: O(V + E) where V is nodes and E is edges.
     */
    private static class TarjanSCC {
        private final TransitionGraph graph;
        private final List<Set<Integer>> sccs;
        private int index = 0;
        private final Stack<Integer> stack;
        private final Map<Integer, Integer> indices;
        private final Map<Integer, Integer> lowlinks;

        TarjanSCC(TransitionGraph graph) {
            this.graph = graph;
            this.sccs = new ArrayList<>();
            this.stack = new Stack<>();
            this.indices = new HashMap<>();
            this.lowlinks = new HashMap<>();
        }

        /**
         * Compute all SCCs.
         *
         * Runs Tarjan's algorithm on all nodes in the graph, returning a list of
         * strongly connected components, each represented as a set of nodes.
         *
         * @return list of SCCs
         */
        List<Set<Integer>> compute() {
            // Collect all nodes that appear in the graph (have edges)
            Set<Integer> allNodes = new HashSet<>();
            for (int i = 0; i < 256; i++) {
                if (!graph.edgesFrom(i).isEmpty()) {
                    allNodes.add(i);
                }
                if (!graph.edgesTo(i).isEmpty()) {
                    allNodes.add(i);
                }
            }

            // Run Tarjan's algorithm on all unvisited nodes
            Set<Integer> onStack = new HashSet<>();
            for (int node : allNodes) {
                if (!indices.containsKey(node)) {
                    strongConnect(node, onStack);
                }
            }

            return sccs;
        }

        /**
         * Visit a node and its successors in the DFS tree.
         *
         * @param v the current node
         * @param onStack set of nodes currently on the recursion stack
         */
        private void strongConnect(int v, Set<Integer> onStack) {
            indices.put(v, index);
            lowlinks.put(v, index);
            index++;
            stack.push(v);
            onStack.add(v);

            // Consider successors of v
            List<TransitionEdge> outgoing = graph.edgesFrom(v);
            for (TransitionEdge edge : outgoing) {
                int w = edge.toNode();
                if (!indices.containsKey(w)) {
                    // Successor w has not yet been visited; recurse on it
                    strongConnect(w, onStack);
                    lowlinks.put(v, Math.min(lowlinks.get(v), lowlinks.get(w)));
                } else if (onStack.contains(w)) {
                    // Successor w is in stack and hence in the current SCC
                    lowlinks.put(v, Math.min(lowlinks.get(v), indices.get(w)));
                }
            }

            // If v is a root node, pop the stack and record an SCC
            if (lowlinks.get(v).equals(indices.get(v))) {
                Set<Integer> component = new HashSet<>();
                while (true) {
                    int w = stack.pop();
                    onStack.remove(w);
                    component.add(w);
                    if (w == v) break;
                }
                sccs.add(component);
            }
        }
    }
}
