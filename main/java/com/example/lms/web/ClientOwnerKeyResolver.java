package com.example.lms.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;




@Component
public class ClientOwnerKeyResolver {

    private static final System.Logger LOG = System.getLogger(ClientOwnerKeyResolver.class.getName());
    private static final String NO_REQUEST_OWNER_KEY = "system:no-request";

    private final HttpServletRequest request;
    private final TrustedProxyPolicy trustedProxyPolicy;

    public ClientOwnerKeyResolver() {
        this(null, new TrustedProxyPolicy(""));
    }

    public ClientOwnerKeyResolver(HttpServletRequest request) {
        this(request, new TrustedProxyPolicy(""));
    }

    @Autowired(required = false)
    public ClientOwnerKeyResolver(HttpServletRequest request, TrustedProxyPolicy trustedProxyPolicy) {
        this.request = request;
        this.trustedProxyPolicy = trustedProxyPolicy == null
                ? new TrustedProxyPolicy("")
                : trustedProxyPolicy;
    }

    /** Compute or retrieve stable ownerKey for current request. */
    public String ownerKey() {
        HttpServletRequest currentRequest = request;
        if (currentRequest == null) {
            return NO_REQUEST_OWNER_KEY;
        }

        // 1) Filter-issued request identity. This keeps the first cookie-less
        // request on the same owner identity that will be persisted in the response.
        Object requestOwner = currentRequest.getAttribute(
                OwnerKeyBootstrapFilter.OWNER_KEY_REQUEST_ATTRIBUTE);
        String requestOwnerKey = requestOwner instanceof String value
                ? OwnerKeyBootstrapFilter.usableOwnerKey(value)
                : null;
        if (requestOwnerKey != null) return requestOwnerKey;

        // 2) ownerKey cookie. Public X-Owner-Key headers are not trusted because
        // browsers and external clients can spoof them.
        String cookieVal = OwnerKeyBootstrapFilter.usableOwnerKey(readCookie(currentRequest, OwnerKeyBootstrapFilter.OWNER_KEY));
        if (cookieVal != null) return cookieVal;

        // 3) gid cookie (compatibility path)
        String gid = usableGid(readCookie(currentRequest, "gid"));
        if (gid != null) return "gid:" + gid;

        // 4) Fallback: IP + UA hash (do not store raw PII)
        String ip = firstForwardedIpOrRemoteAddr(currentRequest);
        String ua = Optional.ofNullable(currentRequest.getHeader("User-Agent")).orElse("");
        if (ua.length() > 120) {
            int end = 120;
            if (Character.isHighSurrogate(ua.charAt(end - 1))
                    && Character.isLowSurrogate(ua.charAt(end))) {
                end--;
            }
            ua = ua.substring(0, end);
        }
        String raw = (ip == null ? "" : ip) + "|" + ua;
        String digest = sha256(raw);
        if (digest != null) return "ipua:" + digest;

        // 5) Random
        return UUID.randomUUID().toString();
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                String v = trimToNull(c.getValue());
                if (v != null) return v;
            }
        }
        return null;
    }

    /** Shared rate-limit identity uses the same trusted-proxy boundary as ownership. */
    public String clientIpHash(HttpServletRequest currentRequest) {
        return sha256(Optional.ofNullable(firstForwardedIpOrRemoteAddr(currentRequest)).orElse("unknown"));
    }

    private String firstForwardedIpOrRemoteAddr(HttpServletRequest request) {
        String remoteAddr = trimToNull(request.getRemoteAddr());
        if (!trustedProxyPolicy.trusts(remoteAddr)) {
            return remoteAddr;
        }
        String forwardedFor = trimToNull(request.getHeader("X-Forwarded-For"));
        if (forwardedFor != null) {
            int separator = forwardedFor.indexOf(',');
            String first = separator >= 0
                    ? forwardedFor.substring(0, separator)
                    : forwardedFor;
            String validated = TrustedProxyPolicy.validatedAddress(first);
            if (validated != null) {
                return validated;
            }
        }
        return remoteAddr;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String usableGid(String gid) {
        if (gid == null) {
            return null;
        }
        try {
            return UUID.fromString(gid).toString();
        } catch (IllegalArgumentException ignored) {
            LOG.log(System.Logger.Level.DEBUG, "Client owner gid cookie rejected");
            return null;
        }
    }

    private String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "Client owner fallback hash unavailable");
            return null;
        }
    }
}
