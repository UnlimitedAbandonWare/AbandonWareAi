package com.example.lms.api;

import com.example.lms.debug.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DebugEventsPageTest {
    private static final String REQUEST = "hash:111111111111";
    private static final String TRACE = "hash:222222222222";
    private final DebugEventStore store = new DebugEventStore();
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private Deque<DebugEvent> ring;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ring = (Deque<DebugEvent>) ReflectionTestUtils.getField(store, "ring");
        mvc = MockMvcBuilders.standaloneSetup(new DebugEventsDiagnosticsController(
                store, mock(DebugEventsSseRuntime.class), 300000)).build();
    }

    private DebugEvent event(String id, long time, String request, String trace) {
        return new DebugEvent(id, Instant.ofEpochMilli(time), time, DebugEventLevel.INFO,
                DebugProbeType.GENERIC, "hash:333333333333", "synthetic", null,
                trace, request, "fixture", "test", Map.of(), null, null);
    }

    private JsonNode page(String cursor, String request, String trace, int expected) throws Exception {
        var builder = get("/api/diagnostics/debug/events/page").param("limit", "1");
        if (cursor != null) builder.param("cursor", cursor);
        if (request != null) builder.param("requestIdHash", request);
        if (trace != null) builder.param("traceIdHash", trace);
        String json = mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.isEmpty() ? null : mapper.readTree(json);
    }

    @Test
    void exactAndFiltersRunBeforeLimitAcrossNewestNonmatches() throws Exception {
        ring.add(event("1-1", 1, REQUEST, TRACE));
        ring.addFirst(event("2-2", 2, REQUEST, "hash:999999999999"));
        ring.addFirst(event("3-3", 3, "hash:999999999999", TRACE));
        JsonNode result = page(null, REQUEST, TRACE, 200);
        assertEquals("1-1", result.path("items").get(0).path("id").asText());
        assertFalse(result.path("hasMore").asBoolean());
        assertEquals(0, store.page("hash:111111111110", TRACE, null, 5).items().size());
    }

    @Test
    void cursorPreservesInsertionOrderWithReversedClockAndConcurrentHeadInsert() throws Exception {
        ring.add(event("3-3", 100, REQUEST, TRACE));
        ring.add(event("2-2", 300, REQUEST, TRACE));
        ring.add(event("1-1", 300, REQUEST, TRACE));
        JsonNode first = page(null, REQUEST, TRACE, 200);
        assertEquals("3-3", first.path("items").get(0).path("id").asText());
        ring.addFirst(event("4-4", 50, REQUEST, TRACE));
        JsonNode second = page(first.path("nextCursor").asText(), REQUEST, TRACE, 200);
        assertEquals("2-2", second.path("items").get(0).path("id").asText());
        JsonNode third = page(second.path("nextCursor").asText(), REQUEST, TRACE, 200);
        assertEquals("1-1", third.path("items").get(0).path("id").asText());
        assertFalse(third.path("hasMore").asBoolean());
        assertTrue(third.path("nextCursor").isNull());
    }

    @Test
    void evictedBoundaryReturnsGoneAndNeverSubstitutesLatest() throws Exception {
        ring.add(event("2-2", 2, REQUEST, TRACE));
        ring.add(event("1-1", 1, REQUEST, TRACE));
        String cursor = page(null, REQUEST, TRACE, 200).path("nextCursor").asText();
        ring.clear();
        ring.add(event("3-3", 3, REQUEST, TRACE));
        JsonNode result = page(cursor, REQUEST, TRACE, 410);
        assertEquals("evicted", result.path("cursorStatus").asText());
        assertEquals(0, result.path("items").size());
        assertFalse(result.path("hasMore").asBoolean());
        assertTrue(result.path("nextCursor").isNull());
    }

    @Test
    void cursorCannotBeReboundToAnotherCorrelationFilter() throws Exception {
        ring.add(event("2-2", 2, REQUEST, TRACE));
        ring.add(event("1-1", 1, REQUEST, TRACE));
        String cursor = page(null, REQUEST, TRACE, 200).path("nextCursor").asText();
        page(cursor, "hash:999999999999", TRACE, 400);
        page(cursor, REQUEST, null, 400);
        page(cursor, null, TRACE, 400);
    }

    @Test
    void malformedInputsAreRejectedWithoutReflection() throws Exception {
        for (String cursor : List.of("", "!", "a".repeat(513), "djIKMS0xCgo", "djEK"))
            page(cursor, null, null, 400);
        page(null, "private-unhashed-request", TRACE, 400);
        page(null, REQUEST.substring(0, 10), TRACE, 400);
    }

    @Test
    void pageSizeIsBoundedAndLegacyArrayContractRemains() throws Exception {
        for (int i = 1; i <= 505; i++)
            ring.add(event(Integer.toHexString(i) + "-1", i, REQUEST, TRACE));
        assertEquals(500, store.page(null, null, null, Integer.MAX_VALUE).items().size());
        assertEquals(1, store.page(null, null, null, -1).items().size());
        String json = mvc.perform(get("/api/diagnostics/debug/events").param("limit", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(mapper.readTree(json).isArray());
        assertEquals(2, mapper.readTree(json).size());
    }
}
