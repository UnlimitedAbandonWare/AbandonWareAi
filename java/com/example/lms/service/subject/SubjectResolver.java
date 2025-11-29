package com.example.lms.service.subject;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.repository.DomainKnowledgeRepository;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;




/**
 * 사용자 쿼리에서 핵심 주제어를 추출하는 메인 해석기 (통합 버전).
 */
@Component("subjectResolver")
public class SubjectResolver {

    private final KnowledgeBaseService kb;
    private final DomainKnowledgeRepository knowledgeRepo;

    /**
     * 기본 생성자: KnowledgeBaseService 만 주입되고, DomainKnowledgeRepository 는 null 로 초기화됩니다.
     * 테스트 또는 기존 호환성을 위해 제공됩니다.
     */
    public SubjectResolver(KnowledgeBaseService kb) {
        this(kb, null);
    }

    /**
     * 전체 생성자: KnowledgeBaseService 와 DomainKnowledgeRepository 를 주입합니다.
     * Spring DI 환경에서는 두 의존성이 함께 제공됩니다.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SubjectResolver(KnowledgeBaseService kb, @org.springframework.beans.factory.annotation.Autowired(required = false) DomainKnowledgeRepository knowledgeRepo) {
        this.kb = kb;
        this.knowledgeRepo = knowledgeRepo;
    }

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

    /**
     * 입력된 텍스트에서 지식베이스에 등록된 모든 엔티티 이름을 찾아 반환합니다.
     * DomainKnowledgeRepository 가 주입된 경우에만 동작하며, 주입되지 않은 경우 빈 목록을 반환합니다.
     * 이름 비교는 대소문자를 구분하지 않으며, 발견 순서를 유지합니다.
     *
     * @param query 사용자 질문 텍스트
     * @return 발견된 엔티티 이름 목록 (없으면 빈 목록)
     */
    public java.util.List<String> resolveMultipleEntities(String query) {
        if (query == null || query.isBlank()) return java.util.Collections.emptyList();
        // knowledgeRepo 가 없으면 기본적으로 빈 결과를 반환
        if (this.knowledgeRepo == null) return java.util.Collections.emptyList();
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        try {
            for (com.example.lms.domain.knowledge.DomainKnowledge dk : this.knowledgeRepo.findAll()) {
                String name = dk.getEntityName();
                if (name != null && !name.isBlank()) {
                    if (lower.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception ignore) {
            // 예외 발생 시 안전하게 빈 목록 반환
            return java.util.Collections.emptyList();
        }
        return new java.util.ArrayList<>(names);
    }

    public static String guessSubjectFromQueryStatic(KnowledgeBaseService kb, String domain, String query) {
        return new SubjectResolver(kb).resolve(query, domain).orElse("");
    }
}