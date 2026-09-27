package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowRetrievalOffDirectModeSourceTest {

    @Test
    void chatWorkflowPropagatesRetrievalOffAndSkipsEvidenceWeakDraftRewrite() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains(".ragEnabled(useWeb || useRag)"), source);
        int weakGuardIndex = source.indexOf(
                "EvidenceAwareGuard.looksWeak(out) && (useWeb || useRag)");
        assertTrue(weakGuardIndex >= 0, source);
        int retrievalOffBranch = source.indexOf(
                "} else if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(out))",
                weakGuardIndex);
        int skipTrace = source.indexOf(
                "TraceStore.put(\"answer.guardRecovery.skipped\", \"retrieval_off_direct\")",
                retrievalOffBranch);
        assertTrue(retrievalOffBranch > weakGuardIndex, source);
        assertTrue(skipTrace > retrievalOffBranch, source);
    }

    @Test
    void retrievalOffDirectModeSkipsDisambiguationLlmBeforeRouting() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int flag = source.indexOf("final boolean directRetrievalOffMode =");
        int disambiguation = source.indexOf("DisambiguationResult dr;");
        int skipBranch = source.indexOf("if (directRetrievalOffMode)", disambiguation);
        int clarify = source.indexOf("disambiguationService.clarify(userQuery, recentHistory)", disambiguation);

        assertTrue(flag > 0 && flag < disambiguation,
                "OFF/OFF direct mode should be computed before the disambiguation branch");
        assertTrue(skipBranch > disambiguation && skipBranch < clarify,
                "OFF/OFF direct mode should skip the disambiguation LLM before clarify(...) can run");
        assertTrue(source.contains("TraceStore.put(\"chat.disambiguation.skipped\", true)"),
                "the skip should leave a stable cost-reduction breadcrumb");
        assertTrue(source.contains("TraceStore.put(\"chat.disambiguation.skipReason\", \"retrieval_off_direct\")"),
                "the skip reason should be stable and source-local");
    }

    @Test
    void retrievalOffDirectModeSkipsIntentInferenceQueryTransformer() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int flag = source.indexOf("final boolean directRetrievalOffMode =");
        int intentDeclaration = source.indexOf("String intent;");
        int skipBranch = source.indexOf("if (directRetrievalOffMode)", intentDeclaration);
        int inferIntent = source.indexOf("intent = inferIntent(finalQuery)", intentDeclaration);

        assertTrue(flag > 0 && flag < intentDeclaration,
                "OFF/OFF direct mode should be computed before intent inference");
        assertTrue(skipBranch > intentDeclaration && skipBranch < inferIntent,
                "OFF/OFF direct mode should skip QueryTransformer-backed intent inference");
        assertTrue(source.contains("TraceStore.put(\"queryTransformer.reason\", \"retrieval_off_direct\")"),
                "the QueryTransformer bypass reason should be stable and source-local");
        assertTrue(source.contains("TraceStore.put(\"chat.intentInference.skipReason\", \"retrieval_off_direct\")"),
                "the intent skip should leave a stable cost-reduction breadcrumb");
    }

    @Test
    void ordinaryDirectChatDoesNotInjectAgentDebugHeartbeatAsPromptEvidence() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int relevanceGate = source.indexOf(
                "boolean agentDebugPromptRelevant = AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(");
        int conditionalBuild = source.indexOf(
                "AgentVisibleDebugEvidenceBuilder.buildSnapshot(agentDebugQuery, debugAiMetricsService)",
                relevanceGate);
        int documents = source.indexOf("agentDebugSnapshot.documents()", conditionalBuild);
        int mergeGate = source.indexOf(
                "if (agentDebugPromptRelevant && agentDebugDocs != null && !agentDebugDocs.isEmpty())",
                relevanceGate);
        int merge = source.indexOf("mergedLocalDocs.addAll(agentDebugDocs)", mergeGate);

        assertTrue(relevanceGate > 0,
                "agent debug evidence generation should remain observable but prompt injection needs a relevance gate");
        assertTrue(conditionalBuild > relevanceGate,
                "unrelated direct chat should skip heartbeat file reads and trace side effects entirely");
        assertTrue(documents > conditionalBuild && mergeGate > documents && merge > mergeGate,
                "ordinary direct chat must not receive the agent debug heartbeat as user-facing prompt evidence");
        assertTrue(source.contains("TraceStore.put(\"prompt.agentDebugEvidence.injected\", agentDebugPromptRelevant)"),
                "the prompt injection decision should leave a count-free trace breadcrumb");
    }
}
