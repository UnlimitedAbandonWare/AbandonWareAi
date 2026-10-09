package com.example.lms.service;

import com.example.lms.debug.ApiFailureRecorder;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * One-shot LIVE wiring proof for the NAVER API HUB migration (A4 evidence).
 * Runs only when explicitly selected AND NAVER_APIHUB_LIVE=true AND the
 * NAVER_APIHUB_CLIENT_ID / NAVER_APIHUB_CLIENT_SECRET pair exists in the
 * process environment. Executes exactly one real API HUB call through the
 * service's own WebClient path (real host, real NCP headers, real parse).
 * Emits counts/trace labels only -- never credentials or response bodies.
 */
@EnabledIfEnvironmentVariable(named = "NAVER_APIHUB_LIVE", matches = "true")
@EnabledIfEnvironmentVariable(named = "NAVER_APIHUB_CLIENT_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "NAVER_APIHUB_CLIENT_SECRET", matches = ".+")
class NaverSearchServiceApiHubLiveIT {
    @TempDir Path temporary;

    @AfterEach void clearTrace() { TraceStore.clear(); }

    @Test void liveApiHubCallSelectsHubRouteAndReturnsHttp200Items() {
        var env = new MockEnvironment()
                .withProperty("naver.search.provider", "apihub")
                .withProperty("naver.apihub.client-id", System.getenv("NAVER_APIHUB_CLIENT_ID"))
                .withProperty("naver.apihub.client-secret", System.getenv("NAVER_APIHUB_CLIENT_SECRET"));
        var transformer = mock(QueryTransformer.class);
        when(transformer.transform(anyString(), anyString())).thenReturn(List.of("커피"));
        var rate = mock(RateLimitPolicy.class);
        when(rate.allowedExpansions()).thenReturn(1);
        var profile = mock(GuardProfileProps.class);
        when(profile.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);
        var client = WebClient.builder().build();
        var service = new NaverSearchService(transformer, mock(MemoryReinforcementService.class),
                mock(ObjectProvider.class), mock(EmbeddingStore.class), mock(EmbeddingModel.class),
                (Supplier<Long>) () -> null, null, "", "", "", 16L, 30L,
                mock(PlatformTransactionManager.class), rate, client, mock(ObjectProvider.class), null);
        var factory = new DefaultListableBeanFactory();
        factory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
        factory.addEmbeddedValueResolver(env::resolvePlaceholders);
        var injector = new AutowiredAnnotationBeanPostProcessor();
        injector.setAutowiredAnnotationType(Value.class);
        injector.setBeanFactory(factory);
        injector.processInjection(service);
        ReflectionTestUtils.setField(service, "guardProfileProps", profile);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 10000L);
        ReflectionTestUtils.setField(service, "queryTransformTimeoutMs", 2000L);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        // Cold-JVM TLS to ntruss needs >600ms; the 600ms floor clamps the
        // per-attempt timeout and produced a spurious TIMEOUT_OR_BUDGET.
        ReflectionTestUtils.setField(service, "adaptivePerCallFloorMs", 6000L);
        ReflectionTestUtils.setField(service, "adaptiveMaxOverallTimeoutMs", 15000L);
        ReflectionTestUtils.setField(service, "enableKeywordFilter", false);
        ReflectionTestUtils.setField(service, "fusionPolicy", "none");
        var recorder = new ApiFailureRecorder(mock(DebugEventStore.class),
                temporary.resolve("incidents.json").toString());
        try {
            ReflectionTestUtils.setField(service, "apiFailureRecorder", recorder);
            assertTrue(service.isEnabled(), "complete apihub pair must enable the provider");

            ReflectionTestUtils.setField(service, "syncBlockTimeoutMs", 30000L);
            var result = service.searchWithTraceSync("커피", 3, Duration.ofSeconds(30));
            var incidents = recorder.snapshot();
            // Diagnostic lines first so a later assertion failure still leaves evidence.
            StringBuilder traceKeys = new StringBuilder();
            TraceStore.context().forEach((k, v) -> {
                if (k != null && (k.contains("naver") || k.contains("web."))) {
                    traceKeys.append(' ').append(k).append('=').append(String.valueOf(v)).append(';');
                }
            });
            System.out.println("[LIVE-APIHUB] provider=" + TraceStore.get("web.naver.provider")
                    + " outcomeClass=" + (result != null && result.trace() != null ? result.trace().outcomeClass : "?")
                    + " incidents=" + incidents.size()
                    + " firstHttpStatus=" + (incidents.isEmpty() ? "-" : incidents.get(0).httpStatus())
                    + " firstErrorCode=" + (incidents.isEmpty() ? "-" : incidents.get(0).errorCode())
                    + " returnedCount=" + (result == null ? -1 : result.snippets().size()));
            System.out.println("[LIVE-APIHUB][trace]" + traceKeys);
            if (result != null && result.trace() != null) {
                result.trace().steps.forEach(s ->
                        System.out.println("[LIVE-APIHUB][step] reason=" + s.query
                                + " returned=" + s.returned + " tookMs=" + s.tookMs));
            }

            assertEquals("apihub", TraceStore.get("web.naver.provider"));
            assertNotNull(result);
            assertTrue(incidents.isEmpty(), "no HTTP incident expected on a healthy call");
            assertEquals("NONE", result.trace().outcomeClass, "terminal outcome must be NONE on clean 200");
            assertFalse(result.snippets().isEmpty(), "live apihub webkr call must return items");
        } finally {
            recorder.close();
        }
    }
}
