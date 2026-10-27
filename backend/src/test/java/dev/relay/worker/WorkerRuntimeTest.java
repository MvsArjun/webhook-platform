package dev.relay.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import dev.relay.delivery.ClaimedDelivery;
import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Timeout(10)
class WorkerRuntimeTest {
    private final DeliveryClaimService claims = mock(DeliveryClaimService.class);
    private final DeliveryProcessor processor = mock(DeliveryProcessor.class);
    private final ClaimedDelivery claim = new ClaimedDelivery(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now().plusSeconds(30));
    private WorkerRuntime runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null) runtime.stop();
    }

    @Test
    void admittedWorkDrainsBeforeTheShutdownCallbackAndRepeatedStartDoesNotDuplicateIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch callback = new CountDownLatch(1);
        AtomicReference<BooleanSupplier> gate = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean completedWithOpenGate = new AtomicBoolean();
        singleClaim();
        doAnswer(invocation -> {
            gate.set(invocation.getArgument(1));
            entered.countDown();
            try { await(finish); }
            catch (InterruptedException failure) { interrupted.set(true); throw failure; }
            completedWithOpenGate.set(gate.get().getAsBoolean());
            return null;
        }).when(processor).process(eq(claim), any(BooleanSupplier.class));
        runtime = runtime(1, Duration.ofSeconds(2));
        try {
            runtime.start();
            runtime.start();
            await(entered);
            runtime.stop(callback::countDown);
            assertThat(runtime.isRunning()).isFalse();
            assertThat(callback.getCount()).isEqualTo(1);
            assertThat(gate.get().getAsBoolean()).isTrue();
            assertThat(interrupted).isFalse();
            finish.countDown();
            await(callback);
            assertThat(completedWithOpenGate).isTrue();
            assertThat(interrupted).isFalse();
            verify(processor).process(eq(claim), any(BooleanSupplier.class));
            verify(processor, never()).process(any(ClaimedDelivery.class));
        } finally { finish.countDown(); }
    }

    @Test
    void deadlineClosesTheGateAndRunsCallbacksEvenWhenAdmittedWorkIgnoresInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        CountDownLatch callback = new CountDownLatch(1);
        AtomicReference<BooleanSupplier> gate = new AtomicReference<>();
        singleClaim();
        doAnswer(invocation -> {
            gate.set(invocation.getArgument(1));
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { interrupted.countDown(); }
            }
            exited.countDown();
            return null;
        }).when(processor).process(eq(claim), any(BooleanSupplier.class));
        runtime = runtime(1, Duration.ofMillis(100));
        try {
            runtime.start();
            await(entered);
            long stoppedAt = System.nanoTime();
            runtime.stop(callback::countDown);
            await(callback);
            assertThat(Duration.ofNanos(System.nanoTime() - stoppedAt)).isLessThan(Duration.ofSeconds(2));
            await(interrupted);
            assertThat(gate.get().getAsBoolean()).isFalse();
            assertThat(exited.getCount()).isEqualTo(1);
            CountDownLatch repeatedCallback = new CountDownLatch(1);
            runtime.stop(repeatedCallback::countDown);
            await(repeatedCallback);
        } finally {
            release.countDown();
            await(exited);
        }
    }

    @Test
    void claimResultArrivingAfterStopIsNeverAdmitted() throws Exception {
        CountDownLatch claiming = new CountDownLatch(1);
        CountDownLatch returnClaim = new CountDownLatch(1);
        CountDownLatch claimReturned = new CountDownLatch(1);
        CountDownLatch callback = new CountDownLatch(1);
        when(claims.claim(anyInt(), any(Duration.class))).thenAnswer(invocation -> {
            claiming.countDown();
            awaitUninterruptibly(returnClaim);
            claimReturned.countDown();
            return List.of(claim);
        });
        runtime = runtime(1, Duration.ofMillis(100));
        try {
            runtime.start();
            await(claiming);
            runtime.stop(callback::countDown);
            await(callback);
            assertThat(runtime.isRunning()).isFalse();
            returnClaim.countDown();
            await(claimReturned);
            // A forbidden late admission would execute immediately on the otherwise empty executor.
            verify(processor, after(150).never()).process(any(ClaimedDelivery.class), any(BooleanSupplier.class));
            verify(processor, never()).process(any(ClaimedDelivery.class));
        } finally { returnClaim.countDown(); }
    }

    @Test
    void concurrentStopCallersEachReceiveExactlyOneCallbackAfterTheSameDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopsReturned = new CountDownLatch(6);
        CountDownLatch callbacks = new CountDownLatch(6);
        AtomicInteger[] counts = new AtomicInteger[6];
        for (int index = 0; index < counts.length; index++) counts[index] = new AtomicInteger();
        singleClaim();
        doAnswer(invocation -> { entered.countDown(); await(release); return null; })
                .when(processor).process(eq(claim), any(BooleanSupplier.class));
        runtime = runtime(1, Duration.ofSeconds(2));
        ExecutorService callers = Executors.newFixedThreadPool(6);
        try {
            runtime.start();
            await(entered);
            for (AtomicInteger count : counts) callers.submit(() -> {
                runtime.stop(() -> { count.incrementAndGet(); callbacks.countDown(); });
                stopsReturned.countDown();
            });
            await(stopsReturned);
            assertThat(callbacks.getCount()).isEqualTo(6);
            release.countDown();
            await(callbacks);
            for (AtomicInteger count : counts) assertThat(count).hasValue(1);
            runtime.stop();
            for (AtomicInteger count : counts) assertThat(count).hasValue(1);
            assertThatThrownBy(runtime::start).isInstanceOf(IllegalStateException.class);
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void stopBeforeStartAndRepeatedStopCompleteWithoutCreatingWorkAndRestartIsRejected() throws Exception {
        runtime = runtime(1, Duration.ofMillis(100));
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        runtime.stop(first::countDown);
        await(first);
        runtime.stop(second::countDown);
        await(second);
        runtime.stop();
        assertThat(runtime.isRunning()).isFalse();
        assertThatThrownBy(runtime::start).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(claims, processor);
    }

    @Test
    void unusedClaimReservationsAreReleasedForTheNextPollWithoutExceedingConcurrency() throws Exception {
        CountDownLatch polls = new CountDownLatch(3);
        when(claims.claim(anyInt(), any(Duration.class))).thenAnswer(invocation -> {
            assertThat(invocation.<Integer>getArgument(0)).isEqualTo(2);
            polls.countDown();
            return List.of();
        });
        runtime = runtime(2, Duration.ofMillis(100));
        runtime.start();
        await(polls);
        runtime.stop();
        verifyNoInteractions(processor);
        verify(claims, atLeast(3)).claim(eq(2), any(Duration.class));
    }

    private void singleClaim() {
        when(claims.claim(anyInt(), any(Duration.class)))
                .thenReturn(List.of(claim)).thenReturn(List.of());
    }

    private WorkerRuntime runtime(int concurrency, Duration grace) {
        WorkerSettings settings = new WorkerSettings(concurrency, Duration.ofMillis(10),
                Duration.ofMillis(100), Duration.ofMillis(50), Duration.ofMillis(50),
                Duration.ofSeconds(5), Duration.ofMillis(100), 20);
        return new WorkerRuntime(claims, processor, settings, new WorkerShutdownSettings(grace));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(3, TimeUnit.SECONDS)).as("expected synchronization point").isTrue();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try { latch.await(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
