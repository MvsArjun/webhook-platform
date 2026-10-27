package dev.relay.observability.metrics;

import java.util.Map;

import dev.relay.delivery.DeliveryStatus;

/** Aggregated retained database rows, never monotonic counters or individual customer records. */
public record DurableMetricsSnapshot(Map<DeliveryStatus, Long> deliveries, Map<AttemptOutcome, Long> attempts,
        long readyDepth, double oldestReadyEventAgeMillis, long automaticRetryAttempts,
        long manualReplayFirstClaims, Statistics initialQueueDelayMillis, Statistics transportLatencyMillis) {
    public DurableMetricsSnapshot {
        deliveries = Map.copyOf(deliveries);
        attempts = Map.copyOf(attempts);
    }

    /** Mean/max are NaN when no retained sample exists; the sample gauge is then zero. */
    public record Statistics(long samples, double mean, double max) { }
}
