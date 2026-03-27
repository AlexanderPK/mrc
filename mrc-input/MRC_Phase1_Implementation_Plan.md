# MRC Phase 1 — Directed Transition Graph with Cycle Detection
## Implementation Plan for Claude Code (Java)

---

## Project overview

Build a self-contained Java library that:
1. Ingests a stream of 8-bit unsigned integer values
2. Constructs a labeled directed multigraph of algebraic transitions
3. Detects cycles in that graph and scores them by compression gain
4. Encodes a data stream using the best cycles + relational edges + literals
5. Decodes the bitstream back to the original data (lossless verification)
6. Runs a randomized baseline test suite to validate overhead floor

**Target JDK:** Java 21 (records, sealed interfaces, pattern matching)
**Build tool:** Maven
**No external dependencies** except JUnit 5 for tests and JMH for benchmarks

---

## Repository structure

```
mrc-phase1/
├── pom.xml
├── README.md
└── src/
    ├── main/java/mrc/
    │   ├── core/
    │   │   ├── Operator.java
    │   │   ├── OperatorLibrary.java
    │   │   ├── Transition.java
    │   │   └── ValueCluster.java
    │   ├── graph/
    │   │   ├── TransitionGraph.java
    │   │   ├── TransitionEdge.java
    │   │   ├── CycleDetector.java
    │   │   ├── CyclePath.java
    │   │   └── GraphProfiler.java
    │   ├── codec/
    │   │   ├── MrcEncoder.java
    │   │   ├── MrcDecoder.java
    │   │   ├── BitStreamWriter.java
    │   │   ├── BitStreamReader.java
    │   │   ├── EncodingTier.java
    │   │   └── CompressionResult.java
    │   └── bench/
    │       ├── RandomBaselineSuite.java
    │       └── CompressionBenchmark.java
    └── test/java/mrc/
        ├── core/OperatorLibraryTest.java
        ├── graph/TransitionGraphTest.java
        ├── graph/CycleDetectorTest.java
        └── codec/RoundTripTest.java
```

---

## Module 1 — `core/` : Operator and value model

### `Operator.java`
```
Sealed interface with permitted implementations:
  - Add(int operand)
  - Sub(int operand)
  - Mul(int operand)
  - Div(int operand)       // integer division, guarded against div-by-zero
  - Mod(int operand)
  - XorOp(int operand)
  - AndOp(int operand)
  - OrOp(int operand)
  - ShiftLeft(int bits)    // bits in [1..7]
  - ShiftRight(int bits)
  - Not()                  // unary, no operand

Each implementation must provide:
  int apply(int x)         // apply to 8-bit value, result masked to 0xFF
  byte opId()              // unique 5-bit ID (0..30), used in bitstream
  int operandBits()        // how many bits the operand occupies in the stream
                           // (0 for Not, 3 for shifts, 8 for arithmetic)
  String toExpression(String varName)  // e.g. "x + 47" for display
```

### `OperatorLibrary.java`
```
Singleton registry that:
  - Holds all Operator instances keyed by opId
  - Provides: Operator byId(byte id)
  - Provides: List<Operator> all()
  - Provides: Optional<Operator> findShortest(int from, int to)
      // brute-force over all operators to find the one with minimal
      // (operandBits + 5) that maps from -> to exactly, 8-bit masked
      // Returns empty if no single operator maps from -> to
  - Static initializer builds all concrete instances for all valid operands
    e.g. Add(0)..Add(255), ShiftLeft(1)..ShiftLeft(7), etc.
```

### `Transition.java`
```
Record: Transition(int from, int to, Operator op, int costBits)
  - costBits = 5 (opId) + op.operandBits()
  - boolean isCompressing()  // costBits < 8 (saves bits vs. raw literal)
  - static Optional<Transition> find(int from, int to, OperatorLibrary lib)
      // returns the cheapest compressing transition if one exists
```

### `ValueCluster.java`
```
Record: ValueCluster(int centroid, int radius)
  - boolean contains(int value)   // |value - centroid| <= radius
  - static List<ValueCluster> partition(int clusterCount)
      // divides 0..255 into clusterCount equal-radius clusters
      // Phase 1 default: clusterCount = 256 (one value per cluster, no fuzzy)
      // Placeholder for future fuzzy-algebra layer
```

---

## Module 2 — `graph/` : Directed transition graph

### `TransitionEdge.java`
```
Record: TransitionEdge(
    int fromNode,        // 8-bit value (0..255)
    int toNode,          // 8-bit value (0..255)
    Operator op,
    int costBits,        // bits needed to encode this edge in the stream
    long frequency,      // how many times this transition was observed
    double weight        // = frequency * (8 - costBits) — compression gain
)
  - boolean dominates(TransitionEdge other)
      // true if same from/to pair but this edge has lower costBits
```

### `TransitionGraph.java`
```
Class backed by a 256x256 adjacency structure:
  - Internal: Map<Integer, List<TransitionEdge>> adjacency
              (key = fromNode, value = all edges from that node)
  - Internal: long[][] frequencyMatrix  // [256][256] raw observation counts

  Build methods:
    void observe(int[] dataStream)
      // walks dataStream pairwise, increments frequencyMatrix[a][b]
      // then calls buildEdges()

    private void buildEdges()
      // for each (from, to) pair with frequency > 0:
      //   calls Transition.find(from, to)
      //   if compressing: creates TransitionEdge and adds to adjacency
      //   keeps only the single cheapest edge per (from, to) pair

  Query methods:
    Optional<TransitionEdge> bestEdge(int from, int to)
    List<TransitionEdge> edgesFrom(int from)
    List<TransitionEdge> edgesTo(int to)
    int nodeCount()
    int edgeCount()
    double averageWeight()

  Export:
    void exportDot(Path outputPath)
      // writes Graphviz .dot file for visualization
      // edge labels = op.toExpression("x") + " [w=" + weight + "]"
```

### `CyclePath.java`
```
Record: CyclePath(
    List<Integer> nodes,        // ordered list of values forming the cycle
    List<TransitionEdge> edges, // edges traversed (length == nodes.length)
    int length,                 // number of edges (= nodes.length for a cycle)
    double totalWeight,         // sum of edge weights
    double compressionGain      // bits saved per full cycle traversal
)
  - int phaseOf(int value)
      // returns index of value in nodes, or -1 if not a member
  - boolean containsNode(int value)
  - String toExpression()
      // e.g. "37 --(+3)--> 40 --(+3)--> 43 --(+3)--> 37"
```

### `CycleDetector.java`
```
Class implementing Johnson's algorithm for finding all simple cycles
in the TransitionGraph, bounded by max cycle length.

  Constructor: CycleDetector(TransitionGraph graph, int maxCycleLength)
    // maxCycleLength default = 8 for Phase 1

  List<CyclePath> findAllCycles()
    // runs Johnson's algorithm restricted to nodes with degree >= 1
    // returns all simple cycles of length 2..maxCycleLength
    // sorted descending by compressionGain

  List<CyclePath> topCycles(int k)
    // returns top-k cycles by compressionGain

  Map<Integer, List<CyclePath>> cyclesByNode()
    // index: for each node value, which cycles pass through it
    // used by encoder for fast cycle lookup

Implementation notes:
  - Johnson's algorithm: O((V + E)(C + 1)) where C = number of cycles
  - For 256 nodes and bounded length 8, this is tractable
  - Use Tarjan's SCC as the first step (Johnson's prerequisite)
  - Implement Tarjan's SCC internally in a private static class
```

### `GraphProfiler.java`
```
Utility class that prints a report about a built TransitionGraph:

  static void report(TransitionGraph g, List<CyclePath> cycles, PrintStream out)

  Report sections:
    1. Graph statistics
       - total nodes with outgoing edges
       - total compressing edges
       - edge cost distribution (histogram: costBits 1..8)
       - top 10 edges by weight

    2. Cycle statistics
       - total cycles found
       - cycle length distribution (count per length 2..8)
       - top 10 cycles by compressionGain with toExpression()

    3. Coverage estimate
       - what fraction of observed transitions are covered by top-K cycles
       - what fraction are covered by any compressing edge
       - estimated compression ratio if all covered transitions use best encoding
```

---

## Module 3 — `codec/` : Encoder and decoder

### `EncodingTier.java`
```
Enum:
  CYCLE    // value is part of an active cycle traversal
  RELATIONAL  // value encoded as prev + operator
  LITERAL     // raw 8-bit value, no match found

Each tier has:
  byte flagBits()     // CYCLE=110, RELATIONAL=10, LITERAL=0  (variable length)
  int flagBitCount()  // number of flag bits: 3, 2, 1
```

### `BitStreamWriter.java`
```
Class wrapping a ByteArrayOutputStream with bit-level writes:
  void writeBit(int bit)
  void writeBits(long value, int count)   // MSB first
  void writeByte(int value)               // writes exactly 8 bits
  void flush()                            // pads final byte with zeros, flushes
  byte[] toByteArray()
  int totalBitsWritten()
```

### `BitStreamReader.java`
```
Class wrapping a byte array with bit-level reads:
  int readBit()
  long readBits(int count)
  int readByte()
  boolean hasMore()
  int totalBitsRead()
```

### `CompressionResult.java`
```
Record: CompressionResult(
    byte[] originalData,
    byte[] compressedData,
    int originalBits,
    int compressedBits,
    double ratio,              // compressedBits / originalBits
    double spaceSaving,        // 1.0 - ratio
    Map<EncodingTier, Long> tierUsageCounts,
    List<String> cyclesUsed,   // toExpression() of each cycle invoked
    long encodingNanos,
    long decodingNanos
)
  void printSummary(PrintStream out)
```

### `MrcEncoder.java`
```
Constructor: MrcEncoder(TransitionGraph graph, List<CyclePath> cycles)

  CompressionResult encode(byte[] input)

  Encoding algorithm (per-value state machine):
    State: { lastValue, activeCycle, cyclePhase, cycleRepeatCount }

    For each input byte value:

      1. If activeCycle != null:
           check if value == activeCycle.nodes.get((cyclePhase+1) % cycle.length)
           if YES: advance cyclePhase, increment cycleRepeatCount, continue
           if NO:  flush the active cycle run first (write CYCLE flag + cycleId
                   + repeatCount), then clear activeCycle and fall through

      2. Try to start a new cycle:
           look up cyclesByNode.get(value)
           find the highest-weight cycle where lastValue is the preceding node
           if found AND cycle compressionGain > relational gain: activate it

      3. Try relational encoding:
           graph.bestEdge(lastValue, value)
           if found AND edge.costBits < 8: write RELATIONAL flag + opId + operand

      4. Fallback literal:
           write LITERAL flag + 8-bit value

  Bitstream header (written once at start):
    - Magic bytes: 0x4D 0x52 0x43 ("MRC")
    - Version: 1 byte = 0x01
    - Cycle table: 2 bytes = number of cycles embedded
    - For each cycle: 1 byte length + length bytes (node values)
                     + length bytes (opIds)
    - 4 bytes: original data length (big-endian int)
```

### `MrcDecoder.java`
```
Constructor: MrcDecoder()  // stateless; reads cycle table from stream header

  byte[] decode(byte[] compressed) throws MrcFormatException

  Decoding algorithm:
    1. Read and validate header, reconstruct cycle table
    2. State: { lastValue, activeCycle, cyclePhase, remainingRepeats }
    3. For each encoded token:
         read flag bits to determine tier:
           starts with 0   -> LITERAL:    read 8 bits, emit value
           starts with 10  -> RELATIONAL: read 5-bit opId, read operand bits,
                                          apply op to lastValue, emit result
           starts with 110 -> CYCLE:      read cycle index (log2(cycleCount) bits)
                                          read repeat count (16 bits)
                                          emit cycle.length * repeatCount values
                                          by walking the cycle nodes
    4. Stop when originalDataLength bytes have been emitted
```

---

## Module 4 — `bench/` : Validation and benchmarks

### `RandomBaselineSuite.java`
```
Static test suite run as a main() entry point:

  void runAll(PrintStream out)

  Tests:
    1. uniformRandom(int length)
       - generate length bytes using SecureRandom
       - encode + decode, verify round-trip
       - assert: compressedBits <= originalBits * 1.02  (max 2% overhead)
       - assert: ratio > 0.98

    2. lcgStream(int length)
       - generate using linear congruential generator (a=1664525, c=1013904223)
       - encode + decode, verify round-trip
       - report: does MRC detect the LCG recurrence as a cycle?
       - expected: LCG has algebraic structure; some compression possible

    3. mersenneTwister(int length)
       - generate using java.util.Random (MT-based in JDK)
       - encode + decode, verify round-trip
       - report: overhead measurement

    4. gaussianQuantized(int length)
       - generate doubles from N(128, 30), round to int, clamp to 0..255
       - this has real transition structure — expect meaningful compression
       - report: ratio, top cycles used

    5. arithmeticSequence(int length, int delta)
       - generate: v[i] = (v[0] + i*delta) mod 256
       - expected: single cycle of length gcd(delta,256) dominates
       - assert: ratio < 0.30  (very high compression expected)

    6. sineQuantized(int length, double freq)
       - generate: v[i] = (int)(127.5 + 127.5 * sin(2*pi*freq*i)) 
       - expected: cycle detection captures periodic structure
       - report: ratio vs. delta-only encoding

  Each test prints: testName | length | ratio | spaceSaving% | topCycle | pass/FAIL
```

### `CompressionBenchmark.java`
```
JMH benchmark class (optional, run separately):
  @Benchmark encodeUniformRandom_1MB()
  @Benchmark encodeArithmeticSequence_1MB()
  @Benchmark encodeSineQuantized_1MB()
  @Benchmark graphBuild_10MB()
  @Benchmark cycleDetection_fullGraph()

  Warmup: 3 iterations x 1s
  Measurement: 5 iterations x 2s
  Output: throughput in MB/s
```

---

## Test specifications

### `RoundTripTest.java`
```
JUnit 5 parameterized tests:

  @ParameterizedTest
  @MethodSource("streamProvider")
  void roundTrip_decodedMatchesOriginal(byte[] input)

  Stream provider generates:
    - all-zeros (256 bytes)
    - all-same-value (256 bytes, value=137)
    - counting up 0..255 repeated 4x
    - random 1024 bytes (seeded, deterministic)
    - alternating two values (e.g. 50, 100 x 512)
    - real pattern: sine wave 512 bytes

  Each test:
    1. Build graph from input (observe)
    2. Find cycles
    3. Encode
    4. Decode
    5. assertArrayEquals(original, decoded)
    6. log ratio to console
```

### `CycleDetectorTest.java`
```
  @Test void singleCycle_arithmetic()
    - input: [10, 13, 16, 10, 13, 16, 10, 13, 16]  (cycle: +3 mod 256)
    - assert: detector finds cycle [10 -> 13 -> 16] with Add(3)
    - assert: cycle length == 3

  @Test void noCycles_strictlyIncreasing()
    - input: [1,2,3,4,5,6,7,8]
    - assert: no cycles found (no repeated transitions)

  @Test void multipleCycles_ranked()
    - input: interleaved arithmetic and alternating patterns
    - assert: topCycles(2) returns two distinct cycles
    - assert: first cycle has higher compressionGain than second

  @Test void cycleDetector_noCrash_randomInput()
    - input: 256 bytes from Random(seed=42)
    - assert: completes without exception
    - assert: all returned cycles have length >= 2 and length <= maxCycleLength
```

---

## pom.xml requirements

```xml
<properties>
  <java.version>21</java.version>
  <junit.version>5.10.2</junit.version>
  <jmh.version>1.37</jmh.version>
</properties>

<dependencies>
  <!-- JUnit 5 -->
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${junit.version}</version>
    <scope>test</scope>
  </dependency>
  <!-- JMH (benchmark only) -->
  <dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>${jmh.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>${jmh.version}</version>
    <scope>test</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <release>21</release>
        <compilerArgs>
          <arg>--enable-preview</arg>  <!-- for pattern matching on sealed types -->
        </compilerArgs>
      </configuration>
    </plugin>
  </plugins>
</build>
```

---

## Implementation constraints and invariants

### 8-bit masking rule
Every arithmetic result inside `Operator.apply()` must be masked:
```java
return (result & 0xFF);
```
This ensures all values stay in the 0..255 domain. Division by zero must throw
`ArithmeticException` — the library must never register a `Div(0)` instance.

### Compression gain definition
```
compressionGain(edge) = 8 - edge.costBits          // bits saved per transition
compressionGain(cycle) = sum(edge.costBits for e in cycle.edges)
                         subtracted from (cycle.length * 8)
                         // bits saved per full traversal
```
A cycle is only worth encoding if its total costBits < cycle.length * 8.

### Bitstream flag prefix-free property
The three flag prefixes must be prefix-free (Kraft inequality satisfied):
```
LITERAL:    0           (1 bit)
RELATIONAL: 10          (2 bits)
CYCLE:      110         (3 bits)
```
This means the decoder can always unambiguously determine the tier by reading
bits one at a time until a valid prefix is matched.

### Cycle table size limit
The bitstream header embeds the cycle table. The cycle index field in CYCLE tokens
is `ceil(log2(cycleCount))` bits. The encoder must cap the number of embedded
cycles at 255 (fits in 1 byte count field, max index field = 8 bits).
Select the top-255 cycles by compressionGain.

### Graph build is separate from encode
`TransitionGraph.observe()` must be called on a representative training corpus
before encoding any data. For the test suite, use the data itself as both
training and encoding input (compressor knows the data — valid for Phase 1
benchmarking). In production use, train on a separate corpus.

---

## Verification checklist for Claude Code

After implementing each module, verify in this order:

- [ ] `OperatorLibrary`: `findShortest(10, 13)` returns `Add(3)` with costBits=13
- [ ] `OperatorLibrary`: `findShortest(255, 0)` returns `Add(1)` (wraps mod 256)
- [ ] `TransitionGraph`: after observing `[10,13,16,10,13,16]`, edgeCount >= 2
- [ ] `CycleDetector`: finds cycle `[10,13,16]` in above graph
- [ ] `MrcEncoder` + `MrcDecoder`: round-trip on counting sequence `[0..255]`
- [ ] `MrcEncoder` + `MrcDecoder`: round-trip on 1024 random bytes (seed=0)
- [ ] `RandomBaselineSuite.arithmeticSequence(1024, 3)`: ratio < 0.30
- [ ] `RandomBaselineSuite.uniformRandom(4096)`: ratio < 1.02 (no expansion)
- [ ] `GraphProfiler.report()`: prints without exception on any valid graph

---

## Expected compression ratios by data type (Phase 1 targets)

| Data type                     | Expected ratio | Notes                          |
|-------------------------------|----------------|--------------------------------|
| Arithmetic sequence (delta=1) | < 0.15         | Single Add(1) cycle dominates  |
| Arithmetic sequence (delta=3) | < 0.20         | Cycle length = gcd(3,256)=1    |
| Sine wave (quantized)         | 0.30–0.50      | Multiple short cycles          |
| Gaussian random (σ=30)        | 0.60–0.75      | Transition clustering helps    |
| LCG pseudo-random             | 0.70–0.85      | Algebraic structure detectable |
| Uniform random                | 0.98–1.02      | Near-incompressible, overhead  |

---

## What Phase 2 will add (out of scope here, for reference)

- Evolutionary edge-finder (genetic algorithm over operator combinations)
- Periodic snapshot protocol (serialize graph + cycle table to file)
- 16-bit value support (Top-K sparse transition map)
- Fuzzy tolerance layer (ε-matching on edge lookup)
- N-dimensional vector field track (separate module, separate codec)

## sidenotes
The plan is structured as a direct Claude Code input — every class is specified with enough precision that Claude Code can implement it without ambiguity, but without pre-writing the logic itself.
A few decisions in the plan worth noting:
### Why Johnson's algorithm for cycle detection. 
For a 256-node graph with bounded cycle length of 8, Johnson's algorithm is the right choice — it finds all simple cycles in O((V+E)(C+1)) time where C is the cycle count, and the Tarjan SCC pre-step prunes unreachable subgraphs early. A naive DFS would revisit the same paths repeatedly and become too slow once the graph has hundreds of high-frequency edges.
### Why sealed interfaces for Operator. 
Java 21 sealed interfaces combined with pattern matching in switch expressions make the apply() dispatch clean and exhaustive — the compiler enforces that every operator subtype is handled. This matters especially in the decoder where an unknown opId must throw rather than silently produce garbage.
### The training/encoding separation. 
The plan specifies that TransitionGraph.observe() is called on the data before encoding it. For Phase 1 benchmarking this is the same data (the compressor "knows" the stream) — which gives the best possible ratio numbers and validates the architecture. In Phase 2 the training corpus becomes separate, and the snapshot protocol carries the trained graph to the decoder.
### The prefix-free flag design. 
The 0 / 10 / 110 flag structure satisfies the Kraft inequality, which guarantees the decoder can always identify the tier unambiguously without lookahead. This is the property that makes the bitstream self-delimiting.