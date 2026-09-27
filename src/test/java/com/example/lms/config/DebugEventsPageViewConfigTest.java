package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugEventsPageViewConfigTest {

    @Test
    void debugEventsViewRendersClasspathHtmlAndInjectsCsrfMeta() throws Exception {
        ChatUiViewConfig config = new ChatUiViewConfig();
        var view = config.chatUiResourceViewResolver().resolveViewName("debug-events", Locale.KOREA);
        assertNotNull(view, "debug-events must not fall through to an internal circular view");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/debug-events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "csrf-test-token");
        request.setAttribute(CsrfToken.class.getName(), csrf);

        view.render(Map.of("_csrf", csrf), request, response);

        String html = response.getContentAsString();
        assertTrue(response.getStatus() < 400);
        assertTrue(html.contains("<title>Debug Events</title>"));
        assertTrue(html.contains("<meta name=\"_csrf\" content=\"csrf-test-token\">"));
        assertTrue(html.contains("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\">"));
        assertTrue(html.contains("btnTriadicAdjudicate"));
    }
}
