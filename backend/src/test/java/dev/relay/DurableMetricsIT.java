package dev.relay;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.relay.observability.metrics.CachedDeliveryMetrics;
import dev.relay.observability.metrics.MetricsSettings;
import dev.relay.observability.metrics.MetricsSnapshotRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Retained PostgreSQL rows and actual query cancellation, not HTTP handlers or simulated aggregates. */
@Timeout(30)
@ExtendWith(OutputCaptureExtension.class)
class DurableMetricsIT extends HttpIntegrationTestSupport {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (UUID account : accounts) {
            for (String table : List.of("delivery_attempts", "deliveries", "events", "endpoint_signing_keys", "api_keys", "endpoints")) {
                jdbc.update("DELETE FROM " + table + " WHERE workspace_id IN "
                        + "(SELECT id FROM workspaces WHERE owner_account_id = ?)", account);
            }
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", account);
            jdbc.update("DELETE FROM accounts WHERE id = ?", account);
        }
    }

    @Test
    void durableStateQueueEligibilityRetryRunsAndObservedTimingsMatchTheirStoredMeaning() {
        var owner = fixture();
        var other = fixture();
        UUID paused = endpoint(owner.workspaceId(), true);
        delivery(owner, owner.endpointId(), "PENDING", 0, 0, 5, 0, NOW.minusSeconds(120), null);
        delivery(owner, owner.endpointId(), "RETRY_WAIT", 1, 1, 5, 0, NOW.minusSeconds(90), NOW);
        delivery(owner, owner.endpointId(), "RETRY_WAIT", 1, 1, 5, 0, NOW.minusSeconds(80), NOW.plusSeconds(1));
        delivery(owner, owner.endpointId(), "PENDING", 5, 5, 5, 0, NOW.minusSeconds(300), null);
        delivery(owner, paused, "PENDING", 0, 0, 5, 0, NOW.minusSeconds(500), null);
        delivery(owner, paused, "RETRY_WAIT", 1, 1, 5, 0, NOW.minusSeconds(400), NOW.minusSeconds(10));
        var active = delivery(owner, owner.endpointId(), "IN_FLIGHT", 1, 1, 5, 0, NOW.minusSeconds(40), null);
        var success = delivery(owner, owner.endpointId(), "SUCCEEDED", 3, 1, 5, 1, NOW.minusSeconds(60), null);
        var failed = delivery(owner, owner.endpointId(), "FAILED", 1, 1, 5, 0, NOW.minusSeconds(50), null);
        delivery(other, other.endpointId(), "PENDING", 0, 0, 5, 0, NOW.minusSeconds(30), null);
        attempt(owner, success, 1, 1, 0, "SUCCEEDED", NOW.minusSeconds(58), 100L);
        attempt(owner, success, 2, 2, 0, "FAILED", NOW.minusSeconds(57), 300L);
        attempt(owner, success, 3, 1, 1, "SUCCEEDED", NOW.minusSeconds(56), 500L);
        attempt(owner, failed, 1, 1, 0, "UNKNOWN", NOW.minusSeconds(46), null);
        attempt(owner, active, 1, 1, 0, "IN_PROGRESS", NOW.minusSeconds(34), null);

        var registry = new SimpleMeterRegistry();
        try (var metrics = collector(registry)) {
            assertThat(registry.get("relay.queue.ready").gauge().value()).isNaN();
            assertThat(metrics.refreshNow()).isTrue();
            state(registry, "PENDING", 4);
            state(registry, "RETRY_WAIT", 3);
            state(registry, "IN_FLIGHT", 1);
            state(registry, "SUCCEEDED", 1);
            state(registry, "FAILED", 1);
            outcome(registry, "SUCCEEDED", 2);
            outcome(registry, "FAILED", 1);
            outcome(registry, "UNKNOWN", 1);
            outcome(registry, "IN_PROGRESS", 1);
            // The full first workspace still contributes eligible rows: queue readiness is not capacity.
            assertThat(registry.get("relay.queue.ready").gauge().value()).isEqualTo(3);
            assertThat(milliseconds(registry, "relay.queue.ready.oldest.event.age")).isEqualTo(120_000);
            assertThat(registry.get("relay.attempts.automatic.retry").gauge().value()).isEqualTo(1);
            assertThat(registry.get("relay.attempts.manual.replay").gauge().value()).isEqualTo(1);
            assertThat(registry.get("relay.delivery.initial.queue.delay.samples").gauge().value()).isEqualTo(3);
            assertThat(milliseconds(registry, "relay.delivery.initial.queue.delay.mean")).isEqualTo(4000);
            assertThat(milliseconds(registry, "relay.delivery.initial.queue.delay.max")).isEqualTo(6000);
            assertThat(registry.get("relay.delivery.transport.latency.samples").gauge().value()).isEqualTo(3);
            assertThat(milliseconds(registry, "relay.delivery.transport.latency.mean")).isEqualTo(300);
            assertThat(milliseconds(registry, "relay.delivery.transport.latency.max")).isEqualTo(500);
            assertThat(registry.get("relay.metrics.refresh.success").gauge().value()).isEqualTo(1);
        } finally { registry.close(); }
    }

    @Test
    void blockedAggregateTimesOutRetainsItsCacheAndDeletionCanReduceSnapshotGauges(CapturedOutput output) throws Exception {
        var fixture = fixture();
        var work = delivery(fixture, fixture.endpointId(), "FAILED", 1, 1, 5, 0, NOW.minusSeconds(10), null);
        attempt(fixture, work, 1, 1, 0, "UNKNOWN", NOW.minusSeconds(8), null);
        var registry = new SimpleMeterRegistry();
        try (var metrics = collector(registry)) {
            assertThat(metrics.refreshNow()).isTrue();
            state(registry, "FAILED", 1);
            try (var lock = jdbc.getDataSource().getConnection()) {
                lock.setAutoCommit(false);
                try {
                    try (var statement = lock.createStatement()) { statement.execute("LOCK TABLE deliveries IN ACCESS EXCLUSIVE MODE"); }
                    // Scrapes cannot wait on this table lock because getters read only the cache.
                    long scrapeStarted = System.nanoTime();
                    for (int index = 0; index < 100; index++) state(registry, "FAILED", 1);
                    assertThat(Duration.ofNanos(System.nanoTime() - scrapeStarted)).isLessThan(Duration.ofSeconds(1));
                    long refreshStarted = System.nanoTime();
                    assertThat(metrics.refreshNow()).isFalse();
                    assertThat(Duration.ofNanos(System.nanoTime() - refreshStarted)).isLessThan(Duration.ofSeconds(6));
                    state(registry, "FAILED", 1);
                    outcome(registry, "UNKNOWN", 1);
                    assertThat(registry.get("relay.metrics.refresh.success").gauge().value()).isZero();
                    assertThat(milliseconds(registry, "relay.metrics.snapshot.age")).isGreaterThan(0);
                } finally { lock.rollback(); }
            }
            jdbc.update("DELETE FROM delivery_attempts WHERE delivery_id = ?", work.deliveryId());
            jdbc.update("DELETE FROM deliveries WHERE id = ?", work.deliveryId());
            jdbc.update("DELETE FROM events WHERE id = ?", work.eventId());
            assertThat(metrics.refreshNow()).isTrue();
            state(registry, "FAILED", 0);
            outcome(registry, "UNKNOWN", 0);
            assertThat(registry.get("relay.delivery.initial.queue.delay.samples").gauge().value()).isZero();
            assertThat(milliseconds(registry, "relay.delivery.initial.queue.delay.mean")).isNaN();
            assertThat(registry.get("relay.metrics.refresh.success").gauge().value()).isEqualTo(1);
            assertThat(output.getAll()).doesNotContain("private-metrics-fixture", SIGNING_MASTER_KEY);
        } finally { registry.close(); }
    }

    private CachedDeliveryMetrics collector(SimpleMeterRegistry registry) {
        var repository = new MetricsSnapshotRepository(jdbc, api.getBean(PlatformTransactionManager.class));
        return new CachedDeliveryMetrics(repository, registry, new MetricsSettings(Duration.ofSeconds(5)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Fixture fixture() {
        UUID account = UUID.randomUUID(), workspace = UUID.randomUUID();
        accounts.add(account);
        jdbc.update("INSERT INTO accounts (id,email,password_hash) VALUES (?, ?, ?)", account,
                "metrics-" + account + "@example.test", "test-only-noncredential");
        jdbc.update("INSERT INTO workspaces (id,owner_account_id,name,delivery_concurrency_limit) VALUES (?, ?, ?, 1)",
                workspace, account, "Metrics fixture");
        return new Fixture(workspace, endpoint(workspace, false));
    }

    private UUID endpoint(UUID workspace, boolean paused) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO endpoints (id,workspace_id,url,paused) VALUES (?, ?, ?, ?)",
                id, workspace, "https://receiver.company.com/metrics", paused);
        return id;
    }

    private Work delivery(Fixture fixture, UUID endpoint, String status, long generation, int used, int maximum,
                          long replay, Instant accepted, Instant due) {
        UUID event = UUID.randomUUID(), delivery = UUID.randomUUID();
        jdbc.update("INSERT INTO events (id,workspace_id,endpoint_id,payload,request_digest,accepted_at) VALUES (?, ?, ?, ?, ?, ?)",
                event, fixture.workspaceId(), endpoint, "{\"private\":\"private-metrics-fixture\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[32], Timestamp.from(accepted));
        jdbc.update("""
                INSERT INTO deliveries (id,workspace_id,event_id,status,claim_generation,run_attempts,max_attempts,
                    replay_generation,claim_token,lease_expires_at,next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, delivery, fixture.workspaceId(), event, status, generation, used, maximum, replay,
                status.equals("IN_FLIGHT") ? UUID.randomUUID() : null,
                status.equals("IN_FLIGHT") ? Timestamp.from(NOW.plusSeconds(60)) : null,
                due == null ? null : Timestamp.from(due));
        return new Work(event, delivery);
    }

    private void attempt(Fixture fixture, Work work, long generation, int runAttempt, long replay,
                          String outcome, Instant started, Long duration) {
        boolean ongoing = outcome.equals("IN_PROGRESS");
        jdbc.update("""
                INSERT INTO delivery_attempts (id,workspace_id,delivery_id,claim_generation,claim_token,started_at,
                    finished_at,outcome,replay_generation,run_attempt,http_status,failure_code,duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), fixture.workspaceId(), work.deliveryId(), generation, UUID.randomUUID(), Timestamp.from(started),
                ongoing ? null : Timestamp.from(started.plusSeconds(1)), outcome, replay, runAttempt,
                duration == null ? null : outcome.equals("SUCCEEDED") ? 200 : 500,
                duration != null && outcome.equals("FAILED") ? "HTTP_STATUS" : null, duration);
    }

    private static void state(SimpleMeterRegistry registry, String status, double expected) {
        assertThat(registry.get("relay.deliveries").tag("status", status).gauge().value()).isEqualTo(expected);
    }

    private static void outcome(SimpleMeterRegistry registry, String outcome, double expected) {
        assertThat(registry.get("relay.attempts").tag("outcome", outcome).gauge().value()).isEqualTo(expected);
    }

    private static double milliseconds(SimpleMeterRegistry registry, String name) {
        return registry.get(name).timeGauge().value(TimeUnit.MILLISECONDS);
    }

    private record Fixture(UUID workspaceId, UUID endpointId) { }
    private record Work(UUID eventId, UUID deliveryId) { }
}
