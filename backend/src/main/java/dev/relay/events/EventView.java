package dev.relay.events;

import java.time.Instant;
import java.util.UUID;

/** Status APIs deliberately omit event payloads, request digests and idempotency keys. */
public record EventView(UUID eventId, UUID endpointId, UUID deliveryId, String status, Instant acceptedAt,
                         int attemptsInRun, int maxAttempts, long replayGeneration, Instant nextAttemptAt) {}
