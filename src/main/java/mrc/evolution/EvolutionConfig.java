package mrc.evolution;

import java.nio.file.Path;

/**
 * Configuration record for the evolutionary edge-finder genetic algorithm.
 * Controls population size, mutation rates, crossover strategy, and other GA parameters.
 */
public record EvolutionConfig(
        int populationSize,
        int maxGenerations,
        int corpusWindowSize,
        int eliteCount,
        int tournamentSize,
        CrossoverStrategy crossoverStrategy,
        double ruleMutationProb,
        double chromosomeMutationProb,
        int snapshotIntervalGenerations,
        long generationDelayMs,
        int maxChromosomeRules,
        boolean parallelFitness,
        Path snapshotOutputDir
) {
    /**
     * Crossover strategy enum.
     */
    public enum CrossoverStrategy {
        SINGLE_POINT,
        TWO_POINT,
        UNIFORM,
        OPERATOR_AWARE // Recommended default
    }

    /**
     * Mutation type enum for tracking mutation operations.
     */
    public enum MutationType {
        RULE_REPLACE,
        RULE_ADD,
        RULE_REMOVE,
        OPERATOR_UPGRADE,
        COMPOSITE_SPLIT,
        COMPOSITE_MERGE,
        SUPERFUNCTION_INJECT
    }

    public EvolutionConfig {
        if (populationSize < 4) throw new IllegalArgumentException("populationSize must be >= 4");
        if (corpusWindowSize < 256) throw new IllegalArgumentException("corpusWindowSize must be >= 256");
        if (eliteCount < 1) throw new IllegalArgumentException("eliteCount must be >= 1");
        if (tournamentSize < 2) throw new IllegalArgumentException("tournamentSize must be >= 2");
    }

    /**
     * Default configuration: balanced for typical use.
     */
    public static EvolutionConfig defaultConfig(Path snapshotDir) {
        return new EvolutionConfig(
                100,                                    // populationSize
                0,                                      // maxGenerations (infinite)
                65536,                                  // corpusWindowSize (64 KB)
                2,                                      // eliteCount
                5,                                      // tournamentSize
                CrossoverStrategy.OPERATOR_AWARE,
                0.05,                                   // ruleMutationProb
                0.02,                                   // chromosomeMutationProb
                100,                                    // snapshotIntervalGenerations
                0L,                                     // generationDelayMs (run fast)
                1024,                                   // maxChromosomeRules
                true,                                   // parallelFitness
                snapshotDir
        );
    }

    /**
     * Fast configuration: small population, quick testing.
     */
    public static EvolutionConfig fastConfig(Path snapshotDir) {
        return new EvolutionConfig(
                20,                                     // populationSize
                100,                                    // maxGenerations
                8192,                                   // corpusWindowSize (8 KB)
                1,                                      // eliteCount
                3,                                      // tournamentSize
                CrossoverStrategy.OPERATOR_AWARE,
                0.1,                                    // ruleMutationProb (higher mutation)
                0.05,                                   // chromosomeMutationProb
                50,                                     // snapshotIntervalGenerations
                0L,                                     // generationDelayMs
                256,                                    // maxChromosomeRules
                false,                                  // parallelFitness (not worth it for small pop)
                snapshotDir
        );
    }

    /**
     * Thorough configuration: large population, long run.
     */
    public static EvolutionConfig thoroughConfig(Path snapshotDir) {
        return new EvolutionConfig(
                500,                                    // populationSize
                0,                                      // maxGenerations (infinite)
                262144,                                 // corpusWindowSize (256 KB)
                5,                                      // eliteCount
                10,                                     // tournamentSize
                CrossoverStrategy.OPERATOR_AWARE,
                0.03,                                   // ruleMutationProb (lower mutation)
                0.01,                                   // chromosomeMutationProb
                50,                                     // snapshotIntervalGenerations
                10L,                                    // generationDelayMs (throttle)
                2048,                                   // maxChromosomeRules
                true,                                   // parallelFitness
                snapshotDir
        );
    }
}
