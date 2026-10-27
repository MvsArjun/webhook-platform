package dev.relay.events;

import java.time.Instant;
import java.util.UUID;

/** Immutable acceptance fields: a later delivery state never changes an idempotent result. */
public record Acceptance(UUID eventId, UUID deliveryId, Instant acceptedAt) {}
