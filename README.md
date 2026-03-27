# MRC Phase 1 — Multi-value Relational Compression

A self-contained Java library that compresses streams of 8-bit unsigned integers using algebraic operator transitions and cycle detection.

## Quick Start

### Build
```bash
mvn clean compile
```

### Run Tests
```bash
mvn test
```

### Run Baseline Suite
```bash
mvn test -Dtest=RandomBaselineSuite
```

## Project Structure

```
mrc-phase1/
├── pom.xml
├── README.md
└── src/
    ├── main/java/mrc/
    │   ├── core/              # Operator algebra and value model
    │   │   ├── Operator.java
    │   │   ├── Add.java, Sub.java, Mul.java, Div.java
    │   │   ├── Mod.java, XorOp.java, AndOp.java, OrOp.java
    │   │   ├── ShiftLeft.java, ShiftRight.java, Not.java
    │   │   ├── OperatorLibrary.java
    │   │   ├── Transition.java
    │   │   └── ValueCluster.java
    │   ├── graph/              # Directed transition graph
    │   │   ├── TransitionGraph.java
    │   │   ├── TransitionEdge.java
    │   │   ├── CycleDetector.java
    │   │   ├── CyclePath.java
    │   │   └── GraphProfiler.java
    │   ├── codec/              # Encoder and decoder
    │   │   ├── BitStreamWriter.java
    │   │   ├── BitStreamReader.java
    │   │   ├── EncodingTier.java
    │   │   ├── MrcEncoder.java
    │   │   ├── MrcDecoder.java
    │   │   ├── CompressionResult.java
    │   │   └── MrcFormatException.java
    │   └── bench/              # Benchmarks and validation
    │       ├── RandomBaselineSuite.java
    │       └── CompressionBenchmark.java
    └── test/java/mrc/
        ├── core/OperatorLibraryTest.java
        ├── graph/TransitionGraphTest.java
        ├── graph/CycleDetectorTest.java
        └── codec/RoundTripTest.java
```

## Architecture Overview

### Core Module (`mrc/core/`)

**Operator** — Sealed interface representing algebraic transformations:
- 12 implementations: `Add`, `Sub`, `Mul`, `Div`, `Mod`, `XorOp`, `AndOp`, `OrOp`, `ShiftLeft`, `ShiftRight`, `Not`
- Each operator has a unique 5-bit ID and encoding cost
- `OperatorLibrary` — singleton registry of all 2,400 operator instances

**Transition** — Record representing a single-operator transition from one 8-bit value to another:
- Cost in bits = 5 (opId) + operand bits
- May be "compressing" if cost < 8 bits

**ValueCluster** — Record representing a centered range of values (placeholder for fuzzy algebra in Phase 2):
- Current implementation: 256 clusters, one per value

### Graph Module (`mrc/graph/`)

**TransitionGraph** — Directed multigraph with 256 nodes (one per 8-bit value):
- Built by observing transitions in a training data stream
- Edges stored as best (lowest-cost) operator per (from, to) pair
- Exportable to Graphviz DOT format

**TransitionEdge** — Record combining operator, cost, and observed frequency:
- Weight = frequency × compression gain

**CycleDetector** — Finds all simple cycles up to a bounded length using Johnson's algorithm:
- Prerequisite: Tarjan's SCC (Strongly Connected Components) to identify cycle-bearing subgraphs
- Returns cycles sorted by compression gain

**CyclePath** — Record representing a detected cycle:
- Immutable list of nodes and edges forming the cycle
- Compression gain = bits saved per full cycle traversal

**GraphProfiler** — Utility for printing human-readable analysis:
- Edge and cycle statistics
- Coverage estimates

### Codec Module (`mrc/codec/`)

**BitStreamWriter** / **BitStreamReader** — Bit-level I/O:
- Write/read individual bits or arbitrary-width values
- Byte-aligned padding at flush

**EncodingTier** — Enum representing three encoding strategies:
- `LITERAL` — raw 8-bit value (flag = `0`, 1 bit)
- `RELATIONAL` — operator application to previous value (flag = `10`, 2 bits)
- `CYCLE` — repeated traversal of a pre-detected cycle (flag = `110`, 3 bits)

**MrcEncoder** — Lossless compressor:
- Per-value state machine tracking active cycles and relational chains
- Outputs bitstream with header (magic, version, cycle table) + data tier-encoded
- Returns `CompressionResult` with ratio and statistics

**MrcDecoder** — Lossless decompressor:
- Reads header and reconstructs cycle table
- Tier-aware bitstream reader with prefix-free flag decoding
- Guarantees round-trip: `decode(encode(data)) == data`

**CompressionResult** — Immutable record with compression metrics:
- Original/compressed byte and bit counts
- Ratio and space saving percentage
- Tier usage counts and list of cycles invoked
- Encoding/decoding wall-clock times (nanoseconds)

**MrcFormatException** — Checked exception for bitstream format errors:
- Thrown on invalid headers, truncated data, or opId out of bounds

### Bench Module (`mrc/bench/`)

**RandomBaselineSuite** — Validation test suite:
1. **Uniform random** — ≤ 2% overhead expected
2. **Linear congruential generator** — algebraic recurrence detectable
3. **Mersenne Twister** — pseudo-random baseline
4. **Gaussian quantized** — clustered transitions
5. **Arithmetic sequence** — highly compressible (< 30% ratio)
6. **Sine wave** — periodic structure

Each test encodes, decodes, verifies round-trip, and reports compression ratio.

**CompressionBenchmark** — JMH benchmark suite (optional):
- Throughput measurements (MB/s) for encoding/decoding and graph construction

## Compression Targets (Phase 1)

| Data Type | Expected Ratio | Notes |
|-----------|---|---|
| Arithmetic (Δ=1) | < 0.15 | Single Add(1) cycle |
| Sine wave | 0.30–0.50 | Periodic structure |
| Gaussian | 0.60–0.75 | Transition clustering |
| LCG | 0.70–0.85 | Algebraic structure |
| Uniform random | 0.98–1.02 | Near-incompressible |

## Invariants

- **8-bit masking**: All arithmetic results masked to `0xFF` before state update
- **Prefix-free flags**: The 0 / 10 / 110 flag structure satisfies Kraft inequality for unambiguous decoding
- **Cycle table limit**: Maximum 255 embedded cycles (8-bit index field)
- **Division by zero**: Never registered; `Div(0)` instances are excluded from OperatorLibrary
- **Training/encoding separation**: `TransitionGraph.observe()` called on training data before encoding

## Compilation & Java 21

Requires Java 21 with `--enable-preview` flag (for pattern matching on sealed types):

```bash
javac --release 21 --enable-preview *.java
```

Maven handles this via `pom.xml` compiler configuration.

## Testing

Run all tests:
```bash
mvn test
```

Run specific test class:
```bash
mvn test -Dtest=OperatorLibraryTest
mvn test -Dtest=TransitionGraphTest
mvn test -Dtest=CycleDetectorTest
mvn test -Dtest=RoundTripTest
```

## What's Next (Phase 2)

- Evolutionary edge-finder (genetic algorithm)
- Persistent graph snapshots (serialize to file)
- 16-bit value support
- Fuzzy tolerance layer (ε-matching)
- Multi-dimensional vector fields

---

**Generated for MRC Phase 1 — Java 21 Edition**
