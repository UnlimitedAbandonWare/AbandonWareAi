package com.example.lms.service.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalConsistencyVerifierReleaseTest {
    private final TemporalConsistencyVerifier verifier = new TemporalConsistencyVerifier();
    private final LocalDate now = LocalDate.of(2026, 9, 7);

    @ParameterizedTest
    @ValueSource(strings = {
            "It was released yesterday.",
            "Both editions were released last week.",
            "The final edition has been released.",
            "The release was not expected. It was released yesterday.",
            "Was it released? It was released yesterday."
    })
    void detectsEnglishCompletedReleaseEvidence(String evidence) {
        assertFalse(verifier.verify("It is not yet released.", List.of(evidence), now).isPass());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "It was not released yesterday.",
            "It will be released when validation has been completed.",
            "It has not yet been released.",
            "Its release was announced yesterday.",
            "It would have been released if approval had arrived.",
            "The claim that it was released yesterday is false.",
            "We asked whether it was released.",
            "If it was released, release notes should exist."
    })
    void keepsNegatedPlannedAndConditionalReleaseEvidenceDistinct(String evidence) {
        assertTrue(verifier.verify("It is not yet released.", List.of(evidence), now).isPass());
    }

    @Test
    void preservesKoreanCompletedReleaseAndAbsentInputControls() {
        assertFalse(verifier.verify("아직 출시되지 않았습니다.", List.of("이미 출시되었습니다."), now).isPass());
        assertTrue(verifier.verify("It is not yet released.", Arrays.asList(null, ""), now).isPass());
        assertTrue(verifier.verify("It is already available.", List.of("It was released yesterday."), now).isPass());
    }
}
