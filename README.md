# MRC — Multi-value Relational Compression

A distributed Java platform that compresses streams of 8-bit unsigned integers using algebraic operator transitions, arithmetic run detection, extended operator spaces, and a continuously evolving GA engine that publishes operator sets as content-addressed snapshots.

## Quick Start

### Build all modules
```bash
mvn clean install
```

### Run all tests (193 tests across 9 modules)
```bash
mvn test
```

### Run a specific module's tests
```bash
mvn test -pl mrc-core --also-make
mvn test -pl mrc-codec --also-make
mvn test -pl mrc-ga-service --also-make
```

**Test Results by module:**

| Module | Tests | Status |
|--------|-------|--------|
| `mrc-core` | 44 | ✅ |
| `mrc-graph` | 21 | ✅ |
| `mrc-codec` | 13 | ✅ |
| `mrc-evolution` | 59 | ✅ |
| `mrc-snapshot` | 15 | ✅ |
| `mrc-snapshot-db` | 10 | ✅ |
| `mrc-codec-v3` | 11 | ✅ |
| `mrc-selector` | 13 | ✅ |
| `mrc-ga-service` | 7 | ✅ |
| **Total** | **193** | ✅ |

---

## Compression Examples

### v0x02 — Arithmetic Run Encoder (embedded use)
```java
SequenceDetector detector = new SequenceDetector();
List<ArithmeticPattern> patterns = detector.topPatterns(inputData, 255);

MrcEncoder encoder = new MrcEncoder(patterns);
CompressionResult result = encoder.encode(inputData);
System.out.println("Ratio: " + result.ratio());  // e.g., 0.0002 for arithmetic data

MrcDecoder decoder = new MrcDecoder();
byte[] decoded = decoder.decode(result.compressedData());
assert Arrays.equals(inputData, decoded);
```

### v0x03 — Snapshot-Aware Encoder (distributed use)
```java
// Encode with a snapshot from the store
SnapshotStore store = new FileSnapshotStore(Path.of("/var/mrc/snapshots"));
SnapshotAwareEncoder enc = new SnapshotAwareEncoder();
byte[] compressed = enc.encodeConnected(inputData, snapshotId, store);

// Decode on any node that has the same store
SnapshotAwareDecoder dec = new SnapshotAwareDecoder();
byte[] restored = dec.decodeConnected(compressed, store);
assert Arrays.equals(inputData, restored);
```

### DOT Graph Export
```java
TransitionGraph graph = new TransitionGraph();
graph.observe(data);
graph.exportDot(Path.of("graph.dot"));
```
Render with: `dot -Tsvg graph.dot -o graph.svg`

---

## Running the Services

### GA Engine Service
```bash
java -cp mrc-ga-service/target/mrc-ga-service-1.0.0-SNAPSHOT.jar \
     mrc.gaservice.GaServiceMain \
     --store-path /var/mrc/snapshots \
     --domain audio-v3 \
     --max-generations 500 \
     --snapshot-every 10 \
     --health-port 8080
```
Health check: `curl http://localhost:8080/health`
```json
{"status":"running","generation":120,"best_fitness":0.871234}
```

### Selector Service
```bash
java -cp mrc-selector/target/mrc-selector-1.0.0-SNAPSHOT.jar \
     mrc.selector.SelectorServerMain \
     --store-path /var/mrc/snapshots \
     --port 8081
```
Select best snapshot for a sample:
```bash
curl -X POST http://localhost:8081/select \
     --data-binary @sample.bin \
     -H "Content-Type: application/octet-stream"
```
```json
{"snapshot_id":"a3f7...","score":0.87,"domain":"audio-v3"}
```

---

## Project Structure

```
mrc-phase1/                        ← root aggregator POM
├── mrc-core/                      ← Operator algebra, OpIdMap, OperatorLibrary, ExtendedOperatorLibrary
├── mrc-graph/                     ← TransitionGraph, CycleDetector, SequenceDetector, GraphProfiler
├── mrc-codec/                     ← MrcEncoder (v0x01/v0x02), MrcDecoder, CompressionResult
├── mrc-evolution/                 ← Chromosome, GA classes (ChromosomeFactory, FitnessEvaluator, EvolutionaryEdgeFinder)
├── mrc-snapshot/                  ← Snapshot protocol: SnapshotSerializer, SnapshotDeserializer, manifest
├── mrc-snapshot-db/               ← Content-addressed FS store (SHA-256 keyed), SnapshotIndex
├── mrc-codec-v3/                  ← Snapshot-aware v0x03 encoder/decoder (SnapshotAwareEncoder/Decoder, V3Header)
├── mrc-selector/                  ← HTTP selector service: DataFingerprint, SnapshotRanker, SelectorServer
└── mrc-ga-service/                ← GA loop service: GaServiceMain, HealthServer
```

**Module dependency order** (each depends only on modules listed above it):
```
mrc-core
  └── mrc-graph
        └── mrc-codec
              └── mrc-evolution
                    └── mrc-snapshot
                          └── mrc-snapshot-db
                                ├── mrc-codec-v3  (also needs mrc-codec, mrc-snapshot)
                                ├── mrc-selector  (also needs mrc-codec)
                                └── mrc-ga-service (also needs mrc-evolution, mrc-snapshot)
```

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

### v0x03 Header (41 bytes fixed)
```
Offset  Size  Field
0       4     magic = 0x4D 0x52 0x43 0x33  ("MRC3")
4       1     mode  (0x00=standalone, 0x01=connected)
5       32    snapshot_id (SHA-256 raw bytes)
37      4     body_length (big-endian)
41      ...   encoded body (v0x02 bitstream)
```

---

## Compression Performance

| Data Type | Size | Compressed | Ratio | Notes |
|-----------|------|-----------|-------|-------|
| Arithmetic sequence | 400 KB | ~39 B | ~0.0001 | 4 ARITH_RUN tokens |
| Repetitive (step=0) | 100 KB | ~19 B | ~0.0002 | 2 ARITH_RUN tokens |
| Text-like | 300 KB | ~280 KB | ~0.93 | Mostly literals |
| Uniform random | 500 KB | ~563 KB | ~1.13 | Expected overhead |

---

## Invariants

- **8-bit masking** — all results masked to `0xFF`
- **Round-trip guarantee** — `decode(encode(data)) == data` for v0x01, v0x02, v0x03
- **Prefix-free flags** (v0x01) — `0` / `10` / `110` satisfies Kraft inequality
- **Snapshot immutability** — `FileSnapshotStore.publish()` never overwrites an existing snapshot; same content → same ID (content-addressed dedup)
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
| P2-2 | Evolutionary edge-finder GA | 59 | ✅ |
| P2-3 | Snapshot serialization protocol | 15 | ✅ |
| P3-A | Maven multi-module restructure (9 modules) | — | ✅ |
| P3-B | Content-addressed snapshot DB | 10 | ✅ |
| P3-C | HTTP selector service | 13 | ✅ |
| P3-D | v0x03 snapshot-aware codec | 11 | ✅ |
| P3-E | GA engine service + health endpoint | 7 | ✅ |

**Total: 193 tests, 0 failures**

---

## Requirements

Java 21, Maven 3.8+. No external runtime dependencies — only JDK built-ins (virtual threads, `com.sun.net.httpserver`).

```bash
mvn clean test   # build + all 193 tests
```
