package mrc.gaservice;

import mrc.core.OpIdMap;
import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.*;
import mrc.graph.TransitionGraph;
import mrc.snapshot.MrcSnapshotException;
import mrc.snapshot.SnapshotSerializer;
import mrc.snapshotdb.FileSnapshotStore;
import mrc.snapshotdb.SnapshotStore;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the GA Engine Service.
 *
 * <p>CLI arguments:
 * <pre>
 *   --store-path      &lt;dir&gt;   path to the snapshot store directory (required)
 *   --log-dir         &lt;dir&gt;   directory for CSV/NDJSON/report logs (default: store-path/logs)
 *   --domain          &lt;tag&gt;   domain tag for published snapshots
 *   --max-generations &lt;n&gt;    stop after N generations (0 = run forever)
 *   --snapshot-every  &lt;n&gt;    publish a snapshot every N generations (default: 10)
 *   --health-port     &lt;port&gt;  HTTP health endpoint port (default: 8080, 0 = disabled)
 *   --no-adaptive            disable adaptive mutation-rate controller
 * </pre>
 *
 * <p>Published snapshot IDs are printed to stdout, one per line.
 * Per-generation stats are written to {@code <log-dir>/generations.csv} and
 * {@code <log-dir>/generations.ndjson}. A run report is written to
 * {@code <log-dir>/run-report.txt} on completion.
 */
public class GaServiceMain {

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);

        ExtendedOperatorLibrary lib   = ExtendedOperatorLibrary.getInstance();
        SnapshotStore store           = new FileSnapshotStore(parsed.storePath);
        Path logDir                   = parsed.logDir != null
                                        ? parsed.logDir
                                        : parsed.storePath.resolve("logs");

        EvolutionConfig config = EvolutionConfig.fastConfig(parsed.storePath.resolve("tmp"));
        if (parsed.maxGenerations > 0) {
            config = new EvolutionConfig(
                    config.populationSize(), (int) parsed.maxGenerations,
                    config.corpusWindowSize(), config.eliteCount(),
                    config.tournamentSize(), config.crossoverStrategy(),
                    config.ruleMutationProb(), config.chromosomeMutationProb(),
                    config.snapshotIntervalGenerations(), config.generationDelayMs(),
                    config.maxChromosomeRules(), config.parallelFitness(),
                    config.snapshotOutputDir());
        }

        // Build harness
        final String domain = parsed.domain;
        GaHarness harness = new GaHarness.Builder(config, lib, logDir)
                .seedGraph(new TransitionGraph())
                .adaptiveEnabled(!parsed.noAdaptive)
                .onNewBest(c -> System.out.printf("[harness] new best  fitness=%.6f rules=%d%n",
                        c.fitness(), c.rules().size()))
                .onConverged(() -> System.out.println("[harness] CONVERGED"))
                .onStall(() -> System.out.println("[harness] STALL detected — adaptive controller boosting mutation"))
                .onGeneration(r -> {
                    // lightweight per-gen console tick every 50 gens
                    if (r.generation() % 50 == 0) {
                        System.out.printf("[harness] gen=%d best=%.4f avg=%.4f ops=%d%n",
                                r.generation(), r.bestFitness(), r.averageFitness(), r.uniqueOperatorsUsed());
                    }
                })
                .build();

        System.out.printf("[harness] logs → %s%n", logDir.toAbsolutePath());

        // Start health server
        HealthServer healthServer = null;
        if (parsed.healthPort != 0) {
            healthServer = HealthServer.start(parsed.healthPort, () -> {
                GaHarness.HarnessMetrics m = harness.getMetrics();
                return new HealthServer.GaStatus(m.generation(), m.bestFitness());
            });
            System.out.println("[harness] health → http://localhost:" + healthServer.port() + "/health");
        }

        // Feed corpus data — use provided files or fall back to arithmetic seed
        if (!parsed.corpusFiles().isEmpty()) {
            for (Path corpusFile : parsed.corpusFiles()) {
                try {
                    byte[] data = java.nio.file.Files.readAllBytes(corpusFile);
                    harness.feedData(data);
                    System.out.printf("[harness] corpus ← %s (%,d bytes)%n",
                            corpusFile.getFileName(), data.length);
                } catch (IOException e) {
                    System.err.println("[harness] failed to load corpus file: " + corpusFile + ": " + e.getMessage());
                }
            }
        } else {
            byte[] seedData = new byte[4096];
            for (int i = 0; i < seedData.length; i++) seedData[i] = (byte) (i & 0xFF);
            harness.feedData(seedData);
            System.out.println("[harness] corpus ← arithmetic seed (4096 bytes)");
        }

        // Start the harness (starts GA + supervisor loop internally)
        harness.start();

        final long snapshotEvery = parsed.snapshotEvery;
        final SnapshotStore snapshotStore = store;
        final ExtendedOperatorLibrary finalLib = lib;

        CountDownLatch done = new CountDownLatch(1);
        Thread managerThread = Thread.ofVirtual().name("ga-manager").start(() -> {
            long lastSnapshotGen = 0;
            long maxGen = parsed.maxGenerations > 0 ? parsed.maxGenerations : Long.MAX_VALUE;

            while (harness.getGenerationCount() < maxGen) {
                long gen = harness.getGenerationCount();
                if (gen - lastSnapshotGen >= snapshotEvery && gen > 0) {
                    try {
                        publishSnapshot(harness.getFinder(), finalLib, snapshotStore, domain, gen);
                        lastSnapshotGen = gen;

                        // Print live metrics to console alongside snapshot
                        GaHarness.HarnessMetrics m = harness.getMetrics();
                        System.out.printf("[metrics] gen=%d best=%.6f avg=%.6f var=%.6f " +
                                "ruleMutProb=%.4f stallBoosts=%d improveDamps=%d elapsedMs=%d%n",
                                m.generation(), m.bestFitness(), m.avgFitness(), m.fitnessVariance(),
                                m.currentRuleMutationProb(), m.stallBoosts(), m.improveDamps(), m.elapsedMs());
                    } catch (Exception e) {
                        System.err.println("[harness] snapshot failed at gen " + gen + ": " + e.getMessage());
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Final snapshot
            long finalGen = harness.getGenerationCount();
            if (finalGen > lastSnapshotGen) {
                try {
                    publishSnapshot(harness.getFinder(), finalLib, snapshotStore, domain, finalGen);
                } catch (Exception e) {
                    System.err.println("[harness] final snapshot failed: " + e.getMessage());
                }
            }

            harness.stop();
            done.countDown();
        });

        done.await();

        // Write run report
        try {
            Path reportPath = harness.writeReport();
            System.out.println("[harness] run report → " + reportPath.toAbsolutePath());
            harness.printReport();
        } catch (IOException e) {
            System.err.println("[harness] report write failed: " + e.getMessage());
        }

        if (healthServer != null) healthServer.stop();
        System.out.println("[harness] GA service completed.");
    }

    // ── Snapshot publishing ───────────────────────────────────────────────────

    static String publishSnapshot(EvolutionaryEdgeFinder finder,
                                   ExtendedOperatorLibrary lib,
                                   SnapshotStore store,
                                   String domainTag,
                                   long generation)
            throws IOException, MrcSnapshotException {
        Chromosome best = finder.getCurrentBest();
        if (best == null) return null;

        List<Chromosome.OperatorRule> safeRules = best.rules().stream()
                .filter(r -> {
                    try {
                        OpIdMap.getOpId(r.assignedOperator());
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .toList();
        if (safeRules.size() != best.rules().size()) {
            best = best.withRules(safeRules);
        }

        Path tmp = Files.createTempFile("mrc-ga-snap-", ".snap");
        try {
            SnapshotSerializer serializer = new SnapshotSerializer(lib);
            serializer.serialize(tmp, generation, null, null, best, null, domainTag);
            String id = store.publish(tmp);
            System.out.println("snapshot_id=" + id + " gen=" + generation);
            return id;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── CLI argument parsing ──────────────────────────────────────────────────

    record Args(Path storePath, Path logDir, String domain, long maxGenerations,
                long snapshotEvery, int healthPort, boolean noAdaptive,
                List<Path> corpusFiles) {

        static Args parse(String[] args) {
            Path storePath = null;
            Path logDir = null;
            String domain = null;
            long maxGenerations = 0;
            long snapshotEvery = 10;
            int healthPort = 8080;
            boolean noAdaptive = false;
            List<Path> corpusFiles = new java.util.ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--store-path"      -> storePath = Path.of(args[++i]);
                    case "--log-dir"         -> logDir = Path.of(args[++i]);
                    case "--domain"          -> domain = args[++i];
                    case "--max-generations" -> maxGenerations = Long.parseLong(args[++i]);
                    case "--snapshot-every"  -> snapshotEvery = Long.parseLong(args[++i]);
                    case "--health-port"     -> healthPort = Integer.parseInt(args[++i]);
                    case "--no-adaptive"     -> noAdaptive = true;
                    case "--corpus-file"     -> corpusFiles.add(Path.of(args[++i]));
                    default -> System.err.println("Unknown arg: " + args[i]);
                }
            }
            if (storePath == null) {
                System.err.println("Usage: --store-path <dir> [--log-dir <dir>] [--domain <tag>] "
                        + "[--max-generations <n>] [--snapshot-every <n>] "
                        + "[--health-port <port>] [--no-adaptive] [--corpus-file <path>...]");
                System.exit(1);
            }
            return new Args(storePath, logDir, domain, maxGenerations, snapshotEvery, healthPort,
                    noAdaptive, List.copyOf(corpusFiles));
        }
    }
}
