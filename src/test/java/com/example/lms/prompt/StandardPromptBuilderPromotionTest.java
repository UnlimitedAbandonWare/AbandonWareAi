package com.example.lms.prompt;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StandardPromptBuilderPromotionTest {
    private String instructions(String query) {
        return new StandardPromptBuilder().buildInstructions(PromptContext.builder().userQuery(query).build());
    }

    @Test
    void lawfulAccountOffersGetDiscoveryAndFieldSpecificVerificationInsteadOfBlanketRefusal() {
        String result = instructions("SuperGrok Heavy existing-account retention discount, not payment bypass");
        assertTrue(result.contains("LAWFUL PROMOTION DISCOVERY"));
        for (String field : List.of("plan identity", "purchase platform", "amount due today", "currency",
                "billing cadence", "discount duration", "new/existing subscriber", "renewal price", "observation time")) {
            assertTrue(result.contains(field), field);
        }
        assertTrue(result.contains("not proof of global expiry"));
        assertTrue(result.contains("evidence_needed"));
        assertEquals(Boolean.TRUE, TraceStore.get("prompt.promotionDiscovery.active"));
    }

    @Test
    void plusPricingAndUrlDiscountLabelsCannotProveHeavyEligibility() {
        String result = instructions("Grok Heavy 67% discount");
        assertTrue(result.contains("SuperGrok Plus"));
        assertTrue(result.contains("SuperGrok Heavy"));
        assertTrue(result.contains("URL slug"));
        assertTrue(result.contains("checkout"));
        assertTrue(result.contains("proration"));
        assertTrue(result.contains("Google Play"));
        assertTrue(result.contains("Stripe"));
    }

    @Test
    void discoveryDoesNotAuthorizePaymentTamperingOrSubscriptionChanges() {
        String result = instructions("Grok Heavy hidden promotion");
        assertTrue(result.contains("Do not fabricate eligibility"));
        assertTrue(result.contains("payment tokens"));
        assertTrue(result.contains("does not authorize purchases"));
    }

    @Test
    void unrelatedTurnDoesNotInheritPromotionPolicyOrTrace() {
        instructions("Grok Heavy discount");
        String result = instructions("Explain Java streams");
        assertFalse(result.contains("LAWFUL PROMOTION DISCOVERY"));
        assertEquals(Boolean.FALSE, TraceStore.get("prompt.promotionDiscovery.active"));
    }

    @Test
    void retrievalOffKeepsCurrentOfferUnverifiedAndDoesNotEnableSearch() {
        String result = new StandardPromptBuilder().buildInstructions(PromptContext.builder()
                .userQuery("Grok Heavy discount").ragEnabled(false).build());
        assertTrue(result.contains("Retrieval is OFF"));
        assertTrue(result.contains("Respect retrieval-off"));
        assertTrue(result.contains("evidence_needed"));
    }
}
