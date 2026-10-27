package dev.relay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Loopback-only controllable receiver used by actual worker and child-process integration tests. */
final class ControllableReceiver implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<Plan> plan = new AtomicReference<>(new Plan(200, "ok", null, null));
    private final BlockingQueue<Received> requests = new LinkedBlockingQueue<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximumActive = new AtomicInteger();
    private final AtomicInteger redirectedCalls = new AtomicInteger();

    ControllableReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/receiver", this::receive);
        server.createContext("/redirect-target", exchange -> {
            redirectedCalls.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/receiver");
    }

    void respond(int status, String body) {
        plan.set(new Plan(status, body, null, null));
    }

    void redirect() {
        plan.set(new Plan(302, "redirect", null, "/redirect-target"));
    }

    void block(CountDownLatch release) {
        plan.set(new Plan(200, "ok", release, null));
    }

    Received next(Duration timeout) throws InterruptedException {
        return requests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    int maximumActive() {
        return maximumActive.get();
    }

    int active() {
        return active.get();
    }

    int redirectedCalls() {
        return redirectedCalls.get();
    }

    private void receive(HttpExchange exchange) throws IOException {
        int inFlight = active.incrementAndGet();
        maximumActive.accumulateAndGet(inFlight, Math::max);
        Plan current = plan.get();
        try (exchange) {
            var headers = new java.util.HashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI(),
                    Map.copyOf(headers), exchange.getRequestBody().readAllBytes()));
            if (current.release() != null && !current.release().await(40, TimeUnit.SECONDS)) return;
            if (current.location() != null) exchange.getResponseHeaders().set("Location", current.location());
            byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(current.status(), body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // A timed-out or killed worker can close its socket while this test handler is gated.
        } finally {
            active.decrementAndGet();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Receiver handlers did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    record Received(String method, URI path, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            var values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    private record Plan(int status, String body, CountDownLatch release, String location) { }
}
