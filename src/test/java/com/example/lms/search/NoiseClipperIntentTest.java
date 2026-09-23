package com.example.lms.search;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoiseClipperIntentTest {
    private final NoiseClipper clipper = new NoiseClipper();

    @Test
    void preservesNegationAndRequestedAttributeAfterEntityQuestion() {
        assertEquals("삼성이 뭐 하는지 말고 법인명", clipper.clip("삼성이 뭐 하는지 말고 법인명 알려줘"));
    }

    @Test
    void preservesSubstantiveTailAfterPlaceAndLocationQuestions() {
        assertEquals("회사가 어떤 곳인지보다 채용 조건", clipper.clip("회사가 어떤 곳인지보다 채용 조건 알려줘"));
        assertEquals("본사가 어디 있는지와 이전 계획", clipper.clip("본사가 어디 있는지와 이전 계획 알려줘"));
    }

    @Test
    void stillExtractsAnEntityFromStandaloneQuestions() {
        assertEquals("삼성", clipper.clip("삼성이 뭐야?"));
        assertEquals("회사", clipper.clip("회사가 어떤 곳인가요?"));
        assertEquals("본사", clipper.clip("본사가 어디야?"));
    }

    @Test
    void retainsOrdinaryCleanupAndEmptyInputHandling() {
        assertEquals("삼성 법인명", clipper.clip("질문:  삼성   법인명 알려줘"));
        assertEquals("", clipper.clip(null));
        assertEquals("", clipper.clip("  "));
    }
}
