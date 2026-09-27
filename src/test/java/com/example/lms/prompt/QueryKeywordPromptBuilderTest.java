package com.example.lms.prompt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryKeywordPromptBuilderTest {

    private final QueryKeywordPromptBuilder builder = new QueryKeywordPromptBuilder();

    @Test
    void rewritePromptsIncludeHonestRewriteContract() {
        assertContract(builder.buildCorrectionPrompt("그런 뜻 아니다"));
        assertContract(builder.buildKeywordVariantsPrompt("DGX 빚 장비 조언", "DGX", 3));
        assertContract(builder.buildSubQueriesPrompt("발화자와 정정 맥락 분리"));
        assertContract(builder.buildSelectedTermsJsonPrompt("션이 해석했고 준우가 정정했다", "general", 4));
    }

    @Test
    void selfAskSeedPromptUsesExactUtf8SearchContract() {
        String prompt = builder.buildSelfAskSeedPrompt("서울 미세먼지");

        assertEquals("""
                당신은 검색어 생성기입니다.
                사용자 질문을 가장 효과적으로 찾을 수 있는 **짧은 키워드형 질의** 1~3개를 제시하세요.
                - 설명이나 접두사는 금지하고, 한 줄에 검색어만 출력하세요.
                질문: 서울 미세먼지
                """, prompt);
        assertMojibakeFree(prompt);
    }

    @Test
    void selfAskFollowupPromptUsesExactUtf8SearchContract() {
        String prompt = builder.buildSelfAskFollowupPrompt("서울 미세먼지");

        assertEquals("""
                "서울 미세먼지" 검색어가 광범위합니다.
                더 구체적이고 정보성을 높일 **키워드형 질의** 1~2개만 한국어로 제안하세요.
                (한 줄에 하나, 설명 금지)
                """, prompt);
        assertMojibakeFree(prompt);
    }

    @Test
    void selfAskPromptsPreserveBlankMultilineAndUnicodeInputs() {
        String blankSeed = builder.buildSelfAskSeedPrompt("");
        String unicodeFollowup = builder.buildSelfAskFollowupPrompt("첫 줄🙂\n둘째 줄");

        assertTrue(blankSeed.endsWith("질문: \n"));
        assertTrue(unicodeFollowup.contains("\"첫 줄🙂\n둘째 줄\""));
        assertMojibakeFree(blankSeed);
        assertMojibakeFree(unicodeFollowup);
    }

    @Test
    void selfAskSeedAndFollowupCallsRemainIsolated() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        try {
            List<Callable<String>> concurrentPromptCalls = List.of(
                    () -> {
                        startTogether.await(5, TimeUnit.SECONDS);
                        return builder.buildSelfAskSeedPrompt("seed-only-marker");
                    },
                    () -> {
                        startTogether.await(5, TimeUnit.SECONDS);
                        return builder.buildSelfAskFollowupPrompt("followup-only-marker");
                    });
            var prompts = executor.invokeAll(concurrentPromptCalls, 5, TimeUnit.SECONDS);

            String seedPrompt = prompts.get(0).get();
            String followupPrompt = prompts.get(1).get();
            assertTrue(seedPrompt.contains("seed-only-marker"));
            assertFalse(seedPrompt.contains("followup-only-marker"));
            assertTrue(followupPrompt.contains("followup-only-marker"));
            assertFalse(followupPrompt.contains("seed-only-marker"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void needleProbePromptTemplatesStayInKeywordPromptBuilder() throws Exception {
        String transformer = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformer.java"),
                StandardCharsets.UTF_8);
        String promptBuilder = Files.readString(
                Path.of("main/java/com/example/lms/prompt/QueryKeywordPromptBuilder.java"),
                StandardCharsets.UTF_8);

        assertFalse(transformer.contains("You are an assistant that suggests a small list of high-authority websites"));
        assertFalse(transformer.contains("You are generating *needle probe* web search queries."));
        assertTrue(promptBuilder.contains("buildAuthoritySitesPrompt("));
        assertTrue(promptBuilder.contains("buildNeedleProbeQueriesPrompt("));
    }

    @Test
    void queryTransformerBuilderPromptsAvoidGenericPromptRiskCallsites() throws Exception {
        String transformer = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformer.java"),
                StandardCharsets.UTF_8);
        String needlePlanner = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformerNeedlePlanner.java"),
                StandardCharsets.UTF_8);

        assertFalse(transformer.contains("String prompt ="));
        assertFalse(transformer.contains("UserMessage.from(prompt)"));
        assertTrue(transformer.contains("QUERY_KEYWORD_PROMPT_BUILDER.buildCorrectionPrompt("));
        assertTrue(needlePlanner.contains("promptBuilder.buildNeedleProbeQueriesPrompt("));
    }

    private static void assertContract(String prompt) {
        assertTrue(prompt.contains("HONEST_REWRITE_CONTRACT"));
        assertTrue(prompt.contains("Preserve speaker attribution"));
        assertTrue(prompt.contains("Do not upgrade ambiguous distress"));
        assertTrue(prompt.contains("interpretation and a correction"));
    }

    private static void assertMojibakeFree(String prompt) {
        assertFalse(prompt.contains("\uFFFD"));
        assertFalse(prompt.contains("?뱀떊"));
        assertFalse(prompt.contains("寃"));
    }
}
