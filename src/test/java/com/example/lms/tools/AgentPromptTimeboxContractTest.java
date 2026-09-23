package com.example.lms.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AgentPromptTimeboxContractTest {

    @Test
    void quantHarmonyPromptUsesDynamicAgentBudgetInsteadOfFixedRuntimeSchedule() throws Exception {
        String prompt = Files.readString(
                Path.of("agent-prompts/agents/demo1_quant_harmony_9h_safe_patch/system_ko.md"),
                StandardCharsets.UTF_8);

        assertTrue(prompt.contains("maximum agent budget"),
                "9h language must be framed as an agent budget, not product runtime behavior");
        assertTrue(prompt.contains("timeAllocationValue"),
                "long-run patches must continue only while the next cycle has measurable value");
        assertTrue(prompt.contains("Do not encode a 9-hour runtime/product patch duration"),
                "prompt must forbid turning the agent timebox into a runtime feature");
        assertFalse(prompt.contains("## 8. Nine-Hour Timebox Plan"),
                "fixed hour-by-hour schedules make agents optimize for elapsed time");
        assertFalse(prompt.contains("1 to N cycles inside 9 hours."),
                "cycle count must be driven by value and evidence, not the wall-clock budget");
        assertFalse(prompt.contains("| 00:00-00:30 |"));
        assertFalse(prompt.contains("| 08:15-09:00 |"));
    }

    @Test
    void desktopRefereePromptUsesDynamicAgentBudgetInsteadOfFixedSchedule() throws Exception {
        String prompt = Files.readString(
                Path.of("agent-prompts/agents/demo1_desktop_referee_ablation_postprocess/system_ko.md"),
                StandardCharsets.UTF_8);

        assertTrue(prompt.contains("maximum agent budget"),
                "Desktop referee prompt must frame 9h as an agent budget");
        assertTrue(prompt.contains("timeAllocationValue"),
                "Desktop referee prompt must judge whether the next apply/review step is worth more time");
        assertTrue(prompt.contains("Do not encode a 9-hour runtime/product patch duration"),
                "prompt must forbid turning the agent timebox into runtime behavior");
        assertFalse(prompt.contains("## 3. 9-Hour Desktop Timebox"));
        assertFalse(prompt.contains("0:00-0:20"));
        assertFalse(prompt.contains("8:30-9:00"));
    }
}
