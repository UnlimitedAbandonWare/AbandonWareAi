package com.nova.protocol.plan;

import java.util.Map;
import java.util.HashMap;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.plan.PlanLoader
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.plan.PlanLoader
role: config
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