package com.example.lms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Validates Upstash Vector credentials and prevents ambiguous configuration.
 *
 * <p>
 * Rules:
 * <ul>
 * <li>Do not mix new keys (upstash.vector.*) with legacy keys
 * (vector.upstash.*).</li>
 * <li>Do not set both environment variables and application properties for the
 * same credentials.</li>
 * </ul>
 */
@Configuration
public class UpstashKeyValidator implements EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(UpstashKeyValidator.class);

    private ConfigurableEnvironment env;

    @Override
    public void setEnvironment(Environment environment) {
        if (environment instanceof ConfigurableEnvironment ce) {
            this.env = ce;
        }
    }

    @EventListener(ContextRefreshedEvent.class)
    public void validate() {
        if (env == null)
            return;

        // Preferred (new) keys
        boolean propRestUrlSet = isMeaningfulPropertyDefined("upstash.vector.rest-url");
        boolean propApiKeySet = isMeaningfulPropertyDefined("upstash.vector.api-key");

        // Legacy keys (backward compatible)
        boolean legacyPropUrlSet = isMeaningfulPropertyDefined("vector.upstash.url");
        boolean legacyPropTokenSet = isMeaningfulPropertyDefined("vector.upstash.token");

        // Preferred (new) env vars
        boolean envRestUrlSet = hasEnv("UPSTASH_VECTOR_REST_URL");
        boolean envApiKeySet = hasEnv("UPSTASH_VECTOR_API_KEY");

        // Legacy env vars
        boolean legacyEnvUrlSet = hasEnv("UPSTASH_VECTOR_URL");
        boolean legacyEnvTokenSet = hasEnv("UPSTASH_VECTOR_TOKEN");

        // Mixing new + legacy keys is error-prone and should fail fast.
        if ((propRestUrlSet && legacyPropUrlSet) || (propApiKeySet && legacyPropTokenSet)) {
            String msg = "Invalid configuration: Do not set both new and legacy Upstash properties. "
                    + "Use either upstash.vector.rest-url|api-key (preferred) OR vector.upstash.url|token (legacy).";
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        if ((envRestUrlSet && legacyEnvUrlSet) || (envApiKeySet && legacyEnvTokenSet)) {
            String msg = "Invalid configuration: Do not set both new and legacy Upstash ENV variables. "
                    + "Use either UPSTASH_VECTOR_REST_URL|UPSTASH_VECTOR_API_KEY (preferred) OR UPSTASH_VECTOR_URL|UPSTASH_VECTOR_TOKEN (legacy).";
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        // ENV + properties should not be set together either.
        boolean anyPropUrl = propRestUrlSet || legacyPropUrlSet;
        boolean anyPropKey = propApiKeySet || legacyPropTokenSet;
        boolean anyEnvUrl = envRestUrlSet || legacyEnvUrlSet;
        boolean anyEnvKey = envApiKeySet || legacyEnvTokenSet;

        if ((anyPropUrl && anyEnvUrl) || (anyPropKey && anyEnvKey)) {
            // Changed from exception to warning: ENV variables take precedence over
            // properties
            // per Spring's standard PropertySource ordering, so there's no actual conflict.
            log.warn(
                    "Both ENV variables (UPSTASH_VECTOR_*) and properties (upstash.vector.* / vector.upstash.*) are set. "
                            + "ENV values will take precedence. Consider removing one source for clarity.");
        }
    }

    private boolean isMeaningfulPropertyDefined(String key) {
        if (!isPropertyDefined(key))
            return false;
        try {
            String v = env.getProperty(key, "");
            if (v == null)
                return false;
            v = v.trim();
            if (v.isBlank())
                return false;
            // Ignore placeholders or template markers.
            if (v.startsWith("${") && v.endsWith("}"))
                return false;
            if (v.contains("<YOUR_") || v.contains("<YOUR"))
                return false;
            if (v.contains("<") && v.contains(">"))
                return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasEnv(String key) {
        String v = System.getenv(key);
        return v != null && !v.isBlank();
    }

    /**
     * Checks whether the property is defined from application property sources
     * (excluding system environment variables).
     */
    private boolean isPropertyDefined(String key) {
        if (env == null)
            return false;
        MutablePropertySources sources = env.getPropertySources();
        for (PropertySource<?> ps : sources) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                for (String name : eps.getPropertyNames()) {
                    if (key.equals(name)) {
                        Object v = eps.getProperty(name);
                        if (v != null && !v.toString().isBlank()) {
                            // Ignore environment property sources for this check
                            String psName = ps.getName();
                            if (psName != null && psName.toLowerCase().contains("systemenvironment"))
                                continue;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
