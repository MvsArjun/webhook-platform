package dev.relay.delivery;

import java.time.Instant;
import java.util.UUID;

/** Customer-visible history; worker fencing tokens and event payloads stay private. */
public record AttemptView(UUID id, UUID deliveryId, long generation, String outcome,
                          Instant startedAt, Instant finishedAt, Integer httpStatus,
                          String failureCode, Long durationMillis, long replayGeneration, int runAttempt,
                          String responsePreview, boolean responseTruncated) {
    @Override public String toString() {
        return "AttemptView[id=" + id + ", outcome=" + outcome + ", responsePreview=REDACTED]";
    }
}
