package dev.relay.events;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.relay.apikeys.ApiKeyPrincipal;
import dev.relay.common.Page;
import dev.relay.common.PageRequest;
import dev.relay.delivery.DeliveryStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("!worker")
public class EventApiController {
    private static final Logger log = LoggerFactory.getLogger(EventApiController.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final EventService events;

    public EventApiController(EventService events) {
        this.events = events;
    }

    @PostMapping(path = "/api/v1/endpoints/{endpointId}/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Acceptance> accept(@AuthenticationPrincipal ApiKeyPrincipal key,
                                             @PathVariable UUID endpointId, HttpServletRequest request)
            throws IOException {
        String idempotencyKey = idempotencyKey(request);
        var charset = MediaType.parseMediaType(request.getContentType()).getCharset();
        var encodings = Collections.list(request.getHeaders("Content-Encoding"));
        if ((charset != null && !charset.equals(StandardCharsets.UTF_8))
                || encodings.size() > 1
                || (encodings.size() == 1 && !encodings.getFirst().equalsIgnoreCase("identity"))) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Expected uncompressed UTF-8 JSON");
        }
        if (request.getContentLengthLong() > PayloadValidator.MAX_BYTES) throw tooLarge();
        byte[] payload = request.getInputStream().readNBytes(PayloadValidator.MAX_BYTES + 1);
        if (payload.length > PayloadValidator.MAX_BYTES) throw tooLarge();
        PayloadValidator.validate(payload);
        var acceptance = events.accept(key.workspaceId(), endpointId, idempotencyKey, payload);
        // The service transaction has committed; an idempotent retry returns this same acceptance.
        log.atInfo().addKeyValue("event_type", "acceptance_returned")
                .addKeyValue("event_id", acceptance.eventId()).addKeyValue("delivery_id", acceptance.deliveryId())
                .log("Durable event acceptance returned");
        return ResponseEntity.accepted().location(URI.create("/api/v1/events/" + acceptance.eventId()))
                .body(acceptance);
    }

    @GetMapping("/api/v1/events/{eventId}")
    public EventView get(@AuthenticationPrincipal ApiKeyPrincipal key, @PathVariable UUID eventId) {
        return events.get(key.workspaceId(), eventId);
    }

    @GetMapping("/api/v1/events")
    public Page<EventView> list(@AuthenticationPrincipal ApiKeyPrincipal key,
                                @RequestParam(defaultValue = "20") int limit,
                                @RequestParam(defaultValue = "0") int offset,
                                @RequestParam(required = false) DeliveryStatus status,
                                @RequestParam(required = false) UUID endpointId) {
        return events.list(key.workspaceId(), new PageRequest(limit, offset), status, endpointId);
    }

    private static String idempotencyKey(HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.isEmpty()) return null;
        if (keys.size() != 1 || !IDEMPOTENCY_KEY.matcher(keys.getFirst()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
        }
        return keys.getFirst();
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.valueOf(413), "Payload is too large");
    }
}
