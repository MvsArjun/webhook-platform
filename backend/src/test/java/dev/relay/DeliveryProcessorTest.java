package dev.relay;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.relay.delivery.ClaimedDelivery;
import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryInputService;
import dev.relay.delivery.DeliveryProcessor;
import dev.relay.delivery.DeliveryRequest;
import dev.relay.delivery.DeliveryResult;
import dev.relay.delivery.WebhookSender;
import dev.relay.worker.WorkerSettings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeliveryProcessorTest {
    private final DeliveryInputService inputs = mock(DeliveryInputService.class);
    private final DeliveryClaimService claims = mock(DeliveryClaimService.class);
    private final WebhookSender sender = mock(WebhookSender.class);
    private final WorkerSettings settings = new WorkerSettings(2, Duration.ofMillis(50),
            Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(10), Duration.ofSeconds(1), 20);
    private final DeliveryProcessor processor = new DeliveryProcessor(inputs, claims, sender, settings);
    private final ClaimedDelivery claim = new ClaimedDelivery(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now().plusSeconds(10));

    @Test
    void staleClaimAndInsufficientLeaseDoNotSend() {
        when(inputs.load(claim)).thenReturn(Optional.empty());
        processor.process(claim);
        verifyNoInteractions(sender, claims);

        when(inputs.load(claim)).thenReturn(Optional.of(new DeliveryInputService.Input(request(), 999)));
        processor.process(claim);
        verifyNoInteractions(sender, claims);
    }

    @Test
    void remainingLeaseCapsDeadlineAndCompletionUsesExactResult() {
        var request = request();
        var result = new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 2);
        when(inputs.load(claim)).thenReturn(Optional.of(new DeliveryInputService.Input(request, 2500)));
        when(sender.send(eq(request), any())).thenReturn(result);
        processor.process(claim);
        var budget = ArgumentCaptor.forClass(Duration.class);
        verify(sender).send(eq(request), budget.capture());
        assertThat(budget.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(1500));
        verify(claims).complete(claim, result);
    }

    @Test
    void refusesAnAmbientTransactionBeforeLoadingOrSending() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> processor.process(claim)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(inputs, sender, claims);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test
    void closedShutdownGateDoesNotLoadOrSend() {
        processor.process(claim, () -> false);
        verifyNoInteractions(inputs, sender, claims);
    }

    @Test
    void shutdownWhileLoadingPreventsTheHttpRequest() {
        AtomicBoolean mayContinue = new AtomicBoolean(true);
        when(inputs.load(claim)).thenAnswer(invocation -> {
            mayContinue.set(false);
            return Optional.of(new DeliveryInputService.Input(request(), 5000));
        });
        processor.process(claim, mayContinue::get);
        verify(inputs).load(claim);
        verifyNoInteractions(sender, claims);
    }

    @Test
    void shutdownWhileSendingLeavesTheAttemptForRecoveryInsteadOfCompletingIt() {
        AtomicBoolean mayContinue = new AtomicBoolean(true);
        var request = request();
        when(inputs.load(claim)).thenReturn(Optional.of(new DeliveryInputService.Input(request, 5000)));
        when(sender.send(eq(request), any())).thenAnswer(invocation -> {
            mayContinue.set(false);
            return new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 2);
        });
        processor.process(claim, mayContinue::get);
        verify(sender).send(eq(request), any());
        verifyNoInteractions(claims);
    }

    @Test
    void metadataCannotPersistAnExceptionMessageOrInconsistentSuccess() {
        assertThatThrownBy(() -> new DeliveryResult(CompletionOutcome.FAILED, null,
                "secret destination detail", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryResult(CompletionOutcome.SUCCEEDED, 500, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryResult(CompletionOutcome.FAILED, 200, "HTTP_STATUS", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DeliveryRequest request() {
        return new DeliveryRequest(URI.create("https://receiver.example/webhook"), new byte[] {123, 125},
                claim.eventId(), claim.deliveryId(), claim.attemptId(), claim.generation(),
                new dev.relay.delivery.signing.SigningKey(new byte[32]));
    }
}
