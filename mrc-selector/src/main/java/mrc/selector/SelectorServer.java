package mrc.selector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import mrc.snapshotdb.SnapshotStore;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP selector service.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /select} — body: raw bytes; response: JSON with best snapshot ID</li>
 *   <li>{@code GET  /health} — response: {@code {"status":"ok"}}</li>
 * </ul>
 *
 * <p>No external framework. Uses {@code com.sun.net.httpserver} from the JDK.
 */
public class SelectorServer {

    private final HttpServer server;

    private SelectorServer(HttpServer server) {
        this.server = server;
    }

    /**
     * Start the selector server on the given port.
     *
     * @param port  TCP port to listen on
     * @param store snapshot store to query for candidates
     * @return a running server (call {@link #stop()} to shut it down)
     */
    public static SelectorServer start(int port, SnapshotStore store) throws IOException {
        SnapshotRanker ranker = new SnapshotRanker();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        httpServer.createContext("/select", exchange -> handleSelect(exchange, store, ranker));
        httpServer.createContext("/health", SelectorServer::handleHealth);

        httpServer.start();
        return new SelectorServer(httpServer);
    }

    /** Stop the server immediately. */
    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ─── handlers ──────────────────────────────────────────────────────────

    private static void handleSelect(HttpExchange exchange,
                                     SnapshotStore store,
                                     SnapshotRanker ranker) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }

        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            respond(exchange, 400, "{\"error\":\"empty_body\"}");
            return;
        }

        // Collect all snapshots across all domains
        // For simplicity, list all (no domain filter) — the ranker sorts by score
        DataFingerprint sampleFp = DataFingerprint.of(body);

        // Gather all IDs: use null domain to get no-domain ones, then scan all known
        // We don't have a "list all" API, so rank across all domains heuristically
        List<SnapshotRanker.RankedSnapshot> ranked = rankAllDomains(body, store, ranker);

        if (ranked.isEmpty()) {
            respond(exchange, 404, "{\"error\":\"no_snapshots_available\"}");
            return;
        }

        SnapshotRanker.RankedSnapshot best = ranked.get(0);
        String domain = best.domainTag() != null ? best.domainTag() : "";
        String json = String.format(
                "{\"snapshot_id\":\"%s\",\"score\":%.4f,\"domain\":\"%s\"}",
                best.snapshotId(), best.score(), escapeJson(domain));
        respond(exchange, 200, json);
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"status\":\"ok\"}");
    }

    private static List<SnapshotRanker.RankedSnapshot> rankAllDomains(
            byte[] sample, SnapshotStore store, SnapshotRanker ranker) {
        // Known domain tags from heuristic fingerprints
        String[] heuristicDomains = {"audio", "arith", "numeric", "sequence",
                "repetitive", "zeros", "random", "binary", ""};
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<String> allIds = new java.util.ArrayList<>();
        for (String domain : heuristicDomains) {
            for (String id : store.listByDomain(domain)) {
                if (seen.add(id)) allIds.add(id);
            }
        }
        return ranker.rank(sample, allIds, store);
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
