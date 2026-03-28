package mrc.evolution;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.graph.TransitionGraph;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The main genetic algorithm that evolves operator rule sets to maximize compression.
 * Runs as a background thread, continuously improving the best solution.
 *
 * The main codec thread reads the current best chromosome via getCurrentBest().
 */
public class EvolutionaryEdgeFinder implements Runnable {
    private final EvolutionConfig config;
    private final ExtendedOperatorLibrary lib;
    private final FitnessEvaluator fitnessEvaluator;
    private final SelectionStrategy selectionStrategy;
    private final CrossoverEngine crossoverEngine;
    private final MutationEngine mutationEngine;
    private final EvolutionMonitor monitor;
    private final ChromosomeFactory factory;

    private volatile boolean running = true;
    private final AtomicReference<Chromosome> bestChromosome = new AtomicReference<>();
    private final List<Chromosome> population = Collections.synchronizedList(new ArrayList<>());
    private final CircularDataBuffer corpus;
    private final Random rng;
    private long generationCount = 0;
    private GenerationResult lastResult;

    public EvolutionaryEdgeFinder(
            EvolutionConfig config,
            ExtendedOperatorLibrary lib,
            TransitionGraph seedGraph,
            EvolutionMonitor monitor
    ) {
        this(config, lib, seedGraph, monitor, new Random());
    }

    public EvolutionaryEdgeFinder(
            EvolutionConfig config,
            ExtendedOperatorLibrary lib,
            TransitionGraph seedGraph,
            EvolutionMonitor monitor,
            Random rng
    ) {
        this.config = config;
        this.lib = lib;
        this.monitor = monitor;
        this.rng = rng;
        this.corpus = new CircularDataBuffer(config.corpusWindowSize());
        this.fitnessEvaluator = new FitnessEvaluator(lib, null);
        this.factory = new ChromosomeFactory(lib, rng);

        // Initialize selection strategy
        this.selectionStrategy = new SelectionStrategy.EliteSelection(
                config.eliteCount(),
                new SelectionStrategy.TournamentSelection(config.tournamentSize())
        );

        this.crossoverEngine = new CrossoverEngine(config, rng);
        this.mutationEngine = new MutationEngine(config, lib, rng);

        // Initialize population
        initializePopulation();
    }

    private void initializePopulation() {
        for (int i = 0; i < config.populationSize(); i++) {
            Chromosome c = factory.createRandom(rng.nextInt(50) + 10); // 10-60 rules
            if (factory.isValid(c)) {
                population.add(c);
            }
        }

        // Ensure minimum population
        while (population.size() < config.populationSize()) {
            Chromosome c = factory.createRandom(20);
            if (factory.isValid(c)) {
                population.add(c);
            }
        }

        if (!population.isEmpty()) {
            bestChromosome.set(population.get(0));
        }
    }

    /**
     * Main evolution loop.
     * Runs indefinitely until stop() is called.
     */
    @Override
    public void run() {
        while (running) {
            try {
                // 1. Get current corpus snapshot
                byte[] corpusSnapshot = corpus.snapshot();
                if (corpusSnapshot.length < 256) {
                    // Wait for more data
                    Thread.sleep(100);
                    continue;
                }

                // 2. Evaluate population fitness
                Map<Chromosome, Double> fitnesses = evaluatePopulation(corpusSnapshot);

                // 3. Update fitness in population
                synchronized (population) {
                    for (Chromosome c : population) {
                        Double fitness = fitnesses.get(c);
                        if (fitness != null) {
                            int idx = population.indexOf(c);
                            if (idx >= 0) {
                                population.set(idx, c.withFitness(fitness));
                            }
                        }
                    }
                }

                // 4. Sort by fitness
                synchronized (population) {
                    population.sort((a, b) -> Double.compare(b.fitness(), a.fitness()));
                }

                // 5. Update best
                if (!population.isEmpty()) {
                    Chromosome best = population.get(0);
                    bestChromosome.set(best);
                }

                // 6. Selection and reproduction
                List<Chromosome> elite = selectionStrategy.select(population,
                        config.eliteCount(), rng);
                List<Chromosome> parents = selectionStrategy.select(population,
                        config.populationSize() - config.eliteCount(), rng);

                List<Chromosome> offspring = new ArrayList<>(elite);
                for (int i = 0; i < parents.size(); i += 2) {
                    if (i + 1 < parents.size()) {
                        Pair<Chromosome, Chromosome> pair = crossoverEngine.crossover(
                                parents.get(i), parents.get(i + 1));
                        offspring.add(mutationEngine.mutate(pair.first()));
                        offspring.add(mutationEngine.mutate(pair.second()));
                    } else if (i < parents.size()) {
                        offspring.add(mutationEngine.mutate(parents.get(i)));
                    }
                }

                // 7. Replace population
                synchronized (population) {
                    population.clear();
                    population.addAll(offspring.stream()
                            .limit(config.populationSize())
                            .toList());
                }

                generationCount++;

                // 8. Record statistics
                if (monitor != null && !population.isEmpty()) {
                    monitor.record(generationCount, bestChromosome.get(),
                            new ArrayList<>(population), corpus.size());
                }

                lastResult = GenerationResult.of(generationCount,
                        bestChromosome.get(),
                        new ArrayList<>(population),
                        corpus.size(),
                        java.time.Instant.now());

                // 9. Check generation limit
                if (config.maxGenerations() > 0 && generationCount >= config.maxGenerations()) {
                    running = false;
                }

                // 10. Throttle if configured
                if (config.generationDelayMs() > 0) {
                    Thread.sleep(config.generationDelayMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private Map<Chromosome, Double> evaluatePopulation(byte[] corpus) {
        synchronized (population) {
            return fitnessEvaluator.evaluatePopulation(new ArrayList<>(population), corpus);
        }
    }

    /**
     * Feed new data to the evolving corpus buffer.
     * Called by the main codec thread as data is processed.
     */
    public void feedData(byte[] newData) {
        corpus.add(newData);
    }

    /**
     * Get the current best chromosome (thread-safe).
     */
    public Chromosome getCurrentBest() {
        return bestChromosome.get();
    }

    /**
     * Stop the evolution loop.
     */
    public void stop() {
        running = false;
    }

    /**
     * Get the last generation result.
     */
    public GenerationResult getLastGenerationResult() {
        return lastResult;
    }

    /**
     * Get current generation count.
     */
    public long getGenerationCount() {
        return generationCount;
    }
}
