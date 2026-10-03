package com.example.lms.llm;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.api.ChatRequestSettingsMerger;
import com.example.lms.config.ChatDefaultsProperties;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.guard.KeyResolver;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Captures the real SDK's serialized HTTP body without sending any network request. */
class OpenAiSamplingSdkBodyContractTest {
    private static final String OFFICIAL = "https://api.openai.com/v1";

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void mergerDeferralUsesOnlyExactExistingContractModels() {
        for (String model : List.of("gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-5.1", "gpt-5.2")) {
            assertTrue(OpenAiSamplingContract.defersMergerClamp(model), model);
        }
        for (String model : List.of("gpt-5.1-2025-11-13", "gpt-5.2-pro", "gpt-5.2-chat-latest",
                "gpt-5-codex", "gpt-5.5", "gpt-4o", "GPT-5.1", "")) {
            assertFalse(OpenAiSamplingContract.defersMergerClamp(model), model);
        }
        assertFalse(OpenAiSamplingContract.defersMergerClamp(null));
    }

    @TestFactory
    List<DynamicTest> mergerSamplingReachesFactoryWireContract() {
        List<DynamicTest> cases = new ArrayList<>();
        for (String model : List.of("gpt-5.1", "gpt-5.2", "gpt-5")) {
            cases.add(DynamicTest.dynamicTest("merger/official/" + model, () -> {
                ChatRequestDto merged = mergeSampling(ChatRequestDto.builder().model(model)
                        .temperature(0.37).topP(0.83).build());
                Capture capture = new Capture();
                factory(capture, OFFICIAL, model, merged.getTemperature(), merged.getTopP())
                        .chat(List.of(UserMessage.from("synthetic fixture")));
                JsonNode body = capture.body();
                if (model.equals("gpt-5")) {
                    assertFalse(body.has("temperature"));
                    assertFalse(body.has("top_p"));
                } else {
                    assertAll(() -> assertEquals(0.37, merged.getTemperature()),
                            () -> assertEquals(0.83, merged.getTopP()),
                            () -> assertEquals(0.37, body.path("temperature").asDouble()),
                            () -> assertEquals(0.83, body.path("top_p").asDouble()));
                }
                assertEquals(model, body.path("model").asText());
                assertEquals(OFFICIAL + "/chat/completions", capture.url.get());
                assertEquals(1, capture.calls.get());
            }));
        }
        cases.add(DynamicTest.dynamicTest("merger/legacy/gpt-5.1", () -> {
            ChatRequestDto merged = mergeSampling(ChatRequestDto.builder().model("gpt-5.1")
                    .temperature(0.37).topP(0.83).build());
            Capture capture = new Capture();
            factory(capture, "https://gateway.example/v1", merged.getModel(),
                    merged.getTemperature(), merged.getTopP()).chat(List.of(UserMessage.from("synthetic fixture")));
            assertEquals(1.0, capture.body().path("temperature").asDouble());
            assertEquals(1.0, capture.body().path("top_p").asDouble());
            assertEquals(1, capture.calls.get());
        }));
        return cases;
    }

    @TestFactory
    List<DynamicTest> unspecifiedMergerSamplingKeepsLegacyDefaultsOnWire() {
        List<DynamicTest> cases = new ArrayList<>();
        for (String model : List.of("gpt-5.1", "gpt-5.2")) {
            for (boolean resolve : List.of(false, true)) {
                cases.add(DynamicTest.dynamicTest("merger/default/" + model + "/resolve=" + resolve, () -> {
                    ChatRequestDto request = ChatRequestDto.builder().model(model).build();
                    ChatRequestDto merged = resolve
                            ? ChatRequestSettingsMerger.resolve(request, Map.of(), Map.of(), samplingDefaults(model),
                                    org.slf4j.LoggerFactory.getLogger(getClass())).request()
                            : mergeSampling(request);
                    assertEquals(1.0, merged.getTemperature());
                    assertEquals(1.0, merged.getTopP());
                    Capture capture = new Capture();
                    factory(capture, OFFICIAL, model, merged.getTemperature(), merged.getTopP())
                            .chat(List.of(UserMessage.from("synthetic fixture")));
                    assertEquals(1.0, capture.body().path("temperature").asDouble());
                    assertEquals(1.0, capture.body().path("top_p").asDouble());
                    assertEquals(1, capture.calls.get());
                }));
            }
        }
        return cases;
    }

    @TestFactory
    List<DynamicTest> resolvedExplicitSamplingSourcesReachWireUnchanged() {
        List<DynamicTest> cases = new ArrayList<>();
        for (String source : List.of("REQUEST", "SESSION", "USER", "ADMIN_DB")) {
            for (String model : List.of("gpt-5.1", "gpt-5.2")) {
                cases.add(DynamicTest.dynamicTest("merger/resolve/" + source + "/" + model, () -> {
                    Map<String, Object> values = Map.of("temperature", 0.37, "topP", 0.83);
                    ChatRequestDto request = ChatRequestDto.builder().model(model).build();
                    if (source.equals("REQUEST") || source.equals("SESSION")) {
                        request.setTemperature(0.37);
                        request.setTopP(0.83);
                        if (source.equals("SESSION")) request.bindChatSettingsSnapshot(
                                new ChatRequestDto.ChatSettingsSnapshot(Map.of(), Map.of(), Map.of()));
                    }
                    var resolved = ChatRequestSettingsMerger.resolve(request,
                            source.equals("USER") ? values : Map.of(),
                            source.equals("ADMIN_DB") ? values : Map.of(), samplingDefaults(model),
                            org.slf4j.LoggerFactory.getLogger(getClass()));
                    assertEquals(source, resolved.sources().get("temperature"));
                    assertEquals(source, resolved.sources().get("topP"));
                    Capture capture = new Capture();
                    factory(capture, OFFICIAL, model, resolved.request().getTemperature(), resolved.request().getTopP())
                            .chat(List.of(UserMessage.from("synthetic fixture")));
                    assertEquals(0.37, capture.body().path("temperature").asDouble());
                    assertEquals(0.83, capture.body().path("top_p").asDouble());
                    assertEquals(1, capture.calls.get());
                }));
            }
        }
        return cases;
    }

    private static ChatRequestDto mergeSampling(ChatRequestDto request) throws Exception {
        var merge = ChatRequestSettingsMerger.class.getDeclaredMethod("merge", ChatRequestDto.class,
                Map.class, boolean.class, org.slf4j.Logger.class);
        merge.setAccessible(true);
        return (ChatRequestDto) merge.invoke(null, request, Map.of(), false,
                org.slf4j.LoggerFactory.getLogger(OpenAiSamplingSdkBodyContractTest.class));
    }

    private static ChatDefaultsProperties samplingDefaults(String model) {
        var defaults = new ChatDefaultsProperties();
        defaults.setModel(model);
        defaults.setModelSelectionMode("preferred");
        defaults.setTemperature(0.3);
        defaults.setTopP(1.0);
        defaults.setFrequencyPenalty(0.0);
        defaults.setPresencePenalty(0.0);
        defaults.setMaxTokens(128);
        defaults.setUseRag(false);
        defaults.setUseWebSearch(false);
        defaults.setSearchMode("OFF");
        defaults.setRagAnswerPolicy("adaptive");
        return defaults;
    }

    @TestFactory
    List<DynamicTest> officialInitialModelsOmitSamplingAtBothBoundaries() {
        List<DynamicTest> cases = new ArrayList<>();
        for (String model : List.of("gpt-5", "gpt-5-mini", "gpt-5-nano")) {
            for (Double temperature : new Double[]{null, 1.0, 0.37}) {
                for (Double topP : new Double[]{null, 1.0, 0.83}) {
                    for (String boundary : List.of("factory", "aspect", "aspect-cue-json")) {
                        String name = boundary + "/" + model + "/temperature=" + temperature + "/top_p=" + topP;
                        cases.add(DynamicTest.dynamicTest(name, () -> {
                            Capture capture = new Capture();
                            ChatModel selected = boundary.equals("factory")
                                    ? factory(capture, OFFICIAL, model, temperature, topP)
                                    : aspect(capture, OFFICIAL, model, temperature, topP,
                                            boundary.equals("aspect-cue-json"));
                            selected.chat(List.of(UserMessage.from("synthetic fixture")));
                            JsonNode body = capture.body();
                            assertEquals(model, body.path("model").asText());
                            assertEquals(128, body.path("max_completion_tokens").asInt());
                            assertEquals(1, body.path("messages").size());
                            assertEquals(1, capture.calls.get());
                            assertEquals(OFFICIAL + "/chat/completions", capture.url.get());
                            if (boundary.equals("aspect-cue-json")) {
                                assertEquals("json_object", body.path("response_format").path("type").asText());
                                assertEquals("default", body.path("service_tier").asText());
                            }
                            assertAll(name + " wire keys=" + body.properties().stream()
                                            .map(Map.Entry::getKey).sorted().toList(),
                                    () -> assertFalse(body.has("temperature"), "temperature key must be absent"),
                                    () -> assertFalse(body.has("top_p"), "top_p key must be absent"));
                        }));
                    }
                }
            }
        }
        return cases;
    }

    static ChatModel factory(Capture capture, String base, String model, Double temperature, Double topP) {
        var env = new MockEnvironment();
        var keys = mock(KeyResolver.class);
        when(keys.resolveOpenAiApiKeyStrict()).thenReturn("synthetic-fixture");
        var factory = new DynamicChatModelFactory(env, keys, capture.tracker());
        ReflectionTestUtils.setField(factory, "openAiBaseUrl", base);
        return factory.lcWithTimeout(model, temperature, topP, null, null, 128, 5, 0);
    }

    @TestFactory
    List<DynamicTest> supportedSamplingUsesActualMergedEffortWithoutChangingIt() {
        List<DynamicTest> cases = new ArrayList<>();
        for (String model : List.of("gpt-5.1", "gpt-5.2")) {
            for (String effort : new String[]{null, "none", "low", "medium", "high"}) {
                // Public construction has no effort argument and recording wrappers flatten OA overrides.
                // Explicit effort is verified at the actual SDK creation seam; product A3 remains PARTIAL.
                for (String boundary : effort == null ? List.of("factory", "aspect", "aspect-cue-json")
                        : List.of("sdk-creation-boundary")) {
                    cases.add(DynamicTest.dynamicTest(boundary + "/" + model + "/effort=" + effort, () -> {
                        Capture capture = new Capture();
                        ChatModel selected = boundary.equals("sdk-creation-boundary")
                                ? DynamicChatModelFactory.buildOpenAiSdkModel(OpenAiChatModel.builder()
                                        .httpClientBuilder(capture.builder).baseUrl(OFFICIAL).apiKey("synthetic-fixture")
                                        .modelName(model).temperature(0.37).topP(0.83).maxCompletionTokens(128)
                                        .maxRetries(0), OFFICIAL)
                                : boundary.equals("factory")
                                ? factory(capture, OFFICIAL, model, 0.37, 0.83)
                                : aspect(capture, OFFICIAL, model, 0.37, 0.83, boundary.equals("aspect-cue-json"));
                        selected.chat(ChatRequest.builder().messages(UserMessage.from("synthetic fixture"))
                                .parameters(OpenAiChatRequestParameters.builder().reasoningEffort(effort).build()).build());
                        JsonNode body = capture.body();
                        assertEquals(model, body.path("model").asText());
                        assertEquals(1, capture.calls.get());
                        if (effort == null || "none".equals(effort)) {
                            assertEquals(0.37, body.path("temperature").asDouble());
                            assertEquals(0.83, body.path("top_p").asDouble());
                        } else {
                            assertFalse(body.has("temperature"));
                            assertFalse(body.has("top_p"));
                        }
                        if (effort == null) assertFalse(body.has("reasoning_effort"));
                        else assertEquals(effort, body.path("reasoning_effort").asText());
                    }));
                }
            }
        }
        return cases;
    }

    @TestFactory
    List<DynamicTest> commonDefaultsAndPerRequestOverridesCannotRestoreForbiddenKeys() {
        List<DynamicTest> cases = new ArrayList<>();
        for (String model : List.of("gpt-5", "gpt-5-mini", "gpt-5-nano")) {
            for (double defaultValue : new double[]{1.0, 0.37}) {
                for (boolean openAiDefaults : new boolean[]{false, true}) {
                    for (String boundary : List.of("factory", "aspect", "aspect-cue-json")) {
                        cases.add(DynamicTest.dynamicTest(boundary + "/" + model + "/common=" + defaultValue
                                + "/openAiDefaults=" + openAiDefaults, () -> {
                            Capture capture = new Capture();
                            var builder = OpenAiChatModel.builder().defaultRequestParameters(openAiDefaults
                                    ? OpenAiChatRequestParameters.builder().temperature(defaultValue).topP(defaultValue).build()
                                    : DefaultChatRequestParameters.builder().temperature(defaultValue).topP(defaultValue).build());
                            // Existing SDK builder seam injects common defaults; the product endpoint stays official.
                            try (var builders = mockStatic(OpenAiChatModel.class, CALLS_REAL_METHODS)) {
                                builders.when(OpenAiChatModel::builder).thenReturn(builder);
                                ChatModel selected = boundary.equals("factory")
                                        ? factory(capture, OFFICIAL, model, null, null)
                                        : aspect(capture, OFFICIAL, model, null, null, boundary.equals("aspect-cue-json"));
                                for (boolean override : new boolean[]{false, true}) {
                                    selected.chat(ChatRequest.builder().messages(UserMessage.from("synthetic fixture"))
                                            .parameters(DefaultChatRequestParameters.builder()
                                                    .temperature(override ? 0.37 : null).topP(override ? 0.83 : null).build())
                                            .build());
                                    JsonNode body = capture.body();
                                    assertFalse(body.has("temperature"));
                                    assertFalse(body.has("top_p"));
                                    assertEquals(model, body.path("model").asText());
                                    assertEquals(128, body.path("max_completion_tokens").asInt());
                                }
                                assertEquals(2, capture.calls.get());
                            }
                        }));
                    }
                }
            }
        }
        return cases;
    }

    @Test
    void postMergeGuardPreservesDefaultsWhenRequestChangesEffortToNone() throws Exception {
        for (String model : List.of("gpt-5.1", "gpt-5.2")) {
            Capture capture = new Capture();
            var defaults = OpenAiChatRequestParameters.builder().temperature(0.37).topP(0.83)
                    .reasoningEffort("low").serviceTier("default").maxCompletionTokens(128)
                    .seed(7).user("synthetic-user").store(false).metadata(Map.of("fixture", "value"))
                    .parallelToolCalls(false).logitBias(Map.of("42", 1))
                    .frequencyPenalty(0.12).presencePenalty(0.23).stopSequences(List.of("END"))
                    .responseFormat(dev.langchain4j.model.chat.request.ResponseFormat.JSON).build();
            var selected = DynamicChatModelFactory.buildOpenAiSdkModel(OpenAiChatModel.builder()
                    .httpClientBuilder(capture.builder).baseUrl(OFFICIAL).apiKey("synthetic-fixture")
                    .modelName(model).maxRetries(0).defaultRequestParameters(defaults), OFFICIAL);
            for (String effort : new String[]{"low", "none"}) {
                selected.chat(ChatRequest.builder().messages(UserMessage.from("synthetic fixture"))
                        .parameters(OpenAiChatRequestParameters.builder().reasoningEffort(effort).build()).build());
                var body = capture.body();
                assertEquals(effort, body.path("reasoning_effort").asText());
                if ("none".equals(effort)) {
                    assertEquals(0.37, body.path("temperature").asDouble());
                    assertEquals(0.83, body.path("top_p").asDouble());
                } else {
                    assertFalse(body.has("temperature"));
                    assertFalse(body.has("top_p"));
                }
                assertEquals(128, body.path("max_completion_tokens").asInt());
                assertEquals("default", body.path("service_tier").asText());
                assertEquals(7, body.path("seed").asInt());
                assertEquals("synthetic-user", body.path("user").asText());
                assertEquals("value", body.path("metadata").path("fixture").asText());
                assertFalse(body.path("store").asBoolean(true));
                assertFalse(body.path("parallel_tool_calls").asBoolean(true));
                assertEquals(1, body.path("logit_bias").path("42").asInt());
                assertEquals(0.12, body.path("frequency_penalty").asDouble());
                assertEquals(0.23, body.path("presence_penalty").asDouble());
                assertEquals("json_object", body.path("response_format").path("type").asText());
            }
            assertEquals(2, capture.calls.get());
        }
    }

    @TestFactory
    List<DynamicTest> customGatewaySamplingRetainsLegacyBehavior() {
        List<DynamicTest> cases = new ArrayList<>();
        for (String base : List.of("http://127.0.0.1:11434/v1", "https://gateway.example/v1")) {
            for (String model : List.of("gpt-5", "gpt-5.1", "gpt-4o")) {
                for (String boundary : List.of("factory", "aspect")) {
                    cases.add(DynamicTest.dynamicTest(boundary + "/legacy/" + model + "/" + base, () -> {
                        Capture capture = new Capture();
                        ChatModel selected = boundary.equals("factory")
                                ? factory(capture, base, model, 0.37, 0.83)
                                : aspect(capture, base, model, 0.37, 0.83, false);
                        selected.chat(List.of(UserMessage.from("synthetic fixture")));
                        var body = capture.body();
                        assertEquals(ModelCapabilities.sanitizeTemperature(model, 0.37), body.path("temperature").asDouble());
                        assertEquals(ModelCapabilities.sanitizeTopP(model, 0.83), body.path("top_p").asDouble());
                        assertEquals(base + "/chat/completions", capture.url.get());
                        assertEquals(model, body.path("model").asText());
                        assertEquals(1, capture.calls.get());
                    }));
                }
            }
        }
        return cases;
    }

    @SuppressWarnings("unchecked")
    static ChatModel aspect(Capture capture, String base, String model, Double temperature,
                            Double topP, boolean cueJson) throws Exception {
        var env = new MockEnvironment();
        var props = new LlmRouterProperties();
        var config = new LlmRouterProperties.ModelConfig();
        config.setName(model);
        config.setBaseUrl(base);
        config.setEnabled(true);
        config.setProvider("openai");
        config.setStage("chat");
        props.setModels(Map.of("primary", config));
        var guard = new NovaModelGuardProperties();
        guard.setEnabled(false);
        var keyResolver = mock(KeyResolver.class);
        when(keyResolver.resolveOpenAiApiKeyStrict()).thenReturn("synthetic-fixture");
        when(keyResolver.resolveOpenAiCredential()).thenReturn(
                new com.example.lms.guard.ProviderCredentialResolver.Resolution(
                        "openai", "synthetic-fixture", true, true, "fixture", 1, false, ""));
        ObjectProvider<KeyResolver> keys = mock(ObjectProvider.class);
        when(keys.getIfAvailable()).thenReturn(keyResolver);
        var aspect = new LlmRouterAspect(env, props, new LlmRouterBandit(props), guard,
                keys, null, null, null, capture.tracker());
        Class<?> argsType = Class.forName(LlmRouterAspect.class.getName() + "$CallArgs");
        var parse = argsType.getDeclaredMethod("parse", Object[].class);
        parse.setAccessible(true);
        Object args = parse.invoke(null, (Object) new Object[]{model, temperature, topP, null, null, 128, 5, 0});
        ReflectionTestUtils.setField(args, "cueJson", cueJson);
        var build = LlmRouterAspect.class.getDeclaredMethod("buildRoutedModel",
                LlmRouterBandit.Selected.class, argsType, boolean.class);
        build.setAccessible(true);
        return (ChatModel) build.invoke(aspect, new LlmRouterBandit.Selected("primary", config), args, false);
    }

    static final class Capture {
        final AtomicReference<String> requestBody = new AtomicReference<>();
        final AtomicReference<String> url = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final HttpClientBuilder builder;

        Capture() {
            HttpClient client = mock(HttpClient.class);
            when(client.execute(any())).thenAnswer(invocation -> {
                dev.langchain4j.http.client.HttpRequest request = invocation.getArgument(0);
                requestBody.set(request.body());
                url.set(request.url());
                calls.incrementAndGet();
                String evidencePath = System.getenv("AWX_SAMPLING_CONTRACT_EVIDENCE");
                if (evidencePath != null) {
                    var mapper = new ObjectMapper();
                    var wire = mapper.readTree(request.body());
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("model", wire.path("model").asText());
                    row.put("url", request.url());
                    for (String key : List.of("temperature", "top_p", "reasoning_effort")) {
                        row.put(key + "Present", wire.has(key));
                        if (wire.has(key)) row.put(key, wire.get(key));
                    }
                    // Sampling metadata only: never headers, credentials, messages, or response content.
                    synchronized (Capture.class) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(evidencePath),
                                mapper.writeValueAsString(row) + System.lineSeparator(),
                                java.nio.charset.StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                }
                return SuccessfulHttpResponse.builder().statusCode(200)
                        .headers(Map.of("Content-Type", List.of("application/json")))
                        .body("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fixture\"},\"finish_reason\":\"stop\"}]}")
                        .build();
            });
            builder = mock(HttpClientBuilder.class, RETURNS_SELF);
            when(builder.build()).thenReturn(client);
        }

        ModelRuntimeHealthTracker tracker() {
            var tracker = spy(new ModelRuntimeHealthTracker());
            doReturn(builder).when(tracker).observedHttpClientBuilder(anyString());
            return tracker;
        }

        JsonNode body() throws Exception {
            assertNotNull(requestBody.get(), "SDK must execute an HTTP request");
            return new ObjectMapper().readTree(requestBody.get());
        }
    }
}
