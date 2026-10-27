package dev.relay.delivery.http;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryRequest;
import dev.relay.delivery.signing.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adversarial wire inputs and TLS verification still apply to the exact local development exception. */
class TransportSecurityAdversarialTest {
    @TempDir Path temporary;

    @Test
    void localDevelopmentExceptionDoesNotTrustASelfSignedTlsCertificate() throws Exception {
        char[] password = "test-only-store-password".toCharArray();
        Path store = temporary.resolve("receiver.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "receiver", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=127.0.0.1", "-ext", "SAN=IP:127.0.0.1", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", new String(password), "-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("temporary receiver certificate generation").isTrue();
            assertThat(process.exitValue()).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, password); }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password);
        var tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        var calls = new AtomicInteger();
        var receiver = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.setHttpsConfigurator(new HttpsConfigurator(tls));
        receiver.createContext("/receiver", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        URI url = URI.create("https://127.0.0.1:" + receiver.getAddress().getPort() + "/receiver");
        try (var sender = sender(url)) {
            var result = sender.send(request(url), Duration.ofSeconds(3));
            assertThat(result.outcome()).isEqualTo(CompletionOutcome.FAILED);
            assertThat(result.failureCode()).isEqualTo("NETWORK_ERROR");
            assertThat(result.httpStatus()).isNull();
            assertThat(result.responsePreview()).isNull();
            assertThat(calls.get()).isZero();
        } finally { receiver.stop(0); }
    }

    @Test
    void oversizedHeadersAndAmbiguousContentLengthCannotBecomeSuccessfulResponses() throws Exception {
        String manyHeaders = java.util.stream.IntStream.range(0, 70)
                .mapToObj(index -> "X-Test-" + index + ": value\r\n").collect(java.util.stream.Collectors.joining());
        for (String response : List.of(
                "HTTP/1.1 200 OK\r\nX-Oversized: " + "x".repeat(9000) + "\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\n" + manyHeaders + "Content-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nContent-Length: 1\r\n\r\nx")) {
            try (var server = new ServerSocket(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                server.bind(new InetSocketAddress("127.0.0.1", 0));
                server.setSoTimeout(5000);
                URI url = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/receiver");
                var served = executor.submit(() -> {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        readRequest(socket.getInputStream());
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    return true;
                });
                try (var sender = sender(url)) {
                    var result = sender.send(request(url), Duration.ofSeconds(3));
                    assertThat(result.outcome()).isEqualTo(CompletionOutcome.FAILED);
                    assertThat(result.failureCode()).isEqualTo("NETWORK_ERROR");
                    assertThat(result.httpStatus()).isNull();
                    assertThat(result.responsePreview()).isNull();
                }
                assertThat(served.get(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void mappedIpv6AndAlternatePrivateNumericFormsCannotPassPublicDestinationValidation() throws Exception {
        var local = new LocalReceiverPolicy(false, false, "");
        for (String host : List.of("[::ffff:8.8.8.8]", "[::ffff:808:808]", "[::ffff:127.0.0.1]")) {
            assertThatThrownBy(() -> DestinationPolicy.validate(URI.create("https://" + host + "/hook"), local))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        }
        for (String numeric : List.of("127.1", "127.0.1", "0.0.0.0", "169.254.169.254")) {
            InetAddress address = InetAddress.getByName(numeric);
            assertThatThrownBy(() -> DestinationPolicy.validateAddresses(new InetAddress[]{address}, false))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        }
    }

    @Test
    void connectionPortResolutionUsesOnlyTheImmutableValidatedAddresses() throws Exception {
        InetAddress trusted = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress[] resolved = {trusted};
        var pinned = new PinnedDnsResolver("Receiver.Company.Com", resolved);
        resolved[0] = InetAddress.getLoopbackAddress();
        var sockets = pinned.resolve("receiver.company.com", 8443);
        assertThat(sockets).hasSize(1);
        assertThat(sockets.getFirst().getAddress()).isEqualTo(trusted);
        assertThat(sockets.getFirst().getPort()).isEqualTo(8443);
        assertThat(pinned.resolveCanonicalHostname("RECEIVER.COMPANY.COM")).isEqualTo("receiver.company.com");
        assertThatThrownBy(() -> pinned.resolve("unexpected.company.com", 8443))
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    private static HttpWebhookSender sender(URI url) {
        return new HttpWebhookSender(1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                new LocalReceiverPolicy(true, true, url.toString()), 64);
    }

    private static DeliveryRequest request(URI url) {
        return new DeliveryRequest(url, "{}".getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, new SigningKey(new byte[32]));
    }

    private static void readRequest(InputStream input) throws Exception {
        int suffix = 0;
        for (int length = 0; length < 16384; length++) {
            int value = input.read();
            if (value < 0) throw new java.io.EOFException("Incomplete receiver request");
            suffix = (suffix << 8) | value;
            if (suffix == 0x0d0a0d0a) {
                if (input.readNBytes(2).length != 2) throw new java.io.EOFException("Incomplete request payload");
                return;
            }
        }
        throw new IllegalStateException("Receiver request headers exceeded test bound");
    }
}
