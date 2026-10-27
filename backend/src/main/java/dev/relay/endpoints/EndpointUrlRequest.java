package dev.relay.endpoints;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EndpointUrlRequest(@NotBlank @Size(max = 2048) String url) {
    @Override
    public String toString() {
        return "EndpointUrlRequest[REDACTED]";
    }
}
