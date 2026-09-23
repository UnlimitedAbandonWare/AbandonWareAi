package com.example.lms.service.rag;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.CitationGate;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.guard.EvidenceGate;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagEvidenceUrlPathEncodingTest {
    @AfterEach
    void clear() { TraceStore.clear(); GuardContextHolder.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"/manual%20one.pdf", "/a%2Fb", "/%ED%95%9C%EA%B8%80",
            "/p%3Fq%23f", "/literal%2520", "/%7ename", "/escaped%25value",
            "//other.example/a%2Fb", "/", "", "/plain/path"})
    void publicPromotionPreservesRawPathWithoutLeakingAuthorityQueryOrFragment(String rawPath) {
        String input = "HTTPS://fixture-user:fixture-password@example.com:8443" + rawPath
                + "?ownerToken=fixture-query-value#fixture-fragment";
        String expected = "https://example.com:8443" + rawPath;
        RagEvidenceAttributionService service = service();

        List<RagEvidenceMetadata> evidence = promote(service, input);

        assertEquals(1, evidence.size());
        String actual = evidence.get(0).source();
        assertEquals(expected, actual);
        assertEquals(URI.create(input).getRawPath(), URI.create(actual).getRawPath());
        assertNull(URI.create(actual).getUserInfo());
        assertNull(URI.create(actual).getRawQuery());
        assertNull(URI.create(actual).getRawFragment());
        String appendix = service.appendFinalEvidenceAppendix("Fixture answer [W1].", evidence);
        assertTrue(appendix.contains(expected));
        String publicTrace = String.valueOf(TraceStore.get("rag.evidence.public"));
        assertFalse(publicTrace.contains("fixture-password"));
        assertFalse(publicTrace.contains("fixture-query-value"));
        assertFalse(publicTrace.contains("fixture-fragment"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///fixture/path", "https:/missing-host", "https://example.com/%broken"})
    void nonHttpHostlessOrMalformedLocatorStillDoesNotPromote(String url) {
        assertTrue(promote(service(), url).isEmpty());
    }

    private static List<RagEvidenceMetadata> promote(RagEvidenceAttributionService service, String url) {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);
        Content web = Content.from(TextSegment.from("fixture evidence body",
                Metadata.from(Map.of("title", "Fixture", "url", url))));
        return service.promoteForPrompt("fixture", List.of(web), List.of(), List.of(), QueryDomain.GENERAL, false);
    }

    private static RagEvidenceAttributionService service() {
        EvidenceGate gate = new EvidenceGate(0, 0, 0, 0, false) {
            @Override
            public boolean hasSufficientCoverage(String question, List<String> rag, List<String> memory,
                    List<String> kb, boolean followUp, QueryDomain domain) { return true; }
        };
        return new RagEvidenceAttributionService(gate, new CitationGate(), null);
    }
}
