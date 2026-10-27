package dev.relay.delivery;

import java.time.Instant;
import java.util.UUID;

/** A committed lease and its fencing identity; contains no payload or destination URL. */
public record ClaimedDelivery(UUID deliveryId, UUID eventId, UUID workspaceId, UUID attemptId,
                              UUID token, long generation, Instant leaseExpiresAt) {
    @Override
    public String toString() {
        return "ClaimedDelivery[deliveryId=" + deliveryId + ", attemptId=" + attemptId
                + ", generation=" + generation + ", token=REDACTED]";
    }
}
