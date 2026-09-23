package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionMetaMergerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void uiValuesOverrideSavedSessionMeta() {
        ChatSession session = ChatSession.builder()
                .id(42L)
                .sessionMeta("""
                        {"model":"saved-model","searchMode":"FORCE_DEEP","useRag":true,
                         "precisionSearch":true,"searchScopes":["saved"],"profile":"saved-profile",
                         "guardLevel":"LOW"}
                        """)
                .build();
        ChatRequestDto request = new ChatRequestDto();
        request.setModel("ui-model");
        request.setSearchMode(SearchMode.OFF);
        request.setUseRag(false);
        request.setPrecisionSearch(false);
        request.setSearchScopes(List.of("ui"));
        request.setProfile("ui-profile");
        request.setGuardLevel("HIGH");

        Map<String, Object> meta = ChatSessionMetaMerger.merge(
                objectMapper,
                session,
                request,
                LoggerFactory.getLogger(ChatSessionMetaMergerTest.class));

        assertEquals("ui-model", meta.get("model"));
        assertEquals("OFF", meta.get("searchMode"));
        assertEquals(false, meta.get("useRag"));
        assertEquals(false, meta.get("precisionSearch"));
        assertEquals(List.of("ui"), meta.get("searchScopes"));
        assertEquals("ui-profile", meta.get("profile"));
        assertEquals("HIGH", meta.get("guardLevel"));
    }

    @Test
    void missingUiValuesRestoreFromSavedSessionMeta() {
        ChatSession session = ChatSession.builder()
                .id(43L)
                .sessionMeta("""
                        {"model":"saved-model","searchMode":"FORCE_LIGHT","useRag":"true",
                         "precisionSearch":true,"searchScopes":["official","docs"],
                         "profile":"saved-profile","guardLevel":"LOW"}
                        """)
                .build();
        ChatRequestDto request = new ChatRequestDto();
        request.setModel(null);
        request.setSearchMode(null);
        request.setUseRag(null);
        request.setPrecisionSearch(null);
        request.setSearchScopes(List.of());
        request.setProfile(null);
        request.setGuardLevel(null);

        Map<String, Object> meta = ChatSessionMetaMerger.merge(
                objectMapper,
                session,
                request,
                LoggerFactory.getLogger(ChatSessionMetaMergerTest.class));

        assertEquals("saved-model", request.getModel());
        assertEquals(SearchMode.FORCE_LIGHT, request.getSearchMode());
        assertEquals(true, request.getUseRag());
        assertEquals(true, request.getPrecisionSearch());
        assertEquals(List.of("official", "docs"), request.getSearchScopes());
        assertEquals("saved-profile", request.getProfile());
        assertEquals("LOW", request.getGuardLevel());
        assertEquals("saved-model", meta.get("model"));
    }

    @Test
    void searchModeRestoreIsCaseAndWhitespaceTolerant() {
        ChatSession session = ChatSession.builder()
                .id(44L)
                .sessionMeta("""
                        {"searchMode":" force_light "}
                        """)
                .build();
        ChatRequestDto request = new ChatRequestDto();
        request.setSearchMode(null);

        ChatSessionMetaMerger.merge(
                objectMapper,
                session,
                request,
                LoggerFactory.getLogger(ChatSessionMetaMergerTest.class));

        assertEquals(SearchMode.FORCE_LIGHT, request.getSearchMode());
    }

    @Test
    void metaMergerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatSessionMetaMerger.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}", Pattern.DOTALL)
                .matcher(source)
                .find());
    }

    @Test
    void metaMergeFallbacksLeaveScannerRecognizedBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatSessionMetaMerger.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressedMergeSkipped(log, \"search_mode_restore\", session, error);"));
        assertTrue(source.contains("traceSuppressedMergeSkipped(log, \"search_scopes_restore\", session, error);"));
    }

    @Test
    void sessionMetaParseFailureLogsHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatSessionMetaMerger.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("errorHash={}") && source.contains("errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(e.getMessage())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
    }

    @Test
    void mergeFallbacksLeaveTraceBreadcrumbsWithoutRawMetaValues() {
        TraceStore.clear();
        String raw = "ownerToken=secret-invalid";
        ChatSession badJsonSession = ChatSession.builder()
                .id(45L)
                .sessionMeta("{\"model\":\"" + raw + "\"")
                .build();

        ChatSessionMetaMerger.merge(
                objectMapper,
                badJsonSession,
                new ChatRequestDto(),
                LoggerFactory.getLogger(ChatSessionMetaMergerTest.class));

        assertEquals(Boolean.TRUE, TraceStore.get("chat.sessionMeta.suppressed.parse"));
        assertEquals("JsonEOFException", TraceStore.get("chat.sessionMeta.suppressed.parse.errorType"));
        assertEquals("parse", TraceStore.get("chat.sessionMeta.suppressed.stage"));
        assertEquals("JsonEOFException", TraceStore.get("chat.sessionMeta.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
        ChatSession badSearchModeSession = ChatSession.builder()
                .id(46L)
                .sessionMeta("{\"searchMode\":\"" + raw + "\"}")
                .build();
        ChatRequestDto badSearchModeRequest = new ChatRequestDto();
        badSearchModeRequest.setSearchMode(null);

        ChatSessionMetaMerger.merge(
                objectMapper,
                badSearchModeSession,
                badSearchModeRequest,
                LoggerFactory.getLogger(ChatSessionMetaMergerTest.class));

        assertEquals(Boolean.TRUE, TraceStore.get("chat.sessionMeta.suppressed.search_mode_restore"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("chat.sessionMeta.suppressed.search_mode_restore.errorType"));
        assertEquals("search_mode_restore", TraceStore.get("chat.sessionMeta.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("chat.sessionMeta.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
    }
}
