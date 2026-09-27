package com.example.lms.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestSettingsMergerTest {

    @Test
    void omittedJsonTemperatureUsesServerSettingInsteadOfDtoLegacyDefault() throws Exception {
        ChatRequestDto ui = new ObjectMapper().readValue(
                "{\"message\":\"hello\"}", ChatRequestDto.class);

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                Map.of(SettingsService.KEY_TEMPERATURE, "0.3"),
                false,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals(0.3, merged.getTemperature());
    }

    @Test
    void nullableUiSamplingAndRetrievalFlagsFallBackToSettings() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .sessionId(7L)
                .message("hello")
                .temperature(null)
                .topP(null)
                .frequencyPenalty(null)
                .presencePenalty(null)
                .model(null)
                .useRag(null)
                .useWebSearch(null)
                .searchMode(SearchMode.FORCE_LIGHT)
                .webProviders(List.of("NAVER"))
                .officialSourcesOnly(true)
                .webTopK(4)
                .precisionSearch(true)
                .precisionTopK(3)
                .accumulation(true)
                .roleScope(List.of("OFFICIAL"))
                .domainProfile("official")
                .attachmentIds(List.of("att-1"))
                .polish(true)
                .build();
        ui.setUseWebSearch(null);
        Map<String, String> settings = new HashMap<>();
        settings.put(SettingsService.KEY_OPENAI_MODEL, "settings-model");
        settings.put(SettingsService.KEY_TEMPERATURE, "0.4");
        settings.put(SettingsService.KEY_TOP_P, "0.8");
        settings.put(SettingsService.KEY_FREQUENCY_PENALTY, "0.1");
        settings.put(SettingsService.KEY_PRESENCE_PENALTY, "0.2");
        settings.put("chat.defaults.useWebSearch", "true");

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                settings,
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals(7L, merged.getSessionId());
        assertEquals("settings-model", merged.getModel());
        assertEquals(0.4, merged.getTemperature());
        assertEquals(0.8, merged.getTopP());
        assertEquals(0.1, merged.getFrequencyPenalty());
        assertEquals(0.2, merged.getPresencePenalty());
        assertTrue(merged.getUseRag());
        assertTrue(merged.getUseWebSearch());
        assertEquals(SearchMode.FORCE_LIGHT, merged.getSearchMode());
        assertEquals(List.of("NAVER"), merged.getWebProviders());
        assertEquals(List.of("att-1"), merged.getAttachmentIds());
    }

    @Test
    void explicitUseRagFalseSurvivesTrueDefaultWhileNullUsesDefault() {
        ChatRequestDto omitted = ChatRequestSettingsMerger.merge(
                ChatRequestDto.builder().message("omitted").useRag(null).build(),
                Map.of(),
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));
        ChatRequestDto explicitFalse = ChatRequestSettingsMerger.merge(
                ChatRequestDto.builder().message("explicit-off").useRag(false).build(),
                Map.of(),
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals(Boolean.TRUE, omitted.getUseRag());
        assertTrue(omitted.isUseRag());
        assertNull(omitted.getRetrievalRequestIntent().rag());

        assertEquals(Boolean.FALSE, explicitFalse.getUseRag());
        assertFalse(explicitFalse.isUseRag());
        assertEquals(Boolean.FALSE, explicitFalse.getRetrievalRequestIntent().rag());
    }

    @Test
    void retrievalIntentSurvivesDefaultsEffectiveOverridesAndJsonSerialization() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ChatRequestDto ui = objectMapper.readValue(
                "{\"message\":\"hello\",\"useWebSearch\":true}",
                ChatRequestDto.class);

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                Map.of("chat.defaults.useWebSearch", "false"),
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        ChatRequestDto.RetrievalRequestIntent intent = merged.getRetrievalRequestIntent();
        assertNotNull(intent);
        assertEquals(Boolean.TRUE, intent.webSearch());
        assertNull(intent.rag());
        assertTrue(merged.getUseRag(), "the effective RAG default is independent from raw intent");

        ChatRequestDto capped = merged.toBuilder()
                .useWebSearch(false)
                .useRag(false)
                .build();

        assertEquals(intent, capped.getRetrievalRequestIntent());
        assertFalse(capped.getUseWebSearch());
        assertFalse(capped.getUseRag());
        assertFalse(objectMapper.writeValueAsString(capped).contains("retrievalRequestIntent"));
    }

    @Test
    void retrievalIntentDistinguishesExplicitOffFromOmittedFlags() {
        ChatRequestDto omitted = ChatRequestSettingsMerger.merge(
                ChatRequestDto.builder().message("omitted").build(),
                Map.of(),
                false,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));
        ChatRequestDto explicitOff = ChatRequestSettingsMerger.merge(
                ChatRequestDto.builder()
                        .message("off")
                        .useWebSearch(false)
                        .useRag(false)
                        .build(),
                Map.of(),
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertNull(omitted.getRetrievalRequestIntent().webSearch());
        assertNull(omitted.getRetrievalRequestIntent().rag());
        assertEquals(Boolean.FALSE, explicitOff.getRetrievalRequestIntent().webSearch());
        assertEquals(Boolean.FALSE, explicitOff.getRetrievalRequestIntent().rag());
        assertEquals(omitted.getUseWebSearch(), explicitOff.getUseWebSearch());
        assertEquals(omitted.getUseRag(), explicitOff.getUseRag());
    }

    @Test
    void directWorkflowStyleDtoRemainsCompatibleWithoutSettingsMergerCarrier() {
        ChatRequestDto direct = ChatRequestDto.builder()
                .message("direct workflow call")
                .useWebSearch(true)
                .useRag(false)
                .build();

        assertNull(direct.getRetrievalRequestIntent());
        assertTrue(direct.getUseWebSearch());
        assertFalse(direct.getUseRag());

        ChatRequestDto rewritten = direct.toBuilder()
                .useWebSearch(false)
                .build();

        assertNull(rewritten.getRetrievalRequestIntent());
        assertFalse(rewritten.getUseWebSearch());
        assertFalse(rewritten.getUseRag());
    }

    @Test
    void clientJsonCannotInjectTheServerOnlyRetrievalIntentCarrier() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        ChatRequestDto parsed = objectMapper.readValue(
                "{\"message\":\"hello\",\"retrievalRequestIntent\":{\"webSearch\":true,\"rag\":true}}",
                ChatRequestDto.class);

        assertNull(parsed.getRetrievalRequestIntent());
        assertFalse(List.of(ChatRequestDto.class.getMethods()).stream()
                .anyMatch(method -> method.getName().equals("setRetrievalRequestIntent")));
    }

    @Test
    void publicChatRouteDoesNotDefaultRagBeforeTheSettingsMergerCapturesIntent() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("req.setUseRag(defaultUseRag)"));
    }

    @Test
    void explicitUiValuesOverrideSettingsAndWebSearchExplicitForcesEnabled() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .message("hello")
                .model("ui-model")
                .temperature(0.3)
                .topP(0.7)
                .frequencyPenalty(0.0)
                .presencePenalty(0.0)
                .useRag(false)
                .useWebSearch(true)
                .build();
        Map<String, String> settings = new HashMap<>();
        settings.put(SettingsService.KEY_OPENAI_MODEL, "settings-model");
        settings.put("chat.defaults.useWebSearch", "false");

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                settings,
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals("ui-model", merged.getModel());
        assertEquals(0.3, merged.getTemperature());
        assertFalse(merged.getUseRag());
        assertTrue(merged.getUseWebSearch());
    }

    @Test
    void explicitMaxTokensSurvivesSettingsMerge() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .message("hello")
                .maxTokens(160)
                .build();

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                Map.of(),
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals(160, merged.getMaxTokens());
    }

    @Test
    void settingsMergePreservesEveryPublicPayloadFieldItDoesNotOverride() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .message("hello")
                .systemPrompt("system")
                .traits(List.of("precise"))
                .inputType("voice")
                .maxMemoryTokens(321)
                .maxRagTokens(654)
                .searchQueries(3)
                .searchScopes(List.of("web", "documents"))
                .imageBase64("QUJDRA==")
                .guardLevel("strict")
                .profile("analyst")
                .build();

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                Map.of(SettingsService.KEY_OPENAI_MODEL, "server-model"),
                false,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals(ui.getSystemPrompt(), merged.getSystemPrompt());
        assertEquals(ui.getTraits(), merged.getTraits());
        assertEquals(ui.getInputType(), merged.getInputType());
        assertEquals(ui.getMaxMemoryTokens(), merged.getMaxMemoryTokens());
        assertEquals(ui.getMaxRagTokens(), merged.getMaxRagTokens());
        assertEquals(ui.getSearchQueries(), merged.getSearchQueries());
        assertEquals(ui.getSearchScopes(), merged.getSearchScopes());
        assertEquals(ui.getImageBase64(), merged.getImageBase64());
        assertEquals(ui.getGuardLevel(), merged.getGuardLevel());
        assertEquals(ui.getProfile(), merged.getProfile());
        assertEquals("server-model", merged.getModel());
    }

    @Test
    void explicitFalseWebSearchSurvivesSettingsDefaultAndExplicitFlag() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .message("hello")
                .useRag(true)
                .useWebSearch(null)
                .build();
        ui.setUseWebSearch(false);
        Map<String, String> settings = new HashMap<>();
        settings.put("chat.defaults.useWebSearch", "true");

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                settings,
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertTrue(ui.isWebSearchExplicit());
        assertFalse(merged.getUseWebSearch());
    }

    @Test
    void finalAnswerTemperatureFallbackIsConservative() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .message("hello")
                .temperature(null)
                .topP(null)
                .frequencyPenalty(null)
                .presencePenalty(null)
                .model("ui-model")
                .build();

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                Map.of(),
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals(0.3, merged.getTemperature());
    }

    @Test
    void settingsServiceDefaultTemperatureFallsBackThroughChatTemperatureOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/SettingsService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains(
                "@Value(\"${openai.api.temperature.default:${llm.chat.temperature:0.3}}\")"));
        assertFalse(source.contains("@Value(\"${openai.api.temperature.default:0.7}\")"));
    }

    @Test
    void finalAnswerRuntimeTemperatureDefaultsDoNotFallBackToLegacyHighTemperature() throws Exception {
        for (Path path : List.of(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                Path.of("main/java/com/example/lms/service/ChatOrchestrator.java"),
                Path.of("main/java/com/example/lms/service/legacy/ChatServiceLegacy.java"),
                Path.of("main/java/com/example/lms/service/patch/ChatServiceLegacyPatch.java"),
                Path.of("main/java/com/example/lms/service/patch/ChatOrchestratorPatch.java"))) {
            String source = Files.readString(path, StandardCharsets.UTF_8);

            assertTrue(source.contains("${openai.api.temperature.default:${llm.chat.temperature:0.3}}"), path::toString);
            assertFalse(source.contains("${openai.api.temperature.default:0.7}"), path::toString);
        }
    }

    @Test
    void directLegacyFinalAnswerEntrypointsDoNotHardcodeHighTemperature() throws Exception {
        String gptService = Files.readString(
                Path.of("main/java/com/example/lms/service/GPTService.java"),
                StandardCharsets.UTF_8);
        String legacyRouter = Files.readString(
                Path.of("main/java/com/example/lms/service/routing/ModelRouterLegacy2.java"),
                StandardCharsets.UTF_8);

        assertFalse(gptService.contains("body.put(\"temperature\", 0.7);"));
        assertFalse(legacyRouter.contains("factory.lc(modelName, 0.7, 1.0, null)"));
        assertTrue(gptService.contains("${openai.api.temperature.default:${llm.chat.temperature:0.3}}"));
        assertTrue(legacyRouter.contains("${llm.chat.temperature:0.3}"));
    }

    @Test
    void conservativeFinalAnswerTemperatureDoesNotClampExplorationGuardrails() throws Exception {
        String sseEvolver = Files.readString(
                Path.of("main/java/com/example/lms/artplate/sse/StochasticTransformerEvolver.java"),
                StandardCharsets.UTF_8);
        String artPlateEvolver = Files.readString(
                Path.of("main/java/com/example/lms/artplate/ArtPlateEvolver.java"),
                StandardCharsets.UTF_8);
        String cfvmTuner = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmKAllocationTuner.java"),
                StandardCharsets.UTF_8);
        String selfAskPlanner = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/SelfAskPlanner.java"),
                StandardCharsets.UTF_8);
        String zero100Aspect = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(sseEvolver.contains("TraceStore.put(\"sse.bypassReason\", \"max_reset_exceeded\")"));
        assertTrue(sseEvolver.contains("TraceStore.put(\"sse.resetCount\", state.consecutivePenalty())"));
        assertTrue(sseEvolver.contains("clampInt(base.webTopK() + intDelta(signedStep, 1), 1, 16)"));
        assertTrue(sseEvolver.contains("clampInt(base.vecTopK() + intDelta(signedStep, 2), 1, 32)"));
        assertTrue(artPlateEvolver.contains("Objects.equals(base.domainAllow(), mutated.domainAllow())"));
        assertTrue(artPlateEvolver.contains("Objects.equals(base.modelCandidates(), mutated.modelCandidates())"));
        assertTrue(artPlateEvolver.contains("TraceStore.put(\"sse.bypassReason\", \"guard_rejected_candidate\")"));

        assertTrue(cfvmTuner.contains("TraceStore.put(\"cfvm.kalloc.boltzmann.temperature\", temperature)"));
        assertTrue(cfvmTuner.contains("TraceStore.put(\"cfvm.kalloc.epsilon\", epsilon)"));
        assertTrue(cfvmTuner.contains("TraceStore.put(\"cfvm.kalloc.chosenArm\""));

        assertTrue(selfAskPlanner.contains("TraceStore.put(\"selfask.regenerate.reason\""));
        assertTrue(selfAskPlanner.contains("TraceStore.put(\"selfask.regenerate.maxAttempts\""));
        assertTrue(zero100Aspect.contains("TraceStore.put(\"zero100.explorationRate\", slice.getExplorationRate())"));
    }
}
