# MRC Compression Theory

## Table of Contents

1. [Core Concepts](#core-concepts)
2. [Operator Algebra](#operator-algebra)
3. [Transition Graphs](#transition-graphs)
4. [Cycle Detection Theory](#cycle-detection-theory)
5. [Encoding Strategies](#encoding-strategies)
6. [Genetic Algorithm for Operator Discovery](#genetic-algorithm-for-operator-discovery)
7. [Snapshot Protocol](#snapshot-protocol)

---

## Core Concepts

### Why Relational Compression Works

Traditional byte-by-byte compression (Huffman, LZ77, etc.) treats each byte independently or as part of a sequence. **MRC is fundamentally different**: it exploits **algebraic relationships** between consecutive values.

**Key Insight**: Instead of encoding a value directly, encode the *transformation* needed to reach it from the previous value.

Example: The sequence `[10, 13, 16, 19, ...]` (arithmetic with step +3)
- **Naive**: Store all 255 possible 8-bit values → 8 bits per byte
- **MRC**: Store one operator rule "Add(3)" → reuse for the entire sequence → ~1 bit per byte

### The Core Metric: Compression Gain

For any operator `op` and transition `from → to`:
- **Operator Cost** (bits to encode the operator): `5-bit opId + operand bits` = 7-15 bits
- **Direct Cost** (encode target byte directly): 8 bits
- **Compression Gain**: If we use this operator N times, we save `8N - (7N + operator_cost) = N - operator_cost` bits

**Threshold**: Break-even is reached when an operator is used ~3+ times (for a 5-bit cost).

---

## Operator Algebra

### Base Operators (Phase 1)

MRC includes 11 base operators, each with a unique 5-bit `opId` (0-10):

| OpId | Name | Operand | Cost | Formula |
|------|------|---------|------|---------|
| 0 | Add | 0-255 | 8 bits | `(x + operand) & 0xFF` |
| 1 | Sub | 0-255 | 8 bits | `(x - operand) & 0xFF` |
| 2 | Mul | 0-255 | 8 bits | `(x * operand) & 0xFF` |
| 3 | Div | 1-255 | 8 bits | `x / operand` (skip 0) |
| 4 | Mod | 1-255 | 8 bits | `x % operand` (skip 0) |
| 5 | XorOp | 0-255 | 8 bits | `x ^ operand` |
| 6 | AndOp | 0-255 | 8 bits | `x & operand` |
| 7 | OrOp | 0-255 | 8 bits | `x \| operand` |
| 8 | ShiftLeft | 1-7 | 3 bits | `(x << bits) & 0xFF` |
| 9 | ShiftRight | 1-7 | 3 bits | `x >> bits` |
| 10 | Not | none | 0 bits | `~x & 0xFF` |

**Total Instances**: ~2,400 (all valid combinations: 256 Add, 255 Sub, ..., 1 Not)

### Why 8-bit Wrapping?

All operators use modulo 256 arithmetic (`result & 0xFF`). This is intentional:
- Matches 8-bit byte boundary naturally
- Preserves algebraic structure (group theory: Z/256Z is a ring)
- Allows predictable composition: `f ∘ g` is also in the operator space

### Extended Operators (Phase 2)

Three levels of extended operators for discovering novel transitions:

**Level 1: FunctionOperators** (opId 32-39)
- `Polynomial(a, b, c)`: `y = ax² + bx + c` (mod 256)
- `LinearCongruential(a, c)`: `y = ax + c` (mod 256)
- `TableLookup`: 256-byte lookup table
- `RotateLeft/RotateRight`: Bit rotation
- `BitReverse`, `NibbleSwap`, `DeltaOfDelta`: Bit/byte manipulations

**Level 2: CompositeOperators** (opId 40)
- Chain of 2-4 base operators: `f1 ∘ f2 ∘ f3`
- Auto-optimize: `Add(1) ∘ Add(2)` → `Add(3)`
- Enables modeling complex patterns

**Level 3: SuperfunctionOperators** (opId 64-66)
- `Iterated(base, n)`: Apply operator n times
- `FixedPointReach(op)`: Find n such that f^n(x) = fixed point
- `Conjugate(h, f, h⁻¹)`: Conjugation: y = h⁻¹(f(h(x)))

---

## Transition Graphs

### Graph Construction: From Data to Structure

A **transition graph** is a directed multigraph where:
- **Nodes**: The 256 possible byte values (0-255)
- **Edges**: Operator transitions from one value to another
- **Weight**: Frequency of this transition in training data

**Construction Algorithm**:
```
for each consecutive pair (prev, curr) in training data:
    1. Find cheapest operator op that transforms prev → curr
    2. Lookup edge (prev, curr, op) or create it
    3. Increment frequency count
    4. Update weight = freq × (8 - costBits)
```

**Weight Interpretation**:
- Positive weight: This operator *compresses* (saves bits)
- Negative weight: This operator costs bits (but still useful for cycles)
- Higher weight: Higher compression potential

### Why Graphs Reveal Structure

Data with patterns creates dense graphs:
- **Random data**: Sparse, uniform edges
- **Text data**: Dense regions (common byte transitions like 'e' → 'r')
- **Arithmetic sequences**: Clique-like structure (strong operators repeated)

The graph becomes a **compression roadmap**: find high-weight paths through the graph to maximize savings.

---

## Cycle Detection Theory

### What is a Cycle?

A **cycle** is a closed path in the transition graph that returns to its starting node:
```
start → op1 → node1 → op2 → node2 → ... → opN → start
```

**Key Insight**: A cycle is compressible if the total cost of operators is less than 8N bits (where N is cycle length).

**Example Cycle** [10, 13, 16, 10]:
- `10 --[Add(3)]--> 13`
- `13 --[Add(3)]--> 16`
- `16 --[Sub(6)]--> 10`
- Total cost: `(5+8) + (5+8) + (5+8) = 39 bits`
- Direct cost: `3 × 8 = 24 bits`
- **Not compressing alone**, but if this cycle repeats, savings grow!

### Cycle Detection: Tarjan's Algorithm

MRC uses **Tarjan's Strongly Connected Components (SCC)** algorithm for cycle detection.

**Why Tarjan?** It's O(V+E) and naturally identifies maximal sets of mutually-reachable nodes.

**Algorithm**:
1. Build SCCs (clusters where every node reaches every other)
2. Within each SCC, use **Johnson's algorithm** for cycle enumeration
3. DFS from each node, mark back-edges (edges to ancestors) as cycle starts
4. Limit cycle length (default 8) to prevent combinatorial explosion

**Deduplication**: Track seen cycles by node-set hash to avoid reporting duplicates.

### Cycle Compression Gain

For a cycle of length L with operators having costs `c1, c2, ..., cL`:
```
If repeated K times:
  Direct cost: L × K × 8 bits
  Cycle cost: (c1 + c2 + ... + cL) + 3 (flag) + log2(cycleCount) + 16 (repeat count) bits

  Gain = L × K × 8 - (sum of costs + overhead)
```

**Break-even**: Depends on cycle cost and repeat count. Generally K ≥ 2 is needed.

---

## Encoding Strategies

### Three Encoding Tiers (v0x01: Cycle-based)

MRC uses **prefix-free coding** to disambiguate encoding choices:

#### Tier 1: LITERAL (Flag = 0)
- **Cost**: 1 (flag) + 8 (value) = **9 bits**
- **Usage**: When no operator exists for transition
- **Format**: `0 | XXXXXXXX` (1 flag bit + 8 data bits)

#### Tier 2: RELATIONAL (Flag = 10)
- **Cost**: 2 (flag) + 5 (opId) + 0-8 (operand) = **7-15 bits**
- **Usage**: When an operator saves bits (cost < 8)
- **Format**: `10 | OOOOO | [operand bits]`
- **Examples**:
  - `Add(3)`: 10 + opId=0 + 8 bits = 15 bits (not compressing)
  - `ShiftLeft(1)`: 10 + opId=8 + 3 bits = 10 bits (saving 1 bit per byte)

#### Tier 3: CYCLE (Flag = 110)
- **Cost**: 3 (flag) + ceil(log2(cycleCount)) + 16 (repeat count) = **~20-25 bits base**
- **Usage**: When a cycle repeats 2+ times and saves overall bits
- **Format**: `110 | [cycleIndex bits] | [repeat count]`
- **Break-even**: Cycle cost ÷ (cycle_length × 8) ≈ saves ~3+ bits per cycle length

### Two Encoding Tiers (v0x02: Arithmetic-based)

Simpler encoding for data with strong arithmetic patterns:

#### Tier 1: LITERAL (Flag = 0)
- **Cost**: 1 + 8 = **9 bits**

#### Tier 2: ARITH_RUN (Flag = 1)
- **Cost**: 1 + 8 (stepIdx) + 8 (startVal) + 16 (runLen) = **33 bits**
- **Usage**: For runs of length ≥ 4 (break-even point)
- **Formula**: Each element is `startVal + i × step` (mod 256)
- **Example**: 100, 103, 106, 109, 112 → ARITH_RUN(step=+3, start=100, len=5)

**Why v0x02 excels**: Detects arithmetic runs via linear scan O(n), applies optimally.

---

## Genetic Algorithm for Operator Discovery

### Why Genetic Algorithms?

Finding the *best* operator set for unknown data is NP-hard (search space is exponential in operator instances). A genetic algorithm (GA) provides:
- **Approximate solutions** in reasonable time
- **Adaptive learning** from data-specific patterns
- **Parallelizable** fitness evaluation

### GA Components

**Chromosome**: A list of (fromValue, toValue, Operator) rules
- Represents: "when transitioning from X to Y, use this operator"
- Overrides default graph lookups
- Compact: ~20-100 rules typical, max 1024

**Fitness**: Compression ratio on training corpus
```
fitness = (1.0 - compressedSize / originalSize) - 0.001 × chromosomeSize
```
The **parsimony penalty** (0.001 × size) prevents rule bloat.

**Selection**: Tournament selection (pick fittest from random tournament)
- Preserves diversity better than roulette-wheel selection
- Prevents premature convergence

**Crossover: Operator-Aware (Recommended)**
- Group rules by (fromValue, toValue) pair
- Merge parents by keeping the cheaper operator for shared pairs
- Respects domain structure → faster convergence

**Mutation**: Six types applied independently
- RULE_REPLACE: Swap operator in a rule
- RULE_ADD: Add new rule for uncovered transition
- RULE_REMOVE: Delete a rule (regress to default)
- OPERATOR_UPGRADE: Replace with cheaper extended operator
- COMPOSITE_SPLIT / COMPOSITE_MERGE: Decompose/combine operators

### Convergence Criteria

Evolution **stalls** when best fitness doesn't improve > 0.0001 for 500 generations. Detector signals:
- May inject random chromosomes for diversity
- Or terminate if resource-constrained

---

## Snapshot Protocol

### Why Snapshots?

The evolved operator set is a **learned artifact**: valuable to distribute and reuse.

**Three Use Cases**:
1. **Codec Distribution**: Ship snapshot with compressed file → decoder loads operators
2. **Evolution Checkpointing**: Resume from generation N without restarting
3. **Domain Specialization**: Financial data snapshot vs. sensor data snapshot

### Snapshot Versioning

Format version 2.0:
- **Major version** must match (backward-incompatible changes)
- **Minor version** differences are backward-compatible
- Enables evolution of format over time

### Section-Based Extensibility

Six section types, each optional:
- **OPERATOR_TABLE**: Extended operators (TableLookup data, etc.)
- **TRANSITION_GRAPH**: Graph edges + weights (for visualization)
- **CYCLE_TABLE**: Detected cycles + compression gain (for analysis)
- **CHROMOSOME**: Best evolved solution (for resuming GA)
- **EVOLUTION_STATS**: Last 1000 generation results (for analysis)
- **VALIDATION_HASH**: SHA-256 of training corpus (for verification)

**Lazy Loading**: Read manifest first (header + section offsets), load only requested sections.

### CRC32 Validation

Detects file corruption:
- Computed over all bytes except magic + CRC field
- Post-computation: write snapshot, compute CRC, seek back, update
- Prevents silent decompression corruption

---

## Summary: Compression Pipeline

```
Raw Data
   ↓
[Operator Library] — 2,400+ base operators
   ↓
[Transition Graph] — analyze data patterns
   ↓
[Cycle Detection] — find repeating structures
   ↓
[Genetic Algorithm] — evolve better operator rules (optional)
   ↓
[Encoder] — apply best rules:
   ├→ LITERAL (9 bits)
   ├→ RELATIONAL (7-15 bits)
   └→ CYCLE (20+ bits)
   ↓
[Snapshot] — serialize operator set for distribution
   ↓
Compressed Data
```

Each stage builds on the previous to maximize compression while remaining lossless.

---

## References

- **Tarjan's Algorithm**: Tarjan, R. (1972). "Depth-first search and linear graph algorithms"
- **Johnson's Algorithm**: Johnson, D. (1975). "Finding all the elementary circuits of a directed graph"
- **Genetic Algorithms**: Holland, J. (1975). "Adaptation in Natural and Artificial Systems"
- **Prefix-Free Codes**: Shannon, C. (1948). "A Mathematical Theory of Communication"
