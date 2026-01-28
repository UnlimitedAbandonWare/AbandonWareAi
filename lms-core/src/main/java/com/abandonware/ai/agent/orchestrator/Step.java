package com.abandonware.ai.agent.orchestrator;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.orchestrator.Step
 * Role: config
 * Dependencies: com.fasterxml.jackson.annotation.JsonProperty
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.agent.orchestrator.Step
role: config
*/
public class Step {

    @JsonProperty("type")
    private NodeType type;

    @JsonProperty("uses")
    private String uses;

    @JsonProperty("args")
    private Map<String, Object> args = new HashMap<>();

    // --- Flow DSL extensions ---
    @JsonProperty("when")
    private String when;

    @JsonProperty("parallel")
    private boolean parallel;

    @JsonProperty("retry")
    private Retry retry;

    public NodeType getType() { return type; }

    /** Accept either enum name or friendly string and map to NodeType. */
    public void setType(String type) {
        if (type == null) { this.type = null; return; }
        String t = type.trim().toUpperCase();
        switch (t) {
            case "PLAN":
            case "PLANNER":
                this.type = NodeType.PLAN; break;
            case "TOOL":
            case "AGENT":
                this.type = NodeType.TOOL; break;
            case "CRITIC":
                this.type = NodeType.CRITIC; break;
            case "SYNTH":
            case "SYNTHESIZER":
                this.type = NodeType.SYNTH; break;
            default:
                this.type = null; // Unknown types are ignored at runtime
        }
    }

    public String getUses() { return uses; }
    public void setUses(String uses) { this.uses = uses; }

    public Map<String, Object> getArgs() { return args; }
    public void setArgs(Map<String, Object> args) { this.args = (args != null ? args : new HashMap<>()); }

    public String getWhen(){ return when; }
    public void setWhen(String when){ this.when = when; }

    public boolean isParallel(){ return parallel; }
    public void setParallel(boolean parallel){ this.parallel = parallel; }

    public Retry getRetry(){ return retry; }
    public void setRetry(Retry retry){ this.retry = retry; }

    public static class Retry {
        @JsonProperty("maxAttempts")
        private int maxAttempts = 1;
        @JsonProperty("initialMs")
        private long initialMs = 0;
        @JsonProperty("mode")
        private String mode = "FIXED"; // FIXED | EXP

        public int getMaxAttempts(){ return maxAttempts; }
        public void setMaxAttempts(int v){ this.maxAttempts = v; }
        public long getInitialMs(){ return initialMs; }
        public void setInitialMs(long v){ this.initialMs = v; }
        public String getMode(){ return mode; }
        public void setMode(String v){ this.mode = v; }
    }
}