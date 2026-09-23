package ai.abandonware.nova.config;

import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaWebFailSoftPropertiesTest {

    @Test
    void nullCollectionSettersFallbackToEmptyCollections() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();

        props.setStageOrder(null);
        props.setTechSpamKeywords(null);
        props.setTechSpamDomains(null);
        props.setOfficialDomains(null);
        props.setDocsDomains(null);
        props.setDevCommunityDomains(null);
        props.setDevCommunityDenyDomains(null);

        assertNotNull(props.getStageOrder());
        assertNotNull(props.getTechSpamKeywords());
        assertNotNull(props.getTechSpamDomains());
        assertNotNull(props.getOfficialDomains());
        assertNotNull(props.getDocsDomains());
        assertNotNull(props.getDevCommunityDomains());
        assertNotNull(props.getDevCommunityDenyDomains());
        assertTrue(props.getStageOrder().isEmpty());
        assertTrue(props.getTechSpamKeywords().isEmpty());
        assertTrue(props.getTechSpamDomains().isEmpty());
        assertTrue(props.getOfficialDomains().isEmpty());
        assertTrue(props.getDocsDomains().isEmpty());
        assertTrue(props.getDevCommunityDomains().isEmpty());
        assertTrue(props.getDevCommunityDenyDomains().isEmpty());
    }

    @Test
    void queryAugmenterToleratesNullBoundSpamLists() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        props.setTechSpamKeywords(null);
        props.setTechSpamDomains(null);

        RuleBasedQueryAugmenter augmenter = new RuleBasedQueryAugmenter(props);

        assertDoesNotThrow(() -> augmenter.augment("Gemini API quota"));
    }
}
