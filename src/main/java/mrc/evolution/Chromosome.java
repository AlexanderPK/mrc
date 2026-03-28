package mrc.evolution;

import mrc.core.Operator;
import java.io.*;
import java.util.*;

/**
 * A chromosome in the genetic algorithm: an ordered list of operator assignment rules
 * that can override default operator selections in the TransitionGraph.
 *
 * Each OperatorRule specifies: "when transitioning from fromValue to toValue, use this Operator".
 * Chromosomes are evaluated by fitness (compression gain) and evolved through mutation, crossover.
 */
public record Chromosome(
        List<OperatorRule> rules,
        int generation,
        double fitness,
        String id
) {
    /**
     * An operator assignment rule: when transitioning from → to, use this operator.
     */
    public record OperatorRule(int fromValue, int toValue, Operator assignedOperator) {
        public OperatorRule {
            if ((fromValue & 0xFF) != fromValue || (toValue & 0xFF) != toValue) {
                throw new IllegalArgumentException("Values must be 0-255");
            }
        }

        public byte[] toBytes() {
            byte[] data = new byte[4];
            data[0] = (byte) fromValue;
            data[1] = (byte) toValue;
            byte opId = (byte) mrc.core.OpIdMap.getOpId(assignedOperator);
            data[2] = opId;
            data[3] = 0; // reserved
            return data;
        }
    }

    public Chromosome {
        // Defensive copy of rules list
        rules = new ArrayList<>(rules);
    }

    /**
     * Number of operator rules in this chromosome.
     */
    public int size() {
        return rules.size();
    }

    /**
     * Return a new chromosome with updated fitness.
     */
    public Chromosome withFitness(double newFitness) {
        return new Chromosome(rules, generation, newFitness, id);
    }

    /**
     * Return a new chromosome with updated generation number.
     */
    public Chromosome withGeneration(int newGen) {
        return new Chromosome(rules, newGen, fitness, id);
    }

    /**
     * Return a new chromosome with updated rules.
     */
    public Chromosome withRules(List<OperatorRule> newRules) {
        return new Chromosome(newRules, generation, fitness, id);
    }

    /**
     * Check if this chromosome dominates another (higher fitness).
     */
    public boolean dominates(Chromosome other) {
        return this.fitness > other.fitness;
    }

    /**
     * Serialize chromosome to bytes.
     * Format: 4-byte size + for each rule: (1 byte from + 1 byte to + 1 byte opId + 1 byte reserved)
     */
    public byte[] toBytes() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeInt(rules.size());
            for (OperatorRule rule : rules) {
                dos.writeByte(rule.fromValue);
                dos.writeByte(rule.toValue);
                dos.writeByte(mrc.core.OpIdMap.getOpId(rule.assignedOperator));
                dos.writeByte(0); // reserved
            }
            dos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize chromosome", e);
        }
    }

    /**
     * Deserialize chromosome from bytes.
     */
    public static Chromosome fromBytes(byte[] data, int generation, mrc.core.OperatorLibrary lib) {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        try {
            int ruleCount = dis.readInt();
            List<OperatorRule> rules = new ArrayList<>();
            for (int i = 0; i < ruleCount; i++) {
                int from = dis.readUnsignedByte();
                int to = dis.readUnsignedByte();
                byte opId = dis.readByte();
                dis.readByte(); // reserved
                Operator op = lib.createOperator(opId, 0);
                if (op != null) {
                    rules.add(new OperatorRule(from, to, op));
                }
            }
            dis.close();
            return new Chromosome(rules, generation, 0.0, UUID.randomUUID().toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize chromosome", e);
        }
    }

    /**
     * Generate a unique ID for this chromosome.
     */
    public static String generateId() {
        return UUID.randomUUID().toString();
    }
}
