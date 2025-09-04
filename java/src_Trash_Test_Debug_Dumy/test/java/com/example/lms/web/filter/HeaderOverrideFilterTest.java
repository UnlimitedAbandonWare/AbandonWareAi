package com.example.lms.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestHeaderModelOverrideFilter}.  These tests
 * exercise the filter in isolation by manipulating the {@code allow}
 * property via reflection.  When overrides are disabled, any incoming
 * {@code X-Model-Override} header should be stripped.  When overrides are
 * enabled only values from the static whitelist should be allowed.
 */
public class HeaderOverrideFilterTest {

    @Test
    void removesOverrideHeaderWhenNotAllowed() throws Exception {
        RequestHeaderModelOverrideFilter filter = new RequestHeaderModelOverrideFilter();
        // Force allow=false to block overrides.
        ReflectionTestUtils.setField(filter, "allow", false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Model-Override", "gpt-5-mini");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final ServletRequest[] captured = new ServletRequest[1];
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                captured[0] = req;
            }
        };

        filter.doFilter(request, response, chain);
        HttpServletRequest processed = (HttpServletRequest) captured[0];
        assertThat(processed.getHeader("X-Model-Override")).isNull();
    }

    @Test
    void allowsWhitelistedOverrideWhenAllowed() throws Exception {
        RequestHeaderModelOverrideFilter filter = new RequestHeaderModelOverrideFilter();
        // Enable overrides
        ReflectionTestUtils.setField(filter, "allow", true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Model-Override", "gpt-5-mini");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final ServletRequest[] captured = new ServletRequest[1];
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                captured[0] = req;
            }
        };

        filter.doFilter(request, response, chain);
        HttpServletRequest processed = (HttpServletRequest) captured[0];
        assertThat(processed.getHeader("X-Model-Override")).isEqualTo("gpt-5-mini");
    }

    @Test
    void stripsNonWhitelistedOverrideWhenAllowed() throws Exception {
        RequestHeaderModelOverrideFilter filter = new RequestHeaderModelOverrideFilter();
        // Enable overrides
        ReflectionTestUtils.setField(filter, "allow", true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Model-Override", "gpt-unknown");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final ServletRequest[] captured = new ServletRequest[1];
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                captured[0] = req;
            }
        };

        filter.doFilter(request, response, chain);
        HttpServletRequest processed = (HttpServletRequest) captured[0];
        assertThat(processed.getHeader("X-Model-Override")).isNull();
    }
}