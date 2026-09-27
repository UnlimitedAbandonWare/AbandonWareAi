package com.example.lms.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardPromptBuilderAnswerDisciplineTest {

    @Test
    void instructionsRequireDecompositionEvidenceAndExplicitEvidenceNeeded() {
        String instructions = new StandardPromptBuilder().buildInstructions(
                PromptContext.builder()
                        .userQuery("프로젝트가 잘 되는지 보고 고쳐줘")
                        .build());

        assertTrue(instructions.contains("### ANSWER DECOMPOSITION AND VERIFICATION DISCIPLINE"));
        assertTrue(instructions.contains("split broad or bundled requests into small sub-questions"));
        assertTrue(instructions.contains("Ask exactly one clarifying question first"));
        assertTrue(instructions.contains("requirements summary -> repo/source evidence -> tool/log/test use -> error correction -> verification report"));
        assertTrue(instructions.contains("Do not claim build, browser, provider, web search, database, or runtime success"));
        assertTrue(instructions.contains("Do not introduce exact model names, API/tool names, parameters, dates, versions, prices, or limits"));
        assertTrue(instructions.contains("If a detail is missing from citable evidence"));
        assertTrue(instructions.contains("For exact field-value questions, copy the source key/value labels without swapping them"));
        assertTrue(instructions.contains("If the source says `type` and `model`, keep those labels attached to their original values"));
        assertTrue(instructions.contains("evidence_needed: <missing artifact> / verify with <exact command or probe>"));
    }
}
