package dev.relay.delivery;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import dev.relay.delivery.signing.SigningKeyService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryInputService {
    private final JdbcTemplate jdbc;
    private final SigningKeyService signing;

    public DeliveryInputService(JdbcTemplate jdbc, SigningKeyService signing) {
        this.jdbc = jdbc;
        this.signing = signing;
    }

    /** A fenced snapshot; this transaction releases its connection before any network work. */
    @Transactional
    public Optional<Input> load(ClaimedDelivery claim) {
        var rows = jdbc.query("""
                SELECT ep.id AS endpoint_id, ep.url, e.payload,
                    FLOOR(EXTRACT(EPOCH FROM (d.lease_expires_at - clock_timestamp())) * 1000)::BIGINT AS remaining_ms
                FROM deliveries d
                JOIN events e ON e.workspace_id = d.workspace_id AND e.id = d.event_id
                JOIN endpoints ep ON ep.workspace_id = e.workspace_id AND ep.id = e.endpoint_id
                WHERE d.id = ? AND d.event_id = ? AND d.workspace_id = ? AND d.status = 'IN_FLIGHT'
                    AND d.claim_token = ? AND d.claim_generation = ? AND d.lease_expires_at > clock_timestamp()
                """, (row, index) -> new Snapshot(row.getObject("endpoint_id", UUID.class),
                        URI.create(row.getString("url")), row.getBytes("payload"), row.getLong("remaining_ms")),
                claim.deliveryId(), claim.eventId(), claim.workspaceId(), claim.token(), claim.generation());
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.getFirst();
        var key = signing.getOrCreate(claim.workspaceId(), row.endpointId());
        return Optional.of(new Input(new DeliveryRequest(row.url(), row.payload(), claim.eventId(),
                claim.deliveryId(), claim.attemptId(), claim.generation(), key), row.remainingMillis()));
    }

    public record Input(DeliveryRequest request, long remainingMillis) {}

    private record Snapshot(UUID endpointId, URI url, byte[] payload, long remainingMillis) {
        @Override public String toString() { return "Snapshot[REDACTED]"; }
    }
}
