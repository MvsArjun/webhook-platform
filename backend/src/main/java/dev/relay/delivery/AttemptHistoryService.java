package dev.relay.delivery;

import java.sql.Timestamp;
import java.util.UUID;

import dev.relay.common.Page;
import dev.relay.common.PageRequest;
import dev.relay.events.EventService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttemptHistoryService {
    private final JdbcTemplate jdbc;
    private final EventService events;

    public AttemptHistoryService(JdbcTemplate jdbc, EventService events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<AttemptView> list(UUID workspaceId, UUID eventId, PageRequest page) {
        // Distinguish an owned event with no attempts from a missing/foreign event.
        events.get(workspaceId, eventId);
        return Page.from(jdbc.query("""
                SELECT a.id, a.delivery_id, a.claim_generation, a.outcome, a.started_at, a.finished_at,
                    a.http_status, a.failure_code, a.duration_ms, a.replay_generation, a.run_attempt,
                    a.response_preview, a.response_truncated
                FROM delivery_attempts a
                JOIN deliveries d ON d.workspace_id = a.workspace_id AND d.id = a.delivery_id
                WHERE a.workspace_id = ? AND d.event_id = ?
                ORDER BY a.claim_generation ASC LIMIT ? OFFSET ?
                """, (row, index) -> {
            Timestamp finished = row.getTimestamp("finished_at");
            return new AttemptView(row.getObject("id", UUID.class), row.getObject("delivery_id", UUID.class),
                    row.getLong("claim_generation"), row.getString("outcome"),
                    row.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant(),
                    row.getObject("http_status", Integer.class), row.getString("failure_code"),
                    row.getObject("duration_ms", Long.class), row.getLong("replay_generation"), row.getInt("run_attempt"),
                    row.getString("response_preview"), row.getBoolean("response_truncated"));
        }, workspaceId, eventId, page.limit() + 1, page.offset()), page);
    }
}
