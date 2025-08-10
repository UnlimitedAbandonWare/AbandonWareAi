package com.example.lms.service.correction;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.*;
// 예: 기본으로 쓰고 싶은 구현체
@Service
@Primary
public class DefaultDomainTermDictionary implements DomainTermDictionary {

    private static final Map<String, Set<String>> DICTIONARY = Map.of(
            "원신", Set.of("스커크", "푸리나", "폰타인", "skirk"),
            "붕괴 스타레일", Set.of("페나코니", "아케론", "반디")
    );

    @Override
    public Set<String> findKnownTerms(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> found = new LinkedHashSet<>();
        for (Set<String> terms : DICTIONARY.values()) {
            for (String t : terms) {
                if (lower.contains(t.toLowerCase(Locale.ROOT))) found.add(t);
            }
        }
        return found;
    }
}
