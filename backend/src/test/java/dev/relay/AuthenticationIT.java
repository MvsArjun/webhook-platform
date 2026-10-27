package dev.relay;

import java.net.HttpCookie;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Authentication, browser sessions and bearer keys are exercised through actual network requests. */
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationIT extends HttpIntegrationTestSupport {
    private static final String PASSWORD = "correct password 42!";
    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeTestAccounts() {
        for (UUID accountId : accounts) {
            jdbc.update("DELETE FROM api_keys WHERE workspace_id IN "
                    + "(SELECT id FROM workspaces WHERE owner_account_id = ?)", accountId);
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndDoesNotLogIn(CapturedOutput output) throws Exception {
        try (var browser = new Browser()) {
            String email = newEmail();
            String password = "  keep these spaces 42!  ";
            var account = register(browser, "  " + email.toUpperCase(Locale.ROOT) + "  ", password);
            assertThat(account.email()).isEqualTo(email);
            assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);

            String stored = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?",
                    String.class, account.id());
            assertThat(stored).startsWith("{bcrypt}$2").hasSize(68).isNotEqualTo(password);
            var encoder = api.getBean(PasswordEncoder.class);
            assertThat(encoder.matches(password, stored)).isTrue();
            assertThat(encoder.matches(password.strip(), stored)).isFalse();
            assertThat(browser.login(email, password.strip(), true).statusCode()).isEqualTo(401);
            assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
            assertThat(output.getAll()).doesNotContain(password, stored);
        }
    }

    @Test
    void registrationRejectsInvalidAndDuplicateInputsAndEnforcesBcryptByteLimit() throws Exception {
        try (var browser = new Browser()) {
            browser.csrf();
            for (String email : List.of("not-an-email", "", "@example.test", "bad@")) {
                assertThat(registerResponse(browser, email, PASSWORD).statusCode()).isEqualTo(400);
            }
            assertThat(registerResponse(browser, newEmail(), "shortPass11").statusCode()).isEqualTo(400);
            assertThat(registerResponse(browser, newEmail(), "a".repeat(73)).statusCode()).isEqualTo(400);
            assertThat(registerResponse(browser, newEmail(), "é".repeat(37)).statusCode()).isEqualTo(400);
            assertThat(browser.json("POST", "/api/auth/register", Map.of()).statusCode()).isEqualTo(400);

            var original = register(browser, newEmail(), PASSWORD);
            assertThat(registerResponse(browser, " " + original.email().toUpperCase(Locale.ROOT) + " ",
                    "different valid password 42!").statusCode()).isEqualTo(409);

            // BCrypt truncates input at 72 bytes; rejecting a longer login prevents suffix aliases.
            String maximumPassword = "0123456789".repeat(7) + "XY";
            var maximum = register(browser, newEmail(), maximumPassword);
            assertThat(browser.login(maximum.email(), maximumPassword + "suffix", true).statusCode())
                    .isEqualTo(401);
            assertThat(browser.login(maximum.email(), maximumPassword, true).statusCode()).isEqualTo(204);

            browser.csrf();
            assertThat(registerResponse(browser, newEmail(), "validPass12!").statusCode()).isEqualTo(201);
            assertThat(registerResponse(browser, newEmail(), "é".repeat(36)).statusCode()).isEqualTo(201);
        }
    }

    @Test
    void loginUsesGenericFailuresRotatesSessionAndLogoutInvalidatesReplayedCookies(CapturedOutput output)
            throws Exception {
        try (var browser = new Browser(); var replay = statelessClient()) {
            var csrf = browser.csrf();
            assertThat(csrf.headers().allValues("Set-Cookie")).anySatisfy(cookie -> {
                assertThat(cookie.toLowerCase(Locale.ROOT)).contains("relay_session=", "httponly", "samesite=lax");
            });
            String anonymousSession = browser.sessionId();
            assertThat(anonymousSession).isNotBlank();
            var account = register(browser, newEmail(), PASSWORD);

            var wrongPassword = browser.login(account.email(), "incorrect password", true);
            var missingAccount = browser.login(newEmail(), "incorrect password", true);
            assertThat(wrongPassword.statusCode()).isEqualTo(401);
            assertThat(missingAccount.statusCode()).isEqualTo(401);
            assertThat(missingAccount.body()).isEqualTo(wrongPassword.body());

            String previousCsrf = browser.csrfToken;
            assertThat(browser.login(" " + account.email().toUpperCase(Locale.ROOT) + " ", PASSWORD, true)
                    .statusCode()).isEqualTo(204);
            String authenticatedSession = browser.sessionId();
            assertThat(authenticatedSession).isNotBlank().isNotEqualTo(anonymousSession);
            var me = browser.get("/api/auth/me");
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json(me).get("id").asString()).isEqualTo(account.id().toString());
            assertThat(json(me).get("email").asString()).isEqualTo(account.email());
            assertThat(request(replay, "GET", "/api/auth/me", null, null,
                    Map.of("Cookie", "RELAY_SESSION=" + anonymousSession)).statusCode()).isEqualTo(401);

            assertThat(request(browser.http, "POST", "/api/workspaces", "{\"name\":\"Stale token\"}",
                    "application/json", Map.of("X-CSRF-TOKEN", previousCsrf)).statusCode()).isEqualTo(403);
            browser.csrf();
            var logout = browser.json("POST", "/api/auth/logout", null);
            assertThat(logout.statusCode()).isEqualTo(204);
            assertThat(logout.headers().allValues("Set-Cookie").stream()
                    .flatMap(header -> HttpCookie.parse(header).stream())
                    .filter(cookie -> cookie.getName().equals("RELAY_SESSION")))
                    .anySatisfy(cookie -> {
                        assertThat(cookie.getValue()).isEmpty();
                        assertThat(cookie.hasExpired()).isTrue();
                    });
            assertThat(browser.sessionId()).isNull();
            assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(request(replay, "GET", "/api/auth/me", null, null,
                    Map.of("Cookie", "RELAY_SESSION=" + authenticatedSession)).statusCode()).isEqualTo(401);
            assertThat(output.getAll()).doesNotContain(PASSWORD, authenticatedSession, previousCsrf);
        }
    }

    @Test
    void allBrowserWritesRequireCsrfIncludingAnonymousAuthenticationAndLogout() throws Exception {
        try (var browser = new Browser()) {
            assertThat(browser.json("POST", "/api/auth/register", Map.of("email", newEmail(),
                    "password", PASSWORD), false).statusCode()).isEqualTo(403);
            assertThat(browser.login(newEmail(), PASSWORD, false).statusCode()).isEqualTo(403);
            signIn(browser);

            assertThat(browser.json("POST", "/api/workspaces", Map.of("name", "Blocked"), false)
                    .statusCode()).isEqualTo(403);
            var workspace = createWorkspace(browser, "CSRF owner");
            String path = "/api/workspaces/" + workspace.get("id").asString();
            assertThat(browser.json("PATCH", path, Map.of("name", "Blocked"), false).statusCode())
                    .isEqualTo(403);
            assertThat(browser.json("POST", path + "/api-keys", Map.of("name", "Blocked"), false)
                    .statusCode()).isEqualTo(403);
            var key = createKey(browser, workspace.get("id").asString(), "CSRF key");
            assertThat(browser.json("DELETE", path + "/api-keys/" + key.get("id").asString(), null, false)
                    .statusCode()).isEqualTo(403);
            assertThat(browser.json("POST", "/api/auth/logout", null, false).statusCode()).isEqualTo(403);
            assertThat(request(browser.http, "POST", "/api/workspaces", "{\"name\":\"Blocked\"}",
                    "application/json", Map.of("X-CSRF-TOKEN", "wrong-token")).statusCode()).isEqualTo(403);
            assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(200);
            assertThat(json(browser.get(path)).get("name").asString()).isEqualTo("CSRF owner");
        }
    }

    @Test
    void workspaceReadsChangesAndListsAreIsolatedByOwner() throws Exception {
        try (var alice = new Browser(); var bob = new Browser()) {
            signIn(alice);
            signIn(bob);
            var first = createWorkspace(alice, "Alice first");
            var second = createWorkspace(alice, "Alice second");
            var other = createWorkspace(bob, "Bob only");
            String path = "/api/workspaces/" + first.get("id").asString();

            assertThat(ids(json(alice.get("/api/workspaces"))))
                    .containsExactlyInAnyOrder(first.get("id").asString(), second.get("id").asString());
            assertThat(ids(json(bob.get("/api/workspaces"))))
                    .containsExactly(other.get("id").asString());
            assertThat(bob.get(path).statusCode()).isEqualTo(404);
            assertThat(bob.json("PATCH", path, Map.of("name", "Stolen")).statusCode()).isEqualTo(404);

            var update = alice.json("PATCH", path, Map.of("name", "  Alice renamed  "));
            assertThat(update.statusCode()).isEqualTo(200);
            assertThat(json(update).get("name").asString()).isEqualTo("Alice renamed");
            assertThat(json(alice.get(path)).get("name").asString()).isEqualTo("Alice renamed");
            assertThat(alice.get("/api/workspaces/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void keysRevealOnceStoreSha256AndAuthenticateOnlyStatelessWorkspaceApi(CapturedOutput output)
            throws Exception {
        try (var browser = new Browser(); var bearer = statelessClient()) {
            signIn(browser);
            var workspace = createWorkspace(browser, "API owner");
            String workspaceId = workspace.get("id").asString();
            var response = browser.json("POST", "/api/workspaces/" + workspaceId + "/api-keys",
                    Map.of("name", "Integration client"));
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            var key = json(response);
            String raw = key.get("key").asString();
            String keyId = key.get("id").asString();
            assertThat(raw).matches("rly_[0-9a-f]{32}\\.[A-Za-z0-9_-]{43}");
            assertThat(key.get("prefix").asString()).isEqualTo(raw.substring(0, 36));
            assertThat(key.get("createdAt").asString()).isNotBlank();
            assertThat(key.get("revokedAt").isNull()).isTrue();
            assertThat(key.has("keyHash")).isFalse();
            byte[] stored = jdbc.queryForObject("SELECT key_hash FROM api_keys WHERE id = ?",
                    byte[].class, UUID.fromString(keyId));
            assertThat(stored).hasSize(32).isEqualTo(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
            assertThat(jdbc.queryForObject("SELECT row_to_json(api_keys)::text FROM api_keys WHERE id = ?",
                    String.class, UUID.fromString(keyId))).doesNotContain(raw);

            var metadata = browser.get("/api/workspaces/" + workspaceId + "/api-keys");
            assertThat(metadata.statusCode()).isEqualTo(200);
            var listedKey = json(metadata).get(0);
            assertThat(listedKey.get("id").asString()).isEqualTo(keyId);
            assertThat(listedKey.has("key")).isFalse();
            assertThat(listedKey.has("keyHash")).isFalse();
            assertThat(listedKey.has("key_hash")).isFalse();
            assertThat(metadata.body()).doesNotContain(raw);

            for (int repeat = 0; repeat < 2; repeat++) {
                var authenticated = bearer(bearer, raw);
                assertThat(authenticated.statusCode()).isEqualTo(200);
                assertThat(json(authenticated).get("id").asString()).isEqualTo(workspaceId);
                assertThat(authenticated.headers().allValues("Set-Cookie")).isEmpty();
            }
            assertThat(browser.get("/api/v1/workspace").statusCode()).isEqualTo(401);
            assertThat(request(browser.http, "GET", "/api/v1/workspace", null, null,
                    Map.of("Authorization", "Bearer invalid")).statusCode()).isEqualTo(401);
            assertThat(request(bearer, "GET", "/api/v1/workspace", null, null, Map.of()).statusCode())
                    .isEqualTo(401);
            assertThat(bearer(bearer, "invalid").statusCode()).isEqualTo(401);
            String changedSecret = raw.substring(0, raw.length() - 1)
                    + (raw.endsWith("A") ? "B" : "A");
            assertThat(bearer(bearer, changedSecret).statusCode()).isEqualTo(401);
            String changedId = "rly_" + UUID.randomUUID().toString().replace("-", "") + raw.substring(36);
            assertThat(bearer(bearer, changedId).statusCode()).isEqualTo(401);
            for (String path : List.of("/api/auth/me", "/api/workspaces",
                    "/api/workspaces/" + workspaceId,
                    "/api/workspaces/" + workspaceId + "/api-keys")) {
                var denied = request(bearer, "GET", path, null, null,
                        Map.of("Authorization", "Bearer " + raw));
                assertThat(denied.statusCode()).as("bearer cannot access %s", path).isEqualTo(401);
                assertThat(denied.headers().allValues("Set-Cookie")).isEmpty();
            }
            assertThat(output.getAll()).doesNotContain(raw, PASSWORD);
        }
    }

    @Test
    void keyManagementIsOwnerAndWorkspaceScopedAndRevocationInvalidatesOnlyThatKey() throws Exception {
        try (var alice = new Browser(); var bob = new Browser(); var bearer = statelessClient()) {
            signIn(alice);
            signIn(bob);
            String ownedId = createWorkspace(alice, "Alice API workspace").get("id").asString();
            String secondId = createWorkspace(alice, "Alice other workspace").get("id").asString();
            String otherId = createWorkspace(bob, "Bob workspace").get("id").asString();
            String keyPath = "/api/workspaces/" + ownedId + "/api-keys";
            var firstKey = createKey(alice, ownedId, "Revoke me");
            var survivor = createKey(alice, ownedId, "Keep active");
            String id = firstKey.get("id").asString();
            String raw = firstKey.get("key").asString();

            assertThat(bob.get(keyPath).statusCode()).isEqualTo(404);
            assertThat(bob.json("POST", keyPath, Map.of("name", "Stolen key")).statusCode()).isEqualTo(404);
            assertThat(bob.json("DELETE", keyPath + "/" + id, null).statusCode()).isEqualTo(404);
            assertThat(alice.json("DELETE", "/api/workspaces/" + secondId + "/api-keys/" + id, null)
                    .statusCode()).isEqualTo(404);
            assertThat(bob.json("DELETE", "/api/workspaces/" + otherId + "/api-keys/" + id, null)
                    .statusCode()).isEqualTo(404);
            assertThat(alice.json("DELETE", keyPath + "/" + UUID.randomUUID(), null).statusCode())
                    .isEqualTo(404);
            assertThat(bearer(bearer, raw).statusCode()).isEqualTo(200);

            assertThat(alice.json("DELETE", keyPath + "/" + id, null).statusCode()).isEqualTo(204);
            assertThat(alice.json("DELETE", keyPath + "/" + id, null).statusCode()).isEqualTo(204);
            assertThat(bearer(bearer, raw).statusCode()).isEqualTo(401);
            assertThat(bearer(bearer, survivor.get("key").asString()).statusCode()).isEqualTo(200);
            var listed = json(alice.get(keyPath));
            JsonNode revoked = null;
            for (int index = 0; index < listed.size(); index++) {
                if (listed.get(index).get("id").asString().equals(id)) revoked = listed.get(index);
            }
            assertThat(revoked).isNotNull();
            assertThat(revoked.get("revokedAt").isNull()).isFalse();
            assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM api_keys WHERE id = ?",
                    Boolean.class, UUID.fromString(id))).isTrue();
        }
    }

    @Test
    void workspaceAndKeyNamesAreValidatedAndAnonymousReadsAreDenied() throws Exception {
        try (var browser = new Browser(); var anonymous = statelessClient()) {
            assertThat(request(anonymous, "GET", "/api/auth/me", null, null, Map.of()).statusCode())
                    .isEqualTo(401);
            assertThat(request(anonymous, "GET", "/api/workspaces", null, null, Map.of()).statusCode())
                    .isEqualTo(401);
            signIn(browser);
            for (String name : List.of("", "   ", "n".repeat(101))) {
                assertThat(browser.json("POST", "/api/workspaces", Map.of("name", name)).statusCode())
                        .isEqualTo(400);
            }
            assertThat(browser.json("POST", "/api/workspaces", Map.of()).statusCode()).isEqualTo(400);
            var workspace = createWorkspace(browser, "  Name is trimmed  ");
            assertThat(workspace.get("name").asString()).isEqualTo("Name is trimmed");
            String path = "/api/workspaces/" + workspace.get("id").asString();
            for (String name : List.of("", "   ", "n".repeat(101))) {
                assertThat(browser.json("PATCH", path, Map.of("name", name)).statusCode()).isEqualTo(400);
                assertThat(browser.json("POST", path + "/api-keys", Map.of("name", name)).statusCode())
                        .isEqualTo(400);
            }
            assertThat(browser.json("POST", path + "/api-keys", Map.of()).statusCode()).isEqualTo(400);
            var key = createKey(browser, workspace.get("id").asString(), "  Trimmed client  ");
            assertThat(key.get("name").asString()).isEqualTo("Trimmed client");
        }
    }

    private Account register(Browser browser, String email, String password) throws Exception {
        if (browser.csrfToken == null) browser.csrf();
        var response = registerResponse(browser, email, password);
        assertThat(response.statusCode()).as("registration").isEqualTo(201);
        var body = json(response);
        return new Account(UUID.fromString(body.get("id").asString()), body.get("email").asString());
    }

    private HttpResponse<String> registerResponse(Browser browser, String email, String password)
            throws Exception {
        var response = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", password));
        if (response.statusCode() == 201) accounts.add(UUID.fromString(json(response).get("id").asString()));
        return response;
    }

    private Account signIn(Browser browser) throws Exception {
        var account = register(browser, newEmail(), PASSWORD);
        assertThat(browser.login(account.email(), PASSWORD, true).statusCode()).as("login").isEqualTo(204);
        browser.csrf();
        return account;
    }

    private JsonNode createWorkspace(Browser browser, String name) throws Exception {
        var response = browser.json("POST", "/api/workspaces", Map.of("name", name));
        assertThat(response.statusCode()).as("workspace creation").isEqualTo(201);
        return json(response);
    }

    private JsonNode createKey(Browser browser, String workspaceId, String name) throws Exception {
        var response = browser.json("POST", "/api/workspaces/" + workspaceId + "/api-keys", Map.of("name", name));
        assertThat(response.statusCode()).as("API key creation").isEqualTo(201);
        return json(response);
    }

    private static HttpResponse<String> bearer(java.net.http.HttpClient client, String key) throws Exception {
        return request(client, "GET", "/api/v1/workspace", null, null,
                Map.of("Authorization", "Bearer " + key));
    }

    private static List<String> ids(JsonNode array) {
        assertThat(array.isArray()).isTrue();
        var ids = new ArrayList<String>();
        for (int index = 0; index < array.size(); index++) ids.add(array.get(index).get("id").asString());
        return ids;
    }

    private static String newEmail() {
        return "it-" + UUID.randomUUID() + "@example.test";
    }

    private record Account(UUID id, String email) { }
}
