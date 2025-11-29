package com.example.lms.service.rag.catalog;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.yaml.snakeyaml.Yaml;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Loads organisation definitions from a YAML file into memory.  Each entry
 * defines a canonical name together with a list of aliases, regions and
 * official sites.  The loaded entries are exposed as an immutable list.
 */
@Component
@ConditionalOnProperty(name = "rag.org-catalog.enabled", havingValue = "true", matchIfMissing = true)
public class OrgCatalogLoader {
    private static final Logger log = LoggerFactory.getLogger(OrgCatalogLoader.class);

    private final ResourceLoader resourceLoader;
    @Value("${rag.org-catalog.file:classpath:/catalog/orgs.yml}")
    private String catalogFile;

    @Getter
    private List<OrgEntry> entries = Collections.emptyList();

    public OrgCatalogLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        try {
            Resource resource = resourceLoader.getResource(catalogFile);
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Object obj = yaml.load(is);
                List<OrgEntry> list = new ArrayList<>();
                if (obj instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        if (item instanceof Map<?, ?> m) {
                            OrgEntry e = new OrgEntry();
                            e.id = Objects.toString(m.get("id"), null);
                            e.canonical = Objects.toString(m.get("canonical"), null);
                            Object aliasesObj = m.get("aliases");
                            if (aliasesObj instanceof Iterable<?>) {
                                List<String> aliases = new ArrayList<>();
                                for (Object a : (Iterable<?>) aliasesObj) {
                                    if (a != null) aliases.add(a.toString());
                                }
                                e.aliases = aliases;
                            }
                            Object regionsObj = m.get("regions");
                            if (regionsObj instanceof Iterable<?>) {
                                List<String> regions = new ArrayList<>();
                                for (Object r : (Iterable<?>) regionsObj) {
                                    if (r != null) regions.add(r.toString());
                                }
                                e.regions = regions;
                            }
                            Object sitesObj = m.get("sites");
                            if (sitesObj instanceof Iterable<?>) {
                                List<String> sites = new ArrayList<>();
                                for (Object s : (Iterable<?>) sitesObj) {
                                    if (s != null) sites.add(s.toString());
                                }
                                e.sites = sites;
                            }
                            list.add(e);
                        }
                    }
                }
                this.entries = Collections.unmodifiableList(list);
                log.info("[OrgCatalogLoader] Loaded {} organisation entries from {}", list.size(), catalogFile);
            }
        } catch (Exception e) {
            log.warn("[OrgCatalogLoader] Failed to load catalog {}: {}", catalogFile, e.toString());
            this.entries = Collections.emptyList();
        }
    }

    /**
     * Represents a single organisation definition.  Fields are public for
     * simplicity; users should treat instances as read-only.
     */
    public static class OrgEntry {
        public String id;
        public String canonical;
        public List<String> aliases = List.of();
        public List<String> regions = List.of();
        public List<String> sites = List.of();
    }
}