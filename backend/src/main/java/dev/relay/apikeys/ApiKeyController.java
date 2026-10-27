package dev.relay.apikeys;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import dev.relay.identity.AccountPrincipal;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
@RequestMapping("/api/workspaces/{workspaceId}/api-keys")
public class ApiKeyController {
    private final ApiKeyService keys;

    public ApiKeyController(ApiKeyService keys) {
        this.keys = keys;
    }

    @PostMapping
    public ResponseEntity<CreatedApiKey> create(@AuthenticationPrincipal AccountPrincipal account,
                                               @PathVariable UUID workspaceId,
                                               @Valid @RequestBody ApiKeyNameRequest request) {
        CreatedApiKey created = keys.create(account.id(), workspaceId, request.name());
        return ResponseEntity.created(URI.create("/api/workspaces/" + workspaceId + "/api-keys/" + created.id()))
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @GetMapping
    public List<ApiKeyMetadata> list(@AuthenticationPrincipal AccountPrincipal account,
                                    @PathVariable UUID workspaceId) {
        return keys.listOwned(account.id(), workspaceId);
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AccountPrincipal account,
                                       @PathVariable UUID workspaceId, @PathVariable UUID keyId) {
        keys.revoke(account.id(), workspaceId, keyId);
        return ResponseEntity.noContent().build();
    }
}
