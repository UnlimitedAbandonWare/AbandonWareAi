// src/main/java/com/example/lms/web/filter/RequestHeaderModelOverrideFilter.java
package com.example.lms.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * RequestHeaderModelOverrideFilter protects the model routing logic by
 * preventing arbitrary clients from overriding the target LLM model via
 * request headers.  A malicious or misconfigured client could send an
 * {@code X-Model-Override} header to force the system to use a
 * particular model, bypassing the server’s routing heuristics.  This
 * filter inspects the {@code X-Model-Override} header on each incoming
 * request and either removes it or allows it through based on
 * configuration.  By default, overrides are blocked entirely.  If
 * overrides are explicitly allowed (e.g. during testing), only those
 * values specified in {@code router.header-override-allowlist} will be
 * permitted.  All other override attempts are stripped from the
 * request.
 */
@Component
public class RequestHeaderModelOverrideFilter implements Filter {

    /**
     * When true, allows model override headers; when false all overrides are
     * blocked.  Defaults to {@code false}.  The property name and default
     * align with the specification defined in the system architect’s
     * guidelines.  See application configuration for details.
     */
    @Value("${router.allow-header-override:false}")
    private boolean allow;

    /**
     * Static whitelist of allowed model overrides when {@code allow} is true.
     * Only these model names will be permitted via the X‑Model‑Override header.
     */
    private static final Set<String> MODEL_WHITELIST = Set.of("gpt-5-mini", "gpt-5-chat-latest");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        String override = http.getHeader("X-Model-Override");
        // If overrides are disabled entirely, strip any override header.
        if (!allow && override != null) {
            chain.doFilter(new HttpServletRequestWrapper(http) {
                @Override
                public String getHeader(String name) {
                    if ("X-Model-Override".equalsIgnoreCase(name)) {
                        return (null);
                    }
                    return super.getHeader(name);
                }
            }, response);
            return;
        }
        // When overrides are enabled, only allow whitelisted values; strip all others.
        if (override != null && !MODEL_WHITELIST.contains(override)) {
            chain.doFilter(new HttpServletRequestWrapper(http) {
                @Override
                public String getHeader(String name) {
                    if ("X-Model-Override".equalsIgnoreCase(name)) {
                        return (null);
                    }
                    return super.getHeader(name);
                }
            }, response);
            return;
        }
        chain.doFilter(request, response);
    }
}