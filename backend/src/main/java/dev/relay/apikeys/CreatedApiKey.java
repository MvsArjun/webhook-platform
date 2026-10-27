package dev.relay.apikeys;

import java.time.Instant;
import java.util.UUID;

/** The raw key is returned once, on creation. Do not log or persist this response. */
public record CreatedApiKey(UUID id, String name, String prefix, Instant createdAt, Instant revokedAt, String key) {
    @Override
    public String toString() {
        return "CreatedApiKey[id=" + id + ", key=[REDACTED]]";
    }
}
