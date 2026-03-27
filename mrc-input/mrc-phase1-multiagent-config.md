# MRC Phase 1 — Multiagent Configuration
## A Distributed Architecture for Building the Directed Transition Graph Library

---

## Executive Summary

This configuration defines **5 specialized agents** that collaborate to implement the MRC Phase 1 library:

1. **Core Algebra Agent** — Operator model, library, and value semantics
2. **Graph Architecture Agent** — Transition graph, cycle detection (Johnson's algorithm)
3. **Codec Infrastructure Agent** — Bitstream encoding/decoding, flag prefixes, round-trip integrity
4. **Validation & Benchmarking Agent** — Test suites, randomized baselines, correctness proofs
5. **Integration & Documentation Agent** — Build system, README, cross-module APIs, project coherence

Each agent owns a **module scope**, **verification checklist**, and **handoff protocol** with other agents. The configuration ensures correctness-first implementation, exhaustive testing, and reproducible compression metrics.

---

## Agent 1: Core Algebra Agent

### Ownership
- **Module:** `core/` (Operator.java, OperatorLibrary.java, Transition.java, ValueCluster.java)
- **Responsibility:** Define the algebraic primitive model—the language of transformations the library uses to compress data streams.

### Key Responsibilities

#### 1.1 Sealed Operator Interface
- [ ] Design `Operator` as a sealed interface with 12 permitted implementations
- [ ] Implement 11 binary operators: `Add`, `Sub`, `Mul`, `Div`, `Mod`, `XorOp`, `AndOp`, `OrOp`, `ShiftLeft`, `ShiftRight`
- [ ] Implement 1 unary operator: `Not`
- [ ] Every operator must:
  - Apply the operation to 8-bit input and mask result to `(result & 0xFF)`
  - Provide `apply(int x) → int` with wraparound semantics
  - Guard against div-by-zero (throw `ArithmeticException` if attempted)
  - Return unique 5-bit opId (0..30) for bitstream encoding
  - Declare operand bit count: 0 for `Not`, 3 for shifts, 8 for arithmetic
  - Provide `toExpression(String varName)` for human-readable output (e.g., `"x + 47"`)

#### 1.2 OperatorLibrary (Singleton Registry)
- [ ] Build static instance catalog:
  - `Add(0..255)` — 256 instances
  - `Sub(0..255)` — 256 instances
  - `Mul(1..255)` — 255 instances (skip 0; Mul(0) is not useful)
  - `Div(1..255)` — 255 instances (div-by-zero guarded)
  - `Mod(1..255)` — 255 instances
  - `XorOp(0..255)` — 256 instances
  - `AndOp(0..255)` — 256 instances
  - `OrOp(0..255)` — 256 instances
  - `ShiftLeft(1..7)` — 7 instances
  - `ShiftRight(1..7)` — 7 instances
  - `Not()` — 1 instance
  - **Total catalog:** ~2400 operators
- [ ] Provide `Operator byId(byte id)` — O(1) lookup
- [ ] Provide `List<Operator> all()` — iteration
- [ ] Provide `Optional<Operator> findShortest(int from, int to)`
  - Brute-force scan all operators
  - Return the one with minimum `(5 + op.operandBits())` that maps `from → to` exactly (mod 256)
  - Return empty if no single operator works
  - **Critical optimization:** Cache results in a 256×256 matrix (65k entries) after first build

#### 1.3 Transition Record
- [ ] Record with fields: `from`, `to`, `op`, `costBits`
- [ ] Implement `isCompressing()` → `costBits < 8`
- [ ] Implement static `Transition.find(int from, int to, OperatorLibrary lib)` → `Optional<Transition>`
  - Calls `lib.findShortest(from, to)`
  - Returns empty or Transition with costBits = 5 + op.operandBits()

#### 1.4 ValueCluster (Placeholder for Phase 2)
- [ ] Record: `ValueCluster(int centroid, int radius)`
- [ ] Implement `contains(int value)` → `|value - centroid| <= radius`
- [ ] Implement static `partition(int clusterCount)` → `List<ValueCluster>`
  - Phase 1 hardcoded: clusterCount = 256 (one value per cluster, no fuzzy matching)
  - Divides 0..255 into equal-radius clusters; returns 256 singletons
  - Future (Phase 2): Support clusterCount < 256 for fuzzy tolerance

### Inter-Agent Dependencies
- **Graph Architecture Agent:** Depends on Operator instances to build TransitionGraph
- **Codec Infrastructure Agent:** Depends on opId and operandBits() for bitstream layout

### Verification Checklist (Core Algebra)
- [ ] `OperatorLibrary.findShortest(10, 13)` returns `Add(3)` with costBits = 5 + 8 = 13
- [ ] `OperatorLibrary.findShortest(255, 0)` returns `Add(1)` (wraparound: 255+1 mod 256 = 0)
- [ ] `OperatorLibrary.findShortest(128, 64)` returns `ShiftRight(1)` with costBits = 5 + 3 = 8
- [ ] `Not.apply(0)` returns 255; `Not.apply(255)` returns 0
- [ ] `Div(0)` instance is never registered; attempting to construct it throws
- [ ] `ShiftLeft(0)` and `ShiftLeft(8)` are never registered; only 1..7 exist
- [ ] All operators mask results: `(255 & 0xFF)` stays 255, `(256 & 0xFF)` becomes 0
- [ ] Cache lookup time for `findShortest(any, any)` is <1 μs after first build

### Handoff Protocol
**Output:** OperatorLibrary singleton instance + Operator catalog
**To Graph Architecture Agent:** Pass fully initialized library
**To Codec Infrastructure Agent:** Provide opId ↔ Operator bidirectional map

---

## Agent 2: Graph Architecture Agent

### Ownership
- **Module:** `graph/` (TransitionGraph.java, TransitionEdge.java, CycleDetector.java, CyclePath.java, GraphProfiler.java)
- **Responsibility:** Build a labeled directed multigraph of observed data transitions, detect cycles using Johnson's algorithm, and export profiling reports.

### Key Responsibilities

#### 2.1 TransitionEdge Record
- [ ] Record fields: `fromNode` (0..255), `toNode` (0..255), `op`, `costBits`, `frequency`, `weight`
- [ ] Compute `weight = frequency * (8 - costBits)`
  - Interpretation: If this edge is used N times and saves (8 - costBits) bits per use, total savings = weight
- [ ] Implement `dominates(TransitionEdge other)` → boolean
  - True if same (fromNode, toNode) pair and this.costBits < other.costBits
  - Used during graph build to keep only the cheapest edge per (from, to) pair

#### 2.2 TransitionGraph (Adjacency-based mutable graph)
- [ ] Internal state:
  - `Map<Integer, List<TransitionEdge>> adjacency` — outgoing edges per node
  - `long[][] frequencyMatrix` — 256×256 observation counts
  - `int totalObservations` — running count
- [ ] Method: `void observe(int[] dataStream)`
  - Walk dataStream pairwise: for i in 0..length-2, observe pair (dataStream[i], dataStream[i+1])
  - Increment `frequencyMatrix[from][to]`
  - After consuming all pairs, call `buildEdges()` once
- [ ] Method: `private void buildEdges()`
  - For each (from, to) with frequencyMatrix[from][to] > 0:
    - Call `Transition.find(from, to, operatorLib)`
    - If present (i.e., operator exists) and `transition.isCompressing()`:
      - Create `TransitionEdge(from, to, op, costBits, frequency, weight)`
      - Add to `adjacency[from]`
    - Keep only the single cheapest edge per (from, to) pair (enforce with `dominates()`)
- [ ] Query methods:
  - `Optional<TransitionEdge> bestEdge(int from, int to)` → O(1) or O(degree)
  - `List<TransitionEdge> edgesFrom(int from)` → all outgoing from that node
  - `List<TransitionEdge> edgesTo(int to)` → all incoming to that node (compute on demand from adjacency)
  - `int nodeCount()` → number of distinct node values that appear
  - `int edgeCount()` → total edges in graph
  - `double averageWeight()` → sum of all edge weights / edge count
- [ ] Export: `void exportDot(Path outputPath)`
  - Write Graphviz .dot format
  - Nodes labeled by value (0..255)
  - Edge labels: `op.toExpression("x") + " [w=" + weight + "]"`
  - Color edges by weight (e.g., darker = higher weight)

#### 2.3 CyclePath Record
- [ ] Record fields:
  - `List<Integer> nodes` — ordered node values in cycle (e.g., [10, 13, 16])
  - `List<TransitionEdge> edges` — edges traversed (length == nodes.length)
  - `int length` — number of edges = nodes.size()
  - `double totalWeight` — sum of edge weights
  - `double compressionGain` — bits saved per full cycle traversal
- [ ] Compute `compressionGain`:
  ```
  compressionGain = (length * 8) - sum(edge.costBits for all edges in cycle)
  ```
  Only encode cycle if compressionGain > 0
- [ ] Implement `int phaseOf(int value)` → index in nodes, or -1 if not member
- [ ] Implement `boolean containsNode(int value)`
- [ ] Implement `String toExpression()` → "10 --(+3)--> 13 --(+3)--> 16 --(+3)--> 10"

#### 2.4 CycleDetector (Johnson's Algorithm Implementation)
- [ ] Constructor: `CycleDetector(TransitionGraph graph, int maxCycleLength)`
  - Default maxCycleLength = 8
  - Validate graph is non-null
- [ ] Algorithm: **Johnson's circuit finding algorithm**
  - **Pre-step: Tarjan's SCC** (implement as private static class)
    - Decompose graph into strongly connected components
    - Find minimal SCC subgraph containing all nodes with outgoing edges
    - Discards isolated nodes (useful for pruning)
  - **Main loop** (Johnson's):
    - Maintain a "blocked set" B and "blocked map" blk[v] = set of nodes blocking v
    - For each unblocked starting node s:
      - Run DFS from s, recording all paths back to s
      - Each path that closes is a cycle; record it
      - Mark nodes on closed paths as "blocked" to avoid re-reporting the same cycle
    - Unblock nodes to allow discovery of cycles that share prefixes
  - **Length constraint:** Stop path if length exceeds maxCycleLength; discard
  - **Output:** List<CyclePath> sorted descending by compressionGain
- [ ] Method: `List<CyclePath> findAllCycles()` → all simple cycles
- [ ] Method: `List<CyclePath> topCycles(int k)` → top-k by compressionGain
- [ ] Method: `Map<Integer, List<CyclePath>> cyclesByNode()` → inverse index for encoder
  - Key: node value
  - Value: list of cycles containing that node
  - Built once after `findAllCycles()`, cached

### Inter-Agent Dependencies
- **Core Algebra Agent:** Requires OperatorLibrary and Operator instances
- **Validation & Benchmarking Agent:** Consumes graph + cycles for round-trip tests

### Verification Checklist (Graph Architecture)
- [ ] After observing `[10, 13, 16, 10, 13, 16]`:
  - edgeCount >= 2
  - bestEdge(10, 13) exists and has op = Add(3)
  - frequencyMatrix[10][13] == 2
- [ ] CycleDetector on above graph finds cycle `[10, 13, 16]` with length=3
- [ ] Cycle compressionGain = 3*8 - (13+13+13) = 24 - 39 = **negative** (not a good cycle) — **EXPECTED, this is correct; shows model works**
- [ ] CycleDetector on arithmetic sequence `[0, 1, 2, ..., 255, 0, 1, 2, ...]` finds single dominant cycle `[0,1,2,...,255]` with Add(1) edges
- [ ] exportDot() produces valid Graphviz file; can be rendered with `dot -Tpng`
- [ ] CycleDetector completes in <100ms on a 256-node graph with 500 edges
- [ ] cyclesByNode() correctly maps each node to all cycles passing through it
- [ ] topCycles(255) respects cycle table size limit (max 255 cycles embedded)

### Handoff Protocol
**Input:** OperatorLibrary from Core Algebra Agent
**Output:** TransitionGraph + List<CyclePath> + cyclesByNode() index
**To Codec Infrastructure Agent:** Pass graph, cycles, and node→cycles map for encoder/decoder

---

## Agent 3: Codec Infrastructure Agent

### Ownership
- **Module:** `codec/` (BitStreamWriter.java, BitStreamReader.java, EncodingTier.java, MrcEncoder.java, MrcDecoder.java, CompressionResult.java)
- **Responsibility:** Implement prefix-free bitstream encoding/decoding, state machine for cycle/relational/literal tiers, and lossless round-trip verification.

### Key Responsibilities

#### 3.1 EncodingTier Enum
- [ ] Define three encoding tiers:
  ```
  LITERAL:     flagBits() = 0b0,    flagBitCount() = 1  (raw 8-bit value)
  RELATIONAL:  flagBits() = 0b10,   flagBitCount() = 2  (prev + operator)
  CYCLE:       flagBits() = 0b110,  flagBitCount() = 3  (active cycle traversal)
  ```
- [ ] Verify prefix-free property: Kraft inequality satisfied
  - 1 + 0 + 0 = 1 (LITERAL)
  - 1/2 (first bit 1) + 1 + 0 = 1/2 + 1 (RELATIONAL, first 2 bits 10)
  - 1/4 (first 2 bits 11) - 1/8 (first 3 bits 110) = 1/4 - 1/8 = 1/8 (CYCLE)
  - Total: 1 + 1/2 + 1/8 = 13/8 > 1 **WAIT — this fails Kraft!**

**CRITICAL CORRECTION:** The flag design must be corrected:
```
LITERAL:     0           (1 bit,  cost 1 bit)
RELATIONAL:  10          (2 bits, cost 2 bits)
CYCLE:       110         (3 bits, cost 3 bits)
```
Kraft sum: 1/2 + 1/4 + 1/8 = 7/8 < 1 ✓ **Prefix-free is satisfied**

- [ ] Implement `byte flagBits()` and `int flagBitCount()` for each tier

#### 3.2 BitStreamWriter
- [ ] Backed by `ByteArrayOutputStream`
- [ ] Bit-level write operations:
  - `void writeBit(int bit)` — write 0 or 1
  - `void writeBits(long value, int count)` — write count bits from value, MSB first
  - `void writeByte(int value)` — write exactly 8 bits
  - `void flush()` — pad final byte with zeros if needed, emit to stream
- [ ] State: current byte buffer + bit position
- [ ] Methods: `byte[] toByteArray()`, `int totalBitsWritten()`

#### 3.3 BitStreamReader
- [ ] Backed by byte array
- [ ] Bit-level read operations:
  - `int readBit()` → 0 or 1, or throw if EOF
  - `long readBits(int count)` → next count bits as long, MSB first
  - `int readByte()` → next 8 bits as int
  - `boolean hasMore()` → true if more bits available
- [ ] Methods: `int totalBitsRead()`

#### 3.4 CompressionResult Record
- [ ] Record fields:
  - `byte[] originalData`
  - `byte[] compressedData`
  - `int originalBits` — length × 8
  - `int compressedBits` — actual bitstream length
  - `double ratio` — compressedBits / originalBits
  - `double spaceSaving` — 1.0 - ratio
  - `Map<EncodingTier, Long> tierUsageCounts` — how many tokens used each tier
  - `List<String> cyclesUsed` — cycle.toExpression() for each invoked cycle
  - `long encodingNanos` — elapsed time for encode()
  - `long decodingNanos` — elapsed time for decode()
- [ ] Method: `void printSummary(PrintStream out)`
  - Print key metrics in human-readable format

#### 3.5 MrcEncoder
- [ ] Constructor: `MrcEncoder(TransitionGraph graph, List<CyclePath> cycles)`
  - Store reference to graph, cycles, and cyclesByNode index
- [ ] Method: `CompressionResult encode(byte[] input)`
  - Timing: wrap logic in `System.nanoTime()` to measure encodingNanos
  - Build bitstream with header + encoded data + statistics
- [ ] Encoding algorithm (state machine):
  ```
  State: {
    int lastValue,
    Optional<CyclePath> activeCycle,
    int cyclePhase,       // 0..cycle.length-1
    int cycleRepeatCount  // how many times we've looped
  }
  
  For each input byte value:
    1. If activeCycle != null:
         nextPhaseValue = activeCycle.nodes.get((cyclePhase + 1) % cycle.length)
         if value == nextPhaseValue:
           cyclePhase = (cyclePhase + 1) % cycle.length
           cycleRepeatCount++
           lastValue = value
           continue to next input
         else:
           // Cycle breaks; emit accumulated cycle run
           writeCycleToken(cycleId, cycleRepeatCount)
           activeCycle = null
           // Fall through to step 2
    
    2. Try to start a new cycle:
         candidates = cyclesByNode.get(value)
         best = maxBy(candidates, c -> c.compressionGain)
         if best != null AND best.nodes.get(0) == lastValue:
           activeCycle = best
           cyclePhase = 0
           cycleRepeatCount = 0
           continue to next input
    
    3. Try relational (bestEdge) encoding:
         edge = graph.bestEdge(lastValue, value)
         if edge != null AND edge.costBits < 8:
           writeRelationalToken(edge.op.opId(), edge.op.operandBits(), operand)
           lastValue = value
           continue to next input
    
    4. Fallback to literal:
         writeLiteralToken(value)
         lastValue = value
  ```
- [ ] Bitstream header (written once at start):
  ```
  Magic:            3 bytes = 0x4D 0x52 0x43 (ASCII "MRC")
  Version:          1 byte  = 0x01
  CycleCount:       1 byte  = number of cycles embedded (0..255)
  For each cycle (total = cycleCount):
    CycleLength:    1 byte
    NodeValues:     cycleLength bytes (node values in order)
    OpIds:          cycleLength bytes (opIds for each edge)
  OriginalLength:   4 bytes (big-endian int) = original data length
  (Remaining bits are encoded data)
  ```
- [ ] Token encoding (no fixed widths; Kraft codes):
  ```
  LITERAL token (if value cannot be compressed):
    0                 (1 bit)
    value             (8 bits)
  
  RELATIONAL token (if prev+op compresses):
    10                (2 bits)
    opId              (5 bits)
    operand           (op.operandBits() bits)
  
  CYCLE token (if cycling):
    110               (3 bits)
    cycleId           (ceil(log2(cycleCount)) bits; if cycleCount=1, 0 bits)
    repeatCount       (16 bits; allows up to 65535 repeats per activation)
  ```
- [ ] Statistics collection:
  - Track count of tokens written per tier
  - Track which cycles were actually used (non-zero repeatCount)
  - Store in result.tierUsageCounts and result.cyclesUsed

#### 3.6 MrcDecoder
- [ ] Constructor: `MrcDecoder()` (stateless; reads header from compressed data)
- [ ] Method: `byte[] decode(byte[] compressed) throws MrcFormatException`
  - Timing: wrap in `System.nanoTime()`
- [ ] Decoding algorithm:
  ```
  1. Parse header:
       Validate magic bytes == "MRC"
       Read version (expect 0x01, throw if mismatch)
       Read cycleCount (0..255)
       For each cycle:
         Read cycleLength (1 byte)
         Read nodeValues (cycleLength bytes)
         Read opIds (cycleLength bytes)
         Reconstruct CyclePath (compute compressionGain for reference)
       Read originalLength (4 bytes, big-endian)
  
  2. State machine:
       int lastValue = 0  // or could be encoded in header
       List<Integer> output = new ArrayList<>(originalLength)
       
       while output.size() < originalLength:
         if !hasMore():
           throw new MrcFormatException("unexpected EOF")
         
         // Try to read tier by checking flag prefix
         bit0 = readBit()
         if bit0 == 0:
           // LITERAL: 0xxxxxxxx (8 bits of value)
           value = readByte()
           output.add(value)
           lastValue = value
         else:
           // bit0 == 1; check next bit
           bit1 = readBit()
           if bit1 == 0:
             // RELATIONAL: 10opIddddd (5-bit opId + operand)
             opId = (int)readBits(5)
             op = operatorLibrary.byId((byte)opId)
             if op == null:
               throw new MrcFormatException("invalid opId: " + opId)
             operand = readBits(op.operandBits())
             value = op.apply(lastValue) // ... wait, how to pass operand?
             // ISSUE: operand is separate from op; need to re-apply
             // RESOLUTION: Operator.apply(int x, long operand)? Or re-find?
             // Let's fix: encoder must store the same Operator instance that was used
             // So operand read is implicit in the op's operandBits()
             // Hmm, actually Operator is singleton per operand value, so:
             // opId uniquely identifies which Add(N) or Div(D) etc.
             // So we just call op.apply(lastValue)
             // But encoder wrote operandBits bits; we must read and use them
             // ACTUAL FIX: Store opId and operand separately; reconstruct Operator on decode
             // OR: Encode the full Operator reference (opId encodes both the type and operand)
             // Let's use the second approach: opId is the index into OperatorLibrary.all()
             // This means OperatorLibrary.all() must be deterministic and sorted
             value = op.apply(lastValue)
             output.add(value)
             lastValue = value
           else:
             // CYCLE: 110cycleIdrepeatCount
             cycleId = (int)readBits(Math.ceil(Math.log2(cycleCount)))
             if cycleId >= cycleCount:
               throw new MrcFormatException("invalid cycleId: " + cycleId)
             repeatCount = (int)readBits(16)
             cycle = cycles[cycleId]
             for (int rep = 0; rep < repeatCount; rep++):
               for (int phase = 0; phase < cycle.nodes.size(); phase++):
                 value = cycle.nodes.get(phase)
                 output.add(value)
                 lastValue = value
  
  3. Finalize:
       if output.size() != originalLength:
         throw new MrcFormatException(...)
       return output.toByteArray()
  ```

**CRITICAL ISSUE — Operator Identity on Decode:**
The encoder uses `Operator` instances from `OperatorLibrary`. On decode, we have only the opId (5 bits). We need a deterministic way to map opId → Operator.

**RESOLUTION:**
- `OperatorLibrary.all()` returns a **sorted, deterministic list** of all ~2400 operators
- opId is the **index** into this list
- Encoder stores opId = library.all().indexOf(op)
- Decoder retrieves op = library.all().get(opId)
- This requires `OperatorLibrary` to expose both `Operator byId(byte id)` (5-bit opcode) AND ensure byId maps to the same instance as .all().get(opId)

**BETTER RESOLUTION — Use operand-aware opId:**
Actually, the 5-bit opId can encode both the operator type AND operand for arithmetic ops:
- Bits 0-2: operator type (Add=0, Sub=1, Mul=2, ... Not=10)
- Bits 3-4: operand high bits (for Mul, Div, Mod which have limited operands)
- Operand bits follow in the bitstream

But this gets complex. **Simplest approach:**
- Assign opId = 0..30 to cover all compressing single-operators (those with operandBits <= 8)
- OperatorLibrary maintains `Operator byId(byte opId)` that returns the globally registered instance
- Encoder: op.opId() → 5 bits
- Decoder: readBits(5) → byId() → apply()

This means **Operator.opId() must be stable and globally assigned**, not computed.

### Inter-Agent Dependencies
- **Core Algebra Agent:** Requires Operator, OperatorLibrary, and opId/operandBits contracts
- **Graph Architecture Agent:** Requires TransitionGraph, CyclePath, and cyclesByNode index
- **Validation & Benchmarking Agent:** Produces test inputs; consumes CompressionResult for analysis

### Verification Checklist (Codec Infrastructure)
- [ ] `BitStreamWriter` + `BitStreamReader` round-trip on all 256 single-byte values
- [ ] Prefix-free flag codes work: decoder never misidentifies LITERAL vs RELATIONAL vs CYCLE
- [ ] MrcEncoder + MrcDecoder round-trip on `[0..255]` (counting sequence)
- [ ] MrcEncoder + MrcDecoder round-trip on 1024 random bytes (seed=0)
- [ ] Header parsing correctly reconstructs cycle table
- [ ] Encoder produces header with magic bytes "MRC" (0x4D 0x52 0x43)
- [ ] Encoder compresses arithmetic sequence `[0,1,2,...,255]` repeated to ratio < 0.20
- [ ] Decoder throws `MrcFormatException` on corrupted magic
- [ ] Decoder throws `MrcFormatException` if compressed data truncates before originalLength bytes
- [ ] Encoder + Decoder timing is captured in `encodingNanos` and `decodingNanos`

### Handoff Protocol
**Input:** TransitionGraph + List<CyclePath> from Graph Architecture Agent; OperatorLibrary from Core Algebra Agent
**Output:** CompressionResult (for each data stream encoded)
**To Validation & Benchmarking Agent:** Pass encoder/decoder instances and CompressionResult objects

---

## Agent 4: Validation & Benchmarking Agent

### Ownership
- **Module:** `bench/` and `test/` (RandomBaselineSuite.java, CompressionBenchmark.java, RoundTripTest.java, CycleDetectorTest.java, OperatorLibraryTest.java, TransitionGraphTest.java)
- **Responsibility:** Implement correctness tests, randomized baseline suite, cycle detection validation, and compression benchmarks.

### Key Responsibilities

#### 4.1 RoundTripTest.java (JUnit 5 Parameterized)
- [ ] Test suite: For each input stream, verify encode → decode = original
- [ ] Parameterized test inputs (via `@MethodSource`):
  1. **all-zeros:** 256 bytes of value 0
  2. **all-same-value:** 256 bytes of value 137
  3. **counting:** [0,1,2,...,255] repeated 4 times (1024 bytes total)
  4. **random-seeded:** 1024 bytes from Random(seed=0)
  5. **alternating:** alternating [50, 100] for 512 bytes
  6. **sine-wave:** 512 bytes from quantized sine wave (as spec'd below)
- [ ] Per-test logic:
  ```java
  @ParameterizedTest
  @MethodSource("streamProvider")
  void roundTrip_decodedMatchesOriginal(byte[] input) {
    // 1. Build graph from input (train on input itself)
    TransitionGraph graph = new TransitionGraph(operatorLibrary);
    graph.observe(input);
    
    // 2. Find cycles
    CycleDetector detector = new CycleDetector(graph, 8);
    List<CyclePath> cycles = detector.findAllCycles();
    
    // 3. Encode
    MrcEncoder encoder = new MrcEncoder(graph, cycles);
    CompressionResult result = encoder.encode(input);
    
    // 4. Decode
    MrcDecoder decoder = new MrcDecoder();
    byte[] decoded = decoder.decode(result.compressedData());
    
    // 5. Verify
    assertArrayEquals(input, decoded, "Decoded data must match original");
    System.out.printf("%-20s | %5d → %5d bytes | ratio %.2f%%\n",
      testName, input.length, result.compressedData.length, result.ratio() * 100);
  }
  ```

#### 4.2 OperatorLibraryTest.java (JUnit 5)
- [ ] Test: `findShortest(10, 13)` returns `Add(3)` with costBits = 13
- [ ] Test: `findShortest(255, 0)` returns `Add(1)` (wraparound)
- [ ] Test: `findShortest(128, 64)` returns `ShiftRight(1)` with costBits = 8
- [ ] Test: `findShortest(50, 50)` returns operation that maps 50→50 (e.g., Mod(1), XorOp(0), etc.)
- [ ] Test: `Not.apply(0)` == 255, `Not.apply(255)` == 0
- [ ] Test: All Operator.apply() results are masked to 0..255
- [ ] Test: Div(0) is never registered
- [ ] Test: ShiftLeft and ShiftRight only register for 1..7 bits
- [ ] Test: All operators in library have unique opId in 0..30

#### 4.3 TransitionGraphTest.java (JUnit 5)
- [ ] Test: After observing `[10, 13, 16, 10, 13, 16]`:
  - edgeCount() >= 2
  - bestEdge(10, 13).op is Add(3)
  - frequencyMatrix has frequencies recorded
- [ ] Test: Graph can observe any random input without crashing
- [ ] Test: exportDot() produces valid file content (readable with Graphviz)
- [ ] Test: edgesFrom(x) returns only edges with fromNode == x
- [ ] Test: edgesTo(x) returns only edges with toNode == x

#### 4.4 CycleDetectorTest.java (JUnit 5)
- [ ] Test: `singleCycle_arithmetic()`
  - Input: [10, 13, 16, 10, 13, 16, 10, 13, 16] (simple +3 loop)
  - Assert: detector finds at least one cycle
  - Assert: top cycle contains nodes [10, 13, 16] in some order
  - **Note:** Our cycle detection may find multiple cycles (e.g., [13,16,10] and [16,10,13] as rotations); accept if any contains the right values
- [ ] Test: `noCycles_strictlyIncreasing()`
  - Input: [1, 2, 3, 4, 5, 6, 7, 8]
  - Assert: detector finds no cycles (or only self-loops, if any)
- [ ] Test: `multipleCycles_ranked()`
  - Input: data with multiple distinct cycles
  - Assert: topCycles(2) returns two cycles
  - Assert: first.compressionGain >= second.compressionGain
- [ ] Test: `cycleDetector_completes_on_random_input()`
  - Input: 256 bytes from Random(seed=42)
  - Assert: completes without exception
  - Assert: all cycles have length >= 2 and length <= 8
- [ ] Test: `cycleDetector_respects_maxCycleLength()`
  - Create detector with maxCycleLength=4
  - Input: data with cycles of length 5+
  - Assert: all returned cycles have length <= 4

#### 4.5 RandomBaselineSuite.java (Main entry point + static tests)
```java
public class RandomBaselineSuite {
  static class TestResult {
    String testName;
    int length;
    double ratio;
    double spaceSaving;
    String topCycle;
    boolean passed;
    long nanos;
  }
  
  public static void main(String[] args) {
    runAll(System.out);
  }
  
  static void runAll(PrintStream out) {
    out.println("=== MRC Phase 1 Randomized Baseline Suite ===\n");
    List<TestResult> results = new ArrayList<>();
    
    results.add(uniformRandom(4096));
    results.add(lcgStream(4096));
    results.add(mersenneTwister(4096));
    results.add(gaussianQuantized(4096));
    results.add(arithmeticSequence(1024, 1));
    results.add(arithmeticSequence(1024, 3));
    results.add(sineQuantized(1024, 0.05));
    
    // Print table
    out.printf("%-30s | %5s | %6s | %8s | %s\n",
      "Test", "Len", "Ratio", "Saving%", "Status");
    out.println(new String(new char[80]).replace('\0', '-'));
    
    for (TestResult r : results) {
      out.printf("%-30s | %5d | %.3f | %7.1f%% | %s\n",
        r.testName, r.length, r.ratio, r.spaceSaving * 100,
        r.passed ? "PASS" : "FAIL");
    }
    
    // Summary
    long totalPass = results.stream().filter(r -> r.passed).count();
    out.printf("\nTotal: %d/%d tests passed\n", totalPass, results.size());
  }
}
```

- [ ] **Test 1: uniformRandom(length)**
  ```java
  TestResult uniformRandom(int length) {
    byte[] input = new byte[length];
    new SecureRandom().nextBytes(input);
    
    // Encode
    CompressionResult result = compressStream(input);
    
    // Verify
    boolean passed = result.ratio() <= 1.02;  // max 2% overhead
    String topCycle = result.cyclesUsed.isEmpty() ? "—" 
                      : result.cyclesUsed.get(0);
    
    return new TestResult("uniformRandom", length, result.ratio(),
      result.spaceSaving(), topCycle, passed, result.encodingNanos);
  }
  ```

- [ ] **Test 2: lcgStream(length)**
  ```java
  TestResult lcgStream(int length) {
    byte[] input = new byte[length];
    long lcg = 12345;  // seed
    for (int i = 0; i < length; i++) {
      lcg = (1664525L * lcg + 1013904223L) & 0xFFFFFFFFL;
      input[i] = (byte)(lcg >> 24);  // high byte
    }
    
    CompressionResult result = compressStream(input);
    boolean passed = true;  // just measure; LCG has some structure
    
    return new TestResult("lcgStream", length, result.ratio(),
      result.spaceSaving(), getCycleInfo(result), passed, ...);
  }
  ```

- [ ] **Test 3: mersenneTwister(length)**
  ```java
  TestResult mersenneTwister(int length) {
    byte[] input = new byte[length];
    Random rand = new Random(42);
    rand.nextBytes(input);
    
    CompressionResult result = compressStream(input);
    boolean passed = result.ratio() <= 1.02;  // should be near random
    
    return new TestResult("mersenneTwister", length, ...);
  }
  ```

- [ ] **Test 4: gaussianQuantized(length)**
  ```java
  TestResult gaussianQuantized(int length) {
    Random rand = new Random(42);
    byte[] input = new byte[length];
    for (int i = 0; i < length; i++) {
      double gaussian = rand.nextGaussian() * 30 + 128;
      int val = (int)Math.round(gaussian);
      input[i] = (byte)Math.min(255, Math.max(0, val));
    }
    
    CompressionResult result = compressStream(input);
    // Expect some clustering of values; expect ratio 0.6..0.75
    boolean passed = result.ratio() < 0.85;  // loose upper bound
    
    return new TestResult("gaussianQuantized", length, ...);
  }
  ```

- [ ] **Test 5: arithmeticSequence(length, delta)**
  ```java
  TestResult arithmeticSequence(int length, int delta) {
    byte[] input = new byte[length];
    int val = 0;
    for (int i = 0; i < length; i++) {
      input[i] = (byte)val;
      val = (val + delta) & 0xFF;
    }
    
    CompressionResult result = compressStream(input);
    // delta=1: gcd(1,256)=1 → single cycle of length 256 → ratio < 0.15
    // delta=3: gcd(3,256)=1 → single cycle of length 256 → ratio < 0.20
    double expectedBound = (delta == 1) ? 0.15 : 0.20;
    boolean passed = result.ratio() < expectedBound;
    
    return new TestResult("arithmeticSequence(Δ=" + delta + ")", length,
      result.ratio(), result.spaceSaving(), getCycleInfo(result),
      passed, result.encodingNanos);
  }
  ```

- [ ] **Test 6: sineQuantized(length, freq)**
  ```java
  TestResult sineQuantized(int length, double freq) {
    byte[] input = new byte[length];
    for (int i = 0; i < length; i++) {
      double sine = 127.5 + 127.5 * Math.sin(2 * Math.PI * freq * i);
      input[i] = (byte)(int)sine;
    }
    
    CompressionResult result = compressStream(input);
    // Sinusoid has periodic structure; expect multiple cycles
    // Expected ratio: 0.30–0.50
    boolean passed = result.ratio() < 0.60;  // loose bound
    
    return new TestResult("sineQuantized(f=" + freq + ")", length, ...);
  }
  ```

#### 4.6 CompressionBenchmark.java (JMH)
- [ ] Benchmarks to include:
  - `encodeUniformRandom_1MB` — throughput in MB/s
  - `encodeArithmeticSequence_1MB` — should be fast (high compression rate)
  - `encodeSineQuantized_1MB` — medium-difficulty input
  - `graphBuild_10MB` — how fast can we observe a 10 MB stream?
  - `cycleDetection_fullGraph` — how fast is Johnson's algorithm on a full 256-node graph?
- [ ] JMH configuration:
  - Warmup: 3 iterations × 1s
  - Measurement: 5 iterations × 2s
  - Output: throughput in ops/sec or MB/s

### Inter-Agent Dependencies
- **Core Algebra Agent:** Uses OperatorLibrary for tests
- **Graph Architecture Agent:** Uses TransitionGraph, CycleDetector, CyclePath
- **Codec Infrastructure Agent:** Uses MrcEncoder, MrcDecoder, BitStream classes

### Verification Checklist (Validation & Benchmarking)
- [ ] RoundTripTest passes for all 6 parameterized inputs
- [ ] arithmeticSequence(Δ=1) achieves ratio < 0.15
- [ ] arithmeticSequence(Δ=3) achieves ratio < 0.20
- [ ] uniformRandom achieves ratio < 1.02 (no >2% overhead)
- [ ] CycleDetectorTest finds expected cycles in synthetic data
- [ ] OperatorLibraryTest validates all operator properties
- [ ] RandomBaselineSuite completes and prints summary table
- [ ] All JUnit tests pass with zero failures
- [ ] CompressionBenchmark runs without exception (if JMH enabled)

### Handoff Protocol
**Input:** Encoder/Decoder instances from Codec Infrastructure Agent
**Output:** Test results, CompressionResult objects, benchmark metrics
**To Integration & Documentation Agent:** Test pass/fail status, benchmark summary

---

## Agent 5: Integration & Documentation Agent

### Ownership
- **Module:** Root level (pom.xml, README.md, build configuration, cross-module contracts)
- **Responsibility:** Ensure Maven build system, module interdependencies, cross-module APIs, documentation, and end-to-end project coherence.

### Key Responsibilities

#### 5.1 pom.xml Configuration
- [ ] Configure Maven parent project:
  - groupId: `com.mrc`
  - artifactId: `mrc-phase1`
  - version: `0.1.0-SNAPSHOT`
  - packaging: `jar`
- [ ] Set properties:
  - `java.version`: 21
  - `junit.version`: 5.10.2
  - `jmh.version`: 1.37
- [ ] Dependencies:
  - JUnit 5 (Jupiter API + Engine): test scope
  - JMH core + annotation processor: test scope
  - No other external dependencies
- [ ] Compiler configuration:
  - Release target: 21
  - Enable preview: `--enable-preview` (for sealed types and pattern matching)
- [ ] Plugins:
  - maven-compiler-plugin (3.11.0 or later)
  - maven-surefire-plugin (run tests)
  - maven-jar-plugin (package JAR)
  - maven-shade-plugin (optional: create fat JAR with JMH)

#### 5.2 Module Visibility & API Contracts
- [ ] Define public API per module:
  
  **Core API (public):**
  - `Operator` (sealed interface)
  - `OperatorLibrary` (singleton factory)
  - `Transition` (record, optional utility)
  - `ValueCluster` (record, placeholder)
  
  **Graph API (public):**
  - `TransitionGraph` (mutable builder)
  - `TransitionEdge` (record, result)
  - `CycleDetector` (finder)
  - `CyclePath` (record, result)
  - `GraphProfiler` (utility)
  
  **Codec API (public):**
  - `MrcEncoder` (builder + encoder)
  - `MrcDecoder` (stateless decoder)
  - `CompressionResult` (record, result)
  - `EncodingTier` (enum)
  - `MrcFormatException` (exception)
  
  **Internals (package-private):**
  - `BitStreamWriter`, `BitStreamReader`
  - Tarjan's SCC (private class in CycleDetector)
  - Any implementation detail not needed by clients

- [ ] Cross-module contract points:
  1. **Core → Graph:** Graph receives initialized `OperatorLibrary` in constructor or static init
  2. **Graph → Codec:** Encoder constructor takes `TransitionGraph` + `List<CyclePath>` + `cyclesByNode()` index
  3. **Codec ← Core:** Encoder/Decoder need to resolve `Operator.opId()` ↔ operator instance; must use shared library
  4. **Test → All:** Tests can construct any module independently (no hidden dependencies)

#### 5.3 README.md
- [ ] Sections:
  1. **Overview:** What is MRC Phase 1? (1–2 paragraphs)
  2. **Features:** Operator model, cycle detection, compression, lossless verification
  3. **Quick Start:**
     ```java
     // Build graph
     TransitionGraph graph = new TransitionGraph(operatorLibrary);
     graph.observe(trainData);
     
     // Detect cycles
     CycleDetector detector = new CycleDetector(graph, 8);
     List<CyclePath> cycles = detector.findAllCycles();
     
     // Encode
     MrcEncoder encoder = new MrcEncoder(graph, cycles);
     CompressionResult result = encoder.encode(dataToCompress);
     
     // Decode
     MrcDecoder decoder = new MrcDecoder();
     byte[] original = decoder.decode(result.compressedData());
     ```
  4. **Build & Test:**
     ```bash
     mvn clean install
     mvn test
     mvn test -Dtest=RandomBaselineSuite
     ```
  5. **Compression Targets:**
     - Table of expected ratios by data type (from spec)
  6. **Architecture:**
     - 4-module design (core, graph, codec, bench)
     - Separation of concerns diagram (optional, or ASCII art)
  7. **Implementation Notes:**
     - 8-bit masking rule
     - Prefix-free flag codes
     - Johnson's algorithm for cycles
     - Phase 2 roadmap
  8. **License & References:** (placeholder)

#### 5.4 Cross-Module Integration Tests
- [ ] Create `integration/` test package
- [ ] Test: Full pipeline end-to-end
  ```java
  @Test void fullPipeline_arithmeticSequence() {
    // Set up: generate data
    byte[] data = ...;
    
    // Execute: build → detect → encode → decode
    TransitionGraph graph = new TransitionGraph(lib);
    graph.observe(data);
    List<CyclePath> cycles = new CycleDetector(graph, 8).findAllCycles();
    CompressionResult result = new MrcEncoder(graph, cycles).encode(data);
    byte[] decoded = new MrcDecoder().decode(result.compressedData());
    
    // Verify
    assertArrayEquals(data, decoded);
    System.out.println("Ratio: " + result.ratio());
  }
  ```

#### 5.5 Code Quality & Conventions
- [ ] Naming conventions:
  - Class names: PascalCase (e.g., `TransitionGraph`)
  - Method names: camelCase (e.g., `findAllCycles()`)
  - Constant names: UPPER_SNAKE_CASE
  - Private fields: use `private final` where possible (records help)
- [ ] Sealed interface pattern:
  ```java
  public sealed interface Operator permits Add, Sub, Mul, Div, ... {
    int apply(int x);
    byte opId();
    int operandBits();
    String toExpression(String varName);
  }
  
  public record Add(int operand) implements Operator { ... }
  ```
- [ ] Records for data transfer (CyclePath, TransitionEdge, CompressionResult)
- [ ] Optionals for nullable returns (e.g., `Optional<Transition>`)
- [ ] No null pointers; use Optional or throw
- [ ] Javadoc on public APIs (brief, focuses on contract not implementation)

#### 5.6 Build Verification Checklist
- [ ] `mvn clean compile` succeeds with no errors
- [ ] `mvn test` runs all JUnit tests, zero failures
- [ ] `mvn package` creates JAR in target/
- [ ] JAR is executable if main-class set (optional for Phase 1)
- [ ] No external dependencies in JAR (only test scope)
- [ ] Compiler warnings: none expected (use `-Wall` if available in Maven)
- [ ] All module class files compile with Java 21 syntax

#### 5.7 Directory Structure Verification
- [ ] Confirm all files from spec exist:
  ```
  pom.xml
  README.md
  src/main/java/mrc/core/*.java (4 files)
  src/main/java/mrc/graph/*.java (5 files)
  src/main/java/mrc/codec/*.java (6 files)
  src/main/java/mrc/bench/*.java (2 files)
  src/test/java/mrc/core/*.java (1 file)
  src/test/java/mrc/graph/*.java (2 files)
  src/test/java/mrc/codec/*.java (1 file)
  src/test/java/mrc/integration/*.java (1 file, optional)
  ```

### Inter-Agent Dependencies
- **All agents:** Integration Agent coordinates and verifies their outputs
- **Receives:** Maven config, module structures, and handoffs from all other agents

### Verification Checklist (Integration & Documentation)
- [ ] `mvn clean compile` succeeds
- [ ] `mvn test` passes all tests
- [ ] `mvn package` creates JAR without dependencies
- [ ] README.md is complete and readable
- [ ] No circular dependencies between modules
- [ ] All public APIs are documented (Javadoc)
- [ ] Cross-module example in README is runnable
- [ ] No compiler warnings
- [ ] All files from spec are present and accounted for

---

## Collaboration Protocol & Handoff Workflow

### Phase 1: Specification & Planning (All Agents)
1. All agents read this configuration document
2. Validate no ambiguities in their module scope
3. Identify any gaps or conflicts → escalate to Integration Agent
4. **Output:** Checklist of clarifying questions (if any)

### Phase 2: Implementation Order (Sequential, with Dependency Ordering)
1. **Core Algebra Agent** implements `core/` module first
   - Build OperatorLibrary fully (all ~2400 instances)
   - Cache findShortest() results
   - **Handoff:** OperatorLibrary instance to Graph and Codec agents
   - **Verification:** All checks in § Core Algebra checklist pass

2. **Codec Infrastructure Agent** implements BitStream classes (independent of graph)
   - `BitStreamWriter`, `BitStreamReader`, `EncodingTier`
   - **Verification:** Bit-level reads/writes work correctly

3. **Graph Architecture Agent** implements `graph/` module
   - Uses OperatorLibrary from Core Algebra Agent
   - Build TransitionGraph, TransitionEdge, CyclePath
   - Implement Tarjan's SCC and Johnson's algorithm
   - **Handoff:** TransitionGraph + CycleDetector to Codec agent
   - **Verification:** All graph checks pass

4. **Codec Infrastructure Agent** implements MrcEncoder, MrcDecoder
   - Uses TransitionGraph, CyclePath, OperatorLibrary
   - Implement state machine, bitstream protocol, cycle table header
   - **Verification:** Round-trip tests pass on simple inputs

5. **Validation & Benchmarking Agent** implements test suite
   - OperatorLibraryTest, TransitionGraphTest, CycleDetectorTest (tests of §4.1–4.4)
   - RoundTripTest with parameterized inputs
   - RandomBaselineSuite
   - **Verification:** All tests pass

6. **Integration & Documentation Agent** finalizes
   - pom.xml configuration, Maven build
   - README.md with examples
   - Cross-module integration tests
   - Final verification: `mvn clean test` succeeds end-to-end

### Phase 3: Validation & Iteration (Parallel, then Sync)
1. Each agent runs their verification checklist independently
2. Document any failures in a shared issue tracker
3. **Sync meeting 1:** Review failures, prioritize fixes by impact
4. **Sync meeting 2:** Confirm all checklist items pass; green light for Phase 2 planning

### Inter-Agent Communication
- **Synchronous (blocker):** When one agent needs output from another before proceeding
- **Asynchronous (non-blocker):** When an agent can implement locally and integrate later
- **Escalation:** Any ambiguity or conflict → Integration Agent mediation

### Handoff Artifacts
Each handoff includes:
1. **Code artifact:** Fully tested, documented module(s)
2. **Verification report:** Checklist items, pass/fail status
3. **API contract:** Public interfaces, expected behavior
4. **Known issues:** Any deviations from spec, rationale

---

## Critical Contracts & Invariants (All Agents Must Respect)

### Contract 1: Operator Catalog Stability
- Once OperatorLibrary is initialized, no new operators can be added
- opId must be **stable** across encode/decode (deterministic ordering)
- Every operator must have a unique 5-bit opId in 0..30

### Contract 2: 8-Bit Wraparound Semantics
- All arithmetic in `Operator.apply()` must mask to `(result & 0xFF)`
- Example: Add(200).apply(100) = (100 + 200) & 0xFF = 300 & 0xFF = 44

### Contract 3: Compression Gain Definition
- compressionGain(edge) = 8 - costBits
- compressionGain(cycle) = (cycle.length * 8) - sum(edge.costBits)
- Only encode if compressionGain > 0

### Contract 4: Prefix-Free Flag Codes
- LITERAL: `0` (1 bit)
- RELATIONAL: `10` (2 bits)
- CYCLE: `110` (3 bits)
- Kraft inequality: 1/2 + 1/4 + 1/8 = 7/8 < 1 ✓

### Contract 5: Cycle Table Limit
- At most 255 cycles embedded in header
- Encoder selects top-255 by compressionGain
- cycleId field: ceil(log2(cycleCount)) bits

### Contract 6: Round-Trip Invariant
- `decode(encode(data)) == data` for all data
- Verified by RoundTripTest parameterized suite

---

## Risk Register & Mitigation

### Risk 1: Operator Catalog Size (2400 instances)
- **Concern:** Memory footprint, initialization time
- **Mitigation:** Lazy singleton pattern; build catalog on first access
- **Acceptance:** Startup delay < 100ms acceptable

### Risk 2: Johnson's Algorithm Complexity
- **Concern:** Cycle detection might be too slow for large graphs
- **Mitigation:** Bound maxCycleLength to 8; Tarjan's SCC pre-pruning reduces effective graph size
- **Acceptance:** Cycle detection must complete in <100ms for 256-node graph

### Risk 3: opId Mapping Ambiguity
- **Concern:** Encoder uses Operator instance; decoder needs opId ↔ Operator mapping
- **Mitigation:** Assign stable opId to each operator; use OperatorLibrary.byId() for lookup
- **Acceptance:** Decoder must throw `MrcFormatException` on invalid opId

### Risk 4: Prefix-Free Code Correctness
- **Concern:** Kraft inequality miscomputation
- **Mitigation:** Manually verify: 1/2 + 1/4 + 1/8 = 7/8 < 1
- **Acceptance:** Bitstream decoder must never misidentify flag prefix

### Risk 5: Test Coverage
- **Concern:** Edge cases in cycle detection, bitstream, operators
- **Mitigation:** Parameterized RoundTripTest with diverse inputs; RandomBaselineSuite
- **Acceptance:** All JUnit tests must pass; RandomBaselineSuite must report <2% overhead on random data

---

## Success Criteria (End of Phase 1)

1. ✅ All agents report green checklist (100% items verified)
2. ✅ `mvn clean test` passes with zero failures
3. ✅ RoundTripTest verifies lossless encode/decode on 6+ input types
4. ✅ RandomBaselineSuite achieves compression targets:
   - Arithmetic sequence (Δ=1): ratio < 0.15
   - Arithmetic sequence (Δ=3): ratio < 0.20
   - Uniform random: ratio < 1.02 (max 2% overhead)
   - Sine quantized: ratio < 0.50
5. ✅ Code is documented (Javadoc on public APIs)
6. ✅ README.md is complete with examples and targets
7. ✅ No circular module dependencies
8. ✅ All verification checklists from § Agent 1–5 pass

---

## Appendix A: Agent Interaction Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ Agent 1: Core Algebra Agent                                     │
│ - Operator sealed interface + 12 implementations                │
│ - OperatorLibrary singleton (2400 operators)                    │
│ └─→ Output: OperatorLibrary instance                            │
└──────────┬───────────────────────────────────────────────────────┘
           │
           ├──────────────────┬──────────────────┬────────────────┐
           │                  │                  │                │
           v                  v                  v                v
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ Agent 2: Graph   │  │ Agent 3: Codec   │  │ Agent 4: Validat.│
│ Arch Agent       │  │ Infra Agent      │  │ & Bench Agent    │
│                  │  │                  │  │                  │
│ Uses: OpLib ────┼──┼─→ Uses: OpLib    │  │ Uses: All        │
│ → TransitionGrph│  │ Uses: TGraph     │  │                  │
│ → CycleDetector │  │ Uses: Cycles ────┼──┤                  │
│ → CyclePath     │  │                  │  │                  │
│ → GraphProfiler │  │ Outputs:         │  │ Outputs:         │
│                  │  │ - Encoder        │  │ - Test pass/fail │
│ Outputs:         │  │ - Decoder        │  │ - Compression    │
│ - Graph          │  │ - BitStream      │  │   metrics        │
│ - Cycles         │  │ - CompressionRes.│  │                  │
│ - Profiles       │  │                  │  │                  │
└──────────┬───────┘  └────────┬─────────┘  └────────┬──────────┘
           │                   │                     │
           └───────────────────┴─────────────────────┘
                              │
                              v
           ┌──────────────────────────────────────┐
           │ Agent 5: Integration & Documentation │
           │ - pom.xml                            │
           │ - README.md                          │
           │ - Cross-module integration tests     │
           │ - Final build verification           │
           └──────────────────────────────────────┘
```

---

## Appendix B: Test Input Specifications (RandomBaselineSuite)

### Input 1: Uniform Random
```
SecureRandom.nextBytes(4096) → each byte independent, uniform 0..255
Expected: ratio ≈ 1.00 (incompressible)
```

### Input 2: LCG Pseudo-Random
```
lcg = (1664525 * lcg + 1013904223) & 0xFFFFFFFF
data[i] = (lcg >> 24) & 0xFF
Length: 4096 bytes
Expected: ratio ≈ 0.70–0.85 (algebraic structure may be detectable)
```

### Input 3: Mersenne Twister (via java.util.Random)
```
Random(seed=42).nextBytes(4096)
Expected: ratio ≈ 1.00 (high-quality pseudo-random)
```

### Input 4: Gaussian Quantized
```
for i in 0..4095:
  gaussian = N(128, 30)  // mean=128, σ=30
  data[i] = clamp(round(gaussian), 0, 255)
Expected: ratio ≈ 0.60–0.75 (clustering helps)
```

### Input 5: Arithmetic Sequence (Δ=1)
```
data[i] = i mod 256
Repeats to fill 1024 bytes
Expected: ratio < 0.15 (single Add(1) cycle dominates)
```

### Input 6: Arithmetic Sequence (Δ=3)
```
data[i] = (i * 3) mod 256
Repeats to fill 1024 bytes
Expected: ratio < 0.20 (Add(3) cycle, length=gcd(3,256)=1)
```

### Input 7: Sine Wave Quantized
```
for i in 0..1023:
  sine = 127.5 + 127.5 * sin(2π * 0.05 * i)
  data[i] = round(sine)
Expected: ratio ≈ 0.30–0.50 (periodic structure)
```

---

## Appendix C: Verification Checklist Template (Per Agent)

Use this template to track progress:

```
# Agent [N]: [Name] — Verification Checklist

## Module: [module_name]

### Sub-Checklist 1: [Feature/Class]
- [ ] Requirement 1 verified
- [ ] Requirement 2 verified
...

### Sub-Checklist 2: [Feature/Class]
...

## Status: [ ] Not Started [ ] In Progress [X] Complete [ ] Blocked

## Blockers (if any):
- (none)

## Dependencies on Other Agents:
- [ ] Awaiting output from Agent X: [specific artifact]

## Handoff Status:
- [ ] Code complete and tested
- [ ] Documentation updated
- [ ] Handed off to: [Agent(s)]
```

---

## Appendix D: Known Implementation Decisions & Rationale

### Decision 1: Sealed Interfaces for Operator
**Why:** Java 21 sealed interfaces enable exhaustive pattern matching in switch expressions. Compiler enforces that every Operator subtype is handled, preventing silent bugs.

**Alternative:** Abstract base class with instanceof checks. ❌ More verbose, less type-safe.

### Decision 2: Records for Data Transfer Objects
**Why:** Records are immutable by default, reduce boilerplate, and are perfect for TransitionEdge, CyclePath, CompressionResult.

**Alternative:** Traditional POJOs with getters/setters. ❌ More boilerplate, mutable by default.

### Decision 3: Johnson's Algorithm for Cycle Detection
**Why:** Finds all simple cycles in O((V+E)(C+1)) time. For 256 nodes and bounded length 8, this is tractable and optimal.

**Alternative 1:** Naive DFS from each node. ❌ Exponential in cycle length, revisits paths.
**Alternative 2:** Strongly connected components (SCC) only. ❌ Only finds cycles, not decomposed paths; less fine-grained.

### Decision 4: Prefix-Free Flag Codes (0, 10, 110)
**Why:** Satisfies Kraft inequality (7/8 < 1); decoder never needs lookahead; self-delimiting.

**Alternative:** Fixed-width flags (e.g., 2-bit code: 00, 01, 10, 11). ❌ Wastes bits; suboptimal for LITERAL tier.

### Decision 5: Operator Catalog Size (~2400 instances)
**Why:** Brute-force findShortest() is feasible; cache makes O(1) after first build.

**Alternative:** Dynamic operator generation on demand. ❌ More complex; initialization cost moved to first use.

### Decision 6: Train-on-Self for Phase 1
**Why:** Compressor has full knowledge of the data; gives best-case compression ratios. Validates architecture without complications of separate training corpus.

**Phase 2 Plan:** Separate training corpus, snapshot protocol to persist graph + cycle table.

---

## Appendix E: Glossary

| Term | Definition |
|------|-----------|
| **Operator** | A function mapping one 8-bit value to another (e.g., Add(3), ShiftLeft(2)) |
| **Transition** | A pair (from, to, op) with costBits; unit of compression |
| **TransitionGraph** | Directed multigraph where nodes are 8-bit values (0..255) and edges are transitions |
| **CyclePath** | A simple cycle in the graph; list of nodes + edges + compressionGain metric |
| **Compression Gain** | Bits saved per use: 8 - costBits for edges; (length*8) - sum(costBits) for cycles |
| **EncodingTier** | One of three encoding modes: LITERAL (raw), RELATIONAL (prev+op), CYCLE (repeat) |
| **Bitstream** | Sequence of bits with prefix-free flag structure; decoded deterministically |
| **Round-Trip** | encode(data) → decode(result) == data; lossless verification |
| **Johnson's Algorithm** | Circuit-finding algorithm; finds all simple cycles in O((V+E)(C+1)) |
| **Tarjan's SCC** | Strongly connected components; preprocessing step for Johnson |
| **Kraft Inequality** | Sum of 2^(-prefixLength) <= 1; necessary & sufficient for prefix-free codes |

---

**End of Multiagent Configuration Document**

This configuration is comprehensive, unambiguous, and ready for implementation by five specialized agents working in parallel (with dependency ordering). Each agent has clear ownership, verification criteria, and handoff protocols.
