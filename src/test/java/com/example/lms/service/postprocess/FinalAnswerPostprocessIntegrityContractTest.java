package com.example.lms.service.postprocess;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalAnswerPostprocessIntegrityContractTest {

    @Test
    void policyGatedFinalVerifierCallSiteFollowsSemanticMutationsAndPrecedesSinglePostprocessBoundary() throws Exception {
        String source = read("main/java/com/example/lms/service/ChatWorkflow.java");

        int weakRecovery = source.lastIndexOf("out = evidenceAwareGuard.degradeToEvidenceList(_ev);");
        int expansion = source.indexOf("out = Optional.ofNullable(answerExpander.expandWithLc");
        int projectionMerge = source.lastIndexOf("out = projectionMergeService.mergeDualView(out, creative);");
        int projectionFinal = source.indexOf("out = finalizeProjectionAnswerFromPlan(");
        int groundedOverwrite = source.indexOf("out = groundedFieldAnswer;");
        int emptyRescue = source.indexOf("out = emptyAnswerGuard(");
        int finalVerifier = source.lastIndexOf("verifier.verifyDetailed(");
        int verificationGate = source.lastIndexOf("if (verifyAnswer) {", finalVerifier);
        int releaseGate = source.indexOf(
                "FinalVerificationReleaseDecision releaseDecision = applyFinalVerificationReleaseGate(",
                finalVerifier);
        int appendix = source.indexOf("out = appendFinalEvidenceOnce(");
        int postprocess = source.indexOf("finalAnswerPostProcessor.process(");
        int memory = source.indexOf("learningWriteInterceptor.ingest(");
        int result = source.indexOf("return ChatResult.of(out, modelUsed, ragUsed");

        int lastSemanticMutation = IntStream.of(
                        weakRecovery,
                        expansion,
                        projectionMerge,
                        projectionFinal,
                        groundedOverwrite,
                        emptyRescue)
                .peek(index -> assertTrue(index >= 0, "semantic mutation anchor missing"))
                .max()
                .orElseThrow();

        assertEquals(1, countOccurrences(source, "verifier.verifyDetailed("),
                "final verifier must have one policy-gated call site");
        assertTrue(lastSemanticMutation < finalVerifier, "final verifier must follow every semantic mutation");
        assertTrue(appearsInsideImmediateBlock(source, verificationGate, finalVerifier),
                "final verifier call site must stay inside the verifyAnswer policy gate");
        assertTrue(finalVerifier < releaseGate,
                "release gate must consume the final verifier outcome");
        assertTrue(releaseGate < appendix,
                "release gate must run before deterministic appendix packaging");
        assertTrue(finalVerifier < appendix, "appendix is deterministic packaging after final verification");
        assertTrue(appendix < postprocess, "one postprocessor must own final cleanup after appendix");
        assertTrue(postprocess < memory, "memory decision must consume the postprocessed result");
        assertTrue(memory < result, "memory decision must finish before ChatResult");
    }

    @Test
    void policyGateScopeCheckRejectsVerifierAfterGateClose() {
        String source = "if (verifyAnswer) {\n}\nverifier.verifyDetailed(";
        int gate = source.indexOf("if (verifyAnswer) {");
        int verifier = source.indexOf("verifier.verifyDetailed(");

        assertFalse(appearsInsideImmediateBlock(source, gate, verifier));
    }

    @Test
    void evidenceAppendixHasOneWorkflowOwner() throws Exception {
        String source = read("main/java/com/example/lms/service/ChatWorkflow.java");

        assertEquals(1, countOccurrences(source, "appendFinalEvidenceAppendix("));
        assertEquals(1, countOccurrences(source, "out = appendFinalEvidenceOnce("));
    }

    @Test
    void preFinalGuardDraftCannotBypassFinalMemoryPayload() throws Exception {
        String source = read("main/java/com/example/lms/service/ChatWorkflow.java");
        assertFalse(source.contains("memorySvc.reinforceFromGuardDecision("));

        int noEvidenceTemplate = source.indexOf("EvidenceAwareGuard.looksNoEvidenceTemplate(verified)");
        int fallbackFlag = source.indexOf("finalAnswerFallbackApplied = true;", noEvidenceTemplate);
        int fallbackAnswer = source.indexOf("verified = guard.degradeToEvidenceList(evidenceDocs);", noEvidenceTemplate);
        assertTrue(noEvidenceTemplate >= 0);
        assertTrue(noEvidenceTemplate < fallbackFlag);
        assertTrue(fallbackFlag < fallbackAnswer);
    }

    @Test
    void verifierFailSoftSignalsDenyMemoryWithoutInventingAnOutcome() throws Exception {
        String source = read("main/java/com/example/lms/service/ChatWorkflow.java");
        assertTrue(source.contains("FactVerifierService.DetailedVerificationResult verification = verifier.verifyDetailed("));
        assertTrue(source.contains("finalVerificationOutcomeKnown = verification.outcomeKnown();"));
        assertTrue(source.contains("finalVerificationAcceptedForMemory = verification.acceptedForMemory();"));
        assertTrue(source.contains("finalVerificationStatus = verification.status();"));
        assertFalse(source.contains("finalVerificationOutcomeKnown = verifyAnswer\n"));
        assertFalse(source.contains("TraceStore.get(\"claimVerifier.judge.disabledReason\") == null"));
    }

    @Test
    void finalVerificationReleaseGateConsumesCanonicalSignalsAndEmitsBoundedTelemetry() throws Exception {
        String source = read("main/java/com/example/lms/service/ChatWorkflow.java");

        assertEquals(1, countOccurrences(source,
                "FinalVerificationReleaseDecision releaseDecision = applyFinalVerificationReleaseGate("));
        assertTrue(source.contains("out,\n                verifyAnswer,\n                finalVerificationStatus,"));
        assertTrue(source.contains("finalVerificationOutcomeKnown,\n                finalVerificationAcceptedForMemory"));
        assertTrue(source.contains("TraceStore.put(\"finalAnswer.releaseStatus\", releaseDecision.releaseStatus());"));
        assertTrue(source.contains("TraceStore.put(\"finalAnswer.releaseReason\", releaseDecision.reasonCode());"));
        assertTrue(source.contains("TraceStore.put(\"finalAnswer.releaseAllowed\", releaseDecision.releaseAllowed());"));
        assertFalse(source.contains("TraceStore.put(\"finalAnswer.releaseContent\""));
    }

    @Test
    void fallbackAspectHasNoPostReturnSemanticRecoveryCall() throws Exception {
        String source = read("main/java/ai/abandonware/nova/orch/aop/FallbackBannerAspect.java");
        int around = source.indexOf("public Object aroundContinueChat(");
        int recoveryDefinition = source.indexOf("private ChatResult tryAuxRecovery(", around);
        String postReturnPath = source.substring(around, recoveryDefinition);

        assertEquals(1, countOccurrences(source, "tryAuxRecovery("),
                "the legacy helper may remain temporarily but must have no caller");
        assertFalse(postReturnPath.contains("tryAuxRecovery("));
        assertFalse(postReturnPath.contains("ChatResult recovered"));
        assertFalse(postReturnPath.contains("ChatResult.of(patched"));
        assertFalse(postReturnPath.contains("recordBannerPrepended("));
        assertTrue(postReturnPath.contains("TraceStore.putIfAbsent(\"answer.mode\", \"FALLBACK_EVIDENCE\")"));
    }

    @Test
    void outerOutputGuardSkipsOnlyAuthenticatedCanonicalPostprocess() throws Exception {
        String source = read("main/java/ai/abandonware/nova/orch/aop/CleanOutputRedactionAspect.java");
        int around = source.indexOf("public Object redactUserVisibleOutput(");
        int canonicalSkip = source.indexOf("canonicalFinalizationObserved(cr)", around);
        int postprocess = source.indexOf("finalAnswerPostProcessor.process(", around);

        assertTrue(canonicalSkip >= 0, "canonical finalization gate missing");
        assertTrue(canonicalSkip < postprocess, "outer guard must skip before a second postprocessor run");
        assertTrue(source.contains("TraceStore.get(\"finalAnswer.memorySaveAllowed\") instanceof Boolean"));
        assertTrue(source.contains("TraceStore.get(\"finalAnswer.postprocess.reason\")"));
        assertTrue(source.contains("TraceStore.get(\"finalAnswer.postprocess.contentHash\")"));
        assertTrue(source.contains("HashUtil.sha256(result.content())"));

        String workflow = read("main/java/com/example/lms/service/ChatWorkflow.java");
        assertTrue(workflow.contains(
                "TraceStore.put(\"finalAnswer.postprocess.contentHash\", HashUtil.sha256(out));"));
    }

    @Test
    void brainStateCaptureRequiresFinalMemoryDecision() throws Exception {
        String source = read("main/java/com/example/lms/service/rag/graph/BrainStateChatWorkflowAspect.java");
        int proceed = source.indexOf("Object result = pjp.proceed();");
        int decision = source.indexOf(
                "Boolean.TRUE.equals(TraceStore.get(\"finalAnswer.memorySaveAllowed\"))",
                proceed);
        int capture = source.indexOf(
                "captureExecutor.execute(ContextPropagation.wrap(",
                proceed);

        assertTrue(proceed >= 0);
        assertTrue(proceed < decision);
        assertTrue(decision < capture);
    }

    @Test
    void postprocessPackageHasNoRandomTimeOrModelCalls() throws Exception {
        Path root = Path.of("main/java/com/example/lms/service/postprocess");
        List<String> forbidden = List.of(
                "new Random(",
                "ThreadLocalRandom",
                "Math.random(",
                "Collections.shuffle(",
                "UUID.randomUUID(",
                "System.currentTimeMillis(",
                "System.nanoTime(",
                "ChatModel",
                ".chat(",
                "ModelRouter",
                "callWithRetry(");

        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String token : forbidden) {
                    assertFalse(source.contains(token), path + " contains nondeterministic token " + token);
                }
            }
        }
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
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

    private static boolean appearsInsideImmediateBlock(String source, int blockAnchor, int target) {
        if (blockAnchor < 0 || target < 0) {
            return false;
        }
        int openingBrace = source.indexOf('{', blockAnchor);
        int closingBrace = openingBrace < 0 ? -1 : source.indexOf('}', openingBrace + 1);
        return openingBrace >= 0 && openingBrace < target && target < closingBrace;
    }
}
