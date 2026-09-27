package com.example.lms.harmony;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarmonySseRuntimeTest {

    @Test
    void thirtyThirdStreamIsRejectedAndEveryCloseReleasesOnce() {
        RuntimeFixture fixture = runtime(32);
        List<HarmonySseRuntime.StreamLease> leases = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            leases.add(fixture.runtime.open(() -> {
            }).orElseThrow());
        }

        assertEquals(32, fixture.runtime.activeCount());
        assertTrue(fixture.runtime.open(() -> {
        }).isEmpty());

        leases.get(0).close();
        HarmonySseRuntime.StreamLease replacement = fixture.runtime.open(() -> {
        }).orElseThrow();
        leases.forEach(HarmonySseRuntime.StreamLease::close);
        replacement.close();
        replacement.close();

        assertEquals(0, fixture.runtime.activeCount());
        verify(fixture.futures.get(0), times(1)).cancel(false);
        verify(fixture.futures.get(32), times(1)).cancel(false);
    }

    @Test
    void tickFailureClosesLeaseAndReturnsCapacity() {
        RuntimeFixture fixture = runtime(1);
        fixture.runtime.open(() -> {
            throw new IllegalStateException("controlled");
        }).orElseThrow();
        ScheduledFuture<?> future = fixture.futures.get(0);

        fixture.ticks.get(0).run();

        assertEquals(0, fixture.runtime.activeCount());
        verify(future, times(1)).cancel(false);
        HarmonySseRuntime.StreamLease replacement = fixture.runtime.open(() -> {
        }).orElseThrow();
        replacement.close();
        assertEquals(0, fixture.runtime.activeCount());
    }

    @Test
    void tickFailureBeforeFutureAttachCancelsReturnedFutureExactlyOnce() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);
        AtomicInteger schedules = new AtomicInteger();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(30L), eq(SECONDS)))
                .thenAnswer(invocation -> {
                    if (schedules.getAndIncrement() == 0) {
                        invocation.<Runnable>getArgument(0).run();
                        return firstFuture;
                    }
                    return secondFuture;
                });
        HarmonySseRuntime runtime = new HarmonySseRuntime(scheduler, 1);

        runtime.open(() -> {
            throw new IllegalStateException("controlled before attach");
        }).orElseThrow();

        assertEquals(0, runtime.activeCount());
        verify(firstFuture, times(1)).cancel(false);
        HarmonySseRuntime.StreamLease replacement = runtime.open(() -> {
        }).orElseThrow();
        replacement.close();
        assertEquals(0, runtime.activeCount());
        verify(secondFuture, times(1)).cancel(false);
    }

    @Test
    void shutdownClosesAllLeasesRejectsNewStreamsAndIsIdempotent() {
        RuntimeFixture fixture = runtime(2);
        HarmonySseRuntime.StreamLease first = fixture.runtime.open(() -> {
        }).orElseThrow();
        HarmonySseRuntime.StreamLease second = fixture.runtime.open(() -> {
        }).orElseThrow();

        fixture.runtime.shutdown();
        fixture.runtime.shutdown();
        first.close();
        second.close();

        assertEquals(0, fixture.runtime.activeCount());
        assertTrue(fixture.runtime.open(() -> {
        }).isEmpty());
        verify(fixture.futures.get(0), times(1)).cancel(false);
        verify(fixture.futures.get(1), times(1)).cancel(false);
        verify(fixture.scheduler, times(1)).shutdownNow();
    }

    @Test
    void shutdownThatWinsDuringSchedulingReturnsEmptyInsteadOfClosedLease() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AtomicReference<HarmonySseRuntime> runtimeRef = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(30L), eq(SECONDS)))
                .thenAnswer(invocation -> {
                    runtimeRef.get().shutdown();
                    return future;
                });
        HarmonySseRuntime runtime = new HarmonySseRuntime(scheduler, 1);
        runtimeRef.set(runtime);

        assertTrue(runtime.open(() -> {
        }).isEmpty());

        assertEquals(0, runtime.activeCount());
        verify(future, times(1)).cancel(false);
        verify(scheduler, times(1)).shutdownNow();
    }

    @Test
    void schedulerRejectionReturnsEmptyAndDoesNotLeakPermit() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(30L), eq(SECONDS)))
                .thenThrow(new RejectedExecutionException("controlled"));
        HarmonySseRuntime runtime = new HarmonySseRuntime(scheduler, 1);

        assertTrue(runtime.open(() -> {
        }).isEmpty());
        assertEquals(0, runtime.activeCount());
    }

    private static RuntimeFixture runtime(int capacity) {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        List<Runnable> ticks = new ArrayList<>();
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(30L), eq(SECONDS)))
                .thenAnswer(invocation -> {
                    ticks.add(invocation.getArgument(0));
                    ScheduledFuture<?> future = mock(ScheduledFuture.class);
                    futures.add(future);
                    return future;
                });
        return new RuntimeFixture(new HarmonySseRuntime(scheduler, capacity), scheduler, ticks, futures);
    }

    private record RuntimeFixture(
            HarmonySseRuntime runtime,
            ScheduledExecutorService scheduler,
            List<Runnable> ticks,
            List<ScheduledFuture<?>> futures) {
    }
}
