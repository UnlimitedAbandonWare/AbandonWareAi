package com.example.lms.llm;

import com.example.lms.search.TraceStore;

/** The explicitly selected identity is internal request state, never public trace data. */
public final class RequestedModelSelection {
    private static final String KEY = "chat.internal.exactModelSelection";
    private RequestedModelSelection() {}
    public static void begin(String model) {
        begin(model, null);
    }
    public static void begin(String model, String ownerHash) {
        TraceStore.putInternal(KEY, model == null || model.isBlank() ? null : model.trim());
        TraceStore.putInternal(KEY + ".outputLimit", null);
        TraceStore.putInternal(KEY + ".mainDecision", null);
        TraceStore.putInternal(KEY + ".mainRole", null);
        TraceStore.putInternal(KEY + ".owner", ownerHash);
    }
    public static String ownerHash() {
        Object value=TraceStore.get(KEY + ".owner");
        return value instanceof String hash && hash.matches("[0-9a-f]{64}") ? hash : null;
    }
    public static void rememberOutputLimit(String model, int limit) {
        if (matches(model) && limit > 0) TraceStore.putInternal(KEY + ".outputLimit", limit);
    }
    /** A timeout rebuild must retain the bound admitted by the exact model router. */
    public static Integer outputLimit(String model, Integer requested) {
        var decision = mainDecision();
        if (decision != null && model != null && model.equals(decision.selectedKey()) && decision.outputLimit() != null)
            return requested != null && requested > 0 ? Math.min(requested, decision.outputLimit()) : decision.outputLimit();
        Object admitted = matches(model) ? TraceStore.get(KEY + ".outputLimit") : null;
        if (admitted instanceof Integer limit && limit > 0) {
            return requested != null && requested > 0 ? Math.min(requested, limit) : limit;
        }
        return requested;
    }
    public static com.example.lms.llm.gateway.LlmRouteDecision mainDecision() {
        Object value = TraceStore.get(KEY + ".mainDecision");
        return value instanceof com.example.lms.llm.gateway.LlmRouteDecision decision ? decision : null;
    }
    public static void rememberMainDecision(com.example.lms.llm.gateway.LlmRouteDecision decision) {
        var existing = mainDecision();
        if (existing != null && !existing.equals(decision)) throw new IllegalStateException("main_route_already_bound");
        TraceStore.putInternal(KEY + ".mainDecision", decision);
    }
    public static com.example.lms.routing.RoutingProfile.Role mainRole() {
        Object value = TraceStore.get(KEY + ".mainRole");
        return value instanceof com.example.lms.routing.RoutingProfile.Role role ? role : null;
    }
    public static void rememberMainRole(com.example.lms.routing.RoutingProfile.Role role) {
        if (mainDecision() == null) throw new IllegalStateException("main_route_not_bound");
        var previous = mainRole();
        if (previous != null && previous != role) throw new IllegalStateException("main_role_already_bound");
        TraceStore.putInternal(KEY + ".mainRole", role);
    }
    public static boolean matches(String model) {
        Object requested = TraceStore.get(KEY);
        return requested instanceof String id && id.equals(model);
    }
    public static boolean active() {
        return TraceStore.get(KEY) instanceof String id && !id.isBlank();
    }
}
