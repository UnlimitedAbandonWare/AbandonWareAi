package com.example.lms.probe;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.service.rag.auth.DomainWhitelist;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;


import lombok.RequiredArgsConstructor;     // ✅ 추가


@Component
@RequiredArgsConstructor                 // ✅ 추가: final 필드 주입용
public class SearchProbeSecurity {
    private static final Logger log = LoggerFactory.getLogger(SearchProbeSecurity.class);

    private final DomainWhitelist domainWhitelist; // ✅ 추가

    @Value("${probe.admin-token:}")
    private String adminToken;

    @Getter
    @Value("${probe.max-noise-ratio:0.15}")
    private double maxNoiseRatio;

    private static final Set<String> FINANCE_NOISE = Set.of(
            "investing.com", "tradingview.com", "nasdaq.com", "finviz.com"
    );

    public boolean permit(String token) {
        // Security posture: if the token is not configured, the probe is DISABLED.
        // (Do not treat "blank" as "allow all".)
        if (ConfigValueGuards.isMissing(adminToken)) return false;
        return adminToken != null && adminToken.equals(token);
    }

    public boolean isOfficial(String url) {
        try {
            // ✅ 인스턴스 메서드 호출
            return url != null && domainWhitelist.isOfficial(url);
        } catch (Throwable ignore) {
            log.debug("[AWX][probe][security] official check skipped errorType={}",
                    errorType(ignore));
            return false;
        }
    }

    public boolean isFinanceNoise(String url) {
        try {
            if (url == null || url.isBlank()) return false;
            String host = new URI(url).getHost();
            if (host == null) return false;
            String h = host.toLowerCase();
            return FINANCE_NOISE.stream().anyMatch(h::endsWith);
        } catch (Exception e) {
            log.debug("[AWX][probe][security] finance-noise check skipped errorType={}",
                    errorType(e));
            return false;
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof URISyntaxException || failure instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return failure.getClass().getSimpleName();
    }
}
