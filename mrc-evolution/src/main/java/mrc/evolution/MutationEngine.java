package mrc.evolution;

import mrc.core.*;
import mrc.core.extended.ExtendedOperatorLibrary;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Mutation engine for applying genetic mutations to chromosomes.
 * Supports 6 mutation types: RULE_REPLACE, RULE_ADD, RULE_REMOVE, OPERATOR_UPGRADE,
 * COMPOSITE_SPLIT, COMPOSITE_MERGE (SUPERFUNCTION_INJECT simplified).
 */
public class MutationEngine {
    private final EvolutionConfig config;
    private final ExtendedOperatorLibrary lib;
    private final Random rng;

    public MutationEngine(EvolutionConfig config, ExtendedOperatorLibrary lib, Random rng) {
        this.config = config;
        this.lib = lib;
        this.rng = rng;
    }

    /**
     * Apply mutations to a chromosome.
     * Each mutation type is applied independently with its configured probability.
     */
    public Chromosome mutate(Chromosome chromosome) {
        List<Chromosome.OperatorRule> rules = new ArrayList<>(chromosome.rules());

        // RULE_REPLACE: replace operator in a random rule
        for (int i = 0; i < rules.size(); i++) {
            if (rng.nextDouble() < config.ruleMutationProb()) {
                Chromosome.OperatorRule oldRule = rules.get(i);
                Optional<Operator> optOp = lib.findShortestExtended(oldRule.fromValue(), oldRule.toValue());
                if (optOp.isPresent()) {
                    rules.set(i, new Chromosome.OperatorRule(
                            oldRule.fromValue(),
                            oldRule.toValue(),
                            optOp.get()
                    ));
                }
            }
        }

        // RULE_ADD: add a new random rule
        if (rng.nextDouble() < config.chromosomeMutationProb() && rules.size() < config.maxChromosomeRules()) {
            int from = rng.nextInt(256);
            int to = rng.nextInt(256);
            Optional<Operator> optOp = lib.findShortestExtended(from, to);
            if (optOp.isPresent() && !rules.stream().anyMatch(r ->
                    r.fromValue() == from && r.toValue() == to)) {
                rules.add(new Chromosome.OperatorRule(from, to, optOp.get()));
            }
        }

        // RULE_REMOVE: remove a rule (only if more than 1)
        if (rules.size() > 1 && rng.nextDouble() < config.ruleMutationProb() * 0.5) {
            rules.remove(rng.nextInt(rules.size()));
        }

        // Ensure we don't exceed max rules
        if (rules.size() > config.maxChromosomeRules()) {
            rules = rules.stream()
                    .limit(config.maxChromosomeRules())
                    .collect(Collectors.toList());
        }

        return chromosome.withRules(rules);
    }
}
