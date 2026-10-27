package dev.relay.delivery;

import java.util.UUID;

import dev.relay.delivery.retry.RetrySettings;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReplayService {
    private final JdbcTemplate jdbc;
    private final RetrySettings settings;

    public ReplayService(JdbcTemplate jdbc, RetrySettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReplayAcceptance replay(UUID workspaceId, UUID eventId, long expectedReplayGeneration) {
        if (expectedReplayGeneration < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid replay generation");
        }
        // Claim, completion, recovery and replay serialize on the delivery before touching its endpoint.
        var deliveries = jdbc.query("""
                SELECT d.id, d.status, d.replay_generation, e.endpoint_id
                FROM deliveries d
                JOIN events e ON e.workspace_id = d.workspace_id AND e.id = d.event_id
                WHERE d.workspace_id = ? AND e.id = ?
                FOR UPDATE OF d
                """, (row, index) -> new ReplayCandidate(row.getObject("id", UUID.class),
                row.getString("status"), row.getLong("replay_generation"),
                row.getObject("endpoint_id", UUID.class)), workspaceId, eventId);
        if (deliveries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        var delivery = deliveries.getFirst();
        if (!delivery.status().equals("FAILED") || delivery.generation() != expectedReplayGeneration
                || delivery.generation() == Long.MAX_VALUE) {
            throw conflict();
        }
        var endpoints = jdbc.query("""
                SELECT paused FROM endpoints WHERE workspace_id = ? AND id = ? FOR SHARE
                """, (row, index) -> row.getBoolean("paused"), workspaceId, delivery.endpointId());
        if (endpoints.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found");
        }
        if (endpoints.getFirst()) throw conflict();

        int reset = jdbc.update("""
                UPDATE deliveries SET status = 'PENDING', next_attempt_at = NULL,
                    run_attempts = 0, max_attempts = ?, replay_generation = replay_generation + 1
                WHERE workspace_id = ? AND id = ? AND status = 'FAILED' AND replay_generation = ?
                """, settings.maxAttempts(), workspaceId, delivery.id(), expectedReplayGeneration);
        if (reset != 1) throw new IllegalStateException("Replay delivery state is inconsistent");
        // Existing attempts and the lifetime claim generation remain untouched.
        return new ReplayAcceptance(eventId, delivery.id(), delivery.generation() + 1, "PENDING");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Event cannot be replayed in its current state");
    }

    private record ReplayCandidate(UUID id, String status, long generation, UUID endpointId) { }
}
