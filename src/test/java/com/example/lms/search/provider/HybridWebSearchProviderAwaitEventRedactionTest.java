package com.example.lms.search.provider;

import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import org.springframework.beans.factory.annotation.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridWebSearchProviderAwaitEventRedactionTest {

    private static final Pattern MOJIBAKE_KOREAN_MARKER = Pattern.compile(
            "\\?섏|\\?대|\\?붿|\\?뺣|\\?덈|\\?먯|\\?꾨|\\?뚯|\\?⑹|\\?ㅼ|\\?띿|\\?쒓|\\?ъ|\\?몄|\\?댁|\\?븘|\\?쁺|\\?곌|\\?섎|猷⑤㉧");

    @AfterEach
    void tearDown() {
        MDC.remove("dbgSearch");
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void hybridTraceSuppressionsUseStableRetryAfterParserLabels() {
        HybridTraceSuppressions.traceSuppressed("retryAfter.seconds", new NumberFormatException("private-second"));
        HybridTraceSuppressions.traceSuppressed("retryAfter.httpDate",
                new java.time.format.DateTimeParseException("private-date", "private-date", 0));

        assertEquals("invalid_number", TraceStore.get("web.hybrid.suppressed.retryAfter.seconds.errorType"));
        assertEquals("invalid_date", TraceStore.get("web.hybrid.suppressed.retryAfter.httpDate.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("DateTimeParseException"), trace);
        assertFalse(trace.contains("private-second"), trace);
        assertFalse(trace.contains("private-date"), trace);
    }

    @Test
    void awaitEventDebugHttpDiagnosticsDoNotStoreRawUriQueryBodyOrMessage() throws Exception {
        String rawQuery = "private search query";
        String apiKey = "sk-secret123456";
        String body = "{\"error\":\"failed for " + rawQuery + " api_key=" + apiKey + "\"}";
        URI uri = URI.create("https://api.example.test/search?q=private%20search%20query&api_key=" + apiKey);

        MDC.put("dbgSearch", "true");

        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
        WebClientResponseException error = WebClientResponseException.create(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
                request);

        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "recordAwaitEvent",
                String.class,
                String.class,
                String.class,
                String.class,
                long.class,
                long.class,
                Throwable.class);
        method.setAccessible(true);
        method.invoke(null, "hard", "Brave", "awaitWithDeadline", "exception", 100L, 10L, error);

        Object last = TraceStore.get("web.await.last");
        assertTrue(last instanceof Map<?, ?>);
        Map<?, ?> event = (Map<?, ?>) last;

        assertEquals(429, event.get("httpStatus"));
        assertEquals("api.example.test/search", event.get("httpTarget"));
        assertEquals(Boolean.TRUE, event.get("httpHasQuery"));
        assertTrue(String.valueOf(event.get("httpQueryHash")).startsWith("hash:"));
        assertTrue(String.valueOf(event.get("httpBodyHash")).startsWith("hash:"));
        assertEquals(body.length(), event.get("httpBodyLength"));
        assertTrue(String.valueOf(event.get("errMsgHash")).startsWith("hash:"));

        assertFalse(event.containsKey("httpUri"));
        assertFalse(event.containsKey("httpBody"));
        assertFalse(event.containsKey("errMsg"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery));
        assertFalse(trace.contains(apiKey));
        assertFalse(trace.contains("api_key"));
    }

    @Test
    void awaitEventLabelsAndCorrelationIdsDoNotStoreRawSensitiveText() throws Exception {
        String rawEngine = "Private Engine ownerToken=raw-secret";
        String rawStage = "Private Stage query text";
        String rawStep = "Private Step api_key=<test-api-key>";
        String rawCause = "private cause Authorization: Bearer raw-token";
        String rawRid = "rid-user-private-query";
        String rawSid = "sid-user-private-query";
        MDC.put("x-request-id", rawRid);
        MDC.put("sessionId", rawSid);

        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "recordAwaitEvent",
                String.class,
                String.class,
                String.class,
                String.class,
                long.class,
                long.class,
                Throwable.class);
        method.setAccessible(true);
        method.invoke(null, rawStage, rawEngine, rawStep, rawCause, 100L, 10L, null);

        Object last = TraceStore.get("web.await.last");
        assertTrue(last instanceof Map<?, ?>);
        String trace = String.valueOf(last);

        assertFalse(trace.contains(rawEngine), trace);
        assertFalse(trace.contains(rawStage), trace);
        assertFalse(trace.contains(rawStep), trace);
        assertFalse(trace.contains(rawCause), trace);
        assertFalse(trace.contains("raw-secret"), trace);
        assertFalse(trace.contains("<test-api-key>"), trace);
        assertFalse(trace.contains("raw-token"), trace);
        assertFalse(trace.contains(rawRid), trace);
        assertFalse(trace.contains(rawSid), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void awaitSkipKeepOnceKeyDoesNotStoreRawSkipReason() throws Exception {
        String rawEngine = "Private Engine ownerToken=raw-secret";
        String rawCause = "skip_private api_key=<test-api-key> raw query";

        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "recordAwaitEvent",
                String.class,
                String.class,
                String.class,
                String.class,
                long.class,
                long.class,
                Throwable.class);
        method.setAccessible(true);
        method.invoke(null, "soft", rawEngine, "provider", rawCause, 0L, 0L, null);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawEngine), trace);
        assertFalse(trace.contains(rawCause), trace);
        assertFalse(trace.contains("<test-api-key>"), trace);
        assertFalse(trace.contains("raw query"), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void safeGetNowRestoresCallerInterruptAndReturnsExactFallback() throws Exception {
        HybridWebSearchProvider provider = new HybridWebSearchProvider(
                mock(NaverSearchService.class), mock(BraveSearchService.class));
        Future<String> future = interruptingFuture();
        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "safeGetNow", Future.class, Object.class, String.class, String.class);
        method.setAccessible(true);

        try {
            Object out = method.invoke(provider, future, "safe-fallback", "Brave-Trace", "hard");

            assertEquals("safe-fallback", out);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(String.valueOf(TraceStore.get("web.await.last")).contains("interrupted"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void awaitWithDeadlineRestoresCallerInterruptAndKeepsCancelFalse() throws Exception {
        HybridWebSearchProvider provider = new HybridWebSearchProvider(
                mock(NaverSearchService.class), mock(BraveSearchService.class));
        Future<String> future = interruptingFuture();
        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "awaitWithDeadline", Future.class, long.class, Object.class, String.class);
        method.setAccessible(true);

        try {
            Object out = method.invoke(
                    provider,
                    future,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                    "hard-fallback",
                    "Brave");

            assertEquals("hard-fallback", out);
            assertTrue(Thread.currentThread().isInterrupted());
            verify(future).cancel(false);
            verify(future, never()).cancel(true);
            assertTrue(String.valueOf(TraceStore.get("web.await.last")).contains("interrupted"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void awaitWithDeadlineRestoresInterruptWhileKeepingFloorCancellationSuppressed() throws Exception {
        HybridWebSearchProvider provider = new HybridWebSearchProvider(
                mock(NaverSearchService.class), mock(BraveSearchService.class));
        setField(provider, "awaitMinLiveBudgetMs", 1_200L);
        setField(provider, "awaitNearExhaustedThresholdMs", 2_000L);
        setField(provider, "awaitFloorTinyBudget", true);
        setField(provider, "awaitCancelSuppressedWhenFloor", true);
        Future<String> future = interruptingFuture();
        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "awaitWithDeadline", Future.class, long.class, Object.class, String.class);
        method.setAccessible(true);

        try {
            Object out = method.invoke(
                    provider,
                    future,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                    "floor-fallback",
                    "Brave");

            assertEquals("floor-fallback", out);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1L, TraceStore.getLong("web.await.cancelSuppressed"));
            assertEquals("near_exhausted", TraceStore.get("web.await.cancelSuppressed.reason"));
            verify(future, never()).cancel(false);
            verify(future, never()).cancel(true);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void awaitSoftRestoresCallerInterruptAndReturnsExactFallback() throws Exception {
        HybridWebSearchProvider provider = new HybridWebSearchProvider(
                mock(NaverSearchService.class), mock(BraveSearchService.class));
        Future<String> future = interruptingFuture();
        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "awaitSoft", Future.class, long.class, Object.class, String.class);
        method.setAccessible(true);

        try {
            Object out = method.invoke(provider, future, 100L, "soft-fallback", "Naver-Trace");

            assertEquals("soft-fallback", out);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(String.valueOf(TraceStore.get("web.await.last")).contains("interrupted"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preInterruptedTraceEntrySkipsEveryProviderCacheAndExecutorOperation() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        ExecutorService executor = mock(ExecutorService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.isCoolingDown()).thenReturn(false);
        when(naver.isEnabled()).thenReturn(true);

        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        setField(provider, "searchIoExecutor", executor);
        setField(provider, "primary", "BRAVE");
        setField(provider, "timeoutSec", 1);
        setField(provider, "koreanHedgeDelayMs", 50L);
        setField(provider, "soakEnabled", false);
        setField(provider, "remergeOnEmptyEnabled", false);

        Thread.currentThread().interrupt();
        try {
            NaverSearchService.SearchResult out = provider.searchWithTrace("사전 취소 요청", 3);

            assertEquals(List.of(), out.snippets());
            assertNull(out.trace());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(brave, never()).isEnabled();
            verify(brave, never()).searchWithMeta(anyString(), anyInt());
            verify(brave, never()).searchCacheOnly(anyString(), anyInt());
            verify(naver, never()).isEnabled();
            verify(naver, never()).searchWithTraceSync(anyString(), anyInt(), any());
            verify(naver, never()).searchSnippetsCacheOnly(anyString(), anyInt(), any());
            verify(executor, never()).submit(any(Callable.class));
            verify(executor, never()).execute(any(Runnable.class));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void interruptedTraceHedgeDoesNotSubmitLaterOptionalProvider() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        ExecutorService executor = mock(ExecutorService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.isCoolingDown()).thenReturn(false);
        when(naver.isEnabled()).thenReturn(true);
        org.mockito.Mockito.doAnswer(call -> {
            // The production scope now owns its FutureTask; interrupt the real waiting thread.
            Thread.currentThread().interrupt();
            return null;
        }).when(executor).execute(any(Runnable.class));

        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        setField(provider, "searchIoExecutor", executor);
        setField(provider, "primary", "BRAVE");
        setField(provider, "timeoutSec", 1);
        setField(provider, "koreanHedgeDelayMs", 50L);
        setField(provider, "skipNaverIfBraveMinResults", 6);
        setField(provider, "skipNaverIfBraveSufficient", true);
        setField(provider, "forceOpportunisticNaverEvenIfBraveFast", false);
        setField(provider, "soakEnabled", false);
        setField(provider, "remergeOnEmptyEnabled", false);

        try {
            NaverSearchService.SearchResult out = provider.searchWithTrace("인터럽트 후속 검색 금지", 5);

            assertEquals(List.of(), out.snippets());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(executor, times(1)).execute(any(Runnable.class));
            verify(naver, never()).searchWithTraceSync(anyString(), anyInt(), any());
            verify(naver, never()).searchSnippetsSync(anyString(), anyInt(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void koreanBraveFirstUsesNaverCacheOnlyWhenNaverProviderDisabled() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.isCoolingDown()).thenReturn(false);
        when(brave.searchWithMeta(anyString(), anyInt())).thenReturn(BraveSearchResult.ok(List.of(), 1L));
        when(naver.isEnabled()).thenReturn(false);
        when(naver.searchSnippetsCacheOnly(anyString(), anyInt(), any()))
                .thenReturn(List.of("naver cached snippet"));

        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            setField(provider, "searchIoExecutor", executor);
            setField(provider, "primary", "BRAVE");
            setField(provider, "timeoutSec", 1);
            setField(provider, "koreanHedgeDelayMs", 1L);
            setField(provider, "soakEnabled", false);
            setField(provider, "remergeOnEmptyEnabled", false);

            List<String> out = provider.search("갤럭시 업데이트 일정", 3);

            assertEquals(List.of("naver cached snippet"), out);
            assertEquals(Boolean.TRUE, TraceStore.get("web.naver.cacheOnlyFuture.used"));
            assertEquals("disabled", TraceStore.get("web.naver.skipped.reason"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void koreanOpportunisticNaverAfterFastBraveIsOptInByDefault() throws Exception {
        Field field = HybridWebSearchProvider.class.getDeclaredField("forceOpportunisticNaverEvenIfBraveFast");
        Value value = field.getAnnotation(Value.class);

        assertEquals("${gpt-search.hybrid.korean.force-opportunistic-naver-even-if-brave-fast:false}",
                value.value());
    }

    @Test
    void koreanTraceBraveFirstRecordsNaverHedgeSkipWhenFastBraveIsEnough() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.isCoolingDown()).thenReturn(false);
        when(brave.searchWithMeta(anyString(), anyInt())).thenReturn(BraveSearchResult.ok(
                List.of("b1", "b2", "b3", "b4", "b5", "b6"), 12L));
        when(naver.isEnabled()).thenReturn(true);

        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            setField(provider, "searchIoExecutor", executor);
            setField(provider, "primary", "BRAVE");
            setField(provider, "timeoutSec", 1);
            setField(provider, "koreanHedgeDelayMs", 50L);
            setField(provider, "skipNaverIfBraveMinResults", 6);
            setField(provider, "skipNaverIfBraveSufficient", true);
            setField(provider, "forceOpportunisticNaverEvenIfBraveFast", false);
            setField(provider, "soakEnabled", false);
            setField(provider, "remergeOnEmptyEnabled", false);

            NaverSearchService.SearchResult out = provider.searchWithTrace("브라우저 검색 상태 확인", 5);

            assertEquals(5, out.snippets().size());
            verify(naver, never()).searchWithTraceSync(anyString(), anyInt(), any());
            assertEquals("brave_sufficient", TraceStore.get("web.naver.skipped.reason"));
            assertEquals("korean.braveFirst.trace.hedge", TraceStore.get("web.naver.skipped.stage"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cheapSearchModeSkipsBackupQueryAfterEmptyPrimary() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        GuardContext ctx = new GuardContext();
        ctx.setCheapSearchMode(true);
        GuardContextHolder.set(ctx);
        setField(provider, "remergeOnEmptyEnabled", false);

        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "maybeBackupOnce",
                String.class,
                int.class,
                List.class);
        method.setAccessible(true);

        Object out = method.invoke(
                provider,
                "qwen api pricing quota latest details official check extra",
                3,
                List.of());

        assertEquals(List.of(), out);
        assertNull(TraceStore.get("websearch.backup.used"));
        assertEquals(Boolean.TRUE, TraceStore.get("websearch.backup.skipped"));
        assertEquals("cheap-search-mode", TraceStore.get("websearch.backup.skipReason"));
    }

    @Test
    void cheapSearchModeSkipsCacheOnlyRemergePollsAfterEmptyPrimary() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        GuardContext ctx = new GuardContext();
        ctx.setCheapSearchMode(true);
        GuardContextHolder.set(ctx);
        setField(provider, "remergeOnEmptyEnabled", true);
        setField(provider, "braveCacheOnlyEscape", true);
        setField(provider, "naverCacheOnlyEscape", true);
        TraceStore.put("web.await.events.timeout.count", 1L);

        Method method = HybridWebSearchProvider.class.getDeclaredMethod(
                "maybeRemergeOnceCacheOnly",
                String.class,
                int.class);
        method.setAccessible(true);

        Object out = method.invoke(
                provider,
                "qwen local light search mode proof",
                3);

        assertEquals(List.of(), out);
        assertNull(TraceStore.get("websearch.remergeOnce.used"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.remergeOnce.skipped"));
        assertEquals("cheap-search-mode", TraceStore.get("web.failsoft.remergeOnce.skipReason"));
        verify(brave, never()).searchCacheOnly(anyString(), anyInt());
        verify(naver, never()).searchSnippetsCacheOnly(anyString(), anyInt(), any());
    }

    @Test
    void koreanOfficialOnlyDoesNotForceNaverFirstWhenBraveIsPrimaryAndFastEnough() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.isCoolingDown()).thenReturn(false);
        when(brave.searchWithMeta(anyString(), anyInt())).thenReturn(BraveSearchResult.ok(
                List.of("b1", "b2", "b3", "b4", "b5", "b6"), 8L));
        when(naver.isEnabled()).thenReturn(true);

        GuardContext ctx = new GuardContext();
        ctx.setOfficialOnly(true);
        GuardContextHolder.set(ctx);

        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            setField(provider, "searchIoExecutor", executor);
            setField(provider, "primary", "BRAVE");
            setField(provider, "timeoutSec", 1);
            setField(provider, "koreanHedgeDelayMs", 50L);
            setField(provider, "skipNaverIfBraveMinResults", 6);
            setField(provider, "skipNaverIfBraveSufficient", true);
            setField(provider, "forceOpportunisticNaverEvenIfBraveFast", false);
            setField(provider, "soakEnabled", false);
            setField(provider, "remergeOnEmptyEnabled", false);

            List<String> out = provider.search("\uBE0C\uB77C\uC6B0\uC800 \uACF5\uC2DD \uBB38\uC11C \uC0C1\uD0DC", 5);

            assertEquals(5, out.size());
            verify(naver, never()).searchSnippetsSync(anyString(), anyInt(), any());
            assertEquals("brave-first", TraceStore.get("webSearch.providerPreference"));
            assertEquals("brave_sufficient", TraceStore.get("web.naver.skipped.reason"));
        } finally {
            GuardContextHolder.clear();
            executor.shutdownNow();
        }
    }

    @Test
    void koreanFoldRumorConversionUsesReadableMarkersInsteadOfMojibakeLiterals() throws Exception {
        assertEquals("Galaxy Z Fold 7 leak rumors renders",
                HybridSearchQueryPolicy.convertToEnglishSearchTerm("갤럭시 폴드7 루머 렌더"));

        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridSearchQueryPolicy.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("normalized.contains(\"?"),
                "runtime Korean marker checks should not use mojibake literals");
    }

    @Test
    void hybridProviderSourceDoesNotRetainMojibakeCommentMarkers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(MOJIBAKE_KOREAN_MARKER.matcher(source).find(),
                "HybridWebSearchProvider should not retain mojibake Korean markers in code or comments");
    }

    @Test
    void hybridProviderDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatchBlocks = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();

        assertEquals(0L, exactEmptyCatchBlocks);
    }

    @Test
    void hybridProviderAndBoundaryNeverClearRequestInterrupts() throws Exception {
        String provider = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);
        String aspect = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspect.java"),
                StandardCharsets.UTF_8);

        assertEquals(0L, Pattern.compile("Thread\\.interrupted\\s*\\(")
                .matcher(provider + aspect)
                .results()
                .count());
        assertEquals(3L, Pattern.compile("\\.cancel\\(false\\)")
                .matcher(provider)
                .results()
                .count());
        assertFalse(provider.contains(".cancel(true)"));
    }

    @Test
    void providerExceptionLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .filter(line -> !line.contains("SafeRedactor.hashValue("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
    }

    @Test
    void hardAwaitFloorAppliedLogsDoNotRenderThrowableToString() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("tag, e.toString(), LogCorrelation.suffix())"));
        assertFalse(source.contains("tag, SafeRedactor.safeMessage(e.getMessage(), 180), LogCorrelation.suffix())"));
        assertTrue(source.contains(
                "tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length(), LogCorrelation.suffix())"));
        assertTrue(source.contains(
                "tag, SafeRedactor.hashValue(ee.getMessage()), ee.getMessage() == null ? 0 : ee.getMessage().length(), LogCorrelation.suffix())"));
    }

    @Test
    void hardAwaitGenericFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "log.debug(\"[{}] Failed: {}\", tag, SafeRedactor.safeMessage(String.valueOf(e), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[{}] Failed: {}\", tag, SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertTrue(source.contains(
                "log.debug(\"[{}] Failed: errorHash={} errorLength={}\", tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[{}] Failed: errorHash={} errorLength={}\", tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
    }

    @Test
    void directProviderFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Brave primary failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Naver fallback failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Naver primary failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Brave fallback failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Brave primary failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Naver fallback failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Naver primary failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Brave fallback failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
    }

    @Test
    void traceProviderFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Brave primary (trace) failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Naver trace-search failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Naver primary (trace) failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Brave fallback (trace) failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Brave primary (trace) failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Naver trace-search failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Naver primary (trace) failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Brave fallback (trace) failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
    }

    @Test
    void providerSchedulingFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Brave scheduling failed, skipping Brave call: {}\", SafeRedactor.safeMessage(String.valueOf(submitEx), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Naver scheduling failed, skipping Naver call: {}\", SafeRedactor.safeMessage(String.valueOf(submitEx), 180));"));
        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Naver scheduling failed, skipping Naver trace call: {}\", SafeRedactor.safeMessage(String.valueOf(submitEx), 180));"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Brave scheduling failed, skipping Brave call errorHash={} errorLength={}\", SafeRedactor.hashValue(submitEx.getMessage()), submitEx.getMessage() == null ? 0 : submitEx.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Naver scheduling failed, skipping Naver call errorHash={} errorLength={}\", SafeRedactor.hashValue(submitEx.getMessage()), submitEx.getMessage() == null ? 0 : submitEx.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Naver scheduling failed, skipping Naver trace call errorHash={} errorLength={}\", SafeRedactor.hashValue(submitEx.getMessage()), submitEx.getMessage() == null ? 0 : submitEx.getMessage().length());"));
    }

    @Test
    void koreanNaverFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Naver korean search failed: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertFalse(source.contains(
                "log.debug(\"[Hybrid] Naver korean-trace search failed: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180));"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Naver korean search failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.debug(\"[Hybrid] Naver korean-trace search failed errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
    }

    @Test
    void braveExpandedSearchFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "log.warn(\"[Hybrid] Brave expanded search failed: {}\", SafeRedactor.safeMessage(ex.getMessage(), 180));"));
        assertTrue(source.contains(
                "log.warn(\"[Hybrid] Brave expanded search failed errorHash={} errorLength={}\", SafeRedactor.hashValue(ex.getMessage()), ex.getMessage() == null ? 0 : ex.getMessage().length());"));
    }

    @Test
    void remainingHybridExceptionLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180), LogCorrelation.suffix()"));
        assertFalse(source.contains("log.error(\"[Hybrid] buildTraceHtml error: {}\", SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertTrue(source.contains(
                "SafeRedactor.hashValue(t.getMessage()), t.getMessage() == null ? 0 : t.getMessage().length()"));
        assertTrue(source.contains(
                "log.debug(\"[{}] Soft await failed errorHash={} errorLength={}\", tag, SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
        assertTrue(source.contains(
                "log.warn(\"[WEBSEARCH_BACKUP_QUERY] backup search failed errorHash={} errorLength={}{}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length(), LogCorrelation.suffix());"));
        assertTrue(source.contains(
                "log.error(\"[Hybrid] buildTraceHtml errorHash={} errorLength={}\", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());"));
    }

    @Test
    void remergeOnceLogsDoNotWriteRawCorrelationIds() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("rid={} sessionId={}"));
        assertFalse(source.contains("LogCorrelation.requestId(), LogCorrelation.sessionId()"));
        assertTrue(source.contains("ridHash={} sessionHash={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(LogCorrelation.requestId())"));
        assertTrue(source.contains("SafeRedactor.hashValue(LogCorrelation.sessionId())"));
    }

    @Test
    void providerSkipAndHardDownReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"web.brave.skipped.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"web.naver.skipped.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"web.hardDown.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"web.naver.cacheOnlyFuture.reason\", reason.trim());"));
        assertFalse(source.contains("step += \"(\" + reason.trim() + \")\";"));
        assertFalse(source.contains("msg = msg + \":\" + reason.trim();"));

        assertTrue(source.contains(
                "TraceStore.put(\"web.brave.skipped.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.naver.skipped.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.hardDown.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.naver.cacheOnlyFuture.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains("step += \"(\" + SafeRedactor.traceLabelOrFallback(reason, \"unknown\") + \")\";"));
        assertTrue(source.contains("msg = msg + \":\" + SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
    }

    @Test
    void providerSkipAndHardDownFreeTextReasonsBecomeHashLabels() throws Exception {
        Method brave = HybridWebSearchProvider.class.getDeclaredMethod(
                "recordBraveSkipped", String.class, String.class, long.class, Throwable.class);
        brave.setAccessible(true);
        Method naver = HybridWebSearchProvider.class.getDeclaredMethod(
                "recordNaverSkipped", String.class, String.class, long.class, Throwable.class);
        naver.setAccessible(true);
        Method hardDown = HybridWebSearchProvider.class.getDeclaredMethod(
                "recordWebHardDown", String.class, String.class);
        hardDown.setAccessible(true);

        brave.invoke(null, "breaker_or_cooldown", "direct", 0L, null);
        assertEquals("breaker_or_cooldown", TraceStore.get("web.brave.skipped.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("hybrid.web.provider.brave.skipped"));
        assertEquals("breaker_or_cooldown", TraceStore.get("hybrid.web.provider.brave.skipReason"));

        String rawReason = "private provider skip reason for student query api_key=<test-api-key>";
        TraceStore.clear();
        brave.invoke(null, rawReason, "direct", 0L, null);
        naver.invoke(null, rawReason, "direct", 0L, null);
        hardDown.invoke(null, rawReason, "direct");

        assertTrue(String.valueOf(TraceStore.get("web.brave.skipped.reason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.naver.skipped.reason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("hybrid.web.provider.brave.skipReason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("hybrid.web.provider.naver.skipReason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.hardDown.reason")).startsWith("hash:"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("student query"), trace);
        assertFalse(trace.contains("<test-api-key>"), trace);
    }

    @Test
    void mergeBoundaryPublishesHybridOutCountAndStarvationAliases() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"hybrid.web.outCount\", mergedSafe.size());"));
        assertTrue(source.contains("TraceStore.put(\"hybrid.web.starvation\", afterFilterStarvation);"));
        assertTrue(source.contains("TraceStore.put(\"outCount\", mergedSafe.size());"));
        assertTrue(source.contains("TraceStore.put(\"stageCountsSelectedFromOut\","));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.poolSafeEmpty\", mergedSafe.isEmpty());"));
        assertTrue(source.contains("TraceStore.put(\"poolSafeEmpty\", mergedSafe.isEmpty());"));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.trigger\", \"all_providers_empty_or_down\");"));
        assertTrue(source.contains("TraceStore.put(\"rescueMerge.used\", false);"));
        assertTrue(source.contains("TraceStore.put(\"tracePool.size\", TraceStore.getPoolItems().size());"));
    }

    @Test
    void providerSkipErrorsUseOperationalLabelsInsteadOfExceptionClassNames() throws Exception {
        Method brave = HybridWebSearchProvider.class.getDeclaredMethod(
                "recordBraveSkipped", String.class, String.class, long.class, Throwable.class);
        brave.setAccessible(true);
        Method naver = HybridWebSearchProvider.class.getDeclaredMethod(
                "recordNaverSkipped", String.class, String.class, long.class, Throwable.class);
        naver.setAccessible(true);

        WebClientResponseException rateLimited = WebClientResponseException.create(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
        brave.invoke(null, "submit_failed", "direct", 0L, rateLimited);
        assertEquals("rate-limit", TraceStore.get("web.brave.skipped.err"));

        TraceStore.clear();
        naver.invoke(null, "submit_failed", "direct", 0L,
                new java.util.concurrent.TimeoutException("private timeout ownerToken=secret"));
        assertEquals("timeout", TraceStore.get("web.naver.skipped.err"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("TooManyRequests"), trace);
        assertFalse(trace.contains("WebClientResponseException"), trace);
        assertFalse(trace.contains("TimeoutException"), trace);
        assertFalse(trace.contains("ownerToken"), trace);
    }

    @Test
    void cancelSuppressedAndRemergeMissReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"web.await.cancelSuppressed.reason\", floorCause);"));
        assertFalse(source.contains("TraceStore.put(\"web.failsoft.remergeOnce.missReason\", missReason);"));
        assertFalse(source.contains("endMeta.put(\"missReason\", missReason);"));
        assertFalse(source.contains("SafeRedactor.safeMessage(floorCause, 120)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(missReason, 120)"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.await.cancelSuppressed.reason\", SafeRedactor.traceLabelOrFallback(floorCause, \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.failsoft.remergeOnce.missReason\", SafeRedactor.traceLabelOrFallback(missReason, \"unknown\"));"));
        assertTrue(source.contains("endMeta.put(\"missReason\", SafeRedactor.traceLabelOrFallback(missReason, \"unknown\"));"));
    }

    @Test
    void rateLimitBackoffDecisionReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "TraceStore.putIfAbsent(\"web.failsoft.rateLimitBackoff.\" + providerKey + \".reason\", d.reason());"));
        assertFalse(source.contains(
                "TraceStore.putIfAbsent(\"web.failsoft.rateLimitBackoff.\" + providerKey + \".reason\", SafeRedactor.safeMessage(d.reason(), 120));"));
        assertTrue(source.contains(
                "TraceStore.putIfAbsent(\"web.failsoft.rateLimitBackoff.\" + providerKey + \".reason\", SafeRedactor.traceLabel(d.reason()));"));
    }

    @Test
    void retryAfterHttpDateParserDoesNotCatchThrowable() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);
        String parserCall = "java.time.ZonedDateTime.parse(";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "Retry-After HTTP-date parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 360));
        assertFalse(window.contains("catch (Throwable"),
                "Retry-After HTTP-date parser must not swallow Throwable");
        assertFalse(window.contains("catch (Exception"),
                "Retry-After HTTP-date parser must not swallow every Exception");
        assertTrue(window.contains("catch (java.time.DateTimeException"),
                "Retry-After HTTP-date parser should catch DateTimeException");
    }

    @Test
    void providerBreakerChecksUseBaseKeysAndDoNotOwnProviderTerminals() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_BRAVE)"));
        assertTrue(source.contains("nightmareBreaker.isOpen(NightmareKeys.WEBSEARCH_NAVER)"));
        assertFalse(source.contains("nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE)"));
        assertFalse(source.contains("nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER)"));
        assertFalse(source.contains("nightmareBreaker.isOpenOrHalfOpen("));
        assertFalse(source.contains("nightmareBreaker.recordSuccess("));
        assertFalse(source.contains("nightmareBreaker.recordFailure("));
        assertFalse(source.contains("nightmareBreaker.recordRateLimit("));
    }

    @Test
    void primaryHealthProbeFailureLeavesRedactedTraceBreadcrumb() throws Exception {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.isEnabled()).thenThrow(new IllegalStateException(
                "private primary health failure ownerToken=raw-secret"));
        when(naver.isEnabled()).thenReturn(true);

        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        setField(provider, "primary", "BRAVE");

        Method method = HybridWebSearchProvider.class.getDeclaredMethod("isBravePrimary");
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(provider));
        assertEquals(Boolean.TRUE, TraceStore.get("webSearch.primary.brave.healthFailure"));
        assertEquals("unknown", TraceStore.get("webSearch.primary.brave.healthFailure.kind"));
        assertEquals("IllegalStateException", TraceStore.get("webSearch.primary.brave.healthFailure.errorType"));
        assertTrue(String.valueOf(TraceStore.get("webSearch.primary.brave.healthFailure.messageHash"))
                .startsWith("hash:"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("raw-secret"), trace);
        assertFalse(trace.contains("private primary health failure"), trace);
    }

    @Test
    void directProviderFallbackCatchesLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("import static com.example.lms.search.provider.HybridTraceSuppressions.traceSuppressed;"));
        assertTrue(source.contains("traceSuppressed(\"primary.autoSwitch.braveToNaver\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"primary.autoSwitch.naverToBrave\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"privacy.webBlocked.direct\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"providerPreference.naverFirst\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"providerPreference.braveFirst\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"providerPreference.naverFirstConfig\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"braveUsable.direct\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"bravePrimary.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"naverUsable.directFallback\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"naverFallback.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"naverSkipped.directFallback\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"naverUsable.direct\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"naverPrimary.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"naverSkipped.directPrimary\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"braveUsable.directFallback\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"braveFallback.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"naverBlockTimeout.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"debugSearch.mdc\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"safeMdc.get\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"braveSkipped.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"naverSkipped.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"naverCacheOnlyFuture.trace\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"naverCacheOnlyFuture\", t);"));
        assertTrue(source.contains("traceSuppressed(\"naverCacheOnlyTraceResult\", t);"));
        assertTrue(source.contains("traceSuppressed(\"braveCacheOnlyResult\", t);"));
        assertTrue(source.contains("traceSuppressed(\"webHardDown.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.keepOnce.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.httpStatus\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.httpBody\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.httpRequest\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.event.record\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.officialOnly.context\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.minLiveBudget.officialOnly\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"await.naverCap.trace\", ignore);"));
        assertFalse(source.contains("web.await.minLiveBudget.budgetExhaustedFloorApplied"),
                "an exhausted request must not acquire another floor budget");
        assertTrue(source.contains("traceSuppressed(\"await.cancelSuppressed.budgetExhausted\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.nearExhausted.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.tinyBudget.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.cancelSuppressed.floorTimeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.minLiveBudget.floorTimeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.cancelFalse.timeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.cancelSuppressed.floorInterrupted\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.minLiveBudget.floorInterrupted\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.cancelFalse.interrupted\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.cancelSuppressed.floorException\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.minLiveBudget.floorException\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"await.cancelFalse.exception\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"awaitTimeout.detectedCount\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"rateLimitBackoff.awaitTimeoutState\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.braveFirst.remainingOpenMs\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.braveFirst.earlyTimeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.braveFirst.earlyInterrupted\", ie);"));
        assertTrue(source.contains("traceSuppressed(\"korean.braveFirst.earlyFailure\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverLateJoin.timeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverLateJoin.interrupted\", ie);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverLateJoin.failure\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverOpportunisticJoin.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverCacheOnlyTimeoutRescue.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.officialOnlyContext\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.remainingOpenMs\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.reserveMs\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.braveFirst.naverSearchFailure\", e);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.naverSearchFailure\", e);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.earlyTimeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.earlyInterrupted\", ie);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.earlyFailure\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.braveRemainingOpenMs\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.braveHedgeSkipBypass.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"braveJoin.policy.direct\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.lateJoin.timeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.lateJoin.interrupted\", ie);"));
        assertTrue(source.contains("traceSuppressed(\"korean.naverFirst.lateJoin.failure\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"webMergeInvariant.emit\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"remergeOnceEvent.emit\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"soakWebMetrics.record\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"privacy.webBlocked.trace\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"trace.braveFirst.earlyTimeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"trace.braveFirst.earlyInterrupted\", ie);"));
        assertTrue(source.contains("traceSuppressed(\"trace.braveFirst.earlyFailure\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.naverFirst.remainingOpenMs\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.naverFirst.naverSearchFailure\", e);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.naverFirst.earlyTimeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.naverFirst.earlyInterrupted\", ie);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.naverFirst.earlyFailure\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"officialOnly.context.trace\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"braveJoin.policy.trace\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.naverFirst.lateJoin.timeout\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.naverFirst.lateJoin.interrupted\", ie);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.naverFirst.lateJoin.failure\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"koreanTrace.braveExpandedSearch\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"trace.braveUsable.primary\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"trace.bravePrimary.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"trace.naverUsable.fallback\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"trace.naverFallback.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"naverSkipped.traceFallback\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"trace.naverUsable.primary\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"trace.naverPrimary.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"naverSkipped.tracePrimary\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"trace.braveUsable.fallback\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"trace.braveFallback.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"logSkipOnce.dedupe\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"backup.usedRead\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"backup.usedWrite\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"backup.braveBreakerRead\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"backup.naverBreakerRead\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"remergeOnce.startEvent\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"remergeOnce.pollEvent\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"remergeOnce.endEvent\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"backupQuery.braveUsable\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"backupQuery.naverUsable\", suppressed);"));
        assertTrue(source.contains("traceSuppressed(\"buildTraceHtml\", e);"));
        assertTrue(source.contains("traceSuppressed(\"retryAfter.seconds\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"retryAfter.httpDate\", ignore);"));
    }

    @Test
    void remergeOnceStartMetadataUsesTraceLabelsForAwaitCause() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "startMeta.put(\"await.minLiveBudget.cause\", String.valueOf(TraceStore.get(\"web.await.minLiveBudget.cause\")));"));
        assertTrue(source.contains(
                "startMeta.put(\"await.minLiveBudget.cause\", SafeRedactor.traceLabel(TraceStore.get(\"web.await.minLiveBudget.cause\")));"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = HybridWebSearchProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> Future<T> interruptingFuture() throws Exception {
        Future<T> future = mock(Future.class);
        when(future.isDone()).thenReturn(false);
        when(future.get()).thenThrow(new InterruptedException("private interrupt detail"));
        when(future.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("private timed interrupt detail"));
        return future;
    }
}
