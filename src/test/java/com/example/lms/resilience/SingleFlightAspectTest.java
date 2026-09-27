package com.example.lms.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SingleFlightAspectTest {

    private static final Pattern UUID_VALUE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);

    private final List<SingleFlightManager> managers = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        for (SingleFlightManager manager : managers) {
            try {
                manager.close();
            } catch (Exception ignored) {
                // Best-effort test cleanup.
            }
        }
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void missingEnablePropertyRegistersNeitherManagerNorAspect() {
        contextRunner().run(context -> {
            assertThat(context).doesNotHaveBean(SingleFlightManager.class);
            assertThat(context).doesNotHaveBean(SingleFlightAspect.class);
        });
    }

    @Test
    void explicitEnablePropertyRegistersExactlyOneManagerAndAspect() {
        contextRunner()
                .withPropertyValues(
                        "cache.singleflight.enabled=true",
                        "cache.singleflight.timeout-ms=250",
                        "cache.singleflight.key-strategy=METHOD_AND_ARGS")
                .run(context -> {
                    assertThat(context).hasSingleBean(SingleFlightManager.class);
                    assertThat(context).hasSingleBean(SingleFlightAspect.class);
                });
    }

    @Test
    void pointcutTargetsOnlyWebSearchRetrieverQueryRetrieve() throws Exception {
        Method advice = SingleFlightAspect.class.getDeclaredMethod("dedupe", ProceedingJoinPoint.class);
        Around around = advice.getAnnotation(Around.class);
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(around.value());

        Method retrieve = WebSearchRetriever.class.getMethod("retrieve", Query.class);
        assertTrue(pointcut.matches(retrieve, WebSearchRetriever.class));
        assertFalse(Arrays.stream(NaverSearchService.class.getDeclaredMethods())
                .anyMatch(method -> pointcut.matches(method, NaverSearchService.class)),
                "NaverSearchService methods must remain outside the Single-Flight boundary");
        assertFalse(pointcut.matches(Object.class.getMethod("toString"), WebSearchRetriever.class),
                "unrelated WebSearchRetriever methods must remain outside the boundary");
    }

    @Test
    void sameRequestAndQueryShareOneOwnerAndKeepScopeTokenPrivate() throws Exception {
        SingleFlightManager manager = configuredManager(2_000L, testPool(2, "sf-aspect-owner"));
        SingleFlightAspect aspect = aspect(manager);
        ExecutorService callers = testPool(2, "sf-aspect-caller");
        Map<String, Object> requestContext = new ConcurrentHashMap<>();
        String rawQuery = "private raw query for single flight";
        Query query = QueryUtils.buildQuery(rawQuery, Map.of("purpose", "WEB_SEARCH"));
        AtomicInteger proceedCalls = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);

        try {
            ProceedingJoinPoint firstPjp = blockingJoinPoint(
                    query, proceedCalls, ownerEntered, releaseOwner, "first-result");
            ProceedingJoinPoint secondPjp = blockingJoinPoint(
                    query, proceedCalls, ownerEntered, releaseOwner, "second-result");

            Future<Object> first = callers.submit(() -> invokeInTraceContext(aspect, firstPjp, requestContext));
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS), "owner never entered advice target");
            Future<Object> second = callers.submit(() -> invokeInTraceContext(aspect, secondPjp, requestContext));
            Thread.sleep(50L);
            releaseOwner.countDown();

            assertEquals("first-result", first.get(1, TimeUnit.SECONDS));
            assertEquals("first-result", second.get(1, TimeUnit.SECONDS));
            assertEquals(1, proceedCalls.get(), "same request/query must share one proceed call");

            long internalUuidCount = requestContext.values().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(value -> UUID_VALUE.matcher(value).matches())
                    .count();
            assertEquals(1L, internalUuidCount, "one internal request scope token must be retained");

            TraceStore.installContext(requestContext);
            Map<String, Object> publicTrace = TraceStore.getAll();
            assertFalse(publicTrace.values().stream().map(String::valueOf)
                    .anyMatch(value -> value.contains(rawQuery)), "raw query must not enter public trace state");
            assertFalse(publicTrace.values().stream().map(String::valueOf)
                    .anyMatch(value -> UUID_VALUE.matcher(value).find()),
                    "internal request scope token must not enter public trace state");
        } finally {
            releaseOwner.countDown();
            callers.shutdownNow();
            TraceStore.clear();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void configuredTimeoutBoundsBlockedAdviceAndAllowsSameKeyReadmission() {
        contextRunner()
                .withPropertyValues(
                        "cache.singleflight.enabled=true",
                        "cache.singleflight.timeout-ms=150")
                .run(context -> {
                    assertThat(context).hasSingleBean(SingleFlightManager.class);
                    assertThat(context).hasSingleBean(SingleFlightAspect.class);
                    SingleFlightManager manager = context.getBean(SingleFlightManager.class);
                    SingleFlightAspect aspect = context.getBean(SingleFlightAspect.class);
                    ExecutorService callers = testPool(1, "sf-timeout-caller");
                    Map<String, Object> requestContext = new ConcurrentHashMap<>();
                    Query query = QueryUtils.buildQuery("synthetic timeout query", Map.of("purpose", "WEB_SEARCH"));
                    AtomicInteger proceedCalls = new AtomicInteger();
                    CountDownLatch entered = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch interrupted = new CountDownLatch(1);
                    CountDownLatch exited = new CountDownLatch(1);
                    try {
                        ProceedingJoinPoint blocked = mock(ProceedingJoinPoint.class);
                        when(blocked.getArgs()).thenReturn(new Object[] { query });
                        org.mockito.Mockito.doAnswer(invocation -> {
                            proceedCalls.incrementAndGet();
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("blocked advice exceeded test ceiling");
                                }
                                return "unexpected-release";
                            } catch (InterruptedException cancellation) {
                                interrupted.countDown();
                                throw cancellation;
                            } finally {
                                exited.countDown();
                            }
                        }).when(blocked).proceed();
                        Future<Object> first = callers.submit(() -> invokeInTraceContext(aspect, blocked, requestContext));
                        assertTrue(entered.await(1, TimeUnit.SECONDS), "advice owner did not enter");
                        java.util.concurrent.ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                                java.util.concurrent.ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
                        assertThat(failure.getCause()).isInstanceOf(java.util.concurrent.TimeoutException.class)
                                .hasMessage("Single-Flight timed out after 150 ms");
                        assertEquals(1L, release.getCount(), "caller completed without releasing the target");
                        assertTrue(interrupted.await(1, TimeUnit.SECONDS), "cooperative target did not observe cancellation");
                        assertTrue(exited.await(1, TimeUnit.SECONDS), "cooperative target did not exit");
                        assertEquals(1, proceedCalls.get());

                        ProceedingJoinPoint next = blockingJoinPoint(query, proceedCalls,
                                new CountDownLatch(1), new CountDownLatch(0), "readmitted-result");
                        Future<Object> readmitted = callers.submit(() -> invokeInTraceContext(aspect, next, requestContext));
                        assertEquals("readmitted-result", readmitted.get(2, TimeUnit.SECONDS));
                        assertEquals(2, proceedCalls.get(), "same-key request must execute a new owner after timeout");
                    } finally {
                        release.countDown();
                        manager.close();
                        callers.shutdownNow();
                        assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS), "owned caller pool did not stop");
                    }
                });
    }

    @Test
    void identicalQueriesInDifferentTraceContextsDoNotJoin() throws Exception {
        SingleFlightManager manager = configuredManager(2_000L, testPool(2, "sf-context-split-owner"));
        SingleFlightAspect aspect = aspect(manager);
        ExecutorService callers = testPool(2, "sf-context-split-caller");
        Query query = QueryUtils.buildQuery("same query", Map.of("purpose", "WEB_SEARCH"));
        AtomicInteger proceedCalls = new AtomicInteger();
        CountDownLatch bothOwnersEntered = new CountDownLatch(2);
        CountDownLatch releaseOwners = new CountDownLatch(1);

        try {
            ProceedingJoinPoint firstPjp = blockingJoinPoint(
                    query, proceedCalls, bothOwnersEntered, releaseOwners, "request-a");
            ProceedingJoinPoint secondPjp = blockingJoinPoint(
                    query, proceedCalls, bothOwnersEntered, releaseOwners, "request-b");
            Future<Object> first = callers.submit(() -> invokeInTraceContext(
                    aspect, firstPjp, new ConcurrentHashMap<>()));
            Future<Object> second = callers.submit(() -> invokeInTraceContext(
                    aspect, secondPjp, new ConcurrentHashMap<>()));

            boolean bothEntered = bothOwnersEntered.await(1, TimeUnit.SECONDS);
            releaseOwners.countDown();
            assertTrue(bothEntered, "different request contexts must create independent owners");
            assertEquals("request-a", first.get(1, TimeUnit.SECONDS));
            assertEquals("request-b", second.get(1, TimeUnit.SECONDS));
            assertEquals(2, proceedCalls.get());
        } finally {
            releaseOwners.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void differentMetadataInOneRequestDoesNotJoin() throws Exception {
        SingleFlightManager manager = configuredManager(2_000L, testPool(2, "sf-metadata-owner"));
        SingleFlightAspect aspect = aspect(manager);
        ExecutorService callers = testPool(2, "sf-metadata-caller");
        Map<String, Object> requestContext = new ConcurrentHashMap<>();
        Query firstQuery = QueryUtils.buildQuery("same text", Map.of("purpose", "WEB_SEARCH", "webTopK", 3));
        AtomicInteger proceedCalls = new AtomicInteger();
        CountDownLatch firstOwnerEntered = new CountDownLatch(1);
        CountDownLatch secondOwnerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwners = new CountDownLatch(1);

        try {
            ProceedingJoinPoint firstPjp = blockingJoinPoint(
                    firstQuery, proceedCalls, firstOwnerEntered, releaseOwners, "metadata-a");
            Future<Object> first = callers.submit(() -> invokeInTraceContext(
                    aspect, firstPjp, requestContext));
            assertTrue(firstOwnerEntered.await(1, TimeUnit.SECONDS), "first metadata owner never started");

            Query secondQuery = QueryUtils.buildQuery(
                    "same text", Map.of("purpose", "WEB_SEARCH", "webTopK", 7));
            ProceedingJoinPoint secondPjp = blockingJoinPoint(
                    secondQuery, proceedCalls, secondOwnerEntered, releaseOwners, "metadata-b");
            Future<Object> second = callers.submit(() -> invokeInTraceContext(
                    aspect, secondPjp, requestContext));

            boolean secondEntered = secondOwnerEntered.await(1, TimeUnit.SECONDS);
            releaseOwners.countDown();
            assertTrue(secondEntered, "metadata differences must produce different Single-Flight keys");
            assertEquals("metadata-a", first.get(1, TimeUnit.SECONDS));
            assertEquals("metadata-b", second.get(1, TimeUnit.SECONDS));
            assertEquals(2, proceedCalls.get());
        } finally {
            releaseOwners.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void equivalentNestedMetadataOrderUsesOneDeterministicKey() throws Exception {
        SingleFlightManager manager = configuredManager(2_000L, testPool(2, "sf-canonical-owner"));
        SingleFlightAspect aspect = aspect(manager);
        ExecutorService callers = testPool(2, "sf-canonical-caller");
        Map<String, Object> requestContext = new ConcurrentHashMap<>();
        Map<String, Object> firstNested = new LinkedHashMap<>();
        firstNested.put("alpha", 1);
        firstNested.put("beta", 2);
        Query firstQuery = QueryUtils.buildQuery(
                "canonical text", Map.of("purpose", "WEB_SEARCH", "filters", firstNested));
        AtomicInteger proceedCalls = new AtomicInteger();
        CountDownLatch firstOwnerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch followerStarted = new CountDownLatch(1);

        try {
            ProceedingJoinPoint firstPjp = blockingJoinPoint(
                    firstQuery, proceedCalls, firstOwnerEntered, releaseOwner, "canonical-result");
            Future<Object> first = callers.submit(() -> invokeInTraceContext(
                    aspect, firstPjp, requestContext));
            assertTrue(firstOwnerEntered.await(1, TimeUnit.SECONDS), "canonical owner never started");

            Map<String, Object> secondNested = new LinkedHashMap<>();
            secondNested.put("beta", 2);
            secondNested.put("alpha", 1);
            Query secondQuery = QueryUtils.buildQuery(
                    "canonical text", Map.of("filters", secondNested, "purpose", "WEB_SEARCH"));
            ProceedingJoinPoint secondPjp = blockingJoinPoint(
                    secondQuery, proceedCalls, new CountDownLatch(1), releaseOwner, "must-not-run");
            Future<Object> second = callers.submit(() -> {
                followerStarted.countDown();
                return invokeInTraceContext(aspect, secondPjp, requestContext);
            });
            assertTrue(followerStarted.await(1, TimeUnit.SECONDS), "canonical follower never called advice");
            Thread.sleep(50L);
            releaseOwner.countDown();

            assertEquals("canonical-result", first.get(1, TimeUnit.SECONDS));
            assertEquals("canonical-result", second.get(1, TimeUnit.SECONDS));
            assertEquals(1, proceedCalls.get(), "metadata map order must not change the key");
        } finally {
            releaseOwner.countDown();
            callers.shutdownNow();
        }
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(SingleFlightManager.class, SingleFlightAspect.class);
    }

    private SingleFlightManager configuredManager(long timeoutMs, ExecutorService executor) {
        try {
            Constructor<SingleFlightManager> constructor =
                    SingleFlightManager.class.getDeclaredConstructor(long.class, ExecutorService.class);
            constructor.setAccessible(true);
            SingleFlightManager manager = constructor.newInstance(timeoutMs, executor);
            managers.add(manager);
            return manager;
        } catch (ReflectiveOperationException missingContract) {
            executor.shutdownNow();
            return fail("SingleFlightManager(long, ExecutorService) test seam is required", missingContract);
        }
    }

    private static SingleFlightAspect aspect(SingleFlightManager manager) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cache.singleflight.enabled", "true")
                .withProperty("cache.singleflight.timeout-ms", "2000")
                .withProperty("cache.singleflight.key-strategy", "METHOD_AND_ARGS");
        return new SingleFlightAspect(manager, environment);
    }

    private static ProceedingJoinPoint blockingJoinPoint(
            Query query,
            AtomicInteger proceedCalls,
            CountDownLatch entered,
            CountDownLatch release,
            Object result) throws Exception {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn("WebSearchRetriever.retrieve(..)");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[] { query });
        try {
            when(pjp.proceed()).thenAnswer(invocation -> {
                proceedCalls.incrementAndGet();
                entered.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("advice target was not released");
                }
                return result;
            });
        } catch (Throwable impossibleDuringStubbing) {
            throw new IllegalStateException(impossibleDuringStubbing);
        }
        return pjp;
    }

    private static Object invokeInTraceContext(
            SingleFlightAspect aspect,
            ProceedingJoinPoint pjp,
            Map<String, Object> context) throws Exception {
        TraceStore.installContext(context);
        try {
            return aspect.dedupe(pjp);
        } catch (Exception failure) {
            throw failure;
        } catch (Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new Exception(failure);
        } finally {
            TraceStore.clear();
        }
    }

    private static ExecutorService testPool(int size, String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(size, factory);
    }
}
