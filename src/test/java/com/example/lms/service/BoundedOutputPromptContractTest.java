package com.example.lms.service;

import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.service.verbosity.SectionSpecGenerator;
import com.example.lms.service.verbosity.VerbosityDetector;
import com.example.lms.service.verbosity.VerbosityProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedOutputPromptContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"17+25는 얼마야? 아라비아 숫자만 답해줘.",
            "대한민국의 수도는 어디인가요? 도시 이름만 답해주세요.",
            "정답을 한 단어로만 대답해주세요."})
    void compactRequestDoesNotReceiveSectionTemplate(String query) {
        VerbosityProfile profile = new VerbosityDetector().detect(query);
        List<String> sections = new SectionSpecGenerator()
                .generate("GENERAL", "EDU", profile.hint());
        PromptContext context = PromptContext.builder()
                .userQuery(query)
                .answerMode(com.example.lms.domain.enums.AnswerMode.ALL_ROUNDER)
                .ragEnabled(false)
                .verbosityHint(profile.hint())
                .minWordCount(profile.minWordCount())
                .targetTokenBudgetOut(profile.targetTokenBudgetOut())
                .sectionSpec(sections)
                .build();

        String instructions = new StandardPromptBuilder().buildInstructions(context);

        assertEquals("brief", profile.hint());
        assertEquals(0, profile.minWordCount());
        assertFalse(sections.isEmpty(), "fixture must exercise a real generated section template");
        assertTrue(instructions.contains("### DIRECT MODE STYLE OVERRIDE"), instructions);
        assertFalse(instructions.contains("### SECTION TEMPLATE"), instructions);
        assertFalse(instructions.contains("위 흐름을 유지하되"),
                "bounded output must not also require the five-section default");
        assertTrue(instructions.contains("Do not add polite sentence endings"),
                "name-only output must stay a name rather than a full sentence");
    }

    @Test
    void ordinaryDirectAnswerKeepsDefaultStructureAndDoesNotRequireExactOutput() {
        String instructions = new StandardPromptBuilder().buildInstructions(PromptContext.builder()
                .userQuery("두 가지 접근법의 장단점을 자세히 설명해줘.")
                .answerMode(com.example.lms.domain.enums.AnswerMode.ALL_ROUNDER)
                .ragEnabled(false).verbosityHint("standard").minWordCount(250).build());

        assertTrue(instructions.contains("### MODE: ALL_ROUNDER"));
        assertTrue(instructions.contains("위 흐름을 유지하되"));
        assertFalse(instructions.contains("Do not add polite sentence endings"));
    }
}
