package mrc.gaservice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Minimal HTTP health endpoint for the GA service.
 *
 * <p>Endpoint: {@code GET /health}
 * <br>Response: {@code {"status":"running","generation":<n>,"best_fitness":<f>}}
 */
public class HealthServer {

    private final HttpServer server;

    private HealthServer(HttpServer server) {
        this.server = server;
    }

    /**
     * Start the health server on the given port.
     *
     * @param port           TCP port (0 = OS-assigned)
     * @param generationInfo supplier of current GA state (called on each request)
     */
    public static HealthServer start(int port, Supplier<GaStatus> generationInfo) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.createContext("/health", exchange -> handleHealth(exchange, generationInfo));
        httpServer.start();
        return new HealthServer(httpServer);
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ─── status record ─────────────────────────────────────────────────────

    public record GaStatus(long generation, double bestFitness) {}

    // ─── handler ───────────────────────────────────────────────────────────

    private static void handleHealth(HttpExchange exchange,
                                     Supplier<GaStatus> statusSupplier) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        GaStatus status = statusSupplier.get();
        String json = String.format(
                "{\"status\":\"running\",\"generation\":%d,\"best_fitness\":%.6f}",
                status.generation(), status.bestFitness());
        respond(exchange, 200, json);
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
