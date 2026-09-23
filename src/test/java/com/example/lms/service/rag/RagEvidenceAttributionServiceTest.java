package com.example.lms.service.rag;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.CitationGate;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvidenceAttributionServiceTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
        MDC.clear();
    }

    @Test
    void promotesOnlyGatePassedMetadataWithoutRawSnippets() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Content web = Content.from(TextSegment.from(
                "alpha citation body that must not be copied into public metadata",
                Metadata.from(Map.of(
                        "title", "Official Alpha",
                        "url", "https://example.com/alpha",
                        "lineStart", 12,
                        "score", 0.87d))));
        Document local = Document.from(
                "local alpha document body",
                new Metadata(Map.of(
                        "title", "Alpha Manual",
                        "filePath", "docs/alpha.md",
                        "lineStart", 3,
                        "lineEnd", 7,
                        "rerankConfidence", 0.71d)));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "alpha",
                List.of(web),
                List.of(),
                List.of(local),
                QueryDomain.GENERAL,
                false);

        assertEquals(2, promoted.size());
        assertEquals("W1", promoted.get(0).marker());
        assertEquals("https://example.com/alpha", promoted.get(0).source());
        assertEquals(12, promoted.get(0).lineStart());
        assertNull(promoted.get(0).lineEnd());
        assertEquals("D1", promoted.get(1).marker());
        assertEquals("docs/alpha.md", promoted.get(1).filePath());

        String publicDump = String.valueOf(TraceStore.get("rag.evidence.public"));
        assertTrue(publicDump.contains("Official Alpha"));
        assertFalse(publicDump.contains("must not be copied"));

        String answer = service.appendFinalEvidenceAppendix("Answer uses [W1].", promoted);
        assertTrue(answer.contains("### Sources"));
        assertTrue(answer.contains("[W1]"));
        assertTrue(answer.contains("confidence 0.870"));
        String appendedTwice = service.appendFinalEvidenceAppendix(answer, promoted);
        assertEquals(answer, appendedTwice);
        assertEquals(1, countOccurrences(appendedTwice, "### Sources"));

        String fileAnswer = service.appendFinalEvidenceAppendix("Answer uses [D1].", promoted);
        assertFalse(fileAnswer.contains("docs/alpha.md"));
        assertTrue(fileAnswer.contains("pathHash=" + SafeRedactor.hashValue("docs/alpha.md")));
    }

    @Test
    void blockedGateProducesNoPublicEvidenceAndRecordsReason() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(false);
        Content web = Content.from(TextSegment.from(
                "alpha body",
                Metadata.from(Map.of("title", "Alpha", "url", "https://example.com/a"))));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "alpha",
                List.of(web),
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertTrue(promoted.isEmpty());
        assertEquals("evidence_gate_blocked", TraceStore.get("rag.evidence.promotion.disabledReason"));
        assertEquals(List.of(), TraceStore.get("rag.evidence.public"));
    }

    @Test
    void detailedResultDistinguishesCompletedEmptyFromGateFailure() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService.PromotionResult completedEmpty = newService(true)
                .promoteForPromptDetailed(
                        "alpha",
                        List.of(),
                        List.of(),
                        List.of(),
                        QueryDomain.GENERAL,
                        false);

        assertEquals(RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY,
                completedEmpty.status());
        assertEquals(RagEvidenceAttributionService.PromotionReason.NO_CITABLE_LOCATOR,
                completedEmpty.reason());
        assertEquals(0, completedEmpty.retrievalCandidateCount());
        assertEquals(0, completedEmpty.retrievalCitableLocatorCount());
        assertTrue(completedEmpty.evidence().isEmpty());

        Content web = Content.from(TextSegment.from(
                "alpha body",
                Metadata.from(Map.of("url", "https://example.com/a"))));
        RagEvidenceAttributionService failingService = new RagEvidenceAttributionService(
                new ThrowingEvidenceGate(),
                new CitationGate(),
                null);

        RagEvidenceAttributionService.PromotionResult failed = failingService.promoteForPromptDetailed(
                "alpha",
                List.of(web),
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertEquals(RagEvidenceAttributionService.PromotionStatus.FAILED, failed.status());
        assertEquals(RagEvidenceAttributionService.PromotionReason.GATE_EXCEPTION, failed.reason());
        assertEquals(1, failed.webCandidateCount());
        assertEquals(1, failed.webCitableLocatorCount());
        assertTrue(failed.evidence().isEmpty());
        assertEquals("gate_exception", TraceStore.get("rag.evidence.promotion.disabledReason"));
    }

    @Test
    void detailedPromotionCarriesPerLaneCountsAndDefensivelyCopiesEvidence() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);
        RagEvidenceAttributionService service = newService(true);
        Content web = Content.from(TextSegment.from(
                "web body",
                Metadata.from(Map.of("url", "https://example.com/web"))));
        Content vectorWithoutLocator = Content.from("vector body without locator");
        Document local = Document.from(
                "local body",
                new Metadata(Map.of("filePath", "docs/local.md")));

        RagEvidenceAttributionService.PromotionResult result = service.promoteForPromptDetailed(
                "body",
                List.of(web),
                List.of(vectorWithoutLocator),
                List.of(local),
                QueryDomain.GENERAL,
                false);

        assertEquals(RagEvidenceAttributionService.PromotionStatus.PROMOTED, result.status());
        assertEquals(RagEvidenceAttributionService.PromotionReason.PROMOTED, result.reason());
        assertEquals(1, result.webCandidateCount());
        assertEquals(1, result.webCitableLocatorCount());
        assertEquals(1, result.vectorCandidateCount());
        assertEquals(0, result.vectorCitableLocatorCount());
        assertEquals(1, result.localCandidateCount());
        assertEquals(1, result.localCitableLocatorCount());
        assertEquals(2, result.retrievalCandidateCount());
        assertEquals(1, result.retrievalCitableLocatorCount());
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().clear());

        assertEquals(result.evidence(), service.promoteForPrompt(
                "body",
                List.of(web),
                List.of(vectorWithoutLocator),
                List.of(local),
                QueryDomain.GENERAL,
                false));
    }

    @Test
    void promotionResultRejectsContradictoryAuthorityStates() {
        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "W1", "WEB", "title", "https://example.com", null,
                null, null, 1, null, "unavailable");
        RagEvidenceMetadata uncitable = new RagEvidenceMetadata(
                "W2", "WEB", "title", null, null,
                null, null, 1, null, "unavailable");

        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY,
                RagEvidenceAttributionService.PromotionReason.NO_CITABLE_LOCATOR,
                List.of(), -1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY,
                RagEvidenceAttributionService.PromotionReason.NO_CITABLE_LOCATOR,
                List.of(), 0, 1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.PROMOTED,
                RagEvidenceAttributionService.PromotionReason.PROMOTED,
                List.of(), 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.PROMOTED,
                RagEvidenceAttributionService.PromotionReason.PROMOTED,
                List.of(evidence), 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.FAILED,
                RagEvidenceAttributionService.PromotionReason.GATE_EXCEPTION,
                List.of(evidence), 1, 1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY,
                RagEvidenceAttributionService.PromotionReason.PROMOTED,
                List.of(), 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY,
                RagEvidenceAttributionService.PromotionReason.CALLER_FAILURE,
                List.of(), 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.PROMOTED,
                RagEvidenceAttributionService.PromotionReason.PROMOTED,
                List.of(uncitable), 1, 1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.PROMOTED,
                RagEvidenceAttributionService.PromotionReason.PROMOTED,
                List.of(evidence), 0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.FAILED,
                RagEvidenceAttributionService.PromotionReason.CALLER_FAILURE,
                List.of(), 1, 0, 0, 0, 0, 0));
    }

    @Test
    void gatePassedBatchStillPromotesOnlyCandidatesWithCitableLocator() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Content titleOnly = Content.from(TextSegment.from(
                "alpha body with title only",
                Metadata.from(Map.of("title", "Title Only Alpha"))));
        Content citable = Content.from(TextSegment.from(
                "alpha body with public URL",
                Metadata.from(Map.of(
                        "title", "Citable Alpha",
                        "url", "https://example.com/alpha?ownerToken=secret#frag"))));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "alpha",
                List.of(titleOnly, citable),
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertEquals(1, promoted.size());
        assertEquals("W2", promoted.get(0).marker());
        assertEquals("https://example.com/alpha", promoted.get(0).source());
        assertEquals(2, TraceStore.get("rag.evidence.promotion.candidateCount"));
        assertEquals(1, TraceStore.get("rag.evidence.promotion.citableLocatorCount"));
        String publicDump = String.valueOf(TraceStore.get("rag.evidence.public"));
        assertTrue(publicDump.contains("Citable Alpha"));
        assertFalse(publicDump.contains("Title Only Alpha"));
        assertFalse(publicDump.contains("ownerToken"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    void recoversCitableLocatorFromWebContentTextWhenMetadataIsMissing() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Content web = Content.from("""
                Official docs excerpt with field values.
                model: "gpt-5.5"
                { type: "web_search" }

                [출처] https://developers.openai.com/api/docs/guides/tools-web-search?ownerToken=secret#frag
                """);

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "OpenAI Responses API web search tool type and example model value",
                List.of(web),
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertEquals(1, promoted.size());
        assertEquals("W1", promoted.get(0).marker());
        assertEquals("https://developers.openai.com/api/docs/guides/tools-web-search", promoted.get(0).source());
        assertEquals(1, TraceStore.get("rag.evidence.promotion.citableLocatorCount"));
        assertEquals(1, TraceStore.get("rag.evidence.promotion.promotedCount"));
        assertFalse(String.valueOf(TraceStore.get("rag.evidence.public")).contains("ownerToken"));
    }

    @Test
    void recoversCitableLocatorFromLocalDocumentTextWhenMetadataIsMissing() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Document localOfficialFallback = Document.from("""
                Official fallback evidence from the docs result pool.
                [source] https://developers.openai.com/api/docs/guides/tools-web-search?ownerToken=secret#frag
                """, new Metadata(Map.of("title", "OpenAI web search docs fallback")));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "RAG web-search verification: answer only from official OpenAI and Supabase docs/changelog evidence; "
                        + "if official evidence is missing say evidence_needed.",
                List.of(),
                List.of(),
                List.of(localOfficialFallback),
                QueryDomain.GENERAL,
                false);

        assertEquals(1, promoted.size());
        assertEquals("D1", promoted.get(0).marker());
        assertEquals("https://developers.openai.com/api/docs/guides/tools-web-search", promoted.get(0).source());
        assertEquals(1, TraceStore.get("rag.evidence.promotion.citableLocatorCount"));
        assertEquals(1, TraceStore.get("rag.evidence.promotion.promotedCount"));
        assertFalse(String.valueOf(TraceStore.get("rag.evidence.public")).contains("ownerToken"));
    }

    @Test
    void cheapSearchModePromotesSingleCitableEvidenceWithoutExtraCitationFanout() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setCheapSearchMode(true);
        ctx.setMinCitations(2);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Content citable = Content.from(TextSegment.from(
                "light mode body with one available public source",
                Metadata.from(Map.of("title", "Light Source", "url", "https://example.com/light"))));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "light query",
                List.of(citable),
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertEquals(1, promoted.size());
        assertEquals("W1", promoted.get(0).marker());
        assertEquals("https://example.com/light", promoted.get(0).source());
        assertEquals(1, TraceStore.get("rag.evidence.promotion.citationMin"));
        assertEquals(true, TraceStore.get("rag.evidence.promotion.citationGateMinPassed"));
    }

    @Test
    void namedOfficialSourcePromptPromotesOnlyMatchingOfficialWebEvidenceWhenAvailable() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        List<Content> webDocs = List.of(
                Content.from(TextSegment.from(
                        "Community unrelated result",
                        Metadata.from(Map.of("title", "Community", "url", "https://github.com/onestardao/WFGY/blob/main/ProblemMap/README.md")))),
                Content.from(TextSegment.from(
                        "OpenAI official tool orchestration",
                        Metadata.from(Map.of("title", "OpenAI Responses API", "url", "https://developers.openai.com/cookbook/examples/responses_api/responses_api_tool_orchestration")))),
                Content.from(TextSegment.from(
                        "Vendor tutorial unrelated to the requested products",
                        Metadata.from(Map.of("title", "NVIDIA VSS FAQ", "url", "https://docs.nvidia.com/vss/2.3.0/content/faq.html")))),
                Content.from(TextSegment.from(
                        "Supabase MCP read-only setup",
                        Metadata.from(Map.of("title", "Supabase MCP", "url", "https://supabase.com/docs/guides/ai-tools/mcp")))));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "Compare OpenAI Responses API web_search tooling and Supabase MCP read_only project_ref setup using official/external sources only.",
                webDocs,
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertEquals(2, promoted.size(),
                "named official-source prompts should not promote unrelated citable web URLs when matching official sources exist");
        assertEquals("W2", promoted.get(0).marker());
        assertEquals("https://developers.openai.com/cookbook/examples/responses_api/responses_api_tool_orchestration",
                promoted.get(0).source());
        assertEquals("W4", promoted.get(1).marker());
        assertEquals("https://supabase.com/docs/guides/ai-tools/mcp", promoted.get(1).source());
        assertEquals(true, TraceStore.get("rag.evidence.promotion.namedOfficialFiltered"));
    }

    @Test
    void koreanNamedSourceDomainPromptPromotesOnlyMatchingOfficialWebEvidenceWhenAvailable() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        List<Content> webDocs = List.of(
                Content.from(TextSegment.from(
                        "Community tutorial result",
                        Metadata.from(Map.of("title", "Community", "url", "https://glukhov.org/post/2025/rag-overview")))),
                Content.from(TextSegment.from(
                        "OpenAI official Responses API tools",
                        Metadata.from(Map.of("title", "OpenAI Responses API", "url", "https://developers.openai.com/api/docs/guides/tools-web-search")))),
                Content.from(TextSegment.from(
                        "Generic cloud blog",
                        Metadata.from(Map.of("title", "Cloud Blog", "url", "https://tech.ktcloud.com/posts/rag")))),
                Content.from(TextSegment.from(
                        "Supabase official MCP setup",
                        Metadata.from(Map.of("title", "Supabase MCP", "url", "https://supabase.com/docs/guides/ai-tools/mcp")))));

        List<RagEvidenceMetadata> promoted = service.promoteForPrompt(
                "OpenAI Responses API web_search/file_search/computer_use and Supabase MCP "
                        + "read_only/project_ref setup latest changes reflected in answer evidence, "
                        + "\uCD9C\uCC98 \uB3C4\uBA54\uC778 2\uAC1C\uC640 \uADFC\uAC70\uB85C \uAC80\uC99D\uD574\uC918.",
                webDocs,
                List.of(),
                List.of(),
                QueryDomain.GENERAL,
                false);

        assertEquals(2, promoted.size(),
                "Korean source-domain probes should not promote generic web evidence when named official sources exist");
        assertEquals("https://developers.openai.com/api/docs/guides/tools-web-search", promoted.get(0).source());
        assertEquals("https://supabase.com/docs/guides/ai-tools/mcp", promoted.get(1).source());
        assertEquals(true, TraceStore.get("rag.evidence.promotion.namedOfficialFiltered"));
    }

    @Test
    void markerMismatchDoesNotAppendFallbackEvidence() {
        RagEvidenceAttributionService service = newService(true);
        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "W2",
                "WEB",
                "Citable Alpha",
                "https://example.com/alpha",
                null,
                null,
                null,
                2,
                null,
                "unavailable");

        String answer = "Answer cites [W1].";
        String withAppendix = service.appendFinalEvidenceAppendix(answer, List.of(evidence));

        assertEquals(answer, withAppendix);
        assertEquals(true, TraceStore.get("rag.evidence.appendix.markerMismatch"));
        assertEquals(1, TraceStore.get("rag.evidence.appendix.requestedMarkerCount"));
        assertEquals(0, TraceStore.get("rag.evidence.appendix.matchedMarkerCount"));
    }

    @Test
    void appendixDetectionIgnoresFencedAndPrefixOnlyHeadings() {
        RagEvidenceAttributionService service = newService(true);
        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "W1",
                "WEB",
                "Citable Alpha",
                "https://example.com/alpha",
                null,
                null,
                null,
                1,
                null,
                "unavailable");

        for (String answer : List.of(
                "Answer cites [W1].\r\n\r\n```markdown\r\n### Evidence\r\n```",
                "Answer cites [W1].\n\n### Evidence-based Analysis")) {
            String withAppendix = service.appendFinalEvidenceAppendix(answer, List.of(evidence));

            assertTrue(withAppendix.contains("### Sources"), withAppendix);
        }
    }

    @Test
    void breadcrumbsStoreCorrelationAsHashOnly() {
        MDC.put("x-request-id", "raw-evidence-request");
        MDC.put("sessionId", "raw-evidence-session");
        TraceStore.put("trace.id", "raw-evidence-trace");
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(1);
        GuardContextHolder.set(ctx);

        RagEvidenceAttributionService service = newService(true);
        Content web = Content.from(TextSegment.from(
                "alpha body",
                Metadata.from(Map.of("title", "Alpha", "url", "https://example.com/a"))));

        service.promoteForPrompt("alpha", List.of(web), List.of(), List.of(), QueryDomain.GENERAL, false);

        Object events = TraceStore.get("ml.breadcrumbs.v1");
        assertTrue(events instanceof List<?>);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) events).get(0);
        assertEquals(SafeRedactor.hashValue("raw-evidence-request"), row.get("requestId"));
        assertEquals(SafeRedactor.hashValue("raw-evidence-session"), row.get("sessionId"));
        assertTrue(row.get("data") instanceof Map<?, ?>);
        Map<?, ?> data = (Map<?, ?>) row.get("data");
        assertEquals(Boolean.TRUE, data.get("queryRedacted"));
        assertEquals(Boolean.TRUE, TraceStore.get("cihRag.breadcrumb.queryRedacted"));
        assertEquals(SafeRedactor.hashValue("raw-evidence-trace"), TraceStore.get("rag.control.last.breadcrumbId"));
        assertFalse(row.toString().contains("raw-evidence-request"));
        assertFalse(row.toString().contains("raw-evidence-session"));
        assertFalse(String.valueOf(TraceStore.get("rag.control.last.breadcrumbId")).contains("raw-evidence-trace"));
        assertFalse(String.valueOf(TraceStore.get("orch.events.v1")).contains("raw-evidence-trace"));
    }

    @Test
    void promotionDisabledReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java"));

        assertFalse(source.contains("TraceStore.put(\"rag.evidence.promotion.disabledReason\", reason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"rag.evidence.promotion.disabledReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"rag.evidence.promotion.disabledReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void ragEvidenceAttributionServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "evidence attribution fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void numericEvidenceMetadataHelpersDropNonFiniteNumbers() throws Exception {
        Method toInt = RagEvidenceAttributionService.class.getDeclaredMethod("toInt", Object.class);
        Method toDouble = RagEvidenceAttributionService.class.getDeclaredMethod("toDouble", Object.class);
        toInt.setAccessible(true);
        toDouble.setAccessible(true);

        assertNull(toInt.invoke(null, Double.POSITIVE_INFINITY));
        assertNull(toInt.invoke(null, Double.NaN));
        assertNull(toDouble.invoke(null, Double.NEGATIVE_INFINITY));
        assertNull(toDouble.invoke(null, "Infinity"));
    }

    private static RagEvidenceAttributionService newService(boolean evidenceAllowed) {
        return new RagEvidenceAttributionService(
                new StubEvidenceGate(evidenceAllowed),
                new CitationGate(),
                null);
    }

    private static final class StubEvidenceGate extends EvidenceGate {
        private final boolean allowed;

        StubEvidenceGate(boolean allowed) {
            super(0.0d, 0.0d, 0.0d, 0.0d, false);
            this.allowed = allowed;
        }

        @Override
        public boolean hasSufficientCoverage(
                String question,
                List<String> ragLines,
                List<String> memoryLines,
                List<String> kbLines,
                boolean isFollowUp,
                QueryDomain domain) {
            return allowed;
        }
    }

    private static final class ThrowingEvidenceGate extends EvidenceGate {
        ThrowingEvidenceGate() {
            super(0.0d, 0.0d, 0.0d, 0.0d, false);
        }

        @Override
        public boolean hasSufficientCoverage(
                String question,
                List<String> ragLines,
                List<String> memoryLines,
                List<String> kbLines,
                boolean isFollowUp,
                QueryDomain domain) {
            throw new IllegalStateException("sensitive dynamic failure");
        }
    }
}
