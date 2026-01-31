package com.example.lms.web;

import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.UUID;




@Component
@Order(1)
public class OwnerKeyBootstrapFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(OwnerKeyBootstrapFilter.class);

    public static final String OWNER_KEY = "ownerKey";
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

        boolean present = false;
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (OWNER_KEY.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    present = true;
                    // Refresh (sliding TTL)
                    Cookie refreshed = new Cookie(OWNER_KEY, c.getValue());
                    refreshed.setHttpOnly(true);
                    refreshed.setPath("/");
                    refreshed.setMaxAge(OWNER_TTL_SECONDS);
                    if (req.isSecure()) refreshed.setSecure(true);
                    res.addCookie(refreshed);
                    // Explicit SameSite
                    res.addHeader("Set-Cookie", String.format(
                            "%s=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=Lax%s",
                            OWNER_KEY, c.getValue(), OWNER_TTL_SECONDS, req.isSecure() ? "; Secure" : ""));
                    break;
                }
            }
        }

        if (!present) {
            String val = UUID.randomUUID().toString();
            Cookie cookie = new Cookie(OWNER_KEY, val);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(OWNER_TTL_SECONDS);
            if (req.isSecure()) cookie.setSecure(true);
            res.addCookie(cookie);
            res.addHeader("Set-Cookie", String.format(
                    "%s=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=Lax%s",
                    OWNER_KEY, val, OWNER_TTL_SECONDS, req.isSecure() ? "; Secure" : ""));
            log.debug("Assigned new persistent ownerKey cookie");
        }

        chain.doFilter(request, response);
    }
}