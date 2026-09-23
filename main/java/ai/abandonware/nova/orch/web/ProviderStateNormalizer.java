package ai.abandonware.nova.orch.web;

import java.util.Locale;

public final class ProviderStateNormalizer {

    private ProviderStateNormalizer() {
    }

    public static String state(Object explicit, boolean skipped, boolean cacheOnly) {
        if (cacheOnly) {
            return "cache_only";
        }
        String value = canonical(explicit);
        if (!value.isBlank()) {
            return value;
        }
        return skipped ? "skipped" : "ok";
    }

    public static String summary(Object... states) {
        if (states != null) {
            for (Object state : states) {
                if ("cache_only".equals(stateLabel(state))) {
                    return "cache_only";
                }
            }
        }
        return allSkipped(states) ? "skipped" : "ok";
    }

    public static boolean allSkipped(Object... states) {
        if (states == null || states.length == 0) {
            return false;
        }
        boolean observed = false;
        for (Object state : states) {
            String label = stateLabel(state);
            if (label.isBlank()) {
                continue;
            }
            observed = true;
            if (!"skipped".equals(label)) {
                return false;
            }
        }
        return observed;
    }

    private static String stateLabel(Object state) {
        String value = canonical(state);
        if (!value.isBlank()) {
            return value;
        }
        String raw = state == null ? "" : String.valueOf(state).trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return raw.matches("ok|skipped|cache_only") ? raw : "";
    }

    private static String canonical(Object explicit) {
        if (explicit == null) {
            return "";
        }
        String raw = String.valueOf(explicit).trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (raw.isBlank()) {
            return "";
        }
        if ("cache_only".equals(raw) || "cacheonly".equals(raw) || raw.contains("cache_only")) {
            return "cache_only";
        }
        if ("ok".equals(raw) || "success".equals(raw) || "live".equals(raw)) {
            return "ok";
        }
        if ("skipped".equals(raw)
                || raw.contains("disabled")
                || raw.contains("missing")
                || raw.contains("rate")
                || raw.contains("limit")
                || raw.contains("timeout")
                || raw.contains("breaker")
                || raw.contains("open")
                || raw.contains("cancel")
                || raw.contains("error")
                || raw.contains("fail")) {
            return "skipped";
        }
        return "";
    }
}
