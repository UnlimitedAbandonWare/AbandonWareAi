package com.example.lms.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ConversationFrameResolverTest {

    private final ConversationFrameResolver resolver = new ConversationFrameResolver();

    @Test
    void explicitStopSelectsRepairAndSuppressesOptionalWorkWhenEnforced() {
        ConversationFrameV1 frame = resolve("이제 그만하고 분석을 멈춰 줘", ConversationFrameV1.Mode.ENFORCE);

        assertEquals(ConversationFrameV1.Stance.REPAIR, frame.stance());
        assertEquals(ConversationFrameV1.ReasonCode.EXPLICIT_STOP, frame.reasonCode());
        assertEquals(ConversationFrameV1.LightweightRole.ABSTAIN, frame.lightweightRole());
        assertFalse(frame.allowsDirectShortCircuit());
        assertFalse(frame.allowsOptionalRefinement());
        assertFalse(frame.allowsOptionalExpansion());
        assertTrue(frame.suppressesMemoryWrites());
    }

    @Test
    void assistantBoundaryComplaintSelectsRepairWithoutChangingEvidencePolicy() {
        ConversationFrameV1 frame = resolve(
                "네가 내 경계를 무시하고 계속 분석해서 불편했어",
                ConversationFrameV1.Mode.ENFORCE);

        assertEquals(ConversationFrameV1.Stance.REPAIR, frame.stance());
        assertEquals(ConversationFrameV1.ReasonCode.ASSISTANT_BOUNDARY_COMPLAINT, frame.reasonCode());
    }

    @Test
    void directDistressSelectsSupportiveCheckIn() {
        for (String request : List.of(
                "너무 지치고 혼자인 느낌이야",
                "요즘 마음이 너무 힘들고 압도돼")) {
            ConversationFrameV1 frame = resolve(request, ConversationFrameV1.Mode.ENFORCE);

            assertEquals(ConversationFrameV1.Stance.SUPPORTIVE_CHECK_IN, frame.stance(), request);
            assertEquals(ConversationFrameV1.ReasonCode.DISTRESS_CHECK_IN, frame.reasonCode(), request);
        }
    }

    @Test
    void immediateFirstPersonSafetySignalHasHighestPriority() {
        ConversationFrameV1 frame = resolve(
                "분석은 멈춰. 지금 나를 해칠 것 같아",
                ConversationFrameV1.Mode.ENFORCE);

        assertEquals(ConversationFrameV1.Stance.SAFETY_FIRST, frame.stance());
        assertEquals(ConversationFrameV1.ReasonCode.IMMEDIATE_SAFETY_SIGNAL, frame.reasonCode());
        assertEquals(ConversationFrameV1.LightweightRole.ABSTAIN, frame.lightweightRole());
    }

    @Test
    void negationTranslationQuotationAndThirdPartyReportingStayStandard() {
        for (String request : List.of(
                "멈추지 말고 계속 설명해",
                "나는 죽고 싶지 않고 자해할 생각도 없어",
                "'죽고 싶다'를 영어로 번역해 줘",
                "정책 문서에서 자살 위험 표현을 어떻게 분류하는지 설명해 줘",
                "친구가 죽고 싶다고 말했다고 보고했어")) {
            ConversationFrameV1 frame = resolve(request, ConversationFrameV1.Mode.ENFORCE);

            assertEquals(ConversationFrameV1.Stance.STANDARD, frame.stance(), request);
            assertEquals(ConversationFrameV1.ReasonCode.DEFAULT, frame.reasonCode(), request);
            assertEquals(ConversationFrameV1.LightweightRole.OBSERVE_ONLY, frame.lightweightRole(), request);
        }
    }

    @Test
    void offAndShadowComputeBoundedObservationWithoutChangingLegacyBehavior() {
        for (ConversationFrameV1.Mode mode : List.of(
                ConversationFrameV1.Mode.OFF,
                ConversationFrameV1.Mode.SHADOW)) {
            ConversationFrameV1 frame = resolve("이제 분석을 멈춰 줘", mode);

            assertEquals(ConversationFrameV1.Stance.REPAIR, frame.stance());
            assertEquals(ConversationFrameV1.LightweightRole.OBSERVE_ONLY, frame.lightweightRole());
            assertTrue(frame.allowsDirectShortCircuit());
            assertTrue(frame.allowsOptionalRefinement());
            assertTrue(frame.allowsOptionalExpansion());
            assertFalse(frame.suppressesMemoryWrites());
            assertEquals(mode == ConversationFrameV1.Mode.SHADOW, frame.shouldTrace());
        }
    }

    @Test
    void normalizationIsNullSafeAndPreservesAStopSignalAtTheBoundedTail() {
        assertEquals(ConversationFrameV1.Stance.STANDARD, resolve(null, ConversationFrameV1.Mode.ENFORCE).stance());
        assertEquals(ConversationFrameV1.Stance.STANDARD, resolve("   ", ConversationFrameV1.Mode.ENFORCE).stance());
        assertEquals(
                ConversationFrameV1.Stance.REPAIR,
                resolve("\u200B멈춰\u200D", ConversationFrameV1.Mode.ENFORCE).stance());
        assertEquals(
                ConversationFrameV1.Stance.REPAIR,
                resolve("일반 설명 ".repeat(700) + " 이제 분석을 멈춰", ConversationFrameV1.Mode.ENFORCE).stance());
    }

    @Test
    void modeParserFailsClosedToOffForUnknownValues() {
        assertEquals(ConversationFrameV1.Mode.OFF, ConversationFrameV1.Mode.parse(null));
        assertEquals(ConversationFrameV1.Mode.OFF, ConversationFrameV1.Mode.parse("unexpected"));
        assertEquals(ConversationFrameV1.Mode.SHADOW, ConversationFrameV1.Mode.parse(" shadow "));
        assertEquals(ConversationFrameV1.Mode.ENFORCE, ConversationFrameV1.Mode.parse("ENFORCE"));
    }

    private ConversationFrameV1 resolve(String text, ConversationFrameV1.Mode mode) {
        return resolver.resolve(text, false, mode);
    }
}
