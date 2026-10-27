package dev.relay;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ingestion is verified over HTTP against PostgreSQL, including contention and rollback. */
@ExtendWith(OutputCaptureExtension.class)
class IngestionIT extends HttpIntegrationTestSupport {
    private static final String PASSWORD = "ingestion fixture password 42!";
    private static final int MAX_PAYLOAD_BYTES = 262_144;
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
    void acceptanceCommitsEventAndDeliveryThatRemainReadableFromFreshApplication(CapturedOutput output)
            throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = tenant(browser);
            String marker = "private-payload-" + UUID.randomUUID();
            String payload = "{\"type\":\"order.created\",\"private\":\"" + marker + "\"}";
            var accepted = ingest(http, owner, "durable", payload);
            var acceptance = acceptance(accepted);
            String eventId = acceptance.get("eventId").asString();
            String deliveryId = acceptance.get("deliveryId").asString();
            assertThat(accepted.headers().firstValue("Location").orElse(""))
                    .isEqualTo("/api/v1/events/" + eventId);
            assertThat(jdbc.queryForObject("SELECT payload FROM events WHERE id = ?", byte[].class,
                    UUID.fromString(eventId))).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE event_id = ? AND id = ? "
                    + "AND status = 'PENDING'", Integer.class, UUID.fromString(eventId), UUID.fromString(deliveryId)))
                    .isEqualTo(1);

            assertThatThrownBy(() -> jdbc.update("INSERT INTO deliveries "
                    + "(id, workspace_id, event_id, status, created_at) VALUES (?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), UUID.fromString(owner.workspaceId()), UUID.fromString(eventId)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            var secondWorkspace = workspace(browser, owner.accountId());
            assertThatThrownBy(() -> jdbc.update("INSERT INTO events "
                    + "(id, workspace_id, endpoint_id, payload, request_digest, accepted_at) "
                    + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)", UUID.randomUUID(),
                    UUID.fromString(secondWorkspace.workspaceId()), UUID.fromString(owner.endpointId()),
                    "{}".getBytes(StandardCharsets.UTF_8), new byte[32]))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // A second context has a separate connection pool and no shared in-memory acceptance state.
            try (var freshApi = application().web(WebApplicationType.SERVLET).run("--server.port=0")) {
                int port = ((WebServerApplicationContext) freshApi).getWebServer().getPort();
                var saved = getEvent(http, URI.create("http://127.0.0.1:" + port), owner.apiKey(), eventId);
                assertThat(saved.statusCode()).isEqualTo(200);
                assertEventView(json(saved), eventId, owner.endpointId(), deliveryId);
                assertThat(saved.body()).doesNotContain(marker, "payload", "requestDigest", "request_digest");
            }
            assertThat(output.getAll()).doesNotContain(marker, owner.apiKey());
        }
    }

    @Test
    void deliveryInsertFailureRollsBackEventAndDoesNotConsumeIdempotencyKey(CapturedOutput output) throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = tenant(browser);
            String marker = "rollback-private-payload-" + UUID.randomUUID();
            String payload = "{\"private\":\"" + marker + "\"}";
            String constraint = "it_reject_delivery_" + UUID.randomUUID().toString().replace("-", "");
            // NOT VALID avoids examining other fixtures; PostgreSQL still enforces this for every new row.
            jdbc.execute("ALTER TABLE deliveries ADD CONSTRAINT " + constraint
                    + " CHECK (workspace_id <> '" + owner.workspaceId() + "'::uuid) NOT VALID");
            try {
                assertThat(ingest(http, owner, "rollback", payload).statusCode()).isEqualTo(500);
                assertCounts(owner, 0, 0);
                assertThat(output.getAll()).doesNotContain(marker, owner.apiKey());
            } finally {
                jdbc.execute("ALTER TABLE deliveries DROP CONSTRAINT " + constraint);
            }
            acceptance(ingest(http, owner, "rollback", payload));
            assertCounts(owner, 1, 1);
        }
    }

    @Test
    void concurrentIdenticalRequestsAllReturnOneStableAcceptance() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = tenant(browser);
            var responses = race(http, owner, "same-key", List.of(
                    "{\"same\":true}", "{\"same\":true}", "{\"same\":true}", "{\"same\":true}",
                    "{\"same\":true}", "{\"same\":true}", "{\"same\":true}", "{\"same\":true}"));
            String original = responses.getFirst().body();
            for (var response : responses) {
                acceptance(response);
                assertThat(response.body()).isEqualTo(original);
            }
            assertCounts(owner, 1, 1);
        }
    }

    @Test
    void concurrentConflictsHaveOneWinnerAndIdempotencyKeysAreWorkspaceScoped() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = tenant(browser);
            var outcomes = race(http, owner, "workspace-key", List.of("{\"version\":1}", "{\"version\":2}"));
            assertThat(outcomes.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(202, 409);
            assertCounts(owner, 1, 1);
            var other = workspace(browser, owner.accountId());
            acceptance(ingest(http, other, "workspace-key", "{\"version\":99}"));
            assertCounts(other, 1, 1);
            String winner = outcomes.get(0).statusCode() == 202 ? "{\"version\":1}" : "{\"version\":2}";
            String acceptance = outcomes.stream().filter(response -> response.statusCode() == 202)
                    .findFirst().orElseThrow().body();
            assertThat(ingest(http, owner, "workspace-key", winner).body()).isEqualTo(acceptance);
        }
    }

    @Test
    void idempotencyComparesExactPayloadBytesAndEndpointWhileOmittedKeysAlwaysCreate() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = tenant(browser);
            String payload = "{\"number\":1,\"label\":\"café\"}";
            var first = ingest(http, owner, "exact.bytes:1", payload);
            acceptance(first);
            var repeated = ingest(http, owner, "exact.bytes:1", payload);
            assertThat(repeated.statusCode()).isEqualTo(202);
            assertThat(repeated.body()).isEqualTo(first.body());
            assertThat(ingest(http, owner, "exact.bytes:1", "{ \"number\": 1, \"label\": \"café\" }")
                    .statusCode()).isEqualTo(409);
            String otherEndpoint = createEndpoint(browser, owner.workspaceId(), "https://other.example.test/hook")
                    .get("id").asString();
            assertThat(ingest(http, owner.apiKey(), otherEndpoint, "exact.bytes:1", payload).statusCode())
                    .isEqualTo(409);
            var withoutKey = acceptance(ingest(http, owner, null, payload));
            var againWithoutKey = acceptance(ingest(http, owner, null, payload));
            assertThat(withoutKey.get("eventId").asString()).isNotEqualTo(againWithoutKey.get("eventId").asString());
            assertCounts(owner, 3, 3);
        }
    }

    @Test
    void endpointManagementValidatesUrlsAndEnforcesOwnerAndCsrf() throws Exception {
        try (var alice = new Browser(); var bob = new Browser(); var http = statelessClient()) {
            var owner = tenant(alice);
            var other = tenant(bob);
            String collection = endpointCollection(owner);
            String item = collection + "/" + owner.endpointId();
            var own = alice.get(item);
            assertThat(own.statusCode()).isEqualTo(200);
            assertThat(json(own).get("paused").asBoolean()).isFalse();
            assertThat(json(own).get("url").asString()).isEqualTo(owner.endpointUrl());
            assertThat(json(own).get("createdAt").asString()).isNotBlank();
            assertThat(json(own).get("updatedAt").asString()).isNotBlank();
            assertThat(bob.get(collection).statusCode()).isEqualTo(404);
            assertThat(bob.get(item).statusCode()).isEqualTo(404);
            assertThat(bob.json("POST", collection, Map.of("url", "https://example.test/hook")).statusCode())
                    .isEqualTo(404);
            assertThat(bob.json("PATCH", item, Map.of("paused", true)).statusCode()).isEqualTo(404);
            assertThat(alice.get(endpointCollection(other) + "/" + owner.endpointId()).statusCode()).isEqualTo(404);
            assertThat(alice.json("POST", collection, Map.of("url", "https://example.test/hook"), false)
                    .statusCode()).isEqualTo(403);
            assertThat(alice.json("PATCH", item, Map.of("paused", true), false).statusCode()).isEqualTo(403);
            assertThat(request(http, "GET", item, null, null, Map.of("Authorization", "Bearer " + owner.apiKey()))
                    .statusCode()).isEqualTo(401);
            assertThat(alice.json("PATCH", item, Map.of()).statusCode()).isEqualTo(400);

            for (String invalid : List.of("http://example.test/hook", "/hook", "https:///hook",
                    "https://user:password@example.test/hook", "https://example.test/hook#fragment",
                    "https://example.test:0/hook", "https://example.test:65536/hook", "https://例え.テスト/hook", "")) {
                assertThat(alice.json("POST", collection, Map.of("url", invalid)).statusCode())
                        .as("invalid HTTPS endpoint").isEqualTo(400);
            }
            String prefix = "https://example.test/";
            String longest = prefix + "x".repeat(2048 - prefix.length());
            assertThat(createEndpoint(alice, owner.workspaceId(), longest).get("url").asString()).isEqualTo(longest);
            assertThat(alice.json("POST", collection, Map.of("url", longest + "x")).statusCode()).isEqualTo(400);
            // Registration performs syntax checks only. No outbound request or DNS resolution occurs in Phase 2.
            createEndpoint(alice, owner.workspaceId(), "https://127.0.0.1:8443/local-test");
            createEndpoint(alice, owner.workspaceId(), "https://[::1]:8443/local-test");
        }
    }

    @Test
    void pausedEndpointRejectsNewEventsButHonorsAlreadyAcceptedIdempotencyKeys() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = tenant(browser);
            String payload = "{\"pause\":\"retained\"}";
            var original = ingest(http, owner, "before-pause", payload);
            acceptance(original);
            String item = endpointCollection(owner) + "/" + owner.endpointId();
            var paused = browser.json("PATCH", item, Map.of("paused", true));
            assertThat(paused.statusCode()).isEqualTo(200);
            assertThat(json(paused).get("paused").asBoolean()).isTrue();
            var duplicate = ingest(http, owner, "before-pause", payload);
            assertThat(duplicate.statusCode()).isEqualTo(202);
            assertThat(duplicate.body()).isEqualTo(original.body());
            assertThat(ingest(http, owner, "while-paused", payload).statusCode()).isEqualTo(409);
            assertThat(ingest(http, owner, null, payload).statusCode()).isEqualTo(409);
            assertCounts(owner, 1, 1);
            assertThat(browser.json("PATCH", item, Map.of("paused", false)).statusCode()).isEqualTo(200);
            acceptance(ingest(http, owner, "while-paused", payload));
            assertCounts(owner, 2, 2);
        }
    }

    @Test
    void eventReadsAndWritesEnforceWorkspaceForBrowserSessionsAndApiKeys() throws Exception {
        try (var alice = new Browser(); var bob = new Browser(); var http = statelessClient()) {
            var owner = tenant(alice);
            var other = tenant(bob);
            var accepted = acceptance(ingest(http, owner, "owner-event", "{\"owner\":1}"));
            String eventId = accepted.get("eventId").asString();
            String ownPath = "/api/workspaces/" + owner.workspaceId() + "/events";
            assertThat(ingest(http, other.apiKey(), owner.endpointId(), "foreign", "{}").statusCode())
                    .isEqualTo(404);
            assertThat(getEvent(http, baseUri, other.apiKey(), eventId).statusCode()).isEqualTo(404);
            assertThat(bob.get(ownPath).statusCode()).isEqualTo(404);
            assertThat(bob.get(ownPath + "/" + eventId).statusCode()).isEqualTo(404);
            assertThat(alice.get("/api/workspaces/" + other.workspaceId() + "/events/" + eventId).statusCode())
                    .isEqualTo(404);
            assertThat(alice.get(ownPath + "/" + eventId).statusCode()).isEqualTo(200);
            assertThat(eventIds(json(alice.get(ownPath)))).containsExactly(eventId);
            assertThat(eventIds(json(apiGet(http, other.apiKey(), "/api/v1/events")))).isEmpty();
            assertThat(apiGet(http, owner.apiKey(), "/api/v1/events/" + UUID.randomUUID()).statusCode())
                    .isEqualTo(404);
            assertThat(request(alice.http, "POST", ingestionPath(owner.endpointId()), "{}", "application/json",
                    Map.of()).statusCode()).isEqualTo(401);
            assertThat(request(http, "POST", ingestionPath(owner.endpointId()), "{}", "application/json", Map.of())
                    .statusCode()).isEqualTo(401);
            assertThat(request(http, "GET", ownPath, null, null, Map.of("Authorization", "Bearer " + owner.apiKey()))
                    .statusCode()).isEqualTo(401);
            assertCounts(other, 0, 0);
        }
    }

    @Test
    void eventAndEndpointPaginationEnforcesBoundsAndStableEventOrdering() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = tenant(browser);
            var ids = new ArrayList<String>();
            for (int index = 0; index < 3; index++) {
                ids.add(acceptance(ingest(http, owner, "page-" + index, "{\"page\":" + index + "}"))
                        .get("eventId").asString());
            }
            jdbc.update("UPDATE events SET accepted_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' WHERE workspace_id = ?",
                    UUID.fromString(owner.workspaceId()));
            ids.sort(Comparator.reverseOrder());
            String browserEvents = "/api/workspaces/" + owner.workspaceId() + "/events";
            for (String collection : List.of("/api/v1/events", browserEvents)) {
                var first = collection.startsWith("/api/v1")
                        ? apiGet(http, owner.apiKey(), collection + "?limit=2&offset=0")
                        : browser.get(collection + "?limit=2&offset=0");
                assertThat(first.statusCode()).isEqualTo(200);
                var page = json(first);
                assertThat(eventIds(page)).containsExactlyElementsOf(ids.subList(0, 2));
                assertThat(page.get("limit").asInt()).isEqualTo(2);
                assertThat(page.get("offset").asInt()).isZero();
                assertThat(page.get("hasMore").asBoolean()).isTrue();
                var last = collection.startsWith("/api/v1")
                        ? apiGet(http, owner.apiKey(), collection + "?limit=2&offset=2")
                        : browser.get(collection + "?limit=2&offset=2");
                assertThat(eventIds(json(last))).containsExactly(ids.get(2));
                assertThat(json(last).get("hasMore").asBoolean()).isFalse();
            }
            var defaultPage = json(apiGet(http, owner.apiKey(), "/api/v1/events"));
            assertThat(defaultPage.get("limit").asInt()).isEqualTo(20);
            assertThat(defaultPage.get("offset").asInt()).isZero();
            assertThat(eventIds(json(apiGet(http, owner.apiKey(), "/api/v1/events?offset=99")))).isEmpty();

            createEndpoint(browser, owner.workspaceId(), "https://page-two.example.test/hook");
            createEndpoint(browser, owner.workspaceId(), "https://page-three.example.test/hook");
            String endpoints = endpointCollection(owner);
            var endpointFirst = json(browser.get(endpoints + "?limit=2&offset=0"));
            var endpointLast = json(browser.get(endpoints + "?limit=2&offset=2"));
            assertThat(endpointFirst.get("items").size()).isEqualTo(2);
            assertThat(endpointFirst.get("hasMore").asBoolean()).isTrue();
            assertThat(endpointLast.get("items").size()).isEqualTo(1);
            assertThat(endpointLast.get("hasMore").asBoolean()).isFalse();
            assertThat(endpointIds(endpointFirst)).doesNotContainAnyElementsOf(endpointIds(endpointLast));
            assertThat(json(browser.get(endpoints)).get("limit").asInt()).isEqualTo(20);
            for (String query : List.of("?limit=0", "?limit=101", "?offset=-1", "?limit=nope")) {
                assertThat(apiGet(http, owner.apiKey(), "/api/v1/events" + query).statusCode()).isEqualTo(400);
                assertThat(browser.get(browserEvents + query).statusCode()).isEqualTo(400);
                assertThat(browser.get(endpoints + query).statusCode()).isEqualTo(400);
            }
        }
    }

    @Test
    void rawJsonUtf8HeadersAndKnownOrChunkedBodyLimitsAreValidated(CapturedOutput output) throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var owner = tenant(browser);
            for (String payload : List.of("", "[]", "null", "42", "true", "\"text\"", "{}{}", "{\"x\":", "{} trailing")) {
                assertThat(ingest(http, owner, null, payload).statusCode()).as("JSON object validation").isEqualTo(400);
            }
            byte[] badUtf8 = new byte[] { '{', '"', 'x', '"', ':', '"', (byte) 0xc3, (byte) 0x28, '"', '}' };
            assertThat(rawIngest(http, owner, HttpRequest.BodyPublishers.ofByteArray(badUtf8),
                    "application/json", List.of(), Map.of()).statusCode()).isEqualTo(400);
            for (String contentType : List.of("text/plain", "application/json; charset=ISO-8859-1")) {
                assertThat(rawIngest(http, owner, HttpRequest.BodyPublishers.ofString("{}"),
                        contentType, List.of(), Map.of()).statusCode()).isEqualTo(415);
            }
            assertThat(rawIngest(http, owner, HttpRequest.BodyPublishers.ofString("{}"), "application/json",
                    List.of(), Map.of("Content-Encoding", "gzip")).statusCode()).isEqualTo(415);
            var repeatedEncoding = HttpRequest.newBuilder(baseUri.resolve(ingestionPath(owner.endpointId())))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + owner.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "identity").header("Content-Encoding", "gzip")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertThat(http.send(repeatedEncoding, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(415);
            for (String invalidKey : List.of("", "bad key", "bad/key", "é", "x".repeat(129))) {
                assertThat(ingest(http, owner, invalidKey, "{}").statusCode()).as("Idempotency-Key validation")
                        .isEqualTo(400);
            }
            assertThat(rawIngest(http, owner, HttpRequest.BodyPublishers.ofString("{}"), "application/json",
                    List.of("duplicate", "duplicate"), Map.of()).statusCode()).isEqualTo(400);
            assertCounts(owner, 0, 0);

            // The multibyte boundary is measured in UTF-8 bytes, rather than Java characters.
            String maximum = "{\"data\":\"" + "é".repeat((MAX_PAYLOAD_BYTES - 12) / 2) + "x\"}";
            assertThat(maximum.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_PAYLOAD_BYTES);
            acceptance(ingest(http, owner, "x".repeat(128), maximum));
            byte[] oversized = ("{\"data\":\"" + "x".repeat(MAX_PAYLOAD_BYTES - 10) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            assertThat(oversized).hasSize(MAX_PAYLOAD_BYTES + 1);
            assertThat(rawIngest(http, owner, HttpRequest.BodyPublishers.ofByteArray(oversized),
                    "application/json", List.of(), Map.of()).statusCode()).isEqualTo(413);
            var chunked = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized));
            assertThat(chunked.contentLength()).isEqualTo(-1);
            assertThat(rawIngest(http, owner, chunked, "application/json", List.of(), Map.of()).statusCode())
                    .isEqualTo(413);
            assertCounts(owner, 1, 1);
            assertThat(output.getAll()).doesNotContain(owner.apiKey(), maximum);
        }
    }

    private Tenant tenant(Browser browser) throws Exception {
        browser.csrf();
        String email = "ingestion-" + UUID.randomUUID() + "@example.test";
        var register = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", PASSWORD));
        assertThat(register.statusCode()).isEqualTo(201);
        UUID accountId = UUID.fromString(json(register).get("id").asString());
        accounts.add(accountId);
        assertThat(browser.login(email, PASSWORD, true).statusCode()).isEqualTo(204);
        browser.csrf();
        return workspace(browser, accountId);
    }

    private Tenant workspace(Browser browser, UUID accountId) throws Exception {
        var workspace = browser.json("POST", "/api/workspaces", Map.of("name", "Ingestion fixtures"));
        assertThat(workspace.statusCode()).isEqualTo(201);
        String workspaceId = json(workspace).get("id").asString();
        var key = browser.json("POST", "/api/workspaces/" + workspaceId + "/api-keys", Map.of("name", "Test client"));
        assertThat(key.statusCode()).isEqualTo(201);
        String url = "https://receiver.example.test/hooks/" + UUID.randomUUID();
        String endpointId = createEndpoint(browser, workspaceId, url).get("id").asString();
        return new Tenant(accountId, workspaceId, json(key).get("key").asString(), endpointId, url);
    }

    private JsonNode createEndpoint(Browser browser, String workspaceId, String url) throws Exception {
        var response = browser.json("POST", "/api/workspaces/" + workspaceId + "/endpoints", Map.of("url", url));
        assertThat(response.statusCode()).as("endpoint creation").isEqualTo(201);
        return json(response);
    }

    private HttpResponse<String> ingest(HttpClient http, Tenant tenant, String key, String payload) throws Exception {
        return ingest(http, tenant.apiKey(), tenant.endpointId(), key, payload);
    }

    private HttpResponse<String> ingest(HttpClient http, String apiKey, String endpointId, String key, String payload)
            throws Exception {
        var headers = new java.util.HashMap<String, String>();
        headers.put("Authorization", "Bearer " + apiKey);
        if (key != null) headers.put("Idempotency-Key", key);
        return request(http, "POST", ingestionPath(endpointId), payload, "application/json", headers);
    }

    private HttpResponse<String> rawIngest(HttpClient http, Tenant tenant, HttpRequest.BodyPublisher body,
                                            String contentType, List<String> idempotencyKeys,
                                            Map<String, String> extraHeaders) throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve(ingestionPath(tenant.endpointId())))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + tenant.apiKey())
                .header("Content-Type", contentType).header("Accept", "application/json").POST(body);
        idempotencyKeys.forEach(key -> request.header("Idempotency-Key", key));
        extraHeaders.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<HttpResponse<String>> race(HttpClient http, Tenant owner, String key, List<String> payloads)
            throws Exception {
        var ready = new CountDownLatch(payloads.size());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = payloads.stream().map(payload -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
                return ingest(http, owner, key, payload);
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) responses.add(future.get(20, TimeUnit.SECONDS));
            return responses;
        }
    }

    private static HttpResponse<String> apiGet(HttpClient http, String apiKey, String path) throws Exception {
        return request(http, "GET", path, null, null, Map.of("Authorization", "Bearer " + apiKey));
    }

    private static HttpResponse<String> getEvent(HttpClient http, URI uri, String apiKey, String eventId)
            throws Exception {
        return http.send(HttpRequest.newBuilder(uri.resolve("/api/v1/events/" + eventId))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + apiKey).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode acceptance(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("durable event acceptance").isEqualTo(202);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        var acceptance = json(response);
        assertThat(acceptance.size()).isEqualTo(3);
        assertThat(UUID.fromString(acceptance.get("eventId").asString())).isNotNull();
        assertThat(UUID.fromString(acceptance.get("deliveryId").asString())).isNotNull();
        assertThat(acceptance.get("acceptedAt").asString()).isNotBlank();
        return acceptance;
    }

    private void assertCounts(Tenant tenant, int events, int deliveries) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE workspace_id = ?", Integer.class,
                UUID.fromString(tenant.workspaceId()))).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE workspace_id = ?", Integer.class,
                UUID.fromString(tenant.workspaceId()))).isEqualTo(deliveries);
    }

    private static void assertEventView(JsonNode view, String eventId, String endpointId, String deliveryId) {
        assertThat(view.size()).isEqualTo(9);
        assertThat(view.get("eventId").asString()).isEqualTo(eventId);
        assertThat(view.get("endpointId").asString()).isEqualTo(endpointId);
        assertThat(view.get("deliveryId").asString()).isEqualTo(deliveryId);
        assertThat(view.get("status").asString()).isEqualTo("PENDING");
        assertThat(view.get("acceptedAt").asString()).isNotBlank();
    }

    private static List<String> eventIds(JsonNode page) {
        var ids = new ArrayList<String>();
        var items = page.get("items");
        assertThat(items.isArray()).isTrue();
        for (int index = 0; index < items.size(); index++) ids.add(items.get(index).get("eventId").asString());
        return ids;
    }

    private static List<String> endpointIds(JsonNode page) {
        var ids = new ArrayList<String>();
        var items = page.get("items");
        for (int index = 0; index < items.size(); index++) ids.add(items.get(index).get("id").asString());
        return ids;
    }

    private static String ingestionPath(String endpointId) {
        return "/api/v1/endpoints/" + endpointId + "/events";
    }

    private static String endpointCollection(Tenant tenant) {
        return "/api/workspaces/" + tenant.workspaceId() + "/endpoints";
    }

    private record Tenant(UUID accountId, String workspaceId, String apiKey, String endpointId, String endpointUrl) { }
}
