package mrc.evolution;

import java.util.*;

/**
 * Crossover engine that creates offspring from parent chromosomes.
 * Supports multiple crossover strategies: SINGLE_POINT, TWO_POINT, UNIFORM, OPERATOR_AWARE.
 */
public class CrossoverEngine {
    private final EvolutionConfig config;
    private final Random rng;

    public CrossoverEngine(EvolutionConfig config, Random rng) {
        this.config = config;
        this.rng = rng;
    }

    /**
     * Create a pair of offspring from two parents using the configured crossover strategy.
     */
    public Pair<Chromosome, Chromosome> crossover(Chromosome parent1, Chromosome parent2) {
        Pair<Chromosome, Chromosome> offspring = switch (config.crossoverStrategy()) {
            case SINGLE_POINT -> singlePointCrossover(parent1, parent2);
            case TWO_POINT -> twoPointCrossover(parent1, parent2);
            case UNIFORM -> uniformCrossover(parent1, parent2);
            case OPERATOR_AWARE -> operatorAwareCrossover(parent1, parent2);
        };
        return offspring;
    }

    private Pair<Chromosome, Chromosome> singlePointCrossover(Chromosome p1, Chromosome p2) {
        int maxSize = Math.max(p1.size(), p2.size());
        if (maxSize == 0) return new Pair<>(p1, p2);

        int splitPoint = rng.nextInt(maxSize);
        List<Chromosome.OperatorRule> child1Rules = new ArrayList<>();
        List<Chromosome.OperatorRule> child2Rules = new ArrayList<>();

        // Add rules from p1 up to split point
        for (int i = 0; i < Math.min(splitPoint, p1.size()); i++) {
            child1Rules.add(p1.rules().get(i));
        }
        // Add rules from p2 from split point onward
        for (int i = splitPoint; i < p2.size(); i++) {
            child1Rules.add(p2.rules().get(i));
        }

        // Reverse for child2
        for (int i = 0; i < Math.min(splitPoint, p2.size()); i++) {
            child2Rules.add(p2.rules().get(i));
        }
        for (int i = splitPoint; i < p1.size(); i++) {
            child2Rules.add(p1.rules().get(i));
        }

        return new Pair<>(
                new Chromosome(child1Rules, 0, 0.0, Chromosome.generateId()),
                new Chromosome(child2Rules, 0, 0.0, Chromosome.generateId())
        );
    }

    private Pair<Chromosome, Chromosome> twoPointCrossover(Chromosome p1, Chromosome p2) {
        int maxSize = Math.min(Math.max(p1.size(), p2.size()), 100); // Limit to avoid long arrays
        if (maxSize < 2) return new Pair<>(p1, p2);

        int i = rng.nextInt(maxSize - 1);
        int j = i + 1 + rng.nextInt(maxSize - i - 1);

        List<Chromosome.OperatorRule> child1Rules = new ArrayList<>();
        List<Chromosome.OperatorRule> child2Rules = new ArrayList<>();

        // Child1: p1[0..i] + p2[i..j] + p1[j..]
        for (int k = 0; k < i && k < p1.size(); k++) child1Rules.add(p1.rules().get(k));
        for (int k = i; k < j && k < p2.size(); k++) child1Rules.add(p2.rules().get(k));
        for (int k = j; k < p1.size(); k++) child1Rules.add(p1.rules().get(k));

        // Child2: p2[0..i] + p1[i..j] + p2[j..]
        for (int k = 0; k < i && k < p2.size(); k++) child2Rules.add(p2.rules().get(k));
        for (int k = i; k < j && k < p1.size(); k++) child2Rules.add(p1.rules().get(k));
        for (int k = j; k < p2.size(); k++) child2Rules.add(p2.rules().get(k));

        return new Pair<>(
                new Chromosome(child1Rules, 0, 0.0, Chromosome.generateId()),
                new Chromosome(child2Rules, 0, 0.0, Chromosome.generateId())
        );
    }

    private Pair<Chromosome, Chromosome> uniformCrossover(Chromosome p1, Chromosome p2) {
        List<Chromosome.OperatorRule> child1Rules = new ArrayList<>();
        List<Chromosome.OperatorRule> child2Rules = new ArrayList<>();

        int maxSize = Math.max(p1.size(), p2.size());
        for (int i = 0; i < maxSize; i++) {
            if (rng.nextBoolean()) {
                if (i < p1.size()) child1Rules.add(p1.rules().get(i));
                if (i < p2.size()) child2Rules.add(p2.rules().get(i));
            } else {
                if (i < p2.size()) child1Rules.add(p2.rules().get(i));
                if (i < p1.size()) child2Rules.add(p1.rules().get(i));
            }
        }

        return new Pair<>(
                new Chromosome(child1Rules, 0, 0.0, Chromosome.generateId()),
                new Chromosome(child2Rules, 0, 0.0, Chromosome.generateId())
        );
    }

    /**
     * Operator-aware crossover: merge rules by (fromValue, toValue) pair,
     * keeping the cheaper operator when both parents have rules for the same transition.
     * This is the recommended default strategy.
     */
    private Pair<Chromosome, Chromosome> operatorAwareCrossover(Chromosome p1, Chromosome p2) {
        Map<String, Chromosome.OperatorRule> map1 = buildRuleMap(p1);
        Map<String, Chromosome.OperatorRule> map2 = buildRuleMap(p2);

        List<Chromosome.OperatorRule> child1Rules = new ArrayList<>();
        List<Chromosome.OperatorRule> child2Rules = new ArrayList<>();

        // Merge common keys
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(map1.keySet());
        allKeys.addAll(map2.keySet());

        for (String key : allKeys) {
            Chromosome.OperatorRule rule1 = map1.get(key);
            Chromosome.OperatorRule rule2 = map2.get(key);

            if (rule1 != null && rule2 != null) {
                // Both have this rule: pick the cheaper operator
                int cost1 = 5 + rule1.assignedOperator().operandBits();
                int cost2 = 5 + rule2.assignedOperator().operandBits();
                if (cost1 <= cost2) {
                    child1Rules.add(rule1);
                    child2Rules.add(rule2);
                } else {
                    child1Rules.add(rule2);
                    child2Rules.add(rule1);
                }
            } else if (rule1 != null && rng.nextDouble() < 0.7) {
                // Only p1 has it: include with prob 0.7
                child1Rules.add(rule1);
                if (rule2 != null) child2Rules.add(rule2);
            } else if (rule2 != null && rng.nextDouble() < 0.7) {
                // Only p2 has it: include with prob 0.7
                child2Rules.add(rule2);
                if (rule1 != null) child1Rules.add(rule1);
            }
        }

        return new Pair<>(
                new Chromosome(child1Rules, 0, 0.0, Chromosome.generateId()),
                new Chromosome(child2Rules, 0, 0.0, Chromosome.generateId())
        );
    }

    private Map<String, Chromosome.OperatorRule> buildRuleMap(Chromosome c) {
        Map<String, Chromosome.OperatorRule> map = new HashMap<>();
        for (Chromosome.OperatorRule rule : c.rules()) {
            String key = rule.fromValue() + ":" + rule.toValue();
            map.put(key, rule);
        }
        return map;
    }
}
