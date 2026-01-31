package com.example.lms.flow;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;



/**
 * FlowJoiner facade that harmonizes three coexisting implementations:
 *  - router.joiner.FlowJoiner        (router)
 *  - com.example.lms.flow.FlowJoiner (lms)
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
 *   - For LMS impl, 'sequence' falls back to router's sequence because
 *     LMS FlowJoiner focuses on async fallback() API rather than discrete sequencing.
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
                case APP -> callSequence("com.abandonware.ai.addons.flow.FlowJoiner", hasRetrieve, hasConsent);
                case ROUTER -> callSequence("router.joiner.FlowJoiner", hasRetrieve, hasConsent);
                case LMS -> {
                    // LMS variant doesn't expose sequence(); use router sequence for policy parity
                    yield callSequence("router.joiner.FlowJoiner", hasRetrieve, hasConsent);
                }
            };
        } catch (Throwable t) {
            // Last-resort default path
            return defaultSequence(hasRetrieve, hasConsent);
        }
    }

    private static String[] callSequence(String fqcn, boolean hasRetrieve, boolean hasConsent) throws Exception {
        Class<?> clazz = Class.forName(fqcn);
        Constructor<?> ctor = clazz.getDeclaredConstructor(boolean.class, boolean.class);
        Object inst = ctor.newInstance(hasRetrieve, hasConsent);
        Method m = clazz.getMethod("sequence");
        Object ret = m.invoke(inst);
        if (ret instanceof String[] arr) return arr;
        throw new IllegalStateException("sequence() didn't return String[] for " + fqcn);
    }

    private static String[] defaultSequence(boolean hasRetrieve, boolean hasConsent) {
        if (hasRetrieve && hasConsent) return new String[]{"plan","retrieve","critic_coverage","synth","send_to_kakao"};
        if (hasRetrieve) return new String[]{"plan","retrieve","critic_coverage","synth","send_outbox"};
        if (hasConsent) return new String[]{"plan","retrieve_fallback","critic_coverage","synth","send_to_kakao"};
        return new String[]{"plan","retrieve_fallback","critic_coverage","synth","send_outbox"};
    }

    public static <T> T withFallback(Supplier<T> primary, Supplier<T> fallback, double threshold) {
        Impl impl = detectImpl();
        if (impl == Impl.APP) {
            // Delegate to APP's FlowJoiner.withFallback when available
            try {
                Class<?> clazz = Class.forName("com.abandonware.ai.addons.flow.FlowJoiner");
                Constructor<?> ctor = clazz.getDeclaredConstructor(boolean.class, boolean.class);
                Object inst = ctor.newInstance(false, false);
                Method m = clazz.getMethod("withFallback", Supplier.class, Supplier.class, double.class);
                @SuppressWarnings("unchecked")
                T val = (T) m.invoke(inst, primary, fallback, threshold);
                return val;
            } catch (Throwable ignore) {
                // Fall through to generic try/catch fallback
            }
        }
        try {
            return Objects.requireNonNull(primary.get());
        } catch (Throwable t) {
            return fallback.get();
        }
    }
}