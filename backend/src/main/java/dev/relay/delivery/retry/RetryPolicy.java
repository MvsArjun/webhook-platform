package dev.relay.delivery.retry;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Set;
import java.util.function.DoubleSupplier;

import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryResult;

/** Platform backoff only; HTTP Retry-After and transport-level retries are deliberately not used. */
public final class RetryPolicy {
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 429, 500, 502, 503, 504);
    private static final Set<String> RETRYABLE_FAILURE = Set.of("REQUEST_TIMEOUT", "CONNECT_TIMEOUT",
            "DNS_ERROR", "NETWORK_ERROR", "RESOURCE_LIMIT");

    private final RetrySettings settings;
    private final Clock clock;
    private final DoubleSupplier jitter;

    public RetryPolicy(RetrySettings settings, Clock clock, DoubleSupplier jitter) {
        if (settings == null || clock == null || jitter == null) {
            throw new IllegalArgumentException("Retry settings, scheduling clock and jitter are required");
        }
        this.settings = settings;
        this.clock = clock;
        this.jitter = jitter;
    }

    public RetrySettings settings() {
        return settings;
    }

    /** Scheduling/eligibility clock only. Claim leases continue to use PostgreSQL's clock. */
    public Instant now() {
        return clock.instant();
    }

    public boolean retryable(DeliveryResult result) {
        if (result == null || result.outcome() != CompletionOutcome.FAILED) {
            return false;
        }
        if ("HTTP_STATUS".equals(result.failureCode())) {
            return result.httpStatus() != null && RETRYABLE_STATUS.contains(result.httpStatus());
        }
        return RETRYABLE_FAILURE.contains(result.failureCode());
    }

    /**
     * The first used attempt receives base delay, then capped exponential backoff. Equal jitter
     * uses inclusive integer-millisecond bounds ceil(delay/2)..delay, keeping even 1ms positive.
     * Callers enforce the attempt budget; UNKNOWN recovery uses this same schedule.
     */
    public Instant nextAttemptAt(int attemptsUsed) {
        if (attemptsUsed < 1) {
            throw new IllegalArgumentException("Attempts used must be positive");
        }
        long cap = settings.maxDelay().toMillis();
        long delay = settings.baseDelay().toMillis();
        int exponent = attemptsUsed - 1;
        while (exponent-- > 0 && delay < cap) {
            delay = delay > cap / 2 ? cap : delay * 2;
        }
        double sample = jitter.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample > 1) {
            throw new IllegalArgumentException("Retry jitter must be between zero and one");
        }
        long minimum = (delay + 1) / 2;
        long span = delay - minimum;
        long offset = Math.min(span, (long) Math.floor(sample * (span + 1)));
        try {
            return now().plusMillis(minimum + offset);
        } catch (DateTimeException | ArithmeticException overflow) {
            return Instant.MAX;
        }
    }
}
