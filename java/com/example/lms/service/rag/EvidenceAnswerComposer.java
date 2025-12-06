package com.example.lms.service.rag;

import com.example.lms.service.guard.EvidenceAwareGuard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Composes a conservative, evidence-based answer when the guard decides
 * that the model's draft does not sufficiently cover the retrieved evidence.
 * <p>
 * This component does not call any LLMs.  It simply formats the already
 * retrieved snippets into a user-facing summary with an appropriate
 * disclaimer depending on the domain risk.
 */
@Component
public class EvidenceAnswerComposer {

    /**
     * Build an answer that summarises the retrieved evidence.
     *
     * @param userQuestion   original user query (used only for context; not parsed)
     * @param evidence       list of evidence documents selected by the guard
     * @param lowRiskDomain  whether the domain is considered low‑risk
     *                       (e.g. games / entertainment / subculture)
     * @return formatted answer string
     */
    public String compose(String userQuestion,
                          List<EvidenceAwareGuard.EvidenceDoc> evidence,
                          boolean lowRiskDomain) {

        if (evidence == null || evidence.isEmpty()) {
            return "검색 결과가 충분하지 않아 답변을 구성하기 어렵습니다.";
        }

        StringBuilder sb = new StringBuilder();

        // 1) Header: explain the situation and risk level.
        if (lowRiskDomain) {
            sb.append("공식 출처는 아니지만, 커뮤니티/위키에서 다음 정보를 찾았습니다.\n\n");
        } else {
            sb.append("검색된 자료를 바탕으로 정리했으나, 공식 문서는 아닐 수 있습니다.\n\n");
        }

        // 2) Evidence bullets (top N).
        sb.append("### 검색 결과 요약\n");
        int limit = Math.min(5, evidence.size());
        for (int i = 0; i < limit; i++) {
            EvidenceAwareGuard.EvidenceDoc doc = evidence.get(i);
            if (doc == null) {
                continue;
            }
            String title = safe(doc.title(), "제목 없음");
            String snippet = safe(doc.snippet(), "");
            if (snippet.length() > 160) {
                snippet = snippet.substring(0, 157) + "...";
            }
            String id = safe(doc.id(), "");
            sb.append("- **").append(title).append("**: ").append(snippet);
            if (!id.isBlank()) {
                sb.append(" (출처: ").append(id).append(")");
            }
            sb.append("\n");
        }

        // 3) Disclaimer.
        sb.append("\n> ⚠️ 이 정보는 비공식 커뮤니티/위키 기반일 수 있으므로, ");
        sb.append("게임 내 최신 정보나 공식 문서를 함께 확인해 주세요.\n");

        return sb.toString();
    }

    private static String safe(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
