package dev.relay.apikeys;

import dev.relay.workspaces.Workspace;
import dev.relay.workspaces.WorkspaceService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class ApiKeyWorkspaceController {
    private final WorkspaceService workspaces;

    public ApiKeyWorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping("/api/v1/workspace")
    public Workspace get(@AuthenticationPrincipal ApiKeyPrincipal key) {
        return workspaces.getForApiKey(key.workspaceId());
    }
}
