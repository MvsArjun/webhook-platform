package dev.relay;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.relay.delivery.DeliveryRequest;
import dev.relay.delivery.signing.SigningKey;
import dev.relay.delivery.signing.WebhookSignature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The independently implemented JDK source-launcher example verifies the backend's actual wire signatures. */
class SampleReceiverIT {
    private static final SigningKey KEY = new SigningKey(keyBytes());
    private static Process receiver;
    private static BufferedReader output;
    private static HttpClient http;
    private static URI url;
    private static Path errorLog;

    @BeforeAll
    static void startSourceLaunchedReceiver() throws Exception {
        Path source = Path.of("samples", "receiver", "Receiver.java").toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) source = Path.of("..", "samples", "receiver", "Receiver.java").toAbsolutePath().normalize();
        assertThat(source).isRegularFile();
        Path logs = source.getParent().getParent().getParent().resolve("work").resolve("phase5-tests");
        Files.createDirectories(logs);
        errorLog = logs.resolve("sample-receiver-" + UUID.randomUUID() + ".log");
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), source.toString())
                .redirectError(errorLog.toFile());
        builder.environment().put("RECEIVER_SIGNING_SECRET", Base64.getEncoder().encodeToString(KEY.bytes()));
        builder.environment().put("PORT", "0");
        receiver = builder.start();
        output = new BufferedReader(new InputStreamReader(receiver.getInputStream(), StandardCharsets.UTF_8));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                String line = executor.submit(() -> output.readLine()).get(25, TimeUnit.SECONDS);
                assertThat(line).as("sample prints only its selected port").matches("[0-9]{1,5}");
                url = URI.create("http://127.0.0.1:" + Integer.parseInt(line) + "/webhook");
            } catch (Exception | AssertionError failure) {
                receiver.destroyForcibly();
                receiver.waitFor(5, TimeUnit.SECONDS);
                throw failure;
            }
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterAll
    static void stopReceiver() throws Exception {
        if (http != null) http.close();
        if (receiver != null) {
            receiver.destroyForcibly();
            assertThat(receiver.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
        if (output != null) output.close();
        if (errorLog != null) assertThat(Files.readString(errorLog)).doesNotContain(Base64.getEncoder().encodeToString(KEY.bytes()));
    }

    @Test
    void authenticatedConcurrentDuplicatesApplyOnceAndConflictingAuthenticatedBodyIsRejected() throws Exception {
        UUID event = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        byte[] body = "{\"sample\":\"café\"}".getBytes(StandardCharsets.UTF_8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int index = 1; index <= 8; index++) {
                var request = request(event, delivery, UUID.randomUUID(), index, body);
                futures.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Sample duplicate start timed out");
                    return send(request, Instant.now(), body, Map.of(), null, "application/json; charset=UTF-8");
                }));
            }
            start.countDown();
            int fresh = 0;
            int duplicates = 0;
            for (var future : futures) {
                var response = future.get(15, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                if (response.body().equals("{\"duplicate\":false}")) fresh++;
                if (response.body().equals("{\"duplicate\":true}")) duplicates++;
            }
            assertThat(fresh).isEqualTo(1);
            assertThat(duplicates).isEqualTo(7);
        }
        var duplicate = request(event, delivery, UUID.randomUUID(), 9, body);
        assertThat(send(duplicate, Instant.now(), body, Map.of(), null, "application/json").body())
                .isEqualTo("{\"duplicate\":true}");
        byte[] conflict = "{\"sample\":\"different\"}".getBytes(StandardCharsets.UTF_8);
        var changed = request(event, delivery, UUID.randomUUID(), 10, conflict);
        assertThat(send(changed, Instant.now(), conflict, Map.of(), null, "application/json").statusCode()).isEqualTo(409);
    }

    @Test
    void independentVerifierRejectsEveryTamperedHeaderBodyAndExpiredTimestamp() throws Exception {
        byte[] body = "{\"private\":\"tamper-target\"}".getBytes(StandardCharsets.UTF_8);
        var request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, body);
        var signed = WebhookSignature.sign(request, Instant.now());
        for (var mutation : List.of(
                Map.of("X-Relay-Event-Id", UUID.randomUUID().toString()),
                Map.of("X-Relay-Delivery-Id", UUID.randomUUID().toString()),
                Map.of("X-Relay-Attempt-Id", UUID.randomUUID().toString()),
                Map.of("X-Relay-Attempt", "2"),
                Map.of("X-Relay-Attempt", "0"),
                Map.of("X-Relay-Timestamp", Long.toString(Long.parseLong(signed.timestamp()) + 1)),
                Map.of("X-Relay-Signature", "v1=" + "0".repeat(64)),
                Map.of("X-Relay-Event-Id", "1-1-1-1-1"))) {
            assertThat(sendSigned(request, signed, body, mutation, null, "application/json").statusCode()).isEqualTo(401);
        }
        assertThat(sendSigned(request, signed, "{}".getBytes(StandardCharsets.UTF_8), Map.of(), null, "application/json")
                .statusCode()).isEqualTo(401);
        assertThat(send(request, Instant.now().minusSeconds(600), body, Map.of(), null, "application/json").statusCode())
                .isEqualTo(401);
        assertThat(send(request, Instant.now().plusSeconds(600), body, Map.of(), null, "application/json").statusCode())
                .isEqualTo(401);
        assertThat(sendSigned(request, signed, body, Map.of(), "X-Relay-Signature", "application/json").statusCode())
                .isEqualTo(401);
        assertThat(send(request, Instant.now(), body, Map.of("X-Extra", "x".repeat(1025)), null, "application/json")
                .statusCode()).isEqualTo(401);
        assertThat(send(request, Instant.now(), body, Map.of(), null, "text/plain").statusCode()).isEqualTo(415);
        assertThat(send(request, Instant.now(), body, Map.of("Content-Encoding", "gzip"), null, "application/json")
                .statusCode()).isEqualTo(415);
        // Rejected unauthenticated requests did not consume the event ID in the dedupe map.
        assertThat(send(request, Instant.now(), body, Map.of(), null, "application/json").body())
                .isEqualTo("{\"duplicate\":false}");
    }

    @Test
    void exactMaximumBodyIsAcceptedAndBothKnownAndUnknownLengthOversizeAreBounded() throws Exception {
        byte[] maximum = ("{\"data\":\"" + "x".repeat(262144 - 11) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(maximum).hasSize(262144);
        var request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, maximum);
        assertThat(send(request, Instant.now(), maximum, Map.of(), null, "application/json").statusCode()).isEqualTo(200);
        byte[] oversized = java.util.Arrays.copyOf(maximum, maximum.length + 1);
        assertThat(send(request, Instant.now(), oversized, Map.of(), null, "application/json").statusCode()).isEqualTo(413);
        var signed = WebhookSignature.sign(request, Instant.now());
        var chunked = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized));
        assertThat(chunked.contentLength()).isEqualTo(-1);
        assertThat(http.send(builder(request, signed, Map.of(), null, "application/json").POST(chunked).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
    }

    @Test
    void incompleteBodyIsClosedByThePinnedRuntimeRequestDeadline() throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(url.getHost(), url.getPort()), 3000);
            socket.setSoTimeout(12_000);
            byte[] incomplete = ("POST /webhook HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Type: application/json\r\nContent-Length: 20\r\n\r\nx")
                    .getBytes(StandardCharsets.US_ASCII);
            long started = System.nanoTime();
            socket.getOutputStream().write(incomplete);
            socket.getOutputStream().flush();
            boolean closed;
            try {
                closed = socket.getInputStream().read() == -1;
            } catch (SocketTimeoutException tooSlow) {
                throw new AssertionError("Sample did not close an incomplete request within its deadline", tooSlow);
            } catch (IOException connectionClosed) {
                closed = true;
            }
            assertThat(closed).as("incomplete request ends with connection closure, not an HTTP response").isTrue();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(4)).isLessThan(Duration.ofSeconds(12));
        }
    }

    private static HttpResponse<String> send(DeliveryRequest request, Instant now, byte[] body, Map<String, String> overrides,
                                               String duplicateHeader, String contentType) throws Exception {
        return sendSigned(request, WebhookSignature.sign(request, now), body, overrides, duplicateHeader, contentType);
    }

    private static HttpResponse<String> sendSigned(DeliveryRequest request, WebhookSignature.SignedHeaders signature,
                                                     byte[] body, Map<String, String> overrides, String duplicateHeader,
                                                     String contentType) throws Exception {
        return http.send(builder(request, signature, overrides, duplicateHeader, contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder builder(DeliveryRequest request, WebhookSignature.SignedHeaders signature,
                                                 Map<String, String> overrides, String duplicateHeader, String contentType) {
        var headers = new HashMap<String, String>();
        headers.put("X-Relay-Event-Id", request.eventId().toString());
        headers.put("X-Relay-Delivery-Id", request.deliveryId().toString());
        headers.put("X-Relay-Attempt-Id", request.attemptId().toString());
        headers.put("X-Relay-Attempt", Long.toString(request.generation()));
        headers.put("X-Relay-Timestamp", signature.timestamp());
        headers.put("X-Relay-Signature", signature.signature());
        headers.put("Content-Type", contentType);
        headers.putAll(overrides);
        var builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        if (duplicateHeader != null) builder.header(duplicateHeader, headers.get(duplicateHeader));
        return builder;
    }

    private static DeliveryRequest request(UUID event, UUID delivery, UUID attempt, long generation, byte[] payload) {
        return new DeliveryRequest(url, payload, event, delivery, attempt, generation, KEY);
    }

    private static byte[] keyBytes() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) key[index] = (byte) index;
        return key;
    }
}
