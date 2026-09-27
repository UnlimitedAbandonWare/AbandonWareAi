package com.example.lms.service.rag.fusion;

import com.example.lms.search.TraceStore;
import com.nova.protocol.fusion.NovaNextFusionService;
import com.nova.protocol.properties.NovaNextProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.Map.entry;

class WeightedReciprocalRankFuserTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void lowGrandasReadinessWeightCannotDominateTopRank() {
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(1, null, "");

        Content lowReadinessTopRank = content("low-readiness", "https://weak.example/a");
        Content highReadinessSecondSource = content("high-readiness", "https://strong.example/b");

        List<Content> fused = fuser.fuse(
                List.of(List.of(lowReadinessTopRank), List.of(highReadinessSecondSource)),
                List.of(0.05d, 1.0d),
                2);

        assertEquals("high-readiness", fused.get(0).textSegment().text());
        assertEquals("low-readiness", fused.get(1).textSegment().text());
    }

    @Test
    void queryLanguageHintGivesSmallBonusToAlignedSourceLanguage() {
        TraceStore.put("query.lang", "ko");
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(60, null, "");
        Content englishFirst = content("english source", "https://brave.example/a", Map.of(
                "source_lang", "en",
                "provider", "brave"));
        Content koreanSecond = content("korean source", "https://naver.example/b", Map.of(
                "source_lang", "ko",
                "provider", "naver"));

        List<Content> fused = fuser.fuse(List.of(List.of(englishFirst, koreanSecond)), List.of(1.0d), 2);

        assertEquals("korean source", fused.get(0).textSegment().text());
        assertEquals("english source", fused.get(1).textSegment().text());
    }

    @Test
    void canonicalizesTrackingUrlVariantsBeforeFusion() {
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(60, null, "");
        Content first = content("first source", "https://Docs.Example/RAG/?utm_source=news&fbclid=abc#section");
        Content duplicate = content("duplicate source", "https://docs.example/RAG/");

        List<Content> fused = fuser.fuse(List.of(List.of(first), List.of(duplicate)), List.of(1.0d, 1.0d), 10);

        assertEquals(1, fused.size());
        assertEquals("first source", fused.get(0).textSegment().text());
    }

    @Test
    void fusionPublishesRrfInputOutputTrace() {
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(60, null, "");
        Content first = content("first source", "https://Docs.Example/RAG/?utm_source=news&fbclid=abc#section");
        Content duplicate = content("duplicate source", "https://docs.example/RAG/");

        List<Content> fused = fuser.fuse(List.of(List.of(first), List.of(duplicate)), List.of(1.0d, 1.0d), 10);

        assertEquals(1, fused.size());
        assertEquals(2, TraceStore.get("rrf.input.count"));
        assertEquals(1, TraceStore.get("rrf.output.count"));
        assertEquals(Boolean.FALSE, TraceStore.get("rrf.fused.starvation"));
        assertEquals("", TraceStore.get("rrf.fused.skipReason"));
    }

    @Test
    void emptyInputPublishesRrfStarvationTrace() {
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(60, null, "");

        List<Content> fused = fuser.fuse(List.of(), List.of(), 10);

        assertTrue(fused.isEmpty());
        assertEquals(0, TraceStore.get("rrf.input.count"));
        assertEquals(0, TraceStore.get("rrf.output.count"));
        assertEquals(Boolean.TRUE, TraceStore.get("rrf.fused.starvation"));
        assertEquals("empty_input", TraceStore.get("rrf.fused.skipReason"));
    }

    @Test
    void canonicalizesWebUrlAliasesBeforeFusion() {
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(60, null, "");
        Content linkAlias = contentWithMetadata("link source", Map.of(
                "link", "https://Docs.Example/RAG/?utm_source=news&fbclid=abc#section"));
        Content canonicalAlias = contentWithMetadata("canonical source", Map.of(
                "canonical", "https://docs.example/RAG/"));

        List<Content> fused = fuser.fuse(List.of(List.of(linkAlias), List.of(canonicalAlias)), List.of(1.0d, 1.0d), 10);

        assertEquals(1, fused.size());
        assertEquals("link source", fused.get(0).textSegment().text());
    }

    @Test
    void grandasPostProcessAddsMetadataWithinGuardBandWhenServiceIsInjected() {
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(60, null, "");
        NovaNextProperties props = new NovaNextProperties();
        ReflectionTestUtils.setField(fuser, "novaNextFusionService", new NovaNextFusionService(props));

        Content tail = content("tail", "https://official.example/tail", Map.ofEntries(
                entry("tailSignal", 0.95d),
                entry("grandasReadiness", 0.90d),
                entry("authorityAvg", 0.90d),
                entry("strongCitationRate", 1.0d),
                entry("duplicateRate", 0.0d),
                entry("contradictionRate", 0.0d),
                entry("_nova.probeMode", "SPREAD_PROBE"),
                entry("_nova.anchorKeyHash", "hash-alpha"),
                entry("_nova.authorityScore", 0.90d),
                entry("_nova.noveltyScore", 0.80d),
                entry("_nova.rerankConfidence", 0.90d)));
        Content normal = content("normal", "https://docs.example/normal", Map.of(
                "tailSignal", 0.20d,
                "grandasReadiness", 0.50d,
                "authorityAvg", 0.50d));

        List<Content> fused = fuser.fuse(List.of(List.of(tail, normal), List.of(normal)), List.of(1.0d, 0.7d), 2);

        Map<String, Object> metadata = fused.get(0).textSegment().metadata().toMap();
        double base = ((Number) metadata.get("grandas_base_score")).doubleValue();
        double adjusted = ((Number) metadata.get("grandas_adjusted_score")).doubleValue();
        assertTrue(metadata.containsKey("grandas_tail_signal"));
        assertTrue(metadata.containsKey("grandas_reason"));
        Map<String, Object> spreadMetadata = fused.stream()
                .map(c -> c.textSegment().metadata().toMap())
                .filter(m -> "SPREAD_PROBE".equals(m.get("_nova.probeMode")))
                .findFirst()
                .orElseThrow();
        assertEquals("hash-alpha", spreadMetadata.get("_nova.anchorKeyHash"));
        assertTrue(Math.abs(adjusted - base) <= base * props.getGrandas().getMaxAdjustment() + 1.0e-9d);
    }

    @Test
    void grandasMetadataReasonDoesNotExposeRawSecrets() {
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(60, null, "");
        ReflectionTestUtils.setField(fuser, "novaNextFusionService", new NovaNextFusionService(new NovaNextProperties()) {
            @Override
            public List<ScoredResult> fuse(List<ScoredResult> in) {
                ScoredResult first = in.get(0);
                first.setAdjustedScore(first.getBaseScore());
                first.setReason("provider_failed Authorization Bearer " + fakeKey());
                return in;
            }
        });

        Content tail = content("tail", "https://official.example/tail", Map.of(
                "tailSignal", 0.95d,
                "grandasReadiness", 0.90d,
                "authorityAvg", 0.90d));

        List<Content> fused = fuser.fuse(List.of(List.of(tail)), List.of(1.0d), 1);

        Map<String, Object> metadata = fused.get(0).textSegment().metadata().toMap();
        String reason = String.valueOf(metadata.get("grandas_reason"));
        assertTrue(metadata.containsKey("grandas_reason"));
        assertTrue(reason.startsWith("hash:"), reason);
        assertFalse(reason.contains(fakeKey()));
        assertFalse(reason.contains("Authorization"));
    }

    @Test
    void grandasPostProcessFailureLeavesHashOnlyBreadcrumbAndOriginalRanking() {
        WeightedReciprocalRankFuser fuser = new WeightedReciprocalRankFuser(60, null, "");
        ReflectionTestUtils.setField(fuser, "novaNextFusionService", new NovaNextFusionService(new NovaNextProperties()) {
            @Override
            public List<ScoredResult> fuse(List<ScoredResult> in) {
                throw new IllegalStateException("Authorization Bearer " + fakeKey());
            }
        });

        Content tail = content("tail", "https://official.example/tail", Map.of(
                "tailSignal", 0.95d,
                "grandasReadiness", 0.90d,
                "authorityAvg", 0.90d));

        List<Content> fused = fuser.fuse(List.of(List.of(tail)), List.of(1.0d), 1);

        assertEquals("tail", fused.get(0).textSegment().text());
        assertEquals(Boolean.TRUE, TraceStore.get("rag.fusion.grandas.skipped"));
        assertEquals("nova_next_fusion_failed", TraceStore.get("rag.fusion.grandas.skipReason"));
        assertEquals("IllegalStateException", TraceStore.get("rag.fusion.grandas.errorType"));
        String errorHash = String.valueOf(TraceStore.get("rag.fusion.grandas.errorHash"));
        assertTrue(errorHash.startsWith("hash:"));
        assertFalse(errorHash.contains(fakeKey()));
        assertFalse(String.valueOf(TraceStore.get("rag.fusion.grandas.errorType")).contains("Authorization"));
    }

    @Test
    void metadataDoubleParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/fusion/WeightedReciprocalRankFuser.java"))
                .replace("\r\n", "\n");

        assertFalse(source.contains("catch (Exception ignored) {\n                    // try next\n                }"));
        assertTrue(source.contains("catch (NumberFormatException ignored)"));
        assertWeightedFuserInvalidNumberStage(source, "metaDouble.parse");
    }

    @Test
    void weightedFuserFallbacksLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/fusion/WeightedReciprocalRankFuser.java"));

        assertWeightedFuserStage(source, "lookupWeight");
        assertWeightedFuserStage(source, "key.metadata");
        assertWeightedFuserStage(source, "enrichGrandasMetadata");
        assertWeightedFuserStage(source, "metadata.toMap");
        assertWeightedFuserInvalidNumberStage(source, "metaDouble.parse");
        assertWeightedFuserStage(source, "sha256");
    }

    private static void assertWeightedFuserStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[WeightedReciprocalRankFuser] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing WeightedReciprocalRankFuser fail-soft stage: " + stage);
    }

    private static void assertWeightedFuserInvalidNumberStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[WeightedReciprocalRankFuser] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing WeightedReciprocalRankFuser invalid_number fail-soft stage: " + stage);
    }

    private static String fakeKey() {
        return "sk-" + "grandasSecretLeak123456789012345";
    }

    private static Content content(String text, String url) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of("url", url))));
    }

    private static Content content(String text, String url, Map<String, Object> extra) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(extra);
        metadata.put("url", url);
        return Content.from(TextSegment.from(text, Metadata.from(metadata)));
    }

    private static Content contentWithMetadata(String text, Map<String, Object> metadata) {
        return Content.from(TextSegment.from(text, Metadata.from(metadata)));
    }
}
