package com.example.lms.service.knowledge;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.domain.knowledge.EntityAttribute;
import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.MemorySnippet;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.rag.LangChainRAGService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DefaultKnowledgeBaseService implements KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeBaseService.class);

    private final DomainKnowledgeRepository repo;
    private final ObjectMapper om;
    private final VectorStoreService vectorStoreService;

    @Value("${knowledge.base.persist.enabled:true}")
    private boolean persistEnabled;

    @Value("${knowledge.base.index.enabled:true}")
    private boolean indexEnabled;

    @Value("${knowledge.base.persist.min-confidence:0.65}")
    private double minConfidence;

    @Value("${knowledge.base.index.max-attributes:32}")
    private int indexMaxAttributes;

    // 소규모 캐시(운영 시 Caffeine 등 전환 가능)
    private final Map<String, Set<String>> nameCache = new ConcurrentHashMap<>();

    @Override
    public List<String> getDomains() {
        try {
            return repo.findAllDomains();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<String> getEntityTypes(String domain) {
        if (domain == null || domain.isBlank()) {
            return List.of();
        }
        try {
            return repo.findEntityTypesByDomain(domain);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<String> listEntities(String domain, String entityType) {
        if (domain == null || domain.isBlank() || entityType == null || entityType.isBlank()) {
            return List.of();
        }

        String cacheKey = (domain.trim().toUpperCase(Locale.ROOT) + "|" + entityType.trim().toUpperCase(Locale.ROOT));
        Set<String> cached = nameCache.get(cacheKey);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        try {
            List<String> names = repo.findEntitiesByDomainAndType(domain, entityType);
            Set<String> set = new LinkedHashSet<>(names == null ? List.of() : names);
            nameCache.put(cacheKey, set);
            return new ArrayList<>(set);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public Optional<DomainKnowledge> find(String domain, String entityName) {
        if (domain == null || domain.isBlank() || entityName == null || entityName.isBlank()) {
            return Optional.empty();
        }
        try {
            return repo.findByDomainAndEntityNameIgnoreCase(domain, entityName);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // MERGE_HOOK:PROJ_AGENT::KB_PERSIST_INDEX_V1
    @Override
    @Transactional
    public IntegrationStatus integrateVerifiedKnowledge(String domain,
            String entityName,
            String structuredDataJson,
            List<String> sources,
            double confidenceScore) {
        if (!persistEnabled) {
            log.info("[KB][INTEGRATE] persist=SKIPPED:disabled domain={}, entity={}, conf={}", domain, entityName,
                    String.format(Locale.ROOT, "%.2f", confidenceScore));
            return IntegrationStatus.SKIPPED;
        }

        if (structuredDataJson == null || structuredDataJson.isBlank()) {
            log.warn("[KB][INTEGRATE] rejected: empty structuredDataJson domain={}, entity={}", domain, entityName);
            return IntegrationStatus.REJECTED;
        }

        if (Double.isNaN(confidenceScore) || confidenceScore < minConfidence) {
            log.info("[KB][INTEGRATE] rejected: low confidence {} < {} domain={}, entity={}",
                    String.format(Locale.ROOT, "%.2f", confidenceScore),
                    String.format(Locale.ROOT, "%.2f", minConfidence),
                    domain, entityName);
            return IntegrationStatus.REJECTED;
        }

        JsonNode root;
        try {
            root = om.readTree(sanitizeJson(structuredDataJson));
        } catch (Exception e) {
            log.warn("[KB][INTEGRATE] rejected: invalid json domain={}, entity={}, err={}", domain, entityName,
                    e.toString());
            return IntegrationStatus.REJECTED;
        }

        String dom = pickText(root, "domain", domain);
        String ent = pickText(root, "entity", entityName);
        String entityType = pickText(root, "entityType", "");
        if (entityType.isBlank()) {
            entityType = pickText(root, "type", "UNKNOWN");
        }

        if (dom.isBlank())
            dom = "GENERAL";
        if (ent.isBlank()) {
            log.warn("[KB][INTEGRATE] rejected: empty entity domain={} rawEntity={}", dom, entityName);
            return IntegrationStatus.REJECTED;
        }
        if (entityType.isBlank())
            entityType = "UNKNOWN";

        Map<String, String> attrs = extractAttributes(root.path("attributes"));
        if (attrs.isEmpty()) {
            // Some curation pipelines put attributes at root level.
            attrs = extractAttributes(root);
            // Avoid accidentally indexing the whole root including domain/entity keys.
            attrs.remove("domain");
            attrs.remove("entity");
            attrs.remove("entityType");
            attrs.remove("type");
            attrs.remove("sources");
        }

        if (attrs.isEmpty()) {
            log.warn("[KB][INTEGRATE] rejected: no attributes domain={}, entity={}", dom, ent);
            return IntegrationStatus.REJECTED;
        }

        List<String> mergedSources = mergeSources(sources, root.path("sources"));

        // Upsert DomainKnowledge
        DomainKnowledge dk = repo.findByDomainAndEntityNameIgnoreCase(dom, ent).orElse(null);
        boolean created = false;
        Instant now = Instant.now();

        if (dk == null) {
            created = true;
            dk = new DomainKnowledge();
            dk.setDomain(dom);
            dk.setEntityName(ent);
            dk.setEntityType(entityType);
            dk.setConfidenceScore(clamp01(confidenceScore));
            dk.setLastAccessedAt(now);
        } else {
            // Update only if missing/unknown.
            if (dk.getEntityType() == null || dk.getEntityType().isBlank()
                    || "UNKNOWN".equalsIgnoreCase(dk.getEntityType())) {
                dk.setEntityType(entityType);
            }
            dk.setConfidenceScore(
                    Math.max(Optional.ofNullable(dk.getConfidenceScore()).orElse(0.0), clamp01(confidenceScore)));
            dk.setLastAccessedAt(now);
        }

        if (dk.getAttributes() == null) {
            dk.setAttributes(new LinkedHashSet<>());
        }

        Map<String, EntityAttribute> byKey = new HashMap<>();
        for (EntityAttribute a : dk.getAttributes()) {
            if (a == null || a.getAttributeKey() == null)
                continue;
            byKey.put(normKey(a.getAttributeKey()), a);
        }

        for (Map.Entry<String, String> e : attrs.entrySet()) {
            String kRaw = safe(e.getKey());
            String vRaw = safe(e.getValue());
            if (kRaw.isBlank() || vRaw.isBlank())
                continue;

            String key = kRaw.trim();
            String norm = normKey(key);
            String val = safeValue(vRaw);
            if (norm.isBlank() || val.isBlank())
                continue;

            EntityAttribute existing = byKey.get(norm);
            if (existing == null) {
                EntityAttribute na = new EntityAttribute();
                na.setOwner(dk);
                na.setAttributeKey(key);
                na.setAttributeValue(val);
                dk.getAttributes().add(na);
                byKey.put(norm, na);
            } else {
                existing.setAttributeValue(val);
            }
        }

        // Store sources/confidence as special attributes (optional, but useful)
        if (!mergedSources.isEmpty()) {
            upsertAttr(dk, byKey, "_sources", omSafeJson(mergedSources));
        }
        upsertAttr(dk, byKey, "_confidence", String.format(Locale.ROOT, "%.4f", clamp01(confidenceScore)));

        repo.save(dk);
        nameCache.clear();

        if (indexEnabled) {
            try {
                indexKnowledgeToVectorStore(dk, attrs, mergedSources, confidenceScore);
            } catch (Exception e) {
                // fail-soft: KB 저장은 성공했으니 인덱싱만 경고
                log.warn("[KB][INTEGRATE] vector index failed (fail-soft): {}", e.toString());
            }
        }

        log.info("[KB][INTEGRATE] domain={}, entity={}, conf={}, sources={}, attrs={} (persist={}, index={})",
                dk.getDomain(), dk.getEntityName(),
                String.format(Locale.ROOT, "%.2f", clamp01(confidenceScore)),
                mergedSources.size(), attrs.size(),
                (created ? "CREATED" : "UPDATED"),
                (indexEnabled ? "QUEUED" : "SKIPPED"));

        return created ? IntegrationStatus.CREATED : IntegrationStatus.UPDATED;
    }

    @Override
    public IntegrationStatus apply(KnowledgeDelta delta) {
        if (delta == null) {
            return IntegrationStatus.SKIPPED;
        }

        int indexed = 0;

        // Best-effort: index memory snippets so RAG can hit them (global sid)
        if (indexEnabled && delta.memories() != null && !delta.memories().isEmpty()) {
            for (MemorySnippet m : delta.memories()) {
                if (m == null || m.text() == null || m.text().isBlank())
                    continue;

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put(VectorMetaKeys.META_SOURCE_TAG, "SYSTEM");
                meta.put(VectorMetaKeys.META_ORIGIN, "SYSTEM");
                meta.put(VectorMetaKeys.META_VERIFIED, String.valueOf(m.confidence() >= 0.75));
                meta.put(VectorMetaKeys.META_CITATION_COUNT, 0);
                if (m.subject() != null && !m.subject().isBlank()) {
                    meta.put("kb_subject", m.subject());
                }
                meta.put("kb_confidence", clamp01(m.confidence()));

                String stableId = "kbm:" + DigestUtils.sha1Hex(m.subject() + "|" + m.text());
                vectorStoreService.enqueue(stableId, LangChainRAGService.GLOBAL_SID, m.text(), meta);
                indexed++;

                if (indexed >= 200) {
                    break;
                }
            }
        }

        if (indexed > 0) {
            return IntegrationStatus.UPDATED;
        }

        return IntegrationStatus.SKIPPED;
    }

    private void indexKnowledgeToVectorStore(DomainKnowledge dk,
            Map<String, String> attrs,
            List<String> sources,
            double confidence) {
        String text = renderForEmbedding(dk, attrs, sources);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(VectorMetaKeys.META_SOURCE_TAG, "SYSTEM");
        meta.put(VectorMetaKeys.META_ORIGIN, "SYSTEM");
        meta.put(VectorMetaKeys.META_VERIFIED, "true");
        meta.put("kb_domain", dk.getDomain());
        meta.put("kb_entity", dk.getEntityName());
        meta.put("kb_entity_type", dk.getEntityType());
        meta.put("kb_confidence", clamp01(confidence));
        meta.put(VectorMetaKeys.META_CITATION_COUNT, sources == null ? 0 : sources.size());

        // Stable per entity, so updates upsert instead of accumulating duplicates.
        String vectorId = "kb:" + DigestUtils.sha1Hex(dk.getDomain() + "|" + dk.getEntityName());

        // 글로벌 SID로 인덱싱하여 세션 무관하게 RAG에서 히트되게
        vectorStoreService.enqueue(vectorId, LangChainRAGService.GLOBAL_SID, text, meta);
    }

    private String renderForEmbedding(DomainKnowledge dk, Map<String, String> attrs, List<String> sources) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("[KB] domain=").append(safe(dk.getDomain()))
                .append(" entity=").append(safe(dk.getEntityName()))
                .append(" type=").append(safe(dk.getEntityType()))
                .append(" conf=")
                .append(String.format(Locale.ROOT, "%.2f", Optional.ofNullable(dk.getConfidenceScore()).orElse(0.0)))
                .append("\n");

        int maxAttrs = Math.max(4, Math.min(indexMaxAttributes, 128));
        int i = 0;

        // Stable ordering improves determinism for hashing/upserts.
        List<Map.Entry<String, String>> entries = new ArrayList<>(attrs.entrySet());
        entries.sort(Comparator.comparing(e -> safe(e.getKey()).toLowerCase(Locale.ROOT)));

        for (Map.Entry<String, String> ent : entries) {
            String k = safe(ent.getKey());
            String v = safe(ent.getValue());
            if (k.isBlank() || v.isBlank())
                continue;
            sb.append("- ").append(k).append(": ").append(v).append("\n");
            if (++i >= maxAttrs) {
                sb.append("- ... (truncated)\n");
                break;
            }
        }

        if (sources != null && !sources.isEmpty()) {
            sb.append("Sources:\n");
            sources.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .limit(8)
                    .forEach(s -> sb.append("- ").append(s).append("\n"));
        }

        return sb.toString().trim();
    }

    private static String sanitizeJson(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "");
            t = t.replaceFirst("\\s*```\\s*$", "");
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            t = t.substring(start, end + 1);
        }
        return t;
    }

    private static String pickText(JsonNode root, String key, String fallback) {
        String v = "";
        try {
            if (root != null && root.has(key)) {
                v = safe(root.path(key).asText(""));
            }
        } catch (Exception ignore) {
        }
        if (v.isBlank()) {
            v = safe(fallback);
        }
        return v == null ? "" : v.trim();
    }

    private static List<String> mergeSources(List<String> sources, JsonNode sourcesNode) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (sources != null) {
            for (String s : sources) {
                if (s != null && !s.isBlank())
                    out.add(s.trim());
            }
        }
        if (sourcesNode != null && sourcesNode.isArray()) {
            for (JsonNode n : sourcesNode) {
                String s = safe(n.asText(""));
                if (s != null && !s.isBlank())
                    out.add(s.trim());
            }
        }
        return new ArrayList<>(out);
    }

    private static Map<String, String> extractAttributes(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }

        // object form: {"k":"v", ...}
        if (node.isObject()) {
            Map<String, String> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                String k = safe(e.getKey());
                String v = stringifyJsonValue(e.getValue());
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    out.put(k, v);
                }
            });
            return out;
        }

        // array form: [{"name":"k","value":"v"}, ...]
        if (node.isArray()) {
            Map<String, String> out = new LinkedHashMap<>();
            for (JsonNode n : node) {
                if (n == null || !n.isObject())
                    continue;

                String k = safe(n.path("name").asText(""));
                if (k.isBlank()) {
                    k = safe(n.path("key").asText(""));
                }
                if (k.isBlank()) {
                    k = safe(n.path("attributeKey").asText(""));
                }

                String v = safe(n.path("value").asText(""));
                if (v.isBlank()) {
                    v = safe(n.path("attributeValue").asText(""));
                }

                if (!k.isBlank() && !v.isBlank()) {
                    out.put(k, v);
                }
            }
            return out;
        }

        return Map.of();
    }

    private static String stringifyJsonValue(JsonNode v) {
        if (v == null || v.isNull())
            return "";
        if (v.isTextual() || v.isNumber() || v.isBoolean())
            return v.asText("");
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode x : v) {
                String s = stringifyJsonValue(x);
                if (s != null && !s.isBlank())
                    parts.add(s);
            }
            return String.join(", ", parts);
        }
        try {
            return v.toString();
        } catch (Exception ignore) {
            return "";
        }
    }

    private static void upsertAttr(DomainKnowledge dk, Map<String, EntityAttribute> byKey, String key, String value) {
        if (dk == null)
            return;
        if (dk.getAttributes() == null) {
            dk.setAttributes(new LinkedHashSet<>());
        }

        String k = normKey(key);
        String v = safeValue(value);
        if (k.isBlank() || v.isBlank())
            return;

        EntityAttribute a = byKey.get(k);
        if (a == null) {
            a = new EntityAttribute();
            a.setOwner(dk);
            a.setAttributeKey(k);
            a.setAttributeValue(v);
            dk.getAttributes().add(a);
            byKey.put(k, a);
        } else {
            a.setAttributeValue(v);
        }
    }

    private static String omSafeJson(List<String> sources) {
        if (sources == null || sources.isEmpty())
            return "[]";
        try {
            // cheap, no ObjectMapper required here.
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0)
                    sb.append(',');
                sb.append('"').append(sources.get(i).replace("\"", "\\\"")).append('"');
            }
            sb.append(']');
            return sb.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String normKey(String s) {
        if (s == null)
            return "";
        return s.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private static String safeValue(String s) {
        if (s == null)
            return "";
        String t = s.strip();
        if (t.isBlank())
            return "";
        int max = 4000;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static double clamp01(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d))
            return 0.0;
        return Math.max(0.0, Math.min(1.0, d));
    }

    @Override
    public Optional<Instant> getLastAccessedAt(String domain, String entityName) {
        try {
            return repo.findByDomainAndEntityNameIgnoreCase(domain, entityName)
                    .map(DomainKnowledge::getLastAccessedAt);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Double> getConfidenceScore(String domain, String entityName) {
        try {
            return repo.findByDomainAndEntityNameIgnoreCase(domain, entityName)
                    .map(DomainKnowledge::getConfidenceScore);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getAttribute(String domain, String entityName, String attributeKey) {
        if (domain == null || entityName == null || attributeKey == null) {
            return Optional.empty();
        }
        try {
            return find(domain, entityName).flatMap(dk -> {
                if (dk.getAttributes() == null)
                    return Optional.empty();
                return dk.getAttributes().stream()
                        .filter(a -> a != null && attributeKey.equalsIgnoreCase(a.getAttributeKey()))
                        .map(EntityAttribute::getAttributeValue)
                        .findFirst();
            });
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Set<String>> getAllRelationships(String domain, String entityName) {
        if (domain == null || entityName == null) {
            return Map.of();
        }
        try {
            return find(domain, entityName).map(dk -> {
                if (dk.getAttributes() == null)
                    return Map.<String, Set<String>>of();
                Map<String, Set<String>> out = new LinkedHashMap<>();
                for (EntityAttribute a : dk.getAttributes()) {
                    if (a == null || a.getAttributeKey() == null)
                        continue;
                    String k = a.getAttributeKey().toUpperCase(Locale.ROOT);
                    if (k.startsWith("RELATIONSHIP_")) {
                        String v = a.getAttributeValue();
                        if (v != null && !v.isBlank()) {
                            Set<String> values = new LinkedHashSet<>();
                            for (String s : v.split("[,\\s]+")) {
                                if (!s.isBlank())
                                    values.add(s.trim());
                            }
                            out.put(k, values);
                        }
                    }
                }
                return out;
            }).orElse(Map.of());
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Override
    public Set<String> listEntityNames(String domain, String entityType) {
        if (domain == null || entityType == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(listEntities(domain, entityType));
    }

    @Override
    public Set<String> findMentionedEntities(String domain, String text) {
        if (domain == null || text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        try {
            // 도메인 내 모든 엔티티 타입에서 엔티티 이름을 가져와 텍스트에서 검색
            List<String> types = getEntityTypes(domain);
            for (String type : types) {
                for (String name : listEntities(domain, type)) {
                    if (name != null && !name.isBlank()
                            && text.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                        result.add(name);
                    }
                }
            }
        } catch (Exception e) {
            // fail-soft
        }
        return result;
    }

    @Override
    public Policy getPairingPolicy(String domain, String entityName) {
        return new Policy(Set.of(), Set.of());
    }
}
