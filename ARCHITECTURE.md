# MRC Architecture & Components

## Table of Contents

1. [System Overview](#system-overview)
2. [Core Module (mrc/core/)](#core-module-mrccore)
3. [Graph Module (mrc/graph/)](#graph-module-mrcgraph)
4. [Codec Module (mrc/codec/)](#codec-module-mrccodec)
5. [Evolution Module (mrc/evolution/)](#evolution-module-mrcevolution)
6. [Snapshot Module (mrc/snapshot/)](#snapshot-module-mrcsnapshot)
7. [Data Flow](#data-flow)

---

## System Overview

```
┌───────────────────────────────────────────────────────────┐
│                      MRC Compression System               │
├───────────────────────────────────────────────────────────┤
│                                                           │
│  [Input Data] → [Operator Library] → [Transition Graph]   │
│                                           ↓               │
│                    ┌────────────────────────────────┐     │
│                    ↓                                ↓     │
│              [Cycle Detection]            [Evolutionary]  │
│                    ↓                      [Edge-Finder ]  │
│                    └──────────────┬────────────────┘      │
│                                   ↓                       │
│                            [Encoder Selection]            │
│                                   ↓                       │
│                 ┌────────────────────────────────┐        │
│                 ├─ v0x01: Cycle-based (advanced) ├─┐      │
│                 └────────────────────────────────┘ │      │
│                 ┌────────────────────────────────┐ │      │
│                 ├─ v0x02: Arithmetic-run (simple)├─┤      │
│                 └────────────────────────────────┘ │      │
│                                                    ↓      │
│                            [Snapshots] ← [Serialization]  │
│                                   ↓                       │
│                        [Compressed Output]                │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

---

## Core Module (mrc/core/)

### Operator Interface & Implementations

**`Operator.java`** (Open Interface)
```java
public interface Operator {
    int apply(int x);  // Transform byte value
    int operandBits(); // Cost: 0, 3, or 8 bits
    String toExpression(String var); // Human-readable: "x + 47"
}
```

**Implementations**:
- **Arithmetic**: `Add`, `Sub`, `Mul`, `Div`, `Mod` (8-bit operand)
- **Bitwise**: `XorOp`, `AndOp`, `OrOp` (8-bit operand)
- **Shift**: `ShiftLeft`, `ShiftRight` (3-bit operand: 1-7 bits)
- **Unary**: `Not` (0-bit operand, always applied)

**Key Design**: Each operator is immutable, stateless. Instances are reused across the codebase.

### OperatorLibrary: Singleton Registry

**Purpose**: Maintain all ~2,400 operator instances and provide fast lookups.

**Initialization**:
```
Create all operators:
  - Add: 256 instances (operands 0-255)
  - Sub: 255 instances (no Sub(0), would be identity)
  - Mul: 256 instances
  - ... (similar for others)
  - ShiftLeft: 7 instances (1-7 bits)
  - ShiftRight: 7 instances
  - Not: 1 instance
  Total: ~2,400
```

**Internal Data Structures**:
- `List<Operator> allOperators` — flat list for iteration (fitness eval)
- `Map<Byte, Operator> byId` — type-level lookup: opId → one representative
- `Operator[][] transitionCache` — 256×256 pre-computed shortest operators

**Key Methods**:
- `findShortest(from, to)` → `Optional<Operator>` — O(1) array lookup
- `createOperator(opId, operand)` → `Operator` — reconstruct from opId+operand
- `getInstance()` → Singleton with lazy initialization + double-checked locking

**Optimization**: Pre-compute all 256×256 transitions at init (~5-10ms), then every lookup is O(1).

### OpIdMap: Type-Level Operation ID Mapping

**Purpose**: Map operator classes to 5-bit opIds (0-10).

**Mapping**:
```
0: Add        1: Sub         2: Mul         3: Div
4: Mod        5: XorOp       6: AndOp       7: OrOp
8: ShiftLeft  9: ShiftRight  10: Not
```

**Usage**: Decoder receives 5-bit opId, knows `operandBits()` from type alone.

### Extended Operators (Phase 2)

Three levels of extended operators layered on top of base operators:

**Level 1: FunctionOperators** (opId 32-39)
- `Polynomial(a, b, c)` — Fit quadratic to data: `y = ax² + bx + c`
- `LinearCongruential(a, c)` — LCG: `y = ax + c` (mod 256)
- `TableLookup(256-byte table)` — Arbitrary mapping
- `RotateLeft(bits)` / `RotateRight(bits)` — Circular bit rotation
- `BitReverse` — Reverse bit order
- `NibbleSwap` — Swap nibbles
- `DeltaOfDelta` — Double-difference encoding

**Level 2: CompositeOperators** (opId 40)
- Chain 2-4 operators: `f(g(h(x)))`
- Auto-optimization: `Add(1) ∘ Add(2)` simplifies to `Add(3)`
- Enables modeling complex patterns

**Level 3: SuperfunctionOperators** (opId 64-66)
- `Iterated(f, n)` — Apply operator n times
- `FixedPointReach(f)` — Find fixed point iterations
- `Conjugate(h, f)` — Conjugation: `h⁻¹(f(h(x)))`

**Cost Model** (in `OperatorCostModel`):
```
relativeCost(op1, op2) = cost(op2) - cost(op1)
isWorthEncoding(operator) = fitness gain > cost threshold

ARITH_RUN break-even = 4 elements (33 bits vs 36 bits literal)
```

---

## Graph Module (mrc/graph/)

### TransitionGraph: Directed Multigraph

**Purpose**: Represent data patterns as a graph where edges are operator transitions.

**Structure**:
- **Nodes**: 256 nodes (byte values 0-255)
- **Edges**: `(from, to, operator)` with frequency and weight
- **Weight**: `frequency × (8 - costBits)` — compression gain estimation

**Construction** (`buildEdges(byte[])`):
```
for each consecutive (prev, curr) in data:
  1. Find cheapest operator op that transforms prev → curr
  2. Create/update edge (prev, curr, op)
  3. Increment frequency
  4. weight = freq × (8 - operatorCost)
```

**Key Methods**:
- `observe(byte[])` — Incremental graph building
- `exportDot(Path)` — Graphviz export (only positive-weight edges)
- `getEdges()` → List of all edges (for analysis)

**Use Cases**:
- **Training**: Analyze pattern structure
- **Visualization**: DOT export → Graphviz rendering
- **GA Seeding**: Use top-K edges to initialize population

### CycleDetector: Find Repeating Patterns

**Purpose**: Find cycles (closed paths) in the graph that compress well.

**Algorithms**:

**1. Tarjan's SCC (Strongly Connected Components)**
- O(V+E) time complexity
- Identifies maximal sets of mutually-reachable nodes
- Foundation for cycle enumeration

**Implementation**:
```
class TarjanSCC:
  - index: counter for discovery order
  - lowlink: minimum index reachable via back-edges
  - stack: DFS stack for tracking path
  - components: result list of SCCs

Algorithm:
  1. DFS from each unvisited node
  2. Track lowlink value (lowest index reachable)
  3. When index == lowlink, output SCC
```

**2. Johnson's Algorithm (Cycle Enumeration)**
- Enumerates all simple cycles within an SCC
- DFS from each node, searching for back-edges to start node
- **Deduplication**: Track seen cycles by node-set hash

**Key Methods**:
- `findAllCycles(TransitionGraph)` → `List<CyclePath>`
- `topCycles(int k)` → Top-K by compression gain

**Cycle Compression Gain**:
```
gain = (cycleLength × 8) - sum(operatorCosts) - overheadBits
```

### CyclePath Record

**Purpose**: Immutable representation of a detected cycle.

**Fields**:
- `nodes: List<Integer>` — Cycle node sequence
- `edges: List<TransitionEdge>` — Operator transitions between nodes
- `length: int` — Cycle length
- `totalWeight: double` — Sum of edge weights
- `compressionGain: double` — Estimated bit savings per repetition

**Methods**:
- `phaseOf(value)` → Position in cycle (or -1)
- `containsNode(value)` → Boolean membership
- `toExpression()` → Human-readable: "37 --[+3]--> 40 --[+3]--> 43 --[+3]--> 37"

### SequenceDetector: Arithmetic Run Detection

**Purpose**: Identify runs of values following arithmetic progression.

**Algorithm** (Linear Scan):
```
for i = 0 to n-2:
  step = (data[i+1] - data[i]) & 0xFF
  j = i + 1
  while j < n-1 and (data[j+1] - data[j]) & 0xFF == step:
    j++
  if (j - i + 1) >= MIN_RUN (4):
    record pattern
```

**ArithmeticPattern Record**:
- `step` — 0-255 representing signed step (-128 to +127)
- `totalRunBytes` — Sum of all run lengths
- `runCount` — Number of distinct runs with this step
- `estimatedSavingBits` — Compression potential

**Methods**:
- `detect(byte[])` → All patterns
- `topPatterns(byte[], k)` → Top-K via min-heap, O(n log k)
- `signedStep()` → Convert 0-255 to -128 to +127

### GraphProfiler: Statistics & Analysis

**Purpose**: Generate readable statistics about graph structure.

**Report Sections**:
1. **Graph Statistics**: Node count, edge count, average weight
2. **Edge Cost Distribution**: Histogram of operator costs
3. **Top-10 Edges**: Highest-weight transitions with operators
4. **Cycle Statistics**: Count and length distribution
5. **Top-10 Cycles**: Highest compression gain
6. **Coverage**: % nodes in cycles, % compressing edges

**Methods**:
- `report(TransitionGraph, CycleDetector)` → Print comprehensive report
- `reportTopEdges(int k)` → Ranked edges with costs
- `reportCycleLengthDistribution()` → Histogram

---

## Codec Module (mrc/codec/)

### BitStreamReader / BitStreamWriter

**Purpose**: Serialize/deserialize data at the bit level (not just byte boundaries).

**BitStreamWriter**:
```java
writeBit(int bit)        // Write single bit
writeBits(long val, int nBits) // Write N bits
writeByte(int byte)      // Write full byte
flush()                  // Align to byte boundary
toByteArray()            // Get compressed data
```

**BitStreamReader**:
```java
readBit()               // Read single bit
readBits(int nBits)     // Read N bits
readByte()              // Read full byte
hasMore()               // Any data left?
```

**Design Note**: Prefix-free flag codes are hand-managed (not Huffman), enabling clear semantics.

### MrcEncoder: Two Format Versions

#### v0x01: Cycle-Based Encoding

**Constructor**: `MrcEncoder(TransitionGraph graph, List<CyclePath> cycles)`

**Encoding Loop**:
```
for each value in input:
  1. Try CYCLE tier (if cycle repeats at current position)
  2. Fallback to RELATIONAL (if operator compresses)
  3. Fallback to LITERAL (always works)
```

**Header Format**:
```
Magic(3 bytes): 0x4D 0x52 0x43
Version(1 byte): 0x01
CycleCount(1 byte): N
For each cycle:
  Length(1 byte)
  Nodes(L bytes)
  OpIds + operands(variable bits per operator)
OriginalLength(4 bytes)
```

**Bitstream Tiers**:
- LITERAL: `0` + 8 bits = 9 bits
- RELATIONAL: `10` + 5-bit opId + operand = 7-15 bits
- CYCLE: `110` + cycleIndex + 16-bit repeat count

#### v0x02: Arithmetic Run Encoding

**Constructor**: `MrcEncoder(List<ArithmeticPattern> patterns)`

**Algorithm**:
```
i = 0
while i < length:
  1. Check if next values form an arithmetic run
  2. If run length >= MIN_RUN (4):
     emit ARITH_RUN token + skip ahead
  3. Else:
     emit LITERAL
  4. i++
```

**Header Format**:
```
Magic(3 bytes): 0x4D 0x52 0x43
Version(1 byte): 0x02
StepCount(1 byte): N
Steps(N bytes): The detected arithmetic steps
OriginalLength(4 bytes)
```

**Bitstream Tiers**:
- LITERAL: `0` + 8 bits = 9 bits
- ARITH_RUN: `1` + 8-bit stepIdx + 8-bit startVal + 16-bit runLen = 33 bits

### MrcDecoder: Version-Dispatched

**Purpose**: Read either v0x01 or v0x02 format transparently.

**Algorithm**:
```
1. Read header (magic + version)
2. Dispatch: version == 0x01 ? decodeV1 : decodeV2
3. Loop until originalLength bytes emitted
4. Validate against originalLength
```

**v0x01 Decoding Loop**:
```
while result.size() < originalLength and hasMore():
  flag = readBit()
  if flag == 0:
    emit LITERAL (readByte)
  else:
    flag2 = readBit()
    if flag2 == 0:
      emit RELATIONAL (opId + operand, apply, emit)
    else:
      flag3 = readBit()
      if flag3 == 0:
        emit CYCLE (repeat cycles K times)
```

**v0x02 Decoding Loop**:
```
while pos < originalLength and hasMore():
  flag = readBit()
  if flag == 0:
    emit LITERAL (readByte)
  else:
    emit ARITH_RUN (stepIdx, startVal, runLen)
```

### CompressionResult Record

**Purpose**: Return compression metrics and statistics.

**Fields**:
- `byte[] original`, `byte[] compressed`
- `int originalBits`, `int compressedBits`
- `double ratio` — compressed/original
- `double spaceSaving` — 1 - ratio
- `Map<EncodingTier, Long> tierCounts` — Breakdown by tier
- `long encodeTime`, `long decodeTime` — Performance

---

## Evolution Module (mrc/evolution/)

### Chromosome & Operator Rules

**Chromosome Record**:
```java
record Chromosome(
    List<OperatorRule> rules,
    int generation,
    double fitness,
    String id
)
```

**OperatorRule Record**:
```java
record OperatorRule(
    int fromValue,
    int toValue,
    Operator assignedOperator
)
```

**Semantics**: When transitioning from `fromValue` to `toValue`, use `assignedOperator` (overrides graph lookup).

### ChromosomeFactory: Creation & Validation

**Methods**:
- `createRandom(int ruleCount)` — Random rules from library
- `createFromGraph(TransitionGraph, int topK)` — Seed from existing graph
- `isValid(Chromosome)` — Verify all rules correct: `op.apply(from) == to`
- `repairInvalid(Chromosome)` — Fix broken rules

### FitnessEvaluator: Compression Performance

**Fitness Calculation**:
```
1. Build temporary TransitionGraph from corpus
2. Override graph edges with chromosome rules
3. Encode corpus with overridden graph
4. fitness = (1.0 - ratio) - 0.001 × ruleCount

(Parsimony penalty prevents bloat)
```

**Methods**:
- `evaluate(Chromosome, byte[])` → Double fitness
- `evaluatePopulation(List, byte[])` → Parallel evaluation
- `evaluateBatch(Chromosome, List<byte[]>)` → Multi-corpus average

### SelectionStrategy: Three Implementations

**TournamentSelection(tournamentSize=5)**:
```
for each selection:
  1. Pick tournamentSize random chromosomes
  2. Return the fittest
(Repeat count times)
```

Advantage: Preserves diversity, avoids premature convergence.

**EliteSelection(eliteCount, fallback)**:
```
1. Sort population by fitness
2. Take top eliteCount unchanged
3. Fill remainder with fallback strategy
```

Ensures best solutions always persist.

**RankSelection()**:
```
1. Rank chromosomes by fitness (1 to N)
2. Probability ∝ rank (not raw fitness)
3. Weighted random selection
```

Reduces dominance of single superhero chromosome.

### CrossoverEngine: Four Strategies

**SINGLE_POINT**:
```
Split at random index i:
  Child1 = Parent1[0..i] + Parent2[i..]
  Child2 = Parent2[0..i] + Parent1[i..]
```

**TWO_POINT**:
```
Split at indices i < j:
  Child1 = Parent1[0..i] + Parent2[i..j] + Parent1[j..]
  Child2 = Parent2[0..i] + Parent1[i..j] + Parent2[j..]
```

**UNIFORM**:
```
For each rule slot:
  Inherit from Parent1 or Parent2 (50/50)
```

**OPERATOR_AWARE** (Recommended):
```
1. Group both parents' rules by (fromValue, toValue)
2. For shared transitions: keep cheaper operator
3. For unique transitions: include with probability 0.7
4. Respects domain structure → fast convergence
```

### MutationEngine: Genetic Mutations

**Applied Independently**:

1. **RULE_REPLACE** (prob 0.05 per rule)
   - Pick random rule, replace operator with another valid one

2. **RULE_ADD** (prob 0.02 per chromosome)
   - Add new rule for uncovered (from, to) pair

3. **RULE_REMOVE** (prob 0.01 per rule)
   - Delete rule, revert to default graph lookup

4. **OPERATOR_UPGRADE** (prob 0.03 per rule)
   - Replace base operator with cheaper extended operator

5. **COMPOSITE_SPLIT** (prob 0.02 per composite)
   - Decompose composite into single operators

6. **COMPOSITE_MERGE** (prob 0.02 per adjacent pair)
   - Merge two sequential operators into composite

### EvolutionaryEdgeFinder: Main GA Loop

**Purpose**: Run genetic algorithm in background thread to discover better operators.

**Initialization**:
```
1. Create initial population (random or seeded from graph)
2. Start background thread
3. Expose getCurrentBest() for codec to read atomically
```

**Generation Loop**:
```
while running:
  1. Evaluate all chromosomes (parallel)
  2. Sort by fitness
  3. Update best (atomic)
  4. Select elite + tournament parents
  5. Crossover pairs → offspring
  6. Mutate offspring
  7. Replace population
  8. Record statistics
  9. Sleep if throttled
```

**Integration with Codec**:
```java
// Evolution thread:
finder.feedData(newData);  // Non-blocking corpus update

// Codec thread (anytime):
Chromosome best = finder.getCurrentBest();  // Lock-free read
```

### CircularDataBuffer: Rolling Corpus

**Purpose**: Maintain recent data window for fitness evaluation.

**Thread-Safe**: ReadWriteLock for multiple readers, single writer.

**Methods**:
- `add(byte[])` — Append data, overwrite oldest if full
- `snapshot()` — Atomic read of current buffer
- `size()`, `clear()`

**Design**: Enables evaluation to work on sliding window without stopping codec.

---

## Snapshot Module (mrc/snapshot/)

### SnapshotVersion & SnapshotManifest

**SnapshotVersion** (2.0):
- Major/minor for compatibility
- `isCompatible(other)` — major versions match

**SnapshotManifest**:
- Version, generation, timestamp, domain tag
- Flags: hasChromosome, hasCycles, hasExtensions
- Section table: typeId → (offset, length)
- CRC32 for integrity
- `read(Path)` — lazy manifest (header only)

### SnapshotSerializer: Binary Writing

**Header Format** (29+ bytes):
```
Magic(4):     0x4D 0x52 0x43 0x32 (MRC2)
Version(2):   0x02 0x00 (v2.0)
Flags(1):     bitmap
Generation(8):   long
Timestamp(8):    epoch milliseconds
CRC32(4):        placeholder
DomainLen(2):    length D
DomainTag(D):    UTF-8 string
SectionTable:    2 bytes count + 9 bytes per section
SectionData:     variable
```

**Section Serializers**:
- `serializeTransitionGraph()` — Edge count + edges
- `serializeCycles()` — Cycle count + node/edge data
- `serializeEvolutionHistory()` — Last 1000 generations (fixed 64-byte each)

**CRC32 Computation**:
```
1. Write snapshot with CRC32 = 0
2. Read full file
3. Compute CRC32(bytes[29..end])
4. Seek(23), write CRC32
5. Sync to disk
```

### SnapshotDeserializer: Binary Reading

**Methods**:
- `readManifest(Path)` — Header + section table only
- `loadGraph()`, `loadCycles()`, `loadChromosome()`, `loadEvolutionHistory()`
- `loadAll()` — FullSnapshot record with all data
- `validateCrc()`, `validateOperatorConsistency()`

**Lazy Loading**: Sections loaded only on request.

### SnapshotScheduler: Async Writing

**Purpose**: Queue snapshot requests from evolution thread, write asynchronously.

**Architecture**:
```
Evolution Thread          Scheduler Thread
     |                          |
  requestSnapshot() ──→ BlockingQueue ──→ writeSnapshot()
  (non-blocking)                |
                        (drains queue)
```

**Filename Pattern**: `mrc2_gen{generation}_{timestamp}.snap`

**Methods**:
- `start()` — Spawn scheduler thread
- `requestSnapshot()` — Queue async write
- `stop()` — Graceful shutdown with drain

---

## Data Flow

### Compression Pipeline

```
Input Data
    ↓
[1] Operator Library (2,400+ operators)
    ↓
[2] Transition Graph (analyze patterns)
    ├→ buildEdges(data)
    └→ exportDot() (optional)
    ↓
[3] Cycle Detection (find repeating structures)
    ├→ Tarjan's SCC
    ├→ Johnson's cycles
    └→ Rank by compression gain
    ↓
[4] Optional: Genetic Algorithm (evolve better rules)
    ├→ Initialize population
    ├→ Generate loop: evaluate → select → crossover → mutate
    ├→ Feed corpus: feedData()
    └→ Output: Chromosome with operator overrides
    ↓
[5] Encoder Selection
    ├→ If arithmetic-heavy → v0x02 (simple)
    └→ Otherwise → v0x01 (with cycles)
    ↓
[6] Encode
    ├→ LITERAL (9 bits)
    ├→ RELATIONAL (7-15 bits)
    └→ CYCLE (20+ bits)
    ↓
[7] Optional: Snapshot
    ├→ Serialize operators, cycles, graph
    ├→ Compute CRC32
    └→ Schedule async write
    ↓
Compressed Output
```

### Decompression Pipeline

```
Compressed Data
    ↓
[1] Read magic + version
    ├→ v0x01? → Load cycles from header
    └→ v0x02? → Load arithmetic patterns
    ↓
[2] Decode bitstream
    ├→ Read flags (0, 10, or 110)
    ├→ Apply operators or emit literals
    └→ Repeat for cycles if v0x01
    ↓
[3] Validate length
    └→ Assert output == originalLength
    ↓
Original Data
```

### Snapshot Distribution Pipeline

```
Running Encoder/Evolution
        ↓
[1] EvolutionaryEdgeFinder evolves
    ├→ Generate loop on background thread
    ├→ Update bestChromosome (atomic)
    └→ Every N generations: requestSnapshot()
    ↓
[2] SnapshotScheduler queues asynchronously
    ├→ Non-blocking: requestSnapshot() puts in queue
    └→ Scheduler thread drains queue
    ↓
[3] SnapshotSerializer writes binary file
    ├→ Build sections (graph, cycles, chromosome, history)
    ├→ Compute CRC32
    └→ Write to disk with timestamp
    ↓
Snapshot File (distributable)
    ├→ Ship with compressed data
    ├→ Resume evolution from this generation
    └→ Share across domains/instances
    ↓
[4] Decoder: SnapshotDeserializer
    ├→ Read manifest (fast)
    ├→ Validate CRC32
    ├→ Load chromosome
    └→ Apply operator rules during decompression
```

---

## Design Principles

1. **Immutability**: Operators, records, threads are immutable where possible
2. **Thread Safety**: Atomic references for shared best, locks for buffered data
3. **Lazy Initialization**: OperatorLibrary singleton, snapshot manifest loading
4. **Separation of Concerns**: Graph analysis, encoding, evolution are independent
5. **Extensibility**: New operators, sections, strategies can be added without breaking existing code
6. **Open/Closed**: Operator is open interface (extendable); core classes are closed
