package com.example.infra.cache;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;


import static org.junit.jupiter.api.Assertions.*;

public class SingleFlightExecutorTest {
    @Test
    void coalesceConcurrentSameKey_callsSupplierOnce() throws Exception {
        var props = new SingleFlightExecutor.SingleFlightProps(true, 1000);
        var sf = new SingleFlightExecutor(props);
        AtomicInteger calls = new AtomicInteger();
        Callable<String> supplier = () -> { calls.incrementAndGet(); Thread.sleep(150); return "VALUE"; };

        ExecutorService es = Executors.newFixedThreadPool(2);
        Callable<String> task = () -> sf.run("k1", supplier);
        Future<String> f1 = es.submit(task);
        Future<String> f2 = es.submit(task);

        assertEquals("VALUE", f1.get());
        assertEquals("VALUE", f2.get());
        assertEquals(1, calls.get());
        es.shutdownNow();
    }

    @Test
    void disabled_runsSupplierIndependently() throws Exception {
        var sf = new SingleFlightExecutor(new SingleFlightExecutor.SingleFlightProps(false, 1000));
        AtomicInteger calls = new AtomicInteger();
        String v1 = sf.run("k", () -> { calls.incrementAndGet(); return "A"; });
        String v2 = sf.run("k", () -> { calls.incrementAndGet(); return "B"; });
        assertEquals("A", v1);
        assertEquals("B", v2);
        assertEquals(2, calls.get());
    }

    @Test
    void exception_doesNotPoisonFuture_subsequentCallSucceeds() throws Exception {
        var sf = new SingleFlightExecutor(new SingleFlightExecutor.SingleFlightProps(true, 200));
        assertThrows(ExecutionException.class, () -> { sf.run("k", () -> { throw new IllegalStateException("boom"); }); });
        String ok = sf.run("k", () -> "OK");
        assertEquals("OK", ok);
    }
}