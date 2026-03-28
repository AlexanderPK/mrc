package mrc.evolution;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Interface for selection strategies in the genetic algorithm.
 * A selection strategy picks parent chromosomes for reproduction.
 */
public interface SelectionStrategy {
    /**
     * Select the specified number of chromosomes from the population.
     */
    List<Chromosome> select(List<Chromosome> population, int count, Random rng);

    /**
     * Tournament selection: pick tournamentSize random candidates, return the fittest.
     */
    class TournamentSelection implements SelectionStrategy {
        private final int tournamentSize;

        public TournamentSelection(int tournamentSize) {
            this.tournamentSize = tournamentSize;
        }

        @Override
        public List<Chromosome> select(List<Chromosome> population, int count, Random rng) {
            List<Chromosome> selected = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Chromosome best = null;
                for (int j = 0; j < tournamentSize; j++) {
                    Chromosome candidate = population.get(rng.nextInt(population.size()));
                    if (best == null || candidate.dominates(best)) {
                        best = candidate;
                    }
                }
                if (best != null) {
                    selected.add(best);
                }
            }
            return selected;
        }
    }

    /**
     * Elite selection: always include the top-eliteCount chromosomes.
     * Combined with another strategy (like tournament) for the remainder.
     */
    class EliteSelection implements SelectionStrategy {
        private final int eliteCount;
        private final SelectionStrategy fallback;

        public EliteSelection(int eliteCount, SelectionStrategy fallback) {
            this.eliteCount = eliteCount;
            this.fallback = fallback;
        }

        @Override
        public List<Chromosome> select(List<Chromosome> population, int count, Random rng) {
            // Sort by fitness (descending)
            List<Chromosome> sorted = population.stream()
                    .sorted((a, b) -> Double.compare(b.fitness(), a.fitness()))
                    .collect(Collectors.toList());

            // Take top eliteCount
            List<Chromosome> elite = sorted.stream()
                    .limit(Math.min(eliteCount, count))
                    .collect(Collectors.toList());

            // Fill remainder with fallback strategy if needed
            if (elite.size() < count) {
                int needed = count - elite.size();
                List<Chromosome> extra = fallback.select(population, needed, rng);
                elite.addAll(extra);
            }

            return elite;
        }
    }

    /**
     * Rank selection: selection probability proportional to rank, not raw fitness.
     * Reduces dominance of a single very-fit chromosome early in evolution.
     */
    class RankSelection implements SelectionStrategy {
        @Override
        public List<Chromosome> select(List<Chromosome> population, int count, Random rng) {
            // Sort by fitness
            List<Chromosome> sorted = population.stream()
                    .sorted((a, b) -> Double.compare(b.fitness(), a.fitness()))
                    .collect(Collectors.toList());

            // Build cumulative weights: rank N gets weight N+1
            int n = sorted.size();
            double[] weights = new double[n];
            double totalWeight = 0.0;
            for (int i = 0; i < n; i++) {
                weights[i] = i + 1; // Rank from 1 to n
                totalWeight += weights[i];
            }

            // Normalize weights
            for (int i = 0; i < n; i++) {
                weights[i] /= totalWeight;
            }

            // Weighted random selection
            List<Chromosome> selected = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                double r = rng.nextDouble();
                double cumulative = 0.0;
                for (int j = 0; j < n; j++) {
                    cumulative += weights[j];
                    if (r <= cumulative) {
                        selected.add(sorted.get(j));
                        break;
                    }
                }
            }

            return selected;
        }
    }
}
