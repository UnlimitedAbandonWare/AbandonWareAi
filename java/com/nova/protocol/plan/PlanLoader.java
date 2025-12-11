package com.nova.protocol.plan;

import java.util.Map;
import java.util.HashMap;

/**
 * Minimal PlanLoader stub that avoids YAML dependency.
 * Load/parse methods return empty/default structures for build-only use.
 */
public class PlanLoader {
    public PlanLoader() {}

    public Map<String,Object> loadAll(){
        return new HashMap<>();
    }

    public Map<String,Object> get(String id){
        return new HashMap<>();
    }

    public Plan loadFromClasspath(String id){
        Plan p = new Plan();
        p.setId(id == null ? "default" : id);
        return p;
    }

}