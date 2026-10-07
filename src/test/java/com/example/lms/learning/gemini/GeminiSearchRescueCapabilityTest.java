package com.example.lms.learning.gemini;

import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeminiSearchRescueCapabilityTest {
    @AfterEach void clearState() { TraceStore.clear(); }

    @Test void unknownSearchCapabilityCannotDispatchNativeRescue() {
        var attempts = new AtomicInteger();
        var result = gateway("gemini-unsupported-synthetic", attempts)
                .searchRescue("synthetic question", "synthetic query", true, true, 10_000).block();
        assertNotNull(result);
        assertEquals("SEARCH_CAPABILITY_UNKNOWN", result.reasonCode());
        assertFalse(result.attempted());
        assertFalse(result.allowed());
        assertEquals(0, attempts.get());
    }

    @Test void explicitOptOutNeverDispatchesEvenForSupportedModel() {
        assertSkipped(false, true, 10_000, "OPT_OUT");
    }

    @Test void sufficientEvidenceNeverDispatches() {
        assertSkipped(true, false, 10_000, "EVIDENCE_SUFFICIENT");
    }

    @Test void exhaustedRequestBudgetNeverDispatches() {
        assertSkipped(true, true, 5_000, "REQUEST_BUDGET");
    }

    @Test void supportedRescueRetainsItsNativeWireWithoutRouterPurposePermission() {
        var attempts = new AtomicInteger();
        var result = gateway("gemini-3.8-flash", attempts)
                .searchRescue("synthetic question", "synthetic query", true, true, 10_000).block();
        assertNotNull(result);
        assertTrue(result.attempted());
        assertEquals(1, attempts.get());
        assertNull(result.answer(), "unattributed text must not become a grounded publication");
    }

    private static void assertSkipped(boolean optIn, boolean needsEvidence, long remaining, String reason) {
        var attempts = new AtomicInteger();
        var result = gateway("gemini-3.8-flash", attempts)
                .searchRescue("synthetic question", "synthetic query", optIn, needsEvidence, remaining).block();
        assertNotNull(result);
        assertEquals(reason, result.reasonCode());
        assertFalse(result.attempted());
        assertEquals(0, attempts.get());
    }

    private static GeminiGateway gateway(String model, AtomicInteger attempts) {
        var environment = new MockEnvironment()
                .withProperty("GEMINI_API_KEY", "synthetic-rescue-key")
                .withProperty("gemini.gateway.enabled", "true")
                .withProperty("gemini.gateway.grounding.enabled", "true")
                .withProperty("gemini.gateway.grounding.usage-ledger", "")
                .withProperty("gemini.gateway.purpose.router.enabled", "false")
                .withProperty("gemini.gateway.models.search-rescue", model);
        return new GeminiGateway(WebClient.builder().exchangeFunction(request -> {
            attempts.incrementAndGet();
            assertTrue(request.url().getPath().endsWith(":generateContent"));
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"synthetic\"}]}}]}")
                    .build());
        }), new ProviderCredentialResolver(environment), environment);
    }
}
