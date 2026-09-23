package com.example.lms.service;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHistoryServiceImplConversationMemoryTest {

    @Test
    void summaryReadFailureStillProvidesRecentTurnsThroughTheRealMemoryLoader() {
        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl service = newService(sessions, messages);
        ChatSession session = new ChatSession("synthetic memory fallback");
        session.setId(42L);
        ChatMessage user = message(session, 1L, "user", "Remember the blue fixture.");
        ChatMessage assistant = message(session, 2L, "assistant", "The fixture is blue.");
        when(messages.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(42L), eq("system"), any()))
                .thenThrow(new IllegalStateException("synthetic-summary-read-failure"));
        when(messages.findNewestWindowBySessionId(eq(42L), any(Pageable.class)))
                .thenReturn(List.of(assistant, user));
        var handler = new com.example.lms.service.rag.handler.MemoryHandler(service);
        ReflectionTestUtils.setField(handler, "maxTurns", 2);

        String memory = handler.loadForSession(42L);

        assertTrue(memory != null && memory.contains("Recent turns:"));
        assertTrue(memory.contains("Remember the blue fixture."));
        assertTrue(memory.contains("The fixture is blue."));
        assertFalse(memory.contains("Conversation summary:"));
        org.mockito.Mockito.verify(messages).findNewestWindowBySessionId(eq(42L), any(Pageable.class));
        org.mockito.Mockito.verify(messages, org.mockito.Mockito.never()).save(any(ChatMessage.class));
    }

    @Test
    void rollingSummaryPersistsAsSystemMetaAndHistoryFormattingSkipsIt() throws Exception {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl service = newService(sessionRepository, messageRepository);

        ChatSession session = new ChatSession("memory");
        session.setId(42L);
        ChatMessage user = message(session, 1L, "user", "remember blue");
        ChatMessage assistant = message(session, 2L, "assistant", "blue noted");
        ChatMessage rsum = message(session, 3L, "system",
                "⎔RSUM⎔{\"lastMessageId\":2,\"summary\":\"old summary\",\"turns\":2,\"updatedAt\":\"2026-05-26T00:00:00Z\"}");

        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
        when(messageRepository.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(42L), eq("system"), eq("⎔RSUM⎔"))).thenReturn(Optional.empty());
        when(messageRepository.findBySession_IdOrderByCreatedAtDesc(eq(42L), any(Pageable.class)))
                .thenReturn(List.of(assistant, user));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(42L))
                .thenReturn(List.of(user, assistant, rsum));
        when(messageRepository.findNewestWindowBySessionId(eq(42L), any(Pageable.class)))
                .thenReturn(List.of(rsum, assistant, user));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateRollingSummary(42L, 2L);

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        org.mockito.Mockito.verify(messageRepository).save(saved.capture());
        assertEquals("system", saved.getValue().getRole());
        String savedContent = saved.getValue().getContent();
        assertTrue(savedContent.startsWith("⎔RSUM⎔"));
        assertTrue(savedContent.contains("\"lastMessageId\":2"));
        assertTrue(savedContent.contains("remember blue"));
        assertTrue(savedContent.contains("blue noted"));

        String storedPayload = savedContent.substring("⎔RSUM⎔".length());
        int newline = storedPayload.indexOf('\n');
        assertTrue(newline > 0);
        String metaJson = storedPayload.substring(0, newline);
        String summaryBody = storedPayload.substring(newline + 1);
        java.util.Map<?, ?> payload = new ObjectMapper().readValue(
                metaJson,
                java.util.Map.class);
        assertEquals(2, ((Number) payload.get("turns")).intValue());
        assertTrue(((List<?>) payload.get("anchors")).contains("blue"));
        assertFalse(((List<?>) payload.get("importantSentences")).isEmpty());
        assertTrue(((Number) payload.get("sentenceCount")).intValue() >= 2);
        assertTrue(((Number) payload.get("tokenEstimate")).intValue() > 0);
        assertTrue(((Number) payload.get("rawCharCount")).intValue() > 0);
        assertEquals(summaryBody.length(), ((Number) payload.get("compressedCharCount")).intValue());
        assertTrue(((Number) payload.get("compressionRatio")).doubleValue() > 0.0d);
        assertEquals(Boolean.TRUE, payload.get("promoted"));
        assertFalse(String.valueOf(payload.get("promotionHash")).isBlank());

        assertEquals(List.of("User: remember blue", "Assistant: blue noted"),
                service.getFormattedRecentHistory(42L, 8));
    }

    @Test
    void getRollingSummaryReadsBareJsonRsumPayload() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl service = newService(sessionRepository, messageRepository);

        ChatSession session = new ChatSession("memory");
        ChatMessage rsum = message(session, 7L, "system",
                "⎔RSUM⎔{\"lastMessageId\":6,\"summary\":\"persistent summary\",\"turns\":4,\"updatedAt\":\"2026-05-26T00:00:00Z\"}");
        when(messageRepository.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(42L), eq("system"), eq("⎔RSUM⎔"))).thenReturn(Optional.of(rsum));

        assertEquals(Optional.of("persistent summary"), service.getRollingSummary(42L));
        ChatHistoryService.ConversationMemorySnapshot snapshot = service.getConversationMemorySnapshot(42L);
        assertEquals("persistent summary", snapshot.summary());
        assertTrue(snapshot.anchors().isEmpty());
        assertTrue(snapshot.importantSentences().isEmpty());
        assertFalse(snapshot.promoted());
    }

    @Test
    void getRollingSummaryReadsEnvelopeAndPrefersBodySummary() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl service = newService(sessionRepository, messageRepository);

        ChatSession session = new ChatSession("memory");
        ChatMessage rsum = message(session, 7L, "system",
                "⎔RSUM⎔{\"lastMessageId\":6,\"summary\":\"metadata summary\",\"turns\":4,"
                        + "\"compressedCharCount\":5,\"updatedAt\":\"2026-05-26T00:00:00Z\"}\nbody summary wins");
        when(messageRepository.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(42L), eq("system"), eq("⎔RSUM⎔"))).thenReturn(Optional.of(rsum));

        assertEquals(Optional.of("body summary wins"), service.getRollingSummary(42L));
        ChatHistoryService.ConversationMemorySnapshot snapshot = service.getConversationMemorySnapshot(42L);
        assertEquals("body summary wins", snapshot.summary());
        assertEquals(5, snapshot.compressedCharCount());
    }

    @Test
    void recentHistoryReadsOnlyTheRequestedNewestWindowFromALongOrdinarySession() {
        HistoryWindowFixture fixture = historyWindowFixture(10_000, List.of());
        assertEquals(List.of("User: turn-9998", "User: turn-9999", "User: turn-10000"),
                fixture.service.getFormattedRecentHistory(42L, 3));
        assertEquals(3, fixture.loadedRows.get(), "three recent turns must not materialize the full transcript");
        org.mockito.Mockito.verify(fixture.messages, org.mockito.Mockito.never())
                .findBySessionIdOrderByCreatedAtAsc(42L);
    }

    @Test
    void recentHistoryScansPastAllSixMetaPrefixesBeforeSelectingVisibleTurns() {
        List<String> meta = List.of("⎔TRACE⎔x", "⎔TRACE64⎔x", "?TRACE?x", "?TRACESNAP?x", "⎔USUM⎔x", "⎔RSUM⎔x");
        HistoryWindowFixture fixture = historyWindowFixture(10_000, meta);
        assertEquals(List.of("User: turn-9999", "User: turn-10000"),
                fixture.service.getFormattedRecentHistory(42L, 2));
        assertEquals(8, fixture.loadedRows.get(), "meta-only pages must not force reading older ordinary history");
        assertEquals(List.of(0, 1, 2, 3), fixture.pages);
    }

    @Test
    void recentHistoryPreservesCaseSensitiveMetaBoundariesAndNullContentFormatting() {
        HistoryWindowFixture fixture = historyWindowFixture(0, java.util.Arrays.asList(
                "⎔trace⎔visible", " ?TRACE?visible", "text ⎔RSUM⎔visible", null));
        assertEquals(List.of("User: ⎔trace⎔visible", "User:  ?TRACE?visible", "User: text ⎔RSUM⎔visible", "User: "),
                fixture.service.getFormattedRecentHistory(42L, 4));
        assertEquals(4, fixture.loadedRows.get());
    }

    @Test
    void recentHistoryReturnsAllAvailableVisibleTurnsWhenTailIsMostlyMeta() {
        HistoryWindowFixture fixture = historyWindowFixture(1, List.of("⎔RSUM⎔a", "⎔USUM⎔b", "?TRACE?c"));
        assertEquals(List.of("User: turn-1"), fixture.service.getFormattedRecentHistory(42L, 3));
        assertEquals(List.of(0, 1), fixture.pages);
    }

    @Test
    void recentHistoryExhaustsAnAllMetaSessionWithoutInventingConversation() {
        HistoryWindowFixture fixture = historyWindowFixture(0, List.of("⎔RSUM⎔a", "⎔USUM⎔b", "?TRACE?c", "?TRACESNAP?d"));
        assertTrue(fixture.service.getFormattedRecentHistory(42L, 2).isEmpty());
        assertEquals(4, fixture.loadedRows.get());
        assertEquals(List.of(0, 1, 2), fixture.pages);
    }

    @Test
    void recentHistoryCapsEachDatabasePageWithoutTruncatingLargerRequestedOutput() {
        HistoryWindowFixture fixture = historyWindowFixture(1_000, List.of());
        List<String> result = fixture.service.getFormattedRecentHistory(42L, 350);
        assertEquals(350, result.size());
        assertEquals("User: turn-651", result.get(0));
        assertEquals("User: turn-1000", result.get(349));
        assertEquals(400, fixture.loadedRows.get());
        assertTrue(fixture.pageSizes.stream().allMatch(size -> size <= ChatHistoryService.MAX_SESSION_DETAIL_LIMIT));
    }

    @Test
    void recentHistoryKeepsOneTurnMinimumAndAvoidsRepositoryForNullSession() {
        for (int limit : new int[] {0, -2}) {
            HistoryWindowFixture fixture = historyWindowFixture(10, List.of());
            assertEquals(List.of("User: turn-10"), fixture.service.getFormattedRecentHistory(42L, limit));
            assertEquals(1, fixture.loadedRows.get());
        }
        HistoryWindowFixture fixture = historyWindowFixture(10, List.of());
        assertTrue(fixture.service.getFormattedRecentHistory(null, 3).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(fixture.messages);
    }

    private record HistoryWindowFixture(ChatHistoryServiceImpl service, ChatMessageRepository messages,
                                        java.util.concurrent.atomic.AtomicInteger loadedRows,
                                        java.util.List<Integer> pages, java.util.List<Integer> pageSizes) { }

    private static HistoryWindowFixture historyWindowFixture(int ordinaryCount, List<String> tail) {
        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl service = newService(sessions, messages);
        ChatSession session = new ChatSession("history window");
        session.setId(42L);
        List<ChatMessage> ascending = new java.util.ArrayList<>();
        java.time.LocalDateTime epoch = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int index = 0; index < ordinaryCount + tail.size(); index++) {
            ChatMessage row = message(session, (long) index + 1, "user",
                    index < ordinaryCount ? "turn-" + (index + 1) : tail.get(index - ordinaryCount));
            row.setCreatedAt(epoch.plusSeconds(index));
            ascending.add(row);
        }
        List<ChatMessage> descending = new java.util.ArrayList<>(ascending);
        java.util.Collections.reverse(descending);
        var loadedRows = new java.util.concurrent.atomic.AtomicInteger();
        List<Integer> pages = new java.util.ArrayList<>();
        List<Integer> sizes = new java.util.ArrayList<>();
        when(messages.findBySessionIdOrderByCreatedAtAsc(42L)).thenAnswer(invocation -> {
            loadedRows.addAndGet(ascending.size());
            return ascending;
        });
        when(messages.findNewestWindowBySessionId(eq(42L), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            int start = (int) Math.min(descending.size(), pageable.getOffset());
            int end = Math.min(descending.size(), start + pageable.getPageSize());
            pages.add(pageable.getPageNumber());
            sizes.add(pageable.getPageSize());
            loadedRows.addAndGet(end - start);
            return descending.subList(start, end);
        });
        return new HistoryWindowFixture(service, messages, loadedRows, pages, sizes);
    }

    private static ChatHistoryServiceImpl newService(
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository) {
        ChatHistoryServiceImpl service = new ChatHistoryServiceImpl(
                sessionRepository,
                messageRepository,
                mock(AdministratorRepository.class),
                new ObjectMapper(),
                mock(ClientOwnerKeyResolver.class));
        ReflectionTestUtils.setField(service, "rollingSummaryMaxChars", 1200);
        ReflectionTestUtils.setField(service, "rollingSummaryPromoteMinTurns", 2);
        ReflectionTestUtils.setField(service, "rollingSummaryPromoteTokenThreshold", 999);
        ReflectionTestUtils.setField(service, "rollingSummaryPromoteSentenceThreshold", 999);
        ReflectionTestUtils.setField(service, "rollingSummaryAnchorCount", 4);
        ReflectionTestUtils.setField(service, "rollingSummaryImportantSentenceCount", 2);
        return service;
    }

    private static ChatMessage message(ChatSession session, Long id, String role, String content) {
        ChatMessage message = new ChatMessage(session, role, content);
        message.setId(id);
        return message;
    }
}
