package dev.relay.apikeys;

import java.time.Instant;
import java.util.UUID;

/** Safe to return when listing keys; neither the secret nor its hash is included. */
public record ApiKeyMetadata(UUID id, String name, String prefix, Instant createdAt, Instant revokedAt) {
}
