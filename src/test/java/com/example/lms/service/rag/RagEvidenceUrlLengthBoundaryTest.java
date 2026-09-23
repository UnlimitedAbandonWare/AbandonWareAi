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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagEvidenceUrlLengthBoundaryTest {
    private static final String ORIGIN = "https://example.com/";

    @AfterEach
    void clear() { TraceStore.clear(); GuardContextHolder.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"%20", "%2F", "%25", "%ED%95%9C"})
    void aValidEscapeCrossingTheLengthBoundaryIsNeverPublishedAsABrokenLocator(String escape) {
        String input = ORIGIN + "a".repeat(999 - ORIGIN.length()) + escape;
        assertDoesNotThrow(() -> URI.create(input));

        List<RagEvidenceMetadata> evidence = promote(input);

        assertTrue(evidence.isEmpty(), "Overlength WEB locators must not become truncated citations");
    }

    @Test
    void aLongPlainPathIsNotSilentlyChangedToAnotherResource() {
        String input = ORIGIN + "a".repeat(1001 - ORIGIN.length());
        assertTrue(promote(input).isEmpty());
    }

    @Test
    void rejectingAnUnrepresentableLocatorRetainsItsValidSibling() {
        String oversized = ORIGIN + "a".repeat(999 - ORIGIN.length()) + "%2F";
        String valid = ORIGIN + "valid%20document";

        List<RagEvidenceMetadata> evidence = promote(oversized, valid);

        assertEquals(1, evidence.size());
        assertEquals(valid, evidence.get(0).source());
    }

    @ParameterizedTest
    @ValueSource(ints = {999, 1000})
    void representableBoundaryPathsRemainExact(int length) {
        String input = ORIGIN + "a".repeat(length - ORIGIN.length());
        List<RagEvidenceMetadata> evidence = promote(input);
        assertEquals(1, evidence.size());
        assertEquals(input, evidence.get(0).source());
    }

    @Test
    void removedQueryDoesNotConsumeThePublicLocatorLengthBudget() {
        String publicUrl = ORIGIN + "a".repeat(1000 - ORIGIN.length());
        List<RagEvidenceMetadata> evidence = promote(publicUrl + "?fixture=" + "x".repeat(2000));
        assertEquals(1, evidence.size());
        assertEquals(publicUrl, evidence.get(0).source());
    }

    @Test
    void dtoPreservesSourceBudgetWithoutExpandingGenericPublicFields() {
        String source = ORIGIN + "a".repeat(1000 - ORIGIN.length());
        var row = metadata(source, "title".repeat(120));
        assertEquals(source, row.source());
        assertEquals("title".repeat(120).substring(0, 512) + "...", row.title());
    }

    @ParameterizedTest
    @ValueSource(strings = {"oversized", "malformed", "redacted"})
    void directDtoConstructionCannotReturnAChangedOrMalformedSource(String kind) {
        String source = switch (kind) {
            case "oversized" -> ORIGIN + "a".repeat(1001 - ORIGIN.length());
            case "malformed" -> ORIGIN + "%broken";
            default -> ORIGIN + "document?ownerToken=fixture-private-value";
        };
        var row = metadata(source, "Fixture");
        assertNull(row.source());
        assertFalse(row.toTraceMap().toString().contains("fixture-private-value"));
    }

    private static RagEvidenceMetadata metadata(String source, String title) {
        return new RagEvidenceMetadata("W1", "WEB", title, source, null,
                null, null, 1, null, null);
    }

    private static List<RagEvidenceMetadata> promote(String... urls) {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);
        EvidenceGate gate = new EvidenceGate(0, 0, 0, 0, false) {
            @Override
            public boolean hasSufficientCoverage(String question, List<String> rag, List<String> memory,
                    List<String> kb, boolean followUp, QueryDomain domain) { return true; }
        };
        var service = new RagEvidenceAttributionService(gate, new CitationGate(), null);
        List<Content> web = java.util.Arrays.stream(urls)
                .map(url -> Content.from(TextSegment.from("synthetic locator evidence",
                        Metadata.from(Map.of("title", "Fixture", "url", url)))))
                .toList();
        return service.promoteForPrompt("fixture", web, List.of(), List.of(), QueryDomain.GENERAL, false);
    }
}
