package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatHarmonyTracePostprocessor {
    private static final int BALANCED_MIN_CHARS = 40;
    private static final Pattern USER_TOKEN = Pattern.compile("[\\p{L}\\p{Nd}]{2,}");
    private static final Pattern PUBLIC_CITATION_MARKER =
            Pattern.compile("(?<![A-Za-z0-9])\\[(?:W|V|D)\\d{1,3}](?![A-Za-z0-9])");
    private static final Pattern DIRECT_CONDITIONAL_HOLD_REQUEST = Pattern.compile(
            "\\bif\\b(?=[^\\r\\n]{0,200}\\bofficial\\b)"
                    + "(?=[^\\r\\n]{0,200}\\b(?:evidence|source|verification)\\b)"
                    + "[^\\r\\n]{0,200}\\b(?:insufficient|missing|unavailable)\\b"
                    + "[^\\r\\n]{0,80}\\b(?:explicitly\\s+)?(?:answer|reply|respond|return)"
                    + "(?:\\s+with)?\\s+hold\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL_HOLD_META_REQUEST = Pattern.compile(
            "\\b(?:translate|explain|discuss|quote|paraphrase|analy[sz]e|define)\\b"
                    + "|\\bsomeone\\s+(?:might|may|could)\\s+say\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL_HOLD_NEGATION = Pattern.compile(
            "\\b(?:do\\s+not|don't|never|must\\s+not|should\\s+not)\\b"
                    + "[^\\r\\n]{0,80}\\b(?:answer|reply|respond|return)?\\s*(?:with\\s+)?hold\\b"
                    + "|\\b(?:is\\s+not|isn't|not)\\s+(?:insufficient|missing|unavailable)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL_HOLD_ALTERNATIVE = Pattern.compile(
            "\\b(?:unless|except|otherwise)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFLICTING_SENTENCE_LIMIT = Pattern.compile(
            "(\\b(?:do\\s+not|don't)\\b.{0,48}\\b(?:one|two|single)[-\\s]+(?:sentences?|lines?)\\b"
                    + "|\\b(?:one|single)[-\\s]+sentence\\s+(?:is\\s+(?:insufficient|not\\s+enough)|isn't\\s+enough)\\b"
                    + "|\\b(?:one|single)[-\\s]+line\\s+per\\s+(?:item|result)\\b"
                    + "|(?:각\\s*(?:항목|결과|사례)(?:에(?:는)?|마다|별로)|(?:항목|결과|사례)(?:마다|별로))(?:(?!(?:전체|최종|종합)\\s*(?:결론|답변?|요약))[^,，;；.!?]){0,48}한\\s*줄(?:로|씩)?"
                    + "|(?:한|두)\\s*문장(?:으로|만)?\\s*(?:답하지|말하지)\\s*말)",
            Pattern.CASE_INSENSITIVE);
    private static final String NO_RELEVANT_EVIDENCE_ANSWER =
            "검색 결과가 질문과 충분히 맞지 않아 답변을 구성하기 어렵습니다.";
    private static final Set<String> USER_QUERY_STOPWORDS = Set.of(
            "현재", "설정", "방법", "한국어", "문장", "답변", "답해줘", "알려줘", "해줘",
            "디버그", "설명", "없이", "요약", "검색", "결과", "자료", "질문", "세", "두", "네",
            "다섯", "only", "answer", "explain", "debug", "sentence", "sentences", "without");
    private static final List<String> AGENT_VISIBLE_EXTERNAL_KEYS = List.of(
            "prompt.agentDebugEvidence.external.browser.status",
            "prompt.agentDebugEvidence.external.browser.evidenceNeeded",
            "prompt.agentDebugEvidence.external.browser.nextAction",
            "prompt.agentDebugEvidence.external.computerUse.status",
            "prompt.agentDebugEvidence.external.computerUse.evidenceNeeded",
            "prompt.agentDebugEvidence.external.computerUse.nextAction",
            "prompt.agentDebugEvidence.external.supabase.status",
            "prompt.agentDebugEvidence.external.supabase.evidenceNeeded",
            "prompt.agentDebugEvidence.external.supabase.nextAction");
    private static final List<String> AGENT_VISIBLE_LOCAL_LLM_KEYS = List.of(
            "prompt.agentDebugEvidence.localLlm.operatorAction.triggered",
            "prompt.agentDebugEvidence.localLlm.operatorAction.triggerReason",
            "prompt.agentDebugEvidence.localLlm.operatorAction.failureClass",
            "prompt.agentDebugEvidence.localLlm.operatorAction.nextAction",
            "prompt.agentDebugEvidence.localLlm.operatorAction.actionScore",
            "prompt.agentDebugEvidence.localLlm.operatorAction.scoreDelta",
            "prompt.agentDebugEvidence.localLlm.operatorAction.negativeSignalCount",
            "prompt.agentDebugEvidence.localLlm.operatorAction.upstreamStatus",
            "prompt.agentDebugEvidence.localLlm.operatorAction.upstreamFailureClass",
            "prompt.agentDebugEvidence.localLlm.operatorAction.upstreamNextAction");

    private ChatHarmonyTracePostprocessor() {
    }

    static String shapeAnswerForUserInstruction(Map<String, Object> meta, String userQuery, String answer) {
        String text = answer == null ? "" : answer.trim();
        if (text.isBlank()) {
            return answer;
        }
        if (meta != null && Boolean.FALSE.equals(meta.get("finalAnswer.releaseAllowed"))) {
            return answer;
        }
        if (exactRequestedOutputAlreadySatisfied(userQuery, text)) {
            int originalSentenceCount = sentenceCount(text);
            traceShape(meta, false, "exact_user_output_already_satisfied",
                    text.length(), text.length(), originalSentenceCount, originalSentenceCount);
            return answer;
        }
        String exactOutput = exactRequestedOutput(userQuery, text);
        if (evidenceNeededExactCandidate(exactOutput)) {
            exactOutput = null;
        }
        if (exactOutput != null) {
            int originalSentenceCount = sentenceCount(text);
            traceShape(meta, true, "exact_user_output",
                    text.length(), exactOutput.length(), originalSentenceCount, 1);
            return exactOutput;
        }
        String exactOneWordOutput = exactOneWordRequestedOutput(userQuery, text);
        if (evidenceNeededExactCandidate(exactOneWordOutput)) {
            exactOneWordOutput = null;
        }
        if (exactOneWordOutput != null) {
            int originalSentenceCount = sentenceCount(text);
            traceShape(meta, true, "exact_user_output",
                    text.length(), exactOneWordOutput.length(), originalSentenceCount, 1);
            return exactOneWordOutput;
        }
        if (userQuestionEchoAnswer(userQuery, text)) {
            int originalSentenceCount = sentenceCount(text);
            traceShape(meta, true, "user_question_echo_suppressed",
                    text.length(), NO_RELEVANT_EVIDENCE_ANSWER.length(),
                    originalSentenceCount, sentenceCount(NO_RELEVANT_EVIDENCE_ANSWER));
            return NO_RELEVANT_EVIDENCE_ANSWER;
        }
        if (currentUiModeStatusAnswer(meta, text)) {
            int originalSentenceCount = sentenceCount(text);
            traceShape(meta, false, "ui_mode_status_preserved",
                    text.length(), text.length(), originalSentenceCount, originalSentenceCount);
            return answer;
        }
        if (agentDebugExternalProofAnswer(meta, text)) {
            int originalSentenceCount = sentenceCount(text);
            traceShape(meta, false, "agent_debug_external_proof_preserved",
                    text.length(), text.length(), originalSentenceCount, originalSentenceCount);
            return answer;
        }
        if (internalDiagnosticOnlyAnswer(text)) {
            int originalSentenceCount = sentenceCount(text);
            traceShape(meta, true, "internal_diagnostic_answer_suppressed",
                    text.length(), NO_RELEVANT_EVIDENCE_ANSWER.length(),
                    originalSentenceCount, sentenceCount(NO_RELEVANT_EVIDENCE_ANSWER));
            return NO_RELEVANT_EVIDENCE_ANSWER;
        }
        String citationCleaned = stripUnsupportedCitationMarkers(meta, text);
        if (!citationCleaned.equals(text)) {
            int originalSentenceCount = sentenceCount(text);
            int cleanedSentenceCount = sentenceCount(citationCleaned);
            traceShape(meta, true, "retrieval_off_citation_markers_removed",
                    text.length(), citationCleaned.length(), originalSentenceCount, cleanedSentenceCount);
            text = citationCleaned;
            answer = citationCleaned;
        }
        int requestedSentenceCount = requestedSentenceCount(userQuery);
        if (requestedSentenceCount <= 0) {
            return answer;
        }
        boolean evidenceFallback = evidenceFallbackDiagnosticAnswer(text);
        int originalSentenceCount = sentenceCount(text);
        if (officialEvidenceInsufficientFallback(text)
                && explicitConditionalHoldRequest(userQuery)) {
            traceShape(meta, true, "conditional_hold_user_request",
                    text.length(), "HOLD".length(), originalSentenceCount, 1);
            return "HOLD";
        }
        if (originalSentenceCount <= requestedSentenceCount && text.length() <= maxSatisfiedAnswerChars(requestedSentenceCount)) {
            if (containsMarkdownHeading(text)) {
                String cleaned = firstUserFacingSentences(text, requestedSentenceCount, userQuery, evidenceFallback);
                if (cleaned != null && !cleaned.isBlank() && !cleaned.equals(text)) {
                    int cleanedSentenceCount = sentenceCount(cleaned);
                    traceShape(meta, true, sentenceShapeReason(requestedSentenceCount, false),
                            text.length(), cleaned.length(), originalSentenceCount, cleanedSentenceCount);
                    return cleaned;
                }
                if (evidenceFallback && (cleaned == null || cleaned.isBlank())) {
                    traceShape(meta, true, sentenceShapeReason(requestedSentenceCount, false) + "_no_relevant_candidate",
                            text.length(), NO_RELEVANT_EVIDENCE_ANSWER.length(),
                            originalSentenceCount, sentenceCount(NO_RELEVANT_EVIDENCE_ANSWER));
                    return NO_RELEVANT_EVIDENCE_ANSWER;
                }
            }
            traceShape(meta, false, sentenceShapeReason(requestedSentenceCount, true),
                    text.length(), text.length(), originalSentenceCount, originalSentenceCount);
            return text;
        }
        String shaped = firstUserFacingSentences(text, requestedSentenceCount, userQuery, evidenceFallback);
        if (evidenceFallback && (shaped == null || shaped.isBlank())) {
            traceShape(meta, true, sentenceShapeReason(requestedSentenceCount, false) + "_no_relevant_candidate",
                    text.length(), NO_RELEVANT_EVIDENCE_ANSWER.length(),
                    originalSentenceCount, sentenceCount(NO_RELEVANT_EVIDENCE_ANSWER));
            return NO_RELEVANT_EVIDENCE_ANSWER;
        }
        if (shaped == null || shaped.isBlank() || shaped.equals(text)) {
            traceShape(meta, false, sentenceShapeReason(requestedSentenceCount, false) + "_no_safe_candidate",
                    text.length(), text.length(), originalSentenceCount, originalSentenceCount);
            return text;
        }
        int shapedSentenceCount = sentenceCount(shaped);
        traceShape(meta, true, sentenceShapeReason(requestedSentenceCount, false),
                text.length(), shaped.length(), originalSentenceCount, shapedSentenceCount);
        return shaped;
    }

    static void enrich(Map<String, Object> meta, String answer, String answerMode) {
        if (meta == null) {
            return;
        }
        String text = answer == null ? "" : answer.trim();
        int answerLength = text.length();
        int sentenceCount = sentenceCount(text);
        int evidenceCount = evidenceCount(meta);
        boolean blankGuarded = answerLength == 0
                || truthy(meta.get("chatApi.emptyFinalText"))
                || ChatApiController.isEmptyFinalTextFallback(text);
        boolean fallbackMode = containsIgnoreCase(answerMode, "fallback")
                || containsIgnoreCase(String.valueOf(meta.get("answer.mode")), "fallback");
        String shapeReason = traceLabel(meta.get("chat.harmony.postprocess.shapeReason"), "");
        boolean shapeRespected = "one_sentence_user_request".equals(shapeReason)
                || "one_sentence_user_request_already_satisfied".equals(shapeReason)
                || "sentence_count_user_request".equals(shapeReason)
                || "sentence_count_user_request_already_satisfied".equals(shapeReason)
                || "exact_user_output".equals(shapeReason)
                || "exact_user_output_already_satisfied".equals(shapeReason)
                || "ui_mode_status_preserved".equals(shapeReason)
                || "agent_debug_external_proof_preserved".equals(shapeReason);
        boolean exactHistoryFallback = exactHistoryFallback(meta);

        String decision;
        String reason;
        if (blankGuarded) {
            decision = "blank_guard";
            reason = "blank_answer_guarded";
        } else if (shapeRespected) {
            decision = "smooth_chat";
            reason = "answer_shape_respected";
        } else if (exactHistoryFallback) {
            decision = "smooth_chat";
            reason = "history_fallback_exact_answer";
        } else if (fallbackMode) {
            decision = "fallback_evidence";
            reason = "fallback_mode_answer";
        } else if (evidenceCount == 0 && answerLength < BALANCED_MIN_CHARS) {
            decision = "evidence_limited";
            reason = "short_answer_without_context";
        } else {
                decision = "smooth_chat";
            reason = "answer_flow_balanced";
        }

        boolean degraded = !"smooth_chat".equals(decision);
        double weightedScore = exactHistoryFallback
                ? 0.65d
                : roundedScore(answerLength, sentenceCount, evidenceCount, fallbackMode, blankGuarded);
        meta.put("chat.harmony.postprocess.applied", Boolean.TRUE);
        meta.put("chat.harmony.postprocess.agentVisible", Boolean.TRUE);
        meta.put("chat.harmony.postprocess.degraded", degraded);
        meta.put("chat.harmony.postprocess.decision", decision);
        meta.put("chat.harmony.postprocess.reason", reason);
        meta.put("chat.harmony.postprocess.weightedScore", weightedScore);
        meta.put("chat.harmony.postprocess.answerLength", answerLength);
        meta.put("chat.harmony.postprocess.sentenceCount", sentenceCount);
        meta.put("chat.harmony.postprocess.evidenceCount", evidenceCount);
        meta.put("prompt.agentDebugEvidence.chatHarmony.applied", Boolean.TRUE);
        meta.put("prompt.agentDebugEvidence.chatHarmony.agentVisible", Boolean.TRUE);
        meta.put("prompt.agentDebugEvidence.chatHarmony.degraded", degraded);
        meta.put("prompt.agentDebugEvidence.chatHarmony.decision", decision);
        meta.put("prompt.agentDebugEvidence.chatHarmony.reason", reason);
        meta.put("prompt.agentDebugEvidence.chatHarmony.weightedScore", weightedScore);
        meta.put("prompt.agentDebugEvidence.chatHarmony.evidenceCount", evidenceCount);
        traceHarmonyBreadcrumbs(degraded, decision, reason, weightedScore, answerLength, sentenceCount, evidenceCount);
        copyVirtualMatrixBreadcrumbs(meta);
        promoteLlmStageBoundaryUpstream(meta);
        copyAgentVisibleExternalBreadcrumbs(meta);
        copyAgentVisibleLocalLlmBreadcrumbs(meta);
        traceDebugAiNextAction(meta, decision, reason);
    }

    private static boolean exactHistoryFallback(Map<String, Object> meta) {
        if (meta == null) {
            return false;
        }
        String answerKind = traceLabel(meta.get("chat.historyFallback.answerKind"), "");
        return truthy(meta.get("chat.historyFallback.exactCodeOnly"))
                || truthy(meta.get("chat.historyFallback.exactValueOnly"))
                || "exact_code_only".equalsIgnoreCase(answerKind)
                || "exact_value_only".equalsIgnoreCase(answerKind);
    }

    private static boolean agentDebugExternalProofAnswer(Map<String, Object> meta, String answer) {
        if (meta == null || !truthy(meta.get("chat.agentDebugEvidence.directAnswer"))) {
            return false;
        }
        String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return lower.contains("browser:")
                && lower.contains("computer:")
                && lower.contains("supabase:");
    }

    private static boolean currentUiModeStatusAnswer(Map<String, Object> meta, String answer) {
        if (meta == null || !truthy(meta.get("chat.uiModeStatus.directAnswer"))) {
            return false;
        }
        String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return lower.contains("search:")
                && lower.contains("rag:")
                && lower.contains("request-local evidence");
    }

    private static String stripUnsupportedCitationMarkers(Map<String, Object> meta, String answer) {
        if (answer == null || answer.isBlank() || !retrievalOffWithoutEvidence(meta)) {
            return answer == null ? "" : answer;
        }
        String cleaned = PUBLIC_CITATION_MARKER.matcher(answer).replaceAll("");
        if (cleaned.equals(answer)) {
            return answer;
        }
        return stripMarkdownEmphasisArtifacts(cleaned)
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\s+([.,!?;:])", "$1")
                .replaceAll("(?m)^\\s+$", "")
                .trim();
    }

    private static boolean retrievalOffWithoutEvidence(Map<String, Object> meta) {
        if (meta == null || evidenceCount(meta) > 0) {
            return false;
        }
        String skipReason = traceLabel(meta.get("chat.disambiguation.skipReason"), "");
        String guardSkipped = traceLabel(meta.get("answer.guardRecovery.skipped"), "");
        return "retrieval_off_direct".equals(skipReason)
                || "retrieval_off_direct".equals(guardSkipped);
    }

    private static String stripMarkdownEmphasisArtifacts(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        return value
                .replaceAll("\\*\\*([^*\\r\\n]{1,240})\\*\\*", "$1")
                .replaceAll("(?<!\\*)\\*\\*(?!\\*)", "");
    }

    private static boolean explicitOneSentenceRequest(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (CONFLICTING_SENTENCE_LIMIT.matcher(lower).find()) {
            return false;
        }
        return lower.contains("one sentence")
                || lower.contains("single sentence")
                || lower.contains("one-line")
                || lower.contains("one line")
                || lower.contains("briefly")
                || lower.contains("concise")
                || containsStandaloneKoreanPhrase(lower, "\ud55c \ubb38\uc7a5")
                || containsStandaloneKoreanPhrase(lower, "\ud55c\ubb38\uc7a5")
                || containsStandaloneKoreanPhrase(lower, "\ud55c \uc904")
                || containsStandaloneKoreanPhrase(lower, "\ud55c\uc904")
                || lower.contains("\uac04\uacb0")
                || lower.contains("\uc9e7\uac8c");
    }

    private static int requestedSentenceCount(String query) {
        if (explicitOneSentenceRequest(query)) {
            return 1;
        }
        if (query == null || query.isBlank()) {
            return 0;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        int english = englishSentenceLimit(lower);
        if (english > 0) {
            return english;
        }
        return koreanSentenceLimit(lower);
    }

    private static int englishSentenceLimit(String lower) {
        if (lower == null || lower.isBlank()) {
            return 0;
        }
        Matcher digit = Pattern.compile("\\b([2-5])\\s*(?:sentences?|lines?)\\s*(?:only|max|maximum|or less)?\\b")
                .matcher(lower);
        if (digit.find()) {
            return Integer.parseInt(digit.group(1));
        }
        Matcher word = Pattern.compile("\\b(two|three|four|five)\\s+(?:sentences?|lines?)\\s*(?:only|max|maximum|or less)?\\b")
                .matcher(lower);
        if (word.find()) {
            return switch (word.group(1)) {
                case "two" -> 2;
                case "three" -> 3;
                case "four" -> 4;
                case "five" -> 5;
                default -> 0;
            };
        }
        return 0;
    }

    private static int koreanSentenceLimit(String lower) {
        if (lower == null || lower.isBlank()) {
            return 0;
        }
        return firstPositiveSentenceLimit(
                koreanSentenceLimit(lower, "(?:\ub450|2)", 2),
                koreanSentenceLimit(lower, "(?:\uc138|3)", 3),
                koreanSentenceLimit(lower, "(?:\ub124|4)", 4),
                koreanSentenceLimit(lower, "(?:\ub2e4\uc12f|5)", 5));
    }

    private static int koreanSentenceLimit(String lower, String numberPattern, int value) {
        String pattern = numberPattern
                + "\\s*\ubb38\uc7a5\\s*(?:\ub9cc|\uc774\ub0b4|\uc548\uc73c\ub85c|\ub0b4\ub85c|\uc73c\ub85c|\ub85c)";
        return Pattern.compile(pattern).matcher(lower).find() ? value : 0;
    }

    private static int firstPositiveSentenceLimit(int... values) {
        if (values == null) {
            return 0;
        }
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static int maxSatisfiedAnswerChars(int requestedSentenceCount) {
        return Math.max(220, Math.min(900, requestedSentenceCount * 240));
    }

    private static String sentenceShapeReason(int requestedSentenceCount, boolean alreadySatisfied) {
        if (requestedSentenceCount <= 1) {
            return alreadySatisfied ? "one_sentence_user_request_already_satisfied" : "one_sentence_user_request";
        }
        return alreadySatisfied ? "sentence_count_user_request_already_satisfied" : "sentence_count_user_request";
    }

    private static boolean exactRequestedOutputAlreadySatisfied(String query, String answer) {
        if (numericOnlyOutputAlreadySatisfied(query, answer)) {
            return true;
        }
        if (!explicitExactOutputRequest(query) || answer == null || answer.isBlank()) {
            return false;
        }
        String requested = requestedExactOutputValue(query);
        if (requested == null || requested.isBlank()) {
            return false;
        }
        return requested.equals(stripWrappingPunctuation(answer.trim()));
    }

    private static boolean numericOnlyOutputAlreadySatisfied(String query, String answer) {
        if (query == null || query.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        boolean numericOnlyRequested = lower.contains("\uC22B\uC790\uB9CC")
                || lower.contains("only the number")
                || lower.contains("number only")
                || lower.contains("numeric only");
        if (!numericOnlyRequested) {
            return false;
        }
        return answer.trim().matches("[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    }

    private static String exactRequestedOutput(String query, String answer) {
        if (!explicitExactOutputRequest(query) || answer == null || answer.isBlank()) {
            return null;
        }
        String literal = requestedExactLiteral(query);
        String requested = (literal == null || literal.isBlank()) ? requestedExactToken(query) : literal;
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String normalizedAnswer = stripWrappingPunctuation(answer.trim());
        if (requested.equals(normalizedAnswer)) {
            return null;
        }
        if (literal != null && !literal.isBlank()) {
            return requested;
        }
        if (!normalizedAnswer.startsWith(requested)) {
            return null;
        }
        String remainder = normalizedAnswer.substring(requested.length()).trim();
        if (!trailingExactInstructionEcho(remainder)) {
            return null;
        }
        return requested;
    }

    private static boolean evidenceNeededExactCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return candidate.trim().matches(
                "(?i)evidence_needed(?:\\s+(?:as\\s+)?(?:one|single)\\s+word)?");
    }

    private static boolean userQuestionEchoAnswer(String query, String answer) {
        if (query == null || query.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        String normalizedQuery = normalizeEchoComparableText(query);
        String normalizedAnswer = normalizeEchoComparableText(answer);
        return !normalizedQuery.isBlank() && normalizedQuery.equals(normalizedAnswer);
    }

    private static String normalizeEchoComparableText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .trim();
    }

    private static boolean explicitExactOutputRequest(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        boolean exactIntent = lower.contains("exactly")
                || lower.contains("return exact")
                || lower.contains("reply exact")
                || Pattern.compile("\\b(?:reply|respond|answer|return)\\s+with\\b").matcher(lower).find()
                || lower.contains("\uc815\ud655\ud788");
        boolean outputOnlyIntent = lower.contains("only")
                || lower.contains("no explanation")
                || lower.contains("without explanation")
                || lower.contains("\ub9cc")
                || lower.contains("\uc124\uba85 \uae08\uc9c0");
        return exactIntent && outputOnlyIntent;
    }

    private static String requestedExactOutputValue(String query) {
        String literal = requestedExactLiteral(query);
        if (literal != null && !literal.isBlank()) {
            return literal;
        }
        return requestedExactToken(query);
    }

    private static String requestedExactLiteral(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Pattern marker = Pattern.compile(
                "(?iu)\\b(?:reply|respond|answer|return)\\s+with\\s+(.+?)\\s+"
                        + "(?:only|no\\s+explanation|without\\s+explanation)\\b");
        Matcher matcher = marker.matcher(query);
        String requested = null;
        while (matcher.find()) {
            requested = normalizeExactLiteralCandidate(matcher.group(1));
        }
        return requested == null || requested.isBlank() ? null : requested;
    }

    private static String normalizeExactLiteralCandidate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = stripWrappingPunctuation(value)
                .replaceAll("\\s+", " ")
                .trim()
                .replaceFirst("(?i)^(?:exactly|exact)\\s+", "")
                .trim();
        if (normalized.length() < 2 || normalized.length() > 120) {
            return "";
        }
        if (!Pattern.compile("[\\p{L}\\p{Nd}]").matcher(normalized).find()) {
            return "";
        }
        return normalized;
    }

    private static String requestedExactToken(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\b[A-Za-z0-9][A-Za-z0-9_.:-]{2,80}\\b").matcher(query);
        String best = null;
        while (matcher.find()) {
            String token = matcher.group();
            if (exactRequestStopToken(token)) {
                continue;
            }
            if (best == null || token.length() > best.length()) {
                best = token;
            }
        }
        return best;
    }

    private static String exactOneWordRequestedOutput(String query, String answer) {
        if (!explicitOneWordOnlyRequest(query) || answer == null || answer.isBlank()) {
            return null;
        }
        String requested = requestedOneWordToken(query);
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String normalizedAnswer = stripWrappingPunctuation(answer.trim());
        if (requested.equals(normalizedAnswer)) {
            return null;
        }
        return requested;
    }

    private static boolean explicitOneWordOnlyRequest(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        boolean oneWordIntent = lower.contains("one word")
                || lower.contains("single word")
                || containsStandaloneKoreanPhrase(lower, "\uD55C \uB2E8\uC5B4")
                || containsStandaloneKoreanPhrase(lower, "\uD55C\uB2E8\uC5B4");
        boolean outputOnlyIntent = lower.contains("only")
                || lower.contains("\uB9CC")
                || lower.contains("no explanation")
                || lower.contains("without explanation")
                || lower.contains("\uC124\uBA85 \uAE08\uC9C0");
        return oneWordIntent && outputOnlyIntent;
    }

    private static String requestedOneWordToken(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Pattern quotedTokenBeforeMarker = Pattern.compile(
                "(?iu)[`\"'\u201C\u201D\u2018\u2019]"
                        + "([\\p{L}\\p{Nd}][\\p{L}\\p{Nd}_.:-]{0,80})"
                        + "[`\"'\u201C\u201D\u2018\u2019]\\s*"
                        + "(?:\uD55C\\s*\uB2E8\uC5B4(?:\uB85C)?\\s*\uB9CC|one\\s+word\\s+only|single\\s+word\\s+only)");
        Matcher quotedMatcher = quotedTokenBeforeMarker.matcher(query);
        String requested = null;
        while (quotedMatcher.find()) {
            requested = quotedMatcher.group(1);
        }
        if (requested != null) {
            return requested;
        }
        Pattern tokenBeforeMarker = Pattern.compile(
                "(?iu)\\b([A-Za-z0-9][A-Za-z0-9_.:-]{0,80})\\b\\s*"
                        + "(?:\uD55C\\s*\uB2E8\uC5B4(?:\uB85C)?\\s*\uB9CC|one\\s+word\\s+only|single\\s+word\\s+only)");
        Matcher matcher = tokenBeforeMarker.matcher(query);
        while (matcher.find()) {
            requested = matcher.group(1);
        }
        return requested;
    }

    private static boolean exactRequestStopToken(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.equals("reply")
                || lower.equals("respond")
                || lower.equals("return")
                || lower.equals("answer")
                || lower.equals("with")
                || lower.equals("exactly")
                || lower.equals("exact")
                || lower.equals("only")
                || lower.equals("line")
                || lower.equals("one")
                || lower.equals("single")
                || lower.equals("sentence")
                || lower.equals("explanation")
                || lower.equals("without")
                || lower.equals("please");
    }

    private static String stripWrappingPunctuation(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.trim();
        stripped = stripped.replaceAll("^[`\"'\\u201C\\u201D\\u2018\\u2019]+", "");
        stripped = stripped.replaceAll("[`\"'\\u201C\\u201D\\u2018\\u2019.。!！?？]+$", "");
        return stripped.trim();
    }

    private static boolean trailingExactInstructionEcho(String remainder) {
        if (remainder == null) {
            return false;
        }
        String normalized = stripWrappingPunctuation(remainder)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalized.matches("(?:one[- ]?line|single[- ]?line|one sentence|single sentence)")
                || normalized.matches("(?:\ud55c\\s*\uc904|\ud55c\uc904|\ud55c\\s*\ubb38\uc7a5|\ud55c\ubb38\uc7a5)(?:\ub9cc)?")
                || normalized.equals("only")
                || normalized.equals("\ub9cc");
    }

    private static boolean containsStandaloneKoreanPhrase(String text, String phrase) {
        if (text == null || text.isBlank() || phrase == null || phrase.isBlank()) {
            return false;
        }
        int index = -1;
        while ((index = text.indexOf(phrase, index + 1)) >= 0) {
            if (index == 0 || !isHangulSyllable(text.charAt(index - 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHangulSyllable(char ch) {
        return ch >= '\uAC00' && ch <= '\uD7A3';
    }

    private static String firstUserFacingSentence(String text) {
        return firstUserFacingSentences(text, 1);
    }

    private static String firstUserFacingSentences(String text, int maxSentences) {
        return firstUserFacingSentences(text, maxSentences, "", false);
    }

    private static String firstUserFacingSentences(String text, int maxSentences,
                                                   String userQuery,
                                                   boolean requireQueryRelevance) {
        int limit = Math.max(1, Math.min(5, maxSentences));
        List<String> candidates = sentenceSegments(text);
        ArrayList<String> normalizedCandidates = new ArrayList<>();
        ArrayList<String> highSignal = new ArrayList<>();
        for (String candidate : candidates) {
            String normalized = normalizeCandidateSentence(candidate);
            if (!normalized.isBlank()) {
                normalizedCandidates.add(normalized);
                if (!lowSignalWrapperSentence(normalized)) {
                    highSignal.add(normalized);
                }
            }
        }
        List<String> source = highSignal.isEmpty() ? normalizedCandidates : highSignal;
        if (requireQueryRelevance && !source.isEmpty()) {
            List<String> keywords = userQueryKeywords(userQuery);
            if (!keywords.isEmpty()) {
                ArrayList<String> relevant = new ArrayList<>();
                String bestSingleSentence = null;
                int bestSingleSentenceScore = 0;
                for (String candidate : source) {
                    int score = queryRelevanceScore(candidate, keywords);
                    if (score > 0) {
                        relevant.add(candidate);
                        if (limit == 1 && score > bestSingleSentenceScore) {
                            bestSingleSentence = candidate;
                            bestSingleSentenceScore = score;
                        }
                    }
                }
                if (relevant.isEmpty()) {
                    return "";
                }
                source = limit == 1 && bestSingleSentence != null
                        ? List.of(bestSingleSentence)
                        : relevant;
            }
        }
        if (!source.isEmpty()) {
            return String.join(" ", source.subList(0, Math.min(limit, source.size())));
        }
        return text == null ? "" : text.trim();
    }

    private static boolean evidenceFallbackDiagnosticAnswer(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("## 검색 결과 요약")
                || text.contains("### 참고 자료")
                || text.contains("[DEGRADED MODE]")
                || text.contains("검색 근거만")
                || text.contains("진단(Plan/Mode/Aux/Guard/WebFailSoft)")
                || text.contains("guard.degradedToEvidence");
    }

    private static boolean officialEvidenceInsufficientFallback(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.stripLeading()
                .toLowerCase(Locale.ROOT)
                .startsWith("evidence_needed: official/changelog evidence is missing");
    }

    private static boolean explicitConditionalHoldRequest(String userQuery) {
        if (userQuery == null || userQuery.isBlank()
                || CONDITIONAL_HOLD_META_REQUEST.matcher(userQuery).find()
                || CONDITIONAL_HOLD_NEGATION.matcher(userQuery).find()
                || CONDITIONAL_HOLD_ALTERNATIVE.matcher(userQuery).find()) {
            return false;
        }
        Matcher matcher = DIRECT_CONDITIONAL_HOLD_REQUEST.matcher(userQuery);
        while (matcher.find()) {
            if (!insidePairedQuote(userQuery, matcher.start())) {
                return true;
            }
        }
        return false;
    }

    private static boolean insidePairedQuote(String value, int offset) {
        return insideSameDelimiter(value, offset, '"')
                || insideSingleQuoteSpan(value, offset)
                || insideBacktickSpan(value, offset)
                || insideDelimitedRange(value, offset, '“', '”')
                || insideDelimitedRange(value, offset, '‘', '’');
    }

    private static boolean insideSingleQuoteSpan(String value, int offset) {
        int openIndex = -1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '\'' || apostropheWithinWord(value, i)) {
                continue;
            }
            if (openIndex < 0) {
                boolean canOpen = i + 1 < value.length()
                        && !Character.isWhitespace(value.charAt(i + 1))
                        && (i == 0 || !Character.isLetterOrDigit(value.charAt(i - 1)));
                if (canOpen) {
                    openIndex = i;
                }
                continue;
            }
            boolean canClose = i > 0
                    && !Character.isWhitespace(value.charAt(i - 1))
                    && (i + 1 == value.length() || !Character.isLetterOrDigit(value.charAt(i + 1)));
            if (canClose) {
                if (offset > openIndex && offset < i) {
                    return true;
                }
                openIndex = -1;
            }
        }
        return false;
    }

    private static boolean apostropheWithinWord(String value, int offset) {
        return offset > 0
                && offset + 1 < value.length()
                && Character.isLetterOrDigit(value.charAt(offset - 1))
                && Character.isLetterOrDigit(value.charAt(offset + 1));
    }

    private static boolean insideBacktickSpan(String value, int offset) {
        int openRunLength = 0;
        int contentStart = -1;
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) != '`') {
                i++;
                continue;
            }
            int runStart = i;
            while (i < value.length() && value.charAt(i) == '`') {
                i++;
            }
            int runLength = i - runStart;
            if (openRunLength == 0) {
                openRunLength = runLength;
                contentStart = i;
            } else if (runLength == openRunLength) {
                if (offset >= contentStart && offset < runStart) {
                    return true;
                }
                openRunLength = 0;
                contentStart = -1;
            }
        }
        return false;
    }

    private static boolean insideSameDelimiter(String value, int offset, char delimiter) {
        int preceding = 0;
        for (int i = 0; i < offset; i++) {
            if (value.charAt(i) == delimiter) {
                preceding++;
            }
        }
        return (preceding & 1) == 1 && value.indexOf(delimiter, offset) >= 0;
    }

    private static boolean insideDelimitedRange(String value, int offset, char open, char close) {
        int openIndex = value.lastIndexOf(open, offset);
        if (openIndex < 0) {
            return false;
        }
        int firstCloseAfterOpen = value.indexOf(close, openIndex + 1);
        return firstCloseAfterOpen >= offset;
    }

    private static boolean internalDiagnosticOnlyAnswer(String text) {
        if (text == null || text.isBlank() || evidenceFallbackDiagnosticAnswer(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT).trim();
        return lower.startsWith("reason=weak_draft_high_evidence")
                || lower.startsWith("guard.degrade.reason=")
                || lower.contains("guard.degrade.reason=weak_draft_high_evidence")
                || lower.contains("compression/answersynthesizer")
                || lower.contains("evidence injection/요약");
    }

    private static List<String> userQueryKeywords(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        Matcher matcher = USER_TOKEN.matcher(userQuery.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = normalizeUserQueryKeyword(matcher.group());
            if (token.length() < 2 || USER_QUERY_STOPWORDS.contains(token)) {
                continue;
            }
            if (!out.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    private static String normalizeUserQueryKeyword(String token) {
        if (token == null) {
            return "";
        }
        String normalized = token.toLowerCase(Locale.ROOT).trim();
        if (normalized.length() > 2) {
            normalized = normalized.replaceFirst("(?:으로|에서|에게|부터|까지|처럼|만|은|는|이|가|을|를|에|로|와|과|도)$", "");
        }
        return normalized;
    }

    private static int queryRelevanceScore(String candidate, List<String> keywords) {
        if (candidate == null || candidate.isBlank() || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (keyword.matches("[a-z0-9]+")) {
                Pattern pattern = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(keyword) + "(?![a-z0-9])");
                if (pattern.matcher(lower).find()) {
                    score++;
                }
                continue;
            }
            if (lower.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    private static List<String> sentenceSegments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (isSentenceBoundary(c)) {
                addSentenceSegment(out, current);
            }
        }
        addSentenceSegment(out, current);
        return out;
    }

    private static void addSentenceSegment(List<String> out, StringBuilder current) {
        if (current == null || current.isEmpty()) {
            return;
        }
        String value = current.toString().trim();
        current.setLength(0);
        if (!value.isBlank()) {
            out.add(value);
        }
    }

    private static String normalizeCandidateSentence(String candidate) {
        if (candidate == null) {
            return "";
        }
        String normalized = candidate
                .replaceAll("(?m)^\\s{0,6}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*(?:[-\\u2022]+|\\*+)\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
        normalized = stripMarkdownEmphasisArtifacts(normalized);
        if (standaloneMarkdownSectionHeading(normalized)) {
            return "";
        }
        normalized = stripLeadingMarkdownSectionLabel(normalized);
        normalized = normalized
                .replaceFirst("^(?:\\[(?:WEB|RAG|LOCAL)\\d{1,3}]|\\[(?:W|V|D)\\d{1,3}])\\s+", "")
                .trim();
        if (standaloneMarkdownSectionHeading(normalized)) {
            return "";
        }
        return SafeRedactor.safeMessage(normalized, 240);
    }

    private static boolean containsMarkdownHeading(String text) {
        return text != null && Pattern.compile("(?m)^\\s{0,6}#{1,6}\\s+\\S+").matcher(text).find();
    }

    private static String stripLeadingMarkdownSectionLabel(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim()
                .replaceFirst("^(?:요약|핵심\\s*포인트|핵심\\s*설명|핵심|근거(?:\\s*\\([^)]*\\))?|참고\\s*자료|검색\\s*결과\\s*요약)\\s*[:：-]?\\s+", "")
                .trim();
    }

    private static boolean standaloneMarkdownSectionHeading(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.matches("(?:summary|key points?|details?|sources?|references?)\\s*[:：-]?")
                || trimmed.matches("(?:요약|핵심\\s*포인트|핵심\\s*설명|핵심|포인트|근거(?:\\s*\\([^)]*\\))?|참고\\s*자료|검색\\s*결과\\s*요약)\\s*[:：-]?");
    }

    private static boolean lowSignalWrapperSentence(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return true;
        }
        String lower = candidate.toLowerCase(Locale.ROOT).trim();
        return lower.startsWith("summary:")
                || lower.startsWith("tl;dr")
                || lower.startsWith("tldr")
                || lower.startsWith("\uc694\uc57d:")
                || lower.startsWith("\uc694\uc57d")
                || lower.startsWith("this answer")
                || lower.startsWith("the answer")
                || lower.startsWith("user requested")
                || lower.startsWith("the user asked")
                || lower.startsWith("※ [degraded mode]")
                || lower.startsWith("[degraded mode]")
                || lower.startsWith("modelused:")
                || lower.startsWith("- modelused:")
                || lower.startsWith("기본 모델 응답이 안정적으로 생성되지 않아")
                || lower.startsWith("상세한 답변을 만들 만큼")
                || lower.startsWith("아래 자료의")
                || lower.contains("진단(plan/mode/aux/guard/webfailsoft)")
                || lower.startsWith("\uc0ac\uc6a9\uc790\uc758 \uc694\uccad")
                || lower.startsWith("\uc774 \ub2f5\ubcc0")
                || lower.startsWith("\ud544\uc694\uc5d0 \ub530\ub77c")
                || lower.startsWith("\uc774\ub7ec\ud55c \ubc29\uc2dd")
                || lower.contains("debug traces")
                || lower.contains("diagnostic traces")
                || lower.contains("\ub514\ubc84\uadf8 \ud754\uc801")
                || lower.startsWith("fix hints")
                || lower.startsWith("guard.degrade.reason")
                || lower.startsWith("reason=weak_draft_high_evidence")
                || lower.contains("compression/answersynthesizer")
                || lower.contains("evidence injection/요약");
    }

    private static void traceShape(
            Map<String, Object> meta,
            boolean applied,
            String reason,
            int originalLength,
            int finalLength,
            int originalSentenceCount,
            int finalSentenceCount) {
        if (meta != null) {
            meta.put("chat.harmony.postprocess.shapeApplied", applied);
            meta.put("chat.harmony.postprocess.shapeReason", traceLabel(reason, "unknown"));
            meta.put("chat.harmony.postprocess.shapeOriginalLength", Math.max(0, originalLength));
            meta.put("chat.harmony.postprocess.shapeFinalLength", Math.max(0, finalLength));
            meta.put("chat.harmony.postprocess.shapeOriginalSentenceCount", Math.max(0, originalSentenceCount));
            meta.put("chat.harmony.postprocess.shapeFinalSentenceCount", Math.max(0, finalSentenceCount));
        }
        TraceStore.put("chat.harmony.postprocess.shapeApplied", applied);
        TraceStore.put("chat.harmony.postprocess.shapeReason", traceLabel(reason, "unknown"));
        TraceStore.put("chat.harmony.postprocess.shapeOriginalLength", Math.max(0, originalLength));
        TraceStore.put("chat.harmony.postprocess.shapeFinalLength", Math.max(0, finalLength));
        TraceStore.put("chat.harmony.postprocess.shapeOriginalSentenceCount", Math.max(0, originalSentenceCount));
        TraceStore.put("chat.harmony.postprocess.shapeFinalSentenceCount", Math.max(0, finalSentenceCount));
    }

    private static void traceHarmonyBreadcrumbs(
            boolean degraded,
            String decision,
            String reason,
            double weightedScore,
            int answerLength,
            int sentenceCount,
            int evidenceCount) {
        TraceStore.put("chat.harmony.postprocess.applied", Boolean.TRUE);
        TraceStore.put("chat.harmony.postprocess.agentVisible", Boolean.TRUE);
        TraceStore.put("chat.harmony.postprocess.degraded", degraded);
        TraceStore.put("chat.harmony.postprocess.decision", decision);
        TraceStore.put("chat.harmony.postprocess.reason", reason);
        TraceStore.put("chat.harmony.postprocess.weightedScore", weightedScore);
        TraceStore.put("chat.harmony.postprocess.answerLength", answerLength);
        TraceStore.put("chat.harmony.postprocess.sentenceCount", sentenceCount);
        TraceStore.put("chat.harmony.postprocess.evidenceCount", evidenceCount);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.applied", Boolean.TRUE);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.agentVisible", Boolean.TRUE);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.degraded", degraded);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.decision", decision);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.reason", reason);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.weightedScore", weightedScore);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.evidenceCount", evidenceCount);
    }

    private static void copyVirtualMatrixBreadcrumbs(Map<String, Object> meta) {
        copyIfPresent(meta, "debug.ai.metrics.virtualMatrix.count");
        copyIfPresent(meta, "debug.ai.metrics.virtualMatrix.chunkSize");
        copyIfPresent(meta, "debug.ai.metrics.virtualMatrix.chunkCount");
        copyIfPresent(meta, "debug.ai.metrics.virtualMatrix.weightedScore");
        copyIfPresent(meta, "debug.ai.metrics.virtualMatrix.decision");
        copyIfPresent(meta, "debug.ai.metrics.virtualMatrix.hotChunkIndex");
        copyIfPresent(meta, "debug.ai.metrics.virtualMatrix.hotChunkRiskScore");
        if (meta.containsKey("debug.ai.metrics.virtualMatrix.count")
                || meta.containsKey("debug.ai.metrics.virtualMatrix.decision")) {
            meta.put("debug.ai.metrics.virtualMatrix.agentVisible", Boolean.TRUE);
        }
    }

    private static void copyIfPresent(Map<String, Object> meta, String key) {
        if (meta == null || key == null || meta.containsKey(key)) {
            return;
        }
        Object value = TraceStore.get(key);
        if (value instanceof Number || value instanceof Boolean) {
            meta.put(key, value);
            return;
        }
        if (value != null) {
            String label = String.valueOf(value).trim();
            if (label.matches("[A-Za-z0-9_.:-]{1,80}")) {
                meta.put(key, label);
            }
        }
    }

    private static void promoteLlmStageBoundaryUpstream(Map<String, Object> meta) {
        if (meta == null) {
            return;
        }
        Object value = meta.get("mla.breadcrumb.step.llm");
        if (!(value instanceof Map<?, ?> step)) {
            return;
        }
        String failureClass = traceLabel(step.get("failureClass"), "");
        String reasonCode = traceLabel(step.get("reasonCode"), "");
        int status = upstreamStatusFromBoundary(failureClass, reasonCode);
        if (status <= 0) {
            return;
        }
        String upstreamFailure = upstreamFailureClass(status);
        String upstreamNext = upstreamNextAction(status);
        putLocalLlmOperatorAction(meta, "upstreamStatus", status);
        putLocalLlmOperatorAction(meta, "upstreamFailureClass", upstreamFailure);
        putLocalLlmOperatorAction(meta, "upstreamNextAction", upstreamNext);
        putLocalLlmOperatorActionIfWeak(meta, "nextAction", upstreamNext);
    }

    private static int upstreamStatusFromBoundary(String failureClass, String reasonCode) {
        String joined = ((failureClass == null ? "" : failureClass)
                + " " + (reasonCode == null ? "" : reasonCode)).toLowerCase(Locale.ROOT);
        if (joined.contains("internalservererror") || joined.contains("http_5xx") || joined.contains("5xx")
                || joined.contains("status500") || joined.contains("500")) {
            return 500;
        }
        if (joined.contains("ratelimit") || joined.contains("rate-limit") || joined.contains("429")) {
            return 429;
        }
        if (joined.contains("http_4xx") || joined.contains("4xx")) {
            return 400;
        }
        return 0;
    }

    private static String upstreamFailureClass(int status) {
        if (status >= 500) {
            return "ollama_upstream_5xx";
        }
        if (status == 429) {
            return "ollama_rate_limit";
        }
        if (status >= 400) {
            return "ollama_upstream_4xx";
        }
        return "ollama_upstream_http_error";
    }

    private static String upstreamNextAction(int status) {
        if (status >= 500) {
            return "inspect_ollama_runtime_capacity";
        }
        if (status == 429) {
            return "respect_ollama_retry_after";
        }
        if (status >= 400) {
            return "inspect_ollama_request_contract";
        }
        return "inspect_ollama_http_failure";
    }

    private static void putLocalLlmOperatorAction(Map<String, Object> meta, String suffix, Object value) {
        if (suffix == null || suffix.isBlank() || value == null) {
            return;
        }
        String rawKey = "llm.localSmoke.operatorAction." + suffix;
        String promptKey = "prompt.agentDebugEvidence.localLlm.operatorAction." + suffix;
        if (!meta.containsKey(rawKey)) {
            meta.put(rawKey, value);
        }
        Object currentPrompt = meta.get(promptKey);
        if (currentPrompt == null || isDiagnosticSummaryString(currentPrompt)) {
            meta.put(promptKey, value);
        }
        TraceStore.put(rawKey, value);
        TraceStore.put(promptKey, value);
    }

    private static void putLocalLlmOperatorActionIfWeak(Map<String, Object> meta, String suffix, Object value) {
        if (suffix == null || suffix.isBlank() || value == null) {
            return;
        }
        String rawKey = "llm.localSmoke.operatorAction." + suffix;
        String promptKey = "prompt.agentDebugEvidence.localLlm.operatorAction." + suffix;
        Object current = firstNonSummaryTraceValue(rawKey, promptKey);
        String label = traceLabel(current, "");
        if (label == null || label.isBlank()
                || "monitor_local_llm_route".equals(label)
                || isDiagnosticSummaryString(current)) {
            meta.put(rawKey, value);
            meta.put(promptKey, value);
            TraceStore.put(rawKey, value);
            TraceStore.put(promptKey, value);
        }
    }

    private static void copyAgentVisibleExternalBreadcrumbs(Map<String, Object> meta) {
        if (meta == null) {
            return;
        }
        for (String key : AGENT_VISIBLE_EXTERNAL_KEYS) {
            Object current = meta.get(key);
            if (current != null && !isDiagnosticSummaryString(current)) {
                continue;
            }
            Object value = firstNonSummaryTraceValue(key, mirrorExternalKey(key));
            if (value instanceof Number || value instanceof Boolean) {
                meta.put(key, value);
                continue;
            }
            String safe = SafeRedactor.traceLabel(value);
            if (safe != null && !safe.isBlank()) {
                meta.put(key, safe);
            }
        }
    }

    private static void copyAgentVisibleLocalLlmBreadcrumbs(Map<String, Object> meta) {
        if (meta == null) {
            return;
        }
        for (String key : AGENT_VISIBLE_LOCAL_LLM_KEYS) {
            Object current = meta.get(key);
            if (current != null && !isDiagnosticSummaryString(current)) {
                continue;
            }
            Object value = firstNonSummaryTraceValue(key, mirrorLocalLlmKey(key), rawLocalLlmKey(key));
            if (value instanceof Number || value instanceof Boolean) {
                meta.put(key, value);
                TraceStore.put(key, value);
                continue;
            }
            String safe = SafeRedactor.traceLabel(value);
            if (safe != null && !safe.isBlank()) {
                meta.put(key, safe);
                TraceStore.put(key, safe);
            }
        }
    }

    private static boolean isDiagnosticSummaryString(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return text.contains("present=") && text.contains("hash12=");
    }

    private static Object firstNonSummaryTraceValue(String... keys) {
        if (keys == null || keys.length == 0) {
            return null;
        }
        Object fallback = null;
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = TraceStore.get(key);
            if (value == null) {
                continue;
            }
            if (!isDiagnosticSummaryString(value)) {
                return value;
            }
            if (fallback == null) {
                fallback = value;
            }
        }
        return fallback;
    }

    private static String mirrorExternalKey(String promptKey) {
        if (promptKey == null) {
            return null;
        }
        String prefix = "prompt.agentDebugEvidence.external.";
        if (!promptKey.startsWith(prefix)) {
            return null;
        }
        return "debug.ai.agentDebugEvidence.external." + promptKey.substring(prefix.length());
    }

    private static String mirrorLocalLlmKey(String promptKey) {
        if (promptKey == null) {
            return null;
        }
        String prefix = "prompt.agentDebugEvidence.localLlm.";
        if (!promptKey.startsWith(prefix)) {
            return null;
        }
        return "debug.ai.agentDebugEvidence.localLlm." + promptKey.substring(prefix.length());
    }

    private static String rawLocalLlmKey(String promptKey) {
        if (promptKey == null) {
            return null;
        }
        String prefix = "prompt.agentDebugEvidence.localLlm.operatorAction.";
        if (!promptKey.startsWith(prefix)) {
            return null;
        }
        return "llm.localSmoke.operatorAction." + promptKey.substring(prefix.length());
    }

    private static void traceDebugAiNextAction(Map<String, Object> meta, String decision, String reason) {
        String safeDecision = traceLabel(decision, "observed");
        String safeReason = traceLabel(reason, safeDecision);
        String matrixDecision = traceLabel(meta == null ? null : meta.get("debug.ai.metrics.virtualMatrix.decision"), null);
        String nextAction;
        String nextReason;
        if (harmonyNeedsInspection(safeDecision)) {
            nextAction = "inspect_chat_harmony_trace";
            nextReason = "chat_harmony." + traceLabel(safeReason, "observed");
        } else {
            nextAction = matrixActionFromDecision(matrixDecision);
            nextReason = traceLabel(matrixDecision, safeDecision);
        }
        putNextAction(meta, nextAction, nextReason);
    }

    private static boolean harmonyNeedsInspection(String decision) {
        return decision != null && !"smooth_chat".equalsIgnoreCase(decision);
    }

    private static String matrixActionFromDecision(String matrixDecision) {
        String decision = traceLabel(matrixDecision, "observe");
        if ("mitigate_now".equals(decision)) {
            return "mitigate_debug_ai_hot_chunk";
        }
        if ("investigate_hot_chunk".equals(decision)) {
            return "investigate_debug_ai_hot_chunk";
        }
        return "continue_observing_chat_harmony";
    }

    private static void putNextAction(Map<String, Object> meta, String nextAction, String nextReason) {
        if (meta != null) {
            meta.put("debug.ai.metrics.nextAction", nextAction);
            meta.put("debug.ai.metrics.nextReason", nextReason);
            meta.put("prompt.agentDebugEvidence.chatHarmony.nextAction", nextAction);
            meta.put("prompt.agentDebugEvidence.chatHarmony.nextReason", nextReason);
        }
        TraceStore.put("debug.ai.metrics.nextAction", nextAction);
        TraceStore.put("debug.ai.metrics.nextReason", nextReason);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.nextAction", nextAction);
        TraceStore.put("prompt.agentDebugEvidence.chatHarmony.nextReason", nextReason);
    }

    private static String traceLabel(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private static int evidenceCount(Map<String, Object> meta) {
        int finalContext = firstPositive(
                countValue(meta.get("finalContextCount")),
                countValue(meta.get("final.context.count")),
                countValue(meta.get("prompt.context.count")));
        if (finalContext > 0) {
            return finalContext;
        }
        int web = firstPositive(
                countValue(meta.get("finalWebTopKCount")),
                countValue(meta.get("finalWebTopK")),
                countValue(meta.get("webCount")),
                countValue(meta.get("web.count")));
        int vector = firstPositive(
                countValue(meta.get("finalVectorTopKCount")),
                countValue(meta.get("finalVectorTopK")),
                countValue(meta.get("vectorCount")),
                countValue(meta.get("vector.count")));
        return Math.max(0, web) + Math.max(0, vector);
    }

    private static int firstPositive(int... values) {
        if (values == null) {
            return 0;
        }
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static int countValue(Object value) {
        if (value instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException ignored) {
            TraceStore.put("chat.harmony.postprocess.suppressed.stage", "countValue.parse");
            TraceStore.put("chat.harmony.postprocess.suppressed.errorType", "invalid_number");
            TraceStore.put("chat.harmony.postprocess.suppressed.countValue.parse", Boolean.TRUE);
            TraceStore.put("chat.harmony.postprocess.suppressed.countValue.parse.errorType", "invalid_number");
            return 0;
        }
    }

    private static int sentenceCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSentenceBoundary(c)) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private static boolean isSentenceBoundary(char c) {
        return c == '.' || c == '!' || c == '?' || c == '\n'
                || c == '\u3002' || c == '\uff01' || c == '\uff1f';
    }

    private static double roundedScore(
            int answerLength,
            int sentenceCount,
            int evidenceCount,
            boolean fallbackMode,
            boolean blankGuarded) {
        if (blankGuarded) {
            return 0.0d;
        }
        double lengthScore = Math.min(1.0d, Math.max(0.0d, answerLength / 120.0d));
        double evidenceScore = Math.min(1.0d, Math.max(0.0d, evidenceCount / 3.0d));
        double flowScore = Math.min(1.0d, Math.max(0.0d, sentenceCount / 3.0d));
        double score = (lengthScore * 0.45d) + (evidenceScore * 0.35d) + (flowScore * 0.20d);
        if (fallbackMode) {
            score = Math.max(0.0d, score - 0.15d);
        }
        return Math.round(score * 1000.0d) / 1000.0d;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null
                && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value));
    }
}
