package mrc.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Detects arithmetic run patterns in a byte stream.
 *
 * Scans for consecutive runs where each byte differs from the previous by a
 * constant step (mod 256). Covers:
 * - step=0: repeated byte (e.g. 0x41 0x41 0x41 ...)
 * - step=1: ascending sequence (e.g. 1 2 3 4 ...)
 * - step=255: descending sequence (e.g. 10 9 8 7 ...)
 * - step=N: any arithmetic progression
 *
 * A run of length L bytes can be encoded as a single ARITH_RUN token (33 bits)
 * instead of L LITERAL tokens (9 bits each). Break-even at L = 4.
 */
public class SequenceDetector {

    /** Minimum run length (bytes) for an ARITH_RUN token to save bits. */
    public static final int MIN_RUN = 4;

    /** Bits per ARITH_RUN token: 1 (flag) + 8 (stepIdx) + 8 (startVal) + 16 (runLen). */
    public static final int ARITH_RUN_BITS = 33;

    /** Bits per LITERAL token: 1 (flag) + 8 (value). */
    private static final int LITERAL_BITS = 9;

    /**
     * Scan the input data and return an ArithmeticPattern for every step value
     * that has at least one qualifying run (length >= MIN_RUN), sorted by
     * estimatedSavingBits descending.
     *
     * @param data the input bytes to analyse
     * @return list of detected patterns, sorted by savings (best first)
     */
    public List<ArithmeticPattern> detect(byte[] data) {
        long[] totalRunBytes = new long[256];
        long[] runCount      = new long[256];

        int n = data.length;
        int i = 0;

        while (i < n) {
            if (i + 1 >= n) {
                // Last byte — no step to compute; treat as length-1 "run" and stop
                i++;
                break;
            }

            // Determine step from first pair
            int step = ((data[i + 1] & 0xFF) - (data[i] & 0xFF)) & 0xFF;

            // Extend run as far as the step holds
            int j = i + 1;
            while (j + 1 < n && (((data[j + 1] & 0xFF) - (data[j] & 0xFF)) & 0xFF) == step) {
                j++;
            }

            int runLen = j - i + 1; // inclusive: bytes i..j

            if (runLen >= MIN_RUN) {
                // Count how many ARITH_RUN tokens this run needs (split at 65535)
                long tokens = (runLen + 65534) / 65535;
                totalRunBytes[step] += runLen;
                runCount[step]      += tokens;
            }

            // Advance past the entire run (non-overlapping scan)
            i = j + 1;
        }

        // Build ArithmeticPattern list for steps with positive savings
        List<ArithmeticPattern> patterns = new ArrayList<>();
        for (int s = 0; s < 256; s++) {
            if (totalRunBytes[s] == 0) continue;
            long savedBits = totalRunBytes[s] * LITERAL_BITS - runCount[s] * ARITH_RUN_BITS;
            if (savedBits > 0) {
                patterns.add(new ArithmeticPattern(s, totalRunBytes[s], runCount[s], savedBits));
            }
        }

        patterns.sort((a, b) -> Long.compare(b.estimatedSavingBits(), a.estimatedSavingBits()));
        return patterns;
    }

    /**
     * Return the top-K patterns by estimated saving bits.
     *
     * @param data the input bytes to analyse
     * @param k    the maximum number of patterns to return
     * @return up to k patterns, best first
     */
    public List<ArithmeticPattern> topPatterns(byte[] data, int k) {
        List<ArithmeticPattern> all = detect(data);
        if (all.size() <= k) return all;

        PriorityQueue<ArithmeticPattern> heap = new PriorityQueue<>(k,
                (a, b) -> Long.compare(a.estimatedSavingBits(), b.estimatedSavingBits()));
        for (ArithmeticPattern p : all) {
            if (heap.size() < k) {
                heap.offer(p);
            } else if (p.estimatedSavingBits() > heap.peek().estimatedSavingBits()) {
                heap.poll();
                heap.offer(p);
            }
        }

        List<ArithmeticPattern> result = new ArrayList<>(heap);
        result.sort((a, b) -> Long.compare(b.estimatedSavingBits(), a.estimatedSavingBits()));
        return result;
    }
}
