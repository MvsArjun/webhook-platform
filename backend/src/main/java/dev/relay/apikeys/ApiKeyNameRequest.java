package dev.relay.apikeys;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApiKeyNameRequest(@NotBlank @Size(max = 100) String name) {
}
