package com.abandonware.ai.guard;

import com.abandonware.ai.service.rag.auth.DomainProfileLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class AnswerSanitizer {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}");
    private static final Pattern PHONE = Pattern.compile("(01[0-9]-?\\d{3,4}-?\\d{4})");

    private final DomainProfileLoader profileLoader;

    @Value("${policy.sanitizer.pii-enabled:true}")
    private boolean piiEnabled;

    @Value("${policy.sanitizer.min-citations:3}")
    private int minCitations;

    @Value("${policy.sanitizer.whitelist-profile:gov+scholar+news}")
    private String whitelistProfile;

    @Value("${policy.banners.zero-break:【주의: 확장 탐색(Zero Break) 모드 적용】}")
    private String zeroBreakBanner;

    public AnswerSanitizer(DomainProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
    }

    public String sanitize(String text) {
        // Backwards-compatible API
        return sanitize(text, List.of(), false);
    }

    public String sanitize(String text, List<String> citations, boolean ruleBreak) {
        if (text == null) return null;
        String t = text;
        if (piiEnabled) {
            t = EMAIL.matcher(t).replaceAll("***@***.***");
            t = PHONE.matcher(t).replaceAll("010-****-****");
        }
        if (ruleBreak) {
            t = zeroBreakBanner + "\n" + t;
        }
        requireMinCitations(citations, minCitations, whitelistProfile);
        return t;
    }

    protected void requireMinCitations(List<String> citations, int min, String profileKey) throws IllegalArgumentException {
        if (min <= 0) return;
        final java.util.Set<String> whitelist = new java.util.HashSet<>(profileLoader.getProfile(profileKey));
        long ok = citations == null ? 0 :
                citations.stream().filter(u -> whitelist.stream().anyMatch(u::contains)).count();
        if (ok < min) {
            throw new IllegalArgumentException("Insufficient authoritative citations: required=" + min + ", ok=" + ok);
        }
    }
}