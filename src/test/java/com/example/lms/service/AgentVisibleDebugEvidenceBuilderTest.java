package com.example.lms.service;

import org.junit.jupiter.api.Test;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.infra.selection.ReplaySelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyProjection;
import com.example.lms.search.TraceStore;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.trace.SelectionEntropyTraceSupport;
import com.example.lms.trace.TraceSnapshotStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentVisibleDebugEvidenceBuilderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void preservesEveryTypedLegacyFieldWhenHeartbeatMustBeTruncated() {
        EnumMap<AgentVisibleDebugEvidenceBuilder.Field, String> fields =
                new EnumMap<>(AgentVisibleDebugEvidenceBuilder.Field.class);
        for (AgentVisibleDebugEvidenceBuilder.Field field
                : AgentVisibleDebugEvidenceBuilder.Field.values()) {
            fields.put(field, field.name().toLowerCase(Locale.ROOT));
        }

        AgentVisibleDebugEvidenceBuilder.Snapshot snapshot =
                new AgentVisibleDebugEvidenceBuilder.Snapshot(
                        "AGENT_VISIBLE_DEBUG_HEARTBEAT\n" + "x".repeat(5_000),
                        fields);
        AgentVisibleDebugEvidenceBuilder.Snapshot reparsed =
                AgentVisibleDebugEvidenceBuilder.Snapshot.fromLegacyHeartbeat(
                        snapshot.heartbeatText());

        assertTrue(snapshot.heartbeatText().length() <= 2_400);
        assertTrue(snapshot.heartbeatText().contains("truncated=true"));
        assertEquals(snapshot.fields(), reparsed.fields());
    }

    @Test
    void rehashesFakeHashPrefixInsteadOfTrustingItAsProof() {
        String normalized = ReflectionTestUtils.invokeMethod(
                AgentVisibleDebugEvidenceBuilder.class,
                "hashTraceIdentifier",
                "hash:not-a-proof");
        String valid = ReflectionTestUtils.invokeMethod(
                AgentVisibleDebugEvidenceBuilder.class,
                "hashTraceIdentifier",
                "hash:ABCDEF123456");

        assertFalse("hash:not-a-proof".equals(normalized));
        assertTrue(normalized.matches("hash:[a-f0-9]{12,64}"));
        assertEquals("hash:abcdef123456", valid);
    }

    @Test
    void exposesAllowlistedTypedSnapshotAlongsideCompatibilityDocument() {
        AgentVisibleDebugEvidenceBuilder.Snapshot snapshot =
                AgentVisibleDebugEvidenceBuilder.buildSnapshotForTest(
                        "show current debug status",
                        Map.of(
                                "virtualMatrixCount", 7,
                                "virtualMatrixChunkCount", 2,
                                "virtualMatrixDecision", "observe"),
                        Map.of("status", "OK", "evidenceNeeded", "none"),
                        Map.of("status", "WARN", "evidenceNeeded", "computer_probe_missing"),
                        Map.of("status", "WARN", "evidenceNeeded", "project_scope_missing"));

        assertEquals("OK", snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.BROWSER_STATUS));
        assertEquals("SUPPORTING_EVIDENCE_MISSING",
                snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_STATUS));
        assertEquals("project_scope_missing",
                snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.SUPABASE_EVIDENCE_NEEDED));
        assertEquals("7", snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.MATRIX_COUNT));
        assertEquals(1, snapshot.documents().size());
        assertEquals(snapshot.heartbeatText(), snapshot.documents().get(0).text());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.fields().put(AgentVisibleDebugEvidenceBuilder.Field.MATRIX_COUNT, "8"));
    }

    @Test
    void exposesFingerprintFreeSelectionEntropyOnlyForExplicitDebugEvidenceQuery() {
        SelectionEntropyProjection projection = replayProjection();
        TraceStore.clear();
        try {
            SelectionEntropyTraceSupport.write(projection);

            AgentVisibleDebugEvidenceBuilder.Snapshot snapshot =
                    AgentVisibleDebugEvidenceBuilder.buildSnapshotForTest(
                            "show current debug status",
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of());

            assertEquals("replay", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_MODE));
            assertEquals(ReplaySelectionEntropy.ALGORITHM_VERSION, snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_ALGORITHM));
            assertEquals("matched", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_COHERENCE));
            assertEquals("4", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_DECISION_COUNT));
            assertEquals("3", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_DRAW_COUNT));
            assertEquals("1", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_STABLE_TIE_COUNT));
            assertEquals("0", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_CANDIDATE_DRIFT_COUNT));
            assertEquals("1", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_ROUTER_DRAW_COUNT));
            assertEquals("1", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_STRATEGY_DRAW_COUNT));
            assertEquals("1", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_ENSEMBLE_DRAW_COUNT));
            assertEquals("false", snapshot.value(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_COMPLETION_ORDER));
            assertEquals("", projection.reasonCode());
            assertFalse(snapshot.fields().containsKey(
                    AgentVisibleDebugEvidenceBuilder.Field.SELECTION_ENTROPY_REASON));

            String text = snapshot.heartbeatText();
            assertTrue(text.contains("selectionEntropy.mode=replay"), text);
            assertTrue(text.contains("selectionEntropy.drawCount=3"), text);
            assertFalse(text.contains(projection.seedFingerprint()), text);
            assertFalse(text.contains(projection.decisionDigest()), text);
            assertFalse(text.contains("X-AWX-Selection-Seed"), text);
            assertFalse(text.contains("raw-replay-seed"), text);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void ordinaryQuestionGetsNoSelectionEntropyBreadcrumbAndUnknownPrefixFailsClosed() {
        TraceStore.clear();
        try {
            SelectionEntropyTraceSupport.write(replayProjection());
            AgentVisibleDebugEvidenceBuilder.Snapshot ordinary =
                    AgentVisibleDebugEvidenceBuilder.buildSnapshotForTest(
                            "Share a dinner idea",
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of());

            assertFalse(ordinary.heartbeatText().contains("selectionEntropy."),
                    ordinary.heartbeatText());
            assertFalse(ordinary.fields().keySet().stream()
                    .anyMatch(field -> field.name().startsWith("SELECTION_ENTROPY_")));

            TraceStore.put("selectionEntropy.rawSeed", "raw-replay-seed");
            AgentVisibleDebugEvidenceBuilder.Snapshot invalid =
                    AgentVisibleDebugEvidenceBuilder.buildSnapshotForTest(
                            "show current debug status",
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of());

            assertFalse(invalid.heartbeatText().contains("selectionEntropy."),
                    invalid.heartbeatText());
            assertFalse(invalid.heartbeatText().contains("raw-replay-seed"),
                    invalid.heartbeatText());
            assertFalse(invalid.fields().keySet().stream()
                    .anyMatch(field -> field.name().startsWith("SELECTION_ENTROPY_")));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void rendersAgentVisibleDebugEvidenceForBrowserStatusQuestions() {
        String text = AgentVisibleDebugEvidenceBuilder.buildEvidenceTextForTest(
                "브라우저 UI 점검입니다. 현재 챗봇 상태를 말해줘.",
                Map.of(
                        "virtualMatrixCount", 300,
                        "virtualMatrixChunkCount", 30,
                        "virtualMatrixWeightedScore", 0.266d,
                        "virtualMatrixDecision", "observe",
                        "totalEvents", 4,
                        "warnEvents", 1,
                        "errorEvents", 0,
                        "topTile", "SPRING_CONTEXT",
                        "topFailureClass", "none"),
                Map.of(
                        "status", "OK",
                        "evidenceNeeded", "none",
                        "nextAction", "browser_ui_smoke_current",
                        "reachable", true,
                        "targetContentVisible", true,
                        "screenshotCaptured", true,
                        "surface", "iab",
                        "secretHits", 0),
                Map.of(
                        "status", "OK",
                        "evidenceNeeded", "none",
                        "nextAction", "computer_use_supporting_evidence_current",
                        "guiOnly", true,
                        "countOnly", true),
                Map.of(
                        "status", "WARN",
                        "evidenceNeeded", "supabase_project_scope_or_auth_unverified",
                        "nextAction", "authenticate_supabase_mcp_or_cli",
                        "projectRefEnvStatus", "missing",
                        "authEnvStatus", "missing"));

        assertTrue(text.contains("AGENT_VISIBLE_DEBUG_HEARTBEAT"));
        assertTrue(text.contains("summary=browser:OK computer-use:OK supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe"));
        assertTrue(text.contains("debug.ai.metrics.virtualMatrix.count=300"));
        assertTrue(text.contains("external.browser.status=OK"));
        assertTrue(text.contains("external.computer-use.status=OK"));
        assertTrue(text.contains("external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified"));
    }

    @Test
    void recognizesKoreanDebugStatusQuestions() {
        String text = AgentVisibleDebugEvidenceBuilder.buildEvidenceTextForTest(
                "브라우저 UI 점검입니다. 현재 챗봇 디버그 상태를 말해줘.",
                Map.of("virtualMatrixCount", 300, "virtualMatrixChunkCount", 30, "virtualMatrixDecision", "observe"),
                Map.of("status", "OK", "evidenceNeeded", "none"),
                Map.of("status", "OK", "evidenceNeeded", "none"),
                Map.of("status", "WARN", "evidenceNeeded", "supabase_project_scope_or_auth_unverified"));

        assertTrue(text.contains("summary=browser:OK computer-use:OK supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:observe"));
    }

    @Test
    void demotesBrowserAndComputerMissingProofToSupportingEvidenceInAgentVisibleText() {
        String text = AgentVisibleDebugEvidenceBuilder.buildEvidenceTextForTest(
                "show current browser and computer debug status",
                Map.of("virtualMatrixCount", 300, "virtualMatrixChunkCount", 30, "virtualMatrixDecision", "observe"),
                Map.of(
                        "status", "WARN",
                        "evidenceNeeded", "browser_ui_smoke_missing",
                        "nextAction", "run_browser_local_ui_smoke"),
                Map.of(
                        "status", "WARN",
                        "evidenceNeeded", "computer_use_smoke_missing",
                        "nextAction", "run_computer_use_lightweight_smoke"),
                Map.of(
                        "status", "WARN",
                        "evidenceNeeded", "supabase_project_scope_or_auth_unverified",
                        "nextAction", "authenticate_supabase_mcp_or_cli"));

        assertTrue(text.contains("summary=browser:SUPPORTING_EVIDENCE_MISSING computer-use:SUPPORTING_EVIDENCE_MISSING supabase:supabase_project_scope_or_auth_unverified"));
        assertTrue(text.contains("external.browser.status=SUPPORTING_EVIDENCE_MISSING"));
        assertTrue(text.contains("external.browser.sourceStatus=WARN"));
        assertTrue(text.contains("external.browser.blocking=false"));
        assertTrue(text.contains("external.browser.evidenceScope=local-ui-proof"));
        assertTrue(text.contains("external.computer-use.status=SUPPORTING_EVIDENCE_MISSING"));
        assertTrue(text.contains("external.computer-use.sourceStatus=WARN"));
        assertTrue(text.contains("external.computer-use.blocking=false"));
        assertTrue(text.contains("external.computer-use.evidenceScope=gui-supporting-only"));
        assertTrue(text.contains("external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified"));
    }

    @Test
    void leavesAgentVisibleTraceForOrdinaryChatTurns() {
        String text = AgentVisibleDebugEvidenceBuilder.buildEvidenceTextForTest(
                "ordinary class summary request",
                Map.of("virtualMatrixCount", 300, "virtualMatrixChunkCount", 30, "virtualMatrixDecision", "observe"),
                Map.of("status", "OK", "evidenceNeeded", "none"),
                Map.of("status", "OK", "evidenceNeeded", "none"),
                Map.of("status", "WARN", "evidenceNeeded", "supabase_project_scope_or_auth_unverified"));

        assertTrue(text.contains("AGENT_VISIBLE_DEBUG_HEARTBEAT"));
        assertTrue(text.contains("source=chat-workflow-pre-llm"));
        assertTrue(text.contains("matrix:300/30"));
    }

    @Test
    void rendersWebPrefetchTraceCountsWithoutRawProviderQuery() {
        TraceStore.clear();
        try {
            String rawProviderQuery = "site:developers.openai.com openai.com official docs GPT-5 API model source summary";
            TraceStore.put("chatApi.web.prefetch.stream.resolvedUseWeb", true);
            TraceStore.put("chatApi.web.prefetch.stream.resolvedUseRag", true);
            TraceStore.put("chatApi.web.prefetch.stream.searchMode", "FORCE_DEEP");
            TraceStore.put("chatApi.web.prefetch.stream.topK", 5);
            TraceStore.put("chatApi.web.prefetch.stream.providerQueryHash", "hash:abc123");
            TraceStore.put("chatApi.web.prefetch.stream.providerQueryLength", rawProviderQuery.length());
            TraceStore.put("chatApi.web.prefetch.stream.providerQuerySiteDomain", "developers.openai.com");
            TraceStore.put("chatApi.web.prefetch.stream.snippetCount", 0);

            String text = AgentVisibleDebugEvidenceBuilder.buildEvidenceTextForTest(
                    "show current debug trace prefetch status",
                    Map.of("virtualMatrixCount", 300, "virtualMatrixChunkCount", 30, "virtualMatrixDecision", "observe"),
                    Map.of("status", "OK", "evidenceNeeded", "none"),
                    Map.of("status", "OK", "evidenceNeeded", "none"),
                    Map.of("status", "WARN", "evidenceNeeded", "supabase_project_scope_or_auth_unverified"));

            assertTrue(text.contains("web.prefetch.phase=stream"));
            assertTrue(text.contains("web.prefetch.resolvedUseWeb=true"));
            assertTrue(text.contains("web.prefetch.resolvedUseRag=true"));
            assertTrue(text.contains("web.prefetch.searchMode=FORCE_DEEP"));
            assertTrue(text.contains("web.prefetch.snippetCount=0"));
            assertTrue(text.contains("web.prefetch.providerQuerySiteDomain=developers.openai.com"));
            assertTrue(text.contains("web.prefetch.providerQueryHash=hash:abc123"));
            assertFalse(text.contains(rawProviderQuery));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void directDebugAnswerPredicateDoesNotHijackOrdinaryChatTraceRequests() {
        String ordinaryTraceRequest = "browser UI smoke. keep debug traces available, answer naturally.";

        assertTrue(AgentVisibleDebugEvidenceBuilder.isDebugEvidenceQuery(ordinaryTraceRequest));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(ordinaryTraceRequest));
        String modeQualifiedExplanation = "\uAC80\uC0C9/RAG OFF \uC0C1\uD0DC\uC5D0\uC11C Provider Guard\uC640 Trace\uC758 "
                + "\uC5ED\uD560\uC744 \uB450 \uBB38\uC7A5\uC73C\uB85C \uC124\uBA85\uD558\uACE0 "
                + "\uAC01 \uBB38\uC7A5 \uB05D\uC5D0 [W1] \uB610\uB294 [V2]\uB97C \uBD99\uC5EC\uC918.";
        assertTrue(AgentVisibleDebugEvidenceBuilder.isDebugEvidenceQuery(modeQualifiedExplanation));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(modeQualifiedExplanation));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                "오늘 집중을 높이는 현실적인 방법을 한국어로 세 문장만 알려줘. 디버그 설명 없이 답변만 해줘."));
        assertTrue(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery("show current debug status"));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                "챗봇으로 내 주문 상태를 알려줘"),
                "an ordinary domain status request must not be replaced by the debug heartbeat");
        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                "Show current Model 3 order status"),
                "a product model number must not become a model-health diagnostic request");
        assertTrue(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                "show current model health"),
                "an explicit model-health request should remain eligible for the debug heartbeat");
        assertTrue(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                "짧게 한 문장으로 현재 디버그 상태가 정상인지 말해줘."));
    }

    @Test
    void promptEvidencePredicateRequiresStatusIntentAndRejectsCitationDetours() {
        String citationDetour = "\uAC80\uC0C9/RAG OFF \uC0C1\uD0DC\uC5D0\uC11C Provider Guard\uC640 Trace\uC758 "
                + "\uC5ED\uD560\uC744 \uB450 \uBB38\uC7A5\uC73C\uB85C \uC124\uBA85\uD558\uACE0 "
                + "\uAC01 \uBB38\uC7A5 \uB05D\uC5D0 [W1] \uB610\uB294 [V2]\uB97C \uBD99\uC5EC\uC918.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(
                "browser UI smoke. keep debug traces available, answer naturally."));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(citationDetour));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(
                "챗봇으로 내 주문 상태를 알려줘."));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(
                "Show current Model 3 order status"));
        assertTrue(AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(
                "show current model health"));
        assertTrue(AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(
                "Summarize current Browser/Computer/Supabase evidence status in one paragraph."));
        assertTrue(AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(
                "현재 디버그 상태를 요약해줘."));
    }

    @Test
    void directDebugAnswerPredicateDoesNotHijackWebSearchCitationQuestions() {
        String officialDocsQuestion = "Using official OpenAI documentation, what exact Responses API web search "
                + "tool type is shown? If the exact field is not visible in evidence, answer evidence_needed "
                + "and include source markers.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(officialDocsQuestion));
    }

    @Test
    void directDebugAnswerPredicateDoesNotHijackOfficialModelIdVerification() {
        String officialModelIdQuestion = "공식 공급자 문서 기준으로 gpt-5.5, openai/gpt-oss-120b, "
                + "gemini-2.5-pro가 현재 유효한 모델 ID인지 확인하고, "
                + "불확실하면 evidence_needed라고 답해줘.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(officialModelIdQuestion));
    }

    @Test
    void directDebugAnswerPredicateDoesNotHijackOfficialModelCodeVerification() {
        String officialModelCodeQuestion = "Using official docs, verify whether gpt-5.5, "
                + "openai/gpt-oss-120b, and gemini-2.5-pro are current model codes; "
                + "if any cannot be confirmed, answer evidence_needed.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(officialModelCodeQuestion));
    }

    @Test
    void directDebugAnswerPredicateAcceptsProviderNamesInsideOfficialDocumentationPhrase() {
        String officialProviderDocumentationQuestion =
                "Search official OpenAI, Groq, and Google documentation. Verify whether gpt-5.5, "
                        + "openai/gpt-oss-120b, and gemini-2.5-pro are documented current model codes. "
                        + "Return one line per provider with its official URL; if any cannot be confirmed, "
                        + "answer evidence_needed.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                officialProviderDocumentationQuestion));
    }

    @Test
    void directDebugAnswerPredicateKeepsExplicitCurrentModelHealthLocal() {
        String currentModelHealthQuestion =
                "Using official documentation, confirm the valid model ID shown in current model health.";

        assertTrue(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(currentModelHealthQuestion));
    }

    @Test
    void directDebugAnswerPredicateSeparatesLocalModelStatusFromOfficialIdentifierLookup() {
        String localModelStatusQuestion =
                "Show current model status and verify the model code. "
                        + "Official support says the documentation is stale.";
        String officialModelIdentifierQuestion =
                "Search official OpenAI, Groq, and Google documentation. Confirm whether gpt-5.5, "
                        + "openai/gpt-oss-120b, and gemini-2.5-pro are current model identifiers; "
                        + "if uncertain answer evidence_needed.";

        assertAll(
                () -> assertTrue(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                        localModelStatusQuestion), "local model status must retain the direct debug route"),
                () -> assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                        officialModelIdentifierQuestion), "official model identifiers require retrieval"));
    }

    @Test
    void directDebugAnswerPredicateDoesNotSwallowMixedStatusAndOfficialUrlRequest() {
        String mixedStatusAndOfficialUrlQuestion =
                "Show current model status and list the official OpenAI, Groq, and Google URLs "
                        + "that validate each selected model code; answer evidence_needed if one is missing.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                mixedStatusAndOfficialUrlQuestion),
                "an explicit official-source request must take precedence over the local status fallback");
    }

    @Test
    void directDebugAnswerPredicateDoesNotSwallowKoreanStatusAndOfficialLinkRequest() {
        String mixedStatusAndOfficialLinkQuestion =
                "현재 모델 상태와 각 모델 ID를 검증할 공식 링크를 알려줘.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                mixedStatusAndOfficialLinkQuestion),
                "a Korean official-link request must take precedence over the local status fallback");
    }

    @Test
    void directDebugAnswerPredicateHonorsNegatedOfficialSourceOutput() {
        String localStatusWithoutOfficialSources =
                "Show current model status; do not list official URLs or citations.";

        assertTrue(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(
                localStatusWithoutOfficialSources),
                "negating official-source output must retain the local status route");
    }

    @Test
    void directDebugAnswerPredicateDoesNotHijackRetrievalQualityProbes() {
        String providerQualityProbe = "RAG web search quality probe: compare site:openai.com Responses API "
                + "built-in tools web_search file_search computer_use with site:supabase.com MCP "
                + "read_only project_ref setup. Confirm whether evidence domains are openai.com "
                + "or supabase.com, and report any other domain as a search quality issue.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(providerQualityProbe));

        String negatedDebugStatusProbe = "RAG random probe 2026-07-08: compare OpenAI Responses API "
                + "web_search tooling with Supabase MCP read_only project_ref setup. Use external/official "
                + "evidence if available, do not answer as current UI debug status, and include 3 next "
                + "debugging actions for this app.";

        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(negatedDebugStatusProbe));
    }

    @Test
    void directDebugAnswerPredicateDoesNotHijackKoreanRagDeepWebSearchDiagnostics() {
        String koreanRagDeepProbe = "RAG ON + DEEP 웹검색 진단: 2026년 현재 OpenAI와 Supabase MCP 관련 "
                + "최신 변화가 답변 근거에 반영되는지, 출처 도메인 2개와 함께 한 문단으로 검증해줘.";

        assertTrue(AgentVisibleDebugEvidenceBuilder.isDebugEvidenceQuery(koreanRagDeepProbe));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(koreanRagDeepProbe));
    }

    @Test
    void redactsSecretShapedEvidenceValues() {
        String text = AgentVisibleDebugEvidenceBuilder.buildEvidenceTextForTest(
                "debug status",
                Map.of("virtualMatrixCount", 300, "virtualMatrixChunkCount", 30),
                Map.of("status", "OK", "evidenceNeeded", "Authorization header private-token"),
                Map.of("status", "WARN", "nextAction", "cookie=session-private"),
                Map.of("status", "WARN", "evidenceNeeded", "SUPABASE_ACCESS_TOKEN"));

        assertFalse(text.contains("private-token"));
        assertFalse(text.contains("Authorization"));
        assertFalse(text.contains("cookie=session"));
        assertFalse(text.contains("SUPABASE_ACCESS_TOKEN"));
    }

    @Test
    void marksExternalEvidenceStaleFromGeneratedAtAndTtl() throws Exception {
        var stale = JSON.readTree("""
                {"generatedAt":"2026-06-27T10:42:29Z","staleAfterMinutes":60}
                """);
        var fresh = JSON.readTree("""
                {"generatedAt":"2026-06-27T10:42:29Z","staleAfterMinutes":120}
                """);

        assertTrue(AgentVisibleDebugEvidenceBuilder.isExternalEvidenceStale(
                stale, Instant.parse("2026-06-27T11:50:00Z")));
        assertFalse(AgentVisibleDebugEvidenceBuilder.isExternalEvidenceStale(
                fresh, Instant.parse("2026-06-27T11:10:00Z")));
    }

    @Test
    void invalidExternalEvidenceGeneratedAtLeavesSuppressionBreadcrumb() throws Exception {
        TraceStore.clear();
        try {
            var invalid = JSON.readTree("""
                    {"generatedAt":"not-a-timestamp","staleAfterMinutes":60}
                    """);

            assertTrue(AgentVisibleDebugEvidenceBuilder.isExternalEvidenceStale(
                    invalid, Instant.parse("2026-06-27T11:10:00Z")));

            assertEquals(Boolean.TRUE,
                    TraceStore.get("chat.workflow.suppressed.prompt.agentDebugEvidence.generatedAt"));
            assertEquals("DateTimeParseException",
                    TraceStore.get("chat.workflow.suppressed.prompt.agentDebugEvidence.generatedAt.errorType"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void invalidLocalLlmOperatorNumbersLeaveSuppressionBreadcrumb() {
        TraceStore.clear();
        try {
            TraceStore.put("llm.localSmoke.operatorAction.triggered", false);
            TraceStore.put("llm.localSmoke.operatorAction.failureClass", "none");
            TraceStore.put("llm.localSmoke.operatorAction.actionScore", "not-a-number");
            TraceStore.put("llm.localSmoke.operatorAction.scoreDelta", "still-not-a-number");

            List<Document> docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                    "ordinary chat follow-up",
                    null);

            assertFalse(docs.isEmpty());
            assertEquals(Boolean.TRUE,
                    TraceStore.get("chat.workflow.suppressed.prompt.agentDebugEvidence.number"));
            assertEquals("invalid_number",
                    TraceStore.get("chat.workflow.suppressed.prompt.agentDebugEvidence.number.errorType"));
            assertFalse(docs.get(0).text().contains("not-a-number"));
            assertFalse(docs.get(0).text().contains("still-not-a-number"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void invalidDoubleMetricLeavesSuppressionBreadcrumb() {
        TraceStore.clear();
        try {
            Double value = ReflectionTestUtils.invokeMethod(
                    AgentVisibleDebugEvidenceBuilder.class,
                    "doubleOrFallback",
                    "not-a-number",
                    0.25d);

            assertEquals(0.25d, value);
            assertEquals(Boolean.TRUE,
                    TraceStore.get("chat.workflow.suppressed.prompt.agentDebugEvidence.double"));
            assertEquals("invalid_number",
                    TraceStore.get("chat.workflow.suppressed.prompt.agentDebugEvidence.double.errorType"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void reinjectsChatHarmonyDegradedStateIntoAgentVisibleNamespaces() {
        TraceStore.clear();
        try {
            TraceStore.put("chat.harmony.postprocess.agentVisible", true);
            TraceStore.put("chat.harmony.postprocess.applied", true);
            TraceStore.put("chat.harmony.postprocess.degraded", true);
            TraceStore.put("chat.harmony.postprocess.decision", "fallback_evidence");
            TraceStore.put("chat.harmony.postprocess.reason", "fallback_mode_answer");
            TraceStore.put("chat.harmony.postprocess.weightedScore", 0.433d);
            TraceStore.put("chat.harmony.postprocess.evidenceCount", 0);
            TraceStore.put("debug.ai.metrics.nextAction", "inspect_chat_harmony_trace");
            TraceStore.put("debug.ai.metrics.nextReason", "chat_harmony.fallback_mode_answer");

            List<Document> docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                    "ordinary chat follow-up",
                    null);

            assertFalse(docs.isEmpty());
            String text = docs.get(0).text();
            assertTrue(text.contains("chat.harmony.degraded=true"));
            assertTrue(text.contains("chat.harmony.nextAction=inspect_chat_harmony_trace"));
            assertTrue(text.contains("chat.harmony.nextReason=chat_harmony.fallback_mode_answer"));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("prompt.agentDebugEvidence.chatHarmony.degraded")));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("debug.ai.agentDebugEvidence.chatHarmony.degraded")));
            assertFalse(text.contains("rawAnswer"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("ordinary chat follow-up"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void reinjectsLatestChatHarmonySnapshotAfterNextTurnTraceReset() {
        TraceStore.clear();
        try {
            TraceSnapshotStore snapshotStore = enabledSnapshotStore();
            TraceStore.put("trace.id", "chat-harmony-prior-turn");
            TraceStore.put("sid", "hash:chat-harmony-session");
            TraceStore.put("chat.harmony.postprocess.agentVisible", true);
            TraceStore.put("chat.harmony.postprocess.applied", true);
            TraceStore.put("chat.harmony.postprocess.degraded", true);
            TraceStore.put("chat.harmony.postprocess.decision", "fallback_evidence");
            TraceStore.put("chat.harmony.postprocess.reason", "fallback_mode_answer");
            TraceStore.put("chat.harmony.postprocess.weightedScore", 0.433d);
            TraceStore.put("chat.harmony.postprocess.evidenceCount", 0);
            TraceStore.put("debug.ai.metrics.nextAction", "inspect_chat_harmony_trace");
            TraceStore.put("debug.ai.metrics.nextReason", "chat_harmony.fallback_mode_answer");
            TraceStore.put("chat.harmony.postprocess.rawAnswer", "private prior answer");
            assertTrue(snapshotStore.captureCurrent(
                    "chat-harmony-postprocess", "POST", "/api/chat", 200, null) != null);

            TraceStore.clear();
            TraceStore.put("sid", "hash:chat-harmony-session");
            List<Document> docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                    "ordinary next-turn follow-up",
                    metricsService(snapshotStore));

            assertFalse(docs.isEmpty());
            String text = docs.get(0).text();
            assertTrue(text.contains("chat.harmony.degraded=true"));
            assertTrue(text.contains("chat.harmony.decision=fallback_evidence"));
            assertTrue(text.contains("chat.harmony.reason=fallback_mode_answer"));
            assertTrue(text.contains("chat.harmony.weightedScore=0.433"));
            assertTrue(text.contains("chat.harmony.evidenceCount=0"));
            assertTrue(text.contains("chat.harmony.nextAction=inspect_chat_harmony_trace"));
            assertFalse(text.contains("private prior answer"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void doesNotReinjectChatHarmonySnapshotAcrossSessionBoundary() {
        TraceStore.clear();
        try {
            TraceSnapshotStore snapshotStore = enabledSnapshotStore();
            TraceStore.put("trace.id", "chat-harmony-prior-session");
            TraceStore.put("sid", "hash:prior-chat-session");
            TraceStore.put("chat.harmony.postprocess.agentVisible", true);
            TraceStore.put("chat.harmony.postprocess.degraded", true);
            TraceStore.put("chat.harmony.postprocess.decision", "fallback_evidence");
            TraceStore.put("chat.harmony.postprocess.reason", "prior_session_only");
            assertTrue(snapshotStore.captureCurrent(
                    "chat-harmony-postprocess", "POST", "/api/chat", 200, null) != null);

            TraceStore.clear();
            TraceStore.put("sid", "hash:current-chat-session");
            List<Document> docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                    "ordinary current-session follow-up",
                    metricsService(snapshotStore));

            assertFalse(docs.isEmpty());
            String text = docs.get(0).text();
            assertFalse(text.contains("prior_session_only"));
            assertFalse(text.contains("chat.harmony.decision=fallback_evidence"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void reinjectsOnlyAllowlistedMlaScalarsForSameSessionExplicitDebugProbe() {
        TraceStore.clear();
        try {
            TraceSnapshotStore snapshotStore = enabledSnapshotStore();
            TraceStore.put("trace.id", "mla-prior-turn");
            TraceStore.put("sid", "hash:mla-session");
            TraceStore.put("cihRag.mlaBreadcrumbCount", 3);
            TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
            TraceStore.put("ml.breadcrumbs.v1", "private raw breadcrumb payload");
            assertTrue(snapshotStore.captureCurrent(
                    "mla-breadcrumb", "POST", "/api/chat", 200, null) != null);

            TraceStore.clear();
            TraceStore.put("sid", "hash:mla-session");
            List<Document> docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                    "Show current chat debug health status",
                    metricsService(snapshotStore));

            assertFalse(docs.isEmpty());
            String text = docs.get(0).text();
            assertTrue(text.contains("chat.mla.breadcrumbCount=3"), text);
            assertTrue(text.contains("chat.mla.queryRedacted=true"), text);
            assertFalse(text.contains("ml.breadcrumbs.v1"), text);
            assertFalse(text.contains("private raw breadcrumb payload"), text);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void excludesMlaScalarsFromOrdinaryPromptsAndOtherSessions() {
        TraceStore.clear();
        try {
            TraceSnapshotStore snapshotStore = enabledSnapshotStore();
            TraceStore.put("trace.id", "mla-prior-session");
            TraceStore.put("sid", "hash:mla-prior-session");
            TraceStore.put("cihRag.mlaBreadcrumbCount", 4);
            TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
            assertTrue(snapshotStore.captureCurrent(
                    "mla-breadcrumb", "POST", "/api/chat", 200, null) != null);

            TraceStore.clear();
            TraceStore.put("sid", "hash:mla-current-session");
            String otherSessionText = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                            "Show current chat debug health status",
                            metricsService(snapshotStore)).get(0).text();
            assertFalse(otherSessionText.contains("chat.mla."), otherSessionText);

            TraceStore.clear();
            TraceStore.put("sid", "hash:mla-prior-session");
            String ordinaryText = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                            "Recommend a quiet dinner recipe",
                            metricsService(snapshotStore)).get(0).text();
            assertFalse(ordinaryText.contains("chat.mla."), ordinaryText);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void rejectsNonScalarMlaValuesFromAgentVisibleEvidence() {
        TraceStore.clear();
        try {
            TraceStore.put("sid", "hash:mla-current-session");
            TraceStore.put("cihRag.mlaBreadcrumbCount", "999");
            TraceStore.put("cihRag.breadcrumb.queryRedacted", "false");
            TraceStore.put("ml.breadcrumbs.v1", List.of("private breadcrumb"));

            String text = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                            "Show current chat debug health status",
                            metricsService(enabledSnapshotStore())).get(0).text();

            assertFalse(text.contains("chat.mla."), text);
            assertFalse(text.contains("private breadcrumb"), text);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void excludesCurrentTraceMlaScalarsWhenSessionIdIsMissing() {
        TraceStore.clear();
        try {
            TraceStore.put("cihRag.mlaBreadcrumbCount", 5);
            TraceStore.put("cihRag.breadcrumb.queryRedacted", true);

            String text = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                            "Show current chat debug health status",
                            metricsService(enabledSnapshotStore())).get(0).text();

            assertFalse(text.contains("chat.mla."), text);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void ordinaryStatusSentenceContainingUiSubstringDoesNotExposeMla() {
        TraceStore.clear();
        try {
            TraceStore.put("sid", "hash:mla-current-session");
            TraceStore.put("cihRag.mlaBreadcrumbCount", 6);
            TraceStore.put("cihRag.breadcrumb.queryRedacted", true);

            String query = "What is the status of fruit imports?";
            assertFalse(AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(query));
            String text = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                            query,
                            metricsService(enabledSnapshotStore())).get(0).text();

            assertFalse(text.contains("chat.mla."), text);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void reinjectsTraceMemoryRecoveryIntoAgentVisibleNamespaces() {
        TraceStore.clear();
        try {
            TraceStore.put("traceMemory.triggered", true);
            TraceStore.put("traceMemory.trigger.reason", "context_contamination");
            TraceStore.put("traceMemory.recovery.action", "ESCALATE");
            TraceStore.put("traceMemory.recovery.failureClass", "POLICY");
            TraceStore.put("traceMemory.recovery.quarantine", true);
            TraceStore.put("traceMemory.errorBreak.risk", "BREAK");
            TraceStore.put("traceMemory.cfvm.offered", true);
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowCount", 1);

            List<Document> docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                    "ordinary chat follow-up",
                    null);

            assertFalse(docs.isEmpty());
            String text = docs.get(0).text();
            assertTrue(text.contains("trace.memory.triggered=true"));
            assertTrue(text.contains("trace.memory.reason=context_contamination"));
            assertTrue(text.contains("trace.memory.recoveryAction=ESCALATE"));
            assertTrue(text.contains("trace.memory.nextAction=inspect_trace_memory_quarantine"));
            assertTrue(text.contains("trace.memory.virtualCheckpointLatestKey=traceMemory.virtualCheckpoint.load"));
            assertTrue(text.contains("trace.memory.virtualCheckpointLatestStage=load"));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("prompt.agentDebugEvidence.traceMemory.triggered")));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("debug.ai.agentDebugEvidence.traceMemory.quarantine")));
            assertTrue("traceMemory.virtualCheckpoint.load".equals(
                    TraceStore.get("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestKey")));
            assertFalse(text.contains("memoryCtx"));
            assertFalse(text.contains("rawSnapshot.raw"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void reinjectsTraceMemorySupabaseShadowStatusIntoAgentVisibleNamespaces() {
        TraceStore.clear();
        try {
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowCount", 1);
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowArtifact", "local_snapshot_summary");
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowProjectScope", "project_ref_missing");
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowAvailable", false);
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowComplete", false);
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowReadOnly", true);
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowMutationAllowed", false);
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowEvidenceNeededCount", 3);
            TraceStore.put("traceMemory.rawSnapshot.supabaseShadowNextAction", "set_project_ref");

            List<Document> docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                    "ordinary chat follow-up",
                    null);

            assertFalse(docs.isEmpty());
            String text = docs.get(0).text();
            assertTrue(text.contains("trace.memory.supabaseShadowArtifact=local_snapshot_summary"));
            assertTrue(text.contains("trace.memory.supabaseShadowProjectScope=project_ref_missing"));
            assertTrue(text.contains("trace.memory.supabaseShadowAvailable=false"));
            assertTrue(text.contains("trace.memory.supabaseShadowComplete=false"));
            assertTrue(text.contains("trace.memory.supabaseShadowReadOnly=true"));
            assertTrue(text.contains("trace.memory.supabaseShadowMutationAllowed=false"));
            assertTrue(text.contains("trace.memory.supabaseShadowEvidenceNeededCount=3"));
            assertTrue(text.contains("trace.memory.supabaseShadowNextAction=set_project_ref"));
            assertEquals("project_ref_missing",
                    TraceStore.get("prompt.agentDebugEvidence.traceMemory.supabaseShadowProjectScope"));
            assertEquals(3,
                    TraceStore.get("debug.ai.agentDebugEvidence.traceMemory.supabaseShadowEvidenceNeededCount"));
            assertFalse(text.contains("SUPABASE_ACCESS_TOKEN"));
            assertFalse(text.contains("rawSnapshot.raw"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void createsTraceMemoryCheckpointWhenFallbackDebugEvidenceHasNoPriorFingerprint() {
        TraceStore.clear();
        try {
            List<Document> docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                    "ordinary chat fallback with debug heartbeat evidence",
                    null);

            assertFalse(docs.isEmpty());
            String text = docs.get(0).text();
            assertTrue(text.contains("trace.memory.nextAction=continue_trace_memory_checkpointing"));
            assertTrue(String.valueOf(TraceStore.get("traceMemory.fingerprint.current")).startsWith("hash:"));
            assertTrue("load".equals(TraceStore.get("traceMemory.checkpoint.stage")));
            assertTrue("AgentVisibleDebugEvidenceBuilder".equals(TraceStore.get("traceMemory.checkpoint.source")));
            assertTrue(text.contains("trace.memory.virtualCheckpointLatestKey=traceMemory.virtualCheckpoint.load"));
            assertTrue(text.contains("trace.memory.virtualCheckpointLatestStage=load"));
            assertTrue("continue_trace_memory_checkpointing".equals(
                    TraceStore.get("prompt.agentDebugEvidence.traceMemory.nextAction")));
            assertTrue("traceMemory.virtualCheckpoint.load".equals(
                    TraceStore.get("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestKey")));
            assertFalse(text.contains("ordinary chat fallback with debug heartbeat evidence"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("ordinary chat fallback with debug heartbeat evidence"));
        } finally {
            TraceStore.clear();
        }
    }

    private static DebugAiMetricsService metricsService(TraceSnapshotStore snapshotStore) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("traceSnapshotStore", snapshotStore);
        ObjectProvider<TraceSnapshotStore> provider = factory.getBeanProvider(TraceSnapshotStore.class);
        return new DebugAiMetricsService(new DebugEventStore(), provider);
    }

    private static SelectionEntropyProjection replayProjection() {
        return new SelectionEntropyProjection(
                "awx.selection-entropy.v1",
                "replay",
                ReplaySelectionEntropy.ALGORITHM_VERSION,
                true,
                "matched",
                "012345abcdef",
                "a".repeat(64),
                4,
                3,
                1,
                0,
                1,
                1,
                1,
                false,
                "");
    }

    private static TraceSnapshotStore enabledSnapshotStore() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<TraceHtmlBuilder> htmlProvider = factory.getBeanProvider(TraceHtmlBuilder.class);
        TraceSnapshotStore store = new TraceSnapshotStore(htmlProvider);
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 30);
        ReflectionTestUtils.setField(store, "maxValueLen", 1_000);
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
        ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(store, "denyKeysCsv", "");
        ReflectionTestUtils.setField(store, "captureSample", 1.0d);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 30);
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600_000L);
        ReflectionTestUtils.setField(store, "htmlEnabled", false);
        return store;
    }
}
