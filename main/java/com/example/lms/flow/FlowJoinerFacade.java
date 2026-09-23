package com.example.lms.flow;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;



/**
 * FlowJoiner facade that harmonizes routing-compatible implementations:
 *  - built-in router-compatible sequence
 *  - com.abandonware.ai.addons.flow.FlowJoiner (app)
 *
 * Selection:
 *   - System property: -Dflow.joiner.impl=router|app|lms
 *   - Environment var: FLOW_JOINER_IMPL=router|app|lms
 * Default: router
 *
 * API:
 *   - sequence(boolean hasRetrieve, boolean hasConsent): String[]
 *   - withFallback(Supplier<T> primary, Supplier<T> fallback, double threshold): T
 *
 * Notes:
 *   - Legacy "lms" selection falls back to router-compatible policy parity.
 */
public final class FlowJoinerFacade {

    private FlowJoinerFacade() {}

    public enum Impl { ROUTER, APP, LMS }

    private static Impl detectImpl() {
        String val = Optional.ofNullable(System.getProperty("flow.joiner.impl"))
                .or(() -> Optional.ofNullable(System.getenv("FLOW_JOINER_IMPL")))
                .orElse("router");
        val = val.toLowerCase(Locale.ROOT).trim();
        return switch (val) {
            case "app" -> Impl.APP;
            case "lms" -> Impl.LMS;
            default -> Impl.ROUTER;
        };
    }

    public static String[] sequence(boolean hasRetrieve, boolean hasConsent) {
        Impl impl = detectImpl();
        try {
            return switch (impl) {
                case APP -> defaultSequence(hasRetrieve, hasConsent);
                case ROUTER -> defaultSequence(hasRetrieve, hasConsent);
                case LMS -> {
                    // LMS variant doesn't expose sequence(); use router-compatible policy parity.
                    yield defaultSequence(hasRetrieve, hasConsent);
                }
            };
        } catch (Throwable t) {
            traceSuppressed("flowJoiner.sequence", t);
            // Last-resort default path
            return defaultSequence(hasRetrieve, hasConsent);
        }
    }

    private static String[] defaultSequence(boolean hasRetrieve, boolean hasConsent) {
        if (hasRetrieve && hasConsent) return new String[]{"plan","retrieve","critic_coverage","synth","send_message"};
        if (hasRetrieve) return new String[]{"plan","retrieve","critic_coverage","synth","send_outbox"};
        if (hasConsent) return new String[]{"plan","retrieve_fallback","critic_coverage","synth","send_message"};
        return new String[]{"plan","retrieve_fallback","critic_coverage","synth","send_outbox"};
    }

    public static <T> T withFallback(Supplier<T> primary, Supplier<T> fallback, double threshold) {
        Impl impl = detectImpl();
        if (impl == Impl.APP) {
            return new com.abandonware.ai.addons.flow.FlowJoiner().withFallback(primary, fallback, threshold);
        }
        try {
            return Objects.requireNonNull(primary.get());
        } catch (Throwable t) {
            traceSuppressed("flowJoiner.primary", t);
            return fallback.get();
        }
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("flow.joiner.suppressed." + safeStage, true);
    }
}
