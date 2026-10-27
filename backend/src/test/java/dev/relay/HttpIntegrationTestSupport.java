package dev.relay;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and PostgreSQL: missing Docker fails unless a dedicated TEST_DB_* database is supplied. */
abstract class HttpIntegrationTestSupport {
    protected static ConfigurableApplicationContext api;
    protected static JdbcTemplate jdbc;
    protected static URI baseUri;
    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static final String SIGNING_MASTER_KEY = testMasterKey();
    private static PostgreSQLContainer postgres;
    private static String databaseUrl;
    private static String databaseUsername;
    private static String databasePassword;

    @BeforeAll
    static void startApi() {
        databaseUrl = System.getenv("TEST_DB_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            postgres = new PostgreSQLContainer("postgres:17.11-bookworm")
                    .withDatabaseName("relay_test");
            postgres.start();
            databaseUrl = postgres.getJdbcUrl();
            databaseUsername = postgres.getUsername();
            databasePassword = postgres.getPassword();
        } else {
            databaseUsername = requiredEnvironment("TEST_DB_USERNAME");
            databasePassword = requiredEnvironment("TEST_DB_PASSWORD");
        }
        api = application().web(WebApplicationType.SERVLET).run("--server.port=0");
        jdbc = api.getBean(JdbcTemplate.class);
        int port = ((WebServerApplicationContext) api).getWebServer().getPort();
        baseUri = URI.create("http://127.0.0.1:" + port);
    }

    @AfterAll
    static void stopApi() {
        if (api != null) api.close();
        if (postgres != null) postgres.stop();
        api = null;
        jdbc = null;
        postgres = null;
    }

    protected static SpringApplicationBuilder application() {
        // Added first: a developer's DB_* environment must never redirect integration tests.
        return new SpringApplicationBuilder(RelayApplication.class)
                .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("integration-database", Map.of(
                                "spring.datasource.url", databaseUrl,
                                "spring.datasource.username", databaseUsername,
                                "spring.datasource.password", databasePassword,
                                "server.servlet.session.cookie.secure", false,
                                "relay.signing.master-key", SIGNING_MASTER_KEY))));
    }

    private static String testMasterKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getEncoder().encodeToString(key);
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    protected static HttpClient statelessClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    protected static HttpResponse<String> request(HttpClient client, String method, String path,
                                                   String body, String contentType,
                                                   Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (contentType != null) request.header("Content-Type", contentType);
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static class Browser implements AutoCloseable {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        String csrfToken;

        HttpResponse<String> csrf() throws Exception {
            var response = get("/api/auth/csrf");
            assertThat(response.statusCode()).as("CSRF bootstrap").isEqualTo(200);
            var body = HttpIntegrationTestSupport.json(response);
            assertThat(body.get("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
            assertThat(body.get("parameterName").asString()).isEqualTo("_csrf");
            csrfToken = body.get("token").asString();
            assertThat(csrfToken).isNotBlank();
            return response;
        }

        HttpResponse<String> get(String path) throws Exception {
            return request(http, "GET", path, null, null, Map.of());
        }

        HttpResponse<String> json(String method, String path, Map<String, ?> body) throws Exception {
            return json(method, path, body, true);
        }

        HttpResponse<String> json(String method, String path, Map<String, ?> body, boolean includeCsrf)
                throws Exception {
            return request(http, method, path, body == null ? null : JSON.writeValueAsString(body),
                    body == null ? null : "application/json", csrfHeaders(includeCsrf));
        }

        HttpResponse<String> login(String email, String password, boolean includeCsrf) throws Exception {
            var response = request(http, "POST", "/api/auth/login",
                    "email=" + encode(email) + "&password=" + encode(password),
                    "application/x-www-form-urlencoded", csrfHeaders(includeCsrf));
            if (response.statusCode() == 204) csrfToken = null;
            return response;
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("RELAY_SESSION") && !cookie.hasExpired())
                    .map(java.net.HttpCookie::getValue).findFirst().orElse(null);
        }

        private Map<String, String> csrfHeaders(boolean includeCsrf) {
            return includeCsrf && csrfToken != null ? Map.of("X-CSRF-TOKEN", csrfToken) : Map.of();
        }

        @Override
        public void close() {
            http.close();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " for the dedicated test database");
        }
        return value;
    }
}
