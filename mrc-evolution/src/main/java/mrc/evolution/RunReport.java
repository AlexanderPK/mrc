package mrc.evolution;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Generates a human-readable run summary at the end of a GA session.
 *
 * <p>Written to {@code run-report.txt} in the configured log directory.
 * Also printable to any {@link PrintStream} for console output.
 */
public class RunReport {

    private final EvolutionMonitor monitor;
    private final AdaptiveController adaptive;  // nullable
    private final Instant startTime;
    private final Instant endTime;
    private final long totalGenerations;
    private final String domain;

    public RunReport(EvolutionMonitor monitor,
                     AdaptiveController adaptive,
                     Instant startTime,
                     Instant endTime,
                     long totalGenerations,
                     String domain) {
        this.monitor          = monitor;
        this.adaptive         = adaptive;
        this.startTime        = startTime;
        this.endTime          = endTime;
        this.totalGenerations = totalGenerations;
        this.domain           = domain;
    }

    /** Write report to file and return the path. */
    public Path writeToDir(Path logDir) throws IOException {
        Files.createDirectories(logDir);
        Path out = logDir.resolve("run-report.txt");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            print(w);
        }
        return out;
    }

    /** Print report to the given stream. */
    public void print(PrintStream out) {
        print(new PrintWriter(out, true));
    }

    public void print(PrintWriter w) {
        Duration elapsed = Duration.between(startTime, endTime);
        List<GenerationResult> history = monitor.history();

        w.println("══════════════════════════════════════════════════════");
        w.println("  MRC GA RUN REPORT");
        w.println("══════════════════════════════════════════════════════");
        w.printf("  Domain             : %s%n", domain != null ? domain : "(none)");
        w.printf("  Start              : %s%n", startTime);
        w.printf("  End                : %s%n", endTime);
        w.printf("  Elapsed            : %s%n", formatDuration(elapsed));
        w.printf("  Total generations  : %d%n", totalGenerations);
        if (!history.isEmpty()) {
            w.printf("  Gen/sec            : %.1f%n",
                    elapsed.toSeconds() > 0 ? (double) totalGenerations / elapsed.toSeconds() : 0.0);
        }
        w.println();

        // ── Fitness summary ──────────────────────────────────────────────────
        w.println("── Fitness ──────────────────────────────────────────");
        if (history.isEmpty()) {
            w.println("  No generations recorded.");
        } else {
            GenerationResult last = monitor.latest();
            double peakFitness = history.stream().mapToDouble(GenerationResult::bestFitness).max().orElse(0.0);
            long   peakGen     = history.stream()
                    .filter(r -> r.bestFitness() == peakFitness)
                    .mapToLong(GenerationResult::generation).min().orElse(0);
            double firstFitness = history.get(0).bestFitness();
            double lastFitness  = last.bestFitness();

            w.printf("  Initial best       : %.6f%n", firstFitness);
            w.printf("  Final best         : %.6f%n", lastFitness);
            w.printf("  Peak best          : %.6f  (gen %d)%n", peakFitness, peakGen);
            w.printf("  Net improvement    : %+.6f%n", lastFitness - firstFitness);
            w.printf("  Final avg fitness  : %.6f%n", last.averageFitness());
            w.printf("  Final variance     : %.6f%n", last.fitnessVariance());
            w.printf("  Converged?         : %s%n", monitor.hasConverged() ? "YES" : "no");
            w.printf("  Convergence rate   : %.8f%n", monitor.convergenceRate());
        }
        w.println();

        // ── Population summary ───────────────────────────────────────────────
        if (!history.isEmpty()) {
            GenerationResult last = monitor.latest();
            w.println("── Population ───────────────────────────────────────");
            w.printf("  Final pop size     : %d%n", last.populationSize());
            w.printf("  Unique operators   : %d%n", last.uniqueOperatorsUsed());
            if (last.best() != null) {
                w.printf("  Best rule count    : %d%n", last.best().rules().size());
            }
            w.printf("  Corpus bytes       : %d%n", last.corpusBytesProcessed());
            w.println();
        }

        // ── Adaptive controller summary ──────────────────────────────────────
        if (adaptive != null) {
            w.println("── Adaptive Controller ──────────────────────────────");
            w.printf("  Stall boosts       : %d%n", adaptive.totalStallBoosts());
            w.printf("  Improve dampens    : %d%n", adaptive.totalImproveDamps());
            w.printf("  Final rule mut prob: %.6f%n", adaptive.currentRuleMutationProb());
            w.printf("  Final chrom mut prob: %.6f%n", adaptive.currentChromosomeMutationProb());
            w.println();
        }

        // ── Fitness timeline (ASCII sparkline) ───────────────────────────────
        if (history.size() >= 2) {
            w.println("── Fitness Sparkline (best fitness per 5% of run) ───");
            w.print("  ");
            int buckets = 20;
            int step = Math.max(1, history.size() / buckets);
            double min = history.stream().mapToDouble(GenerationResult::bestFitness).min().orElse(0);
            double max = history.stream().mapToDouble(GenerationResult::bestFitness).max().orElse(1);
            double range = max - min;
            String bars = "▁▂▃▄▅▆▇█";
            for (int i = 0; i < history.size(); i += step) {
                double f = history.get(i).bestFitness();
                int barIdx = range < 1e-9 ? 3 : (int) ((f - min) / range * (bars.length() - 1));
                barIdx = Math.max(0, Math.min(bars.length() - 1, barIdx));
                w.print(bars.charAt(barIdx));
            }
            w.println();
            w.printf("  Range: [%.4f .. %.4f]%n", min, max);
            w.println();
        }

        // ── Histogram ────────────────────────────────────────────────────────
        w.println("── Fitness Distribution ─────────────────────────────");
        monitor.printHistogram(new PrintStream(new OutputStream() {
            final StringBuilder buf = new StringBuilder();
            @Override public void write(int b) {
                char c = (char) b;
                if (c == '\n') { w.println(buf.toString()); buf.setLength(0); }
                else buf.append(c);
            }
        }));
        w.println();
        w.println("══════════════════════════════════════════════════════");
    }

    private static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        long ms = d.toMillisPart();
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        if (s > 0) return String.format("%d.%03ds", s, ms);
        return String.format("%dms", d.toMillis());
    }
}
