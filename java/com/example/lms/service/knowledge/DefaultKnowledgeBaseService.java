package com.example.lms.service.knowledge;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.repository.DomainKnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Service
@RequiredArgsConstructor
public class DefaultKnowledgeBaseService implements KnowledgeBaseService {
    private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeBaseService.class);

    private final DomainKnowledgeRepository repo;

    // 소규모 캐시(운영 시 Caffeine 등 전환 가능)
    private final Map<String, Set<String>> nameCache = new ConcurrentHashMap<>();

    @Override
    public Optional<String> getAttribute(String domain, String entityName, String key) {
        return repo.findByDomainAndEntityNameIgnoreCase(domain, entityName)
                .map(dk -> {
                    // 갱신: 지식이 조회되었으므로 lastAccessedAt을 업데이트합니다.
                    try {
                        dk.setLastAccessedAt(java.time.Instant.now());
                        // 변경 사항을 즉시 반영하도록 저장합니다. 트랜잭션 컨텍스트가 없으면 단순 업데이트로 동작합니다.
                        repo.save(dk);
                    } catch (Exception ignore) {
                        // 저장 실패 시 로그만 남기고 무시합니다.
                    }
                    return dk;
                })
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

    @Override
    public Map<String, Set<String>> getAllRelationships(String domain, String entityName) {
        return repo.findByDomainAndEntityNameIgnoreCase(domain, entityName)
                .map(dk -> {
                    // 갱신: 지식이 조회되었으므로 lastAccessedAt을 업데이트합니다.
                    try {
                        dk.setLastAccessedAt(java.time.Instant.now());
                        repo.save(dk);
                    } catch (Exception ignore) {
                        // ignore persistence issues
                    }
                    Map<String, Set<String>> map = new LinkedHashMap<>();
                    dk.getAttributes().stream()
                            .filter(a -> a.getAttributeKey() != null
                                    && a.getAttributeKey().toUpperCase(Locale.ROOT).startsWith("RELATIONSHIP_"))
                            .forEach(a -> {
                                String key = a.getAttributeKey().toUpperCase(Locale.ROOT);
                                Set<String> vals = csvSet(a.getAttributeValue());
                                if (!vals.isEmpty()) map.put(key, vals);
                            });
                    return Collections.unmodifiableMap(map);
                })
                .orElseGet(Collections::emptyMap);
    }

    private static Set<String> csvSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : csv.split("[,\\s]+")) {
            if (!t.isBlank()) out.add(t.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * [신규 재정의] 검증된 지식 통합 API의 구현체입니다.
     * 현재는 실제 데이터베이스에 저장하지 않고, 통합 시도에 대한 로그만 기록합니다.
     * 향후 DB 엔티티 구조가 확정되면 이 부분에 실제 영속화 로직이 구현될 것입니다.
     */
    @Override
    public IntegrationStatus integrateVerifiedKnowledge(String domain, String entityName, String structuredDataJson, List<String> sources, double confidenceScore) {
        log.info("[KB][INTEGRATE] domain={}, entity={}, conf={}, sources={} (persist=SKIPPED)",
                domain, entityName, String.format("%.2f", confidenceScore), (sources == null ? 0 : sources.size()));
        return IntegrationStatus.SKIPPED;
    }

    /**
     * Apply a structured knowledge delta to the knowledge base.  This
     * implementation currently performs no actual upsert into the database;
     * instead it logs the incoming delta and returns {@link IntegrationStatus#SKIPPED}.
     * Future iterations should parse triples, rules and aliases contained in
     * the delta and persist them into the {@link DomainKnowledge} and related
     * tables via {@link DomainKnowledgeRepository}.
     *
     * @param delta the structured knowledge delta produced by Gemini curation
     * @return {@code SKIPPED} indicating no changes were applied
     */
    @Override
    public IntegrationStatus apply(com.example.lms.dto.learning.KnowledgeDelta delta) {
        if (delta == null) {
            return IntegrationStatus.SKIPPED;
        }
        try {
            log.info("[KB][APPLY] received KnowledgeDelta: triples={}, rules={}, aliases={}, memories={}, terms={}",
                    delta.triples().size(), delta.rules().size(), delta.aliases().size(), delta.memories().size(), delta.protectedTerms().size());
        } catch (Exception ignore) {
            // ignore logging errors
        }
        // No persistence implemented yet; return SKIPPED to indicate no-op
        return IntegrationStatus.SKIPPED;
    }


    @Override
    public java.util.Optional<java.time.Instant> getLastAccessedAt(String domain, String entityName) {
        try {
            return repo.findByDomainAndEntityNameIgnoreCase(domain, entityName)
                    .map(DomainKnowledge::getLastAccessedAt);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public java.util.Optional<java.lang.Double> getConfidenceScore(String domain, String entityName) {
        try {
            return repo.findByDomainAndEntityNameIgnoreCase(domain, entityName)
                    .map(DomainKnowledge::getConfidenceScore);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

}