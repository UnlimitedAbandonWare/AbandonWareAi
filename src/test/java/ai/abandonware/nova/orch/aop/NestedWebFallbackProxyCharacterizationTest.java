package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NestedWebFallbackProxyCharacterizationTest {
    private static final String QUERY = "Java API official reference";
    private static final int TOP_K = 5;

    @AfterEach
    void clearRequestContext() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyBoundedRouteDoesNotAcquireExtraCallsFromEitherAdvice(boolean traced) {
        Fixture fixture = fixture("", false);
        assertEquals(List.of(), invoke(fixture, traced));
        assertProviderCounts(fixture, 1, 1, 1);
        assertEmptyAdviceSuppressed();
        assertBodyInvocations(fixture, traced);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneAuthorizedExpansionStillHasExactlyTwoProviderCyclesWithBothAdviceLayers(boolean traced) {
        Fixture fixture = fixture("Java standard library official documentation", false);
        assertEquals(List.of(), invoke(fixture, traced));
        assertProviderCounts(fixture, 2, 2, 1);
        assertEmptyAdviceSuppressed();
        assertBodyInvocations(fixture, traced);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulPrimarySearchDoesNotInvokeFallbackProviders(boolean traced) {
        Fixture fixture = fixture("", true);
        List<String> result = invoke(fixture, traced);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(value -> value.contains("docs.oracle.com")));
        assertProviderCounts(fixture, 1, 0, 0);
        assertBodyInvocations(fixture, traced);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privacyBlockedRequestRemainsZeroProviderCallsThroughBothAdviceLayers(boolean traced) {
        Fixture fixture = fixture("Java reference expanded", false);
        ReflectionTestUtils.setField(fixture.target(), "blockWebSearch", true);
        assertEquals(List.of(), invoke(fixture, traced));
        assertProviderCounts(fixture, 0, 0, 0);
        assertEquals(Boolean.TRUE, TraceStore.get("privacy.web.blocked"));
        assertEmptyAdviceSuppressed();
        assertBodyInvocations(fixture, traced);
    }

    private static List<String> invoke(Fixture fixture, boolean traced) {
        List<String> result = traced
                ? fixture.proxy().searchWithTrace(QUERY, TOP_K).snippets()
                : fixture.proxy().search(QUERY, TOP_K);
        assertEquals(Boolean.TRUE, TraceStore.get("web.boundedRoute"));
        assertEquals("boundedRoute", TraceStore.get("web.failsoft.extraCalls.skipped.reason"));
        return result;
    }

    private static void assertEmptyAdviceSuppressed() {
        assertEquals("boundedRoute", TraceStore.get("web.failsoft.hybridEmptyFallback.skipped.reason"));
    }

    private static void assertBodyInvocations(Fixture fixture, boolean traced) {
        // searchWithTrace has one direct self-call to search on the bounded path.
        verify(fixture.target(), times(1)).search(anyString(), eq(TOP_K));
        verify(fixture.target(), times(traced ? 1 : 0)).searchWithTrace(anyString(), eq(TOP_K));
    }

    private static void assertProviderCounts(Fixture fixture, int brave, int naver, int expansion) {
        assertEquals(brave, searchCalls(fixture.brave()));
        assertEquals(naver, searchCalls(fixture.naver()));
        verify(fixture.gateway(), times(expansion)).expandSearchQueryOnce(anyString());
    }

    private static long searchCalls(Object provider) {
        // Count all provider search overloads, including any unexpected fallback API.
        return mockingDetails(provider).getInvocations().stream()
                .filter(call -> call.getMethod().getName().startsWith("search"))
                .count();
    }

    private static Fixture fixture(String expanded, boolean primaryHit) {
        TraceStore.clear();
        GuardContextHolder.clear();
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(naver.isEnabled()).thenReturn(true);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.isCoolingDown()).thenReturn(false);
        when(naver.searchSnippetsSync(anyString(), eq(TOP_K))).thenReturn(List.of());
        when(brave.search(anyString(), eq(TOP_K))).thenReturn(primaryHit
                ? List.of("[WEB:DOCS|CRED:TRUSTED] Java API official reference https://docs.oracle.com/en/java/javase/17/")
                : List.of());
        String reason = expanded.isBlank() ? "purpose-disabled" : "";
        when(gateway.expandSearchQueryOnce(anyString())).thenReturn(Mono.just(
                new GeminiGateway.SearchExpansion(expanded, new GeminiGateway.ProviderStatus(
                        "gemini", "search-expansion", "gemini-fixture", reason.isEmpty(), true,
                        1, 200, 1L, false, "allowed", reason, ""))));
        HybridWebSearchProvider target = spy(new HybridWebSearchProvider(naver, brave));
        ReflectionTestUtils.setField(target, "boundedFallbackEnabled", true);
        ReflectionTestUtils.setField(target, "geminiGateway", gateway);

        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        props.setAllowExtraSearchCalls(true);
        props.setMaxExtraSearchCalls(2);
        WebFailSoftSearchAspect outer = new WebFailSoftSearchAspect(props, new RuleBasedQueryAugmenter(props),
                null, null, null, null, null, null, null, new FixedProvider<>(null));
        HybridWebSearchEmptyFallbackAspect inner = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(), new FixedProvider<>(naver), new FixedProvider<>(brave),
                new FixedProvider<>(null), new FixedProvider<>(null), new FixedProvider<>(null));
        Order outerOrder = AnnotationUtils.findAnnotation(WebFailSoftSearchAspect.class, Order.class);
        Order innerOrder = AnnotationUtils.findAnnotation(HybridWebSearchEmptyFallbackAspect.class, Order.class);
        assertNotNull(outerOrder);
        assertNotNull(innerOrder);
        assertTrue(outerOrder.value() < innerOrder.value());
        // Compose these two real aspects in their declared production order.
        // Other application aspects and full auto-configuration are outside this fixture.
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(outer);
        proxyFactory.addAspect(inner);
        HybridWebSearchProvider proxy = proxyFactory.getProxy();
        assertTrue(AopUtils.isAopProxy(proxy));
        return new Fixture(target, proxy, naver, brave, gateway);
    }

    private record Fixture(HybridWebSearchProvider target, HybridWebSearchProvider proxy,
                           NaverSearchService naver, BraveSearchService brave, GeminiGateway gateway) { }

    private record FixedProvider<T>(T value) implements ObjectProvider<T> {
        @Override public T getObject(Object... args) { return value; }
        @Override public T getObject() { return value; }
        @Override public T getIfAvailable() { return value; }
        @Override public T getIfUnique() { return value; }
    }
}
