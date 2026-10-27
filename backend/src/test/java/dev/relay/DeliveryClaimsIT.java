package dev.relay;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import dev.relay.delivery.ClaimedDelivery;
import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryClaimService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 3a tests durable state transitions only; no outbound delivery or running worker is involved. */
class DeliveryClaimsIT extends HttpIntegrationTestSupport {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final List<UUID> accounts = new ArrayList<>();
    private DeliveryClaimService claims;

    @BeforeEach
    void findClaimService() {
        claims = api.getBean(DeliveryClaimService.class);
    }

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
    void eightConcurrentClaimersNeverShareALiveClaimAndAccountForAllWork() throws Exception {
        var fixture = fixture(32);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<List<ClaimedDelivery>>>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Claim start timed out");
                    return claims.claim(4, LEASE);
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var acquired = new ArrayList<ClaimedDelivery>();
            for (var future : futures) acquired.addAll(future.get(20, TimeUnit.SECONDS));
            // Workspace quota reservations also use SKIP LOCKED. A contender may need a later poll.
            while (acquired.size() < 32) {
                var next = claims.claim(4, LEASE);
                assertThat(next).isNotEmpty();
                acquired.addAll(next);
            }
            assertThat(acquired).hasSize(32);
            assertThat(acquired.stream().map(ClaimedDelivery::deliveryId).toList())
                    .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(
                            fixture.work().stream().map(Work::deliveryId).toList());
            assertThat(acquired.stream().map(ClaimedDelivery::token).toList()).doesNotHaveDuplicates();
            assertThat(acquired.stream().map(ClaimedDelivery::attemptId).toList()).doesNotHaveDuplicates();
            for (var claim : acquired) {
                assertThat(claim.generation()).isEqualTo(1);
                assertThat(claim.workspaceId()).isEqualTo(fixture.workspaceId());
                assertLiveClaim(claim);
            }
            assertThat(attemptCount(fixture.workspaceId())).isEqualTo(32);
            assertThat(claims.claim(100, LEASE)).isEmpty();
        }
    }

    @Test
    void lockedDeliveryAndCompetingPauseAreSkippedWithoutWaitingForTheirTransaction() throws Exception {
        var fixture = fixture(1);
        var blockedByPause = workspace(fixture.accountId(), 1);
        var available = workspace(fixture.accountId(), 1);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> {
                try (var connection = api.getBean(DataSource.class).getConnection()) {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    connection.setAutoCommit(false);
                    try {
                        try (var statement = connection.prepareStatement("SELECT id FROM deliveries WHERE id = ? FOR UPDATE")) {
                            statement.setObject(1, fixture.work().getFirst().deliveryId());
                            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                        }
                        try (var statement = connection.prepareStatement("UPDATE endpoints SET paused = true WHERE id = ?")) {
                            statement.setObject(1, blockedByPause.endpointId());
                            assertThat(statement.executeUpdate()).isEqualTo(1);
                        }
                        locked.countDown();
                        if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Lock release timed out");
                    } finally {
                        connection.rollback();
                    }
                }
                return true;
            });
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                // This future must finish while the row and endpoint locks are still held.
                var result = executor.submit(() -> claims.claim(100, LEASE)).get(5, TimeUnit.SECONDS);
                assertThat(result).hasSize(1);
                assertThat(result.getFirst().deliveryId()).isEqualTo(available.work().getFirst().deliveryId());
                assertThat(status(fixture.work().getFirst().deliveryId())).isEqualTo("PENDING");
                assertThat(status(blockedByPause.work().getFirst().deliveryId())).isEqualTo("PENDING");
            } finally {
                release.countDown();
            }
            assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(claims.claim(100, LEASE)).hasSize(2);
        }
    }

    @Test
    void batchAndLeaseBoundsPreservePausedTerminalAndAlreadyClaimedRows() {
        var fixture = fixture(3);
        var paused = workspace(fixture.accountId(), 2);
        setPaused(paused.endpointId(), true);
        var first = claims.claim(2, Duration.ofMillis(300_000));
        assertThat(first).hasSize(2);
        var remaining = claims.claim(2, Duration.ofMillis(100));
        assertThat(remaining).hasSize(1);
        assertThat(claims.complete(first.get(0), CompletionOutcome.SUCCEEDED)).isTrue();
        assertThat(claims.complete(first.get(1), CompletionOutcome.FAILED)).isTrue();
        assertThat(claims.claim(100, LEASE)).isEmpty();
        assertThat(status(first.get(0).deliveryId())).isEqualTo("SUCCEEDED");
        assertThat(status(first.get(1).deliveryId())).isEqualTo("FAILED");
        assertThat(status(remaining.getFirst().deliveryId())).isEqualTo("IN_FLIGHT");
        for (var work : paused.work()) assertThat(status(work.deliveryId())).isEqualTo("PENDING");
        assertThat(attemptCount(paused.workspaceId())).isZero();
        assertThatThrownBy(() -> claims.claim(0, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claims.claim(101, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claims.claim(1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claims.claim(1, Duration.ofMillis(99))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claims.claim(1, Duration.ofMillis(300_001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claims.recoverExpired(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claims.recoverExpired(101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.work()).hasSize(3);
    }

    @Test
    void attemptInsertFailureRollsBackAllClaimsAndHistoryInTheBatch() {
        var fixture = fixture(2);
        String constraint = constraintName("claim_insert");
        UUID rejected = fixture.work().getLast().deliveryId();
        jdbc.execute("ALTER TABLE delivery_attempts ADD CONSTRAINT " + constraint
                + " CHECK (delivery_id <> '" + rejected + "'::uuid) NOT VALID");
        try {
            assertThatThrownBy(() -> claims.claim(2, LEASE)).isInstanceOf(DataIntegrityViolationException.class);
            for (var work : fixture.work()) {
                assertThat(status(work.deliveryId())).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("SELECT claim_generation FROM deliveries WHERE id = ?",
                        Long.class, work.deliveryId())).isZero();
                assertNoActiveLease(work.deliveryId());
            }
            assertThat(attemptCount(fixture.workspaceId())).isZero();
        } finally {
            jdbc.execute("ALTER TABLE delivery_attempts DROP CONSTRAINT " + constraint);
        }
        assertThat(claims.claim(2, LEASE)).hasSize(2);
        assertThat(attemptCount(fixture.workspaceId())).isEqualTo(2);
    }

    @Test
    void completionRequiresClaimIdentityAndAtomicallyRecordsSuccessOrFailureOnce() {
        fixture(2);
        var batch = claims.claim(2, LEASE);
        var first = batch.get(0);
        var second = batch.get(1);
        assertThat(claims.complete(copy(first, first.workspaceId(), first.attemptId(), UUID.randomUUID(),
                first.generation()), CompletionOutcome.SUCCEEDED)).isFalse();
        assertThat(claims.complete(copy(first, first.workspaceId(), first.attemptId(), first.token(),
                first.generation() + 1), CompletionOutcome.SUCCEEDED)).isFalse();
        assertThat(claims.complete(copy(first, UUID.randomUUID(), first.attemptId(), first.token(),
                first.generation()), CompletionOutcome.SUCCEEDED)).isFalse();
        assertThatThrownBy(() -> claims.complete(copy(first, first.workspaceId(), second.attemptId(), first.token(),
                first.generation()), CompletionOutcome.SUCCEEDED)).isInstanceOf(IllegalStateException.class);
        assertLiveClaim(first);
        assertThat(claims.complete(first, CompletionOutcome.SUCCEEDED)).isTrue();
        assertThat(claims.complete(second, CompletionOutcome.FAILED)).isTrue();
        assertTerminal(first, "SUCCEEDED");
        assertTerminal(second, "FAILED");
        assertThat(claims.complete(first, CompletionOutcome.FAILED)).isFalse();
        assertThat(claims.complete(second, CompletionOutcome.SUCCEEDED)).isFalse();
        assertTerminal(first, "SUCCEEDED");
        assertTerminal(second, "FAILED");
    }

    @Test
    void attemptFinishFailureRollsBackDeliveryCompletionAndPreservesItsLease() {
        var fixture = fixture(1);
        var claim = claims.claim(1, LEASE).getFirst();
        String constraint = constraintName("claim_finish");
        jdbc.execute("ALTER TABLE delivery_attempts ADD CONSTRAINT " + constraint
                + " CHECK (workspace_id <> '" + fixture.workspaceId()
                + "'::uuid OR outcome = 'IN_PROGRESS') NOT VALID");
        try {
            assertThatThrownBy(() -> claims.complete(claim, CompletionOutcome.SUCCEEDED))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertLiveClaim(claim);
            assertThat(jdbc.queryForObject("SELECT finished_at IS NULL FROM delivery_attempts WHERE id = ?",
                    Boolean.class, claim.attemptId())).isTrue();
        } finally {
            jdbc.execute("ALTER TABLE delivery_attempts DROP CONSTRAINT " + constraint);
        }
        assertThat(claims.complete(claim, CompletionOutcome.SUCCEEDED)).isTrue();
        assertTerminal(claim, "SUCCEEDED");
    }

    @Test
    void expiryRecoveryAndReclaimFenceStaleCompletionAndPreserveEventAcceptance() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = browserFixture(browser, http);
            var first = claims.claim(1, LEASE).getFirst();
            assertThat(first.eventId()).isEqualTo(owner.work().eventId());
            assertCompletionWaitingOnRowLockCannotUseAnExpiredLease(first);
            expire(first);
            assertThat(claims.complete(first, CompletionOutcome.SUCCEEDED)).isFalse();
            assertThat(status(first.deliveryId())).isEqualTo("IN_FLIGHT");
            assertThat(outcome(first.attemptId())).isEqualTo("IN_PROGRESS");
            assertThat(claims.recoverExpired(1)).isEqualTo(1);
            assertThat(status(first.deliveryId())).isEqualTo("RETRY_WAIT");
            assertNoActiveLease(first.deliveryId());
            assertFinishedAttempt(first.attemptId(), "UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT claim_generation FROM deliveries WHERE id = ?", Long.class,
                    first.deliveryId())).isEqualTo(first.generation());

            makeRetryDue(first);
            var current = claims.claim(1, LEASE).getFirst();
            assertThat(current.eventId()).isEqualTo(first.eventId());
            assertThat(current.deliveryId()).isEqualTo(first.deliveryId());
            assertThat(current.generation()).isEqualTo(first.generation() + 1);
            assertThat(current.token()).isNotEqualTo(first.token());
            assertThat(current.attemptId()).isNotEqualTo(first.attemptId());
            assertThat(claims.complete(first, CompletionOutcome.FAILED)).isFalse();
            assertLiveClaim(current);
            assertThat(claims.complete(current, CompletionOutcome.SUCCEEDED)).isTrue();
            assertThat(claims.complete(first, CompletionOutcome.FAILED)).isFalse();
            assertTerminal(current, "SUCCEEDED");
            assertFinishedAttempt(first.attemptId(), "UNKNOWN");

            var repeated = request(http, "POST", "/api/v1/endpoints/" + owner.endpointId() + "/events",
                    owner.payload(), "application/json", Map.of("Authorization", "Bearer " + owner.apiKey(),
                            "Idempotency-Key", "stable-acceptance"));
            assertThat(repeated.statusCode()).isEqualTo(202);
            assertThat(repeated.body()).isEqualTo(owner.acceptance());
            var event = apiGet(http, owner.apiKey(), "/api/v1/events/" + first.eventId());
            assertThat(json(event).get("eventId").asString()).isEqualTo(first.eventId().toString());
            assertThat(json(event).get("status").asString()).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void pausedExpiredClaimsRecoverInBoundedBatchesAndWaitForUnpause() {
        var fixture = fixture(2);
        var previous = claims.claim(2, LEASE);
        setPaused(fixture.endpointId(), true);
        previous.forEach(this::expire);
        assertThat(claims.recoverExpired(1)).isEqualTo(1);
        assertThat(claims.recoverExpired(1)).isEqualTo(1);
        assertThat(claims.recoverExpired(1)).isZero();
        assertThat(claims.claim(100, LEASE)).isEmpty();
        for (var claim : previous) {
            assertThat(status(claim.deliveryId())).isEqualTo("RETRY_WAIT");
            assertNoActiveLease(claim.deliveryId());
            assertFinishedAttempt(claim.attemptId(), "UNKNOWN");
        }
        setPaused(fixture.endpointId(), false);
        previous.forEach(this::makeRetryDue);
        var current = claims.claim(2, LEASE);
        assertThat(current).hasSize(2).allSatisfy(claim -> assertThat(claim.generation()).isEqualTo(2));
        assertThat(attemptCount(fixture.workspaceId())).isEqualTo(4);
        previous.forEach(claim -> assertThat(claims.complete(claim, CompletionOutcome.SUCCEEDED)).isFalse());
        current.forEach(this::assertLiveClaim);
    }

    @Test
    void attemptHistoryIsPaginatedInGenerationOrderAndIsolatedForBothAuthenticationModes() throws Exception {
        try (var alice = new Browser(); var bob = new Browser(); var http = statelessClient()) {
            var owner = browserFixture(alice, http);
            var other = browserFixture(bob, http);
            String apiPath = "/api/v1/events/" + owner.work().eventId() + "/attempts";
            String browserPath = "/api/workspaces/" + owner.workspaceId() + "/events/"
                    + owner.work().eventId() + "/attempts";
            var empty = apiGet(http, owner.apiKey(), apiPath);
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(json(empty).get("items").size()).isZero();
            assertThat(json(empty).get("limit").asInt()).isEqualTo(20);
            assertThat(json(empty).get("offset").asInt()).isZero();
            var batch = claims.claim(100, LEASE);
            var first = batch.stream().filter(claim -> claim.deliveryId().equals(owner.work().deliveryId()))
                    .findFirst().orElseThrow();
            assertAttemptView(json(apiGet(http, owner.apiKey(), apiPath)).get("items").get(0), first, "IN_PROGRESS");
            expire(first);
            assertThat(claims.recoverExpired(100)).isEqualTo(1);
            makeRetryDue(first);
            var second = claims.claim(1, LEASE).getFirst();
            expire(second);
            assertThat(claims.recoverExpired(100)).isEqualTo(1);
            makeRetryDue(second);
            var third = claims.claim(1, LEASE).getFirst();
            assertThat(claims.complete(third, CompletionOutcome.FAILED)).isTrue();

            for (boolean browser : List.of(false, true)) {
                var firstPage = browser ? alice.get(browserPath + "?limit=2&offset=0")
                        : apiGet(http, owner.apiKey(), apiPath + "?limit=2&offset=0");
                assertThat(firstPage.statusCode()).isEqualTo(200);
                assertThat(json(firstPage).get("items").size()).isEqualTo(2);
                assertThat(json(firstPage).get("hasMore").asBoolean()).isTrue();
                assertAttemptView(json(firstPage).get("items").get(0), first, "UNKNOWN");
                assertAttemptView(json(firstPage).get("items").get(1), second, "UNKNOWN");
                var lastPage = browser ? alice.get(browserPath + "?limit=2&offset=2")
                        : apiGet(http, owner.apiKey(), apiPath + "?limit=2&offset=2");
                assertThat(json(lastPage).get("items").size()).isEqualTo(1);
                assertThat(json(lastPage).get("hasMore").asBoolean()).isFalse();
                assertAttemptView(json(lastPage).get("items").get(0), third, "FAILED");
                assertThat(firstPage.body() + lastPage.body()).doesNotContain(first.token().toString(),
                        second.token().toString(), third.token().toString(), "payload", "claimToken", "requestDigest");
            }
            assertThat(apiGet(http, other.apiKey(), apiPath).statusCode()).isEqualTo(404);
            assertThat(bob.get(browserPath).statusCode()).isEqualTo(404);
            assertThat(alice.get("/api/workspaces/" + other.workspaceId() + "/events/"
                    + owner.work().eventId() + "/attempts").statusCode()).isEqualTo(404);
            assertThat(apiGet(http, owner.apiKey(), "/api/v1/events/" + UUID.randomUUID() + "/attempts")
                    .statusCode()).isEqualTo(404);
            assertThat(alice.get("/api/workspaces/" + owner.workspaceId() + "/events/"
                    + UUID.randomUUID() + "/attempts").statusCode()).isEqualTo(404);
            assertThat(alice.get(apiPath).statusCode()).isEqualTo(401);
            assertThat(apiGet(http, owner.apiKey(), browserPath).statusCode()).isEqualTo(401);
            for (String query : List.of("?limit=0", "?limit=101", "?offset=-1", "?limit=bad")) {
                assertThat(apiGet(http, owner.apiKey(), apiPath + query).statusCode()).isEqualTo(400);
                assertThat(alice.get(browserPath + query).statusCode()).isEqualTo(400);
            }
        }
    }

    @Test
    void ledgerTenantForeignKeyGenerationAndTokenUniquenessAreDatabaseEnforced() {
        var fixture = fixture(1);
        var other = workspace(fixture.accountId(), 1);
        var batch = claims.claim(2, LEASE);
        var claim = batch.stream().filter(item -> item.workspaceId().equals(fixture.workspaceId()))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> insertAttempt(UUID.randomUUID(), other.workspaceId(), claim.deliveryId(),
                99, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttempt(UUID.randomUUID(), claim.workspaceId(), claim.deliveryId(),
                claim.generation(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttempt(UUID.randomUUID(), other.workspaceId(), other.work().getFirst().deliveryId(),
                99, claim.token())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(attemptCount(fixture.workspaceId())).isEqualTo(1);
        assertThat(attemptCount(other.workspaceId())).isEqualTo(1);
        batch.forEach(this::assertLiveClaim);
    }

    private Fixture fixture(int count) {
        UUID accountId = UUID.randomUUID();
        accounts.add(accountId);
        jdbc.update("INSERT INTO accounts (id, email, password_hash) VALUES (?, ?, ?)", accountId,
                "claims-" + accountId + "@example.test", "test-only-noncredential");
        return workspace(accountId, count);
    }

    private Fixture workspace(UUID accountId, int count) {
        UUID workspaceId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, owner_account_id, name) VALUES (?, ?, ?)",
                workspaceId, accountId, "Claim fixtures");
        jdbc.update("UPDATE workspaces SET delivery_concurrency_limit = 32 WHERE id = ?", workspaceId);
        jdbc.update("INSERT INTO endpoints (id, workspace_id, url) VALUES (?, ?, ?)",
                endpointId, workspaceId, "https://receiver.example.test/claims");
        var work = new ArrayList<Work>();
        for (int index = 0; index < count; index++) {
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            jdbc.update("INSERT INTO events (id, workspace_id, endpoint_id, payload, request_digest) VALUES (?, ?, ?, ?, ?)",
                    eventId, workspaceId, endpointId, "{}".getBytes(StandardCharsets.UTF_8), new byte[32]);
            jdbc.update("INSERT INTO deliveries (id, workspace_id, event_id, created_at) VALUES (?, ?, ?, ?)",
                    deliveryId, workspaceId, eventId, Timestamp.from(Instant.parse("2020-01-01T00:00:00Z").plusMillis(index)));
            work.add(new Work(eventId, deliveryId));
        }
        return new Fixture(accountId, workspaceId, endpointId, work);
    }

    private BrowserFixture browserFixture(Browser browser, HttpClient http) throws Exception {
        String email = "claims-http-" + UUID.randomUUID() + "@example.test";
        String password = "claim fixture password 42!";
        browser.csrf();
        var account = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", password));
        assertThat(account.statusCode()).isEqualTo(201);
        accounts.add(UUID.fromString(json(account).get("id").asString()));
        assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
        browser.csrf();
        var workspace = browser.json("POST", "/api/workspaces", Map.of("name", "Claim API fixture"));
        assertThat(workspace.statusCode()).isEqualTo(201);
        String workspaceId = json(workspace).get("id").asString();
        var endpoint = browser.json("POST", "/api/workspaces/" + workspaceId + "/endpoints",
                Map.of("url", "https://receiver.example.test/claim-history"));
        assertThat(endpoint.statusCode()).isEqualTo(201);
        String endpointId = json(endpoint).get("id").asString();
        var key = browser.json("POST", "/api/workspaces/" + workspaceId + "/api-keys", Map.of("name", "Claim history client"));
        assertThat(key.statusCode()).isEqualTo(201);
        String apiKey = json(key).get("key").asString();
        String payload = "{\"private\":\"stable-event-" + UUID.randomUUID() + "\"}";
        var accepted = request(http, "POST", "/api/v1/endpoints/" + endpointId + "/events", payload,
                "application/json", Map.of("Authorization", "Bearer " + apiKey, "Idempotency-Key", "stable-acceptance"));
        assertThat(accepted.statusCode()).isEqualTo(202);
        var work = new Work(UUID.fromString(json(accepted).get("eventId").asString()),
                UUID.fromString(json(accepted).get("deliveryId").asString()));
        return new BrowserFixture(workspaceId, endpointId, apiKey, work, payload, accepted.body());
    }

    private void expire(ClaimedDelivery claim) {
        assertThat(jdbc.update("UPDATE deliveries SET lease_expires_at = clock_timestamp() - interval '1 second' "
                + "WHERE id = ? AND claim_token = ?", claim.deliveryId(), claim.token())).isEqualTo(1);
    }

    private void assertCompletionWaitingOnRowLockCannotUseAnExpiredLease(ClaimedDelivery claim) throws Exception {
        try (var connection = api.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            int holderPid;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(rows.next()).isTrue();
                holderPid = rows.getInt(1);
            }
            try (var statement = connection.prepareStatement("SELECT id FROM deliveries WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, claim.deliveryId());
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    var completion = executor.submit(() -> claims.complete(claim, CompletionOutcome.SUCCEEDED));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    boolean waiting = false;
                    // Observe PostgreSQL's actual lock dependency instead of guessing with a sleep.
                    while (System.nanoTime() < deadline && !waiting) {
                        waiting = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                                + "WHERE ? = ANY(pg_blocking_pids(pid)))", Boolean.class, holderPid));
                    }
                    assertThat(waiting).as("completion is blocked by the fixture transaction").isTrue();
                    try (var statement = connection.prepareStatement("UPDATE deliveries SET lease_expires_at = "
                            + "clock_timestamp() - interval '1 second' WHERE id = ?")) {
                        statement.setObject(1, claim.deliveryId());
                        assertThat(statement.executeUpdate()).isEqualTo(1);
                    }
                    connection.commit();
                    assertThat(completion.get(5, TimeUnit.SECONDS)).isFalse();
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private void setPaused(UUID endpointId, boolean paused) {
        assertThat(jdbc.update("UPDATE endpoints SET paused = ? WHERE id = ?", paused, endpointId)).isEqualTo(1);
    }

    private void makeRetryDue(ClaimedDelivery claim) {
        // These are storage/fencing regressions. RetryDeliveryIT advances an injected clock to test scheduling.
        assertThat(jdbc.update("UPDATE deliveries SET next_attempt_at = '2000-01-01T00:00:00Z' "
                + "WHERE id = ? AND status = 'RETRY_WAIT'", claim.deliveryId())).isEqualTo(1);
    }

    private String status(UUID deliveryId) {
        return jdbc.queryForObject("SELECT status FROM deliveries WHERE id = ?", String.class, deliveryId);
    }

    private String outcome(UUID attemptId) {
        return jdbc.queryForObject("SELECT outcome FROM delivery_attempts WHERE id = ?", String.class, attemptId);
    }

    private int attemptCount(UUID workspaceId) {
        return jdbc.queryForObject("SELECT count(*) FROM delivery_attempts WHERE workspace_id = ?", Integer.class, workspaceId);
    }

    private void assertLiveClaim(ClaimedDelivery claim) {
        assertThat(status(claim.deliveryId())).isEqualTo("IN_FLIGHT");
        assertThat(jdbc.queryForObject("SELECT claim_token FROM deliveries WHERE id = ?", UUID.class,
                claim.deliveryId())).isEqualTo(claim.token());
        assertThat(jdbc.queryForObject("SELECT claim_generation FROM deliveries WHERE id = ?", Long.class,
                claim.deliveryId())).isEqualTo(claim.generation());
        assertThat(jdbc.queryForObject("SELECT lease_expires_at FROM deliveries WHERE id = ?", Timestamp.class,
                claim.deliveryId()).toInstant()).isEqualTo(claim.leaseExpiresAt());
        assertThat(outcome(claim.attemptId())).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT claim_token FROM delivery_attempts WHERE id = ?", UUID.class,
                claim.attemptId())).isEqualTo(claim.token());
        assertThat(jdbc.queryForObject("SELECT claim_generation FROM delivery_attempts WHERE id = ?", Long.class,
                claim.attemptId())).isEqualTo(claim.generation());
        assertThat(jdbc.queryForObject("SELECT finished_at IS NULL FROM delivery_attempts WHERE id = ?", Boolean.class,
                claim.attemptId())).isTrue();
    }

    private void assertTerminal(ClaimedDelivery claim, String expected) {
        assertThat(status(claim.deliveryId())).isEqualTo(expected);
        assertNoActiveLease(claim.deliveryId());
        assertFinishedAttempt(claim.attemptId(), expected);
    }

    private void assertNoActiveLease(UUID deliveryId) {
        assertThat(jdbc.queryForObject("SELECT claim_token IS NULL AND lease_expires_at IS NULL FROM deliveries WHERE id = ?",
                Boolean.class, deliveryId)).isTrue();
    }

    private void assertFinishedAttempt(UUID attemptId, String expected) {
        assertThat(outcome(attemptId)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL AND finished_at >= started_at "
                + "FROM delivery_attempts WHERE id = ?", Boolean.class, attemptId)).isTrue();
    }

    private void insertAttempt(UUID id, UUID workspaceId, UUID deliveryId, long generation, UUID token) {
        jdbc.update("INSERT INTO delivery_attempts (id, workspace_id, delivery_id, claim_generation, claim_token, "
                + "started_at, outcome) VALUES (?, ?, ?, ?, ?, clock_timestamp(), 'IN_PROGRESS')",
                id, workspaceId, deliveryId, generation, token);
    }

    private static ClaimedDelivery copy(ClaimedDelivery claim, UUID workspaceId, UUID attemptId, UUID token, long generation) {
        return new ClaimedDelivery(claim.deliveryId(), claim.eventId(), workspaceId, attemptId, token, generation,
                claim.leaseExpiresAt());
    }

    private static HttpResponse<String> apiGet(HttpClient http, String key, String path) throws Exception {
        return request(http, "GET", path, null, null, Map.of("Authorization", "Bearer " + key));
    }

    private static void assertAttemptView(JsonNode view, ClaimedDelivery claim, String expectedOutcome) {
        assertThat(view.size()).isEqualTo(13);
        assertThat(view.get("id").asString()).isEqualTo(claim.attemptId().toString());
        assertThat(view.get("deliveryId").asString()).isEqualTo(claim.deliveryId().toString());
        assertThat(view.get("generation").asLong()).isEqualTo(claim.generation());
        assertThat(view.get("outcome").asString()).isEqualTo(expectedOutcome);
        assertThat(view.get("startedAt").asString()).isNotBlank();
        if (expectedOutcome.equals("IN_PROGRESS")) {
            assertThat(view.get("finishedAt").isNull()).isTrue();
        } else {
            assertThat(view.get("finishedAt").asString()).isNotBlank();
        }
    }

    private static String constraintName(String kind) {
        return "it_" + kind + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record Work(UUID eventId, UUID deliveryId) { }
    private record Fixture(UUID accountId, UUID workspaceId, UUID endpointId, List<Work> work) { }
    private record BrowserFixture(String workspaceId, String endpointId, String apiKey, Work work, String payload,
                                  String acceptance) { }
}
