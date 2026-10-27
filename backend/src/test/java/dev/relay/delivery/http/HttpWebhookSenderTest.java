package dev.relay.delivery.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.List;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryRequest;
import dev.relay.delivery.signing.SigningKey;
import dev.relay.delivery.signing.WebhookSignature;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpWebhookSenderTest {
    @Test
    void directSocketsIgnoreAmbientProxySelection() throws Exception {
        var selected = new AtomicInteger();
        ProxySelector original = ProxySelector.getDefault();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/receiver", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/receiver");
        try {
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI destination) {
                    selected.incrementAndGet();
                    return List.of(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 9)));
                }

                @Override
                public void connectFailed(URI destination, SocketAddress address, java.io.IOException failure) {}
            });
            try (var sender = new HttpWebhookSender(1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    new LocalReceiverPolicy(true, true, url.toString()))) {
                assertThat(sender.send(request(url), Duration.ofSeconds(2)).outcome()).isEqualTo(CompletionOutcome.SUCCEEDED);
                assertThat(selected.get()).isZero();
            }
        } finally {
            ProxySelector.setDefault(original);
            server.stop(0);
        }
    }

    @Test
    void signsRawPayloadAndKeepsConfirmedSuccessWhenTheResponseBodyStalls() throws Exception {
        var release = new CountDownLatch(1);
        var received = new AtomicReference<byte[]>();
        var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var receiverThreads = Executors.newFixedThreadPool(1);
        server.setExecutor(receiverThreads);
        server.createContext("/receiver", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            headers.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(201, 0);
            exchange.getResponseBody().write("partial-body".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/receiver");
        try (var sender = new HttpWebhookSender(1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                new LocalReceiverPolicy(true, true, url.toString()))) {
            DeliveryRequest request = request(url);
            var result = sender.send(request, Duration.ofMillis(1500));
            assertThat(result.outcome()).isEqualTo(CompletionOutcome.SUCCEEDED);
            assertThat(result.httpStatus()).isEqualTo(201);
            assertThat(result.responsePreview()).isEqualTo("partial-body");
            assertThat(result.responseTruncated()).isTrue();
            assertThat(result.durationMillis()).isLessThan(3500);
            assertThat(release.getCount()).as("the sender must not wait indefinitely for the body producer").isEqualTo(1);
            assertThat(received.get()).isEqualTo(request.payload());
            assertThat(headers.get().getFirst("X-Relay-Event-Id")).isEqualTo(request.eventId().toString());
            assertThat(headers.get().getFirst("X-Relay-Delivery-Id")).isEqualTo(request.deliveryId().toString());
            assertThat(headers.get().getFirst("X-Relay-Attempt-Id")).isEqualTo(request.attemptId().toString());
            assertThat(headers.get().getFirst("X-Relay-Attempt")).isEqualTo("2");
            assertThat(headers.get().getFirst("Authorization")).isNull();
            assertThat(headers.get().getFirst("Cookie")).isNull();
            assertThat(headers.get().getFirst("Content-Type")).startsWith("application/json");
            assertThat(WebhookSignature.verify(request, headers.get().getFirst("X-Relay-Timestamp"),
                    headers.get().getFirst("X-Relay-Signature"), Instant.now())).isTrue();
            assertThat(WebhookSignature.verify(request, headers.get().getFirst("X-Relay-Timestamp"),
                    headers.get().getFirst("X-Relay-Signature"), Instant.parse("2040-01-01T00:00:00Z"))).isFalse();
        } finally {
            release.countDown();
            server.stop(0);
            receiverThreads.shutdownNow();
        }
    }

    @Test
    void giantFixedAndChunkedBodiesKeepOnlyTheConfiguredPrefix() throws Exception {
        for (boolean chunked : new boolean[]{false, true}) {
            try (var receiver = new Receiver(exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(chunked ? 500 : 200, chunked ? 0 : 1048576);
                byte[] bytes = new byte[4096];
                Arrays.fill(bytes, (byte) 'x');
                try {
                    for (int index = 0; index < 256; index++) exchange.getResponseBody().write(bytes);
                } catch (java.io.IOException clientClosedAtCaptureLimit) {
                    // The transport must close this deliberately oversized body early.
                } finally { exchange.close(); }
            }); var sender = receiver.sender(128)) {
                var result = sender.send(request(receiver.url()), Duration.ofSeconds(2));
                assertThat(result.httpStatus()).isEqualTo(chunked ? 500 : 200);
                assertThat(result.outcome()).isEqualTo(chunked ? CompletionOutcome.FAILED : CompletionOutcome.SUCCEEDED);
                assertThat(result.failureCode()).isEqualTo(chunked ? "HTTP_STATUS" : null);
                assertThat(result.responsePreview()).isEqualTo("x".repeat(128));
                assertThat(result.responseTruncated()).isTrue();
            }
        }
    }

    @Test
    void malformedUtf8AndControlCharactersBecomePostgresqlSafeText() throws Exception {
        byte[] bytes = {'a', 0, '\n', '\r', '\t', 127, (byte) 0xc3, '(', (byte) 0xff, 'e', 'n', 'd'};
        try (var receiver = new Receiver(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }); var sender = receiver.sender(4096)) {
            var result = sender.send(request(receiver.url()), Duration.ofSeconds(2));
            assertThat(result.responsePreview()).startsWith("a").endsWith("end").contains("\ufffd")
                    .doesNotContain("\0", "\n", "\r", "\t", "\u007f");
            assertThat(result.responsePreview().length()).isLessThanOrEqualTo(bytes.length);
            assertThat(result.responseTruncated()).isFalse();
        }
    }

    @Test
    void maximumMalformedPrefixStillFitsBothDatabaseCharacterAndByteLimits() throws Exception {
        byte[] bytes = new byte[16385];
        Arrays.fill(bytes, (byte) 0xff);
        try (var receiver = new Receiver(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }); var sender = receiver.sender(16384)) {
            var result = sender.send(request(receiver.url()), Duration.ofSeconds(2));
            assertThat(result.responsePreview()).hasSize(16384).doesNotContain("\0");
            assertThat(result.responsePreview().getBytes(StandardCharsets.UTF_8)).hasSize(49152);
            assertThat(result.responseTruncated()).isTrue();
            assertThat(result.toString()).doesNotContain(result.responsePreview());
        }
    }

    @Test
    void gzipResponseIsNotInflatedAndRawCaptureRemainsBounded() throws Exception {
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write("z".repeat(500000).getBytes(StandardCharsets.UTF_8));
        }
        byte[] body = compressed.toByteArray();
        try (var receiver = new Receiver(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }); var sender = receiver.sender(64)) {
            var result = sender.send(request(receiver.url()), Duration.ofSeconds(2));
            assertThat(result.outcome()).isEqualTo(CompletionOutcome.SUCCEEDED);
            assertThat(result.responsePreview()).startsWith("\ufffd\ufffd\ufffd").doesNotContain("z".repeat(32));
            assertThat(result.responsePreview().length()).isLessThanOrEqualTo(64);
            assertThat(result.responseTruncated()).isTrue();
        }
    }

    @Test
    void bodyDeadlineKeepsKnownFailureStatusWhileMissingHeadersRemainTimeout() throws Exception {
        for (boolean headers : new boolean[]{false, true}) {
            var release = new CountDownLatch(1);
            try (var receiver = new Receiver(exchange -> {
                exchange.getRequestBody().readAllBytes();
                if (headers) {
                    exchange.sendResponseHeaders(500, 0);
                    exchange.getResponseBody().write("failure-prefix".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            }); var sender = receiver.sender(64)) {
                var result = sender.send(request(receiver.url()), Duration.ofMillis(1200));
                assertThat(result.outcome()).isEqualTo(CompletionOutcome.FAILED);
                assertThat(result.failureCode()).isEqualTo(headers ? "HTTP_STATUS" : "REQUEST_TIMEOUT");
                assertThat(result.httpStatus()).isEqualTo(headers ? 500 : null);
                assertThat(result.responsePreview()).isEqualTo(headers ? "failure-prefix" : null);
                assertThat(result.responseTruncated()).isEqualTo(headers);
                assertThat(result.durationMillis()).isLessThan(3000);
            } finally { release.countDown(); }
        }
    }

    @Test
    void prematureBodyFailurePreservesConfirmedStatusAndCapturedPrefix() throws Exception {
        try (var receiver = new Receiver(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write("short".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.close();
        }); var sender = receiver.sender(64)) {
            var result = sender.send(request(receiver.url()), Duration.ofSeconds(2));
            assertThat(result.outcome()).isEqualTo(CompletionOutcome.SUCCEEDED);
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.failureCode()).isNull();
            assertThat(result.responsePreview()).isEqualTo("short");
            assertThat(result.responseTruncated()).isTrue();
        }
    }

    @Test
    void zeroCaptureDoesNotReadAStalledBodyAndInvalidLimitsFailStartup() throws Exception {
        var release = new CountDownLatch(1);
        try (var receiver = new Receiver(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); var sender = receiver.sender(0)) {
            var result = sender.send(request(receiver.url()), Duration.ofSeconds(2));
            assertThat(result.outcome()).isEqualTo(CompletionOutcome.SUCCEEDED);
            assertThat(result.responsePreview()).isEmpty();
            assertThat(result.responseTruncated()).isTrue();
            assertThat(result.durationMillis()).isLessThan(1000);
            assertThatThrownBy(() -> receiver.sender(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> receiver.sender(16385)).isInstanceOf(IllegalArgumentException.class);
        } finally { release.countDown(); }
    }

    @Test
    void redirectsCannotReachAnotherPrivateReceiverPort() throws Exception {
        var contacts = new AtomicInteger();
        var status = new AtomicInteger(307);
        try (var target = new Receiver(exchange -> {
            contacts.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }); var source = new Receiver(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", target.url().toString());
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        }); var sender = source.sender(64)) {
            for (int code : new int[]{307, 308}) {
                status.set(code);
                var result = sender.send(request(source.url()), Duration.ofSeconds(2));
                assertThat(result.failureCode()).isEqualTo("HTTP_STATUS");
                assertThat(result.httpStatus()).isEqualTo(code);
                assertThat(contacts.get()).isZero();
            }
        }
    }

    @Test
    void mixedAnswersAreRejectedBeforeConnecting() throws Exception {
        var lookups = new AtomicInteger();
        try (var sender = new HttpWebhookSender(1, Duration.ofMillis(100), Duration.ofMillis(100),
                new LocalReceiverPolicy(false, false, ""), host -> {
                    lookups.incrementAndGet();
                    return new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")};
                })) {
            var result = sender.send(request(URI.create("https://receiver.company.com/hook")), Duration.ofSeconds(1));
            assertThat(result.failureCode()).isEqualTo("DESTINATION_BLOCKED");
            assertThat(lookups.get()).isEqualTo(1);
        }
    }

    @Test
    void stuckNativeDnsHasBoundedThreadsNoQueueAndDoesNotBlockClose() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lookups = new AtomicInteger();
        var sender = new HttpWebhookSender(1, Duration.ofMillis(100), Duration.ofMillis(50),
                new LocalReceiverPolicy(false, false, ""), host -> {
                    lookups.incrementAndGet();
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try { release.await(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException nativeDnsMayIgnoreInterrupts) { /* Deliberate native-DNS simulation. */ }
                    }
                    return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
                });
        try {
            var first = sender.send(request(URI.create("https://receiver.company.com/hook")), Duration.ofSeconds(1));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(first.failureCode()).isEqualTo("DNS_ERROR");
            var second = sender.send(request(URI.create("https://receiver.company.com/hook")), Duration.ofSeconds(1));
            assertThat(second.failureCode()).isEqualTo("RESOURCE_LIMIT");
            assertThat(lookups.get()).isEqualTo(1);
            long started = System.nanoTime();
            sender.close();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(500);
            assertThat(release.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
            sender.close();
        }
    }

    private static DeliveryRequest request(URI url) {
        return new DeliveryRequest(url, "{ \"preserved\" : true }".getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, new SigningKey(new byte[32]));
    }

    private static final class Receiver implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(1);

        Receiver(com.sun.net.httpserver.HttpHandler handler) throws java.io.IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/receiver", handler);
            server.start();
        }

        URI url() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/receiver"); }

        HttpWebhookSender sender(int limit) {
            return new HttpWebhookSender(1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    new LocalReceiverPolicy(true, true, url().toString()), limit);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
