# MRC Phase 1 — Agent Coordination Workflow

## Implementation Timeline & Dependency Graph

```
PHASE 1: SPECIFICATION & PLANNING (Day 0)
  All Agents
  ├─ Read multiagent-config.md
  ├─ Validate module scope
  ├─ Identify ambiguities
  └─ Output: Clarifying questions (if any)
  
  SYNC POINT 1: Config Review
  └─ Resolve ambiguities; green light to Phase 2

PHASE 2: IMPLEMENTATION (Days 1–5)
  
  ┌─────────────────────────────────────────────────────────────┐
  │ AGENT 1: CORE ALGEBRA AGENT (Sequential, Day 1)             │
  │ Task: Implement core/ module                                │
  ├─────────────────────────────────────────────────────────────┤
  │ 1. Design Operator sealed interface (1h)                    │
  │ 2. Implement 12 Operator subclasses (2h)                    │
  │ 3. Build OperatorLibrary singleton (1.5h)                  │
  │ 4. Optimize findShortest() with cache (1h)                 │
  │ 5. Implement Transition record (0.5h)                       │
  │ 6. Implement ValueCluster record (0.5h)                     │
  │ 7. Unit tests: OperatorLibraryTest (1.5h)                  │
  │ 8. Verify all checklist items (1h)                          │
  │                                                             │
  │ Estimated time: 9.5h                                        │
  │ Deliverable: OperatorLibrary instance                       │
  │ Output files:                                               │
  │   - src/main/java/mrc/core/Operator.java                    │
  │   - src/main/java/mrc/core/{Add,Sub,Mul,Div,...}.java      │
  │   - src/main/java/mrc/core/OperatorLibrary.java             │
  │   - src/main/java/mrc/core/Transition.java                  │
  │   - src/main/java/mrc/core/ValueCluster.java                │
  │   - src/test/java/mrc/core/OperatorLibraryTest.java         │
  └─────────────────────────────────────────────────────────────┘
           ║ HANDS OFF TO: Agents 2, 3, 4, 5
           ║
           ▼
  ┌─────────────────────────────────────────────────────────────┐
  │ AGENT 3: CODEC INFRASTRUCTURE AGENT (Parallel, Days 1–2)   │
  │ Task: Implement bitstream layer (independent of graph)      │
  ├─────────────────────────────────────────────────────────────┤
  │ 1. Design BitStreamWriter (0.5h)                            │
  │ 2. Design BitStreamReader (0.5h)                            │
  │ 3. Implement EncodingTier enum (0.5h)                       │
  │ 4. Unit tests: BitStream read/write round-trip (1h)        │
  │ 5. Verify prefix-free codes (0.5h)                          │
  │                                                             │
  │ Estimated time: 3.5h (can start in parallel on Day 1)      │
  │ Deliverable: BitStream classes + EncodingTier              │
  │ Output files:                                               │
  │   - src/main/java/mrc/codec/BitStreamWriter.java            │
  │   - src/main/java/mrc/codec/BitStreamReader.java            │
  │   - src/main/java/mrc/codec/EncodingTier.java               │
  └─────────────────────────────────────────────────────────────┘
           ║
           ╠═══════════════════════════════════════════════════╗
           ║                                                   ║
           ▼                                                   ▼
  ┌──────────────────────────────┐         ┌──────────────────────────────┐
  │ AGENT 2: GRAPH ARCH AGENT    │         │ AGENT 4: VALIDATION AGENT    │
  │ (Days 2–3, needs Agent 1)    │         │ (Days 3–4, needs others)     │
  ├──────────────────────────────┤         ├──────────────────────────────┤
  │ Blockers: Wait for Agent 1   │         │ Blockers: Wait for Agents 1–3│
  │ Output: Requires OpLib       │         │ Output: All tests pass       │
  │                              │         │                              │
  │ 1. TransitionEdge record     │         │ 1. OperatorLibraryTest       │
  │ 2. TransitionGraph class     │         │ 2. TransitionGraphTest       │
  │ 3. CyclePath record          │         │ 3. CycleDetectorTest         │
  │ 4. Tarjan's SCC (internal)   │         │ 4. RoundTripTest             │
  │ 5. Johnson's algorithm       │         │ 5. RandomBaselineSuite       │
  │ 6. CycleDetector class       │         │ 6. CompressionBenchmark      │
  │ 7. GraphProfiler utility     │         │                              │
  │ 8. TransitionGraphTest       │         │ Estimated time: 8h           │
  │ 9. CycleDetectorTest         │         │                              │
  │ 10. Verify checklist         │         │                              │
  │                              │         │                              │
  │ Estimated time: 10h          │         │                              │
  │ Deliverable: Graph + Cycles  │         │                              │
  └──────────────────────────────┘         └──────────────────────────────┘
           ║                                      ║
           ║ HANDS OFF                            ║
           ╠═══════════════════════════════╗      ║
           ║                               ║      ║
           ▼                               ▼      ▼
  ┌────────────────────────────────────────────────────────────┐
  │ AGENT 3: MRC ENCODER & DECODER (Days 3–4, blocking path) │
  │ Task: Implement full codec pipeline                        │
  ├────────────────────────────────────────────────────────────┤
  │ Blockers: Need Agent 2 (graph) + Agent 4 (tests)          │
  │ Input: TransitionGraph + Cycles + OperatorLibrary         │
  │                                                            │
  │ 1. MrcEncoder state machine (2h)                          │
  │ 2. Bitstream header protocol (1h)                         │
  │ 3. MrcDecoder state machine (2h)                          │
  │ 4. CompressionResult record (0.5h)                        │
  │ 5. MrcFormatException (0.25h)                             │
  │ 6. Round-trip sanity tests (1h)                           │
  │ 7. Verify all codec checklist items (1h)                  │
  │                                                            │
  │ Estimated time: 7.75h                                      │
  │ Deliverable: Encoder + Decoder instances                  │
  │ Output files:                                              │
  │   - src/main/java/mrc/codec/MrcEncoder.java                │
  │   - src/main/java/mrc/codec/MrcDecoder.java                │
  │   - src/main/java/mrc/codec/CompressionResult.java         │
  │   - src/main/java/mrc/codec/MrcFormatException.java        │
  └────────────────────────────────────────────────────────────┘
           ║ HANDS OFF TO: Agent 5
           ║
           ▼
  ┌────────────────────────────────────────────────────────────┐
  │ AGENT 5: INTEGRATION & DOCUMENTATION (Days 4–5)           │
  │ Task: Finalize build system, docs, end-to-end testing     │
  ├────────────────────────────────────────────────────────────┤
  │ Input: All modules from Agents 1–4                        │
  │                                                            │
  │ 1. pom.xml configuration (1h)                             │
  │ 2. README.md with examples (1.5h)                         │
  │ 3. Integration tests (1h)                                 │
  │ 4. Build verification (0.5h)                              │
  │ 5. Final checklist verification (0.5h)                    │
  │                                                            │
  │ Estimated time: 4.5h                                       │
  │ Deliverable: Runnable Maven project                       │
  │ Output files:                                              │
  │   - pom.xml                                                │
  │   - README.md                                              │
  │   - src/test/java/mrc/integration/*.java                   │
  └────────────────────────────────────────────────────────────┘
           ║
           ▼
  SYNC POINT 2: Phase 2 Complete
  └─ All checklists green; ready for Phase 3

PHASE 3: VALIDATION & ITERATION (Days 5–6)
  Parallel Execution:
  
  ┌─ Agent 1: Run OperatorLibrary on spec test cases
  │  └─ findShortest(10, 13) → Add(3) ✓
  │
  ├─ Agent 2: Run cycle detection on synthetic inputs
  │  └─ Arithmetic sequence finds Add(1) cycle ✓
  │
  ├─ Agent 3: Run encoder/decoder on all test streams
  │  └─ Round-trip verify all 6 inputs ✓
  │
  ├─ Agent 4: Run full test suite
  │  └─ mvn test → all 40+ tests pass ✓
  │
  └─ Agent 5: Run build verification
     └─ mvn clean install → JAR created ✓
  
  SYNC POINT 3: Issue Triage (if any)
  └─ Resolve test failures; re-verify

PHASE 4: SIGN-OFF (Day 6)
  All agents report:
  ├─ Checklist: 100% items verified ✓
  ├─ Tests: All green ✓
  ├─ Build: mvn clean test passes ✓
  ├─ Documentation: Complete ✓
  ├─ Compression targets: Met ✓
  └─ Code quality: Production-ready ✓
  
  FINAL OUTPUT
  └─ mrc-phase1/ repository with all files
```

---

## Agent Dependency Matrix

```
        Depends on:
Agent   Core  Graph  Codec  Valid  Integ
─────────────────────────────────────────
1. Core   —     —     —     —     —
2. Graph  ✓     —     —     —     —
3. Codec  ✓     ✓     —     —     —
4. Valid  ✓     ✓     ✓     —     —
5. Integ  ✓     ✓     ✓     ✓     —

Legend:
✓ = Blocked dependency (must wait)
— = Independent or output-only
```

---

## Synchronization Points (Critical Handoffs)

### SYNC 1: Config Review
**When:** End of Day 0 (2 hours)
**Who:** All agents + project lead
**What:** 
- Walkthrough of multiagent-config.md
- Each agent presents their module scope
- Identify any gaps or conflicts

**Acceptance Criteria:**
- [ ] All agents understand their checklist
- [ ] No circular dependencies
- [ ] No ambiguities in contracts
- [ ] Green light to Phase 2

**Output:** Config verified; proceed to implementation

---

### SYNC 2: Phase 2 Completion
**When:** End of Day 4 (1 hour)
**Who:** All agents
**What:**
- Agent 1 presents OperatorLibrary + test results
- Agent 2 presents Graph + cycle detection + test results
- Agent 3 presents Encoder/Decoder + round-trip tests
- Agent 4 presents full test suite results
- Agent 5 presents build system + docs

**Acceptance Criteria:**
- [ ] Agent 1: OperatorLibraryTest all pass
- [ ] Agent 2: TransitionGraphTest, CycleDetectorTest all pass
- [ ] Agent 3: MrcEncoder/Decoder basic round-trip works
- [ ] Agent 4: RoundTripTest parameterized suite passes
- [ ] Agent 5: `mvn clean compile` succeeds

**Output:** All modules complete and integrated; proceed to validation

---

### SYNC 3: Validation Results
**When:** End of Day 5 (1 hour)
**Who:** All agents
**What:**
- Present test results from parallel validation
- Discuss any failures or warnings
- Assign fixes for Phase 4

**Acceptance Criteria:**
- [ ] All 40+ JUnit tests pass
- [ ] RandomBaselineSuite target ratios met
- [ ] `mvn clean test` exits with 0
- [ ] No compiler warnings
- [ ] All checklist items verified

**Output:** Issues logged; fixes assigned; proceed to Phase 4 or iterate

---

### SYNC 4: Final Sign-Off
**When:** End of Day 6 (0.5 hours)
**Who:** All agents + project lead
**What:**
- Final verification that all checklists are green
- Code review spot-checks
- Sign off on deliverable

**Acceptance Criteria:**
- [ ] All checklists: 100% verified
- [ ] `mvn clean install` produces JAR
- [ ] README.md complete with examples
- [ ] Compression targets verified
- [ ] No outstanding issues

**Output:** mrc-phase1 repository ready for Phase 2 planning

---

## Parallel vs. Sequential Work Streams

### Critical Path (Sequential, Days 1–5)
```
Agent 1 (Day 1) → Agent 2 (Days 2–3) ─┐
                                       ├→ Agent 3 (Days 3–4) → Agent 5 (Days 4–5)
Agent 3 BitStream (Day 1–2) ────────┐ │
                                     ├→ Agent 4 (Days 3–4)
```

**Critical path duration:** ~5 days

### Opportunity for Parallelization
- **Days 1–2:** Agent 1 (core) + Agent 3 (bitstream) can run in parallel
- **Days 2–3:** While Agent 2 builds graph, Agent 3 can refine bitstream layer
- **Day 3 onward:** Agents 3 and 4 can iterate independently until Agent 2 handoff

---

## Communication Channels (During Implementation)

| Channel | Purpose | Frequency |
|---------|---------|-----------|
| **Daily Standup** | 15-min sync; blockers, progress | Every weekday 9 AM |
| **Agent Pair Meetings** | Dense technical sync between dependent agents | As needed (2–3/day) |
| **Shared Issue Tracker** | Log bugs, blockers, questions | Continuous |
| **Sync Point Meetings** | Structured handoffs + approval | 4 times (see above) |
| **Slack/Chat** | Asynchronous Q&A, quick fixes | Continuous |

---

## Escalation Protocol

**Scenario 1: Agent A blocks on Agent B's output**
```
1. Agent A opens issue: "Waiting on [specific artifact] from Agent B"
2. Agent B assigned; 2-hour response SLA
3. If blocked on external factor: escalate to project lead → adjust timeline
```

**Scenario 2: Ambiguity in spec or contract**
```
1. Raise in daily standup
2. Agent 5 (Integration) mediates
3. If unresolved: project lead makes decision
4. Document decision in config supplement
```

**Scenario 3: Test failure in dependent agent**
```
1. Raise in issue tracker with stack trace
2. Both agents investigate
3. Fix in upstream agent if root cause found
4. Re-verify downstream tests
5. Document in verification checklist
```

---

## Verification Checklist Status Board

Track real-time progress:

```
Agent 1: Core Algebra
  ✓ Operator sealed interface
  ✓ 12 implementations
  ✓ OperatorLibrary singleton
  ✓ findShortest() cache
  ✓ Unit tests
  ✓ All verifications green

Agent 2: Graph Architecture
  ⧖ TransitionGraph (in progress)
  ⧖ CycleDetector (in progress)
  —  Unit tests (waiting on code)
  —  All verifications (waiting on tests)

Agent 3: Codec Infrastructure
  ✓ BitStreamWriter/Reader
  ✓ EncodingTier
  ⧖ MrcEncoder (waiting on Agent 2)
  ⧖ MrcDecoder (waiting on Agent 2)
  —  Integration tests (waiting on Agents 2, 4)

Agent 4: Validation & Benchmarking
  —  All tests (waiting on Agents 1–3)

Agent 5: Integration & Documentation
  —  All artifacts (waiting on Agents 1–4)

Legend:
✓ = Complete and verified
⧖ = In progress
— = Blocked / waiting
✗ = Failed / blocker
```

---

## Example Handoff Document (Agent 1 → Agents 2, 3, 4, 5)

**From:** Agent 1 (Core Algebra)  
**To:** Agents 2, 3, 4, 5  
**Date:** Day 1, EOD  
**Status:** ✓ Complete and verified

### Deliverable: OperatorLibrary

#### Code Artifact
```
src/main/java/mrc/core/
├── Operator.java (sealed interface)
├── Add.java (record, implements Operator)
├── Sub.java
├── Mul.java
├── Div.java
├── Mod.java
├── XorOp.java
├── AndOp.java
├── OrOp.java
├── ShiftLeft.java
├── ShiftRight.java
├── Not.java
├── Transition.java (record)
├── ValueCluster.java (record)
└── OperatorLibrary.java (singleton)
```

#### Verification Checklist
- [ ] `OperatorLibrary.findShortest(10, 13)` returns `Add(3)` with costBits=13 ✓
- [ ] `OperatorLibrary.findShortest(255, 0)` returns `Add(1)` ✓
- [ ] All 2400 operators registered with unique opId ✓
- [ ] Cache lookup <1 μs after first build ✓
- [ ] Unit tests: 12/12 passing ✓

#### API Contract
```java
public class OperatorLibrary {
  public static OperatorLibrary getInstance();
  public Operator byId(byte opId);
  public List<Operator> all();
  public Optional<Operator> findShortest(int from, int to);
}

public sealed interface Operator permits Add, Sub, Mul, Div, ... {
  int apply(int x);
  byte opId();
  int operandBits();
  String toExpression(String varName);
}
```

#### Known Issues
- None

#### Handoff Notes
- OperatorLibrary is thread-safe; uses lazy initialization
- opId is stable; based on insertion order into .all() list
- Div(0) instance is explicitly never registered
- All .apply() results are masked to 0xFF

#### Ready for Use By:
- [x] Agent 2 (Graph Architecture) — needs OperatorLibrary for Transition lookup
- [x] Agent 3 (Codec Infrastructure) — needs opId/operandBits for bitstream layout
- [x] Agent 4 (Validation) — needs all operators for test coverage
- [x] Agent 5 (Integration) — needs artifact for JAR inclusion

---

## Risk Mitigation Plan

### Risk: Agent 2 (Graph Architecture) takes longer than estimated

**Impact:** Blocks Agents 3, 4, 5; delays Phase 2 completion
**Mitigation:**
- Pre-allocate extra 2 days in timeline
- Agent 2 can present partial work (e.g., TransitionGraph without cycles)
- Agent 3 can implement MrcEncoder/Decoder stub in parallel
- **Action:** Daily sync with Agent 2; escalate if >2 days behind

### Risk: Cycle detection (Johnson's algorithm) is too slow

**Impact:** Encoder/decoder throughput insufficient for benchmarks
**Mitigation:**
- Pre-test on 256-node graph with 500 edges
- If >200ms, reduce maxCycleLength from 8 to 6
- Implement parallel Tarjan's SCC as fallback
- **Action:** Benchmark cycle detection immediately after implementation

### Risk: Prefix-free codes have Kraft violation

**Impact:** Bitstream decoder misidentifies tier; round-trip fails
**Mitigation:**
- Manually verify Kraft inequality before code
- Unit test all flag prefix reads on ~1000 random inputs
- **Action:** This is non-negotiable; Agent 3 must test thoroughly

### Risk: Compression targets not met on RandomBaselineSuite

**Impact:** Benchmark results are below spec
**Mitigation:**
- This is a sign of implementation bug, not spec issue
- Re-run with debug traces; check cycle detection output
- If ratios are consistently off by <5%, adjust expectations slightly
- **Action:** Investigate root cause; do not ship if >10% off target

---

## Success Metrics (EOD Phase 2)

| Metric | Target | Pass/Fail |
|--------|--------|-----------|
| Code complete | All classes implemented | [ ] |
| Unit tests pass | 40+ JUnit tests, 0 failures | [ ] |
| Round-trip verify | 6 parameterized inputs pass | [ ] |
| Compression ratios | Hit targets (Table § 4.5) | [ ] |
| Build success | `mvn clean install` → JAR | [ ] |
| Documentation | README + Javadoc complete | [ ] |
| Code quality | Zero compiler warnings | [ ] |
| No blockers | All known issues resolved | [ ] |

---

**End of Agent Coordination Workflow**

This document is a real-time dashboard for project managers and agents to track progress, identify blockers, and coordinate handoffs.
