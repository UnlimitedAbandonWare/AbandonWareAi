package com.example.lms.resilience;

import com.example.lms.prompt.DisambiguationPromptBuilder;
import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.KeywordSelectionService;
import com.example.lms.search.NoiseClipper;
import com.example.lms.search.TraceStore;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.service.correction.DomainTermDictionary;
import com.example.lms.service.disambiguation.QueryDisambiguationService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.llm.LlmClient;
import com.example.lms.transform.QueryTransformer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tiny "no-network / no-LLM" unit tests:
 * When auxDegraded is already set in GuardContext, auxiliary stages must not fan out to more LLM calls.
 * Instead, they should record TraceStore "...blocked" keys.
 */
public class AuxDegradedBlocksAuxStagesTest {

    @BeforeEach
    void setup() {
        TraceStore.clear();
        GuardContextHolder.clear();

        GuardContext ctx = GuardContext.defaultContext();
        ctx.setAuxDegraded(true);
        GuardContextHolder.set(ctx);
    }

    @AfterEach
    void cleanup() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void keywordSelection_isBlocked_andWritesTraceKey() {
        ChatModel chatModel = strictMock(ChatModel.class, "ChatModel(keywordSelection)");
        KeywordSelectionService svc = new KeywordSelectionService(chatModel, new QueryKeywordPromptBuilder());

        var result = svc.select("user: hello\nassistant: hi\nuser: find me docs", "general", 3);

        assertTrue(result.isPresent(), "fallback terms should still be returned");
        assertEquals(Boolean.TRUE, TraceStore.get("aux.keywordSelection.blocked"));
        assertEquals("auxDegraded", TraceStore.get("aux.keywordSelection.blocked.reason"));

        assertEquals(Boolean.TRUE, TraceStore.get("aux.blocked"));
        assertNotNull(TraceStore.get("aux.blocked.first"));
        assertNotNull(TraceStore.get("aux.blocked.last"));
        assertNotNull(TraceStore.get("aux.blocked.events"));
    }

    @Test
    void disambiguation_isBlocked_andWritesTraceKey() {
        LlmClient llm = strictMock(LlmClient.class, "LlmClient(disambiguation)");
        DomainTermDictionary dict = q -> Set.of(); // deterministic seed only
        QueryDisambiguationService svc = new QueryDisambiguationService(
                llm,
                new ObjectMapper(),
                dict,
                new DisambiguationPromptBuilder(),
                new NoiseClipper()
        );

        var res = svc.clarify("spring boot", List.of());

        assertNotNull(res);
        assertEquals(Boolean.TRUE, TraceStore.get("aux.disambiguation.blocked"));
        assertEquals("auxDegraded", TraceStore.get("aux.disambiguation.blocked.reason"));

        assertEquals(Boolean.TRUE, TraceStore.get("aux.blocked"));
        assertNotNull(TraceStore.get("aux.blocked.first"));
        assertNotNull(TraceStore.get("aux.blocked.last"));
        assertNotNull(TraceStore.get("aux.blocked.events"));
    }

    @Test
    void queryTransformer_isBlocked_andWritesTraceKey() throws Exception {
        ChatModel chatModel = strictMock(ChatModel.class, "ChatModel(queryTransformer)");
        QueryTransformer qt = new QueryTransformer(chatModel);

        // This class relies on @Value injection in production; in a tiny unit test (no Spring),
        // set those flags manually to keep behavior consistent with "enabled by default" config.
        setPrivateBoolean(qt, "novaOrchEnabled", true);
        setPrivateBoolean(qt, "novaOrchQueryTransformerEnabled", true);

        var out = qt.transform("ctx", "hello world");

        assertNotNull(out);
        assertFalse(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.blocked"));
        assertEquals("auxDegraded", TraceStore.get("aux.queryTransformer.blocked.reason"));

        assertEquals(Boolean.TRUE, TraceStore.get("aux.blocked"));
        assertNotNull(TraceStore.get("aux.blocked.first"));
        assertNotNull(TraceStore.get("aux.blocked.last"));
        assertNotNull(TraceStore.get("aux.blocked.events"));
    }

    @Test
    void auxDegraded_blocksAllThreeStages_andEmitsEventTimeline() throws Exception {
        ChatModel chatModelKs = strictMock(ChatModel.class, "ChatModel(keywordSelection)");
        KeywordSelectionService ks = new KeywordSelectionService(chatModelKs, new QueryKeywordPromptBuilder());
        ks.select("user: hello\nassistant: hi\nuser: find me docs", "general", 3);

        LlmClient llm = strictMock(LlmClient.class, "LlmClient(disambiguation)");
        DomainTermDictionary dict = q -> Set.of();
        QueryDisambiguationService dis = new QueryDisambiguationService(
                llm,
                new ObjectMapper(),
                dict,
                new DisambiguationPromptBuilder(),
                new NoiseClipper()
        );
        dis.clarify("spring boot", List.of());

        ChatModel chatModelQt = strictMock(ChatModel.class, "ChatModel(queryTransformer)");
        QueryTransformer qt = new QueryTransformer(chatModelQt);
        setPrivateBoolean(qt, "novaOrchEnabled", true);
        setPrivateBoolean(qt, "novaOrchQueryTransformerEnabled", true);
        qt.transform("ctx", "hello world");

        assertEquals(Boolean.TRUE, TraceStore.get("aux.keywordSelection.blocked"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.disambiguation.blocked"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.blocked"));

        assertEquals("auxDegraded", TraceStore.get("aux.keywordSelection.blocked.reason"));
        assertEquals("auxDegraded", TraceStore.get("aux.disambiguation.blocked.reason"));
        assertEquals("auxDegraded", TraceStore.get("aux.queryTransformer.blocked.reason"));

        Object eventsObj = TraceStore.get("aux.blocked.events");
        assertNotNull(eventsObj);
        assertTrue(eventsObj instanceof List, "aux.blocked.events must be a List");

        @SuppressWarnings("unchecked")
        List<?> events = (List<?>) eventsObj;
        assertTrue(events.size() >= 3, "expected at least one blocked event per stage");

        // Event schema: ensure each stage-blocked event includes standardized fields.
        for (Object e : events) {
            if (!(e instanceof Map)) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) e;

            // Only assert strongly on our known stage events.
            Object stage = m.get("stage");
            if (stage == null) continue;
            String stageName = stage.toString();
            if (!Set.of("keywordSelection", "disambiguation", "queryTransformer").contains(stageName)) {
                continue;
            }

            assertEquals(AuxBlockTracker.EVENT_TYPE_STAGE_BLOCKED, m.get("eventType"));
            assertEquals(Boolean.FALSE, m.get("breakerOpen"));

            Object flagsObj = m.get("ctxFlags");
            assertNotNull(flagsObj, "ctxFlags must exist on aux.blocked.events entries");
            assertTrue(flagsObj instanceof Map, "ctxFlags must be a Map");

            @SuppressWarnings("unchecked")
            Map<String, Object> flags = (Map<String, Object>) flagsObj;
            assertEquals(Boolean.TRUE, flags.get("auxDegraded"));
            assertEquals(Boolean.FALSE, flags.get("auxHardDown"));
        }

        Set<String> stages = events.stream()
                .filter(Map.class::isInstance)
                .map(e -> (Map<?, ?>) e)
                .map(m -> m.get("stage"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertTrue(stages.contains("keywordSelection"), "blocked events should include keywordSelection");
        assertTrue(stages.contains("disambiguation"), "blocked events should include disambiguation");
        assertTrue(stages.contains("queryTransformer"), "blocked events should include queryTransformer");
    }

    private static <T> T strictMock(Class<T> clazz, String label) {
        return Mockito.mock(clazz, invocation -> {
            String name = invocation.getMethod().getName();
            if ("toString".equals(name)) return "strictMock(" + label + ")";
            if ("hashCode".equals(name)) return System.identityHashCode(invocation.getMock());
            if ("equals".equals(name)) return invocation.getMock() == invocation.getArguments()[0];
            throw new AssertionError(label + " must NOT be called when auxDegraded=true. Called method=" + name);
        });
    }

    private static void setPrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setBoolean(target, value);
    }
}
