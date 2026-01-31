package com.example.lms.service.guard;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.Function;
import com.example.lms.service.routing.RouteSignal;
import dev.langchain4j.model.chat.ChatModel;



@Component // ✅ 이 한 줄만 추가하면 주입 가능
public class EvidenceAwareGuard {

    public static final class EvidenceDoc {
        public final String id;
        public final String title;
        public final String snippet;
        public EvidenceDoc(String id, String title, String snippet) {
            this.id = id; this.title = title; this.snippet = snippet;
        }
    }

    public static final class Result {
        private final boolean escalated;
        private final String regeneratedText;
        public Result(boolean escalated, String regeneratedText) {
            this.escalated = escalated; this.regeneratedText = regeneratedText;
        }
        public boolean escalated() { return escalated; }
        public String regeneratedText() { return regeneratedText; }
    }

    public Result ensureCoverage(
            String draft,
            List<EvidenceDoc> evidence,
            Function<RouteSignal, ChatModel> escalator,
            RouteSignal signal,
            int maxRegens
    ) {
        // 최소 구현: "정보 없음" 등 약한 초안이면 에스컬레이션 신호만 반환
        if (looksWeak(draft)) {
            return new Result(true, null);
        }
        // 아주 단순 커버리지 체크(제목 토큰과 초안의 교집합 여부)
        boolean covered = evidence != null && evidence.stream().anyMatch(doc ->
                doc != null && doc.title != null &&
                        draft != null && draft.toLowerCase().contains(doc.title.toLowerCase()));
        return new Result(!covered, null);
    }

    public static boolean looksWeak(String text) {
        if (text == null) return true;
        String t = text.trim();
        return t.isEmpty() || t.length() < 64 || t.contains("정보 없음") || t.contains("정보 부족");
    }

    public String degradeToEvidenceList(List<EvidenceDoc> ev) {
        if (ev == null || ev.isEmpty()) return "충분한 증거를 찾지 못했습니다.";
        StringBuilder sb = new StringBuilder("🔎 참고한 증거 목록:\n");
        for (var d : ev) {
            sb.append("- ").append(d.title == null ? "(제목 없음)" : d.title);
            if (d.snippet != null && !d.snippet.isBlank()) {
                sb.append(" - ").append(d.snippet.length() > 120 ? d.snippet.substring(0, 120) + "/* ... *&#47;" : d.snippet);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}