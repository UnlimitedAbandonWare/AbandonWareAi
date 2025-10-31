package com.example.lms.service.correction;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import java.util.*;



@Service("defaultDomainTermDictionary")
@Primary
public class DefaultDomainTermDictionary implements DomainTermDictionary {

    private static final Map<String, Set<String>> DICTIONARY = Map.of(
            // 원신 관련 보호어(교정 단계에서 보존)
            "원신", Set.of("스커크", "푸리나", "폰타인", "skirk", "다이루크", "클로린드", "향릉"),
            // 실존 인물/요리사(게임 비도메인 힌트)
            "요리/인물", Set.of("에스코피에", "에스코피", "Auguste Escoffier", "escoffier"),
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