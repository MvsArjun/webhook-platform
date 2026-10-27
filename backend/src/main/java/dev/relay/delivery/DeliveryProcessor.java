package dev.relay.delivery;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import dev.relay.worker.WorkerSettings;
import dev.relay.observability.logging.SafeLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Orchestrates separate database snapshots, network work, and fenced completion. */
public class DeliveryProcessor {
    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);
    private final DeliveryInputService inputs;
    private final DeliveryClaimService claims;
    private final WebhookSender sender;
    private final WorkerSettings settings;

    public DeliveryProcessor(DeliveryInputService inputs, DeliveryClaimService claims,
                             WebhookSender sender, WorkerSettings settings) {
        this.inputs = inputs;
        this.claims = claims;
        this.sender = sender;
        this.settings = settings;
    }

    public void process(ClaimedDelivery claim) {
        process(claim, () -> true);
    }

    /** A forced worker stop abandons unfinished work rather than recording cancellation as failure. */
    public void process(ClaimedDelivery claim, BooleanSupplier mayContinue) {
        Objects.requireNonNull(mayContinue, "A delivery continuation gate is required");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Delivery processing requires a transaction-free caller");
        }
        try (var ignored = SafeLogContext.delivery(claim)) {
            processWithinContext(claim, mayContinue);
        }
    }

    private void processWithinContext(ClaimedDelivery claim, BooleanSupplier mayContinue) {
        if (!mayContinue.getAsBoolean()) { abandoned("shutdown"); return; }
        long loadStarted = System.nanoTime();
        var input = inputs.load(claim);
        if (input.isEmpty()) { abandoned("stale_claim"); return; }
        if (!mayContinue.getAsBoolean()) { abandoned("shutdown"); return; }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - loadStarted).toMillis();
        long remainingMillis = input.get().remainingMillis() - elapsedMillis - settings.leaseSafety().toMillis();
        if (remainingMillis < 1) { abandoned("lease_budget"); return; }
        Duration budget = Duration.ofMillis(Math.min(settings.requestTimeout().toMillis(), remainingMillis));
        log.atInfo().addKeyValue("event_type", "delivery_attempt_started").log("Delivery attempt started");
        var result = sender.send(input.get().request(), budget);
        // A failed database write leaves the attempt in progress, for UNKNOWN recovery. Never pretend
        // the receiver failed merely because persisting a possibly successful result failed.
        // An already-entered completion transaction may commit during shutdown; its normal
        // token/generation/lease fences still apply. This gate prevents starting a new write
        // after forced cancellation, and never invents a confirmed receiver outcome.
        if (!mayContinue.getAsBoolean()) { abandoned("shutdown"); return; }
        if (claims.complete(claim, result)) {
            log.atInfo().addKeyValue("event_type", "completion_committed")
                    .addKeyValue("outcome", result.outcome().name())
                    .addKeyValue("http_status", result.httpStatus())
                    .addKeyValue("failure_code", result.failureCode())
                    .addKeyValue("duration_ms", result.durationMillis())
                    .log("Delivery attempt completion committed");
        } else {
            log.atInfo().addKeyValue("event_type", "completion_ignored")
                    .log("Delivery attempt completion ignored by fencing");
        }
    }

    private static void abandoned(String reason) {
        log.atInfo().addKeyValue("event_type", "delivery_abandoned").addKeyValue("reason", reason)
                .log("Delivery attempt left for lease recovery");
    }
}
