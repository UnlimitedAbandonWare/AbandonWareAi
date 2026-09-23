package com.example.lms.search.probe;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.energy.ContradictionScorer;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSignalsTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void evidenceSignalsDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/probe/EvidenceSignals.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "evidence signal parsing needs safe breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void metadataScoreParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/probe/EvidenceSignals.java"),
                StandardCharsets.UTF_8);
        String parserCall = "double d = Double.parseDouble(s.trim());";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "metadata score parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 220));
        assertFalse(window.contains("catch (Exception"),
                "metadata score parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "metadata score parser must not swallow Throwable");
        assertTrue(window.contains("catch (NumberFormatException"),
                "metadata score parser should only catch NumberFormatException");
    }

    @Test
    void malformedMetadataScoreLeavesStableInvalidNumberTrace() {
        String rawScore = "private-score-token";
        Content doc = Content.from(TextSegment.from("Rare official evidence source: one",
                Metadata.from(Map.of(
                        "retrieval_lane", "BQ",
                        "retrieval_stage", "selfask3way",
                        "url", "https://official-a.example/a",
                        "source", "web:selfask",
                        "grandas_base_score", rawScore))));

        EvidenceSignals.LaneEvidenceSignals signal = EvidenceSignals.computeLane(
                "BQ",
                List.of(doc),
                null,
                null,
                1);

        assertEquals(1, signal.docCount());
        assertEquals("firstDouble.parse", TraceStore.get("evidenceSignals.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("evidenceSignals.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("evidenceSignals.suppressed.firstDouble.parse"));
        assertEquals("invalid_number", TraceStore.get("evidenceSignals.suppressed.firstDouble.parse.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawScore));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
    }

    @Test
    void computesLaneEvidenceDuplicateContradictionAndConfidence() {
        List<Content> docs = List.of(
                content("BQ", "Project alpha ships in 2026. source: A", "https://official-a.example/a"),
                content("BQ", "Project alpha ships in 2027. source: B", "https://official-b.example/b"),
                content("BQ", "Project alpha definition. source: C", "https://docs.example/c"));

        EvidenceSignals.LaneEvidenceSignals signal = EvidenceSignals.computeLane(
                "BQ",
                docs,
                null,
                new FixedContradictionScorer(),
                3);

        assertEquals("domain_definition", signal.role());
        assertEquals(3, signal.docCount());
        assertEquals(1.0d, signal.evidenceRate());
        assertEquals(1.0d, signal.strongCitationRate());
        assertEquals(3, signal.distinctCitationCount());
        assertEquals(1.0d, signal.crossLaneSupportRate());
        assertEquals(0.0d, signal.duplicateRate());
        assertTrue(signal.contradictionRate() >= 0.3d);
        assertTrue(signal.grandasReadiness() >= 0.6d);
        assertTrue(signal.confidence() >= 0.6d);
    }

    @Test
    void groupsSignalsByRetrievalLane() {
        Map<String, EvidenceSignals.LaneEvidenceSignals> signals = EvidenceSignals.computeByLane(
                List.of(
                        content("ER", "Alias source: one", "https://docs.example/one"),
                        content("RC", "Relation source: two", "https://docs.example/two")),
                null,
                null,
                1);

        assertEquals("alias_synonym", signals.get("ER").role());
        assertEquals("relation_hypothesis", signals.get("RC").role());
    }

    @Test
    void providerMetadataOnlyIsNotStrongCitation() {
        List<Content> docs = List.of(
                Content.from(TextSegment.from("Provider-only snippet about project alpha.",
                        Metadata.from(Map.of(
                                "retrieval_lane", "BQ",
                                "retrieval_stage", "selfask3way",
                                "source", "web:selfask",
                                "provider", "naver")))));

        EvidenceSignals.LaneEvidenceSignals signal = EvidenceSignals.computeLane(
                "BQ",
                docs,
                null,
                null,
                1);

        assertEquals(1.0d, signal.evidenceRate());
        assertEquals(0.0d, signal.strongCitationRate());
        assertEquals(0, signal.distinctCitationCount());
        assertTrue(signal.grandasReadiness() < 0.6d);
    }

    @Test
    void tavilyProviderOnlyEvidenceDoesNotCollapseIntoDuplicateSourceKey() {
        List<Content> docs = List.of(
                providerOnlyContent("Tavily provider-only snippet alpha.", "tavily"),
                providerOnlyContent("Tavily provider-only snippet beta.", "tavily"));

        EvidenceSignals.LaneEvidenceSignals signal = EvidenceSignals.computeLane(
                "BQ",
                docs,
                null,
                null,
                1);

        assertEquals(1.0d, signal.evidenceRate());
        assertEquals(0.0d, signal.strongCitationRate());
        assertEquals(0, signal.distinctCitationCount());
        assertEquals(0.0d, signal.duplicateRate());
    }

    @Test
    void sparseUpperTailSignalSurfacesEvenWithLowCrossLaneSupport() {
        List<Content> docs = List.of(
                content("BQ", "Rare official evidence source: one", "https://official-a.example/a", 1.0d),
                content("BQ", "Secondary official evidence source: two", "https://official-b.example/b", 0.18d),
                content("BQ", "Tertiary official evidence source: three", "https://official-c.example/c", 0.17d));

        EvidenceSignals.LaneEvidenceSignals signal = EvidenceSignals.computeLane(
                "BQ",
                docs,
                List.of(),
                null,
                null,
                1);

        assertEquals(0.0d, signal.crossLaneSupportRate());
        assertTrue(signal.tailSignal() >= 0.70d);
    }

    @Test
    void contradictionReducesTailSignal() {
        List<Content> docs = List.of(
                content("BQ", "Rare official evidence says KPI improved in 2026. source: one",
                        "https://official-a.example/a", 1.0d),
                content("BQ", "Rare official evidence says KPI degraded in 2027. source: two",
                        "https://official-b.example/b", 0.18d));

        EvidenceSignals.LaneEvidenceSignals signal = EvidenceSignals.computeLane(
                "BQ",
                docs,
                List.of(),
                null,
                new FixedContradictionScorer(),
                1);

        assertTrue(signal.contradictionRate() >= 0.3d);
        assertTrue(signal.tailSignal() < 0.90d);
    }

    private static Content content(String lane, String text, String url) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "retrieval_lane", lane,
                "retrieval_stage", "selfask3way",
                "url", url,
                "source", "web:selfask"))));
    }

    private static Content content(String lane, String text, String url, double score) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "retrieval_lane", lane,
                "retrieval_stage", "selfask3way",
                "url", url,
                "source", "web:selfask",
                "grandas_base_score", score))));
    }

    private static Content providerOnlyContent(String text, String source) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "retrieval_lane", "BQ",
                "retrieval_stage", "selfask3way",
                "source", source))));
    }

    private static final class FixedContradictionScorer extends ContradictionScorer {
        @Override
        public double score(String a, String b) {
            if (String.valueOf(a).contains("2026") && String.valueOf(b).contains("2027")) {
                return 0.8d;
            }
            if (String.valueOf(a).contains("2027") && String.valueOf(b).contains("2026")) {
                return 0.8d;
            }
            return 0.2d;
        }
    }
}
