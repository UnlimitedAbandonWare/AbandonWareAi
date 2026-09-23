package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Zero100SessionAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void zero100SessionTraceStoresMpIntentDiagnosticsWithoutRawQuery() throws Throwable {
        Zero100EngineProperties props = new Zero100EngineProperties();
        Zero100SessionAspect aspect = new Zero100SessionAspect(
                props,
                new Zero100SessionRegistry(props),
                new MockEnvironment());
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("search.zero100.enabled", true);
        String rawQuery = "raw zero100 query should never be written to TraceStore";
        ctx.setUserQuery(rawQuery);
        GuardContextHolder.set(ctx);

        Object out = aspect.aroundChatEntry(new FakePjp("ok", rawQuery));

        assertEquals("ok", out);
        assertNull(TraceStore.get("zero100.mpIntent"));
        assertEquals(Boolean.TRUE, TraceStore.get("zero100.mpIntent.present"));
        assertEquals("33-128", TraceStore.get("zero100.mpIntent.lengthBucket"));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("zero100.mpIntent.hash12"));
        assertFalse(TraceStore.getAll().containsValue(rawQuery));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
        assertTrue(Boolean.TRUE.equals(TraceStore.get("zero100.enabled")));
        assertTrue(TraceStore.get("zero100.branch.callRatios") instanceof java.util.Map<?, ?>);
        assertTrue(TraceStore.get("zero100.branch.timeboxMs") instanceof java.util.Map<?, ?>);
        assertEquals(Boolean.TRUE, TraceStore.get("zero100.riskConsensus.enabled"));
        assertEquals(2, TraceStore.get("zero100.riskConsensus.minLaneCoverage"));
        assertEquals(0.45d, TraceStore.get("zero100.riskConsensus.riskPenaltyLambda"));
    }

    @Test
    void zero100SessionTraceStoresSessionHashOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"));

        assertFalse(source.contains("TraceStore.put(\"zero100.sessionId\", sid);"));
        assertTrue(source.contains("TraceStore.put(\"zero100.sessionId\", SafeRedactor.hashValue(sid));"));
        assertFalse(source.contains("TraceStore.put(\"zero100.scheduler.failureClass\", t.getClass().getSimpleName())"));
        assertTrue(source.contains("TraceStore.put(\"zero100.scheduler.failureClass\", \"zero100_scheduler_failed\")"));
    }

    @Test
    void zero100SessionFallbackCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.guardContextLookup\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.sessionIdLookup\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.enabledPlanOverride\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.planIdLookup\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.traceLongParse\", ignored);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.mapDoubleParse\", ignored);"));
    }

    @Test
    void zero100NumericTraceHelpersIgnoreNonFiniteNumbers() throws Exception {
        TraceStore.put("qtx.softCooldown.remainingMs", Double.POSITIVE_INFINITY);
        Long traceLong = ReflectionTestUtils.invokeMethod(Zero100SessionAspect.class, "traceLong",
                "qtx.softCooldown.remainingMs", 37L);
        assertEquals(37L, traceLong);

        Double mapDouble = ReflectionTestUtils.invokeMethod(Zero100SessionAspect.class, "mapDouble",
                Map.of("score", Double.NaN), "score", 0.42d);
        assertEquals(0.42d, mapDouble);
    }

    @Test
    void zero100WebTimeboxTraceStoresRequestHashOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/Zero100WebTimeboxAspect.java"));

        assertFalse(source.contains("TraceStore.put(\"zero100.webTimebox.rid\", LogCorrelation.requestId())"));
        assertTrue(source.contains(
                "TraceStore.put(\"zero100.webTimebox.rid\", SafeRedactor.hashValue(LogCorrelation.requestId()))"));
    }

    @Test
    void zero100WebTimeboxTreatsSerpApiAndTavilyRateLimitsAsAlreadyLimited() {
        Zero100EngineProperties props = new Zero100EngineProperties();
        props.setWebCallTimeboxMs(2500L);
        props.setWebCallTimeboxMsWhenRateLimited(650L);
        Zero100WebTimeboxAspect aspect = new Zero100WebTimeboxAspect(
                props,
                new Zero100SessionRegistry(props),
                null);

        TraceStore.put("web.serpapi.skipped.reason", "rate_limit");
        Long serpApiTimebox = ReflectionTestUtils.invokeMethod(aspect, "resolveTimeboxMs");
        TraceStore.clear();
        TraceStore.put("web.tavily.skipped.reason", "cooldown");
        Long tavilyTimebox = ReflectionTestUtils.invokeMethod(aspect, "resolveTimeboxMs");

        assertEquals(650L, serpApiTimebox);
        assertEquals(650L, tavilyTimebox);
    }

    @Test
    void zero100WebTimeboxDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/Zero100WebTimeboxAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Zero100 web timebox needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void zero100SessionLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void zero100TimeoutReturnsBeforeWorkerMutatesSharedRequestTrace() throws Throwable {
        var worker = (java.util.concurrent.ThreadPoolExecutor)
                java.util.concurrent.Executors.newFixedThreadPool(1);
        worker.prestartAllCoreThreads();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var workerStartedAt = new java.util.concurrent.atomic.AtomicLong();
        var workerFinishedAt = new java.util.concurrent.atomic.AtomicLong();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var sameTrace = new java.util.concurrent.atomic.AtomicBoolean();
        var sameGuard = new java.util.concurrent.atomic.AtomicBoolean();
        var mdcPropagated = new java.util.concurrent.atomic.AtomicBoolean();
        TraceStore.put("zero100.enabled", true);
        Map<String, Object> callerTrace = TraceStore.context();
        GuardContext callerGuard = new GuardContext();
        GuardContextHolder.set(callerGuard);
        org.slf4j.MDC.put("test.r03.marker", "synthetic");
        try {
            Zero100EngineProperties props = new Zero100EngineProperties();
            props.setEngineEnabled(true);
            props.setWebCallTimeboxMs(200L);
            var beanFactory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            beanFactory.registerSingleton("r03OwnedExecutor", worker);
            Zero100WebTimeboxAspect aspect = new Zero100WebTimeboxAspect(props,
                    new Zero100SessionRegistry(props),
                    beanFactory.getBeanProvider(java.util.concurrent.ExecutorService.class));
            ProceedingJoinPoint pjp = org.mockito.Mockito.mock(ProceedingJoinPoint.class);
            org.mockito.Mockito.when(pjp.proceed()).thenAnswer(invocation -> {
                sameTrace.set(TraceStore.context() == callerTrace);
                sameGuard.set(GuardContextHolder.get() == callerGuard);
                mdcPropagated.set("synthetic".equals(org.slf4j.MDC.get("test.r03.marker")));
                workerStartedAt.set(System.nanoTime());
                started.countDown();
                try {
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    TraceStore.put("test.r03.lateWorker.count", 1L);
                    GuardContextHolder.get().putPlanOverride("test.r03.lateWorker.count", 1L);
                    return List.of("synthetic-late-result");
                } catch (InterruptedException unexpected) {
                    interrupted.set(true);
                    throw unexpected;
                } finally {
                    workerFinishedAt.set(System.nanoTime());
                    finished.countDown();
                }
            });
            long callStartedAt = System.nanoTime();
            Object returned = aspect.aroundHybridSearch(pjp);
            long returnedAt = System.nanoTime();
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(workerStartedAt.get() < returnedAt);
            assertEquals(List.of(), returned);
            assertEquals(1L, release.getCount());
            assertFalse(finished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            assertFalse(interrupted.get());
            assertEquals(Boolean.TRUE, TraceStore.get("zero100.webTimebox.hit"));
            assertEquals(200L, ((Number) TraceStore.get("zero100.webTimebox.ms")).longValue());
            assertEquals(1L, ((Number) TraceStore.get("zero100.webTimebox.timeout.count")).longValue());
            assertNull(TraceStore.get("test.r03.lateWorker.count"));
            assertEquals(0L, callerGuard.planLong("test.r03.lateWorker.count", 0L));
            Map<String, Object> snapshotAtFallback = Map.copyOf(TraceStore.getAll());
            release.countDown();
            assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(workerFinishedAt.get() > returnedAt);
            assertEquals(1L, TraceStore.get("test.r03.lateWorker.count"));
            assertEquals(1L, callerGuard.planLong("test.r03.lateWorker.count", 0L));
            assertFalse(snapshotAtFallback.containsKey("test.r03.lateWorker.count"));
            assertTrue(sameTrace.get());
            assertTrue(sameGuard.get());
            assertTrue(mdcPropagated.get());
            assertFalse(interrupted.get());
            assertTrue(worker.submit(() -> TraceStore.getAll().isEmpty()
                    && GuardContextHolder.get() == null
                    && org.slf4j.MDC.get("test.r03.marker") == null).get(2,
                            java.util.concurrent.TimeUnit.SECONDS));
            org.mockito.Mockito.verify(pjp, org.mockito.Mockito.times(1)).proceed();
            worker.shutdown();
            assertTrue(worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            System.out.printf("R03_TIMEOUT fallbackMs=%d workerExitMs=%d lateTrace=1 lateGuard=1 "
                            + "snapshotLate=0 interrupted=false sameContext=true workerCleared=true terminated=true%n",
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(returnedAt - callStartedAt),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(workerFinishedAt.get() - callStartedAt));
        } finally {
            release.countDown();
            worker.shutdown();
            if (!worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            }
            TraceStore.clear();
            GuardContextHolder.clear();
            org.slf4j.MDC.clear();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "limited_authored,true,650,650,false", "limited_short,true,50,50,true",
            "normal_authored,false,650,2500,false", "normal_short,false,50,2500,false"})
    @org.junit.jupiter.api.Timeout(10)
    void shippedRateLimitedWebTimeboxChangesActualWaitWithAnOwnedWorker(
            String control, boolean limited, long rawTightMs, long expectedMs, boolean timedOut) throws Throwable {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero100.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "search.zero100.webTimeboxMsWhenRateLimited";
        assertEquals(650L, original.path("params").path(key).asLong());
        var modified = original.deepCopy();
        if (rawTightMs != 650L) ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put(key, rawTightMs);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only the raw tight timebox changes within each marker pair");
        assertEquals(2500L, modified.path("params").path("search.zero100.webTimeboxMs").asLong());
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero100.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero100.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var ctx = new GuardContext();
        applier.applyToGuardContext(applier.load("zero100.v1"), ctx);
        assertEquals(rawTightMs, ctx.planLong(key, -1L));
        assertEquals(2500L, ctx.planLong("search.zero100.webTimeboxMs", -1L));
        var props = new Zero100EngineProperties();
        props.setEngineEnabled(true);
        var registry = new Zero100SessionRegistry(props);
        var sessionAspect = new Zero100SessionAspect(props, registry, new MockEnvironment());
        var worker = (java.util.concurrent.ThreadPoolExecutor) java.util.concurrent.Executors.newFixedThreadPool(1);
        worker.prestartAllCoreThreads();
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var elapsedMs = new java.util.concurrent.atomic.AtomicLong();
        var trace = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("tbl07OwnedSearchExecutor", worker);
        var webAspect = new Zero100WebTimeboxAspect(props, registry,
                beans.getBeanProvider(java.util.concurrent.ExecutorService.class));
        ProceedingJoinPoint body = org.mockito.Mockito.mock(ProceedingJoinPoint.class);
        org.mockito.Mockito.when(body.proceed()).thenAnswer(invocation -> {
            started.countDown();
            try {
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return List.of("bounded-timebox-result");
            } catch (InterruptedException unexpected) {
                interrupted.set(true);
                throw unexpected;
            } finally {
                finished.countDown();
            }
        });
        ProceedingJoinPoint entry = org.mockito.Mockito.mock(ProceedingJoinPoint.class);
        org.mockito.Mockito.when(entry.getArgs()).thenReturn(new Object[]{"bounded timebox fixture"});
        org.mockito.Mockito.when(entry.proceed()).thenAnswer(invocation -> {
            assertEquals(Boolean.TRUE, TraceStore.get("zero100.enabled"));
            assertEquals("CALIBRATE", TraceStore.get("zero100.phase"));
            return webAspect.aroundHybridSearch(body);
        });
        try {
            var request = caller.submit(() -> {
                TraceStore.clear();
                GuardContextHolder.set(ctx);
                if (limited) TraceStore.put("web.serpapi.skipped.reason", "rate_limit");
                long began = System.nanoTime();
                try {
                    return sessionAspect.aroundChatEntry(entry);
                } catch (Throwable failure) {
                    throw new AssertionError(failure);
                } finally {
                    elapsedMs.set(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
                    trace.set(new java.util.HashMap<>(TraceStore.getAll()));
                    GuardContextHolder.clear();
                    TraceStore.clear();
                }
            });
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "the actual advised body starts");
            Object result;
            if (timedOut) {
                result = request.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(List.of(), result);
                assertEquals(1L, finished.getCount(), "caller fallback precedes the held worker's completion");
                assertTrue(elapsedMs.get() >= 40L, "the caller actually waits for the short timebox");
            } else {
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> request.get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
                assertEquals(1L, finished.getCount());
                release.countDown();
                result = request.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(List.of("bounded-timebox-result"), result);
                assertTrue(elapsedMs.get() >= 100L);
            }
            assertEquals(timedOut, trace.get().get("zero100.webTimebox.hit"));
            assertEquals(expectedMs, trace.get().get("zero100.webTimebox.ms"));
            assertFalse(interrupted.get());
            release.countDown();
            assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(worker.submit(() -> TraceStore.getAll().isEmpty() && GuardContextHolder.get() == null
                    && (org.slf4j.MDC.getCopyOfContextMap() == null || org.slf4j.MDC.getCopyOfContextMap().isEmpty()))
                    .get(2, java.util.concurrent.TimeUnit.SECONDS));
            org.mockito.Mockito.verify(body, org.mockito.Mockito.times(1)).proceed();
            org.mockito.Mockito.verify(entry, org.mockito.Mockito.times(1)).proceed();
            System.out.printf("TBL07_TIGHT control=%s limited=%s rawTightMs=%d effectiveMs=%d elapsedMs=%d timedOut=%s workerHeldAtFallback=%s interrupted=false workerContextCleared=true bodyCalls=1 externalRequests=0%n",
                    control, limited, rawTightMs, expectedMs, elapsedMs.get(), timedOut, timedOut);
        } finally {
            release.countDown();
            caller.shutdownNow();
            worker.shutdown();
            assertTrue(caller.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            if (!worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        @Override
        public Object proceed() {
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            return result;
        }

        @Override
        public void set$AroundClosure(AroundClosure arc) {
        }

        @Override
        public Object getThis() {
            return this;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return null;
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return "method-execution";
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }

        @Override
        public String toShortString() {
            return "FakePjp";
        }

        @Override
        public String toLongString() {
            return "FakePjp";
        }
    }
}
