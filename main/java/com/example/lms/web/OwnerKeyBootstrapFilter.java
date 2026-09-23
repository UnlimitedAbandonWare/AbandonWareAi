package com.example.lms.web;

import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;




@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class OwnerKeyBootstrapFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(OwnerKeyBootstrapFilter.class);

    public static final String OWNER_KEY = "ownerKey";
    static final String OWNER_KEY_REQUEST_ATTRIBUTE =
            OwnerKeyBootstrapFilter.class.getName() + ".ownerKey";
    private static final int OWNER_TTL_SECONDS = 60 * 60 * 24 * 180; // 180 days

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String ownerKey = null;
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                String existing = OWNER_KEY.equals(c.getName()) ? usableOwnerKey(c.getValue()) : null;
                if (existing != null) {
                    ownerKey = existing;
                    break;
                }
            }
        }

        if (ownerKey == null) {
            ownerKey = UUID.randomUUID().toString();
            log.debug("Assigned new persistent ownerKey cookie");
        }

        req.setAttribute(OWNER_KEY_REQUEST_ATTRIBUTE, ownerKey);
        writeOwnerCookie(res, req, ownerKey);
        chain.doFilter(request, response);
    }

    private static void writeOwnerCookie(HttpServletResponse res, HttpServletRequest req, String value) {
        ResponseCookie cookie = ResponseCookie.from(OWNER_KEY, value)
                .httpOnly(true)
                .secure(isHttpsRequest(req))
                .path("/")
                .maxAge(Duration.ofSeconds(OWNER_TTL_SECONDS))
                .sameSite("Lax")
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static boolean isHttpsRequest(HttpServletRequest request) {
        return request != null && request.isSecure();
    }

    public static String usableOwnerKey(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException ignored) {
            log.debug("Rejected malformed ownerKey cookie");
            return null;
        }
    }
}
