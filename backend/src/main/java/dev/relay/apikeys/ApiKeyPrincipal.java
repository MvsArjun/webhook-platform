package dev.relay.apikeys;

import java.util.UUID;

/** Identity produced by an active API key; contains no credential material. */
public record ApiKeyPrincipal(UUID id, UUID workspaceId) {
}
