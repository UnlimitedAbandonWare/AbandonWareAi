package com.example.lms.service.correction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;




/**
 * application.yml 의 "query.correction.protected-terms" (콤마 구분) 값을 읽어
 * 보호어 사전을 구성하는 기본 구현.
 */
@Component
public class InMemoryDomainTermDictionary implements DomainTermDictionary {

    private final Set<String> protectedTerms;

    public InMemoryDomainTermDictionary(
            @Value("${query.correction.protected-terms:}") String protectedTermsCsv
    ) {
        if (protectedTermsCsv == null || protectedTermsCsv.isBlank()) {
            this.protectedTerms = Set.of();
        } else {
            this.protectedTerms = Arrays.stream(protectedTermsCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    @Override
    public Set<String> findKnownTerms(String text) {
        if (text == null || text.isBlank() || protectedTerms.isEmpty()) return Set.of();
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (String t : protectedTerms) {
            if (lower.contains(t.toLowerCase(Locale.ROOT))) out.add(t);
        }
        return out;
    }
}