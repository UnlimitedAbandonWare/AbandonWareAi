package com.example.lms.api;

import com.example.lms.web.OwnerKeyBootstrapFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBootstrapControllerTest {
    private static final String OWNER_UUID = "22222222-2222-4222-8222-222222222222";

    @Test
    void bootstrapReportsRemoteAddressAsHashOnly() {
        SessionBootstrapController controller = new SessionBootstrapController();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bootstrap");
        request.setRemoteAddr("203.0.113.42");

        var response = controller.bootstrap(request, new MockHttpServletResponse());
        String body = String.valueOf(response.getBody());

        assertTrue(body.contains("remoteAddrHash"));
        assertTrue(body.contains("remoteAddrLength"));
        assertFalse(body.contains("remoteAddr=203.0.113.42"));
        assertFalse(body.contains("203.0.113.42"));
    }

    @Test
    void ownerKeyPresentOnlyReflectsOwnerKeyCookie() {
        SessionBootstrapController controller = new SessionBootstrapController();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bootstrap");
        request.setCookies(new Cookie("unrelated", "present"));

        var noOwner = controller.bootstrap(request, new MockHttpServletResponse());
        assertEquals(false, noOwner.getBody().get("ownerKeyPresent"));

        request.setCookies(new Cookie(OwnerKeyBootstrapFilter.OWNER_KEY, OWNER_UUID));
        var withOwner = controller.bootstrap(request, new MockHttpServletResponse());
        assertEquals(true, withOwner.getBody().get("ownerKeyPresent"));

        request.setCookies(new Cookie(OwnerKeyBootstrapFilter.OWNER_KEY, "owner-cookie"));
        var unsafeOwner = controller.bootstrap(request, new MockHttpServletResponse());
        assertEquals(false, unsafeOwner.getBody().get("ownerKeyPresent"));
    }
}
