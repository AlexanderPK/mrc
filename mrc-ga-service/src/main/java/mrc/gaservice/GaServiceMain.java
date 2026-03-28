package mrc.gaservice;

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
 *   --store-path     &lt;dir&gt;   path to the snapshot store directory (required)
 *   --domain         &lt;tag&gt;   domain tag for published snapshots (optional)
 *   --max-generations &lt;n&gt;   stop after N generations (0 = run forever)
 *   --snapshot-every  &lt;n&gt;   publish a snapshot every N generations (default: 10)
 *   --health-port    &lt;port&gt;  HTTP health endpoint port (default: 8080, 0 = disabled)
 * </pre>
 *
 * <p>Published snapshot IDs are printed to stdout, one per line.
 */
public class GaServiceMain {

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);

        ExtendedOperatorLibrary lib = ExtendedOperatorLibrary.getInstance();
        SnapshotStore store = new FileSnapshotStore(parsed.storePath);

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

        TransitionGraph seedGraph = new TransitionGraph();
        EvolutionMonitor monitor = new EvolutionMonitor();
        EvolutionaryEdgeFinder finder = new EvolutionaryEdgeFinder(config, lib, seedGraph, monitor);

        // Start health server
        HealthServer healthServer = null;
        if (parsed.healthPort != 0) {
            healthServer = HealthServer.start(parsed.healthPort, () -> {
                Chromosome best = finder.getCurrentBest();
                return new HealthServer.GaStatus(
                        finder.getGenerationCount(),
                        best != null ? best.fitness() : 0.0);
            });
            System.out.println("Health endpoint: http://localhost:" + healthServer.port() + "/health");
        }

        // Feed some seed data so the corpus has bytes to work with
        byte[] seedData = new byte[4096];
        for (int i = 0; i < seedData.length; i++) seedData[i] = (byte) (i & 0xFF);
        finder.feedData(seedData);

        // Run GA on a virtual thread and snapshot periodically
        final long snapshotEvery = parsed.snapshotEvery;
        final String domain = parsed.domain;
        final SnapshotStore snapshotStore = store;
        final ExtendedOperatorLibrary finalLib = lib;

        CountDownLatch done = new CountDownLatch(1);
        Thread gaThread = Thread.ofVirtual().name("ga-main").start(() -> {
            Thread.ofVirtual().name("ga-evolution").start(finder);

            long lastSnapshotGen = 0;
            while (finder.getGenerationCount() < (parsed.maxGenerations > 0 ? parsed.maxGenerations : Long.MAX_VALUE)) {
                long gen = finder.getGenerationCount();
                if (gen - lastSnapshotGen >= snapshotEvery && gen > 0) {
                    try {
                        publishSnapshot(finder, finalLib, snapshotStore, domain, gen);
                        lastSnapshotGen = gen;
                    } catch (Exception e) {
                        System.err.println("Snapshot failed at gen " + gen + ": " + e.getMessage());
                    }
                }
                try {
                    Thread.sleep(50); // check every 50ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Final snapshot
            if (finder.getGenerationCount() > lastSnapshotGen) {
                try {
                    publishSnapshot(finder, finalLib, snapshotStore, domain, finder.getGenerationCount());
                } catch (Exception e) {
                    System.err.println("Final snapshot failed: " + e.getMessage());
                }
            }

            finder.stop();
            done.countDown();
        });

        done.await();
        if (healthServer != null) healthServer.stop();
        System.out.println("GA service completed.");
    }

    /**
     * Write a snapshot to a temp file, publish it to the store, and print the ID.
     */
    static String publishSnapshot(EvolutionaryEdgeFinder finder,
                                   ExtendedOperatorLibrary lib,
                                   SnapshotStore store,
                                   String domainTag,
                                   long generation)
            throws IOException, MrcSnapshotException {
        Chromosome best = finder.getCurrentBest();
        if (best == null) return null;

        // Filter rules to only those whose operator is registered in OpIdMap.
        // ExtendedOperatorLibrary may produce operator types not yet in the map.
        List<Chromosome.OperatorRule> safeRules = best.rules().stream()
                .filter(r -> {
                    try {
                        mrc.core.OpIdMap.getOpId(r.assignedOperator());
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

    // ─── CLI argument parsing ───────────────────────────────────────────────

    record Args(Path storePath, String domain, long maxGenerations,
                long snapshotEvery, int healthPort) {

        static Args parse(String[] args) {
            Path storePath = null;
            String domain = null;
            long maxGenerations = 0;
            long snapshotEvery = 10;
            int healthPort = 8080;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--store-path"      -> storePath = Path.of(args[++i]);
                    case "--domain"          -> domain = args[++i];
                    case "--max-generations" -> maxGenerations = Long.parseLong(args[++i]);
                    case "--snapshot-every"  -> snapshotEvery = Long.parseLong(args[++i]);
                    case "--health-port"     -> healthPort = Integer.parseInt(args[++i]);
                    default -> System.err.println("Unknown arg: " + args[i]);
                }
            }
            if (storePath == null) {
                System.err.println("Usage: --store-path <dir> [--domain <tag>] "
                        + "[--max-generations <n>] [--snapshot-every <n>] [--health-port <port>]");
                System.exit(1);
            }
            return new Args(storePath, domain, maxGenerations, snapshotEvery, healthPort);
        }
    }
}
