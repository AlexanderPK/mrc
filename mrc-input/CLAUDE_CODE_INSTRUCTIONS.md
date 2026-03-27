# MRC Phase 1 — Auto-Configuration Request

## Task: Generate Complete Project Structure

Read the three multiagent configuration documents and automatically:

1. **Generate pom.xml** with all Maven config (Java 21, JUnit 5, JMH, --enable-preview)
2. **Generate directory structure** matching spec
3. **Generate README.md** with quick start, compression targets, architecture overview
4. **Generate all 22 source files** (see list below)
5. **Generate stub implementations** for each class with:
   - Correct signatures per spec
   - Javadoc comments
   - TODO markers for complex logic (e.g., Johnson's algorithm, encoder state machine)
6. **Generate test stubs** with @Test methods marked TODO

## Input Documents
- See: mrc-phase1-multiagent-config.md (§ Agent 1–5 responsibilities)
- See: mrc-phase1-agent-coordination.md (§ Appendix A: Directory Structure)
- See: mrc-original-spec.md (§ Repository Structure)

## Output Artifact: mrc-phase1/ directory with:

### Core (5 files)
- src/main/java/mrc/core/Operator.java
- src/main/java/mrc/core/{Add,Sub,Mul,Div,Mod,XorOp,AndOp,OrOp,ShiftLeft,ShiftRight,Not}.java
- src/main/java/mrc/core/OperatorLibrary.java
- src/main/java/mrc/core/Transition.java
- src/main/java/mrc/core/ValueCluster.java

### Graph (5 files)
- src/main/java/mrc/graph/TransitionGraph.java
- src/main/java/mrc/graph/TransitionEdge.java
- src/main/java/mrc/graph/CycleDetector.java
- src/main/java/mrc/graph/CyclePath.java
- src/main/java/mrc/graph/GraphProfiler.java

### Codec (6 files)
- src/main/java/mrc/codec/BitStreamWriter.java
- src/main/java/mrc/codec/BitStreamReader.java
- src/main/java/mrc/codec/EncodingTier.java
- src/main/java/mrc/codec/MrcEncoder.java
- src/main/java/mrc/codec/MrcDecoder.java
- src/main/java/mrc/codec/CompressionResult.java
- src/main/java/mrc/codec/MrcFormatException.java

### Bench (2 files)
- src/main/java/mrc/bench/RandomBaselineSuite.java
- src/main/java/mrc/bench/CompressionBenchmark.java

### Tests (4 files)
- src/test/java/mrc/core/OperatorLibraryTest.java
- src/test/java/mrc/graph/TransitionGraphTest.java
- src/test/java/mrc/graph/CycleDetectorTest.java
- src/test/java/mrc/codec/RoundTripTest.java

### Build & Docs (2 files)
- pom.xml
- README.md

## Constraints
- Java 21 features: sealed interfaces, records, pattern matching
- No external dependencies except JUnit 5 and JMH (test scope)
- All classes must match signatures in multiagent-config.md exactly
- Javadoc on all public APIs
- TODO markers for complex algorithms (don't implement, just scaffold)

## Acceptance Criteria
- [ ] All 22 files generated
- [ ] Directory structure matches spec
- [ ] pom.xml compiles with `mvn clean compile`
- [ ] All classes are valid Java 21 code
- [ ] All public methods have Javadoc
- [ ] No compilation errors