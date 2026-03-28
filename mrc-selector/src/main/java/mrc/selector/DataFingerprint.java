package mrc.selector;

/**
 * A 12-element feature vector characterising a byte sequence.
 * Used for snapshot selection via cosine similarity.
 *
 * <p>Features (indices 0–11):
 * <ol>
 *   <li>Shannon entropy (0–8 bits)</li>
 *   <li>Arithmetic-run density (fraction of consecutive equal-delta pairs)</li>
 *   <li>Cycle density (fraction of pairs whose delta repeats within a window)</li>
 *   <li>Delta histogram bucket 0  (|Δ| in [  0,  31])</li>
 *   <li>Delta histogram bucket 1  (|Δ| in [ 32,  63])</li>
 *   <li>Delta histogram bucket 2  (|Δ| in [ 64,  95])</li>
 *   <li>Delta histogram bucket 3  (|Δ| in [ 96, 127])</li>
 *   <li>Delta histogram bucket 4  (|Δ| in [128, 159])</li>
 *   <li>Delta histogram bucket 5  (|Δ| in [160, 191])</li>
 *   <li>Delta histogram bucket 6  (|Δ| in [192, 223])</li>
 *   <li>Delta histogram bucket 7  (|Δ| in [224, 255])</li>
 *   <li>Average absolute delta (0–255, normalised to 0–1)</li>
 * </ol>
 */
public record DataFingerprint(double[] features) {

    public static final int SIZE = 12;

    /** Compute a fingerprint from a raw byte array. */
    public static DataFingerprint of(byte[] data) {
        if (data == null || data.length == 0) {
            return new DataFingerprint(new double[SIZE]);
        }

        double[] f = new double[SIZE];
        int n = data.length;

        // ── Feature 0: Shannon entropy ─────────────────────────────────────
        int[] freq = new int[256];
        for (byte b : data) freq[b & 0xFF]++;
        double entropy = 0.0;
        for (int c : freq) {
            if (c > 0) {
                double p = (double) c / n;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        f[0] = entropy / 8.0; // normalise to [0, 1]

        if (n < 2) {
            return new DataFingerprint(f);
        }

        // Compute absolute deltas
        int[] deltas = new int[n - 1];
        long deltaSum = 0;
        for (int i = 0; i < n - 1; i++) {
            deltas[i] = Math.abs((data[i + 1] & 0xFF) - (data[i] & 0xFF));
            deltaSum += deltas[i];
        }

        // ── Feature 1: Arithmetic-run density ─────────────────────────────
        // Count consecutive pairs where delta[i] == delta[i-1]
        int arithPairs = 0;
        for (int i = 1; i < deltas.length; i++) {
            if (deltas[i] == deltas[i - 1]) arithPairs++;
        }
        f[1] = (deltas.length > 1) ? (double) arithPairs / (deltas.length - 1) : 0.0;

        // ── Feature 2: Cycle density (repeating delta within window of 8) ─
        int cyclePairs = 0;
        int window = Math.min(8, deltas.length);
        for (int i = window; i < deltas.length; i++) {
            for (int w = 1; w <= window; w++) {
                if (deltas[i] == deltas[i - w]) {
                    cyclePairs++;
                    break;
                }
            }
        }
        f[2] = (deltas.length > window) ? (double) cyclePairs / (deltas.length - window) : 0.0;

        // ── Features 3–10: Delta histogram (8 buckets of width 32) ─────────
        int[] buckets = new int[8];
        for (int d : deltas) buckets[Math.min(d / 32, 7)]++;
        for (int i = 0; i < 8; i++) {
            f[3 + i] = (double) buckets[i] / deltas.length;
        }

        // ── Feature 11: Average absolute delta (normalised) ────────────────
        f[11] = (double) deltaSum / deltas.length / 255.0;

        return new DataFingerprint(f);
    }

    /**
     * Cosine similarity between this fingerprint and another (range [0, 1]).
     * Returns 0 if either vector is all-zeros.
     */
    public double cosineSimilarity(DataFingerprint other) {
        double dot = 0, magA = 0, magB = 0;
        for (int i = 0; i < SIZE; i++) {
            dot  += features[i] * other.features[i];
            magA += features[i] * features[i];
            magB += other.features[i] * other.features[i];
        }
        if (magA == 0 || magB == 0) return 0.0;
        return dot / (Math.sqrt(magA) * Math.sqrt(magB));
    }
}
