package com.example.lms.api;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHarmonyTracePostprocessorTest {

    @Test
    void enrichAddsAgentVisibleHarmonyMetricsWithoutRawAnswerText() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("finalWebTopKCount", 2);
        meta.put("finalVectorTopKCount", 1);

        ChatHarmonyTracePostprocessor.enrich(
                meta,
                "This answer should stay private. Authorization=private-token",
                "ALL_ROUNDER");

        assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.applied"));
        assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.agentVisible"));
        assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
        assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
        assertEquals("answer_flow_balanced", meta.get("chat.harmony.postprocess.reason"));
        assertEquals(Boolean.FALSE, meta.get("prompt.agentDebugEvidence.chatHarmony.degraded"));
        assertEquals(3, meta.get("chat.harmony.postprocess.evidenceCount"));
        assertTrue(((Number) meta.get("chat.harmony.postprocess.weightedScore")).doubleValue() > 0.0d);
        assertFalse(String.valueOf(meta).contains("private-token"));
        assertFalse(String.valueOf(meta).contains("Authorization"));
    }

    @Test
    void enrichClassifiesBlankGuardAndFallbackWithoutRawPayloads() {
        Map<String, Object> blankMeta = new LinkedHashMap<>();
        blankMeta.put("chatApi.emptyFinalText", true);

        ChatHarmonyTracePostprocessor.enrich(blankMeta, "  ", "FALLBACK_EVIDENCE");

        assertEquals("blank_guard", blankMeta.get("chat.harmony.postprocess.decision"));
        assertEquals("blank_answer_guarded", blankMeta.get("chat.harmony.postprocess.reason"));
        assertEquals(Boolean.TRUE, blankMeta.get("chat.harmony.postprocess.degraded"));
        assertEquals(Boolean.TRUE, blankMeta.get("prompt.agentDebugEvidence.chatHarmony.degraded"));
        assertEquals(0, blankMeta.get("chat.harmony.postprocess.answerLength"));
        assertFalse(String.valueOf(blankMeta).contains("FALLBACK_EVIDENCE raw"));
    }

    @Test
    void enrichCopiesVirtualMatrixBreadcrumbsForAgentVisibleChatTrace() {
        TraceStore.clear();
        try {
            TraceStore.put("debug.ai.metrics.virtualMatrix.count", 300);
            TraceStore.put("debug.ai.metrics.virtualMatrix.chunkSize", 10);
            TraceStore.put("debug.ai.metrics.virtualMatrix.chunkCount", 30);
            TraceStore.put("debug.ai.metrics.virtualMatrix.weightedScore", 0.476d);
            TraceStore.put("debug.ai.metrics.virtualMatrix.decision", "investigate_hot_chunk");
            TraceStore.put("debug.ai.metrics.virtualMatrix.hotChunkIndex", 17);
            TraceStore.put("debug.ai.metrics.virtualMatrix.hotChunkRiskScore", 0.77d);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("finalWebTopKCount", 1);
            ChatHarmonyTracePostprocessor.enrich(meta, "A compact answer for trace visibility.", "ALL_ROUNDER");

            assertEquals(Boolean.TRUE, meta.get("debug.ai.metrics.virtualMatrix.agentVisible"));
            assertEquals(300, meta.get("debug.ai.metrics.virtualMatrix.count"));
            assertEquals(10, meta.get("debug.ai.metrics.virtualMatrix.chunkSize"));
            assertEquals(30, meta.get("debug.ai.metrics.virtualMatrix.chunkCount"));
            assertEquals(0.476d, meta.get("debug.ai.metrics.virtualMatrix.weightedScore"));
            assertEquals("investigate_hot_chunk", meta.get("debug.ai.metrics.virtualMatrix.decision"));
            assertEquals(17, meta.get("debug.ai.metrics.virtualMatrix.hotChunkIndex"));
            assertEquals(0.77d, meta.get("debug.ai.metrics.virtualMatrix.hotChunkRiskScore"));
            assertEquals("investigate_debug_ai_hot_chunk", meta.get("debug.ai.metrics.nextAction"));
            assertEquals("investigate_hot_chunk", meta.get("debug.ai.metrics.nextReason"));
            assertEquals("investigate_debug_ai_hot_chunk", TraceStore.get("debug.ai.metrics.nextAction"));
            assertEquals("investigate_hot_chunk", TraceStore.get("debug.ai.metrics.nextReason"));
            assertFalse(String.valueOf(meta).contains("Authorization"));
            assertFalse(String.valueOf(meta).contains("ownerToken"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichMirrorsHarmonyDecisionIntoTraceStoreForAgentDiagnostics() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("finalWebTopKCount", 1);

            ChatHarmonyTracePostprocessor.enrich(meta, "A balanced answer with enough shape for follow-up.", "ALL_ROUNDER");

            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.applied"));
            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.agentVisible"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", TraceStore.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_flow_balanced", TraceStore.get("chat.harmony.postprocess.reason"));
            assertEquals(1, TraceStore.get("chat.harmony.postprocess.evidenceCount"));
            assertEquals("continue_observing_chat_harmony", TraceStore.get("debug.ai.metrics.nextAction"));
            assertEquals("smooth_chat", TraceStore.get("debug.ai.metrics.nextReason"));
            assertEquals("smooth_chat", meta.get("prompt.agentDebugEvidence.chatHarmony.decision"));
            assertEquals("answer_flow_balanced", meta.get("prompt.agentDebugEvidence.chatHarmony.reason"));
            assertEquals(Boolean.FALSE, meta.get("prompt.agentDebugEvidence.chatHarmony.degraded"));
            assertEquals("continue_observing_chat_harmony", meta.get("prompt.agentDebugEvidence.chatHarmony.nextAction"));
            assertEquals("smooth_chat", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.decision"));
            assertEquals("answer_flow_balanced", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.reason"));
            assertEquals(Boolean.FALSE, TraceStore.get("prompt.agentDebugEvidence.chatHarmony.degraded"));
            assertEquals(1, TraceStore.get("prompt.agentDebugEvidence.chatHarmony.evidenceCount"));
            assertEquals("continue_observing_chat_harmony", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.nextAction"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("balanced answer"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichRecordsInvalidCountFallbackWithoutLeakingRawValue() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("finalWebTopKCount", "private-count-value");

            ChatHarmonyTracePostprocessor.enrich(meta, "A balanced answer for parse fallback.", "ALL_ROUNDER");

            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.suppressed.countValue.parse"));
            assertEquals("invalid_number", TraceStore.get("chat.harmony.postprocess.suppressed.countValue.parse.errorType"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("private-count-value"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichTurnsDegradedHarmonyIntoAgentNextActionWithoutRawPayloads() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            ChatHarmonyTracePostprocessor.enrich(
                    meta,
                    "tiny",
                    "FALLBACK_EVIDENCE");

            assertEquals("fallback_evidence", meta.get("chat.harmony.postprocess.decision"));
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("inspect_chat_harmony_trace", meta.get("debug.ai.metrics.nextAction"));
            assertEquals("chat_harmony.fallback_mode_answer", meta.get("debug.ai.metrics.nextReason"));
            assertEquals("fallback_evidence", meta.get("prompt.agentDebugEvidence.chatHarmony.decision"));
            assertEquals("fallback_mode_answer", meta.get("prompt.agentDebugEvidence.chatHarmony.reason"));
            assertEquals(Boolean.TRUE, meta.get("prompt.agentDebugEvidence.chatHarmony.degraded"));
            assertEquals("inspect_chat_harmony_trace", meta.get("prompt.agentDebugEvidence.chatHarmony.nextAction"));
            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.degraded"));
            assertEquals("inspect_chat_harmony_trace", TraceStore.get("debug.ai.metrics.nextAction"));
            assertEquals("chat_harmony.fallback_mode_answer", TraceStore.get("debug.ai.metrics.nextReason"));
            assertEquals("fallback_evidence", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.decision"));
            assertEquals("fallback_mode_answer", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.reason"));
            assertEquals(Boolean.TRUE, TraceStore.get("prompt.agentDebugEvidence.chatHarmony.degraded"));
            assertEquals("inspect_chat_harmony_trace", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.nextAction"));
            assertFalse(String.valueOf(meta).contains("FALLBACK_EVIDENCE tiny"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("tiny"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichTreatsAgentDebugExternalProofFallbackModeAsSmoothChat() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.agentDebugEvidence.directAnswer", Boolean.TRUE);
            String answer = """
                    - Browser: SUPPORTING_EVIDENCE_MISSING (evidence_needed=browser_ui_smoke_stale; stale=true)
                    - Computer: SUPPORTING_EVIDENCE_MISSING (evidence_needed=computer_use_smoke_stale; count-only=true)
                    - Supabase: evidence_needed (read-only only; DB verification not claimed)
                    """;

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "Browser, Computer, Supabase 상태만 3줄로 요약해줘.",
                    answer);
            ChatHarmonyTracePostprocessor.enrich(meta, shaped, "FALLBACK_EVIDENCE");

            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_shape_respected", meta.get("chat.harmony.postprocess.reason"));
            assertEquals("continue_observing_chat_harmony", meta.get("debug.ai.metrics.nextAction"));
            assertEquals(Boolean.FALSE, TraceStore.get("prompt.agentDebugEvidence.chatHarmony.degraded"));
            assertEquals("continue_observing_chat_harmony", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.nextAction"));
            assertFalse(String.valueOf(meta).contains("DB verification not claimed"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("browser_ui_smoke_stale"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichTreatsExactRecentHistoryCodeFallbackAsSmoothChatWithoutRawValue() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.historyFallback.used", Boolean.TRUE);
            meta.put("chat.historyFallback.source", "recent_history");
            meta.put("chat.historyFallback.exactCodeOnly", Boolean.TRUE);
            meta.put("chat.historyFallback.answerKind", "exact_code_only");
            meta.put("chat.historyFallback.valueHash", "abc123def456");
            meta.put("chat.historyFallback.valueLength", 7);

            ChatHarmonyTracePostprocessor.enrich(meta, "\uBCF4\uB77C-914", null);

            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
            assertEquals("history_fallback_exact_answer", meta.get("chat.harmony.postprocess.reason"));
            assertEquals("continue_observing_chat_harmony", meta.get("debug.ai.metrics.nextAction"));
            assertEquals("smooth_chat", meta.get("debug.ai.metrics.nextReason"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", TraceStore.get("chat.harmony.postprocess.decision"));
            assertEquals("history_fallback_exact_answer", TraceStore.get("chat.harmony.postprocess.reason"));
            assertEquals("continue_observing_chat_harmony", TraceStore.get("debug.ai.metrics.nextAction"));
            assertFalse(String.valueOf(meta).contains("\uBCF4\uB77C-914"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("\uBCF4\uB77C-914"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichTreatsExactRecentHistoryValueFallbackAsSmoothChatWithoutRawValue() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.historyFallback.used", Boolean.TRUE);
            meta.put("chat.historyFallback.source", "recent_history");
            meta.put("chat.historyFallback.exactValueOnly", Boolean.TRUE);
            meta.put("chat.historyFallback.answerKind", "exact_value_only");
            meta.put("chat.historyFallback.valueHash", "def456abc123");
            meta.put("chat.historyFallback.valueLength", 3);

            ChatHarmonyTracePostprocessor.enrich(meta, "\uD478\uB978\uB2EC", null);

            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
            assertEquals("history_fallback_exact_answer", meta.get("chat.harmony.postprocess.reason"));
            assertEquals("continue_observing_chat_harmony", meta.get("debug.ai.metrics.nextAction"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.degraded"));
            assertEquals("history_fallback_exact_answer", TraceStore.get("chat.harmony.postprocess.reason"));
            assertFalse(String.valueOf(meta).contains("\uD478\uB978\uB2EC"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("\uD478\uB978\uB2EC"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionCondensesExplicitOneSentenceRequest() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String verbose = """
                    Summary: the user asked for a compact natural greeting.
                    Hello, I can help with that.
                    This answer keeps diagnostic traces internal and explains the policy again.
                    Let me know if you need more detail.
                    """;

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "Please answer in one sentence and keep debug traces internal.",
                    verbose);

            assertEquals("Hello, I can help with that.", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("one_sentence_user_request", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(1, meta.get("chat.harmony.postprocess.shapeFinalSentenceCount"));
            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("one_sentence_user_request", TraceStore.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("diagnostic traces internal"));
            assertFalse(String.valueOf(meta).contains("diagnostic traces internal"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionSuppressesUserQuestionEcho() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String query = "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 아주 짧게 답해줘.";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    query,
                    query);

            assertEquals("검색 결과가 질문과 충분히 맞지 않아 답변을 구성하기 어렵습니다.", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("user_question_echo_suppressed", meta.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("대한민국 수도"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionCondensesExplicitThreeSentenceRequest() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String verbose = """
                    집중력을 높이는 세 가지 현실적인 방법을 간결하게 안내합니다.
                    첫째, 짧은 시간을 정해놓고 집중하는 포커스 타임을 설정하세요.
                    둘째, 외부 자극을 줄이기 위해 조용하고 간결한 환경에서 작업하세요.
                    셋째, 집중력을 키우기 위해 일관된 습관을 만들어 나가세요.
                    이 방법들을 꾸준히 시도해 보세요.
                    """;

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "오늘 집중을 높이는 현실적인 방법을 한국어로 세 문장만 알려줘. 디버그 설명 없이 답변만 해줘.",
                    verbose);

            assertEquals("집중력을 높이는 세 가지 현실적인 방법을 간결하게 안내합니다. "
                    + "첫째, 짧은 시간을 정해놓고 집중하는 포커스 타임을 설정하세요. "
                    + "둘째, 외부 자극을 줄이기 위해 조용하고 간결한 환경에서 작업하세요.", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("sentence_count_user_request", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(3, meta.get("chat.harmony.postprocess.shapeFinalSentenceCount"));
            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("sentence_count_user_request", TraceStore.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("꾸준히 시도"));
            assertFalse(String.valueOf(meta).contains("꾸준히 시도"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionDropsDanglingMarkdownHeadings() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String verbose = """
                    ### 요약
                    현재 RAG/LIGHT 설정으로 집중을 높이는 방법에 대한 구체적인 정보는 검색 결과와 메모리에서 명확히 확인되지 않았습니다.
                    ### 핵심
                    """;

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "현재 RAG/LIGHT 설정으로 집중을 높이는 방법을 한국어로 세 문장만 답해줘. 디버그 설명 없이 답변만 해줘.",
                    verbose);

            assertEquals("현재 RAG/LIGHT 설정으로 집중을 높이는 방법에 대한 구체적인 정보는 검색 결과와 메모리에서 명확히 확인되지 않았습니다.", shaped);
            assertFalse(shaped.contains("###"));
            assertFalse(shaped.endsWith("핵심"));
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("sentence_count_user_request", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(1, meta.get("chat.harmony.postprocess.shapeFinalSentenceCount"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionSkipsEvidenceFallbackDiagnosticWrappers() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String verbose = """
                    ## 검색 결과 요약
                    상세한 답변을 만들 만큼 **공식 문서가 많지는 않지만**, 관련 자료를 확보했습니다.
                    아래 자료의 **스니펫/근거**와 함께, 하단의 **진단(Plan/Mode/Aux/Guard/WebFailSoft)** 을 확인해 주세요.
                    ### 핵심 포인트
                    짧은 집중 시간을 정하고 알림을 줄이세요.
                    """;

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "현재 RAG/LIGHT 설정으로 집중을 높이는 방법을 한국어로 세 문장만 답해줘. 디버그 설명 없이 답변만 해줘.",
                    verbose);

            assertEquals("짧은 집중 시간을 정하고 알림을 줄이세요.", shaped);
            assertFalse(shaped.contains("진단"));
            assertFalse(shaped.contains("포인트"));
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("sentence_count_user_request", meta.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionPrefersMostRelevantEvidenceFallbackSentence() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String verbose = """
                    ## 검색 결과 요약
                    상세한 답변을 만들 만큼 **공식 문서가 많지는 않지만**, 관련 자료를 확보했습니다.
                    ### 핵심 포인트
                    대한민국의 입법, 사법, 행정을 비롯한 중추적인 국가기관이 위치하고 있습니다.
                    서울은 대한민국의 수도입니다.
                    ### 참고 자료
                    1. **[SRC:WEB]** **[서울 - 위키백과]**
                       - 서울은 대한민국의 수도입니다.
                    """;

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 답하고 검색/증거 상태를 짧게 말해줘.",
                    verbose);

            assertEquals("서울은 대한민국의 수도입니다.", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("one_sentence_user_request", meta.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionPrefersCompactEvidenceOverDegradedBanner() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String verbose = """
                    ※ [DEGRADED MODE] LLM 호출이 실패/차단되어 'Evidence-only(LLM-OFF)' 경로로 답변했습니다.
                     - modelUsed: OpenAiChatModel:fallback:evidence

                    기본 모델 응답이 안정적으로 생성되지 않아 검색 근거만 짧게 정리합니다.
                    - [WEB1] 대한민국의 수도는 서울입니다.
                    - [WEB2] 서울은 대한민국의 수도이자 최대 도시입니다.
                    """;

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 아주 짧게 답해줘.",
                    verbose);

            assertEquals("대한민국의 수도는 서울입니다.", shaped);
            assertFalse(shaped.contains("DEGRADED MODE"));
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("one_sentence_user_request", meta.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotPromoteIrrelevantEvidenceFallbackSnippet() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String verbose = """
                    ## 검색 결과 요약
                    상세한 답변을 만들 만큼 **공식 문서가 많지는 않지만**, 관련 자료를 확보했습니다.
                    아래 자료의 **스니펫/근거**와 함께, 하단의 **진단(Plan/Mode/Aux/Guard/WebFailSoft)** 을 확인해 주세요.
                    ### 핵심 포인트
                    재현 방법: killall Spotlight → ⌘Space → '한' 입력하면 'gㅏㄴ'이 입력됨 InputMonitor에 버퍼가 있지만 입력기로 NSEvent는 전달되지 않음.
                    ### 참고 자료
                    1. **[SRC:WEB]** **[한글 입력기 이슈]**
                       - 재현 방법: killall Spotlight → ⌘Space → '한' 입력하면 'gㅏㄴ'이 입력됨
                    """;

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "현재 RAG/LIGHT 설정으로 집중을 높이는 방법을 한국어로 세 문장만 답해줘. 디버그 설명 없이 답변만 해줘.",
                    verbose);

            assertEquals("검색 결과가 질문과 충분히 맞지 않아 답변을 구성하기 어렵습니다.", shaped);
            assertFalse(shaped.contains("Spotlight"));
            assertFalse(shaped.contains("InputMonitor"));
            assertFalse(shaped.contains("재현 방법"));
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("sentence_count_user_request_no_relevant_candidate",
                    meta.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotPromoteGuardFixHintAsAnswer() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String diagnosticOnly =
                    "reason=weak_draft_high_evidence: 근거는 있는데 답변 초안이 약함 → "
                            + "evidence injection/요약 단계(Compression/AnswerSynthesizer) 를 점검하세요.";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 답하고 검색/증거 상태를 짧게 말해줘.",
                    diagnosticOnly);

            assertEquals("검색 결과가 질문과 충분히 맞지 않아 답변을 구성하기 어렵습니다.", shaped);
            assertFalse(shaped.contains("weak_draft_high_evidence"));
            assertFalse(shaped.contains("Compression/AnswerSynthesizer"));
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("internal_diagnostic_answer_suppressed",
                    meta.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionPreservesAgentDebugExternalProofRows() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.agentDebugEvidence.directAnswer", Boolean.TRUE);
            String answer = """
                    - Browser: OK (evidence_needed=none; stale=false)
                    - Computer: OK (evidence_needed=none; stale=false; count-only=true)
                    - Supabase: evidence_needed (supabase_project_scope_or_auth_unverified; read-only only; DB verification not claimed)""";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "\uD604\uC7AC Browser, Computer, Supabase \uC99D\uAC70 \uC0C1\uD0DC\uB97C \uC9E7\uAC8C \uC694\uC57D\uD574\uC918.",
                    answer);

            assertEquals(answer, shaped);
            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("agent_debug_external_proof_preserved", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("agent_debug_external_proof_preserved", TraceStore.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("DB verification not claimed"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionPreservesCurrentUiModeStatusRows() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.uiModeStatus.directAnswer", Boolean.TRUE);
            String answer = """
                    Current UI mode status (request-local evidence):
                    - Search: LIGHT (webSearch=true; requested=true)
                    - RAG: ON (useRag=true)
                    Source: current request controls; no external proof claimed.""";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "\uD604\uC7AC Search\uC640 RAG \uBAA8\uB4DC\uAC00 \uCF1C\uC84C\uB294\uC9C0 \uC9E7\uAC8C \uD655\uC778\uD574\uC918.",
                    answer);

            assertEquals(answer, shaped);
            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("ui_mode_status_preserved", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("ui_mode_status_preserved", TraceStore.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionRemovesUnsupportedCitationMarkersWhenRetrievalOff() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.disambiguation.skipReason", "retrieval_off_direct");
            meta.put("finalWebTopKCount", 0);
            meta.put("finalVectorTopKCount", 0);
            String answer = "Provider Guard keeps optional providers fail-soft [W1]. "
                    + "Trace records redacted breadcrumbs [V2].";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "\uC6B4\uC601 \uC548\uC815\uC131 \uAD00\uC810\uC5D0\uC11C Provider Guard\uC640 Trace\uB97C \uC815\uB9AC\uD574\uC918.",
                    answer);

            assertEquals("Provider Guard keeps optional providers fail-soft. "
                    + "Trace records redacted breadcrumbs.", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("retrieval_off_citation_markers_removed",
                    meta.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(shaped.contains("[W1]"));
            assertFalse(shaped.contains("[V2]"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("Provider Guard"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotInferEvidenceSemanticsFromMetadata() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = confirmedEnabledEmptyEvidenceMeta(0, 0L);
            String answer = "Unsupported fixture claim [W1]. Another unsupported detail follows.";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "근거가 없으면 evidence_needed라고 답하고, 있으면 두 문장으로 답해줘.",
                    answer);

            assertEquals(answer, shaped);
            assertEquals(0, meta.get("prompt.citableEvidenceCount"));
            assertEquals(0L, meta.get("prompt.citableEvidenceRenderedCount"));
            assertFalse("enabled_empty_evidence_needed_user_request".equals(
                    meta.get("chat.harmony.postprocess.shapeReason")));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionLeavesEvidenceDirectiveSynonymsToWorkflowPolicy() {
        List<String> queries = List.of(
                "출처를 찾지 못하면 evidence_needed로 응답해.",
                "When sources are unavailable, evidence_needed라고 답해.",
                "If the evidence is missing, return evidence_needed.",
                "If there's no evidence, return evidence_needed because I can't verify it.",
                "자료가 부족한 경우 evidence_needed를 출력해.");
        Number[] zeros = {0, 0L, 0.0d};

        for (int i = 0; i < queries.size(); i++) {
            TraceStore.clear();
            Map<String, Object> meta = confirmedEnabledEmptyEvidenceMeta(
                    zeros[i % zeros.length], zeros[(i + 1) % zeros.length]);

            String answer = "Unverified answer [V2].";
            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta, queries.get(i), answer);

            assertEquals(answer, shaped, queries.get(i));
        }
        TraceStore.clear();
    }

    @Test
    void shapeAnswerForUserInstructionPassesThroughUncertainOrEvidencePresentMetadata() {
        String answer = "Grounded or uncertain literal [W1] and [A1] remain.";
        String query = "If evidence is missing, return evidence_needed.";

        Map<String, Object> missingKey = confirmedEnabledEmptyEvidenceMeta(0, 0);
        missingKey.remove("prompt.citableEvidenceRenderedCount");
        Map<String, Object> malformedZero = confirmedEnabledEmptyEvidenceMeta("0", 0);
        Map<String, Object> conflictingCount = confirmedEnabledEmptyEvidenceMeta(0, 1);
        Map<String, Object> evidencePresent = confirmedEnabledEmptyEvidenceMeta(0, 0);
        evidencePresent.put("finalVectorTopK", List.of("grounded"));
        Map<String, Object> missingWebCollection = confirmedEnabledEmptyEvidenceMeta(0, 0);
        missingWebCollection.remove("finalWebTopK");
        Map<String, Object> missingVectorCollection = confirmedEnabledEmptyEvidenceMeta(0, 0);
        missingVectorCollection.remove("finalVectorTopK");
        Map<String, Object> noFinalCollection = new LinkedHashMap<>();
        noFinalCollection.put("prompt.citableEvidenceCount", 0);
        noFinalCollection.put("prompt.citableEvidenceRenderedCount", 0);

        for (Map<String, Object> meta : List.of(
                missingKey, malformedZero, conflictingCount, evidencePresent,
                missingWebCollection, missingVectorCollection, noFinalCollection)) {
            TraceStore.clear();
            assertEquals(answer,
                    ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(meta, query, answer),
                    String.valueOf(meta));
        }
        TraceStore.clear();
    }

    @Test
    void shapeAnswerForUserInstructionRejectsNegatedQuotedMetaAndOrdinaryEvidenceNeededText() {
        String answer = "Literal [W1], [A1], and [CODE2] remain unchanged.";
        List<String> queries = List.of(
                "근거가 없으면 evidence_needed라고 답하지 말고 가능한 범위를 설명해줘.",
                "If evidence is missing, do not return evidence_needed.",
                "답은 evidence_needed로 하지 마세요. 근거가 없더라도 가능한 범위를 알려줘.",
                "근거가 없으면 evidence_needed로 답변하지는 마세요.",
                "\"근거가 없으면 evidence_needed라고 답해\"라는 규칙을 설명해줘.",
                "Explain whether 'If evidence is missing, return evidence_needed' is a good rule.",
                "Explain the rule: if evidence is missing, return evidence_needed.",
                "근거가 없는 상태를 어떻게 진단하나요?");

        for (String query : queries) {
            TraceStore.clear();
            Map<String, Object> meta = confirmedEnabledEmptyEvidenceMeta(0.0d, 0L);
            assertEquals(answer,
                    ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(meta, query, answer),
                    query);
        }
        TraceStore.clear();
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotTreatMirroredRetrievalOffListsAsEnabled() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = confirmedEnabledEmptyEvidenceMeta(0, 0);
            meta.put("chatApi.web.prefetch.stream.resolvedUseRag", Boolean.FALSE);
            meta.put("chat.disambiguation.skipReason", "retrieval_off_direct");

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "근거가 없으면 evidence_needed라고 답해.",
                    "Direct answer [W1].");

            assertEquals("Direct answer.", shaped);
            assertEquals("retrieval_off_citation_markers_removed",
                    meta.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionKeepsRetrievalOffPriorityForConflictingMetadata() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = confirmedEnabledEmptyEvidenceMeta(0, 0);
            meta.put("chat.disambiguation.skipReason", "retrieval_off_direct");

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "근거가 없으면 evidence_needed라고 답해.",
                    "Direct answer [W1].");

            assertEquals("Direct answer.", shaped);
            assertEquals("retrieval_off_citation_markers_removed",
                    meta.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotOwnPositiveEvidenceDirectiveSemantics() {
        List<String> queries = List.of(
                "Do not guess; if sources are unavailable, return evidence_needed.",
                "If sources are unavailable, return evidence_needed; otherwise explain what was checked.",
                "If sources are unavailable, return evidence_needed, and do not guess.",
                "근거가 없으면 evidence_needed로 답해, 추측하지 마.");

        for (String query : queries) {
            TraceStore.clear();
            String answer = "Unsupported claim [W1].";
            assertEquals(answer, ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    confirmedEnabledEmptyEvidenceMeta(0, 0), query, answer), query);
        }
        TraceStore.clear();
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotApplyConditionalExactTokenWhenEvidenceIsPresent() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("finalAnswer.releaseAllowed", Boolean.TRUE);
            meta.put("finalAnswer.evidenceReleaseState", "EVIDENCE_PRESENT");
            String answer = "Grounded answer with a citable source [W1].";

            for (String query : List.of(
                    "If evidence is missing, reply with evidence_needed only.",
                    "If evidence is missing, reply with evidence_needed one word only.")) {
                assertEquals(answer, ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                        meta,
                        query,
                        answer), query);
            }
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionStillAppliesUnrelatedExactOutputAfterEvidenceCondition() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("finalAnswer.releaseAllowed", Boolean.TRUE);
            meta.put("finalAnswer.evidenceReleaseState", "EVIDENCE_PRESENT");

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "If sources are missing, return evidence_needed. Otherwise reply with READY only.",
                    "READY with an unnecessary explanation.");

            assertEquals("READY", shaped);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionNeverSynthesizesReservedEvidenceTokenFromNegativeOrQuotedText() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("finalAnswer.releaseAllowed", Boolean.TRUE);
            meta.put("finalAnswer.evidenceReleaseState", "EVIDENCE_PRESENT");
            String answer = "Grounded answer remains visible [W1].";

            for (String query : List.of(
                    "If evidence is missing, do not reply with evidence_needed only; explain.",
                    "Discuss the example: if evidence is missing, reply with \"evidence_needed\" only.")) {
                assertEquals(answer, ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                        meta,
                        query,
                        answer), query);
            }
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionRejectsKoreanAnswerNounNegation() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = confirmedEnabledEmptyEvidenceMeta(0, 0);
            String answer = "Keep this ordinary answer [A1].";

            assertEquals(answer, ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "근거가 없으면 evidence_needed로 답변하지 마세요.",
                    answer));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionRejectsMultiBacktickAndUnrelatedClauseMatches() {
        String answer = "Keep the quoted or unrelated text [A1].";
        List<String> queries = List.of(
                "규칙 예시 ``근거가 없으면 evidence_needed라고 답해``를 검토해줘.",
                "규칙 예시 ````If evidence is missing, return evidence_needed.````를 검토해줘.",
                "If evidence is missing, state the limitation. If the service is offline, return evidence_needed.",
                "If evidence is missing, note the limitation, but if the service is offline, return evidence_needed.");

        for (String query : queries) {
            TraceStore.clear();
            assertEquals(answer, ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    confirmedEnabledEmptyEvidenceMeta(0, 0), query, answer), query);
        }
        TraceStore.clear();
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotBridgeSeparateCurlyQuoteRanges() {
        TraceStore.clear();
        try {
            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    confirmedEnabledEmptyEvidenceMeta(0, 0),
                    "“Example” If evidence is missing, return evidence_needed. “End”",
                    "Unsupported claim [D1].");

            assertEquals("Unsupported claim [D1].", shaped);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotLeaveDanglingMarkdownEmphasis() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.disambiguation.skipReason", "retrieval_off_direct");
            meta.put("finalWebTopKCount", 0);
            meta.put("finalVectorTopKCount", 0);
            String answer = "Provider Guard keeps optional providers fail-soft [W1]. "
                    + "**Core** Trace records redacted breadcrumbs [V2].";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "Provider Guard\uC640 Trace\uB97C \uB450 \uBB38\uC7A5\uC73C\uB85C \uC124\uBA85\uD574\uC918.",
                    answer);

            assertEquals("Provider Guard keeps optional providers fail-soft. "
                    + "Core Trace records redacted breadcrumbs.", shaped);
            assertFalse(shaped.contains("**"));
            assertFalse(shaped.contains("[W1]"));
            assertFalse(shaped.contains("[V2]"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionHonorsExactTokenOnlyRequest() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "\uC815\uD655\uD788 DIRECT_MODE_18240_OK \uD55C \uC904\uB9CC \uB2F5\uD574. \uC124\uBA85 \uAE08\uC9C0.",
                    "DIRECT_MODE_18240_OK \uD55C \uC904");

            assertEquals("DIRECT_MODE_18240_OK", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(1, meta.get("chat.harmony.postprocess.shapeFinalSentenceCount"));
            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output", TraceStore.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("DIRECT_MODE_18240_OK"));
            assertFalse(String.valueOf(meta).contains("DIRECT_MODE_18240_OK"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionTreatsNumericOnlyAnswerAsSatisfied() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "5 \uB354\uD558\uAE30 4\uB294 \uC5BC\uB9C8\uC778\uAC00? \uC22B\uC790\uB9CC \uB2F5\uD574\uC918.",
                    "9");

            assertEquals("9", shaped);
            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output_already_satisfied",
                    meta.get("chat.harmony.postprocess.shapeReason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionTreatsScientificNotationAsNumericOnly() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "1000은 scientific notation으로 얼마야? 숫자만 답해줘.",
                    "1e3");
            ChatHarmonyTracePostprocessor.enrich(meta, shaped, null);

            assertEquals("1e3", shaped);
            assertEquals("exact_user_output_already_satisfied",
                    meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void resultOnlyCommandDoesNotClaimSemanticQualityFromShapeAlone() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "12+8을 계산하고 결과만 말해줘.",
                    "20");
            ChatHarmonyTracePostprocessor.enrich(meta, shaped, "ALL_ROUNDER");

            assertEquals("20", shaped);
            assertFalse(meta.containsKey("chat.harmony.postprocess.shapeApplied"), String.valueOf(meta));
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("evidence_limited", meta.get("chat.harmony.postprocess.decision"));
            assertEquals("short_answer_without_context", meta.get("chat.harmony.postprocess.reason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void resultOnlyScopeNegationConflictAndMultipleValuesDoNotBecomeSentenceLimits() {
        String answer = "첫 번째 결과입니다. 두 번째 결과입니다. 세 번째 결과입니다.";

        for (String query : List.of(
                "검색 결과만 보여줘.",
                "결과만 말하지 말고 풀이도 보여줘.",
                "결과만 말해줘. 그리고 풀이도 두 단계로 설명해줘.",
                "세 실험의 정확도·지연시간·비용 결과만 말해줘.")) {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(meta, query, answer);

            assertEquals(answer, shaped);
            assertFalse(meta.containsKey("chat.harmony.postprocess.shapeApplied"), String.valueOf(meta));
        }
    }

    @Test
    void negatedOrPerItemSentencePhrasesDoNotShapeTheAnswer() {
        String answer = "First result is correct. Second result includes the reasoning.";

        for (String query : List.of(
                "Do not answer in one sentence; explain thoroughly.",
                "One sentence is insufficient; explain thoroughly.",
                "One sentence isn't enough; explain thoroughly.",
                "Use one line per item, with detailed reasoning.",
                "각 항목에 확인할 반례를 한 줄로 붙이고 자세한 근거를 설명해줘.",
                "각 항목에 확인할 반례를\n한 줄로 붙이고 자세한 근거를 설명해줘.",
                "각 사례마다 확인할 반례를 한 줄씩 붙이고 자세한 근거를 설명해줘.")) {
            Map<String, Object> meta = new LinkedHashMap<>();
            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(meta, query, answer);

            assertEquals(answer, shaped, query);
            assertFalse(meta.containsKey("chat.harmony.postprocess.shapeApplied"), query + " " + meta);
        }
    }

    @Test
    void globalOneLineRequestsStillShapeTheAnswer() {
        String answer = "첫 번째 설명입니다. 두 번째 설명입니다.";

        for (String query : List.of(
                "결과가 좋다면 한 줄로 요약해줘.",
                "항목을 하나 골라 한 줄로 답해줘.",
                "각 항목을 비교하되 최종 결론은 한 줄로 답해줘.",
                "각 항목에는 점수를 매기고, 전체 결론은 한 줄로 요약해줘.")) {
            Map<String, Object> meta = new LinkedHashMap<>();
            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(meta, query, answer);

            assertEquals("첫 번째 설명입니다.", shaped, query);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"), query + " " + meta);
            assertEquals("one_sentence_user_request", meta.get("chat.harmony.postprocess.shapeReason"));
        }
    }

    @Test
    void generatedEmptyAnswerFallbackIsAlwaysDegradedEvenWithoutTraceFlag() {
        Map<String, Object> meta = new LinkedHashMap<>();
        String fallback = ChatApiController.emptyFinalTextFallback("일반 질문");

        ChatHarmonyTracePostprocessor.enrich(meta, fallback, "ALL_ROUNDER");

        assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.degraded"));
        assertEquals("blank_guard", meta.get("chat.harmony.postprocess.decision"));
        assertEquals("blank_answer_guarded", meta.get("chat.harmony.postprocess.reason"));
    }

    @Test
    void shapeAnswerForUserInstructionHonorsReplyWithMultiWordLiteralOnlyRequest() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "cont23gamma random walk: reply with cont23gamma ok only.",
                    "\"cont23gamma random walk: reply with cont23gamma ok only\" is a status check. "
                            + "If everything is normal, return \"cont23gamma ok\".");

            assertEquals("cont23gamma ok", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(1, meta.get("chat.harmony.postprocess.shapeFinalSentenceCount"));
            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output", TraceStore.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(meta).contains("random walk"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("status check"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionHonorsReplyWithLiteralOnlyWhenRagEvidenceDetours() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "cont24light random walk: reply with cont24light ok only.",
                    """
                            ## Search result summary
                            Related web evidence was collected.
                            ### Key points
                            - Random walks are fun to analyze.
                            ### Diagnostics
                            - guard.final.action: DEGRADE_EVIDENCE_LIST
                            - guard.degrade.reason: weak_draft_high_evidence
                            """);

            assertEquals("cont24light ok", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(1, meta.get("chat.harmony.postprocess.shapeFinalSentenceCount"));
            assertFalse(String.valueOf(meta).contains("DEGRADE_EVIDENCE_LIST"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("Random walks"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionHonorsKoreanOneWordLiteralRequest() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "cont15 direct ping: ping \uD55C \uB2E8\uC5B4\uB9CC \uB2F5\uD574\uC918.",
                    "## Search result summary\n"
                            + "The generated answer explains unrelated web evidence about network diagnostics.");

            assertEquals("ping", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output", meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(1, meta.get("chat.harmony.postprocess.shapeFinalSentenceCount"));
            assertEquals(Boolean.TRUE, TraceStore.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output", TraceStore.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("network diagnostics"));
            assertFalse(String.valueOf(meta).contains("network diagnostics"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionHonorsQuotedHangulOneWordLiteralWithRoParticle() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "\uAC80\uC99D\uC6A9 \uC9C8\uBB38: '\uAC70\uC9D3' \uD55C \uB2E8\uC5B4\uB85C\uB9CC \uB2F5\uD558\uC138\uC694.",
                    "\uAC70\uC9D3\uC740 \uC0AC\uC2E4\uC774 \uC544\uB2CC \uAC83\uC744 \uB73B\uD569\uB2C8\uB2E4. \uCD94\uAC00 \uC124\uBA85\uC785\uB2C8\uB2E4.");

            assertEquals("\uAC70\uC9D3", shaped);
            assertEquals(Boolean.TRUE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output", meta.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(meta).contains("\uCD94\uAC00 \uC124\uBA85"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("\uCD94\uAC00 \uC124\uBA85"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotInferLiteralFromGenericKoreanOneWordSummaryRequest() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String answer = "\uC694\uC57D\uB41C \uB2F5\uBCC0\uC785\uB2C8\uB2E4.";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "\uB0B4\uC6A9\uC744 \uD55C \uB2E8\uC5B4\uB85C\uB9CC \uC694\uC57D\uD574\uC918.",
                    answer);

            assertEquals(answer, shaped);
            assertFalse(meta.containsKey("chat.harmony.postprocess.shapeApplied"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichTreatsExactUserOutputAsSmoothChatShape() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.harmony.postprocess.shapeApplied", Boolean.TRUE);
            meta.put("chat.harmony.postprocess.shapeReason", "exact_user_output");

            ChatHarmonyTracePostprocessor.enrich(meta, "DIRECT_MODE_18240_OK", "ALL_ROUNDER");

            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_shape_respected", meta.get("chat.harmony.postprocess.reason"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", TraceStore.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_shape_respected", TraceStore.get("chat.harmony.postprocess.reason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichTreatsReplyWithTokenOnlyAlreadySatisfiedAsSmoothChat() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "cont22alpha direct literal ping: reply with cont22alpha only",
                    "cont22alpha");
            ChatHarmonyTracePostprocessor.enrich(meta, shaped, "ALL_ROUNDER");

            assertEquals("cont22alpha", shaped);
            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.shapeApplied"));
            assertEquals("exact_user_output_already_satisfied",
                    meta.get("chat.harmony.postprocess.shapeReason"));
            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_shape_respected", meta.get("chat.harmony.postprocess.reason"));
            assertEquals("continue_observing_chat_harmony", meta.get("debug.ai.metrics.nextAction"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", TraceStore.get("chat.harmony.postprocess.decision"));
            assertEquals("exact_user_output_already_satisfied",
                    TraceStore.get("chat.harmony.postprocess.shapeReason"));
            assertFalse(String.valueOf(meta).contains("direct literal ping"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("direct literal ping"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void shapeAnswerForUserInstructionDoesNotTreatInputHanSentenceAsOneSentenceDirective() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            String answer = "\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0: \uB79C\uB364 \uD14C\uC2A4\uD2B8 \uBA54\uBAA8: \uCF54\uB4DC\uAC12\uC740 DX-73M\uC785\uB2C8\uB2E4. \uC774 \uAC12\uC744 \uAE30\uC5B5\uD574.";

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    meta,
                    "\uB0B4\uAC00 \uCC98\uC74C\uC73C\uB85C \uC785\uB825\uD55C \uBB38\uC7A5 \uADF8\uB300\uB85C \uBCF4\uC5EC\uC918.",
                    answer);

            assertEquals(answer, shaped);
            assertFalse(meta.containsKey("chat.harmony.postprocess.shapeApplied"), String.valueOf(meta));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("DX-73M"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichTreatsShapedOneSentenceAsSmoothChat() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.harmony.postprocess.shapeApplied", Boolean.TRUE);
            meta.put("chat.harmony.postprocess.shapeReason", "one_sentence_user_request");

            ChatHarmonyTracePostprocessor.enrich(meta, "Hello.", "ALL_ROUNDER");

            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_shape_respected", meta.get("chat.harmony.postprocess.reason"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", TraceStore.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_shape_respected", TraceStore.get("chat.harmony.postprocess.reason"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichTreatsAlreadySatisfiedOneSentenceFallbackAsSmoothChatShape() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chat.harmony.postprocess.shapeApplied", Boolean.FALSE);
            meta.put("chat.harmony.postprocess.shapeReason", "one_sentence_user_request_already_satisfied");

            ChatHarmonyTracePostprocessor.enrich(meta, "안녕하세요!", "FALLBACK_EVIDENCE");

            assertEquals(Boolean.FALSE, meta.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", meta.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_shape_respected", meta.get("chat.harmony.postprocess.reason"));
            assertEquals(Boolean.FALSE, TraceStore.get("chat.harmony.postprocess.degraded"));
            assertEquals("smooth_chat", TraceStore.get("chat.harmony.postprocess.decision"));
            assertEquals("answer_shape_respected", TraceStore.get("chat.harmony.postprocess.reason"));
        } finally {
            TraceStore.clear();
        }
    }

    private static Map<String, Object> confirmedEnabledEmptyEvidenceMeta(
            Object citableCount,
            Object renderedCount) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chatApi.web.prefetch.stream.resolvedUseRag", Boolean.TRUE);
        meta.put("finalWebTopK", List.of());
        meta.put("finalVectorTopK", List.of());
        meta.put("prompt.citableEvidenceCount", citableCount);
        meta.put("prompt.citableEvidenceRenderedCount", renderedCount);
        return meta;
    }
}
