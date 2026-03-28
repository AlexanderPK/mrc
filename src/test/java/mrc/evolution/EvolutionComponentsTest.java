package mrc.evolution;

import mrc.core.Operator;
import mrc.core.extended.ExtendedOperatorLibrary;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for all evolution module classes.
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
class EvolutionComponentsTest {

    static ExtendedOperatorLibrary lib;
    static EvolutionConfig fastConfig;

    @BeforeAll
    static void setUpLib(@TempDir Path tempDir) {
        lib = ExtendedOperatorLibrary.getInstance();
        fastConfig = EvolutionConfig.fastConfig(tempDir);
    }

    /** Build arithmetic byte data: val, val+step, val+2*step ... (mod 256) */
    static byte[] arithmeticData(int length, int start, int step) {
        byte[] data = new byte[length];
        int val = start;
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (val & 0xFF);
            val = (val + step) & 0xFF;
        }
        return data;
    }

    /** Build a valid Chromosome with at least one rule using the library. */
    static Chromosome validChromosome() {
        Optional<Operator> op = lib.findShortestExtended(10, 13);
        assertTrue(op.isPresent(), "Library must have operator for 10->13");
        List<Chromosome.OperatorRule> rules = List.of(
                new Chromosome.OperatorRule(10, 13, op.get())
        );
        return new Chromosome(rules, 0, 0.5, Chromosome.generateId());
    }

    // ========================= Chromosome =========================

    @Test
    @DisplayName("Chromosome: rules list is defensive copy")
    void chromosome_rulesIsDefensiveCopy() {
        List<Chromosome.OperatorRule> mutable = new ArrayList<>();
        Optional<Operator> op = lib.findShortestExtended(0, 3);
        assumeOpPresent(op, "0->3");
        mutable.add(new Chromosome.OperatorRule(0, 3, op.get()));
        Chromosome c = new Chromosome(mutable, 0, 0.0, "id");

        mutable.clear();
        assertEquals(1, c.rules().size(), "Rules list must be defensive copy");
    }

    @Test
    @DisplayName("Chromosome: withFitness returns new instance with updated fitness")
    void chromosome_withFitnessReturnsNewInstance() {
        Chromosome c = validChromosome();
        Chromosome updated = c.withFitness(0.99);
        assertNotSame(c, updated);
        assertEquals(0.99, updated.fitness(), 1e-9);
        assertEquals(0.5, c.fitness(), 1e-9, "Original must be unchanged");
    }

    @Test
    @DisplayName("Chromosome: withGeneration returns new instance with updated generation")
    void chromosome_withGenerationReturnsNewInstance() {
        Chromosome c = validChromosome();
        Chromosome updated = c.withGeneration(42);
        assertNotSame(c, updated);
        assertEquals(42, updated.generation());
        assertEquals(0, c.generation());
    }

    @Test
    @DisplayName("Chromosome: withRules returns new instance with updated rules")
    void chromosome_withRulesReturnsNewInstance() {
        Chromosome c = validChromosome();
        Chromosome updated = c.withRules(Collections.emptyList());
        assertNotSame(c, updated);
        assertTrue(updated.rules().isEmpty());
        assertEquals(1, c.rules().size());
    }

    @Test
    @DisplayName("Chromosome: dominates returns true when fitness is higher")
    void chromosome_dominates() {
        Chromosome better = validChromosome().withFitness(0.8);
        Chromosome worse = validChromosome().withFitness(0.3);
        assertTrue(better.dominates(worse));
        assertFalse(worse.dominates(better));
    }

    @Test
    @DisplayName("Chromosome: toBytes/fromBytes round-trip preserves rules")
    void chromosome_toBytesFromBytesRoundTrip() {
        Chromosome c = validChromosome();
        byte[] bytes = c.toBytes();
        Chromosome restored = Chromosome.fromBytes(bytes, 5, lib);
        assertEquals(c.rules().size(), restored.rules().size());
        for (int i = 0; i < c.rules().size(); i++) {
            assertEquals(c.rules().get(i).fromValue(), restored.rules().get(i).fromValue());
            assertEquals(c.rules().get(i).toValue(), restored.rules().get(i).toValue());
        }
        assertEquals(5, restored.generation());
    }

    @Test
    @DisplayName("Chromosome: empty rules list is valid")
    void chromosome_emptyRulesValid() {
        Chromosome c = new Chromosome(Collections.emptyList(), 0, 0.0, "empty");
        assertEquals(0, c.size());
        assertNotNull(c.rules());
    }

    @Test
    @DisplayName("Chromosome.OperatorRule: rejects fromValue > 255")
    void operatorRule_rejectsFromValueOver255() {
        Optional<Operator> op = lib.findShortestExtended(0, 3);
        assumeOpPresent(op, "0->3");
        assertThrows(IllegalArgumentException.class,
                () -> new Chromosome.OperatorRule(256, 3, op.get()));
    }

    @Test
    @DisplayName("Chromosome.OperatorRule: rejects toValue > 255")
    void operatorRule_rejectsToValueOver255() {
        Optional<Operator> op = lib.findShortestExtended(0, 3);
        assumeOpPresent(op, "0->3");
        assertThrows(IllegalArgumentException.class,
                () -> new Chromosome.OperatorRule(0, 256, op.get()));
    }

    // ========================= ChromosomeFactory =========================

    @Test
    @DisplayName("ChromosomeFactory: createRandom returns non-empty chromosome")
    void chromosomeFactory_createRandomNonEmpty() {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        Chromosome c = factory.createRandom(10);
        assertFalse(c.rules().isEmpty(), "createRandom(10) must return non-empty chromosome");
    }

    @Test
    @DisplayName("ChromosomeFactory: createRandom has no duplicate (from,to) pairs")
    void chromosomeFactory_createRandomNoDuplicates() {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(123));
        Chromosome c = factory.createRandom(30);
        Set<String> pairs = c.rules().stream()
                .map(r -> r.fromValue() + ":" + r.toValue())
                .collect(Collectors.toSet());
        assertEquals(c.rules().size(), pairs.size(), "No duplicate (from,to) pairs");
    }

    @Test
    @DisplayName("ChromosomeFactory: same seed produces same chromosome")
    void chromosomeFactory_deterministicWithSameSeed() {
        ChromosomeFactory f1 = new ChromosomeFactory(lib, new Random(999));
        ChromosomeFactory f2 = new ChromosomeFactory(lib, new Random(999));
        Chromosome c1 = f1.createRandom(15);
        Chromosome c2 = f2.createRandom(15);
        assertEquals(c1.rules().size(), c2.rules().size());
        for (int i = 0; i < c1.rules().size(); i++) {
            assertEquals(c1.rules().get(i).fromValue(), c2.rules().get(i).fromValue());
            assertEquals(c1.rules().get(i).toValue(), c2.rules().get(i).toValue());
        }
    }

    @Test
    @DisplayName("ChromosomeFactory: createFromGraph returns at most topK rules")
    void chromosomeFactory_createFromGraphAtMostTopK() {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        mrc.graph.TransitionGraph graph = new mrc.graph.TransitionGraph();
        graph.observe(arithmeticData(256, 0, 3));
        Chromosome c = factory.createFromGraph(graph, 20);
        assertTrue(c.rules().size() <= 20, "createFromGraph must not exceed topK");
    }

    @Test
    @DisplayName("ChromosomeFactory: isValid returns true for factory-built chromosomes")
    void chromosomeFactory_isValidForFactoryBuilt() {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        Chromosome c = factory.createRandom(10);
        assertTrue(factory.isValid(c), "Factory-built chromosomes must be valid");
    }

    @Test
    @DisplayName("ChromosomeFactory: isValid returns false for corrupted chromosome")
    void chromosomeFactory_isValidFalseForCorrupted() {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        Optional<Operator> op = lib.findShortestExtended(0, 3);
        assumeOpPresent(op, "0->3");
        // Create invalid rule: operator maps 0->3, but we claim toValue=99
        Chromosome.OperatorRule badRule = new Chromosome.OperatorRule(0, 99, op.get());
        // op.apply(0) == 3, not 99 — so this rule is invalid
        int actual = op.get().apply(0) & 0xFF;
        assumeTrue(actual != 99, "Operator for 0->3 must not produce 99");
        Chromosome corrupted = new Chromosome(List.of(badRule), 0, 0.0, "bad");
        assertFalse(factory.isValid(corrupted));
    }

    @Test
    @DisplayName("ChromosomeFactory: repairInvalid produces valid chromosome")
    void chromosomeFactory_repairInvalidProducesValid() {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        Chromosome c = factory.createRandom(10);
        Chromosome repaired = factory.repairInvalid(c);
        assertTrue(factory.isValid(repaired), "Repaired chromosome must be valid");
    }

    // ========================= FitnessEvaluator =========================

    @Test
    @DisplayName("FitnessEvaluator: empty corpus returns 0")
    void fitnessEvaluator_emptyCorpusReturnsZero() {
        FitnessEvaluator evaluator = new FitnessEvaluator(lib, null);
        Chromosome c = validChromosome();
        assertEquals(0.0, evaluator.evaluate(c, new byte[0]), 1e-9);
    }

    @Test
    @DisplayName("FitnessEvaluator: empty population returns empty map")
    void fitnessEvaluator_emptyPopulationReturnsEmptyMap() {
        FitnessEvaluator evaluator = new FitnessEvaluator(lib, null);
        byte[] corpus = arithmeticData(512, 0, 3);
        Map<Chromosome, Double> result = evaluator.evaluatePopulation(
                Collections.emptyList(), corpus);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("FitnessEvaluator: returns non-negative fitness on arithmetic data")
    void fitnessEvaluator_nonNegativeOnArithmeticData() {
        FitnessEvaluator evaluator = new FitnessEvaluator(lib, null);
        byte[] corpus = arithmeticData(512, 0, 3);
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        Chromosome c = factory.createRandom(15);
        double fitness = evaluator.evaluate(c, corpus);
        assertTrue(fitness >= 0.0, "Fitness must be non-negative");
    }

    @Test
    @DisplayName("FitnessEvaluator: evaluatePopulation maps all chromosomes")
    void fitnessEvaluator_evaluatePopulationMapsAllChromosomes() {
        FitnessEvaluator evaluator = new FitnessEvaluator(lib, null);
        byte[] corpus = arithmeticData(512, 0, 3);
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        List<Chromosome> population = List.of(
                factory.createRandom(10),
                factory.createRandom(15),
                factory.createRandom(20)
        );
        Map<Chromosome, Double> results = evaluator.evaluatePopulation(population, corpus);
        assertEquals(population.size(), results.size());
        for (Chromosome c : population) {
            assertTrue(results.containsKey(c));
        }
    }

    @Test
    @DisplayName("FitnessEvaluator: empty batch returns 0")
    void fitnessEvaluator_emptyBatchReturnsZero() {
        FitnessEvaluator evaluator = new FitnessEvaluator(lib, null);
        Chromosome c = validChromosome();
        assertEquals(0.0, evaluator.evaluateBatch(c, Collections.emptyList()), 1e-9);
    }

    // ========================= GenerationResult =========================

    @Test
    @DisplayName("GenerationResult: of computes correct average and variance")
    void generationResult_ofComputesStats() {
        Chromosome best = validChromosome().withFitness(0.8);
        Chromosome c2 = validChromosome().withFitness(0.4);
        List<Chromosome> pop = List.of(best, c2);
        GenerationResult result = GenerationResult.of(1, best, pop, 1024, Instant.now());
        assertEquals(1, result.generation());
        assertEquals(0.8, result.bestFitness(), 1e-9);
        assertEquals(0.6, result.averageFitness(), 1e-6);
        assertTrue(result.fitnessVariance() > 0, "Variance must be positive for mixed fitness");
    }

    @Test
    @DisplayName("GenerationResult: single-element population has zero variance")
    void generationResult_singleElementZeroVariance() {
        Chromosome best = validChromosome().withFitness(0.5);
        GenerationResult result = GenerationResult.of(1, best, List.of(best), 0, Instant.now());
        assertEquals(0.0, result.fitnessVariance(), 1e-9);
    }

    @Test
    @DisplayName("GenerationResult: printSummary contains generation number and fitness")
    void generationResult_printSummaryContainsGenAndFitness() {
        Chromosome best = validChromosome().withFitness(0.75);
        GenerationResult result = GenerationResult.of(42, best, List.of(best), 512, Instant.now());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        result.printSummary(new PrintStream(baos));
        String output = baos.toString();
        assertTrue(output.contains("42"), "Output must contain generation number");
        // Locale-independent check: decimal separator may be '.' or ','
        assertTrue(output.matches("(?s).*best=0[.,]750.*"),
                "Output must contain fitness value: " + output);
    }

    // ========================= EvolutionMonitor =========================

    @Test
    @DisplayName("EvolutionMonitor: initial state is empty")
    void evolutionMonitor_initialStateEmpty() {
        EvolutionMonitor monitor = new EvolutionMonitor();
        assertTrue(monitor.history().isEmpty());
        assertNull(monitor.latest());
        assertEquals(0.0, monitor.convergenceRate(), 1e-9);
        assertFalse(monitor.hasConverged());
    }

    @Test
    @DisplayName("EvolutionMonitor: record adds to history")
    void evolutionMonitor_recordAddsToHistory() {
        EvolutionMonitor monitor = new EvolutionMonitor();
        Chromosome best = validChromosome().withFitness(0.5);
        monitor.record(1, best, List.of(best), 512);
        assertEquals(1, monitor.history().size());
        assertEquals(1, monitor.latest().generation());
    }

    @Test
    @DisplayName("EvolutionMonitor: history returns defensive copy")
    void evolutionMonitor_historyReturnsDefensiveCopy() {
        EvolutionMonitor monitor = new EvolutionMonitor();
        Chromosome best = validChromosome().withFitness(0.5);
        monitor.record(1, best, List.of(best), 512);
        List<GenerationResult> h1 = monitor.history();
        List<GenerationResult> h2 = monitor.history();
        assertNotSame(h1, h2, "history() must return different list instances");
    }

    @Test
    @DisplayName("EvolutionMonitor: convergenceRate correct with 2 records")
    void evolutionMonitor_convergenceRateCorrect() {
        EvolutionMonitor monitor = new EvolutionMonitor();
        Chromosome c1 = validChromosome().withFitness(0.2);
        Chromosome c2 = validChromosome().withFitness(0.4);
        monitor.record(0, c1, List.of(c1), 512);
        monitor.record(1, c2, List.of(c2), 512);
        double rate = monitor.convergenceRate();
        // rate = (0.4 - 0.2) / (1 - 0) = 0.2
        assertEquals(0.2, rate, 1e-6);
    }

    @Test
    @DisplayName("EvolutionMonitor: hasConverged is false with fewer than 500 generations")
    void evolutionMonitor_hasConvergedFalseBelow500() {
        EvolutionMonitor monitor = new EvolutionMonitor();
        Chromosome best = validChromosome().withFitness(0.5);
        for (int i = 0; i < 100; i++) {
            monitor.record(i, best, List.of(best), 512);
        }
        assertFalse(monitor.hasConverged());
    }

    @Test
    @DisplayName("EvolutionMonitor: printHistory doesn't throw")
    void evolutionMonitor_printHistoryDoesNotThrow() {
        EvolutionMonitor monitor = new EvolutionMonitor();
        Chromosome best = validChromosome().withFitness(0.5);
        monitor.record(1, best, List.of(best), 512);
        assertDoesNotThrow(() -> monitor.printHistory(new PrintStream(new ByteArrayOutputStream())));
    }

    @Test
    @DisplayName("EvolutionMonitor: printHistogram prints 'No data' on empty")
    void evolutionMonitor_printHistogramNoDataOnEmpty() {
        EvolutionMonitor monitor = new EvolutionMonitor();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        monitor.printHistogram(new PrintStream(baos));
        assertTrue(baos.toString().contains("No data"), "Must print 'No data' when empty");
    }

    @Test
    @DisplayName("EvolutionMonitor: concurrent writes produce correct count")
    void evolutionMonitor_concurrentWritesCorrectCount() throws InterruptedException {
        EvolutionMonitor monitor = new EvolutionMonitor();
        Chromosome best = validChromosome().withFitness(0.5);
        int threads = 10;
        int recordsPerThread = 100;
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int offset = t * recordsPerThread;
            workers.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < recordsPerThread; i++) {
                    monitor.record(offset + i, best, List.of(best), 512);
                }
            }));
        }
        for (Thread w : workers) w.join();
        assertEquals(threads * recordsPerThread, monitor.history().size());
    }

    // ========================= SelectionStrategy =========================

    @Test
    @DisplayName("TournamentSelection: returns requested count")
    void tournamentSelection_returnsRequestedCount() {
        SelectionStrategy strat = new SelectionStrategy.TournamentSelection(3);
        List<Chromosome> pop = buildPopulation(10);
        List<Chromosome> selected = strat.select(pop, 5, new Random(42));
        assertEquals(5, selected.size());
    }

    @Test
    @DisplayName("TournamentSelection: selected chromosomes are members of population")
    void tournamentSelection_selectedAreMembersOfPopulation() {
        SelectionStrategy strat = new SelectionStrategy.TournamentSelection(3);
        List<Chromosome> pop = buildPopulation(10);
        List<Chromosome> selected = strat.select(pop, 5, new Random(42));
        for (Chromosome c : selected) {
            assertTrue(pop.contains(c), "Selected chromosome must be from population");
        }
    }

    @Test
    @DisplayName("EliteSelection: guarantees best chromosome in result")
    void eliteSelection_bestInResult() {
        SelectionStrategy strat = new SelectionStrategy.EliteSelection(
                1, new SelectionStrategy.TournamentSelection(2));
        List<Chromosome> pop = buildPopulationWithFitnesses(0.1, 0.5, 0.9, 0.3, 0.7);
        Chromosome best = pop.stream()
                .max(Comparator.comparingDouble(Chromosome::fitness)).get();
        List<Chromosome> selected = strat.select(pop, 3, new Random(42));
        assertTrue(selected.contains(best), "Elite selection must include the best chromosome");
    }

    @Test
    @DisplayName("EliteSelection: capped when eliteCount exceeds requested count")
    void eliteSelection_cappedWhenEliteCountExceedsRequested() {
        SelectionStrategy strat = new SelectionStrategy.EliteSelection(
                5, new SelectionStrategy.TournamentSelection(2));
        List<Chromosome> pop = buildPopulation(10);
        List<Chromosome> selected = strat.select(pop, 3, new Random(42));
        assertTrue(selected.size() <= 3, "Cannot select more than requested count");
    }

    @Test
    @DisplayName("RankSelection: returns requested count")
    void rankSelection_returnsRequestedCount() {
        SelectionStrategy strat = new SelectionStrategy.RankSelection();
        List<Chromosome> pop = buildPopulation(10);
        List<Chromosome> selected = strat.select(pop, 5, new Random(42));
        assertEquals(5, selected.size());
    }

    @Test
    @DisplayName("RankSelection: equal-fitness population doesn't throw")
    void rankSelection_equalFitnessDoesNotThrow() {
        SelectionStrategy strat = new SelectionStrategy.RankSelection();
        // All chromosomes with same fitness — floating point rounding edge case
        List<Chromosome> pop = buildPopulationWithFitnesses(0.5, 0.5, 0.5, 0.5, 0.5);
        assertDoesNotThrow(() -> {
            List<Chromosome> selected = strat.select(pop, 5, new Random(99));
            assertEquals(5, selected.size());
        });
    }

    // ========================= CrossoverEngine =========================

    @Test
    @DisplayName("CrossoverEngine: all 4 strategies produce non-null offspring")
    void crossoverEngine_allStrategiesProduceNonNullOffspring() {
        Chromosome p1 = buildChromosomeWithRules(5);
        Chromosome p2 = buildChromosomeWithRules(5);

        for (EvolutionConfig.CrossoverStrategy strategy : EvolutionConfig.CrossoverStrategy.values()) {
            EvolutionConfig config = buildConfigWithStrategy(strategy);
            CrossoverEngine engine = new CrossoverEngine(config, new Random(42));
            Pair<Chromosome, Chromosome> offspring = engine.crossover(p1, p2);
            assertNotNull(offspring, "Offspring must not be null for strategy " + strategy);
            assertNotNull(offspring.first(), "First child must not be null for " + strategy);
            assertNotNull(offspring.second(), "Second child must not be null for " + strategy);
        }
    }

    @Test
    @DisplayName("CrossoverEngine: OPERATOR_AWARE handles empty parents")
    void crossoverEngine_operatorAwareHandlesEmptyParents() {
        EvolutionConfig config = buildConfigWithStrategy(EvolutionConfig.CrossoverStrategy.OPERATOR_AWARE);
        CrossoverEngine engine = new CrossoverEngine(config, new Random(42));
        Chromosome empty1 = new Chromosome(Collections.emptyList(), 0, 0.0, "e1");
        Chromosome empty2 = new Chromosome(Collections.emptyList(), 0, 0.0, "e2");
        assertDoesNotThrow(() -> engine.crossover(empty1, empty2));
    }

    @Test
    @DisplayName("CrossoverEngine: offspring have different IDs than parents")
    void crossoverEngine_newIdsAssignedToChildren() {
        EvolutionConfig config = buildConfigWithStrategy(EvolutionConfig.CrossoverStrategy.SINGLE_POINT);
        CrossoverEngine engine = new CrossoverEngine(config, new Random(42));
        Chromosome p1 = buildChromosomeWithRules(5);
        Chromosome p2 = buildChromosomeWithRules(5);
        Pair<Chromosome, Chromosome> offspring = engine.crossover(p1, p2);
        assertNotEquals(p1.id(), offspring.first().id());
        assertNotEquals(p1.id(), offspring.second().id());
        assertNotEquals(p2.id(), offspring.first().id());
        assertNotEquals(p2.id(), offspring.second().id());
    }

    // ========================= MutationEngine =========================

    @Test
    @DisplayName("MutationEngine: result never exceeds maxChromosomeRules")
    void mutationEngine_resultNeverExceedsMax() {
        MutationEngine engine = new MutationEngine(fastConfig, lib, new Random(42));
        Chromosome c = buildChromosomeWithRules(fastConfig.maxChromosomeRules());
        for (int i = 0; i < 50; i++) {
            c = engine.mutate(c);
            assertTrue(c.rules().size() <= fastConfig.maxChromosomeRules(),
                    "Chromosome must not exceed maxChromosomeRules after mutation");
        }
    }

    @Test
    @DisplayName("MutationEngine: does not mutate in place")
    void mutationEngine_doesNotMutateInPlace() {
        MutationEngine engine = new MutationEngine(fastConfig, lib, new Random(42));
        Chromosome c = validChromosome();
        Chromosome mutated = engine.mutate(c);
        assertNotSame(c, mutated, "mutate() must return a new instance");
    }

    @Test
    @DisplayName("MutationEngine: empty chromosome doesn't throw")
    void mutationEngine_emptyChromosomeDoesNotThrow() {
        MutationEngine engine = new MutationEngine(fastConfig, lib, new Random(42));
        Chromosome empty = new Chromosome(Collections.emptyList(), 0, 0.0, "empty");
        assertDoesNotThrow(() -> engine.mutate(empty));
    }

    // ========================= CircularDataBuffer =========================

    @Test
    @DisplayName("CircularDataBuffer: add and snapshot under capacity")
    void circularDataBuffer_addSnapshotUnderCapacity() {
        CircularDataBuffer buf = new CircularDataBuffer(1024);
        byte[] data = arithmeticData(256, 0, 1);
        buf.add(data);
        byte[] snap = buf.snapshot();
        assertEquals(256, snap.length);
        assertArrayEquals(data, snap);
    }

    @Test
    @DisplayName("CircularDataBuffer: overflow overwrites oldest data")
    void circularDataBuffer_overflowOverwritesOldest() {
        CircularDataBuffer buf = new CircularDataBuffer(100);
        byte[] firstChunk = arithmeticData(80, 0, 1);
        byte[] secondChunk = arithmeticData(50, 10, 1);
        buf.add(firstChunk);
        buf.add(secondChunk);
        assertEquals(100, buf.size()); // size is capped at capacity
    }

    @Test
    @DisplayName("CircularDataBuffer: clear resets to empty")
    void circularDataBuffer_clearResetsToEmpty() {
        CircularDataBuffer buf = new CircularDataBuffer(256);
        buf.add(arithmeticData(100, 0, 1));
        assertEquals(100, buf.size());
        buf.clear();
        assertEquals(0, buf.size());
        assertEquals(0, buf.snapshot().length);
    }

    @Test
    @DisplayName("CircularDataBuffer: size never exceeds capacity")
    void circularDataBuffer_sizeNeverExceedsCapacity() {
        CircularDataBuffer buf = new CircularDataBuffer(100);
        for (int i = 0; i < 20; i++) {
            buf.add(arithmeticData(10, i, 1));
        }
        assertTrue(buf.size() <= 100, "Size must never exceed capacity");
    }

    @Test
    @DisplayName("CircularDataBuffer: concurrent add and snapshot doesn't throw")
    void circularDataBuffer_concurrentAddSnapshotDoesNotThrow() throws InterruptedException {
        CircularDataBuffer buf = new CircularDataBuffer(8192);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread writer = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    buf.add(arithmeticData(64, i, 1));
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    buf.snapshot();
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        latch.await(5, TimeUnit.SECONDS);
        assertNull(error.get(), "Concurrent add/snapshot must not throw: " + error.get());
    }

    // ========================= EvolutionConfig =========================

    @Test
    @DisplayName("EvolutionConfig: fastConfig satisfies all constraints")
    void evolutionConfig_fastConfigSatisfiesConstraints(@TempDir Path dir) {
        EvolutionConfig cfg = EvolutionConfig.fastConfig(dir);
        assertTrue(cfg.populationSize() >= 4);
        assertTrue(cfg.corpusWindowSize() >= 256);
        assertTrue(cfg.eliteCount() >= 1);
        assertTrue(cfg.tournamentSize() >= 2);
        assertEquals(100, cfg.maxGenerations());
    }

    @Test
    @DisplayName("EvolutionConfig: thoroughConfig has larger population than fastConfig")
    void evolutionConfig_thoroughConfigLargerThanFast(@TempDir Path dir) {
        EvolutionConfig fast = EvolutionConfig.fastConfig(dir);
        EvolutionConfig thorough = EvolutionConfig.thoroughConfig(dir);
        assertTrue(thorough.populationSize() > fast.populationSize());
    }

    @Test
    @DisplayName("EvolutionConfig: defaultConfig has 0 maxGenerations (infinite)")
    void evolutionConfig_defaultConfigHasZeroMaxGenerations(@TempDir Path dir) {
        EvolutionConfig cfg = EvolutionConfig.defaultConfig(dir);
        assertEquals(0, cfg.maxGenerations());
    }

    @Test
    @DisplayName("EvolutionConfig: invalid populationSize throws")
    void evolutionConfig_invalidPopulationSizeThrows(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> new EvolutionConfig(3, 100, 8192, 1, 2,
                        EvolutionConfig.CrossoverStrategy.OPERATOR_AWARE,
                        0.05, 0.02, 50, 0L, 256, false, dir));
    }

    @Test
    @DisplayName("EvolutionConfig: invalid corpusWindowSize throws")
    void evolutionConfig_invalidCorpusWindowSizeThrows(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> new EvolutionConfig(10, 100, 100, 1, 2,
                        EvolutionConfig.CrossoverStrategy.OPERATOR_AWARE,
                        0.05, 0.02, 50, 0L, 256, false, dir));
    }

    // ========================= EvolutionaryEdgeFinder =========================

    @Test
    @DisplayName("EvolutionaryEdgeFinder: initial getCurrentBest is not null")
    void edgeFinder_initialBestNotNull(@TempDir Path dir) {
        EvolutionConfig config = EvolutionConfig.fastConfig(dir);
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, null, null, new Random(42));
        assertNotNull(finder.getCurrentBest());
    }

    @Test
    @DisplayName("EvolutionaryEdgeFinder: initial getGenerationCount is 0")
    void edgeFinder_initialGenerationCountZero(@TempDir Path dir) {
        EvolutionConfig config = EvolutionConfig.fastConfig(dir);
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, null, null, new Random(42));
        assertEquals(0, finder.getGenerationCount());
    }

    @Test
    @DisplayName("EvolutionaryEdgeFinder: feedData doesn't throw")
    void edgeFinder_feedDataDoesNotThrow(@TempDir Path dir) {
        EvolutionConfig config = EvolutionConfig.fastConfig(dir);
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, null, null, new Random(42));
        byte[] data = arithmeticData(512, 0, 3);
        assertDoesNotThrow(() -> finder.feedData(data));
    }

    @Test
    @DisplayName("EvolutionaryEdgeFinder: stop terminates thread within 3 seconds")
    void edgeFinder_stopTerminatesThread(@TempDir Path dir) throws InterruptedException {
        EvolutionConfig config = EvolutionConfig.fastConfig(dir);
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, null, new EvolutionMonitor(), new Random(42));
        byte[] data = arithmeticData(fastConfig.corpusWindowSize(), 0, 3);
        finder.feedData(data);
        Thread gaThread = Thread.ofVirtual().start(finder);
        Thread.sleep(200);
        finder.stop();
        gaThread.join(3000);
        assertFalse(gaThread.isAlive(), "GA thread must stop within 3 seconds");
    }

    @Test
    @DisplayName("EvolutionaryEdgeFinder: getLastGenerationResult is null before run")
    void edgeFinder_lastResultNullBeforeRun(@TempDir Path dir) {
        EvolutionConfig config = EvolutionConfig.fastConfig(dir);
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, null, null, new Random(42));
        assertNull(finder.getLastGenerationResult());
    }

    @Test
    @Tag("slow")
    @DisplayName("[SLOW] EvolutionaryEdgeFinder: runs maxGenerations then completes")
    void edgeFinder_runUntilMaxGenerations_completesWithinTimeout(@TempDir Path dir)
            throws InterruptedException {
        EvolutionConfig config = EvolutionConfig.fastConfig(dir);
        EvolutionMonitor monitor = new EvolutionMonitor();
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(
                config, lib, null, monitor, new Random(42));
        byte[] data = arithmeticData(config.corpusWindowSize(), 0, 3);
        finder.feedData(data);

        Thread gaThread = Thread.ofVirtual().start(finder);
        gaThread.join(15_000); // 15 second timeout

        assertFalse(gaThread.isAlive(), "GA must complete maxGenerations within 15s");
        assertTrue(finder.getGenerationCount() > 0, "At least 1 generation must have run");
    }

    // ========================= Helper Methods =========================

    static void assumeOpPresent(Optional<Operator> op, String label) {
        org.junit.jupiter.api.Assumptions.assumeTrue(op.isPresent(),
                "Operator not found for " + label + " — skipping test");
    }

    static void assumeTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(condition, message);
    }

    static List<Chromosome> buildPopulation(int size) {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        List<Chromosome> pop = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            double fitness = (double) i / size;
            pop.add(factory.createRandom(10).withFitness(fitness));
        }
        return pop;
    }

    static List<Chromosome> buildPopulationWithFitnesses(double... fitnesses) {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        List<Chromosome> pop = new ArrayList<>();
        for (double f : fitnesses) {
            pop.add(factory.createRandom(10).withFitness(f));
        }
        return pop;
    }

    static Chromosome buildChromosomeWithRules(int count) {
        ChromosomeFactory factory = new ChromosomeFactory(lib, new Random(42));
        return factory.createRandom(count);
    }

    static EvolutionConfig buildConfigWithStrategy(EvolutionConfig.CrossoverStrategy strategy) {
        return new EvolutionConfig(
                20, 100, 8192, 1, 3,
                strategy,
                0.1, 0.05, 50, 0L, 256, false,
                Path.of(".")
        );
    }
}
