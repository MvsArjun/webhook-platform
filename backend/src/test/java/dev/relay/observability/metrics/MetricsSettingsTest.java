package dev.relay.observability.metrics;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsSettingsTest {
    @Test
    void bindsTheDefaultAndExactBoundaries() {
        var context = new ApplicationContextRunner().withUserConfiguration(SettingsOnly.class);
        context.run(application -> assertThat(application.getBean(MetricsSettings.class).refreshInterval()).isEqualTo(Duration.ofSeconds(5)));
        for (String interval : List.of("1s", "60s")) {
            context.withPropertyValues("relay.operations.metrics.refresh-interval=" + interval).run(application -> {
                assertThat(application).hasNotFailed();
                assertThat(application.getBean(MetricsSettings.class).refreshInterval()).isEqualTo(Duration.parse("PT" + interval.toUpperCase()));
            });
        }
    }

    @Test
    void rejectsUnboundedOrSubsecondCollectionIntervalsWithoutRounding() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(999), Duration.ofSeconds(60).plusNanos(1), Duration.ofDays(1))) {
            assertThatThrownBy(() -> new MetricsSettings(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new MetricsSettings(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledOperationsAndWorkerProfilesDoNotConstructCollectorsOrRequireARegistry() {
        var context = new ApplicationContextRunner().withUserConfiguration(DurableMetricsConfiguration.class);
        context.run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(CachedDeliveryMetrics.class));
        context.withPropertyValues("relay.operations.enabled=true").withInitializer(application ->
                application.getEnvironment().setActiveProfiles("worker")).run(application ->
                assertThat(application).hasNotFailed().doesNotHaveBean(CachedDeliveryMetrics.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MetricsSettings.class)
    static class SettingsOnly { }
}
