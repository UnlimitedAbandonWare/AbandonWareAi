package com.example.lms.service.knowledge.seed;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.domain.knowledge.EntityAttribute;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.VectorStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Seeder for the Azure free tier knowledge base.  When enabled this runner
 * loads a JSON resource containing a curated list of Azure services that
 * are available for 12 months at no cost.  Each entry is normalized
 * into the domain knowledge repository and simultaneously enqueued for
 * vector indexing.  The seeder executes only when explicitly enabled via
 * a configuration property and gated by the presence of a <code>.desktop_ok</code>
 * file containing the string "DESKTOP_OK".  No external web requests are
 * performed.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AzureFree12mSeeder implements ApplicationRunner {

    private final DomainKnowledgeRepository repo;
    private final VectorStoreService vectorStore;
    private final ObjectMapper mapper;

    @Value("${seed.azure.free12m.enabled:false}")
    private boolean enabled;

    @Value("${seed.azure.free12m.resource:classpath:knowledge/azure/free_12m_ko.json}")
    private String resourcePath;

    @Value("${seed.azure.free12m.session-id:__KB_AZURE__}")
    private String sessionId;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            log.debug("[AzureFree12mSeeder] disabled via property");
            return;
        }
        if (!desktopOk()) {
            log.debug("[AzureFree12mSeeder] desktop gate not satisfied; skipping");
            return;
        }
        // Load JSON resource
        Resource res = new PathMatchingResourcePatternResolver().getResource(resourcePath);
        if (!res.exists()) {
            log.warn("[AzureFree12mSeeder] resource not found: {}", resourcePath);
            return;
        }
        JsonNode root = mapper.readTree(res.getInputStream());
        JsonNode items = root != null ? root.get("items") : null;
        if (items == null || !items.isArray()) {
            log.warn("[AzureFree12mSeeder] items array missing or malformed in {}", resourcePath);
            return;
        }
        int count = 0;
        for (JsonNode item : items) {
            if (item == null || !item.isObject()) continue;
            upsertDomainKnowledge(item);
            enqueueVectorSnippet(item);
            count++;
        }
        // flush batched segments
        vectorStore.flush();
        log.info("[AzureFree12mSeeder] seeded {} items", count);
    }

    private boolean desktopOk() {
        try {
            Path p = Path.of(".desktop_ok");
            if (!Files.exists(p)) return false;
            String content = Files.readString(p).trim();
            return "DESKTOP_OK".equals(content);
        } catch (Exception e) {
            return false;
        }
    }

    private void upsertDomainKnowledge(JsonNode item) {
        try {
            String domain = text(item, "domain");
            if (domain == null) domain = "AZURE";
            String entityType = text(item, "category");
            if (entityType == null) entityType = "SERVICE";
            String entityName = text(item, "serviceName");
            if (entityName == null) return;
            // Look up existing entity
            Optional<DomainKnowledge> maybe = repo.findByDomainAndEntityNameIgnoreCase(domain, entityName);
            DomainKnowledge dk = maybe.orElseGet(DomainKnowledge::new);
            dk.setDomain(domain);
            dk.setEntityType(entityType);
            dk.setEntityName(entityName);
            // Remove previous attributes and rebuild
            List<EntityAttribute> attrs = dk.getAttributes();
            if (attrs == null) {
                attrs = new ArrayList<>();
                dk.setAttributes(attrs);
            } else {
                attrs.clear();
            }
            // Add attributes from item
            addAttr(attrs, dk, "tiers", joinArray(item.get("tiers")));
            addAttr(attrs, dk, "quota", quotaToString(item.get("quota")));
            addAttr(attrs, dk, "redundancy", text(item, "redundancy"));
            addAttr(attrs, dk, "accessTier", text(item, "accessTier"));
            addAttr(attrs, dk, "ops", opsToString(item.get("ops")));
            addAttr(attrs, dk, "notes", text(item, "notes"));
            addAttr(attrs, dk, "keywords", joinArray(item.get("keywords")));
            // persist
            repo.save(dk);
        } catch (Exception e) {
            log.warn("[AzureFree12mSeeder] domain knowledge upsert failed: {}", e.toString());
        }
    }

    private void addAttr(List<EntityAttribute> attrs, DomainKnowledge owner, String key, String value) {
        if (value == null || value.isBlank()) return;
        EntityAttribute attr = new EntityAttribute();
        attr.setOwner(owner);
        attr.setAttributeKey(key);
        attr.setAttributeValue(value);
        attrs.add(attr);
    }

    private void enqueueVectorSnippet(JsonNode item) {
        String serviceName = text(item, "serviceName");
        if (serviceName == null) return;
        String snippet = buildSnippet(item);
        Map<String, Object> meta = new HashMap<>();
        meta.put("domain", "azure");
        meta.put("doc", "free_12m");
        meta.put("service", serviceName);
        meta.put("lang", "ko");
        vectorStore.enqueue(sessionId, snippet, meta);
    }

    private String buildSnippet(JsonNode item) {
        StringBuilder sb = new StringBuilder();
        String name = text(item, "serviceName");
        if (name != null) {
            sb.append(name);
        }
        String tiers = joinArray(item.get("tiers"));
        String quota = quotaToString(item.get("quota"));
        String notes = text(item, "notes");
        String redundancy = text(item, "redundancy");
        String accessTier = text(item, "accessTier");
        String ops = opsToString(item.get("ops"));
        // Build concise description
        if (tiers != null) {
            sb.append("(").append(tiers).append(")");
        }
        if (quota != null) {
            sb.append(": ").append(quota);
        }
        if (redundancy != null) {
            sb.append(" ").append(redundancy);
        }
        if (accessTier != null) {
            sb.append(" ").append(accessTier);
        }
        if (ops != null) {
            sb.append(" ").append(ops);
        }
        if (notes != null) {
            sb.append(". ").append(notes);
        }
        return sb.toString().trim();
    }

    private String text(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            String v = node.get(field).asText();
            return (v != null && !v.isBlank()) ? v : null;
        }
        return null;
    }

    private String joinArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) return null;
        List<String> list = new ArrayList<>();
        for (JsonNode n : arrayNode) {
            String t = n.asText();
            if (t != null && !t.isBlank()) {
                list.add(t);
            }
        }
        return list.isEmpty() ? null : String.join(",", list);
    }

    private String quotaToString(JsonNode quotaNode) {
        if (quotaNode == null || !quotaNode.isObject()) return null;
        String unit = text(quotaNode, "unit");
        String value = text(quotaNode, "value");
        return (value != null ? value : "") + (unit != null ? unit : "");
    }

    private String opsToString(JsonNode opsNode) {
        if (opsNode == null || !opsNode.isObject()) return null;
        String read = text(opsNode, "read");
        String write = text(opsNode, "write");
        List<String> parts = new ArrayList<>();
        if (read != null) {
            parts.add("읽기 " + read);
        }
        if (write != null) {
            parts.add("쓰기 " + write);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }
}