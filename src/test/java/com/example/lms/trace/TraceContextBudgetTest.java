package com.example.lms.trace;

import com.abandonware.ai.addons.budget.TimeBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class TraceContextBudgetTest {
    @AfterEach void clear() { TraceContext.cleanupCurrentThread(); }

    @Test
    void absentBudgetRemainsUnlimitedAndDoesNotTreatANanoTimestampAsASentinel() {
        TraceContext ctx = TraceContext.current();
        assertNull(ctx.timeBudget());
        assertEquals(Long.MAX_VALUE, ctx.remainingMillis());
        ctx.startWithBudget(Duration.ZERO).startWithBudget(Duration.ofMillis(-1));
        assertNull(ctx.timeBudget());
    }

    @Test
    void tighterCarrierReusesTheExistingMonotonicBudgetWithoutReplenishingIt() {
        TraceContext ctx = TraceContext.current().startWithBudget(Duration.ofSeconds(3));
        TimeBudget first = ctx.timeBudget();
        long remaining = ctx.remainingMillis();
        ctx.tightenBudget(Duration.ofSeconds(5));
        assertSame(first, ctx.timeBudget());
        assertTrue(ctx.remainingMillis() <= remaining);
        ctx.tightenBudget(Duration.ofMillis(600));
        assertNotSame(first, ctx.timeBudget());
        assertTrue(ctx.remainingMillis() > 0 && ctx.remainingMillis() <= 600);
    }

    @Test
    void expiredBudgetCannotBeReopenedByTightening() {
        TraceContext ctx = TraceContext.current().startWithBudget(Duration.ofNanos(1));
        assertEquals(0, ctx.remainingMillis());
        TimeBudget exhausted = ctx.timeBudget();
        ctx.tightenBudget(Duration.ofSeconds(3));
        assertSame(exhausted, ctx.timeBudget());
        assertEquals(0, ctx.remainingMillis());
    }
}
