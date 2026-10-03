package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.api.SettingsExposurePolicy;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
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

            if (SettingsExposurePolicy.shouldMask(k, v)) {
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
        Object[] args = pjp.getArgs();
        if (args != null && args.length > 0 && args[0] instanceof Map<?, ?> in
                && SettingsExposurePolicy.hasForbiddenWrite(in)) {
            try {
                TraceStore.put("security.settings.write.strippedSecrets", false);
                TraceStore.put("security.settings.write.rejectedSecrets", true);
            } catch (Throwable ignore) {
                WebFailSoftTraceSuppressions.trace("settingsSecretMask.saveTrace", ignore);
            }
            return ResponseEntity.badRequest().body(Map.of("code", "SETTINGS_SECRET_VALUE_FORBIDDEN"));
        }
        return pjp.proceed();
    }

    private boolean enabled() {
        return env.getProperty("nova.security.settings.mask.enabled", Boolean.class, true);
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
