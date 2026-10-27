package dev.relay.observability.metrics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Objects;

import dev.relay.delivery.DeliveryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One bounded aggregate statement in its own consistent, read-only database transaction. */
public class MetricsSnapshotRepository {
    private static final String AGGREGATES = """
            WITH delivery_counts AS (
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS delivery_pending,
                    count(*) FILTER (WHERE status = 'IN_FLIGHT') AS delivery_in_flight,
                    count(*) FILTER (WHERE status = 'RETRY_WAIT') AS delivery_retry_wait,
                    count(*) FILTER (WHERE status = 'SUCCEEDED') AS delivery_succeeded,
                    count(*) FILTER (WHERE status = 'FAILED') AS delivery_failed
                FROM deliveries
            ), attempt_counts AS (
                SELECT count(*) FILTER (WHERE outcome = 'IN_PROGRESS') AS attempt_in_progress,
                    count(*) FILTER (WHERE outcome = 'SUCCEEDED') AS attempt_succeeded,
                    count(*) FILTER (WHERE outcome = 'FAILED') AS attempt_failed,
                    count(*) FILTER (WHERE outcome = 'UNKNOWN') AS attempt_unknown,
                    count(*) FILTER (WHERE run_attempt > 1) AS automatic_retries,
                    count(*) FILTER (WHERE replay_generation > 0 AND run_attempt = 1) AS manual_replay_firsts,
                    count(*) FILTER (WHERE finished_at IS NOT NULL AND duration_ms IS NOT NULL) AS latency_samples,
                    avg(duration_ms) FILTER (WHERE finished_at IS NOT NULL AND duration_ms IS NOT NULL) AS latency_mean,
                    max(duration_ms) FILTER (WHERE finished_at IS NOT NULL AND duration_ms IS NOT NULL) AS latency_max
                FROM delivery_attempts
            ), ready AS (
                SELECT count(*) AS ready_depth,
                    max(GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - e.accepted_at)) * 1000)) AS oldest_ready_age
                FROM deliveries d
                JOIN events e ON e.workspace_id = d.workspace_id AND e.id = d.event_id
                JOIN endpoints ep ON ep.workspace_id = e.workspace_id AND ep.id = e.endpoint_id
                WHERE (d.status = 'PENDING' OR (d.status = 'RETRY_WAIT' AND d.next_attempt_at <= ?))
                    AND ep.paused = FALSE AND d.run_attempts < d.max_attempts
            ), initial_delay AS (
                SELECT count(*) AS initial_samples,
                    avg(GREATEST(0, EXTRACT(EPOCH FROM (a.started_at - e.accepted_at)) * 1000)) AS initial_mean,
                    max(GREATEST(0, EXTRACT(EPOCH FROM (a.started_at - e.accepted_at)) * 1000)) AS initial_max
                FROM delivery_attempts a
                JOIN deliveries d ON d.workspace_id = a.workspace_id AND d.id = a.delivery_id
                JOIN events e ON e.workspace_id = d.workspace_id AND e.id = d.event_id
                WHERE a.claim_generation = 1
            )
            SELECT * FROM delivery_counts CROSS JOIN attempt_counts CROSS JOIN ready CROSS JOIN initial_delay
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public MetricsSnapshotRepository(JdbcTemplate applicationJdbc, PlatformTransactionManager transactions) {
        // A private template keeps the scan's timeout from changing any application service.
        jdbc = new JdbcTemplate(Objects.requireNonNull(applicationJdbc.getDataSource()));
        jdbc.setQueryTimeout(2);
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(2);
    }

    public DurableMetricsSnapshot read(Instant schedulingNow) {
        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '2s'");
            return jdbc.queryForObject(AGGREGATES, MetricsSnapshotRepository::map,
                    Timestamp.from(schedulingNow), Timestamp.from(schedulingNow));
        }));
    }

    private static DurableMetricsSnapshot map(ResultSet row, int ignored) throws SQLException {
        var deliveries = new EnumMap<DeliveryStatus, Long>(DeliveryStatus.class);
        for (var state : DeliveryStatus.values()) deliveries.put(state, row.getLong("delivery_" + state.name().toLowerCase(java.util.Locale.ROOT)));
        var attempts = new EnumMap<AttemptOutcome, Long>(AttemptOutcome.class);
        for (var outcome : AttemptOutcome.values()) attempts.put(outcome, row.getLong("attempt_" + outcome.name().toLowerCase(java.util.Locale.ROOT)));
        return new DurableMetricsSnapshot(deliveries, attempts, row.getLong("ready_depth"),
                row.getLong("ready_depth") == 0 ? 0 : number(row, "oldest_ready_age"),
                row.getLong("automatic_retries"), row.getLong("manual_replay_firsts"),
                new DurableMetricsSnapshot.Statistics(row.getLong("initial_samples"), number(row, "initial_mean"), number(row, "initial_max")),
                new DurableMetricsSnapshot.Statistics(row.getLong("latency_samples"), number(row, "latency_mean"), number(row, "latency_max")));
    }

    private static double number(ResultSet row, String column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? Double.NaN : value;
    }
}
