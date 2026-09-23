package com.example.lms.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextConcurrencyTest {

    @AfterEach
    void cleanup() {
        TraceContext.cleanupCurrentThread();
        MDC.clear();
    }

    @Test
    void currentContextDoesNotCrossThreadsAndCloseRestoresRoot() throws Exception {
        TraceContext.cleanupCurrentThread();
        MDC.clear();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(task("sid-one", "trace-one", ready, release));
            Future<String> second = pool.submit(task("sid-two", "trace-two", ready, release));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();

            assertEquals("sid-one", first.get(5, TimeUnit.SECONDS));
            assertEquals("sid-two", second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void traceContextDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/TraceContext.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "TraceContext needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    private static Callable<String> task(String sid, String trace, CountDownLatch ready, CountDownLatch release) {
        return () -> {
            TraceContext root = TraceContext.current();
            try (TraceContext ctx = TraceContext.attach(sid, trace)) {
                ctx.setFlag("sid", sid);
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));

                assertSame(ctx, TraceContext.current());
                assertEquals(sid, MDC.get("sid"));
                assertEquals(sid, MDC.get("sessionId"));
                assertEquals(trace, MDC.get("trace"));
                assertEquals(trace, MDC.get("traceId"));
                assertEquals(sid, TraceContext.current().getFlag("sid"));
            }
            assertSame(root, TraceContext.current());
            return sid;
        };
    }
}
