package com.example.lms.service.subject;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사용자 쿼리에서 핵심 주제어를 추출하는 메인 해석기 (통합 버전).
 */
@Component("subjectResolver")
@RequiredArgsConstructor
public class SubjectResolver {

    private final KnowledgeBaseService kb;

    private static final Pattern QUOTED_PATTERN   = Pattern.compile("[\"“”'’`](.+?)[\"“”'’`]");
    private static final Pattern COMPOUND_PATTERN = Pattern.compile("(?i)\\b([a-z]{1,4}\\d+[a-z]*|\\d+[a-z]{1,4}|[가-힣A-Za-z]{2,20}학원|아카데미|Academy)\\b");
    private static final Pattern ACRONYM_PATTERN  = Pattern.compile("\\b[A-Z]{2,5}\\b");

    public Optional<String> resolve(String query, String domain) {
        if (query == null || query.isBlank()) return Optional.empty();

        if (domain != null && !domain.isBlank()) {
            String lower = query.toLowerCase(Locale.ROOT);
            for (String name : kb.listEntityNames(domain, "CHARACTER")) {
                if (lower.contains(name.toLowerCase(Locale.ROOT))) {
                    return Optional.of(name);
                }
            }
        }
        return heuristicGuess(query);
    }

    private Optional<String> heuristicGuess(String query) {
        Matcher m1 = QUOTED_PATTERN.matcher(query);
        if (m1.find()) return Optional.ofNullable(m1.group(1)).map(String::trim).filter(s -> !s.isBlank());

        Matcher m2 = COMPOUND_PATTERN.matcher(query);
        if (m2.find()) return Optional.ofNullable(m2.group(0)).map(String::trim).filter(s -> !s.isBlank());

        Matcher m3 = ACRONYM_PATTERN.matcher(query);
        if (m3.find()) return Optional.ofNullable(m3.group(0)).map(String::trim).filter(s -> !s.isBlank());

        String cleaned = query.replaceAll("[^\\p{IsHangul}A-Za-z0-9 ]", " ").trim();
        if (cleaned.isBlank()) return Optional.empty();
        String first = cleaned.split("\\s+")[0];
        return (first.length() >= 2 && first.length() <= 12) ? Optional.of(first) : Optional.empty();
    }

    public static String guessSubjectFromQueryStatic(KnowledgeBaseService kb, String domain, String query) {
        return new SubjectResolver(kb).resolve(query, domain).orElse("");
    }
}
