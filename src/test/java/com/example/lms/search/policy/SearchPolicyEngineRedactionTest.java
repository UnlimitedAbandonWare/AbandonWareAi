package com.example.lms.search.policy;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchPolicyEngineRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void uiSearchModeOverridesPolicyBreadthBeforeHeuristics() {
        SearchPolicyEngine engine = new SearchPolicyEngine();

        SearchPolicyDecision light = engine.decide("지금 설정 기준 답변 경로를 설명해줘",
                Map.of("searchMode", "FORCE_LIGHT"));
        SearchPolicyDecision deep = engine.decide("정의와 최신 변경사항을 비교해줘",
                Map.of("searchMode", "FORCE_DEEP"));
        SearchPolicyDecision off = engine.decide("최신 릴리즈를 찾아줘",
                Map.of("searchMode", "OFF"));

        assertEquals(SearchPolicyMode.PRECISION, light.mode());
        assertFalse(light.slicingEnabled());
        assertFalse(light.expansionEnabled());
        assertEquals(SearchPolicyMode.RECALL, deep.mode());
        assertEquals(SearchPolicyMode.OFF, off.mode());
    }

    @Test
    void uiDeepModeCarriesExploratoryRewriteTemperatureProfileIntoMeta() {
        SearchPolicyEngine engine = new SearchPolicyEngine();
        SearchPolicyDecision deep = engine.decide("최신 변경사항과 반례를 섞어서 찾아줘",
                Map.of("searchMode", "FORCE_DEEP"));

        assertEquals("exploratory", deep.rewriteTemperatureProfile());
        assertTrue(deep.rewriteValidationTemperature() <= 0.25d);
        assertTrue(deep.rewriteExplorationTemperature() > deep.rewriteValidationTemperature());

        Map<String, Object> meta = engine.enrichMeta(new HashMap<>(), deep);

        assertEquals("exploratory", meta.get("searchPolicy.rewriteTemperatureProfile"));
        assertEquals(String.valueOf(deep.rewriteValidationTemperature()),
                String.valueOf(meta.get("searchPolicy.rewriteValidationTemperature")));
        assertEquals(String.valueOf(deep.rewriteExplorationTemperature()),
                String.valueOf(meta.get("searchPolicy.rewriteExplorationTemperature")));
    }

    @Test
    void creativeProfileUsesApprovedSearchValuesButProviderMetadataStaysExploratory() {
        SearchPolicyEngine engine = new SearchPolicyEngine();
        Map<String, Object> hints = completeCreativeHints("WILD", 0.94d, 0.80d);

        SearchPolicyDecision decision = engine.decide("creative story architecture", hints);
        Map<String, Object> providerMeta = engine.enrichMeta(new HashMap<>(), decision);

        assertEquals("creative:WILD", decision.rewriteTemperatureProfile());
        assertEquals(0.94d, decision.rewriteExplorationTemperature());
        assertEquals(0.80d, decision.rewriteExplorationRate());
        assertEquals("exploratory", providerMeta.get("searchPolicy.rewriteTemperatureProfile"));
    }

    @Test
    void creativeSearchReservesOriginalAndOfficialAnchorBeforeBoundedVariants() {
        SearchPolicyEngine engine = new SearchPolicyEngine();
        SearchPolicyDecision decision = engine.decide("새로운 도시 정원 디자인",
                completeCreativeHints("VIVID", 0.88d, 0.73d));

        List<String> queries = engine.apply(
                List.of("planner facet one", "planner facet two"),
                "새로운 도시 정원 디자인",
                decision);

        assertEquals("새로운 도시 정원 디자인", queries.get(0));
        assertEquals("새로운 도시 정원 디자인 공식 1차 자료", queries.get(1));
        assertTrue(queries.size() <= 16);
        assertTrue(queries.stream().anyMatch(q -> q.startsWith("새로운 도시 정원 디자인 ")
                && !q.endsWith("공식 1차 자료")));
    }

    @Test
    void hardGuardAndForceLightSuppressCreativeExpansionBeforeUiBreadth() {
        SearchPolicyEngine engine = new SearchPolicyEngine();
        Map<String, Object> creative = completeCreativeHints("WILD", 0.94d, 0.80d);
        creative.put("searchMode", "FORCE_DEEP");
        creative.put("nightmareMode", true);

        SearchPolicyDecision guarded = engine.decide("creative system design", creative);

        assertEquals(SearchPolicyMode.PRECISION, guarded.mode());
        assertFalse(guarded.expansionEnabled());
        assertFalse(guarded.rewriteTemperatureProfile().startsWith("creative:"));

        Map<String, Object> lightHints = new HashMap<>(creative);
        lightHints.remove("nightmareMode");
        lightHints.put("searchMode", "FORCE_LIGHT");
        SearchPolicyDecision light = engine.decide("creative system design", lightHints);
        List<String> lightQueries = engine.apply(
                List.of("creative system design"), "creative system design", light);

        assertFalse(light.rewriteTemperatureProfile().startsWith("creative:"));
        assertEquals(List.of("creative system design"), lightQueries);
    }

    @Test
    void creativeOneTokenQueryStillKeepsExactOriginalBeforeOfficialAnchor() {
        SearchPolicyEngine engine = new SearchPolicyEngine();
        SearchPolicyDecision decision = engine.decide("C++",
                completeCreativeHints("VIVID", 0.88d, 0.73d));

        List<String> queries = engine.apply(List.of(), "C++", decision);

        assertEquals("C++", queries.get(0));
        assertEquals("C++ official primary source", queries.get(1));
    }

    @Test
    void creativeHardCapIsExactlySixteenEvenWhenDecisionRequestsMore() {
        SearchPolicyDecision oversized = new SearchPolicyDecision(
                SearchPolicyMode.RECALL,
                true,
                true,
                32,
                2,
                1,
                20,
                20,
                1.0d,
                1.0d,
                "creative-cap-test",
                0.22d,
                0.94d,
                0.80d,
                "creative:WILD");
        List<String> base = java.util.stream.IntStream.range(0, 24)
                .mapToObj(i -> "planner facet " + i)
                .toList();
        TraceStore.putInternal("search.policy.creative.requestedOptionsHash", "hash:0123456789ab");

        List<String> queries = new SearchPolicyEngine().apply(base, "creative cap query", oversized);

        assertEquals(16, queries.size());
        assertEquals("creative cap query", queries.get(0));
        assertEquals("creative cap query official primary source", queries.get(1));
    }

    @Test
    void invalidCreativeMetadataFailsSoftToLegacyDecision() {
        Map<String, Object> hints = completeCreativeHints("FERAL", 1.25d, 0.85d);
        SearchPolicyDecision decision = new SearchPolicyEngine().decide("creative story architecture", hints);

        assertEquals("balanced", decision.rewriteTemperatureProfile());
    }

    @Test
    void missingLineageOrPrivacyBoundaryCannotActivateCreativeSearch() {
        SearchPolicyEngine engine = new SearchPolicyEngine();
        Map<String, Object> hints = completeCreativeHints("WILD", 0.94d, 0.80d);
        hints.remove("creative.emergence.requestedOptionsHash");

        assertEquals("balanced", engine.decide("creative architecture", hints).rewriteTemperatureProfile());

        hints.put("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        hints.put("privacy.boundary.enforce", true);
        assertEquals("balanced", engine.decide("creative architecture", hints).rewriteTemperatureProfile());
    }

    @Test
    void partialCreativeProfileCannotActivateSearchPolicy() {
        SearchPolicyEngine engine = new SearchPolicyEngine();
        List<String> numericKeys = List.of(
                "creative.emergence.search.temperature",
                "creative.emergence.search.rate",
                "creative.emergence.candidate.temperature",
                "creative.emergence.candidate.topP",
                "creative.emergence.final.temperature",
                "creative.emergence.final.topP",
                "creative.emergence.selfAsk.temperature");

        for (String missingKey : numericKeys) {
            Map<String, Object> partial = completeCreativeHints("WILD", 0.94d, 0.80d);
            partial.remove(missingKey);
            assertEquals("balanced",
                    engine.decide("creative architecture", partial).rewriteTemperatureProfile(),
                    missingKey);
        }

        Map<String, Object> outOfBand = completeCreativeHints("WILD", 0.94d, 0.80d);
        outOfBand.put("creative.emergence.candidate.temperature", 1.46d);
        assertEquals("balanced",
                engine.decide("creative architecture", outOfBand).rewriteTemperatureProfile());
    }

    @Test
    void enrichMetaStoresReasonAsLowCardinalityLabel() {
        SearchPolicyDecision decision = new SearchPolicyDecision(
                SearchPolicyMode.RECALL,
                true,
                true,
                8,
                2,
                1,
                4,
                2,
                1.0d,
                1.0d,
                "private policy reason for query=student medical record");

        Map<String, Object> meta = new SearchPolicyEngine().enrichMeta(new HashMap<>(), decision);
        String reason = String.valueOf(meta.get("searchPolicy.reason"));

        assertFalse(reason.contains("student medical record"), reason);
        assertFalse(reason.contains("query="), reason);
        assertTrue(reason.startsWith("hash:"), reason);
    }

    @Test
    void modeParserOnlyCatchesIllegalArgumentException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/search/policy/SearchPolicyEngine.java"));
        String parserCall = "return SearchPolicyMode.valueOf(s.toUpperCase(Locale.ROOT));";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "search policy mode parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 220));
        assertFalse(window.contains("catch (Exception"),
                "search policy mode parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "search policy mode parser must not swallow Throwable");
        assertTrue(window.contains("catch (IllegalArgumentException"),
                "search policy mode parser should only catch IllegalArgumentException");
    }

    @Test
    void modeParserFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/search/policy/SearchPolicyEngine.java"));

        assertTrue(source.contains("private static void traceSuppressed(String stage, Throwable failure)"));
        assertTrue(source.contains("traceSuppressed(\"mode.parse\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"searchPolicy.suppressed.\" + safeStage, true);"));
    }

    private static Map<String, Object> completeCreativeHints(
            String profile,
            double searchTemperature,
            double searchRate) {
        Map<String, Object> hints = new HashMap<>();
        hints.put("creative.emergence.active", true);
        hints.put("creative.emergence.profile", profile);
        hints.put("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        hints.put("promptPose.application.intentSlot", "explore");
        hints.put("creative.emergence.search.temperature", searchTemperature);
        hints.put("creative.emergence.search.rate", searchRate);
        switch (profile) {
            case "VIVID" -> {
                hints.put("creative.emergence.candidate.temperature", 1.18d);
                hints.put("creative.emergence.candidate.topP", 0.96d);
                hints.put("creative.emergence.final.temperature", 1.12d);
                hints.put("creative.emergence.final.topP", 0.96d);
                hints.put("creative.emergence.selfAsk.temperature", 0.84d);
            }
            case "WILD" -> {
                hints.put("creative.emergence.candidate.temperature", 1.35d);
                hints.put("creative.emergence.candidate.topP", 0.98d);
                hints.put("creative.emergence.final.temperature", 1.30d);
                hints.put("creative.emergence.final.topP", 0.98d);
                hints.put("creative.emergence.selfAsk.temperature", 0.93d);
            }
            case "FERAL" -> {
                hints.put("creative.emergence.candidate.temperature", 1.48d);
                hints.put("creative.emergence.candidate.topP", 1.00d);
                hints.put("creative.emergence.final.temperature", 1.46d);
                hints.put("creative.emergence.final.topP", 1.00d);
                hints.put("creative.emergence.selfAsk.temperature", 0.99d);
            }
            default -> throw new IllegalArgumentException("unsupported creative profile");
        }
        return hints;
    }
}
