package dev.relay.observability.logging;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dev.relay.delivery.ClaimedDelivery;
import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryInputService;
import dev.relay.delivery.DeliveryProcessor;
import dev.relay.delivery.DeliveryRequest;
import dev.relay.delivery.DeliveryResult;
import dev.relay.delivery.WebhookSender;
import dev.relay.delivery.signing.SigningKey;
import dev.relay.worker.WorkerRuntime;
import dev.relay.worker.WorkerSettings;
import dev.relay.worker.WorkerShutdownSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeliveryLifecycleLogTest {
    private final WorkerSettings settings = new WorkerSettings(1, Duration.ofMillis(10), Duration.ofMillis(100),
            Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofSeconds(5), Duration.ofMillis(100), 10);

    @AfterEach
    void clearCallerContext() { MDC.clear(); }

    @Test
    void onlyCommittedCompletionHasAnOutcomeAndAbandonmentIsExplicitWithoutInheritedRequestIds() {
        var inputs = mock(DeliveryInputService.class);
        var claims = mock(DeliveryClaimService.class);
        var sender = mock(WebhookSender.class);
        var first = claim();
        var second = claim();
        var third = claim();
        var result = new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 5,
                "private-preview-marker", false);
        when(inputs.load(any())).thenAnswer(invocation -> {
            ClaimedDelivery current = invocation.getArgument(0);
            return Optional.of(new DeliveryInputService.Input(request(current), 5000));
        });
        when(sender.send(any(), any())).thenReturn(result);
        when(claims.complete(first, result)).thenReturn(false);
        when(claims.complete(second, result)).thenReturn(true);
        var processor = new DeliveryProcessor(inputs, claims, sender, settings);
        MDC.put("request_id", "stale-request-marker");
        MDC.put("private", "private-context-marker");
        try (var logs = new EventCapture(DeliveryProcessor.class)) {
            processor.process(first);
            processor.process(second);
            processor.process(third, () -> false);
            assertThat(logs.ofType("completion_ignored")).hasSize(1);
            var ignored = logs.ofType("completion_ignored").getFirst();
            assertThat(ignored.getMDCPropertyMap()).containsEntry("attempt_id", first.attemptId().toString());
            assertThat(fields(ignored)).doesNotContainKeys("outcome", "http_status", "failure_code");
            assertThat(logs.ofType("completion_committed")).hasSize(1);
            var committed = logs.ofType("completion_committed").getFirst();
            assertThat(committed.getMDCPropertyMap()).containsEntry("attempt_id", second.attemptId().toString());
            assertThat(fields(committed)).containsEntry("outcome", "SUCCEEDED").containsEntry("http_status", 204);
            assertThat(logs.ofType("delivery_abandoned")).hasSize(1);
            var abandoned = logs.ofType("delivery_abandoned").getFirst();
            assertThat(abandoned.getMDCPropertyMap()).containsEntry("attempt_id", third.attemptId().toString());
            assertThat(fields(abandoned)).containsEntry("reason", "shutdown").doesNotContainKey("outcome");
            for (var event : logs.events) {
                assertThat(event.getMDCPropertyMap()).containsOnlyKeys("event_id", "delivery_id", "attempt_id", "generation");
                assertThat(event.getMDCPropertyMap().values()).doesNotContain("stale-request-marker", "private-context-marker");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("private-");
                assertThat(fields(event).toString()).doesNotContain("private-preview-marker", "private-payload-marker",
                        "private-destination-marker", first.token().toString(), second.token().toString(), third.token().toString());
            }
            verify(claims, never()).complete(eq(third), any(DeliveryResult.class));
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "request_id", "stale-request-marker", "private", "private-context-marker"));
        }
    }

    @Test
    void runtimeCatchRetainsSafeClaimIdsAndTheNextTaskOnTheSameThreadHasItsOwnScope() throws Exception {
        var claims = mock(DeliveryClaimService.class);
        var processor = mock(DeliveryProcessor.class);
        var first = claim();
        var second = claim();
        when(claims.claim(anyInt(), any())).thenReturn(List.of(first)).thenReturn(List.of(second)).thenReturn(List.of());
        var scopes = new CopyOnWriteArrayList<Map<String, String>>();
        CountDownLatch bothProcessed = new CountDownLatch(2);
        doAnswer(invocation -> {
            scopes.add(MDC.getCopyOfContextMap());
            bothProcessed.countDown();
            ClaimedDelivery current = invocation.getArgument(0);
            if (current.equals(first)) throw new IllegalStateException("private-exception-marker");
            return null;
        }).when(processor).process(any(ClaimedDelivery.class), any(BooleanSupplier.class));
        var runtime = new WorkerRuntime(claims, processor, settings, new WorkerShutdownSettings(Duration.ofSeconds(1)));
        try (var logs = new EventCapture(WorkerRuntime.class)) {
            try {
                runtime.start();
                assertThat(bothProcessed.await(3, TimeUnit.SECONDS)).isTrue();
                runtime.stop();
                assertThat(scopes).hasSize(2);
                assertThat(scopes.getFirst()).containsEntry("attempt_id", first.attemptId().toString());
                assertThat(scopes.getLast()).containsEntry("attempt_id", second.attemptId().toString());
                assertThat(scopes.getLast().values()).doesNotContain(first.attemptId().toString(), first.token().toString());
                var interrupted = logs.ofType("delivery_processing_interrupted");
                assertThat(interrupted).hasSize(1);
                assertThat(interrupted.getFirst().getMDCPropertyMap()).containsEntry("event_id", first.eventId().toString())
                        .containsEntry("delivery_id", first.deliveryId().toString()).containsEntry("attempt_id", first.attemptId().toString())
                        .containsEntry("generation", "3").doesNotContainKeys("request_id", "token", "workspace_id");
                assertThat(interrupted.getFirst().getThrowableProxy()).isNull();
                assertThat(interrupted.getFirst().getFormattedMessage()).doesNotContain("private-exception-marker");
                assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            } finally { runtime.stop(); }
        }
    }

    private static ClaimedDelivery claim() {
        return new ClaimedDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 3, Instant.now().plusSeconds(30));
    }

    private static DeliveryRequest request(ClaimedDelivery claim) {
        return new DeliveryRequest(URI.create("https://private-destination-marker.example/webhook"),
                "private-payload-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8), claim.eventId(), claim.deliveryId(),
                claim.attemptId(), claim.generation(), new SigningKey(new byte[32]));
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        var result = new java.util.HashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> result.put(pair.key, pair.value));
        return result;
    }

    private static final class EventCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final Logger logger;
        private final CopyOnWriteArrayList<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        private EventCapture(Class<?> type) {
            logger = (Logger) LoggerFactory.getLogger(type);
            start();
            logger.addAppender(this);
        }

        @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); events.add(event); }
        private List<ILoggingEvent> ofType(String type) {
            return events.stream().filter(event -> type.equals(fields(event).get("event_type"))).toList();
        }
        @Override public void close() { logger.detachAppender(this); stop(); }
    }
}
