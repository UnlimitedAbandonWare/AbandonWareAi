package com.abandonware.ai.addons.budget;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TimeBudgetMonotonicTest {
    @Test
    void providerLimitIsCappedByElapsedRequestTime() {
        AtomicLong clock = new AtomicLong(-TimeUnit.SECONDS.toNanos(10));
        TimeBudget budget = new TimeBudget(1_500, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(900));
        assertEquals(900, budget.elapsedMillis());
        assertEquals(600, budget.remainingMillis());
        assertEquals(600, budget.capWaitMillis(1_000));
        assertEquals(250, budget.capWaitMillis(250));
    }

    @Test
    void expiredDeadlineDoesNotGrantTheConstructorMinimumAgain() {
        AtomicLong clock = new AtomicLong(-100);
        TimeBudget budget = TimeBudget.untilNanoDeadline(-101, clock::get);
        assertTrue(budget.expired());
        assertEquals(0, budget.capWaitMillis(900));
        assertEquals(1, new TimeBudget(0, clock::get).remainingMillis());
        assertEquals(1, new TimeBudget(-1, clock::get).remainingMillis());
    }

    @Test
    void absoluteNanoTimeSignAndWrapDoNotChangeElapsedTime() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(5));
        TimeBudget budget = new TimeBudget(20, clock::get);
        long deadline = clock.get() + TimeUnit.MILLISECONDS.toNanos(20);
        TimeBudget adapter = TimeBudget.untilNanoDeadline(deadline, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(12));
        assertTrue(clock.get() < 0);
        assertEquals(8, budget.remainingMillis());
        assertEquals(8, adapter.remainingMillis());
        assertEquals(12, budget.elapsedMillis());
    }

    @Test
    void submillisecondRemainderCannotBecomeAnUnlimitedIoWait() {
        AtomicLong clock = new AtomicLong();
        TimeBudget budget = new TimeBudget(1, clock::get);
        clock.addAndGet(999_999);
        assertEquals(0, budget.capWaitMillis(1_000));
        assertTrue(budget.expired());
        assertEquals(0, budget.capWaitMillis(0));
        assertEquals(0, budget.capWaitMillis(-1));
    }

    @Test
    void independentRequestsDoNotReplenishEachOther() {
        AtomicLong clock = new AtomicLong();
        TimeBudget first = new TimeBudget(1_500, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(900));
        TimeBudget second = new TimeBudget(1_500, clock::get);
        assertEquals(600, first.remainingMillis());
        assertEquals(1_500, second.remainingMillis());
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(600));
        assertTrue(first.expired());
        assertEquals(900, second.remainingMillis());
    }
}
