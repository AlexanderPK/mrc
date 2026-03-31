package mrc.evolution;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.graph.TransitionGraph;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Meta-harness for the MRC Genetic Algorithm.
 *
 * <p>Wraps {@link EvolutionaryEdgeFinder} with:
 * <ul>
 *   <li>Lifecycle management (start / pause / resume / stop)</li>
 *   <li>Per-generation structured logging to CSV + NDJSON via {@link GenerationLogger}</li>
 *   <li>Adaptive mutation-rate control via {@link AdaptiveController}</li>
 *   <li>Event callbacks: onNewBest, onConverged, onStall</li>
 *   <li>Rich metrics snapshot via {@link HarnessMetrics}</li>
 *   <li>End-of-run report via {@link RunReport}</li>
 * </ul>
 *
 * <p>Typical usage:
 * <pre>{@code
 * GaHarness harness = new GaHarness.Builder(config, lib, logDir)
 *     .onNewBest(c -> System.out.println("New best: " + c.fitness()))
 *     .onConverged(() -> System.out.println("Converged!"))
 *     .build();
 *
 * harness.feedData(inputBytes);
 * harness.start();
 *
 * // ... later ...
 * HarnessMetrics m = harness.getMetrics();
 * harness.stop();
 * harness.writeReport();
 * }</pre>
 */
public class GaHarness {

    // ── Metrics snapshot ──────────────────────────────────────────────────────

    public record HarnessMetrics(
        long   generation,
        double bestFitness,
        double avgFitness,
        double fitnessVariance,
        int    populationSize,
        int    uniqueOperators,
        int    bestRuleCount,
        long   corpusBytes,
        double convergenceRate,
        double currentRuleMutationProb,
        double currentChromMutationProb,
        boolean converged,
        boolean paused,
        boolean running,
        long   stallBoosts,
        long   improveDamps,
        long   elapsedMs,
        Instant timestamp
    ) {}

    // ── Builder ───────────────────────────────────────────────────────────────

    public static class Builder {
        private final EvolutionConfig config;
        private final ExtendedOperatorLibrary lib;
        private final Path logDir;
        private TransitionGraph seedGraph = new TransitionGraph();
        private AdaptiveController.Config adaptiveConfig = AdaptiveController.Config.defaults();
        private boolean adaptiveEnabled = true;
        private Consumer<Chromosome> onNewBest = c -> {};
        private Runnable onConverged = () -> {};
        private Runnable onStall = () -> {};
        private Consumer<GenerationResult> onGeneration = r -> {};

        public Builder(EvolutionConfig config, ExtendedOperatorLibrary lib, Path logDir) {
            this.config = config;
            this.lib = lib;
            this.logDir = logDir;
        }

        public Builder seedGraph(TransitionGraph g)            { this.seedGraph = g; return this; }
        public Builder adaptiveConfig(AdaptiveController.Config c) { this.adaptiveConfig = c; return this; }
        public Builder adaptiveEnabled(boolean b)              { this.adaptiveEnabled = b; return this; }
        public Builder onNewBest(Consumer<Chromosome> cb)      { this.onNewBest = cb; return this; }
        public Builder onConverged(Runnable cb)                { this.onConverged = cb; return this; }
        public Builder onStall(Runnable cb)                    { this.onStall = cb; return this; }
        public Builder onGeneration(Consumer<GenerationResult> cb) { this.onGeneration = cb; return this; }

        public GaHarness build() throws IOException {
            return new GaHarness(this);
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final EvolutionaryEdgeFinder finder;
    private final EvolutionMonitor monitor;
    private final GenerationLogger logger;
    private final AdaptiveController adaptive;  // null if disabled
    private final Path logDir;
    private final EvolutionConfig config;

    private final Consumer<Chromosome> onNewBest;
    private final Runnable onConverged;
    private final Runnable onStall;
    private final Consumer<GenerationResult> onGeneration;

    private volatile boolean running  = false;
    private volatile boolean paused   = false;
    private volatile boolean convergedFired = false;
    private volatile boolean stallFired     = false;

    private final AtomicReference<Chromosome> lastKnownBest = new AtomicReference<>();
    private final Instant startTime;
    private volatile Instant endTime;

    private Thread harnessThread;

    // ── Constructor (use Builder) ─────────────────────────────────────────────

    private GaHarness(Builder b) throws IOException {
        this.config   = b.config;
        this.logDir   = b.logDir;
        this.monitor  = new EvolutionMonitor();
        this.finder   = new EvolutionaryEdgeFinder(b.config, b.lib, b.seedGraph, monitor);
        this.logger   = new GenerationLogger(b.logDir);
        this.adaptive = b.adaptiveEnabled
                ? new AdaptiveController(monitor, finder.getMutationEngine(), b.config, b.adaptiveConfig)
                : null;
        this.onNewBest    = b.onNewBest;
        this.onConverged  = b.onConverged;
        this.onStall      = b.onStall;
        this.onGeneration = b.onGeneration;
        this.startTime    = Instant.now();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Start the GA and the harness supervisor loop. */
    public void start() {
        if (running) return;
        running = true;
        Thread.ofVirtual().name("ga-evolution").start(finder);
        harnessThread = Thread.ofVirtual().name("ga-harness").start(this::supervisorLoop);
    }

    /** Pause evolution (the GA thread keeps running but is throttled to 0 work). */
    public void pause() {
        paused = true;
    }

    /** Resume after a pause. */
    public void resume() {
        paused = false;
    }

    /** Stop everything and flush logs. */
    public void stop() {
        running = false;
        finder.stop();
        if (harnessThread != null) harnessThread.interrupt();
        endTime = Instant.now();
        logger.close();
    }

    /** Feed new corpus data to the GA. */
    public void feedData(byte[] data) {
        finder.feedData(data);
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    /** Thread-safe snapshot of current harness state. */
    public HarnessMetrics getMetrics() {
        GenerationResult last = monitor.latest();
        Chromosome best = finder.getCurrentBest();
        long gen  = finder.getGenerationCount();
        double rate = monitor.convergenceRate();

        return new HarnessMetrics(
            gen,
            best != null ? best.fitness() : 0.0,
            last != null ? last.averageFitness() : 0.0,
            last != null ? last.fitnessVariance() : 0.0,
            last != null ? last.populationSize() : 0,
            last != null ? last.uniqueOperatorsUsed() : 0,
            best != null ? best.rules().size() : 0,
            last != null ? last.corpusBytesProcessed() : 0,
            rate,
            adaptive != null ? adaptive.currentRuleMutationProb()        : config.ruleMutationProb(),
            adaptive != null ? adaptive.currentChromosomeMutationProb()  : config.chromosomeMutationProb(),
            monitor.hasConverged(),
            paused,
            running,
            adaptive != null ? adaptive.totalStallBoosts()  : 0,
            adaptive != null ? adaptive.totalImproveDamps() : 0,
            System.currentTimeMillis() - startTime.toEpochMilli(),
            Instant.now()
        );
    }

    /** Return the current best chromosome. */
    public Chromosome getCurrentBest() {
        return finder.getCurrentBest();
    }

    /** Return the underlying finder (for snapshot publishing etc.). */
    public EvolutionaryEdgeFinder getFinder() {
        return finder;
    }

    /** Return the evolution monitor. */
    public EvolutionMonitor getMonitor() {
        return monitor;
    }

    /** Return the generation logger (to check dropped rows etc.). */
    public GenerationLogger getLogger() {
        return logger;
    }

    public long getGenerationCount() {
        return finder.getGenerationCount();
    }

    public boolean isRunning() { return running; }
    public boolean isPaused()  { return paused; }

    // ── Report ────────────────────────────────────────────────────────────────

    /**
     * Generate and write the run report. Call after stop().
     *
     * @return path of the written report file
     */
    public Path writeReport() throws IOException {
        Instant end = endTime != null ? endTime : Instant.now();
        RunReport report = new RunReport(monitor, adaptive, startTime, end,
                finder.getGenerationCount(), null);
        return report.writeToDir(logDir);
    }

    /** Print run report to System.out. */
    public void printReport() {
        Instant end = endTime != null ? endTime : Instant.now();
        RunReport report = new RunReport(monitor, adaptive, startTime, end,
                finder.getGenerationCount(), null);
        report.print(System.out);
    }

    // ── Supervisor loop ───────────────────────────────────────────────────────

    private void supervisorLoop() {
        long lastLoggedGen = -1;

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (paused) {
                    Thread.sleep(100);
                    continue;
                }

                long gen = finder.getGenerationCount();

                // Only act when a new generation has been recorded
                if (gen > lastLoggedGen) {
                    GenerationResult result = finder.getLastGenerationResult();
                    if (result != null) {
                        double rate = monitor.convergenceRate();

                        // 1. Log to disk
                        logger.log(result, rate);

                        // 2. Per-generation callback
                        onGeneration.accept(result);

                        // 3. Adaptive control tick
                        if (adaptive != null) adaptive.tick();

                        // 4. New-best callback
                        Chromosome best = finder.getCurrentBest();
                        Chromosome prev = lastKnownBest.get();
                        if (best != null && (prev == null || best.fitness() > prev.fitness())) {
                            lastKnownBest.set(best);
                            onNewBest.accept(best);
                        }

                        // 5. Convergence callback (fires once)
                        if (!convergedFired && monitor.hasConverged()) {
                            convergedFired = true;
                            onConverged.run();
                        }

                        // 6. Stall callback: stalled if no improvement for last 200 gens
                        if (!stallFired && gen >= 200 && Math.abs(rate) < 1e-7) {
                            stallFired = true;
                            onStall.run();
                        } else if (rate > 1e-6) {
                            stallFired = false; // reset if improving again
                        }

                        lastLoggedGen = gen;
                    }
                }

                // Check generation limit
                if (config.maxGenerations() > 0 && gen >= config.maxGenerations()) {
                    running = false;
                }

                Thread.sleep(20); // poll every 20ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
