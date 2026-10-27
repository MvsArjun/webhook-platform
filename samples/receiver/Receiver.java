import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** JDK-only example: java samples/receiver/Receiver.java. State is in memory and lost on restart. */
public final class Receiver {
    private static final int MAX_BODY = 262_144;
    private static final int MAX_EVENTS = 10_000;
    private final byte[] secret;
    private final Map<UUID, byte[]> applied = new HashMap<>();
    private long appliedEvents;

    private Receiver(byte[] secret) { this.secret = secret.clone(); }

    public static void main(String[] args) {
        try {
            String configured = System.getenv("RECEIVER_SIGNING_SECRET");
            byte[] key = configured == null ? new byte[0] : Base64.getDecoder().decode(configured);
            if (key.length != 32) throw new IllegalArgumentException();
            String portSetting = System.getenv().getOrDefault("PORT", "9090");
            if (!portSetting.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
            int port = Integer.parseInt(portSetting);
            if (port > 65535) throw new IllegalArgumentException();
            var receiver = new Receiver(key);
            java.util.Arrays.fill(key, (byte) 0);
            System.setProperty("sun.net.httpserver.maxReqHeaders", "32");
            System.setProperty("sun.net.httpserver.maxReqHeaderSize", "8192");
            // Pinned Temurin 21.0.12.1 converts these seconds to milliseconds; the child test checks the deadline.
            System.setProperty("sun.net.httpserver.maxReqTime", "5");
            System.setProperty("sun.net.httpserver.maxRspTime", "5");
            System.setProperty("sun.net.httpserver.idleInterval", "5");
            System.setProperty("sun.net.httpserver.maxIdleConnections", "16");
            System.setProperty("jdk.httpserver.maxConnections", "64");
            System.setProperty("sun.net.httpserver.drainAmount", "0");
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
            var executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.CallerRunsPolicy());
            server.setExecutor(executor);
            server.createContext("/webhook", receiver::handle);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(0);
                executor.shutdownNow();
            }));
            server.start();
            System.out.println(server.getAddress().getPort());
        } catch (Exception invalid) {
            System.err.println("Receiver could not start; check its configuration");
            System.exit(1);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // Consume at most the bounded body before application-level rejection. Closing an
            // exchange with unread bytes can otherwise reset TCP before its error response arrives.
            byte[] payload = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (payload.length > MAX_BODY) { reply(exchange, 413, "{\"error\":\"too_large\"}"); return; }
            if (!exchange.getRequestURI().getPath().equals("/webhook") || exchange.getRequestURI().getRawQuery() != null) {
                reply(exchange, 404, "{\"error\":\"not_found\"}"); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, "{\"error\":\"method\"}"); return;
            }
            if (exchange.getRequestHeaders().size() > 32 || exchange.getRequestHeaders().values().stream()
                    .anyMatch(values -> values.size() != 1 || values.getFirst().length() > 1024)
                    || exchange.getRequestHeaders().entrySet().stream()
                    .mapToInt(header -> header.getKey().length() + header.getValue().stream().mapToInt(String::length).sum()).sum() > 8192) {
                reply(exchange, 401, "{\"error\":\"invalid_signature\"}"); return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
            String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
            if (!(normalizedType.equals("application/json") || normalizedType.equals("application/json;charset=utf-8")
                    || normalizedType.equals("application/json;charset=\"utf-8\""))
                    || (encoding != null && !encoding.equalsIgnoreCase("identity"))) {
                reply(exchange, 415, "{\"error\":\"content_type\"}"); return;
            }
            UUID event = authenticatedEvent(exchange, payload);
            if (event == null) { reply(exchange, 401, "{\"error\":\"invalid_signature\"}"); return; }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            int status;
            String response;
            synchronized (applied) {
                byte[] previous = applied.get(event);
                if (previous != null) {
                    status = MessageDigest.isEqual(previous, digest) ? 200 : 409;
                    response = status == 200 ? "{\"duplicate\":true}" : "{\"error\":\"conflicting_event\"}";
                } else if (applied.size() >= MAX_EVENTS) {
                    status = 503;
                    response = "{\"error\":\"dedupe_capacity\"}";
                } else {
                    applied.put(event, digest);
                    appliedEvents++; // Example business effect, committed under the same lock as deduplication.
                    status = 200;
                    response = "{\"duplicate\":false}";
                }
            }
            reply(exchange, status, response);
        } catch (Exception ignored) {
            // No request body, signature, key or exception detail is logged by the example.
        }
    }

    private UUID authenticatedEvent(HttpExchange exchange, byte[] payload) {
        try {
            var headers = exchange.getRequestHeaders();
            String event = canonicalUuid(headers.getFirst("X-Relay-Event-Id"));
            String delivery = canonicalUuid(headers.getFirst("X-Relay-Delivery-Id"));
            String attempt = canonicalUuid(headers.getFirst("X-Relay-Attempt-Id"));
            String generation = headers.getFirst("X-Relay-Attempt");
            String timestamp = headers.getFirst("X-Relay-Timestamp");
            String signature = headers.getFirst("X-Relay-Signature");
            if (generation == null || !generation.matches("[1-9][0-9]{0,18}") || Long.parseLong(generation) < 1
                    || timestamp == null || !timestamp.matches("0|[1-9][0-9]{0,18}")
                    || signature == null || !signature.matches("v1=[0-9a-f]{64}")) return null;
            long seconds = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (seconds < now - 300 || seconds > now + 300) return null;
            String prefix = "relay.webhook.v1\n" + timestamp + "\n" + event + "\n" + delivery + "\n"
                    + attempt + "\n" + generation + "\n";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(mac.doFinal(payload), HexFormat.of().parseHex(signature.substring(3)))) return null;
            return UUID.fromString(event);
        } catch (Exception invalid) { return null; }
    }

    private static String canonicalUuid(String input) {
        if (input == null || !UUID.fromString(input).toString().equals(input)) throw new IllegalArgumentException();
        return input;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (status >= 400) exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }
}
