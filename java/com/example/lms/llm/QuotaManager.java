package com.example.lms.llm;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;



/**
 * Tracks usage of language models and enforces simple soft limits on a
 * best-effort basis.  The quotas are maintained per model identifier and
 * encompass the total number of invocations (request count) as well as
 * approximate token consumption for both input and output.  Once a model
 * reaches 80% of its configured capacity within a rolling one-minute
 * window, subsequent requests are transparently degraded to the next
 * available tier (see {@link #degrade(String)}).
 *
 * <p>This implementation is intentionally lightweight and does not attempt
 * to enforce strict concurrency control.  It is designed to provide
 * reasonable protection against overuse of premium models in the free tier
 * without blocking calling threads.  It should not be treated as a
 * precise rate limiter.</p>
 */
public final class QuotaManager {

    /** Single entry representing usage statistics for a model. */
    private static final class Stats {
        // count of calls within the current rolling window
        volatile int calls;
        // last window start
        volatile Instant windowStart;
    }

    // Global registry of model → Stats
    private static final Map<String, Stats> registry = new ConcurrentHashMap<>();

    // Quota thresholds per model (calls per minute).  These values can be
    // adjusted via environment variables of the form MODELNAME_LIMIT_RPM.
    private static final Map<String, Integer> rpmLimits = new ConcurrentHashMap<>();

    private QuotaManager() {}

    /**
     * Returns the effective model name that should be used given the
     * requested model.  If the current usage for the requested model is
     * below 80% of its RPM quota, the same model is returned and the call
     * is recorded.  Otherwise this method returns the next lower tier and
     * records the call against that tier instead.  If no lower tier is
     * available then the requested model is returned.
     *
     * @param requested the logical model identifier (e.g. "gemini-2.5-pro")
     * @return the model to actually use for this invocation
     */
    public static String getAvailableModel(String requested) {
        String m = requested;
        // initialise stats for this model and its downstream candidates
        Stats s = registry.computeIfAbsent(m, k -> new Stats());
        // Determine limit for this model from env or default
        int limit = rpmLimits.computeIfAbsent(m, k -> getLimitForModel(k));
        // Reset window if one minute elapsed
        Instant now = Instant.now();
        Instant start = s.windowStart;
        if (start == null || Duration.between(start, now).toMinutes() >= 1) {
            s.windowStart = now;
            s.calls = 0;
        }
        // Check usage against limit (80% soft threshold)
        if (limit > 0 && s.calls >= 0.8 * limit) {
            // degrade once and recursively recheck
            String degraded = degrade(m);
            if (!degraded.equals(m)) {
                return getAvailableModel(degraded);
            }
        }
        // Record this call
        s.calls++;
        return m;
    }

    /**
     * Determines the rate limit for a given model.  The environment variable
     * name is constructed by uppercasing the model identifier, replacing
     * non-alphanumeric characters with underscores, appending
     * {@code _LIMIT_RPM} and reading its integer value.  If the variable
     * is unset or invalid then a default value of 60 is used.
     */
    private static int getLimitForModel(String model) {
        String envName = model.toUpperCase().replaceAll("[^A-Z0-9]", "_") + "_LIMIT_RPM";
        // Use system properties instead of environment variables to obtain limits
        String val = System.getProperty(envName);
        if (val == null) return 60;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException ignore) {
            return 60;
        }
    }

    /**
     * Returns the next lower tier for the specified model.  The degrade
     * hierarchy is defined by the {@link ModelMap} ordering: Pro → Flash
     * → Flash-Lite → LargeContext.  Models not in this list are returned
     * unchanged.
     */
    private static String degrade(String model) {
        String pro  = ModelMap.getProModel();
        String flash= ModelMap.getFlashModel();
        String lite = ModelMap.getFlashLiteModel();
        String large= ModelMap.getLargeContextModel();
        if (model.equalsIgnoreCase(pro)) {
            return flash;
        } else if (model.equalsIgnoreCase(flash)) {
            return lite;
        } else if (model.equalsIgnoreCase(lite)) {
            return large;
        }
        return model;
    }
}