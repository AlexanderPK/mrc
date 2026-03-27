package mrc.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Record representing a centered range of 8-bit values.
 *
 * Used as a placeholder for future fuzzy-algebra layer (Phase 2).
 * In Phase 1, each cluster has radius 0 (one value per cluster).
 */
public record ValueCluster(int centroid, int radius) {

    /**
     * Check if a value falls within this cluster.
     *
     * @param value the value to test
     * @return true if |value - centroid| <= radius
     */
    public boolean contains(int value) {
        return Math.abs(value - centroid) <= radius;
    }

    /**
     * Partition the 8-bit value space (0..255) into equal-radius clusters.
     *
     * For Phase 1 with clusterCount=256, each cluster contains exactly one value.
     *
     * TODO: Generalize for clusterCount < 256 (fuzzy clustering).
     *
     * @param clusterCount the desired number of clusters
     * @return list of clusters covering 0..255
     */
    public static List<ValueCluster> partition(int clusterCount) {
        List<ValueCluster> clusters = new ArrayList<>();
        if (clusterCount <= 0 || clusterCount > 256) {
            throw new IllegalArgumentException("clusterCount must be in [1..256]");
        }

        if (clusterCount == 256) {
            // Phase 1: one cluster per value, radius 0
            for (int i = 0; i <= 255; i++) {
                clusters.add(new ValueCluster(i, 0));
            }
        } else {
            // TODO: Implement fuzzy clustering for clusterCount < 256
            // For now, fall back to one cluster per value
            for (int i = 0; i <= 255; i++) {
                clusters.add(new ValueCluster(i, 0));
            }
        }

        return clusters;
    }
}
