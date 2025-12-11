package com.abandonwareai.planner;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.planner.PlanLoader
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.planner.PlanLoader
role: config
*/
public class PlanLoader {
    public Plan load(String planName) { return new Plan(planName); }

    public static class Plan { public final String name; public Plan(String n){this.name=n;} }

}