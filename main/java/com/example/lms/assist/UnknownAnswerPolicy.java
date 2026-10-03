package com.example.lms.assist;

import com.example.lms.service.guard.EvidenceAwareGuard;
import org.springframework.util.StringUtils;

/**
 * "모름 → 즉시 웹" 우선순위의 단일 판정 지점. 일반 cue 힌트와 Nova Focus가 공유한다.
 * 새 라우터나 검색 엔진이 아니라 기존 UnifiedRagOrchestrator/ChatWorkflow 호출 앞의
 * 게이트이며, 웹 재시도는 요청당 최대 한 번이다. SCOPED_RAG·RECENT_ONLY·요청별
 * 웹 OFF 우선순위를 다른 경로가 조용히 우회하지 못하도록 여기서만 계산한다.
 */
final class UnknownAnswerPolicy {
    private UnknownAnswerPolicy() {}

    /** 유효 검색 모드 — 우선순위 표의 행 이름과 동일하게 유지한다. */
    enum Mode { GENERAL, WEB, HYBRID, SCOPED_RAG, RECENT_ONLY }

    /** 모름 신호 종류. LOW_ASR_CONFIDENCE는 발화 신뢰도가 이 경로에 도달하면 연결하는 hook이다. */
    enum Trigger { EMPTY_ANSWER, NO_CUE, INSUFFICIENT_EVIDENCE, LOW_ASR_CONFIDENCE, EXPLICIT_UNKNOWN }

    record Decision(Trigger trigger, Mode mode, boolean webAllowed, String reason) {}

    /** 사용 가능한 Jev verdict를 우선순위 모드로 환산한다. off/shadow/defer는 로컬 결정이 곧 GENERAL이다. */
    static Mode mode(JevDecisionAdvisor.Advice jev) {
        if (jev == null || !jev.usable()) return Mode.GENERAL;
        return switch (jev.verdict()) {
            case WEB -> Mode.WEB;
            case HYBRID -> Mode.HYBRID;
            case SCOPED_RAG -> Mode.SCOPED_RAG;
            case RECENT_ONLY -> Mode.RECENT_ONLY;
            case CLARIFY -> Mode.GENERAL;
        };
    }

    /** 비어 있거나 기존 no-evidence 템플릿과 일치하는 답변이면 모름 신호다. */
    static Trigger classify(String answer) {
        if (!StringUtils.hasText(answer)) return Trigger.EMPTY_ANSWER;
        return EvidenceAwareGuard.looksNoEvidenceTemplate(answer) ? Trigger.EXPLICIT_UNKNOWN : null;
    }

    /** RAG_CUE의 선제 웹 확장(모름 신호와 무관)도 같은 SCOPED_RAG/RECENT_ONLY 우선순위를 따른다. */
    static boolean defaultWebAllowed(Mode mode, boolean scopedWebEnabled) {
        return switch (mode) {
            case SCOPED_RAG -> scopedWebEnabled;
            case RECENT_ONLY -> false;
            default -> true;
        };
    }

    /**
     * 모름 신호에 대한 단 한 번의 웹 재시도 허용 여부.
     * reason은 진단 키에 그대로 기록되는 불변 문자열이다.
     */
    static Decision decide(Mode mode, boolean requestWebOff, boolean scopedWebEnabled,
                           boolean featureEnabled, boolean webAttempted, Trigger trigger) {
        if (trigger == null) return new Decision(null, mode, false, "no_signal");
        if (requestWebOff) return new Decision(trigger, mode, false, "request_web_off");
        // 모드 우선순위가 먼저 결정된다; 허용된 경우에만 기능 스위치와 요청당 한 번 제한을 적용한다.
        boolean modeAllowed = switch (mode) {
            case RECENT_ONLY -> false;
            case SCOPED_RAG -> scopedWebEnabled;
            default -> true;
        };
        String modeReason = mode == Mode.RECENT_ONLY ? "recent_only_precedence"
                : mode == Mode.SCOPED_RAG ? (scopedWebEnabled ? "scoped_flag" : "scoped_rag_precedence")
                : "allowed";
        if (!modeAllowed) return new Decision(trigger, mode, false, modeReason);
        if (!featureEnabled) return new Decision(trigger, mode, false, "disabled");
        if (webAttempted) return new Decision(trigger, mode, false, "web_already_attempted");
        return new Decision(trigger, mode, true, modeReason);
    }
}
