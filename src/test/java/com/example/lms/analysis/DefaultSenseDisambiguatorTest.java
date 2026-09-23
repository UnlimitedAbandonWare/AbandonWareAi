package com.example.lms.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSenseDisambiguatorTest {

    @Test
    void noLongerDefaultsUnclearChatbotQueriesToLegacyAcademySense() {
        var result = new DefaultSenseDisambiguator().candidates("summarize this chatbot answer", List.of());

        assertTrue(result.senses().isEmpty());
    }

    @Test
    void educationSignalsUseGenericSenseNamesWithoutLegacyAcademyIds() {
        var result = new DefaultSenseDisambiguator()
                .candidates("교육 커리큘럼 비교", List.of("국비지원 교육 과정 .kr"));

        assertTrue(result.senses().stream().anyMatch(s -> s.id().equals("education-organization")));
        assertFalse(result.senses().stream().anyMatch(s -> s.id().equals("local-academy")));
        assertFalse(result.senses().stream().anyMatch(s -> s.id().equals("dw-akademie")));
        assertFalse(result.senses().toString().contains("DW Akademie"));
    }
}
