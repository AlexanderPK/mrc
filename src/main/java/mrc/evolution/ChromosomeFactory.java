package mrc.evolution;

import mrc.core.*;
import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.graph.TransitionGraph;
import java.util.*;

/**
 * Factory for creating, validating, and seeding chromosomes.
 */
public class ChromosomeFactory {
    private final ExtendedOperatorLibrary lib;
    private final Random rng;

    public ChromosomeFactory(ExtendedOperatorLibrary lib, Random rng) {
        this.lib = lib;
        this.rng = rng;
    }

    /**
     * Create a random chromosome with the given number of rules.
     * Each rule is a random (from, to) pair with a matching operator from the library.
     */
    public Chromosome createRandom(int ruleCount) {
        List<Chromosome.OperatorRule> rules = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();

        while (rules.size() < ruleCount && seenPairs.size() < 256 * 256) {
            int from = rng.nextInt(256);
            int to = rng.nextInt(256);
            String pair = from + ":" + to;

            if (!seenPairs.contains(pair)) {
                seenPairs.add(pair);
                Optional<Operator> optOp = lib.findShortestExtended(from, to);
                if (optOp.isPresent()) {
                    rules.add(new Chromosome.OperatorRule(from, to, optOp.get()));
                }
            }
        }

        return new Chromosome(rules, 0, 0.0, Chromosome.generateId());
    }

    /**
     * Seed a chromosome from a TransitionGraph's top edges.
     * Uses the best operators already found by the graph for initialization.
     */
    public Chromosome createFromGraph(TransitionGraph graph, int topK) {
        List<Chromosome.OperatorRule> rules = new ArrayList<>();
        Map<String, Operator> topEdges = new HashMap<>();

        // Collect top-K edges from graph (implementation depends on graph API)
        // For now, we'll use a simple heuristic: iterate through possible transitions
        for (int from = 0; from < 256 && rules.size() < topK; from++) {
            for (int to = 0; to < 256 && rules.size() < topK; to++) {
                Optional<Operator> optOp = lib.findShortestExtended(from, to);
                if (optOp.isPresent()) {
                    rules.add(new Chromosome.OperatorRule(from, to, optOp.get()));
                }
            }
        }

        return new Chromosome(rules, 0, 0.0, Chromosome.generateId());
    }

    /**
     * Validate that all rules in a chromosome are correct: op.apply(from) == to.
     */
    public boolean isValid(Chromosome chromosome) {
        for (Chromosome.OperatorRule rule : chromosome.rules()) {
            int result = rule.assignedOperator().apply(rule.fromValue()) & 0xFF;
            if (result != rule.toValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Repair invalid rules by replacing them with valid operators.
     */
    public Chromosome repairInvalid(Chromosome chromosome) {
        List<Chromosome.OperatorRule> repaired = new ArrayList<>();
        for (Chromosome.OperatorRule rule : chromosome.rules()) {
            int result = rule.assignedOperator().apply(rule.fromValue()) & 0xFF;
            if (result == rule.toValue()) {
                repaired.add(rule);
            } else {
                // Find a valid replacement operator
                Optional<Operator> optOp = lib.findShortestExtended(rule.fromValue(), rule.toValue());
                if (optOp.isPresent()) {
                    repaired.add(new Chromosome.OperatorRule(rule.fromValue(), rule.toValue(), optOp.get()));
                }
            }
        }
        return chromosome.withRules(repaired);
    }
}
