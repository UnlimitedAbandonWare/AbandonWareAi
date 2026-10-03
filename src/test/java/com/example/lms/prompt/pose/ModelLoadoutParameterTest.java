package com.example.lms.prompt.pose;

import com.example.lms.llm.ModelCapabilities;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static com.example.lms.prompt.pose.ModelLoadoutResolver.*;
import static com.example.lms.prompt.pose.ModelLoadoutResolverTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelLoadoutParameterTest {
    @Test void unverifiedCapabilitiesCannotProduceEligibleParameterPlan() {
        ParameterPlan p = parameters(profile(ModelCapabilities.Profile.unknown(), "responses"), null, false, 200);
        assertFalse(p.eligible()); assertTrue(p.fields().isEmpty());
    }
    @Test void parameterPlanCannotExceedKnownOutputLimit() {
        ParameterPlan p = parameters(capable(), "low", false, 1025);
        assertFalse(p.eligible()); assertTrue(p.fields().isEmpty());
    }
    @Test void chatCompletionsUsesItsOwnOutputAndReasoningFields() {
        ParameterPlan p = parameters(profile(capable().capabilities(), "openai_chat_completions"), "low", false, 200);
        assertEquals(Set.of("model", "max_completion_tokens", "reasoning_effort"), p.fields().keySet());
        assertEquals(200, p.fields().get("max_completion_tokens"));
    }
    @Test void responsesUsesNestedReasoningRatherThanChatField() {
        ParameterPlan p = parameters(capable(), "low", false, 200);
        assertEquals(Set.of("model", "max_output_tokens", "reasoning"), p.fields().keySet());
        assertEquals(java.util.Map.of("effort", "low"), p.fields().get("reasoning"));
    }
    @Test void oauthRespectsExistingAllowlistAndRecordsUnsupportedOptionalEffort() {
        ParameterPlan p = parameters(profile(capable().capabilities(), "chatgpt-oauth"), "high", false, 200);
        assertEquals(Set.of("model", "store", "stream"), p.fields().keySet());
        assertEquals(false, p.fields().get("store")); assertEquals(true, p.fields().get("stream"));
        assertTrue(p.reasons().contains("OPTIONAL_REASONING_UNSUPPORTED"));
    }
    @Test void requiredReasoningCannotBeSilentlyRemovedForOauth() {
        assertFalse(parameters(profile(capable().capabilities(), "chatgpt-oauth"), "high", true, 200).eligible());
    }
    @Test void geminiDoesNotReceiveOpenaiReasoningString() {
        ParameterPlan p = parameters(profile(capable().capabilities(), "gemini_generate_content"), "high", false, 200);
        assertEquals(Set.of("generationConfig"), p.fields().keySet());
        assertEquals(java.util.Map.of("maxOutputTokens", 200), p.fields().get("generationConfig"));
        assertTrue(p.reasons().contains("OPTIONAL_REASONING_UNSUPPORTED"));
    }
    @Test void unknownCapabilityDropsOptionalReasoningAndRejectsRequired() {
        var profile = profile(ModelCapabilities.Profile.unknown(), "responses");
        assertFalse(parameters(profile, "high", false, 200).fields().containsKey("reasoning"));
        assertFalse(parameters(profile, "high", true, 200).eligible());
    }
    @Test void unsupportedEffortIsExplicitAndNeverCopiedIntoPayload() {
        ParameterPlan p = parameters(capable(), "unsupported-fixture", false, 200);
        assertFalse(p.fields().containsKey("reasoning")); assertTrue(p.reasons().contains("INVALID_REASONING_EFFORT"));
        assertFalse(parameters(capable(), "unsupported-fixture", true, 200).eligible());
    }
    @Test void unverifiedEndpointCombinationIsExcluded() {
        ParameterPlan p = parameters(profile(capable().capabilities(), "unverified-endpoint"), "low", false, 200);
        assertFalse(p.eligible()); assertTrue(p.fields().isEmpty());
    }
    @Test void cacheIdentitySeparatesEffortBudgetSchemaAndModelSnapshot() {
        var p = capable(); var l = new ModelLoadoutResolver(true).resolve(request(Set.of("evidence")), p);
        String a = cacheIdentity(p, l, "low", 1000, List.of("schema-a"));
        assertNotEquals(a, cacheIdentity(p, l, "high", 1000, List.of("schema-a")));
        assertNotEquals(a, cacheIdentity(p, l, "low", 2000, List.of("schema-a")));
        assertNotEquals(a, cacheIdentity(p, l, "low", 1000, List.of("schema-b")));
        assertNotEquals(a, cacheIdentity(profile(p.capabilities(), "openai_chat_completions"), l, "low", 1000, List.of("schema-a")));
        assertNotEquals(cacheIdentity(p, l, "low", 200, 200, 1000, List.of("schema-a")),
                cacheIdentity(p, l, "low", 200, 300, 1000, List.of("schema-a")));
    }
}
