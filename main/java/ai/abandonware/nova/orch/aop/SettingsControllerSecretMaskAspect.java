package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Masks sensitive configuration values returned by {@code /api/settings}.
 *
 * <p>
 * Why:
 * - Returning raw settings as Key-Value JSON can leak API keys to browsers/crawlers.
 * - This matches OWASP API security guidance: avoid excessive data exposure.
 *
 * <p>
 * This aspect is intentionally conservative: it masks values when the key looks sensitive
 * (apiKey/token/secret/password...) and optionally strips such keys from write payloads.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SettingsControllerSecretMaskAspect {

    private final Environment env;

    public SettingsControllerSecretMaskAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.api.SettingsController.getAllSettings(..))")
    public Object aroundGetAllSettings(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();
        if (!enabled()) {
            return ret;
        }
        if (!(ret instanceof ResponseEntity<?> re)) {
            return ret;
        }

        Object body = re.getBody();
        if (!(body instanceof Map<?, ?> m)) {
            return ret;
        }

        int masked = 0;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = (e.getKey() == null) ? "" : String.valueOf(e.getKey());
            String v = (e.getValue() == null) ? null : String.valueOf(e.getValue());

            if (isSensitiveKey(k) || looksLikeSecret(v)) {
                out.put(k, maskValue(v));
                masked++;
            } else {
                out.put(k, v);
            }
        }

        try {
            TraceStore.put("security.settings.masked", true);
            TraceStore.put("security.settings.masked.count", masked);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("settingsSecretMask.getTrace", ignore);
        }

        return ResponseEntity.status(re.getStatusCode()).headers(re.getHeaders()).body(out);
    }

    @Around("execution(* com.example.lms.api.SettingsController.saveAllSettings(..))")
    public Object aroundSaveAllSettings(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled()) {
            return pjp.proceed();
        }
        boolean allowSecretUpdate = env.getProperty("nova.security.settings.allowSecretUpdate", Boolean.class, false);
        Object[] args = pjp.getArgs();
        if (allowSecretUpdate || args == null || args.length == 0 || !(args[0] instanceof Map<?, ?> in)) {
            return pjp.proceed();
        }

        Map<String, String> filtered = new LinkedHashMap<>();
        int stripped = 0;
        for (Map.Entry<?, ?> e : in.entrySet()) {
            String k = (e.getKey() == null) ? "" : String.valueOf(e.getKey());
            String v = (e.getValue() == null) ? null : String.valueOf(e.getValue());
            if (isSensitiveKey(k)) {
                stripped++;
                continue;
            }
            filtered.put(k, v);
        }

        if (stripped > 0) {
            try {
                TraceStore.put("security.settings.write.strippedSecrets", true);
                TraceStore.put("security.settings.write.strippedSecrets.count", stripped);
            } catch (Throwable ignore) {
                WebFailSoftTraceSuppressions.trace("settingsSecretMask.saveTrace", ignore);
            }
            // preserve behavior but avoid persisting secrets from the browser.
            return pjp.proceed(new Object[]{filtered});
        }

        return pjp.proceed();
    }

    private boolean enabled() {
        return env.getProperty("nova.security.settings.mask.enabled", Boolean.class, true);
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.isBlank()) {
            return false;
        }
        return k.contains("apikey")
                || k.contains("api_key")
                || k.contains("api-key")
                || k.endsWith(".key")
                || k.contains("secret")
                || k.contains("token")
                || k.contains("password")
                || k.contains("access_key")
                || k.contains("access-key")
                || k.contains("accesskey")
                || k.contains("private_key")
                || k.contains("private-key")
                || k.contains("privatekey")
                || k.contains("bearer")
                || k.contains("gemini")
                || k.contains("openai")
                || k.contains("jammini");
    }

    private static boolean looksLikeSecret(String v) {
        if (v == null) {
            return false;
        }
        String t = v.trim();
        if (t.isBlank()) {
            return false;
        }
        // Heuristic: long opaque strings are likely secrets.
        if (t.length() >= 28 && t.matches("[A-Za-z0-9_\\-\\.]{28,}")) {
            return true;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return t.startsWith("AIza")
                || t.startsWith("sk-")
                || lower.startsWith("sb_secret_")
                || lower.startsWith("sb_publishable_")
                || t.startsWith("Bearer ")
                || hasUriUserInfo(lower)
                || (lower.contains("-----begin ") && lower.contains("private key-----"));
    }

    private static boolean hasUriUserInfo(String value) {
        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) {
            return false;
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = value.length();
        int slash = value.indexOf('/', authorityStart);
        int query = value.indexOf('?', authorityStart);
        int fragment = value.indexOf('#', authorityStart);
        if (slash >= 0) {
            authorityEnd = Math.min(authorityEnd, slash);
        }
        if (query >= 0) {
            authorityEnd = Math.min(authorityEnd, query);
        }
        if (fragment >= 0) {
            authorityEnd = Math.min(authorityEnd, fragment);
        }
        int colon = value.indexOf(':', authorityStart);
        int at = value.indexOf('@', authorityStart);
        return colon > authorityStart && colon < at && at < authorityEnd;
    }

    private static String maskValue(String v) {
        if (v == null || v.isBlank()) {
            return "***";
        }
        String redacted = SafeRedactor.redact(v);
        if (redacted == null || redacted.isBlank() || "***".equals(redacted)) {
            return "***";
        }
        // Keep a tiny suffix to aid debugging without exposing full secret.
        String t = redacted.trim();
        if (t.length() <= 8) {
            return "***";
        }
        return "***" + t.substring(t.length() - 4);
    }
}
