package com.abandonware.ai.agent.orchestrator;

import com.abandonware.ai.agent.tool.ToolScope;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;



/**
 * Represents a declarative flow.  A flow consists of a name, an optional
 * list of required scopes and an ordered list of steps.  Flows are loaded
 * from YAML resources and executed by the {@link Orchestrator}.
 */
public class FlowDefinition {
    private String flow;
    @JsonProperty("require_scopes")
    private List<String> requireScopesRaw;
    private List<Step> steps;

    public FlowDefinition() {
        // no-arg constructor for YAML binding
    }

    public String getFlow() {
        return flow;
    }

    public void setFlow(String flow) {
        this.flow = flow;
    }

    public List<ToolScope> getRequireScopes() {
        if (requireScopesRaw == null || requireScopesRaw.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolScope> scopes = new ArrayList<>();
        for (String s : requireScopesRaw) {
            for (ToolScope scope : ToolScope.values()) {
                if (scope.value().equalsIgnoreCase(s)) {
                    scopes.add(scope);
                    break;
                }
            }
        }
        return scopes;
    }

    public void setRequire_scopes(List<String> raw) {
        this.requireScopesRaw = raw;
    }

    public List<Step> getSteps() {
        return steps == null ? Collections.emptyList() : steps;
    }

    public void setSteps(List<Step> steps) {
        this.steps = steps;
    }
}