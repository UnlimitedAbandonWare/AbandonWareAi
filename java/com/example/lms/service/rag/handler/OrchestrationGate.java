package com.example.lms.service.rag.handler;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import dev.langchain4j.rag.query.Query;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fixed-chain(SELF_ASK → ANALYZE → WEB → VECTOR) 경로에서도
 * ChatWorkflow가 주입한 OrchestrationHints/metaHints 를 실행 제어로 반영하기 위한 게이트.
 *
 * <p>
 * 목적:
 * - STRIKE/COMPRESSION/BYPASS, Nightmare/AuxDown 상황에서 "비싼 단계"를 조기 스킵하여
 *   연쇄 타임아웃/Blank/Fallback 가능성을 낮춘다.
 * - FailurePatternOrchestrator(쿨다운) 신호를 fixed-chain에도 적용한다.
 * </p>
 */
public final class OrchestrationGate {

    private final FailurePatternOrchestrator failurePatterns;

    public OrchestrationGate(FailurePatternOrchestrator failurePatterns) {
        this.failurePatterns = failurePatterns;
    }

    /** Self-Ask(LLM + Web) 단계는 aux 상태/strike/compression에서 우선적으로 차단한다. */
    public boolean allowSelfAsk(Query q) {
        Map<String, Object> md = toMap(q != null ? q.metadata() : null);
        if (!metaBool(md, "enableSelfAsk", true)) {
            return false;
        }
        // Self-Ask is web-dependent: respect allowWeb/webRateLimited demotions.
        if (!metaBool(md, "allowWeb", true)) {
            return false;
        }
        if (metaBool(md, "webRateLimited", false)) {
            return false;
        }
        if (isAuxSuppressed(md)) {
            return false;
        }
        // Self-Ask는 결과적으로 web까지 동원되는 경우가 많아서, web cooldown이면 스킵
        if (isCoolingDown("web")) {
            return false;
        }
        return true;
    }

    /** Analyze(LLM + Web) 단계는 aux 상태/strike/compression에서 우선적으로 차단한다. */
    public boolean allowAnalyze(Query q) {
        Map<String, Object> md = toMap(q != null ? q.metadata() : null);
        if (!metaBool(md, "enableAnalyze", true)) {
            return false;
        }
        // Analyze is web-dependent: respect allowWeb/webRateLimited demotions.
        if (!metaBool(md, "allowWeb", true)) {
            return false;
        }
        if (metaBool(md, "webRateLimited", false)) {
            return false;
        }
        if (isAuxSuppressed(md)) {
            return false;
        }
        if (isCoolingDown("web")) {
            return false;
        }
        return true;
    }

    /** Web 단계는 allowWeb=false 또는 webRateLimited/cooldown이면 스킵한다. */
    public boolean allowWeb(Query q) {
        Map<String, Object> md = toMap(q != null ? q.metadata() : null);
        if (!metaBool(md, "allowWeb", true)) {
            return false;
        }
        if (metaBool(md, "webRateLimited", false)) {
            return false;
        }
        if (isCoolingDown("web")) {
            return false;
        }
        return true;
    }

    /** Vector(RAG) 단계는 allowRag=false 또는 vector cooldown이면 스킵한다. */
    public boolean allowVector(Query q) {
        Map<String, Object> md = toMap(q != null ? q.metadata() : null);
        if (!metaBool(md, "allowRag", true)) {
            return false;
        }
        if (isCoolingDown("vector")) {
            return false;
        }
        return true;
    }

    private boolean isCoolingDown(String canonicalSource) {
        try {
            return failurePatterns != null
                    && canonicalSource != null
                    && failurePatterns.isCoolingDown(canonicalSource);
        } catch (Exception ignore) {
            return false;
        }
    }

    /**
     * UAW: STRIKE/BYPASS, NightmareMode, AuxDown 시나리오에서
     * Self-Ask/Analyze 같은 "aux-heavy" 단계는 강제로 스킵하여 폭주를 막는다.
     *
     * <p>COMPRESSION은 비용/지연 절감 모드지만, 기능 자체를 끊어버리면
     * (특히 irregularity 기반 compression) 오케스트레이션 단절이 발생할 수 있다.
     * 따라서 COMPRESSION은 여기서 "전면 차단" 조건으로 취급하지 않고,
     * 각 단계(hints)에서 선택적으로 줄이는 방식으로 처리한다.
     */
    private static boolean isAuxSuppressed(Map<String, Object> md) {
        if (md == null || md.isEmpty()) {
            return false;
        }
        // explicit flags
        if (metaBool(md, "nightmareMode", false))
            return true;
        if (metaBool(md, "auxLlmDown", false))
            return true;
        if (metaBool(md, "auxDegraded", false))
            return true;
        if (metaBool(md, "auxHardDown", false))
            return true;

        // orchestration modes
        if (metaBool(md, "strikeMode", false))
            return true;
        // BYPASS는 LLM 생성만 우회하는 개념이지만, Self-Ask/Analyze는 LLM 의존도가 높아서 스킵
        if (metaBool(md, "bypassMode", false))
            return true;

        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object meta) {
        if (meta == null) {
            return Map.of();
        }

        // LangChain4j 1.0.x: rag.query.Metadata → chatMemoryId 및 asMap 지원
        if (meta instanceof dev.langchain4j.rag.query.Metadata m) {
            Map<String, Object> out = new HashMap<>();
            try {
                Map<String, Object> inner = m.asMap();
                if (inner != null) {
                    out.putAll(inner);
                }
            } catch (Exception ignore) {
                // fail-soft
            }

            Object sid = null;
            try {
                sid = m.chatMemoryId();
            } catch (Exception ignore) {
                sid = null;
            }
            if (sid != null) {
                out.put(com.example.lms.service.rag.LangChainRAGService.META_SID, sid);
            }
            return out;
        }

        if (meta instanceof Map<?, ?> raw) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                Object k = e.getKey();
                if (k != null) {
                    out.put(k.toString(), e.getValue());
                }
            }
            return out;
        }

        return Map.of();
    }

    private static boolean metaBool(Map<String, Object> md, String key, boolean defaultValue) {
        if (md == null || md.isEmpty() || key == null || key.isBlank()) {
            return defaultValue;
        }
        Object v = md.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return defaultValue;
        }
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s)) {
            return false;
        }
        return defaultValue;
    }
}
