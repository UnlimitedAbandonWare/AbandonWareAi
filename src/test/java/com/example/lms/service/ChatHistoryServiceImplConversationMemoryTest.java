package com.example.lms.service;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.ContextOrchestrator;
import com.example.lms.service.rag.handler.MemoryHandler;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHistoryServiceImplConversationMemoryTest {

    @ParameterizedTest
    @ValueSource(strings = {"current", "legacy", "missing-summary", "current-small-importance", "legacy-small-importance", "missing-summary-small-importance", "current-long-correction", "legacy-long-correction", "missing-summary-long-correction", "current-repeated", "legacy-repeated", "missing-summary-repeated", "current-recall-overflow", "legacy-recall-overflow", "missing-summary-recall-overflow"})
    void sessionAssignmentsAndCorrectionsSurviveTenLaterExchangesWithinTheSummaryBudget(String snapshotKind) {
        boolean legacy = snapshotKind.startsWith("legacy");
        boolean missingSummary = snapshotKind.startsWith("missing-summary");
        boolean recallOverflow = snapshotKind.endsWith("-recall-overflow");
        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        Map<Long, ChatSession> sessionById = new HashMap<>();
        Map<Long, List<ChatMessage>> rows = new HashMap<>();
        AtomicLong nextId = new AtomicLong();
        for (long id : new long[] {41L, 42L}) {
            ChatSession session = new ChatSession("synthetic bounded memory");
            session.setId(id);
            sessionById.put(id, session);
            rows.put(id, new ArrayList<>());
        }
        when(sessions.findById(anyLong())).thenAnswer(call ->
                Optional.ofNullable(sessionById.get(call.getArgument(0))));
        when(messages.save(any(ChatMessage.class))).thenAnswer(call -> {
            ChatMessage row = call.getArgument(0);
            long id = nextId.incrementAndGet();
            row.setId(id);
            row.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(id));
            rows.get(row.getSession().getId()).add(row);
            return row;
        });
        when(messages.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                anyLong(), eq("system"), eq("⎔RSUM⎔"))).thenAnswer(call ->
                rows.get((Long) call.getArgument(0)).stream()
                        .filter(row -> "system".equals(row.getRole()) && row.getContent().startsWith("⎔RSUM⎔"))
                        .max(java.util.Comparator.comparing(ChatMessage::getId)));
        when(messages.findBySession_IdOrderByCreatedAtDesc(anyLong(), any(Pageable.class)))
                .thenAnswer(call -> rows.get((Long) call.getArgument(0)).stream()
                        .sorted(java.util.Comparator.comparing(ChatMessage::getId).reversed()).toList());
        when(messages.findBySession_IdAndIdGreaterThanOrderByIdAsc(
                anyLong(), anyLong(), any(Pageable.class))).thenAnswer(call ->
                rows.get((Long) call.getArgument(0)).stream()
                        .filter(row -> row.getId() > (Long) call.getArgument(1)).toList());
        when(messages.findNewestWindowBySessionId(anyLong(), any(Pageable.class))).thenAnswer(call -> {
            List<ChatMessage> newest = rows.get((Long) call.getArgument(0)).stream()
                    .sorted(java.util.Comparator.comparing(ChatMessage::getId).reversed()).toList();
            Pageable page = call.getArgument(1);
            int start = (int) Math.min(newest.size(), page.getOffset());
            return newest.subList(start, Math.min(newest.size(), start + page.getPageSize()));
        });
        ChatHistoryServiceImpl history = newService(sessions, messages);
        if (!snapshotKind.endsWith("-small-importance")) {
            ReflectionTestUtils.setField(history, "rollingSummaryAnchorCount", 12);
            ReflectionTestUtils.setField(history, "rollingSummaryImportantSentenceCount", 6);
        }
        try {
            Long first = history.appendMessageReturningId(41L, "user",
                    "이 대화에서만 시험 프로젝트 이름 해솔-42, 색상 청록, 비교 기준 공식 자료 우선·확인 가능한 갱신일을 기억해줘. 계정의 장기 기억에 저장할 필요는 없어.");
            if (!legacy && !missingSummary) history.updateRollingSummary(41L, first);
            Long correction = history.appendMessageReturningId(41L, "user",
                    (snapshotKind.endsWith("-long-correction") ? "추가 설명은 정정값보다 우선하지 않습니다. ".repeat(9) : "")
                    + "방금 정한 프로젝트 이름·색상·비교 기준을 다시 말해줘. 그리고 이 대화의 색상은 남색으로 정정해줘.");
            if (!legacy && !missingSummary) history.updateRollingSummary(41L, correction);
            if (snapshotKind.endsWith("-repeated")) {
                Long repeated = history.appendMessageReturningId(41L, "user",
                        "이 대화에서만 시험 프로젝트 이름 해솔-42, 색상 청록, 비교 기준 공식 자료 우선·확인 가능한 갱신일을 기억해줘. 계정의 장기 기억에 저장할 필요는 없어.");
                if (!legacy && !missingSummary) history.updateRollingSummary(41L, repeated);
            }
            for (int turn = 0; turn < 10; turn++) {
                history.appendMessageReturningId(41L, "user",
                        recallOverflow ? "이 대화의 시험 프로젝트 이름, 마지막으로 정정된 색상, 처음 정한 비교 기준을 정확히 다시 말해줘. 외부 검색은 필요 없어." : "Explain climate change in general terms, without storing anything. Question " + turn);
                Long last = history.appendMessageReturningId(41L, "assistant",
                        recallOverflow ? "시험 프로젝트 이름: 해솔-42. 마지막으로 정정된 색상: 청록. 처음 정한 비교 기준: 공식 자료 우선·확인 가능한 갱신일." : ("보통의 설명 문장으로 최근 맥락을 채웁니다. ").repeat(18) + turn);
                if (!legacy && !missingSummary) history.updateRollingSummary(41L, last);
            }
            if (legacy) {
                long watermark = rows.get(41L).stream().mapToLong(ChatMessage::getId).max().orElseThrow();
                // A pre-pinning snapshot has already evicted the assignments.
                history.appendMessageReturningId(41L, "system",
                        "⎔RSUM⎔{\"lastMessageId\":" + watermark
                                + ",\"turns\":22,\"rawCharCount\":12000,\"promoted\":true,\"promotionHash\":\"legacy-snapshot\"}\n"
                                + "Assistant: 최근의 일반 설명만 남아 있습니다.");
            }
            history.appendMessageReturningId(42L, "user", "독립 세션의 일반 질문입니다.");
            MemoryHandler loader = new MemoryHandler(history);
            ReflectionTestUtils.setField(loader, "maxTurns", 8);
            int storedRowCount = rows.get(41L).size();
            String memory = loader.loadForSession(41L);
            assertEquals(storedRowCount, rows.get(41L).size(), "read recovery must not persist or promote memory");
            ContextOrchestrator orchestrator = new ContextOrchestrator(new StandardPromptBuilder());
            NovaOrchestrationProperties properties = new NovaOrchestrationProperties();
            properties.getRagCompressor().setMemoryMaxLines(12);
            properties.getRagCompressor().setMemoryMaxChars(1400);
            ReflectionTestUtils.setField(orchestrator, "promptContextCompressor",
                    new DynamicContextCompressor(properties));
            String compressedMemory = new DynamicContextCompressor(properties).compressMemoryForPrompt(
                    "이 대화의 시험 프로젝트 이름, 마지막으로 정정된 색상, 처음 정한 비교 기준을 정확히 다시 말해줘. 외부 검색은 필요 없어.", memory);
            assertEquals(Boolean.TRUE, TraceStore.get("prompt.memory.compressor.activated"));
            assertTrue(compressedMemory.length() < memory.length(), "the real workflow compression boundary must be exercised");
            String prompt = orchestrator.orchestrate("이 대화의 시험 프로젝트 이름, 마지막으로 정정된 색상, 처음 정한 비교 기준을 정확히 다시 말해줘. 외부 검색은 필요 없어.",
                    List.of(), List.of(), Map.of(), null, compressedMemory);
            assertTrue(prompt.contains("해솔-42"), "the initial session assignment must survive recent-history eviction");
            assertTrue(prompt.contains("공식 자료 우선·확인 가능한 갱신일"));
            assertTrue(prompt.contains("남색으로 정정"), "the later correction must survive with its ordering");
            if (!snapshotKind.endsWith("-repeated")) {
                assertTrue(prompt.indexOf("해솔-42") < prompt.indexOf("남색으로 정정"));
            }
            assertTrue(recallOverflow || (snapshotKind.endsWith("-repeated")
                            ? prompt.lastIndexOf("청록") > prompt.lastIndexOf("남색")
                            : prompt.lastIndexOf("남색") > prompt.lastIndexOf("청록")),
                    "derived memory must not repeat the superseded color after its latest correction");
            if (recallOverflow) {
                assertTrue(prompt.contains("### SESSION VALUE PRECEDENCE"),
                        "real compression must retain assignment authority despite repeated stale assistant guesses");
                assertTrue(compressedMemory.length() <= 1400);
                assertTrue(compressedMemory.lines().count() <= 12);
            }
            assertTrue(history.getConversationMemorySnapshot(41L).summary().length() <= 1200);
            String other = loader.loadForSession(42L);
            assertFalse(other.contains("해솔-42"));
            assertFalse(other.contains("남색"));
        } finally {
            TraceStore.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "legacy", "read-failure"})
    void sessionAssignmentReadProjectionIsBoundedIsolatedAndPreservesStoredPromotion(String kind) {
        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl history = newService(sessions, messages);
        ChatSession session = new ChatSession("synthetic projection");
        session.setId(41L);
        session.setOwnerKey("synthetic-owner-A");
        ChatSession other = new ChatSession("synthetic independent projection");
        other.setId(42L);
        other.setOwnerKey("synthetic-owner-B");
        String initial = "시험 프로젝트 이름 해솔-42, 색상 청록, 비교 기준 공식 자료 우선을 기억해줘.";
        String correction = "색상만 남색으로 정정해줘.";
        String summary = "complete".equals(kind)
                ? "User: " + initial + "\nUser: " + correction + "\nAssistant: 최근 설명"
                : "Assistant: 최근 설명";
        ChatMessage stored = message(session, 7L, "system",
                "⎔RSUM⎔{\"lastMessageId\":4,\"rawCharCount\":1000,\"promoted\":true,"
                        + "\"promotionHash\":\"synthetic-promotion\"}\n" + summary);
        when(messages.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(41L), eq("system"), eq("⎔RSUM⎔"))).thenReturn(Optional.of(stored));
        when(messages.findNewestWindowBySessionId(eq(41L), any(Pageable.class))).thenAnswer(call -> {
            if ("read-failure".equals(kind)) throw new IllegalStateException("synthetic-recovery-read-failure");
            return List.of(message(session, 9L, "user", "future-value 기억해줘."),
                    message(other, 3L, "user", "foreign-value 기억해줘."),
                    message(session, 4L, "user", correction),
                    message(session, 2L, "assistant", "assistant-value 기억해줘."),
                    message(session, 1L, "user", initial));
        });
        try {
            var first = history.getConversationMemorySnapshot(41L);
            var second = history.getConversationMemorySnapshot(41L);
            assertEquals(first, second, "reads must be stable without persisting a projection");
            assertFalse(first.summary().contains("future-value"));
            assertFalse(first.summary().contains("foreign-value"));
            assertFalse(first.summary().contains("assistant-value"));
            assertTrue(first.summary().length() <= 1200);
            if ("legacy".equals(kind)) {
                assertTrue(first.summary().contains("해솔-42"));
                assertTrue(first.summary().contains("남색으로 정정"));
                assertTrue(first.summary().indexOf("해솔-42") < first.summary().indexOf("남색으로 정정"));
                assertFalse(first.promoted(), "a changed projection must not claim the old stored promotion");
                assertEquals("", first.promotionHash());
            } else {
                assertEquals(summary, first.summary());
                assertTrue(first.promoted(), "an unchanged stored snapshot keeps its promotion");
                assertEquals("synthetic-promotion", first.promotionHash());
            }
            if ("complete".equals(kind)) assertEquals(1, first.summary().split("해솔-42", -1).length - 1);
            ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
            org.mockito.Mockito.verify(messages, org.mockito.Mockito.times(2))
                    .findNewestWindowBySessionId(eq(41L), pages.capture());
            assertTrue(pages.getAllValues().stream().allMatch(p -> p.getPageNumber() == 0 && p.getPageSize() == 128));
            org.mockito.Mockito.verify(messages, org.mockito.Mockito.never()).save(any(ChatMessage.class));
            org.mockito.Mockito.verify(messages, org.mockito.Mockito.never()).findBySessionIdOrderByCreatedAtAsc(anyLong());
            assertTrue(stored.getContent().endsWith(summary), "the stored metadata is read only");
        } finally {
            TraceStore.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-tail", "stale-pins", "complete"})
    void legacyReadProjectionKeepsTheLatestRepeatedCorrectionInTheRealPrompt(String kind) {
        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatHistoryServiceImpl history = newService(sessions, messages);
        ChatSession session = new ChatSession("synthetic repeated correction");
        session.setId(41L);
        String teal = "색상은 청록으로 정정해줘.";
        String navy = "색상은 남색으로 정정해줘.";
        String old = "complete".equals(kind) ? "User: " + teal + "\nUser: " + navy + "\nUser: " + teal
                : "stale-pins".equals(kind) ? "User: " + teal + "\nUser: " + navy : "Assistant: 최근 설명";
        ChatMessage metadata = message(session, 5L, "system",
                "⎔RSUM⎔{\"lastMessageId\":3}\n" + old);
        when(messages.findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                eq(41L), eq("system"), eq("⎔RSUM⎔"))).thenReturn(Optional.of(metadata));
        when(messages.findNewestWindowBySessionId(eq(41L), any(Pageable.class))).thenAnswer(call -> {
            Pageable page = call.getArgument(1);
            return page.getPageSize() == 128
                    ? List.of(message(session, 3L, "user", teal), message(session, 2L, "user", navy),
                            message(session, 1L, "user", teal))
                    : List.of(message(session, 4L, "assistant", "最近の説明だけです。"));
        });
        MemoryHandler loader = new MemoryHandler(history);
        ReflectionTestUtils.setField(loader, "maxTurns", 1);
        ContextOrchestrator orchestrator = new ContextOrchestrator(new StandardPromptBuilder());
        NovaOrchestrationProperties properties = new NovaOrchestrationProperties();
        properties.getRagCompressor().setMemoryMaxLines(12);
        properties.getRagCompressor().setMemoryMaxChars(1400);
        ReflectionTestUtils.setField(orchestrator, "promptContextCompressor", new DynamicContextCompressor(properties));
        try {
            String projected = history.getConversationMemorySnapshot(41L).summary();
            assertTrue(projected.lastIndexOf(teal) > projected.indexOf(navy),
                    "summary order must retain the latest repeated correction, independently of anchor echoes");
            String memory = loader.loadForSession(41L) + "\nAssistant: 일반 후속 설명입니다.".repeat(15);
            String compressed = new DynamicContextCompressor(properties).compressMemoryForPrompt(
                    "이 대화의 마지막으로 정정된 색상을 말해줘.", memory);
            assertEquals(Boolean.TRUE, TraceStore.get("prompt.memory.compressor.activated"));
            String prompt = orchestrator.orchestrate("이 대화의 마지막으로 정정된 색상을 말해줘.",
                    List.of(), List.of(), Map.of(), null, compressed);
            assertTrue(prompt.contains(navy));
            assertTrue(prompt.lastIndexOf(navy) < prompt.lastIndexOf(teal),
                    "the bounded delivered summary must place the latest unique correction last");
            assertTrue(prompt.lastIndexOf(teal) > prompt.lastIndexOf(navy),
                    "the latest repeated correction must follow the intervening correction in the delivered prompt");
            org.mockito.Mockito.verify(messages, org.mockito.Mockito.never()).save(any(ChatMessage.class));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void threeSessionStoredMemoryLoadsCompressesAndEntersOnlyItsOwnPrompt() {
        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        Map<Long, ChatSession> sessionById = new HashMap<>();
        Map<Long, List<ChatMessage>> storedRows = new HashMap<>();
        AtomicLong nextMessageId = new AtomicLong();
        for (long sessionId : new long[] {11L, 12L, 13L}) {
            ChatSession session = new ChatSession("synthetic memory session");
            session.setId(sessionId);
            sessionById.put(sessionId, session);
        }
        when(sessions.findById(anyLong())).thenAnswer(call ->
                Optional.ofNullable(sessionById.get(call.getArgument(0))));
        when(messages.save(any(ChatMessage.class))).thenAnswer(call -> {
            ChatMessage row = call.getArgument(0);
            long id = nextMessageId.incrementAndGet();
            row.setId(id);
            row.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(id));
            storedRows.computeIfAbsent(row.getSession().getId(), ignored -> new ArrayList<>()).add(row);
            return row;
        });
        when(messages.findNewestWindowBySessionId(anyLong(), any(Pageable.class)))
                .thenAnswer(call -> {
                    Long sessionId = call.getArgument(0);
                    Pageable page = call.getArgument(1);
                    List<ChatMessage> newest = new ArrayList<>(
                            storedRows.getOrDefault(sessionId, List.of()));
                    Collections.reverse(newest);
                    int start = (int) Math.min(newest.size(), page.getOffset());
                    int end = Math.min(newest.size(), start + page.getPageSize());
                    return newest.subList(start, end);
                });

        ChatHistoryServiceImpl history = newService(sessions, messages);
        String[] markers = {"alphamemory", "betamemory", "gammamemory"};
        for (int index = 0; index < markers.length; index++) {
            long sessionId = 11L + index;
            assertTrue(history.appendMessageReturningId(sessionId, "user",
                    markers[index] + " selected session fact") != null);
            for (int filler = 0; filler < 3; filler++) {
                assertTrue(history.appendMessageReturningId(sessionId, "assistant",
                        "ordinary filler " + filler) != null);
            }
            assertEquals(4, storedRows.get(sessionId).size());
        }

        MemoryHandler loader = new MemoryHandler(history);
        ReflectionTestUtils.setField(loader, "maxTurns", 8);
        NovaOrchestrationProperties properties = new NovaOrchestrationProperties();
        properties.getRagCompressor().setMemoryMaxLines(3);
        ContextOrchestrator orchestrator = new ContextOrchestrator(new StandardPromptBuilder());
        ReflectionTestUtils.setField(orchestrator, "promptContextCompressor",
                new DynamicContextCompressor(properties));
        OverdriveGuard guard = mock(OverdriveGuard.class);
        when(guard.shouldActivate(anyString(), anyList())).thenReturn(true);
        ReflectionTestUtils.setField(orchestrator, "overdriveGuard", guard);

        try {
            for (int index = 0; index < markers.length; index++) {
                TraceStore.clear();
                String marker = markers[index];
                String memory = loader.loadForSession(11L + index);
                assertTrue(memory != null && memory.contains(marker));
                String prompt = orchestrator.orchestrate(
                        marker, List.of(), List.of(), Map.of(), null, memory);
                assertTrue(prompt.contains("### MEMORY"));
                assertTrue(prompt.contains(marker));
                for (int other = 0; other < markers.length; other++) {
                    if (other != index) {
                        assertFalse(prompt.contains(markers[other]));
                    }
                }
                assertEquals(Boolean.TRUE, TraceStore.get("prompt.memory.compressor.activated"));
            }
        } finally {
            TraceStore.clear();
        }
    }

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
        var pages = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(messages, org.mockito.Mockito.times(2))
                .findNewestWindowBySessionId(eq(42L), pages.capture());
        assertEquals(List.of(2, 128), pages.getAllValues().stream()
                .map(Pageable::getPageSize).sorted().toList());
        assertTrue(pages.getAllValues().stream().allMatch(page -> page.getPageNumber() == 0));
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
