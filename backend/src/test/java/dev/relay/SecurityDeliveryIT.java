package dev.relay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryProcessor;
import dev.relay.delivery.DeliveryRequest;
import dev.relay.delivery.DeliveryResult;
import dev.relay.delivery.signing.SigningKey;
import dev.relay.delivery.signing.WebhookSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Signed real delivery, bounded authorized previews and absence of sensitive material in application logs. */
@ExtendWith(OutputCaptureExtension.class)
class SecurityDeliveryIT extends HttpIntegrationTestSupport {
    private final List<UUID> accounts = new ArrayList<>();

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
    void signedDeliveryPersistsOnlyABoundedOwnerVisiblePreviewAndLogsNoSecrets(CapturedOutput output) throws Exception {
        try (var receiver = new ControllableReceiver(); var owner = new Browser(); var other = new Browser();
             var http = statelessClient()) {
            var customer = customer(owner);
            var outsider = customer(other);
            UUID endpointId = endpoint(customer, receiver.url());
            var revealed = owner.json("POST", "/api/workspaces/" + customer.workspaceId() + "/endpoints/"
                    + endpointId + "/signing-secret", null);
            assertThat(revealed.statusCode()).isEqualTo(200);
            assertThat(revealed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            String secret = json(revealed).get("secret").asString();
            String payloadMarker = "private-payload-" + UUID.randomUUID();
            String payload = "{\"private\":\"" + payloadMarker + "\",\"label\":\"café\"}";
            var accepted = request(http, "POST", "/api/v1/endpoints/" + endpointId + "/events", payload,
                    "application/json", Map.of("Authorization", "Bearer " + customer.apiKey()));
            assertThat(accepted.statusCode()).isEqualTo(202);
            UUID eventId = UUID.fromString(json(accepted).get("eventId").asString());
            UUID deliveryId = UUID.fromString(json(accepted).get("deliveryId").asString());
            String responseMarker = "private-response-" + UUID.randomUUID();
            String omittedTail = "omitted-tail-" + UUID.randomUUID();
            String body = responseMarker + "a".repeat(800) + omittedTail;
            receiver.respond(200, body);

            String signature;
            try (var worker = worker(receiver.url(), 96)) {
                var claims = worker.getBean(DeliveryClaimService.class);
                var claim = claims.claim(1, Duration.ofMinutes(2)).getFirst();
                assertThat(claim.deliveryId()).isEqualTo(deliveryId);
                worker.getBean(DeliveryProcessor.class).process(claim);
                var received = receiver.next(Duration.ofSeconds(2));
                assertThat(received).isNotNull();
                assertThat(received.body()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
                assertThat(received.header("Authorization")).isNull();
                assertThat(received.header("Cookie")).isNull();
                signature = received.header("X-Relay-Signature");
                assertThat(signature).matches("v1=[0-9a-f]{64}");
                var verification = new DeliveryRequest(receiver.url(), received.body(), eventId, deliveryId,
                        UUID.fromString(received.header("X-Relay-Attempt-Id")),
                        Long.parseLong(received.header("X-Relay-Attempt")),
                        new SigningKey(Base64.getDecoder().decode(secret)));
                assertThat(WebhookSignature.verify(verification, received.header("X-Relay-Timestamp"), signature,
                        Instant.now())).isTrue();

                var stored = jdbc.queryForMap("SELECT response_preview,response_truncated,http_status FROM delivery_attempts WHERE id = ?",
                        claim.attemptId());
                assertThat(stored).containsEntry("response_preview", body.substring(0, 96))
                        .containsEntry("response_truncated", true).containsEntry("http_status", 200);
                // A late duplicate must not replace a committed preview or status.
                assertThat(claims.complete(claim, new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null,
                        1, "late-forged-preview", false))).isFalse();
                assertThat(jdbc.queryForObject("SELECT response_preview FROM delivery_attempts WHERE id = ?",
                        String.class, claim.attemptId())).isEqualTo(body.substring(0, 96));
            }

            String path = "/api/v1/events/" + eventId + "/attempts";
            var history = request(http, "GET", path, null, null,
                    Map.of("Authorization", "Bearer " + customer.apiKey()));
            assertThat(history.statusCode()).isEqualTo(200);
            var attempt = json(history).get("items").get(0);
            assertThat(attempt.get("responsePreview").asString()).isEqualTo(body.substring(0, 96));
            assertThat(attempt.get("responseTruncated").asBoolean()).isTrue();
            assertThat(history.body()).doesNotContain(omittedTail, secret, customer.apiKey(), SIGNING_MASTER_KEY);
            String ownerPath = "/api/workspaces/" + customer.workspaceId() + "/events/" + eventId + "/attempts";
            assertThat(owner.get(ownerPath).statusCode()).isEqualTo(200);
            assertThat(other.get(ownerPath).statusCode()).isEqualTo(404);
            assertThat(request(http, "GET", path, null, null,
                    Map.of("Authorization", "Bearer " + outsider.apiKey())).statusCode()).isEqualTo(404);
            assertThat(owner.sessionId()).isNotBlank();
            assertThat(output.getAll()).doesNotContain(SIGNING_MASTER_KEY, secret, signature, customer.apiKey(),
                    outsider.apiKey(), customer.password(), outsider.password(), owner.sessionId(), other.sessionId(),
                    payloadMarker, payload, responseMarker, omittedTail, "late-forged-preview");
        }
    }

    @Test
    void disablingResponseCaptureKeepsHttpSuccessWithoutPersistingReceiverContent(CapturedOutput output) throws Exception {
        try (var receiver = new ControllableReceiver(); var browser = new Browser(); var http = statelessClient()) {
            var customer = customer(browser);
            UUID endpointId = endpoint(customer, receiver.url());
            var accepted = request(http, "POST", "/api/v1/endpoints/" + endpointId + "/events", "{}",
                    "application/json", Map.of("Authorization", "Bearer " + customer.apiKey()));
            assertThat(accepted.statusCode()).isEqualTo(202);
            UUID deliveryId = UUID.fromString(json(accepted).get("deliveryId").asString());
            String privateResponse = "capture-disabled-" + UUID.randomUUID();
            receiver.respond(200, privateResponse);
            try (var worker = worker(receiver.url(), 0)) {
                var claim = worker.getBean(DeliveryClaimService.class).claim(1, Duration.ofMinutes(2)).getFirst();
                worker.getBean(DeliveryProcessor.class).process(claim);
                assertThat(receiver.next(Duration.ofSeconds(2))).isNotNull();
                assertThat(jdbc.queryForObject("SELECT status FROM deliveries WHERE id = ?", String.class, deliveryId))
                        .isEqualTo("SUCCEEDED");
                assertThat(jdbc.queryForMap("SELECT http_status,response_preview,response_truncated "
                        + "FROM delivery_attempts WHERE id = ?", claim.attemptId()))
                        .containsEntry("http_status", 200).containsEntry("response_preview", "")
                        .containsEntry("response_truncated", true);
                assertThat(output.getAll()).doesNotContain(privateResponse, SIGNING_MASTER_KEY, customer.apiKey());
            }
        }
    }

    private Customer customer(Browser browser) throws Exception {
        browser.csrf();
        String email = "security-" + UUID.randomUUID() + "@example.test";
        String password = "privacy-password-" + UUID.randomUUID();
        var registered = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", password));
        assertThat(registered.statusCode()).isEqualTo(201);
        accounts.add(UUID.fromString(json(registered).get("id").asString()));
        assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
        browser.csrf();
        var workspace = browser.json("POST", "/api/workspaces", Map.of("name", "Security fixture"));
        assertThat(workspace.statusCode()).isEqualTo(201);
        UUID workspaceId = UUID.fromString(json(workspace).get("id").asString());
        var key = browser.json("POST", "/api/workspaces/" + workspaceId + "/api-keys", Map.of("name", "Security client"));
        assertThat(key.statusCode()).isEqualTo(201);
        return new Customer(workspaceId, json(key).get("key").asString(), password);
    }

    private UUID endpoint(Customer customer, URI receiver) {
        UUID id = UUID.randomUUID();
        // A controlled fixture permits local transport without broadening the normal HTTPS API.
        jdbc.update("INSERT INTO endpoints (id,workspace_id,url) VALUES (?, ?, ?)", id, customer.workspaceId(), receiver.toString());
        return id;
    }

    private ConfigurableApplicationContext worker(URI receiver, int captureBytes) {
        return application().profiles("worker", "development").web(WebApplicationType.NONE)
                .run("--spring.main.keep-alive=false", "--relay.worker.enabled=false",
                        "--relay.delivery.allow-local-receiver=true", "--relay.delivery.local-receiver-url=" + receiver,
                        "--relay.delivery.response-capture-bytes=" + captureBytes);
    }

    private record Customer(UUID workspaceId, String apiKey, String password) {
        @Override public String toString() { return "Customer[workspaceId=" + workspaceId + "]"; }
    }
}
