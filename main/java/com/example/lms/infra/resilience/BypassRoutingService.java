package com.example.lms.infra.resilience;

import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.service.rag.EvidenceAnswerComposer;
import com.example.lms.service.guard.EvidenceAwareGuard;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * UAW: Bypass routing for unstable orchestration...
 */
@Service
public class BypassRoutingService {

    private final EvidenceAnswerComposer evidenceAnswerComposer;

    public BypassRoutingService(EvidenceAnswerComposer evidenceAnswerComposer) {
        this.evidenceAnswerComposer = evidenceAnswerComposer;
    }

    /**
     * Render a safe alternative answer without calling an LLM.
     *
     * @param userQuery     original user query
     * @param evidenceList  evidence docs (web/vector fused)
     * @param lowRiskDomain whether the detected domain is low-risk
     * @param sig           orchestration signals (strike/compression/bypass)
     */
    public String renderSafeAlternative(String userQuery,
            List<EvidenceAwareGuard.EvidenceDoc> evidenceList,
            boolean lowRiskDomain,
            OrchestrationSignals sig) {

        if (evidenceList == null || evidenceList.isEmpty()) {
            // Deterministic fallback. Do not invent.
            return "정보 없음";
        }

        int cap = (sig != null && (sig.strikeMode() || sig.compressionMode())) ? 3 : 6;
        List<EvidenceAwareGuard.EvidenceDoc> top = evidenceList.size() <= cap
                ? evidenceList
                : evidenceList.subList(0, cap);

        String body = evidenceAnswerComposer.compose(userQuery, top, lowRiskDomain);

        // Minimal guidance to keep the conversation moving (no extra calls).
        StringBuilder sb = new StringBuilder(body == null ? "" : body.trim());
        if (sb.length() > 0) {
            sb.append("\n\n---\n");
        }
        sb.append("※ 현재 시스템 상태(")
                .append(sig != null ? sig.modeLabel() : "BYPASS")
                .append(")에서는 추가 추론/분석을 최소화하고, 확인 가능한 근거만 요약합니다.");
        sb.append("\n\n다음 중 하나를 알려주면 더 정확히 찾을 수 있어요:");
        sb.append("\n- 대상(모델명/버전/지역/시기)");
        sb.append("\n- 원하는 정보(출시일/스펙/가격/공식 발표 여부 등)");
        return sb.toString();
    }
}
