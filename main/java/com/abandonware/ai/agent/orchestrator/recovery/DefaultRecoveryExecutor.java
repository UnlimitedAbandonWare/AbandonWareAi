package com.abandonware.ai.agent.orchestrator.recovery;

import com.abandonware.ai.agent.fallback.NovaFallbackCoordinator;
import com.abandonware.ai.agent.rag.model.Result;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.SseEventPublisher;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import com.example.lms.trace.TraceLogger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DefaultRecoveryExecutor implements RecoveryExecutor {
    private final RecoveryPolicy policy;
    private final NovaFallbackCoordinator fallbackCoordinator;
    private final SseEventPublisher ssePublisher;

    public DefaultRecoveryExecutor() {
        this(RecoveryPolicy.load(), new NovaFallbackCoordinator(), null);
    }

    @Autowired
    public DefaultRecoveryExecutor(RecoveryPolicy policy,
                                   ObjectProvider<NovaFallbackCoordinator> fallbackCoordinator,
                                   ObjectProvider<SseEventPublisher> ssePublisher) {
        this(
                policy,
                fallbackCoordinator == null
                        ? null
                        : fallbackCoordinator.getIfAvailable(NovaFallbackCoordinator::new),
                ssePublisher == null ? null : ssePublisher.getIfAvailable());
    }

    public DefaultRecoveryExecutor(RecoveryPolicy policy, NovaFallbackCoordinator fallbackCoordinator) {
        this(policy, fallbackCoordinator, null);
    }

    DefaultRecoveryExecutor(RecoveryPolicy policy,
                            NovaFallbackCoordinator fallbackCoordinator,
                            SseEventPublisher ssePublisher) {
        this.policy = policy == null ? RecoveryPolicy.load() : policy;
        this.fallbackCoordinator = fallbackCoordinator == null ? new NovaFallbackCoordinator() : fallbackCoordinator;
        this.ssePublisher = ssePublisher;
    }

    public RecoveryAction resolve(FailureClass failureClass) {
        return policy.resolve(failureClass);
    }

    @Override
    public Map<String, Object> apply(RecoveryAction action,
                                     Verdict verdict,
                                     Map<String, Object> state,
                                     ToolContext context) {
        RecoveryAction effective = action == null ? RecoveryAction.ESCALATE : action;
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("recovery.action", effective.name());
        patch.put("recovery.event", "agent.recovery." + effective.name().toLowerCase(Locale.ROOT));

        switch (effective) {
            case BACKOFF -> {
                patch.put("recovery.backoff", true);
                patch.put("recovery.backoff.ms", Math.max(0L, asLong(value(state, "retry.initialMs"), 0L)));
            }
            case DEGRADE -> {
                String currentFlow = String.valueOf(valueOrDefault(state, "flow", "default.v1"));
                patch.put("recovery.nextFlow", policy.degrade(currentFlow));
            }
            case FALLBACK -> {
                String query = String.valueOf(valueOrDefault(state, "query", valueOrDefault(state, "question", "")));
                List<Result> results = coerceResults(value(state, "results"));
                patch.put("fallback.message", fallbackCoordinator.handle(query, results, verdict));
            }
            case ESCALATE -> {
                TraceContext.current().setFlag("recovery.escalated", true);
                patch.put("recovery.escalated", true);
            }
        }
        publishRecoveryEvent(effective, verdict, state, patch);
        return patch;
    }

    private void publishRecoveryEvent(RecoveryAction action,
                                      Verdict verdict,
                                      Map<String, Object> state,
                                      Map<String, Object> patch) {
        String eventType = String.valueOf(patch.get("recovery.event"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("round", valueOrDefault(patch, "recovery.round",
                valueOrDefault(state, "recovery.round", 0)));
        payload.put("action", action.name());
        if (verdict != null) {
            payload.put("decision", verdict.decision().name());
            payload.put("failureClass", verdict.failureClass().name());
            payload.put("confidence", verdict.confidence());
            payload.put("reason", SafeRedactor.traceLabelOrFallback(verdict.reason(), "unknown"));
        }
        Object nextFlow = patch.get("recovery.nextFlow");
        if (nextFlow != null) payload.put("nextFlow", nextFlow);
        if (Boolean.TRUE.equals(patch.get("recovery.escalated"))) {
            payload.put("escalated", true);
        }

        if (ssePublisher != null) {
            try {
                ssePublisher.emit(eventType, payload);
            } catch (RuntimeException ignored) {
                traceSuppressed("sse", "sse.emit", eventType, ignored);
                // Recovery must remain fail-soft if the optional SSE sink is unavailable.
            }
        }
        TraceLogger.emit("agent_recovery", "recovery", payload);
    }

    private static Object value(Map<String, Object> state, String key) {
        return state == null ? null : state.get(key);
    }

    private static Object valueOrDefault(Map<String, Object> state, String key, Object defaultValue) {
        Object val = value(state, key);
        return val == null ? defaultValue : val;
    }

    private static long asLong(Object value, long defaultValue) {
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                traceSuppressed("coerce", "long", value, new NumberFormatException("non-finite"));
                return defaultValue;
            }
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                traceSuppressed("coerce", "long", s, ignored);
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<Result> coerceResults(Object raw) {
        if (raw instanceof List<?> list) {
            List<Result> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Result result) {
                    out.add(result);
                } else if (item instanceof Map<?, ?> map) {
                    out.add(new Result(
                            String.valueOf(mapValue(map, "id", "")),
                            String.valueOf(mapValue(map, "title", "")),
                            String.valueOf(mapValue(map, "snippet", "")),
                            String.valueOf(mapValue(map, "source", "")),
                            asDouble(map.get("score"), 0.0),
                            (int) asLong(map.get("rank"), 0L)));
                }
            }
            return out;
        }
        return List.of();
    }

    private static Object mapValue(Map<?, ?> map, String key, Object defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : value;
    }

    private static double asDouble(Object value, double defaultValue) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                traceSuppressed("coerce", "double", value, new NumberFormatException("non-finite"));
                return defaultValue;
            }
            return parsed;
        }
        if (value instanceof String s) {
            try {
                double parsed = Double.parseDouble(s.trim());
                if (!Double.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite");
                }
                return parsed;
            } catch (NumberFormatException ignored) {
                traceSuppressed("coerce", "double", s, ignored);
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static void traceSuppressed(String family, String stage, Object value, Throwable error) {
        String safeFamily = SafeRedactor.traceLabelOrFallback(family, "unknown");
        String prefix = "agent.recovery." + safeFamily;
        TraceStore.put(prefix + ".suppressed", true);
        TraceStore.put(prefix + ".suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put(prefix + ".suppressed.errorType",
                "coerce".equals(safeFamily) ? "invalid_number" : errorType(error));
        if ("sse".equals(safeFamily)) {
            TraceStore.put(prefix + ".suppressed.eventType",
                    SafeRedactor.traceLabelOrFallback(value, "unknown"));
        } else {
            String raw = value == null ? null : String.valueOf(value);
            TraceStore.put(prefix + ".suppressed.valueHash", SafeRedactor.hashValue(raw));
            TraceStore.put(prefix + ".suppressed.valueLength", raw == null ? 0 : raw.length());
        }
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
