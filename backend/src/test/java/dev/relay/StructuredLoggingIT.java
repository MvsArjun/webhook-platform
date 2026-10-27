package dev.relay;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Parses the application's real ECS JSON output while exercising HTTP, committed SQL and delivery. */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT extends HttpIntegrationTestSupport {
    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (UUID account : accounts) {
            for (String table : List.of("delivery_attempts", "deliveries", "events", "endpoint_signing_keys", "endpoints", "api_keys")) {
                jdbc.update("DELETE FROM " + table + " WHERE workspace_id IN "
                        + "(SELECT id FROM workspaces WHERE owner_account_id = ?)", account);
            }
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", account);
            jdbc.update("DELETE FROM accounts WHERE id = ?", account);
        }
    }

    @Test
    void everyRejectedOrConcurrentRequestGetsItsOwnServerIdWithoutLoggingClientMarkers(CapturedOutput output)
            throws Exception {
        String hostile = "hostile-request-marker-" + UUID.randomUUID();
        String query = "private-query-marker-" + UUID.randomUUID();
        String body = "private-body-marker-" + UUID.randomUUID();
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (var http = statelessClient()) {
            responses.add(request(http, "GET", "/api/auth/me?private=" + query, null, null,
                    Map.of("X-Request-Id", hostile, "X-Private-Marker", hostile)));
            assertThat(responses.getLast().statusCode()).isEqualTo(401);
            responses.add(request(http, "POST", "/api/auth/register?private=" + query,
                    "{\"email\":\"" + body + "\",\"password\":\"" + body + "\"}", "application/json",
                    Map.of("X-Request-Id", hostile)));
            assertThat(responses.getLast().statusCode()).isEqualTo(403);
            responses.add(request(http, "POST", "/api/auth/register?private=" + query,
                    "{\"secret\":\"" + body + "x".repeat(17000) + "\"}", "application/json",
                    Map.of("X-Request-Id", hostile)));
            assertThat(responses.getLast().statusCode()).isEqualTo(413);
            try (var callers = Executors.newFixedThreadPool(4)) {
                var requests = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) requests.add(callers.submit(() ->
                        request(http, "GET", "/api/auth/csrf?private=" + query, null, null,
                                Map.of("X-Request-Id", hostile))));
                for (var future : requests) {
                    var response = future.get(5, TimeUnit.SECONDS);
                    assertThat(response.statusCode()).isEqualTo(200);
                    responses.add(response);
                }
            }
            var uniqueIds = new HashSet<String>();
            for (var response : responses) {
                String requestId = requestId(response);
                assertThat(uniqueIds.add(requestId)).isTrue();
                JsonNode record = awaitRecord(output, "request_completed", requestId);
                assertThat(record.get("status").asInt()).isEqualTo(response.statusCode());
                assertThat(record.get("duration_ms").asLong()).isGreaterThanOrEqualTo(0);
                assertThat(record.get("aborted").asBoolean()).isFalse();
                assertThat(text(record, "route_template")).isIn("OTHER", "/api/auth/csrf");
                assertThat(record.has("event_id") || record.has("delivery_id") || record.has("attempt_id")).isFalse();
                assertThat(records(output, "request_completed").stream()
                        .filter(row -> requestId.equals(text(row, "request_id"))).count()).isEqualTo(1);
            }
            assertThat(output.getAll()).doesNotContain(hostile, query, body);
        }
    }

    @Test
    void acceptanceRetryWorkerCompletionAndReplayHaveCommittedIdsInActualJsonWithoutSecrets(CapturedOutput output)
            throws Exception {
        try (var browser = new Browser(); var http = statelessClient(); var receiver = new ControllableReceiver()) {
            var owner = owner(browser);
            UUID endpointId = UUID.randomUUID();
            jdbc.update("INSERT INTO endpoints (id,workspace_id,url) VALUES (?, ?, ?)",
                    endpointId, owner.workspaceId(), receiver.url().toString());
            String marker = "private-event-body-" + UUID.randomUUID();
            String payload = "{\"private\":\"" + marker + "\"}";
            String clientHeader = "client-request-id-" + UUID.randomUUID();
            String idempotencyKey = "private-idempotency-" + UUID.randomUUID();
            Map<String, String> headers = Map.of("Authorization", "Bearer " + owner.apiKey(),
                    "Idempotency-Key", idempotencyKey, "X-Request-Id", clientHeader);
            var accepted = request(http, "POST", "/api/v1/endpoints/" + endpointId + "/events", payload,
                    "application/json", headers);
            var duplicate = request(http, "POST", "/api/v1/endpoints/" + endpointId + "/events", payload,
                    "application/json", headers);
            assertThat(accepted.statusCode()).isEqualTo(202);
            assertThat(duplicate.statusCode()).isEqualTo(202);
            assertThat(duplicate.body()).isEqualTo(accepted.body());
            UUID eventId = UUID.fromString(json(accepted).get("eventId").asString());
            UUID deliveryId = UUID.fromString(json(accepted).get("deliveryId").asString());
            for (var response : List.of(accepted, duplicate)) {
                JsonNode acceptance = awaitRecord(output, "acceptance_returned", requestId(response));
                assertThat(text(acceptance, "event_id")).isEqualTo(eventId.toString());
                assertThat(text(acceptance, "delivery_id")).isEqualTo(deliveryId.toString());
                JsonNode completed = awaitRecord(output, "request_completed", requestId(response));
                assertThat(text(completed, "route_template")).isEqualTo("/api/v1/endpoints/{endpointId}/events");
                assertThat(completed.get("status").asInt()).isEqualTo(202);
            }
            assertThat(requestId(accepted)).isNotEqualTo(requestId(duplicate));

            String privatePreview = "private-response-preview-" + UUID.randomUUID();
            receiver.respond(400, privatePreview);
            String token;
            try (var worker = application().profiles("worker", "development").web(WebApplicationType.NONE)
                    .run("--spring.main.keep-alive=false", "--relay.worker.enabled=false",
                            "--relay.delivery.allow-local-receiver=true", "--relay.delivery.local-receiver-url=" + receiver.url())) {
                var claim = worker.getBean(DeliveryClaimService.class).claim(1, Duration.ofMinutes(1)).getFirst();
                assertThat(claim.deliveryId()).isEqualTo(deliveryId);
                token = claim.token().toString();
                worker.getBean(DeliveryProcessor.class).process(claim);
                assertThat(receiver.next(Duration.ofSeconds(2))).isNotNull();
                List<JsonNode> completed = records(output, "completion_committed").stream()
                        .filter(row -> claim.attemptId().toString().equals(text(row, "attempt_id"))).toList();
                assertThat(completed).hasSize(1);
                JsonNode completion = completed.getFirst();
                assertThat(text(completion, "event_id")).isEqualTo(eventId.toString());
                assertThat(text(completion, "delivery_id")).isEqualTo(deliveryId.toString());
                assertThat(completion.get("generation").asLong()).isEqualTo(claim.generation());
                assertThat(text(completion, "outcome")).isEqualTo("FAILED");
                assertThat(completion.get("http_status").asInt()).isEqualTo(400);
                assertThat(text(completion, "failure_code")).isEqualTo("HTTP_STATUS");
                assertThat(completion.has("request_id")).isFalse();
                assertThat(jdbc.queryForObject("SELECT status FROM deliveries WHERE id = ?", String.class, deliveryId))
                        .isEqualTo("FAILED");
            }
            var replay = browser.json("POST", "/api/workspaces/" + owner.workspaceId() + "/events/" + eventId + "/replay",
                    Map.of("expectedReplayGeneration", 0));
            assertThat(replay.statusCode()).isEqualTo(202);
            JsonNode replayLog = awaitRecord(output, "replay_accepted", requestId(replay));
            assertThat(text(replayLog, "event_id")).isEqualTo(eventId.toString());
            assertThat(text(replayLog, "delivery_id")).isEqualTo(deliveryId.toString());
            assertThat(replayLog.get("replay_generation").asLong()).isEqualTo(1);
            var after = browser.get("/api/auth/me");
            JsonNode nextRequest = awaitRecord(output, "request_completed", requestId(after));
            assertThat(nextRequest.has("event_id") || nextRequest.has("delivery_id") || nextRequest.has("attempt_id")).isFalse();
            assertThat(output.getAll()).doesNotContain(marker, payload, clientHeader, idempotencyKey, owner.apiKey(),
                    owner.password(), browser.sessionId(), privatePreview, token, SIGNING_MASTER_KEY, receiver.url().toString());
        }
    }

    private Owner owner(Browser browser) throws Exception {
        browser.csrf();
        String email = "logging-" + UUID.randomUUID() + "@example.test";
        String password = "private-password-" + UUID.randomUUID();
        var account = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", password));
        assertThat(account.statusCode()).isEqualTo(201);
        accounts.add(UUID.fromString(json(account).get("id").asString()));
        assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
        browser.csrf();
        var workspace = browser.json("POST", "/api/workspaces", Map.of("name", "Logging fixture"));
        assertThat(workspace.statusCode()).isEqualTo(201);
        UUID workspaceId = UUID.fromString(json(workspace).get("id").asString());
        var key = browser.json("POST", "/api/workspaces/" + workspaceId + "/api-keys", Map.of("name", "Logging client"));
        assertThat(key.statusCode()).isEqualTo(201);
        return new Owner(workspaceId, json(key).get("key").asString(), password);
    }

    private static String requestId(HttpResponse<?> response) {
        List<String> values = response.headers().allValues("X-Request-Id");
        assertThat(values).hasSize(1);
        String value = values.getFirst();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
        return value;
    }

    private static JsonNode awaitRecord(CapturedOutput output, String type, String requestId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            var matching = records(output, type).stream().filter(row -> requestId.equals(text(row, "request_id"))).toList();
            if (!matching.isEmpty()) { assertThat(matching).hasSize(1); return matching.getFirst(); }
            Thread.sleep(10); // The socket may flush before the request filter's final log statement.
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected structured application log was not emitted");
    }

    private static List<JsonNode> records(CapturedOutput output, String type) {
        List<JsonNode> result = new ArrayList<>();
        for (String line : output.getAll().split("\\R")) {
            if (!line.startsWith("{") || !line.endsWith("}")) continue;
            JsonNode record = JSON.readTree(line);
            if (type.equals(text(record, "event_type"))) result.add(record);
        }
        return result;
    }

    private static String text(JsonNode record, String key) {
        return record.has(key) ? record.get(key).asString() : null;
    }

    private record Owner(UUID workspaceId, String apiKey, String password) {
        @Override public String toString() { return "Owner[workspaceId=" + workspaceId + "]"; }
    }
}
