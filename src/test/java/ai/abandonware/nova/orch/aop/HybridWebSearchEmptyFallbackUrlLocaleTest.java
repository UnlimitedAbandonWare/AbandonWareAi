package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridWebSearchEmptyFallbackUrlLocaleTest {
    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void fullSnippetPathKeepsLowTrustQuotaIndependentOfLocale(String languageTag) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            List<String> snippets = List.of("https://INVEN.CO.KR/one", "https://TISTORY.COM/two",
                    "https://EXAMPLE.COM/official");

            assertEquals(List.of(snippets.get(0), snippets.get(2)),
                    HybridWebSearchEmptyFallbackSupport.maybeFilterLowTrustForNofilterSafe(
                            snippets, 3, true, 0.34d, 1d, "fixture"));
        } finally {
            Locale.setDefault(previous);
            TraceStore.clear();
        }
    }

    @Test
    void disabledFilterPreservesAllSnippets() {
        List<String> snippets = List.of("https://INVEN.CO.KR/one", "https://TISTORY.COM/two");
        try {
            assertEquals(snippets, HybridWebSearchEmptyFallbackSupport.maybeFilterLowTrustForNofilterSafe(
                    snippets, 3, false, 0.34d, 1d, "fixture"));
        } finally {
            TraceStore.clear();
        }
    }
}
