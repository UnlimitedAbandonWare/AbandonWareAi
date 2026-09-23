package com.example.lms.service.rag;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskPlannerTest {

    @Test
    void fallbackLogsDoNotWriteRawModelIdentifiers() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/SelfAskPlanner.java"));

        assertFalse(source.contains("lane={} model={} fallbackReason={}"));
        assertFalse(source.contains("local RC fallback model={} failed: {}"));
        assertFalse(source.contains("lane, modelId, reason"));
        assertFalse(source.contains("modelId, safeReason(e)"));
        assertTrue(source.contains("modelHash={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(modelId)"));
    }

    @Test
    void apiDisabledReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/SelfAskPlanner.java"));

        assertFalse(source.contains("TraceStore.put(\"selfask.3way.api.disabledReason\", disabledReason);"));
        assertTrue(source.contains(
                "TraceStore.put(\"selfask.3way.api.disabledReason\", SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\"));"));
    }

    @Test
    void telemetrySkippedStageUsesSafeTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/SelfAskPlanner.java"));
        int start = source.indexOf("private static void traceSelfAskSkipped");
        int end = source.indexOf("private static String errorType", start);
        String method = source.substring(start, end);

        assertFalse(method.contains("stage, errorType(error)"));
        assertTrue(method.contains("String safeStage = SafeRedactor.traceLabelOrFallback(stage, \"unknown\");"));
        assertTrue(method.contains("log.debug(\"[SelfAskPlanner] telemetry skipped stage={} errorType={}\", safeStage, errorType(error));"));
    }

    @Test
    void selfAskPlannerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/SelfAskPlanner.java"));

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "SelfAskPlanner trace fail-soft paths need fixed-stage breadcrumbs instead of exact empty catches");
        assertSelfAskStage(source, "plan");
        assertSelfAskStage(source, "tracePlannerSetup");
        assertSelfAskStage(source, "generateThreeLanes.lane");
        assertSelfAskStage(source, "traceRequeryConfirmed");
        assertSelfAskStage(source, "regenerateLane");
        assertSelfAskStage(source, "localCounterFallback");
        assertSelfAskStage(source, "zero100LaneTimeboxMs");
        assertSelfAskInvalidNumberStage(source, "parseLong");
        assertSelfAskStage(source, "traceLane");
        assertSelfAskStage(source, "traceRewriteHonesty");
    }

    private static void assertSelfAskStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[SelfAskPlanner] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing SelfAskPlanner fail-soft stage: " + stage);
    }

    private static void assertSelfAskInvalidNumberStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[SelfAskPlanner] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing SelfAskPlanner invalid_number fail-soft stage: " + stage);
    }

    @Test
    void laneMetadataDisabledReasonUsesTraceLabel() {
        String fakeKey = "sk-" + "test-self-a-placeholder-1234567890abcdef";
        String rawReason = "provider failed api_key=" + fakeKey;

        Map<String, Object> meta = ReflectionTestUtils.invokeMethod(
                SelfAskPlanner.class,
                "laneMeta",
                SelfAskPlanner.SubQuestionType.RC,
                "llmrouter.api3",
                "true",
                "api3",
                rawReason,
                1.0d,
                0.2d,
                3000L);

        assertNotNull(meta);
        assertEquals(SafeRedactor.traceLabelOrFallback(rawReason, "unknown"), meta.get("disabledReason"));
        assertFalse(String.valueOf(meta).contains(fakeKey));
    }

    @Test
    void parseLongDropsNonFiniteNumericHints() {
        Long positiveInfinity = ReflectionTestUtils.invokeMethod(
                SelfAskPlanner.class, "parseLong", Double.POSITIVE_INFINITY, 77L);
        Long negativeInfinity = ReflectionTestUtils.invokeMethod(
                SelfAskPlanner.class, "parseLong", Double.NEGATIVE_INFINITY, 77L);
        Long notANumber = ReflectionTestUtils.invokeMethod(
                SelfAskPlanner.class, "parseLong", Double.NaN, 77L);

        assertEquals(77L, positiveInfinity);
        assertEquals(77L, negativeInfinity);
        assertEquals(77L, notANumber);
    }

    @Test
    void generateThreeLanesUsesDeterministicFallbackOnShortTimeout() {
        SelfAskPlanner planner = new SelfAskPlanner(null, null);

        List<SelfAskPlanner.SubQuestion> lanes =
                planner.generateThreeLanes("rag chunk overlap self ask", 1);

        assertEquals(3, lanes.size());
        Set<SelfAskPlanner.SubQuestionType> types = lanes.stream()
                .map(sq -> sq.type)
                .collect(Collectors.toSet());
        assertEquals(EnumSet.allOf(SelfAskPlanner.SubQuestionType.class), types);
        assertEquals(3, lanes.stream().map(sq -> sq.text).distinct().count());
        assertFalse(lanes.stream().anyMatch(sq -> sq.text == null || sq.text.isBlank()));
        assertTrue(lanes.stream().allMatch(sq -> sq.meta.containsKey("branchId")));
        assertTrue(lanes.stream().allMatch(sq -> sq.meta.containsKey("intentAxis")));
        assertTrue(lanes.stream().allMatch(sq -> sq.meta.containsKey("consensusRole")));
    }

    @Test
    void regenerateLaneUsesSameMetadataAndSingleTargetLane() {
        TraceStore.clear();
        SelfAskPlanner planner = new SelfAskPlanner(null, null);

        java.util.Optional<SelfAskPlanner.SubQuestion> regenerated = planner.regenerateLane(
                "rag duplicate branch correction",
                SelfAskPlanner.SubQuestionType.ER,
                500L,
                0.30d,
                0.70d);

        assertTrue(regenerated.isPresent());
        SelfAskPlanner.SubQuestion sq = regenerated.get();
        assertEquals(SelfAskPlanner.SubQuestionType.ER, sq.type);
        assertEquals("ER", sq.meta.get("branchId"));
        assertEquals("alias_synonym", sq.meta.get("intentAxis"));
        assertEquals("RELAXED", sq.meta.get("consensusRole"));
        assertEquals("true", sq.meta.get("fallback"));
        assertEquals("IllegalStateException", TraceStore.get("selfask.regenerate.reason"));
        assertEquals(1, TraceStore.get("selfask.regenerate.maxAttempts"));
        assertEquals("ER", TraceStore.get("selfask.regenerate.lane"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.regenerate.fallback"));
        assertTrue(((Number) TraceStore.get("selfask.regenerate.timeoutMs")).longValue() <= 500L);
        TraceStore.clear();
    }

    @Test
    void zero100TraceAddsRolePhaseAndClampsLaneTimeout() {
        TraceStore.clear();
        TraceStore.put("zero100.phase", "DIVERGE");
        TraceStore.put("zero100.activeLane", "RC");
        TraceStore.put("zero100.branch.timeboxMs", Map.of("BQ", 300L, "ER", 300L, "RC", 300L));
        SelfAskPlanner planner = new SelfAskPlanner(null, null);

        List<SelfAskPlanner.SubQuestion> lanes =
                planner.generateThreeLanes("rag branch consensus timeout", 3_000);

        assertEquals(3, lanes.size());
        assertTrue(lanes.stream().allMatch(sq -> "DIVERGE".equals(sq.meta.get("zero100Phase"))));
        assertTrue(lanes.stream().allMatch(sq -> "RC".equals(sq.meta.get("zero100ActiveLane"))));
        assertTrue(lanes.stream().allMatch(sq -> ((Number) sq.meta.get("timeoutMs")).longValue() <= 300L));
        assertEquals("STRICT", lanes.stream()
                .filter(sq -> sq.type == SelfAskPlanner.SubQuestionType.BQ)
                .findFirst()
                .orElseThrow()
                .meta.get("consensusRole"));
        TraceStore.clear();
    }

    @Test
    void generateThreeLanesFallsBackToTwoLocalModelsWhenApiLaneDisabled() {
        TraceStore.clear();
        FakeFactory factory = new FakeFactory(Map.of(
                "llmrouter.gemma", new StubModel("strict official query"),
                "llmrouter.light", new StubModel("alias relation query")));
        SelfAskPlanner planner = new SelfAskPlanner(new StubModel("legacy query"), provider(factory));

        List<SelfAskPlanner.SubQuestion> lanes =
                planner.generateThreeLanes("rag long tail", 3_000);

        assertEquals(3, lanes.size());
        SelfAskPlanner.SubQuestion rc = lanes.stream()
                .filter(sq -> sq.type == SelfAskPlanner.SubQuestionType.RC)
                .findFirst()
                .orElseThrow();
        assertEquals("true", rc.meta.get("fallback"));
        assertEquals("local-fallback", rc.meta.get("provider"));
        assertEquals("llmrouter.gemma+llmrouter.light", rc.meta.get("model"));
        assertEquals("missing-key-or-unauthorized", rc.meta.get("disabledReason"));
        assertTrue(rc.text.contains("strict") || rc.text.contains("alias"));
        assertEquals("missing-key-or-unauthorized", TraceStore.getString("selfask.3way.api.disabledReason"));
        TraceStore.clear();
    }

    @Test
    void generateThreeLanesClassifiesLaneCancellationWithoutRawLeak() {
        TraceStore.clear();
        String rawQuery = "private self ask cancellation source";
        String rawToken = "ownerToken abc123";
        SelfAskPlanner planner = new SelfAskPlanner(new StubModel("legacy query"), provider(
                new BqCancellingFactory(rawToken, Map.of(
                        "llmrouter.light", new StubModel("alias relation query"),
                        "llmrouter.api3", new StubModel("counter relation query")))));

        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes(rawQuery, 3_000);

        assertEquals(3, lanes.size());
        SelfAskPlanner.SubQuestion bq = lanes.stream()
                .filter(sq -> sq.type == SelfAskPlanner.SubQuestionType.BQ)
                .findFirst()
                .orElseThrow();
        assertEquals("true", bq.meta.get("fallback"));
        assertEquals("cancelled", bq.meta.get("disabledReason"));
        assertEquals("cancelled", bq.meta.get("failureClass"));
        String traceDump = String.valueOf(TraceStore.getAll());
        assertTrue(traceDump.contains("failureClass=cancelled"));
        assertFalse(traceDump.contains(rawQuery));
        assertFalse(traceDump.contains(rawToken));
        TraceStore.clear();
    }

    @Test
    void generateThreeLanesRecordsRedactedLaneWeights() {
        TraceStore.clear();
        String rawQuery = "sensitive self ask source query";
        FakeFactory factory = new FakeFactory(Map.of(
                "llmrouter.gemma", new StubModel("strict official query"),
                "llmrouter.light", new StubModel("alias relation query")));
        SelfAskPlanner planner = new SelfAskPlanner(new StubModel("legacy query"), provider(factory));

        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes(
                rawQuery,
                3_000,
                0.25d,
                Map.of("BQ", 1.4d, "ER", 0.8d, "RC", 1.2d));

        assertEquals(3, lanes.size());
        assertEquals(Map.of("BQ", 1.4d, "ER", 0.8d, "RC", 1.2d),
                TraceStore.get("selfask.3way.weights"));
        Object eventsObj = TraceStore.get("selfask.3way.events");
        assertNotNull(eventsObj);
        String eventDump = String.valueOf(eventsObj);
        assertFalse(eventDump.contains(rawQuery));
        assertTrue(eventDump.contains("weight="));
        assertTrue(eventDump.contains("temperature="));
        assertTrue(eventDump.contains("timeoutMs="));
        assertTrue(eventDump.contains("failureClass="));
        assertTrue(eventDump.contains("seedHash12="));
        assertFalse(eventDump.contains("strict official query"));
        assertEquals(List.of("BQ", "RC", "ER"), TraceStore.get("selfask.3way.laneOrder"));
        TraceStore.clear();
    }

    @Test
    void honestRewriteGuardDoesNotPromoteCorrectedDistressInterpretation() {
        TraceStore.clear();
        String rawQuery = "션은 준우가 자살하라고 했다고 해석했지만 준우는 그런 뜻이 절대 아니고 손절선을 잡으라는 말이었다고 정정했다.";
        FakeFactory factory = new FakeFactory(Map.of(
                "llmrouter.gemma", new StubModel("준우 자살 권유 인신공격 확정"),
                "llmrouter.light", new StubModel("션 준우 발화자 정정 오해")));
        SelfAskPlanner planner = new SelfAskPlanner(new StubModel("legacy query"), provider(factory));

        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes(rawQuery, 3_000);

        assertEquals(3, lanes.size());
        String laneDump = lanes.toString();
        assertFalse(laneDump.contains("자살 권유"));
        assertFalse(laneDump.contains("인신공격 확정"));
        String traceDump = String.valueOf(TraceStore.getAll());
        assertTrue(traceDump.contains("OVERREACH"));
        assertTrue(traceDump.contains("sensitive_inference"));
        assertFalse(traceDump.contains(rawQuery));
        TraceStore.clear();
    }

    @Test
    void honestRewriteGuardKeepsOtherCeoCaseSeparateFromDgxAdvice() {
        TraceStore.clear();
        String rawQuery = "창업과 사기 대표 이야기는 다른 대표 이야기였고, 션에게 한 말은 DGX 장비와 빚 부담을 정리하라는 조언이었다.";
        FakeFactory factory = new FakeFactory(Map.of(
                "llmrouter.gemma", new StubModel("션 창업 DGX 사기 대표"),
                "llmrouter.light", new StubModel("션 DGX 빚 장비 조언 발화자 분리")));
        SelfAskPlanner planner = new SelfAskPlanner(new StubModel("legacy query"), provider(factory));

        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes(rawQuery, 3_000);

        assertEquals(3, lanes.size());
        assertFalse(lanes.toString().contains("션 창업 DGX 사기 대표"));
        String traceDump = String.valueOf(TraceStore.getAll());
        assertTrue(traceDump.contains("actor_context_merge"));
        assertFalse(traceDump.contains(rawQuery));
        TraceStore.clear();
    }

    private static ObjectProvider<DynamicChatModelFactory> provider(DynamicChatModelFactory factory) {
        return new ObjectProvider<>() {
            @Override
            public DynamicChatModelFactory getObject(Object... args) {
                return factory;
            }

            @Override
            public DynamicChatModelFactory getIfAvailable() {
                return factory;
            }

            @Override
            public DynamicChatModelFactory getIfUnique() {
                return factory;
            }

            @Override
            public DynamicChatModelFactory getObject() {
                return factory;
            }

            @Override
            public Iterator<DynamicChatModelFactory> iterator() {
                return List.of(factory).iterator();
            }

            @Override
            public Stream<DynamicChatModelFactory> stream() {
                return Stream.of(factory);
            }

            @Override
            public Stream<DynamicChatModelFactory> orderedStream() {
                return Stream.of(factory);
            }
        };
    }

    private static final class FakeFactory extends DynamicChatModelFactory {
        private final Map<String, ChatModel> models;

        private FakeFactory(Map<String, ChatModel> models) {
            super(null, null);
            this.models = models;
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP, Integer maxTokens,
                int timeoutSeconds) {
            if ("llmrouter.api3".equals(modelName)) {
                throw new IllegalStateException("provider=groq disabledReason=missing GROQ_API_KEY");
            }
            return models.getOrDefault(modelName, new StubModel(modelName + " query"));
        }
    }

    private static final class BqCancellingFactory extends DynamicChatModelFactory {
        private final String rawToken;
        private final Map<String, ChatModel> models;

        private BqCancellingFactory(String rawToken, Map<String, ChatModel> models) {
            super(null, null);
            this.rawToken = rawToken;
            this.models = models;
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP, Integer maxTokens,
                int timeoutSeconds) {
            if ("llmrouter.gemma".equals(modelName)) {
                throw new CancellationException("cancelled " + rawToken);
            }
            return models.getOrDefault(modelName, new StubModel(modelName + " query"));
        }
    }

    private record StubModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
