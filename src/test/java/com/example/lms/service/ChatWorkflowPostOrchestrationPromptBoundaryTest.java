package com.example.lms.service;

import org.junit.jupiter.api.Test;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

import java.nio.file.Files;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowPostOrchestrationPromptBoundaryTest {

    @Test
    void postOrchestrationAnswerPathsUsePromptBuilderBoundary() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("private List<ChatMessage> buildPostOrchestrationPromptMessages"));
        assertTrue(source.contains("String built = promptBuilder.build(ctx);"));
        assertTrue(source.contains("TraceStore.put(\"prompt.postOrchestration.usesPromptBuilder\", true);"));
        assertTrue(source.contains("TraceStore.put(\"prompt.postOrchestration.stage\", SafeRedactor.traceLabelOrFallback(stage, \"unknown\"));"));
        assertTrue(source.contains("TraceStore.put(\"prompt.builder.required\", true);"));
        assertTrue(source.contains("TraceStore.put(\"prompt.builder.required.enforced\", ctxText != null);"));
        assertTrue(source.contains("\"guard.detour.cheapRetry.regen\", system, user"));
        assertTrue(source.contains("\"projection.free-idea.legacy\", sys, user"));
        assertTrue(source.contains("\"projection.free-idea.plan\", system.toString(), user"));
        assertTrue(source.contains("\"projection.final\", system.toString(), user"));
        assertTrue(countOccurrences(source, "buildPostOrchestrationPromptMessages(") >= 5);
    }

    @Test
    void selfAskLaneGateFiltersBeforePromptContextBuild() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("filterPromptEligibleSelfAsk(topDocs)"));
        assertTrue(source.contains("filterPromptEligibleSelfAsk(vectorDocs)"));
        assertTrue(source.contains(".web(promptWebDocs)"));
        assertTrue(source.contains(".rag(promptVectorDocs)"));
        assertTrue(source.contains("isPromptEligible"));
        assertTrue(source.contains("grandas_managed"));
        assertTrue(source.contains("PromptBuilder.build(ctx)"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void grandasManagedCandidatesRequirePromptEligibleTrue() throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod("filterPromptEligibleSelfAsk", List.class);
        method.setAccessible(true);

        Content unmanaged = content("plain unmanaged evidence", Map.of("retrieval_stage", "web"));
        Content rejected = content("managed but rejected", Map.of(
                "grandas_managed", "true",
                "promptEligible", "false"));
        Content accepted = content("managed and accepted", Map.of(
                "grandas_managed", "true",
                "promptEligible", "true"));

        List<Content> filtered = (List<Content>) method.invoke(null, List.of(unmanaged, rejected, accepted));

        assertEquals(2, filtered.size());
        assertEquals("plain unmanaged evidence", filtered.get(0).textSegment().text());
        assertEquals("managed and accepted", filtered.get(1).textSegment().text());
    }

    @Test
    @SuppressWarnings("unchecked")
    void needleMergeReservesProbeCandidatesWithoutDroppingFirstTopEvidence() throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "mergeNeedleCandidates", List.class, List.class, List.class, int.class);
        method.setAccessible(true);

        Content top1 = content("top one", Map.of("url", "https://top.example/one"));
        Content top2 = content("top two", Map.of("url", "https://top.example/two"));
        Content top3 = content("top three", Map.of("url", "https://top.example/three"));
        Content needle = content("needle official evidence", Map.of("url", "https://needle.example/evidence"));
        Content fused = content("fused evidence", Map.of("url", "https://fused.example/evidence"));

        List<Content> merged = (List<Content>) method.invoke(null,
                List.of(top1, top2, top3),
                List.of(needle),
                List.of(fused),
                3);

        assertEquals(3, merged.size());
        assertEquals("top one", merged.get(0).textSegment().text());
        assertTrue(merged.stream().anyMatch(c -> c.textSegment().text().equals("needle official evidence")));
    }

    @Test
    void needleProbeTraceDoesNotWriteRawPlannedQueries() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        String queryTransformerSource = Files.readString(Path.of("main/java/com/example/lms/transform/QueryTransformer.java"));
        String needlePlannerSource = Files.readString(Path.of("main/java/com/example/lms/transform/QueryTransformerNeedlePlanner.java"));

        assertFalse(source.contains("TraceStore.put(\"needle.plan.queries\""));
        assertTrue(source.contains("TraceStore.put(\"needle.plan.queryHashes\""));
        assertTrue(source.contains("TraceStore.put(\"needle.plan.queryCount\""));
        assertFalse(queryTransformerSource.contains("TraceStore.put(\"probe.needle.llm.sites\""));
        assertFalse(queryTransformerSource.contains("TraceStore.put(\"probe.needle.llm.queries\""));
        assertFalse(needlePlannerSource.contains("TraceStore.put(\"probe.needle.llm.sites\""));
        assertFalse(needlePlannerSource.contains("TraceStore.put(\"probe.needle.llm.queries\""));
        assertTrue(needlePlannerSource.contains("putHashedListTrace(\"probe.needle.llm.site\""));
        assertTrue(needlePlannerSource.contains("putHashedListTrace(\"probe.needle.llm.query\""));
    }

    @Test
    void prefetchDiagnosticsKeepRawFieldsInternalAndExposeOnlyHashes() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("metaHints.put(\"prefetch.web.query\", q0);"));
        assertTrue(source.contains("metaHints.put(\"prefetch.web.snippets\", prefetched);"));
        assertTrue(source.contains("addPrefetchDiagnostics(metaHints, q0, prefetched);"));
        assertTrue(source.contains("\"prefetch.web.queryHash\""));
        assertTrue(source.contains("\"prefetch.web.queryLength\""));
        assertTrue(source.contains("\"prefetch.web.queryTokenBucket\""));
        assertTrue(source.contains("\"prefetch.web.snippetCount\""));
        assertTrue(source.contains("\"prefetch.web.snippetHash12\""));
        assertFalse(source.contains("TraceStore.put(\"prefetch.web.query\""));
        assertFalse(source.contains("TraceStore.put(\"prefetch.web.snippets\""));
    }

    @Test
    void centralAppendixOwnsPromotedAndLegacyPathsAfterBlankRescue() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        String normalized = source.replace("\r\n", "\n");

        assertTrue(source.contains("TraceStore.put(\"rag.evidence.appendix.skipped\", \"no_promoted_evidence\");"));
        assertTrue(source.contains("emptyAnswerGuard(out, finalQuery, topDocs, vectorDocs)"));
        assertFalse(source.contains("chat.emptyAnswerGuard.appendixSkipped"));
        assertTrue(normalized.contains("return appendEvidenceReferencesIfNeeded(\n                    answer,"));
        assertEquals(1, countOccurrences(source, "appendFinalEvidenceAppendix("));
    }

    @Test
    void emptyAnswerGuardFallbackKeepsProvenanceInModelLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int fallbackFlag = source.indexOf("boolean emptyAnswerFallbackApplied = out == null || out.isBlank();");
        int guard = source.indexOf(
                "out = emptyAnswerGuard(out, finalQuery, topDocs, vectorDocs);",
                fallbackFlag);
        int modelSuffix = source.indexOf("modelUsed = modelUsed + \":fallback:empty-answer\";", guard);
        int result = source.indexOf("return ChatResult.of(out, modelUsed, ragUsed", modelSuffix);

        assertTrue(fallbackFlag >= 0 && fallbackFlag < guard,
                "empty-answer provenance must be captured before the guard synthesizes visible text");
        assertTrue(modelSuffix > guard && modelSuffix < result,
                "guard-generated text must retain fallback provenance in modelUsed");
    }

    @Test
    void promptBoundaryExtendsToChatWorkflowReachableAuxiliaryPaths() throws Exception {
        String expander = Files.readString(Path.of("main/java/com/example/lms/service/answer/AnswerExpanderService.java"));
        String verifier = Files.readString(Path.of("main/java/com/example/lms/service/FactVerifierService.java"));
        String orchestrator = Files.readString(Path.of("main/java/com/example/lms/service/rag/ContextOrchestrator.java"));
        String fallback = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/FallbackBannerAspect.java"));

        assertTrue(expander.contains("promptBuilder.build(ctx)"));
        assertFalse(expander.contains("SystemMessage.from(\"You are a cautious Korean editor"));

        assertTrue(verifier.contains("buildVerifierPrompt(\"FACT_META_CHECK\""));
        assertTrue(verifier.contains("buildVerifierPrompt(\"FACT_CORRECTION\""));
        assertFalse(verifier.contains("String metaPrompt = String.format(META_TEMPLATE"));
        assertFalse(verifier.contains("String correctionPrompt = String.format(CORRECTION_TEMPLATE"));

        assertTrue(orchestrator.contains("private final PromptBuilder promptBuilder;"));
        assertFalse(orchestrator.contains("promptEngine.createPrompt(ctx)"));

        assertTrue(fallback.contains("PromptContext.builder()"));
        assertTrue(fallback.contains("promptBuilder.build(ctx)"));
        assertFalse(fallback.contains("List.of(SystemMessage.from(sys), UserMessage.from(user))"));
    }

    @Test
    void fallbackBannerAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/FallbackBannerAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "fallback banner fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void fallbackBannerIntegerParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/FallbackBannerAspect.java"))
                .replace("\r\n", "\n");
        String parserCall = "return Integer.parseInt(v.trim());";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "fallback banner integer parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "fallback banner numeric parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "fallback banner numeric parser fallback should catch only NumberFormatException");
    }

    @Test
    void completionsFallbackUsesStageNamedPromptVariable() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        String normalized = source.replace("\r\n", "\n");

        assertFalse(source.contains("String prompt = OpenAiEndpointCompatibility.toCompletionsPrompt(msgs);"));
        assertFalse(normalized.contains("modelId,\n                prompt,"));
        assertTrue(source.contains("String completionsFallbackPrompt = OpenAiEndpointCompatibility.toCompletionsPrompt(msgs);"));
        assertTrue(normalized.contains("modelId,\n                completionsFallbackPrompt,"));
    }

    @Test
    void finalAnswerPathUsesPrimaryOnceAfterOptionalReferenceSampling() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("private final com.example.lms.ensemble.EnsembleFinalAnswerService ensembleFinalAnswerService;"));
        int refinerCall = source.indexOf("ensembleFinalAnswerService.sampleCandidatesForRefinement");
        int promptBuild = source.indexOf("String ctxText = promptBuilder.build(ctx);");
        int finalBlock = source.indexOf("ChatRequestDto finalReq = applyFinalAnswerSamplingOverrides(llmReq);");
        int primaryCall = source.indexOf("draft = callWithRetryReportingSuccess(", finalBlock);
        int cancellationCheck = source.indexOf("throwIfCancelled(sessionIdLong);", finalBlock);
        int finalOwner = source.indexOf(
                "TraceStore.put(\"ensemble.finalAnswerOwner\", \"primary_model\")", finalBlock);

        assertTrue(refinerCall >= 0 && refinerCall < promptBuild);
        assertTrue(promptBuild < primaryCall);
        assertTrue(primaryCall < cancellationCheck);
        assertTrue(cancellationCheck < finalOwner);
        assertEquals(1, countOccurrences(source.substring(finalBlock, cancellationCheck),
                "callWithRetryReportingSuccess("));
        assertTrue(source.contains("boolean strictSingleAttempt = hasThreeRoleRefinementCandidates(ctx);"),
                "an attached three-role refinement must select the strict one-attempt primary path");
        assertTrue(Pattern.compile("primarySuccessRef::set\\s*,\\s*strictSingleAttempt")
                        .matcher(source).find(),
                "the final primary call must receive the three-role one-attempt decision");
        assertTrue(source.contains("final int maxAttempts = strictSingleAttempt ? 0 : llmMaxAttempts;"),
                "strict primary adjudication must suppress workflow-level retries");
        assertTrue(source.contains("if (!strictSingleAttempt && (hintCompletions || hintResponses || chatEndpointMissing))"),
                "strict primary adjudication must not expand into endpoint fallback calls");
        assertTrue(source.contains("if (!strictSingleAttempt && !selfHealed"),
                "strict primary adjudication must not expand into parameter self-heal calls");
        assertTrue(source.contains("if (!strictSingleAttempt && !modelHealed"),
                "strict primary adjudication must not expand into model self-heal calls");
        assertTrue(source.contains("TraceStore.put(\"ensemble.finalAnswerOwner\", \"primary_model\")"));
    }

    @Test
    void refinerTraceCanRepresentAllThreeApiRoles() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains(
                "TraceStore.put(\"prompt.context.refiner.candidateCount\", Math.min(3, refinementCandidates.size()))"));
        assertFalse(source.contains(
                "TraceStore.put(\"prompt.context.refiner.candidateCount\", Math.min(2, refinementCandidates.size()))"));
    }

    @Test
    void referenceOnlyFinalPathHasNoJudgeDraftOrHealthBypass() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int finalBlock = source.indexOf("ChatRequestDto finalReq = applyFinalAnswerSamplingOverrides(llmReq);");
        int finalCatch = source.indexOf("} catch (CancellationException ce) {", finalBlock);
        String primaryOnlyBlock = source.substring(finalBlock, finalCatch);

        assertFalse(primaryOnlyBlock.contains("tryGenerate("));
        assertFalse(primaryOnlyBlock.contains("judgeService"));
        assertFalse(primaryOnlyBlock.contains("ensembleDraftUsed"));
        assertFalse(primaryOnlyBlock.contains("ensembleEvidenceHold"));
        assertTrue(primaryOnlyBlock.contains("if (primarySuccess != null)"));
        assertTrue(primaryOnlyBlock.contains("if (primaryPermit != null)"));
        assertTrue(primaryOnlyBlock.contains("primaryPermit.completeSuccess(ms);"));
    }

    @Test
    void contextRefinerPropagatesCancellationBeforeFailSoftCatch() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"))
                .replace("\r\n", "\n");
        int refinerCall = source.indexOf("ensembleFinalAnswerService.sampleCandidatesForRefinement");
        int cancellationCatch = source.indexOf("catch (CancellationException ce)", refinerCall);
        int failSoftCatch = source.indexOf("catch (Throwable ex)", refinerCall);

        assertTrue(refinerCall >= 0);
        assertTrue(source.contains("sampleCandidatesForRefinement(refinerSeedCtx, sessionIdLong,"));
        assertTrue(source.contains("() -> throwIfCancelled(sessionIdLong)"));
        assertTrue(cancellationCatch > refinerCall && cancellationCatch < failSoftCatch);
        assertTrue(source.substring(cancellationCatch, failSoftCatch).contains("throw ce;"));
    }

    @Test
    void dualVisionFallbackDoesNotAppendCreativeOutsidePromptBuilderBoundary() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertFalse(source.contains("out = out + \"\\n\\n---\\n###"));
        assertTrue(source.contains("TraceStore.put(\"prompt.dualvision.creative.skipped\", \"no_merge_service\")"));
        assertTrue(source.contains("TraceStore.put(\"prompt.projection.creative.skipped\", \"no_merge_service\")"));
    }

    @Test
    void chatApiDtoForCallPreservesPromptPolicyFields() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertTrue(countOccurrences(source, "ChatRequestDto dtoForCall = dto.toBuilder()") >= 2);
        assertFalse(source.contains("ChatRequestDto dtoForCall = ChatRequestDto.builder()"));
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

    private static Content content(String text, Map<String, Object> metadata) {
        return Content.from(TextSegment.from(text, Metadata.from(metadata)));
    }
}
