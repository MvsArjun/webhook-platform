package dev.relay.observability.logging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.relay.delivery.ClaimedDelivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class SafeLogContextTest {
    @AfterEach
    void clearCallerContext() { MDC.clear(); }

    @Test
    void nestedScopesExcludeUntrustedContextAndRestoreTheCallerOnEveryExit() {
        Map<String, String> original = Map.of("request_id", "stale-request", "private", "must-not-inherit");
        MDC.setContextMap(original);
        UUID requestId = UUID.randomUUID();
        var claim = claim();
        try (var request = SafeLogContext.request(requestId)) {
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("request_id", requestId.toString()));
            try (var delivery = SafeLogContext.delivery(claim)) {
                assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "event_id", claim.eventId().toString(), "delivery_id", claim.deliveryId().toString(),
                        "attempt_id", claim.attemptId().toString(), "generation", "7"));
                assertThat(MDC.getCopyOfContextMap().values()).doesNotContain(claim.token().toString(),
                        claim.workspaceId().toString(), requestId.toString(), "must-not-inherit");
            }
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("request_id", requestId.toString()));
        }
        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(original);
    }

    @Test
    void reusedExecutorThreadDoesNotRetainRequestOrDeliveryIdsAfterAnException() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> {
                try (var request = SafeLogContext.request(UUID.randomUUID());
                     var delivery = SafeLogContext.delivery(claim())) {
                    throw new IllegalStateException("synthetic failure");
                } catch (IllegalStateException expected) {
                    return MDC.getCopyOfContextMap();
                }
            });
            assertThat(first.get(2, TimeUnit.SECONDS)).isNullOrEmpty();
            assertThat(executor.submit(MDC::getCopyOfContextMap).get(2, TimeUnit.SECONDS)).isNullOrEmpty();
            UUID nextRequest = UUID.randomUUID();
            assertThat(executor.submit(() -> {
                try (var ignored = SafeLogContext.request(nextRequest)) { return MDC.getCopyOfContextMap(); }
            }).get(2, TimeUnit.SECONDS)).containsExactlyEntriesOf(Map.of("request_id", nextRequest.toString()));
            assertThat(executor.submit(MDC::getCopyOfContextMap).get(2, TimeUnit.SECONDS)).isNullOrEmpty();
        }
    }

    private static ClaimedDelivery claim() {
        return new ClaimedDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 7, Instant.now().plusSeconds(30));
    }
}
