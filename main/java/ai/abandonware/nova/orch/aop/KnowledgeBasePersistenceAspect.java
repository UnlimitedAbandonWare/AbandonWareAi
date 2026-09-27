package ai.abandonware.nova.orch.aop;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.domain.knowledge.EntityAttribute;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
 * - 기본 서비스가 저장한 결과는 그대로 유지합니다.
 * - SKIPPED인 경우에만 선택적으로 DomainKnowledge / EntityAttribute를 upsert 합니다.
 * - 저장 및 충돌 재조회는 항상 domain+entityName 범위에 한정합니다.
 * 다른 도메인의 동명 행을 이동하지 않으며, 저장 실패 시 원래 결과로 폴백합니다.
 */
@Slf4j
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 55)
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
            int attributeLimit = Math.max(0, maxAttributes);
            if (attrs.size() > attributeLimit) {
                attrs = attrs.subList(0, attributeLimit);
            }

            KnowledgeBaseService.IntegrationStatus status = upsert(d.trim(), e.trim(), attrs, confidence,
                    sources == null ? List.of() : sources);
            return status;
        } catch (Exception e) {
            log.warn("[nova][kb-persist] overlay persist failed; returning original result. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
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
            WebFailSoftTraceSuppressions.trace("knowledgeBasePersistence.uniqueConstraintRetry", dive);
            // A concurrent insert may have created this domain's row. Never move a
            // same-name row from another domain, including with an older global constraint.
            Optional<DomainKnowledge> fallback = repo.findByDomainAndEntityNameIgnoreCase(domain, entityName);
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
            log.info("[nova][kb-persist] created domainHash={} domainLength={} entityHash={} entityLength={} attrs={} sources={} conf={}",
                    SafeRedactor.hashValue(domain), lengthOf(domain), SafeRedactor.hashValue(entityName), lengthOf(entityName), attributes.size(), sources == null ? 0 : sources.size(), confidence);
            return KnowledgeBaseService.IntegrationStatus.CREATED;
        }

        log.info("[nova][kb-persist] updated domainHash={} domainLength={} entityHash={} entityLength={} attrs={} sources={} conf={}",
                SafeRedactor.hashValue(domain), lengthOf(domain), SafeRedactor.hashValue(entityName), lengthOf(entityName), attributes.size(), sources == null ? 0 : sources.size(), confidence);
        return KnowledgeBaseService.IntegrationStatus.UPDATED;
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
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
            log.debug("[nova][kb-persist] JSON parse failed; will try fallback args only. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
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
        if (o instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                WebFailSoftTraceSuppressions.trace("knowledgeBasePersistence.safeDouble",
                        new NumberFormatException("non-finite"));
                return null;
            }
            return parsed;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(o));
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException("non-finite");
            }
            return parsed;
        } catch (NumberFormatException e) {
            WebFailSoftTraceSuppressions.trace("knowledgeBasePersistence.safeDouble", e);
            return null;
        }
    }

    private static String safeString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private record ParsedKnowledge(String domain, String entity, List<Kv> attributes) {
    }

    private record Kv(String key, String value) {
    }
}
