// src/main/java/com/example/lms/service/knowledge/DefaultKnowledgeBaseService.java
package com.example.lms.service.knowledge;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.repository.DomainKnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DefaultKnowledgeBaseService implements KnowledgeBaseService {

    private final DomainKnowledgeRepository repo;

    // 소규모 캐시(운영 시 Caffeine 등 전환 가능)
    private final Map<String, Set<String>> nameCache = new ConcurrentHashMap<>();

    @Override
    public Optional<String> getAttribute(String domain, String entityName, String key) {
        return repo.findByDomainAndEntityNameIgnoreCase(domain, entityName)
                .flatMap(dk -> dk.getAttributes().stream()
                        .filter(a -> key.equalsIgnoreCase(a.getAttributeKey()))
                        .map(a -> Optional.ofNullable(a.getAttributeValue()))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .findFirst());
    }

    @Override
    public Policy getPairingPolicy(String domain, String entityName) {
        // 1) DB 정책 우선
        var allow = csvSet(getAttribute(domain, entityName, "PAIRING_POLICY_ALLOW").orElse(null));
        var dis   = csvSet(getAttribute(domain, entityName, "PAIRING_POLICY_DISCOURAGE").orElse(null));
        if (!allow.isEmpty() || !dis.isEmpty()) return new Policy(allow, dis);

        // 2) Fallback: ELEMENT 기반 보수 정책 (예: CRYO → allow: HYDRO,ELECTRO / discourage: PYRO,DENDRO)
        var elem = getAttribute(domain, entityName, "ELEMENT").orElse("");
        if ("CRYO".equalsIgnoreCase(elem)) {
            return new Policy(Set.of("HYDRO","ELECTRO"), Set.of("PYRO","DENDRO"));
        }
        // 3) 안전 기본값
        return new Policy(Set.of(), Set.of());
    }

    @Override
    public Set<String> listEntityNames(String domain, String entityType) {
        String key = (domain + "::" + entityType).toUpperCase(Locale.ROOT);
        return nameCache.computeIfAbsent(key, k -> {
            List<DomainKnowledge> list = repo.findByDomainAndEntityType(domain, entityType);
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (DomainKnowledge dk : list) out.add(dk.getEntityName());
            return Collections.unmodifiableSet(out);
        });
    }

    @Override
    public Set<String> findMentionedEntities(String domain, String text) {
        if (text == null || text.isBlank()) return Set.of();
        String s = text.toLowerCase(Locale.ROOT);
        Set<String> candidates = new LinkedHashSet<>();
        // 캐릭터만 우선
        for (String name : listEntityNames(domain, "CHARACTER")) {
            if (s.contains(name.toLowerCase(Locale.ROOT))) candidates.add(name);
        }
        return candidates;
    }

    private static Set<String> csvSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : csv.split("[,\\s]+")) {
            if (!t.isBlank()) out.add(t.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }
}
