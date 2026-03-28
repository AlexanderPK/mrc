package mrc.evolution;

import mrc.codec.CompressionResult;
import mrc.codec.MrcEncoder;
import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.core.extended.OperatorCostModel;
import mrc.graph.TransitionGraph;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Evaluates the fitness (compression performance) of chromosomes.
 *
 * Fitness is computed as: compression_ratio = 1.0 - (compressedBits / originalBits)
 * A parsimony penalty is applied to prefer smaller chromosomes when fitness is similar.
 */
public class FitnessEvaluator {
    private final ExtendedOperatorLibrary lib;
    private final OperatorCostModel costModel;

    public FitnessEvaluator(ExtendedOperatorLibrary lib, OperatorCostModel costModel) {
        this.lib = lib;
        this.costModel = costModel;
    }

    /**
     * Evaluate a single chromosome's fitness on a corpus of data.
     * Steps:
     * 1. Build a temporary TransitionGraph from the corpus
     * 2. Override edges with chromosome's OperatorRules
     * 3. Encode the corpus
     * 4. Return fitness = spaceSaving - parsimony_penalty * chromosome.size()
     */
    public double evaluate(Chromosome chromosome, byte[] corpus) {
        if (corpus.length == 0) {
            return 0.0;
        }

        try {
            // Build graph from corpus
            TransitionGraph graph = new TransitionGraph();
            graph.observe(corpus);

            // Override edges with chromosome rules
            for (Chromosome.OperatorRule rule : chromosome.rules()) {
                graph.setEdge(rule.fromValue(), rule.toValue(), rule.assignedOperator());
            }

            // Encode using the graph (with overrides, if implemented)
            MrcEncoder encoder = new MrcEncoder(graph, new ArrayList<>());
            CompressionResult result = encoder.encode(corpus);

            double spaceSaving = 1.0 - result.ratio();
            double parsimonyPenalty = 0.001 * chromosome.size();
            return Math.max(0.0, spaceSaving - parsimonyPenalty);
        } catch (Exception e) {
            // Invalid chromosome
            return 0.0;
        }
    }

    /**
     * Evaluate multiple chromosomes in parallel using ForkJoinPool.
     */
    public Map<Chromosome, Double> evaluatePopulation(List<Chromosome> population, byte[] corpus) {
        if (population.isEmpty() || corpus.length == 0) {
            return new HashMap<>();
        }

        ForkJoinPool pool = ForkJoinPool.commonPool();
        return population.parallelStream()
                .collect(Collectors.toMap(
                        c -> c,
                        c -> evaluate(c, corpus),
                        (a, b) -> a,
                        HashMap::new
                ));
    }

    /**
     * Evaluate a chromosome on multiple corpora, weighted by size.
     */
    public double evaluateBatch(Chromosome chromosome, List<byte[]> corpora) {
        if (corpora.isEmpty()) {
            return 0.0;
        }

        double totalFitness = 0.0;
        long totalBytes = 0;

        for (byte[] corpus : corpora) {
            double fitness = evaluate(chromosome, corpus);
            totalFitness += fitness * corpus.length;
            totalBytes += corpus.length;
        }

        return totalBytes > 0 ? totalFitness / totalBytes : 0.0;
    }
}
