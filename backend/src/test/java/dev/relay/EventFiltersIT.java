package dev.relay;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.relay.delivery.DeliveryStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and PostgreSQL filtering, including pagination and both workspace authentication modes. */
class EventFiltersIT extends HttpIntegrationTestSupport {
    private final List<UUID> accounts = new ArrayList<>();
    private int eventOrder;

    @AfterEach
    void removeFixtures() {
        for (UUID accountId : accounts) {
            for (String table : List.of("delivery_attempts", "deliveries", "events", "endpoint_signing_keys", "endpoints", "api_keys")) {
                jdbc.update("DELETE FROM " + table + " WHERE workspace_id IN "
                        + "(SELECT id FROM workspaces WHERE owner_account_id = ?)", accountId);
            }
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
    }

    @Test
    void statusFilteringPrecedesLimitOffsetAndHasMoreForBothAuthenticationModes() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var tenant = tenant(browser);
            String oldest = event(http, tenant, tenant.endpointId(), DeliveryStatus.FAILED);
            event(http, tenant, tenant.endpointId(), DeliveryStatus.PENDING);
            String middle = event(http, tenant, tenant.endpointId(), DeliveryStatus.FAILED);
            event(http, tenant, tenant.endpointId(), DeliveryStatus.PENDING);
            String newest = event(http, tenant, tenant.endpointId(), DeliveryStatus.FAILED);
            String newestUnmatched = event(http, tenant, tenant.endpointId(), DeliveryStatus.PENDING);

            for (boolean bearer : new boolean[]{false, true}) {
                var defaults = page(http, browser, tenant, "", bearer);
                assertThat(defaults.get("limit").asInt()).isEqualTo(20);
                assertThat(defaults.get("offset").asInt()).isZero();
                assertThat(defaults.get("items").get(0).get("eventId").asString()).isEqualTo(newestUnmatched);
                assertThat(defaults.get("items").get(0).size()).isEqualTo(9);
                assertPage(page(http, browser, tenant, "?status=FAILED&limit=1&offset=0", bearer),
                        List.of(newest), 1, 0, true);
                assertPage(page(http, browser, tenant, "?status=FAILED&limit=1&offset=1", bearer),
                        List.of(middle), 1, 1, true);
                assertPage(page(http, browser, tenant, "?status=FAILED&limit=1&offset=2", bearer),
                        List.of(oldest), 1, 2, false);
                assertPage(page(http, browser, tenant, "?status=FAILED&limit=1&offset=3", bearer),
                        List.of(), 1, 3, false);
            }
        }
    }

    @Test
    void endpointAndCombinedFiltersSelectTheirOwnPagesRatherThanFilteringAnExistingPage() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var tenant = tenant(browser);
            UUID otherEndpoint = endpoint(browser, tenant.workspaceId());
            String firstFailure = event(http, tenant, tenant.endpointId(), DeliveryStatus.FAILED);
            event(http, tenant, otherEndpoint, DeliveryStatus.FAILED);
            String success = event(http, tenant, tenant.endpointId(), DeliveryStatus.SUCCEEDED);
            event(http, tenant, otherEndpoint, DeliveryStatus.SUCCEEDED);
            String lastFailure = event(http, tenant, tenant.endpointId(), DeliveryStatus.FAILED);
            event(http, tenant, otherEndpoint, DeliveryStatus.FAILED);
            UUID emptyEndpoint = endpoint(browser, tenant.workspaceId());

            for (boolean bearer : new boolean[]{false, true}) {
                String endpointQuery = "?endpointId=" + tenant.endpointId() + "&limit=1";
                assertPage(page(http, browser, tenant, endpointQuery + "&offset=0", bearer),
                        List.of(lastFailure), 1, 0, true);
                assertPage(page(http, browser, tenant, endpointQuery + "&offset=1", bearer),
                        List.of(success), 1, 1, true);
                assertPage(page(http, browser, tenant, endpointQuery + "&offset=2", bearer),
                        List.of(firstFailure), 1, 2, false);
                String combined = endpointQuery + "&status=FAILED";
                assertPage(page(http, browser, tenant, combined + "&offset=0", bearer),
                        List.of(lastFailure), 1, 0, true);
                assertPage(page(http, browser, tenant, combined + "&offset=1", bearer),
                        List.of(firstFailure), 1, 1, false);
                assertPage(page(http, browser, tenant, combined + "&offset=2", bearer),
                        List.of(), 1, 2, false);
                assertPage(page(http, browser, tenant, "?endpointId=" + emptyEndpoint, bearer),
                        List.of(), 20, 0, false);
            }
        }
    }

    @Test
    void everySupportedStateCanBeFilteredAndInvalidStatusUuidAndPaginationAreRejected() throws Exception {
        try (var browser = new Browser(); var http = statelessClient()) {
            var tenant = tenant(browser);
            var byStatus = new java.util.EnumMap<DeliveryStatus, String>(DeliveryStatus.class);
            for (var status : DeliveryStatus.values()) {
                byStatus.put(status, event(http, tenant, tenant.endpointId(), status));
            }
            for (boolean bearer : new boolean[]{false, true}) {
                for (var status : DeliveryStatus.values()) {
                    var selected = page(http, browser, tenant, "?status=" + status, bearer);
                    assertPage(selected, List.of(byStatus.get(status)), 20, 0, false);
                    assertThat(selected.get("items").get(0).get("status").asString()).isEqualTo(status.name());
                }
                for (String query : List.of("?status=unknown-private-state", "?status=failed", "?status=IN_PROGRESS",
                        "?status=FAILED,SUCCEEDED", "?endpointId=private-not-a-uuid", "?status=FAILED&endpointId=invalid",
                        "?status=FAILED&limit=101", "?endpointId=" + tenant.endpointId() + "&offset=-1")) {
                    var rejected = list(http, browser, tenant, query, bearer);
                    assertThat(rejected.statusCode()).as(query).isEqualTo(400);
                    assertThat(rejected.body()).doesNotContain("unknown-private-state", "private-not-a-uuid");
                }
            }
        }
    }

    @Test
    void filtersStayWithinTheAuthorizedWorkspaceAndDoNotRevealForeignEndpointExistence() throws Exception {
        try (var alice = new Browser(); var bob = new Browser(); var http = statelessClient()) {
            var owner = tenant(alice);
            var sameOwnerOtherWorkspace = workspace(alice);
            var outsider = tenant(bob);
            String ownEvent = event(http, owner, owner.endpointId(), DeliveryStatus.FAILED);
            String otherWorkspaceEvent = event(http, sameOwnerOtherWorkspace, sameOwnerOtherWorkspace.endpointId(), DeliveryStatus.FAILED);
            String outsiderEvent = event(http, outsider, outsider.endpointId(), DeliveryStatus.FAILED);
            UUID missingEndpoint = UUID.randomUUID();

            for (boolean bearer : new boolean[]{false, true}) {
                var scoped = page(http, alice, owner, "?status=FAILED", bearer);
                assertPage(scoped, List.of(ownEvent), 20, 0, false);
                assertThat(scoped.toString()).doesNotContain(otherWorkspaceEvent, outsiderEvent);
                var missing = page(http, alice, owner, "?status=FAILED&endpointId=" + missingEndpoint, bearer);
                for (UUID foreignEndpoint : List.of(sameOwnerOtherWorkspace.endpointId(), outsider.endpointId())) {
                    var foreign = page(http, alice, owner, "?status=FAILED&endpointId=" + foreignEndpoint, bearer);
                    assertThat(foreign).as("a foreign endpoint and nonexistent endpoint have the same empty result")
                            .isEqualTo(missing);
                }
            }
            assertThat(bob.get(browserPath(owner) + "?status=FAILED&endpointId=" + owner.endpointId()).statusCode())
                    .isEqualTo(404);
            assertThat(alice.get(browserPath(outsider) + "?status=FAILED&endpointId=" + owner.endpointId()).statusCode())
                    .isEqualTo(404);
            assertPage(page(http, bob, outsider, "?status=FAILED&endpointId=" + owner.endpointId(), true),
                    List.of(), 20, 0, false);
        }
    }

    private Tenant tenant(Browser browser) throws Exception {
        browser.csrf();
        String email = "event-filter-" + UUID.randomUUID() + "@example.test";
        String password = "event filter test password 42!";
        var registered = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", password));
        assertThat(registered.statusCode()).isEqualTo(201);
        accounts.add(UUID.fromString(json(registered).get("id").asString()));
        assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
        browser.csrf();
        return workspace(browser);
    }

    private Tenant workspace(Browser browser) throws Exception {
        var workspace = browser.json("POST", "/api/workspaces", Map.of("name", "Event filter fixture"));
        assertThat(workspace.statusCode()).isEqualTo(201);
        UUID workspaceId = UUID.fromString(json(workspace).get("id").asString());
        var key = browser.json("POST", "/api/workspaces/" + workspaceId + "/api-keys", Map.of("name", "Filter client"));
        assertThat(key.statusCode()).isEqualTo(201);
        return new Tenant(workspaceId, json(key).get("key").asString(), endpoint(browser, workspaceId));
    }

    private UUID endpoint(Browser browser, UUID workspaceId) throws Exception {
        var created = browser.json("POST", "/api/workspaces/" + workspaceId + "/endpoints",
                Map.of("url", "https://receiver.company.com/" + UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(201);
        return UUID.fromString(json(created).get("id").asString());
    }

    private String event(HttpClient http, Tenant tenant, UUID endpointId, DeliveryStatus status) throws Exception {
        var accepted = request(http, "POST", "/api/v1/endpoints/" + endpointId + "/events", "{}", "application/json",
                Map.of("Authorization", "Bearer " + tenant.key()));
        assertThat(accepted.statusCode()).isEqualTo(202);
        UUID eventId = UUID.fromString(json(accepted).get("eventId").asString());
        UUID deliveryId = UUID.fromString(json(accepted).get("deliveryId").asString());
        jdbc.update("UPDATE events SET accepted_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(eventOrder++)), eventId);
        if (status != DeliveryStatus.PENDING) {
            // Prepare durable states without making this event-list test depend on network execution.
            jdbc.update("""
                    UPDATE deliveries SET status = ?, claim_generation = 1, run_attempts = 1,
                        claim_token = ?, lease_expires_at = ?, next_attempt_at = ? WHERE id = ?
                    """, status.name(), status == DeliveryStatus.IN_FLIGHT ? UUID.randomUUID() : null,
                    status == DeliveryStatus.IN_FLIGHT ? Timestamp.from(Instant.now().plusSeconds(120)) : null,
                    status == DeliveryStatus.RETRY_WAIT ? Timestamp.from(Instant.now().plusSeconds(120)) : null, deliveryId);
        }
        return eventId.toString();
    }

    private static HttpResponse<String> list(HttpClient http, Browser browser, Tenant tenant, String query,
                                              boolean bearer) throws Exception {
        return bearer ? request(http, "GET", "/api/v1/events" + query, null, null,
                Map.of("Authorization", "Bearer " + tenant.key())) : browser.get(browserPath(tenant) + query);
    }

    private static JsonNode page(HttpClient http, Browser browser, Tenant tenant, String query,
                                 boolean bearer) throws Exception {
        var response = list(http, browser, tenant, query, bearer);
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response);
    }

    private static void assertPage(JsonNode page, List<String> ids, int limit, int offset, boolean more) {
        var actual = new ArrayList<String>();
        page.get("items").forEach(item -> actual.add(item.get("eventId").asString()));
        assertThat(actual).containsExactlyElementsOf(ids);
        assertThat(page.get("limit").asInt()).isEqualTo(limit);
        assertThat(page.get("offset").asInt()).isEqualTo(offset);
        assertThat(page.get("hasMore").asBoolean()).isEqualTo(more);
    }

    private static String browserPath(Tenant tenant) { return "/api/workspaces/" + tenant.workspaceId() + "/events"; }

    private record Tenant(UUID workspaceId, String key, UUID endpointId) {
        @Override public String toString() { return "Tenant[workspaceId=" + workspaceId + "]"; }
    }
}
