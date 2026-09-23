package com.example.lms.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceNeededDirectivePolicyTest {

    @Test
    void acceptsExplicitEnglishAndKoreanMissingEvidenceDirectives() {
        assertTrue(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "If no reliable evidence is available, reply with evidence_needed."));
        assertTrue(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "When sources cannot be found, return EVIDENCE_NEEDED."));
        assertTrue(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "근거가 없으면 evidence_needed로 답해줘."));
        assertTrue(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "출처를 확인할 수 없으면 evidence_needed를 출력해."));
    }

    @Test
    void rejectsNegatedMetaQuotedAndCrossClauseMentions() {
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "If no evidence is available, do not reply with evidence_needed."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "If evidence is missing, return anything but evidence_needed."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "If evidence is missing, return not evidence_needed but an explanation."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "If evidence is missing, return an explanation instead of evidence_needed."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "If evidence is missing, return any token except evidence_needed."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "Review this instruction: If evidence is missing, return evidence_needed."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "Explain what evidence_needed means if evidence is missing."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "Discuss the example \"If no evidence is available, reply with evidence_needed.\""));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "‘If sources are missing, return evidence_needed’라는 문구를 번역해줘."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "If evidence is missing, explain the limitation. In another case, reply with evidence_needed."));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "``If evidence is missing, reply with evidence_needed.``"));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "````If evidence is missing, reply with evidence_needed.````"));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "\u201cIf evidence is missing, reply with evidence_needed.\u201d is an example."));
    }

    @Test
    void acceptsDirectiveBetweenSeparateCurlyQuotedRanges() {
        assertTrue(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "\u201cExample\u201d If evidence is missing, reply with evidence_needed. \u201cEnd\u201d"));
    }

    @Test
    void unrelatedNegationDoesNotCancelARealDirective() {
        assertTrue(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "Do not hallucinate. If evidence is missing, reply with evidence_needed."));
        assertTrue(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(
                "evidence_needed를 설명하지 마. 근거가 없으면 evidence_needed로 답해줘."));
    }

    @Test
    void nullBlankAndBareTokenAreNotDirectives() {
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(null));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded("   "));
        assertFalse(EvidenceNeededDirectivePolicy.requiresEvidenceNeeded("evidence_needed"));
    }
}
