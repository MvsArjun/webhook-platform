package dev.relay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import dev.relay.delivery.ClaimedDelivery;
import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryProcessor;
import dev.relay.delivery.DeliveryResult;
import dev.relay.delivery.ReplayService;
import dev.relay.endpoints.EndpointService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL and HTTP; retry eligibility advances through an injected clock, never sleeps. */
class RetryDeliveryIT extends HttpIntegrationTestSupport {
    private static final Duration LEASE = Duration.ofSeconds(30);
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
    void realFailureThenRecoveryUsesDueTimeAndPreservesPayloadAndStableIdentifiers() throws Exception {
        try (var receiver = new ControllableReceiver(); var runtime = runtime(receiver)) {
            String payload = "{ \"type\" : \"retry\", \"label\" : \"café\" }";
            var fixture = fixture(receiver.url(), 3, payload);
            receiver.respond(500, "temporary failure");

            var first = claim(runtime, fixture);
            runtime.processor().process(first);
            var original = receiver.next(Duration.ofSeconds(2));
            assertRequest(original, fixture, first, payload);
            assertThat(status(fixture)).isEqualTo("RETRY_WAIT");
            assertThat(runtime.claims().claim(1, LEASE)).isEmpty();

            Instant due = nextAttemptAt(fixture);
            assertThat(due).isAfter(runtime.clock().instant());
            runtime.clock().set(due.minusMillis(1));
            assertThat(runtime.claims().claim(1, LEASE)).as("backoff has not expired").isEmpty();
            runtime.clock().set(due);
            receiver.respond(200, "recovered");
            var second = claim(runtime, fixture);
            runtime.processor().process(second);
            assertRequest(receiver.next(Duration.ofSeconds(2)), fixture, second, payload);

            assertThat(second.generation()).isEqualTo(first.generation() + 1);
            assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
            assertThat(status(fixture)).isEqualTo("SUCCEEDED");
            assertThat(nextAttemptAtOrNull(fixture)).isNull();
            assertHistory(fixture, List.of("FAILED", "SUCCEEDED"), List.of(0L, 0L), List.of(1, 2));
            assertThat(jdbc.queryForList("SELECT http_status FROM delivery_attempts WHERE delivery_id = ? "
                    + "ORDER BY claim_generation", Integer.class, fixture.deliveryId())).containsExactly(500, 200);
            assertThat(runtime.claims().claim(1, LEASE)).isEmpty();
        }
    }

    @Test
    void retryableHttpFailureExhaustsTheSnapshottedRunBudget() throws Exception {
        try (var receiver = new ControllableReceiver(); var runtime = runtime(receiver)) {
            // Runtime default is five. This delivery must retain its smaller accepted budget.
            var fixture = fixture(receiver.url(), 2, "{\"exhaust\":true}");
            receiver.respond(503, "still unavailable");
            for (int attempt = 1; attempt <= 2; attempt++) {
                runtime.processor().process(claim(runtime, fixture));
                assertThat(receiver.next(Duration.ofSeconds(2))).isNotNull();
                if (attempt == 1) {
                    assertThat(status(fixture)).isEqualTo("RETRY_WAIT");
                    runtime.clock().set(nextAttemptAt(fixture));
                }
            }
            assertThat(status(fixture)).isEqualTo("FAILED");
            assertThat(nextAttemptAtOrNull(fixture)).isNull();
            assertCounters(fixture, 0, 2, 2);
            assertHistory(fixture, List.of("FAILED", "FAILED"), List.of(0L, 0L), List.of(1, 2));
            runtime.clock().set(runtime.clock().instant().plus(Duration.ofDays(1)));
            assertThat(runtime.claims().claim(1, LEASE)).isEmpty();
        }
    }

    @Test
    void expiredUnknownAttemptsConsumeTheSameFiniteBudget() throws Exception {
        try (var receiver = new ControllableReceiver(); var runtime = runtime(receiver)) {
            var fixture = fixture(receiver.url(), 2, "{\"abandoned\":true}");
            var first = claim(runtime, fixture);
            expire(fixture);
            assertThat(runtime.claims().recoverExpired(100)).isEqualTo(1);
            assertThat(status(fixture)).isEqualTo("RETRY_WAIT");
            assertThat(runtime.claims().claim(1, LEASE)).isEmpty();
            runtime.clock().set(nextAttemptAt(fixture));
            var second = claim(runtime, fixture);
            assertThat(second.generation()).isEqualTo(first.generation() + 1);
            expire(fixture);
            assertThat(runtime.claims().recoverExpired(100)).isEqualTo(1);

            assertThat(status(fixture)).isEqualTo("FAILED");
            assertCounters(fixture, 0, 2, 2);
            assertHistory(fixture, List.of("UNKNOWN", "UNKNOWN"), List.of(0L, 0L), List.of(1, 2));
            assertThat(runtime.claims().recoverExpired(100)).isZero();
            runtime.clock().set(runtime.clock().instant().plus(Duration.ofDays(1)));
            assertThat(runtime.claims().claim(1, LEASE)).isEmpty();
            assertThat(runtime.claims().complete(first,
                    new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 1))).isFalse();
            assertThat(runtime.claims().complete(second,
                    new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 1))).isFalse();
            assertThat(status(fixture)).isEqualTo("FAILED");
            assertThat(receiver.next(Duration.ZERO)).isNull();
        }
    }

    @Test
    void nonRetryableHttpResponseFailsImmediatelyWithUnusedBudget() throws Exception {
        try (var receiver = new ControllableReceiver(); var runtime = runtime(receiver)) {
            var fixture = fixture(receiver.url(), 5, "{\"invalid\":true}");
            receiver.respond(400, "invalid request");
            runtime.processor().process(claim(runtime, fixture));
            assertThat(receiver.next(Duration.ofSeconds(2))).isNotNull();
            assertThat(status(fixture)).isEqualTo("FAILED");
            assertCounters(fixture, 0, 1, 5);
            assertHistory(fixture, List.of("FAILED"), List.of(0L), List.of(1));
            assertThat(nextAttemptAtOrNull(fixture)).isNull();
            runtime.clock().set(runtime.clock().instant().plus(Duration.ofDays(1)));
            assertThat(runtime.claims().claim(1, LEASE)).isEmpty();
        }
    }

    @Test
    void pausedEndpointRetainsItsDueRetryUntilItIsUnpaused() throws Exception {
        try (var receiver = new ControllableReceiver(); var runtime = runtime(receiver)) {
            var fixture = fixture(receiver.url(), 3, "{\"paused\":true}");
            receiver.respond(429, "slow down");
            runtime.processor().process(claim(runtime, fixture));
            assertThat(receiver.next(Duration.ofSeconds(2))).isNotNull();
            var endpoints = api.getBean(EndpointService.class);
            endpoints.setPaused(fixture.accountId(), fixture.workspaceId(), fixture.endpointId(), true);
            runtime.clock().set(nextAttemptAt(fixture).plusSeconds(1));

            assertThat(runtime.claims().claim(1, LEASE)).isEmpty();
            assertThat(status(fixture)).isEqualTo("RETRY_WAIT");
            assertCounters(fixture, 0, 1, 3);
            endpoints.setPaused(fixture.accountId(), fixture.workspaceId(), fixture.endpointId(), false);
            receiver.respond(200, "resumed");
            runtime.processor().process(claim(runtime, fixture));
            assertThat(receiver.next(Duration.ofSeconds(2))).isNotNull();
            assertThat(status(fixture)).isEqualTo("SUCCEEDED");
            assertHistory(fixture, List.of("FAILED", "SUCCEEDED"), List.of(0L, 0L), List.of(1, 2));
        }
    }

    @Test
    void replayStartsANewBudgetAndOldHttpCompletionCannotRewriteItsAttempt() throws Exception {
        try (var receiver = new ControllableReceiver(); var runtime = runtime(receiver)) {
            var fixture = fixture(receiver.url(), 1, "{\"replay\":true}");
            receiver.respond(500, "exhaust original run");
            var original = claim(runtime, fixture);
            runtime.processor().process(original);
            assertThat(receiver.next(Duration.ofSeconds(2))).isNotNull();
            assertThat(status(fixture)).isEqualTo("FAILED");

            var replay = runtime.context().getBean(ReplayService.class)
                    .replay(fixture.workspaceId(), fixture.eventId(), 0);
            assertThat(replay.eventId()).isEqualTo(fixture.eventId());
            assertThat(replay.deliveryId()).isEqualTo(fixture.deliveryId());
            assertThat(replay.replayGeneration()).isEqualTo(1);
            assertCounters(fixture, 1, 0, 5);
            var current = claim(runtime, fixture);
            assertThat(current.generation()).isEqualTo(original.generation() + 1);

            assertThat(runtime.claims().complete(original,
                    new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 99))).isFalse();
            assertThat(status(fixture)).isEqualTo("IN_FLIGHT");
            assertThat(jdbc.queryForMap("SELECT outcome, http_status, duration_ms FROM delivery_attempts WHERE id = ?",
                    current.attemptId())).containsEntry("outcome", "IN_PROGRESS")
                    .containsEntry("http_status", null).containsEntry("duration_ms", null);
            receiver.respond(200, "new run succeeds");
            runtime.processor().process(current);
            assertThat(receiver.next(Duration.ofSeconds(2))).isNotNull();
            assertThat(status(fixture)).isEqualTo("SUCCEEDED");
            assertHistory(fixture, List.of("FAILED", "SUCCEEDED"), List.of(0L, 1L), List.of(1, 1));
            assertThat(jdbc.queryForObject("SELECT http_status FROM delivery_attempts WHERE id = ?",
                    Integer.class, original.attemptId())).isEqualTo(500);
        }
    }

    @Test
    void retryScheduleAndAttemptResultRollBackTogetherWhenHistoryWriteFails() throws Exception {
        try (var receiver = new ControllableReceiver(); var runtime = runtime(receiver)) {
            var fixture = fixture(receiver.url(), 3, "{\"atomic\":true}");
            var claim = claim(runtime, fixture);
            String constraint = "retry_failure_" + UUID.randomUUID().toString().replace("-", "");
            jdbc.execute("ALTER TABLE delivery_attempts ADD CONSTRAINT " + constraint
                    + " CHECK (id <> '" + claim.attemptId() + "'::uuid OR outcome = 'IN_PROGRESS')");
            var failure = new DeliveryResult(CompletionOutcome.FAILED, 503, "HTTP_STATUS", 7);
            try {
                assertThatThrownBy(() -> runtime.claims().complete(claim, failure))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThat(status(fixture)).isEqualTo("IN_FLIGHT");
                assertThat(nextAttemptAtOrNull(fixture)).isNull();
                assertThat(jdbc.queryForMap("SELECT outcome, http_status, failure_code, duration_ms "
                        + "FROM delivery_attempts WHERE id = ?", claim.attemptId()))
                        .containsEntry("outcome", "IN_PROGRESS").containsEntry("http_status", null)
                        .containsEntry("failure_code", null).containsEntry("duration_ms", null);
            } finally {
                jdbc.execute("ALTER TABLE delivery_attempts DROP CONSTRAINT " + constraint);
            }
            assertThat(runtime.claims().complete(claim, failure)).isTrue();
            assertThat(status(fixture)).isEqualTo("RETRY_WAIT");
            assertCounters(fixture, 0, 1, 3);
            assertHistory(fixture, List.of("FAILED"), List.of(0L), List.of(1));
        }
    }

    private Runtime runtime(ControllableReceiver receiver) {
        var clock = new MutableClock(Instant.parse("2040-01-01T00:00:00Z"));
        var context = application().initializers(application -> application.getBeanFactory()
                        .registerSingleton("testRetryClock", clock))
                .profiles("worker", "development").web(WebApplicationType.NONE)
                .run("--spring.main.keep-alive=false", "--relay.worker.enabled=false",
                        "--relay.delivery.allow-local-receiver=true", "--relay.delivery.local-receiver-url=" + receiver.url(),
                        "--relay.retry.max-attempts=5", "--relay.retry.base-delay=1s", "--relay.retry.max-delay=10s");
        return new Runtime(context, clock, context.getBean(DeliveryClaimService.class),
                context.getBean(DeliveryProcessor.class));
    }

    private Fixture fixture(URI endpoint, int maximumAttempts, String payload) {
        UUID accountId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        accounts.add(accountId);
        jdbc.update("INSERT INTO accounts (id, email, password_hash) VALUES (?, ?, ?)", accountId,
                "retry-" + accountId + "@example.test", "test-only-noncredential");
        jdbc.update("INSERT INTO workspaces (id, owner_account_id, name) VALUES (?, ?, ?)",
                workspaceId, accountId, "Retry integration fixture");
        jdbc.update("INSERT INTO endpoints (id, workspace_id, url) VALUES (?, ?, ?)", endpointId, workspaceId, endpoint.toString());
        jdbc.update("INSERT INTO events (id, workspace_id, endpoint_id, payload, request_digest) VALUES (?, ?, ?, ?, ?)",
                eventId, workspaceId, endpointId, payload.getBytes(StandardCharsets.UTF_8), new byte[32]);
        jdbc.update("INSERT INTO deliveries (id, workspace_id, event_id, max_attempts) VALUES (?, ?, ?, ?)",
                deliveryId, workspaceId, eventId, maximumAttempts);
        return new Fixture(accountId, workspaceId, endpointId, eventId, deliveryId);
    }

    private ClaimedDelivery claim(Runtime runtime, Fixture fixture) {
        var batch = runtime.claims().claim(1, LEASE);
        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst().deliveryId()).isEqualTo(fixture.deliveryId());
        return batch.getFirst();
    }

    private String status(Fixture fixture) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE id = ?", String.class, fixture.deliveryId());
    }

    private Instant nextAttemptAt(Fixture fixture) {
        Instant value = nextAttemptAtOrNull(fixture);
        assertThat(value).as("a retry has a durable due time").isNotNull();
        return value;
    }

    private Instant nextAttemptAtOrNull(Fixture fixture) {
        Timestamp value = jdbc.queryForObject("SELECT next_attempt_at FROM deliveries WHERE id = ?",
                Timestamp.class, fixture.deliveryId());
        return value == null ? null : value.toInstant();
    }

    private void expire(Fixture fixture) {
        assertThat(jdbc.update("UPDATE deliveries SET lease_expires_at = clock_timestamp() - interval '1 second' "
                + "WHERE id = ? AND status = 'IN_FLIGHT'", fixture.deliveryId())).isEqualTo(1);
    }

    private void assertCounters(Fixture fixture, long replayGeneration, int attemptsUsed, int maximumAttempts) {
        var counters = jdbc.queryForMap("SELECT replay_generation, run_attempts, max_attempts FROM deliveries WHERE id = ?",
                fixture.deliveryId());
        assertThat(((Number) counters.get("replay_generation")).longValue()).isEqualTo(replayGeneration);
        assertThat(((Number) counters.get("run_attempts")).intValue()).isEqualTo(attemptsUsed);
        assertThat(((Number) counters.get("max_attempts")).intValue()).isEqualTo(maximumAttempts);
    }

    private void assertHistory(Fixture fixture, List<String> outcomes, List<Long> replays, List<Integer> attempts) {
        var rows = jdbc.queryForList("SELECT outcome, replay_generation, run_attempt FROM delivery_attempts "
                + "WHERE delivery_id = ? ORDER BY claim_generation", fixture.deliveryId());
        assertThat(rows.stream().map(row -> (String) row.get("outcome")).toList()).containsExactlyElementsOf(outcomes);
        assertThat(rows.stream().map(row -> ((Number) row.get("replay_generation")).longValue()).toList())
                .containsExactlyElementsOf(replays);
        assertThat(rows.stream().map(row -> ((Number) row.get("run_attempt")).intValue()).toList())
                .containsExactlyElementsOf(attempts);
    }

    private static void assertRequest(ControllableReceiver.Received request, Fixture fixture,
                                      ClaimedDelivery claim, String payload) {
        assertThat(request).isNotNull();
        assertThat(request.body()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(request.header("X-Relay-Event-Id")).isEqualTo(fixture.eventId().toString());
        assertThat(request.header("X-Relay-Delivery-Id")).isEqualTo(fixture.deliveryId().toString());
        assertThat(request.header("X-Relay-Attempt-Id")).isEqualTo(claim.attemptId().toString());
        assertThat(request.header("X-Relay-Attempt")).isEqualTo(Long.toString(claim.generation()));
    }

    private record Fixture(UUID accountId, UUID workspaceId, UUID endpointId, UUID eventId, UUID deliveryId) {}

    private record Runtime(ConfigurableApplicationContext context, MutableClock clock,
                           DeliveryClaimService claims, DeliveryProcessor processor) implements AutoCloseable {
        @Override public void close() { context.close(); }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time;
        private final ZoneId zone;

        MutableClock(Instant initial) { this(new AtomicReference<>(initial), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> time, ZoneId zone) { this.time = time; this.zone = zone; }
        void set(Instant next) { time.set(next); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(time, zone); }
        @Override public Instant instant() { return time.get(); }
    }
}
