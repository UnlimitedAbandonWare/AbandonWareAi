package com.example.lms.service.rag.auth;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.detector.RiskBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainWhitelistTest {

    @Test
    void allowlistMatchesExactHostOrSubdomainButNotSuffixCollision() {
        DomainWhitelist whitelist = new DomainWhitelist();
        whitelist.setEnableDomainFilter(true);
        whitelist.setDomainAllowlist(List.of("openai.com"));

        assertTrue(whitelist.isOfficial("https://openai.com/docs"));
        assertTrue(whitelist.isOfficial("https://platform.openai.com/docs"));
        assertFalse(whitelist.isOfficial("https://notopenai.com/phish"));
    }

    @ParameterizedTest
    @CsvSource({
            "HIGH, openai.com, true, false, true, 3.0",
            "NORMAL, openai.com, true, false, true, 2.0",
            "LOW, openai.com, true, false, true, 2.0",
            "HIGH, namu.wiki, true, true, false, -2.0",
            "NORMAL, namu.wiki, true, true, true, 2.5",
            "LOW, namu.wiki, true, true, true, 4.0",
            "HIGH, unknown.example.test, false, false, true, 0.0",
            "NORMAL, unknown.example.test, false, false, true, 0.0",
            "LOW, unknown.example.test, false, false, true, 0.0",
            "HIGH, unconfigured-ban-candidate.example.test, false, false, true, 0.0",
            "NORMAL, unconfigured-ban-candidate.example.test, false, false, true, 0.0",
            "LOW, unconfigured-ban-candidate.example.test, false, false, true, 0.0"
    })
    void currentRiskBandPredicatesKeepOfficialCommunityAllowedAndUnconfiguredBanOutcomesDistinct(
            RiskBand band, String host, boolean official, boolean community, boolean allowed, double score) {
        DomainWhitelist whitelist = new DomainWhitelist();
        whitelist.setEnableDomainFilter(true);
        whitelist.setDomainAllowlist(List.of("openai.com"));
        String url = "https://" + host + "/synthetic-domain-policy";

        assertEquals(official, whitelist.isOfficial(url));
        assertEquals(community, whitelist.isCommunity(host));
        assertEquals(allowed, whitelist.isAllowed(url, band));
        assertEquals(score, whitelist.getDomainScore(url, band));
        // No actual banned-host policy is configured by this fixture.
        assertFalse(whitelist.isBanned(host));
    }

    @Test
    void invalidUrlHostParseLeavesRedactedTraceBreadcrumb() {
        DomainWhitelist whitelist = new DomainWhitelist();

        TraceStore.clear();
        String host = whitelist.extractHost("http://[raw-domain-secret");

        assertNull(host);
        assertTrue((Boolean) TraceStore.get("web.domainWhitelist.extractHost.failed"));
        assertTrue(TraceStore.get("web.domainWhitelist.extractHost.errorType") instanceof String);
        assertFalse(TraceStore.getAll().toString().contains("raw-domain-secret"));
    }
}
