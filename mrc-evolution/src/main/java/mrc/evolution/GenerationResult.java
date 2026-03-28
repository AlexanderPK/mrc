package mrc.evolution;

import java.io.PrintStream;
import java.time.Instant;
import java.util.DoubleSummaryStatistics;

/**
 * Record capturing statistics from a single generation of evolution.
 */
public record GenerationResult(
        long generation,
        Chromosome best,
        double bestFitness,
        double averageFitness,
        double fitnessVariance,
        int populationSize,
        int uniqueOperatorsUsed,
        long corpusBytesProcessed,
        Instant timestamp
) {
    /**
     * Print a one-line summary of this generation result.
     */
    public void printSummary(PrintStream out) {
        out.printf("Gen %5d | best=%.3f | avg=%.3f | var=%.4f | ops=%3d | corpus=%dB%n",
                generation,
                bestFitness,
                averageFitness,
                fitnessVariance,
                uniqueOperatorsUsed,
                corpusBytesProcessed
        );
    }

    /**
     * Create a GenerationResult from population statistics.
     */
    public static GenerationResult of(
            long generation,
            Chromosome best,
            java.util.List<Chromosome> population,
            long corpusBytesProcessed,
            Instant timestamp
    ) {
        double[] fitnesses = population.stream()
                .mapToDouble(Chromosome::fitness)
                .toArray();

        DoubleSummaryStatistics stats = java.util.Arrays.stream(fitnesses)
                .summaryStatistics();

        double variance = 0.0;
        if (fitnesses.length > 1) {
            double avg = stats.getAverage();
            double sumSq = java.util.Arrays.stream(fitnesses)
                    .map(f -> (f - avg) * (f - avg))
                    .sum();
            variance = sumSq / fitnesses.length;
        }

        int uniqueOps = (int) population.stream()
                .flatMap(c -> c.rules().stream())
                .map(r -> r.assignedOperator())
                .distinct()
                .count();

        return new GenerationResult(
                generation,
                best,
                best.fitness(),
                stats.getAverage(),
                variance,
                population.size(),
                uniqueOps,
                corpusBytesProcessed,
                timestamp
        );
    }
}
