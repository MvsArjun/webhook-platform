package dev.relay.delivery.retry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrySettingsTest {
    @Test
    void bindsDefaultsAndDefaultUtcClockWithoutDependingOnWorkerProfile() {
        new ApplicationContextRunner().withUserConfiguration(RetryConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RetrySettings.class).hasSingleBean(RetryPolicy.class).hasSingleBean(Clock.class);
            var settings = context.getBean(RetrySettings.class);
            assertThat(settings.maxAttempts()).isEqualTo(5);
            assertThat(settings.baseDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(settings.maxDelay()).isEqualTo(Duration.ofMinutes(5));
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    void bindsConfiguredBoundsAndKeepsAnInjectedClock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-24T01:00:00Z"), ZoneOffset.UTC);
        new ApplicationContextRunner().withUserConfiguration(RetryConfiguration.class)
                .withBean(Clock.class, () -> fixed)
                .withPropertyValues("relay.retry.max-attempts=20", "relay.retry.base-delay=1ms", "relay.retry.max-delay=24h")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Clock.class);
                    assertThat(context.getBean(Clock.class)).isSameAs(fixed);
                    assertThat(context.getBean(RetryPolicy.class).now()).isEqualTo(fixed.instant());
                    assertThat(context.getBean(RetrySettings.class)).isEqualTo(new RetrySettings(20, Duration.ofMillis(1), Duration.ofHours(24)));
                });
    }

    @Test
    void rejectsEveryConfigurationBoundaryAndReversedDelays() {
        assertInvalid(0, Duration.ofSeconds(1), Duration.ofMinutes(5));
        assertInvalid(21, Duration.ofSeconds(1), Duration.ofMinutes(5));
        assertInvalid(5, null, Duration.ofMinutes(5));
        assertInvalid(5, Duration.ZERO, Duration.ofMinutes(5));
        assertInvalid(5, Duration.ofMillis(1).minusNanos(1), Duration.ofMinutes(5));
        assertInvalid(5, Duration.ofHours(1).plusNanos(1), Duration.ofHours(24));
        assertInvalid(5, Duration.ofSeconds(1), null);
        assertInvalid(5, Duration.ofSeconds(1), Duration.ofMillis(999));
        assertInvalid(5, Duration.ofSeconds(1), Duration.ofHours(24).plusNanos(1));
        assertThat(new RetrySettings(1, Duration.ofHours(1), Duration.ofHours(1)).maxAttempts()).isEqualTo(1);
        new ApplicationContextRunner().withUserConfiguration(RetryConfiguration.class)
                .withPropertyValues("relay.retry.max-attempts=0").run(context -> assertThat(context).hasFailed());
    }

    private static void assertInvalid(int attempts, Duration base, Duration maximum) {
        assertThatThrownBy(() -> new RetrySettings(attempts, base, maximum)).isInstanceOf(IllegalArgumentException.class);
    }
}
