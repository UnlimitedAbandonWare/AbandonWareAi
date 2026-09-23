package com.example.lms.uaw.autolearn;

import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Local fail-closed guard for AutoLearn's optional OpenCode Zen free-model route.
 */
@Component
public class OpenCodeFreeQuotaGuard {

    private static final String TRACE_PREFIX = "uaw.autolearn.externalQuota.";
    private static final System.Logger LOG = System.getLogger(OpenCodeFreeQuotaGuard.class.getName());

    private final UawAutolearnProperties props;
    private final AutoLearnRunStateStore store;
    private final Environment env;

    public OpenCodeFreeQuotaGuard(UawAutolearnProperties props, AutoLearnRunStateStore store, Environment env) {
        this.props = props == null ? new UawAutolearnProperties() : props;
        this.store = store == null ? new AutoLearnRunStateStore() : store;
        this.env = env;
    }

    public Decision tryAcquire(String requestedModel, Integer maxTokens, int cycleCallsSoFar) {
        UawAutolearnProperties.ExternalQuota cfg = cfg();
        String routeModel = trimToEmpty(cfg.getRouteModel());
        if (!cfg.isEnabled()) {
            Decision decision = Decision.allowed(false, null, "disabled_by_config", routeModel);
            trace(decision, statusSnapshot(null, requestedModel, maxTokens, cycleCallsSoFar, "disabled_by_config", false));
            return decision;
        }
        if (!equalsIgnoreCase(trimToEmpty(requestedModel), routeModel)) {
            Decision decision = Decision.allowed(false, null, "not_applicable", trimToEmpty(requestedModel));
            trace(decision, statusSnapshot(null, requestedModel, maxTokens, cycleCallsSoFar, "not_applicable", false));
            return decision;
        }

        String day = today(cfg);
        Path statePath = statePath();
        int reserveTokens = reserveTokens(cfg, maxTokens);
        synchronized (this) {
            AutoLearnRunState state = store.load(statePath, day);
            normalizeExternalQuotaDay(state, day);
            Route route = route(routeModel);
            KeyStatus keyStatus = openCodeKeyStatus(route);
            String disabledReason = disabledReason(cfg, route, keyStatus, state, reserveTokens, cycleCallsSoFar);
            if (!disabledReason.isBlank()) {
                state.externalQuotaDisabledReason = disabledReason;
                store.saveStrict(statePath, state);
                Decision decision = Decision.denied(disabledReason, route.model());
                trace(decision, statusSnapshot(state, requestedModel, maxTokens, cycleCallsSoFar, disabledReason, true,
                        route, keyStatus, false));
                return decision;
            }

            state.externalQuotaCallsToday = Math.max(0, state.externalQuotaCallsToday) + 1;
            state.externalQuotaOutputTokensToday = Math.max(0, state.externalQuotaOutputTokensToday) + reserveTokens;
            state.externalQuotaDisabledReason = "";
            if (!store.saveStrict(statePath, state)) {
                releaseReservation(state, new Lease(statePath, day, reserveTokens));
                state.externalQuotaDisabledReason = "quota_state_persist_failed";
                Decision decision = Decision.denied("quota_state_persist_failed", route.model());
                trace(decision, statusSnapshot(state, requestedModel, maxTokens, cycleCallsSoFar,
                        "quota_state_persist_failed", true, route, keyStatus, false));
                return decision;
            }
            Lease lease = new Lease(statePath, day, reserveTokens);
            Decision decision = Decision.allowed(true, lease, "", route.model());
            trace(decision, statusSnapshot(state, requestedModel, maxTokens, cycleCallsSoFar + 1, "", true,
                    route, keyStatus, true));
            return decision;
        }
    }

    public void recordFailure(Lease lease, Throwable failure) {
        if (lease == null) {
            return;
        }
        String day = today(cfg());
        synchronized (this) {
            AutoLearnRunState state = store.load(lease.statePath, day);
            normalizeExternalQuotaDay(state, day);
            if (isPreflightNoCallFailure(failure)) {
                releaseReservation(state, lease);
                state.externalQuotaDisabledReason = "provider_disabled";
                store.saveStrict(lease.statePath, state);
                trace(Decision.denied("provider_disabled", cfg().getFreeModel()),
                        statusSnapshot(state, cfg().getRouteModel(), lease.reservedOutputTokens, 0,
                                "provider_disabled", true));
                return;
            }
            if (isQuotaFailure(failure)) {
                int seconds = Math.max(1, cfg().getRateLimitCooldownSeconds());
                long until = System.currentTimeMillis() + seconds * 1000L;
                state.externalQuotaRateLimitUntilEpochMs = Math.max(state.externalQuotaRateLimitUntilEpochMs, until);
                state.externalQuotaDisabledReason = "rate_limit_cooldown";
                store.saveStrict(lease.statePath, state);
                trace(Decision.denied("rate_limit_cooldown", cfg().getFreeModel()),
                        statusSnapshot(state, cfg().getRouteModel(), lease.reservedOutputTokens, 0,
                                "rate_limit_cooldown", true));
            }
        }
    }

    public Map<String, Object> status() {
        String day = today(cfg());
        synchronized (this) {
            AutoLearnRunState state = store.load(statePath(), day);
            normalizeExternalQuotaDay(state, day);
            String requested = prop("uaw.autolearn.strict.model", "");
            if (requested.isBlank()) {
                requested = cfg().getRouteModel();
            }
            Route route = route(cfg().getRouteModel());
            int requestedMaxTokens = intProp("uaw.autolearn.strict.max-tokens", 1024);
            int reserveTokens = reserveTokens(cfg(), requestedMaxTokens);
            KeyStatus keyStatus = openCodeKeyStatus(route);
            String disabledReason = disabledReason(cfg(), route, keyStatus, state, reserveTokens, 0);
            boolean applies = equalsIgnoreCase(trimToEmpty(requested), trimToEmpty(cfg().getRouteModel()));
            if (!applies) {
                disabledReason = "not_applicable";
            }
            return statusSnapshot(state, requested, requestedMaxTokens, 0, disabledReason, applies,
                    route, keyStatus, false);
        }
    }

    private String disabledReason(UawAutolearnProperties.ExternalQuota cfg,
                                  Route route,
                                  KeyStatus keyStatus,
                                  AutoLearnRunState state,
                                  int reserveTokens,
                                  int cycleCallsSoFar) {
        if (!route.enabled()) {
            return "route_disabled";
        }
        if (route.baseUrl().isBlank() || route.model().isBlank()) {
            return "missing_route_config";
        }
        String providerHost = trimToEmpty(cfg.getProviderHost()).toLowerCase(Locale.ROOT);
        String endpointHost = trimToEmpty(route.endpointHost()).toLowerCase(Locale.ROOT);
        if (!hostMatches(endpointHost, providerHost)) {
            return "provider_host_mismatch";
        }
        if (cfg.isStrictFreeModelOnly()
                && !equalsIgnoreCase(route.model(), trimToEmpty(cfg.getFreeModel()))) {
            return "non_free_model";
        }
        if (keyStatus != null && !keyStatus.disabledReason().isBlank()) {
            return keyStatus.disabledReason();
        }
        long now = System.currentTimeMillis();
        if (state.externalQuotaRateLimitUntilEpochMs > now) {
            return "rate_limit_cooldown";
        }
        int maxCycle = cfg.getMaxCallsPerCycle();
        if (maxCycle > 0 && cycleCallsSoFar >= maxCycle) {
            return "cycle_call_limit";
        }
        int maxDailyCalls = cfg.getMaxCallsPerDay();
        if (maxDailyCalls > 0 && state.externalQuotaCallsToday >= maxDailyCalls) {
            return "daily_call_limit";
        }
        int maxDailyTokens = cfg.getMaxOutputTokensPerDay();
        if (maxDailyTokens > 0
                && state.externalQuotaOutputTokensToday + Math.max(0, reserveTokens) > maxDailyTokens) {
            return "daily_token_limit";
        }
        return "";
    }

    private Map<String, Object> statusSnapshot(AutoLearnRunState state,
                                               String requestedModel,
                                               Integer requestedMaxTokens,
                                               int cycleCallsSoFar,
                                               String disabledReason,
                                               boolean applies) {
        return statusSnapshot(state, requestedModel, requestedMaxTokens, cycleCallsSoFar, disabledReason, applies,
                route(cfg().getRouteModel()), openCodeKeyStatus(route(cfg().getRouteModel())), false);
    }

    private Map<String, Object> statusSnapshot(AutoLearnRunState state,
                                               String requestedModel,
                                               Integer requestedMaxTokens,
                                               int cycleCallsSoFar,
                                               String disabledReason,
                                               boolean applies,
                                               Route route,
                                               KeyStatus keyStatus,
                                               boolean consumed) {
        UawAutolearnProperties.ExternalQuota cfg = cfg();
        int calls = state == null ? 0 : Math.max(0, state.externalQuotaCallsToday);
        int tokens = state == null ? 0 : Math.max(0, state.externalQuotaOutputTokensToday);
        int maxCalls = Math.max(0, cfg.getMaxCallsPerDay());
        int maxTokens = Math.max(0, cfg.getMaxOutputTokensPerDay());
        int nextReservationTokens = reserveTokens(cfg, requestedMaxTokens);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("applies", applies);
        out.put("routeModel", trimToEmpty(cfg.getRouteModel()));
        out.put("requestedModel", trimToEmpty(requestedModel));
        out.put("routeEnabled", route.enabled());
        out.put("hasKey", keyStatus != null && keyStatus.hasKey());
        out.put("consumed", consumed);
        out.put("nextReservationTokens", nextReservationTokens);
        out.put("providerHost", trimToEmpty(cfg.getProviderHost()));
        out.put("endpointHost", route.endpointHost());
        out.put("model", route.model());
        out.put("freeModel", trimToEmpty(cfg.getFreeModel()));
        out.put("resetZone", safeZoneId(cfg).getId());
        out.put("privacyMode", trimToEmpty(cfg.getPrivacyMode()));
        out.put("canonicalTrainingPolicy", trimToEmpty(cfg.getCanonicalTrainingPolicy()));
        out.put("routeConfigured", route.enabled() && !route.baseUrl().isBlank() && !route.model().isBlank());
        out.put("endpointFamily", ExternalFreeModelPolicy.endpointFamily(route.model()));
        out.put("modelPolicy", ExternalFreeModelPolicy.evaluate(cfg, route.model(), route.model(), "opencode").modelPolicy());
        out.put("strictFreeModelOnly", cfg.isStrictFreeModelOnly());
        out.put("maxCallsPerDay", maxCalls);
        out.put("callsToday", calls);
        out.put("remainingCalls", maxCalls <= 0 ? Integer.MAX_VALUE : Math.max(0, maxCalls - calls));
        out.put("maxOutputTokensPerDay", maxTokens);
        out.put("outputTokensReservedToday", tokens);
        out.put("remainingOutputTokens", maxTokens <= 0 ? Integer.MAX_VALUE : Math.max(0, maxTokens - tokens));
        out.put("maxOutputTokensPerCall", Math.max(0, cfg.getMaxOutputTokensPerCall()));
        out.put("maxCallsPerCycle", Math.max(0, cfg.getMaxCallsPerCycle()));
        out.put("cycleCallsSoFar", Math.max(0, cycleCallsSoFar));
        out.put("requestedMaxTokens", Math.max(0, requestedMaxTokens == null ? 0 : requestedMaxTokens));
        out.put("rateLimitCooldownUntilEpochMs",
                state == null ? 0L : Math.max(0L, state.externalQuotaRateLimitUntilEpochMs));
        out.put("disabledReason", blankToNull(disabledReason));
        out.put("allowed", statusAllowed(applies, disabledReason));
        return out;
    }

    private void trace(Decision decision, Map<String, Object> status) {
        try {
            TraceStore.put(TRACE_PREFIX + "allowed", decision.allowed());
            TraceStore.put(TRACE_PREFIX + "applies", status.get("applies"));
            TraceStore.put(TRACE_PREFIX + "routeEnabled", status.get("routeEnabled"));
            TraceStore.put(TRACE_PREFIX + "hasKey", status.get("hasKey"));
            TraceStore.put(TRACE_PREFIX + "consumed", decision.consumed());
            TraceStore.put(TRACE_PREFIX + "nextReservationTokens", status.get("nextReservationTokens"));
            TraceStore.put(TRACE_PREFIX + "disabledReason", safeDisabledReason(status.get("disabledReason")));
            TraceStore.put("uaw.autolearn.externalQuota", redactedStatus(status));
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG,
                    "OpenCode external quota trace skipped stage=trace_store errorType="
                            + SafeRedactor.traceLabelOrFallback(errorType(t), "unknown"));
        }
    }

    private static String errorType(Throwable t) {
        return t == null ? null : t.getClass().getSimpleName();
    }

    private static Map<String, Object> redactedStatus(Map<String, Object> status) {
        Map<String, Object> out = new LinkedHashMap<>(status == null ? Map.of() : status);
        replaceWithHashAndLength(out, "routeModel");
        replaceWithHashAndLength(out, "requestedModel");
        replaceWithHashAndLength(out, "model");
        replaceWithHashAndLength(out, "freeModel");
        if (out.containsKey("disabledReason")) {
            out.put("disabledReason", safeDisabledReason(out.get("disabledReason")));
        }
        return out;
    }

    private static Object safeDisabledReason(Object value) {
        if (value == null) {
            return null;
        }
        String reason = String.valueOf(value).trim();
        if (reason.isBlank()) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(reason, "unknown");
    }

    private static void replaceWithHashAndLength(Map<String, Object> out, String key) {
        Object raw = out.remove(key);
        String value = raw == null ? "" : String.valueOf(raw);
        out.put(key + "Hash", value.isBlank() ? "" : SafeRedactor.hashValue(value));
        out.put(key + "Length", value.length());
    }

    private Route route(String routeModel) {
        String key = routeKey(routeModel);
        String prefix = "llmrouter.models." + key + ".";
        String baseUrl = trimToEmpty(prop(prefix + "base-url", ""));
        String model = trimToEmpty(prop(prefix + "name", ""));
        boolean enabled = boolProp(prefix + "enabled", true);
        return new Route(baseUrl, model, LocalLlmGatewaySecurity.endpointHost(baseUrl), enabled);
    }

    private String routeKey(String routeModel) {
        String model = trimToEmpty(routeModel);
        String prefix = "llmrouter.";
        return model.toLowerCase(Locale.ROOT).startsWith(prefix) ? model.substring(prefix.length()) : model;
    }

    private void normalizeExternalQuotaDay(AutoLearnRunState state, String day) {
        if (state.externalQuotaDay == null || !state.externalQuotaDay.equals(day)) {
            state.externalQuotaDay = day;
            state.externalQuotaCallsToday = 0;
            state.externalQuotaOutputTokensToday = 0;
        }
        if (state.externalQuotaDisabledReason == null) {
            state.externalQuotaDisabledReason = "";
        }
    }

    private void releaseReservation(AutoLearnRunState state, Lease lease) {
        state.externalQuotaCallsToday = Math.max(0, state.externalQuotaCallsToday - 1);
        state.externalQuotaOutputTokensToday = Math.max(0,
                state.externalQuotaOutputTokensToday - Math.max(0, lease.reservedOutputTokens));
    }

    private Path statePath() {
        UawAutolearnProperties.Budget budget = props.getBudget();
        String configured = budget == null ? "" : trimToEmpty(budget.getStatePath());
        return Path.of(configured.isBlank() ? "data/uaw/autolearn_state.json" : configured);
    }

    private UawAutolearnProperties.ExternalQuota cfg() {
        UawAutolearnProperties.ExternalQuota cfg = props.getExternalQuota();
        return cfg == null ? new UawAutolearnProperties.ExternalQuota() : cfg;
    }

    private String prop(String key, String defaultValue) {
        return env == null ? defaultValue : env.getProperty(key, defaultValue);
    }

    private boolean boolProp(String key, boolean defaultValue) {
        String value = prop(key, null);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private int intProp(String key, int defaultValue) {
        String value = prop(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            TraceStore.put(TRACE_PREFIX + "suppressed.intProp",
                    SafeRedactor.traceLabelOrFallback(key, "unknown"));
            return defaultValue;
        }
    }

    private KeyStatus openCodeKeyStatus(Route route) {
        if (route == null || !hostMatches(route.endpointHost(), cfg().getProviderHost())) {
            return new KeyStatus(false, "");
        }
        if (env == null) {
            return new KeyStatus(false, "missing_opencode_api_key");
        }
        try {
            String key = new KeyResolver(env).resolveOpenCodeApiKeyStrict();
            return new KeyStatus(key != null && !key.isBlank(), key == null ? "missing_opencode_api_key" : "");
        } catch (IllegalStateException e) {
            TraceStore.put(TRACE_PREFIX + "suppressed.keyStatus", "opencode_api_key_conflict");
            return new KeyStatus(false, "opencode_api_key_conflict");
        }
    }

    private static int reserveTokens(UawAutolearnProperties.ExternalQuota cfg, Integer maxTokens) {
        int requested = Math.max(1, maxTokens == null ? 1 : maxTokens);
        int perCallCap = cfg == null ? 0 : Math.max(0, cfg.getMaxOutputTokensPerCall());
        return perCallCap <= 0 ? requested : Math.min(requested, perCallCap);
    }

    private static boolean hostMatches(String endpointHost, String providerHost) {
        if (trimToEmpty(providerHost).isBlank()) {
            return true;
        }
        return ExternalFreeModelPolicy.hostMatches(endpointHost, providerHost);
    }

    private static String today(UawAutolearnProperties.ExternalQuota cfg) {
        return LocalDate.now(safeZoneId(cfg)).toString();
    }

    private static ZoneId safeZoneId(UawAutolearnProperties.ExternalQuota cfg) {
        String zone = trimToEmpty(cfg == null ? null : cfg.getResetZone());
        if (zone.isBlank()) {
            zone = "UTC";
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException ignored) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][uaw][quota] invalid reset zone; using UTC");
            return ZoneId.of("UTC");
        }
    }

    private static boolean isQuotaFailure(Throwable failure) {
        String text = throwableText(failure);
        return text.contains("429")
                || text.contains("rate limit")
                || text.contains("rate_limit")
                || text.contains("quota")
                || text.contains("too many requests");
    }

    private static boolean isPreflightNoCallFailure(Throwable failure) {
        String text = throwableText(failure);
        return text.contains("missing opencode_api_key")
                || text.contains("missing_provider_api_key")
                || text.contains("route_disabled")
                || text.contains("missing_route_config");
    }

    private static String throwableText(Throwable failure) {
        if (failure == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cur = failure;
        for (int i = 0; i < 8 && cur != null; i++) {
            sb.append(' ').append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append(' ').append(cur.getMessage());
            }
            cur = cur.getCause();
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return trimToEmpty(a).equalsIgnoreCase(trimToEmpty(b));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Object blankToNull(String value) {
        String v = trimToEmpty(value);
        return v.isBlank() ? null : v;
    }

    private static boolean statusAllowed(boolean applies, String disabledReason) {
        String reason = trimToEmpty(disabledReason);
        return reason.isBlank() || !applies || "disabled_by_config".equals(reason) || "not_applicable".equals(reason);
    }

    private record Route(String baseUrl, String model, String endpointHost, boolean enabled) {
    }

    private record KeyStatus(boolean hasKey, String disabledReason) {
    }

    public record Lease(Path statePath, String day, int reservedOutputTokens) {
    }

    public record Decision(boolean allowed, boolean consumed, Lease lease, String disabledReason, String model) {
        static Decision allowed(boolean consumed, Lease lease, String disabledReason, String model) {
            return new Decision(true, consumed, lease, trimToEmpty(disabledReason), trimToEmpty(model));
        }

        static Decision denied(String disabledReason, String model) {
            return new Decision(false, false, null, trimToEmpty(disabledReason), trimToEmpty(model));
        }
    }
}
