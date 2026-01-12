package com.example.lms.service.rag.pre;

import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Boundary preprocessor for web-search queries.
 *
 * <p>Purpose: prevent accidental leakage of PII/sensitive narrative fragments into external web providers.
 * This runs at the highest precedence inside {@link CompositeQueryContextPreprocessor}.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SensitiveBoundaryPreprocessor implements MetaAwareQueryContextPreprocessor {

    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.%-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?82\\s*-?)?0?1\\d[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}");

    @Value("${privacy.boundary.mask-web-query:true}")
    private boolean enabled;

    @Value("${privacy.boundary.max-query-length:220}")
    private int maxLen;

    @Override
    public String enrich(String q) {
        return enrich(q, Map.of());
    }

    @Override
    public String enrich(String q, Map<String, Object> meta) {
        if (!enabled || q == null) return q;

        // 목적 기반 적용: 기본은 web 쿼리로 가정하되,
        // purpose가 명시적으로 non-web이면 건드리지 않습니다.
        String purpose = (meta == null) ? "" : String.valueOf(meta.getOrDefault("purpose", ""));
        if (purpose != null && !purpose.isBlank()) {
            String p = purpose.toLowerCase(Locale.ROOT);
            boolean isWeb = p.equals("web_search") || p.equals("web") || p.contains("web");
            if (!isWeb) return q;
        }

        GuardContext g = GuardContextHolder.get();
        boolean on = g != null && (g.isSensitiveTopic() || g.planBool("privacy.boundary.mask-web-query", true));
        if (!on) return q;

        String s = q;
        s = EMAIL.matcher(s).replaceAll("[EMAIL]");
        s = PHONE.matcher(s).replaceAll("[PHONE]");

        if (maxLen > 0 && s.length() > maxLen) {
            s = s.substring(0, maxLen);
        }
        String out = s.trim();

        // Optional hint for downstream logging/trace.
        try {
            if (meta != null) {
                meta.put("privacy.masked", true);
                if (g != null && g.isSensitiveTopic()) meta.put("privacy.sensitive", true);
            }
        } catch (Throwable ignore) {
            // ignore
        }

        return out;
    }
}
