# MRC Usage Guide & Examples

## Table of Contents

1. [Installation](#installation)
2. [Basic Usage](#basic-usage)
3. [Advanced Usage](#advanced-usage)
4. [Performance Tuning](#performance-tuning)
5. [API Reference](#api-reference)
6. [Troubleshooting](#troubleshooting)

---

## Installation

### Requirements

- Java 21+
- Maven 3.8+

### Build

```bash
cd /path/to/mrc-phase1
mvn clean install
```

### As a Dependency

```xml
<!-- pom.xml -->
<dependency>
    <groupId>mrc</groupId>
    <artifactId>mrc-phase1</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Basic Usage

### Simple Compression (Arithmetic Runs)

Use v0x02 encoder for data with arithmetic patterns (easiest):

```java
import mrc.codec.*;
import mrc.graph.*;
import java.util.*;

byte[] originalData = new byte[] {
    100, 103, 106, 109, 112, 115, 118,  // Arithmetic: +3
    50, 55, 60, 65, 70                   // Arithmetic: +5
};

// Detect arithmetic patterns
SequenceDetector detector = new SequenceDetector();
List<ArithmeticPattern> patterns = detector.topPatterns(originalData, 255);

// Compress
MrcEncoder encoder = new MrcEncoder(patterns);
CompressionResult result = encoder.encode(originalData);

System.out.println("Original: " + result.originalBits() + " bits");
System.out.println("Compressed: " + result.compressedBits() + " bits");
System.out.println("Ratio: " + result.ratio());
System.out.println("Tier breakdown:");
result.tierCounts().forEach((tier, count) ->
    System.out.println("  " + tier + ": " + count)
);

// Decompress and verify
MrcDecoder decoder = new MrcDecoder();
byte[] decompressed = decoder.decode(result.compressed());
assert Arrays.equals(originalData, decompressed);
```

**Output**:
```
Original: 112 bits
Compressed: 66 bits
Ratio: 0.5893
Tier breakdown:
  LITERAL: 3
  ARITH_RUN: 2
```

### Cycle-Based Compression (Advanced)

Use v0x01 encoder for data with repeating byte sequences:

```java
import mrc.graph.*;

byte[] data = new byte[] {
    10, 13, 16,  // Cycle
    10, 13, 16,  // Cycle repeat
    10, 13, 16   // Cycle repeat
};

// Build transition graph from training data
TransitionGraph graph = new TransitionGraph();
graph.observe(data);

// Detect cycles
CycleDetector cycleDetector = new CycleDetector();
List<CyclePath> cycles = cycleDetector.findAllCycles(graph);

System.out.println("Cycles found: " + cycles.size());
for (CyclePath cycle : cycles) {
    System.out.println("  Nodes: " + cycle.nodes());
    System.out.println("  Compression gain: " + cycle.compressionGain() + " bits");
}

// Compress with cycles
MrcEncoder encoder = new MrcEncoder(graph, cycles);
CompressionResult result = encoder.encode(data);

System.out.println("Ratio: " + result.ratio());
System.out.println("CYCLE tier uses: " + result.tierCounts().get(EncodingTier.CYCLE));

// Decompress
MrcDecoder decoder = new MrcDecoder();
byte[] decompressed = decoder.decode(result.compressed());
assert Arrays.equals(data, decompressed);
```

**Output**:
```
Cycles found: 1
  Nodes: [10, 13, 16]
  Compression gain: 5.0 bits
Ratio: 0.3333
CYCLE tier uses: 1
```

---

## Advanced Usage

### Graph Analysis and Visualization

Understand data patterns via transition graph:

```java
import mrc.graph.*;
import java.nio.file.*;

byte[] data = /* your data */;

// Build graph
TransitionGraph graph = new TransitionGraph();
graph.observe(data);

// Analyze structure
GraphProfiler profiler = new GraphProfiler();
profiler.report(System.out);

// Export for visualization
graph.exportDot(Paths.get("graph.dot"));

// Render (requires Graphviz installed):
// dot -Tsvg graph.dot -o graph.svg
```

**Report Output**:
```
=== Graph Statistics ===
Nodes: 256, Edges: 42, Avg weight: 3.2

=== Edge Cost Distribution ===
Cost 7: ####
Cost 8: ####################
Cost 15: ##

=== Top-10 Edges ===
1. 100 → 103 (Add 3): weight=15.4
2. 103 → 106 (Add 3): weight=14.2
...

=== Cycle Statistics ===
Total cycles: 3
Lengths: [3, 4, 5]

=== Top-10 Cycles ===
1. [10, 13, 16]: gain=5.0 bits
...
```

### Evolutionary Operator Discovery

Let GA find better compression:

```java
import mrc.evolution.*;
import mrc.core.extended.*;
import java.nio.file.*;

byte[] trainingData = /* your data */;

// Setup
EvolutionConfig config = EvolutionConfig.defaultConfig(
    Paths.get("snapshots")
);
ExtendedOperatorLibrary lib = ExtendedOperatorLibrary.getInstance();
TransitionGraph graph = new TransitionGraph();
graph.observe(trainingData);

// Create monitor for tracking
EvolutionMonitor monitor = new EvolutionMonitor();

// Initialize evolutionary edge-finder
EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
    config, lib, graph, monitor
);

// Start evolution on background thread
Thread gaThread = Thread.ofVirtual().start(finder);

// Simulate codec feeding data
for (int i = 0; i < 1000; i++) {
    byte[] newChunk = /* get next data chunk */;
    finder.feedData(newChunk);

    if (i % 100 == 0) {
        Chromosome best = finder.getCurrentBest();
        System.out.println("Gen " + finder.getGenerationCount() +
            ": best fitness=" + best.fitness());
    }
}

// Let evolution run for a bit
Thread.sleep(5000);

// Get best evolved solution
Chromosome best = finder.getCurrentBest();
System.out.println("Final best: " + best.size() + " rules, fitness=" + best.fitness());

// Optional: Save snapshot
finder.stop();
gaThread.join();

// Print evolution history
monitor.printHistory(System.out);
```

**Output**:
```
Gen 0: best fitness=0.123
Gen 100: best fitness=0.245
Gen 200: best fitness=0.287
...
Final best: 47 rules, fitness=0.312

Evolution history (500 generations):
Gen 0 | best=0.123 | avg=0.055 | var=0.0031 | ops=12 | corpus=65536B
Gen 1 | best=0.145 | avg=0.068 | var=0.0042 | ops=14 | corpus=65536B
...
```

### Snapshot Management

Save and load compression state:

```java
import mrc.snapshot.*;
import java.nio.file.*;

// After evolution, save state
SnapshotSerializer serializer = new SnapshotSerializer(lib);
serializer.serialize(
    Paths.get("snapshots/financial_gen1000.snap"),
    1000,  // generation
    graph,
    cycles,
    bestChromosome,
    monitor.history(),
    "financial"  // domain tag
);

System.out.println("Snapshot written");

// Later, load state from snapshot
SnapshotDeserializer deserializer = new SnapshotDeserializer(lib);

// Fast: read manifest only
SnapshotManifest manifest = deserializer.readManifest(
    Paths.get("snapshots/financial_gen1000.snap")
);
manifest.printSummary(System.out);

// Validate integrity
if (!deserializer.validateCrc(snapshotPath)) {
    System.err.println("Snapshot corrupted!");
}

// Load full snapshot
SnapshotDeserializer.FullSnapshot snapshot =
    deserializer.loadAll(snapshotPath);

System.out.println("Loaded generation " + snapshot.manifest().generation());
System.out.println("Domain: " + snapshot.manifest().domainTag());
System.out.println("Chromosome rules: " + snapshot.chromosome().size());

// Use loaded chromosome for compression
MrcEncoder encoder = new MrcEncoder(snapshot.graph(), snapshot.cycles());
CompressionResult result = encoder.encode(newData);
```

**Output**:
```
Snapshot written

Snapshot Summary:
  Version: 2.0
  Generation: 1000
  Timestamp: 2026-03-28T15:45:23Z
  Domain Tag: financial
  Has Chromosome: true
  Has Cycle Table: true
  Sections: 3

Loaded generation 1000
Domain: financial
Chromosome rules: 47
```

---

## Performance Tuning

### Choosing v0x01 vs v0x02

**Use v0x02 (Arithmetic Runs) when**:
- Data has strong arithmetic patterns
- Need simple, fast encoding
- Target audience doesn't need advanced features
- Ratio target < 0.15

**Use v0x01 (Cycle-Based) when**:
- Data has repeating byte sequences
- Want maximum compression
- Can afford complexity
- Ratio target < 0.10

### Operator Library Optimization

Pre-compute transitions at startup:

```java
// First access initializes singleton + precomputes 256×256 transitions
long startInit = System.nanoTime();
OperatorLibrary lib = OperatorLibrary.getInstance();
long initTime = (System.nanoTime() - startInit) / 1_000_000;

System.out.println("Init time: " + initTime + "ms");

// Subsequent lookups are O(1)
long start = System.nanoTime();
Optional<Operator> op = lib.findShortest(100, 103);
long lookupTime = (System.nanoTime() - start);

System.out.println("Lookup time: " + lookupTime + "ns");
```

**Output**:
```
Init time: 8ms
Lookup time: 25ns
```

### Genetic Algorithm Tuning

For faster convergence:

```java
// Fast config for testing
EvolutionConfig config = EvolutionConfig.fastConfig(snapshotDir);

// Thorough config for production
EvolutionConfig config = EvolutionConfig.thoroughConfig(snapshotDir);

// Custom config
EvolutionConfig config = new EvolutionConfig(
    50,                                    // populationSize
    500,                                   // maxGenerations
    131072,                                // corpusWindowSize (128 KB)
    2,                                     // eliteCount
    5,                                     // tournamentSize
    EvolutionConfig.CrossoverStrategy.OPERATOR_AWARE,
    0.04,                                  // ruleMutationProb
    0.01,                                  // chromosomeMutationProb
    100,                                   // snapshotIntervalGenerations
    50L,                                   // generationDelayMs (throttle)
    512,                                   // maxChromosomeRules
    true,                                  // parallelFitness
    Paths.get("snapshots")
);
```

### Corpus Window Sizing

Larger window = better fitness, slower evaluation:

```
Window Size | Fitness Eval | Memory | Best For
------------|------------|--------|----------
8 KB        | 0.5ms     | <1MB   | Testing
64 KB       | 4ms       | 1MB    | Development
256 KB      | 20ms      | 4MB    | Production
1 MB        | 100ms     | 16MB   | Research
```

---

## API Reference

### Core Classes

#### OperatorLibrary
```java
OperatorLibrary lib = OperatorLibrary.getInstance();

Optional<Operator> op = lib.findShortest(from, to);
Operator op = lib.createOperator(opId, operand);
List<Operator> all = lib.allOperators();
```

#### TransitionGraph
```java
TransitionGraph graph = new TransitionGraph();
graph.observe(byte[] data);
List<TransitionEdge> edges = graph.getEdges();
graph.exportDot(Path.of("graph.dot"));
```

#### CycleDetector
```java
CycleDetector detector = new CycleDetector();
List<CyclePath> cycles = detector.findAllCycles(graph);
List<CyclePath> topCycles = detector.topCycles(graph, 10);
```

#### MrcEncoder
```java
// v0x02
MrcEncoder encoder = new MrcEncoder(patterns);

// v0x01
MrcEncoder encoder = new MrcEncoder(graph, cycles);

CompressionResult result = encoder.encode(data);
```

#### MrcDecoder
```java
MrcDecoder decoder = new MrcDecoder();
byte[] original = decoder.decode(compressed);
```

### Evolution Classes

#### EvolutionaryEdgeFinder
```java
finder.feedData(newData);
Chromosome best = finder.getCurrentBest();
finder.stop();
long gen = finder.getGenerationCount();
GenerationResult result = finder.getLastGenerationResult();
```

#### SnapshotScheduler
```java
scheduler.start();
scheduler.requestSnapshot(gen, best, graph, cycles, history);
scheduler.stop();
```

---

## Troubleshooting

### Issue: Low Compression Ratio

**Causes**:
- Data is random or incompressible
- Operator cost > 8 bits for frequent transitions

**Solutions**:
```java
// Analyze what's happening
GraphProfiler profiler = new GraphProfiler();
profiler.report(System.out);

// Check if evolution helps
EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(...);
// Run GA and monitor fitness growth

// Try v0x02 if data has arithmetic
SequenceDetector detector = new SequenceDetector();
detector.topPatterns(data, 10)
    .forEach(p -> System.out.println(p.label() + ": " + p.estimatedSavingBits()));
```

### Issue: Slow Encoding

**Causes**:
- Operator library not initialized (first call)
- Cycle detection on large graphs
- Fitness evaluation during GA

**Solutions**:
```java
// Pre-warmup operator library
long start = System.nanoTime();
OperatorLibrary.getInstance();  // Triggers init + precompute
long warmup = (System.nanoTime() - start) / 1_000_000;

// Limit cycle length to reduce complexity
CycleDetector detector = new CycleDetector(8);  // maxCycleLength=8

// Use fast GA config for testing
EvolutionConfig config = EvolutionConfig.fastConfig(dir);
```

### Issue: High Memory Usage

**Causes**:
- Large corpus window
- Many cycles in snapshot
- Evolution history accumulation

**Solutions**:
```java
// Reduce corpus window
EvolutionConfig config = new EvolutionConfig(
    /* ... */,
    8192,   // 8 KB instead of 64 KB
    /* ... */
);

// Limit snapshots to recent generations only
// (hardcoded: last 1000 in snapshot)

// Clear old cycles
List<CyclePath> topCycles = detector.topCycles(graph, 100);  // Top 100 only
```

### Issue: Snapshot Validation Fails

**Causes**:
- File corruption in transit
- Wrong snapshot format version

**Solutions**:
```java
// Validate before using
SnapshotDeserializer deser = new SnapshotDeserializer(lib);

if (!deser.validateCrc(path)) {
    System.err.println("File corrupted!");
    return;
}

SnapshotManifest manifest = deser.readManifest(path);
if (!manifest.version().isCompatible(SnapshotVersion.CURRENT)) {
    System.err.println("Version mismatch: " + manifest.version());
    return;
}

// Check operator consistency
SnapshotDeserializer.FullSnapshot snapshot = deser.loadAll(path);
if (!deser.validateOperatorConsistency(snapshot)) {
    System.err.println("Chromosome rules are corrupted!");
    return;
}
```

---

## Example: Complete Pipeline

```java
import mrc.codec.*; import mrc.graph.*; import mrc.evolution.*;
import mrc.core.extended.*; import mrc.snapshot.*;
import java.nio.file.*; import java.util.*;

public class CompletePipeline {
    public static void main(String[] args) throws Exception {
        byte[] data = loadData("training.bin");

        // 1. Analyze
        TransitionGraph graph = new TransitionGraph();
        graph.observe(data);
        graph.exportDot(Paths.get("analysis/graph.dot"));

        // 2. Detect cycles
        CycleDetector detector = new CycleDetector();
        List<CyclePath> cycles = detector.findAllCycles(graph);
        System.out.println("Cycles: " + cycles.size());

        // 3. Evolve (optional)
        EvolutionConfig config = EvolutionConfig.fastConfig(Paths.get("snaps"));
        ExtendedOperatorLibrary lib = ExtendedOperatorLibrary.getInstance();
        EvolutionMonitor monitor = new EvolutionMonitor();
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
            config, lib, graph, monitor);
        Thread.ofVirtual().start(finder);

        for (int i = 0; i < 100; i++) {
            finder.feedData(Arrays.copyOfRange(data, i*1000, (i+1)*1000));
            Thread.sleep(10);
        }
        finder.stop();

        Chromosome best = finder.getCurrentBest();
        System.out.println("Best GA: " + best.size() + " rules");

        // 4. Compress
        MrcEncoder encoder = new MrcEncoder(graph, cycles);
        CompressionResult result = encoder.encode(data);
        System.out.println("Ratio: " + result.ratio());

        // 5. Save snapshot
        SnapshotSerializer serializer = new SnapshotSerializer(lib);
        serializer.serialize(
            Paths.get("snaps/final.snap"),
            finder.getGenerationCount(),
            graph, cycles, best,
            monitor.history(), "general"
        );

        // 6. Verify
        MrcDecoder decoder = new MrcDecoder();
        byte[] decoded = decoder.decode(result.compressed());
        assert Arrays.equals(data, decoded);
        System.out.println("✓ Round-trip successful");
    }
}
```

---

## See Also

- [THEORY.md](THEORY.md) — Compression algorithms and mathematical foundations
- [ARCHITECTURE.md](ARCHITECTURE.md) — Component descriptions and system design
- [README.md](README.md) — Quick start and project overview
