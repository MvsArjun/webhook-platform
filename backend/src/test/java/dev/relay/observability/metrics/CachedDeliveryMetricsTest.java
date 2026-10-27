package dev.relay.observability.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.relay.delivery.DeliveryStatus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Timeout(10)
@ExtendWith(OutputCaptureExtension.class)
class CachedDeliveryMetricsTest {
    private final MetricsSnapshotRepository repository = mock(MetricsSnapshotRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    private final AtomicLong ticker = new AtomicLong();

    @Test
    void unknownAndFailedFirstCollectionNeverInventZeroCountsOrTimings(CapturedOutput output) {
        String secret = "private-metrics-failure-content";
        when(repository.read(any())).thenThrow(new IllegalStateException(secret));
        var registry = new SimpleMeterRegistry();
        try (var metrics = collector(registry)) {
            assertThat(registry.getMeters()).hasSize(21);
            registry.getMeters().forEach(meter -> meter.measure().forEach(value -> assertThat(value.getValue()).isNaN()));
            verifyNoInteractions(repository);
            assertThat(metrics.refreshNow()).isFalse();
            assertThat(registry.get("relay.metrics.refresh.success").gauge().value()).isZero();
            assertThat(registry.get("relay.deliveries").tag("status", "PENDING").gauge().value()).isNaN();
            assertThat(registry.get("relay.metrics.snapshot.age").timeGauge().value(TimeUnit.MILLISECONDS)).isNaN();
            assertThat(output.getAll()).doesNotContain(secret, "IllegalStateException");
        } finally { registry.close(); }
    }

    @Test
    void failedRefreshRetainsDataAndItsOriginalAgeWhileGaugeReadsNeverRunSql(CapturedOutput output) {
        when(repository.read(any())).thenReturn(snapshot(7)).thenThrow(new IllegalStateException("private-row-preview"));
        var registry = new SimpleMeterRegistry();
        try (var metrics = collector(registry)) {
            assertThat(metrics.refreshNow()).isTrue();
            ticker.set(Duration.ofSeconds(2).toNanos());
            assertThat(registry.get("relay.metrics.snapshot.age").timeGauge().value(TimeUnit.MILLISECONDS)).isEqualTo(2000);
            assertThat(metrics.refreshNow()).isFalse();
            ticker.set(Duration.ofSeconds(5).toNanos());
            for (int index = 0; index < 100; index++) {
                assertThat(registry.get("relay.deliveries").tag("status", "PENDING").gauge().value()).isEqualTo(7);
                assertThat(registry.get("relay.metrics.refresh.success").gauge().value()).isZero();
                assertThat(registry.get("relay.metrics.snapshot.age").timeGauge().value(TimeUnit.MILLISECONDS)).isEqualTo(5000);
            }
            verify(repository, times(2)).read(clock.instant());
            assertThat(output.getAll()).doesNotContain("private-row-preview");
        } finally { registry.close(); }
    }

    @Test
    void meterCardinalityAndLabelsStayFiniteAndEmptyTimingSamplesRemainUndefined() {
        when(repository.read(any())).thenReturn(snapshot(0));
        var registry = new SimpleMeterRegistry();
        try (var metrics = collector(registry)) {
            assertThat(metrics.refreshNow()).isTrue();
            assertThat(registry.getMeters()).hasSize(21).allMatch(meter -> meter.getId().getType() == Meter.Type.GAUGE);
            assertThat(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                    .map(tag -> tag.getKey()).distinct().toList()).containsExactlyInAnyOrder("status", "outcome");
            assertThat(registry.find("relay.deliveries").gauges()).hasSize(5);
            assertThat(registry.find("relay.attempts").gauges()).hasSize(4);
            assertThat(registry.get("relay.delivery.transport.latency.samples").gauge().value()).isZero();
            assertThat(registry.get("relay.delivery.transport.latency.mean").timeGauge().value(TimeUnit.MILLISECONDS)).isNaN();
            assertThat(registry.get("relay.delivery.initial.queue.delay.max").timeGauge().value(TimeUnit.MILLISECONDS)).isNaN();
        } finally { registry.close(); }
    }

    @Test
    void blockedCollectionCannotOverlapOrKeepShutdownWaitingOrPublishAfterClose() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        when(repository.read(any())).thenAnswer(invocation -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Native JDBC may ignore thread interruption. */ }
            }
            returned.countDown();
            return snapshot(9);
        });
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = collector(registry);
            var pending = registry.get("relay.deliveries").tag("status", "PENDING").gauge();
            try {
                metrics.start();
                metrics.start();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(metrics.refreshNow()).isFalse();
                long started = System.nanoTime();
                metrics.close();
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                release.countDown();
                assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(metrics.refreshNow()).isFalse();
                assertThat(pending.value()).isNaN();
                assertThat(registry.getMeters()).isEmpty();
                verify(repository).read(clock.instant());
            } finally { release.countDown(); metrics.close(); }
        } finally { registry.close(); }
    }

    private CachedDeliveryMetrics collector(SimpleMeterRegistry registry) {
        return new CachedDeliveryMetrics(repository, registry, new MetricsSettings(Duration.ofSeconds(5)), clock, ticker::get);
    }

    private static DurableMetricsSnapshot snapshot(long pending) {
        var deliveries = new EnumMap<DeliveryStatus, Long>(DeliveryStatus.class);
        for (var status : DeliveryStatus.values()) deliveries.put(status, status == DeliveryStatus.PENDING ? pending : 0L);
        var attempts = new EnumMap<AttemptOutcome, Long>(AttemptOutcome.class);
        for (var outcome : AttemptOutcome.values()) attempts.put(outcome, 0L);
        var empty = new DurableMetricsSnapshot.Statistics(0, Double.NaN, Double.NaN);
        return new DurableMetricsSnapshot(deliveries, attempts, pending, 0, 0, 0, empty, empty);
    }
}
