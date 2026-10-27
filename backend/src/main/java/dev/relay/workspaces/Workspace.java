package dev.relay.workspaces;

import java.time.Instant;
import java.util.UUID;

/** Public workspace representation; ownership is deliberately kept out of responses. */
public record Workspace(UUID id, String name, Instant createdAt, int deliveryConcurrencyLimit) {
}
