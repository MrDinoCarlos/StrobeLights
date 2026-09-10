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
        executor = Executors.newFixedThreadPool(8, daemonThreads());
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

        serveResourcePackBytes(exchange, packBytes);
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        byte[] body = Integer.toString(status).getBytes(StandardCharsets.US_ASCII);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=us-ascii");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void serveResourcePackBytes(HttpExchange exchange, byte[] bytes)
        throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Cache-Control", "public, max-age=31536000, immutable");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Accept-Ranges", "bytes");

        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        ByteRange range = parseByteRange(rangeHeader, bytes.length);
        if (rangeHeader != null && range == null) {
            headers.set("Content-Range", "bytes */" + bytes.length);
            exchange.sendResponseHeaders(416, -1);
            return;
        }

        int start = range == null ? 0 : range.start();
        int end = range == null ? bytes.length - 1 : range.end();
        int length = end - start + 1;
        int status = range == null ? 200 : 206;
        headers.set("Content-Length", Integer.toString(length));
        if (range != null) {
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
        }
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head ? -1 : length);
        if (!head) {
            exchange.getResponseBody().write(bytes, start, length);
        }
    }

    private static ByteRange parseByteRange(String header, int length) {
        if (header == null) {
            return null;
        }
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || length <= 0) {
            return null;
        }
        String value = header.substring("bytes=".length()).trim();
        int separator = value.indexOf('-');
        if (separator < 0) {
            return null;
        }
        try {
            String first = value.substring(0, separator).trim();
            String last = value.substring(separator + 1).trim();
            if (first.isEmpty()) {
                long suffixLength = Long.parseLong(last);
                if (suffixLength <= 0) {
                    return null;
                }
                int start = (int) Math.max(0L, length - suffixLength);
                return new ByteRange(start, length - 1);
            }
            long requestedStart = Long.parseLong(first);
            if (requestedStart < 0 || requestedStart >= length) {
                return null;
            }
            long requestedEnd = last.isEmpty() ? length - 1L : Long.parseLong(last);
            if (requestedEnd < requestedStart) {
                return null;
            }
            return new ByteRange((int) requestedStart, (int) Math.min(requestedEnd, length - 1L));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "strobelights-pack-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record ByteRange(int start, int end) {
    }
}
