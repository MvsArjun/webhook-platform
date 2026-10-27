package dev.relay;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** Real servlet filtering and Tomcat form parsing, including unknown-length request bodies. */
@ExtendWith(OutputCaptureExtension.class)
class RequestBodyLimitsIT extends HttpIntegrationTestSupport {
    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeAccounts() {
        for (UUID id : accounts) jdbc.update("DELETE FROM accounts WHERE id = ?", id);
    }

    @Test
    void rejectsKnownAndChunkedOversizedJsonBeforeCsrfOrParsingWithoutEchoingValues(CapturedOutput output)
            throws Exception {
        String marker = "oversized-json-private-" + UUID.randomUUID();
        String invalidJson = "not-json-" + marker + "x".repeat(20000);
        try (var http = statelessClient()) {
            for (String type : List.of("Application/JSON; charset=UTF-8", "application/vnd.relay+json")) {
                for (boolean chunked : new boolean[]{false, true}) {
                    var response = post(http, "/api/auth/register", invalidJson, type, Map.of(), chunked);
                    assertThat(response.statusCode()).isEqualTo(413);
                    assertThat(json(response).get("error").asString()).isEqualTo("request_too_large");
                    assertThat(response.body()).doesNotContain(marker, "not-json");
                }
            }
        }
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void cachedChunkedJsonPreservesUtf8CredentialsAndStillRequiresCsrf(CapturedOutput output) throws Exception {
        String email = email();
        String password = "  preserve é spaces 42!  ";
        try (var browser = new Browser()) {
            browser.csrf();
            String json = JSON.writeValueAsString(Map.of("email", email, "password", password));
            String body = json + " ".repeat(16384 - json.getBytes(StandardCharsets.UTF_8).length);
            var noCsrf = post(browser.http, "/api/auth/register", body, "application/json; charset=utf-8", Map.of(), true);
            assertThat(noCsrf.statusCode()).isEqualTo(403);
            var registration = post(browser.http, "/api/auth/register", body, "application/json; charset=utf-8",
                    Map.of("X-CSRF-TOKEN", browser.csrfToken), true);
            assertThat(registration.statusCode()).isEqualTo(201);
            UUID accountId = UUID.fromString(json(registration).get("id").asString());
            accounts.add(accountId);
            assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
            assertThat(json(browser.get("/api/auth/me")).get("id").asString()).isEqualTo(accountId.toString());
        }
        assertThat(output.getAll()).doesNotContain(password);
    }

    @Test
    void oversizedKnownAndChunkedFormsCannotAuthenticateButNormalFormLoginStillWorks(CapturedOutput output)
            throws Exception {
        String email = email();
        String password = "form limit valid password 42!";
        String marker = "oversized-form-private-" + UUID.randomUUID();
        try (var browser = new Browser()) {
            browser.csrf();
            var registration = browser.json("POST", "/api/auth/register", Map.of("email", email, "password", password));
            assertThat(registration.statusCode()).isEqualTo(201);
            accounts.add(UUID.fromString(json(registration).get("id").asString()));
            String form = "email=" + encode(email) + "&password=" + encode(password) + "&padding=" + marker + "p".repeat(20000);
            for (boolean chunked : new boolean[]{false, true}) {
                browser.csrf();
                var response = post(browser.http, "/api/auth/login", form, "application/x-www-form-urlencoded",
                        Map.of("X-CSRF-TOKEN", browser.csrfToken), chunked);
                if (chunked) assertThat(response.statusCode()).isIn(400, 401, 403, 413);
                else assertThat(response.statusCode()).isEqualTo(413);
                assertThat(response.body()).doesNotContain(marker, password);
                assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
            }
            browser.csrf();
            assertThat(browser.login(email, password, true).statusCode()).isEqualTo(204);
            assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(200);
        }
        assertThat(output.getAll()).doesNotContain(marker, password);
    }

    private static HttpResponse<String> post(HttpClient http, String path, String body, String type,
                                              Map<String, String> headers, boolean chunked) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : HttpRequest.BodyPublishers.ofByteArray(bytes);
        assertThat(publisher.contentLength()).isEqualTo(chunked ? -1 : bytes.length);
        var request = HttpRequest.newBuilder(baseUri.resolve(path)).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10)).header("Content-Type", type).POST(publisher);
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String email() { return "body-limit-" + UUID.randomUUID() + "@example.test"; }
}
