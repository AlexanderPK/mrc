package mrc.evolution;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes per-generation statistics to CSV and NDJSON files asynchronously.
 *
 * <p>Files produced (under the configured log directory):
 * <ul>
 *   <li>{@code generations.csv}  — spreadsheet-friendly, one row per generation</li>
 *   <li>{@code generations.ndjson} — newline-delimited JSON, one object per generation</li>
 * </ul>
 *
 * Writes are offloaded to a background thread so the GA loop is never blocked by I/O.
 */
public class GenerationLogger implements AutoCloseable {

    private static final String CSV_HEADER =
            "generation,timestamp_ms,elapsed_ms,best_fitness,avg_fitness,variance," +
            "population_size,unique_operators,rule_count,corpus_bytes,convergence_rate\n";

    private final Path csvPath;
    private final Path ndjsonPath;
    private final BlockingQueue<String[]> queue = new LinkedBlockingQueue<>(4096);
    private final Thread writerThread;
    private final long startMs = System.currentTimeMillis();
    private final AtomicLong droppedRows = new AtomicLong(0);

    private volatile boolean closed = false;

    public GenerationLogger(Path logDir) throws IOException {
        Files.createDirectories(logDir);
        this.csvPath    = logDir.resolve("generations.csv");
        this.ndjsonPath = logDir.resolve("generations.ndjson");

        // Write CSV header (overwrite existing file each run)
        Files.writeString(csvPath, CSV_HEADER, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        // Truncate NDJSON
        Files.writeString(ndjsonPath, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        writerThread = Thread.ofVirtual().name("ga-logger").start(this::drainLoop);
    }

    /**
     * Log one generation. Non-blocking: rows are enqueued and written by the background thread.
     *
     * @param result          generation statistics
     * @param convergenceRate slope of best fitness over last 100 gens (from EvolutionMonitor)
     */
    public void log(GenerationResult result, double convergenceRate) {
        if (closed) return;
        String[] row = buildRow(result, convergenceRate);
        if (!queue.offer(row)) {
            droppedRows.incrementAndGet(); // backpressure: queue full, skip this row
        }
    }

    /** Number of rows dropped due to queue backpressure. */
    public long droppedRows() {
        return droppedRows.get();
    }

    @Override
    public void close() {
        closed = true;
        writerThread.interrupt();
        try {
            writerThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Drain remaining
        flush();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private String[] buildRow(GenerationResult r, double convergenceRate) {
        long elapsedMs = System.currentTimeMillis() - startMs;
        long tsMs = r.timestamp() != null ? r.timestamp().toEpochMilli() : System.currentTimeMillis();
        int ruleCount = r.best() != null ? r.best().rules().size() : 0;

        return new String[]{
            Long.toString(r.generation()),
            Long.toString(tsMs),
            Long.toString(elapsedMs),
            fmt(r.bestFitness()),
            fmt(r.averageFitness()),
            fmt(r.fitnessVariance()),
            Integer.toString(r.populationSize()),
            Integer.toString(r.uniqueOperatorsUsed()),
            Integer.toString(ruleCount),
            Long.toString(r.corpusBytesProcessed()),
            fmt(convergenceRate)
        };
    }

    private void drainLoop() {
        try (
            BufferedWriter csvWriter   = Files.newBufferedWriter(csvPath,    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            BufferedWriter jsonWriter  = Files.newBufferedWriter(ndjsonPath, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
        ) {
            while (!Thread.currentThread().isInterrupted()) {
                String[] row = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (row != null) {
                    writeRow(csvWriter, jsonWriter, row);
                    // Drain burst
                    String[] next;
                    while ((next = queue.poll()) != null) {
                        writeRow(csvWriter, jsonWriter, next);
                    }
                    csvWriter.flush();
                    jsonWriter.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("[GenerationLogger] I/O error: " + e.getMessage());
        }
        flush(); // final drain after interrupt
    }

    private void writeRow(BufferedWriter csv, BufferedWriter json, String[] row) throws IOException {
        // CSV
        csv.write(String.join(",", row));
        csv.write('\n');
        // NDJSON
        json.write(toJson(row));
        json.write('\n');
    }

    private static final String[] FIELD_NAMES = {
        "generation","timestamp_ms","elapsed_ms","best_fitness","avg_fitness","variance",
        "population_size","unique_operators","rule_count","corpus_bytes","convergence_rate"
    };

    private String toJson(String[] row) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < row.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(FIELD_NAMES[i]).append("\":");
            // numeric fields — no quotes
            sb.append(row[i]);
        }
        sb.append('}');
        return sb.toString();
    }

    private void flush() {
        if (queue.isEmpty()) return;
        try (
            BufferedWriter csvWriter  = Files.newBufferedWriter(csvPath,    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            BufferedWriter jsonWriter = Files.newBufferedWriter(ndjsonPath, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
        ) {
            String[] row;
            while ((row = queue.poll()) != null) {
                writeRow(csvWriter, jsonWriter, row);
            }
        } catch (IOException e) {
            System.err.println("[GenerationLogger] flush error: " + e.getMessage());
        }
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.US, "%.6f", d);
    }
}
