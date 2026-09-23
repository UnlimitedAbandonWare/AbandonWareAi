package com.example.lms.search.policy;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GrokPromotionEvidenceTest {
    @Test
    void fullBaseCannotCrowdOutAccountOfferAndTermsLanes() {
        List<List<String>> lanes = List.of(List.of("offer-1", "offer-2"),
                List.of("terms-1", "terms-2"), List.of("list-price"));
        assertEquals(List.of("offer-1"), GrokPromotionDiscovery.mergeEvidence(lanes, 1));
        assertEquals(List.of("offer-1", "terms-1", "list-price", "offer-2"),
                GrokPromotionDiscovery.mergeEvidence(lanes, 4));
    }

    @Test
    void duplicateAndBlankEvidenceDoesNotConsumeTheResultLimit() {
        assertEquals(List.of("offer", "terms"), GrokPromotionDiscovery.mergeEvidence(
                List.of(List.of("offer", "offer", ""), List.of("offer", "terms")), 2));
    }

    @Test
    void absentEvidenceAndNonpositiveLimitsStayEmpty() {
        assertEquals(List.of(), GrokPromotionDiscovery.mergeEvidence(null, 3));
        assertEquals(List.of(), GrokPromotionDiscovery.mergeEvidence(List.of(), 3));
        assertEquals(List.of(), GrokPromotionDiscovery.mergeEvidence(List.of(List.of("offer")), 0));
        assertEquals(List.of(), GrokPromotionDiscovery.mergeEvidence(List.of(List.of("offer")), -1));
    }
}
