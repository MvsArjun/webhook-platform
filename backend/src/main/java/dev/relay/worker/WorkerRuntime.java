package dev.relay.worker;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryProcessor;
import dev.relay.observability.logging.SafeLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@ConditionalOnProperty(name = "relay.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerRuntime implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);
    private final DeliveryClaimService claims;
    private final DeliveryProcessor processor;
    private final WorkerSettings settings;
    private final WorkerShutdownSettings shutdown;
    private final Semaphore capacity;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService poller;
    private final Object lifecycle = new Object();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile State state = State.NEW;
    private volatile boolean abandoning;
    private volatile long shutdownStarted;

    private enum State { NEW, RUNNING, STOPPING, STOPPED }

    public WorkerRuntime(DeliveryClaimService claims, DeliveryProcessor processor, WorkerSettings settings,
                         WorkerShutdownSettings shutdown) {
        this.claims = claims;
        this.processor = processor;
        this.settings = settings;
        this.shutdown = shutdown;
        capacity = new Semaphore(settings.concurrency());
        executor = new ThreadPoolExecutor(settings.concurrency(), settings.concurrency(), 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.concurrency()),
                Thread.ofPlatform().daemon().name("relay-delivery-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        poller = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("relay-poller").factory());
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (state == State.RUNNING) return;
            if (state != State.NEW) throw new IllegalStateException("A stopped worker cannot be restarted");
            state = State.RUNNING;
            poller.scheduleWithFixedDelay(this::poll, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void poll() {
        if (state != State.RUNNING) return;
        try {
            claims.recoverExpired(settings.recoveryBatch());
            if (state != State.RUNNING) return;
            int slots = 0;
            while (slots < settings.concurrency() && capacity.tryAcquire()) slots++;
            if (slots == 0) return;
            int reserved = slots;
            try {
                var batch = claims.claim(slots, settings.lease());
                for (var claim : batch) {
                    // The admission barrier covers only state and executor submission, never SQL/HTTP.
                    // A claim transaction already in progress may return after stop: its unused
                    // claims remain fenced IN_PROGRESS work for normal lease recovery.
                    synchronized (lifecycle) {
                        if (state != State.RUNNING) break;
                        executor.execute(() -> {
                            try (var ignored = SafeLogContext.delivery(claim)) {
                                try { processor.process(claim, this::mayContinue); }
                                catch (Exception failure) {
                                    log.atWarn().addKeyValue("event_type", "delivery_processing_interrupted")
                                            .log("Delivery processing interrupted; unfinished work will recover after lease expiry");
                                }
                            } finally { capacity.release(); }
                        });
                        reserved--;
                    }
                }
            } finally { capacity.release(reserved); }
        } catch (Exception failure) {
            if (state == State.RUNNING) log.atWarn().addKeyValue("event_type", "worker_poll_interrupted")
                    .log("Worker polling interrupted; polling will resume");
        }
    }

    @Override
    public void stop(Runnable callback) {
        beginStop();
        stopped.whenComplete((ignored, failure) -> callback.run());
    }

    @Override
    @PreDestroy
    public void stop() {
        beginStop();
        stopped.join();
    }

    private void beginStop() {
        synchronized (lifecycle) {
            if (state == State.STOPPING || state == State.STOPPED) return;
            shutdownStarted = System.nanoTime();
            state = State.STOPPING;
            // Do not interrupt a claim transaction or an admitted HTTP request during its grace.
            poller.shutdown();
            executor.shutdown();
            Thread.ofPlatform().daemon().name("relay-shutdown").start(this::drain);
        }
    }

    private boolean mayContinue() {
        State current = state;
        return !abandoning && (current == State.RUNNING || current == State.STOPPING
                && System.nanoTime() - shutdownStarted < shutdown.shutdownGrace().toNanos());
    }

    private void drain() {
        boolean drained = false;
        try {
            long deadline = shutdownStarted + shutdown.shutdownGrace().toNanos();
            boolean deliveriesFinished = awaitUntil(executor, deadline);
            boolean pollingFinished = awaitUntil(poller, deadline);
            drained = deliveriesFinished && pollingFinished;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (!drained) {
                abandoning = true;
                log.atWarn().addKeyValue("event_type", "worker_drain_expired")
                        .log("Worker drain ended; unfinished claims will recover after lease expiry");
                executor.shutdownNow();
                poller.shutdownNow();
                // Native DNS/JDBC or application code may ignore interrupts. Bound our wait;
                // daemon pools, continuation gates and database fencing constrain late work.
                long cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                try {
                    awaitUntil(executor, cancellationDeadline);
                    awaitUntil(poller, cancellationDeadline);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            synchronized (lifecycle) { state = State.STOPPED; }
            stopped.complete(null);
        }
    }

    private static boolean awaitUntil(ExecutorService service, long deadline) throws InterruptedException {
        if (service.isTerminated()) return true;
        long remaining = deadline - System.nanoTime();
        return remaining > 0 && service.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    @Override public boolean isRunning() { return state == State.RUNNING; }
}
