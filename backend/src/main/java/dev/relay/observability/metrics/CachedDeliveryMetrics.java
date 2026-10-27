package dev.relay.observability.metrics;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

import dev.relay.delivery.DeliveryStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** All meter getters read this cache; collection never runs SQL on an operator scrape thread. */
public final class CachedDeliveryMetrics implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CachedDeliveryMetrics.class);
    private final MetricsSnapshotRepository repository;
    private final MeterRegistry registry;
    private final MetricsSettings settings;
    private final Clock clock;
    private final LongSupplier ticker;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("relay-metrics").factory());
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(null, 0, Double.NaN));
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private final List<Meter> meters = new ArrayList<>();
    private boolean started;

    private record Cached(DurableMetricsSnapshot snapshot, long refreshedAtNanos, double refreshSuccess) { }

    public CachedDeliveryMetrics(MetricsSnapshotRepository repository, MeterRegistry registry,
                                 MetricsSettings settings, Clock clock) {
        this(repository, registry, settings, clock, System::nanoTime);
    }

    CachedDeliveryMetrics(MetricsSnapshotRepository repository, MeterRegistry registry,
                          MetricsSettings settings, Clock clock, LongSupplier ticker) {
        this.repository = Objects.requireNonNull(repository);
        this.registry = Objects.requireNonNull(registry);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.ticker = Objects.requireNonNull(ticker);
        registerMeters();
    }

    public void start() {
        synchronized (lifecycle) {
            if (closed.get()) throw new IllegalStateException("Closed metrics collection cannot restart");
            if (started) return;
            started = true;
            // One recurring task, fixed delay and a refresh gate prevent queued or overlapping scans.
            scheduler.scheduleWithFixedDelay(this::refreshNow, 0, settings.refreshInterval().toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** Deterministic internal entry point; deliberately not exposed as an HTTP operation. */
    public boolean refreshNow() {
        if (closed.get() || !refreshing.compareAndSet(false, true)) return false;
        try {
            DurableMetricsSnapshot snapshot = Objects.requireNonNull(repository.read(clock.instant()));
            synchronized (lifecycle) {
                if (closed.get()) return false;
                cached.set(new Cached(snapshot, ticker.getAsLong(), 1));
            }
            return true;
        } catch (RuntimeException failure) {
            synchronized (lifecycle) {
                if (closed.get()) return false;
                var prior = cached.get();
                cached.set(new Cached(prior.snapshot(), prior.refreshedAtNanos(), 0));
            }
            // Persistence errors may include raw row values. Do not log a throwable or its message.
            log.warn("Durable metrics refresh failed; retaining the last successful snapshot");
            return false;
        } finally {
            refreshing.set(false);
        }
    }

    private void registerMeters() {
        for (var status : DeliveryStatus.values()) {
            gauge("relay.deliveries", "Retained delivery rows by current state; durable snapshot gauge, not a counter",
                    snapshot -> snapshot.deliveries().get(status), "status", status.name());
        }
        for (var outcome : AttemptOutcome.values()) {
            gauge("relay.attempts", "Retained attempt rows by outcome; durable snapshot gauge, not a counter",
                    snapshot -> snapshot.attempts().get(outcome), "outcome", outcome.name());
        }
        gauge("relay.queue.ready", "Unpaused due or pending delivery rows with attempt budget; capacity is not guaranteed",
                DurableMetricsSnapshot::readyDepth);
        timeGauge("relay.queue.ready.oldest.event.age", "Age at refresh of the oldest accepted ready event; not current-run queue waiting time",
                DurableMetricsSnapshot::oldestReadyEventAgeMillis);
        gauge("relay.attempts.automatic.retry", "Retained claims with run attempt greater than one; snapshot gauge",
                DurableMetricsSnapshot::automaticRetryAttempts);
        gauge("relay.attempts.manual.replay", "Retained first claims of replay runs; snapshot gauge",
                DurableMetricsSnapshot::manualReplayFirstClaims);
        gauge("relay.delivery.initial.queue.delay.samples", "Retained lifetime first claims contributing an accepted-to-first-start sample",
                snapshot -> snapshot.initialQueueDelayMillis().samples());
        timeGauge("relay.delivery.initial.queue.delay.mean", "Mean accepted-to-lifetime-first-claim start delay among retained samples",
                snapshot -> snapshot.initialQueueDelayMillis().mean());
        timeGauge("relay.delivery.initial.queue.delay.max", "Maximum accepted-to-lifetime-first-claim start delay among retained samples",
                snapshot -> snapshot.initialQueueDelayMillis().max());
        gauge("relay.delivery.transport.latency.samples", "Retained finished attempts with persisted transport duration; UNKNOWN without duration is excluded",
                snapshot -> snapshot.transportLatencyMillis().samples());
        timeGauge("relay.delivery.transport.latency.mean", "Mean persisted transport duration among retained finished attempts",
                snapshot -> snapshot.transportLatencyMillis().mean());
        timeGauge("relay.delivery.transport.latency.max", "Maximum persisted transport duration among retained finished attempts",
                snapshot -> snapshot.transportLatencyMillis().max());
        meters.add(Gauge.builder("relay.metrics.refresh.success", this, value -> value.cached.get().refreshSuccess())
                .description("Last attempted refresh succeeded (1) or failed (0); NaN before any attempt").register(registry));
        meters.add(TimeGauge.builder("relay.metrics.snapshot.age", this, TimeUnit.MILLISECONDS, value -> {
            var current = value.cached.get();
            return current.snapshot() == null ? Double.NaN
                    : Math.max(0L, value.ticker.getAsLong() - current.refreshedAtNanos()) / 1_000_000.0;
        }).description("Monotonic elapsed time since the last successful refresh; NaN before any good snapshot").register(registry));
    }

    private double dataValue(ToDoubleFunction<DurableMetricsSnapshot> value) {
        var snapshot = cached.get().snapshot();
        return snapshot == null ? Double.NaN : value.applyAsDouble(snapshot);
    }

    private void gauge(String name, String description, ToDoubleFunction<DurableMetricsSnapshot> value, String... tags) {
        meters.add(Gauge.builder(name, this, collector -> collector.dataValue(value))
                .description(description).tags(tags).register(registry));
    }

    private void timeGauge(String name, String description, ToDoubleFunction<DurableMetricsSnapshot> value) {
        meters.add(TimeGauge.builder(name, this, TimeUnit.MILLISECONDS, collector -> collector.dataValue(value))
                .description(description).register(registry));
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) return;
        }
        scheduler.shutdownNow();
        try { scheduler.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        // A slow native JDBC call can outlive this wait, but cannot publish or start a later scan.
        meters.forEach(registry::remove);
    }
}
