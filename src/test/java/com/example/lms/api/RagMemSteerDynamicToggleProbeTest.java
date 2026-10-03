package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.service.postprocess.FinalAnswerPostProcessor;
import com.example.lms.service.postprocess.OutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rag-mem-steer-0928-76d11b5a: measured dynamic-toggle behavior across
 * consecutive turns (M1/M3/M5/M9 matrix cells).
 *
 * <p>These are evidence probes, not product changes. They exercise the real
 * {@link ChatSessionMetaMerger} (session restore) + {@link ChatRequestSettingsMerger}
 * (defaults) chain exactly as the controller does per turn, asserting the
 * <em>observed</em> end-state so the matrix cells are measured, not guessed.
 * M5 now asserts the repaired explicit-memory-mode round trip.</p>
 */
class RagMemSteerDynamicToggleProbeTest {

    private static final Logger LOG = LoggerFactory.getLogger(RagMemSteerDynamicToggleProbeTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> NO_SETTINGS = Map.of();

    /** Simulate one controller turn: session-meta merge restores/persists, then settings defaults apply. */
    private static Map<String, Object> persistTurn(ChatSession session, ChatRequestDto req) throws Exception {
        Map<String, Object> meta = ChatSessionMetaMerger.merge(MAPPER, session, req, LOG);
        session.setSessionMeta(MAPPER.writeValueAsString(meta));
        return meta;
    }

    private static ChatRequestDto mergedTurn(ChatSession session, ChatRequestDto req) {
        ChatSessionMetaMerger.merge(MAPPER, session, req, LOG);
        return ChatRequestSettingsMerger.merge(req, NO_SETTINGS, true, LOG);
    }

    // ---- M1: RAG ON -> RAG OFF (browser payload shape) ----
    @Test
    void m1_ragOnThenRagOff_explicitFalseClampsEffectiveFlags() throws Exception {
        ChatSession session = new ChatSession("probe-m1");
        ChatRequestDto t1 = ChatRequestDto.builder()
                .sessionId(1L).message("turn1")
                .useRag(true).useWebSearch(true).searchMode(SearchMode.AUTO)
                .build();
        persistTurn(session, t1);

        ChatRequestDto t2 = ChatRequestDto.builder()
                .sessionId(1L).message("turn2")
                .useRag(false).useWebSearch(true).searchMode(SearchMode.AUTO)
                .build();
        ChatRequestDto m2 = mergedTurn(session, t2);

        assertEquals(Boolean.FALSE, m2.getUseRag(), "explicit useRag=false survives");
        assertFalse(m2.isUseRag());
        assertTrue(m2.isUseWebSearch(), "RAG off does not imply web off");
        assertEquals(SearchMode.AUTO, m2.getSearchMode());
        assertNotNull(m2.getRetrievalRequestIntent());
        assertEquals(Boolean.TRUE, m2.getRetrievalRequestIntent().webSearch());
        assertEquals(Boolean.FALSE, m2.getRetrievalRequestIntent().rag(),
                "turn-2 intent carries rag=false; no turn-1 residue");
    }

    // ---- M1/M9 sticky-OFF: client that omits flags on the next turn ----
    @Test
    void m1_omittedFlagsAfterOff_turnRestoreSessionMeta_notSettingsDefault() throws Exception {
        ChatSession session = new ChatSession("probe-m1-omit");
        persistTurn(session, ChatRequestDto.builder()
                .sessionId(2L).message("off turn")
                .useRag(false).useWebSearch(false).searchMode(SearchMode.OFF)
                .build());

        ChatRequestDto omit = ChatRequestDto.builder().sessionId(2L).message("next").build();
        ChatRequestDto merged = mergedTurn(session, omit);

        // session meta restores the explicit OFF state before settings defaults run,
        // so defaultUseRag=true cannot resurrect retrieval for omitting clients.
        assertEquals(Boolean.FALSE, merged.getUseRag());
        assertEquals(Boolean.FALSE, merged.getUseWebSearch());
        assertEquals(SearchMode.OFF, merged.getSearchMode(), "omission must preserve the saved OFF choice");
    }

    // ---- M3: search AUTO -> OFF -> AUTO across three turns ----
    @Test
    void m3_searchAutoOffAuto_roundTripsThroughSessionMeta() throws Exception {
        ChatSession session = new ChatSession("probe-m3");

        persistTurn(session, ChatRequestDto.builder()
                .sessionId(3L).message("t1")
                .useRag(true).useWebSearch(true).searchMode(SearchMode.AUTO).build());
        Map<String, Object> metaAfterOff = persistTurn(session, ChatRequestDto.builder()
                .sessionId(3L).message("t2")
                .useRag(false).useWebSearch(false).searchMode(SearchMode.OFF).build());
        assertEquals("OFF", metaAfterOff.get("searchMode"));
        assertEquals(Boolean.FALSE, metaAfterOff.get("useWebSearch"));

        ChatRequestDto t3 = ChatRequestDto.builder()
                .sessionId(3L).message("t3")
                .useRag(true).useWebSearch(true).searchMode(SearchMode.AUTO).build();
        ChatRequestDto m3 = mergedTurn(session, t3);
        assertEquals(SearchMode.AUTO, m3.getSearchMode());
        assertTrue(m3.isUseWebSearch());
        assertTrue(m3.isUseRag());
        assertEquals(Boolean.TRUE, m3.getRetrievalRequestIntent().webSearch());
        assertEquals(Boolean.TRUE, m3.getRetrievalRequestIntent().rag());
    }

    // ---- M5: explicit memory mode survives an omitting next turn ----
    @ParameterizedTest
    @ValueSource(strings = {"FULL", "HYBRID", "EPHEMERAL"})
    void m5_explicitMemoryModeSurvivesNextTurnAndReachesSaveGate(String mode) throws Exception {
        ChatSession session = new ChatSession("probe-m5");

        Map<String, Object> meta = persistTurn(session, ChatRequestDto.builder()
                .sessionId(5L).message("t1")
                .useRag(true).useWebSearch(true).searchMode(SearchMode.AUTO)
                .memoryMode(mode)
                .build());
        assertEquals(mode, meta.get("memoryMode"), "persist the explicitly selected mode");

        ChatRequestDto t2 = ChatRequestDto.builder()
                .sessionId(5L).message("t2") // omits memoryMode
                .useRag(true).useWebSearch(true).searchMode(SearchMode.AUTO).build();
        ChatRequestDto m2 = mergedTurn(session, t2);

        assertEquals(mode, m2.getMemoryMode(), "turn-2 omission restores the session choice");
        MemoryMode resolved = MemoryMode.fromString(m2.getMemoryMode());
        assertEquals(MemoryMode.valueOf(mode), resolved);
        assertEquals(!"EPHEMERAL".equals(mode), resolved.isReadEnabled());
        FinalAnswerPostProcessor processor = new FinalAnswerPostProcessor(new OutputSanitizer());
        FinalAnswerPostProcessor.Result answer = processor.process(new FinalAnswerPostProcessor.Request(
                "A normal factual answer.", "A normal factual answer.", true, true,
                resolved.isWriteEnabled(), false, false, false, false, "probe query"));
        assertEquals("FULL".equals(mode), answer.memorySaveAllowed());
        assertEquals("FULL".equals(mode) ? "none" : "write_disabled", answer.memoryDenyReason());
        if ("FULL".equals(mode)) {
            FinalAnswerPostProcessor.Result denied = processor.process(new FinalAnswerPostProcessor.Request(
                    "A normal factual answer.", "A normal factual answer.", true, true,
                    resolved.isWriteEnabled(), true, false, false, false, "probe query"));
            assertFalse(denied.memorySaveAllowed(), "restoring FULL never bypasses the evidence-policy gate");
            assertEquals("memory_policy_denied", denied.memoryDenyReason());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HYBRID", "EPHEMERAL"})
    void m5_explicitWriteDisabledModeOverridesSavedFullAndStaysDisabled(String mode) throws Exception {
        ChatSession session = new ChatSession("probe-m5-override");
        session.setSessionMeta("{\"memoryMode\":\"FULL\"}");
        Map<String, Object> meta = persistTurn(session, ChatRequestDto.builder()
                .sessionId(5L).message("disable writes").memoryMode(mode).build());
        assertEquals(mode, meta.get("memoryMode"));
        ChatRequestDto next = mergedTurn(session, ChatRequestDto.builder()
                .sessionId(5L).message("next").build());
        assertEquals(mode, next.getMemoryMode());
        assertFalse(MemoryMode.fromString(next.getMemoryMode()).isWriteEnabled());
    }

    @Test
    void m5_unspecifiedFreshSessionKeepsReadOnlyHybridDefault() throws Exception {
        ChatSession session = new ChatSession("probe-m5-default");
        ChatRequestDto request = ChatRequestDto.builder().sessionId(5L).message("fresh").build();
        assertFalse(persistTurn(session, request).containsKey("memoryMode"));
        ChatRequestDto merged = mergedTurn(session, request);
        assertNull(merged.getMemoryMode());
        assertEquals(MemoryMode.HYBRID, MemoryMode.fromString(merged.getMemoryMode()));
        assertFalse(MemoryMode.fromString(merged.getMemoryMode()).isWriteEnabled());
    }

    // ---- M9: reload -> next turn restores control state ----
    @Test
    void m9_sessionMetaRoundTrip_restoresModelModeAndToggles() throws Exception {
        ChatSession session = new ChatSession("probe-m9");
        persistTurn(session, ChatRequestDto.builder()
                .sessionId(9L).message("t1")
                .model("fixture-model")
                .useRag(false).useWebSearch(true)
                .searchMode(SearchMode.FORCE_LIGHT)
                .precisionSearch(true)
                .guardLevel("LOW")
                .build());

        ChatRequestDto empty = ChatRequestDto.builder().sessionId(9L).message("t2").build();
        ChatSessionMetaMerger.merge(MAPPER, session, empty, LOG);

        assertEquals("fixture-model", empty.getModel());
        assertEquals(Boolean.FALSE, empty.getUseRag());
        assertEquals(Boolean.TRUE, empty.getUseWebSearch());
        assertEquals(SearchMode.FORCE_LIGHT, empty.getSearchMode());
        assertEquals(Boolean.TRUE, empty.getPrecisionSearch());
        assertEquals("LOW", empty.getGuardLevel());
    }

    @ParameterizedTest
    @EnumSource(SearchMode.class)
    void m9_explicitSearchModeOverridesSavedOffThenSurvivesOmission(SearchMode mode) throws Exception {
        ChatSession session = new ChatSession("probe-m9-explicit");
        session.setSessionMeta("{\"searchMode\":\"OFF\",\"memoryMode\":\"FULL\"}");
        ChatRequestDto explicit = MAPPER.readValue(
                "{\"message\":\"change mode\",\"searchMode\":\"" + mode.name() + "\"}", ChatRequestDto.class);
        assertEquals(mode.name(), persistTurn(session, explicit).get("searchMode"));
        ChatRequestDto omitted = MAPPER.readValue("{\"message\":\"next\"}", ChatRequestDto.class);
        ChatRequestDto restored = mergedTurn(session, omitted.toBuilder().message("copied next").build());
        assertEquals(mode, restored.getSearchMode());
        assertEquals("FULL", restored.getMemoryMode(), "M5 must remain sticky alongside M9");
        assertFalse(MAPPER.writeValueAsString(restored).contains("searchModeExplicit"));
    }

    @Test
    void m9_explicitBuilderAutoOverridesSavedOffWhileFreshOmissionStillDefaultsToAuto() throws Exception {
        ChatSession session = new ChatSession("probe-m9-auto");
        session.setSessionMeta("{\"searchMode\":\"OFF\"}");
        ChatRequestDto explicit = ChatRequestDto.builder().message("automatic").searchMode(SearchMode.AUTO).build();
        assertEquals("AUTO", persistTurn(session, explicit.toBuilder().build()).get("searchMode"));
        assertEquals(SearchMode.AUTO, ChatRequestDto.builder().message("fresh").build().getSearchMode());
        assertEquals(SearchMode.AUTO, new ChatRequestDto().getSearchMode());
    }

    // ---- intent vs effective divergence (measured seam for M1/M3) ----
    @Test
    void intentStaysNullWhileDefaultsFillEffectiveFlags() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .sessionId(10L).message("omitted toggles")
                .useRag(null).useWebSearch(null)
                .build();
        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui, Map.of("chat.defaults.useWebSearch", "true"), true, LOG);

        assertEquals(Boolean.TRUE, merged.getUseRag());
        assertEquals(Boolean.TRUE, merged.getUseWebSearch());
        ChatRequestDto.RetrievalRequestIntent intent = merged.getRetrievalRequestIntent();
        assertNotNull(intent);
        assertNull(intent.webSearch(), "raw intent keeps null: caller did not ask");
        assertNull(intent.rag());
    }
}
