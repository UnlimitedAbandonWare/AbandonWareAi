package com.example.lms.service.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.example.lms.config.LlmConfig;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugEventTracePromotionService;
import com.example.lms.guard.KeyResolver;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.StageBoundaryBreadcrumbs;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class VerificationFailSoftRedactionContractTest {

    @Test
    void verificationAndCorrectionFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/service/rag/handler/AnalyzeHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/EntityDisambiguationHandler.java"),
                Path.of("main/java/com/example/lms/service/FactVerifierService.java"),
                Path.of("main/java/com/example/lms/service/ner/LLMNamedEntityExtractor.java"),
                Path.of("main/java/com/example/lms/service/disambiguation/QueryDisambiguationService.java"),
                Path.of("main/java/com/example/lms/service/correction/LLMQueryCorrectionService.java"),
                Path.of("main/java/com/example/lms/service/verification/ClaimVerifierService.java"),
                Path.of("main/java/com/example/lms/service/verification/FactStatusClassifier.java"),
                Path.of("main/java/com/example/lms/service/verification/SourceAnalyzerService.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()")
                            || line.contains(".toString()")
                            || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        }

        String disambiguation = Files.readString(
                Path.of("main/java/com/example/lms/service/disambiguation/QueryDisambiguationService.java"),
                StandardCharsets.UTF_8);
        assertFalse(disambiguation.contains("queryHash, queryLength, e.toString())"));
        assertFalse(disambiguation.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertFalse(disambiguation.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(disambiguation.contains("[Disambig] DomainTermDictionary lookup failed. errorHash={} errorLength={}"));
        assertTrue(disambiguation.contains(
                "[Disambig] LLM disambiguation failed, falling back. queryHash={} queryLength={} errorHash={} errorLength={}"));
        assertTrue(disambiguation.contains(
                "queryHash, queryLength, SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        String sourceAnalyzer = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/SourceAnalyzerService.java"),
                StandardCharsets.UTF_8);
        String authorityScorer = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/auth/AuthorityScorer.java"),
                StandardCharsets.UTF_8);
        assertFalse(sourceAnalyzer.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(sourceAnalyzer.contains("AuthorityScorer evaluation failed. errorHash={} errorLength={}"));
        assertTrue(sourceAnalyzer.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(sourceAnalyzer.contains("TraceStore.put(\"sourceAnalyzer.suppressed.hostParse\", true)"));
        assertFalse(authorityScorer.contains("catch (Exception e) {\n                                return 0.5;"));
        assertTrue(authorityScorer.contains("catch (NumberFormatException e) {"));
        assertTrue(authorityScorer.contains("stage={}\", \"parse.weight\""));
        assertTrue(authorityScorer.contains("return 0.5;"));

        String factStatusClassifier = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/FactStatusClassifier.java"),
                StandardCharsets.UTF_8);
        assertFalse(factStatusClassifier.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(factStatusClassifier.contains("FactStatusClassifier LLM classification failed. errorHash={} errorLength={}"));
        assertTrue(factStatusClassifier.contains("[FactStatusClassifier] ChatModel call failed. errorHash={} errorLength={}"));
        assertTrue(factStatusClassifier.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        String claimVerifier = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/ClaimVerifierService.java"),
                StandardCharsets.UTF_8);
        assertFalse(claimVerifier.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(claimVerifier.contains("SafeRedactor.safeMessage(String.valueOf(ignore), 180)"));
        assertTrue(claimVerifier.contains("[ClaimVerifier] temporal verification failed. errorHash={} errorLength={}"));
        assertTrue(claimVerifier.contains("Claim verification failed. errorHash={} errorLength={}"));
        assertTrue(claimVerifier.contains("JSON array of strings parse failed rawHash={} rawLength={} errorHash={} errorLength={}"));
        assertTrue(claimVerifier.contains("JSON array of booleans parse failed rawHash={} rawLength={} errorHash={} errorLength={}"));
        assertTrue(claimVerifier.contains("[ClaimVerifier] ChatModel call failed. errorHash={} errorLength={}"));
        assertFalse(claimVerifier.contains("catch (Exception ignore) { /*"));
        assertTrue(claimVerifier.contains("[ClaimVerifier] implicit positive update failed. errorHash={} errorLength={}"));
        assertTrue(claimVerifier.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(claimVerifier.contains("SafeRedactor.hashValue(messageOf(ignore)), messageLength(ignore)"));

        String factVerifier = Files.readString(
                Path.of("main/java/com/example/lms/service/FactVerifierService.java"),
                StandardCharsets.UTF_8);
        assertFalse(factVerifier.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(factVerifier.contains("[Meta-Verify] Source analysis failed. errorHash={} errorLength={}"));
        assertTrue(factVerifier.contains("[Verify] META-CHECK failed. errorHash={} errorLength={}"));
        assertTrue(factVerifier.contains("Correction generation failed, falling back to '정보 없음'. errorHash={} errorLength={}"));
        assertTrue(factVerifier.contains("[FactVerifier] ChatModel call failed. errorHash={} errorLength={}"));
        assertTrue(factVerifier.contains("[Self-Healing] correctiveRegenerate failed. errorHash={} errorLength={}"));
        assertTrue(factVerifier.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        String correction = Files.readString(
                Path.of("main/java/com/example/lms/service/correction/LLMQueryCorrectionService.java"),
                StandardCharsets.UTF_8);
        assertFalse(correction.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(correction.contains("[QC] correction failed passthrough. errorHash={} errorLength={}"));
        assertTrue(correction.contains("[LLMQueryCorrection] ChatModel call failed. errorHash={} errorLength={}"));
        assertTrue(correction.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        String namedEntityExtractor = Files.readString(
                Path.of("main/java/com/example/lms/service/ner/LLMNamedEntityExtractor.java"),
                StandardCharsets.UTF_8);
        assertFalse(namedEntityExtractor.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(namedEntityExtractor.contains("[NER] LLM extraction failed. errorHash={} errorLength={}"));
        assertTrue(namedEntityExtractor.contains("[NER] dictionary lookup failed. errorHash={} errorLength={}"));
        assertTrue(namedEntityExtractor.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        String entityDisambiguation = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/EntityDisambiguationHandler.java"),
                StandardCharsets.UTF_8);
        assertFalse(entityDisambiguation.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(entityDisambiguation.contains("Query analysis failed, fallback to legacy. errorHash={} errorLength={}"));
        assertTrue(entityDisambiguation.contains("rebuild failed, fallback to legacy disambiguation. errorHash={} errorLength={}"));
        assertTrue(entityDisambiguation.contains("legacy disambiguate failed. errorHash={} errorLength={}"));
        assertTrue(entityDisambiguation.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void claimVerifierJsonParseFailureLogsDoNotWriteRawLlmJson() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/ClaimVerifierService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.warn(\"JSON array of strings 파싱 실패: {}\", raw, e);"));
        assertFalse(source.contains("log.warn(\"JSON array of booleans 파싱 실패: {}\", raw, e);"));
        assertTrue(source.contains("log.warn(\"JSON array of strings parse failed rawHash={} rawLength={} errorHash={} errorLength={}\""));
        assertTrue(source.contains("log.warn(\"JSON array of booleans parse failed rawHash={} rawLength={} errorHash={} errorLength={}\""));
    }
    @Test
    void claimVerifierTemporalMismatchReasonUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/ClaimVerifierService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("Temporal mismatch detected: {}\", temporalResult.reason()"));
        assertFalse(source.contains("+ temporalResult.reason()"));
        assertTrue(source.contains("String safeTemporalReason = SafeRedactor.traceLabelOrFallback(temporalResult.reason(), \"unknown\");"));
        assertTrue(source.contains("Temporal mismatch detected: {}\", safeTemporalReason"));
        assertTrue(source.contains("+ safeTemporalReason"));
    }

    @Test
    void claimVerifierPromptCallsitesUseStageSpecificPromptNames() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/ClaimVerifierService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("callChatModel(prompt)"));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String claimExtractionPrompt ="));
        assertTrue(source.contains("String claimJudgmentPrompt ="));
        assertTrue(source.contains("UserMessage.from(claimVerifierPrompt)"));
    }

    @Test
    void factStatusClassifierPromptCallsitesUseStageSpecificPromptNames() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/FactStatusClassifier.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String factStatusClassificationPrompt ="));
        assertTrue(source.contains("UserMessage.from(factStatusClassificationPrompt)"));
    }

    @Test
    void verificationServicesUseJudgeChatModelQualifierAndRedactedFailSoftTrace() throws Exception {
        String factStatusClassifier = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/FactStatusClassifier.java"),
                StandardCharsets.UTF_8);
        String claimVerifier = Files.readString(
                Path.of("main/java/com/example/lms/service/verification/ClaimVerifierService.java"),
                StandardCharsets.UTF_8);

        assertTrue(factStatusClassifier.contains(
                "public FactStatusClassifier(@Qualifier(\"judgeChatModel\") ObjectProvider<ChatModel> chatModelProvider)"));
        assertTrue(factStatusClassifier.contains(
                "TraceStore.put(\"factStatusClassifier.judge.disabledReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(claimVerifier.contains(
                "public ClaimVerifierService(@Qualifier(\"judgeChatModel\") ChatModel chatModel,"));
        assertTrue(claimVerifier.contains(
                "TraceStore.put(\"claimVerifier.judge.disabledReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void missingJudgeCredentialCreatesCoherentProviderDisabledBreadcrumbThroughRealProducers() {
        TraceStore.clear();
        String endpointSentinel = "http://127.0.0.1:9/v1";
        String modelSentinel = "cycle7-private-judge-model";
        try {
            ChatModel judge = new LlmConfig().judgeChatModel(
                    endpointSentinel,
                    new KeyResolver(new MockEnvironment()),
                    modelSentinel,
                    1L,
                    0,
                    512);
            assertInstanceOf(ExpectedFailureChatModel.class, judge);

            String draft = "A deterministic local claim.";
            ClaimVerifierService.VerificationResult claimResult =
                    new ClaimVerifierService(judge, null, null, null)
                            .verifyClaims("safe local context", draft, "local-model");

            StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
            beanFactory.addBean("judgeChatModel", judge);
            FactVerificationStatus factStatus =
                    new FactStatusClassifier(beanFactory.getBeanProvider(ChatModel.class)).classify(
                            "deterministic claim",
                            "deterministic claim " + "supporting local context ".repeat(8),
                            draft,
                            "local-model");

            assertEquals(draft, claimResult.verifiedAnswer());
            assertTrue(claimResult.unsupportedClaims().isEmpty());
            assertFalse(claimResult.outcomeKnown());
            assertEquals(FactVerificationStatus.PASS, factStatus);
            assertEquals("judge_model_unavailable",
                    TraceStore.get("claimVerifier.judge.disabledReason"));
            assertEquals("judge_model_unavailable",
                    TraceStore.get("factStatusClassifier.judge.disabledReason"));

            List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                    StageBoundaryBreadcrumbs.recordFromCurrentTrace("final");
            assertEquals(1, rows.size());
            StageBoundaryBreadcrumbs.BoundaryBreadcrumb row = rows.get(0);
            Map<String, Object> data = row.data();
            assertEquals("verification", row.stage());
            assertEquals("fail_soft", data.get("status"));
            assertEquals("provider-disabled", data.get("failureClass"));
            assertEquals("judge_model_unavailable", data.get("reasonCode"));
            assertEquals("both", data.get("judgeLane"));
            assertEquals(2, data.get("judgeFailSoftLaneCount"));
            assertEquals(Boolean.FALSE, data.get("judgeCallAttempted"));
            assertEquals(Boolean.FALSE, data.get("verificationOutcomeKnown"));
            assertEquals(Boolean.TRUE, data.get("redacted"));

            DebugEventStore store = enabledDebugEventStore();
            new DebugEventTracePromotionService(store).promoteStageBoundaryBreadcrumbsOnly(
                    "final",
                    TraceStore.getAll(),
                    "VerificationFailSoftRedactionContractTest");
            DebugEvent event = store.list(5).stream()
                    .filter(candidate -> "verification".equals(candidate.data().get("boundaryStage")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("verification.judge", event.data().get("layer"));
            assertFalse(event.toString().contains(endpointSentinel), event.toString());
            assertFalse(event.toString().contains(modelSentinel), event.toString());
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void queryCorrectionPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/correction/LLMQueryCorrectionService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("callChatModel(ChatModel llm, String prompt)"));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("callChatModel(ChatModel llm, String queryCorrectionPrompt)"));
        assertTrue(source.contains("UserMessage.from(queryCorrectionPrompt)"));
    }

    @Test
    void queryDisambiguationPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/disambiguation/QueryDisambiguationService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt = promptBuilder.buildUniversal(query, history, seed);"));
        assertFalse(source.contains("completeWithKey(NightmareKeys.DISAMBIGUATION_CLARIFY, prompt)"));
        assertTrue(source.contains("String queryDisambiguationPrompt = promptBuilder.buildUniversal(query, history, seed);"));
        assertTrue(source.contains("nightmareBreaker.acquire(NightmareKeys.DISAMBIGUATION_CLARIFY, \"disambiguation\")"));
        assertTrue(source.contains(
                "llmClient.completeWithPermit(permit, \"disambiguation\", queryDisambiguationPrompt)"));
    }

    @Test
    void factVerifierChatModelHelperUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/FactVerifierService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("callChatModel(String prompt)"));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("callChatModel(String factVerifierPrompt)"));
        assertTrue(source.contains("UserMessage.from(factVerifierPrompt)"));
    }

    @Test
    void factVerifierMetaVerdictParserMapsAllowlistedStatusesWithoutExceptionFallback() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/FactVerifierService.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private static MetaVerdict parseMetaVerdict(");
        int end = source.indexOf("private static boolean isMetaControlReason(", start);
        assertTrue(start >= 0 && end > start, "meta verdict parser span should be locatable");
        String parser = source.substring(start, end);

        assertFalse(parser.contains("catch (IllegalArgumentException"),
                "regex-allowlisted verdicts should not rely on an exception fallback");
        assertTrue(parser.contains("case \"CONSISTENT\" -> MetaVerdict.CONSISTENT;"));
        assertTrue(parser.contains("case \"MISMATCH\" -> MetaVerdict.MISMATCH;"));
        assertTrue(parser.contains("case \"INSUFFICIENT\" -> MetaVerdict.INSUFFICIENT;"));
        assertTrue(parser.contains("default -> null;"));
    }

    @Test
    void claimVerifierUnsupportedClaimsDoNotReturnRawSecretLikeLabelsOrValues() {
        String apiKey = "sk-" + "1234567890" + "abcdef1234";
        String ownerSecret = "owner-" + "secret-" + "123";
        String supabaseSecret = "sb_secret_" + "claimVerifier123456";
        String claim = "The answer leaked ownerToken=" + ownerSecret + ", " + apiKey + ", and " + supabaseSecret;
        ClaimVerifierService service = new ClaimVerifierService(
                new SequentialModel(List.of("[\"" + claim + "\"]", "[false]")),
                null,
                null,
                null);

        ClaimVerifierService.VerificationResult result = service.verifyClaims("safe context", claim + ".", "model");

        assertTrue(result.outcomeKnown());
        assertEquals(1, result.unsupportedClaims().size());
        String unsupportedClaim = result.unsupportedClaims().get(0);
        assertFalse(unsupportedClaim.contains(ownerSecret), unsupportedClaim);
        assertFalse(unsupportedClaim.contains(apiKey), unsupportedClaim);
        assertFalse(unsupportedClaim.contains(supabaseSecret), unsupportedClaim);
        assertFalse(unsupportedClaim.toLowerCase(Locale.ROOT).contains("sb_secret_"), unsupportedClaim);
        assertFalse(unsupportedClaim.toLowerCase(Locale.ROOT).contains("ownertoken"), unsupportedClaim);
        assertTrue(unsupportedClaim.contains("[redacted]") || unsupportedClaim.contains("***"), unsupportedClaim);
    }

    @Test
    void claimVerifierSynergyConfidenceAppliesMultiPositiveBonus() throws Exception {
        Field cuesField = ClaimVerifierService.class.getDeclaredField("SYNERGY_CUES");
        cuesField.setAccessible(true);
        String[] cues = (String[]) cuesField.get(null);
        Method estimate = ClaimVerifierService.class.getDeclaredMethod(
                "estimateSynergyConfidence", List.class, List.class);
        estimate.setAccessible(true);

        double out = (double) estimate.invoke(null,
                List.of(cues[0] + " claim one", cues[1] + " claim two", cues[0] + " claim three"),
                List.of(true, true, false));

        assertEquals((2.0d / 3.0d) + 0.1d, out, 1.0e-9d);
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        return store;
    }

    private static final class SequentialModel implements ChatModel {
        private final List<String> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private SequentialModel(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(ChatMessage... messages) {
            return nextResponse();
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return nextResponse();
        }

        private ChatResponse nextResponse() {
            int index = Math.min(calls.getAndIncrement(), responses.size() - 1);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responses.get(index)))
                    .build();
        }
    }
}
