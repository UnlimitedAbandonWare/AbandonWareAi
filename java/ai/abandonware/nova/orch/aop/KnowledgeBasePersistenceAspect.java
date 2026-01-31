package ai.abandonware.nova.orch.aop;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.domain.knowledge.EntityAttribute;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Nova Overlay: "persist=SKIPPED" 상태로 남아있는 KB 통합(integrateVerifiedKnowledge)을
 * 실제 저장소(JPA Repository)로 연결합니다.
 *
 * - 기존 DefaultKnowledgeBaseService.integrateVerifiedKnowledge(...)는 SKIPPED를
 * 반환하며
 * DB에 아무것도 저장하지 않습니다.
 * - 이 AOP는 해당 호출을 가로채 DomainKnowledge / EntityAttribute로 upsert 합니다.
 *
 * 주의:
 * - DomainKnowledge는 현재 entityName이 전역 유니크로 선언되어 있어
 * (domain+entityName 유니크가 아닌) 충돌이 발생할 수 있습니다.
 * 충돌 시 entityName 기반 폴백 조회를 시도하고, 그래도 실패하면 원래 메서드(SKIPPED)로 폴백합니다.
 */
@Slf4j
@Aspect
public class KnowledgeBasePersistenceAspect {

    private final DomainKnowledgeRepository repo;
    private final ObjectMapper om;

    // MERGE_HOOK:PROJ_AGENT::KB_PERSIST_ASPECT_OVERLAY_TOGGLE_V1
    @Value("${knowledge.integrate.overlay.enabled:${nova.orch.kb-persistence.enabled:false}}")
    private boolean overlayEnabled;

    @Value("${knowledge.integrate.overlay.persist.enabled:true}")
    private boolean persistEnabled;

    @Value("${knowledge.integrate.overlay.min-confidence:0.0}")
    private double minConfidence;

    @Value("${knowledge.integrate.overlay.max-attributes:50}")
    private int maxAttributes;

    public KnowledgeBasePersistenceAspect(DomainKnowledgeRepository repo, ObjectMapper om) {
        this.repo = repo;
        this.om = om;
    }

    @Around("execution(* com.example.lms.service.knowledge.DefaultKnowledgeBaseService.integrateVerifiedKnowledge(..))")
    public Object persistVerifiedKnowledge(ProceedingJoinPoint pjp) throws Throwable {
        // MERGE_HOOK:PROJ_AGENT::KB_PERSIST_ASPECT_PASS_THROUGH_V1
        // Prefer the concrete KnowledgeBaseService implementation. This aspect is kept
        // as an optional
        // legacy overlay (disabled by default) for deployments where
        // integrateVerifiedKnowledge() is still a no-op.
        Object proceeded = pjp.proceed();

        // Overlay can be disabled explicitly (default ON when this aspect is enabled).
        if (!overlayEnabled) {
            return proceeded;
        }

        // If the underlying implementation already performed persistence, do not
        // override.
        if (proceeded instanceof KnowledgeBaseService.IntegrationStatus st
                && st != KnowledgeBaseService.IntegrationStatus.SKIPPED) {
            return proceeded;
        }

        // Overlay can be gated separately.
        if (!persistEnabled) {
            return proceeded;
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length < 5) {
            return proceeded;
        }

        try {
            String domain = safeString(args[0]);
            String entityName = safeString(args[1]);
            String structuredJson = safeString(args[2]);
            @SuppressWarnings("unchecked")
            List<String> sources = (List<String>) args[3];
            Double confidence = safeDouble(args[4]);

            if (isBlank(structuredJson) || isBlank(entityName)) {
                return proceeded;
            }
            if (confidence != null && confidence < minConfidence) {
                return proceeded;
            }

            ParsedKnowledge parsed = parse(structuredJson);
            String d = isBlank(domain) ? parsed.domain : domain;
            String e = isBlank(entityName) ? parsed.entity : entityName;
            if (isBlank(d))
                d = "GENERAL";
            if (isBlank(e)) {
                return proceeded;
            }

            List<Kv> attrs = parsed.attributes == null ? List.of() : parsed.attributes;
            if (attrs.size() > maxAttributes) {
                attrs = attrs.subList(0, maxAttributes);
            }

            KnowledgeBaseService.IntegrationStatus status = upsert(d.trim(), e.trim(), attrs, confidence,
                    sources == null ? List.of() : sources);
            return status;
        } catch (Exception e) {
            log.warn("[nova][kb-persist] overlay persist failed; returning original result. err={}", e.toString());
            return proceeded;
        }
    }

    private KnowledgeBaseService.IntegrationStatus upsert(
            String domain,
            String entityName,
            List<Kv> attributes,
            Double confidence,
            List<String> sources) {
        Optional<DomainKnowledge> found = repo.findByDomainAndEntityNameIgnoreCase(domain, entityName);
        if (found.isEmpty()) {
            // DomainKnowledge.entityName is currently global-unique; use a fallback lookup
            // when needed.
            found = repo.findByEntityNameIgnoreCase(entityName);
        }

        DomainKnowledge dk = found.orElseGet(DomainKnowledge::new);
        boolean created = (dk.getId() == null);

        dk.setDomain(domain);
        dk.setEntityName(entityName);

        if (confidence != null) {
            dk.setConfidenceScore(confidence);
        }

        mergeAttributes(dk, attributes, sources);

        try {
            repo.save(dk);
        } catch (DataIntegrityViolationException dive) {
            // One more attempt: if unique constraint on entityName is hit, try updating the
            // existing row.
            Optional<DomainKnowledge> fallback = repo.findByEntityNameIgnoreCase(entityName);
            if (fallback.isPresent()) {
                DomainKnowledge existing = fallback.get();
                existing.setDomain(domain);
                if (confidence != null)
                    existing.setConfidenceScore(confidence);
                mergeAttributes(existing, attributes, sources);
                repo.save(existing);
                return KnowledgeBaseService.IntegrationStatus.UPDATED;
            }
            throw dive;
        }

        if (created) {
            log.info("[nova][kb-persist] created domain='{}' entity='{}' attrs={} sources={} conf={}",
                    domain, entityName, attributes.size(), sources == null ? 0 : sources.size(), confidence);
            return KnowledgeBaseService.IntegrationStatus.CREATED;
        }

        log.info("[nova][kb-persist] updated domain='{}' entity='{}' attrs={} sources={} conf={}",
                domain, entityName, attributes.size(), sources == null ? 0 : sources.size(), confidence);
        return KnowledgeBaseService.IntegrationStatus.UPDATED;
    }

    private void mergeAttributes(DomainKnowledge dk, List<Kv> attrs, List<String> sources) {
        Set<EntityAttribute> set = dk.getAttributes();
        if (set == null) {
            set = new LinkedHashSet<>();
            dk.setAttributes(set);
        }

        Map<String, EntityAttribute> existingByKey = new HashMap<>();
        for (EntityAttribute ea : set) {
            if (ea == null || ea.getAttributeKey() == null)
                continue;
            existingByKey.put(ea.getAttributeKey().trim().toLowerCase(Locale.ROOT), ea);
        }

        if (attrs != null) {
            for (Kv kv : attrs) {
                if (kv == null || isBlank(kv.key))
                    continue;
                String normalized = kv.key.trim();
                String nKey = normalized.toLowerCase(Locale.ROOT);
                String value = kv.value == null ? "" : kv.value;

                EntityAttribute ea = existingByKey.get(nKey);
                if (ea == null) {
                    ea = new EntityAttribute();
                    ea.setOwner(dk);
                    ea.setAttributeKey(normalized);
                    ea.setAttributeValue(value);
                    set.add(ea);
                    existingByKey.put(nKey, ea);
                } else {
                    ea.setAttributeValue(value);
                }
            }
        }

        // Optional: store sources as a reserved attribute when provided.
        if (sources != null && !sources.isEmpty()) {
            String nKey = "_sources";
            EntityAttribute ea = existingByKey.get(nKey);
            String packed = String.join("\n", sources);
            if (ea == null) {
                ea = new EntityAttribute();
                ea.setOwner(dk);
                ea.setAttributeKey(nKey);
                ea.setAttributeValue(packed);
                set.add(ea);
            } else {
                ea.setAttributeValue(packed);
            }
        }
    }

    private ParsedKnowledge parse(String structuredJson) {
        if (isBlank(structuredJson)) {
            return new ParsedKnowledge(null, null, List.of());
        }

        try {
            JsonNode root = om.readTree(structuredJson);
            String domain = text(root, "domain");
            String entity = text(root, "entity");

            List<Kv> attrs = new ArrayList<>();
            JsonNode a = root.get("attributes");
            if (a != null && a.isArray()) {
                for (JsonNode n : a) {
                    if (n == null || !n.isObject())
                        continue;
                    String k = text(n, "name");
                    JsonNode vNode = n.get("value");
                    if (isBlank(k))
                        continue;
                    String v = (vNode == null || vNode.isNull()) ? ""
                            : (vNode.isTextual() ? vNode.asText() : vNode.toString());
                    attrs.add(new Kv(k, v));
                }
            }

            return new ParsedKnowledge(domain, entity, attrs);
        } catch (Exception e) {
            log.debug("[nova][kb-persist] JSON parse failed; will try fallback args only. err={}", e.toString());
            return new ParsedKnowledge(null, null, List.of());
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null)
            return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull())
            return null;
        return v.isTextual() ? v.asText() : v.toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (!isBlank(a))
            return a;
        if (!isBlank(b))
            return b;
        return null;
    }

    private static Double safeDouble(Object o) {
        if (o == null)
            return null;
        if (o instanceof Number n)
            return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private record ParsedKnowledge(String domain, String entity, List<Kv> attributes) {
    }

    private record Kv(String key, String value) {
    }
}
