package com.example.lms.service.correction;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;   // ✅ Service 임포트 추가
import java.util.*;

@Service("defaultDomainTermDictionary")    // ✅ Qualifier에서 부르는 이름을 명시(안 써도 기본 네이밍이 같음)
@Primary                                   // ✅ 이 구현을 기본으로 쓰고 싶으면 유지, 아니면 제거
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