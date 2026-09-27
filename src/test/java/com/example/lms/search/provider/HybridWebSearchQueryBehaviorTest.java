package com.example.lms.search.provider;

import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HybridWebSearchQueryBehaviorTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    static Stream<Arguments> backupCases() {
        return Stream.of(
                Arguments.of("RTX 5090 가격 알려줘", true, true, "RTX 5090 가격"),
                Arguments.of("RTX 5090 가격 알려줘", true, false, "RTX 5090"),
                Arguments.of("RTX 5090 가격 알려줘", false, true, "RTX 5090 가격"),
                Arguments.of("RTX 5090 가격 알려줘", false, false, "RTX 5090 가격"),
                Arguments.of("site:example.test Alpha alpha beta gamma delta epsilon", true, false,
                        "site:example.test Alpha beta gamma delta"),
                Arguments.of("(\"SITE:example.test\") alpha Alpha 알려줘", true, true,
                        "SITE:example.test alpha"),
                Arguments.of("filetype:pdf intitle:Guide Alpha Alpha beta gamma delta epsilon", true, false,
                        "filetype:pdf intitle:Guide Alpha beta gamma delta"),
                Arguments.of("site: alpha beta gamma delta epsilon zeta eta", true, false,
                        "site: alpha beta gamma delta epsilon"),
                Arguments.of("one two three four five six seven eight", true, true,
                        "one two three four five six"),
                Arguments.of("2026", true, true, ""),
                Arguments.of("알려줘", true, true, ""),
                Arguments.of("ordinary unchanged query", true, true, ""));
    }

    @ParameterizedTest
    @MethodSource("backupCases")
    void backupSelectionPreservesProviderPreferenceOperatorsAndFallbackOrder(
            String query, boolean braveEnabled, boolean naverEnabled, String expected) {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        when(brave.isEnabled()).thenReturn(braveEnabled);
        when(naver.isEnabled()).thenReturn(naverEnabled);
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);

        assertEquals(expected, ReflectionTestUtils.invokeMethod(provider, "buildBackupQuery", query));
        assertEquals(expected, HybridSearchQueryPolicy.buildBackupQuery(query, braveEnabled && !naverEnabled));
        verify(brave).isEnabled();
        verify(naver).isEnabled();
        verifyNoMoreInteractions(brave, naver);
    }

    @Test
    void emptyBackupInputDoesNotProbeProviders() {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        assertEquals("", ReflectionTestUtils.invokeMethod(provider, "buildBackupQuery", (Object) null));
        assertEquals("", ReflectionTestUtils.invokeMethod(provider, "buildBackupQuery", "  "));
        verifyNoInteractions(brave, naver);
    }

    @Test
    void actualBoundedSearchUsesTheSelectedRewriteWithoutAddingProviderCalls() {
        BraveSearchService brave = mock(BraveSearchService.class);
        NaverSearchService naver = mock(NaverSearchService.class);
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(brave.isEnabled()).thenReturn(true);
        when(naver.isEnabled()).thenReturn(true);
        when(brave.searchWithMeta(anyString(), anyInt())).thenReturn(
                com.example.lms.service.web.BraveSearchResult.ok(List.of(), 1),
                com.example.lms.service.web.BraveSearchResult.ok(List.of("fixture result"), 1));
        var trace = new NaverSearchService.SearchTrace();
        trace.outcomeClass = "TRUE_ZERO";
        when(naver.searchWithTraceSync(anyString(), anyInt())).thenReturn(new NaverSearchService.SearchResult(List.of(), trace));
        when(gateway.expandSearchQueryOnce("RTX 5090 가격 알려줘"))
                .thenReturn(Mono.just(new GeminiGateway.SearchExpansion("RTX 5090 가격 알려줘 공식 자료", null)));
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", true);
        ReflectionTestUtils.setField(provider, "geminiGateway", gateway);

        NaverSearchService.SearchResult result = provider.searchWithTrace("RTX 5090 가격 알려줘", 4);

        assertEquals(List.of("fixture result"), result.snippets());
        assertEquals("BOUNDED:research-hit", result.trace().steps.get(0).query);
        var ordered = inOrder(brave, naver, gateway);
        ordered.verify(brave).searchWithMeta("RTX 5090 가격 알려줘", 4);
        ordered.verify(naver).searchWithTraceSync("RTX 5090 가격 알려줘", 4);
        ordered.verify(gateway).expandSearchQueryOnce("RTX 5090 가격 알려줘");
        ordered.verify(brave).searchWithMeta("RTX 5090 가격 알려줘 공식 자료", 4);
        verify(brave, times(2)).searchWithMeta(anyString(), anyInt());
        verify(naver, times(1)).searchWithTraceSync(anyString(), anyInt());
        verify(gateway, times(1)).expandSearchQueryOnce(anyString());
    }
}
