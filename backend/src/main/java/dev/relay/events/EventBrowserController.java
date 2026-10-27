package dev.relay.events;

import java.util.UUID;

import dev.relay.common.Page;
import dev.relay.common.PageRequest;
import dev.relay.delivery.DeliveryStatus;
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
public class EventBrowserController {
    private final EventService events;
    private final WorkspaceService workspaces;

    public EventBrowserController(EventService events, WorkspaceService workspaces) {
        this.events = events;
        this.workspaces = workspaces;
    }

    @GetMapping("/api/workspaces/{workspaceId}/events/{eventId}")
    public EventView get(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID workspaceId,
                         @PathVariable UUID eventId) {
        workspaces.getOwned(account.id(), workspaceId);
        return events.get(workspaceId, eventId);
    }

    @GetMapping("/api/workspaces/{workspaceId}/events")
    public Page<EventView> list(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID workspaceId,
                                @RequestParam(defaultValue = "20") int limit,
                                @RequestParam(defaultValue = "0") int offset,
                                @RequestParam(required = false) DeliveryStatus status,
                                @RequestParam(required = false) UUID endpointId) {
        workspaces.getOwned(account.id(), workspaceId);
        return events.list(workspaceId, new PageRequest(limit, offset), status, endpointId);
    }
}
