package dev.relay;

import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Upgrade real pre-policy history, rather than testing V6 only on empty tables. */
class RetryMigrationIT extends HttpIntegrationTestSupport {
    @Test
    void upgradePreservesExistingHistoryAndAccountsForAlreadyUsedAttempts() throws Exception {
        String schema = "retry_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = api.getEnvironment().getRequiredProperty("spring.datasource.url");
        String username = api.getEnvironment().getRequiredProperty("spring.datasource.username");
        String password = api.getEnvironment().getRequiredProperty("spring.datasource.password");
        try {
            Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .target("5").cleanDisabled(true).load().migrate();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                connection.setSchema(schema);
                var old = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                UUID account = UUID.randomUUID(), workspace = UUID.randomUUID(), endpoint = UUID.randomUUID();
                old.update("INSERT INTO accounts (id,email,password_hash) VALUES (?, ?, 'test-only-noncredential')",
                        account, account + "@example.test");
                old.update("INSERT INTO workspaces (id,owner_account_id,name) VALUES (?, ?, 'Upgrade fixture')", workspace, account);
                old.update("INSERT INTO endpoints (id,workspace_id,url) VALUES (?, ?, 'https://receiver.example/hook')", endpoint, workspace);
                UUID fresh = seed(old, workspace, endpoint, 0, false);
                UUID active = seed(old, workspace, endpoint, 2, true);
                UUID exhausted = seed(old, workspace, endpoint, 5, false);
                Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                        .cleanDisabled(true).load().migrate();
                assertThat(old.queryForObject("SELECT status FROM deliveries WHERE id=?", String.class, fresh)).isEqualTo("PENDING");
                assertThat(old.queryForObject("SELECT status FROM deliveries WHERE id=?", String.class, active)).isEqualTo("IN_FLIGHT");
                assertThat(old.queryForObject("SELECT run_attempts FROM deliveries WHERE id=?", Integer.class, active)).isEqualTo(2);
                assertThat(old.queryForObject("SELECT status FROM deliveries WHERE id=?", String.class, exhausted)).isEqualTo("FAILED");
                assertThat(old.queryForObject("SELECT max_attempts FROM deliveries WHERE id=?", Integer.class, exhausted)).isEqualTo(5);
                assertThat(old.queryForObject("SELECT count(*) FROM delivery_attempts", Integer.class)).isEqualTo(7);
                assertThat(old.queryForObject("SELECT count(*) FROM delivery_attempts WHERE replay_generation=0 AND run_attempt=claim_generation",
                        Integer.class)).isEqualTo(7);
                assertThat(old.queryForObject("SELECT count(*) FROM delivery_attempts WHERE outcome='IN_PROGRESS'", Integer.class)).isEqualTo(1);
            }
        } finally {
            // The name is generated above from a UUID, never supplied by a caller.
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    private UUID seed(JdbcTemplate old, UUID workspace, UUID endpoint, int generation, boolean active) {
        UUID event = UUID.randomUUID(), delivery = UUID.randomUUID(), token = UUID.randomUUID();
        old.update("INSERT INTO events (id,workspace_id,endpoint_id,payload,request_digest) VALUES (?, ?, ?, ?, ?)",
                event, workspace, endpoint, new byte[]{123, 125}, new byte[32]);
        old.update("INSERT INTO deliveries (id,workspace_id,event_id,status,claim_generation,claim_token,lease_expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN clock_timestamp() + interval '2 minutes' ELSE NULL END)",
                delivery, workspace, event, active ? "IN_FLIGHT" : "PENDING", generation, active ? token : null, active);
        for (int attempt = 1; attempt <= generation; attempt++) {
            boolean inProgress = active && attempt == generation;
            old.update("INSERT INTO delivery_attempts (id,workspace_id,delivery_id,claim_generation,claim_token,outcome,finished_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN NULL ELSE clock_timestamp() END)",
                    UUID.randomUUID(), workspace, delivery, attempt, inProgress ? token : UUID.randomUUID(),
                    inProgress ? "IN_PROGRESS" : "UNKNOWN", inProgress);
        }
        return delivery;
    }
}
