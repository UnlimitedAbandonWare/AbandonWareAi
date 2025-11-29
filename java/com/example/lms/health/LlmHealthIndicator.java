package com.example.lms.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;



/**
 * Health indicator for the configured LLM provider.  This indicator exposes
 * the current provider, a masked base URL and the model name via the
  * /actuator/health endpoint.  It does not perform a live ping but
 * reflects the configuration status only.  When the provider property is
 * missing the health status is reported as UNKNOWN.
 */
public class LlmHealthIndicator implements HealthIndicator {

    private final Environment env;

    public LlmHealthIndicator(Environment env) {
        this.env = env;
    }

    @Override
    public Health health() {
        String provider = env.getProperty("llm.provider", "");
        String baseUrl  = env.getProperty("llm.base-url", "");
        String model    = env.getProperty("llm.chat-model", "");
        // Mask the base URL to avoid leaking secrets or paths.  Display the
        // scheme and host only when available.  If no host is present
        // fallback to an empty string.
        String masked = maskBaseUrl(baseUrl);
        boolean configured = provider != null && !provider.isBlank();
        Health.Builder builder = configured ? Health.up() : Health.unknown();
        return builder
                .withDetail("provider", provider)
                .withDetail("baseUrl", masked)
                .withDetail("model", model)
                .build();
    }

    private static String maskBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            String host   = uri.getHost();
            if (scheme == null || host == null) {
                return "";
            }
            return scheme + "://" + host;
        } catch (Exception e) {
            return "";
        }
    }
}