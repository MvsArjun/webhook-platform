package dev.relay.delivery;

import java.util.UUID;

/** Existing event and delivery identities are preserved across explicit replay runs. */
public record ReplayAcceptance(UUID eventId, UUID deliveryId, long replayGeneration, String status) { }
