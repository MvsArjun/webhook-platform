package dev.relay.delivery;

import java.util.UUID;

import dev.relay.apikeys.ApiKeyPrincipal;
import dev.relay.common.Page;
import dev.relay.common.PageRequest;
import dev.relay.identity.AccountPrincipal;
import dev.relay.workspaces.WorkspaceService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class AttemptHistoryController {
    private final AttemptHistoryService attempts;
    private final WorkspaceService workspaces;

    public AttemptHistoryController(AttemptHistoryService attempts, WorkspaceService workspaces) {
        this.attempts = attempts;
        this.workspaces = workspaces;
    }

    @GetMapping("/api/v1/events/{eventId}/attempts")
    public Page<AttemptView> list(@AuthenticationPrincipal ApiKeyPrincipal key,
                                  @PathVariable UUID eventId,
                                  @RequestParam(defaultValue = "20") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        return attempts.list(key.workspaceId(), eventId, new PageRequest(limit, offset));
    }

    @GetMapping("/api/workspaces/{workspaceId}/events/{eventId}/attempts")
    public Page<AttemptView> listOwned(@AuthenticationPrincipal AccountPrincipal account,
                                       @PathVariable UUID workspaceId, @PathVariable UUID eventId,
                                       @RequestParam(defaultValue = "20") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        workspaces.getOwned(account.id(), workspaceId);
        return attempts.list(workspaceId, eventId, new PageRequest(limit, offset));
    }
}
