package dev.relay.workspaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkspaceNameRequest(@NotBlank @Size(max = 100) String name) {
}
