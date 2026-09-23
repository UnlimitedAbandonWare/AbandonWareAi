package com.example.lms.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SuspiciousPathBlockFilterTest {

    @Test
    void blocksContainerScanBeforeOwnerKeyCanBeIssued() throws Exception {
        SuspiciousPathBlockFilter scannerFilter = enabledFilter();
        OwnerKeyBootstrapFilter ownerKeyFilter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/containers/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean ownerKeyReached = new AtomicBoolean(false);

        FilterChain downstream = (req, res) -> {
            ownerKeyReached.set(true);
            ownerKeyFilter.doFilter(req, res, (ignoredReq, ignoredRes) -> { });
        };

        scannerFilter.doFilter(request, response, downstream);

        assertEquals(404, response.getStatus());
        assertFalse(ownerKeyReached.get());
        assertNull(response.getHeader("Set-Cookie"));
    }

    @Test
    void blocksPhpInputQueryScan() throws Exception {
        SuspiciousPathBlockFilter scannerFilter = enabledFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/hello.world");
        request.setQueryString("x=php://input");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);

        scannerFilter.doFilter(request, response, (req, res) -> reached.set(true));

        assertEquals(404, response.getStatus());
        assertFalse(reached.get());
    }

    @Test
    void blocksMixedCaseGitProbeUnderTurkishDefaultLocale() throws Exception {
        SuspiciousPathBlockFilter scannerFilter = enabledFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/.GIT/config");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);

        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            scannerFilter.doFilter(request, response, (req, res) -> reached.set(true));
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals(404, response.getStatus());
        assertFalse(reached.get());
    }

    private static SuspiciousPathBlockFilter enabledFilter() {
        SuspiciousPathBlockFilter filter = new SuspiciousPathBlockFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        return filter;
    }
}
