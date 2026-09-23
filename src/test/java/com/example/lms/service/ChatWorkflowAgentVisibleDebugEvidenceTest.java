package com.example.lms.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowAgentVisibleDebugEvidenceTest {

    @Test
    void typedDebugFallbackDoesNotReparseOrTrustSerializedHeartbeatLines() throws Exception {
        AgentVisibleDebugEvidenceBuilder.Snapshot snapshot = new AgentVisibleDebugEvidenceBuilder.Snapshot(
                "AGENT_VISIBLE_DEBUG_HEARTBEAT\nsummary=corrupted-serialized-summary\n",
                Map.of(
                        AgentVisibleDebugEvidenceBuilder.Field.SUMMARY, "typed-summary",
                        AgentVisibleDebugEvidenceBuilder.Field.BROWSER_STATUS, "OK",
                        AgentVisibleDebugEvidenceBuilder.Field.BROWSER_EVIDENCE_NEEDED, "none",
                        AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_STATUS, "OK",
                        AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_EVIDENCE_NEEDED, "none",
                        AgentVisibleDebugEvidenceBuilder.Field.SUPABASE_EVIDENCE_NEEDED, "project_scope_missing",
                        AgentVisibleDebugEvidenceBuilder.Field.MATRIX_COUNT, "7",
                        AgentVisibleDebugEvidenceBuilder.Field.MATRIX_CHUNKS, "2",
                        AgentVisibleDebugEvidenceBuilder.Field.MATRIX_DECISION, "observe"));
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "composeAgentVisibleDebugFallback",
                String.class,
                AgentVisibleDebugEvidenceBuilder.Snapshot.class);
        method.setAccessible(true);

        String answer = (String) method.invoke(null, "show current debug status", snapshot);

        assertNotNull(answer);
        assertTrue(answer.contains("typed-summary"));
        assertFalse(answer.contains("corrupted-serialized-summary"));
        assertTrue(answer.contains("Browser proof: OK"));
        assertTrue(answer.contains("Computer proof: OK"));
        assertTrue(answer.contains("Supabase: project_scope_missing"));
    }

    @Test
    void typedAndLegacyDebugFallbackRemainOutputCompatible() throws Exception {
        AgentVisibleDebugEvidenceBuilder.Snapshot snapshot =
                AgentVisibleDebugEvidenceBuilder.buildSnapshotForTest(
                        "show current debug status",
                        Map.of(
                                "virtualMatrixCount", 7,
                                "virtualMatrixChunkCount", 2,
                                "virtualMatrixDecision", "observe"),
                        Map.of("status", "OK", "evidenceNeeded", "none", "stale", false),
                        Map.of("status", "OK", "evidenceNeeded", "none", "stale", false, "countOnly", true),
                        Map.of("status", "WARN", "evidenceNeeded", "project_scope_missing"));
        Method typed = ChatWorkflow.class.getDeclaredMethod(
                "composeAgentVisibleDebugFallback",
                String.class,
                AgentVisibleDebugEvidenceBuilder.Snapshot.class);
        Method legacy = ChatWorkflow.class.getDeclaredMethod(
                "composeAgentVisibleDebugFallback",
                String.class,
                List.class);
        typed.setAccessible(true);
        legacy.setAccessible(true);

        assertEquals(
                typed.invoke(null, "show current debug status", snapshot),
                legacy.invoke(null, "show current debug status", snapshot.documents()));
    }

    @Test
    void verifierEligibilityUsesMemoryOnlyForActualFollowUp() throws ReflectiveOperationException {
        Method method = assertDoesNotThrow(() -> ChatWorkflow.class.getDeclaredMethod(
                "verifierEligibilityContext", String.class, String.class, boolean.class));
        method.setAccessible(true);
        String memory = "persisted memory evidence that is long enough for the verifier contract";

        assertEquals("", method.invoke(null, "", memory, false));
        assertEquals(memory, method.invoke(null, "", memory, true));
        assertEquals("[WEB]\nofficial evidence", method.invoke(
                null, "[WEB]\nofficial evidence", memory, false));
    }

    @Test
    void finalVerifierUsesRetrievedEvidenceInsteadOfRenderedPromptAsContext() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("String verifierEvidenceContext = buildVerifierEvidenceContext("));
        assertTrue(source.contains("promptWebDocs, promptVectorDocs, promptLocalDocs);"));
        assertTrue(source.contains("String verifierMemoryContext = verifierFollowUp ? memoryCtx : \"\";"));
        assertTrue(source.contains("boolean verifyAnswer = shouldVerify(verifierEligibilityEvidence, llmReq, sig);"));
        assertTrue(source.contains("/* context */ verifierEvidenceContext,"));
        assertTrue(source.contains("/* memory */ verifierMemoryContext,"));
        assertFalse(source.contains("boolean verifyAnswer = shouldVerify(unifiedCtx, llmReq, sig);"));
        assertFalse(source.contains("/* context */ unifiedCtx,"));
        assertFalse(source.contains("/* memory */ memoryCtx,"));
    }

    @Test
    void verifierEvidenceContextIsEmptyWithoutRetrievedOrAttachedEvidence() {
        assertEquals("", ChatWorkflow.buildVerifierEvidenceContext(List.of(), List.of(), List.of()));

        String evidence = ChatWorkflow.buildVerifierEvidenceContext(
                List.of(Content.from("official route evidence")),
                List.of(Content.from("vector route evidence")),
                List.of(Document.from("attached route evidence")));

        assertTrue(evidence.contains("official route evidence"));
        assertTrue(evidence.contains("vector route evidence"));
        assertTrue(evidence.contains("attached route evidence"));
    }

    @Test
    void chatWorkflowPromotesAgentVisibleDebugEvidenceIntoPromptAndFallback() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("AgentVisibleDebugEvidenceBuilder.buildSnapshot("),
                "ChatWorkflow should build typed agent-visible debug evidence after TraceStore.clear()");
        assertTrue(source.contains("agentDebugSnapshot.documents()"),
                "PromptContext compatibility documents should derive from the typed snapshot");
        assertTrue(source.contains("(userQuery != null && !userQuery.isBlank()) ? userQuery : finalQuery"),
                "agent-visible debug evidence should key off the original user question before query rewriting");
        assertTrue(source.contains("ctxBuilder.localDocs(promptLocalDocs);"),
                "agent debug evidence must flow through PromptContext.localDocs");
        assertTrue(source.contains("composeEvidenceFallback(finalQuery, promptWebDocs, promptVectorDocs, promptLocalDocs"),
                "fallback evidence composer should see filtered web/vector and local debug docs when LLM path degrades");
        assertTrue(source.contains("NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModel, useRag, promptWebDocs, promptVectorDocs, promptLocalDocs"),
                "fallback selector should count local debug docs without reintroducing quarantined retrieval results");
        assertTrue(source.contains("AgentVisibleDebugEvidenceBuilder.fromDocuments(localDocs)"),
                "legacy local-doc callers should cross one compatibility parser boundary");
        assertTrue(source.contains("local://agent-visible-debug/1"),
                "agent-visible debug heartbeat needs a stable local evidence locator");
        assertTrue(source.contains("Field.BROWSER_EVIDENCE_NEEDED"),
                "direct debug fallback should consume typed browser evidence_needed");
        assertTrue(source.contains("Field.COMPUTER_EVIDENCE_NEEDED"),
                "direct debug fallback should consume typed computer-use evidence_needed");
        assertTrue(source.contains("chat.agentDebugEvidence.directAnswer"),
                "debug/status questions should get a deterministic answer after PromptBuilder trace is recorded");
        assertTrue(source.contains("agent-debug:fallback:evidence"),
                "direct debug evidence answers should be labeled as evidence fallback");
        assertTrue(source.contains("composeAgentVisibleDebugFallback(userQuery, agentDebugSnapshot)"),
                "direct debug evidence answers should use the freshly-built typed snapshot");
        assertTrue(source.contains("chat.agentDebugEvidence.shortCircuitBefore"),
                "direct debug evidence answers should record where the early cost-saving short-circuit happened");
    }

    @Test
    void directDebugEvidenceShortCircuitsBeforeDisambiguationAndRetrieval() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int shortCircuit = source.indexOf("chat.agentDebugEvidence.shortCircuitBefore");
        int disambiguation = source.indexOf("DisambiguationResult dr;");
        int retrieval = source.indexOf("hybridRetriever.retrieveAll(");

        assertTrue(shortCircuit > 0, "direct debug short-circuit trace marker should exist");
        assertTrue(disambiguation > shortCircuit,
                "direct debug/status questions should skip disambiguation before expensive routing");
        assertTrue(retrieval > shortCircuit,
                "direct debug/status questions should skip web/vector retrieval before answering");
    }

    @Test
    void supabaseOperationalProbeShortCircuitsBeforeDisambiguationAndRetrieval() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        String query = "Random RAG/web search probe: feature pool marker=open, login/logout disabled, "
                + "Supabase project_ref/auth missing. Answer with [local] 3 allowed features "
                + "and [external] 3 evidence_needed features only.";
        assertTrue(ChatWorkflow.isSupabaseOperationalJudgmentRequest(query));

        String browserProbeQuery = "Random local feature-pool probe: feature pool marker=open, login/logout disabled, "
                + "ops nav Brain/Pipeline/RAG Ops/Vector/Models clickability, Supabase project_ref/auth missing. "
                + "[local] 4 and [external] evidence_needed 3 only.";
        assertTrue(ChatWorkflow.isSupabaseOperationalJudgmentRequest(browserProbeQuery),
                "browser-discovered local/external feature-pool probes should take the cheap local evidence path");

        int predicate = source.indexOf("isSupabaseOperationalJudgmentRequest(userQuery)");
        int shortCircuit = source.indexOf("chat.agentDebugEvidence.supabaseOperationalShortCircuit", predicate);
        int disambiguation = source.indexOf("DisambiguationResult dr;");
        int retrieval = source.indexOf("hybridRetriever.retrieveAll(");

        assertTrue(predicate > 0, "Supabase operational probes should have an early local predicate");
        assertTrue(shortCircuit > predicate, "Supabase operational probes should record their early short-circuit");
        assertTrue(disambiguation > shortCircuit,
                "Supabase operational probes should skip disambiguation before expensive routing");
        assertTrue(retrieval > shortCircuit,
                "Supabase operational probes should skip web/vector retrieval before answering");
        assertTrue(source.contains("ChatResult.of(earlySupabaseOperationalAnswer, \"agent-debug:supabase-operational:evidence\", false)"),
                "Supabase operational probes should return a deterministic local ChatResult without model/RAG usage");
    }

    @Test
    void supabaseOperationalPredicateDoesNotCaptureOfficialSourceComparisonRequest() {
        String query = "Official source constrained RAG probe: compare OpenAI Responses API web_search tool "
                + "and Supabase Vector pgvector docs. Allowed sources are developers.openai.com, "
                + "platform.openai.com, and supabase.com official docs only. Exclude Reddit, blogs, "
                + "forums, and general articles. End with a Sources section.";

        assertFalse(ChatWorkflow.isSupabaseOperationalJudgmentRequest(query),
                "official-source comparison requests should continue through retrieval instead of local Supabase status");
    }

    @Test
    void chatApiControllerSkipsPrefetchForDirectDebugEvidenceQueries() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        int predicate = source.indexOf("AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(dto.getMessage())");
        int firstPrefetch = source.indexOf("webSearchProvider.searchWithTrace(prefetchQuery, topKParam)");
        if (firstPrefetch < 0) {
            firstPrefetch = source.indexOf("webSearchProvider.searchWithTrace(__providerSearchQuery, topKParam)");
        }
        if (firstPrefetch < 0) {
            firstPrefetch = source.indexOf("webSearchProvider.searchWithTrace(dto.getMessage(), topKParam)");
        }

        assertTrue(predicate > 0, "stream/sync controller should reuse the direct debug predicate");
        assertTrue(firstPrefetch > predicate, "direct debug predicate should run before controller web prefetch");
        assertTrue(source.contains("__finalUseWeb = __finalUseWeb && !__directDebugAnswer;"),
                "direct debug/status questions should disable controller web prefetch");
        assertTrue(source.contains("&& !__directDebugAnswer"),
                "direct debug/status questions should disable controller vector/RAG prefetch");
        assertTrue(source.contains("ChatWorkflow.isCurrentModeStatusRequest(dto.getMessage())"),
                "current UI mode status questions should reuse the same direct local predicate");
        assertTrue(source.contains("__finalUseWeb = __finalUseWeb && !__directUiModeStatusAnswer;"),
                "current UI mode status questions should disable controller web prefetch");
        assertTrue(source.contains("&& !__directUiModeStatusAnswer"),
                "current UI mode status questions should disable controller vector/RAG prefetch");
        assertTrue(source.contains("__workflowUseWeb = __directUiModeStatusAnswer ? __reqUseWeb : __finalUseWeb;"),
                "current UI mode status answers should preserve requested web-search state in the workflow DTO");
        assertTrue(source.contains("__workflowUseRag = __directUiModeStatusAnswer ? Boolean.TRUE.equals(dto.isUseRag()) : __finalUseRag;"),
                "current UI mode status answers should preserve requested RAG state in the workflow DTO");
        assertTrue(source.contains("ChatWorkflow.isDirectLiteralAnswerRequest(dto.getMessage())"),
                "direct literal/code-only questions should reuse the workflow local-answer predicate");
        assertTrue(source.contains("__finalUseWeb = __finalUseWeb && !__directLiteralAnswer;"),
                "direct literal/code-only questions should disable controller web prefetch");
        assertTrue(source.contains("&& !__directLiteralAnswer"),
                "direct literal/code-only questions should disable controller vector/RAG prefetch");
        assertTrue(source.contains("ChatWorkflow.isSupabaseOperationalJudgmentRequest(dto.getMessage())"),
                "Supabase operational evidence probes should reuse the workflow local-answer predicate");
        assertTrue(source.contains("__finalUseWeb = __finalUseWeb && !__directSupabaseOperationalJudgment;"),
                "Supabase operational evidence probes should disable controller web prefetch");
        assertTrue(source.contains("&& !__directSupabaseOperationalJudgment"),
                "Supabase operational evidence probes should disable controller vector/RAG prefetch");
    }

    @Test
    void agentVisibleDebugFallbackMarksBrowserAndComputerProofAsOptionalWhenStale() throws Exception {
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:SUPPORTING_EVIDENCE_MISSING computer-use:SUPPORTING_EVIDENCE_MISSING supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=SUPPORTING_EVIDENCE_MISSING
                external.browser.evidenceNeeded=browser_ui_smoke_stale
                external.browser.stale=true
                external.browser.blocking=false
                external.browser.evidenceScope=local-ui-proof
                external.computer-use.status=SUPPORTING_EVIDENCE_MISSING
                external.computer-use.evidenceNeeded=computer_use_smoke_stale
                external.computer-use.stale=true
                external.computer-use.blocking=false
                external.computer-use.evidenceScope=gui-supporting-only
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;

        String answer = invokeAgentDebugFallback(List.of(Document.from(heartbeat)));

        assertTrue(answer.contains("Browser proof: SUPPORTING_EVIDENCE_MISSING"));
        assertTrue(answer.contains("Computer proof: SUPPORTING_EVIDENCE_MISSING"));
        assertTrue(answer.contains("optional"));
        assertTrue(answer.contains("not blocking Desktop chat"));
        assertTrue(answer.contains("browser_ui_smoke_stale"));
        assertTrue(answer.contains("computer_use_smoke_stale"));
    }

    @Test
    void agentVisibleDebugFallbackHonorsExternalOnlyThreeLineRequest() throws Exception {
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:OK computer-use:OK supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=OK
                external.browser.evidenceNeeded=none
                external.browser.stale=false
                external.browser.blocking=false
                external.browser.evidenceScope=local-ui-proof
                external.computer-use.status=OK
                external.computer-use.evidenceNeeded=none
                external.computer-use.stale=false
                external.computer-use.blocking=false
                external.computer-use.evidenceScope=gui-supporting-only
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                agentDbContext.status=DISABLED
                agentDbContext.reason=agent_db_context_disabled
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;

        String answer = invokeAgentDebugFallback(
                "\uD604\uC7AC \uB514\uBC84\uADF8 \uC99D\uAC70 \uC0C1\uD0DC\uB97C 3\uC904\uB85C \uC694\uC57D\uD574\uC918. "
                        + "Browser, Computer, Supabase \uC0C1\uD0DC\uB9CC \uB9D0\uD558\uACE0 "
                        + "Supabase\uB294 \uC2E4\uC81C DB \uAC80\uC99D \uC644\uB8CC\uB77C\uACE0 \uB9D0\uD558\uC9C0 \uB9C8.",
                List.of(Document.from(heartbeat)));

        List<String> lines = answer.lines()
                .filter(line -> !line.isBlank())
                .toList();

        assertEquals(3, lines.size(), answer);
        assertTrue(lines.get(0).contains("Browser: OK"));
        assertTrue(lines.get(0).contains("stale=false"));
        assertTrue(lines.get(1).contains("Computer: OK"));
        assertTrue(lines.get(1).contains("count-only"));
        assertTrue(lines.get(2).contains("Supabase: evidence_needed"));
        assertTrue(lines.get(2).contains("supabase_project_scope_or_auth_unverified"));
        assertFalse(answer.contains("DB context"));
        assertFalse(answer.contains("Debug matrix"));
        assertFalse(answer.contains("FALLBACK_EVIDENCE"));
        assertFalse(answer.contains("local://agent-visible-debug"));
        assertFalse(answer.contains("\uAC80\uC99D \uC644\uB8CC"));
    }

    @Test
    void agentVisibleDebugFallbackHonorsExternalOnlyOneParagraphRequest() throws Exception {
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:OK computer-use:OK supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=OK
                external.browser.evidenceNeeded=none
                external.browser.stale=false
                external.browser.blocking=false
                external.browser.evidenceScope=local-ui-proof
                external.computer-use.status=OK
                external.computer-use.evidenceNeeded=none
                external.computer-use.stale=false
                external.computer-use.blocking=false
                external.computer-use.evidenceScope=gui-supporting-only
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                agentDbContext.status=DISABLED
                agentDbContext.reason=agent_db_context_disabled
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;

        String answer = invokeAgentDebugFallback(
                "\uB85C\uCEEC \uC804\uC6A9\uC73C\uB85C \uD55C \uBB38\uB2E8\uB9CC \uB2F5\uD574\uC918. "
                        + "Browser/Computer/Supabase \uC99D\uAC70 \uC0C1\uD0DC\uB97C \uACFC\uC7A5\uD558\uC9C0 \uB9D0\uACE0, "
                        + "Supabase DB \uAC80\uC99D\uC740 \uD558\uC9C0 \uC54A\uC558\uB2E4\uACE0 \uB9D0\uD574\uC918.",
                List.of(Document.from(heartbeat)));

        assertNotNull(answer);
        assertTrue(answer.contains("Browser: OK"), answer);
        assertTrue(answer.contains("Computer: OK"), answer);
        assertTrue(answer.contains("Supabase: evidence_needed"), answer);
        assertTrue(answer.contains("DB verification not claimed"), answer);
        assertFalse(answer.contains("DB context"), answer);
        assertFalse(answer.contains("Debug matrix"), answer);
        assertFalse(answer.contains("local://agent-visible-debug"), answer);
    }

    @Test
    void agentVisibleDebugFallbackAnswersSupabaseOperationalJudgmentRequest() throws Exception {
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:SUPPORTING_EVIDENCE_MISSING computer-use:SUPPORTING_EVIDENCE_MISSING supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=SUPPORTING_EVIDENCE_MISSING
                external.browser.evidenceNeeded=browser_ui_smoke_stale
                external.browser.stale=true
                external.browser.blocking=false
                external.browser.evidenceScope=local-ui-proof
                external.computer-use.status=SUPPORTING_EVIDENCE_MISSING
                external.computer-use.evidenceNeeded=computer_use_smoke_stale
                external.computer-use.stale=true
                external.computer-use.blocking=false
                external.computer-use.evidenceScope=gui-supporting-only
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                agentDbContext.status=DISABLED
                agentDbContext.reason=agent_db_context_disabled
                agentDbContext.nextAction=enable_agent_db_context_for_full_pipeline_health
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;

        String answer = invokeAgentDebugFallback(
                "RAG/web search probe: Supabase MCP read_only is reachable, but project_ref and auth are missing. "
                        + "List 5 deferred features and 3 allowed features with evidence and debug status.",
                List.of(Document.from(heartbeat)));

        assertTrue(answer.contains("Deferred:"), answer);
        assertTrue(answer.contains("Allowed:"), answer);
        assertTrue(answer.contains("live Supabase schema/advisor reads"), answer);
        assertTrue(answer.contains("Browser/Computer local UI proof refresh"), answer);
        assertTrue(answer.contains("supabase_project_scope_or_auth_unverified"), answer);
        assertTrue(answer.contains("local://agent-visible-debug/1"), answer);
        assertFalse(answer.contains("DB verification complete"), answer);
    }

    @Test
    void agentVisibleDebugFallbackAnswersLocalExternalMarkerProbeWithoutModelWait() throws Exception {
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:OK computer-use:OK supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=OK
                external.browser.evidenceNeeded=none
                external.browser.stale=false
                external.browser.blocking=false
                external.browser.evidenceScope=local-ui-proof
                external.computer-use.status=OK
                external.computer-use.evidenceNeeded=none
                external.computer-use.stale=false
                external.computer-use.blocking=false
                external.computer-use.evidenceScope=gui-supporting-only
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                agentDbContext.status=DISABLED
                agentDbContext.reason=agent_db_context_disabled
                agentDbContext.nextAction=enable_agent_db_context_for_full_pipeline_health
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;

        String answer = invokeAgentDebugFallback(
                "Random RAG/web search probe: feature pool marker=open, login/logout disabled, "
                        + "Supabase project_ref/auth missing. Answer with [local] 3 allowed features "
                        + "and [external] 3 evidence_needed features only.",
                List.of(Document.from(heartbeat)));

        assertNotNull(answer);
        assertTrue(answer.contains("[local]"), answer);
        assertTrue(answer.contains("[external]"), answer);
        assertTrue(answer.contains("feature pool"), answer);
        assertTrue(answer.contains("login/logout disabled"), answer);
        assertTrue(answer.contains("Browser localhost proof"), answer);
        assertTrue(answer.contains("Supabase"), answer);
        assertTrue(answer.contains("evidence_needed"), answer);
        assertTrue(answer.contains("project_ref"), answer);
        assertTrue(answer.contains("local://agent-visible-debug/1"), answer);
        assertFalse(answer.contains("DB verification complete"), answer);
    }

    @Test
    void localExternalFeaturePoolProbeReportsOpsNavAsLocalTestModeEvidence() throws Exception {
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:SUPPORTING_EVIDENCE_MISSING computer-use:SUPPORTING_EVIDENCE_MISSING supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=SUPPORTING_EVIDENCE_MISSING
                external.browser.evidenceNeeded=browser_ui_smoke_stale
                external.browser.stale=true
                external.computer.status=SUPPORTING_EVIDENCE_MISSING
                external.computer.evidenceNeeded=computer_use_smoke_stale
                external.computer.stale=true
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                agentDbContext.status=DISABLED
                agentDbContext.reason=agent_db_context_disabled
                agentDbContext.nextAction=enable_agent_db_context_for_full_pipeline_health
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;

        String answer = invokeAgentDebugFallback(
                "Random local feature-pool probe: feature pool marker=open, login/logout disabled, "
                        + "ops nav Brain/Pipeline/RAG Ops/Vector/Models clickability, Supabase project_ref/auth missing. "
                        + "[local] 4 and [external] evidence_needed 3 only.",
                List.of(Document.from(heartbeat)));

        assertNotNull(answer);
        assertTrue(answer.contains("[local]"), answer);
        assertTrue(answer.contains("[external]"), answer);
        assertTrue(answer.contains("feature pool"), answer);
        assertTrue(answer.contains("login/logout disabled"), answer);
        assertTrue(answer.contains("ops nav"), answer);
        assertTrue(answer.contains("Brain/Pipeline/RAG Ops/Vector/Models"), answer);
        assertTrue(answer.contains("auth disabled test mode"), answer);
        assertTrue(answer.contains("evidence_needed"), answer);
        assertTrue(answer.contains("project_ref"), answer);
        assertTrue(answer.contains("local://agent-visible-debug/1"), answer);
        assertFalse(answer.contains("DB verification complete"), answer);
    }

    @Test
    void naturalBrowserFeaturePoolProbeUsesLocalEvidenceInsteadOfWebSearch() throws Exception {
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:SUPPORTING_EVIDENCE_MISSING computer-use:SUPPORTING_EVIDENCE_MISSING supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=SUPPORTING_EVIDENCE_MISSING
                external.browser.evidenceNeeded=browser_ui_smoke_stale
                external.browser.stale=true
                external.computer.status=SUPPORTING_EVIDENCE_MISSING
                external.computer.evidenceNeeded=computer_use_smoke_stale
                external.computer.stale=true
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                agentDbContext.status=DISABLED
                agentDbContext.reason=agent_db_context_disabled
                agentDbContext.nextAction=enable_agent_db_context_for_full_pipeline_health
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;
        String query = "무작위 기능풀/인증 상태 탐침 2026-07-08: 현재 chat-ui의 feature pool이 open인지, "
                + "login/logout이 테스트 단계라 disabled인지, Browser/Computer/Supabase evidence lane이 "
                + "local/external로 분리되는지 짧게 진단해줘. 실제 로그인이나 로그아웃을 시도하지 말고, "
                + "RAG/web search 기능 풀에서 가능한 강한 기능만 추천해줘.";

        assertTrue(ChatWorkflow.isSupabaseOperationalJudgmentRequest(query));

        String answer = invokeAgentDebugFallback(query, List.of(Document.from(heartbeat)));

        assertNotNull(answer);
        assertTrue(answer.contains("[local]"), answer);
        assertTrue(answer.contains("[external]"), answer);
        assertTrue(answer.contains("feature pool"), answer);
        assertTrue(answer.contains("login/logout disabled"), answer);
        assertTrue(answer.contains("auth disabled test mode"), answer);
        assertTrue(answer.contains("Browser localhost proof"), answer);
        assertTrue(answer.contains("Computer count-only proof"), answer);
        assertTrue(answer.contains("Supabase"), answer);
        assertTrue(answer.contains("evidence_needed"), answer);
        assertTrue(answer.contains("project_ref"), answer);
        assertTrue(answer.contains("local://agent-visible-debug/1"), answer);
        assertFalse(answer.contains("[SRC:WEB]"), answer);
    }

    @Test
    void currentUiModeQuestionReturnsLocalSearchAndRagStatus() throws Exception {
        ChatRequestDto request = ChatRequestDto.builder()
                .message("\uD604\uC7AC Search\uC640 RAG \uBAA8\uB4DC\uAC00 \uCF1C\uC84C\uB294\uC9C0 \uC9E7\uAC8C \uD655\uC778\uD574\uC918.")
                .searchMode(SearchMode.FORCE_LIGHT)
                .useWebSearch(true)
                .useRag(true)
                .build();

        String answer = invokeCurrentModeStatusFallback(request.getMessage(), request);

        assertNotNull(answer);
        assertTrue(answer.contains("Search: LIGHT"), answer);
        assertFalse(answer.contains("FORCE_LIGHT"), answer);
        assertTrue(answer.contains("webSearch=true"), answer);
        assertTrue(answer.contains("RAG: ON"), answer);
        assertFalse(answer.contains("DEGRADED MODE"), answer);
        assertFalse(answer.contains("Supabase"), answer);
    }

    @Test
    void currentUiStateQuestionWithKoreanSearchAndRagOffReturnsLocalStatus() throws Exception {
        ChatRequestDto request = ChatRequestDto.builder()
                .message("\uC9C0\uAE08 UI \uC0C1\uD0DC\uC5D0\uC11C RAG\uC640 \uAC80\uC0C9\uC774 \uAEBC\uC838 \uC788\uB294\uC9C0 \uC9E7\uAC8C \uD655\uC778\uD574\uC918.")
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .build();

        assertTrue(ChatWorkflow.isCurrentModeStatusRequest(request.getMessage()));

        String answer = invokeCurrentModeStatusFallback(request.getMessage(), request);

        assertNotNull(answer);
        assertTrue(answer.contains("Search: OFF"), answer);
        assertTrue(answer.contains("webSearch=false"), answer);
        assertTrue(answer.contains("RAG: OFF"), answer);
        assertFalse(answer.contains("Supabase"), answer);
        assertFalse(answer.contains("Browser proof"), answer);
        assertFalse(answer.contains("local://agent-visible-debug"), answer);
    }

    @Test
    void currentUiStateQuestionWithKoreanPromptUsesKoreanFrame() throws Exception {
        ChatRequestDto request = ChatRequestDto.builder()
                .message("\uAC80\uC0C9\uACFC RAG \uC5C6\uC774 \uC9C0\uAE08 \uCC57\uBD07 UI \uC0C1\uD0DC\uB97C \uD55C\uAD6D\uC5B4 \uD55C \uBB38\uC7A5\uC73C\uB85C \uC9E7\uAC8C \uB9D0\uD574\uC918.")
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .build();

        String answer = invokeCurrentModeStatusFallback(request.getMessage(), request);

        assertNotNull(answer);
        assertTrue(answer.contains("\uD604\uC7AC UI \uBAA8\uB4DC \uC0C1\uD0DC"), answer);
        assertTrue(answer.contains("Search: OFF"), answer);
        assertTrue(answer.contains("RAG: OFF"), answer);
        assertTrue(answer.contains("\uC678\uBD80 \uC99D\uAC70\uB294 \uC8FC\uC7A5\uD558\uC9C0 \uC54A\uC74C"), answer);
        assertFalse(answer.contains("Current UI mode status"), answer);
    }

    @Test
    void projectFeatureInventoryQuestionDoesNotShortCircuitAsCurrentUiModeStatus() {
        String query = "Dynamic RAG Orchestration Platform\uC5D0\uC11C Plan DSL, MoE Strategy Selector, "
                + "GraphRAG/KG, CFVM \uC911 \uC2E4\uC81C \uADFC\uAC70\uAC00 \uC788\uB294 \uD56D\uBAA9\uACFC evidence_needed "
                + "\uD56D\uBAA9\uC744 source marker\uB85C \uB098\uB220\uC918. \uC678\uBD80 \uC6F9 \uAC80\uC0C9 \uACB0\uACFC\uC640 "
                + "\uD504\uB85C\uC81D\uD2B8 \uB85C\uCEEC source marker\uB97C \uAD6C\uBD84\uD574\uC918.";

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(query),
                "project feature source-marker inventory should continue through normal RAG/prompt flow");
    }

    @Test
    void retrievalQualityProbeDoesNotShortCircuitAsCurrentUiModeStatus() throws Exception {
        String query = "RAG web search quality probe: compare site:openai.com Responses API "
                + "built-in tools web_search file_search computer_use with site:supabase.com MCP "
                + "read_only project_ref setup. Confirm whether evidence domains are openai.com "
                + "or supabase.com, and report any other domain as a search quality issue.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(query)
                .searchMode(SearchMode.AUTO)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(query),
                "external evidence/domain quality probes should continue through retrieval flow");
        assertNull(invokeCurrentModeStatusFallback(query, request),
                "current UI mode fallback must not answer provider-quality probes");
    }

    @Test
    void negatedCurrentUiDebugPhraseDoesNotShortCircuitExternalEvidenceProbe() throws Exception {
        String query = "RAG random probe 2026-07-08: compare OpenAI Responses API web_search tooling "
                + "with Supabase MCP read_only project_ref setup. Use external/official evidence if available, "
                + "do not answer as current UI debug status, and include 3 next debugging actions for this app.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(query)
                .searchMode(SearchMode.FORCE_DEEP)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(query),
                "negated current-UI wording inside an external evidence probe should not trigger local UI status");
        assertNull(invokeCurrentModeStatusFallback(query, request),
                "current UI mode fallback must not answer external evidence probes that explicitly reject debug status");
    }

    @Test
    void namedOfficialSourcePromptFiltersPromptWebDocsBeforePromptBuilder() throws Exception {
        String query = "Compare OpenAI Responses API web_search tooling and Supabase MCP read_only project_ref setup "
                + "using official/external sources only.";
        List<Content> webDocs = List.of(
                content("Community unrelated result", Map.of("url", "https://github.com/onestardao/WFGY/blob/main/README.md")),
                content("OpenAI official tool orchestration", Map.of("url", "https://developers.openai.com/cookbook/examples/responses_api/responses_api_tool_orchestration")),
                content("Vendor tutorial unrelated to the requested products", Map.of("url", "https://docs.nvidia.com/vss/2.3.0/content/faq.html")),
                content("Supabase MCP read-only setup", Map.of("url", "https://supabase.com/docs/guides/ai-tools/mcp")));

        List<Content> filtered = invokeOfficialSourcePromptWebFilter(query, webDocs);

        assertEquals(2, filtered.size(),
                "official-source prompts should not send off-domain citable web docs into PromptBuilder context");
        assertTrue(contentText(filtered.get(0)).contains("OpenAI official"));
        assertTrue(contentText(filtered.get(1)).contains("Supabase MCP"));
        assertFalse(filtered.stream().map(ChatWorkflowAgentVisibleDebugEvidenceTest::contentText)
                .anyMatch(text -> text.contains("Community") || text.contains("NVIDIA")),
                "off-domain snippets must be removed before LLM prompt construction, not only from final evidence rail");
        assertEquals(true, TraceStore.get("prompt.officialSourceFilter.applied"));
        assertEquals(2, TraceStore.get("prompt.officialSourceFilter.keptCount"));
        assertEquals(2, TraceStore.get("prompt.officialSourceFilter.removedCount"));
    }

    @Test
    void koreanNamedSourceDomainPromptFiltersPromptWebDocsBeforePromptBuilder() throws Exception {
        String query = "OpenAI Responses API web_search/file_search/computer_use and Supabase MCP "
                + "read_only/project_ref setup latest changes reflected in answer evidence, "
                + "\uCD9C\uCC98 \uB3C4\uBA54\uC778 2\uAC1C\uC640 \uADFC\uAC70\uB85C \uAC80\uC99D\uD574\uC918.";
        List<Content> webDocs = List.of(
                content("Community tutorial result", Map.of("url", "https://glukhov.org/post/2025/rag-overview")),
                content("OpenAI official Responses API tools", Map.of("url", "https://developers.openai.com/api/docs/guides/tools-web-search")),
                content("Generic cloud blog", Map.of("url", "https://tech.ktcloud.com/posts/rag")),
                content("Supabase official MCP setup", Map.of("url", "https://supabase.com/docs/guides/ai-tools/mcp")));

        List<Content> filtered = invokeOfficialSourcePromptWebFilter(query, webDocs);

        assertEquals(2, filtered.size(),
                "Korean named source-domain probes should keep only official named-product docs before PromptBuilder");
        assertTrue(contentText(filtered.get(0)).contains("OpenAI official"));
        assertTrue(contentText(filtered.get(1)).contains("Supabase official"));
        assertFalse(filtered.stream().map(ChatWorkflowAgentVisibleDebugEvidenceTest::contentText)
                .anyMatch(text -> text.contains("Community") || text.contains("Generic cloud")),
                "generic snippets must not reach PromptBuilder for named source-domain probes");
        assertEquals(true, TraceStore.get("prompt.officialSourceFilter.applied"));
        assertEquals(2, TraceStore.get("prompt.officialSourceFilter.keptCount"));
        assertEquals(2, TraceStore.get("prompt.officialSourceFilter.removedCount"));
    }

    @Test
    void namedOfficialSourcePromptDropsPromptWebDocsWhenNoOfficialMatchExists() throws Exception {
        String query = "Compare OpenAI Responses API web_search tooling and Supabase MCP read_only project_ref setup "
                + "using official/external sources only.";
        List<Content> webDocs = List.of(
                content("Community unrelated result", Map.of("url", "https://github.com/onestardao/WFGY/blob/main/README.md")),
                content("Vendor tutorial unrelated to the requested products", Map.of("url", "https://docs.nvidia.com/vss/2.3.0/content/faq.html")));

        List<Content> filtered = invokeOfficialSourcePromptWebFilter(query, webDocs);

        assertTrue(filtered.isEmpty(),
                "strict official-source prompts should not pass unrelated web docs into PromptBuilder when provider rescue finds no official URL");
        assertEquals(true, TraceStore.get("prompt.officialSourceFilter.applied"));
        assertEquals(0, TraceStore.get("prompt.officialSourceFilter.keptCount"));
        assertEquals(2, TraceStore.get("prompt.officialSourceFilter.removedCount"));
    }

    @Test
    void namedOfficialSourcePromptDropsGuardEvidenceDocsWhenNoOfficialMatchExists() throws Exception {
        String query = "Compare OpenAI Responses API web_search tooling and Supabase MCP read_only project_ref setup "
                + "using official/external sources only.";
        List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://github.com/onestardao/WFGY/blob/main/README.md",
                        "Community mirror",
                        "Community unrelated result"),
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://docs.nvidia.com/vss/2.3.0/content/faq.html",
                        "Vendor tutorial",
                        "Vendor tutorial unrelated to the requested products"));

        List<EvidenceAwareGuard.EvidenceDoc> filtered = invokeOfficialSourceEvidenceDocFilter(query, evidenceDocs);

        assertTrue(filtered.isEmpty(),
                "strict official-source prompts should not let EvidenceAwareGuard render off-domain degrade-to-evidence snippets");
        assertEquals(true, TraceStore.get("prompt.officialSourceEvidenceFilter.applied"));
        assertEquals(0, TraceStore.get("prompt.officialSourceEvidenceFilter.keptCount"));
        assertEquals(2, TraceStore.get("prompt.officialSourceEvidenceFilter.removedCount"));
    }

    @Test
    void koreanOfficialChangelogPromptFiltersVisibleEvidenceMetadataForClientRail() throws Exception {
        String query = "RAG web search: Supabase MCP read_only, 2026 OpenAI latest changes, "
                + "internal Dynamic RAG failure handling, "
                + "\uACF5\uC2DD/changelog \uADFC\uAC70 lane, \uCD5C\uC2E0\uC131 lane, "
                + "\uBC18\uB840 lane, \uCD9C\uCC98 \uB610\uB294 evidence_needed";
        List<RagEvidenceMetadata> evidence = List.of(
                new RagEvidenceMetadata("W1", "WEB", "Korean university PDF",
                        "https://www.kongju.ac.kr/bbs/KNU/2132/651320/download.do", null,
                        null, null, 1, 0.80d, "score"),
                new RagEvidenceMetadata("W2", "WEB", "HRD PDF",
                        "https://www.hrd4u.or.kr/portal/cmm/fms/FileDown.do?atchFileId=FILE_000000000601563",
                        null, null, null, 2, 0.75d, "score"),
                new RagEvidenceMetadata("W3", "WEB", "OpenAI release notes",
                        "https://help.openai.com/en/articles/6825453-chatgpt-release-notes", null,
                        null, null, 3, 0.91d, "score"),
                new RagEvidenceMetadata("W4", "WEB", "Supabase MCP docs",
                        "https://supabase.com/docs/guides/ai-tools/mcp", null,
                        null, null, 4, 0.88d, "score"));

        List<RagEvidenceMetadata> filtered = invokeOfficialSourceEvidenceMetadataFilter(query, evidence);

        assertEquals(2, filtered.size(),
                "strict Korean official/changelog probes should not render off-domain PDFs in the browser evidence rail");
        assertTrue(filtered.stream().map(RagEvidenceMetadata::source).anyMatch(source -> source.contains("help.openai.com")),
                filtered::toString);
        assertTrue(filtered.stream().map(RagEvidenceMetadata::source).anyMatch(source -> source.contains("supabase.com")),
                filtered::toString);
        assertFalse(filtered.stream().map(RagEvidenceMetadata::source).anyMatch(source -> source.contains("kongju.ac.kr")),
                "off-domain PDF evidence should be filtered before client rail rendering");
        assertEquals(true, TraceStore.get("prompt.officialSourceMetadataFilter.applied"));
        TraceStore.clear();
    }

    @Test
    void explicitAllowedSourcePromptFiltersVisibleEvidenceMetadataForClientRail() throws Exception {
        String query = "Official source constrained RAG probe: compare OpenAI Responses API web_search "
                + "and Supabase Vector pgvector docs. Allowed sources are developers.openai.com, "
                + "platform.openai.com, supabase.com only. Exclude unofficial sources and openai.com marketing pages.";
        List<Content> promptWebDocs = List.of(
                content("Community tutorial", Map.of("url", "https://dev.to/example/rag")),
                content("OpenAI marketing page", Map.of("url", "https://openai.com/index/openai-api/")),
                content("OpenAI official docs",
                        Map.of("url", "https://developers.openai.com/api/docs/guides/tools-web-search")),
                content("Supabase vector docs", Map.of("url", "https://supabase.com/docs/guides/ai/vector")));
        List<RagEvidenceMetadata> evidence = List.of(
                new RagEvidenceMetadata("W1", "WEB", "Community tutorial", "https://dev.to/example/rag", null,
                        null, null, 1, 0.30d, "score"),
                new RagEvidenceMetadata("W2", "WEB", "OpenAI marketing page",
                        "https://openai.com/index/openai-api/", null,
                        null, null, 2, 0.61d, "score"),
                new RagEvidenceMetadata("W3", "WEB", "OpenAI official docs",
                        "https://developers.openai.com/api/docs/guides/tools-web-search", null,
                        null, null, 3, 0.91d, "score"),
                new RagEvidenceMetadata("W4", "WEB", "Supabase vector docs",
                        "https://supabase.com/docs/guides/ai/vector", null,
                        null, null, 4, 0.88d, "score"));

        List<Content> filteredPromptDocs = invokeOfficialSourcePromptWebFilter(query, promptWebDocs);
        List<RagEvidenceMetadata> filtered = invokeOfficialSourceEvidenceMetadataFilter(query, evidence);

        assertEquals(2, filteredPromptDocs.size(),
                "explicit allowed-source prompts should remove off-domain docs before PromptBuilder");
        assertFalse(filteredPromptDocs.stream().map(ChatWorkflowAgentVisibleDebugEvidenceTest::contentText)
                .anyMatch(text -> text.contains("Community")),
                "off-domain snippets should not enter the prompt context for strict allowed-source probes");
        assertEquals(2, filtered.size(),
                "strict allowed-source prompts should remove off-domain evidence before UI evidence rail rendering");
        assertTrue(filtered.stream().map(RagEvidenceMetadata::source)
                .allMatch(source -> source.contains("developers.openai.com") || source.contains("supabase.com")),
                filtered::toString);
        assertFalse(filtered.stream().map(RagEvidenceMetadata::source).anyMatch(source -> source.contains("dev.to")),
                "browser-visible evidence rail must not list searched-but-uncited off-domain sources");
        assertFalse(filtered.stream().map(RagEvidenceMetadata::source).anyMatch(source -> source.contains("openai.com/index")),
                "explicit subdomain allowlists must not widen to parent openai.com");
        assertEquals(true, TraceStore.get("prompt.officialSourceMetadataFilter.applied"));
        assertEquals(2, TraceStore.get("prompt.officialSourceMetadataFilter.keptCount"));
        assertEquals(2, TraceStore.get("prompt.officialSourceMetadataFilter.removedCount"));
    }

    @Test
    void officialChangelogPromptPrioritizesVisibleEvidenceMetadataForClientRail() throws Exception {
        String query = "OpenAI API 최신 변경사항을 공식 changelog 근거 위주로 알려줘";
        List<RagEvidenceMetadata> evidence = List.of(
                new RagEvidenceMetadata("W1", "WEB", "OpenAI platform changelog",
                        "https://platform.openai.com/docs/changelog", null,
                        null, null, 1, 0.91d, "score"),
                new RagEvidenceMetadata("W2", "WEB", "openai-python repository",
                        "https://github.com/openai/openai-python", null,
                        null, null, 2, 0.89d, "score"),
                new RagEvidenceMetadata("W3", "WEB", "Community OpenAI SDK",
                        "https://github.com/anasfik/openai", null,
                        null, null, 3, 0.75d, "score"),
                new RagEvidenceMetadata("W4", "WEB", "ChatGPT release notes",
                        "https://help.openai.com/en/articles/6825453-chatgpt-release-notes", null,
                        null, null, 4, 0.86d, "score"),
                new RagEvidenceMetadata("W5", "WEB", "Codex changelog",
                        "https://developers.openai.com/codex/changelog", null,
                        null, null, 5, 0.84d, "score"));

        List<RagEvidenceMetadata> prioritized = invokeOfficialSourceEvidenceMetadataFilter(query, evidence);

        assertEquals(5, prioritized.size(), "non-strict changelog prompts should keep searched evidence available");
        List<String> topSources = prioritized.stream().limit(3).map(RagEvidenceMetadata::source).toList();
        assertTrue(topSources.stream().anyMatch(source -> source.contains("platform.openai.com/docs/changelog")),
                topSources::toString);
        assertTrue(topSources.stream().anyMatch(source -> source.contains("help.openai.com")),
                topSources::toString);
        assertTrue(topSources.stream().anyMatch(source -> source.contains("developers.openai.com")),
                topSources::toString);
        assertFalse(topSources.stream().anyMatch(source -> source.contains("github.com")),
                "community/repository results should fold below official changelog/release-note evidence");
    }

    @Test
    void retrievalEvidenceAttachmentProbeDoesNotShortCircuitAsCurrentUiModeStatus() throws Exception {
        String query = "\uBE0C\uB77C\uC6B0\uC800 UI RAG \uC6F9\uAC80\uC0C9 \uCD5C\uC885 \uD0D0\uCE68: "
                + "openai.com \uACF5\uC2DD \uBB38\uC11C \uADFC\uAC70\uAC00 evidence\uB85C "
                + "\uBD99\uB294\uC9C0 \uD55C \uC904\uB85C \uD655\uC778\uD574\uC918.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(query)
                .searchMode(SearchMode.AUTO)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(query),
                "evidence attachment probes should continue through retrieval flow");
        assertNull(invokeCurrentModeStatusFallback(query, request),
                "current UI mode fallback must not answer evidence attachment probes");
    }

    @Test
    void domainEvidenceProbeDoesNotShortCircuitAsCurrentUiModeStatus() throws Exception {
        String query = "Browser UI RAG web search final probe: confirm openai.com official docs evidence in one line.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(query)
                .searchMode(SearchMode.AUTO)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(query),
                "domain evidence probes should continue through retrieval flow");
        assertNull(invokeCurrentModeStatusFallback(query, request),
                "current UI mode fallback must not answer domain evidence probes");
    }

    @Test
    void officialUrlEvidenceProbeDoesNotShortCircuitAsCurrentUiModeStatus() throws Exception {
        String query = "RAG random probe 2026-07-08 guard evidence fail-closed: compare OpenAI Responses API web_search tooling "
                + "and Supabase MCP read_only project_ref setup using official/external sources only. "
                + "If official OpenAI/Supabase URLs are unavailable, answer evidence_needed without listing GitHub mirrors, "
                + "NVIDIA docs, Streamlit tutorials, WFGY/community pages, or local PDFs as evidence.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(query)
                .searchMode(SearchMode.FORCE_DEEP)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(query),
                "official URL evidence probes should continue through retrieval instead of local UI-mode status");
        assertNull(invokeCurrentModeStatusFallback(query, request),
                "current UI mode fallback must not answer official URL evidence probes");
    }

    @Test
    void fallbackReferenceAppenderUsesLiteralSourcesHeadingAndPublicUrls() throws Exception {
        String answer = "Answer cites [W1].";
        List<Content> webDocs = List.of(content(
                "OpenAI Responses API official source",
                Map.of("title", "OpenAI Responses API",
                        "url", "https://developers.openai.com/api/docs/guides/tools-web-search?secret=redact#frag")));

        String withReferences = invokeAppendEvidenceReferencesIfNeeded(answer, webDocs, List.of());

        assertTrue(withReferences.contains("### Sources"),
                "fallback evidence references should use a stable literal heading the browser proof can detect");
        assertTrue(withReferences.contains("https://developers.openai.com/api/docs/guides/tools-web-search"),
                "fallback evidence references should expose sanitized public source URLs");
        assertFalse(withReferences.contains("secret=redact"),
                "fallback evidence references must strip query strings from public URLs");
        assertFalse(withReferences.contains("??"),
                "fallback evidence references should not render mojibake delimiters");
    }

    @Test
    void fallbackReferenceAppenderPreservesAlternateExistingAppendixHeadings() throws Exception {
        List<Content> webDocs = List.of(content(
                "OpenAI Responses API official source",
                Map.of("title", "OpenAI Responses API",
                        "url", "https://developers.openai.com/api/docs/guides/tools-web-search")));

        for (String heading : List.of("Evidence", "References", "Citations")) {
            String answer = "Answer cites [W1].\n\n### " + heading + "\n- [W1] Existing source";

            String withReferences = invokeAppendEvidenceReferencesIfNeeded(answer, webDocs, List.of());

            assertEquals(answer, withReferences,
                    "an existing " + heading + " appendix must remain the single final appendix");
            assertFalse(withReferences.contains("### Sources"), withReferences);
        }
    }

    @Test
    void fallbackReferenceAppenderIgnoresFencedAndPrefixOnlyAppendixHeadings() throws Exception {
        List<Content> webDocs = List.of(content(
                "OpenAI Responses API official source",
                Map.of("title", "OpenAI Responses API",
                        "url", "https://developers.openai.com/api/docs/guides/tools-web-search")));

        for (String answer : List.of(
                "Answer cites [W1].\r\n\r\n```markdown\r\n### Evidence\r\n```",
                "Answer cites [W1].\n\n### Evidence-based Analysis")) {
            String withReferences = invokeAppendEvidenceReferencesIfNeeded(answer, webDocs, List.of());

            assertTrue(withReferences.contains("### Sources"), withReferences);
        }
    }

    @Test
    void fallbackReferenceAppenderDoesNotReviveFilteredOffDomainSourcesForOfficialPrompt() throws Exception {
        String query = "RAG \uC6F9\uC11C\uCE58 \uAC80\uC99D: Supabase MCP read_only, "
                + "2026\uB144 OpenAI \uCD5C\uC2E0 \uBCC0\uACBD, "
                + "\uCD9C\uCC98 \uB610\uB294 evidence_needed";
        String answer = "evidence_needed: \uACF5\uC2DD/changelog \uADFC\uAC70\uB97C "
                + "\uD604\uC7AC \uAC80\uC0C9 \uACB0\uACFC\uC5D0\uC11C \uD655\uC778\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.";
        List<Content> webDocs = List.of(content(
                "WEB:OFFICIAL metadata only",
                Map.of("title", "WEB:OFFICIAL",
                        "url", "https://www.kongju.ac.kr/bbs/KNU/2132/651320/download.do")));

        String withReferences = invokeAppendEvidenceReferencesIfNeeded(answer, webDocs, List.of(), query);

        assertFalse(withReferences.contains("kongju.ac.kr"), withReferences);
        assertFalse(withReferences.contains("### Sources"), withReferences);
        assertTrue(withReferences.contains("evidence_needed"), withReferences);
    }

    @Test
    void finalAppendixFallsBackToTopDocsWhenPromotedEvidenceIsEmpty() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"))
                .replace("\r\n", "\n");

        int centralOwner = source.indexOf("private String appendFinalEvidenceOnce(");
        int skipped = source.indexOf(
                "TraceStore.put(\"rag.evidence.appendix.skipped\", \"no_promoted_evidence\");",
                centralOwner);
        int legacyFallback = source.indexOf("return appendEvidenceReferencesIfNeeded(", skipped);

        assertTrue(centralOwner > 0, "final appendix should have one central owner");
        assertTrue(skipped > 0, "empty promoted-evidence branch should remain visible in trace output");
        assertTrue(legacyFallback > skipped, "promoted-empty and service-null paths should use legacy references");

        String promotedEmptyBranch = source.substring(skipped, legacyFallback + "return appendEvidenceReferencesIfNeeded(".length());
        assertTrue(promotedEmptyBranch.contains("appendEvidenceReferencesIfNeeded("),
                "when attribution promotion yields no citable evidence, final answers should still fall back to topDocs references");
    }

    @Test
    void currentUiModeShortCircuitStampsAnswerModeForVisibleRails() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int shortCircuit = source.indexOf("chat.uiModeStatus.shortCircuit");
        int answerMode = source.indexOf("TraceStore.put(\"answer.mode\", \"ui-mode:local:evidence\")", shortCircuit);
        int result = source.indexOf("ChatResult.of(earlyCurrentModeStatusFallback, \"ui-mode:local:evidence\"", shortCircuit);

        assertTrue(shortCircuit > 0, "current UI mode status branch should keep a trace marker");
        assertTrue(answerMode > shortCircuit,
                "request-local UI mode answers should stamp answer.mode before controller metadata capture");
        assertTrue(result > answerMode,
                "visible result label should match the stamped answer.mode");
    }

    @Test
    void currentUiSearchRagEvidenceOnlyQuestionReturnsLocalStatus() throws Exception {
        ChatRequestDto request = ChatRequestDto.builder()
                .message("RDEEP-403 \uD604\uC7AC UI\uC758 Search/RAG \uC124\uC815 \uC99D\uAC70\uB9CC \uC9E7\uAC8C \uB2F5\uD574. Supabase DB \uC5F0\uACB0 \uC131\uACF5\uC774\uB77C\uACE0 \uB9D0\uD558\uC9C0 \uB9C8.")
                .searchMode(SearchMode.FORCE_DEEP)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertTrue(ChatWorkflow.isCurrentModeStatusRequest(request.getMessage()));

        String answer = invokeCurrentModeStatusFallback(request.getMessage(), request);

        assertNotNull(answer);
        assertTrue(answer.contains("Search: DEEP"), answer);
        assertTrue(answer.contains("webSearch=true"), answer);
        assertTrue(answer.contains("RAG: ON"), answer);
        assertFalse(answer.contains("\uD604\uC7AC \uB514\uBC84\uADF8 heartbeat"), answer);
        assertFalse(answer.contains("Supabase DB \uC5F0\uACB0 \uC131\uACF5"), answer);
        assertFalse(answer.contains("local://agent-visible-debug"), answer);
    }

    @Test
    void currentUiSearchRagOneSentenceStatusQuestionStillReturnsLocalStatus() throws Exception {
        ChatRequestDto request = ChatRequestDto.builder()
                .message("\uD604\uC7AC Search/RAG \uC0C1\uD0DC\uB97C \uD55C \uBB38\uC7A5\uC73C\uB85C \uB2F5\uD574\uC918.")
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .build();

        assertTrue(ChatWorkflow.isCurrentModeStatusRequest(request.getMessage()));

        String answer = invokeCurrentModeStatusFallback(request.getMessage(), request);

        assertNotNull(answer);
        assertTrue(answer.contains("Search: OFF"), answer);
        assertTrue(answer.contains("RAG: OFF"), answer);
    }

    @Test
    void currentUiModeReadbackWithJustChangedKoreanQualifierDoesNotFallBackToHistory() throws Exception {
        String prompt = "\uD604\uC7AC Search/RAG UI \uBAA8\uB4DC\uB97C \uD55C \uBB38\uC7A5\uC73C\uB85C "
                + "\uB9D0\uD574\uC918. \uBC29\uAE08 \uC124\uC815 \uAE30\uC900\uC73C\uB85C\uB9CC "
                + "\uB2F5\uD574\uC918.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .build();
        String history = """
                User: \uD604\uC7AC Search/RAG UI \uBAA8\uB4DC\uB97C \uD55C \uBB38\uC7A5\uC73C\uB85C \uB9D0\uD574\uC918.
                Assistant: Current UI mode status (request-local evidence):
                - Search: LIGHT (webSearch=true; requested=true)
                - RAG: ON (useRag=true)
                Source: current request controls; no external proof claimed.
                User: %s
                """.formatted(prompt);

        assertTrue(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertNull(ChatWorkflow.composeRecentHistoryFallback(prompt, history));

        String answer = invokeCurrentModeStatusFallback(prompt, request);

        assertNotNull(answer);
        assertTrue(answer.contains("Search: OFF"), answer);
        assertTrue(answer.contains("RAG: OFF"), answer);
        assertFalse(answer.contains("Search: LIGHT"), answer);
    }

    @Test
    void currentRagCheckboxStatusReadbackDoesNotFallBackToHistory() throws Exception {
        String prompt = "\uC9C0\uAE08 RAG \uC0C1\uD0DC\uB9CC \uD655\uC778\uD574\uC918. "
                + "\uC774\uC804 \uB2F5\uBCC0\uC740 \uBC18\uBCF5\uD558\uC9C0 \uB9D0\uACE0 "
                + "\uD604\uC7AC \uCCB4\uD06C\uBC15\uC2A4 \uAE30\uC900\uC73C\uB85C\uB9CC "
                + "\uB9D0\uD574\uC918.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(true)
                .build();
        String history = """
                User: \uD604\uC7AC Search/RAG UI \uBAA8\uB4DC\uB97C \uD55C \uBB38\uC7A5\uC73C\uB85C \uB9D0\uD574\uC918.
                Assistant: Current UI mode status (request-local evidence):
                - Search: OFF (webSearch=false; requested=false)
                - RAG: OFF (useRag=false)
                Source: current request controls; no external proof claimed.
                User: %s
                """.formatted(prompt);

        assertTrue(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertNull(ChatWorkflow.composeRecentHistoryFallback(prompt, history));

        String answer = invokeCurrentModeStatusFallback(prompt, request);

        assertNotNull(answer);
        assertTrue(answer.contains("Search: OFF"), answer);
        assertTrue(answer.contains("RAG: ON"), answer);
        assertFalse(answer.contains("RAG: OFF"), answer);
        assertFalse(answer.contains("\uC9C1\uC804 \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0"), answer);
    }

    @Test
    void currentUiModeQuestionWithPreviousKeywordDoesNotFallBackToRecentHistory() throws Exception {
        String prompt = "cont20 RAG \uBA40\uD2F0\uB77C\uC778 \uC810\uAC80:\n"
                + "1) \uC774\uC804 LIGHT \uAC80\uC0C9 \uC0C1\uD0DC\uB97C \uD55C \uBB38\uC7A5\uC73C\uB85C \uC694\uC57D\n"
                + "2) \uC9C0\uAE08 RAG ON \uC0C1\uD0DC\uB97C UI \uAE30\uC900\uC73C\uB85C\uB9CC \uB9D0\uD574\uC918.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(true)
                .build();
        String history = """
                User: cont20 LIGHT search mode check
                Assistant: Current UI mode status (request-local evidence):
                - Search: LIGHT (webSearch=true; requested=true)
                - RAG: OFF (useRag=false)
                Source: current request controls; no external proof claimed.
                User: %s
                """.formatted(prompt);

        assertTrue(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertNull(ChatWorkflow.composeRecentHistoryFallback(prompt, history));

        String answer = invokeCurrentModeStatusFallback(prompt, request);

        assertNotNull(answer);
        assertTrue(answer.contains("Search: OFF"), answer);
        assertTrue(answer.contains("RAG: ON"), answer);
        assertFalse(answer.contains("\uC9C1\uC804 \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0"), answer);
    }

    @Test
    void currentUiModePredicateDoesNotCaptureModeQualifiedContentRequest() throws Exception {
        String prompt = "\uB79C\uB364 UI \uC2A4\uBAA8\uD06C: \uD604\uC7AC RAG/LIGHT \uC124\uC815\uC73C\uB85C "
                + "\uC9D1\uC911\uC744 \uB192\uC774\uB294 \uBC29\uBC95\uC744 \uD55C\uAD6D\uC5B4\uB85C "
                + "\uC138 \uBB38\uC7A5\uB9CC \uB2F5\uD574\uC918. "
                + "\uB514\uBC84\uADF8 \uC124\uBA85 \uC5C6\uC774 \uB2F5\uBCC0\uB9CC \uD574\uC918.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.FORCE_LIGHT)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertEquals(null, invokeCurrentModeStatusFallback(prompt, request));
    }

    @Test
    void currentUiModePredicateDoesNotCaptureEnterSendContentRequestWithModesOff() throws Exception {
        String prompt = "\uB79C\uB364 \uC2A4\uBAA8\uD06C H0916: \uAC80\uC0C9 OFF\uC640 RAG OFF "
                + "\uC0C1\uD0DC\uC5D0\uC11C Enter \uC804\uC1A1\uC774 \uB418\uB294\uC9C0 "
                + "\uD55C \uBB38\uC7A5\uC73C\uB85C \uB2F5\uD574. "
                + "\uB514\uBC84\uADF8 \uC124\uBA85 \uC5C6\uC774 \uB2F5\uBCC0\uB9CC.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertEquals(null, invokeCurrentModeStatusFallback(prompt, request));
    }

    @Test
    void currentUiModePredicateDoesNotCaptureStopContentRequestWithModesOff() throws Exception {
        String prompt = "\uB79C\uB364 \uC2A4\uBAA8\uD06C H0921: \uAC80\uC0C9 OFF\uC640 RAG OFF "
                + "\uC0C1\uD0DC\uC5D0\uC11C \uC544\uC8FC \uAE34 \uC124\uBA85\uC744 "
                + "\uC2DC\uC791\uD574\uC918. \uB0B4\uAC00 \uACE7 Stop\uC744 "
                + "\uB204\uB97C \uAC70\uC57C. \uB514\uBC84\uADF8 \uC124\uBA85 "
                + "\uC5C6\uC774 \uBCF8\uBB38\uB9CC \uC774\uC5B4\uC11C \uC368\uC918.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertEquals(null, invokeCurrentModeStatusFallback(prompt, request));
    }

    @Test
    void currentUiModePredicateDoesNotCaptureModeQualifiedExplanationWithCitationRequest() throws Exception {
        String prompt = "\uAC80\uC0C9/RAG OFF \uC0C1\uD0DC\uC5D0\uC11C Provider Guard\uC640 Trace\uC758 "
                + "\uC5ED\uD560\uC744 \uB450 \uBB38\uC7A5\uC73C\uB85C \uC124\uBA85\uD558\uACE0 "
                + "\uAC01 \uBB38\uC7A5 \uB05D\uC5D0 [W1] \uB610\uB294 [V2]\uB97C \uBD99\uC5EC\uC918.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertEquals(null, invokeCurrentModeStatusFallback(prompt, request));
    }

    @Test
    void currentUiModePredicateDoesNotCaptureOpenAiModelValueDocsQuestion() throws Exception {
        String prompt = "OpenAI official docs Responses API web search tool type and example model value.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.FORCE_DEEP)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertEquals(null, invokeCurrentModeStatusFallback(prompt, request));
    }

    @Test
    void currentUiModePredicateDoesNotCaptureModeQualifiedSupabaseFeatureExplanation() throws Exception {
        String prompt = "With current RAG and web search evidence, explain which Supabase MCP "
                + "read_only project_ref missing features should be deferred, with evidence and debug status.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.FORCE_DEEP)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertEquals(null, invokeCurrentModeStatusFallback(prompt, request));
    }

    @Test
    void currentUiModePredicateDoesNotCaptureOperationalDeferredAllowedListRequest() throws Exception {
        String prompt = "RAG/web search probe: Supabase MCP read_only is reachable, "
                + "but project_ref and auth are missing. List 5 deferred features "
                + "and 3 allowed features with evidence and debug status.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.FORCE_DEEP)
                .useWebSearch(true)
                .useRag(true)
                .build();

        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertEquals(null, invokeCurrentModeStatusFallback(prompt, request));
    }

    @Test
    void openAiResponsesWebSearchFieldAnswerCopiesCitableEvidenceValues() throws Exception {
        String prompt = "OpenAI official docs Responses API web search tool type and example model value.";
        Content web = Content.from(TextSegment.from("""
                Web search tool example
                const response = await client.responses.create({
                  model: "gpt-5.5",
                  tools: [
                    { type: "web_search" },
                  ],
                });
                """, Metadata.from(Map.of(
                "title", "Web search | OpenAI API",
                "url", "https://developers.openai.com/api/docs/guides/tools-web-search"))));
        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "W1",
                "WEB",
                "Web search | OpenAI API",
                "https://developers.openai.com/api/docs/guides/tools-web-search",
                null,
                null,
                null,
                1,
                0.91d,
                "score");

        String answer = invokeOpenAiResponsesWebSearchFieldAnswer(prompt, List.of(web), List.of(evidence));

        assertNotNull(answer);
        assertTrue(answer.contains("tool type: `web_search` [W1]"), answer);
        assertTrue(answer.contains("example model: `gpt-5.5` [W1]"), answer);
        assertFalse(answer.contains("gpt-4"), answer);
        assertFalse(answer.contains("gpt-3.5"), answer);
    }

    @Test
    void openAiResponsesWebSearchFieldAnswerMatchesCitableEvidenceByUrlWhenRankShifted() throws Exception {
        String prompt = "OpenAI official docs Responses API web search tool type and example model value.";
        Content web = Content.from(TextSegment.from("""
                Web search tool example
                const response = await client.responses.create({
                  model: "gpt-5.5",
                  tools: [
                    { type: "web_search" },
                  ],
                });
                """, Metadata.from(Map.of(
                "title", "Web search | OpenAI API",
                "url", "https://developers.openai.com/api/docs/guides/tools-web-search"))));
        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "W4",
                "WEB",
                "Web search | OpenAI API",
                "https://developers.openai.com/api/docs/guides/tools-web-search",
                null,
                null,
                null,
                4,
                0.91d,
                "score");

        String answer = invokeOpenAiResponsesWebSearchFieldAnswer(prompt, List.of(web), List.of(evidence));

        assertNotNull(answer);
        assertTrue(answer.contains("tool type: `web_search` [W4]"), answer);
        assertTrue(answer.contains("example model: `gpt-5.5` [W4]"), answer);
        assertFalse(answer.contains("gpt-3.5"), answer);
    }

    @Test
    void openAiResponsesWebSearchFieldAnswerReturnsEvidenceNeededWithoutCitableEvidence() throws Exception {
        String prompt = "OpenAI official docs Responses API web search tool type and example model value.";
        Content web = Content.from("""
                Search result summary mentions web search but has no public citable locator.
                """);

        String answer = invokeOpenAiResponsesWebSearchFieldAnswer(prompt, List.of(web), List.of());

        assertNotNull(answer);
        assertTrue(answer.contains("tool type: evidence_needed"), answer);
        assertTrue(answer.contains("example model: evidence_needed"), answer);
        assertFalse(answer.contains("gpt-3.5"), answer);
        assertFalse(answer.contains("gpt-4"), answer);
    }

    @Test
    void agentDebugFallbackDoesNotCaptureModeQualifiedExplanationWithCitationRequest() throws Exception {
        String prompt = "\uAC80\uC0C9/RAG OFF \uC0C1\uD0DC\uC5D0\uC11C Provider Guard\uC640 Trace\uC758 "
                + "\uC5ED\uD560\uC744 \uB450 \uBB38\uC7A5\uC73C\uB85C \uC124\uBA85\uD558\uACE0 "
                + "\uAC01 \uBB38\uC7A5 \uB05D\uC5D0 [W1] \uB610\uB294 [V2]\uB97C \uBD99\uC5EC\uC918.";
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                source=chat-workflow-pre-llm
                external.browser.evidenceNeeded=browser_ui_smoke_missing
                external.computer-use.evidenceNeeded=computer_use_smoke_missing
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                matrix:300/30 decision:observe
                """;

        assertTrue(AgentVisibleDebugEvidenceBuilder.isDebugEvidenceQuery(prompt));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(prompt));
        assertEquals(null, invokeAgentDebugFallback(prompt, List.of(Document.from(heartbeat))));
    }

    @Test
    void currentUiModePredicateDoesNotCaptureExternalProofStatusRequest() throws Exception {
        String prompt = "Summarize current Search/RAG UI mode and Browser/Computer/Supabase evidence status "
                + "in one paragraph without claiming Supabase DB proof.";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .searchMode(SearchMode.FORCE_LIGHT)
                .useWebSearch(true)
                .useRag(true)
                .build();
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:OK computer-use:OK supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=OK
                external.browser.evidenceNeeded=none
                external.browser.stale=false
                external.browser.blocking=false
                external.browser.evidenceScope=local-ui-proof
                external.computer-use.status=OK
                external.computer-use.evidenceNeeded=none
                external.computer-use.stale=false
                external.computer-use.blocking=false
                external.computer-use.evidenceScope=gui-supporting-only
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;

        String debugAnswer = invokeAgentDebugFallback(prompt, List.of(Document.from(heartbeat)));

        assertNotNull(debugAnswer);
        assertTrue(debugAnswer.contains("Browser: OK"), debugAnswer);
        assertTrue(debugAnswer.contains("Computer: OK"), debugAnswer);
        assertTrue(debugAnswer.contains("Supabase: evidence_needed"), debugAnswer);
        assertFalse(ChatWorkflow.isCurrentModeStatusRequest(prompt));
        assertEquals(null, invokeCurrentModeStatusFallback(prompt, request));

        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int externalProofOnly = source.indexOf("chat.agentDebugEvidence.externalProofOnlyShortCircuit");
        int currentMode = source.indexOf("composeCurrentModeStatusFallback(userQuery, req)");

        assertTrue(externalProofOnly > 0,
                "external proof-only requests should have a deterministic early branch");
        assertTrue(externalProofOnly < currentMode,
                "external proof-only status requests should not be shadowed by local UI-mode fallback");
    }

    @Test
    void skillLaneLocalExternalProofRequestUsesDeterministicExternalProofFallback() throws Exception {
        String prompt = "\uC2A4\uD0AC \uAE30\uBC18 RAG \uAC80\uC99D: "
                + "demo1-demand-driven-external-proof, demo1-superpowers-repo-evidence-guard, supabase skill "
                + "\uAE30\uC900\uC73C\uB85C Browser / Computer / Supabase lane\uC744 local proof\uC640 external evidence\uB85C "
                + "\uBD84\uB9AC\uD574 5\uC904\uB9CC \uB2F5\uD574\uC918. repo\uC5D0 \uC2E4\uC81C \uC874\uC7AC\uD558\uB294 "
                + "\uBA85\uB839\uB9CC \uC81C\uC2DC\uD558\uACE0, \uC874\uC7AC \uD655\uC778 \uC548 \uB41C \uBA85\uB839\uC740 "
                + "evidence_needed\uB85C \uD45C\uC2DC\uD574\uC918.";
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:OK computer-use:OK supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=OK
                external.browser.evidenceNeeded=browser_ui_smoke_stale
                external.browser.stale=true
                external.computer-use.status=OK
                external.computer-use.evidenceNeeded=computer_use_smoke_stale
                external.computer-use.stale=true
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                """;

        assertTrue(invokeExternalProofOnlyAnswerRequest(prompt));

        String answer = invokeAgentDebugFallback(prompt, List.of(Document.from(heartbeat)));

        assertNotNull(answer);
        assertTrue(answer.contains("Browser:"), answer);
        assertTrue(answer.contains("Computer:"), answer);
        assertTrue(answer.contains("Supabase:"), answer);
        assertTrue(answer.contains("evidence_needed"), answer);
        assertFalse(answer.contains("inspect_supabase_shadow_snapshot"), answer);
        assertFalse(answer.contains("run_browser_local_ui_smoke"), answer);
        assertFalse(answer.contains("run_computer_use_lightweight_smoke"), answer);
    }

    @Test
    void postSoakProofLaneSplitRequestUsesDeterministicExternalProofFallback() throws Exception {
        String prompt = "60\uBD84 soak \uC774\uD6C4 \uAC80\uC99D\uC774\uC57C. "
                + "\uACF5\uC2DD OpenAI changelog \uADFC\uAC70\uAC00 \uC5C6\uC73C\uBA74 "
                + "evidence_needed\uB85C \uD45C\uC2DC\uD558\uACE0, "
                + "Browser Computer Supabase proof lane\uC744 \uAC01\uAC01 \uBD84\uB9AC\uD574\uC11C "
                + "\uB9D0\uD574\uC918.";
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:SUPPORTING_EVIDENCE_MISSING computer-use:SUPPORTING_EVIDENCE_MISSING supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=SUPPORTING_EVIDENCE_MISSING
                external.browser.evidenceNeeded=browser_ui_smoke_missing
                external.browser.nextAction=run_browser_local_ui_smoke
                external.computer-use.status=SUPPORTING_EVIDENCE_MISSING
                external.computer-use.evidenceNeeded=computer_use_smoke_missing
                external.computer-use.nextAction=run_computer_use_lightweight_smoke
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=inspect_supabase_shadow_snapshot
                """;

        assertTrue(invokeExternalProofOnlyAnswerRequest(prompt));

        String answer = invokeAgentDebugFallback(prompt, List.of(Document.from(heartbeat)));

        assertNotNull(answer);
        assertTrue(answer.contains("Browser:"), answer);
        assertTrue(answer.contains("Computer:"), answer);
        assertTrue(answer.contains("Supabase:"), answer);
        assertTrue(answer.contains("evidence_needed"), answer);
        assertFalse(answer.contains("inspect_supabase_shadow_snapshot"), answer);
        assertFalse(answer.contains("run_browser_local_ui_smoke"), answer);
        assertFalse(answer.contains("run_computer_use_lightweight_smoke"), answer);
    }

    @Test
    void refreshedExternalProofStatusRequestDoesNotFallBackToRecentHistory() throws Exception {
        String prompt = "Summarize current Search/RAG UI mode and Browser/Computer/Supabase evidence status "
                + "in one paragraph without claiming Supabase DB proof. "
                + "\uBE0C\uB77C\uC6B0\uC800\uC640 \uCEF4\uD4E8\uD130 proof\uAC00 "
                + "\uBC29\uAE08 \uAC31\uC2E0\uB410\uB294\uC9C0\uB3C4 \uAC19\uC774 \uB9D0\uD574\uC918.";
        String history = """
                User: Summarize current Search/RAG UI mode and Browser/Computer/Supabase evidence status in one paragraph without claiming Supabase DB proof.
                Assistant: - Browser: SUPPORTING_EVIDENCE_MISSING (evidence_needed=browser_ui_smoke_stale; stale=true)
                - Computer: SUPPORTING_EVIDENCE_MISSING (evidence_needed=computer_use_smoke_stale; stale=unknown; count-only=true)
                - Supabase: evidence_needed (evidence_needed; read-only only; DB verification not claimed)
                User: %s
                """.formatted(prompt);
        String heartbeat = """
                AGENT_VISIBLE_DEBUG_HEARTBEAT
                summary=browser:OK computer-use:OK supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe
                external.browser.status=OK
                external.browser.evidenceNeeded=none
                external.browser.stale=false
                external.computer-use.status=OK
                external.computer-use.evidenceNeeded=none
                external.computer-use.stale=false
                external.computer-use.countOnly=true
                external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified
                debug.ai.metrics.virtualMatrix.count=300
                debug.ai.metrics.virtualMatrix.chunkCount=30
                debug.ai.metrics.virtualMatrix.decision=observe
                """;

        assertTrue(invokeExternalProofOnlyAnswerRequest(prompt));
        assertNull(ChatWorkflow.composeRecentHistoryFallback(prompt, history));

        String debugAnswer = invokeAgentDebugFallback(prompt, List.of(Document.from(heartbeat)));

        assertNotNull(debugAnswer);
        assertTrue(debugAnswer.contains("Browser: OK"), debugAnswer);
        assertTrue(debugAnswer.contains("Computer: OK"), debugAnswer);
        assertFalse(debugAnswer.contains("\uC9C1\uC804 \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0"), debugAnswer);
    }

    private static String invokeAgentDebugFallback(List<Document> docs) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod("composeAgentVisibleDebugFallback", List.class);
        method.setAccessible(true);
        return (String) method.invoke(null, docs);
    }

    private static boolean invokeExternalProofOnlyAnswerRequest(String query) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod("isExternalProofOnlyAnswerRequest", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, query);
    }

    private static String invokeAgentDebugFallback(String query, List<Document> docs) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod("composeAgentVisibleDebugFallback", String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(null, query, docs);
    }

    private static String invokeCurrentModeStatusFallback(String query, ChatRequestDto request) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "composeCurrentModeStatusFallback",
                String.class,
                ChatRequestDto.class);
        method.setAccessible(true);
        return (String) method.invoke(null, query, request);
    }

    @SuppressWarnings("unchecked")
    private static List<Content> invokeOfficialSourcePromptWebFilter(String query, List<Content> webDocs) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "filterOfficialSourcePromptWebDocs",
                String.class,
                List.class);
        method.setAccessible(true);
        return (List<Content>) method.invoke(null, query, webDocs);
    }

    @SuppressWarnings("unchecked")
    private static List<EvidenceAwareGuard.EvidenceDoc> invokeOfficialSourceEvidenceDocFilter(
            String query,
            List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "filterOfficialSourceEvidenceDocs",
                String.class,
                List.class);
        method.setAccessible(true);
        return (List<EvidenceAwareGuard.EvidenceDoc>) method.invoke(null, query, evidenceDocs);
    }

    @SuppressWarnings("unchecked")
    private static List<RagEvidenceMetadata> invokeOfficialSourceEvidenceMetadataFilter(
            String query,
            List<RagEvidenceMetadata> evidence) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "filterOfficialSourceEvidenceMetadata",
                String.class,
                List.class);
        method.setAccessible(true);
        return (List<RagEvidenceMetadata>) method.invoke(null, query, evidence);
    }

    private static String contentText(Content content) {
        return content == null || content.textSegment() == null ? "" : content.textSegment().text();
    }

    private static Content content(String text, Map<String, Object> metadata) {
        return Content.from(TextSegment.from(text, Metadata.from(metadata)));
    }

    @SuppressWarnings("unchecked")
    private static String invokeAppendEvidenceReferencesIfNeeded(
            String answer,
            List<Content> webDocs,
            List<Content> vectorDocs) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "appendEvidenceReferencesIfNeeded",
                String.class,
                List.class,
                List.class);
        method.setAccessible(true);
        return (String) method.invoke(null, answer, webDocs, vectorDocs);
    }

    private static String invokeAppendEvidenceReferencesIfNeeded(
            String answer,
            List<Content> webDocs,
            List<Content> vectorDocs,
            String query) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "appendEvidenceReferencesIfNeeded",
                String.class,
                List.class,
                List.class,
                String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, answer, webDocs, vectorDocs, query);
    }

    @SuppressWarnings("unchecked")
    private static String invokeOpenAiResponsesWebSearchFieldAnswer(
            String query,
            List<Content> web,
            List<RagEvidenceMetadata> evidence) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "composeOpenAiResponsesWebSearchFieldAnswer",
                String.class,
                List.class,
                List.class);
        method.setAccessible(true);
        return (String) method.invoke(null, query, web, evidence);
    }

    private static String invokeDirectLiteralAnswerFallback(String query) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod("composeDirectLiteralAnswerFallback", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, query);
    }

    @Test
    void directLiteralAnswerFallbackReturnsRequestedKoreanOneWord() throws Exception {
        String answer = invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uB178\uC774\uC988 \uAC80\uC99D: "
                        + "\uB2F5\uBCC0\uC740 \uC815\uD655\uD788 \uC548\uB155 "
                        + "\uD55C \uB2E8\uC5B4\uB9CC \uD574\uC918.");

        assertEquals("\uC548\uB155", answer);
    }

    @Test
    void directLiteralAnswerFallbackAcceptsKoreanAnswerVerbForExactOneWord() throws Exception {
        String answer = invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uD6C4\uC18D \uD655\uC778: "
                        + "\uC815\uD655\uD788 OK \uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574.");

        assertEquals("OK", answer);
    }

    @Test
    void directLiteralAnswerFallbackAcceptsKoreanOutputVerbsForExactOneWord() throws Exception {
        assertEquals("ALPHA", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uB3D9\uC0AC \uD655\uC778: "
                        + "\uC815\uD655\uD788 ALPHA \uD55C \uB2E8\uC5B4\uB9CC \uC54C\uB824\uC918."));
        assertEquals("BRAVO", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uB3D9\uC0AC \uD655\uC778: "
                        + "\uC815\uD655\uD788 BRAVO \uD55C \uB2E8\uC5B4\uB9CC \uB9D0\uD574\uC918."));
        assertEquals("CHARLIE", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uB3D9\uC0AC \uD655\uC778: "
                        + "\uC815\uD655\uD788 CHARLIE \uD55C \uB2E8\uC5B4\uB9CC \uCD9C\uB825\uD574."));
        assertEquals("DELTA", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uB3D9\uC0AC \uD655\uC778: "
                        + "\uC815\uD655\uD788 DELTA \uD55C \uB2E8\uC5B4\uB9CC \uC368\uC918."));
        assertEquals("ECHO", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uC644\uACE1 \uB3D9\uC0AC \uD655\uC778: "
                        + "\uC815\uD655\uD788 ECHO \uD55C \uB2E8\uC5B4\uB9CC \uBD80\uD0C1\uD574."));
        assertEquals("FOXTROT", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uBCF4\uAE30 \uB3D9\uC0AC \uD655\uC778: "
                        + "\uC815\uD655\uD788 FOXTROT \uD55C \uB2E8\uC5B4\uB9CC \uBCF4\uC5EC\uC918."));
    }

    @Test
    void directLiteralAnswerFallbackAcceptsKoreanPostposedExactMarker() throws Exception {
        assertEquals("OK", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uC5B4\uC21C \uD655\uC778: "
                        + "OK\uB77C\uACE0 \uC815\uD655\uD788 \uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574."));
        assertEquals("READY", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uC5B4\uC21C \uD655\uC778: "
                        + "READY\uB9CC \uC815\uD655\uD788 \uB2F5\uD574."));
    }

    @Test
    void directLiteralAnswerFallbackAcceptsKoreanPostposedOnlyWithoutExactMarker() throws Exception {
        assertEquals("ping", invokeDirectLiteralAnswerFallback(
                "\uAC80\uC0C9\uACFC RAG \uC5C6\uC774 ping \uC774\uB77C\uACE0\uB9CC \uB2F5\uD574\uC918."));
        assertEquals("OK", invokeDirectLiteralAnswerFallback(
                "OK\uB77C\uACE0\uB9CC \uB300\uB2F5\uD574."));
    }

    @Test
    void directLiteralAnswerFallbackAcceptsKoreanTokenOnlyMachineTokenWithoutExactMarker() throws Exception {
        assertEquals("RREC-302", invokeDirectLiteralAnswerFallback(
                "\uBE0C\uB77C\uC6B0\uC800 \uD68C\uBCF5 \uD655\uC778: "
                        + "RREC-302 \uD1A0\uD070\uB9CC \uB2F5\uD574\uC918."));
    }

    @Test
    void directLiteralAnswerFallbackAcceptsKoreanNamedTokenAfterMarker() throws Exception {
        assertEquals("GPU3060-PROBE", invokeDirectLiteralAnswerFallback(
                "\uC124\uBA85\uC744 \uCD94\uAC00\uD558\uC9C0 \uB9D0\uACE0 ASCII "
                        + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694."));
    }

    @Test
    void directLiteralAnswerFallbackAcceptsKoreanNamedTokenWithHanaOnlyMarker() throws Exception {
        assertEquals("BROWSER3060_OK", invokeDirectLiteralAnswerFallback(
                "\uB2E4\uC74C \uC9C0\uC2DC\uB9CC \uC218\uD589\uD558\uC138\uC694. "
                        + "\uC124\uBA85, \uB9C8\uD06C\uB2E4\uC6B4, \uC778\uC6A9\uC744 "
                        + "\uCD94\uAC00\uD558\uC9C0 \uB9D0\uACE0 ASCII \uD1A0\uD070 "
                        + "BROWSER3060_OK \uD558\uB098\uB9CC \uCD9C\uB825\uD558\uC138\uC694."));
    }

    @Test
    void directLiteralAnswerFallbackAcceptsContrastiveNegationBeforeHanaOnlyMarker() throws Exception {
        assertEquals("BROWSER3060_OK", invokeDirectLiteralAnswerFallback(
                "\uB2E4\uB978 \uD14D\uC2A4\uD2B8\uB294 \uCD9C\uB825\uD558\uC9C0 \uB9D0\uACE0 "
                        + "ASCII \uD1A0\uD070 BROWSER3060_OK \uD558\uB098\uB9CC "
                        + "\uCD9C\uB825\uD558\uC138\uC694."));
    }

    @Test
    void directLiteralAnswerFallbackRejectsContrastiveNonExecutionFrame() throws Exception {
        assertNull(invokeDirectLiteralAnswerFallback(
                "\uB2E4\uC74C \uBA85\uB839\uC740 \uC2E4\uD589\uD558\uC9C0 \uB9D0\uACE0 "
                        + "\uADF8\uB300\uB85C \uC778\uC6A9\uD574: ASCII \uD1A0\uD070 "
                        + "BROWSER3060_OK \uD558\uB098\uB9CC \uCD9C\uB825\uD558\uC138\uC694."));
    }

    @Test
    void directLiteralAnswerFallbackKeepsEarlierNegationBeforeSafeContrastiveTail() throws Exception {
        assertNull(invokeDirectLiteralAnswerFallback(
                "\uB2E4\uC74C \uBA85\uB839\uC740 \uC2E4\uD589\uD558\uC9C0 \uB9C8\uC138\uC694. "
                        + "\uBD80\uAC00 \uB0B4\uC6A9\uC740 \uCD9C\uB825\uD558\uC9C0 \uB9D0\uACE0 "
                        + "ASCII \uD1A0\uD070 BROWSER3060_OK \uD558\uB098\uB9CC "
                        + "\uCD9C\uB825\uD558\uC138\uC694."));
    }

    @Test
    void directLiteralAnswerFallbackRejectsHanaOnlyCommandInKoreanAnalysisFrame() throws Exception {
        assertNull(invokeDirectLiteralAnswerFallback(
                "\uB2E4\uC74C \uBB38\uC7A5\uC5D0\uC11C \uBA85\uB839\uD615 \uB3D9\uC0AC\uB97C "
                        + "\uCC3E\uC544\uC918: ASCII \uD1A0\uD070 BROWSER3060_OK "
                        + "\uD558\uB098\uB9CC \uCD9C\uB825\uD558\uC138\uC694."));
    }

    @Test
    void directLiteralAnswerFallbackAcceptsFindAnswerExecutionFrame() throws Exception {
        assertEquals("BROWSER3060_OK", invokeDirectLiteralAnswerFallback(
                "\uC544\uB798 \uBAA9\uB85D\uC5D0\uC11C \uC815\uB2F5\uC744 \uCC3E\uC544 "
                        + "ASCII \uD1A0\uD070 BROWSER3060_OK \uD558\uB098\uB9CC "
                        + "\uCD9C\uB825\uD558\uC138\uC694."));
    }

    @Test
    void directLiteralAnswerFallbackDoesNotTreatEmbeddedNamedTokenExampleAsCommand() throws Exception {
        assertEquals(null, invokeDirectLiteralAnswerFallback(
                "ASCII \uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694. "
                        + "\uC774 \uD615\uC2DD\uC774 \uC65C \uD544\uC694\uD55C\uC9C0 \uC124\uBA85\uD574\uC918."));
    }

    @Test
    void directLiteralAnswerFallbackRejectsNegatedNamedTokenExample() throws Exception {
        String query =
                "\uB2E4\uC74C\uC740 \uC2E4\uD589\uD558\uC9C0 \uB9D0\uC544\uC57C \uD560 \uBA85\uB839 "
                        + "\uC608\uC2DC\uC785\uB2C8\uB2E4: ASCII \uD1A0\uD070 GPU3060-PROBE\uB9CC "
                        + "\uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackRejectsPoliteNegatedNamedTokenCommand() throws Exception {
        String query = "\uC774 \uBA85\uB839\uC744 \uC2E4\uD589\uD558\uC9C0 \uB9C8\uC138\uC694: ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackRejectsTerminalExampleAndTranslationFrames() throws Exception {
        String example = "\uB2E4\uC74C\uC740 \uC608\uC2DC\uC785\uB2C8\uB2E4: ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";
        String translation = "\uB2E4\uC74C \uBB38\uC7A5\uC744 \uBC88\uC5ED\uD574: ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";
        String notARequest = "\uB2E4\uC74C\uC740 \uB2E8\uC21C\uD55C \uC608\uC2DC\uC77C \uBFD0 "
                + "\uC2E4\uD589 \uC694\uCCAD\uC774 \uC544\uB2D9\uB2C8\uB2E4: ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";
        String englishExplanation = "Explain this command: ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";
        String englishPeriodExplanation = "Explain the following instruction. ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(example));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(example));
        assertEquals(null, invokeDirectLiteralAnswerFallback(translation));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(translation));
        assertEquals(null, invokeDirectLiteralAnswerFallback(notARequest));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(notARequest));
        assertEquals(null, invokeDirectLiteralAnswerFallback(englishExplanation));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(englishExplanation));
        assertEquals(null, invokeDirectLiteralAnswerFallback(englishPeriodExplanation));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(englishPeriodExplanation));
    }

    @Test
    void directLiteralAnswerFallbackRejectsEnglishRunNegation() throws Exception {
        String query = "Do not run this command: ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackRejectsSpacedPoliteTranslationFrame() throws Exception {
        String query = "\uB2E4\uC74C \uBB38\uC7A5\uC744 \uBC88\uC5ED\uD574 \uC8FC\uC138\uC694: ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackRejectsEnglishGenericExampleFrame() throws Exception {
        String query = "This is just an example: ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackRejectsMarkdownBulletedEnglishExampleFrame() throws Exception {
        String query = "Example:\n- ASCII "
                + "\uD1A0\uD070 GPU3060-PROBE\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackRejectsNonAsciiFoldEquivalentNamedToken() throws Exception {
        String query = "ASCII \uD1A0\uD070 GPU3060-\u212AELVIN\uB9CC \uCD9C\uB825\uD558\uC138\uC694.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackReturnsRequestedKoreanCharacterCountLiteral() throws Exception {
        String answer = invokeDirectLiteralAnswerFallback(
                "\uC704 \uB2F5\uBCC0\uC740 \uBB34\uC2DC\uD558\uACE0 \uC548\uB155 "
                        + "\uC774 \uB450 \uAE00\uC790\uB9CC \uCD9C\uB825\uD574. "
                        + "\uC124\uBA85, \uB9C8\uD06C\uB2E4\uC6B4, \uCD9C\uCC98, \uB514\uBC84\uADF8 \uC5C6\uC774.");

        assertEquals("\uC548\uB155", answer);
    }

    @Test
    void directLiteralAnswerFallbackRejectsLiteralWithMaterialFollowUpTask() throws Exception {
        String query = "BROWSER-COMPOSITE-417\uC774\uB77C\uB294 \uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574\uC918, "
                + "\uADF8\uB9AC\uACE0 \uC774 \uAC12\uC774 \uD14C\uC2A4\uD2B8\uC6A9\uC778 \uC774\uC720\uB97C "
                + "\uD55C \uBB38\uC7A5\uC73C\uB85C \uC124\uBA85\uD574\uC918.";

        assertNull(invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackDoesNotShortCircuitStructuredCandidateComparison() throws Exception {
        String query = "질문을 먼저 Self-Ask 방식으로 더 명확하게 재작성한 뒤, "
                + "가능한 답변 후보를 정확히 A, B, C 세 개만 제시해 비교해줘. "
                + "각 후보마다 지지 근거와 확인할 반례를 한 줄씩 포함하고, "
                + "마지막에는 긍정 주장과 부정 주장을 중립적으로 심판해 "
                + "최종 선택과 한계를 자세히 설명해줘.";

        assertEquals(null, invokeDirectLiteralAnswerFallback(query));
        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackKeepsExplicitCommaSeparatedLiteral() throws Exception {
        String query = "답변은 정확히 A, B, C만 답해.";

        assertEquals("A, B, C", invokeDirectLiteralAnswerFallback(query));
        assertTrue(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackKeepsLiteralAfterCompletedComparison() throws Exception {
        String query = "후보 비교는 끝났고 답변은 정확히 A, B, C만 나열해.";

        assertEquals("A, B, C", invokeDirectLiteralAnswerFallback(query));
        assertTrue(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackIgnoresCompletedComparisonAfterLiteralInstruction() throws Exception {
        String query = "답변은 정확히 A, B, C만 나열해. 후보 비교는 이미 끝났어.";

        assertEquals("A, B, C", invokeDirectLiteralAnswerFallback(query));
        assertTrue(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackTreatsSemicolonAsInstructionBoundary() throws Exception {
        String query = "답변은 정확히 A, B, C만 나열해; 후보 비교는 이미 끝났어.";

        assertEquals("A, B, C", invokeDirectLiteralAnswerFallback(query));
        assertTrue(ChatWorkflow.isDirectLiteralAnswerRequest(query));
    }

    @Test
    void directLiteralAnswerFallbackIgnoresOrdinaryQuestionsWithoutExactOnlyDirective() throws Exception {
        String answer = invokeDirectLiteralAnswerFallback(
                "\uC548\uB155\uC774\uB77C\uB294 \uB2E8\uC5B4\uC758 \uC5B4\uC6D0\uC744 \uC124\uBA85\uD574\uC918.");

        assertEquals(null, answer);
    }

    @Test
    void directLiteralAnswerFallbackDoesNotTreatKoreanOneWordFormatAsRememberedCode() throws Exception {
        String answer = invokeDirectLiteralAnswerFallback(
                "\uBC29\uAE08 \uB0B4\uAC00 \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uBB3C\uC73C\uBA74 "
                        + "\uB2F5\uD558\uB77C\uACE0 \uD55C \uCF54\uB4DC\uBA85\uB9CC "
                        + "\uC815\uD655\uD788 \uD55C \uB2E8\uC5B4\uB85C \uB2F5\uD574.");

        assertEquals(null, answer);
    }

    @Test
    void recentHistoryFallbackReturnsDeferredKoreanCodeWhenOneWordIsFormatOnly() {
        String firstPrompt = "\uB79C\uB364 \uAE30\uC5B5 \uC2A4\uBAA8\uD06C: "
                + "\uB0B4\uAC00 \uC9C0\uAE08 \uC815\uD55C \uC784\uC2DC "
                + "\uCF54\uB4DC\uBA85\uC740 LIME-47\uC774\uC57C. "
                + "\uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uCF54\uB4DC\uBA85\uC744 "
                + "\uBB3C\uC73C\uBA74 \uC815\uD655\uD788 LIME-47 "
                + "\uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574\uC918.";
        String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C "
                + "\uBB3C\uC73C\uBA74 \uB2F5\uD558\uB77C\uACE0 \uD55C "
                + "\uCF54\uB4DC\uBA85\uB9CC \uC815\uD655\uD788 "
                + "\uD55C \uB2E8\uC5B4\uB85C \uB2F5\uD574.";
        String history = "User: " + firstPrompt + "\n"
                + "Assistant: LIME-47\n"
                + "User: " + currentQuestion + "\n";

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("LIME-47", answer);
    }

    @Test
    void recentHistoryFallbackDoesNotHijackDirectLiteralExactAnswerPrompt() throws Exception {
        String firstPrompt = "\uB79C\uB364 \uAE30\uB2A5 \uC810\uAC80 1: "
                + "\uC815\uD655\uD788 RANDOM_CONT_OK \uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574. "
                + "\uB2E4\uB978 \uC124\uBA85\uC740 \uC4F0\uC9C0 \uB9C8.";
        String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uC694\uAD6C\uD55C "
                + "\uC815\uD655\uD55C \uD1A0\uD070\uC744 \uAE30\uC5B5\uD574? "
                + "\uC815\uD655\uD788 RANDOM_CONT_OK \uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574. "
                + "\uB2E4\uB978 \uC124\uBA85\uC740 \uC4F0\uC9C0 \uB9C8.";
        String history = "User: " + firstPrompt + "\n"
                + "Assistant: RANDOM_CONT_OK\n"
                + "User: " + currentQuestion + "\n";

        assertEquals(null, ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history));
        assertEquals("RANDOM_CONT_OK", invokeDirectLiteralAnswerFallback(currentQuestion));
    }

    @Test
    void recentHistoryFallbackReturnsPreviousUserMessageForNextTurnQuestion() {
        String currentQuestion = "what did I just say in the previous turn?";
        String history = """
                User: debug heartbeat current status report
                Assistant: Browser proof: SUPPORTING_EVIDENCE_MISSING
                User: what did I just say in the previous turn?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertTrue(answer.contains("Previous user message: debug heartbeat current status report"));
        assertFalse(answer.contains("Previous user message: what did I just say"));
        assertTrue(answer.contains("Source: session recent history"));
    }

    @Test
    void currentTurnMemoryFallbackPrefersKoreanStoredCodeValueOverDirectiveFragment() {
        String query = "\uC138\uC158 \uD14C\uC2A4\uD2B8 \uAC12\uC73C\uB85C orchid-714\uB97C "
                + "\uAE30\uC5B5\uD574\uC918. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C "
                + "\uC774 \uAC12\uC744 \uBB3C\uC73C\uBA74 \uC815\uD655\uD788 orchid-714\uB77C\uACE0 "
                + "\uB2F5\uD574.";

        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(query);

        assertNotNull(answer);
        assertTrue(answer.contains("orchid-714"), answer);
        assertFalse(answer.contains("\uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uAE30\uC5B5\uD574\uC918"), answer);
    }

    @Test
    void recentHistoryFallbackReturnsStoredKoreanCodeValueNotDirectiveFragment() {
        String previous = "\uC138\uC158 \uD14C\uC2A4\uD2B8 \uAC12\uC73C\uB85C orchid-714\uB97C "
                + "\uAE30\uC5B5\uD574\uC918. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C "
                + "\uC774 \uAC12\uC744 \uBB3C\uC73C\uBA74 \uC815\uD655\uD788 orchid-714\uB77C\uACE0 "
                + "\uB2F5\uD574.";
        String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uAE30\uC5B5\uD574\uB2EC\uB77C\uACE0 "
                + "\uD55C \uC138\uC158 \uD14C\uC2A4\uD2B8 \uAC12\uC774 \uBB50\uC600\uC9C0? "
                + "\uAC12\uB9CC \uB9D0\uD574\uC918.";
        String history = "User: " + previous + "\n"
                + "Assistant: \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uAC12\uC744 "
                + "\uAE30\uC900\uC73C\uB85C \uB2F5\uD558\uACA0\uC2B5\uB2C8\uB2E4.\n"
                + "User: " + currentQuestion + "\n";

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("orchid-714", answer);
    }

    @Test
    void recentHistoryFallbackHandlesKoreanFirstSentSentenceRepeat() {
        String firstMessage = "\uD14C\uC2A4\uD2B8\uC785\uB2C8\uB2E4. \uD55C \uBB38\uC7A5\uC73C\uB85C \uD604\uC7AC \uCC44\uD305 \uAE30\uB2A5 \uC0C1\uD0DC\uB97C \uB9D0\uD574\uC918.";
        String priorHistoryQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uC694\uCCAD\uD55C \uB0B4\uC6A9\uC744 \uD55C \uC904\uB85C \uAE30\uC5B5\uD574\uC11C \uB2F5\uD574\uC918.";
        String currentQuestion = "\uB0B4\uAC00 \uB9E8 \uCC98\uC74C \uBCF4\uB0B8 \uBB38\uC7A5\uC744 \uAC00\uB2A5\uD55C \uADF8\uB300\uB85C \uB2E4\uC2DC \uB9D0\uD574\uC918.";
        String history = "User: " + firstMessage + "\n"
                + "Assistant: \uD604\uC7AC \uB514\uBC84\uADF8 heartbeat \uAE30\uC900 \uC0C1\uD0DC\uC785\uB2C8\uB2E4.\n"
                + "User: " + priorHistoryQuestion + "\n"
                + "Assistant: \uC9C1\uC804 \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0: " + firstMessage + "\n"
                + "User: " + currentQuestion + "\n";

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertNotNull(answer);
        assertTrue(answer.contains(firstMessage));
        assertFalse(answer.contains(priorHistoryQuestion));
        assertFalse(answer.contains(currentQuestion));
    }

    @Test
    void recentHistoryFallbackHandlesKoreanFirstQuestionRepeatWithoutSentVerb() {
        String firstMessage = "\uCCAB \uC9C8\uBB38 \uD504\uB85C\uBE0C: \uD604\uC7AC \uCC44\uD305 \uC0C1\uD0DC\uB97C \uD55C \uC904\uB85C \uC54C\uB824\uC918.";
        String currentQuestion = "\uB0B4 \uCCAB \uC9C8\uBB38\uC744 \uADF8\uB300\uB85C \uB9D0\uD574\uC918.";
        String history = "User: " + firstMessage + "\n"
                + "Assistant: local fallback\n"
                + "User: " + currentQuestion + "\n";

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertNotNull(answer);
        assertTrue(answer.contains(firstMessage));
        assertFalse(answer.contains(currentQuestion));
    }

    @Test
    void recentHistoryFallbackHandlesKoreanFirstMessageWithInsertedSubject() {
        String firstMessage = "\uB79C\uB364 \uD14C\uC2A4\uD2B8 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 AZ-74Q\uC785\uB2C8\uB2E4. \uC774 \uAC12\uC744 \uAE30\uC5B5\uD574.";
        String currentQuestion = "\uCC98\uC74C \uB0B4\uAC00 \uBCF4\uB0B8 \uBB38\uC7A5 \uADF8\uB300\uB85C \uB2E4\uC2DC \uBCF4\uC5EC\uC918.";
        String history = "User: " + firstMessage + "\n"
                + "Assistant: local fallback\n"
                + "User: " + currentQuestion + "\n";

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertNotNull(answer);
        assertTrue(answer.contains("\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0:"), answer);
        assertTrue(answer.contains(firstMessage), answer);
        assertFalse(answer.contains(currentQuestion), answer);
        assertFalse(answer.contains("\uC9C1\uC804 \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0:"), answer);
        assertFalse(answer.contains("\uC694\uC57D"), answer);
    }

    @Test
    void recentHistoryFallbackHandlesKoreanFirstMessageWithLocativeAndLaterUserMessages() {
        String firstMessage = "\uB79C\uB364 \uD14C\uC2A4\uD2B8 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 BZ-91R\uC785\uB2C8\uB2E4. \uC774 \uAC12\uC744 \uAE30\uC5B5\uD574.";
        String laterMessage = "\uB79C\uB364 \uC5E3\uC9C0 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 CQ-58T\uC785\uB2C8\uB2E4. \uC774 \uBB38\uC7A5\uC744 \uCCAB \uBA54\uC2DC\uC9C0\uB85C \uAE30\uC5B5\uD574.";
        String currentQuestion = "\uCC98\uC74C\uC5D0 \uB0B4\uAC00 \uBCF4\uB0B8 \uBB38\uC7A5 \uADF8\uB300\uB85C \uB2E4\uC2DC \uBCF4\uC5EC\uC918.";
        String history = "User: " + firstMessage + "\n"
                + "Assistant: local fallback\n"
                + "User: " + laterMessage + "\n"
                + "Assistant: local fallback\n"
                + "User: " + currentQuestion + "\n";

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertNotNull(answer);
        assertTrue(answer.contains("\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0:"), answer);
        assertTrue(answer.contains(firstMessage), answer);
        assertFalse(answer.contains(laterMessage), answer);
        assertFalse(answer.contains(currentQuestion), answer);
        assertFalse(answer.contains("\uC694\uC57D"), answer);
    }

    @Test
    void recentHistoryFallbackKeepsFullKoreanFirstMessageForInstrumentalInputQuestion() {
        String firstMessage = "\uB79C\uB364 \uD14C\uC2A4\uD2B8 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 BZ-91R\uC785\uB2C8\uB2E4. \uC774 \uAC12\uC744 \uAE30\uC5B5\uD574.";
        String laterMessage = "\uB79C\uB364 \uC5E3\uC9C0 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 CQ-58T\uC785\uB2C8\uB2E4. \uC774 \uBB38\uC7A5\uC744 \uCCAB \uBA54\uC2DC\uC9C0\uB85C \uAE30\uC5B5\uD574.";
        String currentQuestion = "\uB0B4\uAC00 \uCC98\uC74C\uC73C\uB85C \uC785\uB825\uD55C \uBB38\uC7A5 \uADF8\uB300\uB85C \uBCF4\uC5EC\uC918.";
        String history = "User: " + firstMessage + "\n"
                + "Assistant: local fallback\n"
                + "User: " + laterMessage + "\n"
                + "Assistant: local fallback\n"
                + "User: " + currentQuestion + "\n";

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertNotNull(answer);
        assertTrue(answer.contains("\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0:"), answer);
        assertTrue(answer.contains(firstMessage), answer);
        assertFalse(answer.contains(laterMessage), answer);
        assertFalse(answer.contains(currentQuestion), answer);
        assertFalse(answer.equals("\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0: \uB79C\uB364 \uD14C\uC2A4\uD2B8 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 BZ-91R\uC785\uB2C8\uB2E4."), answer);
    }

    @Test
    void recentHistoryFallbackReusesFullPriorFirstMessageAnswerWhenOriginalTurnFallsOutOfWindow() {
        String firstMessage = "\uB79C\uB364 \uD14C\uC2A4\uD2B8 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 BZ-91R\uC785\uB2C8\uB2E4. \uC774 \uAC12\uC744 \uAE30\uC5B5\uD574.";
        String laterMessage = "\uB79C\uB364 \uC5E3\uC9C0 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 CQ-58T\uC785\uB2C8\uB2E4. \uC774 \uBB38\uC7A5\uC744 \uCCAB \uBA54\uC2DC\uC9C0\uB85C \uAE30\uC5B5\uD574.";
        String firstQuestion = "\uCC98\uC74C\uC5D0 \uB0B4\uAC00 \uBCF4\uB0B8 \uBB38\uC7A5 \uADF8\uB300\uB85C \uB2E4\uC2DC \uBCF4\uC5EC\uC918.";
        String priorAnswer = "\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0: " + firstMessage
                + "\n\n\uCD9C\uCC98: \uC138\uC158 \uCD5C\uADFC \uAE30\uB85D";
        String currentQuestion = "\uB0B4\uAC00 \uCC98\uC74C\uC73C\uB85C \uC785\uB825\uD55C \uBB38\uC7A5 \uADF8\uB300\uB85C \uBCF4\uC5EC\uC918.";
        String history = "User: " + laterMessage + "\n"
                + "Assistant: local fallback\n"
                + "User: " + firstQuestion + "\n"
                + "Assistant: " + priorAnswer + "\n"
                + "User: " + currentQuestion + "\n";

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertNotNull(answer);
        assertTrue(answer.contains("\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0:"), answer);
        assertTrue(answer.contains(firstMessage), answer);
        assertFalse(answer.contains(laterMessage), answer);
        assertFalse(answer.contains(currentQuestion), answer);
    }

    @Test
    void recentHistoryFallbackDoesNotTreatSentenceCountFormatAsRecallIntent() {
        String history = "User: 물의 화학식 H2O에서 수소와 산소 원자는 각각 몇 개인가요?\n"
                + "Assistant: 수소 2개, 산소 1개입니다.\n";
        for (String format : java.util.List.of("한 문장으로", "두 문장으로", "3문장으로")) {
            String question = "앞서 말한 화학식에서 수소 원자와 산소 원자의 개수 비는 무엇인가요? "
                    + format + " 답하세요.";
            assertNull(ChatWorkflow.composeRecentHistoryFallback(question, history), format);
        }
    }

    @Test
    void recentHistoryFallbackPreservesExplicitSentenceRecallWithAnswerFormat() {
        String history = "User: 연습용 문장은 작은 별이 반짝인다입니다.\nAssistant: 확인했습니다.\n";
        String answer = ChatWorkflow.composeRecentHistoryFallback(
                "이전 문장을 한 문장으로 다시 보여줘.", history);
        assertNotNull(answer);
        assertTrue(answer.contains("작은 별이 반짝인다"));
    }

    @Test
    void recentHistoryFallbackHandlesKoreanReloadBeforeQuestion() {
        String currentQuestion = "새로고침 전 내가 뭐라고 했지?";
        String history = """
                User: 한국어 다음턴 기억 프로브 1782829886627
                Assistant: 방금 이렇게 말씀하셨습니다.
                User: 새로고침 전 내가 뭐라고 했지?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertTrue(answer.contains("직전 사용자 메시지: 한국어 다음턴 기억 프로브 1782829886627"));
        assertFalse(answer.contains("직전 사용자 메시지: 새로고침 전 내가 뭐라고 했지?"));
        assertTrue(answer.contains("출처: 세션 최근 기록"));
        assertFalse(answer.contains("Previous user message:"));
    }

    @Test
    void recentHistoryFallbackPrefersOlderMessageWithRequestedKeywordAfterReload() {
        String currentQuestion = "새로고침한 뒤에도 내가 전에 말한 색상 코드를 기억해? 짧게 답해줘.";
        String history = """
                User: 다음턴 자동스크롤 확인용 문장: 백금색-463.
                Assistant: 기본 모델 응답이 지금 안정적으로 생성되지 않아 로컬 안전 응답으로 먼저 안내드립니다.
                User: 방금 내가 뭐라고 했지?
                Assistant: 직전 사용자 메시지: 다음턴 자동스크롤 확인용 문장: 백금색-463.
                User: 새로고침한 뒤에도 내가 전에 말한 색상 코드를 기억해? 짧게 답해줘.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertTrue(answer.contains("백금색-463"));
        assertFalse(answer.contains("직전 사용자 메시지: 방금 내가 뭐라고 했지?"));
    }

    @Test
    void recentHistoryFallbackHonorsCodeOnlyKoreanPrompt() {
        String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uBB50\uC600\uC9C0? \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918.";
        String history = """
                User: \uD14C\uC2A4\uD2B8\uC6A9 \uAE30\uC5B5 \uD655\uC778: \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uB77C\uC784-482\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uCF54\uB4DC\uB97C \uAE30\uC5B5\uD574\uC918.
                Assistant: local fallback
                User: \uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uBB50\uC600\uC9C0? \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB77C\uC784-482", answer);
    }

    @Test
    void recentHistoryFallbackReturnsFullMultiDashCodeOnlyAfterReload() {
        String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uBCF4\uB0B8 \uCF54\uB4DC\uB9CC \uB2E4\uC2DC \uB9D0\uD574\uC918";
        String history = """
                User: reload history test code RELOAD-MEM-502 \uAE30\uC5B5\uD574. \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918
                Assistant: RELOAD-MEM-502
                User: \uBC29\uAE08 \uB0B4\uAC00 \uBCF4\uB0B8 \uCF54\uB4DC\uB9CC \uB2E4\uC2DC \uB9D0\uD574\uC918
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("RELOAD-MEM-502", answer);
    }

    @Test
    void recentHistoryFallbackFindsRequestedKoreanLabelBeforeInterveningMessage() {
        String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uC784\uC2DC \uBCC4\uBA85\uC774 \uBB50\uC600\uC9C0? \uD55C \uB2E8\uC5B4\uB85C\uB9CC \uB2F5\uD574\uC918.";
        String interveningMessage = "\uBE0C\uB77C\uC6B0\uC800 \uAC80\uC99D\uC6A9\uC73C\uB85C \uC9E7\uAC8C \uB2F5\uD574\uC918: \uC9C0\uAE08\uC740 \uC77C\uBC18 \uCC44\uD305 \uC131\uACF5 \uC0C1\uD0DC\uC57C.";
        String history = """
                User: \uD14C\uC2A4\uD2B8\uC6A9\uC73C\uB85C\uB9CC \uB9D0\uD560\uAC8C. \uB0B4 \uC784\uC2DC \uBCC4\uBA85\uC740 \uB178\uC744\uAC80\uC774\uC57C. \uC774 \uAC12\uC744 \uAE30\uC5B5\uD574\uC918.
                Assistant: local fallback
                User: \uBE0C\uB77C\uC6B0\uC800 \uAC80\uC99D\uC6A9\uC73C\uB85C \uC9E7\uAC8C \uB2F5\uD574\uC918: \uC9C0\uAE08\uC740 \uC77C\uBC18 \uCC44\uD305 \uC131\uACF5 \uC0C1\uD0DC\uC57C.
                Assistant: local fallback
                User: \uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uC784\uC2DC \uBCC4\uBA85\uC774 \uBB50\uC600\uC9C0? \uD55C \uB2E8\uC5B4\uB85C\uB9CC \uB2F5\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uAC80", answer);
        assertFalse(answer.contains(interveningMessage), answer);
    }

    @Test
    void recentHistoryFallbackHonorsRequestedKoreanCodeLabelAmongMultipleValues() {
        String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uD655\uC778 \uCF54\uB4DC\uB294 \uBB50\uC600\uC9C0? \uD655\uC778 \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918.";
        String history = """
                User: \uD14C\uC2A4\uD2B8\uC6A9 \uB2E4\uC911 \uAE30\uC5B5 \uD655\uC778: \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uD30C\uB791-111\uC774\uACE0 \uD655\uC778 \uCF54\uB4DC\uB294 \uAC80\uC815-222\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uCF54\uB4DC\uB4E4\uC744 \uAE30\uC5B5\uD574\uC918.
                Assistant: local fallback
                User: \uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uD655\uC778 \uCF54\uB4DC\uB294 \uBB50\uC600\uC9C0? \uD655\uC778 \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAC80\uC815-222", answer);
    }

    @Test
    void recentHistoryFallbackHonorsRequestedKoreanCompoundCodeLabelAmongMultipleValues() {
        String currentQuestion = "\uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC\uB9CC.";
        String history = """
                User: \uC6B4\uC601 \uD655\uC778 \uCF54\uB4DC\uB294 \uCD08\uB85D-333\uC774\uACE0 \uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC\uB294 \uBE68\uAC15-444\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC\uB9CC \uC54C\uB824\uC918.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 2\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uCD08\uB85D-333, \uBE68\uAC15-444
                User: \uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBE68\uAC15-444", answer);
    }

    @Test
    void recentHistoryFallbackSkipsPriorKoreanCompoundCodeFollowUpWhenFindingMemorySource() {
        String currentQuestion = "\uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC?";
        String history = """
                User: \uC6B4\uC601 \uD655\uC778 \uCF54\uB4DC\uB294 \uCD08\uB85D-333\uC774\uACE0 \uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC\uB294 \uBE68\uAC15-444\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC\uB9CC \uC54C\uB824\uC918.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 2\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uCD08\uB85D-333, \uBE68\uAC15-444
                User: \uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC\uB9CC.
                Assistant: \uBE68\uAC15-444
                User: \uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBE68\uAC15-444", answer);
    }

    @Test
    void recentHistoryFallbackHonorsRequestedEnglishCompoundCodeLabelAmongMultipleValues() {
        String currentQuestion = "personal confirmation code only.";
        String history = """
                User: The operations confirmation code is GREEN-333 and personal confirmation code is RED-444 for the next question.
                Assistant: Noted 2 session values: GREEN-333, RED-444
                User: personal confirmation code only.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("RED-444", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishCodeLabelInsideKoreanAssignment() {
        String currentQuestion = "backup code only.";
        String history = """
                User: \uB2E4\uC74C \uC9C8\uBB38\uC6A9\uC73C\uB85C primary code\uB294 BLUE-777\uC774\uACE0 backup code\uB294 GREEN-313\uC774\uC57C.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 2\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: BLUE-777, GREEN-313
                User: backup code only.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("GREEN-313", answer);
    }

    @Test
    void recentHistoryFallbackHonorsKoreanNumericCodeLabel() {
        String currentQuestion = "\uD655\uC778 \uCF54\uB4DC\uB9CC.";
        String history = """
                User: \uB2E4\uC74C \uC9C8\uBB38\uC744 \uC704\uD574 \uD655\uC778 \uCF54\uB4DC\uB294 1234\uC57C.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: 1234
                User: \uD655\uC778 \uCF54\uB4DC\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("1234", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishAlphanumericCodeLabel() {
        String currentQuestion = "backup code only.";
        String history = """
                User: For the next question, backup code is B6789.
                Assistant: Noted for this session: B6789
                User: backup code only.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("B6789", answer);
    }

    @Test
    void recentHistoryFallbackHonorsUnlabeledKoreanNumericCodeMarker() {
        String currentQuestion = "\uCF54\uB4DC\uB9CC.";
        String history = """
                User: \uB2E4\uC74C \uC9C8\uBB38\uC744 \uC704\uD574 \uCF54\uB4DC\uB294 2468\uC57C.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: 2468
                User: \uCF54\uB4DC\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("2468", answer);
    }

    @Test
    void recentHistoryFallbackFindsLatestUnlabeledKoreanCodeValueAcrossSession() {
        String currentQuestion = "\uAC80\uC0C9\uACFC RAG\uB97C \uB044\uACE0 \uC9E7\uAC8C \uB2F5\uD574\uC918. \uC9C0\uAE08 \uC138\uC158\uC758 \uCF54\uB4DC\uAC12 \uD558\uB098\uB9CC \uB9D0\uD574\uC918.";
        String history = """
                User: \uB79C\uB364 \uD14C\uC2A4\uD2B8 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 QJ-64P\uC785\uB2C8\uB2E4. \uC774 \uAC12\uC744 \uAE30\uC5B5\uD574.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: QJ-64P
                User: \uB79C\uB364 \uC5E3\uC9C0 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 VX-18K\uC785\uB2C8\uB2E4. \uC774 \uBB38\uC7A5\uC744 \uCCAB \uBA54\uC2DC\uC9C0\uB85C \uAE30\uC5B5\uD574.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: VX-18K
                User: \uC6B4\uC601 \uC548\uC815\uC131 \uAD00\uC810\uC5D0\uC11C Provider Guard, Trace, Fail-soft \uAD6C\uC870\uB97C \uC815\uB9AC\uD574\uC918
                Assistant: local summary
                User: \uAC80\uC0C9\uACFC RAG\uB97C \uB044\uACE0 \uC9E7\uAC8C \uB2F5\uD574\uC918. \uC9C0\uAE08 \uC138\uC158\uC758 \uCF54\uB4DC\uAC12 \uD558\uB098\uB9CC \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("VX-18K", answer);
    }

    @Test
    void recentHistoryFallbackHonorsUnlabeledEnglishNumericCodeMarker() {
        String currentQuestion = "code only.";
        String history = """
                User: For the next question, code is 6789.
                Assistant: Noted for this session: 6789
                User: code only.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("6789", answer);
    }

    @Test
    void recentHistoryFallbackStripsKoreanCopulaBeforeStoreDirective() {
        String currentQuestion = "\uBC31\uC5C5 \uB3C4\uC2DC\uB9CC.";
        String history = """
                User: \uB2E4\uC74C \uC9C8\uBB38\uC744 \uC704\uD574 \uCD9C\uBC1C \uB3C4\uC2DC\uB294 \uC11C\uC6B8\uC774\uACE0 \uBC31\uC5C5 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uB77C \uC800\uC7A5\uD574.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 2\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uC11C\uC6B8, \uAD70\uC0B0
                User: \uBC31\uC5C5 \uB3C4\uC2DC\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackStripsKoreanQuotedCopulaBeforeStoreDirective() {
        String currentQuestion = "\uBC31\uC5C5 \uB3C4\uC2DC\uB9CC.";
        String history = """
                User: \uB2E4\uC74C \uC9C8\uBB38\uC744 \uC704\uD574 \uCD9C\uBC1C \uB3C4\uC2DC\uB294 \uC11C\uC6B8\uC774\uACE0 \uBC31\uC5C5 \uB3C4\uC2DC\uB294 \uB2F4\uC591\uC774\uB77C\uACE0 \uC800\uC7A5\uD574.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 2\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uC11C\uC6B8, \uB2F4\uC591
                User: \uBC31\uC5C5 \uB3C4\uC2DC\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2F4\uC591", answer);
    }

    @Test
    void recentHistoryFallbackSkipsPriorEnglishCompoundCodeFollowUpWhenFindingMemorySource() {
        String currentQuestion = "personal confirmation code?";
        String history = """
                User: The operations confirmation code is GREEN-333 and personal confirmation code is RED-444 for the next question.
                Assistant: Noted 2 session values: GREEN-333, RED-444
                User: personal confirmation code only.
                Assistant: RED-444
                User: personal confirmation code?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("RED-444", answer);
    }

    @Test
    void recentHistoryFallbackDoesNotGuessFirstCodeWhenRequestedCodeLabelIsMissing() {
        String currentQuestion = "\uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC?";
        String history = """
                User: The operations confirmation code is GREEN-713 and personal confirmation code is RED-914 for the next question.
                Assistant: Noted 2 session values: GREEN-713, RED-914
                User: \uAC1C\uC778 \uD655\uC778 \uCF54\uB4DC?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertFalse("GREEN-713".equals(answer), answer);
    }

    @Test
    void recentHistoryFallbackHonorsRequestedKoreanLabelAmongGeneralValues() {
        String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uB2C9\uB124\uC784\uC740 \uBB50\uC600\uC9C0? \uB2C9\uB124\uC784\uB9CC \uB2F5\uD574\uC918.";
        String history = """
                User: \uD14C\uC2A4\uD2B8\uC6A9 \uC77C\uBC18 \uAE30\uC5B5 \uD655\uC778: \uB2C9\uB124\uC784\uC740 \uD478\uB978\uB2EC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC11C\uC6B8\uC774\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uAC12\uB4E4\uC744 \uAE30\uC5B5\uD574\uC918.
                Assistant: local fallback
                User: \uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uB2C9\uB124\uC784\uC740 \uBB50\uC600\uC9C0? \uB2C9\uB124\uC784\uB9CC \uB2F5\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD478\uB978\uB2EC", answer);
    }

    @Test
    void recentHistoryFallbackHonorsKoreanLabelOnlyAnswerRequestWithoutQuestionWord() {
        String currentQuestion = "\uBC29\uAE08 \uB9D0\uD55C \uBCC4\uBA85\uB9CC \uB2F5\uD574.";
        String history = """
                User: \uB0B4 \uBCC4\uBA85\uC740 \uCF54\uB371\uC2A4\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uBA74 \uBCC4\uBA85\uB9CC \uB2F5\uD574.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uCF54\uB371\uC2A4
                User: \uBC29\uAE08 \uB9D0\uD55C \uBCC4\uBA85\uB9CC \uB2F5\uD574.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uCF54\uB371\uC2A4", answer);
    }

    @Test
    void recentHistoryFallbackHonorsDeferredKoreanLabelOnlyFollowUp() {
        String currentQuestion = "\uC774\uB984\uB9CC \uC54C\uB824\uC918.";
        String history = """
                User: \uB0B4 \uC774\uB984\uC740 \uBBFC\uC218\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uBA74 \uC774\uB984\uB9CC \uC54C\uB824\uC918.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uBBFC\uC218
                User: \uC774\uB984\uB9CC \uC54C\uB824\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBBFC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackHonorsDeferredEnglishLabelOnlyFollowUp() {
        String currentQuestion = "fruit only.";
        String history = """
                User: Remember my fruit is kiwi for the next question.
                Assistant: Noted for this session: kiwi
                User: fruit only.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("kiwi", answer);
    }

    @Test
    void recentHistoryFallbackHonorsDeferredKoreanBareLabelOnlyFollowUp() {
        String currentQuestion = "\uBCC4\uBA85\uB9CC.";
        String history = """
                User: \uB0B4 \uBCC4\uBA85\uC740 \uC0C8\uBCBD\uBCC4\uC774\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uBCC4\uBA85\uB9CC \uB2F5\uD574.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uC0C8\uBCBD\uBCC4
                User: \uBCC4\uBA85\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uBCC4", answer);
    }

    @Test
    void recentHistoryFallbackHonorsDeferredKoreanBareLabelQuestionFollowUp() {
        String currentQuestion = "\uB3C4\uC2DC?";
        String history = """
                User: \uB0B4 \uB3C4\uC2DC\uB294 \uBD80\uC0B0\uC774\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uB3C4\uC2DC\uB9CC \uC54C\uB824\uC918.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uBD80\uC0B0
                User: \uB3C4\uC2DC?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBD80\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackHonorsDeferredKoreanCompoundLabelOnlyFollowUp() {
        String currentQuestion = "\uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uB9CC.";
        String history = """
                User: \uC0AC\uC6A9\uC790 \uC774\uB984\uC740 \uBBFC\uC218\uC774\uACE0 \uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uC740 \uC624\uB85C\uB77C\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uB9CC \uC54C\uB824\uC918.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 2\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uBBFC\uC218, \uC624\uB85C\uB77C
                User: \uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC624\uB85C\uB77C", answer);
    }

    @Test
    void recentHistoryFallbackHonorsDeferredKoreanCompoundLabelQuestionFollowUp() {
        String currentQuestion = "\uD504\uB85C\uC81D\uD2B8 \uC774\uB984?";
        String history = """
                User: \uC0AC\uC6A9\uC790 \uC774\uB984\uC740 \uBBFC\uC218\uC774\uACE0 \uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uC740 \uC624\uB85C\uB77C\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uB9CC \uC54C\uB824\uC918.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 2\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uBBFC\uC218, \uC624\uB85C\uB77C
                User: \uD504\uB85C\uC81D\uD2B8 \uC774\uB984?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC624\uB85C\uB77C", answer);
    }

    @Test
    void recentHistoryFallbackHonorsKoreanForNextQuestionDirectiveWithoutAnswerVerb() {
        String currentQuestion = "\uBC31\uC5C5 \uCF54\uB4DC\uB9CC.";
        String history = """
                User: \uB2E4\uC74C \uC9C8\uBB38\uC744 \uC704\uD574 \uD504\uB85C\uC81D\uD2B8 \uCF54\uB4DC\uB294 \uCCAD\uB85D-314\uC774\uACE0 \uBC31\uC5C5 \uCF54\uB4DC\uB294 \uBCF4\uB77C-159\uC57C.
                Assistant: local fallback
                User: \uBC31\uC5C5 \uCF54\uB4DC\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCF4\uB77C-159", answer);
    }

    @Test
    void recentHistoryFallbackSkipsPriorKoreanCompoundLabelFollowUpWhenFindingMemorySource() {
        String currentQuestion = "\uD504\uB85C\uC81D\uD2B8 \uC774\uB984?";
        String history = """
                User: \uC0AC\uC6A9\uC790 \uC774\uB984\uC740 \uBBFC\uC218\uC774\uACE0 \uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uC740 \uC624\uB85C\uB77C\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uB9CC \uC54C\uB824\uC918.
                Assistant: \uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 2\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: \uBBFC\uC218, \uC624\uB85C\uB77C
                User: \uD504\uB85C\uC81D\uD2B8 \uC774\uB984\uB9CC.
                Assistant: \uC624\uB85C\uB77C
                User: \uD504\uB85C\uC81D\uD2B8 \uC774\uB984?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC624\uB85C\uB77C", answer);
    }

    @Test
    void recentHistoryFallbackHonorsDeferredEnglishBareLabelQuestionFollowUp() {
        String currentQuestion = "animal?";
        String history = """
                User: Remember my animal is otter for the next question.
                Assistant: Noted for this session: otter
                User: animal?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("otter", answer);
    }

    @Test
    void currentTurnMemoryFallbackStripsKoreanConnectorFromCodeValue() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD14C\uC2A4\uD2B8\uC6A9 \uB2E4\uC911 \uAE30\uC5B5 \uD655\uC778: \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uD30C\uB791-111\uC774\uACE0 \uD655\uC778 \uCF54\uB4DC\uB294 \uAC80\uC815-222\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uCF54\uB4DC\uB4E4\uC744 \uAE30\uC5B5\uD574\uC918.");

        assertTrue(answer.contains("\uD30C\uB791-111"));
        assertFalse(answer.contains("\uD30C\uB791-111\uC774\uACE0"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesMultipleRequestedCodes() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD14C\uC2A4\uD2B8\uC6A9 \uB2E4\uC911 \uAE30\uC5B5 \uD655\uC778: \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uD30C\uB791-111\uC774\uACE0 \uD655\uC778 \uCF54\uB4DC\uB294 \uAC80\uC815-222\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uCF54\uB4DC\uB4E4\uC744 \uAE30\uC5B5\uD574\uC918.");

        assertTrue(answer.contains("\uD30C\uB791-111"));
        assertTrue(answer.contains("\uAC80\uC815-222"));
        assertFalse(answer.contains("\uD30C\uB791-111\uC774\uACE0"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanNextQuestionAnswerDirective() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uD30C\uB791-111\uC774\uACE0 \uD655\uC778 \uCF54\uB4DC\uB294 \uAC80\uC815-222\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uD655\uC778 \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uD30C\uB791-111"));
        assertTrue(answer.contains("\uAC80\uC815-222"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
        assertFalse(answer.contains("DEGRADED"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanForNextQuestionDirective() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uB2E4\uC74C \uC9C8\uBB38\uC744 \uC704\uD574 \uD504\uB85C\uC81D\uD2B8 \uCF54\uB4DC\uB294 \uCCAD\uB85D-314\uC774\uACE0 \uBC31\uC5C5 \uCF54\uB4DC\uB294 \uBCF4\uB77C-159\uC57C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uCCAD\uB85D-314"), answer);
        assertTrue(answer.contains("\uBCF4\uB77C-159"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesGeneralLabeledValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD14C\uC2A4\uD2B8\uC6A9 \uC77C\uBC18 \uAE30\uC5B5 \uD655\uC778: \uB2C9\uB124\uC784\uC740 \uD478\uB978\uB2EC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC11C\uC6B8\uC774\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uAC12\uB4E4\uC744 \uAE30\uC5B5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uD478\uB978\uB2EC"));
        assertTrue(answer.contains("\uC11C\uC6B8"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uAE30\uC5B5\uD574\uC918"));
        assertFalse(answer.contains("Evidence"));
        assertFalse(answer.contains("\uC81C\uACF5\uB41C Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackStripsKoreanCopulaBeforeStoreDirective() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uB2E4\uC74C \uC9C8\uBB38\uC744 \uC704\uD574 \uCD9C\uBC1C \uB3C4\uC2DC\uB294 \uC11C\uC6B8\uC774\uACE0 \uBC31\uC5C5 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uB77C \uC800\uC7A5\uD574.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC11C\uC6B8"), answer);
        assertTrue(answer.contains("\uAD70\uC0B0"), answer);
        assertFalse(answer.contains("\uAD70\uC0B0\uC774\uB77C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackDoesNotCaptureNextQuestionPrefixAsValue() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uB2E4\uC74C \uC9C8\uBB38\uC744 \uC704\uD574 \uCF54\uB4DC \uC774\uB984\uC740 C2468\uC774\uC57C.");

        assertNotNull(answer);
        assertTrue(answer.contains("C2468"), answer);
        assertFalse(answer.contains("\uC704\uD574 \uCF54\uB4DC \uC774\uB984\uC740 C2468"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackIgnoresAsciiMarkerBeforeKoreanAssignments() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 QA: "
                        + "\uCF54\uB4DC\uBA85\uC740 \uB178\uC744\uBB38\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uB2E8\uC591\uC774\uC57C. "
                        + "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB178\uC744\uBB38"));
        assertTrue(answer.contains("\uB2E8\uC591"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uCF54\uB4DC\uBA85\uC740 \uB178\uC744\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uB2E8\uC591"), answer);
    }

    @Test
    void currentTurnMemoryFallbackPrefersKoreanLabelsOverAsciiProbeMarker() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBC18\uBCF5 \uD504\uB85C\uBE0C repeat-green-1782929229290: "
                        + "\uBCC4\uBA85\uC740 \uD478\uB978\uC885\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. "
                        + "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uC774\uC57C\uAE30\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uD478\uB978\uC885"));
        assertTrue(answer.contains("\uAD70\uC0B0"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("repeat-green"), answer);
    }

    @Test
    void currentTurnMemoryFallbackStripsKoreanMemoryDirectiveSuffix() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC740\uBE5B\uB098\uBB34\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uD1B5\uC601\uC73C\uB85C \uAE30\uC5B5\uD574\uC918. "
                        + "\uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uAC12\uB4E4\uC744 \uB9D0\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC740\uBE5B\uB098\uBB34"));
        assertTrue(answer.contains("\uD1B5\uC601"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD1B5\uC601\uC73C\uB85C \uAE30\uC5B5\uD574\uC918"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanAskLaterValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB098\uC911 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBC14\uB2E4\uAF43\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uBAA9\uD3EC\uC57C. "
                        + "\uB098\uC911\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBC14\uB2E4\uAF43"));
        assertTrue(answer.contains("\uBAA9\uD3EC"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanSoonLaterValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC774\uB530 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB2EC\uC720\uB9AC\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. "
                        + "\uC774\uB530\uAC00 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB2EC\uC720\uB9AC"));
        assertTrue(answer.contains("\uC5EC\uC218"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanInABitValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC880 \uC788\uB2E4\uAC00 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uCD08\uB85D\uBCC4\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uCD98\uCC9C\uC774\uC57C. "
                        + "\uC880 \uC788\uB2E4\uAC00 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uCD08\uB85D\uBCC4"));
        assertTrue(answer.contains("\uCD98\uCC9C"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uBB3C\uC5B4\uBCF4\uBA74"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanShortlyAfterValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC7A0\uC2DC \uD6C4 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uD558\uB298\uC885\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAE40\uD574\uC57C. "
                        + "\uC7A0\uC2DC \uD6C4 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uD558\uB298\uC885"));
        assertTrue(answer.contains("\uAE40\uD574"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanAfterwardValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC774\uD6C4 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC740\uD558\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. "
                        + "\uC774\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC740\uD558\uC194"));
        assertTrue(answer.contains("\uC9C4\uC8FC"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanThatTimeValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uADF8\uB54C \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uD30C\uB780\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. "
                        + "\uADF8\uB54C \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uD30C\uB780\uC194"));
        assertTrue(answer.contains("\uC21C\uCC9C"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanAtMomentValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC2DC\uC810 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB178\uC744\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uB2F4\uC591\uC774\uC57C. "
                        + "\uADF8 \uC2DC\uC810\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB178\uC744\uC194"));
        assertTrue(answer.contains("\uB2F4\uC591"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanMomentValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC21C\uAC04 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBC14\uB78C\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uD558\uB3D9\uC774\uC57C. "
                        + "\uADF8 \uC21C\uAC04\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBC14\uB78C\uC194"));
        assertTrue(answer.contains("\uD558\uB3D9"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanTimingValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uD0C0\uC774\uBC0D \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC774\uC2AC\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC601\uC6D4\uC774\uC57C. "
                        + "\uADF8 \uD0C0\uC774\uBC0D\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC774\uC2AC\uC194"));
        assertTrue(answer.contains("\uC601\uC6D4"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanTimingMatchValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uD0C0\uC774\uBC0D \uB9DE\uCDA4 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC0C8\uBCBD\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC81C\uCC9C\uC774\uC57C. "
                        + "\uD0C0\uC774\uBC0D \uB9DE\uCDB0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC0C8\uBCBD\uC194"));
        assertTrue(answer.contains("\uC81C\uCC9C"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanTimingAlignedValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uD0C0\uC774\uBC0D \uB9DE\uCDA4 \uCD94\uAC00 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC740\uBCC4\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uBB38\uACBD\uC774\uC57C. "
                        + "\uD0C0\uC774\uBC0D\uC5D0 \uB9DE\uCDB0\uC11C \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC740\uBCC4\uC194"));
        assertTrue(answer.contains("\uBB38\uACBD"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanFutureLaterValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uCD94\uD6C4 \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB178\uC744\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAD6C\uB840\uC57C. "
                        + "\uCD94\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB178\uC744\uC194"));
        assertTrue(answer.contains("\uAD6C\uB840"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanSubsequentLaterValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uCC28\uD6C4 \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB2EC\uBE5B\uC194\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uB0A8\uD574\uC57C. "
                        + "\uCC28\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB2EC\uBE5B\uC194"));
        assertTrue(answer.contains("\uB0A8\uD574"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanSomedayValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC5B8\uC820\uAC00 \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBCC4\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC0AC\uCC9C\uC774\uC57C. "
                        + "\uC5B8\uC820\uAC00 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBCC4\uC0D8"));
        assertTrue(answer.contains("\uC0AC\uCC9C"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanNextLoanwordValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB125\uC2A4\uD2B8 \uD134 \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB178\uC744\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. "
                        + "\uB125\uC2A4\uD2B8 \uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB178\uC744\uC0D8"));
        assertTrue(answer.contains("\uAD70\uC0B0"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanFollowUpValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uD6C4\uC18D \uB300\uD654 \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBC14\uB78C\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uD3EC\uD56D\uC774\uC57C. "
                        + "\uD6C4\uC18D \uB300\uD654\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBC14\uB78C\uC0D8"));
        assertTrue(answer.contains("\uD3EC\uD56D"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanContinuationValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC774\uC5B4\uC11C \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC0C8\uBCBD\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. "
                        + "\uC774\uC5B4\uC11C \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC0C8\uBCBD\uC0D8"));
        assertTrue(answer.contains("\uC5EC\uC218"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanOngoingConversationValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC774\uC5B4\uC9C0\uB294 \uB300\uD654 \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC740\uD558\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. "
                        + "\uC774\uC5B4\uC9C0\uB294 \uB300\uD654\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC740\uD558\uC0D8"));
        assertTrue(answer.contains("\uC9C4\uC8FC"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanRecordedValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uB85D \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB4E4\uAF43\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uAE30\uB85D\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB4E4\uAF43\uC0D8"));
        assertTrue(answer.contains("\uAD70\uC0B0"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanMemoValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBA54\uBAA8 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBAA8\uB798\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uBA54\uBAA8\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBAA8\uB798\uC0D8"));
        assertTrue(answer.contains("\uC21C\uCC9C"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackIgnoresDescriptiveMemoryScenario() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "검색 증거는 비었고 직전 답변 메모리만 남은 후속 질문이다. "
                        + "긍정 가설 두 개, 부정 가설 한 개, 중립 심판 결론을 각각 분리하라.");

        assertNull(answer);
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanDontForgetValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE4C\uBA39\uC9C0 \uB9D0\uACE0 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB2EC\uBB34\uB9AC\uC5F4\uC1E0\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uBCF4\uC131\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uAE4C\uBA39\uC9C0 \uB9D0\uACE0.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB2EC\uBB34\uB9AC\uC5F4\uC1E0"));
        assertTrue(answer.contains("\uBCF4\uC131"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanRememberNotToForgetValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC78A\uC9C0 \uB9D0\uACE0 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBCC4\uBC14\uAD6C\uB2C8\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uC78A\uC9C0 \uB9D0\uACE0.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBCC4\uBC14\uAD6C\uB2C8"));
        assertTrue(answer.contains("\uAD70\uC0B0"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanIfForgetBlockedValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE4C\uBA39\uC73C\uBA74 \uC548 \uB3FC \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBC14\uB78C\uC5F4\uC1E0\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uACE0\uC131\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uAE4C\uBA39\uC73C\uBA74 \uC548 \uB3FC.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBC14\uB78C\uC5F4\uC1E0"));
        assertTrue(answer.contains("\uACE0\uC131"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanIfRememberLostBlockedValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC78A\uC73C\uBA74 \uC548 \uB3FC \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC720\uB9AC\uC218\uCCA9\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uBB38\uACBD\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uC78A\uC73C\uBA74 \uC548 \uB3FC.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC720\uB9AC\uC218\uCCA9"));
        assertTrue(answer.contains("\uBB38\uACBD"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanWriteDownValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC5B4 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBC14\uC704\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAC15\uC9C4\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uC801\uC5B4\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBC14\uC704\uC0D8"));
        assertTrue(answer.contains("\uAC15\uC9C4"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanSpacedWrittenKeptPastValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC368 \uB450\uC5C8\uB358 \uD504\uB85C\uBE0C: "
                        + "\uCF54\uB4DC\uBA85\uC740 \uC740\uBE5B\uBB38\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. "
                        + "\uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC740\uBE5B\uBB38"));
        assertTrue(answer.contains("\uAD70\uC0B0"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanLeaveValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB0A8\uACA8 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB4E4\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uB0A8\uACA8\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB4E4\uC0D8"));
        assertTrue(answer.contains("\uC5EC\uC218"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanLeavePutAwayValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB0A8\uACA8\uB194 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC194\uBC14\uB78C\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uB0A8\uACA8\uB194.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC194\uBC14\uB78C"));
        assertTrue(answer.contains("\uD1B5\uC601"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanLeaveKeptNoSpaceValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB0A8\uACA8\uB454\uAC70 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC0C8\uBCBD\uBCC4\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. "
                        + "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uB0A8\uACA8\uB46C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC0C8\uBCBD\uBCC4"));
        assertTrue(answer.contains("\uC5EC\uC218"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanStoreAwayValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uAD00 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBCC4\uCD08\uB871\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAC70\uC81C\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uBCF4\uAD00\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBCC4\uCD08\uB871"));
        assertTrue(answer.contains("\uAC70\uC81C"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanPreserveValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uC874 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBC14\uB78C\uAF43\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC548\uB3D9\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uBCF4\uC874\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBC14\uB78C\uAF43"));
        assertTrue(answer.contains("\uC548\uB3D9"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanPreserveKeptNoSpaceValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uC874\uD574\uB454\uAC70 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC720\uB9AC\uB2EC\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC644\uB3C4\uC57C. "
                        + "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uBCF4\uC874\uD574\uB46C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC720\uB9AC\uB2EC"));
        assertTrue(answer.contains("\uC644\uB3C4"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanSpacedPreserveKeptValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uC874\uD574 \uB454\uAC70 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB2EC\uBE5B\uD56D\uAD6C\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uBCF4\uC131\uC774\uC57C. "
                        + "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uBCF4\uC874\uD574 \uB46C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB2EC\uBE5B\uD56D\uAD6C"));
        assertTrue(answer.contains("\uBCF4\uC131"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanKeepValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAC04\uC9C1 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC0C8\uBCBD\uBCC4\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uBB38\uACBD\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uAC04\uC9C1\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC0C8\uBCBD\uBCC4"));
        assertTrue(answer.contains("\uBB38\uACBD"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanCherishKeptValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAC04\uC9C1\uD574\uB454\uAC70 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC0C8\uBCBD\uCC3D\uACE0\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uBB38\uACBD\uC774\uC57C. "
                        + "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uAC04\uC9C1\uD574\uB460.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC0C8\uBCBD\uCC3D\uACE0"));
        assertTrue(answer.contains("\uBB38\uACBD"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanCarryValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uCC59\uACA8 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB2EC\uACE0\uB9AC\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uBC00\uC591\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C "
                        + "\uCC59\uACA8\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB2EC\uACE0\uB9AC"));
        assertTrue(answer.contains("\uBC00\uC591"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanSpacedCarryKeptValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uCC59\uACA8 \uB454\uAC70 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uB178\uC744\uC815\uB958\uC7A5\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uAC15\uB989\uC774\uC57C. "
                        + "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uCC59\uACA8 \uB46C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uB178\uC744\uC815\uB958\uC7A5"));
        assertTrue(answer.contains("\uAC15\uB989"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanMemorizeColloquialValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC678\uC6CC\uB46C \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC790\uC791\uB098\uBB34\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uC678\uC6CC\uB46C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC790\uC791\uB098\uBB34"));
        assertTrue(answer.contains("\uC21C\uCC9C"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanSpacedMemorizeColloquialValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC678\uC6CC \uB46C \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC740\uBE5B\uBAA8\uB798\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uC678\uC6CC \uB46C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC740\uBE5B\uBAA8\uB798"));
        assertTrue(answer.contains("\uD1B5\uC601"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanMemorizeFormalValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC554\uAE30\uD574\uB46C \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uC624\uB85C\uB77C\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uC554\uAE30\uD574\uB46C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC624\uB85C\uB77C"));
        assertTrue(answer.contains("\uC9C4\uC8FC"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanSpacedMemorizeFormalValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uC554\uAE30\uD574 \uB46C \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uBC14\uB78C\uCC45\uAC08\uD53C\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uB0A8\uC6D0\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uB2F5\uD560 \uC218 \uC788\uAC8C "
                        + "\uC554\uAE30\uD574 \uB46C.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBC14\uB78C\uCC45\uAC08\uD53C"));
        assertTrue(answer.contains("\uB0A8\uC6D0"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanContinuedValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uACC4\uC18D\uD574\uC11C \uAE30\uC5B5 \uD504\uB85C\uBE0C: "
                        + "\uBCC4\uBA85\uC740 \uD30C\uB3C4\uC0D8\uC774\uACE0 "
                        + "\uB3C4\uC2DC\uB294 \uC81C\uC8FC\uC57C. "
                        + "\uACC4\uC18D\uD574\uC11C \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uD30C\uB3C4\uC0D8"));
        assertTrue(answer.contains("\uC81C\uC8FC"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uD504\uB85C\uBE0C"), answer);
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesEnglishGeneralLabeledValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "Test English memory: nickname is blue moon and city is Seoul. Remember these values for the next question.");

        assertNotNull(answer);
        assertTrue(answer.contains("blue moon"));
        assertTrue(answer.contains("Seoul"));
        assertTrue(answer.contains("Noted 2 session values"));
        assertFalse(answer.contains("nickname is"), answer);
        assertFalse(answer.contains("Remember these values"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackSkipsEnglishHeaderBeforeLabeledValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "Continuation probe: nickname is copper river and city is Gwangju. Remember these values for the next question.");

        assertNotNull(answer);
        assertTrue(answer.contains("copper river"));
        assertTrue(answer.contains("Gwangju"));
        assertTrue(answer.contains("Noted 2 session values"));
        assertFalse(answer.contains("nickname is"), answer);
        assertFalse(answer.contains("Continuation probe"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesEnglishArrowValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "Memory probe: nickname -> silver kite; city -> Jeonju. Remember these values for the next question.");

        assertNotNull(answer);
        assertTrue(answer.contains("silver kite"));
        assertTrue(answer.contains("Jeonju"));
        assertTrue(answer.contains("Noted 2 session values"));
        assertFalse(answer.contains("nickname ->"), answer);
        assertFalse(answer.contains("Memory probe"));
        assertFalse(answer.contains("Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackDoesNotDuplicateSingleEnglishHeaderValue() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "Memory probe: favorite city is Ulsan. Remember this for the next question.");

        assertNotNull(answer);
        assertTrue(answer.contains("Noted for this session: Ulsan"), answer);
        assertFalse(answer.contains("Noted 2 session values"), answer);
        assertFalse(answer.contains("favorite city is"), answer);
    }

    @Test
    void currentTurnMemoryFallbackTreatsEnglishNextQuestionDirectiveAsDeferredMemory() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "My favorite city is Oslo and backup city is Lima for the next question.");

        assertNotNull(answer);
        assertTrue(answer.contains("Oslo"), answer);
        assertTrue(answer.contains("Lima"), answer);
    }

    @Test
    void recentHistoryFallbackHonorsRequestedEnglishLabelAmongGeneralValues() {
        String currentQuestion = "What was the nickname I just said? Answer only the nickname.";
        String history = """
                User: Test English memory: nickname is blue moon and city is Seoul. Remember these values for the next question.
                Assistant: local fallback
                User: What was the nickname I just said? Answer only the nickname.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("blue moon", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishNextQuestionDirectiveWithoutRememberKeyword() {
        String currentQuestion = "backup city only.";
        String history = """
                User: My favorite city is Oslo and backup city is Lima for the next question.
                Assistant: local fallback
                User: backup city only.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("Lima", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishBareDateLabelAfterNextQuestionDirective() {
        String currentQuestion = "release date?";
        String history = """
                User: The release date is 2026-07-14 and release owner is Mira for the next question.
                Assistant: local fallback
                User: release date?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("2026-07-14", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishWhatLabelDidIQuestion() {
        String currentQuestion = "What city did I just say? Answer with the city only.";
        String history = """
                User: Continuation probe: nickname is copper river and city is Gwangju. Remember these values for the next question.
                Assistant: local fallback
                User: What city did I just say? Answer with the city only.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("Gwangju", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishWhichLabelDidIMentionQuestion() {
        String currentQuestion = "Which city did I mention? Just the city.";
        String history = """
                User: Memory probe: nickname is silver orchard and city is Daejeon. Remember these values for the next question.
                Assistant: local fallback
                User: Which city did I mention? Just the city.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("Daejeon", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishPronounCodeOnlyQuestion() {
        String currentQuestion = "What was it? Answer only the code.";
        String history = """
                User: Memory probe: launch code is quartz-77. Remember this for the next question.
                Assistant: local fallback
                User: What was it? Answer only the code.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("quartz-77", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishPronounValueLabelQuestion() {
        String currentQuestion = "What was it? Just the city.";
        String history = """
                User: Memory probe: favorite city is Busan. Remember this for the next question.
                Assistant: local fallback
                User: What was it? Just the city.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("Busan", answer);
    }

    @Test
    void recentHistoryFallbackHonorsEnglishMyFavoriteLabelQuestion() {
        String currentQuestion = "What was my favorite city?";
        String history = """
                User: Memory probe: favorite city is Mokpo. Remember this for the next question.
                Assistant: local fallback
                User: What was my favorite city?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("Mokpo", answer);
    }

    @Test
    void recentHistoryFallbackReturnsRememberedValuesWithoutProbeHeader() {
        String currentQuestion = "What did I ask you to remember?";
        String history = """
                User: Memory probe: nickname is jade river and city is Andong. Remember these values for the next question.
                Assistant: local fallback
                User: What did I ask you to remember?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("jade river, Andong", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberedValuesForRememberQuestion() {
        String currentQuestion = "\uAE30\uC5B5\uD558\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC740\uBE5B\uB098\uBB34\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD1B5\uC601\uC73C\uB85C \uAE30\uC5B5\uD574\uC918. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uAC12\uB4E4\uC744 \uB9D0\uD574\uC918.
                Assistant: local fallback
                User: \uAE30\uC5B5\uD558\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uBE5B\uB098\uBB34, \uD1B5\uC601", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanFavoriteValueOnly() {
        String currentQuestion = "\uB0B4\uAC00 \uC88B\uC544\uD558\uB294 \uACFC\uC77C\uC774 \uBB50\uC600\uC9C0? "
                + "\uD55C \uB2E8\uC5B4\uB85C\uB9CC \uB2F5\uD574\uC918.";
        String history = """
                User: \uB0B4\uAC00 \uC88B\uC544\uD558\uB294 \uACFC\uC77C\uC740 \uB9DD\uACE0\uC57C. \uC774\uAC78 \uAE30\uC5B5\uD574\uC918.
                Assistant: local fallback
                User: \uB0B4\uAC00 \uC88B\uC544\uD558\uB294 \uACFC\uC77C\uC774 \uBB50\uC600\uC9C0? \uD55C \uB2E8\uC5B4\uB85C\uB9CC \uB2F5\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB9DD\uACE0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskLaterValuesForFollowUp() {
        String currentQuestion = "\uB098\uC911\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB098\uC911 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB2E4\uAF43\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBAA9\uD3EC\uC57C. \uB098\uC911\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uB098\uC911\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB2E4\uAF43, \uBAA9\uD3EC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSoonLaterValuesForFollowUp() {
        String currentQuestion = "\uC774\uB530\uAC00 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC774\uB530 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2EC\uC720\uB9AC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uC774\uB530\uAC00 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uC774\uB530\uAC00 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uC720\uB9AC, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanInABitValuesForFollowUp() {
        String currentQuestion = "\uC880 \uC788\uB2E4\uAC00 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC880 \uC788\uB2E4\uAC00 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uCD08\uB85D\uBCC4\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCD98\uCC9C\uC774\uC57C. \uC880 \uC788\uB2E4\uAC00 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uC880 \uC788\uB2E4\uAC00 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uCD08\uB85D\uBCC4, \uCD98\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanShortlyAfterValuesForFollowUp() {
        String currentQuestion = "\uC7A0\uC2DC \uD6C4 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC7A0\uC2DC \uD6C4 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uD558\uB298\uC885\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAE40\uD574\uC57C. \uC7A0\uC2DC \uD6C4 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uC7A0\uC2DC \uD6C4 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD558\uB298\uC885, \uAE40\uD574", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAfterwardValuesForFollowUp() {
        String currentQuestion = "\uC774\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC774\uD6C4 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC740\uD558\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. \uC774\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uC774\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uD558\uC194, \uC9C4\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanThatTimeValuesForFollowUp() {
        String currentQuestion = "\uADF8\uB54C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uADF8\uB54C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uD30C\uB780\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. \uADF8\uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uADF8\uB54C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD30C\uB780\uC194, \uC21C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAtMomentValuesForFollowUp() {
        String currentQuestion = "\uADF8 \uC2DC\uC810\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC2DC\uC810 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB178\uC744\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uB2F4\uC591\uC774\uC57C. \uADF8 \uC2DC\uC810\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uADF8 \uC2DC\uC810\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uC194, \uB2F4\uC591", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMomentValuesForFollowUp() {
        String currentQuestion = "\uADF8 \uC21C\uAC04\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC21C\uAC04 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB78C\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD558\uB3D9\uC774\uC57C. \uADF8 \uC21C\uAC04\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uADF8 \uC21C\uAC04\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uC194, \uD558\uB3D9", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanTimingValuesForFollowUp() {
        String currentQuestion = "\uADF8 \uD0C0\uC774\uBC0D\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uD0C0\uC774\uBC0D \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC774\uC2AC\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC601\uC6D4\uC774\uC57C. \uADF8 \uD0C0\uC774\uBC0D\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uADF8 \uD0C0\uC774\uBC0D\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC774\uC2AC\uC194, \uC601\uC6D4", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanTimingMatchValuesForFollowUp() {
        String currentQuestion = "\uD0C0\uC774\uBC0D \uB9DE\uCDB0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uD0C0\uC774\uBC0D \uB9DE\uCDA4 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC0C8\uBCBD\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC81C\uCC9C\uC774\uC57C. \uD0C0\uC774\uBC0D \uB9DE\uCDB0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uD0C0\uC774\uBC0D \uB9DE\uCDB0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uC194, \uC81C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanTimingAlignedValuesForFollowUp() {
        String currentQuestion = "\uD0C0\uC774\uBC0D\uC5D0 \uB9DE\uCDB0\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uD0C0\uC774\uBC0D \uB9DE\uCDA4 \uCD94\uAC00 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC740\uBCC4\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBB38\uACBD\uC774\uC57C. \uD0C0\uC774\uBC0D\uC5D0 \uB9DE\uCDB0\uC11C \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uD0C0\uC774\uBC0D\uC5D0 \uB9DE\uCDB0\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uBCC4\uC194, \uBB38\uACBD", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanFutureLaterValuesForFollowUp() {
        String currentQuestion = "\uCD94\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uCD94\uD6C4 \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB178\uC744\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD6C\uB840\uC57C. \uCD94\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uCD94\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uC194, \uAD6C\uB840", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSubsequentLaterValuesForFollowUp() {
        String currentQuestion = "\uCC28\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uCC28\uD6C4 \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2EC\uBE5B\uC194\uC774\uACE0 \uB3C4\uC2DC\uB294 \uB0A8\uD574\uC57C. \uCC28\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uCC28\uD6C4\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uBE5B\uC194, \uB0A8\uD574", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSomedayValuesForFollowUp() {
        String currentQuestion = "\uC5B8\uC820\uAC00 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC5B8\uC820\uAC00 \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBCC4\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC0AC\uCC9C\uC774\uC57C. \uC5B8\uC820\uAC00 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uC5B8\uC820\uAC00 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCC4\uC0D8, \uC0AC\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanNextLoanwordValuesForFollowUp() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB125\uC2A4\uD2B8 \uD134 \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB178\uC744\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB125\uC2A4\uD2B8 \uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8 \uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uC0D8, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanFollowUpValuesForFollowUp() {
        String currentQuestion = "\uD6C4\uC18D \uB300\uD654\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uD6C4\uC18D \uB300\uD654 \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB78C\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD3EC\uD56D\uC774\uC57C. \uD6C4\uC18D \uB300\uD654\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uD6C4\uC18D \uB300\uD654\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uC0D8, \uD3EC\uD56D", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanContinuationValuesForFollowUp() {
        String currentQuestion = "\uC774\uC5B4\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC774\uC5B4\uC11C \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC0C8\uBCBD\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uC774\uC5B4\uC11C \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uC774\uC5B4\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uC0D8, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanOngoingConversationValuesForFollowUp() {
        String currentQuestion = "\uC774\uC5B4\uC9C0\uB294 \uB300\uD654\uC5D0\uC11C "
                + "\uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC774\uC5B4\uC9C0\uB294 \uB300\uD654 \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC740\uD558\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. \uC774\uC5B4\uC9C0\uB294 \uB300\uD654\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uC774\uC5B4\uC9C0\uB294 \uB300\uD654\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uD558\uC0D8, \uC9C4\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRecordedValuesForFollowUpKoreanPromptVariant() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uAE30\uB85D\uD558\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uB85D \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB4E4\uAF43\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uAE30\uB85D\uD574\uC918.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uAE30\uB85D\uD558\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB4E4\uAF43\uC0D8, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemoValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uBA54\uBAA8\uD558\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBA54\uBAA8 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBAA8\uB798\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uBA54\uBAA8\uD574\uC918.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uBA54\uBAA8\uD558\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBAA8\uB798\uC0D8, \uC21C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanWriteDownValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uC801\uC5B4\uB450\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC5B4 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uC704\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAC15\uC9C4\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uC801\uC5B4\uB450\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uC704\uC0D8, \uAC15\uC9C4", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanWrittenPastTenseValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC801\uC5B4\uB480\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC5B4 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC5F0\uB450\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCD98\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC5F0\uB450\uBB38, \uCD98\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedWrittenPastTenseValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC801\uC5B4 \uB480\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC5B4 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBCF4\uB77C\uAF43\uC774\uACE0 \uB3C4\uC2DC\uB294 \uACE0\uC131\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCF4\uB77C\uAF43, \uACE0\uC131", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanLeaveValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uB0A8\uACA8\uB450\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB0A8\uACA8 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB4E4\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uB0A8\uACA8\uC918.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uB0A8\uACA8\uB450\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB4E4\uC0D8, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanLeavePutAwayValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uB0A8\uACA8\uB193\uC73C\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB0A8\uACA8\uB194 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC194\uBC14\uB78C\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uB0A8\uACA8\uB194.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uB0A8\uACA8\uB193\uC73C\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC194\uBC14\uB78C, \uD1B5\uC601", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanStoreAwayValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uBCF4\uAD00\uD558\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uAD00 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBCC4\uCD08\uB871\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAC70\uC81C\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uBCF4\uAD00\uD574\uC918.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uBCF4\uAD00\uD558\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCC4\uCD08\uB871, \uAC70\uC81C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanPreserveValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uBCF4\uC874\uD558\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uC874 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB78C\uAF43\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC548\uB3D9\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uBCF4\uC874\uD574\uC918.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uBCF4\uC874\uD558\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uAF43, \uC548\uB3D9", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanKeepValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uAC04\uC9C1\uD558\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAC04\uC9C1 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC0C8\uBCBD\uBCC4\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBB38\uACBD\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uAC04\uC9C1\uD574\uC918.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uAC04\uC9C1\uD558\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uBCC4, \uBB38\uACBD", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanCarryValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uCC59\uAE30\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uCC59\uACA8 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2EC\uACE0\uB9AC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBC00\uC591\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uC4F8 \uC218 \uC788\uAC8C \uCC59\uACA8\uC918.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uCC59\uAE30\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uACE0\uB9AC, \uBC00\uC591", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemorizeColloquialValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uC678\uC6CC\uB450\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC678\uC6CC\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC790\uC791\uB098\uBB34\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC678\uC6CC\uB46C.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uC678\uC6CC\uB450\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC790\uC791\uB098\uBB34, \uC21C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberKeepValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uAE30\uC5B5\uD574\uB450\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uAE30\uC5B5\uD574\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB178\uC744\uC774\uACE0 \uC7A5\uC18C\uB294 \uBAA9\uD3EC\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574\uB46C.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uAE30\uC5B5\uD574\uB450\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744, \uBAA9\uD3EC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSaveKeepValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uC800\uC7A5\uD574\uB450\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC800\uC7A5\uD574\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB2E4\uBCC4\uC774\uACE0 \uC7A5\uC18C\uB294 \uAC15\uB989\uC774\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC800\uC7A5\uD574\uB46C.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uC800\uC7A5\uD574\uB450\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB2E4\uBCC4, \uAC15\uB989", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSavedPastTenseValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC800\uC7A5\uD588\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC800\uC7A5 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uB178\uB780\uAC15\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD3EC\uD56D\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC800\uC7A5\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uB780\uAC15, \uD3EC\uD56D", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedSavedPastTenseValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC800\uC7A5 \uD588\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC800\uC7A5 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBCF4\uB77C\uC12C\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uD3EC\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC800\uC7A5\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCF4\uB77C\uC12C, \uAD70\uD3EC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemoPastTenseValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uBA54\uBAA8\uD588\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBA54\uBAA8 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC8FC\uD669\uBCC4\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC18D\uCD08\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBA54\uBAA8\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC8FC\uD669\uBCC4, \uC18D\uCD08", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedMemoPastTenseValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uBA54\uBAA8 \uD588\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBA54\uBAA8 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uD558\uB298\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBA54\uBAA8\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD558\uB298\uBB38, \uC9C4\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanWrittenKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC801\uC5B4\uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC5B4 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uD30C\uB780\uB3CC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD6C\uBBF8\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD30C\uB780\uB3CC, \uAD6C\uBBF8", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanWrittenPlacedValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC801\uC5B4\uB193\uC740 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC5B4\uB193\uC740 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC740\uD558\uB3CC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uD558\uB3CC, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanWrittenPlacedPastValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC801\uC5B4\uB1A8\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC5B4\uB1A8\uB358 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBD84\uD64D\uC12C\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD6C\uB840\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBD84\uD64D\uC12C, \uAD6C\uB840", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanWrittenShortValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC368\uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC368\uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uCD08\uB85D\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCC3D\uC6D0\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uCD08\uB85D\uBB38, \uCC3D\uC6D0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedWrittenShortValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC368 \uB193\uC740 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC368 \uB193\uC740 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uAC80\uC740\uBCC4\uC774\uACE0 \uB3C4\uC2DC\uB294 \uACE0\uC591\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAC80\uC740\uBCC4, \uACE0\uC591", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanWrittenShortPlacedPastValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC368\uB1A8\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC368\uB1A8\uB358 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC790\uC8FC\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD3EC\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC790\uC8FC\uBB38, \uD3EC\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedWrittenShortPlacedPastValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC368 \uB1A8\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC368 \uB1A8\uB358 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uB0A8\uC0C9\uB3CC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC6D0\uC8FC\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB0A8\uC0C9\uB3CC, \uC6D0\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedWrittenKeptPastValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC368 \uB450\uC5C8\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC368 \uB450\uC5C8\uB358 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC740\uBE5B\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uBE5B\uBB38, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanDraftedKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC791\uC131\uD574 \uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC791\uC131\uD574 \uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uCD08\uB85D\uBCC4\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC81C\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uCD08\uB85D\uBCC4, \uC81C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanEnteredKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uAE30\uC785\uD574 \uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC785\uD574 \uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBCF4\uB77C\uAE38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCF4\uB77C\uAE38, \uC21C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanUnspacedDraftedKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC791\uC131\uD574\uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC791\uC131\uD574\uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBD84\uD64D\uBCC4\uC774\uACE0 \uB3C4\uC2DC\uB294 \uACF5\uC8FC\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBD84\uD64D\uBCC4, \uACF5\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanUnspacedEnteredKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uAE30\uC785\uD574\uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC785\uD574\uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uAC80\uC740\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC11C\uC0B0\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAC80\uC740\uBB38, \uC11C\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanUnspacedInputKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC785\uB825\uD574\uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC785\uB825\uD574\uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uB178\uC744\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC548\uB3D9\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uBB38, \uC548\uB3D9", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanUnspacedTypingKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uD0C0\uC774\uD551\uD574\uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uD0C0\uC774\uD551\uD574\uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB2E4\uAE38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB2E4\uAE38, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedInputKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC785\uB825\uD574 \uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC785\uB825\uD574 \uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uB2EC\uBE5B\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBC00\uC591\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uBE5B\uBB38, \uBC00\uC591", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedTypingKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uD0C0\uC774\uD551\uD574 \uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uD0C0\uC774\uD551\uD574 \uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC232\uAE38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD6C\uBBF8\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC232\uAE38, \uAD6C\uBBF8", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanNaturalInputPastValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uB0B4\uAC00 \uC785\uB825\uD55C \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC785\uB825\uD55C \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC740\uD558\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC18D\uCD08\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uD558\uBB38, \uC18D\uCD08", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanNaturalTypingPastValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uB0B4\uAC00 \uD0C0\uC774\uD551\uD55C \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uD0C0\uC774\uD551\uD55C \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB78C\uAE38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCD98\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uAE38, \uCD98\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanNaturalWrittenPastValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uB0B4\uAC00 \uC801\uC740 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC740 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uB178\uC744\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBAA9\uD3EC\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uBB38, \uBAA9\uD3EC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanNaturalSentValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uB0B4\uAC00 \uBCF4\uB0B8 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uB0B8 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB2E4\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB2E4\uBB38, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskLaterReferredValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uB77C\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBCF4\uB77C\uB358 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC0C8\uBCBD\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAC15\uB989\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uBB38, \uAC15\uB989", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskedLaterReferredValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF8\uB2E4\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBCF8\uB2E4\uB358 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uD638\uC218\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC548\uB3D9\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD638\uC218\uBB38, \uC548\uB3D9", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSaidWouldAskValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF8\uB2E4\uACE0 \uD55C "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBCF8\uB2E4\uACE0 \uD55C \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC18C\uB098\uBB34\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCCAD\uC8FC\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC18C\uB098\uBB34\uBB38, \uCCAD\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskWhenValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCFC \uB54C "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBCFC \uB54C \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC790\uC791\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCD98\uCC9C\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC790\uC791\uBB38, \uCD98\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanNoSpaceAskWhenValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCFC\uB54C "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBCFC\uB54C \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC194\uC78E\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC194\uC78E\uBB38, \uC21C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskedToAskValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBD10\uB2EC\uB77C\uACE0 \uD55C "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBD10\uB2EC\uB77C\uACE0 \uD55C \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC740\uD558\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uD558\uBB38, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedAskedToAskValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBD10 \uB2EC\uB77C\uACE0 \uD55C "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBD10 \uB2EC\uB77C\uACE0 \uD55C \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB78C\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uBB38, \uD1B5\uC601", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskedToAskReferredValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBD10\uB2EC\uB77C\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBD10\uB2EC\uB77C\uB358 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uB178\uC744\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uBB38, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedAskedToAskReferredValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBD10 \uB2EC\uB77C\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBD10 \uB2EC\uB77C\uB358 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uD587\uC0B4\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD587\uC0B4\uBB38, \uD1B5\uC601", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskedToAskContractedValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBD10\uB2EC\uB7AC\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBD10\uB2EC\uB7AC\uB358 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uB2EC\uBE5B\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uB0A8\uC6D0\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uBE5B\uBB38, \uB0A8\uC6D0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedAskedToAskContractedValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBD10 \uB2EC\uB7AC\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBD10 \uB2EC\uB7AC\uB358 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB2E4\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC18D\uCD08\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB2E4\uBB38, \uC18D\uCD08", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskedToAskRemindedValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBD10\uB2EC\uB7AC\uC796\uC544, "
                + "\uADF8 \uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBD10\uB2EC\uB7AC\uC796\uC544 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uB178\uB798\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBC00\uC591\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uB798\uBB38, \uBC00\uC591", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedAskedToAskRemindedValuesForNextTurn() {
        String currentQuestion = "\uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBD10 \uB2EC\uB7AC\uC796\uC544, "
                + "\uADF8 \uAC12 \uBB50\uC57C? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBB3C\uC5B4\uBD10 \uB2EC\uB7AC\uC796\uC544 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uAC15\uBB3C\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC6D0\uC8FC\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAC15\uBB3C\uBB38, \uC6D0\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAskedToAnswerLaterValuesForNextTurn() {
        String currentQuestion = "\uB098\uC911\uC5D0 \uB2F5\uD574\uB2EC\uB77C\uACE0 \uD588\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB2F5\uD574\uB2EC\uB77C\uACE0 \uD588\uB358 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC5F0\uBABB\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCD98\uCC9C\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC5F0\uBABB\uBB38, \uCD98\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedAskedToAnswerLaterValuesForNextTurn() {
        String currentQuestion = "\uB098\uC911\uC5D0 \uB2F5\uD574 \uB2EC\uB77C\uACE0 \uD588\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB2F5\uD574 \uB2EC\uB77C\uACE0 \uD588\uB358 \uAC12 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uD638\uC218\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD638\uC218\uBB38, \uC9C4\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanToldToAnswerValuesForNextTurn() {
        String currentQuestion = "\uB098\uC911\uC5D0 \uB300\uB2F5\uD558\uB77C\uB358 "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB300\uB2F5 \uAC12 \uBC18\uB840: \uCF54\uB4DC\uBA85\uC740 \uC740\uD558\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD3EC\uD56D\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uD558\uBB38, \uD3EC\uD56D", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanToldToAnswerActionValuesForNextTurn() {
        String currentQuestion = "\uB098\uC911\uC5D0 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C "
                + "\uAC12 \uBB50\uC57C? \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 \uBC18\uB840: \uCF54\uB4DC\uBA85\uC740 \uD574\uC624\uB984\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD6C\uBBF8\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD574\uC624\uB984\uBB38, \uAD6C\uBBF8", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanLeftForLaterValuesForNextTurn() {
        String currentQuestion = "\uC544\uAE4C \uB2E4\uC74C\uC5D0 \uC4F0\uB77C\uACE0 \uB0A8\uAE34 "
                + "\uAC70 \uBB50\uC600\uC5B4? \uAC12\uB9CC \uC54C\uB824\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB0A8\uAE34 \uAC12 \uBC18\uB840: \uCF54\uB4DC\uBA85\uC740 \uC624\uC194\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC624\uC194\uBB38, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedWrittenKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uC801\uC5B4 \uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC801\uC5B4 \uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uD30C\uB791\uC232\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC774\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD30C\uB791\uC232, \uC774\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemoPlacedValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uBA54\uBAA8\uD574\uB193\uC740 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBA54\uBAA8\uD574\uB193\uC740 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uC740\uBE5B\uB3CC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBA54\uBAA8\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uBE5B\uB3CC, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemoSpacedKeptValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uBA54\uBAA8\uD574 \uB454 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBA54\uBAA8\uD574 \uB454 \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uCD08\uB85D\uBCC4\uC774\uACE0 \uB3C4\uC2DC\uB294 \uACBD\uC8FC\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBA54\uBAA8\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uCD08\uB85D\uBCC4, \uACBD\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemorizeFormalValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 "
                + "\uC554\uAE30\uD574\uB450\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC554\uAE30\uD574\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC624\uB85C\uB77C\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC554\uAE30\uD574\uB46C.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uC554\uAE30\uD574\uB450\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC624\uB85C\uB77C, \uC9C4\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanContinuedValuesForFollowUp() {
        String currentQuestion = "\uACC4\uC18D\uD574\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C "
                + "\uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C "
                + "\uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uACC4\uC18D\uD574\uC11C \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uD30C\uB3C4\uC0D8\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC81C\uC8FC\uC57C. \uACC4\uC18D\uD574\uC11C \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uACC4\uC18D\uD574\uC11C \uBB3C\uC5B4\uBCF4\uB77C\uACE0 \uD55C \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD30C\uB3C4\uC0D8, \uC81C\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanPronounValuesForRepeatRequest() {
        String currentQuestion = "\uADF8 \uAC12 \uB2E4\uC2DC \uB9D0\uD574\uC918. \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 QA: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB78C\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC218\uC6D0\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                User: \uB098\uC911\uC5D0 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 \uBB50\uC57C? \uAC12\uB9CC.
                Assistant: \uBC14\uB78C\uBB38, \uC218\uC6D0
                User: \uADF8 \uAC12 \uB2E4\uC2DC \uB9D0\uD574\uC918. \uAC12\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uBB38, \uC218\uC6D0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanDeicticValuesForRepeatRequest() {
        String currentQuestion = "\uADF8\uAC70 \uB2E4\uC2DC \uC54C\uB824\uC918. \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 QA: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB78C\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC218\uC6D0\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                User: \uB098\uC911\uC5D0 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 \uBB50\uC57C? \uAC12\uB9CC.
                Assistant: \uBC14\uB78C\uBB38, \uC218\uC6D0
                User: \uADF8\uAC70 \uB2E4\uC2DC \uC54C\uB824\uC918. \uAC12\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uBB38, \uC218\uC6D0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanEarlierDeicticWhatValues() {
        String currentQuestion = "\uC544\uAE4C \uADF8\uAC70 \uBB50\uC600\uC9C0? \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 QA: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB78C\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC218\uC6D0\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                User: \uB098\uC911\uC5D0 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 \uBB50\uC57C? \uAC12\uB9CC.
                Assistant: \uBC14\uB78C\uBB38, \uC218\uC6D0
                User: \uC544\uAE4C \uADF8\uAC70 \uBB50\uC600\uC9C0? \uAC12\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uBB38, \uC218\uC6D0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanBeforeDeicticWhatValues() {
        String currentQuestion = "\uC804\uC5D0 \uADF8\uAC70 \uBB50\uC600\uC9C0? \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 QA: \uCF54\uB4DC\uBA85\uC740 \uBC14\uB78C\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC218\uC6D0\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                User: \uB098\uC911\uC5D0 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 \uBB50\uC57C? \uAC12\uB9CC.
                Assistant: \uBC14\uB78C\uBB38, \uC218\uC6D0
                User: \uC804\uC5D0 \uADF8\uAC70 \uBB50\uC600\uC9C0? \uAC12\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uBB38, \uC218\uC6D0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanThatTimeDeicticWhatValues() {
        String currentQuestion = "\uADF8\uB54C \uADF8\uAC70 \uBB50\uC600\uC9C0? \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 QA: \uCF54\uB4DC\uBA85\uC740 \uC740\uD558\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBAA9\uD3EC\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC801\uC5B4\uC918.
                Assistant: local fallback
                User: \uB098\uC911\uC5D0 \uB300\uB2F5\uD558\uB77C\uACE0 \uD55C \uAC12 \uBB50\uC57C? \uAC12\uB9CC.
                Assistant: \uC740\uD558\uBB38, \uBAA9\uD3EC
                User: \uADF8\uB54C \uADF8\uAC70 \uBB50\uC600\uC9C0? \uAC12\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uD558\uBB38, \uBAA9\uD3EC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanThatTimeMentionedWhatValues() {
        String currentQuestion = "\uADF8\uB54C \uB9D0\uD588\uB358 \uAC12 \uBB50\uC57C? \uAC12\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5 \uD504\uB85C\uBE0C: \uCF54\uB4DC\uBA85\uC740 \uBCC4\uBE5B\uBB38\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC9C4\uD574\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uAC12\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uADF8\uB54C \uB9D0\uD588\uB358 \uAC12 \uBB50\uC57C? \uAC12\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCC4\uBE5B\uBB38, \uC9C4\uD574", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanThatTimeMentionedContentValues() {
        String currentQuestion = "\uADF8\uB54C \uB9D0\uD588\uB358 \uB0B4\uC6A9 \uBB50\uC57C? \uB0B4\uC6A9\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uB85D \uD504\uB85C\uBE0C: \uB0B4\uC6A9\uC740 \uC740\uBE5B\uB2E4\uB9AC\uC774\uACE0 \uC7A5\uC18C\uB294 \uB0A8\uD574\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uB0B4\uC6A9\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uADF8\uB54C \uB9D0\uD588\uB358 \uB0B4\uC6A9 \uBB50\uC57C? \uB0B4\uC6A9\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uBE5B\uB2E4\uB9AC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanThatTimeTalkedContentValues() {
        String currentQuestion = "\uADF8\uB54C \uC598\uAE30\uD588\uB358 \uB0B4\uC6A9 \uBB50\uC57C? \uB0B4\uC6A9\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC598\uAE30 \uD45C\uD604 \uD504\uB85C\uBE0C: \uB0B4\uC6A9\uC740 \uC790\uD64D\uB4F1\uB300\uC774\uACE0 \uC7A5\uC18C\uB294 \uD1B5\uC601\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uB0B4\uC6A9\uC73C\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uADF8\uB54C \uC598\uAE30\uD588\uB358 \uB0B4\uC6A9 \uBB50\uC57C? \uB0B4\uC6A9\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC790\uD64D\uB4F1\uB300", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanThatTimeTalkedThingValues() {
        String currentQuestion = "\uADF8\uB54C \uC774\uC57C\uAE30\uD55C \uAC70 \uBB50\uC600\uC9C0? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC9C0\uC2DC\uC5B4 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBD89\uC740\uD0D1\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uC774\uC57C\uAE30\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uADF8\uB54C \uC774\uC57C\uAE30\uD55C \uAC70 \uBB50\uC600\uC9C0? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBD89\uC740\uD0D1, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanEarlierTalkedThingRepeatValues() {
        String currentQuestion = "\uC544\uAE4C \uC598\uAE30\uD55C \uAC70 \uB2E4\uC2DC \uB9D0\uD574\uC918. \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBC18\uBCF5 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uD478\uB978\uC885\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB2E4\uC74C\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uC774 \uC774\uC57C\uAE30\uB85C \uB2F5\uD574\uC918.
                Assistant: local fallback
                User: \uC544\uAE4C \uC598\uAE30\uD55C \uAC70 \uB2E4\uC2DC \uB9D0\uD574\uC918. \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD478\uB978\uC885, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsArrowRememberedValuesWithoutProbeHeader() {
        String currentQuestion = "What did I ask you to remember?";
        String history = """
                User: Memory probe: nickname -> silver kite; city -> Jeonju. Remember these values for the next question.
                Assistant: evidence fallback
                User: What did I ask you to remember?
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("silver kite, Jeonju", answer);
    }

    @Test
    void recentHistoryFallbackHonorsArrowEnglishLabelAmongGeneralValues() {
        String currentQuestion = "What was my nickname? Answer only the nickname.";
        String history = """
                User: Memory probe: nickname -> silver kite; city -> Jeonju. Remember these values for the next question.
                Assistant: evidence fallback
                User: What was my nickname? Answer only the nickname.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("silver kite", answer);
    }

    @Test
    void recentHistoryCodeOnlyFallbackTracesExactRouteWithoutRawValue() {
        TraceStore.clear();
        try {
            String currentQuestion = "\uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uBB50\uC600\uC9C0? \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918.";
            String history = """
                    User: \uD14C\uC2A4\uD2B8\uC6A9 \uAE30\uC5B5 \uD655\uC778: \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uBCF4\uB77C-914\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uCF54\uB4DC\uB97C \uAE30\uC5B5\uD574\uC918.
                    Assistant: local fallback
                    User: \uBC29\uAE08 \uB0B4\uAC00 \uB9D0\uD55C \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uBB50\uC600\uC9C0? \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918.
                    """;

            String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

            assertEquals("\uBCF4\uB77C-914", answer);
            assertEquals(Boolean.TRUE, TraceStore.get("chat.historyFallback.exactCodeOnly"));
            assertEquals("exact_code_only", TraceStore.get("chat.historyFallback.answerKind"));
            assertEquals("recent_history", TraceStore.get("chat.historyFallback.source"));
            assertEquals(6, TraceStore.get("chat.historyFallback.valueLength"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("\uBCF4\uB77C-914"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesProvidedCodeWithoutEvidenceComplaint() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD14C\uC2A4\uD2B8\uC6A9 \uAE30\uC5B5 \uD655\uC778: \uC0C9\uC0C1 \uCF54\uB4DC\uB294 \uC790\uC8FC\uC0C9-738\uC774\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uCF54\uB4DC\uB97C \uAE30\uC5B5\uD574\uC918.");

        assertTrue(answer.contains("\uC790\uC8FC\uC0C9-738"));
        assertTrue(answer.contains("\uC774\uBC88 \uC138\uC158"));
        assertTrue(answer.contains("\uB2E4\uC74C \uC9C8\uBB38"));
        assertFalse(answer.contains("Evidence"));
        assertFalse(answer.contains("\uC81C\uACF5\uB41C Evidence"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesImmediateKoreanRememberCommand() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uB79C\uB364 \uD14C\uC2A4\uD2B8 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 AZ-74Q\uC785\uB2C8\uB2E4. \uC774 \uAC12\uC744 \uAE30\uC5B5\uD574.");

        assertNotNull(answer);
        assertTrue(answer.contains("AZ-74Q"), answer);
        assertFalse(answer.contains("Evidence"));
        assertFalse(answer.contains("\uC694\uC57D"));
    }

    @Test
    void currentTurnMemoryFallbackAcknowledgesKoreanReloadMemoryValues() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uD55C\uAD6D\uC5B4 \uB9AC\uB85C\uB4DC \uAE30\uC5B5 \uAC80\uC99D: "
                        + "\uBCC4\uBA85\uC740 \uBCC4\uD558\uC774\uACE0 "
                        + "\uC7A5\uC18C\uB294 \uCD98\uCC9C\uC774\uC57C. "
                        + "\uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                        + "\uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uBCC4\uD558"));
        assertTrue(answer.contains("\uCD98\uCC9C"));
        assertTrue(answer.contains("2\uAC1C"));
        assertFalse(answer.contains("\uC9C1\uC804"));
    }

    @Test
    void currentTurnMemoryFallbackHonorsKoreanRequestedLabelWhenCodeAlsoPresent() {
        String answer = ChatWorkflow.composeCurrentTurnMemoryFallback(
                "\uB79C\uB364 \uB9AC\uB85C\uB4DC \uAE30\uC5B5 H0942: "
                        + "\uBCC4\uBA85\uC740 \uC740\uD558\uB4F1\uB300\uC774\uACE0 "
                        + "\uB3C4\uC2DC \uCF54\uB4DC\uB294 GWANGJU-58\uC774\uC57C. "
                        + "\uC0C8\uB85C\uACE0\uCE68 \uB4A4 \uBCC4\uBA85\uB9CC \uBB3C\uC73C\uBA74 "
                        + "\uC815\uD655\uD788 \uC740\uD558\uB4F1\uB300 \uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574\uC918.");

        assertNotNull(answer);
        assertTrue(answer.contains("\uC740\uD558\uB4F1\uB300"), answer);
        assertFalse(answer.contains("GWANGJU-58"), answer);
    }

    @Test
    void recentHistoryFallbackDoesNotStealKoreanReloadMemoryRequest() {
        String currentRequest = "\uD55C\uAD6D\uC5B4 \uB9AC\uB85C\uB4DC \uAE30\uC5B5 \uAC80\uC99D: "
                + "\uBCC4\uBA85\uC740 \uBCC4\uD558\uC774\uACE0 "
                + "\uC7A5\uC18C\uB294 \uCD98\uCC9C\uC774\uC57C. "
                + "\uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 "
                + "\uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uC800\uC7A5\uD574\uB454 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uAD6C\uB984\uBCC4\uC774\uACE0 \uC7A5\uC18C\uB294 \uC18D\uCD08\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC800\uC7A5\uD574\uB46C.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentRequest, history);

        assertTrue(answer == null);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRecordedValuesForFollowUp() {
        String currentQuestion = "\uB2E4\uC74C \uB300\uD654 \uB54C "
                + "\uAE30\uB85D\uD574\uB454 \uAC12\uB4E4\uC774 \uBB50\uC600\uC9C0? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uAE30\uB85D\uD574\uB454 \uAC12 \uAC80\uC99D: \uBCC4\uBA85\uC740 \uAC15\uBB3C\uBCC4\uC774\uACE0 \uC7A5\uC18C\uB294 \uBAA9\uD3EC\uC57C. \uB2E4\uC74C \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uB85D\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAC15\uBB3C\uBCC4, \uBAA9\uD3EC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRecordedPastTenseValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uAE30\uB85D\uD588\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uB85D \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uD30C\uB780\uB2EC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uB85D\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD30C\uB780\uB2EC, \uC21C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedRecordedPastTenseValuesForNextTurn() {
        String currentQuestion = "\uB125\uC2A4\uD2B8 \uB300\uD654\uC5D0\uC11C "
                + "\uAE30\uB85D \uD588\uB358 \uAC12 \uBB50\uC57C? "
                + "\uAC12\uB9CC \uC9E7\uAC8C \uB9D0\uD574\uC918.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uB85D \uD14C\uC2A4\uD2B8: \uCF54\uB4DC\uBA85\uC740 \uBD89\uC740\uBE44\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCD98\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8 \uB300\uD654 \uB54C \uBB3C\uC5B4\uBCF4\uBA74 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uB85D\uD574\uC918.
                Assistant: local fallback
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBD89\uC740\uBE44, \uCD98\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanUnspacedRecordedThingForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C "
                + "\uAE30\uB85D\uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB125\uC2A4\uD2B8 \uAE30\uB85D \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC740\uBE5B\uD56D\uAD6C\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uB85D\uD574\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uB85D\uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uBE5B\uD56D\uAD6C, \uD1B5\uC601", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanTypoNextConversationRecordedPluralValues() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uB85D\uB4E4\uC774 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB125\uC2A4\uD2B8 \uAE30\uB85D \uBCF5\uC218 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uD30C\uB3C4\uB4F1\uB300\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAC15\uB989\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uB85D\uD574\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAE30\uB85D\uB4E4\uC774 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD30C\uB3C4\uB4F1\uB300, \uAC15\uB989", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRecordedThingPluralValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uB85D\uD55C \uAC83\uB4E4 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uB85D\uD55C \uAC83\uB4E4 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uD574\uC624\uB984\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uB85D\uD574\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAE30\uB85D\uD55C \uAC83\uB4E4 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD574\uC624\uB984, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemoPluralValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uBA54\uBAA8\uB4E4 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBA54\uBAA8\uB4E4 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC0C8\uBCBD\uB3CC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC775\uC0B0\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBA54\uBAA8\uD574\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uBA54\uBAA8\uB4E4 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uB3CC, \uC775\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanDontForgetValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE4C\uBA39\uC9C0 \uB9D0\uB77C\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE4C\uBA39\uC9C0 \uB9D0\uACE0 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2EC\uBB34\uB9AC\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBCF4\uC131\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE4C\uBA39\uC9C0 \uB9D0\uACE0.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAE4C\uBA39\uC9C0 \uB9D0\uB77C\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uBB34\uB9AC\uC5F4\uC1E0, \uBCF4\uC131", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberNotToForgetValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uC78A\uC9C0 \uB9D0\uB77C\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC78A\uC9C0 \uB9D0\uACE0 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBCC4\uBC14\uAD6C\uB2C8\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC78A\uC9C0 \uB9D0\uACE0.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uC78A\uC9C0 \uB9D0\uB77C\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCC4\uBC14\uAD6C\uB2C8, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanIfForgetBlockedValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE4C\uBA39\uC73C\uBA74 \uC548 \uB41C\uB2E4\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE4C\uBA39\uC73C\uBA74 \uC548 \uB3FC \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB78C\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uACE0\uC131\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE4C\uBA39\uC73C\uBA74 \uC548 \uB3FC.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAE4C\uBA39\uC73C\uBA74 \uC548 \uB41C\uB2E4\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uC5F4\uC1E0, \uACE0\uC131", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanIfRememberLostBlockedValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uC78A\uC73C\uBA74 \uC548 \uB41C\uB2E4\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC78A\uC73C\uBA74 \uC548 \uB3FC \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC720\uB9AC\uC218\uCCA9\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBB38\uACBD\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC78A\uC73C\uBA74 \uC548 \uB3FC.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uC78A\uC73C\uBA74 \uC548 \uB41C\uB2E4\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC720\uB9AC\uC218\uCCA9, \uBB38\uACBD", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberKeepingValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD558\uACE0 \uC788\uC73C\uB77C\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD558\uACE0 \uC788\uC5B4\uC918 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uAD6C\uB984\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC81C\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD558\uACE0 \uC788\uC5B4\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAE30\uC5B5\uD558\uACE0 \uC788\uC73C\uB77C\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAD6C\uB984\uC5F4\uC1E0, \uC81C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberPutAwayValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574 \uB193\uC73C\uB77C\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574 \uB193\uACE0 \uC788\uC5B4\uC918 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2EC\uBE5B\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBCF4\uB839\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574 \uB193\uACE0 \uC788\uC5B4\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAE30\uC5B5\uD574 \uB193\uC73C\uB77C\uACE0 \uD55C\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uBE5B\uC5F4\uC1E0, \uBCF4\uB839", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574 \uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBCC4\uBE5B\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC0BC\uCC99\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574 \uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAE30\uC5B5\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCC4\uBE5B\uC5F4\uC1E0, \uC0BC\uCC99", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberPutColloquialValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574\uB1A8\uB358\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574\uB194 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC0C8\uBCBD\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD0DC\uBC31\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574\uB194.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574\uB1A8\uB358\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uC5F4\uC1E0, \uD0DC\uBC31", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberPutKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574 \uB193\uC740 \uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574 \uB193\uC544\uC918 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB78C\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574 \uB193\uC544\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574 \uB193\uC740 \uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uC5F4\uC1E0, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberPutCompletedValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574 \uB193\uC558\uB358 \uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574 \uB193\uC544\uC918 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB178\uC744\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uCCAD\uC8FC\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574 \uB193\uC544\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574 \uB193\uC558\uB358 \uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uC5F4\uC1E0, \uCCAD\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberPutCompletedContentValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574 \uB193\uC558\uB358 \uB0B4\uC6A9 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574 \uB193\uC544\uC918 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uAD6C\uB984\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC81C\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574 \uB193\uC544\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574 \uB193\uC558\uB358 \uB0B4\uC6A9 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAD6C\uB984\uC5F4\uC1E0, \uC81C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberKeptAsideContentValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574 \uB194\uB480\uB358 \uB0B4\uC6A9 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574 \uB194\uB46C\uC918 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB178\uB798\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC18D\uCD08\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574 \uB194\uB46C\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574 \uB194\uB480\uB358 \uB0B4\uC6A9 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uB798\uC5F4\uC1E0, \uC18D\uCD08", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberKeptAsideShortContentValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574 \uB194\uB454 \uB0B4\uC6A9 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574 \uB194\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC870\uC57D\uB3CC\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uB0A8\uD574\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574 \uB194\uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574 \uB194\uB454 \uB0B4\uC6A9 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC870\uC57D\uB3CC\uC5F4\uC1E0, \uB0A8\uD574", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberKeptAsideShortValueValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574 \uB194\uB454 \uAC12 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574 \uB194\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC720\uB9AC\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC9C4\uD574\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574 \uB194\uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574 \uB194\uB454 \uAC12 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC720\uB9AC\uC5F4\uC1E0, \uC9C4\uD574", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberKeptNoSpaceContentValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574\uB454 \uB0B4\uC6A9 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2E8\uD48D\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC548\uB3D9\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574\uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574\uB454 \uB0B4\uC6A9 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2E8\uD48D\uC5F4\uC1E0, \uC548\uB3D9", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanRememberKeptNoSpaceValueValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAE30\uC5B5\uD574\uB454 \uAC12 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uAD6C\uC2AC\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD6C\uBBF8\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uB2F5\uD560 \uC218 \uC788\uAC8C \uAE30\uC5B5\uD574\uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB54C \uAE30\uC5B5\uD574\uB454 \uAC12 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uAD6C\uC2AC\uC5F4\uC1E0, \uAD6C\uBBF8", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanAnswerLaterSaidValueForNextConversation() {
        String currentQuestion = "\uB2E4\uC74C\uD134\uC5D0 \uB2F5\uD558\uB77C\uACE0 \uD588\uB358 "
                + "\uAC12 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAE30\uC5B5\uD574\uB46C \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC790\uC218\uC815\uC5F4\uC1E0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC6D0\uC8FC\uC57C. \uB2E4\uC74C\uD134\uC5D0 \uB2F5\uD558\uB77C\uACE0 \uD588\uB358 \uAC12\uC73C\uB85C \uAE30\uC5B5\uD574\uB46C.
                Assistant: local fallback
                User: \uB2E4\uC74C\uD134\uC5D0 \uB2F5\uD558\uB77C\uACE0 \uD588\uB358 \uAC12 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC790\uC218\uC815\uC5F4\uC1E0, \uC6D0\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemoKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uBA54\uBAA8\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBA54\uBAA8\uD574\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC740\uD558\uBABB\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAD70\uC0B0\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBA54\uBAA8\uD574\uB460.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uBA54\uBAA8\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uD558\uBABB, \uAD70\uC0B0", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanStoredKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uC800\uC7A5\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC800\uC7A5\uD574\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC18C\uAE08\uB4F1\uB300\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC800\uC7A5\uD574\uB460.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uC800\uC7A5\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC18C\uAE08\uB4F1\uB300, \uD1B5\uC601", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedStoredKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uC800\uC7A5\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC800\uC7A5\uD574 \uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB2E4\uC6B0\uCCB4\uD1B5\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBAA9\uD3EC\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC800\uC7A5\uD574 \uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uC800\uC7A5\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB2E4\uC6B0\uCCB4\uD1B5, \uBAA9\uD3EC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanStoredAwayKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uBCF4\uAD00\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uAD00\uD574\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uD30C\uB3C4\uCC3D\uACE0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBCF4\uAD00\uD574\uB460.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uBCF4\uAD00\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uD30C\uB3C4\uCC3D\uACE0, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedStoredAwayKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uBCF4\uAD00\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uAD00\uD574 \uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2EC\uBE5B\uC11C\uB78D\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBCF4\uAD00\uD574 \uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uBCF4\uAD00\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uBE5B\uC11C\uB78D, \uD1B5\uC601", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanPreserveKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uBCF4\uC874\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uC874\uD574\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC720\uB9AC\uB2EC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC644\uB3C4\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBCF4\uC874\uD574\uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uBCF4\uC874\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC720\uB9AC\uB2EC, \uC644\uB3C4", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedPreserveKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uBCF4\uC874\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uBCF4\uC874\uD574 \uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2EC\uBE5B\uD56D\uAD6C\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBCF4\uC131\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uBCF4\uC874\uD574 \uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uBCF4\uC874\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uBE5B\uD56D\uAD6C, \uBCF4\uC131", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanLeaveKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uB0A8\uACA8\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uB0A8\uACA8\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC0C8\uBCBD\uBCC4\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC5EC\uC218\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uB0A8\uACA8\uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB0A8\uACA8\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uBCC4, \uC5EC\uC218", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanCherishedKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAC04\uC9C1\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAC04\uC9C1\uD574\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC0C8\uBCBD\uCC3D\uACE0\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBB38\uACBD\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAC04\uC9C1\uD574\uB460.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAC04\uC9C1\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC0C8\uBCBD\uCC3D\uACE0, \uBB38\uACBD", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedCherishedKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uAC04\uC9C1\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uAC04\uC9C1\uD574 \uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBCC4\uBE5B\uC6B0\uCCB4\uAD6D\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uAC04\uC9C1\uD574 \uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uAC04\uC9C1\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBCC4\uBE5B\uC6B0\uCCB4\uAD6D, \uC21C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanCarriedKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uCC59\uACA8\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uCC59\uACA8\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB2EC\uACE0\uB9AC\uC774\uACE0 \uB3C4\uC2DC\uB294 \uBC00\uC591\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uCC59\uACA8\uC918.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uCC59\uACA8\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB2EC\uACE0\uB9AC, \uBC00\uC591", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedCarriedKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uCC59\uACA8 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uCC59\uACA8 \uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uB178\uC744\uC815\uB958\uC7A5\uC774\uACE0 \uB3C4\uC2DC\uB294 \uAC15\uB989\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uCC59\uACA8 \uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uCC59\uACA8 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uB178\uC744\uC815\uB958\uC7A5, \uAC15\uB989", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanMemorizedKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uC678\uC6CC\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC678\uC6CC\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC790\uC791\uB098\uBB34\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC21C\uCC9C\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC678\uC6CC\uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uC678\uC6CC\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC790\uC791\uB098\uBB34, \uC21C\uCC9C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedMemorizedKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uC678\uC6CC \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC678\uC6CC \uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC740\uBE5B\uBAA8\uB798\uC774\uACE0 \uB3C4\uC2DC\uB294 \uD1B5\uC601\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC678\uC6CC \uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uC678\uC6CC \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC740\uBE5B\uBAA8\uB798, \uD1B5\uC601", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanFormalMemorizedKeptNoSpaceValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uC554\uAE30\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC554\uAE30\uD574\uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uC624\uB85C\uB77C\uC774\uACE0 \uB3C4\uC2DC\uB294 \uC9C4\uC8FC\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC554\uAE30\uD574\uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uC554\uAE30\uD574\uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uC624\uB85C\uB77C, \uC9C4\uC8FC", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanSpacedFormalMemorizedKeptValuesForNextConversation() {
        String currentQuestion = "\uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 "
                + "\uC554\uAE30\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.";
        String history = """
                User: \uD55C\uAD6D\uC5B4 \uB2E4\uC74C\uD134 \uC554\uAE30\uD574 \uB454\uAC70 \uD504\uB85C\uBE0C: \uBCC4\uBA85\uC740 \uBC14\uB78C\uCC45\uAC08\uD53C\uC774\uACE0 \uB3C4\uC2DC\uB294 \uB0A8\uC6D0\uC774\uC57C. \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uB2F5\uD560 \uC218 \uC788\uAC8C \uC554\uAE30\uD574 \uB46C.
                Assistant: local fallback
                User: \uB125\uC2A4\uD2B8\uB85C \uB300\uD654\uD560\uB584 \uC554\uAE30\uD574 \uB454\uAC70 \uBB50\uC57C? \uB2F5\uB9CC.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(currentQuestion, history);

        assertEquals("\uBC14\uB78C\uCC45\uAC08\uD53C, \uB0A8\uC6D0", answer);
    }

    @Test
    void currentTurnMemoryFallbackShortCircuitsBeforeDisambiguationAndLlm() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int recentHistoryLoad = source.indexOf(
                "java.util.List<String> recentHistory = (memoryReadEnabled && sessionIdLong != null)");
        int currentTurnShortCircuit = source.indexOf("composeCurrentTurnMemoryFallback(userQuery)", recentHistoryLoad);
        int disambiguation = source.indexOf("DisambiguationResult dr;", recentHistoryLoad);

        assertTrue(recentHistoryLoad > 0, "memory-gated recent-history load must remain present");
        assertTrue(currentTurnShortCircuit > recentHistoryLoad,
                "current-turn memory acknowledgements should be checked after loading session history");
        assertTrue(currentTurnShortCircuit < disambiguation,
                "current-turn memory acknowledgement should avoid disambiguation/search/LLM work");
        assertTrue(source.contains("ChatResult.of(earlyCurrentTurnMemoryFallback, \"history:fallback:current-turn\", false)"),
                "short-circuit should return a deterministic local ChatResult without model/RAG usage");
    }

    @Test
    void directLiteralAnswerFallbackShortCircuitsBeforeDisambiguationAndLlm() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int recentHistoryLoad = source.indexOf(
                "java.util.List<String> recentHistory = (memoryReadEnabled && sessionIdLong != null)");
        int directLiteralShortCircuit = source.indexOf("composeDirectLiteralAnswerFallback(userQuery)", recentHistoryLoad);
        int disambiguation = source.indexOf("DisambiguationResult dr;", recentHistoryLoad);

        assertTrue(recentHistoryLoad > 0, "memory-gated recent-history load must remain present");
        assertTrue(directLiteralShortCircuit > recentHistoryLoad,
                "direct literal exact-answer prompts should be checked after local history probes");
        assertTrue(directLiteralShortCircuit < disambiguation,
                "direct literal exact-answer prompts should avoid disambiguation/search/LLM work");
        assertTrue(source.contains("ChatResult.of(earlyDirectLiteralFallback, \"direct:literal\", false)"),
                "short-circuit should return a deterministic direct literal ChatResult without model/RAG usage");
    }

    @Test
    void directLiteralAnswerFallbackPrecedesCurrentTurnMemoryAcknowledgement() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int recentHistoryLoad = source.indexOf(
                "java.util.List<String> recentHistory = (memoryReadEnabled && sessionIdLong != null)");
        int directLiteralShortCircuit = source.indexOf("composeDirectLiteralAnswerFallback(userQuery)", recentHistoryLoad);
        int currentTurnShortCircuit = source.indexOf("composeCurrentTurnMemoryFallback(userQuery)", recentHistoryLoad);

        assertTrue(recentHistoryLoad > 0, "memory-gated recent-history load must remain present");
        assertTrue(directLiteralShortCircuit > recentHistoryLoad,
                "direct literal exact-answer prompts should be checked after local history probes");
        assertTrue(currentTurnShortCircuit > directLiteralShortCircuit,
                "code-only literal prompts that also say remember should answer the literal before memory acknowledgement");
    }

    @Test
    void directLiteralAnswerFallbackReturnsInlineKoreanCodeOnlyTokenBeforeDirective() {
        String answer = ChatWorkflow.composeDirectLiteralAnswerFallback(
                "\uBC29\uAE08 \uB0B4\uAC00 \uBD99\uC778 \uCF54\uB4DC RANDOM-SOAK-UI-001 \uAE30\uC5B5\uB098? \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918");

        assertEquals("RANDOM-SOAK-UI-001", answer);
    }

    @Test
    void directLiteralAnswerFallbackReturnsReloadMemoryCodeOnlyToken() {
        String answer = ChatWorkflow.composeDirectLiteralAnswerFallback(
                "reload history test code RELOAD-MEM-501 \uAE30\uC5B5\uD574. \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918");

        assertEquals("RELOAD-MEM-501", answer);
    }

    @Test
    void directLiteralAnswerFallbackReturnsKoreanOneWordLiteral() {
        String answer = ChatWorkflow.composeDirectLiteralAnswerFallback(
                "papaya \uB77C\uB294 \uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574\uC918.");

        assertEquals("papaya", answer);
    }

    @Test
    void directLiteralAnswerFallbackReturnsKoreanQuotedAnswerToken() {
        String query = "\uC9E7\uAC8C OK\uB77C\uACE0 \uB2F5\uD574\uC918.";

        assertTrue(ChatWorkflow.isDirectLiteralAnswerRequest(query));
        assertEquals("OK", ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }

    @Test
    void evidenceNeededFallbackInstructionDoesNotHijackRagFeatureProbeAsDirectLiteral() {
        String query = "\uD604\uC7AC \uADFC\uAC70\uB9CC \uC0AC\uC6A9\uD574\uC11C \uB2F5\uD574\uC918. "
                + "Dynamic RAG Orchestration Platform\uC5D0\uC11C Plan DSL, MoE Strategy Selector, "
                + "GraphRAG/KG, CFVM \uC911 \uC2E4\uC81C \uADFC\uAC70\uAC00 \uC788\uB294 \uD56D\uBAA9\uACFC "
                + "evidence_needed \uD56D\uBAA9\uC744 4\uD589 \uD45C\uB85C \uB098\uB204\uACE0, "
                + "\uAC01 \uD589\uC5D0 \uAC00\uB2A5\uD55C source marker\uB97C \uBD99\uC5EC\uC918. "
                + "\uADFC\uAC70\uAC00 \uC5C6\uC73C\uBA74 \uCD94\uCE21\uD558\uC9C0 \uB9D0\uACE0 evidence_needed\uB77C\uACE0 \uC368\uC918.";

        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }

    @Test
    void conditionalSearchQualityInstructionDoesNotHijackRagProbeAsDirectLiteral() {
        String query = "RAG \uC6F9\uAC80\uC0C9 \uD488\uC9C8 \uD0D0\uCE68: site:openai.com Responses API built-in tools "
                + "web_search file_search computer_use \uC640 site:supabase.com MCP read_only project_ref \uC124\uC815\uC744 "
                + "\uBE44\uAD50\uD574\uC918. \uBC18\uB4DC\uC2DC evidence \uB3C4\uBA54\uC778\uC774 openai.com \uB610\uB294 "
                + "supabase.com\uC778\uC9C0 \uD655\uC778\uD558\uACE0, \uB2E4\uB978 \uB3C4\uBA54\uC778\uC774\uBA74 "
                + "\uAC80\uC0C9 \uD488\uC9C8 \uBB38\uC81C\uB77C\uACE0 \uB9D0\uD574\uC918.";

        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }

    @Test
    void fourLineFormatInstructionDoesNotBecomeDirectLiteralAnswer() {
        String query = "\uB450 \uACBD\uB85C\uC758 \uACB0\uACFC\uB97C \uBE44\uAD50\uD558\uACE0 "
                + "\uC815\uD655\uD788 4\uC904\uB9CC \uCD9C\uB825\uD558\uC138\uC694. "
                + "P1, P2, N1, JUDGE\uB97C \uAC01\uAC01 \uD55C \uC904\uB85C \uC4F0\uC138\uC694.";

        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }

    @Test
    void directLiteralAnswerPredicateMatchesCodeOnlyFallback() {
        assertTrue(ChatWorkflow.isDirectLiteralAnswerRequest(
                "\uB79C\uB364 \uC138\uC158 \uC9C0\uC18D\uC131 \uD14C\uC2A4\uD2B8 \uCF54\uB4DC SESSION-STAY-417 \uAE30\uC5B5\uB098? \uCF54\uB4DC\uB9CC \uB2F5\uD574\uC918"));
    }

    @Test
    void rememberedKoreanValueQuestionDoesNotFallThroughToLiteralValueWord() {
        String query = "\uBC29\uAE08 \uB0B4\uAC00 \uD655\uC778 \uB2E8\uC5B4\uB85C \uB9D0\uD55C \uAC12\uC774 \uBB50\uC600\uC9C0? "
                + "\uC815\uD655\uD788 \uAC12\uB9CC \uB9D0\uD574\uC918.";

        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }

    @Test
    void recentHistoryFallbackReturnsRememberedKoreanMentionedValueOnly() {
        String query = "\uBC29\uAE08 \uB0B4\uAC00 \uD655\uC778 \uB2E8\uC5B4\uB85C \uB9D0\uD55C \uAC12\uC774 \uBB50\uC600\uC9C0? "
                + "\uC815\uD655\uD788 \uAC12\uB9CC \uB9D0\uD574\uC918.";
        String history = """
                User: \uD14C\uC2A4\uD2B8\uB85C \uAE30\uC5B5\uD574\uC918. \uC624\uB298\uC758 \uD655\uC778 \uB2E8\uC5B4\uB294 \uBE14\uB8E8\uB9DD\uACE0\uC57C.
                Assistant: local fallback
                User: \uBC29\uAE08 \uB0B4\uAC00 \uD655\uC778 \uB2E8\uC5B4\uB85C \uB9D0\uD55C \uAC12\uC774 \uBB50\uC600\uC9C0? \uC815\uD655\uD788 \uAC12\uB9CC \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(query, history);

        assertEquals("\uBE14\uB8E8\uB9DD\uACE0", answer);
    }

    @Test
    void rememberedKoreanValueQuestionDoesNotFallThroughToPreMarkerLiteralValueWord() {
        String query = "\uBC29\uAE08 \uB0B4\uAC00 \uB3C4\uC2DC \uAC12\uC73C\uB85C \uB9D0\uD55C \uAC8C \uBB50\uC600\uC9C0? "
                + "\uAC12\uB9CC \uC815\uD655\uD788 \uB9D0\uD574\uC918.";

        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }

    @Test
    void recentHistoryFallbackReturnsPreMarkerMentionedKoreanValueOnly() {
        String query = "\uBC29\uAE08 \uB0B4\uAC00 \uB3C4\uC2DC \uAC12\uC73C\uB85C \uB9D0\uD55C \uAC8C \uBB50\uC600\uC9C0? "
                + "\uAC12\uB9CC \uC815\uD655\uD788 \uB9D0\uD574\uC918.";
        String history = """
                User: \uC774\uBC88 \uB79C\uB364 \uC810\uAC80\uC5D0\uC11C \uB3C4\uC2DC \uAC12\uC740 \uB178\uC744\uD56D\uAD6C\uC57C. \uAE30\uC5B5\uD574\uC918.
                Assistant: local fallback
                User: \uBC29\uAE08 \uB0B4\uAC00 \uB3C4\uC2DC \uAC12\uC73C\uB85C \uB9D0\uD55C \uAC8C \uBB50\uC600\uC9C0? \uAC12\uB9CC \uC815\uD655\uD788 \uB9D0\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(query, history);

        assertEquals("\uB178\uC744\uD56D\uAD6C", answer);
    }

    @Test
    void recentHistoryFallbackReturnsKoreanTokenOnlyRecallWithoutWhatQuestion() {
        String query = "\uBC29\uAE08 \uB9D0\uD55C \uC784\uC2DC \uD655\uC778 \uD1A0\uD070\uB9CC \uB2F5\uD574\uC918.";
        String history = """
                User: cont16-current memory setup: \uB0B4 \uC784\uC2DC \uD655\uC778 \uD1A0\uD070\uC740 NOVA-716\uC774\uC57C. \uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uD1A0\uD070\uB9CC \uB2F5\uD574.
                Assistant: NOVA-716
                User: \uBC29\uAE08 \uB9D0\uD55C \uC784\uC2DC \uD655\uC778 \uD1A0\uD070\uB9CC \uB2F5\uD574\uC918.
                """;

        String answer = ChatWorkflow.composeRecentHistoryFallback(query, history);

        assertEquals("NOVA-716", answer);
    }

    @Test
    void recentHistoryFallbackShortCircuitsBeforeDisambiguationAndLlm() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int memoryReadGate = source.indexOf(
                "final boolean memoryReadEnabled = memoryMode == null || memoryMode.isReadEnabled()");
        int recentHistoryLoad = source.indexOf(
                "java.util.List<String> recentHistory = (memoryReadEnabled && sessionIdLong != null)");
        int shortCircuit = source.indexOf("composeRecentHistoryFallback(userQuery, String.join(\"\\n\", recentHistory))");
        int disambiguation = source.indexOf("DisambiguationResult dr;", recentHistoryLoad);

        assertTrue(memoryReadGate > 0, "continueChat should resolve one memory-read admission gate");
        assertTrue(recentHistoryLoad > memoryReadGate,
                "read-enabled sessions should load recent history after memory admission");
        assertTrue(shortCircuit > recentHistoryLoad,
                "recent-history questions should be checked immediately after loading recent history");
        assertTrue(shortCircuit < disambiguation,
                "recent-history fallback should short-circuit before disambiguation/search/LLM work");
        assertTrue(source.contains("ChatResult.of(earlyRecentHistoryFallback, \"history:fallback:recent\", false)"),
                "short-circuit should return a deterministic local ChatResult without model/RAG usage");
    }

    @Test
    void recentHistoryFallbackRedactsSecretLikePriorMessage() {
        String rawTokenValue = "sk-" + "A".repeat(24);
        String history = "User: " + rawTokenValue + "\n"
                + "Assistant: received\n"
                + "User: what was my previous message?";

        String answer = ChatWorkflow.composeRecentHistoryFallback("what was my previous message?", history);

        assertFalse(answer.contains(rawTokenValue));
        assertTrue(answer.contains("Previous user message:"));
    }
}
