package com.example.lms.service.subject;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 메인 SubjectResolver (통합판)
 * 우선순위:
 *  1) KnowledgeBase에 등록된 도메인 엔티티(CHARACTER) 이름 매칭
 *  2) 폴백 휴리스틱(따옴표 안 토큰 → 첫 토큰(2+자))
 */
@Component // 빈 이름 기본: "subjectResolver"
@RequiredArgsConstructor
public class SubjectResolver {

    private final KnowledgeBaseService kb;

    /**
     * KB 기반 탐지(도메인 내 캐릭터명 포함 시 즉시 반환) + 휴리스틱 폴백.
     */
    public Optional<String> resolve(String query, String domain) {
        if (query == null || query.isBlank()) return Optional.empty();

        // 1) KB 매칭
        if (domain != null && !domain.isBlank()) {
            String s = query.toLowerCase(Locale.ROOT);
            for (String name : kb.listEntityNames(domain, "CHARACTER")) {
                if (s.contains(name.toLowerCase(Locale.ROOT))) {
                    return Optional.of(name);
                }
            }
        }

        // 2) 휴리스틱 폴백 (이전 rag/subject/SubjectResolver의 로직 흡수)
        String guess = heuristicGuess(query);
        return guess.isBlank() ? Optional.empty() : Optional.of(guess);
    }

    /** Reranker 등 DI 없이 정적으로 쓰도록 제공 */
    public static String guessSubjectFromQueryStatic(KnowledgeBaseService kb, String domain, String query) {
        return new SubjectResolver(kb).resolve(query, domain).orElse("");
    }

    // ─────────────────────────────
    // 내부 휴리스틱 (따옴표 > 첫 토큰)
    // ─────────────────────────────
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,})\"");

    private static String heuristicGuess(String query) {
        String q = query.trim();

        // 큰따옴표 내부 토큰이 있으면 우선 사용
        var m = QUOTED.matcher(q);
        if (m.find()) {
            String t = safeTrim(m.group(1));
            if (t.length() >= 2) return t;
        }

        // 한글/영문 혼합 고려: 첫 토큰(2자 이상)
        String ko = q.replaceAll("[\"“”`']", "").split("\\s+")[0];
        if (ko != null && ko.length() >= 2) return ko;

        return "";
    }

    private static String safeTrim(String s) { return s == null ? "" : s.trim(); }
}
