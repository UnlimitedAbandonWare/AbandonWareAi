package com.example.lms.api;

import com.example.lms.service.SettingsService;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One exposure contract for public preference projection and its AOP boundary. */
public final class SettingsExposurePolicy {
    public enum Exposure { PUBLIC_PREFERENCE, SECRET_REFERENCE, FORBIDDEN_SECRET_VALUE }
    private static final Set<String> PUBLIC_KEYS = Set.of(
            SettingsService.KEY_TEMPERATURE, SettingsService.KEY_TOP_P,
            SettingsService.KEY_FREQUENCY_PENALTY, SettingsService.KEY_PRESENCE_PENALTY,
            SettingsService.KEY_OPENAI_MODEL, SettingsService.KEY_FINE_TUNED_MODEL,
            SettingsService.KEY_EXECUTION_MODE,
            "chat.defaults.useWebSearch", "chat.ragAnswerPolicy");
    private SettingsExposurePolicy() {}
    public static Set<String> publicKeys() { return PUBLIC_KEYS; }
    public static Exposure classify(String key) {
        if (PUBLIC_KEYS.contains(key == null ? "" : key)) return Exposure.PUBLIC_PREFERENCE;
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (k.contains("apikey") || k.contains("api_key") || k.contains("api-key")
                || k.endsWith(".key") || k.contains("secret") || k.contains("token")
                || k.contains("password") || k.contains("access_key") || k.contains("access-key")
                || k.contains("accesskey") || k.contains("private_key") || k.contains("private-key")
                || k.contains("privatekey") || k.contains("bearer"))
            return Exposure.SECRET_REFERENCE;
        return Exposure.FORBIDDEN_SECRET_VALUE;
    }
    public static boolean isCredentialValue(String value) {
        if (value == null || value.isBlank()) return false;
        String text = value.trim(), lower = text.toLowerCase(Locale.ROOT);
        return text.startsWith("AIza") || text.startsWith("sk-") || text.startsWith("gsk_")
                || text.startsWith("pcsk_") || lower.startsWith("sb_secret_")
                || lower.startsWith("sb_publishable_") || lower.startsWith("bearer ")
                || text.startsWith("rt_") || text.matches("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                || hasUriUserInfo(lower)
                || (lower.contains("-----begin ") && lower.contains("private key-----"));
    }
    public static boolean shouldMask(String key, String value) {
        if (classify(key) == Exposure.SECRET_REFERENCE || isCredentialValue(value)) return true;
        // Keep unknown opaque values protected; model IDs are explicit public preferences.
        return classify(key) != Exposure.PUBLIC_PREFERENCE && value != null
                && value.trim().matches("[A-Za-z0-9_\\-.]{28,}");
    }
    public static boolean hasForbiddenWrite(Map<?, ?> values) {
        if (values == null) return false;
        return values.entrySet().stream().anyMatch(e ->
                classify(e.getKey() == null ? null : String.valueOf(e.getKey())) == Exposure.SECRET_REFERENCE
                || isCredentialValue(e.getValue() == null ? null : String.valueOf(e.getValue())));
    }
    private static boolean hasUriUserInfo(String value) {
        int scheme = value.indexOf("://");
        if (scheme <= 0) return false;
        int start = scheme + 3, end = value.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int found = value.indexOf(delimiter, start);
            if (found >= 0) end = Math.min(end, found);
        }
        int colon = value.indexOf(':', start), at = value.indexOf('@', start);
        return colon > start && colon < at && at < end;
    }
}
