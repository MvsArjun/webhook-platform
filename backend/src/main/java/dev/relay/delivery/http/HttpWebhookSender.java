package dev.relay.delivery.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryRequest;
import dev.relay.delivery.DeliveryResult;
import dev.relay.delivery.WebhookSender;
import dev.relay.delivery.signing.WebhookSignature;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.InetAddressUtils;
import org.apache.hc.core5.util.Timeout;

/** Bounded, direct HTTP transport with one deadline covering validation through immediate cleanup. */
public final class HttpWebhookSender implements WebhookSender, AutoCloseable {
    private final Duration connectTimeout;
    private final Duration dnsTimeout;
    private final LocalReceiverPolicy local;
    private final AddressLookup lookup;
    private final int responseLimit;
    private final ThreadPoolExecutor requests;
    private final ThreadPoolExecutor dns;
    private final Set<HttpPost> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public HttpWebhookSender(int concurrency, Duration connectTimeout, Duration dnsTimeout, LocalReceiverPolicy local) {
        this(concurrency, connectTimeout, dnsTimeout, local, 4096, InetAddress::getAllByName);
    }

    public HttpWebhookSender(int concurrency, Duration connectTimeout, Duration dnsTimeout,
                             LocalReceiverPolicy local, int responseLimit) {
        this(concurrency, connectTimeout, dnsTimeout, local, responseLimit, InetAddress::getAllByName);
    }

    HttpWebhookSender(int concurrency, Duration connectTimeout, Duration dnsTimeout,
                      LocalReceiverPolicy local, AddressLookup lookup) {
        this(concurrency, connectTimeout, dnsTimeout, local, 4096, lookup);
    }

    HttpWebhookSender(int concurrency, Duration connectTimeout, Duration dnsTimeout,
                      LocalReceiverPolicy local, int responseLimit, AddressLookup lookup) {
        if (concurrency < 1 || concurrency > 100 || !positive(connectTimeout) || !positive(dnsTimeout)
                || local == null || lookup == null || responseLimit < 0 || responseLimit > 16384) {
            throw new IllegalArgumentException("Invalid HTTP transport configuration");
        }
        this.connectTimeout = connectTimeout;
        this.dnsTimeout = dnsTimeout;
        this.local = local;
        this.lookup = lookup;
        this.responseLimit = responseLimit;
        requests = executor(concurrency, "relay-http-", true);
        dns = executor(concurrency, "relay-dns-", false);
    }

    @Override
    public DeliveryResult send(DeliveryRequest request, Duration budget) {
        if (request == null || !positive(budget)) {
            throw new IllegalArgumentException("A request and positive request budget are required");
        }
        long started = System.nanoTime();
        long budgetNanos = saturatedNanos(budget);
        HttpPost post = new HttpPost(request.url());
        AtomicReference<ResponseSnapshot> observed = new AtomicReference<>();
        Future<DeliveryResult> task;
        try {
            if (closed.get()) return failed("RESOURCE_LIMIT", started);
            task = requests.submit(() -> perform(request, post, observed, started, budgetNanos));
        } catch (RejectedExecutionException unavailable) {
            return failed("RESOURCE_LIMIT", started);
        }
        try {
            return task.get(remaining(started, budgetNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            post.cancel();
            task.cancel(true);
            return observedOrFailed(observed, "REQUEST_TIMEOUT", started);
        } catch (InterruptedException interrupted) {
            post.cancel();
            task.cancel(true);
            Thread.currentThread().interrupt();
            return observedOrFailed(observed, "REQUEST_TIMEOUT", started);
        } catch (CancellationException cancelled) {
            return observedOrFailed(observed, "REQUEST_TIMEOUT", started);
        } catch (ExecutionException failure) {
            return observedOrFailed(observed, "INTERNAL_ERROR", started);
        }
    }

    private DeliveryResult perform(DeliveryRequest request, HttpPost post, AtomicReference<ResponseSnapshot> observed,
                                   long started, long budgetNanos) {
        active.add(post);
        CloseableHttpClient client = null;
        CloseableHttpResponse response = null;
        try {
            if (closed.get() || post.isCancelled()) return failed("REQUEST_TIMEOUT", started);
            DestinationPolicy.Target target = DestinationPolicy.validate(request.url(), local);
            InetAddress[] addresses = resolve(target, started, budgetNanos);
            checkTime(started, budgetNanos);
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(started, budgetNanos)));
            Timeout whole = Timeout.ofMilliseconds(remainingMillis);
            Timeout connection = Timeout.ofMilliseconds(Math.min(remainingMillis, positiveMillis(connectTimeout)));
            var manager = new DirectConnectionManagerBuilder()
                    .setMaxConnTotal(1).setMaxConnPerRoute(1)
                    .setDnsResolver(new PinnedDnsResolver(target.host(), addresses))
                    .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                            .http1Config(Http1Config.custom().setMaxHeaderCount(64).setMaxLineLength(8192).build()).build())
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(connection).setSocketTimeout(whole).build())
                    .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(connection).build())
                    // Default trust and hostname verification retain the original URI host/SNI.
                    .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().buildClassic())
                    .build();
            client = HttpClients.custom().setConnectionManager(manager)
                    .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                    .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                    .disableAuthCaching().disableContentCompression().disableConnectionState()
                    .setConnectionReuseStrategy((httpRequest, httpResponse, context) -> false)
                    .build();
            post.setConfig(RequestConfig.custom().setAuthenticationEnabled(false).setRedirectsEnabled(false)
                    .setExpectContinueEnabled(false).setContentCompressionEnabled(false)
                    .setConnectionRequestTimeout(whole).setResponseTimeout(whole).setHardCancellationEnabled(true).build());
            post.setEntity(new ByteArrayEntity(request.payload(), ContentType.APPLICATION_JSON));
            post.setHeader("X-Relay-Event-Id", request.eventId().toString());
            post.setHeader("X-Relay-Delivery-Id", request.deliveryId().toString());
            post.setHeader("X-Relay-Attempt-Id", request.attemptId().toString());
            post.setHeader("X-Relay-Attempt", Long.toString(request.generation()));
            // Receiver freshness uses actual UTC time, independent of the injected retry clock.
            var signed = WebhookSignature.sign(request, Instant.now());
            post.setHeader("X-Relay-Timestamp", signed.timestamp());
            post.setHeader("X-Relay-Signature", signed.signature());
            checkTime(started, budgetNanos);
            // execute(responseHandler) drains the entity after the handler. Adapt executeOpen
            // instead, read only the bounded prefix, then close IMMEDIATE without draining.
            response = CloseableHttpResponse.adapt(client.executeOpen(null, post, HttpClientContext.create()));
            int status = response.getCode();
            if (status < 100 || status > 599) return failed("NETWORK_ERROR", started);
            observed.set(new ResponseSnapshot(status, new byte[0], true));
            capture(response, observed, started, budgetNanos);
            response.close(CloseMode.IMMEDIATE);
            response = null;
            client.close(CloseMode.IMMEDIATE);
            client = null;
            checkTime(started, budgetNanos);
            return responseResult(observed.get(), false, started);
        } catch (DestinationPolicy.BlockedDestinationException blocked) {
            return failed("DESTINATION_BLOCKED", started);
        } catch (DnsFailure failure) {
            return failed(failure.code, started);
        } catch (ConnectTimeoutException timeout) {
            return observedOrFailed(observed, "CONNECT_TIMEOUT", started);
        } catch (SocketTimeoutException | TimeoutException timeout) {
            post.cancel();
            return observedOrFailed(observed, "REQUEST_TIMEOUT", started);
        } catch (IOException network) {
            return observedOrFailed(observed, "NETWORK_ERROR", started);
        } catch (RuntimeException failure) {
            return observedOrFailed(observed, "INTERNAL_ERROR", started);
        } finally {
            if (response != null) response.close(CloseMode.IMMEDIATE);
            if (client != null) client.close(CloseMode.IMMEDIATE);
            active.remove(post);
        }
    }

    private void capture(CloseableHttpResponse response, AtomicReference<ResponseSnapshot> observed,
                         long started, long budgetNanos) throws IOException, TimeoutException {
        int status = response.getCode();
        if (response.getEntity() == null) {
            observed.set(new ResponseSnapshot(status, new byte[0], false));
            return;
        }
        if (responseLimit == 0) {
            observed.set(new ResponseSnapshot(status, new byte[0], response.getEntity().getContentLength() != 0));
            return;
        }
        byte[] prefix = new byte[responseLimit + 1];
        int count = 0;
        // Do not close this stream: its graceful close may drain the untrusted body. The
        // enclosing response is always closed IMMEDIATE, including cancellation/failure.
        InputStream body = response.getEntity().getContent();
        while (count < prefix.length) {
            checkTime(started, budgetNanos);
            int read = body.read(prefix, count, Math.min(1024, prefix.length - count));
            if (read < 0) {
                observed.set(new ResponseSnapshot(status, Arrays.copyOf(prefix, count), false));
                return;
            }
            if (read > 0) {
                count += read;
                observed.set(new ResponseSnapshot(status, Arrays.copyOf(prefix, Math.min(count, responseLimit)), true));
            }
        }
    }

    private InetAddress[] resolve(DestinationPolicy.Target target, long started, long budgetNanos)
            throws DestinationPolicy.BlockedDestinationException, DnsFailure, UnknownHostException {
        String host = target.host();
        if (InetAddressUtils.isIPv4Address(host) || InetAddressUtils.isIPv6Address(host)) {
            // The syntactic literal check ensures getByName cannot invoke name resolution here.
            return DestinationPolicy.validateAddresses(new InetAddress[]{InetAddress.getByName(host)}, target.local());
        }
        Future<InetAddress[]> task;
        try {
            task = dns.submit(() -> lookup.resolve(host));
        } catch (RejectedExecutionException exhausted) {
            throw new DnsFailure("RESOURCE_LIMIT");
        }
        try {
            long timeout = Math.min(saturatedNanos(dnsTimeout), remaining(started, budgetNanos));
            return DestinationPolicy.validateAddresses(task.get(timeout, TimeUnit.NANOSECONDS), target.local());
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw new DnsFailure(remaining(started, budgetNanos) == 0 ? "REQUEST_TIMEOUT" : "DNS_ERROR");
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new DnsFailure("REQUEST_TIMEOUT");
        } catch (ExecutionException failure) {
            throw new DnsFailure("DNS_ERROR");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach(HttpPost::cancel);
            requests.shutdownNow().forEach(task -> {
                if (task instanceof Future<?> future) future.cancel(true);
            });
            // Native DNS may ignore interruption. Daemon threads, a fixed pool and zero queue
            // bound that resource; shutdown deliberately never waits for native DNS to return.
            dns.shutdownNow();
        }
    }

    private static ThreadPoolExecutor executor(int concurrency, String prefix, boolean queueRequests) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        BlockingQueue<Runnable> queue = queueRequests ? new ArrayBlockingQueue<>(concurrency) : new SynchronousQueue<>();
        return new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                queue, factory, new ThreadPoolExecutor.AbortPolicy());
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    private static long saturatedNanos(Duration duration) {
        try { return duration.toNanos(); }
        catch (ArithmeticException tooLarge) { return Long.MAX_VALUE; }
    }

    private static long positiveMillis(Duration duration) {
        try { return Math.max(1, duration.toMillis()); }
        catch (ArithmeticException tooLarge) { return Long.MAX_VALUE; }
    }

    private static long remaining(long started, long budget) {
        return Math.max(0, budget - Math.max(0, System.nanoTime() - started));
    }

    private static void checkTime(long started, long budget) throws TimeoutException {
        if (remaining(started, budget) == 0 || Thread.currentThread().isInterrupted()) throw new TimeoutException();
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started));
    }

    private static DeliveryResult failed(String code, long started) {
        return new DeliveryResult(CompletionOutcome.FAILED, null, code, elapsedMillis(started));
    }

    private static DeliveryResult observedOrFailed(AtomicReference<ResponseSnapshot> observed, String code, long started) {
        ResponseSnapshot snapshot = observed.get();
        return snapshot == null ? failed(code, started) : responseResult(snapshot, true, started);
    }

    private static DeliveryResult responseResult(ResponseSnapshot snapshot, boolean incomplete, long started) {
        boolean succeeded = snapshot.status() >= 200 && snapshot.status() < 300;
        return new DeliveryResult(succeeded ? CompletionOutcome.SUCCEEDED : CompletionOutcome.FAILED,
                snapshot.status(), succeeded ? null : "HTTP_STATUS", elapsedMillis(started),
                sanitizedPreview(snapshot.prefix()), incomplete || snapshot.truncated());
    }

    private static String sanitizedPreview(byte[] prefix) {
        // The decoder replaces malformed UTF-8. Each input byte needs at most one UTF-16
        // character / three UTF-8 bytes after replacement, keeping the database bounds.
        String decoded = new String(prefix, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(decoded.length());
        decoded.codePoints().forEach(codePoint -> result.appendCodePoint(Character.isISOControl(codePoint) ? 0xfffd : codePoint));
        return result.toString();
    }

    private record ResponseSnapshot(int status, byte[] prefix, boolean truncated) {
        @Override
        public String toString() { return "ResponseSnapshot[status=" + status + ", truncated=" + truncated + "]"; }
    }

    @FunctionalInterface
    interface AddressLookup { InetAddress[] resolve(String host) throws UnknownHostException; }

    private static final class DirectConnectionManagerBuilder extends PoolingHttpClientConnectionManagerBuilder {
        @Override
        protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver ports, DnsResolver resolver,
                                                                        TlsSocketStrategy tls) {
            // A direct HTTP route alone does not disable the JDK socket's ambient SOCKS proxy.
            return new DefaultHttpClientConnectionOperator(ignored -> new Socket(Proxy.NO_PROXY), ports, resolver,
                    RegistryBuilder.<TlsSocketStrategy>create().register("https", tls).build());
        }
    }

    private static final class DnsFailure extends Exception {
        private final String code;
        DnsFailure(String code) { super("DNS resolution did not complete safely"); this.code = code; }
    }
}
