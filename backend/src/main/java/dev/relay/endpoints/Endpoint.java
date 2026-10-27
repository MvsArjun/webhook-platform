package dev.relay.endpoints;

import java.time.Instant;
import java.util.UUID;

public record Endpoint(UUID id, String url, boolean paused, Instant createdAt, Instant updatedAt) {
}
