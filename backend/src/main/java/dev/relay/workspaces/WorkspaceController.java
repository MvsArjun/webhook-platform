package dev.relay.workspaces;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import dev.relay.identity.AccountPrincipal;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @PostMapping
    public ResponseEntity<Workspace> create(@AuthenticationPrincipal AccountPrincipal account,
                                            @Valid @RequestBody WorkspaceNameRequest request) {
        Workspace workspace = workspaces.create(account.id(), request.name());
        return ResponseEntity.created(URI.create("/api/workspaces/" + workspace.id())).body(workspace);
    }

    @GetMapping
    public List<Workspace> list(@AuthenticationPrincipal AccountPrincipal account) {
        return workspaces.listOwned(account.id());
    }

    @GetMapping("/{workspaceId}")
    public Workspace get(@AuthenticationPrincipal AccountPrincipal account,
                         @PathVariable UUID workspaceId) {
        return workspaces.getOwned(account.id(), workspaceId);
    }

    @PatchMapping("/{workspaceId}")
    public Workspace rename(@AuthenticationPrincipal AccountPrincipal account,
                            @PathVariable UUID workspaceId,
                            @Valid @RequestBody WorkspaceNameRequest request) {
        return workspaces.rename(account.id(), workspaceId, request.name());
    }
}
