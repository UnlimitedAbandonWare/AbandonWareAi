package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowTraceRedactionContractTest {

    @Test
    void workflowSeedsRequestTraceEnvelopeBetweenClearAndDownstream() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int clearIndex = source.indexOf("TraceStore.clear();");
        int seedIndex = source.indexOf("ChatWorkflowRequestTraceEnvelope.seed(req, sessionKey);", clearIndex);
        int refinerIndex = source.indexOf(
                "ensembleFinalAnswerService.sampleCandidatesForRefinement", seedIndex);

        assertTrue(clearIndex >= 0);
        assertTrue(seedIndex > clearIndex);
        assertTrue(refinerIndex > seedIndex);
    }

    @Test
    void koreanLiteralCharacterCountParseFailureLeavesRedactedBreadcrumb() throws Exception {
        TraceStore.clear();
        try {
            Method parser = ChatWorkflow.class.getDeclaredMethod("koreanLiteralCharacterCount", String.class);
            parser.setAccessible(true);

            assertEquals(null, parser.invoke(null, "ownerToken=raw-character-count"));
            assertEquals(Boolean.TRUE,
                    TraceStore.get("chat.workflow.suppressed.chat.directLiteralFallback.characterCountParse"));
            assertEquals("invalid_number",
                    TraceStore.get("chat.workflow.suppressed.chat.directLiteralFallback.characterCountParse.errorType"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=raw-character-count"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void chatWorkflowTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();
        try {
            ChatWorkflowTraceSuppressions.traceSuppressed(
                    "chat.evidenceCountParse",
                    new NumberFormatException("ownerToken=raw-secret"));

            assertEquals("invalid_number",
                    TraceStore.get("chat.workflow.suppressed.chat.evidenceCountParse.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("NumberFormatException"), trace);
            assertFalse(trace.contains("ownerToken=raw-secret"), trace);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void webFallbackExceptionMessagesAreRedactedBeforeTraceStore() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"retrieval.analyzeWeb.error\", String.valueOf(e.getMessage()))"));
        assertFalse(source.contains("TraceStore.put(\"fallback.webOnly.analyzeError\", String.valueOf(e.getMessage()))"));
        assertFalse(source.contains("TraceStore.put(\"fallback.webOnly.error\", String.valueOf(e.getMessage()))"));
        assertFalse(source.contains(
                "TraceStore.put(\"retrieval.analyzeWeb.error\", SafeRedactor.safeMessage(String.valueOf(e.getMessage()), 240))"));
        assertFalse(source.contains(
                "TraceStore.put(\"fallback.webOnly.analyzeError\", SafeRedactor.safeMessage(String.valueOf(e.getMessage()), 240))"));
        assertFalse(source.contains(
                "TraceStore.put(\"fallback.webOnly.error\", SafeRedactor.safeMessage(String.valueOf(e.getMessage()), 240))"));
        assertTrue(source.contains(
                "TraceStore.put(\"retrieval.analyzeWeb.error\", SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"))"));
        assertTrue(source.contains(
                "TraceStore.put(\"fallback.webOnly.analyzeError\", SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"))"));
        assertTrue(source.contains(
                "TraceStore.put(\"fallback.webOnly.error\", SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"))"));
    }

    @Test
    void chatWorkflowDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        long exactEmptyCatchBlocks = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();

        assertEquals(0L, exactEmptyCatchBlocks);
    }

    @Test
    void riskDetectionDoesNotUseMojibakeFragileRegexMatches() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("return s.matches(\".*("),
                "detectRisk must not use a one-shot regex that can break SSE when source encoding is corrupted");
        assertTrue(source.contains("HIGH_RISK_TERMS.stream().anyMatch(s::contains)"));
        assertTrue(source.contains("HIGH_RISK_TERMS"));
    }

    @Test
    void explicitRetrievalOffControlsOverrideDomainStrategy() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF"));
        assertTrue(source.contains("Boolean.FALSE.equals(req.getUseWebSearch())"));
        assertTrue(source.contains("Boolean.FALSE.equals(req.getUseRag())"));
    }

    @Test
    void endpointCompatibilityResponseBodyTraceUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"llm.endpoint.compat.\" + ep + \".body\", body)"));
        assertFalse(source.contains("model={} body={}"));
        assertFalse(source.contains("modelId, body"));
        assertFalse(source.contains("modelId, ex.toString()"));
        assertFalse(source.contains("resolved, pre.toString()"));
        assertTrue(source.contains("String rawBody = w.getResponseBodyAsString()"));
        assertTrue(source.contains("TraceStore.put(\"llm.endpoint.compat.\" + ep + \".bodyHash\", SafeRedactor.hashValue(rawBody))"));
        assertTrue(source.contains("TraceStore.put(\"llm.endpoint.compat.\" + ep + \".bodyLength\", rawBody == null ? 0 : rawBody.length())"));
        assertTrue(source.contains("modelHash"));
        assertTrue(source.contains("bodyHash"));
        assertTrue(source.contains("bodyLength"));
    }

    @Test
    void endpointCompatibilityExceptionTraceUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"llm.endpoint.compat.\" + ep + \".error\", msg);"));
        assertTrue(source.contains("TraceStore.put(\"llm.endpoint.compat.\" + ep + \".errorHash\", SafeRedactor.hashValue(msg));"));
        assertTrue(source.contains("TraceStore.put(\"llm.endpoint.compat.\" + ep + \".errorLength\", msg == null ? 0 : msg.length())"));
    }

    @Test
    void completionsFallbackErrorMessageUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("\"Completion fallback failed: \" + err"));
        assertFalse(source.contains("SafeRedactor.safeMessage(err"));
        assertTrue(source.contains("\"Completion fallback failed errorHash=\" + SafeRedactor.hashValue(err) + \" errorLength=\" + err.length()"));
    }

    @Test
    void workflowTraceStoreDoesNotWriteRawModelOrBaseUrlValues() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"cost.zone.\" + safeZone + \".model\", model == null ? \"\" : model)"));
        assertFalse(source.contains("\"model\", model == null ? \"\" : model"));
        assertFalse(source.contains("data.put(\"model\", model == null ? \"\" : model)"));
        assertFalse(source.contains("TraceStore.put(\"llm.call.model\", resolved)"));
        assertFalse(source.contains("TraceStore.put(\"llm.call.model.requested\", requestedModel)"));
        assertFalse(source.contains("TraceStore.put(\"llm.call.model.routed\", routedModel)"));
        assertFalse(source.contains("TraceStore.put(\"llm.endpoint.compat.baseUrl\", baseUrlUsed)"));
        assertFalse(source.contains("TraceStore.put(\"llm.model.policy.blocked.model\", modelId)"));
        assertFalse(source.contains("TraceStore.put(\"llm.model.policy.blocked.model\", resolved)"));

        assertTrue(source.contains(".modelHash"));
        assertTrue(source.contains(".baseUrlHost"));
        assertTrue(source.contains(".baseUrlHash"));
    }

    @Test
    void earlyWorkflowFallbackCatchesLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        String envelopeSource = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelope.java"));
        String finalizedPersistenceSource = Files.readString(
                Path.of("main/java/com/example/lms/service/chat/FinalizedMemoryPersistence.java"));

        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"rerank.onnxBreakerOpen\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"traceStore.clear\", ignore);"));
        assertTrue(envelopeSource.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"traceSeed.requestSid\", failure);"));
        assertTrue(envelopeSource.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"traceSeed.chatSessionId\", failure);"));
        assertTrue(envelopeSource.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"traceSeed.mdcTraceId\", failure);"));
        assertTrue(envelopeSource.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"traceSeed.mdcSession\", failure);"));
        assertTrue(envelopeSource.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"traceSeed.breadcrumb\", failure);"));
        assertTrue(envelopeSource.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"traceSeed.envelope\", failure);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"searchPolicy.decide\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"webHardDown.stageOffCheck\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"webHardDown.nowCheck\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"webHardDown.fallbackCheck\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"rerank.secondPass.guard\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"needle.probe\", e);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"finalEvidence.trace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"prompt.localDocs\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"prompt.finalEvidenceMirror\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"prompt.builderRequired\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"evidenceGuard.preDecisionProjection\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"guard.outcomeTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"guard.detourCheapRetry\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"answer.guardRecovery\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"projection.view2.pipeline\", e);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"dualVision.view2.generation\", e);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions::traceSuppressed"));
        assertTrue(source.contains("\"memory.learningWriteInterceptor\""));
        assertTrue(source.contains("\"memory.understandAndMemorize\""));
        assertTrue(finalizedPersistenceSource.contains(
                "suppressionSink.suppressed(stage.suppressionReason(), failure);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"model.resolveName\", e);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"evidence.appendix\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"chat.draftBreakerOpen\", oce);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"llm.chatDraft\", e);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"emptyAnswerGuard.triggerTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"emptyAnswerGuard.evidenceDocsTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"finalAnswer.postprocess.trace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"emptyAnswerGuard.fallbackTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"emptyAnswerGuard.finalFallbackTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"evidenceOnly.compose\", e);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"guard.detour.requiredCitationsTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"guard.detour.contextCitationsTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"guard.detour.forceEscalateTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"guard.detour.combineSitesModeTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"guard.detour.providerSupportsOr\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"evidence.keyForContent\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"evidence.urlMetadata\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"evidence.urlText\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"evidence.urlOuter\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"content.dedupeKey\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"llm.answerOverridesTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"rag.pipelineEvent\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"cost.zoneTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"cost.zoneDebugEvent\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"chat.evidenceCountParse\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"chat.evidencePresentTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"selfask.promptFilterTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"trace.safeString\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"trace.hostOf\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"trace.safeDouble\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"trace.safeBool\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"llm.endpointCompatAttempt\", ex);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"uaw.autolearn.preemptionSupplier\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"finalRescue.compose\", e);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"finalRescue.trace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"finalRescue.breaker\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"projection.freeIdea.stepRequest\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"projection.freeIdea.draft\", e);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"projection.final.stepRequest\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"prompt.systemPromptRejectionTrace\", ignore);"));
        assertTrue(source.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"selfask.planBool\", ignore);"));
        assertFalse(source.contains("ChatWorkflowTraceSuppressions.trace(\""));
    }

    @Test
    void evidenceCountParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int idx = source.indexOf("cInt = Integer.parseInt(String.valueOf(c));");
        assertTrue(idx >= 0, "missing chat evidence count parser");
        String window = source.substring(idx, Math.min(source.length(), idx + 260));

        assertTrue(window.contains("catch (NumberFormatException ignore)"),
                "chat evidence count parser should catch only NumberFormatException");
        assertFalse(window.contains("catch (Exception ignore)"),
                "chat evidence count parser should not swallow broad Exception");
        assertTrue(window.contains("ChatWorkflowTraceSuppressions.traceSuppressed(\"chat.evidenceCountParse\", ignore);"));
    }

    @Test
    void publicSystemPromptRejectedReasonUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"prompt.systemPrompt.rejectedReason\", reason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"prompt.systemPrompt.rejectedReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"prompt.systemPrompt.rejectedReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void criticDetourReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"rag.critic.reason\", detourReason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"rag.critic.reason\", SafeRedactor.safeMessage(detourReason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"rag.critic.reason\", SafeRedactor.traceLabelOrFallback(detourReason, \"unknown\"));"));
    }

    @Test
    void learningDegradedReasonDiagnosticsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"prompt.learningDegradedReason\", learningDegradedReason);"));
        assertFalse(source.contains("TraceStore.put(\"prompt.ragSupport.degradedReason\", learningDegradedReason);"));
        assertFalse(source.contains("pev.put(\"learningDegradedReason\", learningDegradedReason);"));
        assertFalse(source.contains("pev.put(\"ragSupportDegradedReason\", learningDegradedReason);"));
        assertFalse(source.contains("dd.put(\"learningDegradedReason\", debugLearningDegradedReason);"));
        assertFalse(source.contains("dd.put(\"ragSupportDegradedReason\", debugLearningDegradedReason);"));
        assertFalse(source.contains(
                "String safeLearningDegradedReason = SafeRedactor.safeMessage(learningDegradedReason, 120);"));
        assertFalse(source.contains(
                "String safeDebugLearningDegradedReason = SafeRedactor.safeMessage(debugLearningDegradedReason, 120);"));

        assertTrue(source.contains(
                "String safeLearningDegradedReason = SafeRedactor.traceLabelOrFallback(learningDegradedReason, \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"prompt.learningDegradedReason\", safeLearningDegradedReason);"));
        assertTrue(source.contains("TraceStore.put(\"prompt.ragSupport.degradedReason\", safeLearningDegradedReason);"));
        assertTrue(source.contains("pev.put(\"learningDegradedReason\", safeLearningDegradedReason);"));
        assertTrue(source.contains("pev.put(\"ragSupportDegradedReason\", safeLearningDegradedReason);"));
        assertTrue(source.contains(
                "String safeDebugLearningDegradedReason = SafeRedactor.traceLabelOrFallback(debugLearningDegradedReason, \"unknown\");"));
        assertTrue(source.contains("dd.put(\"learningDegradedReason\", safeDebugLearningDegradedReason);"));
        assertTrue(source.contains("dd.put(\"ragSupportDegradedReason\", safeDebugLearningDegradedReason);"));
    }

    @Test
    void promptContextFailSoftExceptionsUseStableOperationalLabels() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"prompt.context.composer.exception\", ex.getClass().getSimpleName())"),
                "prompt composer fail-soft trace must not expose Java exception class names");
        assertFalse(source.contains("TraceStore.put(\"prompt.memory.compressor.exception\", ex.getClass().getSimpleName())"),
                "memory compressor fail-soft trace must not expose Java exception class names");
        assertFalse(source.contains("TraceStore.put(\"prompt.learning.exception\", ex.getClass().getSimpleName())"),
                "learning context fail-soft trace must not expose Java exception class names");
        assertFalse(source.contains("TraceStore.put(\"prompt.ragSupport.exception\", ex.getClass().getSimpleName())"),
                "RAG support fail-soft trace must not expose Java exception class names");
        assertFalse(source.contains("RagLearningSupportContext.degraded(ex.getClass().getSimpleName())"),
                "learning degraded reason should not carry Java exception class names toward prompt metadata");

        assertTrue(source.contains("TraceStore.put(\"prompt.context.composer.exception\", \"prompt_context_composer_failed\")"),
                "prompt composer should leave a stable fail-soft label");
        assertTrue(source.contains("TraceStore.put(\"prompt.memory.compressor.exception\", \"memory_compressor_failed\")"),
                "memory compressor should leave a stable fail-soft label");
        assertTrue(source.contains("TraceStore.put(\"prompt.learning.exception\", \"learning_context_failed\")"),
                "learning context should leave a stable fail-soft label");
        assertTrue(source.contains("TraceStore.put(\"prompt.ragSupport.exception\", \"learning_context_failed\")"),
                "RAG support context should leave a stable fail-soft label");
        assertTrue(source.contains("RagLearningSupportContext.degraded(\"learning_context_failed\")"),
                "learning degraded reason should be a stable operational label");
    }

    @Test
    void searchNeedleAndSecondPassReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"search.policy.reason\", searchPolicyDecision.reason());"));
        assertFalse(source.contains(
                "TraceStore.put(\"needle.plan.reason\", needle.plan() == null ? \"unknown\" : needle.plan().reason());"));
        assertFalse(source.contains("TraceStore.put(\"rerank.secondPass.guard.reason\", \"budget_low\");"));
        assertFalse(source.contains("TraceStore.put(\"rerank.secondPass.guard.reason\", \"cancelled\");"));
        assertFalse(source.contains(
                "TraceStore.put(\"search.policy.reason\", SafeRedactor.safeMessage(searchPolicyDecision.reason(), 120));"));
        assertFalse(source.contains(
                "TraceStore.put(\"needle.plan.reason\", SafeRedactor.safeMessage(needle.plan() == null ? \"unknown\" : needle.plan().reason(), 120));"));
        assertFalse(source.contains(
                "TraceStore.put(\"rerank.secondPass.guard.reason\", SafeRedactor.safeMessage(\"budget_low\", 120));"));
        assertFalse(source.contains(
                "TraceStore.put(\"rerank.secondPass.guard.reason\", SafeRedactor.safeMessage(\"cancelled\", 120));"));
        assertFalse(source.contains(
                "SafeRedactor.safeMessage((topDocs == null || topDocs.isEmpty()) ? \"empty_second_pass\" : \"score_drop\", 120)"));
        assertTrue(source.contains(
                "TraceStore.put(\"search.policy.reason\", SafeRedactor.traceLabelOrFallback(searchPolicyDecision.reason(), \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"needle.plan.reason\", SafeRedactor.traceLabelOrFallback(needle.plan() == null ? \"unknown\" : needle.plan().reason(), \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"rerank.secondPass.guard.reason\", SafeRedactor.traceLabelOrFallback(\"budget_low\", \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"rerank.secondPass.guard.reason\", SafeRedactor.traceLabelOrFallback(\"cancelled\", \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"rerank.secondPass.guard.reason\",\n                                    SafeRedactor.traceLabelOrFallback((topDocs == null || topDocs.isEmpty()) ? \"empty_second_pass\" : \"score_drop\", \"unknown\"));")
                || source.contains(
                "TraceStore.put(\"rerank.secondPass.guard.reason\",\r\n                                    SafeRedactor.traceLabelOrFallback((topDocs == null || topDocs.isEmpty()) ? \"empty_second_pass\" : \"score_drop\", \"unknown\"));"));
    }

    @Test
    void orchestrationReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"orch.reasons\", sig.reasons());"));
        assertFalse(source.contains("TraceStore.put(\"orch.reason\", sig.reason());"));
        assertFalse(source.contains("TraceStore.put(\"orch.reason\", hints.getBypassReason());"));
        assertFalse(source.contains(".map(reason -> SafeRedactor.safeMessage(reason, 120))"));
        assertFalse(source.contains("TraceStore.put(\"orch.reason\", SafeRedactor.safeMessage(sig.reason(), 120));"));
        assertFalse(source.contains(
                "TraceStore.put(\"orch.reason\", SafeRedactor.safeMessage(hints.getBypassReason(), 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"orch.reasons\", sig.reasons() == null ? java.util.List.of() : sig.reasons().stream()"));
        assertTrue(source.contains(".map(reason -> SafeRedactor.traceLabelOrFallback(reason, \"unknown\"))"));
        assertTrue(source.contains("TraceStore.put(\"orch.reason\", SafeRedactor.traceLabelOrFallback(sig.reason(), \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"orch.reason\", SafeRedactor.traceLabelOrFallback(hints.getBypassReason(), \"unknown\"));"));
    }

    @Test
    void workflowLlmFailureLogsDoNotWriteRawSessionModelOrEndpointValues() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("[Chat] cancelled. sessionId={}"));
        assertFalse(source.contains("[LLM_CONFIG] code={} model={} endpoint={}"));
        assertFalse(source.contains("degrade-to-evidence. sessionId={}, model={}"));
        assertFalse(source.contains("unavailable after retries. sessionId={}, model={}"));
        assertFalse(source.contains("non-retryable (model not found). model={} detail={}"));
        assertFalse(source.contains("TraceStore.put(\"llm.config.model\", cfg.getModel())"));
        assertFalse(source.contains("TraceStore.put(\"llm.config.endpoint\", cfg.getEndpoint())"));

        assertTrue(source.contains("sessionHash"));
        assertTrue(source.contains("endpointHash"));
        assertTrue(source.contains("endpointHost"));
    }

    @Test
    void workflowLlmFailureRecordsRedactedOperatorActionForHeartbeat() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains(
                "recordLocalLlmOperatorAction(\"llm_unavailable_after_retries\", \"model_unavailable\", \"inspect_model_route_or_start_local_llm\", 100, 1);"));
        assertTrue(source.contains("TraceStore.put(\"llm.localSmoke.operatorAction.triggered\", true);"));
        assertTrue(source.contains("TraceStore.put(\"llm.localSmoke.operatorAction.failureClass\","));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(failureClass, \"model_unavailable\")"));
        assertTrue(source.contains("TraceStore.put(\"llm.localSmoke.operatorAction.nextAction\","));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(nextAction, \"inspect_model_route\")"));
        assertFalse(source.contains("TraceStore.put(\"llm.localSmoke.operatorAction.rawError\""));
        assertFalse(source.contains("TraceStore.put(\"llm.localSmoke.operatorAction.rawPrompt\""));
    }

    @Test
    void workflowMemoryModeLogsUseSessionHashes() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("skip memory context load for session {}"));
        assertFalse(source.contains("skip reinforcement for session {}"));
        assertTrue(source.contains("skip memory context load for sessionHash={}"));
        assertTrue(source.contains("skip reinforcement for sessionHash={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(req.getSessionId()))"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(sessionKey))"));
    }

    @Test
    void dynamicModelRebuildGuardLogUsesSafeReasonAndModelHash() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("keep original model='{}'"));
        assertFalse(source.contains("guard.getMessage(), resolved"));
        assertTrue(source.contains("dynamic model rebuild blocked: reason={} originalModelHash={} originalModelLength={}"));
        assertTrue(source.contains("SafeRedactor.safeMessage(guard.getMessage(), 180)"));
        assertTrue(source.contains("SafeRedactor.hashValue(resolved)"));
    }

    @Test
    void workflowLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        org.junit.jupiter.api.Assertions.assertEquals(List.of(), rawThrowableLogLines);
    }

    @Test
    void workflowFallbackDiagnosticsUseStableLabelsAndRedactedThrowableLogs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("TraceStore.put(\"rerank.fallback.error\", e.getClass().getSimpleName());"));
        assertFalse(source.contains("TraceStore.put(\"rag.evidence.promotion.exception\", ex.getClass().getSimpleName());"));
        assertFalse(source.contains("TraceStore.put(\"guard.detour.cheapRetry.web.error\", e.getClass().getSimpleName());"));
        assertFalse(source.contains("TraceStore.put(\"guard.detour.cheapRetry.regen.error\", e.getClass().getSimpleName());"));
        assertFalse(source.contains("e.toString());"));
        assertFalse(source.contains("composerError.toString());"));

        assertTrue(source.contains("TraceStore.put(\"rerank.fallback.error\", \"reranker_failed\");"));
        assertTrue(source.contains("TraceStore.put(\"rag.evidence.promotion.exception\", \"evidence_promotion_failed\");"));
        assertTrue(source.contains("TraceStore.put(\"guard.detour.cheapRetry.web.error\", \"cheap_retry_web_failed\");"));
        assertTrue(source.contains("TraceStore.put(\"guard.detour.cheapRetry.regen.error\", \"cheap_retry_regen_failed\");"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(composerError), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e2), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(pre), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(healEx), 180)"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length()"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e2)), String.valueOf(e2).length()"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(pre)), String.valueOf(pre).length()"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(healEx)), String.valueOf(healEx).length()"));
    }

    @Test
    void guardDetourCheapRetryReadsCanonicalStarvationTriggerAlias() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("Object trig0 = TraceStore.get(\"web.failsoft.starvationFallback.trigger\");"));
        assertTrue(source.contains("TraceStore.get(\"starvationFallback.trigger\")"));
        assertTrue(source.contains("TraceStore.put(\"guard.detour.cheapRetry.forceEscalate.trigger\", String.valueOf(trig0));"));
    }
}
