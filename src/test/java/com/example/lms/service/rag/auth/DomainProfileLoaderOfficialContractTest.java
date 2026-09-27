package com.example.lms.service.rag.auth;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DomainProfileLoaderOfficialContractTest {
    @Test
    void builtInOfficialSeparatesVendorHostsFromCommunityAndSuffixImpostors() {
        DomainProfileLoader loader = new DomainProfileLoader(new DomainWhitelist());
        loader.load();
        assertTrue(loader.isAllowedByProfile("https://openai.com/docs", "official"));
        assertTrue(loader.isAllowedByProfile("https://platform.openai.com/docs", "official"));
        for (String host : List.of("namu.wiki", "blog.naver.com", "example.tistory.com",
                "medium.com", "notopenai.com", "openai.com.example.test")) {
            assertFalse(loader.isAllowedByProfile("https://" + host + "/synthetic", "official"), host);
        }
        assertFalse(loader.isAllowedByProfile("", "official"));
        assertFalse(loader.isAllowedByProfile("http://[invalid", "official"));
    }

    @Test
    void unknownProfileFallsBackToRestrictedOfficialInsteadOfPermissiveWhitelist() {
        DomainWhitelist whitelist = new DomainWhitelist();
        assertTrue(whitelist.isOfficial("https://blog.naver.com/synthetic"));
        DomainProfileLoader loader = new DomainProfileLoader(whitelist);
        loader.load();
        assertTrue(loader.isAllowedByProfile("https://openai.com/docs", "missing-profile"));
        assertFalse(loader.isAllowedByProfile("https://blog.naver.com/synthetic", "missing-profile"));
    }

    @Test
    void genericAllowlistDoesNotPromoteListedCommunityRootsIntoOfficialProfile() {
        DomainWhitelist whitelist = new DomainWhitelist();
        whitelist.setEnableDomainFilter(true);
        whitelist.setDomainAllowlist(List.of("blog.naver.com", "tistory.com", "medium.com"));
        assertTrue(whitelist.isOfficial("https://blog.naver.com/synthetic"));
        DomainProfileLoader loader = new DomainProfileLoader(whitelist);
        loader.load();
        for (String host : whitelist.getDomainAllowlist()) {
            assertFalse(loader.isAllowedByProfile("https://" + host + "/synthetic", "official"), host);
        }
        assertTrue(loader.isAllowedByProfile("https://openai.com/docs", "official"));
    }
}
