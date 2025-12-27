package com.example.lms.service.rag.knowledge;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UniversalLoreRegistry
 *
 * <p>A lightweight registry that loads YAML lore catalogs from
 * {@code classpath:catalog/lore/*.yml} and exposes lookup by entity
 * name.  This is used by DynamicRetrievalHandlerChain to inject
 * high-confidence domain knowledge (e.g. game characters, hardware
 * models) before expensive web/vector retrieval kicks in.
 *
 * <p>YAML format:
 * <pre>
 * domain: "Genshin"
 * entries:
 *   - names: ["마키바", "Makiba"]
 *     content: "..."
 * </pre>
 */
@Service
public class UniversalLoreRegistry {
    private static final Logger log = LoggerFactory.getLogger(UniversalLoreRegistry.class);

    private final Map<String, DomainKnowledge> keywordMap = new HashMap<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DomainKnowledge {
        private String domain;
        private List<String> names;
        private String content;
    }

    @Data
    public static class LoreCatalog {
        private String domain;
        private List<DomainKnowledge> entries;
    }

    @PostConstruct
    public void loadAll() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:catalog/lore/*.yml");
            if (resources == null || resources.length == 0) {
                log.info("[LoreRegistry] No lore catalogs found under classpath:catalog/lore/*.yml");
                return;
            }
            Yaml yaml = new Yaml();

            for (Resource resource : resources) {
                try (InputStream inputStream = resource.getInputStream()) {
                    LoreCatalog catalog = yaml.loadAs(inputStream, LoreCatalog.class);
                    if (catalog == null || catalog.getEntries() == null) {
                        log.warn("[LoreRegistry] Empty or invalid lore catalog: {}", resource.getFilename());
                        continue;
                    }
                    String domain = catalog.getDomain();
                    for (DomainKnowledge entry : catalog.getEntries()) {
                        if (entry == null || entry.getNames() == null) {
                            continue;
                        }
                        if (entry.getDomain() == null) {
                            entry.setDomain(domain);
                        }
                        for (String name : entry.getNames()) {
                            if (name == null) continue;
                            String key = name.toLowerCase(Locale.ROOT).trim();
                            if (key.isEmpty()) continue;
                            keywordMap.put(key, entry);
                        }
                    }
                    log.info("[LoreRegistry] Loaded {} lore entries from {}", catalog.getEntries().size(), resource.getFilename());
                } catch (Exception e) {
                    log.warn("[LoreRegistry] Failed to load lore catalog {}", resource.getFilename(), e);
                }
            }
            log.info("[LoreRegistry] Total lore keywords: {}", keywordMap.size());
        } catch (Exception e) {
            log.error("[LoreRegistry] Failed to scan lore catalogs", e);
        }
    }

    /**
     * Look up lore for the given entity names.
     *
     * @param entities list of entity strings (typically from LLMNamedEntityExtractor)
     * @return de-duplicated list of domain knowledge entries
     */
    public List<DomainKnowledge> findLore(List<String> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        List<DomainKnowledge> results = new ArrayList<>();
        Set<DomainKnowledge> dedup = new HashSet<>();
        for (String entity : entities) {
            if (entity == null) continue;
            String key = entity.toLowerCase(Locale.ROOT).trim();
            if (key.isEmpty()) {
                continue;
            }
            DomainKnowledge lore = keywordMap.get(key);
            if (lore != null && dedup.add(lore)) {
                results.add(lore);
            }
        }
        return results;
    }
}