package dev.relay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.relay.delivery.signing.SigningKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class SigningIT extends HttpIntegrationTestSupport {
    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (UUID accountId : accounts) {
            for (String table : List.of("endpoint_signing_keys", "endpoints", "api_keys")) {
                jdbc.update("DELETE FROM " + table + " WHERE workspace_id IN "
                        + "(SELECT id FROM workspaces WHERE owner_account_id = ?)", accountId);
            }
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
    }

    @Test
    void concurrentLazyProvisioningReturnsOneStableEncryptedEndpointSecret() throws Exception {
        try (var browser = new Browser()) {
            var fixture = fixture(browser);
            var keys = api.getBean(SigningKeyService.class);
            var ready = new CountDownLatch(6);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<byte[]>>();
                for (int index = 0; index < 6; index++) futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Key start timed out");
                    return keys.getOrCreate(fixture.workspace(), fixture.endpoint()).bytes();
                }));
                try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
                finally { start.countDown(); }
                byte[] expected = futures.getFirst().get(15, TimeUnit.SECONDS);
                for (var future : futures) assertThat(future.get(15, TimeUnit.SECONDS)).isEqualTo(expected);
                assertThat(expected).hasSize(32);
                byte[] stored = stored(fixture);
                assertThat(stored).hasSize(60);
                for (int offset = 0; offset <= stored.length - expected.length; offset++) {
                    assertThat(Arrays.equals(stored, offset, offset + 32, expected, 0, 32)).isFalse();
                }
                assertThat(keys.getOrCreate(fixture.workspace(), fixture.endpoint()).bytes()).isEqualTo(expected);
                assertThat(stored(fixture)).isEqualTo(stored);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM endpoint_signing_keys WHERE workspace_id = ? AND endpoint_id = ?",
                        Integer.class, fixture.workspace(), fixture.endpoint())).isEqualTo(1);
            }
        }
    }

    @Test
    void aadCiphertextAndMasterKeyTamperingFailWithoutLeakingSecretDetails() throws Exception {
        try (var alice = new Browser(); var bob = new Browser()) {
            var first = fixture(alice);
            var other = fixture(bob);
            var keys = api.getBean(SigningKeyService.class);
            byte[] secret = keys.getOrCreate(first.workspace(), first.endpoint()).bytes();
            keys.getOrCreate(other.workspace(), other.endpoint());
            byte[] original = stored(first);
            byte[] otherOriginal = stored(other);
            try {
                jdbc.update("UPDATE endpoint_signing_keys SET encrypted_key = ? WHERE workspace_id = ? AND endpoint_id = ?",
                        original, other.workspace(), other.endpoint());
                assertThatThrownBy(() -> keys.getOrCreate(other.workspace(), other.endpoint()))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Endpoint signing key is unavailable").hasNoCause();
                byte[] modified = original.clone();
                modified[modified.length - 1] ^= 1;
                jdbc.update("UPDATE endpoint_signing_keys SET encrypted_key = ? WHERE workspace_id = ? AND endpoint_id = ?",
                        modified, first.workspace(), first.endpoint());
                assertThatThrownBy(() -> keys.getOrCreate(first.workspace(), first.endpoint()))
                        .hasMessage("Endpoint signing key is unavailable").hasNoCause();
            } finally {
                jdbc.update("UPDATE endpoint_signing_keys SET encrypted_key = ? WHERE workspace_id = ? AND endpoint_id = ?",
                        original, first.workspace(), first.endpoint());
                jdbc.update("UPDATE endpoint_signing_keys SET encrypted_key = ? WHERE workspace_id = ? AND endpoint_id = ?",
                        otherOriginal, other.workspace(), other.endpoint());
            }
            byte[] wrong = new byte[32];
            new java.security.SecureRandom().nextBytes(wrong);
            var wrongMaster = new SigningKeyService(jdbc, new MockEnvironment().withProperty("relay.signing.master-key",
                    Base64.getEncoder().encodeToString(wrong)));
            assertThatThrownBy(() -> wrongMaster.getOrCreate(first.workspace(), first.endpoint()))
                    .hasMessage("Endpoint signing key is unavailable").hasNoCause();
            assertThat(keys.getOrCreate(first.workspace(), first.endpoint()).bytes()).isEqualTo(secret);
            assertThatThrownBy(() -> keys.getOrCreate(other.workspace(), first.endpoint()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
        }
    }

    @Test
    void onlyWorkspaceOwnerWithCsrfCanRevealTheStableNoStoreSecret(CapturedOutput output) throws Exception {
        try (var alice = new Browser(); var bob = new Browser(); var anonymous = new Browser()) {
            var owner = fixture(alice);
            var other = fixture(bob);
            String path = path(owner);
            assertThat(alice.json("POST", path, null, false).statusCode()).isEqualTo(403);
            var revealed = alice.json("POST", path, null);
            assertThat(revealed.statusCode()).isEqualTo(200);
            assertThat(revealed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            String secret = json(revealed).get("secret").asString();
            assertThat(Base64.getDecoder().decode(secret)).hasSize(32);
            assertThat(json(alice.json("POST", path, null)).get("secret").asString()).isEqualTo(secret);
            assertThat(bob.json("POST", path, null).statusCode()).isEqualTo(404);
            assertThat(alice.json("POST", "/api/workspaces/" + other.workspace() + "/endpoints/" + owner.endpoint()
                    + "/signing-secret", null).statusCode()).isEqualTo(404);
            assertThat(alice.json("POST", "/api/workspaces/" + owner.workspace() + "/endpoints/" + UUID.randomUUID()
                    + "/signing-secret", null).statusCode()).isEqualTo(404);
            var apiKey = alice.json("POST", "/api/workspaces/" + owner.workspace() + "/api-keys", Map.of("name", "No key access"));
            assertThat(apiKey.statusCode()).isEqualTo(201);
            anonymous.csrf();
            assertThat(request(anonymous.http, "POST", path, null, null, Map.of("Authorization", "Bearer "
                    + json(apiKey).get("key").asString(), "X-CSRF-TOKEN", anonymous.csrfToken)).statusCode()).isEqualTo(401);
            assertThat(output.getAll()).doesNotContain(secret,
                    api.getEnvironment().getRequiredProperty("relay.signing.master-key"));
        }
    }

    @Test
    void invalidMasterStopsApplicationStartupWithoutEchoingTheConfiguredValue(CapturedOutput output) {
        String invalid = "startup-private-master-" + UUID.randomUUID();
        for (String configured : List.of("", invalid)) {
            assertThatThrownBy(() -> application().profiles("worker").web(WebApplicationType.NONE)
                    .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("invalid-signing-master", Map.of("relay.signing.master-key", configured))))
                    .run("--spring.main.keep-alive=false", "--relay.worker.enabled=false"))
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("Configure a valid relay signing master key");
        }
        assertThat(output.getAll()).doesNotContain(invalid)
                .contains("Configure a valid relay signing master key");
    }

    private Fixture fixture(Browser browser) throws Exception {
        browser.csrf();
        String email = "signing-" + UUID.randomUUID() + "@example.test";
        String password = "signing fixture password 42!";
        var registered = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", password));
        assertThat(registered.statusCode()).isEqualTo(201);
        accounts.add(UUID.fromString(json(registered).get("id").asString()));
        assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
        browser.csrf();
        var workspace = browser.json("POST", "/api/workspaces", Map.of("name", "Signing fixture"));
        assertThat(workspace.statusCode()).isEqualTo(201);
        UUID workspaceId = UUID.fromString(json(workspace).get("id").asString());
        var endpoint = browser.json("POST", "/api/workspaces/" + workspaceId + "/endpoints",
                Map.of("url", "https://receiver.example.test/signed"));
        assertThat(endpoint.statusCode()).isEqualTo(201);
        return new Fixture(workspaceId, UUID.fromString(json(endpoint).get("id").asString()));
    }

    private byte[] stored(Fixture fixture) {
        return jdbc.queryForObject("SELECT encrypted_key FROM endpoint_signing_keys WHERE workspace_id = ? AND endpoint_id = ?",
                byte[].class, fixture.workspace(), fixture.endpoint());
    }

    private static String path(Fixture fixture) {
        return "/api/workspaces/" + fixture.workspace() + "/endpoints/" + fixture.endpoint() + "/signing-secret";
    }

    private record Fixture(UUID workspace, UUID endpoint) { }
}
