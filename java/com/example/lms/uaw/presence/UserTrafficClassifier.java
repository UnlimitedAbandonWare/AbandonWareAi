package com.example.lms.uaw.presence;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Locale;

/**
 * Classifies whether a request should count as "user traffic".
 * Exclude patterns win over include patterns.
 */
@Component
public class UserTrafficClassifier {

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final UserPresenceProperties props;

    public UserTrafficClassifier(UserPresenceProperties props) {
        this.props = props;
    }

    public boolean isUserTraffic(HttpServletRequest req) {
        if (req == null) return false;
        String path = req.getRequestURI();
        if (path == null) return false;

        // HTTP 메서드 기준으로 먼저 필터링한다.
        // 기본 설정은 "변경을 유발하는" 메서드만 사용자 트래픽으로 간주하여,
        // UI의 폴링/상태조회(GET)나 SSE/keepalive가 autolearn 게이트를 영구적으로 막는 문제를 완화한다.
        final String method = req.getMethod();
        if (method != null) {
            final String m = method.toUpperCase(Locale.ROOT);

            if (props.getExcludeMethods() != null) {
                for (String em : props.getExcludeMethods()) {
                    if (em != null && m.equalsIgnoreCase(em)) return false;
                }
            }

            if (props.getIncludeMethods() != null && !props.getIncludeMethods().isEmpty()) {
                boolean allowed = false;
                for (String im : props.getIncludeMethods()) {
                    if (im != null && m.equalsIgnoreCase(im)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) return false;
            }
        }

        for (String p : props.getExcludePaths()) {
            if (matcher.match(p, path)) return false;
        }
        for (String p : props.getIncludePaths()) {
            if (matcher.match(p, path)) return true;
        }
        return false;
    }
}
