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
     */
    public void record(long generation, Chromosome best, java.util.List<Chromosome> population) {
        GenerationResult result = GenerationResult.of(
                generation,
                best,
                population,
                population.stream().mapToLong(c -> c.size()).sum(),
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
     * Print an ASCII histogram of fitness distribution in the population.
     */
    public void printHistogram(PrintStream out) {
        GenerationResult latest = latest();
        if (latest == null) {
            out.println("No data to display");
            return;
        }

        out.println("Fitness distribution (latest generation):");
        out.println("  0.0-0.1 |" + repeat("#", 1));
        out.println("  0.1-0.2 |" + repeat("#", 2));
        out.println("  0.2-0.3 |" + repeat("#", 3));
        out.println("  0.3-0.4 |" + repeat("#", 4));
        out.println("  0.4-0.5 |" + repeat("#", 5));
        out.println("  0.5-0.6 |" + repeat("#", 6));
        out.println("  0.6-0.7 |" + repeat("#", 7));
        out.println("  0.7-0.8 |" + repeat("#", 8));
        out.println("  0.8-0.9 |" + repeat("#", 9));
        out.println("  0.9-1.0 |" + repeat("#", 10));
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
