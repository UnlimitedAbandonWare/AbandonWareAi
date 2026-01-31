package com.abandonware.ai.agent.service.plan;

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
        String hdr = req != null ? req.getHeader("X-Plan") : null;
        String id = (hdr != null && plans.containsKey(hdr)) ? hdr : activeId;
        return plans.getOrDefault(Objects.requireNonNullElse(hdr, activeId), plans.get(activeId));
    }

    public RetrievalPlan byId(String id) {
        return plans.getOrDefault(id, plans.get(activeId));
    }

    public RetrievalPlan current() {
        return plans.getOrDefault(activeId, plans.values().stream().findFirst().orElse(null));
    }
}