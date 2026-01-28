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
     * A comma-separated allowlist of model names that may be used when
     * header overrides are enabled.  If the list is empty or undefined,
     * no overrides will be accepted even when {@code allow} is {@code true}.
     * The SpEL expression splits the property on commas and trims
     * whitespace, returning an empty set when undefined.
     */
    @Value("#{'${router.header-override-allowlist:}'.isEmpty() ? T(java.util.Collections).emptySet() : '${router.header-override-allowlist:}'.split(',')}")
    private Set<String> allowlist;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        String override = http.getHeader("X-Model-Override");
        // If the override header is present and not allowed, strip it by wrapping
        // the request and overriding getHeader().
        if (override != null && (!allow || allowlist == null || !allowlist.contains(override))) {
            chain.doFilter(new HttpServletRequestWrapper(http) {
                @Override
                public String getHeader(String name) {
                    // return null for the override header to effectively remove it
                    if ("X-Model-Override".equalsIgnoreCase(name)) {
                        return null;
                    }
                    return super.getHeader(name);
                }
            }, response);
            return;
        }
        chain.doFilter(request, response);
    }
}