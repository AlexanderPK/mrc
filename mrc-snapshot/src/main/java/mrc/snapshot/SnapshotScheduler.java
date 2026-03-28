package mrc.snapshot;

import mrc.evolution.Chromosome;
import mrc.evolution.EvolutionConfig;
import mrc.graph.CyclePath;
import mrc.graph.TransitionGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages asynchronous snapshot writing.
 * Queues snapshot requests from evolution thread and writes on a background thread.
 */
public class SnapshotScheduler implements Runnable {
    private final EvolutionConfig config;
    private final SnapshotSerializer serializer;
    private final BlockingQueue<SnapshotRequest> requestQueue;
    private volatile boolean running = true;
    private Thread schedulerThread;

    /**
     * Request to write a snapshot.
     */
    private static class SnapshotRequest {
        final long generation;
        final Chromosome bestChromosome;
        final TransitionGraph graph;
        final List<CyclePath> cycles;
        final List<mrc.evolution.GenerationResult> history;
        final String domainTag;

        SnapshotRequest(long generation, Chromosome chromosome,
                       TransitionGraph graph, List<CyclePath> cycles,
                       List<mrc.evolution.GenerationResult> history, String domainTag) {
            this.generation = generation;
            this.bestChromosome = chromosome;
            this.graph = graph;
            this.cycles = cycles;
            this.history = history;
            this.domainTag = domainTag;
        }
    }

    public SnapshotScheduler(EvolutionConfig config, SnapshotSerializer serializer) {
        this.config = config;
        this.serializer = serializer;
        this.requestQueue = new LinkedBlockingQueue<>();
    }

    /**
     * Start the snapshot scheduler thread.
     */
    public void start() {
        schedulerThread = Thread.ofVirtual().name("snapshot-scheduler").start(this);
    }

    /**
     * Request a snapshot to be written (non-blocking).
     * Called by EvolutionaryEdgeFinder on the evolution thread.
     */
    public void requestSnapshot(long generation, Chromosome best,
                               TransitionGraph graph, List<CyclePath> cycles,
                               List<mrc.evolution.GenerationResult> history) {
        try {
            requestQueue.put(new SnapshotRequest(generation, best, graph, cycles, history, null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Request a snapshot with a domain tag.
     */
    public void requestSnapshot(long generation, Chromosome best,
                               TransitionGraph graph, List<CyclePath> cycles,
                               List<mrc.evolution.GenerationResult> history, String domainTag) {
        try {
            requestQueue.put(new SnapshotRequest(generation, best, graph, cycles, history, domainTag));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stop the scheduler (drains queue first).
     */
    public void stop() {
        running = false;
        if (schedulerThread != null) {
            try {
                schedulerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Main scheduler thread loop.
     */
    @Override
    public void run() {
        while (running) {
            try {
                SnapshotRequest request = requestQueue.poll(1, TimeUnit.SECONDS);
                if (request != null) {
                    writeSnapshot(request);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Drain remaining requests on shutdown
        SnapshotRequest request;
        while ((request = requestQueue.poll()) != null) {
            try {
                writeSnapshot(request);
            } catch (Exception e) {
                System.err.println("Failed to write snapshot: " + e.getMessage());
            }
        }
    }

    /**
     * Write a single snapshot to disk.
     */
    private void writeSnapshot(SnapshotRequest request) {
        try {
            // Ensure output directory exists
            Path outputDir = config.snapshotOutputDir();
            if (outputDir != null) {
                Files.createDirectories(outputDir);

                // Generate filename with timestamp
                String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                        .replace(":", "-").replace(".", "-");
                String filename = String.format("mrc2_gen%d_%s.snap",
                        request.generation, timestamp);
                Path outputPath = outputDir.resolve(filename);

                // Serialize snapshot
                serializer.serialize(outputPath,
                        request.generation,
                        request.graph,
                        request.cycles,
                        request.bestChromosome,
                        request.history,
                        request.domainTag);

                System.out.println("Snapshot written: " + outputPath.getFileName());
            }
        } catch (IOException | MrcSnapshotException e) {
            System.err.println("Failed to write snapshot for generation " + request.generation + ": " + e.getMessage());
        }
    }
}
