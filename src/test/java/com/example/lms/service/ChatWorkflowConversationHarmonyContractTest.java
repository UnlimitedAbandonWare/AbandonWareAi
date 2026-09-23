package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.guard.ConversationFrameResolver;

class ChatWorkflowConversationHarmonyContractTest {

    @Test
    void offShadowAndEnforcedStandardPreserveLegacyOptionalPaths() {
        for (ConversationFrameV1 frame : List.of(
                frame(ConversationFrameV1.Mode.OFF, ConversationFrameV1.Stance.REPAIR),
                frame(ConversationFrameV1.Mode.SHADOW, ConversationFrameV1.Stance.REPAIR),
                frame(ConversationFrameV1.Mode.ENFORCE, ConversationFrameV1.Stance.STANDARD))) {
            assertTrue(ChatWorkflow.allowsConversationShortCircuit(true, frame), frame.toString());
            assertTrue(ChatWorkflow.allowsConversationRefinement(true, frame), frame.toString());
            assertTrue(ChatWorkflow.allowsConversationExpansion(frame), frame.toString());
            assertFalse(ChatWorkflow.deniesConversationMemoryWrite(false, frame), frame.toString());
        }
    }

    @Test
    void enforcedNonStandardStancesSuppressOptionalPathsAndMemoryWrites() {
        for (ConversationFrameV1.Stance stance : List.of(
                ConversationFrameV1.Stance.REPAIR,
                ConversationFrameV1.Stance.SUPPORTIVE_CHECK_IN,
                ConversationFrameV1.Stance.SAFETY_FIRST)) {
            ConversationFrameV1 frame = frame(ConversationFrameV1.Mode.ENFORCE, stance);

            assertFalse(ChatWorkflow.allowsConversationShortCircuit(true, frame), stance.name());
            assertFalse(ChatWorkflow.allowsConversationRefinement(true, frame), stance.name());
            assertFalse(ChatWorkflow.allowsConversationExpansion(frame), stance.name());
            assertTrue(ChatWorkflow.deniesConversationMemoryWrite(false, frame), stance.name());
        }
    }

    @Test
    void existingInteractionAndFeatureGatesRemainAuthoritative() {
        ConversationFrameV1 standard = frame(
                ConversationFrameV1.Mode.ENFORCE,
                ConversationFrameV1.Stance.STANDARD);

        assertFalse(ChatWorkflow.allowsConversationShortCircuit(false, standard));
        assertFalse(ChatWorkflow.allowsConversationRefinement(false, standard));
        assertTrue(ChatWorkflow.deniesConversationMemoryWrite(true, standard));
    }

    @Test
    void nullFrameUsesTheSafeOffCompatibilityDefault() {
        assertTrue(ChatWorkflow.allowsConversationShortCircuit(true, null));
        assertTrue(ChatWorkflow.allowsConversationRefinement(true, null));
        assertTrue(ChatWorkflow.allowsConversationExpansion(null));
        assertFalse(ChatWorkflow.deniesConversationMemoryWrite(false, null));
    }

    @Test
    void wireCoverageRequiresAnObservedAttemptLedgerRow() {
        assertFalse(ChatWorkflow.hasObservedConversationWireAttempt(List.of()));
        assertFalse(ChatWorkflow.hasObservedConversationWireAttempt(List.of(
                Map.of("providerAttemptObserved", true, "wireAttemptObserved", false))));
        assertTrue(ChatWorkflow.hasObservedConversationWireAttempt(List.of(
                Map.of("wireAttemptObserved", true))));
    }

    @Test
    void missingResolverInLegacyManualFixtureUsesTheDeterministicFallback() {
        ConversationFrameV1 off = ChatWorkflow.resolveConversationFrame(
                null,
                "그만해",
                false,
                ConversationFrameV1.Mode.OFF);
        ConversationFrameV1 enforce = ChatWorkflow.resolveConversationFrame(
                new ConversationFrameResolver(),
                "그만해",
                false,
                ConversationFrameV1.Mode.ENFORCE);

        assertTrue(off.mode() == ConversationFrameV1.Mode.OFF);
        assertTrue(enforce.stance() == ConversationFrameV1.Stance.REPAIR);
    }

    private static ConversationFrameV1 frame(
            ConversationFrameV1.Mode mode,
            ConversationFrameV1.Stance stance) {
        return new ConversationFrameV1(
                mode,
                stance,
                ConversationFrameV1.LightweightRole.OBSERVE_ONLY,
                false,
                false,
                false,
                ConversationFrameV1.ReasonCode.DEFAULT);
    }
}
