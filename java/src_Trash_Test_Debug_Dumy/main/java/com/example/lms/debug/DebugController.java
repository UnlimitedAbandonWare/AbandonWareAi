package com.example.lms.debug;

import com.example.lms.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    @Autowired(required = false)
    ApplicationContext ctx;

    @Autowired(required = false)
    CacheManager cacheManager;

    @Autowired(required = false)
    SettingsService settings;

    @GetMapping(path="/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> health() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("timestamp", Instant.now().toString());
        m.put("jvm", System.getProperty("java.version"));
        m.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        return m;
    }

    @GetMapping(path="/checks", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> checks() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());

        // Cache managers
        try {
            Map<String, CacheManager> cms = ctx.getBeansOfType(CacheManager.class);
            List<Map<String,String>> list = new ArrayList<>();
            for (Map.Entry<String,CacheManager> e : cms.entrySet()) {
                Map<String,String> row = new LinkedHashMap<>();
                row.put("bean", e.getKey());
                row.put("class", e.getValue().getClass().getName());
                list.add(row);
            }
            m.put("cacheManagers", list);
            m.put("cacheManagerCount", list.size());
        } catch (Throwable t) {
            m.put("cacheManagers", List.of());
        }

        // LangChain4j version probe
        m.put("langchain4j", VersionProbe.langchain4j());

        // Brave key existence (boolean only; never return the key)
        boolean hasKey = false;
        String key = null;
        if (settings != null) {
            try { key = settings.get("search.brave.api-key"); } catch (Throwable ignored) {}
        }
        hasKey = StringUtils.hasText(key);
        m.put("braveApiKeyPresentInSettings", hasKey);

        return m;
    }
}
