package mrc.evolution;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.graph.TransitionGraph;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GA meta-harness: GenerationLogger, AdaptiveController, GaHarness, RunReport.
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
class GaHarnessTest {

    static ExtendedOperatorLibrary lib;
    static EvolutionConfig fastConfig;

    @BeforeAll
    static void setup(@TempDir Path tmp) {
        lib = ExtendedOperatorLibrary.getInstance();
        fastConfig = EvolutionConfig.fastConfig(tmp.resolve("snap"));
    }

    // ─── GenerationLogger ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GenerationLogger writes parseable CSV header")
    void logger_csvHeader(@TempDir Path logDir) throws Exception {
        try (GenerationLogger logger = new GenerationLogger(logDir)) {
            // just open and close — header should be written
        }
        Path csv = logDir.resolve("generations.csv");
        assertTrue(Files.exists(csv), "generations.csv must exist");
        String firstLine = Files.readAllLines(csv).get(0);
        assertTrue(firstLine.startsWith("generation,"), "CSV must start with 'generation,'");
        assertTrue(firstLine.contains("best_fitness"), "CSV header must contain 'best_fitness'");
        assertTrue(firstLine.contains("convergence_rate"), "CSV header must contain 'convergence_rate'");
    }

    @Test
    @DisplayName("GenerationLogger writes CSV and NDJSON rows")
    void logger_writesRows(@TempDir Path logDir) throws Exception {
        EvolutionMonitor monitor = new EvolutionMonitor();
        List<Chromosome> pop = buildMiniPopulation(5);
        Chromosome best = pop.get(0);

        try (GenerationLogger logger = new GenerationLogger(logDir)) {
            for (int gen = 1; gen <= 10; gen++) {
                GenerationResult r = GenerationResult.of(gen, best, pop, 1024L, Instant.now());
                logger.log(r, 0.001);
            }
        } // close() flushes

        // CSV
        Path csv = logDir.resolve("generations.csv");
        List<String> csvLines = Files.readAllLines(csv);
        assertEquals(11, csvLines.size(), "1 header + 10 data rows");
        String[] cols = csvLines.get(1).split(",");
        assertEquals(11, cols.length, "CSV row must have 11 columns");
        assertEquals("1", cols[0], "First data row generation must be 1");

        // NDJSON
        Path ndjson = logDir.resolve("generations.ndjson");
        List<String> jsonLines = Files.readAllLines(ndjson);
        assertEquals(10, jsonLines.size(), "10 JSON objects");
        String first = jsonLines.get(0);
        assertTrue(first.startsWith("{"), "NDJSON line must be a JSON object");
        assertTrue(first.contains("\"generation\":1"), "NDJSON must contain generation field");
        assertTrue(first.contains("\"best_fitness\":"), "NDJSON must contain best_fitness field");
    }

    @Test
    @DisplayName("GenerationLogger NDJSON lines are valid JSON objects")
    void logger_validJson(@TempDir Path logDir) throws Exception {
        EvolutionMonitor monitor = new EvolutionMonitor();
        List<Chromosome> pop = buildMiniPopulation(3);
        Chromosome best = pop.get(0);

        try (GenerationLogger logger = new GenerationLogger(logDir)) {
            GenerationResult r = GenerationResult.of(42L, best, pop, 512L, Instant.now());
            logger.log(r, -0.0001);
        }

        Path ndjson = logDir.resolve("generations.ndjson");
        List<String> lines = Files.readAllLines(ndjson);
        assertFalse(lines.isEmpty(), "Should have at least one NDJSON line");
        String json = lines.get(0);
        assertTrue(json.startsWith("{") && json.endsWith("}"), "Must be a JSON object");
        assertTrue(json.contains("\"generation\":42"), "Must record generation 42");
        assertTrue(json.contains("\"corpus_bytes\":512"), "Must record corpus_bytes");
    }

    // ─── AdaptiveController ───────────────────────────────────────────────────

    @Test
    @DisplayName("AdaptiveController increases mutation on stall")
    void adaptive_boostOnStall(@TempDir Path tmp) {
        EvolutionConfig config = EvolutionConfig.fastConfig(tmp);
        EvolutionMonitor monitor = new EvolutionMonitor();
        // fill monitor with flat fitness (stalling)
        List<Chromosome> pop = buildMiniPopulation(4);
        Chromosome best = pop.get(0);
        for (int i = 0; i < 600; i++) {
            monitor.record(i, best, pop, 1024L);
        }
        assertTrue(monitor.hasConverged(), "Monitor should detect convergence after 600 flat gens");

        MutationEngine engine = new MutationEngine(config, lib, new Random(42));
        AdaptiveController.Config ctrl = new AdaptiveController.Config(
                0.005, 0.4, 1.0, 0.005, 1, 10, 1.5, 0.9, 0.999);
        AdaptiveController adaptive = new AdaptiveController(monitor, engine, config, ctrl);

        double before = engine.effectiveRuleMutationProb();
        adaptive.tick(); // convergence rate ≈ 0, stallWindow=1 → boost immediately
        double after = engine.effectiveRuleMutationProb();

        assertTrue(after > before || after >= ctrl.maxMutationProb() - 1e-9,
                "Mutation prob should increase or reach max on stall");
    }

    @Test
    @DisplayName("AdaptiveController dampens mutation on rapid improvement")
    void adaptive_dampenOnImprovement(@TempDir Path tmp) {
        EvolutionConfig config = EvolutionConfig.fastConfig(tmp);
        EvolutionMonitor monitor = new EvolutionMonitor();
        // fill monitor with rapidly improving fitness
        List<Chromosome> pop = buildMiniPopulation(4);
        for (int i = 0; i < 20; i++) {
            Chromosome c = pop.get(0).withFitness(0.01 + i * 0.05);
            monitor.record(i, c, pop, 1024L);
        }

        MutationEngine engine = new MutationEngine(config, lib, new Random(42));
        // set prob above baseline so dampening can work
        engine.setRuleMutationProb(0.3);
        AdaptiveController.Config ctrl = new AdaptiveController.Config(
                0.005, 0.4, 0.00005, 0.001, 30, 1, 1.2, 0.8, 0.999);
        AdaptiveController adaptive = new AdaptiveController(monitor, engine, config, ctrl);

        double before = engine.effectiveRuleMutationProb();
        // tick boostWindow=1 times
        adaptive.tick();
        double after = engine.effectiveRuleMutationProb();
        assertTrue(after <= before, "Mutation prob should decrease or stay on fast improvement");
    }

    @Test
    @DisplayName("AdaptiveController respects min/max bounds")
    void adaptive_bounds(@TempDir Path tmp) {
        EvolutionConfig config = EvolutionConfig.fastConfig(tmp);
        EvolutionMonitor monitor = new EvolutionMonitor();
        List<Chromosome> pop = buildMiniPopulation(4);
        Chromosome best = pop.get(0);
        for (int i = 0; i < 600; i++) {
            monitor.record(i, best, pop, 1024L);
        }

        MutationEngine engine = new MutationEngine(config, lib, new Random(0));
        engine.setRuleMutationProb(0.39); // near max
        AdaptiveController.Config ctrl = new AdaptiveController.Config(
                0.005, 0.4, 1.0, 0.005, 1, 10, 2.0, 0.9, 0.999);
        AdaptiveController adaptive = new AdaptiveController(monitor, engine, config, ctrl);

        for (int i = 0; i < 50; i++) adaptive.tick();

        double prob = engine.effectiveRuleMutationProb();
        assertTrue(prob <= ctrl.maxMutationProb() + 1e-9, "Must not exceed maxMutationProb");
        assertTrue(prob >= ctrl.minMutationProb() - 1e-9, "Must not go below minMutationProb");
    }

    // ─── MutationEngine override ──────────────────────────────────────────────

    @Test
    @DisplayName("MutationEngine override fields default to config values")
    void mutationEngine_defaultsToConfig(@TempDir Path tmp) {
        EvolutionConfig config = EvolutionConfig.fastConfig(tmp);
        MutationEngine engine = new MutationEngine(config, lib, new Random(1));
        assertEquals(config.ruleMutationProb(), engine.effectiveRuleMutationProb(), 1e-9);
        assertEquals(config.chromosomeMutationProb(), engine.effectiveChromosomeMutationProb(), 1e-9);
    }

    @Test
    @DisplayName("MutationEngine setRuleMutationProb clamps to [0,1]")
    void mutationEngine_clamp(@TempDir Path tmp) {
        EvolutionConfig config = EvolutionConfig.fastConfig(tmp);
        MutationEngine engine = new MutationEngine(config, lib, new Random(1));
        engine.setRuleMutationProb(99.0);
        assertEquals(1.0, engine.effectiveRuleMutationProb(), 1e-9, "Must clamp to 1.0");
        engine.setRuleMutationProb(-5.0);
        assertEquals(0.0, engine.effectiveRuleMutationProb(), 1e-9, "Must clamp to 0.0");
    }

    // ─── RunReport ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RunReport writes to file and contains expected sections")
    void runReport_writesToFile(@TempDir Path logDir) throws Exception {
        EvolutionMonitor monitor = new EvolutionMonitor();
        List<Chromosome> pop = buildMiniPopulation(5);
        Chromosome best = pop.get(0);
        for (int i = 1; i <= 20; i++) {
            monitor.record(i, best.withFitness(i * 0.01), pop, 1024L);
        }

        RunReport report = new RunReport(monitor, null,
                Instant.now().minusSeconds(10), Instant.now(), 20L, "test-domain");
        Path outPath = report.writeToDir(logDir);

        assertTrue(Files.exists(outPath), "run-report.txt must be created");
        String content = Files.readString(outPath);
        assertTrue(content.contains("MRC GA RUN REPORT"), "Must contain title");
        assertTrue(content.contains("test-domain"), "Must contain domain");
        assertTrue(content.contains("Total generations"), "Must contain generation count");
        assertTrue(content.contains("Fitness"), "Must contain fitness section");
        assertTrue(content.contains("Sparkline"), "Must contain sparkline");
    }

    // ─── GaHarness lifecycle ──────────────────────────────────────────────────

    @Test
    @DisplayName("GaHarness starts, runs, reports metrics, stops cleanly")
    void harness_lifecycle(@TempDir Path logDir) throws Exception {
        EvolutionConfig config = EvolutionConfig.fastConfig(logDir.resolve("snap"));

        AtomicInteger newBestCount = new AtomicInteger(0);
        CountDownLatch started = new CountDownLatch(1);

        GaHarness harness = new GaHarness.Builder(config, lib, logDir)
                .seedGraph(new TransitionGraph())
                .adaptiveEnabled(true)
                .onNewBest(c -> newBestCount.incrementAndGet())
                .onGeneration(r -> { if (r.generation() == 1) started.countDown(); })
                .build();

        byte[] seed = new byte[4096];
        new Random(42).nextBytes(seed);
        harness.feedData(seed);
        harness.start();

        // Wait for at least 1 generation
        assertTrue(started.await(10, TimeUnit.SECONDS), "GA should complete at least 1 generation");

        GaHarness.HarnessMetrics m = harness.getMetrics();
        assertTrue(m.generation() >= 1, "At least 1 generation must be recorded");
        assertTrue(m.running(), "Harness must still be running");
        assertFalse(m.paused(), "Harness must not be paused");
        assertTrue(m.currentRuleMutationProb() > 0, "Rule mutation prob must be positive");

        harness.stop();
        assertFalse(harness.isRunning(), "Harness must be stopped");
    }

    @Test
    @DisplayName("GaHarness pause/resume state transitions correctly")
    void harness_pauseResume(@TempDir Path logDir) throws Exception {
        EvolutionConfig config = EvolutionConfig.fastConfig(logDir.resolve("snap"));
        GaHarness harness = new GaHarness.Builder(config, lib, logDir)
                .adaptiveEnabled(false)
                .build();

        byte[] seed = new byte[2048];
        harness.feedData(seed);
        harness.start();
        Thread.sleep(100);

        assertFalse(harness.isPaused(), "Harness must not be paused before pause()");

        harness.pause();
        assertTrue(harness.isPaused(), "Harness must be paused after pause()");
        assertTrue(harness.isRunning(), "Harness must still be running while paused");

        harness.resume();
        assertFalse(harness.isPaused(), "Harness must not be paused after resume()");

        harness.stop();
        assertFalse(harness.isRunning(), "Harness must not be running after stop()");
    }

    @Test
    @DisplayName("GaHarness writeReport produces run-report.txt")
    void harness_writeReport(@TempDir Path logDir) throws Exception {
        EvolutionConfig config = EvolutionConfig.fastConfig(logDir.resolve("snap"));
        GaHarness harness = new GaHarness.Builder(config, lib, logDir)
                .adaptiveEnabled(false)
                .build();

        byte[] seed = new byte[2048];
        harness.feedData(seed);
        harness.start();
        Thread.sleep(500);
        harness.stop();

        Path report = harness.writeReport();
        assertTrue(Files.exists(report), "run-report.txt must be created");
        String content = Files.readString(report);
        assertTrue(content.contains("MRC GA RUN REPORT"), "Report must contain title");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private List<Chromosome> buildMiniPopulation(int size) {
        Random rng = new Random(0);
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> {
                    List<Chromosome.OperatorRule> rules = new java.util.ArrayList<>();
                    for (int j = 0; j < 5; j++) {
                        int from = rng.nextInt(256);
                        var op = lib.findShortest(from, from);
                        op.ifPresent(o -> rules.add(new Chromosome.OperatorRule(from, from, o)));
                    }
                    return new Chromosome(rules, i, 0.1 + i * 0.05, "test-" + i);
                })
                .<Chromosome>toList();
    }
}
