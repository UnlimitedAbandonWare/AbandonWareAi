package com.abandonware.ai.agent.integrations.orchestrator.plates;


/**
 * Nine plates gate (MoE-style), selecting a plate id based on signals.
 */
public class NinePlatesGate {
    public String selectPlate(String intent, String risk, String recency){
        // simplistic mapping; replace with calibrated routing
        if ("high".equalsIgnoreCase(risk)) return "AP9_COST_SAVER";
        if ("fresh".equalsIgnoreCase(recency)) return "AP1_AUTH_WEB";
        if ("embed".equalsIgnoreCase(intent)) return "AP3_VEC_DENSE";
        return "AP1_AUTH_WEB";
    }
}