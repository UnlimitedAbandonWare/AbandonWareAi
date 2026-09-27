package com.abandonware.ai.planner.dsl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** Loads YAML plans from classpath: /plans/*.yaml */
public class PlanLoader {
    private static final Logger log = LoggerFactory.getLogger(PlanLoader.class);

    private final ClassLoader cl = Thread.currentThread().getContextClassLoader();
    public Plan load(String name) {
        try(InputStream in = cl.getResourceAsStream("plans/" + name)) {
            if (in == null) return null;
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Plan p = Plan.of((String)map.getOrDefault("name", name), (String)map.getOrDefault("version","1"));
            p.params.putAll((Map)map.getOrDefault("params", new HashMap<>()));
            p.chain.addAll((List<String>)map.getOrDefault("chain", new ArrayList<>()));
            return p;
        } catch(Exception e) {
            logFailSoft("load", e);
            return null;
        }
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isDebugEnabled()) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.debug("[AWX][planner][dsl-loader] failSoft stage={} errorType={}", stage, errorType);
        }
    }
}
