
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
        if (env == null) return;
        boolean propUrlSet = isPropertyDefined("vector.upstash.url");
        boolean propTokenSet = isPropertyDefined("vector.upstash.token");
        boolean envUrlSet = System.getenv("UPSTASH_VECTOR_URL") != null && !System.getenv("UPSTASH_VECTOR_URL").isBlank();
        boolean envTokenSet = System.getenv("UPSTASH_VECTOR_TOKEN") != null && !System.getenv("UPSTASH_VECTOR_TOKEN").isBlank();
        if ((propUrlSet && envUrlSet) || (propTokenSet && envTokenSet)) {
            String msg = "Invalid configuration: Do not set both ENV and properties for Upstash (URL/TOKEN). " +
                    "Unset either UPSTASH_VECTOR_URL|UPSTASH_VECTOR_TOKEN or vector.upstash.url|token.";
            log.error(msg);
            throw new IllegalStateException(msg);
        }
    }

    private boolean isPropertyDefined(String key) {
        MutablePropertySources sources = env.getPropertySources();
        for (PropertySource<?> ps : sources) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                for (String name : eps.getPropertyNames()) {
                    if (key.equals(name)) {
                        Object v = eps.getProperty(name);
                        if (v != null && !v.toString().isBlank()) {
                            // Ignore environment property sources for this check
                            String psName = ps.getName();
                            if (psName != null && psName.toLowerCase().contains("systemenvironment")) continue;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}