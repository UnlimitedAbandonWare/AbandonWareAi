package com.abandonware.ai.agent.orchestrator;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.orchestrator.Step
 * Role: config
 * Feature Flags: sse
 * Dependencies: com.fasterxml.jackson.annotation.JsonProperty
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.agent.orchestrator.Step
role: config
flags: [sse]
*/
public class Step {
    private NodeType type;
    private String uses;
    private Map<String, Object> args;

    public Step() {
        // default constructor for YAML binding
    }

    @JsonProperty("type")
    public NodeType getType() {
        return type;
    }

    public void setType(NodeType type) {
        this.type = type;
    }


    // Allow YAML synonyms like PLANNER/AGENT/SYNTHESIZER by accepting a String 'type'
    @com.fasterxml.jackson.annotation.JsonProperty("type")
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
                // Unknown types are ignored at runtime
                this.type = null;
        }
    }


    @JsonProperty("uses")
    public String getUses() {
        return uses;
    }

    public void setUses(String uses) {
        this.uses = uses;
    }

    

    // Support 'ref' as an alias for 'uses' in YAML
    @com.fasterxml.jackson.annotation.JsonProperty("ref")
    public void setRef(String ref) {
        this.uses = ref;
    }

    @JsonProperty("args")
    public Map<String, Object> getArgs() {
        return args == null ? Collections.emptyMap() : args;
    }

    public void setArgs(Map<String, Object> args) {
        if (args == null) {
            this.args = null;
        } else {
            this.args = new HashMap<>(args);
        }
    }


// Support 'params' as an alias for 'args' in YAML
@com.fasterxml.jackson.annotation.JsonProperty("params")
public void setParams(java.util.Map<String,Object> params) {
    setArgs(params);

    // --- Flow DSL extensions ---
    private String when;
    private boolean parallel;
    private Retry retry;

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


    // --- Flow DSL extensions ---
    private String when;
    private boolean parallel;
    private Retry retry;

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