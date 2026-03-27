package mrc.codec;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * Record containing compression metrics and metadata.
 *
 * Holds original and compressed data, bit/byte counts, compression ratio,
 * tier usage statistics, and timing information.
 */
public record CompressionResult(
        byte[] originalData,
        byte[] compressedData,
        int originalBits,
        int compressedBits,
        double ratio,
        double spaceSaving,
        Map<EncodingTier, Long> tierUsageCounts,
        List<String> cyclesUsed,
        long encodingNanos,
        long decodingNanos
) {
    /**
     * Print a summary of the compression result.
     *
     * @param out the output stream
     */
    public void printSummary(PrintStream out) {
        out.println("=== Compression Result ===");
        out.println("Original size:    " + originalData.length + " bytes (" + originalBits + " bits)");
        out.println("Compressed size:  " + compressedData.length + " bytes (" + compressedBits + " bits)");
        out.println("Ratio:            " + String.format("%.4f", ratio));
        out.println("Space saving:     " + String.format("%.2f%%", spaceSaving * 100));
        out.println();

        out.println("Tier usage:");
        for (EncodingTier tier : EncodingTier.values()) {
            long count = tierUsageCounts.getOrDefault(tier, 0L);
            out.println("  " + tier.name() + ": " + count);
        }
        out.println();

        if (!cyclesUsed.isEmpty()) {
            out.println("Cycles used:");
            for (String cycle : cyclesUsed) {
                out.println("  " + cycle);
            }
        }

        out.println();
        out.println("Encoding time:    " + encodingNanos + " ns");
        out.println("Decoding time:    " + decodingNanos + " ns");
    }
}
