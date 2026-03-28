# MRC — Multi-value Relational Compression

A self-contained Java library that compresses streams of 8-bit unsigned integers using algebraic operator transitions, arithmetic run detection, and extended operator spaces.

## Quick Start

### Build
```bash
mvn clean compile
```

### Run All Tests (78 tests)
```bash
mvn test
```

**Test Results:**
- `OperatorLibraryTest`: 17/17 ✅
- `TransitionGraphTest`: 14/14 ✅
- `CycleDetectorTest`: 7/7 ✅
- `RoundTripTest`: 9/9 ✅
- `LargeInputBenchmarkTest`: 4/4 ✅
- `ExtendedOperatorTest`: 27/27 ✅

### Run Specific Tests
```bash
mvn test -Dtest=OperatorLibraryTest
mvn test -Dtest=TransitionGraphTest
mvn test -Dtest=CycleDetectorTest
mvn test -Dtest=RoundTripTest
mvn test -Dtest=LargeInputBenchmarkTest   # 1.3 MB test inputs
mvn test -Dtest=ExtendedOperatorTest      # Phase 2 extended operators
```

### Run Main
```bash
mvn compile exec:java -Dexec.mainClass=mrc.Main
```

---

## Compression Example

### v0x02 — Arithmetic Run Encoder (recommended)
```java
// Detect arithmetic runs in the data
SequenceDetector detector = new SequenceDetector();
List<ArithmeticPattern> patterns = detector.topPatterns(inputData, 255);

// Compress
MrcEncoder encoder = new MrcEncoder(patterns);
CompressionResult result = encoder.encode(inputData);
System.out.println("Ratio: " + result.ratio());  // e.g., 0.0002 for arithmetic data

// Decompress
MrcDecoder decoder = new MrcDecoder();
byte[] decoded = decoder.decode(result.compressedData());
assert Arrays.equals(inputData, decoded);
```

### v0x01 — Cycle-based Encoder
```java
TransitionGraph graph = new TransitionGraph();
graph.observe(trainingData);

CycleDetector detector = new CycleDetector(graph, 8);
MrcEncoder encoder = new MrcEncoder(graph, detector.topCycles(255));
CompressionResult result = encoder.encode(inputData);

MrcDecoder decoder = new MrcDecoder();
byte[] decoded = decoder.decode(result.compressedData());
```

### DOT Graph Export
```java
TransitionGraph graph = new TransitionGraph();
graph.observe(data);

// Explicit path
graph.exportDot(Path.of("graph.dot"));

// Or configure a default directory
graph.setExportDir(Path.of(".tmp/xyz"));
graph.exportDot();  // writes .tmp/xyz/mrc_graph.dot
```
Render with: `dot -Tsvg graph.dot -o graph.svg`

---

## Project Structure

```
src/
├── main/java/mrc/                        # 38 source files
│   ├── Main.java
│   ├── core/                             # Operator algebra
│   │   ├── Operator.java                 # Interface (open, not sealed)
│   │   ├── Add, Sub, Mul, Div, Mod       # Arithmetic operators
│   │   ├── XorOp, AndOp, OrOp           # Bitwise operators
│   │   ├── ShiftLeft, ShiftRight, Not    # Shift/invert operators
│   │   ├── OpIdMap.java                  # Type-level opId mapping (0..10)
│   │   ├── OperatorLibrary.java          # Singleton, ~2,400 instances
│   │   ├── Transition.java
│   │   ├── ValueCluster.java
│   │   └── extended/                     # Phase 2 — Level 1-3 operators
│   │       ├── OperatorArity.java        # UNARY / BINARY enum
│   │       ├── FunctionOperator.java     # Sealed: Polynomial, LinearCongruential,
│   │       │                             #   TableLookup, RotateLeft, RotateRight,
│   │       │                             #   BitReverse, NibbleSwap, DeltaOfDelta
│   │       ├── SuperfunctionOperator.java # Sealed: Iterated, FixedPointReach, Conjugate
│   │       ├── CompositeOperator.java    # 2-4 operator chain + optimized()
│   │       ├── OperatorCostModel.java    # Break-even analysis utilities
│   │       └── ExtendedOperatorLibrary.java # Extends base library
│   ├── graph/                            # Transition graph and analysis
│   │   ├── TransitionGraph.java          # Directed multigraph, DOT export
│   │   ├── TransitionEdge.java
│   │   ├── CycleDetector.java            # Tarjan's SCC + Johnson's algorithm
│   │   ├── CyclePath.java
│   │   ├── ArithmeticPattern.java        # step, runCount, totalRunBytes, savings
│   │   ├── SequenceDetector.java         # Linear scan for arithmetic runs
│   │   └── GraphProfiler.java            # Statistics and analysis
│   ├── codec/                            # Encoder / decoder
│   │   ├── BitStreamWriter.java
│   │   ├── BitStreamReader.java
│   │   ├── EncodingTier.java             # LITERAL, RELATIONAL, CYCLE, ARITH_RUN
│   │   ├── MrcEncoder.java               # v0x01 (cycle) + v0x02 (arith run)
│   │   ├── MrcDecoder.java               # Version-dispatched decoder
│   │   ├── CompressionResult.java
│   │   └── MrcFormatException.java
│   └── bench/                            # Benchmarks and validation
│       ├── RandomBaselineSuite.java      # 6 baseline tests, exports DOT to .tmp/xyz
│       └── CompressionBenchmark.java
└── test/java/mrc/                        # 8 test files
    ├── core/OperatorLibraryTest.java
    ├── core/extended/ExtendedOperatorTest.java
    ├── graph/TransitionGraphTest.java
    ├── graph/CycleDetectorTest.java
    ├── codec/RoundTripTest.java
    ├── LargeInputBenchmarkTest.java
    ├── TestInputs.java
    └── test/resources/test-inputs/
        ├── random-500kb.bin
        ├── arithmetic-400kb.bin
        ├── text-like-300kb.bin
        └── repetitive-100kb.bin
```

---

## Architecture

### Core Module (`mrc/core/`)

**`Operator`** — Open interface for algebraic byte transformations:
- 11 base implementations: `Add`, `Sub`, `Mul`, `Div`, `Mod`, `XorOp`, `AndOp`, `OrOp`, `ShiftLeft`, `ShiftRight`, `Not`
- Each has a unique 5-bit opId and encoding cost (operandBits: 0, 3, or 8)
- `apply(int x)` — unary transform; `apply(int x, int context)` — binary (default delegates to unary)
- `OperatorLibrary` — singleton, lazily builds and caches all ~2,400 instances

**`extended/` — Phase 2 Level 1–3 operators:**

| Level | Class | Implementations | opId range |
|-------|-------|----------------|------------|
| 1 | `FunctionOperator` | Polynomial, LinearCongruential, TableLookup, RotateLeft, RotateRight, BitReverse, NibbleSwap, DeltaOfDelta | 32–39 |
| 2 | `CompositeOperator` | Chain of 2–4 operators; `optimized()` fuses Add+Add, XOR+XOR, Not+Not, etc. | 40 |
| 3 | `SuperfunctionOperator` | Iterated (f^n), FixedPointReach, Conjugate (h⁻¹∘f∘h) | 64–66 |

`OperatorCostModel` — break-even analysis: `relationalTokenCost()`, `arithRunBreakEven()=4`, `estimatedArithRunSavings(runLen)`.

`ExtendedOperatorLibrary` — extends base library; `findShortestExtended(from, to)` searches both pools.

---

### Graph Module (`mrc/graph/`)

**`TransitionGraph`** — Directed multigraph, 256 nodes (one per byte value):
- `observe(byte[])` — builds edges from training data; keeps cheapest operator per (from, to) pair
- `exportDot(Path)` — Graphviz DOT export; emits only positive-weight edges, frequency-scaled penwidth
- `setExportDir(Path)` / `exportDot()` — no-arg overload using configured directory

**`SequenceDetector`** — Linear scan for arithmetic runs (mod-256 constant step):
- `MIN_RUN = 4` bytes (ARITH_RUN token breaks even at 4 × 9 bits = 36 > 33 bits)
- `detect(byte[])` — returns all `ArithmeticPattern` records sorted by estimated savings
- `topPatterns(byte[], int k)` — top-K via min-heap, O(n log k)

**`ArithmeticPattern`** — `record(int step, long totalRunBytes, long runCount, long estimatedSavingBits)`:
- `signedStep()` — converts 0..255 to −128..127
- `label()` — human-readable e.g. `+3`, `repeat(0)`, `-1`

**`CycleDetector`** — Johnson's algorithm with Tarjan's SCC preprocessing:
- Parallelised per SCC via `ExecutorService`
- `topCycles(int k)` — O(n log k) partial sort by compression gain

---

### Codec Module (`mrc/codec/`)

**Two format versions, both lossless:**

#### v0x01 — Cycle-based (original)
```
Header: MRC + 0x01 + cycle table + original length
Data:   LITERAL  (flag=0  + 8 bits = 9 bits)
        RELATIONAL (flag=10 + 5-bit opId + operand = 7-15 bits)
        CYCLE    (flag=110 + index + 16-bit count)
```

#### v0x02 — Arithmetic run (current default)
```
Header: MRC + 0x02 + step count + step values + original length
Data:   LITERAL   (flag=0 + 8 bits  = 9 bits)
        ARITH_RUN (flag=1 + 8-bit stepIdx + 8-bit startVal + 16-bit runLen = 33 bits)
```

`MrcEncoder` constructor dispatch:
- `MrcEncoder(List<ArithmeticPattern>)` → v0x02
- `MrcEncoder(TransitionGraph, List<CyclePath>)` → v0x01

`MrcDecoder.decode()` reads the version byte and dispatches automatically.

---

### Bench Module (`mrc/bench/`)

**`RandomBaselineSuite`** — 6 validation tests, each encoding + round-trip verifying:
1. Uniform random (≤ 2% overhead)
2. LCG stream (algebraic recurrence)
3. Mersenne Twister
4. Gaussian quantized (clustered transitions)
5. Arithmetic sequence (< 30% ratio)
6. Sine wave (periodic structure)

Each test exports a named `.dot` file to `.tmp/xyz` by default.
Override: `RandomBaselineSuite.runAll(out, Path.of("custom/dir"))`.

---

## Compression Performance

| Data Type | Size | Compressed | Ratio | Notes |
|-----------|------|-----------|-------|-------|
| Arithmetic sequence | 400 KB | ~39 B | ~0.0001 | 4 ARITH_RUN tokens |
| Repetitive (step=0) | 100 KB | ~19 B | ~0.0002 | 2 ARITH_RUN tokens |
| Text-like | 300 KB | ~280 KB | ~0.93 | Mostly literals |
| Uniform random | 500 KB | ~563 KB | ~1.13 | Expected overhead |

---

## Bitstream Format Details

### opId table (base operators)
```
Add=0  Sub=1  Mul=2  Div=3  Mod=4
XorOp=5  AndOp=6  OrOp=7
ShiftLeft=8  ShiftRight=9  Not=10
```
Operand bits: 0 for Not, 3 for shifts, 8 for all arithmetic/bitwise.

### Extended operator opIds
```
FunctionOperator:     Polynomial=32  LinearCongruential=33  TableLookup=34
                      RotateLeft=35  RotateRight=36  BitReverse=37
                      NibbleSwap=38  DeltaOfDelta=39
CompositeOperator:    40
SuperfunctionOperator: Iterated=64  FixedPointReach=65  Conjugate=66
```

---

## Invariants

- **8-bit masking** — all results masked to `0xFF`
- **Round-trip guarantee** — `decode(encode(data)) == data` for both v0x01 and v0x02
- **Prefix-free flags** (v0x01) — `0` / `10` / `110` satisfies Kraft inequality
- **Cycle table limit** — max 255 cycles (8-bit count field)
- **Div/Mod(0) excluded** — never registered in OperatorLibrary
- **ARITH_RUN runs split at 65535** — 16-bit length field limit

---

## Implementation Status

| Phase | Description | Tests | Status |
|-------|-------------|-------|--------|
| 1 | OpId fix, OperatorLibrary (~2,400 instances) | 17 | ✅ |
| 2 | Graph & bitstream fixes | 14 | ✅ |
| 3 | Cycle detection (Tarjan's SCC + Johnson's) | 7 | ✅ |
| 4 | Encoder/decoder (v0x01) | 9 | ✅ |
| 5 | GraphProfiler, DOT export | — | ✅ |
| 6a | v0x02 arithmetic run codec | 4 | ✅ |
| P2-1 | Extended operator space (Levels 1–3) | 27 | ✅ |
| P2-2 | Evolutionary edge-finder | — | 🔜 |
| P2-3 | Snapshot protocol | — | 🔜 |

**Total: 38 source files, 78 tests**

---

## Requirements

Java 21, Maven 3.8+. Maven handles `--enable-preview` for pattern matching.

```bash
mvn clean test   # build + all 78 tests
```
