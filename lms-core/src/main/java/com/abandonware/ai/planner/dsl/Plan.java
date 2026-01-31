package com.abandonware.ai.planner.dsl;

import java.util.*;
import java.util.concurrent.TimeUnit;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.planner.dsl.Plan
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.planner.dsl.Plan
role: config
*/
public class Plan {
    public String name;
    public String version;
    public Map<String, Object> params = new HashMap<>();
    public List<String> chain = new ArrayList<>();
    public static Plan of(String name, String version) {
        Plan p = new Plan();
        p.name = name; p.version = version;
        return p;
    }
}