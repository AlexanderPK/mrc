# MRC Phase 2 — Extended Operators, Evolutionary Edge-Finder, Snapshot Protocol
## Implementation Plan for Claude Code (Java)

---

## Relationship to Phase 1

Phase 2 is a vertical extension of the Phase 1 codebase. It does not replace
Phase 1 modules — it extends and replaces specific classes while keeping the
`codec/`, `graph/`, and `bench/` contracts stable. The Phase 1 `MrcEncoder`
and `MrcDecoder` must still compile and pass all Phase 1 tests after Phase 2
is integrated.

**Phase 2 adds three independent pillars:**

| Pillar | Key new package | Depends on Phase 1 |
|--------|----------------|-------------------|
| Extended operator space | `core/extended/` | `core/Operator.java` |
| Evolutionary edge-finder | `evolution/` | `graph/TransitionGraph.java` |
| Snapshot protocol | `snapshot/` | `graph/`, `codec/`, `evolution/` |

---

## Repository structure (additions to Phase 1)

```
mrc-phase1/   (same root, Phase 2 adds to it)
└── src/
    ├── main/java/mrc/
    │   ├── core/
    │   │   ├── [Phase 1 files unchanged]
    │   │   ├── extended/
    │   │   │   ├── FunctionOperator.java
    │   │   │   ├── SuperfunctionOperator.java
    │   │   │   ├── CompositeOperator.java
    │   │   │   ├── OperatorArity.java
    │   │   │   ├── ExtendedOperatorLibrary.java
    │   │   │   └── OperatorCostModel.java
    │   ├── graph/
    │   │   ├── [Phase 1 files unchanged]
    │   │   └── WeightedCycleRanker.java      ← replaces GraphProfiler ranking
    │   ├── evolution/
    │   │   ├── Chromosome.java
    │   │   ├── ChromosomeFactory.java
    │   │   ├── FitnessEvaluator.java
    │   │   ├── SelectionStrategy.java
    │   │   ├── CrossoverEngine.java
    │   │   ├── MutationEngine.java
    │   │   ├── EvolutionaryEdgeFinder.java
    │   │   ├── GenerationResult.java
    │   │   ├── EvolutionConfig.java
    │   │   └── EvolutionMonitor.java
    │   ├── snapshot/
    │   │   ├── SnapshotSerializer.java
    │   │   ├── SnapshotDeserializer.java
    │   │   ├── SnapshotManifest.java
    │   │   ├── SnapshotScheduler.java
    │   │   ├── SnapshotVersion.java
    │   │   └── SnapshotStore.java
    │   └── bench/
    │       ├── [Phase 1 files unchanged]
    │       ├── EvolutionBenchmark.java
    │       └── SnapshotRoundTripSuite.java
    └── test/java/mrc/
        ├── [Phase 1 tests unchanged]
        ├── core/extended/ExtendedOperatorTest.java
        ├── evolution/EvolutionaryEdgeFinderTest.java
        └── snapshot/SnapshotRoundTripTest.java
```

---

## Pillar 1 — Extended Operator Space

### Design principle

Phase 1 operators are all **arity-1 closed-form maps**: `f: Z_256 -> Z_256`.
Phase 2 widens this to three new operator classes:

```
Level 0 (Phase 1):  Primitive operators     f(x)          single value
Level 1 (Phase 2):  Function operators      f(x, params)  parameterized
Level 2 (Phase 2):  Composite operators     f(g(x))       operator chains
Level 3 (Phase 2):  Superfunction operators F^n(x)        iterated application
```

Each level is backward-compatible: a Level-0 operator is a degenerate Level-1
with zero parameters. The bitstream encoding adds a 2-bit level prefix before
the opId to distinguish levels.

---

### `OperatorArity.java`
```
Enum:
  UNARY_FIXED        // f(x)         — Phase 1 operators
  UNARY_PARAMETERIZED // f(x, p...)  — Phase 2 function operators
  BINARY             // f(x, y)      — uses two previous values as input
  COMPOSITE          // f(g(x))      — chain of two operators
  ITERATED           // F^n(x)       — superfunction

Each has:
  int extraBits()    // additional bits needed beyond opId for this arity class
```

### `FunctionOperator.java`
```
Sealed interface extending Operator with permitted implementations:

  // Polynomial: result = sum(coeffs[i] * x^i) mod 256
  Polynomial(int[] coeffs)
    - coeffs stored as 4-bit signed values [-8..7], max degree 3
    - bitstream: 2-bit degree + (degree+1)*4-bit coefficients
    - costBits = 5 (opId) + 2 + (degree+1)*4

  // Linear congruential step: result = (a*x + c) mod 256
  LinearCongruential(int a, int c)
    - a and c each stored as 8-bit values
    - bitstream: 8 bits a + 8 bits c = 16 bits
    - costBits = 5 + 16 = 21 bits
    - NOTE: only useful if it matches long runs; encoder must check break-even

  // Table lookup: result = table[x mod tableSize]
  // Table is embedded in the snapshot, NOT in the bitstream per transition
  TableLookup(byte[] table)
    - table has exactly 256 entries (one per input value)
    - bitstream: 0 extra bits (table resolved from snapshot)
    - costBits = 5 (just the opId, table is pre-shared)
    - This is the most powerful single operator for arbitrary bijections

  // Bitfield rotation: result = Integer.rotateLeft(x, bits) & 0xFF
  // (different from shift: wraps bits around)
  RotateLeft(int bits)   // bits in [1..7]
  RotateRight(int bits)

  // Reverse bits in the byte
  BitReverse()
    - costBits = 5 (no operand)

  // Nibble swap: swap high and low 4 bits
  NibbleSwap()
    - costBits = 5

  // Delta-of-delta: result = (x - prev) - (prev - prevprev) mod 256
  // Requires 2-step context; encoder must maintain prevprev state
  DeltaOfDelta()
    - costBits = 5
    - encoder must track lastValue AND secondLastValue

  int apply(int x)                   // single-context application
  int apply(int x, int context)      // two-context application (DeltaOfDelta)
  byte opId()                        // unique 6-bit ID in [32..63]
  int operandBits()
  OperatorArity arity()
  String toExpression(String varName)
```

### `SuperfunctionOperator.java`
```
Sealed interface extending Operator with permitted implementations:

  // Iterated application: apply base operator n times
  // F^n(x) where F is any Phase 1 or Phase 2 FunctionOperator
  Iterated(Operator base, int iterations)
    - iterations stored as 4-bit value [1..15]
    - bitstream: 6-bit base opId + 4-bit iteration count = 10 bits
    - costBits = 5 + 10 = 15
    - apply(x): loop base.apply() n times, all mod 256

  // Fixed-point approach: iterate until x_k = x_{k+n} (cycle detected)
  // Encodes the number of steps to reach the fixed-point or periodic orbit
  // Useful when data converges to attractor patterns
  FixedPointReach(Operator base, int maxSteps)
    - maxSteps stored as 4 bits
    - apply(x): iterate base until value repeats or maxSteps reached
    - returns the value at the detected period start
    - costBits = 5 + 6 + 4 = 15

  // Conjugate operator: g^{-1}(f(g(x)))
  // Applies a "change of basis" g, then f, then inverse of g
  // Useful when data lives in a transformed domain
  Conjugate(Operator g, Operator f, Operator gInverse)
    - g, f, gInverse each stored as 5-bit opIds
    - bitstream: 3 * 5 = 15 bits
    - costBits = 5 + 15 = 20
    - apply(x) = gInverse.apply(f.apply(g.apply(x)))
    - NOTE: encoder must verify gInverse is actually the inverse of g on 0..255

  byte opId()       // unique 7-bit ID in [64..127]
  int apply(int x)
  int operandBits()
  OperatorArity arity()
  String toExpression(String varName)
```

### `CompositeOperator.java`
```
Record: CompositeOperator(List<Operator> chain)
  // chain.size() must be in [2..4]
  // apply(x) = chain.get(n-1).apply(... chain.get(0).apply(x) ...)

  byte opId()
    // Composite operators do NOT have a fixed opId
    // They are encoded inline as a sequence of their component opIds
    // Bitstream: 3-bit chain length + chain.size() * (opId + operandBits)
    // The COMPOSITE flag (2-bit level prefix = 11) signals this encoding

  int totalCostBits()
    // 2 (level prefix) + 3 (chain length) + sum(op.operandBits() + opIdBits for each op)

  int apply(int x)

  CompositeOperator optimized()
    // attempts algebraic simplification:
    // Add(a).then(Add(b)) -> Add((a+b) mod 256)
    // ShiftLeft(a).then(ShiftLeft(b)) -> ShiftLeft(a+b) if a+b < 8
    // Not().then(Not()) -> identity (remove both)
    // returns a new CompositeOperator or a single Operator if fully reduced

  String toExpression(String varName)
    // e.g. "rot3(nibbleSwap(x + 12))"
```

### `OperatorCostModel.java`
```
Utility class for computing break-even analysis:

  static int breakEvenRuns(Operator op, int rawBitsPerValue)
    // how many consecutive transitions must use this operator before
    // the per-encoding overhead is amortized
    // Formula: headerBits(op) / (rawBitsPerValue - op.operandBits())
    // For TableLookup: headerBits = cost of embedding table in snapshot

  static boolean isWorthEncoding(Operator op, int observedRunLength, int rawBitsPerValue)
    // returns true if observedRunLength >= breakEvenRuns(op, rawBitsPerValue)

  static Map<Operator, Integer> rankByGain(List<Operator> candidates, int[] dataStream)
    // profiles each operator against the stream
    // returns map of operator -> total bits saved across all transitions
    // used by the evolutionary fitness evaluator
```

### `ExtendedOperatorLibrary.java`
```
Extends Phase 1 OperatorLibrary:
  - Registers all FunctionOperator instances
  - Registers all SuperfunctionOperator instances
  - Provides: Optional<Operator> findBestSingle(int from, int to)
      // searches Phase 1 AND Phase 2 single operators, returns cheapest
  - Provides: Optional<CompositeOperator> findBestComposite(int from, int to, int maxChain)
      // tries all chains up to maxChain length (default 3)
      // returns cheapest composite that maps from -> to
      // search strategy: BFS over operator composition space
  - Provides: List<Operator> findAllMatching(int from, int to)
      // returns every operator (any level) that maps from -> to exactly
      // sorted by costBits ascending

  Important: findBestComposite is called only when findBestSingle fails
  or when the composite saves >= 2 bits over the best single operator.
  This prevents the encoder from using complex composites when simple
  operators suffice.
```

---

## Pillar 2 — Evolutionary Edge-Finder

### Architecture overview

The evolutionary edge-finder is a **genetic algorithm (GA)** that operates
over the space of operator combinations (chromosomes) and evolves a population
toward maximizing compression gain on a streaming data corpus.

It runs as a background thread in an infinite loop. The main codec thread
reads the current best chromosome from a shared atomic reference. The
EvolutionMonitor records generation statistics. The SnapshotScheduler
(Pillar 3) periodically freezes the current best chromosome into a snapshot.

```
EvolutionaryEdgeFinder (background thread)
    |
    |-- population: List<Chromosome>     (size = config.populationSize)
    |-- corpus:     CircularDataBuffer   (rolling window of recent data)
    |-- best:       AtomicReference<Chromosome>
    |
    Generation loop:
      1. Evaluate fitness of all chromosomes against corpus
      2. Select parents (tournament selection)
      3. Crossover pairs -> offspring
      4. Mutate offspring
      5. Replace weakest with offspring
      6. Update best
      7. Notify monitor
      8. If generation % snapshotInterval == 0: notify SnapshotScheduler
      9. Sleep config.generationDelayMs
```

---

### `Chromosome.java`
```
Record: Chromosome(
    List<OperatorRule> rules,    // ordered list of operator assignment rules
    int generation,              // generation in which this was created
    double fitness,              // last computed fitness score (0.0..1.0)
    String id                    // UUID, for tracking lineage
)

  OperatorRule is a nested record:
    OperatorRule(int fromValue, int toValue, Operator assignedOperator)
    // Represents: "when transitioning from fromValue to toValue, use assignedOperator"
    // This overrides the default best-operator lookup in TransitionGraph

  int size()
    // = rules.size()

  Chromosome withFitness(double f)
    // returns new Chromosome with updated fitness

  Chromosome withGeneration(int g)

  boolean dominates(Chromosome other)
    // true if this.fitness > other.fitness

  // Chromosome encoding for serialization (used by snapshot):
  byte[] toBytes()
    // format: 4 bytes size + for each rule: 1 byte from + 1 byte to + 2 bytes opId
  static Chromosome fromBytes(byte[] data, int generation)
```

### `ChromosomeFactory.java`
```
Factory for creating and validating chromosomes:

  static Chromosome random(int ruleCount, ExtendedOperatorLibrary lib, Random rng)
    // creates a chromosome with ruleCount random OperatorRules
    // each rule: random from/to pair, random matching operator from lib
    // only assigns operators that actually map from -> to correctly

  static Chromosome fromGraph(TransitionGraph graph, int topK)
    // seeds a chromosome from the Phase 1 graph's top-K edges
    // used to initialize the first generation from a known-good baseline

  static Chromosome fromSnapshot(SnapshotManifest snapshot)
    // deserializes a chromosome from a snapshot file
    // used to seed a new evolution run from a previous generation

  static boolean isValid(Chromosome c, ExtendedOperatorLibrary lib)
    // validates all rules: for each rule, op.apply(from) == to
    // invalid chromosomes must never enter the population
```

### `FitnessEvaluator.java`
```
Class: FitnessEvaluator(ExtendedOperatorLibrary lib, OperatorCostModel costModel)

  double evaluate(Chromosome chromosome, byte[] corpus)
    // Steps:
    // 1. Build a temporary TransitionGraph from corpus
    // 2. Override graph edges with chromosome's OperatorRules
    // 3. Run MrcEncoder on corpus using the modified graph
    // 4. fitness = 1.0 - (compressedBits / originalBits)
    //    i.e. fitness = spaceSaving (0.0 = no compression, 1.0 = perfect)
    // 5. Penalty: subtract 0.001 * chromosome.size() (parsimony pressure)
    //    This prevents bloat — smaller chromosomes are preferred when equal fitness

  double evaluateBatch(Chromosome chromosome, List<byte[]> corpora)
    // evaluates on multiple corpora, returns weighted average fitness
    // weight proportional to corpus length
    // use for generalization testing

  Map<Chromosome, Double> evaluatePopulation(List<Chromosome> population, byte[] corpus)
    // evaluates all chromosomes in parallel using ForkJoinPool.commonPool()
    // returns map of chromosome -> fitness
    // uses virtual threads (Java 21): Thread.ofVirtual().start(...)
```

### `SelectionStrategy.java`
```
Interface:
  List<Chromosome> select(List<Chromosome> population, int count, Random rng)

Implementations:

  TournamentSelection(int tournamentSize)
    // default tournamentSize = 5
    // for each slot: pick tournamentSize random candidates, return the fittest
    // repeat until count chromosomes selected
    // preserves diversity better than roulette wheel on fitness landscapes
    // with plateaus

  EliteSelection(int eliteCount)
    // always include top-eliteCount chromosomes unchanged in next generation
    // combined with TournamentSelection for the remainder
    // eliteCount default = 2 (preserves best solution across generations)

  RankSelection()
    // selection probability proportional to rank, not raw fitness
    // reduces dominance of a single very-fit chromosome early in evolution
```

### `CrossoverEngine.java`
```
Class: CrossoverEngine(EvolutionConfig config, Random rng)

  Pair<Chromosome, Chromosome> crossover(Chromosome parent1, Chromosome parent2)
    // Applies the crossover strategy from config

  Crossover strategies (enum in EvolutionConfig):

    SINGLE_POINT
      // pick random split index i in [1..min(p1.size, p2.size)-1]
      // child1 = p1.rules[0..i] + p2.rules[i..]
      // child2 = p2.rules[0..i] + p1.rules[i..]

    TWO_POINT
      // pick two split indices i < j
      // child1 = p1[0..i] + p2[i..j] + p1[j..]
      // child2 = p2[0..i] + p1[i..j] + p2[j..]

    UNIFORM
      // for each rule slot: independently pick from parent1 or parent2
      // with probability 0.5 each
      // produces more diverse offspring, better for large rule sets

    OPERATOR_AWARE
      // group rules by (fromValue, toValue) pair
      // for each pair that exists in both parents: keep the one
      //   with lower costBits (greedily prefers cheaper operators)
      // for pairs in only one parent: include with probability 0.7
      // Most domain-specific strategy; recommended default

  Post-crossover: validate offspring with ChromosomeFactory.isValid()
  If invalid: replace invalid rules with random valid ones from lib
```

### `MutationEngine.java`
```
Class: MutationEngine(EvolutionConfig config, ExtendedOperatorLibrary lib, Random rng)

  Chromosome mutate(Chromosome chromosome)
    // applies each mutation type independently with its configured probability

  Mutation types (probabilities in EvolutionConfig, defaults shown):

    RULE_REPLACE (prob = 0.05 per rule)
      // replace the operator in a random rule with another operator
      // that also maps from -> to (must preserve correctness)
      // selects uniformly from lib.findAllMatching(from, to)

    RULE_ADD (prob = 0.02 per chromosome)
      // add a new random OperatorRule for a (from, to) pair not yet covered
      // select pair from transitions observed in corpus but not in chromosome

    RULE_REMOVE (prob = 0.01 per rule)
      // remove a rule entirely (fallback to default graph lookup)
      // only remove rules with below-average fitness contribution

    OPERATOR_UPGRADE (prob = 0.03 per rule)
      // replace a Phase 1 operator with a Phase 2 operator for same from->to
      // only if Phase 2 operator has lower costBits

    COMPOSITE_SPLIT (prob = 0.02 per composite rule)
      // split a CompositeOperator rule into two sequential single-operator rules
      // tests whether splitting improves coverage of intermediate values

    COMPOSITE_MERGE (prob = 0.02 per adjacent rule pair)
      // attempt to merge two adjacent single-operator rules into a Composite
      // only if CompositeOperator.optimized() produces a cheaper encoding

    SUPERFUNCTION_INJECT (prob = 0.01 per chromosome)
      // scan chromosome for repeated identical rules (same operator applied
      // in sequence) and replace with Iterated(base, n)
      // purely compressive mutation — never increases costBits
```

### `EvolutionConfig.java`
```
Record: EvolutionConfig(
    int populationSize,          // default 100
    int maxGenerations,          // 0 = infinite loop
    int corpusWindowSize,        // bytes of recent data to use as training corpus
                                 // default 65536 (64 KB rolling window)
    int eliteCount,              // default 2
    int tournamentSize,          // default 5
    CrossoverStrategy crossoverStrategy,  // default OPERATOR_AWARE
    double ruleMutationProb,     // default 0.05
    double chromosomeMutationProb, // default 0.02
    int snapshotIntervalGenerations, // default 100
    long generationDelayMs,      // default 0 (run as fast as possible)
    int maxChromosomeRules,      // default 1024 (cap for parsimony)
    boolean parallelFitness,     // default true (use virtual threads)
    Path snapshotOutputDir       // where to write snapshot files
)

  static EvolutionConfig defaultConfig(Path snapshotDir)
  static EvolutionConfig fastConfig(Path snapshotDir)    // small pop, quick test
  static EvolutionConfig thoroughConfig(Path snapshotDir) // large pop, long run
```

### `EvolutionaryEdgeFinder.java`
```
Class implementing Runnable (designed for Thread.ofVirtual() or Thread.ofPlatform())

  Constructor: EvolutionaryEdgeFinder(
      EvolutionConfig config,
      ExtendedOperatorLibrary lib,
      TransitionGraph seedGraph,
      SnapshotScheduler snapshotScheduler,
      EvolutionMonitor monitor
  )

  Fields:
    private volatile boolean running = true
    private final AtomicReference<Chromosome> bestChromosome
    private final List<Chromosome> population          // synchronized access
    private final CircularDataBuffer corpus            // thread-safe ring buffer
    private long generationCount = 0

  void run()
    // Infinite evolution loop:
    while (running) {
      1.  Map<Chromosome, Double> scores =
              fitnessEvaluator.evaluatePopulation(population, corpus.snapshot())
      2.  population.forEach(c -> c = c.withFitness(scores.get(c)))
      3.  population.sort(Comparator.comparingDouble(Chromosome::fitness).reversed())
      4.  bestChromosome.set(population.get(0))
      5.  List<Chromosome> elite = selectionStrategy.select(population, config.eliteCount, rng)
      6.  List<Chromosome> parents = selectionStrategy.select(population,
              config.populationSize - config.eliteCount, rng)
      7.  List<Chromosome> offspring = new ArrayList<>(elite)
          for (int i = 0; i < parents.size(); i += 2) {
              var pair = crossoverEngine.crossover(parents.get(i), parents.get(i+1))
              offspring.add(mutationEngine.mutate(pair.first()))
              offspring.add(mutationEngine.mutate(pair.second()))
          }
      8.  population = offspring.subList(0, config.populationSize)
      9.  generationCount++
      10. monitor.record(generationCount, population.get(0), scores)
      11. if (generationCount % config.snapshotIntervalGenerations == 0)
              snapshotScheduler.requestSnapshot(generationCount, bestChromosome.get())
      12. Thread.sleep(config.generationDelayMs)  // yield if configured
    }

  void feedData(byte[] newData)
    // adds newData to the rolling corpus buffer (thread-safe)
    // called by the main codec thread as new data is processed

  Chromosome getCurrentBest()
    // returns bestChromosome.get() — safe to call from any thread

  void stop()
    // sets running = false, waits for current generation to complete

  GenerationResult getLastGenerationResult()
```

### `GenerationResult.java`
```
Record: GenerationResult(
    long generation,
    Chromosome best,
    double bestFitness,
    double averageFitness,
    double fitnessVariance,
    int populationSize,
    int uniqueOperatorsUsed,
    long corpusBytesProcessed,
    Instant timestamp
)
  void printSummary(PrintStream out)
  // format: "Gen 1042 | best=0.647 | avg=0.521 | var=0.031 | ops=87 | corpus=65536B"
```

### `EvolutionMonitor.java`
```
Class for tracking evolution progress over time:

  void record(long generation, Chromosome best, Map<Chromosome, Double> scores)

  // Query methods:
  List<GenerationResult> history()
  GenerationResult latest()
  double convergenceRate()
    // slope of bestFitness over last 100 generations
    // if < 0.0001 for 500 generations: evolution has stalled
    // EvolutionaryEdgeFinder should inject fresh random chromosomes
    // when this is detected (diversity injection)

  boolean hasConverged()
    // true if convergenceRate() < threshold for 500 generations

  void printHistogram(PrintStream out)
    // prints ASCII histogram of fitness distribution in current population
    // e.g.:
    //  0.3-0.4 |##
    //  0.4-0.5 |########
    //  0.5-0.6 |################
    //  0.6-0.7 |#######
    //  0.7-0.8 |##
```

---

## Pillar 3 — Snapshot Protocol

### Design goals

A snapshot is a **self-contained, versioned, binary file** that captures the
complete state of the MRC system at a given generation. The decoder requires
only the snapshot file and the compressed bitstream — no other shared state.

Snapshots serve three roles:
1. **Codec distribution**: ship a snapshot with compressed data so the decoder
   can reconstruct the operator table without running the evolution loop.
2. **Evolution checkpointing**: resume an evolution run from a prior generation
   without restarting from scratch.
3. **Domain specialization**: a "Financial snapshot" vs. a "Sensor snapshot"
   encodes domain-specific operator tables, distributable independently.

---

### Snapshot binary format

```
Offset   Size     Field
------   ------   -----
0        4        Magic: 0x4D 0x52 0x43 0x32  ("MRC2")
4        2        Format version: 0x0002
6        1        Flags byte:
                    bit 0 = has chromosome (1 = yes)
                    bit 1 = has cycle table (1 = yes)
                    bit 2 = has operator extensions (1 = yes)
                    bit 3 = has domain tag (1 = yes)
                    bits 4-7 = reserved, must be 0
7        8        Generation number (long, big-endian)
15       8        Timestamp (Unix epoch milliseconds, long)
23       4        CRC32 of entire file excluding this field and magic (int)
27       2        Domain tag length D (0 if no domain tag)
29       D        Domain tag string (UTF-8, no null terminator)
29+D     ...      Section table (variable, described below)

Section table format:
  2 bytes: number of sections N
  For each section:
    1 byte:  section type ID
    4 bytes: section offset from start of file (int)
    4 bytes: section length in bytes (int)

Section type IDs:
  0x01  OPERATOR_TABLE     — registered extended operators + TableLookup data
  0x02  TRANSITION_GRAPH   — serialized TransitionGraph (edges + weights)
  0x03  CYCLE_TABLE        — top-K CyclePaths with their node/edge sequences
  0x04  CHROMOSOME         — best Chromosome from evolution
  0x05  EVOLUTION_STATS    — GenerationResult history (last 1000 generations)
  0x06  VALIDATION_HASH    — SHA-256 of training corpus used to build this snapshot
```

### `SnapshotVersion.java`
```
Record: SnapshotVersion(int major, int minor)
  static final SnapshotVersion CURRENT = new SnapshotVersion(2, 0)
  boolean isCompatible(SnapshotVersion other)
    // compatible if major versions match
    // minor version differences are backward-compatible
  static SnapshotVersion fromBytes(byte[] header, int offset)
```

### `SnapshotManifest.java`
```
Record: SnapshotManifest(
    SnapshotVersion version,
    long generation,
    Instant timestamp,
    String domainTag,              // e.g. "financial", "sensor-iot", null
    boolean hasChromosome,
    boolean hasCycleTable,
    boolean hasOperatorExtensions,
    int crc32,
    Map<Byte, SnapshotSection> sections
)

SnapshotSection is a nested record:
  SnapshotSection(byte typeId, int offset, int length)

  static SnapshotManifest read(Path snapshotFile) throws MrcSnapshotException
    // reads and validates header + section table
    // does NOT load section data into memory
    // throws MrcSnapshotException if magic bytes wrong, CRC mismatch, or
    // version incompatible

  void printSummary(PrintStream out)
    // prints human-readable summary of snapshot contents
```

### `SnapshotSerializer.java`
```
Class: SnapshotSerializer(ExtendedOperatorLibrary lib)

  void serialize(
      Path outputPath,
      long generation,
      TransitionGraph graph,
      List<CyclePath> cycles,
      Chromosome bestChromosome,   // may be null if evolution not running
      List<GenerationResult> evolutionHistory,
      String domainTag             // may be null
  ) throws IOException

  Implementation:
    1. Build all sections as byte arrays in memory
    2. Compute section offsets (header + section-table size + sum of prior sections)
    3. Write header with placeholder CRC32 = 0
    4. Write all sections
    5. Compute CRC32 over bytes [29..fileLength-1] (skipping magic + CRC field)
    6. Seek back and overwrite CRC32 field
    7. Sync to disk (FileChannel.force(true))

  Section serialization details:

    OPERATOR_TABLE section:
      2 bytes: count of extended operators
      For each operator:
        1 byte: opId
        1 byte: arity class (OperatorArity ordinal)
        2 bytes: operand data length
        N bytes: operand data (format specific to operator type)
      Special for TableLookup:
        256 bytes: the full lookup table

    TRANSITION_GRAPH section:
      4 bytes: edge count
      For each edge:
        1 byte: fromNode
        1 byte: toNode
        1 byte: opId
        8 bytes: frequency (long)
        8 bytes: weight (double)

    CYCLE_TABLE section:
      2 bytes: cycle count
      For each cycle:
        1 byte: cycle length L
        L bytes: node values
        L bytes: opIds for each edge
        8 bytes: totalWeight (double)
        8 bytes: compressionGain (double)

    CHROMOSOME section:
      Chromosome.toBytes() result, prefixed with 4-byte length

    EVOLUTION_STATS section:
      4 bytes: count of GenerationResult records
      For each record: fixed 64-byte binary encoding
        8 bytes generation, 8 bytes timestamp, 8 bytes bestFitness,
        8 bytes avgFitness, 8 bytes variance, 4 bytes uniqueOps,
        8 bytes corpusBytes, 12 bytes reserved
```

### `SnapshotDeserializer.java`
```
Class: SnapshotDeserializer(ExtendedOperatorLibrary lib)

  SnapshotManifest readManifest(Path snapshotFile) throws MrcSnapshotException

  TransitionGraph loadGraph(Path snapshotFile, SnapshotManifest manifest)
  List<CyclePath> loadCycles(Path snapshotFile, SnapshotManifest manifest)
  Chromosome loadChromosome(Path snapshotFile, SnapshotManifest manifest)
  List<GenerationResult> loadEvolutionHistory(Path snapshotFile, SnapshotManifest manifest)

  // Convenience: load everything in one call
  record FullSnapshot(
      SnapshotManifest manifest,
      TransitionGraph graph,
      List<CyclePath> cycles,
      Chromosome chromosome,        // null if not present
      List<GenerationResult> history // empty if not present
  ) {}

  FullSnapshot loadAll(Path snapshotFile) throws MrcSnapshotException, IOException

  // Validation:
  boolean validateCrc(Path snapshotFile)
  boolean validateOperatorConsistency(FullSnapshot snapshot)
    // re-applies each chromosome rule and verifies op.apply(from) == to
    // catches snapshot corruption silently
```

### `SnapshotScheduler.java`
```
Class: SnapshotScheduler(EvolutionConfig config, SnapshotSerializer serializer)

  // Called by EvolutionaryEdgeFinder on the evolution thread:
  void requestSnapshot(long generation, Chromosome best)
    // queues a snapshot request (non-blocking)
    // actual write happens on the scheduler's own thread

  // Internal write thread loop:
  private void processQueue()
    // drains the request queue
    // for each request:
    //   1. Get current graph + cycles from TransitionGraph reference
    //   2. Build filename: "mrc2_gen{generation}_{timestamp}.snap"
    //   3. Write to config.snapshotOutputDir using SnapshotSerializer
    //   4. Write a symlink (or copy) "mrc2_latest.snap" pointing to new file
    //   5. Prune old snapshots: keep only last config.maxSnapshotsToKeep files
    //      (default 10), delete older ones

  void forceSnapshot(long generation, Chromosome best)
    // synchronous snapshot, blocks until written
    // used for clean shutdown

  Path latestSnapshotPath()
    // returns path of the most recent successfully written snapshot
    // null if none written yet

  int pendingCount()
    // number of snapshot requests queued but not yet written
```

### `SnapshotStore.java`
```
Class: SnapshotStore(Path storeDir)
  // Manages a directory of snapshot files

  List<Path> listSnapshots()
    // returns all .snap files sorted by generation number ascending

  Optional<Path> latest()
    // returns the snapshot with the highest generation number

  Optional<Path> latestForDomain(String domainTag)
    // filters by domain tag

  void prune(int keepCount)
    // deletes all but the keepCount most recent snapshots

  SnapshotManifest inspect(Path snapshotFile)
    // reads manifest only (fast, no section data loaded)

  void exportSummary(PrintStream out)
    // prints a table of all snapshots:
    // Gen | Timestamp | Domain | BestFitness | Edges | Cycles | Size
```

---

## Updated codec — Phase 2 MrcEncoder

Phase 2 adds a constructor overload to `MrcEncoder` that accepts a snapshot:

```java
MrcEncoder(FullSnapshot snapshot)
  // uses snapshot.graph() as the base transition graph
  // uses snapshot.cycles() as the pre-built cycle table
  // if snapshot.chromosome() != null:
  //   overrides graph edges with chromosome's OperatorRules before encoding

MrcEncoder(TransitionGraph graph, List<CyclePath> cycles, Chromosome chromosome)
  // same as above but with explicit parameters
```

The bitstream header (Phase 2 version) adds:
```
- 4 bytes: snapshot generation number (0 if no snapshot used)
- 16 bytes: snapshot validation hash (SHA-256 first 16 bytes, 0s if no snapshot)
```

The decoder uses the validation hash to verify it is using the correct snapshot
before decoding. If the hash does not match, `MrcDecoder` throws
`MrcSnapshotMismatchException` with a clear message.

---

## Test specifications

### `ExtendedOperatorTest.java`
```
  @Test void polynomial_appliesCorrectly()
    - Polynomial([1, 2, 3]) on x=5: 1 + 2*5 + 3*25 = 86
    - verify: result == 86

  @Test void iterated_appliesNTimes()
    - Iterated(Add(3), 4).apply(10) == (10+3+3+3+3) mod 256 == 22

  @Test void conjugate_appliesChain()
    - g = Add(10), f = ShiftLeft(1), gInv = Sub(10)
    - Conjugate(g, f, gInv).apply(5) == Sub(10).apply(ShiftLeft(1).apply(Add(10).apply(5)))
    - verify against manual calculation

  @Test void composite_optimizes_addAdd()
    - CompositeOperator([Add(3), Add(5)]).optimized() == Add(8)

  @Test void composite_optimizes_notNot()
    - CompositeOperator([Not(), Not()]).optimized() is identity (empty chain)

  @Test void tableLookup_roundTrip()
    - build random permutation table
    - TableLookup(table).apply(x) == table[x] for all x in 0..255

  @Test void extendedLibrary_findsChaper()
    - for some (from, to) pair: Phase 2 operator costs fewer bits than Phase 1
    - assert: findBestSingle returns a Phase 2 operator for that pair
```

### `EvolutionaryEdgeFinderTest.java`
```
  @Test void evolution_improvesOverGenerations()
    - create EvolutionaryEdgeFinder with fastConfig
    - feed 10KB arithmetic sequence corpus
    - run for 50 generations
    - assert: fitness at gen 50 > fitness at gen 1

  @Test void evolution_doesNotCrash_randomCorpus()
    - feed 64KB uniform random corpus
    - run for 20 generations
    - assert: completes without exception
    - assert: best chromosome is valid (ChromosomeFactory.isValid)

  @Test void chromosome_crossover_preservesValidity()
    - create two random chromosomes
    - crossover with OPERATOR_AWARE strategy
    - assert: both offspring are valid

  @Test void mutation_preservesValidity()
    - create a chromosome from graph
    - apply all mutation types 1000 times each
    - assert: all results are valid

  @Test void convergenceDetection_triggersOnFlatFitness()
    - mock fitness evaluator returning constant fitness for 500 generations
    - assert: monitor.hasConverged() returns true
```

### `SnapshotRoundTripTest.java`
```
  @Test void snapshot_writeRead_manifestMatches()
    - build a graph from test data
    - serialize to temp file
    - deserialize manifest
    - assert: generation, timestamp, section offsets are identical

  @Test void snapshot_crcValidation_detectsCorruption()
    - write snapshot
    - flip one byte in the middle of the file
    - assert: validateCrc returns false

  @Test void snapshot_fullRoundTrip_graphPreserved()
    - build graph from 10KB arithmetic sequence
    - serialize including chromosome
    - deserialize
    - assert: graph.edgeCount() matches
    - assert: all cycle nodes preserved
    - assert: chromosome rules preserved

  @Test void snapshot_decoder_rejectsMismatchedHash()
    - encode with snapshot A
    - attempt to decode with snapshot B (different generation)
    - assert: MrcSnapshotMismatchException thrown

  @Test void snapshotStore_prune_keepsCorrectCount()
    - write 15 snapshots to temp dir
    - prune(10)
    - assert: store.listSnapshots().size() == 10
    - assert: remaining snapshots are the 10 most recent by generation
```

---

## pom.xml additions (Phase 2)

No new runtime dependencies required.

Add to `<build><plugins>`:
```xml
<!-- Enable preview features for virtual threads in Java 21 -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>--enable-preview</argLine>
  </configuration>
</plugin>
```

---

## Implementation constraints and invariants (Phase 2)

### Operator correctness invariant
Every operator in every chromosome must satisfy:
```java
assert op.apply(rule.fromValue()) == rule.toValue()
    : "Operator " + op + " does not map " + rule.fromValue() + " -> " + rule.toValue();
```
This must be checked at: chromosome creation, after crossover, after mutation,
and during snapshot deserialization. Any chromosome violating this invariant
must never enter the population and must never be written to a snapshot.

### Snapshot atomicity
Snapshot writes must be atomic from the reader's perspective:
1. Write to a temp file in the same directory: `mrc2_gen{N}.snap.tmp`
2. Rename to final name: `mrc2_gen{N}.snap`  (atomic on POSIX systems)
3. Update the "latest" symlink last
Never write directly to the final filename — a crash mid-write would corrupt
the snapshot.

### Evolution thread isolation
The evolution thread must NEVER directly modify the TransitionGraph used by
the encoder thread. It works on a COPY of the graph for fitness evaluation.
The bestChromosome AtomicReference is the only shared state between threads.

### Corpus window management
`CircularDataBuffer` must be implemented as a fixed-size byte ring buffer with:
- `void write(byte[] data)` — overwrites oldest data when full
- `byte[] snapshot()` — returns a consistent copy for fitness evaluation
- Thread safety: one writer (codec thread), one reader (evolution thread)
- No blocking — if the evolution thread is slow, the codec thread never waits

### Cost model enforcement
A CompositeOperator or SuperfunctionOperator must only be used in a chromosome
rule if its `totalCostBits()` is strictly less than the best Phase 1 operator
for the same (from, to) pair. This prevents the extended operator space from
introducing bloat chromosomes.

---

## Expected outcomes (Phase 2 targets)

| Scenario | Phase 1 ratio | Phase 2 target | Driver |
|----------|--------------|----------------|--------|
| Arithmetic sequence delta=1 | 0.12 | 0.08 | Iterated(Add(1), N) replaces N edges |
| Sine wave (quantized) | 0.38 | 0.22 | TableLookup captures full sine mapping |
| LCG pseudo-random | 0.78 | 0.55 | LinearCongruential operator matches LCG step |
| Mixed structured data | 0.65 | 0.45 | Evolution discovers domain-specific operators |
| Uniform random | 1.00 | 1.00 | Incompressible — no regression allowed |

---

## What Phase 3 will add (out of scope here, for reference)

- 16-bit value support with sparse Top-K transition map
- Fuzzy tolerance layer (ε-matching) for lossy mode
- N-dimensional vector field operators (MRC-Matrix track)
- Domain-specific snapshot distribution protocol (publish/subscribe)
- Parallel corpus training across multiple data streams

## A few architectural decisions in the plan that are worth explaining explicitly.
### Why four operator levels rather than a flat extended list. 
Levels 0–3 form a strict cost hierarchy: each level is only used when the level below it cannot do the job cheaply enough. 
This prevents the extended operator space from becoming a search explosion — the encoder always tries the cheapest level first, 
and the OperatorCostModel.isWorthEncoding() guard prevents a complex superfunction from being chosen when a simple Add would serve. 
Without this discipline, the chromosome search space grows factorially and the evolutionary algorithm stalls.
### Why OPERATOR_AWARE crossover is the recommended default. 
Standard single-point crossover treats chromosome rules as positionally ordered, 
but MRC rules are actually keyed by (fromValue, toValue) pairs — position in the list is irrelevant. 
Operator-aware crossover respects this structure: it merges two chromosomes by pairing rules that cover the same transitions and keeping the cheaper operator. 
This produces offspring that are semantically coherent rather than randomly spliced, and dramatically accelerates convergence in early generations.
### Why snapshots use atomic rename rather than direct write.
The decoder needs a guarantee that any snapshot file it opens is either fully written or absent — a half-written snapshot with a valid magic header but truncated section data would cause silent decompression corruption, which is the worst possible failure mode for a codec. The write-to-tmp then rename pattern is atomic on all POSIX filesystems and gives this guarantee at zero cost.
### The CircularDataBuffer is the key concurrency design. 
The evolution thread and the codec thread share only two things: the AtomicReference<Chromosome> for the best solution, and the rolling corpus buffer. Making the buffer non-blocking (writer never waits for reader) ensures the codec thread — which is on the latency-critical path — is never blocked by the evolution thread running a slow fitness evaluation.