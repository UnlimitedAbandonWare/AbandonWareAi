package com.example.lms.dto;

import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Map;

/** Identity of a successful provider response; requested/configured models are never evidence. */
public record GenerationObservation(String observedProvider, String observedModel, String routeId,
        Integer fallbackCount, String fallbackReason, String observedReason) {
    public static GenerationObservation capture(ChatResponse response, String provider, String route,
            int fallbacks, String reason) {
        String model = response == null || response.metadata() == null ? null : response.metadata().modelName();
        model = model != null && model.matches("[A-Za-z0-9][A-Za-z0-9._:/+@-]{0,199}") ? model : null;
        provider = label(provider);
        route = label(route);
        return new GenerationObservation(provider, model, route, Math.max(0, fallbacks), label(reason),
                model == null ? "response_model_missing" : provider == null ? "response_provider_missing" : null);
    }
    private static String label(Object value) {
        return value instanceof String s && s.matches("[A-Za-z0-9_.:-]{1,80}") ? s : null;
    }
    public static GenerationObservation current() {
        Object value = TraceStore.get("llm.call.observation");
        return value instanceof GenerationObservation observed ? observed
                : new GenerationObservation(null, null, null, null, null, "response_not_observed");
    }
    public void store() { TraceStore.putInternal("llm.call.observation", this); }
    public void publish() {
        TraceStore.put("observedProvider", observedProvider);
        TraceStore.put("observedModel", observedModel);
        TraceStore.put("routeId", routeId);
        TraceStore.put("fallbackCount", fallbackCount);
        TraceStore.put("fallbackReason", fallbackReason);
        TraceStore.put("observedReason", observedReason);
    }
    public static GenerationObservation from(Map<String, Object> trace) {
        Object model = trace.get("observedModel");
        String observed = model instanceof String s && s.matches("[A-Za-z0-9][A-Za-z0-9._:/+@-]{0,199}") ? s : null;
        return new GenerationObservation(label(trace.get("observedProvider")), observed,
                label(trace.get("routeId")), trace.get("fallbackCount") instanceof Number n ? n.intValue() : null,
                label(trace.get("fallbackReason")), label(trace.get("observedReason")));
    }
}
