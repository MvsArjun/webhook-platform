package dev.relay.worker;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerShutdownSettingsTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SettingsConfiguration.class);

    @Test
    void defaultsToTenSecondsAndBindsBothInclusiveBounds() {
        context.run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(WorkerShutdownSettings.class);
            assertThat(application.getBean(WorkerShutdownSettings.class).shutdownGrace())
                    .isEqualTo(Duration.ofSeconds(10));
        });
        context.withPropertyValues("relay.worker.shutdown-grace=100ms").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(WorkerShutdownSettings.class).shutdownGrace())
                    .isEqualTo(Duration.ofMillis(100));
        });
        context.withPropertyValues("relay.worker.shutdown-grace=20s").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(WorkerShutdownSettings.class).shutdownGrace())
                    .isEqualTo(Duration.ofSeconds(20));
        });
    }

    @Test
    void rejectsMissingUnboundedOrOutOfRangeGraceWithoutRounding() {
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1),
                Duration.ofMillis(100).minusNanos(1), Duration.ofSeconds(20).plusNanos(1),
                Duration.ofDays(1)}) {
            assertThatThrownBy(() -> new WorkerShutdownSettings(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        context.withPropertyValues("relay.worker.shutdown-grace=99ms")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("relay.worker.shutdown-grace=21s")
                .run(application -> assertThat(application).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkerShutdownSettings.class)
    static class SettingsConfiguration {}
}
