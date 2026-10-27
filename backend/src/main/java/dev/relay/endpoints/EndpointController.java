package dev.relay.endpoints;

import java.net.URI;
import java.util.UUID;

import dev.relay.common.Page;
import dev.relay.common.PageRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
@RequestMapping("/api/workspaces/{workspaceId}/endpoints")
public class EndpointController {
    private final EndpointService endpoints;

    public EndpointController(EndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @PostMapping
    public ResponseEntity<Endpoint> create(@AuthenticationPrincipal AccountPrincipal account,
                                           @PathVariable UUID workspaceId,
                                           @Valid @RequestBody EndpointUrlRequest request) {
        Endpoint endpoint = endpoints.create(account.id(), workspaceId, request.url());
        return ResponseEntity.created(URI.create("/api/workspaces/" + workspaceId + "/endpoints/" + endpoint.id()))
                .body(endpoint);
    }

    @GetMapping
    public Page<Endpoint> list(@AuthenticationPrincipal AccountPrincipal account,
                               @PathVariable UUID workspaceId,
                               @RequestParam(name = "limit", defaultValue = "20") int limit,
                               @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return endpoints.listOwned(account.id(), workspaceId, new PageRequest(limit, offset));
    }

    @GetMapping("/{endpointId}")
    public Endpoint get(@AuthenticationPrincipal AccountPrincipal account,
                         @PathVariable UUID workspaceId, @PathVariable UUID endpointId) {
        return endpoints.getOwned(account.id(), workspaceId, endpointId);
    }

    @PatchMapping("/{endpointId}")
    public Endpoint setPaused(@AuthenticationPrincipal AccountPrincipal account,
                               @PathVariable UUID workspaceId, @PathVariable UUID endpointId,
                               @Valid @RequestBody EndpointPauseRequest request) {
        return endpoints.setPaused(account.id(), workspaceId, endpointId, request.paused());
    }
}
