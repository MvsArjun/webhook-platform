package dev.relay.endpoints;

import jakarta.validation.constraints.NotNull;

public record EndpointPauseRequest(@NotNull Boolean paused) {
}
