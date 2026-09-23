package com.example.lms.service.rag.auth;

import com.example.lms.domain.enums.RerankSourceCredibility;
import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.search.TraceStore;
import com.example.lms.service.verification.SourceAnalyzerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;

class AuthorityScorerHostIdentityTest {
    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"docs.unrelated.example", "api.docs.unrelated.example", "developer.unrelated.example"})
    void documentationLabelDoesNotEstablishOfficialOwnership(String host) {
        assertEquals(RerankSourceCredibility.UNVERIFIED,
                scorer("", "").getSourceCredibility("https://" + host + "/page"));
    }

    @Test
    void existingVendorGovernmentAndEducationRulesRemainOfficial() {
        AuthorityScorer scorer = scorer("", "");
        for (String host : new String[] {"docs.microsoft.com", "developer.openai.com", "docs.spring.io", "fixture.gov", "fixture.edu"}) {
            assertEquals(RerankSourceCredibility.OFFICIAL, scorer.getSourceCredibility("https://" + host + "/page"));
        }
    }

    @Test
    void explicitOfficialAndUnverifiedOverridesRetainPrecedence() {
        assertEquals(RerankSourceCredibility.OFFICIAL,
                scorer("docs.unrelated.example", "").getSourceCredibility("https://docs.unrelated.example/page"));
        assertEquals(RerankSourceCredibility.UNVERIFIED,
                scorer("", "docs.microsoft.com").getSourceCredibility("https://docs.microsoft.com/page"));
    }

    @Test
    void knownCommunityAndUnverifiedHostsKeepTheirClassification() {
        AuthorityScorer scorer = scorer("", "");
        assertEquals(RerankSourceCredibility.COMMUNITY, scorer.getSourceCredibility("https://community.openai.com/page"));
        assertEquals(RerankSourceCredibility.UNVERIFIED, scorer.getSourceCredibility("https://unrelated.example/page"));
    }

    @Test
    void sourceAnalyzerDoesNotPromoteTheSyntheticDocumentationHost() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("authority", scorer("", ""));
        SourceAnalyzerService analyzer = new SourceAnalyzerService(beans.getBeanProvider(AuthorityScorer.class));
        assertEquals(SourceCredibility.COMMUNITY, analyzer.analyze("source", "https://docs.unrelated.example/page"));
        assertEquals(SourceCredibility.OFFICIAL, analyzer.analyze("source", "https://docs.microsoft.com/page"));
    }

    private AuthorityScorer scorer(String official, String unverified) {
        return new AuthorityScorer("", "", "", "", "", official, "", "", unverified,
                1.0, 0.85, 0.80, 0.70, 0.55, 0.25);
    }
}
