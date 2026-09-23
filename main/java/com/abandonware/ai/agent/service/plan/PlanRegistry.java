package com.abandonware.ai.agent.service.plan;

import com.example.lms.search.TraceStore;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
public class PlanRegistry {

    private Map<String, RetrievalPlan> plans;

    @Value("${planner.active-id:safe.v1}")
    private String activeId;

    @Value("${planner.plans-path:classpath:plans/{default,brave,rulebreak,zero_break,hypernova}*.yaml}")
    private String plansPath;

    private final PlanLoader loader;

    public PlanRegistry(PlanLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    public void init() {
        this.plans = loader.loadAll(plansPath);
    }

    public RetrievalPlan current(HttpServletRequest req) {
        Map<String, RetrievalPlan> safePlans = plans();
        String hdr = req != null ? req.getHeader("X-Plan") : null;
        String id = (hdr != null && safePlans.containsKey(hdr)) ? hdr : activeId();
        return select(safePlans, id);
    }

    public RetrievalPlan byId(String id) {
        return select(plans(), id == null || id.isBlank() ? activeId() : id);
    }

    public RetrievalPlan current() {
        return select(plans(), activeId());
    }

    private Map<String, RetrievalPlan> plans() {
        return plans == null ? Map.of() : plans;
    }

    private RetrievalPlan select(Map<String, RetrievalPlan> safePlans, String id) {
        RetrievalPlan selected = safePlans.get(id);
        if (selected != null) {
            return selected;
        }
        RetrievalPlan active = safePlans.get(activeId());
        if (active != null) {
            traceFallback("requested_plan_missing");
            return active;
        }
        RetrievalPlan first = safePlans.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (first != null) {
            traceFallback("active_plan_missing");
            return first;
        }
        traceFallback("missing_active_plan");
        RetrievalPlan fallback = new RetrievalPlan();
        fallback.setId(activeId());
        fallback.setDesc("generated fallback plan");
        return fallback;
    }

    private String activeId() {
        return activeId == null || activeId.isBlank() ? "safe.v1" : activeId.trim();
    }

    private static void traceFallback(String reason) {
        TraceStore.put("agent.planRegistry.fallback", true);
        TraceStore.put("agent.planRegistry.fallback.reason", reason);
    }
}
