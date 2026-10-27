package dev.relay.delivery;

import java.util.UUID;

import dev.relay.apikeys.ApiKeyPrincipal;
import dev.relay.identity.AccountPrincipal;
import dev.relay.workspaces.WorkspaceService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class ReplayController {
    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);
    private final ReplayService replays;
    private final WorkspaceService workspaces;

    public ReplayController(ReplayService replays, WorkspaceService workspaces) {
        this.replays = replays;
        this.workspaces = workspaces;
    }

    @PostMapping(path = "/api/v1/events/{eventId}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReplayAcceptance> replay(@AuthenticationPrincipal ApiKeyPrincipal key,
                                                   @PathVariable UUID eventId,
                                                   @Valid @RequestBody ReplayRequest request) {
        return replayAccepted(replays.replay(key.workspaceId(), eventId, request.expectedReplayGeneration()));
    }

    @PostMapping(path = "/api/workspaces/{workspaceId}/events/{eventId}/replay",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReplayAcceptance> replayOwned(@AuthenticationPrincipal AccountPrincipal account,
                                                        @PathVariable UUID workspaceId, @PathVariable UUID eventId,
                                                        @Valid @RequestBody ReplayRequest request) {
        workspaces.getOwned(account.id(), workspaceId);
        return replayAccepted(replays.replay(workspaceId, eventId, request.expectedReplayGeneration()));
    }

    private static ResponseEntity<ReplayAcceptance> replayAccepted(ReplayAcceptance acceptance) {
        log.atInfo().addKeyValue("event_type", "replay_accepted")
                .addKeyValue("event_id", acceptance.eventId()).addKeyValue("delivery_id", acceptance.deliveryId())
                .addKeyValue("replay_generation", acceptance.replayGeneration())
                .log("Manual replay accepted");
        return ResponseEntity.accepted().body(acceptance);
    }
}
