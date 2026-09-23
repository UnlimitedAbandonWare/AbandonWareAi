package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.postprocess.FinalAnswerPostProcessor;
import com.example.lms.service.postprocess.OutputSanitizer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatWorkflowLlmTimeoutContractTest {

    @Test
    void forcedDetourRegenRespectsLiveRequestBudget() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        AtomicInteger calls = new AtomicInteger();
        ChatModel slowModel = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                calls.incrementAndGet();
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("late detour answer")).build();
            }
        };
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(promptBuilder.build(any(PromptContext.class))).thenReturn("bounded detour prompt");

        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "chatUsageLedger", ledger);
        ReflectionTestUtils.setField(workflow, "promptBuilder", promptBuilder);
        ReflectionTestUtils.setField(workflow, "detourCheapRetryRegenLlmEnabled", false);
        ReflectionTestUtils.setField(workflow, "detourForceEscalateRegenLlmEnabled", true);
        ReflectionTestUtils.setField(workflow, "detourCheapRetryRegenLlmOnlyIfLowRisk", false);
        ReflectionTestUtils.setField(workflow, "detourCheapRetryRegenLlmTemperature", 0.2d);
        ReflectionTestUtils.setField(workflow, "detourCheapRetryRegenLlmMaxTokens", 64);
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);

        ChatRequestDto request = ChatRequestDto.builder()
                .message("official-source detour budget probe")
                .model("gpt-budget-loopback")
                .maxTokens(64)
                .build();

        try {
            TimeBudgetContext.set(new TimeBudget(750L));
            String result = assertTimeout(Duration.ofMillis(1_200L),
                    () -> ReflectionTestUtils.invokeMethod(
                            workflow,
                            "tryDetourCheapRetryLlmRegen",
                            request.getMessage(),
                            "draft",
                            List.of(),
                            slowModel,
                            request,
                            "chat:gpt-budget-loopback",
                            null,
                            true));

            assertNull(result, "a detour response arriving after the live request budget must be discarded");
            assertEquals(1, calls.get(), "the forced detour may start at most one physical model call");
            assertEquals(Boolean.TRUE, TraceStore.get("llm.call.timeout"));
            assertEquals("guard_detour_regen", TraceStore.get("llm.call.timeout.stage"));
            @SuppressWarnings("unchecked")
            Map<String, Object> modelInvocations = (Map<String, Object>) ledger.snapshot().get("modelInvocations");
            assertEquals(1L, ((Number) modelInvocations.get("attempts")).longValue(),
                    "the detour model call must be visible in the count-only usage ledger");
        } finally {
            TimeBudgetContext.clear();
            TraceStore.clear();
        }
    }

    @Test
    void disabledCheapRetrySkipsBeforeSearchOrRegenWork() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel forbiddenModel = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                modelCalls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("unexpected")).build();
            }
        };
        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "detourCheapRetryEnabled", false);
        ReflectionTestUtils.setField(workflow, "detourCheapRetrySiteHintsCsv", "");
        ReflectionTestUtils.setField(workflow, "detourCheapRetryMaxSites", 1);

        try {
            TraceStore.put("guard.detour", "insufficient_citations");
            Object result = ReflectionTestUtils.invokeMethod(
                    workflow,
                    "tryDetourCheapRetry",
                    "detour disabled probe",
                    QueryDomain.GENERAL,
                    Map.of(),
                    1L,
                    VisionMode.STRICT,
                    List.of(),
                    "draft",
                    forbiddenModel,
                    ChatRequestDto.builder().message("detour disabled probe").maxTokens(64).build(),
                    "chat:detour-disabled");

            assertNotNull(result);
            assertNull(ReflectionTestUtils.getField(result, "content"));
            assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(result, "unattributedEvidenceAdded"));
            assertEquals(0, modelCalls.get());
            assertEquals("disabled", TraceStore.get("guard.detour.cheapRetry.skip"),
                    "the public cost-control knob must stop the detour before search or model work");
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void callWithRetryUsesHardTimeoutWrapperForAllChatModelDraftCalls() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(source.contains("import com.example.lms.llm.TimedChatModelCaller;"));
        assertTrue(method.contains("TimedChatModelCaller.chat("),
                "final draft calls must use the hard timeout wrapper");
        assertFalse(method.contains("modelForCall.chat(msgs).aiMessage()"),
                "primary draft call must not block directly on ChatModel.chat");
        assertFalse(method.contains("healed.chat(msgs).aiMessage()"),
                "self-heal draft calls must not block directly on ChatModel.chat");
    }

    @Test
    void noEvidenceTimeoutFastBailsBeforeAsyncRequestTimeout() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(method.contains("!evidencePresent && timeoutHits >= 1"),
                "zero-evidence chat should fast-bail after the first model timeout");
        assertTrue(method.contains("new LlmFastBailoutException(\"LLM timeout fast-bail\""),
                "fast-bail must be routed through the existing LLM fallback catch path");
    }

    @Test
    void noEvidenceUpstream5xxFastBailsThroughExistingFallbackPath() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(method.contains("\"UPSTREAM_5XX\".equals(cls.code())"),
                "upstream 5xx must be classified explicitly before retry-budget exhaustion");
        assertTrue(method.contains("!evidencePresent && upstream5xxHits >= 1"),
                "zero-evidence chat should not wait through the full retry budget after local upstream 5xx");
        assertTrue(method.contains("new LlmFastBailoutException(\"LLM upstream fast-bail\""),
                "upstream 5xx fast-bail should reuse the existing evidence/local-lite fallback catch path");
        assertTrue(source.contains("TraceStore.put(\"llm.fastBailUpstream5xx\", true)"),
                "top-level fallback catch should leave a low-cardinality upstream fast-bail breadcrumb");
    }

    @Test
    void noEvidenceBlankResponseFastBailsThroughExistingFallbackPath() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(method.contains("\"BLANK_RESPONSE\".equals(cls.code())"),
                "blank local LLM 200 OK responses must be classified before retry-budget exhaustion");
        assertTrue(method.contains("recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, \"blank_response\")"),
                "blank output should block bad local model promotion until a successful response is observed");
        assertTrue(method.contains("!evidencePresent && blankHits >= 1"),
                "zero-evidence chat should not repeatedly call a model that returned blank content");
        assertTrue(method.contains("new LlmFastBailoutException(\"LLM blank response fast-bail\""),
                "blank-response fast-bail should reuse the existing evidence/local-lite fallback catch path");
        assertTrue(source.contains("TraceStore.put(\"llm.fastBailBlankResponse\", true)"),
                "top-level fallback catch should leave a low-cardinality blank-response breadcrumb");
    }

    @Test
    void retryBudgetExceededIsProjectedToTraceBeforeThrowing() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        String traceSource = Files.readString(
                Path.of("main/java/com/example/lms/service/LlmRetryBudgetTrace.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);
        int budgetCheck = method.indexOf("elapsedMs > budgetMs");
        int traceCall = method.indexOf("LlmRetryBudgetTrace.emit(", budgetCheck);
        int throwSite = method.indexOf("throw new RuntimeException(\"LLM retry budget exceeded\"", budgetCheck);

        assertTrue(traceCall > budgetCheck,
                "retry-budget overflow should leave a TraceStore breadcrumb before throwing");
        assertTrue(traceCall < throwSite,
                "retry-budget breadcrumb must be written before the exception leaves callWithRetry");
        assertTrue(traceSource.contains("TraceStore.put(\"llm.retryBudget.exceeded\", true)"));
        assertTrue(traceSource.contains("TraceStore.put(\"llm.retryBudget.code\", safeCode)"));
        assertTrue(traceSource.contains("TraceStore.nextSequence(\"llm.retryBudget.exceeded\")"));
    }

    @Test
    void requestedModelDraftCallsUseSelectionScopedTimeout() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(source.contains("import com.example.lms.llm.RequestedModelTimeoutPolicy;"));
        assertTrue(method.contains("RequestedModelTimeoutPolicy.timeoutSeconds(dto.getModel(), resolved, llmTimeoutSeconds"));
        assertTrue(method.contains("requestBudgetBoundedLlmTimeout(defaultCallTimeoutMs,"));
        assertTrue(method.contains("callTimeoutBudgetSeconds"));
        assertFalse(method.contains("Duration.ofSeconds(llmTimeoutSeconds),\r\n                        \"chat_draft\""));
        assertFalse(method.contains("Duration.ofSeconds(llmTimeoutSeconds),\n                        \"chat_draft\""));
    }

    @Test
    void callWithRetryCapsRetryBudgetByRequestTimeBudgetContext() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int callWithRetry = source.indexOf("private String callWithRetry(ChatModel model,");
        int nextMethod = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", callWithRetry);
        String method = source.substring(callWithRetry, nextMethod);

        assertTrue(source.contains("import com.abandonware.ai.addons.budget.TimeBudget;"));
        assertTrue(source.contains("import com.abandonware.ai.addons.budget.TimeBudgetContext;"));
        assertTrue(method.contains("TimeBudget requestBudget = TimeBudgetContext.get()"),
                "stream and sync callers that set X-Budget-Ms must cap LLM retry work at the request budget");
        assertTrue(method.contains("Math.min(configuredRetryBudgetMs, callerRemainingMs)"),
                "LLM retry budget must not outlive the caller's request budget");
        assertTrue(method.contains("TraceStore.put(\"llm.retryBudget.cappedByRequestBudget\", true)"),
                "budget capping must leave a low-cardinality trace breadcrumb");
        assertTrue(method.contains("requestBudgetBoundedLlmTimeout(defaultCallTimeoutMs,"),
                "individual LLM calls must use the capped per-attempt timeout, not the wider default timeout");
    }

    @Test
    void expiredRequestBudgetSkipsFinalModelInsteadOfStartingOneMillisecondWorker() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int evidenceTrace = source.indexOf("TraceStore.put(\"chat.evidence.present\", evidenceCount > 0)");
        int expiredGuard = source.indexOf("finalModelRequestBudget.expired()", evidenceTrace);
        int timedCall = source.indexOf("draft = callWithRetryReportingSuccess(", evidenceTrace);

        assertTrue(expiredGuard > evidenceTrace,
                "final model budget must be checked after evidence metadata is available");
        assertTrue(expiredGuard < timedCall,
                "an expired request must skip before a timed model worker is submitted");
        String guardBlock = source.substring(expiredGuard, timedCall);
        assertTrue(guardBlock.contains("TraceStore.put(\"llm.final.skipped\", \"request_budget_exhausted\")"),
                "budget skip must leave a low-cardinality reason instead of model_unavailable");
        assertTrue(guardBlock.contains("NoEvidenceChatFallback.orEvidenceFallback("),
                "expired requests should reuse the existing evidence-aware fallback result");
        assertTrue(guardBlock.contains("citableEvidence"),
                "budget fallback must preserve promoted citation metadata");
    }

    @Test
    void finalStageFallbacksCannotReintroduceEvidenceRemovedFromPrompt() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int finalStageStart = source.indexOf("if (nightmareBreaker != null) {");
        int finalStageEnd = source.indexOf("boolean verifyAnswer =", finalStageStart);
        String finalStage = source.substring(finalStageStart, finalStageEnd);
        String safeFallbackArgs = "promptWebDocs, promptVectorDocs, promptLocalDocs";
        int safeFallbackCount = finalStage.split(Pattern.quote(safeFallbackArgs), -1).length - 1;

        assertTrue(safeFallbackCount >= 4,
                "breaker, budget, and retry fallbacks must use the filtered prompt evidence lists");
        assertFalse(finalStage.contains("topDocs, vectorDocs, promptLocalDocs"),
                "raw retrieval results must not bypass quarantine or official-source filtering in a fallback");
        assertFalse(finalStage.contains("if (topDocs != null)"),
                "retry evidence-present state must be based on the filtered evidence passed to the prompt");
        assertTrue(finalStage.split(Pattern.quote(
                        "return sanitizeFallbackResult(NoEvidenceChatFallback.orEvidenceFallback("), -1).length - 1 >= 4,
                "every early final-stage fallback must pass through the deterministic output sanitizer");
        assertTrue(source.contains("private ChatResult sanitizeFallbackResult(ChatResult fallback)"));
        assertTrue(source.contains("finalAnswerPostProcessor.process("),
                "fallback sanitization must reuse the canonical final answer postprocessor");
    }

    @Test
    void everyRetryAndEndpointFallbackRechecksTheLiveRequestDeadline() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int retryStart = source.indexOf("private String callWithRetry(ChatModel model,");
        int retryEnd = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", retryStart);
        String retryCore = source.substring(retryStart, retryEnd);
        int compatStart = source.indexOf("private String tryEndpointCompatFallback(");
        int compatEnd = source.indexOf("private void recordEndpointCompatFailure", compatStart);
        String compatCore = source.substring(compatStart, compatEnd);
        int completionsStart = source.indexOf("private String callCompletionsFallback(");
        int responsesEnd = source.indexOf("private void recordModelSuccess", completionsStart);
        String directEndpointCore = source.substring(completionsStart, responsesEnd);
        String deadlineHelper = "requestBudgetBoundedLlmTimeout(";

        int retryDeadlineChecks = retryCore.split(Pattern.quote(deadlineHelper), -1).length - 1;
        assertTrue(retryDeadlineChecks >= 4,
                "preflight, primary, sampling-heal, and model-heal calls must each recheck the live request deadline");
        assertTrue(retryCore.contains("requestBudgetBoundedBackoffMillis("),
                "retry backoff must be capped by the live request deadline");
        assertTrue(compatCore.contains("catch (LlmFastBailoutException budgetExhausted)"),
                "endpoint failover must propagate request-budget exhaustion instead of recording it as provider failure");
        assertTrue(directEndpointCore.split(Pattern.quote(deadlineHelper), -1).length - 1 >= 2,
                "completions and responses fallbacks must both use request-bounded timeouts");
        assertFalse(directEndpointCore.contains("Math.max(1_000L, (long) llmTimeoutSeconds * 1000L)"),
                "Responses fallback must not replace a sub-second remaining request budget with a one-second timeout");
        assertTrue(source.contains("if (remainingMs <= 1L)"),
                "one millisecond remaining must fail soft before a timed worker or direct endpoint call is submitted");
        int finalCatchStart = source.indexOf("} catch (Exception e) {", source.indexOf("draft = callWithRetryReportingSuccess("));
        int finalCatchEnd = source.indexOf("boolean verifyAnswer =", finalCatchStart);
        String finalCatch = source.substring(finalCatchStart, finalCatchEnd);
        assertTrue(finalCatch.indexOf("LlmFastBailoutException fastBail = unwrapFastBail(e);")
                        < finalCatch.indexOf("primaryPermit.completeFailure("),
                "request-budget exhaustion must be identified before provider breaker bookkeeping");
        assertTrue(finalCatch.contains("primaryPermit.completeAbandoned(\"chat-draft-primary\", \"request-budget\")"),
                "caller budget exhaustion must release the admitted permit without penalizing the provider");
        assertTrue(finalCatch.contains("\"request_budget_exhausted\""),
                "the catch path must keep request-budget exhaustion distinct from model timeout/unavailability");
        assertTrue(finalCatch.contains("if (!requestBudgetFastBail) {\n                    recordLocalLlmOperatorAction("),
                "caller budget exhaustion must not create a model-repair operator failure signal");
        String exhaustedRethrow = "rethrowIfRequestBudgetExhausted(";
        assertTrue(retryCore.split(Pattern.quote(exhaustedRethrow), -1).length - 1 >= 4,
                "preflight, primary, and both self-heal catches must reclassify request-capped timeouts");
        assertTrue(compatCore.indexOf(exhaustedRethrow) < compatCore.indexOf("recordEndpointCompatFailure("),
                "direct endpoint timeouts caused by caller deadline must not be recorded as provider failures");
        int preflightCatch = retryCore.indexOf("catch (Exception pre)");
        assertTrue(retryCore.indexOf(exhaustedRethrow, preflightCatch)
                        < retryCore.indexOf("recordModelFailure(", preflightCatch),
                "completion preflight must reclassify request deadline before model-health bookkeeping");
    }

    @Test
    void diagnosticsOnlyFallbackCannotKeepEvidenceLabelOrCitations() {
        RagEvidenceMetadata citation = new RagEvidenceMetadata(
                "[W1]", "web", "Public source", "https://example.com", null,
                null, null, 1, 0.9d, "test");
        ChatResult fallback = ChatResult.of(
                "TRACE_JSON\n{\"diagnostic\":true}",
                "model:fallback:evidence",
                true,
                Set.of("web"),
                List.of(citation));

        ChatResult sanitized = ChatWorkflow.sanitizeFallbackResult(
                fallback,
                new FinalAnswerPostProcessor(new OutputSanitizer()));

        assertEquals("model:fallback:empty-answer", sanitized.modelUsed());
        assertTrue(sanitized.evidence().isEmpty());
        assertTrue(sanitized.evidenceMetadata().isEmpty());
        assertFalse(sanitized.content().contains("TRACE_JSON"));
    }

    @Test
    void s8FallbackMustRetainTheRequestScopedOutputContract() {
        String query = "Fictional budgeting scenario. Return exactly two labeled lines. "
                + "OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;"
                + "riskTolerance=low;purchaseCost=high. "
                + "INFERENCE: discretionaryBudget=unknown. "
                + "Do not invent or repeat any exact financial amount.";
        ChatResult fallback = ChatResult.of(
                "The local model did not return a usable answer.",
                "qwen3:8b:fallback:local-lite",
                false);

        ChatResult sanitized = ChatWorkflow.sanitizeFallbackResult(
                fallback,
                new FinalAnswerPostProcessor(new OutputSanitizer()),
                query);

        assertTrue(sanitized.content().startsWith("HOLD\n"),
                "a timeout fallback must fail closed instead of losing the S8 request contract");
        assertTrue(sanitized.content().contains("한계:"));
    }

    @Test
    void preLlmRetrievalFanoutAndBudgetsRespectRequestTimeBudget() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int metaHints = source.indexOf("metaHints.put(\"webBudgetMs\", hints.getWebBudgetMs());");
        int hybridCall = source.indexOf("hybridRetriever.retrieveAll(planned, plateLimit, sessionIdLong, metaHints)", metaHints);
        int vectorCall = source.indexOf("ragSvc.asContentRetriever(pineconeIndexName)", hybridCall);
        int guard = source.indexOf("TimeBudget preLlmRequestBudget = TimeBudgetContext.get();", metaHints);

        assertTrue(guard > metaHints, "pre-LLM retrieval guard must run after orchestration hints exist");
        assertTrue(guard < hybridCall, "pre-LLM retrieval guard must cap fanout before hybrid retrieval starts");
        assertTrue(guard < vectorCall, "pre-LLM retrieval guard must cap vector retrieval before it starts");
        assertTrue(source.contains("TraceStore.put(\"retrieval.preLlm.requestBudget.remainingMs\", preLlmRemainingMs)"),
                "retrieval budget guard must leave a low-cardinality remaining-budget breadcrumb");
        assertTrue(source.contains("TraceStore.put(\"retrieval.preLlm.budgetGuard.applied\", true)"),
                "budget clamping must be visible to stream/debug diagnostics");
        assertTrue(source.contains("planned = planned.stream().limit(preLlmMaxQueries).toList();"),
                "request-budgeted streams must reduce query fanout before provider calls");
        assertTrue(source.contains("hints = hints.toBuilder()")
                        && source.contains(".webBudgetMs(cappedWebBudgetMs)")
                        && source.contains(".vecBudgetMs(cappedVecBudgetMs)"),
                "request-budgeted streams must cap web/vector stage budgets through existing hints");
        assertTrue(source.contains("metaHints.put(\"webBudgetMs\", hints.getWebBudgetMs());")
                        && source.contains("metaHints.put(\"vecBudgetMs\", hints.getVecBudgetMs());"),
                "clamped hint budgets must be propagated to existing retriever metadata");
    }

    @Test
    void llmUnavailableWithNoEvidenceDoesNotDegradeToEvidenceOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int catchBlock = source.indexOf("LlmFastBailoutException fastBail = unwrapFastBail(e);");
        int noEvidenceFallback = source.indexOf("NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModel", catchBlock);
        int evidenceFallback = source.indexOf(
                "composeEvidenceFallback(modelFailureFallbackQuestion, promptWebDocs, promptVectorDocs", catchBlock);

        assertTrue(noEvidenceFallback >= 0,
                "LLM unavailable fallback must first detect zero-evidence conversations");
        assertTrue(noEvidenceFallback < evidenceFallback,
                "zero-evidence fallback must run before evidence-only fallback");
        String fallbackSource = Files.readString(
                Path.of("main/java/com/example/lms/service/NoEvidenceChatFallback.java"),
                StandardCharsets.UTF_8);
        assertTrue(fallbackSource.contains("\":fallback:local-lite\""),
                "zero-evidence LLM fallback should not be reported as fallback:evidence");
    }

    @Test
    void llmEvidenceFallbackCarriesPromotedCitationMetadata() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int breakerFallback = source.indexOf(
                "NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModelName");
        assertTrue(breakerFallback >= 0, "breaker-open fallback should use NoEvidenceChatFallback");
        String breakerCall = source.substring(breakerFallback, Math.min(source.length(), breakerFallback + 500));
        assertTrue(breakerCall.contains("citableEvidence"),
                "breaker-open evidence fallback must preserve promoted citation metadata");

        int catchBlock = source.indexOf("LlmFastBailoutException fastBail = unwrapFastBail(e);");
        int retryFallback = source.indexOf(
                "NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModel", catchBlock);
        assertTrue(retryFallback >= 0, "LLM retry fallback should use NoEvidenceChatFallback");
        String retryCall = source.substring(retryFallback, Math.min(source.length(), retryFallback + 500));
        assertTrue(retryCall.contains("citableEvidence"),
                "LLM retry evidence fallback must preserve promoted citation metadata");
    }

    @Test
    void configOpenChatDraftBreakerFallsBackBeforeRetryingModel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int breakerGate = source.indexOf("shouldUseChatDraftConfigBreakerFallback(breakerKey)");
        int retryPath = source.indexOf("draft = callWithRetryReportingSuccess(");

        assertTrue(breakerGate >= 0,
                "CONFIG-open chat draft breaker must have a deterministic fallback gate");
        assertTrue(retryPath >= 0, "primary model retry path must be present");
        assertTrue(breakerGate < retryPath,
                "CONFIG-open breaker fallback must run before the expensive model retry path");

        int helper = source.indexOf("private boolean shouldUseChatDraftConfigBreakerFallback");
        assertTrue(helper >= 0, "fallback gate helper must be source-visible for this contract");
        String helperSource = source.substring(helper, Math.min(source.length(), helper + 1400));
        assertTrue(helperSource.contains("nightmareBreaker.inspect(breakerKey)"),
                "fallback gate must inspect the existing breaker state without opening a new call");
        assertTrue(helperSource.contains("view.open"),
                "fallback gate should only trigger for currently open breakers");
        assertTrue(helperSource.contains("NightmareBreaker.FailureKind.CONFIG"),
                "fallback gate must be limited to non-retryable configuration failures");
        assertTrue(helperSource.contains("TraceStore.put(\"llm.chatDraft.configBreakerOpenFallback\", true);"),
                "fallback gate must leave a durable trace breadcrumb");
        assertTrue(helperSource.contains("recordLocalLlmOperatorAction(")
                        && helperSource.contains("\"llm_config_breaker_open\""),
                "fallback gate must leave the agent-visible local LLM operator action");
    }

    @Test
    void fastBailFallbackLeavesLocalLlmOperatorActionForDebugFx() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int catchBlock = source.indexOf("LlmFastBailoutException fastBail = unwrapFastBail(e);");
        int fastBailBranch = source.indexOf("if (fastBail != null) {", catchBlock);
        int elseBranch = source.indexOf("} else {", fastBailBranch);
        String fastBailSource = source.substring(fastBailBranch, elseBranch);

        assertTrue(fastBailSource.contains("recordLocalLlmOperatorAction("),
                "fast-bail fallback must leave local LLM operator action labels for final debug_fx");
        assertTrue(fastBailSource.contains("\"llm_fast_bail\""),
                "fast-bail operator action trigger reason must be low-cardinality");
        assertTrue(fastBailSource.contains("\"inspect_model_route_or_start_local_llm\""),
                "fast-bail next action should point to the existing local model route inspection");
    }

    @Test
    void weakDraftNoEvidenceFallbackUsesReadableKorean() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int weakDraftGuard = source.indexOf("EvidenceAwareGuard.looksWeak(out)");
        int lengthGuard = source.indexOf("lengthVerifier.isShort(out", weakDraftGuard);
        String weakDraftBlock = source.substring(weakDraftGuard, lengthGuard);

        assertTrue(weakDraftBlock.contains(
                "\uCDA9\uBD84\uD55C \uC99D\uAC70\uB97C \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4."),
                "weak-draft no-evidence fallback must be readable Korean");
        assertFalse(weakDraftBlock.contains("\u7570\u2478"));
        assertFalse(weakDraftBlock.contains("\uF9DD\uC575\uAD85"));
        assertFalse(weakDraftBlock.contains("\uF9E1\uC580"));
    }
}
