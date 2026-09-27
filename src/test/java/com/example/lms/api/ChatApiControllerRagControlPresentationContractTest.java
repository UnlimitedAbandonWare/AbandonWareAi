package com.example.lms.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatApiControllerRagControlPresentationContractTest {

    @Test
    void syncAndStreamPersistOnlyBoundaryApprovedContentAndHoldPromotions() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        int streamSemantic = source.indexOf("String semanticFinalText = result.content();");
        int streamProjection = source.indexOf("streamRagControlProjection = projectRagControlForUser(", streamSemantic);
        int streamProjectionArgs = source.indexOf(
                "semanticFinalText, __workflowUseWebForCall || __workflowUseRag", streamProjection);
        int streamPersistable = source.indexOf(
                "String persistableFinalText = streamRagControlProjection.persistableAnswer();", streamProjection);
        int streamPersist = source.indexOf(
                "persistenceSessionId, \"assistant\", persistableFinalText", streamPersistable);
        int streamHoldGate = source.indexOf("if (!streamRagControlProjection.held())", streamPersist);
        int syncSemantic = source.indexOf("String semanticFinalContent = result.content();");
        int syncProjection = source.indexOf("syncRagControlProjection = projectRagControlForUser(", syncSemantic);
        int syncProjectionArgs = source.indexOf(
                "semanticFinalContent, __workflowUseWebForCall || __workflowUseRag", syncProjection);
        int syncPersistable = source.indexOf(
                "String persistableFinalContent = syncRagControlProjection.persistableAnswer();", syncProjection);
        int syncPersist = source.indexOf(
                "completedSession.getId(), \"assistant\", persistableFinalContent", syncPersistable);
        int syncHoldGate = source.indexOf("if (!syncRagControlProjection.held())", syncPersist);
        int syncResponse = source.indexOf(
                "new ChatResponseDto(visibleFinalContent,", syncPersist);

        assertTrue(streamSemantic > 0, "stream must name the semantic answer explicitly");
        assertTrue(streamProjection > streamSemantic, "stream projection belongs after semantic shaping");
        assertTrue(streamProjectionArgs > streamProjection, "stream projection must use semantic content and RAG intent");
        assertTrue(streamPersistable > streamProjection, "stream boundary must expose a distinct persistable answer");
        assertTrue(streamPersist > streamPersistable, "stream transcript must persist only boundary-approved content");
        assertTrue(streamHoldGate > streamPersist, "stream HOLD must gate summary and model-success promotion");
        assertTrue(syncSemantic > 0, "sync must name the semantic answer explicitly");
        assertTrue(syncProjection > syncSemantic, "sync projection belongs after semantic shaping");
        assertTrue(syncProjectionArgs > syncProjection, "sync projection must use semantic content and RAG intent");
        assertTrue(syncPersistable > syncProjection, "sync boundary must expose a distinct persistable answer");
        assertTrue(syncPersist > syncPersistable, "sync transcript must persist only boundary-approved content");
        assertTrue(syncHoldGate > syncPersist, "sync HOLD must gate summary and model-success promotion");
        assertTrue(syncResponse > syncPersist, "sync response must return the visible projection");
        assertTrue(source.contains("RagControlPresentationBoundary.Projection projectRagControlForUser("));
        assertTrue(source.contains("return RagControlPresentationBoundary.Projection.passThrough(semanticAnswer);"));
        assertTrue(source.contains("return RagControlPresentationBoundary.Projection.failClosed(null);"));
        assertFalse(source.contains("session.getId(), \"assistant\", semanticFinalText"));
        assertFalse(source.contains("session.getId(), \"assistant\", semanticFinalContent"));
        assertFalse(source.contains("completedSession.getId(), \"assistant\", semanticFinalText"));
        assertFalse(source.contains("completedSession.getId(), \"assistant\", semanticFinalContent"));
    }
}
