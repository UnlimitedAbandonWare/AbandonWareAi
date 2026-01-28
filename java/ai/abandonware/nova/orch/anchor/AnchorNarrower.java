package ai.abandonware.nova.orch.anchor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic anchor picker used as a cheap-path fallback when auxiliary LLM utilities
 * (query-transform / disambiguation) are degraded.
 *
 * <p>Patch goals (from {스터프0}/{스터프2}):
 * <ul>
 *   <li>Avoid "assistantese" / polite command tokens (e.g. "알려줘봐") being selected as anchors.</li>
 *   <li>Prefer prefix entity phrases (up to 3 tokens) as anchors.</li>
 *   <li>Generate minimal, intent-aware cheap query variants to reduce low-signal fan-out.</li>
 * </ul>
 */
public class AnchorNarrower {

    private static final Pattern TOKEN_PAT = Pattern.compile("[가-힣A-Za-z0-9]+");

    /**
     * Polite / helper verbs and generic meta-words that should never become the anchor.
     *
     * <p>NOTE: keep conservative; this list is meant to block obvious low-signal tokens only.</p>
     */
    private static final Set<String> STOP_TOKENS = Set.of(
            "알려줘", "알려줘봐", "알려줘요", "알려줘줘",
            "말해줘", "말해줘봐", "말해줘요",
            "검색", "검색해", "검색해줘", "검색해봐", "검색해요",
            "찾아줘", "찾아줘봐", "찾아줘요",
            "해줘", "해줘봐", "해줘요",
            "설명", "설명해", "설명해줘", "설명해봐",
            "좀", "요", "주세요", "부탁", "부탁해", "부탁해요",

            // meta / generic suffixes (intent word로 분류, anchor가 되면 안됨)
            "의미", "뜻", "정의", "뭐", "뭐야", "무엇", "무엇이야", "누구", "누구야",
            "정보", "사양", "스펙", "가격", "출시", "성능", "비교", "추천", "방법", "해결", "오류", "에러",

            // discourse/meta lead-ins (avoid anchor pollution on long pasted inputs)
            "그런데", "근데", "그리고", "또", "또한", "그래서",
            "아래", "위", "내용", "다음", "참고",


            // location / address intent (keep anchors clean)
            "근처", "주변", "인근", "근거리", "가까운", "근방",
            "주소", "소재지", "본사", "위치"
    );

    public record Anchor(String term, double confidence) {
    }

    public Anchor pick(String query, List<String> protectedTerms, List<String> history) {
        if (!isEmpty(protectedTerms)) {
            // Pick longest protected term.
            String best = protectedTerms.stream()
                    .filter(Objects::nonNull)
                    .max(Comparator.comparingInt(String::length))
                    .orElse(null);
            if (best != null && !best.isBlank()) {
                return new Anchor(best.trim(), 0.95);
            }
        }

        String q = safe(query).trim();

        // 1) Prefix phrase 우선 (최대 3토큰까지 엔티티 연결)
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN_PAT.matcher(q);
        while (m.find()) {
            String t = m.group();
            if (t != null && !t.isBlank()) {
                tokens.add(t);
            }
        }

        List<String> prefix = new ArrayList<>();
        for (String raw : tokens) {
            String norm = raw.toLowerCase(Locale.ROOT);
            if (norm.length() < 2) {
                continue;
            }
            if (isStopLike(norm)) {
                break; // stop 토큰 만나면 prefix 종료
            }
            prefix.add(raw);
            if (prefix.size() >= 3) {
                break; // MAX_PREFIX_TOKENS = 3
            }
        }

        if (!prefix.isEmpty()) {
            String phrase = String.join(" ", prefix).trim();
            double conf = prefix.size() >= 2 ? 0.75 : 0.65;
            return new Anchor(phrase, conf);
        }

        // 2) Fallback: stop 제외한 최장 토큰 (phrase 보강: 인접 토큰 2~3개 묶기)
        String bestToken = "";
        int bestIdx = -1;
        for (int i = 0; i < tokens.size(); i++) {
            String raw = tokens.get(i);
            String norm = raw.toLowerCase(Locale.ROOT);
            if (isStopLike(norm)) {
                continue;
            }
            if (raw.length() > bestToken.length()) {
                bestToken = raw;
                bestIdx = i;
            }
        }

        if (!bestToken.isBlank()) {
            String phrase = bestToken;
            try {
                // candidates: [best], [left+best], [best+right], [left+best+right]
                List<String> candidates = new ArrayList<>();
                candidates.add(bestToken);

                if (bestIdx > 0) {
                    String left = tokens.get(bestIdx - 1);
                    if (left.length() >= 2 && !isStopLike(left.toLowerCase(Locale.ROOT))) {
                        candidates.add(left + " " + bestToken);
                    }
                }
                if (bestIdx + 1 < tokens.size()) {
                    String right = tokens.get(bestIdx + 1);
                    if (right.length() >= 2 && !isStopLike(right.toLowerCase(Locale.ROOT))) {
                        candidates.add(bestToken + " " + right);
                    }
                }
                if (bestIdx > 0 && bestIdx + 1 < tokens.size()) {
                    String left = tokens.get(bestIdx - 1);
                    String right = tokens.get(bestIdx + 1);
                    if (left.length() >= 2 && right.length() >= 2
                            && !isStopLike(left.toLowerCase(Locale.ROOT))
                            && !isStopLike(right.toLowerCase(Locale.ROOT))) {
                        candidates.add(left + " " + bestToken + " " + right);
                    }
                }

                // Prefer slightly longer phrase but keep it bounded.
                phrase = candidates.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(sx -> !sx.isBlank())
                        .filter(sx -> sx.length() <= 32)
                        .max(Comparator.comparingInt(String::length))
                        .orElse(bestToken);
            } catch (Exception ignore) {
                phrase = bestToken;
            }

            double conf = phrase.contains(" ") ? 0.65 : 0.60;
            return new Anchor(phrase, conf);
        }

        // 3) Last resort
        return new Anchor(q.length() > 28 ? q.substring(0, 28) : q, 0.30);
    }

    public List<String> cheapVariants(String query, Anchor anchor) {
        String q = safe(query).trim();
        if (q.isBlank()) {
            return Collections.emptyList();
        }
        String a = anchor != null ? safe(anchor.term()).trim() : "";

        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(q);

        if (!a.isBlank() && !isStopLike(a.toLowerCase(Locale.ROOT))) {
            String qLower = q.toLowerCase(Locale.ROOT);
            boolean addressIntent = qLower.contains("주소")
                    || qLower.contains("소재지")
                    || qLower.contains("본사")
                    || qLower.contains("위치")
                    || qLower.contains("어디")
                    || qLower.contains("근처")
                    || qLower.contains("주변")
                    || qLower.contains("인근")
                    || qLower.contains("근거리")
                    || qLower.contains("가까운")
                    || qLower.contains("고시원")
                    || qLower.contains("원룸")
                    || qLower.contains("자취")
                    || qLower.contains("숙소");

            if (addressIntent) {
                // Address/registered-office intent: generate explicit variants.
                out.add(a + " 본사 주소");
                out.add(a + " 소재지");
            } else {
                out.add(a);

                // Intent 기반 조건부 추가
                if (qLower.contains("의미") || qLower.contains("뜻")) {
                    out.add(a + " 의미");
                } else if (qLower.contains("사양") || qLower.contains("스펙") || qLower.contains("가격")) {
                    out.add(a + " 사양");
                } else {
                    out.add(a + " 정보"); // 기본 1개만
                }
            }
        }

        // 최대 3개로 캡
        if (out.size() > 3) {
            return new ArrayList<>(out).subList(0, 3);
        }
        return new ArrayList<>(out);
    }

    private static boolean isStopLike(String normalizedToken) {
        if (normalizedToken == null) return true;
        String t = normalizedToken.trim();
        if (t.isBlank()) return true;

        if (STOP_TOKENS.contains(t)) return true;

        // common suffix strip (e.g. "사양좀" -> "사양")
        String stripped = t;
        if (stripped.endsWith("좀") && stripped.length() > 1) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (stripped.endsWith("요") && stripped.length() > 1) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (!stripped.equals(t) && STOP_TOKENS.contains(stripped)) return true;

        // 명령형 패턴 매칭
        if (t.matches("^(알려줘|말해줘|검색해줘|찾아줘|해줘)(봐|요|줘)?$")) return true;
        if (t.matches("^(알려|검색|설명|말해|찾아)(줘|봐|요)?$")) return true;

        return false;
    }

    private static boolean isEmpty(List<String> xs) {
        return xs == null || xs.isEmpty();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
