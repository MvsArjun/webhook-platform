package dev.relay.delivery.retry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {
    private static final Instant START = Instant.parse("2026-10-24T00:00:00Z");
    private static final RetrySettings DEFAULTS = new RetrySettings(5, Duration.ofSeconds(1), Duration.ofMinutes(5));

    @Test
    void classifiesOnlyTheExplicitTransientStatusesAndFailureCodes() {
        var policy = policy(DEFAULTS, 0);
        for (int status : List.of(408, 429, 500, 502, 503, 504)) {
            assertThat(policy.retryable(failed(status, "HTTP_STATUS"))).as("HTTP %s", status).isTrue();
        }
        for (int status : List.of(301, 400, 401, 403, 404, 409, 410, 422, 501, 505)) {
            assertThat(policy.retryable(failed(status, "HTTP_STATUS"))).as("HTTP %s", status).isFalse();
        }
        for (String code : List.of("REQUEST_TIMEOUT", "CONNECT_TIMEOUT", "DNS_ERROR", "NETWORK_ERROR", "RESOURCE_LIMIT")) {
            assertThat(policy.retryable(failed(null, code))).as(code).isTrue();
        }
        for (String code : List.of("DESTINATION_BLOCKED", "INTERNAL_ERROR", "HTTP_STATUS")) {
            assertThat(policy.retryable(failed(null, code))).as(code).isFalse();
        }
        assertThat(policy.retryable(new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 1))).isFalse();
        assertThat(policy.retryable(null)).isFalse();
    }

    @Test
    void firstFailureUsesBaseThenExponentialDelayBeforeTheCap() {
        var upper = policy(DEFAULTS, 1);
        var lower = policy(DEFAULTS, 0);
        long[] expected = {1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000, 256000, 300000};
        for (int index = 0; index < expected.length; index++) {
            assertThat(upper.nextAttemptAt(index + 1)).isEqualTo(START.plusMillis(expected[index]));
            assertThat(lower.nextAttemptAt(index + 1)).isEqualTo(START.plusMillis((expected[index] + 1) / 2));
        }
        assertThat(upper.nextAttemptAt(Integer.MAX_VALUE)).isEqualTo(START.plusMillis(300000));
    }

    @Test
    void positiveEqualJitterHandlesOddDelaysAndEveryDiscreteBoundary() {
        var tiny = new RetrySettings(20, Duration.ofMillis(3), Duration.ofMillis(7));
        assertThat(policy(tiny, 0).nextAttemptAt(1)).isEqualTo(START.plusMillis(2));
        assertThat(policy(tiny, 1).nextAttemptAt(1)).isEqualTo(START.plusMillis(3));
        assertThat(policy(tiny, 0).nextAttemptAt(2)).isEqualTo(START.plusMillis(3));
        assertThat(policy(tiny, 1).nextAttemptAt(2)).isEqualTo(START.plusMillis(6));
        assertThat(policy(tiny, 0).nextAttemptAt(3)).isEqualTo(START.plusMillis(4));
        assertThat(policy(tiny, 1).nextAttemptAt(3)).isEqualTo(START.plusMillis(7));
        assertThat(policy(DEFAULTS, 0.5).nextAttemptAt(1)).isEqualTo(START.plusMillis(750));
        var minimum = new RetrySettings(1, Duration.ofMillis(1), Duration.ofMillis(1));
        assertThat(policy(minimum, 0).nextAttemptAt(1)).isEqualTo(START.plusMillis(1));
        assertThat(policy(minimum, Math.nextDown(1d)).nextAttemptAt(Integer.MAX_VALUE)).isEqualTo(START.plusMillis(1));
    }

    @Test
    void usesInjectedSchedulingClockOnEveryCallAndSaturatesInstantOverflow() {
        var clock = new MutableClock(START);
        var policy = new RetryPolicy(DEFAULTS, clock, () -> 1);
        assertThat(policy.now()).isEqualTo(START);
        clock.advance(Duration.ofHours(2));
        assertThat(policy.now()).isEqualTo(START.plus(Duration.ofHours(2)));
        assertThat(policy.nextAttemptAt(1)).isEqualTo(START.plus(Duration.ofHours(2)).plusSeconds(1));
        var end = new RetryPolicy(DEFAULTS, Clock.fixed(Instant.MAX.minusMillis(1), ZoneOffset.UTC), () -> 1);
        assertThat(end.nextAttemptAt(Integer.MAX_VALUE)).isEqualTo(Instant.MAX);
    }

    @Test
    void rejectsInvalidAttemptsAndJitterRatherThanSchedulingPastOrUnboundedWork() {
        assertThatThrownBy(() -> policy(DEFAULTS, 0).nextAttemptAt(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(DEFAULTS, 0).nextAttemptAt(-1)).isInstanceOf(IllegalArgumentException.class);
        for (double sample : new double[]{-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> policy(DEFAULTS, sample).nextAttemptAt(1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static RetryPolicy policy(RetrySettings settings, double sample) {
        return new RetryPolicy(settings, Clock.fixed(START, ZoneOffset.UTC), () -> sample);
    }

    private static DeliveryResult failed(Integer status, String code) {
        return new DeliveryResult(CompletionOutcome.FAILED, status, code, 1);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
