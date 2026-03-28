package mrc.graph;

/**
 * A detected arithmetic run pattern: consecutive values that differ by a constant step (mod 256).
 *
 * Covers neighbour sequences (+1, -1, +2 ...), self-repeats (step=0),
 * and any arithmetic progression (1-2-3-4, 1-3-5-7, etc.).
 *
 * step is stored unsigned (0..255); step 0 = repeat, step 128 = -128 signed, etc.
 */
public record ArithmeticPattern(
        int step,                  // 0..255 unsigned byte step (mod-256)
        long totalRunBytes,        // total input bytes covered by qualifying runs
        long runCount,             // number of distinct qualifying runs
        long estimatedSavingBits   // estimated bit savings vs all-LITERAL encoding
) {
    /** step interpreted as a signed byte (-128..127). */
    public int signedStep() {
        return step > 127 ? step - 256 : step;
    }

    public String label() {
        int s = signedStep();
        return switch (s) {
            case 0  -> "repeat(0)";
            case 1  -> "seq(+1)";
            case -1 -> "seq(-1)";
            default -> "arith(" + (s > 0 ? "+" : "") + s + ")";
        };
    }
}
