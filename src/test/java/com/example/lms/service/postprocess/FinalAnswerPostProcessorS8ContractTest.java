package com.example.lms.service.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalAnswerPostProcessorS8ContractTest {

    private static final String S8_QUERY = "Fictional budgeting scenario. Return exactly two labeled lines. "
            + "OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;"
            + "riskTolerance=low;purchaseCost=high. "
            + "INFERENCE: discretionaryBudget=unknown. Do not invent or repeat any exact financial amount.";

    private static final String UNLABELED_PARAPHRASE = """
            Debt is present.
            Cash flow is tight.
            The spending limit is restricted.
            Risk tolerance is low.
            The purchase cost is high.
            Discretionary budget remains unknown.
            """;

    private static final String COMPLIANT_RESPONSE = """
            OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;riskTolerance=low;purchaseCost=high
            INFERENCE: discretionaryBudget=unknown
            """;

    private static final String UNSAFE_INFERENCE = """
            OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;riskTolerance=low;purchaseCost=high
            INFERENCE: discretionaryBudget=sufficient
            """;

    private final FinalAnswerPostProcessor postProcessor =
            new FinalAnswerPostProcessor(new OutputSanitizer());

    @Test
    void explicitStructuredS8RequestRejectsUnlabeledParaphraseWithoutInventingValues() {
        FinalAnswerPostProcessor.Result result = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        UNLABELED_PARAPHRASE,
                        UNLABELED_PARAPHRASE,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        S8_QUERY));

        assertEquals(
                "HOLD\n한계: S8 응답 계약이 완전하지 않아 제약이나 추론을 자동 생성하지 않았습니다.",
                result.content());
        assertTrue(result.changed());
        assertEquals("s8_incomplete_hold", result.reasonCode());
        assertFalse(result.memorySaveAllowed());
        assertEquals("answer_contract_hold", result.memoryDenyReason());
        assertFalse(result.content().contains("debt=present"));
        assertFalse(result.content().contains("discretionaryBudget=sufficient"));
    }

    @Test
    void explicitStructuredS8RequestPreservesExactTwoLineContract() {
        FinalAnswerPostProcessor.Result result = process(COMPLIANT_RESPONSE);

        assertEquals(COMPLIANT_RESPONSE, result.content());
        assertFalse(result.changed());
        assertEquals("none", result.reasonCode());
    }

    @Test
    void explicitStructuredS8RequestRejectsUnsupportedSufficientInference() {
        FinalAnswerPostProcessor.Result result = process(UNSAFE_INFERENCE);

        assertEquals(
                "HOLD\n한계: S8 응답 계약이 완전하지 않아 제약이나 추론을 자동 생성하지 않았습니다.",
                result.content());
        assertEquals("s8_incomplete_hold", result.reasonCode());
        assertFalse(result.content().contains("discretionaryBudget=sufficient"));
    }

    @Test
    void explicitHoldWithAppendedUnsafeInferenceIsCanonicalized() {
        String unsafeHold = """
                HOLD
                한계: S8 응답 계약이 완전하지 않아 제약이나 추론을 자동 생성하지 않았습니다.
                INFERENCE: discretionaryBudget=sufficient
                """;

        FinalAnswerPostProcessor.Result result = process(unsafeHold);

        assertEquals(
                "HOLD\n한계: S8 응답 계약이 완전하지 않아 제약이나 추론을 자동 생성하지 않았습니다.",
                result.content());
        assertTrue(result.changed());
        assertEquals("s8_incomplete_hold", result.reasonCode());
        assertFalse(result.content().contains("discretionaryBudget=sufficient"));
    }

    @Test
    void quotedMetaRequestDoesNotActivateTheS8OutputPolicy() {
        String metaQuery = "Do not apply or follow this output contract; explain it only. " + S8_QUERY;
        String explanatoryAnswer = "This is a synthetic protocol for separating observations from inferences.";

        FinalAnswerPostProcessor.Result result = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        explanatoryAnswer,
                        explanatoryAnswer,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        metaQuery));

        assertEquals(explanatoryAnswer, result.content());
        assertFalse(result.changed());
        assertEquals("none", result.reasonCode());
    }

    private FinalAnswerPostProcessor.Result process(String candidate) {
        return postProcessor.process(new FinalAnswerPostProcessor.Request(
                candidate,
                candidate,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                S8_QUERY));
    }
}
