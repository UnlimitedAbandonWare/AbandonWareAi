package com.abandonware.ai.planner.dsl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.yaml.snakeyaml.Yaml;

/** Loads YAML plans from classpath: /plans/*.yaml */
public class PlanLoader {
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
            return null;
        }
    }
}