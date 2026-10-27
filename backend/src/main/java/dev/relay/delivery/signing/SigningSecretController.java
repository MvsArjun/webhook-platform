package dev.relay.delivery.signing;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import dev.relay.identity.AccountPrincipal;
import dev.relay.workspaces.WorkspaceService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class SigningSecretController {
    private final SigningKeyService keys;
    private final WorkspaceService workspaces;

    public SigningSecretController(SigningKeyService keys, WorkspaceService workspaces) {
        this.keys = keys;
        this.workspaces = workspaces;
    }

    @PostMapping("/api/workspaces/{workspaceId}/endpoints/{endpointId}/signing-secret")
    public ResponseEntity<SigningSecret> reveal(@AuthenticationPrincipal AccountPrincipal account,
                                               @PathVariable UUID workspaceId, @PathVariable UUID endpointId) {
        workspaces.getOwned(account.id(), workspaceId);
        byte[] key = keys.getOrCreate(workspaceId, endpointId).bytes();
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new SigningSecret(Base64.getEncoder().encodeToString(key)));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public record SigningSecret(String secret) {
        @Override public String toString() { return "SigningSecret[REDACTED]"; }
    }
}
