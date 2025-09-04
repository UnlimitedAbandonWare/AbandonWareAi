package com.example.lms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Configuration class providing a {@link ForwardedHeaderFilter} bean.  This filter
 * processes X‑Forwarded-* headers from upstream proxies (e.g. protocol and port)
 * so that the application can reconstruct the original request URL correctly.
 * Without this filter the application may log URLs like https://example.com:80
 * when running behind an HTTPS terminator with forwarded headers.
 */
@Configuration
public class ForwardedHeaderFilterConfig {

    /**
     * Register a {@link ForwardedHeaderFilter}.  This bean ensures that Spring Boot
     * respects the X‑Forwarded‑Proto, X‑Forwarded‑Port and related headers
     * forwarded by reverse proxies.  By declaring this bean explicitly we avoid
     * needing to modify application.yml at runtime, which is forbidden under the
     * project policies.
     *
     * @return a new ForwardedHeaderFilter instance
     */
    @Bean
    public ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }
}