package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ChatWorkflowInteractionEvidencePolicyContractTest {

    @Test
    void workflowComputesOnceCarriesTypedDecisionAndKeepsCanonicalPromptBoundary() throws Exception {
        String source = source();

        assertTrue(source.contains("@Value(\"${interaction.evidence-neutral.mode:off}\")"));
        assertTrue(source.contains("InteractionEvidencePolicy.FeatureMode.parse(configuredMode)"));
        assertTrue(source.contains("InteractionEvidencePolicy.observeRequest(userQuery)"));
        assertTrue(source.contains("InteractionEvidencePolicy.evaluate(interactionObservation,"));
        assertTrue(source.contains("resolveInteractionPolicyDecision("));
        assertTrue(source.contains("withAdditionalFacts(context.getInteractionPolicyFacts())"));
        assertTrue(source.contains("attachmentService.observeInteractionEvidence("));
        assertTrue(source.contains("!= InteractionEvidencePolicy.FeatureMode.OFF"));
        assertTrue(source.contains("gctx.setInteractionPolicyDecision(interactionPolicyDecision);"));
        assertTrue(source.contains(".interactionPolicyDecision(interactionPolicyDecision)"));
        assertTrue(source.contains("promptBuilder.build(ctx)"));
        assertTrue(source.contains("promptBuilder.buildInstructions(ctx)"));
        assertFalse(source.contains("new StandardPromptBuilder("));
    }

    @Test
    void enforcedDefenseAndConversationFrameComposeShortcutsGuardAndMemoryWriters() throws Exception {
        String source = source();

        int composedShortCircuit = source.indexOf(
                "final boolean interactionShortCircuitAllowed = allowsConversationShortCircuit(");
        int interactionGate = source.indexOf(
                "!interactionPolicyDecision.defensive()",
                composedShortCircuit);
        int frameGate = source.indexOf("conversationFrame);", interactionGate);
        assertTrue(composedShortCircuit >= 0
                && interactionGate > composedShortCircuit
                && frameGate > interactionGate);
        assertTrue(source.contains("interactionPolicyDecision.enforceVision(requestedVisionMode)"));
        assertTrue(source.contains("interactionPolicyDecision.enforceAnswerMode(requestedAnswerMode)"));
        assertTrue(source.contains("interactionPolicyDecision.enforceGuardProfile(guardProfile)"));
        assertTrue(source.contains("evidenceAwareGuard.prepareInteractionEvidence("));
        int prePromptQuarantine = source.indexOf(
                "evidenceAwareGuard.requiresRetrievedEvidenceQuarantine(interactionPolicyDecision)");
        int promptBuild = source.indexOf("promptBuilder.build(ctx)");
        assertTrue(prePromptQuarantine >= 0 && prePromptQuarantine < promptBuild);
        assertTrue(source.contains("filterSuspectPromptContents("));
        assertTrue(source.contains("filterSuspectPromptDocuments("));
        assertTrue(source.contains("getInteractionSuspectEvidenceIds()"));
        assertTrue(source.contains("interactionPolicyDecision.suppressMemoryWrites()"));
        assertTrue(source.contains("if (finalized.memorySaveAllowed())"));
        assertTrue(source.contains("learningWriteInterceptor.ingest("));
        assertTrue(source.contains("memoryWriteInterceptor.save("));
        assertTrue(source.contains("understandAndMemorizeInterceptor.afterVerified("));
        int finalizedPersistence = source.indexOf("FinalizedMemoryPersistence.persist(");
        int reinforcedSideEffect = source.indexOf("\"memory.reinforce\"", finalizedPersistence);
        assertTrue(finalizedPersistence >= 0 && reinforcedSideEffect > finalizedPersistence);
        String reinforcedWindow = source.substring(
                reinforcedSideEffect,
                Math.min(source.length(), reinforcedSideEffect + 520));
        assertTrue(reinforcedWindow.contains("() -> reinforce("));
        assertTrue(reinforcedWindow.contains("sessionKey"));
        assertTrue(reinforcedWindow.contains("userQuery"));
        assertTrue(reinforcedWindow.contains("finalAnswerForMemory"));
    }

    @Test
    void workflowPolicyTraceHasNoRawQueryOrStableQueryFingerprintArguments() throws Exception {
        String source = source();
        int call = source.indexOf("MlaBreadcrumb.appendInteractionPolicyTransition(");

        assertTrue(call >= 0);
        String callWindow = source.substring(call, Math.min(source.length(), call + 260));
        assertTrue(callWindow.contains("interactionPolicyDecision"));
        assertFalse(callWindow.contains("userQuery"));
        assertFalse(callWindow.contains("queryHash"));
        assertFalse(callWindow.contains("queryLength"));
        assertFalse(source.contains("interactionPolicyDecision.rawQuery"));
        assertFalse(source.contains("interactionPolicyDecision.matchedText"));
    }

    private static String source() throws Exception {
        return Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
    }
}
