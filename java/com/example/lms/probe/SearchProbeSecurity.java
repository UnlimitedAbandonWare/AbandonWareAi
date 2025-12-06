package com.example.lms.probe;

import com.example.lms.service.rag.auth.DomainWhitelist;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;


import lombok.RequiredArgsConstructor;     // ✅ 추가


@Component
@RequiredArgsConstructor                 // ✅ 추가: final 필드 주입용
public class SearchProbeSecurity {

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
        if (adminToken == null || adminToken.isBlank()) return true;
        return adminToken.equals(token);
    }

    public boolean isOfficial(String url) {
        try {
            // ✅ 인스턴스 메서드 호출
            return url != null && domainWhitelist.isOfficial(url);
        } catch (Throwable ignore) {
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
            return false;
        }
    }
}