package dev.relay.observability.logging;

import java.util.Map;
import java.util.UUID;

import dev.relay.delivery.ClaimedDelivery;
import org.slf4j.MDC;

/** Installs only server-owned identifiers and restores the caller's complete MDC on exit. */
public final class SafeLogContext implements AutoCloseable {
    private final Map<String, String> previous;

    private SafeLogContext(Map<String, String> fields) {
        previous = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.setContextMap(fields);
    }

    public static SafeLogContext request(UUID requestId) {
        return new SafeLogContext(Map.of("request_id", requestId.toString()));
    }

    public static SafeLogContext delivery(ClaimedDelivery claim) {
        return new SafeLogContext(Map.of("event_id", claim.eventId().toString(),
                "delivery_id", claim.deliveryId().toString(), "attempt_id", claim.attemptId().toString(),
                "generation", Long.toString(claim.generation())));
    }

    @Override
    public void close() {
        MDC.clear();
        if (previous != null) MDC.setContextMap(previous);
    }
}
