package mrc.evolution;

import java.io.PrintStream;
import java.time.Instant;
import java.util.*;

/**
 * Monitors evolution progress, tracks fitness history, and detects convergence.
 */
public class EvolutionMonitor {
    private final List<GenerationResult> history;
    private final Object lock = new Object();

    public EvolutionMonitor() {
        this.history = new ArrayList<>();
    }

    /**
     * Record statistics from a generation.
     *
     * @param corpusBytesProcessed the actual number of corpus bytes used for fitness evaluation
     */
    public void record(long generation, Chromosome best, java.util.List<Chromosome> population,
                       long corpusBytesProcessed) {
        GenerationResult result = GenerationResult.of(
                generation,
                best,
                population,
                corpusBytesProcessed,
                Instant.now()
        );
        synchronized (lock) {
            history.add(result);
        }
    }

    /**
     * Get the complete history of generation results.
     */
    public List<GenerationResult> history() {
        synchronized (lock) {
            return new ArrayList<>(history);
        }
    }

    /**
     * Get the latest generation result.
     */
    public GenerationResult latest() {
        synchronized (lock) {
            return history.isEmpty() ? null : history.get(history.size() - 1);
        }
    }

    /**
     * Calculate convergence rate: slope of best fitness over last 100 generations.
     * Lower slope means evolution is stalling.
     */
    public double convergenceRate() {
        synchronized (lock) {
            if (history.size() < 2) {
                return 0.0;
            }

            int window = Math.min(100, history.size());
            int start = history.size() - window;

            double x1 = start;
            double x2 = history.size() - 1;
            double y1 = history.get(start).bestFitness();
            double y2 = history.get(history.size() - 1).bestFitness();

            return (y2 - y1) / (x2 - x1);
        }
    }

    /**
     * Check if evolution has converged (stalled for 500 generations).
     * Convergence is detected when convergence rate < 0.0001 for 500 generations.
     */
    public boolean hasConverged() {
        synchronized (lock) {
            if (history.size() < 500) {
                return false;
            }

            // Check if last 500 generations have convergence rate < threshold
            int start = history.size() - 500;
            double threshold = 0.0001;

            for (int i = start; i < history.size() - 1; i++) {
                double y1 = history.get(i).bestFitness();
                double y2 = history.get(i + 1).bestFitness();
                if ((y2 - y1) >= threshold) {
                    return false; // Still improving
                }
            }
            return true;
        }
    }

    /**
     * Print an ASCII histogram of best-fitness distribution across all recorded generations.
     */
    public void printHistogram(PrintStream out) {
        List<GenerationResult> snap;
        synchronized (lock) {
            snap = new ArrayList<>(history);
        }
        if (snap.isEmpty()) {
            out.println("No data to display");
            return;
        }

        int[] buckets = new int[10];
        for (GenerationResult r : snap) {
            int idx = (int) (r.bestFitness() * 10);
            idx = Math.max(0, Math.min(9, idx));
            buckets[idx]++;
        }

        out.println("Fitness distribution (best fitness per generation):");
        for (int i = 0; i < 10; i++) {
            out.printf("  %.1f-%.1f |%s%n", i * 0.1, (i + 1) * 0.1, "#".repeat(buckets[i]));
        }
    }

    private String repeat(String s, int count) {
        return s.repeat(Math.max(0, count));
    }

    /**
     * Print the complete evolution history.
     */
    public void printHistory(PrintStream out) {
        synchronized (lock) {
            out.println("Evolution history (" + history.size() + " generations):");
            for (GenerationResult result : history) {
                result.printSummary(out);
            }
        }
    }
}
