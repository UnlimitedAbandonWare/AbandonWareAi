package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.llm.ModelGuardSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGuardYamlCompatibilityTest {

    @Test
    void applicationLlmYamlDoesNotMisclassifyChatCompatibleGpt55AsResponsesOnly() throws Exception {
        String yaml = Files.readString(
                Path.of("main/resources/application-llm.yaml"),
                StandardCharsets.UTF_8);
        int guardStart = yaml.indexOf("    model-guard:");
        int guardEnd = yaml.indexOf("    web-failsoft:", guardStart);
        assertTrue(guardStart >= 0 && guardEnd > guardStart, "model-guard block must remain present");

        List<String> configuredPrefixes = yaml.substring(guardStart, guardEnd).lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).trim())
                .toList();

        assertFalse(ModelGuardSupport.isResponsesOnlyModel("gpt-5.5", configuredPrefixes),
                "gpt-5.5 supports /v1/chat/completions and must reach its configured API route");
        assertTrue(ModelGuardSupport.isResponsesOnlyModel("gpt-5.5-pro", configuredPrefixes),
                "the Responses-only Pro variant must remain guarded");

        // gpt-5.6-luna/terra/sol are routed to chat/completions by
        // application-meta-display.yml (Display cue LLM contract, pinned by
        // ConversateApiRouteTest). Adding a bare "gpt-5.6" prefix would
        // substitute those calls to the local chat model — it must stay absent.
        assertFalse(ModelGuardSupport.isResponsesOnlyModel("gpt-5.6-luna", configuredPrefixes),
                "gpt-5.6-luna is a chat/completions model in the Display cue route and must not be guarded");

        // Spring List binding replaces NovaModelGuardProperties defaults, so the YAML
        // must carry the Java default set as well as the chat-model additions.
        for (String responsesOnly : List.of(
                "gpt-5-pro", "gpt-5.1-codex", "gpt-5-codex",
                "o3-deep-research", "o4-mini-deep-research")) {
            assertTrue(ModelGuardSupport.isResponsesOnlyModel(responsesOnly, configuredPrefixes),
                    "Java-default responses-only model must stay guarded: " + responsesOnly);
        }
    }
}
