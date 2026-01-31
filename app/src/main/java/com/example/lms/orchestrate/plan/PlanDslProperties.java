package com.example.lms.orchestrate.plan;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("plan")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.orchestrate.plan.PlanDslProperties
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.orchestrate.plan.PlanDslProperties
role: config
*/
public class PlanDslProperties {
    private String active;
    private String dir = "plans";

    public String getActive() { return active; }
    public void setActive(String active) { this.active = active; }
    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
}