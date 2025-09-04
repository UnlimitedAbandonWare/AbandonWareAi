
        package com.example.lms.service.guard;

        import com.example.lms.service.routing.RouteSignal;
        import dev.langchain4j.model.chat.ChatModel;

        import java.util.List;
        import java.util.regex.Pattern;

public class EvidenceAwareGuard {

    public static final Pattern INFO_NONE =
            Pattern.compile("(정보\\s*없음|insufficient\\s*information|no\\s*info)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE_TOKENS =
            Pattern.compile("\\s+|[\\u3000-\\u303F\\p{Punct}]");

    public record EvidenceDoc(String id, String title, String snippet) {}

    public static class Result {
        public final String answer;
        public final boolean escalated;
        public Result(String answer, boolean escalated) { this.answer = answer; this.escalated = escalated; }

        // 👇 [변경] ChatService에서 호출할 접근자 메서드 추가
        public boolean escalated() {
            return this.escalated;
        }

        public String regeneratedText() {
            // 호환성을 위해 answer를 반환 (필요시 재생성 로직 추가 가능)
            return this.answer;
        }
    }

    public Result ensureCoverage(
            String draft,
            List<EvidenceDoc> topDocs,
            java.util.function.Function<RouteSignal, ChatModel> escalateFn,
            RouteSignal signal,
            int minEntitiesCovered) {

        int coverage = estimateCoverage(draft, topDocs);
        boolean infoNone = INFO_NONE.matcher(draft).find();

        if (!topDocs.isEmpty() && (coverage < minEntitiesCovered || infoNone)) {
            escalateFn.apply(signal); // 필요시 반환된 ChatModel로 재생성 로직 추가 가능
            return new Result(draft, true);
        }
        return new Result(draft, false);
    }

    public String degradeToEvidenceList(List<EvidenceDoc> topDocs) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래 근거를 바탕으로 가능한 사실만 정리합니다.\n\n");
        for (EvidenceDoc d : topDocs) {
            sb.append("- [").append(d.toString()).append("] ").append(d.title()).append(" — ").append(d.snippet()).append("\n");
        }
        sb.append("\n(상세 설명이 필요하면 추가 질문을 해 주세요.)");
        return sb.toString();
    }

    
    /** 외부에서 약한 초안(정보 없음 등) 판별에 사용 */
    public static boolean looksWeak(String draft) {
        if (draft == null) return true;
        String d = draft.trim();
        if (d.isEmpty()) return true;
        if (d.length() < 12) return true;
        return INFO_NONE.matcher(d).find();
    }
    
    private int estimateCoverage(String draft, List<EvidenceDoc> topDocs) {
        int covered = 0;
        String lower = draft.toLowerCase();
        for (EvidenceDoc d : topDocs) {
            String[] toks = TITLE_TOKENS.split(d.title());
            for (String t : toks) {
                if (t.length() >= 2 && lower.contains(t.toLowerCase())) {
                    covered++;
                    break;
                }
            }
        }
        return covered;
    }
}