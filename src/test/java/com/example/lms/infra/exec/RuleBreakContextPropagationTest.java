package com.example.lms.infra.exec;

import com.example.lms.guard.rulebreak.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RuleBreakContextPropagationTest {
    @AfterEach void clear() { RuleBreakContextHolder.clear(); }
    private static RuleBreakContext context(String id) {
        return RuleBreakContext.active(RuleBreakPolicy.SAFE_EXPLORE,"synthetic-hash",
                Instant.now().plusSeconds(60),id,"synthetic-session");
    }
    @Test void reusedWorkerReceivesCopiedContextThenAbsentContextAndRestoresContamination() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            RuleBreakContext a=context("a");RuleBreakContextHolder.set(a);
            Callable<RuleBreakContext> enabled=ContextPropagation.wrapCallable(RuleBreakContextHolder::get);
            a.setActive(false);a.setRequestId("mutated");
            RuleBreakContextHolder.clear();
            Callable<RuleBreakContext> absent=ContextPropagation.wrapCallable(RuleBreakContextHolder::get);
            RuleBreakContext contamination=context("worker");
            pool.submit(()->RuleBreakContextHolder.set(contamination)).get();
            RuleBreakContext seen=pool.submit(enabled).get();
            assertNotNull(seen);assertTrue(seen.isActive());assertEquals("a",seen.getRequestId());assertNotSame(a,seen);
            assertNull(pool.submit(absent).get());
            assertSame(contamination,pool.submit(RuleBreakContextHolder::get).get());
        } finally { pool.shutdownNow(); }
    }
    @Test void runnableRestoresWorkerAfterNestedThrow() {
        RuleBreakContextHolder.set(context("captured"));
        Runnable outer=ContextPropagation.wrap(()->{
            assertEquals("captured",RuleBreakContextHolder.get().getRequestId());
            RuleBreakContextHolder.clear();
            Runnable absent=ContextPropagation.wrap(()->{assertNull(RuleBreakContextHolder.get());throw new IllegalArgumentException("synthetic");});
            RuleBreakContext nested=context("nested");RuleBreakContextHolder.set(nested);
            assertThrows(IllegalArgumentException.class,absent::run);
            assertSame(nested,RuleBreakContextHolder.get());
            throw new IllegalStateException("synthetic");
        });
        RuleBreakContext worker=context("worker");RuleBreakContextHolder.set(worker);
        assertThrows(IllegalStateException.class,outer::run);assertSame(worker,RuleBreakContextHolder.get());
    }
    @Test void supplierCapturesExpiredValuesWithoutExtendingExpiry() {
        RuleBreakContext expired=context("expired");expired.setExpiresAt(Instant.EPOCH);
        RuleBreakContextHolder.set(expired);
        var supplier=ContextPropagation.wrapSupplier(RuleBreakContextHolder::get);
        RuleBreakContextHolder.clear();
        RuleBreakContext seen=supplier.get();assertNotNull(seen);
        assertEquals(Instant.EPOCH,seen.getExpiresAt());assertFalse(seen.isValid());assertNull(RuleBreakContextHolder.get());
    }
    @Test void interruptCancellationRestoresWorkerBeforeReuse() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1), never=new CountDownLatch(1);
        try {
            RuleBreakContextHolder.set(context("cancelled"));
            Runnable wrapped=ContextPropagation.wrap(()->{
                assertEquals("cancelled",RuleBreakContextHolder.get().getRequestId());entered.countDown();
                try {never.await();} catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            RuleBreakContextHolder.clear();
            Future<?> future=pool.submit(wrapped);assertTrue(entered.await(2,TimeUnit.SECONDS));
            future.cancel(true);
            assertNull(pool.submit(RuleBreakContextHolder::get).get(2,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
}
