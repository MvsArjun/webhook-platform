package dev.relay;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual local HTTP delivery and packaged-process recovery, using the same PostgreSQL foundation. */
@ExtendWith(OutputCaptureExtension.class)
class WorkerDeliveryIT extends HttpIntegrationTestSupport {
    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (UUID accountId : accounts) {
            for (String table : List.of("delivery_attempts", "deliveries", "events", "endpoints", "api_keys")) {
                jdbc.update("DELETE FROM " + table + " WHERE workspace_id IN "
                        + "(SELECT id FROM workspaces WHERE owner_account_id = ?)", accountId);
            }
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
    }

    @Test
    void successfulHttpDeliveryPreservesBytesAndIdsAndRecordsOnlyBoundedMetadata(CapturedOutput output)
            throws Exception {
        try (var receiver = new ControllableReceiver()) {
            String marker = "private-webhook-body-" + UUID.randomUUID();
            String payload = "{\"type\":\"order.created\",\"private\":\"" + marker + "\",\"label\":\"café\"}";
            try (var customer = apiFixture(receiver.url(), payload);
                 var worker = worker(receiver.url(), 4, Map.of())) {
                var fixture = customer.fixture();
                var work = fixture.work().getFirst();
                var received = receiver.next(Duration.ofSeconds(15));
                assertThat(received).isNotNull();
                awaitTerminal(fixture, "SUCCEEDED");
                assertThat(received.method()).isEqualTo("POST");
                assertThat(received.path().getPath()).isEqualTo("/receiver");
                assertThat(received.body()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
                assertHeaders(received, work, 1);
                UUID attemptId = UUID.fromString(received.header("X-Relay-Attempt-Id"));
                assertMetadata(attemptId, "SUCCEEDED", 200, null);
                assertThat(attemptCount(work.deliveryId())).isEqualTo(1);
                var history = apiRequest(customer.origin(), customer.http(), "GET", "/api/v1/events/"
                        + work.eventId() + "/attempts", null, null,
                        Map.of("Authorization", "Bearer " + customer.apiKey()));
                assertThat(history.statusCode()).isEqualTo(200);
                var item = json(history).get("items").get(0);
                assertThat(item.size()).isEqualTo(13);
                assertThat(item.get("id").asString()).isEqualTo(attemptId.toString());
                assertThat(item.get("httpStatus").asInt()).isEqualTo(200);
                assertThat(item.get("failureCode").isNull()).isTrue();
                assertThat(item.get("durationMillis").asLong()).isGreaterThanOrEqualTo(0);
                assertThat(history.body()).doesNotContain(marker, payload, customer.apiKey(), "claimToken", "requestDigest");
                assertThat(output.getAll()).doesNotContain(marker, payload);
            }
        }
    }

    @Test
    void singleAttemptBudgetMakesServerFailureTerminalWithoutImplicitTransportRetry(CapturedOutput output) throws Exception {
        try (var receiver = new ControllableReceiver()) {
            String responseMarker = "private-response-" + UUID.randomUUID();
            receiver.respond(500, responseMarker);
            var fixture = fixture(receiver.url(), 1, "{\"failure\":true}");
            jdbc.update("UPDATE deliveries SET max_attempts = 1 WHERE workspace_id = ?", fixture.workspaceId());
            var work = fixture.work().getFirst();
            try (var worker = worker(receiver.url(), 4, Map.of())) {
                var received = receiver.next(Duration.ofSeconds(15));
                assertThat(received).isNotNull();
                awaitTerminal(fixture, "FAILED");
                assertMetadata(UUID.fromString(received.header("X-Relay-Attempt-Id")), "FAILED", 500, "HTTP_STATUS");
                // Several worker polling opportunities pass while the terminal row remains untouched.
                assertThat(receiver.next(Duration.ofMillis(800))).isNull();
                assertThat(attemptCount(work.deliveryId())).isEqualTo(1);
                assertThat(output.getAll()).doesNotContain(responseMarker);
            }
        }
    }

    @Test
    void stalledReceiverHitsRequestTimeoutAndLeavesOneTerminalAttempt() throws Exception {
        var release = new CountDownLatch(1);
        try (var receiver = new ControllableReceiver()) {
            receiver.block(release);
            var fixture = fixture(receiver.url(), 1, "{\"timeout\":true}");
            jdbc.update("UPDATE deliveries SET max_attempts = 1 WHERE workspace_id = ?", fixture.workspaceId());
            try (var worker = worker(receiver.url(), 1, Map.of("relay.worker.request-timeout", "1s",
                    "relay.worker.connect-timeout", "500ms", "relay.worker.dns-timeout", "500ms"))) {
                var received = receiver.next(Duration.ofSeconds(15));
                assertThat(received).isNotNull();
                awaitTerminal(fixture, "FAILED");
                assertMetadata(UUID.fromString(received.header("X-Relay-Attempt-Id")), "FAILED", null, "REQUEST_TIMEOUT");
                assertThat(attemptCount(fixture.work().getFirst().deliveryId())).isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void redirectsAreRecordedAsFailuresWithoutFollowingTheirLocation() throws Exception {
        try (var receiver = new ControllableReceiver()) {
            receiver.redirect();
            var fixture = fixture(receiver.url(), 1, "{\"redirect\":true}");
            try (var worker = worker(receiver.url(), 1, Map.of())) {
                var received = receiver.next(Duration.ofSeconds(15));
                assertThat(received).isNotNull();
                awaitTerminal(fixture, "FAILED");
                assertMetadata(UUID.fromString(received.header("X-Relay-Attempt-Id")), "FAILED", 302, "HTTP_STATUS");
                assertThat(receiver.redirectedCalls()).isZero();
                assertThat(attemptCount(fixture.work().getFirst().deliveryId())).isEqualTo(1);
            }
        }
    }

    @Test
    void competingWorkersDeliverEveryJobOnceWithoutExceedingTheirCombinedCapacity() throws Exception {
        var release = new CountDownLatch(1);
        try (var receiver = new ControllableReceiver()) {
            receiver.block(release);
            var fixture = fixture(receiver.url(), 12, "{\"concurrency\":true}");
            try (var firstWorker = worker(receiver.url(), 2, Map.of("relay.worker.request-timeout", "15s"))) {
                var received = new ArrayList<ControllableReceiver.Received>();
                received.add(receiver.next(Duration.ofSeconds(15)));
                received.add(receiver.next(Duration.ofSeconds(15)));
                assertThat(received).doesNotContainNull();
                assertDeliveryRowUnlocked(UUID.fromString(received.getFirst().header("X-Relay-Delivery-Id")));
                assertThat(receiver.next(Duration.ofMillis(600))).isNull();
                assertThat(receiver.maximumActive()).isEqualTo(2);
                assertThat(inFlight(fixture.workspaceId())).isEqualTo(2);
                try (var secondWorker = worker(receiver.url(), 2, Map.of("relay.worker.request-timeout", "15s"))) {
                    received.add(receiver.next(Duration.ofSeconds(15)));
                    received.add(receiver.next(Duration.ofSeconds(15)));
                    assertThat(received).doesNotContainNull();
                    assertThat(receiver.next(Duration.ofMillis(600))).isNull();
                    assertThat(inFlight(fixture.workspaceId())).isEqualTo(4);
                    release.countDown();
                    awaitTerminal(fixture, "SUCCEEDED");
                    for (int index = received.size(); index < fixture.work().size(); index++) {
                        var next = receiver.next(Duration.ofSeconds(5));
                        assertThat(next).isNotNull();
                        received.add(next);
                    }
                    var delivered = new HashSet<UUID>();
                    for (var request : received) {
                        assertThat(delivered.add(UUID.fromString(request.header("X-Relay-Delivery-Id")))).isTrue();
                        assertThat(request.header("X-Relay-Attempt")).isEqualTo("1");
                    }
                    assertThat(delivered).containsExactlyInAnyOrderElementsOf(
                            fixture.work().stream().map(Work::deliveryId).toList());
                    assertThat(receiver.maximumActive()).isLessThanOrEqualTo(4);
                    for (var work : fixture.work()) assertThat(attemptCount(work.deliveryId())).isEqualTo(1);
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void loopbackIsBlockedByDefaultAndDevelopmentExceptionRequiresTheExactReceiverUrl() throws Exception {
        try (var receiver = new ControllableReceiver()) {
            URI httpsLoopback = URI.create(receiver.url().toString().replace("http://", "https://"));
            var secure = fixture(httpsLoopback, 1, "{\"blocked\":true}");
            try (var worker = defaultWorker()) {
                awaitTerminal(secure, "FAILED");
                assertMetadata(attemptId(secure.work().getFirst().deliveryId()), "FAILED", null, "DESTINATION_BLOCKED");
                assertThat(receiver.next(Duration.ofMillis(300))).isNull();
            }
            var mismatched = fixture(URI.create(receiver.url() + "?changed=true"), 1, "{\"exact\":true}");
            try (var worker = worker(receiver.url(), 1, Map.of())) {
                awaitTerminal(mismatched, "FAILED");
                assertMetadata(attemptId(mismatched.work().getFirst().deliveryId()), "FAILED", null, "DESTINATION_BLOCKED");
                assertThat(receiver.next(Duration.ofMillis(300))).isNull();
            }
        }
    }

    @Test
    void forciblyKilledPackagedWorkerRecoversUnknownAttemptWithStableEventAndDeliveryIds() throws Exception {
        var release = new CountDownLatch(1);
        try (var receiver = new ControllableReceiver()) {
            receiver.block(release);
            String marker = "crash-payload-" + UUID.randomUUID();
            var fixture = fixture(receiver.url(), 1, "{\"private\":\"" + marker + "\"}");
            var work = fixture.work().getFirst();
            try (var firstWorker = childWorker(receiver.url(), "before-kill")) {
                var original = receiver.next(Duration.ofSeconds(35));
                assertThat(original).as("packaged child reaches the receiver").isNotNull();
                assertHeaders(original, work, 1);
                UUID originalAttempt = UUID.fromString(original.header("X-Relay-Attempt-Id"));
                assertThat(status(work.deliveryId())).isEqualTo("IN_FLIGHT");
                firstWorker.kill();
                assertThat(firstWorker.process().isAlive()).isFalse();
                assertThat(jdbc.queryForObject("SELECT outcome FROM delivery_attempts WHERE id = ?", String.class,
                        originalAttempt)).isEqualTo("IN_PROGRESS");
                assertThat(status(work.deliveryId())).isEqualTo("IN_FLIGHT");
                receiver.respond(200, "recovered");
                release.countDown();
                await("original disconnected handler exits", Duration.ofSeconds(5), () -> receiver.active() == 0);
                assertThat(jdbc.update("UPDATE deliveries SET lease_expires_at = clock_timestamp() - interval '1 second' "
                        + "WHERE id = ? AND status = 'IN_FLIGHT'", work.deliveryId())).isEqualTo(1);
                try (var replacement = childWorker(receiver.url(), "after-kill")) {
                    var recovered = receiver.next(Duration.ofSeconds(35));
                    assertThat(recovered).isNotNull();
                    assertHeaders(recovered, work, 2);
                    UUID currentAttempt = UUID.fromString(recovered.header("X-Relay-Attempt-Id"));
                    assertThat(currentAttempt).isNotEqualTo(originalAttempt);
                    awaitTerminal(fixture, "SUCCEEDED");
                    var outcomes = jdbc.queryForList("SELECT outcome FROM delivery_attempts WHERE delivery_id = ? "
                            + "ORDER BY claim_generation", String.class, work.deliveryId());
                    assertThat(outcomes).containsExactly("UNKNOWN", "SUCCEEDED");
                    assertMetadata(currentAttempt, "SUCCEEDED", 200, null);
                    assertThat(attemptCount(work.deliveryId())).isEqualTo(2);
                    replacement.kill();
                    assertThat(Files.readString(replacement.log())).doesNotContain(marker);
                }
                assertThat(Files.readString(firstWorker.log())).doesNotContain(marker);
            } finally {
                release.countDown();
            }
        }
    }

    private ConfigurableApplicationContext worker(URI receiver, int concurrency, Map<String, String> overrides) {
        var properties = workerProperties(receiver, concurrency);
        properties.putAll(overrides);
        return application().profiles("worker", "development").web(WebApplicationType.NONE)
                .run(arguments(properties));
    }

    private ApiFixture apiFixture(URI receiver, String payload) throws Exception {
        var developmentApi = application().profiles("development").web(WebApplicationType.SERVLET).run(
                "--server.port=0", "--relay.delivery.allow-local-receiver=true",
                "--relay.delivery.local-receiver-url=" + receiver);
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
        try {
            int port = ((WebServerApplicationContext) developmentApi).getWebServer().getPort();
            URI origin = URI.create("http://127.0.0.1:" + port);
            String csrf = json(apiRequest(origin, http, "GET", "/api/auth/csrf", null, null, Map.of()))
                    .get("token").asString();
            String email = "actual-delivery-" + UUID.randomUUID() + "@example.test";
            String password = "actual receiver password 42!";
            var registered = apiRequest(origin, http, "POST", "/api/auth/register",
                    JSON.writeValueAsString(Map.of("email", email, "password", password)), "application/json",
                    Map.of("X-CSRF-TOKEN", csrf));
            assertThat(registered.statusCode()).isEqualTo(201);
            accounts.add(UUID.fromString(json(registered).get("id").asString()));
            assertThat(apiRequest(origin, http, "POST", "/api/auth/login", "email=" + encode(email)
                    + "&password=" + encode(password), "application/x-www-form-urlencoded",
                    Map.of("X-CSRF-TOKEN", csrf)).statusCode()).isEqualTo(204);
            csrf = json(apiRequest(origin, http, "GET", "/api/auth/csrf", null, null, Map.of()))
                    .get("token").asString();
            var workspace = apiRequest(origin, http, "POST", "/api/workspaces", "{\"name\":\"Actual delivery\"}",
                    "application/json", Map.of("X-CSRF-TOKEN", csrf));
            assertThat(workspace.statusCode()).isEqualTo(201);
            String workspaceId = json(workspace).get("id").asString();
            String endpointPath = "/api/workspaces/" + workspaceId + "/endpoints";
            var endpoint = apiRequest(origin, http, "POST", endpointPath,
                    JSON.writeValueAsString(Map.of("url", receiver.toString())), "application/json",
                    Map.of("X-CSRF-TOKEN", csrf));
            assertThat(endpoint.statusCode()).as("development API accepts its exact configured HTTP receiver").isEqualTo(201);
            assertThat(apiRequest(origin, http, "POST", endpointPath,
                    JSON.writeValueAsString(Map.of("url", receiver + "?alternate=true")), "application/json",
                    Map.of("X-CSRF-TOKEN", csrf)).statusCode()).as("development exception is exact").isEqualTo(400);
            String endpointId = json(endpoint).get("id").asString();
            var key = apiRequest(origin, http, "POST", "/api/workspaces/" + workspaceId + "/api-keys",
                    "{\"name\":\"Actual receiver client\"}", "application/json", Map.of("X-CSRF-TOKEN", csrf));
            assertThat(key.statusCode()).isEqualTo(201);
            String apiKey = json(key).get("key").asString();
            var accepted = apiRequest(origin, http, "POST", "/api/v1/endpoints/" + endpointId + "/events",
                    payload, "application/json", Map.of("Authorization", "Bearer " + apiKey));
            assertThat(accepted.statusCode()).isEqualTo(202);
            var work = new Work(UUID.fromString(json(accepted).get("eventId").asString()),
                    UUID.fromString(json(accepted).get("deliveryId").asString()));
            return new ApiFixture(developmentApi, http, origin, apiKey,
                    new Fixture(UUID.fromString(workspaceId), List.of(work)));
        } catch (Exception | AssertionError failure) {
            http.close();
            developmentApi.close();
            throw failure;
        }
    }

    private static HttpResponse<String> apiRequest(URI origin, HttpClient http, String method, String path,
                                                    String body, String contentType, Map<String, String> headers)
            throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (contentType != null) request.header("Content-Type", contentType);
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private ConfigurableApplicationContext defaultWorker() {
        return application().profiles("worker").web(WebApplicationType.NONE)
                .run("--spring.main.keep-alive=false", "--relay.worker.concurrency=1");
    }

    private static java.util.HashMap<String, String> workerProperties(URI receiver, int concurrency) {
        var properties = new java.util.HashMap<String, String>();
        properties.put("spring.main.keep-alive", "false");
        properties.put("relay.worker.concurrency", Integer.toString(concurrency));
        properties.put("relay.worker.poll-interval", "250ms");
        properties.put("relay.worker.request-timeout", "5s");
        properties.put("relay.worker.connect-timeout", "2s");
        properties.put("relay.worker.dns-timeout", "1s");
        properties.put("relay.worker.lease", "30s");
        properties.put("relay.worker.lease-safety", "1s");
        properties.put("relay.worker.recovery-batch", "100");
        properties.put("relay.delivery.allow-local-receiver", "true");
        properties.put("relay.delivery.local-receiver-url", receiver.toString());
        return properties;
    }

    private ChildWorker childWorker(URI receiver, String label) throws Exception {
        Path jar = Path.of("target", "relay-0.0.1-SNAPSHOT.jar").toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) jar = Path.of("backend", "target", "relay-0.0.1-SNAPSHOT.jar").toAbsolutePath().normalize();
        assertThat(jar).as("packaged application must exist before integration tests").isRegularFile();
        Path logDirectory = jar.getParent().getParent().getParent().resolve("work").resolve("phase3b-tests");
        Files.createDirectories(logDirectory);
        Path log = logDirectory.resolve(label + "-" + UUID.randomUUID() + ".log");
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-jar");
        command.add(jar.toString());
        command.add("--spring.profiles.active=worker,development");
        var properties = workerProperties(receiver, 1);
        properties.put("spring.main.keep-alive", "true");
        properties.put("relay.worker.request-timeout", "15s");
        command.addAll(List.of(arguments(properties)));
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("DB_URL", api.getEnvironment().getRequiredProperty("spring.datasource.url"));
        builder.environment().put("DB_USERNAME", api.getEnvironment().getRequiredProperty("spring.datasource.username"));
        builder.environment().put("DB_PASSWORD", api.getEnvironment().getRequiredProperty("spring.datasource.password"));
        builder.environment().put("RELAY_SIGNING_MASTER_KEY", SIGNING_MASTER_KEY);
        return new ChildWorker(builder.start(), log);
    }

    private Fixture fixture(URI endpoint, int count, String payload) {
        UUID accountId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        accounts.add(accountId);
        jdbc.update("INSERT INTO accounts (id, email, password_hash) VALUES (?, ?, ?)", accountId,
                "http-worker-" + accountId + "@example.test", "test-only-noncredential");
        jdbc.update("INSERT INTO workspaces (id, owner_account_id, name) VALUES (?, ?, ?)",
                workspaceId, accountId, "Actual HTTP worker fixture");
        jdbc.update("INSERT INTO endpoints (id, workspace_id, url) VALUES (?, ?, ?)", endpointId, workspaceId, endpoint.toString());
        var work = new ArrayList<Work>();
        for (int index = 0; index < count; index++) {
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            jdbc.update("INSERT INTO events (id, workspace_id, endpoint_id, payload, request_digest) VALUES (?, ?, ?, ?, ?)",
                    eventId, workspaceId, endpointId, payload.getBytes(StandardCharsets.UTF_8), new byte[32]);
            jdbc.update("INSERT INTO deliveries (id, workspace_id, event_id) VALUES (?, ?, ?)", deliveryId, workspaceId, eventId);
            work.add(new Work(eventId, deliveryId));
        }
        return new Fixture(workspaceId, work);
    }

    private void awaitTerminal(Fixture fixture, String expected) {
        await("all fixture deliveries become " + expected, Duration.ofSeconds(25), () ->
                jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE workspace_id = ? AND status = ?",
                        Integer.class, fixture.workspaceId(), expected) == fixture.work().size());
    }

    private void assertDeliveryRowUnlocked(UUID deliveryId) throws Exception {
        try (var connection = api.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = '500ms'");
                }
                try (var statement = connection.prepareStatement("SELECT id FROM deliveries WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, deliveryId);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).as("delivery row remains unlocked during outbound HTTP").isTrue();
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void await(String label, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted awaiting " + label);
        }
        assertThat(condition.getAsBoolean()).as(label).isTrue();
    }

    private int inFlight(UUID workspaceId) {
        return jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE workspace_id = ? AND status = 'IN_FLIGHT'",
                Integer.class, workspaceId);
    }

    private int attemptCount(UUID deliveryId) {
        return jdbc.queryForObject("SELECT count(*) FROM delivery_attempts WHERE delivery_id = ?", Integer.class, deliveryId);
    }

    private String status(UUID deliveryId) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE id = ?", String.class, deliveryId);
    }

    private UUID attemptId(UUID deliveryId) {
        return jdbc.queryForObject("SELECT id FROM delivery_attempts WHERE delivery_id = ?", UUID.class, deliveryId);
    }

    private void assertMetadata(UUID attemptId, String outcome, Integer httpStatus, String failureCode) {
        var row = jdbc.queryForMap("SELECT outcome, http_status, failure_code, duration_ms, finished_at "
                + "FROM delivery_attempts WHERE id = ?", attemptId);
        assertThat(row.get("outcome")).isEqualTo(outcome);
        assertThat(row.get("http_status")).isEqualTo(httpStatus);
        assertThat(row.get("failure_code")).isEqualTo(failureCode);
        assertThat(row.get("duration_ms")).isInstanceOf(Number.class);
        assertThat(((Number) row.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(row.get("finished_at")).isNotNull();
    }

    private static void assertHeaders(ControllableReceiver.Received received, Work work, long generation) {
        assertThat(received.header("Content-Type")).startsWith("application/json");
        assertThat(received.header("X-Relay-Event-Id")).isEqualTo(work.eventId().toString());
        assertThat(received.header("X-Relay-Delivery-Id")).isEqualTo(work.deliveryId().toString());
        assertThat(UUID.fromString(received.header("X-Relay-Attempt-Id"))).isNotNull();
        assertThat(received.header("X-Relay-Attempt")).isEqualTo(Long.toString(generation));
    }

    private static String[] arguments(Map<String, String> properties) {
        return properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private record Work(UUID eventId, UUID deliveryId) { }
    private record Fixture(UUID workspaceId, List<Work> work) { }

    private record ApiFixture(ConfigurableApplicationContext context, HttpClient http, URI origin,
                              String apiKey, Fixture fixture) implements AutoCloseable {
        @Override
        public void close() {
            http.close();
            context.close();
        }
    }

    private record ChildWorker(Process process, Path log) implements AutoCloseable {
        void kill() throws InterruptedException {
            if (process.isAlive()) process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("child worker terminates").isTrue();
        }

        @Override
        public void close() throws InterruptedException {
            kill();
        }
    }
}
