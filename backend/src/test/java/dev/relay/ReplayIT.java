package dev.relay;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryResult;
import dev.relay.delivery.retry.RetrySettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Replay is an explicit, generation-guarded new run of the existing durable delivery. */
class ReplayIT extends HttpIntegrationTestSupport {
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
    void replayPreservesIdentitiesAndHistoryAndResetsOnlyTheRunBudget() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var fixture = failedFixture(browser);
            var history = history(fixture);
            var accepted = bearerReplay(http, fixture, 0);
            assertAccepted(accepted, fixture, 1);
            assertThat(accepted.headers().allValues("Set-Cookie")).isEmpty();
            var delivery = delivery(fixture);
            assertThat(delivery.get("status")).isEqualTo("PENDING");
            assertThat(delivery.get("run_attempts")).isEqualTo(0);
            assertThat(delivery.get("max_attempts")).isEqualTo(api.getBean(RetrySettings.class).maxAttempts());
            assertThat(delivery.get("next_attempt_at")).isNull();
            assertThat(delivery.get("claim_generation")).isEqualTo(2L);
            assertThat(history(fixture)).isEqualTo(history);

            var claimed = api.getBean(DeliveryClaimService.class).claim(1, Duration.ofMinutes(2)).getFirst();
            assertThat(claimed.eventId()).isEqualTo(fixture.eventId());
            assertThat(claimed.deliveryId()).isEqualTo(fixture.deliveryId());
            assertThat(claimed.generation()).isEqualTo(3);
            var latest = jdbc.queryForMap("SELECT replay_generation, run_attempt FROM delivery_attempts WHERE id = ?",
                    claimed.attemptId());
            assertThat(latest.get("replay_generation")).isEqualTo(1L);
            assertThat(latest.get("run_attempt")).isEqualTo(1);
            assertThat(history(fixture).subList(0, 2)).isEqualTo(history);
        }
    }

    @Test
    void browserReplayRequiresCsrfAndExplicitIntegerGenerationAndKeepsAuthenticationModesSeparate() throws Exception {
        try (var browser = new Browser(); var anonymous = new Browser(); var http = statelessClient()) {
            var fixture = failedFixture(browser);
            assertThat(browser.json("POST", browserPath(fixture), Map.of("expectedReplayGeneration", 0), false)
                    .statusCode()).isEqualTo(403);
            assertThat(request(browser.http, "POST", apiPath(fixture), "{\"expectedReplayGeneration\":0}",
                    "application/json", Map.of()).statusCode()).isEqualTo(401);
            anonymous.csrf();
            assertThat(request(anonymous.http, "POST", browserPath(fixture), "{\"expectedReplayGeneration\":0}",
                    "application/json", Map.of("Authorization", "Bearer " + fixture.key(),
                            "X-CSRF-TOKEN", anonymous.csrfToken)).statusCode()).isEqualTo(401);
            assertThat(request(http, "POST", apiPath(fixture), "{\"expectedReplayGeneration\":0}",
                    "application/json", Map.of()).statusCode()).isEqualTo(401);
            for (String body : List.of("{}", "null", "[]", "{\"expectedReplayGeneration\":null}",
                    "{\"expectedReplayGeneration\":-1}", "{\"expectedReplayGeneration\":0.5}",
                    "{\"expectedReplayGeneration\":\"0\"}", "{\"expectedReplayGeneration\":9223372036854775808}")) {
                assertThat(request(http, "POST", apiPath(fixture), body, "application/json",
                        Map.of("Authorization", "Bearer " + fixture.key())).statusCode())
                        .as("replay generation validation").isEqualTo(400);
            }
            assertThat(delivery(fixture).get("replay_generation")).isEqualTo(0L);
            var accepted = browser.json("POST", browserPath(fixture), Map.of("expectedReplayGeneration", 0));
            assertAccepted(accepted, fixture, 1);
        }
    }

    @Test
    void foreignAndMissingEventsAreHiddenForBothBrowserAndBearerReplay() throws Exception {
        try (var alice = new Browser(); var bob = new Browser(); var http = statelessClient()) {
            var owner = failedFixture(alice);
            var other = failedFixture(bob);
            var before = delivery(owner);
            assertThat(request(http, "POST", apiPath(owner), "{\"expectedReplayGeneration\":0}", "application/json",
                    Map.of("Authorization", "Bearer " + other.key())).statusCode()).isEqualTo(404);
            assertThat(bob.json("POST", browserPath(owner), Map.of("expectedReplayGeneration", 0)).statusCode())
                    .isEqualTo(404);
            assertThat(alice.json("POST", "/api/workspaces/" + other.workspaceId() + "/events/"
                    + owner.eventId() + "/replay", Map.of("expectedReplayGeneration", 0)).statusCode()).isEqualTo(404);
            assertThat(request(http, "POST", "/api/v1/events/" + UUID.randomUUID() + "/replay",
                    "{\"expectedReplayGeneration\":0}", "application/json",
                    Map.of("Authorization", "Bearer " + owner.key())).statusCode()).isEqualTo(404);
            assertThat(alice.json("POST", "/api/workspaces/" + owner.workspaceId() + "/events/"
                    + UUID.randomUUID() + "/replay", Map.of("expectedReplayGeneration", 0)).statusCode()).isEqualTo(404);
            assertThat(delivery(owner)).isEqualTo(before);
            assertThat(history(owner)).hasSize(2);
        }
    }

    @Test
    void duplicateConcurrentReplayHasOneWinnerAndDelayedDuplicateCannotReplayALaterFailedRun() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var fixture = failedFixture(browser);
            var ready = new CountDownLatch(4);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
                for (int index = 0; index < 4; index++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Replay start timed out");
                        return bearerReplay(http, fixture, 0);
                    }));
                }
                try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
                finally { start.countDown(); }
                var statuses = new ArrayList<Integer>();
                for (var future : futures) statuses.add(future.get(15, TimeUnit.SECONDS).statusCode());
                assertThat(statuses).containsExactlyInAnyOrder(202, 409, 409, 409);
            }
            assertThat(delivery(fixture).get("replay_generation")).isEqualTo(1L);
            assertThat(history(fixture)).hasSize(2);
            var service = api.getBean(DeliveryClaimService.class);
            var claim = service.claim(1, Duration.ofMinutes(2)).getFirst();
            assertThat(service.complete(claim, new DeliveryResult(CompletionOutcome.FAILED, 400, "HTTP_STATUS", 1)))
                    .isTrue();
            assertThat(delivery(fixture).get("status")).isEqualTo("FAILED");
            var failedAgain = delivery(fixture);
            var retained = history(fixture);
            assertThat(bearerReplay(http, fixture, 0).statusCode()).isEqualTo(409);
            assertThat(delivery(fixture)).isEqualTo(failedAgain);
            assertThat(history(fixture)).isEqualTo(retained);
            assertAccepted(bearerReplay(http, fixture, 1), fixture, 2);
            assertThat(history(fixture)).isEqualTo(retained);
        }
    }

    @Test
    void pausedNonterminalMismatchedAndOverflowGenerationsCannotBeReplayed() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var fixture = failedFixture(browser);
            assertThat(bearerReplay(http, fixture, 1).statusCode()).isEqualTo(409);
            jdbc.update("UPDATE endpoints SET paused = true WHERE id = ?", fixture.endpointId());
            assertThat(bearerReplay(http, fixture, 0).statusCode()).isEqualTo(409);
            jdbc.update("UPDATE endpoints SET paused = false WHERE id = ?", fixture.endpointId());
            for (String status : List.of("SUCCEEDED", "PENDING", "RETRY_WAIT")) {
                jdbc.update("UPDATE deliveries SET status = ?, max_attempts = 5, next_attempt_at = "
                        + "CASE WHEN ? = 'RETRY_WAIT' THEN clock_timestamp() + interval '1 hour' ELSE NULL END WHERE id = ?",
                        status, status, fixture.deliveryId());
                assertThat(bearerReplay(http, fixture, 0).statusCode()).as("state %s", status).isEqualTo(409);
            }
            jdbc.update("UPDATE deliveries SET status = 'PENDING', next_attempt_at = NULL WHERE id = ?", fixture.deliveryId());
            var claim = api.getBean(DeliveryClaimService.class).claim(1, Duration.ofMinutes(2)).getFirst();
            assertThat(bearerReplay(http, fixture, 0).statusCode()).isEqualTo(409);
            assertThat(api.getBean(DeliveryClaimService.class).complete(claim,
                    new DeliveryResult(CompletionOutcome.FAILED, 400, "HTTP_STATUS", 1))).isTrue();
            jdbc.update("UPDATE deliveries SET replay_generation = ? WHERE id = ?", Long.MAX_VALUE, fixture.deliveryId());
            assertThat(bearerReplay(http, fixture, Long.MAX_VALUE).statusCode()).isEqualTo(409);
            assertThat(delivery(fixture).get("replay_generation")).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Test
    void replayRacingAutomaticRetryCannotResetOrDuplicateAnActiveRun() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var fixture = failedFixture(browser);
            assertAccepted(bearerReplay(http, fixture, 0), fixture, 1);
            var service = api.getBean(DeliveryClaimService.class);
            var claim = service.claim(1, Duration.ofMinutes(2)).getFirst();
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var completion = executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Retry race timed out");
                    return service.complete(claim, new DeliveryResult(CompletionOutcome.FAILED, 500, "HTTP_STATUS", 1));
                });
                var replay = executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Replay race timed out");
                    return bearerReplay(http, fixture, 1);
                });
                start.countDown();
                assertThat(completion.get(15, TimeUnit.SECONDS)).isTrue();
                assertThat(replay.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
            }
            var current = delivery(fixture);
            assertThat(current.get("status")).isEqualTo("RETRY_WAIT");
            assertThat(current.get("replay_generation")).isEqualTo(1L);
            assertThat(current.get("run_attempts")).isEqualTo(1);
            assertThat(current.get("next_attempt_at")).isNotNull();
            assertThat(history(fixture)).hasSize(3);
        }
    }

    @Test
    void failedDatabaseResetRollsBackReplayWithoutChangingHistoryOrConsumingGeneration() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var fixture = failedFixture(browser);
            var before = delivery(fixture);
            var retained = history(fixture);
            String constraint = "it_replay_" + UUID.randomUUID().toString().replace("-", "");
            jdbc.execute("ALTER TABLE deliveries ADD CONSTRAINT " + constraint + " CHECK (id <> '"
                    + fixture.deliveryId() + "'::uuid OR replay_generation = 0) NOT VALID");
            try {
                assertThat(bearerReplay(http, fixture, 0).statusCode()).isEqualTo(500);
                assertThat(delivery(fixture)).isEqualTo(before);
                assertThat(history(fixture)).isEqualTo(retained);
            } finally {
                jdbc.execute("ALTER TABLE deliveries DROP CONSTRAINT " + constraint);
            }
            assertAccepted(bearerReplay(http, fixture, 0), fixture, 1);
        }
    }

    private Fixture failedFixture(Browser browser) throws Exception {
        browser.csrf();
        String email = "replay-" + UUID.randomUUID() + "@example.test";
        String password = "replay fixture password 42!";
        var account = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", password));
        assertThat(account.statusCode()).isEqualTo(201);
        UUID accountId = UUID.fromString(json(account).get("id").asString());
        accounts.add(accountId);
        assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
        browser.csrf();
        var workspace = browser.json("POST", "/api/workspaces", Map.of("name", "Replay fixture"));
        assertThat(workspace.statusCode()).isEqualTo(201);
        UUID workspaceId = UUID.fromString(json(workspace).get("id").asString());
        var key = browser.json("POST", "/api/workspaces/" + workspaceId + "/api-keys", Map.of("name", "Replay client"));
        assertThat(key.statusCode()).isEqualTo(201);
        var endpoint = browser.json("POST", "/api/workspaces/" + workspaceId + "/endpoints",
                Map.of("url", "https://receiver.example.test/replay"));
        assertThat(endpoint.statusCode()).isEqualTo(201);
        UUID endpointId = UUID.fromString(json(endpoint).get("id").asString());
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("INSERT INTO events (id, workspace_id, endpoint_id, payload, request_digest) VALUES (?, ?, ?, ?, ?)",
                eventId, workspaceId, endpointId, "{}".getBytes(StandardCharsets.UTF_8), new byte[32]);
        jdbc.update("INSERT INTO deliveries (id, workspace_id, event_id, status, claim_generation, replay_generation, "
                + "run_attempts, max_attempts) VALUES (?, ?, ?, 'FAILED', 2, 0, 2, 2)", deliveryId, workspaceId, eventId);
        for (int attempt = 1; attempt <= 2; attempt++) {
            jdbc.update("INSERT INTO delivery_attempts (id, workspace_id, delivery_id, claim_generation, claim_token, "
                    + "replay_generation, run_attempt, started_at, finished_at, outcome, http_status, failure_code, duration_ms) "
                    + "VALUES (?, ?, ?, ?, ?, 0, ?, clock_timestamp() - interval '1 second', clock_timestamp(), "
                    + "'FAILED', 400, 'HTTP_STATUS', 1)", UUID.randomUUID(), workspaceId, deliveryId, attempt,
                    UUID.randomUUID(), attempt);
        }
        return new Fixture(workspaceId, endpointId, eventId, deliveryId, json(key).get("key").asString());
    }

    private HttpResponse<String> bearerReplay(HttpClient http, Fixture fixture, long expected) throws Exception {
        return request(http, "POST", apiPath(fixture), "{\"expectedReplayGeneration\":" + expected + "}",
                "application/json", Map.of("Authorization", "Bearer " + fixture.key()));
    }

    private Map<String, Object> delivery(Fixture fixture) {
        return jdbc.queryForMap("SELECT * FROM deliveries WHERE id = ?", fixture.deliveryId());
    }

    private List<Map<String, Object>> history(Fixture fixture) {
        return jdbc.queryForList("SELECT id, claim_generation, replay_generation, run_attempt, outcome, started_at, "
                + "finished_at, http_status, failure_code, duration_ms FROM delivery_attempts WHERE delivery_id = ? "
                + "ORDER BY claim_generation", fixture.deliveryId());
    }

    private static void assertAccepted(HttpResponse<String> response, Fixture fixture, long generation) {
        assertThat(response.statusCode()).isEqualTo(202);
        var body = json(response);
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.get("eventId").asString()).isEqualTo(fixture.eventId().toString());
        assertThat(body.get("deliveryId").asString()).isEqualTo(fixture.deliveryId().toString());
        assertThat(body.get("replayGeneration").asLong()).isEqualTo(generation);
        assertThat(body.get("status").asString()).isEqualTo("PENDING");
    }

    private static String apiPath(Fixture fixture) {
        return "/api/v1/events/" + fixture.eventId() + "/replay";
    }

    private static String browserPath(Fixture fixture) {
        return "/api/workspaces/" + fixture.workspaceId() + "/events/" + fixture.eventId() + "/replay";
    }

    private record Fixture(UUID workspaceId, UUID endpointId, UUID eventId, UUID deliveryId, String key) { }
}
