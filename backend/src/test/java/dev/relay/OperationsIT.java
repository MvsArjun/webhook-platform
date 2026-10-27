package dev.relay;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual HTTP and PostgreSQL, with independent operator, account-session and customer-key scopes. */
@ExtendWith(OutputCaptureExtension.class)
class OperationsIT extends HttpIntegrationTestSupport {
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
    void defaultDisabledExportRejectsAllCredentialsWhileExactPublicProbesRevealOnlyStatus() throws Exception {
        try (var http = statelessClient()) {
            assertProbe(send(http, baseUri, "GET", "/actuator/health/liveness", null, Map.of()), 200, "UP");
            assertProbe(send(http, baseUri, "GET", "/actuator/health/readiness", null, Map.of()), 200, "UP");
            for (var headers : List.of(Map.<String, String>of(), bearer(operatorToken()))) {
                var response = send(http, baseUri, "GET", "/actuator/prometheus", null, headers);
                assertUnauthorized(response);
            }
            assertUnauthorized(send(http, baseUri, "GET", "/actuator/health", null, Map.of()));
            assertThat(send(http, baseUri, "HEAD", "/actuator/health/liveness", null, Map.of()).statusCode()).isEqualTo(401);
        }
        try (var customer = customer(baseUri)) {
            assertUnauthorized(send(customer.http(), baseUri, "GET", "/actuator/prometheus", null, Map.of()));
            assertUnauthorized(send(customer.http(), baseUri, "GET", "/actuator/prometheus", null, bearer(customer.key())));
            assertThat(send(customer.http(), baseUri, "GET", "/api/auth/me", null, Map.of()).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void enabledScrapeAcceptsOnlyIndependentOperatorBearerAndNeverGrantsCustomerAccess(CapturedOutput output)
            throws Exception {
        String token = operatorToken();
        try (var enabled = operationsApi(token, "--management.endpoints.web.exposure.include=health,prometheus,env,loggers");
             var http = statelessClient(); var customer = customer(enabled.origin())) {
            var scrape = send(http, enabled.origin(), "GET", "/actuator/prometheus", null, bearer(token));
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains("# HELP");
            assertThat(scrape.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(scrape.body()).doesNotContain(token, customer.key(), customer.email(), customer.password(),
                    customer.workspaceId(), SIGNING_MASTER_KEY, api.getEnvironment().getRequiredProperty("spring.datasource.url"));

            for (var headers : List.of(Map.<String, String>of(), bearer(operatorToken()), bearer(customer.key()),
                    Map.of("Authorization", "Basic " + token), Map.of("Authorization", "Bearer " + "x".repeat(4096)))) {
                assertUnauthorized(send(http, enabled.origin(), "GET", "/actuator/prometheus", null, headers));
            }
            assertUnauthorized(send(customer.http(), enabled.origin(), "GET", "/actuator/prometheus", null, Map.of()));
            var duplicate = HttpRequest.newBuilder(enabled.origin().resolve("/actuator/prometheus"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                    .header("Authorization", "Bearer " + token).GET().build();
            assertUnauthorized(http.send(duplicate, HttpResponse.BodyHandlers.ofString()));

            // Even deliberately exposing a sensitive endpoint cannot expand this operator's role.
            for (String path : List.of("/actuator", "/actuator/env", "/actuator/loggers", "/actuator/health")) {
                var denied = send(http, enabled.origin(), "GET", path, null, bearer(token));
                assertThat(denied.statusCode()).as("non-scrape management operations are denied").isEqualTo(403);
                assertThat(denied.body()).isEmpty();
            }
            assertThat(send(http, enabled.origin(), "POST", "/actuator/prometheus", null, bearer(token)).statusCode()).isEqualTo(403);
            assertThat(send(http, enabled.origin(), "HEAD", "/actuator/prometheus", null, bearer(token)).statusCode()).isEqualTo(403);
            for (String path : List.of("/api/workspaces", "/api/auth/me", "/api/v1/workspace")) {
                assertThat(send(http, enabled.origin(), "GET", path, null, bearer(token)).statusCode())
                        .as("an operator bearer does not authenticate customer routes").isEqualTo(401);
            }
            assertThat(send(customer.http(), enabled.origin(), "GET", "/api/auth/me", null, Map.of()).statusCode()).isEqualTo(200);
            assertProbe(send(http, enabled.origin(), "GET", "/actuator/health/readiness", null, Map.of()), 200, "UP");
            assertThat(output.getAll()).doesNotContain(token, customer.key(), customer.email(), customer.password(), SIGNING_MASTER_KEY);
        }
    }

    @Test
    void readinessDetectsOnlyItsOwnClosedDatabasePoolWhileLivenessAndTheSharedTestDatabaseRemainUp() throws Exception {
        try (var isolated = operationsApi(operatorToken()); var http = statelessClient()) {
            assertProbe(send(http, isolated.origin(), "GET", "/actuator/health/readiness", null, Map.of()), 200, "UP");
            ((HikariDataSource) isolated.context().getBean(DataSource.class)).close();
            assertProbe(send(http, isolated.origin(), "GET", "/actuator/health/readiness", null, Map.of()), 503, "DOWN");
            assertProbe(send(http, isolated.origin(), "GET", "/actuator/health/liveness", null, Map.of()), 200, "UP");
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            assertProbe(send(http, baseUri, "GET", "/actuator/health/readiness", null, Map.of()), 200, "UP");
        }
    }

    @Test
    void endpointMatchersKeepTheirScopesWhenTheManagementBasePathAndEndpointMappingsChange() throws Exception {
        String token = operatorToken();
        try (var remapped = operationsApi(token, "--management.endpoints.web.base-path=/internal-observation",
                "--management.endpoints.web.path-mapping.health=probe", "--management.endpoints.web.path-mapping.prometheus=scrape");
             var http = statelessClient()) {
            assertProbe(send(http, remapped.origin(), "GET", "/internal-observation/probe/readiness", null, Map.of()), 200, "UP");
            assertProbe(send(http, remapped.origin(), "GET", "/internal-observation/probe/liveness", null, Map.of()), 200, "UP");
            assertUnauthorized(send(http, remapped.origin(), "GET", "/internal-observation/scrape", null, Map.of()));
            assertThat(send(http, remapped.origin(), "GET", "/internal-observation/scrape", null, bearer(token)).statusCode()).isEqualTo(200);
            assertThat(send(http, remapped.origin(), "GET", "/internal-observation/probe", null, bearer(token)).statusCode()).isEqualTo(403);
        }
    }

    @Test
    void invalidOperatorConfigurationStopsStartupWithOnlyAFixedSafeDiagnostic(CapturedOutput output) {
        String privateValue = "private-operator-startup-" + UUID.randomUUID();
        for (String token : List.of("", privateValue, "${" + privateValue + "}")) {
            assertThatThrownBy(() -> operationsApi(token)).isInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("Invalid operations configuration");
        }
        assertThat(output.getAll()).doesNotContain(privateValue)
                .contains("operations_configuration_invalid", "Invalid operations configuration");
    }

    private RunningApi operationsApi(String token, String... extraArguments) {
        var arguments = new ArrayList<>(List.of("--server.port=0"));
        arguments.addAll(List.of(extraArguments));
        var context = application().web(WebApplicationType.SERVLET).initializers(application ->
                application.getEnvironment().getPropertySources().addFirst(new MapPropertySource("operations-test",
                        Map.of("relay.operations.enabled", "true", "relay.operations.token", token))))
                .run(arguments.toArray(String[]::new));
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return new RunningApi(context, URI.create("http://127.0.0.1:" + port));
    }

    private Customer customer(URI origin) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
        String email = "operations-customer-" + UUID.randomUUID() + "@example.test";
        String password = "private-operations-customer-" + UUID.randomUUID();
        try {
            String csrf = json(send(http, origin, "GET", "/api/auth/csrf", null, Map.of())).get("token").asString();
            var registered = send(http, origin, "POST", "/api/auth/register", JSON.writeValueAsString(Map.of("email", email,
                    "password", password)), Map.of("Content-Type", "application/json", "X-CSRF-TOKEN", csrf));
            assertThat(registered.statusCode()).isEqualTo(201);
            accounts.add(UUID.fromString(json(registered).get("id").asString()));
            assertThat(send(http, origin, "POST", "/api/auth/login", "email=" + encode(email) + "&password=" + encode(password),
                    Map.of("Content-Type", "application/x-www-form-urlencoded", "X-CSRF-TOKEN", csrf)).statusCode()).isEqualTo(204);
            csrf = json(send(http, origin, "GET", "/api/auth/csrf", null, Map.of())).get("token").asString();
            var workspace = send(http, origin, "POST", "/api/workspaces", "{\"name\":\"Operations customer\"}",
                    Map.of("Content-Type", "application/json", "X-CSRF-TOKEN", csrf));
            assertThat(workspace.statusCode()).isEqualTo(201);
            String workspaceId = json(workspace).get("id").asString();
            var key = send(http, origin, "POST", "/api/workspaces/" + workspaceId + "/api-keys", "{\"name\":\"Customer key\"}",
                    Map.of("Content-Type", "application/json", "X-CSRF-TOKEN", csrf));
            assertThat(key.statusCode()).isEqualTo(201);
            return new Customer(http, json(key).get("key").asString(), workspaceId, email, password);
        } catch (Exception | AssertionError failure) { http.close(); throw failure; }
    }

    private static HttpResponse<String> send(HttpClient http, URI origin, String method, String path, String body,
                                             Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10)).header("Accept", "*/*");
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProbe(HttpResponse<String> response, int code, String status) {
        assertThat(response.statusCode()).isEqualTo(code);
        var body = json(response);
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get("status").asString()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("components", "details", "jdbc", "exception", "password");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    private static void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.body()).isEmpty();
    }

    private static Map<String, String> bearer(String token) { return Map.of("Authorization", "Bearer " + token); }

    private static String operatorToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record RunningApi(ConfigurableApplicationContext context, URI origin) implements AutoCloseable {
        @Override public void close() { context.close(); }
    }

    private record Customer(HttpClient http, String key, String workspaceId, String email, String password) implements AutoCloseable {
        @Override public void close() { http.close(); }
        @Override public String toString() { return "Customer[REDACTED]"; }
    }
}
