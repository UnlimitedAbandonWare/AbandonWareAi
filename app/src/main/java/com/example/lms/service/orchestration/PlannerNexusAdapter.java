package com.example.lms.service.orchestration;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.orchestration.PlannerNexusAdapter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.orchestration.PlannerNexusAdapter
role: config
*/
public class PlannerNexusAdapter {

    private final PlannerNexus nexus;

    @Autowired
    public PlannerNexusAdapter(PlannerNexus nexus) {
        this.nexus = nexus;
    }

    public String resolvePlanId(String requested) {
        if (requested == null || requested.isBlank()) return "safe_autorun.v1";
        return requested.contains(".") ? requested : requested + ".v1";
    }
}