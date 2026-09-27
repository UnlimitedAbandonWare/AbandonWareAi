package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import com.example.lms.search.TraceStore;
import com.example.lms.service.web.BraveSearchResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderRateLimitBackoffAspectTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void naverCancellationReturnsEmptyWithoutBreakerBackoff() throws Throwable {
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());
        ProviderRateLimitBackoffAspect aspect = new ProviderRateLimitBackoffAspect(backoff, null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        doThrow(new CancellationException("client cancelled ownerToken=fake-token")).when(pjp).proceed();

        Object out = aspect.aroundNaverSearchSnippetsSync(pjp);

        List<?> snippets = assertInstanceOf(List.class, out);
        assertTrue(snippets.isEmpty());
        assertFalse(backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER).shouldSkip());
        assertEquals("CANCELLED_NO_BREAKER",
                TraceStore.get("web.failsoft.rateLimitBackoff.naver.last.kind"));
        assertEquals("naver", TraceStore.get("web.failsoft.rateLimitBackoff.naver.last.provider"));
        assertEquals(1L, TraceStore.getLong("web.failsoft.cancelled.naver.count"));
        assertEquals("cancelled", TraceStore.get("web.failsoft.cancelled.naver.lastType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertNull(TraceStore.get("web.naver.skipped"));
    }

    @Test
    void naverInterruptedCancellationClearsPoisonedThreadFlag() throws Throwable {
        Thread.interrupted();
        try {
            RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());
            ProviderRateLimitBackoffAspect aspect = new ProviderRateLimitBackoffAspect(backoff, null);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            doAnswer(invocation -> {
                Thread.currentThread().interrupt();
                throw new RuntimeException(new InterruptedException("interrupted private-query"));
            }).when(pjp).proceed();

            Object out = aspect.aroundNaverSearchSnippetsSync(pjp);

            List<?> snippets = assertInstanceOf(List.class, out);
            assertTrue(snippets.isEmpty());
            assertFalse(Thread.currentThread().isInterrupted(),
                    "swallowed provider cancellation must not poison the request thread");
            assertEquals(Boolean.TRUE, TraceStore.get("interrupt.cleaned"));
            assertFalse(backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER).shouldSkip());
            assertFalse(String.valueOf(TraceStore.getAll()).contains("private-query"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void braveCancellationReturnsEmptyWithoutCooldownBackoff() throws Throwable {
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());
        ProviderRateLimitBackoffAspect aspect = new ProviderRateLimitBackoffAspect(backoff, null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        doThrow(new CancellationException("client cancelled ownerToken=fake-token")).when(pjp).proceed();

        Object out = aspect.aroundBraveSearchWithMeta(pjp);

        BraveSearchResult result = assertInstanceOf(BraveSearchResult.class, out);
        assertTrue(result.snippets().isEmpty());
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals(0L, result.cooldownMs());
        assertFalse(backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE).shouldSkip());
        assertEquals("CANCELLED_NO_BREAKER",
                TraceStore.get("web.failsoft.rateLimitBackoff.brave.last.kind"));
        assertEquals("brave", TraceStore.get("web.failsoft.rateLimitBackoff.brave.last.provider"));
        assertEquals(1L, TraceStore.getLong("web.failsoft.cancelled.brave.count"));
        assertEquals("cancelled", TraceStore.get("web.failsoft.cancelled.brave.lastType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertNull(TraceStore.get("web.brave.skipped"));
    }

    @Test
    void braveDisabledResultStoresSpecificSkippedReason() throws Throwable {
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());
        ProviderRateLimitBackoffAspect aspect = new ProviderRateLimitBackoffAspect(backoff, null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(new BraveSearchResult(
                List.of(),
                BraveSearchResult.Status.DISABLED,
                null,
                0L,
                "brave disabled",
                0L));

        Object out = aspect.aroundBraveSearchWithMeta(pjp);

        BraveSearchResult result = assertInstanceOf(BraveSearchResult.class, out);
        assertEquals(BraveSearchResult.Status.DISABLED, result.status());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.skipped"));
        assertEquals("quota_exhausted_or_disabled", TraceStore.get("web.brave.skipped.reason"));
        assertEquals("provider_disabled", TraceStore.get("web.brave.skipped.stage"));
        assertEquals("quota_exhausted_or_disabled", TraceStore.get("web.await.brave.disabledReason"));
    }

    @Test
    void webPartialDownReasonDoesNotStoreRawFreeFormText() throws Exception {
        String fakeKey = "sk-" + "12345678901234567890";
        String rawReason = "cooldown private query api_key=" + fakeKey;

        Method method = ProviderRateLimitBackoffAspect.class.getDeclaredMethod(
                "markWebPartialDown",
                String.class,
                String.class);
        method.setAccessible(true);
        method.invoke(null, "brave", rawReason);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("private query"), trace);
        assertFalse(trace.contains(fakeKey), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void rateLimitedSkippedErrorUsesOperationalTraceLabel() throws Exception {
        HttpClientErrorException rateLimit = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);

        Method method = ProviderRateLimitBackoffAspect.class.getDeclaredMethod(
                "markRateLimited",
                String.class,
                Long.class,
                Throwable.class);
        method.setAccessible(true);
        method.invoke(null, "naver", 250L, rateLimit);

        assertEquals("rate-limit", TraceStore.get("web.naver.skipped.err"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("TooManyRequests"), trace);
        assertFalse(trace.contains("HttpClientErrorException"), trace);
    }

    @Test
    void timeoutBackoffDetailsUseStableLabelsInsteadOfThrowableClassNames() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("t.getClass().getSimpleName()"),
                "timeout/await-timeout backoff details must use stable labels, not throwable class names");
    }

    @Test
    void providerRateLimitBackoffAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspect.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "provider rate-limit backoff fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void suppressedTraceHelperRecordsRedactedStageAndErrorType() throws Exception {
        String secret = "sk-" + "providerRateLimitSecret123456789";
        Method method = ProviderRateLimitBackoffAspect.class.getDeclaredMethod(
                "traceSuppressed",
                String.class,
                Throwable.class);
        method.setAccessible(true);

        method.invoke(null, "retryAfter.header " + secret, new IllegalArgumentException("raw " + secret));

        Object stage = TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.stage");
        assertTrue(String.valueOf(stage).startsWith("hash:"), String.valueOf(stage));
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.rateLimitBackoff.suppressed." + stage));
        assertEquals("IllegalArgumentException", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.errorType"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("web.failsoft.rateLimitBackoff.suppressed." + stage + ".errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(secret), trace);
    }

    @Test
    void providerRateLimitBackoffAspectDoesNotUseSilentFailSoftCatchComments() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspect.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertFalse(source.contains("} catch (Throwable ignore) {\n            // best-effort\n        }"));
        assertFalse(source.contains("} catch (Throwable ignore) {\n            // fail-soft\n        }"));
        assertFalse(source.contains(
                "} catch (Throwable ignore) {\n            // fail-soft diagnostics must not affect provider behavior\n        }"));
    }
}
