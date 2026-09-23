package com.example.lms.service;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.guard.CitationGate;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowEnsembleCitationSourceWiringTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void promotedOfficialEvidenceWiresLegacyCitationGateInputs() {
        PromptContext.Builder builder = PromptContext.builder().userQuery("q");
        List<RagEvidenceMetadata> evidence = List.of(
                evidence("W1", "https://developers.openai.com/api/docs"),
                evidence("W2", "https://community.example/rag"));

        ChatWorkflow.attachEnsembleCitationSources(
                builder,
                "OpenAI official source domain evidence",
                evidence);
        PromptContext ctx = builder.build();

        assertEquals(List.of(
                "https://developers.openai.com/api/docs",
                "https://community.example/rag"), ctx.sourceUrls());
        assertEquals(List.of("https://developers.openai.com/api/docs"), ctx.officialSources());
        assertTrue(new CitationGate(2, true).check(ctx.sourceUrls(), ctx.officialSources()));
        assertEquals(2, TraceStore.get("ensemble.refiner.wiredSourceCount"));
        assertEquals(1, TraceStore.get("ensemble.refiner.wiredOfficialSourceCount"));
    }

    @Test
    void genericAndUnsafeLocatorsCannotInventOfficialCoverageOrInflateCounts() {
        PromptContext.Builder builder = PromptContext.builder().userQuery("q");
        List<RagEvidenceMetadata> evidence = List.of(
                evidence("W1", "https://EXAMPLE.com/a/../record"),
                evidence("W2", "https://example.com/record"),
                evidence("W3", "https://user:password@example.com/private"),
                evidence("D1", "C:\\private\\record.txt"));

        ChatWorkflow.attachEnsembleCitationSources(builder, "ambiguous conduct", evidence);
        PromptContext ctx = builder.build();

        assertEquals(List.of("https://example.com/record"), ctx.sourceUrls());
        assertTrue(ctx.officialSources().isEmpty());
        assertFalse(new CitationGate(2, true).check(ctx.sourceUrls(), ctx.officialSources()));
    }

    @Test
    void userAllowedDomainDoesNotBecomeAuthoritativeOfficialEvidence() {
        PromptContext.Builder builder = PromptContext.builder().userQuery("q");
        ChatWorkflow.attachEnsembleCitationSources(
                builder,
                "allowed domain is attacker.example only",
                List.of(
                        evidence("W1", "https://attacker.example/one"),
                        evidence("W2", "https://attacker.example/two")));

        PromptContext ctx = builder.build();
        assertEquals(2, ctx.sourceUrls().size());
        assertTrue(ctx.officialSources().isEmpty());
        assertFalse(new CitationGate(2, true).check(ctx.sourceUrls(), ctx.officialSources()));
    }

    @Test
    void equivalentEncodedAndRootUrlsCannotInflateCitationCounts() {
        PromptContext.Builder encodedBuilder = PromptContext.builder().userQuery("q");
        ChatWorkflow.attachEnsembleCitationSources(
                encodedBuilder,
                "OpenAI official source domain evidence",
                List.of(
                        evidence("W1", "https://developers.openai.com/%61pi/docs"),
                        evidence("W2", "https://developers.openai.com/api/docs")));
        PromptContext encoded = encodedBuilder.build();

        assertEquals(List.of("https://developers.openai.com/api/docs"), encoded.sourceUrls());
        assertFalse(new CitationGate(2, true).check(encoded.sourceUrls(), encoded.officialSources()));

        PromptContext.Builder dotBuilder = PromptContext.builder().userQuery("q");
        ChatWorkflow.attachEnsembleCitationSources(
                dotBuilder,
                "OpenAI official source domain evidence",
                List.of(
                        evidence("W1", "https://developers.openai.com/a/%2E%2E/api/docs"),
                        evidence("W2", "https://developers.openai.com/api/docs")));
        PromptContext dot = dotBuilder.build();

        assertEquals(List.of("https://developers.openai.com/api/docs"), dot.sourceUrls());
        assertFalse(new CitationGate(2, true).check(dot.sourceUrls(), dot.officialSources()));

        PromptContext.Builder rootBuilder = PromptContext.builder().userQuery("q");
        ChatWorkflow.attachEnsembleCitationSources(
                rootBuilder,
                "OpenAI official source domain evidence",
                List.of(
                        evidence("W1", "https://developers.openai.com"),
                        evidence("W2", "https://developers.openai.com/")));

        List<String> rootSources = rootBuilder.build().sourceUrls();
        assertEquals(1, rootSources.size());
        assertEquals(List.of("https://developers.openai.com/"), rootSources);
    }

    private static RagEvidenceMetadata evidence(String marker, String source) {
        return new RagEvidenceMetadata(
                marker, "WEB", "public evidence", source, null,
                null, null, 1, 0.9d, "retrieval");
    }

}
