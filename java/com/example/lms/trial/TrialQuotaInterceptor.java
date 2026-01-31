package com.example.lms.trial;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;
import java.util.UUID;




/**
 * Interceptor that enforces the anonymous trial quota for the chat API.  For
 * each incoming request the interceptor determines whether the caller is
 * authenticated; if not, it extracts or creates a trial identifier from a
 * signed cookie and decrements the quota via {@link TrialQuotaService}.  The
 * remaining quota is communicated to the client via the
 * {@code X-Trial-Remaining} response header.  When the quota is exhausted a
 * HTTP 429 response is emitted and downstream handlers are short-circuited.
 */
@Component
public class TrialQuotaInterceptor implements HandlerInterceptor {

    private final TrialProperties props;
    private final TrialTokenService tokenService;
    private final TrialQuotaService quotaService;

    public TrialQuotaInterceptor(TrialProperties props, TrialTokenService tokenService, TrialQuotaService quotaService) {
        this.props = props;
        this.tokenService = tokenService;
        this.quotaService = quotaService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!props.isEnabled()) {
            return true;
        }
        // Bypass quota for authenticated users with any non-anonymous role
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            // Admins and registered users are not limited
            return true;
        }
        // Extract or create a trial identifier from the cookie
        String trialId = null;
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (props.getCookieName().equals(c.getName())) {
                    String token = c.getValue();
                    trialId = tokenService.verify(token);
                    break;
                }
            }
        }
        boolean newId = false;
        if (trialId == null || trialId.isEmpty()) {
            trialId = UUID.randomUUID().toString();
            newId = true;
        }
        TrialQuotaService.Result result = quotaService.consume(trialId);
        // Always set the remaining header
        response.setHeader("X-Trial-Remaining", String.valueOf(result.remaining()));
        if (!result.allowed()) {
            // Exceeded quota: send 429 and JSON body
            response.setStatus(429);
            response.setContentType("application/json");
            try {
                response.getWriter().write("{\"error\":\"trial_exceeded\"}");
            } catch (IOException ignore) {
            }
            return false;
        }
        // Refresh the cookie if new or to extend expiry
        if (newId) {
            String signed = tokenService.sign(trialId);
            Cookie cookie = new Cookie(props.getCookieName(), signed);
            cookie.setHttpOnly(true);
            cookie.setSecure(true);
            cookie.setPath("/");
            // Set maxAge to window length in seconds; cast safe as int as window durations are small
            long secs = props.getWindow().getSeconds();
            cookie.setMaxAge(secs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) secs);
            // The standard Servlet API does not yet expose SameSite, but many containers
            // honour it via a system property.  This could be set at the container
            // level if required.
            response.addCookie(cookie);
        }
        return true;
    }
}