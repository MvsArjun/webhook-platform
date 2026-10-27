package dev.relay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import dev.relay.delivery.ClaimedDelivery;
import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryResult;
import dev.relay.worker.WorkerRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual Spring-context shutdown, PostgreSQL leases and HTTP; this does not simulate OS signals. */
@ExtendWith(OutputCaptureExtension.class)
@Timeout(60)
class WorkerShutdownIT extends HttpIntegrationTestSupport {
    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (UUID account : accounts) {
            for (String table : List.of("delivery_attempts", "deliveries", "events", "endpoint_signing_keys",
                    "api_keys", "endpoints")) {
                jdbc.update("DELETE FROM " + table + " WHERE workspace_id IN "
                        + "(SELECT id FROM workspaces WHERE owner_account_id = ?)", account);
            }
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", account);
            jdbc.update("DELETE FROM accounts WHERE id = ?", account);
        }
    }

    @Test
    void admittedHttpFinishesAndPersistsWhileContextCloseWaitsWithoutAdmittingAnotherDelivery(CapturedOutput output)
            throws Exception {
        var release = new CountDownLatch(1);
        String marker = "private-graceful-shutdown-" + UUID.randomUUID();
        String payload = "{\"private\":\"" + marker + "\"}";
        try (var receiver = new ControllableReceiver()) {
            receiver.block(release);
            var fixture = fixture(receiver.url());
            var admitted = addWork(fixture, payload);
            try (var worker = worker(receiver.url(), "5s")) {
                var received = receiver.next(Duration.ofSeconds(15));
                assertThat(received).as("admitted delivery reaches the actual receiver").isNotNull();
                assertRequest(received, admitted, 1, payload);
                assertThat(status(admitted)).isEqualTo("IN_FLIGHT");

                var stopped = new CountDownLatch(1);
                worker.getBean(WorkerRuntime.class).stop(stopped::countDown);
                // A committed new row appears only after stop has closed admission.
                var unadmitted = addWork(fixture, "{\"afterStop\":true}");
                var closeEntered = new CountDownLatch(1);
                var closed = closeAsync(worker, closeEntered);
                assertThat(closeEntered.await(2, TimeUnit.SECONDS)).isTrue();
                try {
                    assertThat(stopped.await(150, TimeUnit.MILLISECONDS))
                            .as("shutdown callback waits for the admitted response and persistence").isFalse();
                    assertThat(closed.isDone()).as("context resources stay alive during the grace period").isFalse();
                    assertThat(status(admitted)).isEqualTo("IN_FLIGHT");
                    assertThat(status(unadmitted)).isEqualTo("PENDING");
                    assertThat(attemptCount(unadmitted)).isZero();
                    assertThat(receiver.next(Duration.ofMillis(100))).as("no second HTTP admission after stop").isNull();
                } finally {
                    release.countDown();
                }

                assertThat(stopped.await(5, TimeUnit.SECONDS)).as("drained worker invokes its callback").isTrue();
                closed.get(5, TimeUnit.SECONDS);
                assertThat(worker.isActive()).isFalse();
                assertThat(status(admitted)).isEqualTo("SUCCEEDED");
                var attempt = jdbc.queryForMap("SELECT outcome, http_status, failure_code, finished_at "
                        + "FROM delivery_attempts WHERE delivery_id = ?", admitted.deliveryId());
                assertThat(attempt.get("outcome")).isEqualTo("SUCCEEDED");
                assertThat(attempt.get("http_status")).isEqualTo(200);
                assertThat(attempt.get("failure_code")).isNull();
                assertThat(attempt.get("finished_at")).isNotNull();
                assertThat(attemptCount(admitted)).isEqualTo(1);
                assertThat(status(unadmitted)).isEqualTo("PENDING");
                assertThat(attemptCount(unadmitted)).isZero();
                assertThat(receiver.next(Duration.ofMillis(150))).as("drain completion never restarts polling").isNull();
                assertThat(output.getAll()).doesNotContain(marker, payload);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void graceExpiryLeavesAnUnknownRecoverableClaimAndFencesItsLateCompletion(CapturedOutput output) throws Exception {
        var release = new CountDownLatch(1);
        String marker = "private-abandoned-shutdown-" + UUID.randomUUID();
        String payload = "{\"private\":\"" + marker + "\"}";
        try (var receiver = new ControllableReceiver()) {
            receiver.block(release);
            var fixture = fixture(receiver.url());
            var work = addWork(fixture, payload);
            ClaimedDelivery abandoned;
            try (var first = worker(receiver.url(), "150ms")) {
                var original = receiver.next(Duration.ofSeconds(15));
                assertThat(original).isNotNull();
                assertRequest(original, work, 1, payload);
                abandoned = snapshot(work);
                assertThat(abandoned.attemptId().toString()).isEqualTo(original.header("X-Relay-Attempt-Id"));
                long started = System.nanoTime();
                closeAsync(first, new CountDownLatch(1)).get(3, TimeUnit.SECONDS);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
                assertThat(first.isActive()).isFalse();
                assertThat(status(work)).isEqualTo("IN_FLIGHT");
                assertUnfinished(abandoned);
                assertThat(receiver.next(Duration.ofMillis(150))).as("forced shutdown sends no replacement").isNull();
                assertUnfinished(abandoned);
            } finally {
                receiver.respond(200, "replacement succeeded");
                release.countDown();
            }

            await("original receiver handler exits", Duration.ofSeconds(3), () -> receiver.active() == 0);
            var claims = api.getBean(DeliveryClaimService.class);
            assertThat(jdbc.update("UPDATE deliveries SET lease_expires_at = clock_timestamp() - interval '1 second' "
                    + "WHERE id = ? AND workspace_id = ? AND status = 'IN_FLIGHT'", work.deliveryId(), fixture.workspaceId()))
                    .isEqualTo(1);
            assertThat(claims.recoverExpired(100)).isEqualTo(1);
            assertThat(status(work)).isEqualTo("RETRY_WAIT");
            assertThat(jdbc.queryForObject("SELECT outcome FROM delivery_attempts WHERE id = ?", String.class,
                    abandoned.attemptId())).isEqualTo("UNKNOWN");
            var lateSuccess = new DeliveryResult(CompletionOutcome.SUCCEEDED, 200, null, 1);
            assertThat(claims.complete(abandoned, lateSuccess)).as("an expired attempt cannot overwrite UNKNOWN").isFalse();
            // Advance eligibility directly; PostgreSQL still supplies all lease times.
            assertThat(jdbc.update("UPDATE deliveries SET next_attempt_at = clock_timestamp() - interval '1 second' "
                    + "WHERE id = ? AND workspace_id = ? AND status = 'RETRY_WAIT'", work.deliveryId(), fixture.workspaceId()))
                    .isEqualTo(1);
            try (var replacement = worker(receiver.url(), "5s")) {
                var recovered = receiver.next(Duration.ofSeconds(15));
                assertThat(recovered).isNotNull();
                assertRequest(recovered, work, 2, payload);
                assertThat(recovered.header("X-Relay-Attempt-Id")).isNotEqualTo(abandoned.attemptId().toString());
                await("replacement persists successful delivery", Duration.ofSeconds(5), () -> status(work).equals("SUCCEEDED"));
                assertThat(attemptCount(work)).isEqualTo(2);
                assertThat(jdbc.queryForList("SELECT outcome FROM delivery_attempts WHERE delivery_id = ? "
                        + "ORDER BY claim_generation", String.class, work.deliveryId())).containsExactly("UNKNOWN", "SUCCEEDED");
                assertThat(jdbc.queryForList("SELECT claim_generation FROM delivery_attempts WHERE delivery_id = ? "
                        + "ORDER BY claim_generation", Long.class, work.deliveryId())).containsExactly(1L, 2L);
                assertThat(claims.complete(abandoned, lateSuccess)).as("old generation cannot overwrite replacement success").isFalse();
                assertThat(status(work)).isEqualTo("SUCCEEDED");
                assertThat(receiver.next(Duration.ofMillis(150))).as("no implicit third attempt").isNull();
                assertThat(output.getAll()).doesNotContain(marker, payload);
            }
        } finally {
            release.countDown();
        }
    }

    private ConfigurableApplicationContext worker(URI receiver, String grace) {
        return application().profiles("worker", "development").web(WebApplicationType.NONE).run(
                "--spring.main.keep-alive=false", "--relay.worker.concurrency=1", "--relay.worker.poll-interval=25ms",
                "--relay.worker.request-timeout=15s", "--relay.worker.connect-timeout=1s", "--relay.worker.dns-timeout=500ms",
                "--relay.worker.lease=30s", "--relay.worker.lease-safety=1s", "--relay.worker.shutdown-grace=" + grace,
                "--relay.delivery.allow-local-receiver=true", "--relay.delivery.local-receiver-url=" + receiver);
    }

    private Fixture fixture(URI receiver) {
        UUID account = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID endpoint = UUID.randomUUID();
        accounts.add(account);
        jdbc.update("INSERT INTO accounts (id, email, password_hash) VALUES (?, ?, ?)", account,
                "shutdown-" + account + "@example.test", "test-only-noncredential");
        jdbc.update("INSERT INTO workspaces (id, owner_account_id, name) VALUES (?, ?, ?)", workspace, account, "Shutdown fixture");
        jdbc.update("INSERT INTO endpoints (id, workspace_id, url) VALUES (?, ?, ?)", endpoint, workspace, receiver.toString());
        return new Fixture(workspace, endpoint);
    }

    private Work addWork(Fixture fixture, String payload) {
        UUID event = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        jdbc.update("INSERT INTO events (id, workspace_id, endpoint_id, payload, request_digest) VALUES (?, ?, ?, ?, ?)",
                event, fixture.workspaceId(), fixture.endpointId(), payload.getBytes(StandardCharsets.UTF_8), new byte[32]);
        jdbc.update("INSERT INTO deliveries (id, workspace_id, event_id, max_attempts) VALUES (?, ?, ?, 3)",
                delivery, fixture.workspaceId(), event);
        return new Work(event, delivery);
    }

    private ClaimedDelivery snapshot(Work work) {
        return jdbc.queryForObject("SELECT id, event_id, workspace_id, claim_token, claim_generation, lease_expires_at, "
                + "(SELECT a.id FROM delivery_attempts a WHERE a.delivery_id = deliveries.id "
                + "AND a.claim_generation = deliveries.claim_generation) AS attempt_id FROM deliveries WHERE id = ?",
                (row, index) -> new ClaimedDelivery(row.getObject("id", UUID.class), row.getObject("event_id", UUID.class),
                        row.getObject("workspace_id", UUID.class), row.getObject("attempt_id", UUID.class),
                        row.getObject("claim_token", UUID.class), row.getLong("claim_generation"),
                        row.getTimestamp("lease_expires_at").toInstant()), work.deliveryId());
    }

    private void assertUnfinished(ClaimedDelivery claim) {
        var attempt = jdbc.queryForMap("SELECT outcome, finished_at, http_status, failure_code, duration_ms, response_preview, "
                + "response_truncated FROM delivery_attempts WHERE id = ?", claim.attemptId());
        assertThat(attempt.get("outcome")).isEqualTo("IN_PROGRESS");
        assertThat(attempt.get("finished_at")).isNull();
        assertThat(attempt.get("http_status")).isNull();
        assertThat(attempt.get("failure_code")).isNull();
        assertThat(attempt.get("duration_ms")).isNull();
        assertThat(attempt.get("response_preview")).isNull();
        assertThat(attempt.get("response_truncated")).isEqualTo(false);
        assertThat(attemptCount(new Work(claim.eventId(), claim.deliveryId()))).isEqualTo(1);
    }

    private String status(Work work) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE id = ?", String.class, work.deliveryId());
    }

    private int attemptCount(Work work) {
        return jdbc.queryForObject("SELECT count(*) FROM delivery_attempts WHERE delivery_id = ?", Integer.class, work.deliveryId());
    }

    private static void assertRequest(ControllableReceiver.Received received, Work work, int generation, String payload) {
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.body()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(received.header("X-Relay-Event-Id")).isEqualTo(work.eventId().toString());
        assertThat(received.header("X-Relay-Delivery-Id")).isEqualTo(work.deliveryId().toString());
        assertThat(received.header("X-Relay-Attempt")).isEqualTo(Integer.toString(generation));
    }

    private static CompletableFuture<Void> closeAsync(ConfigurableApplicationContext context, CountDownLatch entered) {
        var result = new CompletableFuture<Void>();
        Thread.ofPlatform().daemon().name("test-worker-context-close").start(() -> {
            entered.countDown();
            try { context.close(); result.complete(null); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    private static void await(String label, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted awaiting " + label);
        }
        assertThat(condition.getAsBoolean()).as(label).isTrue();
    }

    private record Fixture(UUID workspaceId, UUID endpointId) { }
    private record Work(UUID eventId, UUID deliveryId) { }
}
