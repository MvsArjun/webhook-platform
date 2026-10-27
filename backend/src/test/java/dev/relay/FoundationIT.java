package dev.relay;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses an isolated Testcontainer by default, or a dedicated PostgreSQL DB supplied via TEST_DB_*.
 * No disabledIfDockerUnavailable: missing prerequisites fail verification instead of silently skipping.
 */
class FoundationIT extends HttpIntegrationTestSupport {
    @Test
    void appliesAndValidatesMigrationAndServesStatusOverHttp() throws Exception {
        var flyway = api.getBean(Flyway.class);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("7");
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (var http = statelessClient()) {
            var response = request(http, "GET", "/api/system/status", null, null, Map.of());
            assertThat(response.statusCode()).isEqualTo(200);
            // Compare parsed JSON so ordering/whitespace do not become part of the API contract.
            var json = json(response);
            assertThat(json.get("service").asString()).isEqualTo("relay");
            assertThat(json.get("role").asString()).isEqualTo("api");
            assertThat(json.get("phase").asInt()).isEqualTo(7);
            assertThat(json.get("database").asString()).isEqualTo("connected");
        }
    }

    @Test
    void databaseEnforcesWorkspaceOwnerAndNormalizedUniqueEmail() {
        var jdbc = api.getBean(JdbcTemplate.class);
        UUID accountId = UUID.randomUUID();
        String email = accountId + "@example.test";
        try {
            jdbc.update("INSERT INTO accounts (id, email, password_hash) VALUES (?, ?, ?)",
                    accountId, email, "test-only-noncredential");
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO accounts (id, email, password_hash) VALUES (?, ?, ?)",
                    UUID.randomUUID(), email, "test-only-noncredential"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO accounts (id, email, password_hash) VALUES (?, ?, ?)",
                    UUID.randomUUID(), "UPPER@example.test", "test-only-noncredential"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO workspaces (id, owner_account_id, name) VALUES (?, ?, ?)",
                    UUID.randomUUID(), UUID.randomUUID(), "Missing owner"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("INSERT INTO workspaces (id, owner_account_id, name) VALUES (?, ?, ?)",
                    UUID.randomUUID(), accountId, "Foundation test");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE owner_account_id = ?",
                    Integer.class, accountId)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
    }

    @Test
    void workerStartsAgainstSameDatabaseWithoutHttpServer() {
        try (var worker = application().profiles("worker").web(WebApplicationType.NONE)
                .run("--spring.main.keep-alive=false")) {
            assertThat(worker).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(worker.getBeansOfType(dev.relay.system.SystemStatusController.class)).isEmpty();
            assertThat(worker.getBean(JdbcTemplate.class).queryForObject("SELECT 1", Integer.class))
                    .isEqualTo(1);
        }
    }

}
