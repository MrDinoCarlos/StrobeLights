package es.mrdino.strobelights.resourcepack;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Serves the immutable shader pack embedded in the plugin JAR. */
final class EmbeddedPackServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;

    EmbeddedPackServer(
        InetSocketAddress address,
        String downloadPath,
        byte[] packBytes,
        Logger logger
    ) throws IOException {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(downloadPath, "downloadPath");
        Objects.requireNonNull(packBytes, "packBytes");
        if (!downloadPath.startsWith("/")) {
            throw new IllegalArgumentException("downloadPath must start with /");
        }

        server = HttpServer.create(address, 0);
        executor = Executors.newFixedThreadPool(2, daemonThreads());
        server.setExecutor(executor);
        server.createContext(downloadPath, exchange -> {
            try {
                handle(exchange, downloadPath, packBytes);
            } catch (IOException exception) {
                logger.log(Level.FINE, "Resource-pack connection closed", exception);
            } finally {
                exchange.close();
            }
        });
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void handle(
        HttpExchange exchange,
        String downloadPath,
        byte[] packBytes
    ) throws IOException {
        if (!downloadPath.equals(exchange.getRequestURI().getPath())) {
            sendEmpty(exchange, 404);
            return;
        }
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendEmpty(exchange, 405);
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Cache-Control", "public, max-age=31536000, immutable");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Length", Integer.toString(packBytes.length));
        if ("HEAD".equals(method)) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, packBytes.length);
        exchange.getResponseBody().write(packBytes);
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        byte[] body = Integer.toString(status).getBytes(StandardCharsets.US_ASCII);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=us-ascii");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "strobelights-pack-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
