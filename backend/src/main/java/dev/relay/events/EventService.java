package dev.relay.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import dev.relay.common.Page;
import dev.relay.common.PageRequest;
import dev.relay.delivery.DeliveryStatus;
import dev.relay.delivery.retry.RetrySettings;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventService {
    private final JdbcTemplate jdbc;
    private final RetrySettings retries;

    public EventService(JdbcTemplate jdbc, RetrySettings retries) {
        this.jdbc = jdbc;
        this.retries = retries;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Acceptance accept(UUID workspaceId, UUID endpointId, String idempotencyKey, byte[] payload) {
        byte[] digest = requestDigest(endpointId, payload);
        if (idempotencyKey != null) {
            var original = findOriginal(workspaceId, idempotencyKey);
            if (original.isPresent()) return original.get().matching(endpointId, digest, payload);
        }

        // SHARE permits concurrent submissions but conflicts with a pause UPDATE.
        var endpoints = jdbc.query("""
                SELECT paused FROM endpoints WHERE workspace_id = ? AND id = ? FOR SHARE
                """, (row, index) -> row.getBoolean("paused"), workspaceId, endpointId);
        if (endpoints.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found");
        }
        // A winner could have committed while we waited for a concurrent pause/unpause.
        if (idempotencyKey != null) {
            var original = findOriginal(workspaceId, idempotencyKey);
            if (original.isPresent()) return original.get().matching(endpointId, digest, payload);
        }
        if (endpoints.getFirst()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Endpoint is paused");
        }

        UUID eventId = UUID.randomUUID();
        var accepted = jdbc.query("""
                INSERT INTO events (id, workspace_id, endpoint_id, payload, request_digest, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, idempotency_key) DO NOTHING
                RETURNING accepted_at
                """, (row, index) -> row.getTimestamp("accepted_at").toInstant(),
                eventId, workspaceId, endpointId, payload, digest, idempotencyKey);
        if (accepted.isEmpty()) {
            // ON CONFLICT waits for the winner. This separate READ COMMITTED statement
            // sees its committed event AND delivery, or our insert would have succeeded.
            return findOriginal(workspaceId, idempotencyKey).orElseThrow(
                    () -> new IllegalStateException("Accepted event is missing durable delivery work"))
                    .matching(endpointId, digest, payload);
        }

        UUID deliveryId = UUID.randomUUID();
        jdbc.update("INSERT INTO deliveries (id, workspace_id, event_id, max_attempts) VALUES (?, ?, ?, ?)",
                deliveryId, workspaceId, eventId, retries.maxAttempts());
        // The Spring transaction interceptor commits both rows before the controller sends 202.
        return new Acceptance(eventId, deliveryId, accepted.getFirst());
    }

    @Transactional(readOnly = true)
    public EventView get(UUID workspaceId, UUID eventId) {
        return jdbc.query("""
                SELECT e.id, e.endpoint_id, e.accepted_at, d.id AS delivery_id, d.status,
                    d.run_attempts, d.max_attempts, d.replay_generation, d.next_attempt_at
                FROM events e JOIN deliveries d ON d.workspace_id = e.workspace_id AND d.event_id = e.id
                WHERE e.workspace_id = ? AND e.id = ?
                """, EventService::mapEvent, workspaceId, eventId).stream().findFirst().orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    @Transactional(readOnly = true)
    public Page<EventView> list(UUID workspaceId, PageRequest page) {
        return list(workspaceId, page, null, null);
    }

    @Transactional(readOnly = true)
    public Page<EventView> list(UUID workspaceId, PageRequest page, DeliveryStatus status, UUID endpointId) {
        StringBuilder query = new StringBuilder("""
                SELECT e.id, e.endpoint_id, e.accepted_at, d.id AS delivery_id, d.status,
                    d.run_attempts, d.max_attempts, d.replay_generation, d.next_attempt_at
                FROM events e JOIN deliveries d ON d.workspace_id = e.workspace_id AND d.event_id = e.id
                WHERE e.workspace_id = ?
                """);
        var arguments = new ArrayList<Object>();
        arguments.add(workspaceId);
        if (status != null) {
            query.append(" AND d.status = ?");
            arguments.add(status.name());
        }
        if (endpointId != null) {
            query.append(" AND e.endpoint_id = ?");
            arguments.add(endpointId);
        }
        query.append(" ORDER BY e.accepted_at DESC, e.id DESC LIMIT ? OFFSET ?");
        arguments.add(page.limit() + 1);
        arguments.add(page.offset());
        return Page.from(jdbc.query(query.toString(), EventService::mapEvent, arguments.toArray()), page);
    }

    private Optional<Original> findOriginal(UUID workspaceId, String key) {
        return jdbc.query("""
                SELECT e.id, e.endpoint_id, e.payload, e.request_digest, e.accepted_at, d.id AS delivery_id
                FROM events e JOIN deliveries d ON d.workspace_id = e.workspace_id AND d.event_id = e.id
                WHERE e.workspace_id = ? AND e.idempotency_key = ?
                """, (row, index) -> new Original(row.getObject("endpoint_id", UUID.class),
                row.getBytes("payload"), row.getBytes("request_digest"),
                new Acceptance(row.getObject("id", UUID.class), row.getObject("delivery_id", UUID.class),
                        row.getTimestamp("accepted_at").toInstant())), workspaceId, key).stream().findFirst();
    }

    private static EventView mapEvent(ResultSet row, int index) throws SQLException {
        var next = row.getTimestamp("next_attempt_at");
        return new EventView(row.getObject("id", UUID.class), row.getObject("endpoint_id", UUID.class),
                row.getObject("delivery_id", UUID.class), row.getString("status"),
                row.getTimestamp("accepted_at").toInstant(), row.getInt("run_attempts"), row.getInt("max_attempts"),
                row.getLong("replay_generation"), next == null ? null : next.toInstant());
    }

    private static byte[] requestDigest(UUID endpointId, byte[] payload) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update("relay.ingestion.v1\0".getBytes(StandardCharsets.US_ASCII));
            hash.update(ByteBuffer.allocate(16).putLong(endpointId.getMostSignificantBits())
                    .putLong(endpointId.getLeastSignificantBits()).array());
            return hash.digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Original(UUID endpointId, byte[] payload, byte[] digest, Acceptance acceptance) {
        Acceptance matching(UUID endpoint, byte[] candidateDigest, byte[] candidatePayload) {
            if (!endpointId.equals(endpoint) || !MessageDigest.isEqual(digest, candidateDigest)
                    || !Arrays.equals(payload, candidatePayload)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key conflicts with accepted input");
            }
            return acceptance;
        }

        @Override
        public String toString() {
            return "Original[eventId=" + acceptance.eventId() + ", payload=REDACTED]";
        }
    }
}
