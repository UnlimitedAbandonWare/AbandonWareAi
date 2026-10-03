package com.example.lms.service.routing;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.llm.*;
import com.example.lms.llm.spec.*;
import com.example.lms.prompt.pose.ModelLoadoutResolver;
import com.example.lms.routing.RoutingProfile.Role;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatWorkflow;
import com.example.lms.service.guard.GuardContextHolder;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real workflow/router path with the existing message-role fixture and stub providers. */
class ModelLoadoutRoutingTest {
    @AfterEach void cleanup() { GuardContextHolder.clear(); TimeBudgetContext.clear(); TraceStore.clear(); }
    private record Fixture(ChatWorkflow workflow, PolicyBasedModelRouter router, ChatModel model,
                           ModelSpecRegistry specs, ModelSpecSnapshot snapshot, List<List<ChatMessage>> calls) { }
    private static void fieldIfPresent(Object target, String name, Object value) {
        if (org.springframework.util.ReflectionUtils.findField(target.getClass(), name) != null)
            ReflectionTestUtils.setField(target, name, value);
    }
    private ModelRoleProfile profile(int context, ModelCapabilities.Support json) {
        return new ModelRoleProfile(new ModelRoleProfile.ModelKey("fixture", "fixture-model", "snapshot", "responses", "adapter-fixture"),
                null, Set.of(Role.MAIN_DEFAULT, Role.MAIN_FAST, Role.MAIN_HIGH),
                new ModelCapabilities.Profile(ModelCapabilities.Support.NO, json, ModelCapabilities.Support.NO,
                        context, 1024, Set.of("text"), Set.of(), Set.of("responses")), "fixture", null, null, Map.of());
    }
    private Fixture fixture(boolean enabled, ModelRoleProfile profile) throws Exception {
        cleanup();
        List<List<ChatMessage>> calls = new ArrayList<>();
        // Reuse the existing service fixture, including its retrieval, attribution and cleanup contract.
        Class<?> existing = Class.forName("com.example.lms.service.ChatWorkflowPromptMessageRoleTest");
        Method create = existing.getDeclaredMethod("fixture", List.class); create.setAccessible(true);
        Object setup = create.invoke(null, calls);
        Method accessor = setup.getClass().getDeclaredMethod("workflow"); accessor.setAccessible(true);
        ChatWorkflow workflow = (ChatWorkflow) accessor.invoke(setup);
        NamedChatModel model = mock(NamedChatModel.class);
        when(model.resolvedModelName()).thenReturn("fixture-model");
        // Stub construction evidence independently of the supplied capability profile.
        registerRuntimeIdentity(model);
        when(model.chat(anyList())).thenAnswer(call -> {
            calls.add(List.copyOf(call.getArgument(0)));
            return ChatResponse.builder().aiMessage(AiMessage.from("unsupported draft")).build();
        });
        DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
        when(factory.canServe(anyString())).thenReturn(true);
        when(factory.lcWithTimeout(anyString(), anyDouble(), isNull(), isNull(), isNull(), anyInt(), anyInt(), eq(0))).thenReturn(model);
        PolicyBasedModelRouter router = new PolicyBasedModelRouter(model, null, null, null, factory);
        ReflectionTestUtils.setField(router, "requestedModelTimeoutSeconds", 2);
        ReflectionTestUtils.setField(router, "timeoutSeconds", 2);
        ModelSpecRegistry specs = mock(ModelSpecRegistry.class);
        ModelSpecSnapshot snapshot = mock(ModelSpecSnapshot.class);
        when(snapshot.model()).thenReturn("fixture-model"); when(snapshot.provider()).thenReturn("fixture");
        when(snapshot.observedAt()).thenReturn(java.time.Instant.now());
        when(snapshot.metadata()).thenReturn(Map.of("verifiedEndpointIdentityHash", "fixture-origin-hash"));
        when(snapshot.contextTokens()).thenReturn(profile == null ? null : profile.capabilities().contextTokens());
        when(snapshot.roleProfile()).thenReturn(profile);
        when(specs.snapshots()).thenReturn(List.of(snapshot));
        fieldIfPresent(router, "loadoutEnabled", enabled);
        fieldIfPresent(router, "loadoutResolver", new ModelLoadoutResolver(enabled, ModelLoadoutResolver.initialSkills()));
        fieldIfPresent(router, "loadoutSpecs", specs);
        ReflectionTestUtils.setField(workflow, "modelRouter", router);
        ReflectionTestUtils.setField(workflow, "focusModelSpecs", specs);
        fieldIfPresent(workflow, "loadoutEnabled", enabled);
        return new Fixture(workflow, router, model, specs, snapshot, calls);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerRuntimeIdentity(ChatModel model) throws Exception {
        try {
            Class<?> type = Class.forName("com.example.lms.llm.DynamicChatModelFactory$ConfiguredModelIdentity");
            Object identity = type.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class)
                    .newInstance("fixture-model", "fixture", "responses", "adapter-fixture", "fixture-origin-hash");
            var map = (Map) ReflectionTestUtils.getField(DynamicChatModelFactory.class, "CONFIGURED_MODEL_IDS");
            map.put(model, identity);
        } catch (ClassNotFoundException beforeImplementation) { /* semantic RED remains runnable */ }
    }
    @Test void opaqueRuntimeCannotUseAnOtherwiseVerifiedProfile() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES));
        ((Map<?, ?>) ReflectionTestUtils.getField(DynamicChatModelFactory.class, "CONFIGURED_MODEL_IDS")).remove(f.model());
        run(f, "근거 자료를 요약해 주세요");
        assertFalse(systems(f).contains("### LOADOUT"));
        assertEquals(Boolean.FALSE, TraceStore.get("prompt.loadout.applied"));
    }
    @Test void differentEndpointProfileCannotAttestTheRuntimeClient() throws Exception {
        var original = profile(32000, ModelCapabilities.Support.YES);
        var different = new ModelRoleProfile(new ModelRoleProfile.ModelKey("fixture", "fixture-model", "snapshot", "openai_chat_completions", "adapter-fixture"),
                null, original.supportedRoles(), new ModelCapabilities.Profile(ModelCapabilities.Support.NO, ModelCapabilities.Support.YES,
                ModelCapabilities.Support.NO, 32000, 1024, Set.of("text"), Set.of(), Set.of("openai_chat_completions")), "fixture", null, null, Map.of());
        var f = fixture(true, different); run(f, "근거 자료를 요약해 주세요");
        assertFalse(systems(f).contains("### LOADOUT"));
        assertEquals(Boolean.FALSE, TraceStore.get("prompt.loadout.applied"));
    }
    @Test void differentAdapterVersionCannotAttestTheRuntimeClient() throws Exception {
        var original = profile(32000, ModelCapabilities.Support.YES);
        var different = new ModelRoleProfile(new ModelRoleProfile.ModelKey("fixture", "fixture-model", "snapshot", "responses", "different-adapter"),
                null, original.supportedRoles(), original.capabilities(), "fixture", null, null, Map.of());
        var f = fixture(true, different); run(f, "근거 자료를 요약해 주세요");
        assertFalse(systems(f).contains("### LOADOUT"));
        assertEquals(Boolean.FALSE, TraceStore.get("prompt.loadout.applied"));
    }
    private ChatRequestDto request(String query) {
        return ChatRequestDto.builder().message(query).model("fixture-model")
                .maxTokens(256).mode("FACT").memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                .useWebSearch(true).useRag(false).useVerification(true)
                .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, false)).build();
    }
    private com.example.lms.service.ChatResult run(Fixture f, String query) {
        return f.workflow().continueChat(request(query), ignored -> List.of());
    }
    private String systems(Fixture f) {
        assertEquals(1, f.calls().size(), "exactly one primary generation");
        return f.calls().get(0).stream().filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                .map(SystemMessage::text).collect(java.util.stream.Collectors.joining("\n"));
    }
    @Test void mainEquipmentUsesTheRealServiceAndAddsNoGeneration() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES));
        assertNotNull(run(f, "근거 자료를 요약해 주세요"));
        String text = systems(f); assertTrue(text.contains("LOADOUT evidence-summary@1"),
                () -> String.valueOf(TraceStore.get("prompt.loadout.reasons")));
        assertEquals(1, text.split("LOADOUT evidence-summary@1", -1).length - 1);
        assertEquals(Boolean.TRUE, TraceStore.get("prompt.loadout.applied"));
        assertEquals("fixture-model", RequestedModelSelection.mainDecision().selectedKey());
        assertEquals("CURRENT", TraceStore.get("prompt.loadout.retrieval"));
    }
    @Test void offDoesNotLookupProfilesOrAddInstructions() throws Exception {
        var f = fixture(false, profile(32000, ModelCapabilities.Support.YES)); run(f, "근거 자료를 요약해 주세요");
        assertFalse(systems(f).contains("### LOADOUT"));
        assertFalse(TraceStore.getAll().keySet().stream().anyMatch(k -> k.startsWith("prompt.loadout.")));
        // The legacy workflow already reads cached context caps; OFF must avoid the new profile lookup.
        verify(f.snapshot(), never()).roleProfile();
    }
    @Test void unknownProfilePreservesTheServiceBaseline() throws Exception {
        var f = fixture(true, null); run(f, "근거 자료를 요약해 주세요");
        assertFalse(systems(f).contains("### LOADOUT"));
        assertEquals(Boolean.FALSE, TraceStore.get("prompt.loadout.applied"));
        assertNotNull(TraceStore.get("prompt.loadout.reasons"));
    }
    @Test void fullPromptBudgetPreventsEquipment() throws Exception {
        var f = fixture(true, profile(512, ModelCapabilities.Support.YES)); run(f, "근거 자료를 요약해 주세요");
        assertFalse(systems(f).contains("### LOADOUT")); assertEquals(Boolean.FALSE, TraceStore.get("prompt.loadout.applied"));
        assertTrue(((Number)TraceStore.get("prompt.loadout.inputTokens")).longValue() > 0);
    }
    @Test void explicitContradictionTriggerEquipsVerifierInstructions() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES)); run(f, "근거의 모순을 검증해 주세요");
        assertTrue(systems(f).contains("LOADOUT contradiction-check@1"));
    }
    @Test void noVerifierTriggerDoesNotEquipVerifier() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES)); run(f, "근거 자료를 요약해 주세요");
        assertFalse(systems(f).contains("LOADOUT contradiction-check@1"));
        assertNotNull(TraceStore.get("prompt.loadout.role"));
    }
    @Test void missingSchemaCapabilityDoesNotEquipVerifier() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.UNKNOWN)); run(f, "근거의 모순을 검증해 주세요");
        assertFalse(systems(f).contains("LOADOUT contradiction-check@1"));
        assertEquals(1, f.calls().size());
    }
    @Test void newRequestCannotReusePriorLoadout() throws Exception {
        var first = fixture(true, profile(32000, ModelCapabilities.Support.YES)); run(first, "근거의 모순을 검증해 주세요");
        assertTrue(systems(first).contains("LOADOUT contradiction-check@1"));
        var second = fixture(true, null); run(second, "근거 자료를 요약해 주세요");
        assertFalse(systems(second).contains("### LOADOUT"));
    }
    @Test void generationWithoutObservationIsNotReportedAsObservedModel() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES)); run(f, "근거 자료를 요약해 주세요");
        systems(f); assertEquals("fixture-model", RequestedModelSelection.mainDecision().selectedKey());
        assertFalse(TraceStore.getAll().keySet().stream().anyMatch(k -> k.startsWith("prompt.loadout.executed")));
        assertTrue(TraceStore.get("prompt.loadout.skills") instanceof List<?>);
        assertEquals("NO_OBSERVATION", TraceStore.get("prompt.loadout.observation"));
    }
    private com.example.lms.service.chat.ChatRunRegistry registry() {
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        return registry;
    }
    @Test void cancelledRunCannotReachEquipmentOrStartGeneration() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES));
        var registry = registry(); var run = registry.beginOrJoin(9001L).context();
        try {
            assertTrue(registry.cancelExact(9001L, run.clientToken()));
            try (var scope = com.example.lms.service.chat.ChatRunExecutionContext.bind(run)) {
                assertThrows(java.util.concurrent.CancellationException.class, () -> run(f, "근거 자료를 요약해 주세요"));
            }
            assertEquals(0, f.calls().size());
            assertNull(TraceStore.get("prompt.loadout.applied"));
            assertEquals(com.example.lms.service.chat.ChatRunRegistry.Status.CANCELLED,
                    registry.describeExact(9001L, run.clientToken()).orElseThrow().status());
        } finally { ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
    }
    @Test void cancellationDuringPrimaryDoesNotStartVerifierOrFallback() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES));
        var registry = registry(); var run = registry.beginOrJoin(9002L).context();
        doAnswer(call -> {
            f.calls().add(List.copyOf(call.getArgument(0)));
            assertTrue(registry.cancelExact(9002L, run.clientToken()));
            return ChatResponse.builder().aiMessage(AiMessage.from("late fixture response")).build();
        }).when(f.model()).chat(anyList());
        try {
            try (var scope = com.example.lms.service.chat.ChatRunExecutionContext.bind(run)) {
                assertEquals("cancelled", run(f, "근거의 모순을 검증해 주세요").modelUsed());
            }
            assertEquals(1, f.calls().size());
            assertFalse(registry.markDone(run));
            assertEquals(com.example.lms.service.chat.ChatRunRegistry.Status.CANCELLED,
                    registry.describeExact(9002L, run.clientToken()).orElseThrow().status());
        } finally { ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
    }
    @Test void nextOwnerOnSameThreadDoesNotInheritPreviousEquipment() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES));
        var a = com.example.lms.service.AttachmentOwnerIdentity.forAnonymous("loadout-owner-a");
        var b = com.example.lms.service.AttachmentOwnerIdentity.forAnonymous("loadout-owner-b");
        var first = request("근거의 모순을 검증해 주세요"); first.bindVerifiedRequestOwner(a);
        f.workflow().continueChat(first, ignored -> List.of());
        assertEquals(a.hash(), RequestedModelSelection.ownerHash());
        assertTrue(systems(f).contains("LOADOUT contradiction-check@1"));
        when(f.snapshot().roleProfile()).thenReturn(null);
        var second = request("근거 자료를 요약해 주세요"); second.bindVerifiedRequestOwner(b);
        f.workflow().continueChat(second, ignored -> List.of());
        assertEquals(2, f.calls().size()); assertEquals(b.hash(), RequestedModelSelection.ownerHash());
        assertFalse(f.calls().get(1).toString().contains("### LOADOUT"));
        assertEquals(Boolean.FALSE, TraceStore.get("prompt.loadout.applied"));
        assertFalse(TraceStore.getAll().values().contains(a.hash()));
    }
    @Test void opaqueGatewayFallbackReceivesBaselineMessagesAndNoPrimaryEquipment() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES));
        List<String> order = new ArrayList<>();
        when(f.model().chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenAnswer(call -> {
            order.add("primary"); f.calls().add(List.copyOf(((dev.langchain4j.model.chat.request.ChatRequest)call.getArgument(0)).messages()));
            throw new com.example.lms.llm.gateway.LlmGatewayException("synthetic rejection",
                    com.example.lms.llm.gateway.LlmFailureClass.RATE_LIMIT_COOLDOWN, "local_backend_busy");
        });
        ChatModel fallback = mock(ChatModel.class);
        when(fallback.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenAnswer(call -> {
            order.add("fallback"); f.calls().add(List.copyOf(((dev.langchain4j.model.chat.request.ChatRequest)call.getArgument(0)).messages()));
            return ChatResponse.builder().aiMessage(AiMessage.from("fallback fixture response")).build();
        });
        var gateway = new com.example.lms.llm.gateway.FallbackAwareChatModel(f.model(), () -> fallback, null, null, "fixture-model", "fallback-fixture");
        ReflectionTestUtils.setField(f.router(), "defaultModel", gateway);
        assertNotNull(run(f, "근거의 모순을 검증해 주세요"));
        assertEquals(List.of("primary", "fallback"), order);
        assertEquals(2, f.calls().size());
        assertTrue(f.calls().stream().noneMatch(messages -> messages.toString().contains("### LOADOUT")));
        assertEquals(Boolean.FALSE, TraceStore.get("prompt.loadout.applied"));
    }
    @Test void duplicateControllerRequestStartsOneGenerationAndFinalizesOnce() throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES));
        Class<?> type = Class.forName("com.example.lms.api.ChatApiControllerSyncLifecycleTest$Fixture");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object harness = constructor.newInstance();
        Method send = type.getDeclaredMethod("request", String.class); send.setAccessible(true);
        var chat = (com.example.lms.service.ChatService) ReflectionTestUtils.getField(harness, "chat");
        var registry = (com.example.lms.service.chat.ChatRunRegistry) ReflectionTestUtils.getField(harness, "registry");
        var history = (com.example.lms.service.ChatHistoryService) ReflectionTestUtils.getField(harness, "history");
        doAnswer(invocation -> {
            ChatRequestDto dto = invocation.getArgument(0);
            dto.setMessage("근거 자료를 요약해 주세요"); dto.setModel("fixture-model"); dto.setMaxTokens(256);
            dto.setUseWebSearch(true); dto.setUseRag(false); dto.setUseVerification(true);
            dto.setMemoryMode("EPHEMERAL"); dto.setMode("FACT"); dto.setSearchMode(SearchMode.AUTO);
            ReflectionTestUtils.setField(dto, "retrievalRequestIntent", new ChatRequestDto.RetrievalRequestIntent(true, false));
            return f.workflow().continueChat(dto, ignored -> List.of());
        }).when(chat).continueChat(any(ChatRequestDto.class), any());
        doAnswer(call -> {
            f.calls().add(List.copyOf(call.getArgument(0)));
            var joined = (org.springframework.http.ResponseEntity<?>)send.invoke(harness, "sync");
            assertEquals(org.springframework.http.HttpStatus.CONFLICT, joined.getStatusCode());
            return ChatResponse.builder().aiMessage(AiMessage.from("single fixture response")).build();
        }).when(f.model()).chat(anyList());
        try {
            var response = (org.springframework.http.ResponseEntity<?>)send.invoke(harness, "sync");
            assertEquals(org.springframework.http.HttpStatus.OK, response.getStatusCode());
            assertEquals(1, f.calls().size());
            verify(chat, times(1)).continueChat(any(ChatRequestDto.class), any());
            verify(registry, times(1)).markDone(any());
            verify(history, times(1)).appendMessageReturningId(eq(42L), eq("assistant"), anyString());
            assertFalse(registry.isRunning(42L));
            assertNull(com.example.lms.service.chat.ChatRunExecutionContext.current());
        } finally { ((AutoCloseable)harness).close(); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"FACT", "REWRITE"})
    void sharedFastAndHighClientKeepsTheActuallyChosenRole(String intent) throws Exception {
        var f = fixture(true, profile(32000, ModelCapabilities.Support.YES));
        NamedChatModel other = mock(NamedChatModel.class);
        when(other.resolvedModelName()).thenReturn("other-fixture-model");
        var policy = mock(RouterPolicy.class); when(policy.shouldPromote(any())).thenReturn(true);
        ReflectionTestUtils.setField(f.router(), "policy", policy);
        ReflectionTestUtils.setField(f.router(), "defaultModel", other);
        ReflectionTestUtils.setField(f.router(), "fastModel", f.model());
        ReflectionTestUtils.setField(f.router(), "highModel", f.model());
        var preprocessor = (com.example.lms.service.rag.pre.QueryContextPreprocessor)ReflectionTestUtils.getField(f.workflow(), "qcPreprocessor");
        when(preprocessor.inferIntent(anyString())).thenReturn(intent);
        f.workflow().continueChat(request("근거 자료를 요약해 주세요").toBuilder().model("").build(), ignored -> List.of());
        systems(f);
        assertEquals("REWRITE".equals(intent) ? Role.MAIN_FAST : Role.MAIN_HIGH, RequestedModelSelection.mainRole());
        assertEquals("fixture-model", RequestedModelSelection.mainDecision().selectedKey());
    }
}
