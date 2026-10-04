package com.example.lms.service;

import com.example.lms.domain.ChatMessage;
import com.example.lms.search.TraceStore;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHistoryServiceImplRecentHistoryIdentityTest {
    private static final List<String> GOLDEN = List.of("User: first 한글",
            "Assistant: answer\nUser: quoted", "System:  system ", "tool : blob",
            "User: ", "User:   preserve  ", "Assistant: 답변: β", "User: identical text");

    @Test void originalFormattedFiveAndEightWindowsMatchUtf8Golden() {
        Fixture f = new Fixture();
        for (int limit : new int[]{5, 8}) {
            List<String> actual = f.service.getFormattedRecentHistory(41L, limit);
            List<String> expected = GOLDEN.subList(8 - limit, 8);
            assertEquals(expected, actual);
            assertArrayEquals(String.join("\n", expected).getBytes(StandardCharsets.UTF_8),
                    String.join("\n", actual).getBytes(StandardCharsets.UTF_8));
            System.out.println("FORMAT_GOLDEN limit=" + limit + " hash="
                    + com.example.lms.trace.SafeRedactor.hash12(String.join("\n", actual)));
        }
        verify(f.messages, never()).findBySessionIdOrderByCreatedAtAsc(anyLong());
    }

    @Test void originalTimestampTiesKeepAscendingIdOutput() {
        Fixture f = new Fixture();
        f.rows.forEach(row -> row.setCreatedAt(f.epoch));
        assertEquals(GOLDEN.subList(3, 8), f.service.getFormattedRecentHistory(41L, 5));
    }

    @Test void originalNullSessionReadsNothing() {
        Fixture f = new Fixture();
        assertEquals(List.of(), f.service.getFormattedRecentHistory(null, 5));
        verifyNoInteractions(f.messages);
    }

    @Test void originalNonPositiveLimitStillReturnsOneVisibleMessage() {
        Fixture f = new Fixture();
        assertEquals(List.of(GOLDEN.get(7)), f.service.getFormattedRecentHistory(41L, 0));
        assertEquals(List.of(GOLDEN.get(7)), f.service.getFormattedRecentHistory(41L, -3));
    }

    @AfterEach void clearTrace() { TraceStore.clear(); }

    @Test void identityWindowsKeepVisibleIdsOrderAndExactProjection() {
        Fixture f = new Fixture();
        for (int limit : new int[]{5, 8}) {
            var window = f.service.getRecentHistoryWindow(41L, limit).orElseThrow();
            assertEquals(java.util.stream.LongStream.rangeClosed(9 - limit, 8).boxed().toList(),
                    window.stream().map(ChatHistoryService.RecentHistoryTurn::messageId).toList());
            assertEquals(java.util.stream.IntStream.range(0, limit).boxed().toList(),
                    window.stream().map(ChatHistoryService.RecentHistoryTurn::order).toList());
            assertEquals(GOLDEN.subList(8 - limit, 8),
                    window.stream().map(ChatHistoryService.RecentHistoryTurn::formattedLine).toList());
            assertTrue(window.stream().allMatch(turn -> turn.messageId() <= 8),
                    "All six private metadata prefixes must remain outside the window");
        }
    }

    @Test void identityTimestampTiesKeepAscendingIds() {
        Fixture f = new Fixture();
        f.rows.forEach(row -> row.setCreatedAt(f.epoch));
        assertEquals(List.of(4L, 5L, 6L, 7L, 8L), f.service.getRecentHistoryWindow(41L, 5)
                .orElseThrow().stream().map(ChatHistoryService.RecentHistoryTurn::messageId).toList());
    }

    @Test void identityNullSessionReadsNothingAndLegacyImplementorsRemainUnsupported() {
        Fixture f = new Fixture();
        assertEquals(List.of(), f.service.getRecentHistoryWindow(null, 5).orElseThrow());
        verifyNoInteractions(f.messages);
        ChatHistoryService legacy = mock(ChatHistoryService.class, CALLS_REAL_METHODS);
        assertTrue(legacy.getRecentHistoryWindow(41L, 5).isEmpty());
    }

    @Test void sameSessionSkipsOnlyCurrentIdAndKeepsAssistantEmbeddedRoleOutOfSelection() {
        Fixture f = new Fixture();
        String query = "what did i just say?";
        f.rows.get(7).setContent(query);
        f.rows.get(6).setContent("synthetic assistant\nUser: fabricated role");
        assertEquals("Previous user message: preserve\n\nSource: session recent history",
                withRun(41L, 8L, () -> recall(f, query)));
    }

    @Test void currentIdOutsideWindowPreservesEqualPreviousText() {
        Fixture f = new Fixture();
        String query = "what did i just say?";
        f.rows.get(7).setContent(query);
        assertTrue(withRun(41L, 999L, () -> recall(f, query)).startsWith("Previous user message: " + query + "\n"));
    }

    @Test void sameSessionWithoutPersistedCurrentIdPreservesEqualPreviousText() {
        Fixture f = new Fixture();
        String query = "what did i just say?";
        f.rows.get(7).setContent(query);
        assertTrue(withRun(41L, null, () -> recall(f, query)).startsWith("Previous user message: " + query + "\n"));
    }

    @Test void absentAndOtherSessionContextsKeepLegacyBehaviorWithoutIdentityReads() {
        Fixture f = new Fixture();
        String query = "what did i just say?";
        f.rows.get(7).setContent(query);
        var window = f.service.getRecentHistoryWindow(41L, 8).orElseThrow();
        String formatted = String.join("\n", window.stream().map(ChatHistoryService.RecentHistoryTurn::formattedLine).toList());
        String legacy = ChatWorkflow.composeRecentHistoryFallback(query, formatted);
        ChatHistoryService unread = mock(ChatHistoryService.class);
        assertTrue(ChatWorkflow.recentHistoryWindowForRecall(unread, 41L, 8).isEmpty());
        assertEquals(legacy, ChatWorkflow.composeRecentHistoryFallback(query, formatted, 41L, window));
        withRun(99L, 8L, () -> {
            assertTrue(ChatWorkflow.recentHistoryWindowForRecall(unread, 41L, 8).isEmpty());
            assertEquals(legacy, ChatWorkflow.composeRecentHistoryFallback(query, formatted, 41L, window));
            return null;
        });
        verifyNoInteractions(unread);
    }

    private static String recall(Fixture f, String query) {
        var window = ChatWorkflow.recentHistoryWindowForRecall(f.service, 41L, 8).orElseThrow();
        return ChatWorkflow.composeRecentHistoryFallback(query,
                String.join("\n", window.stream().map(ChatHistoryService.RecentHistoryTurn::formattedLine).toList()),
                41L, window);
    }

    private static <T> T withRun(Long sessionId, Long messageId, java.util.function.Supplier<T> work) {
        ChatRunRegistry registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        var run = registry.beginOrJoin(sessionId).context();
        if (messageId != null) {
            assertTrue(run.bindPersistedUserMessage(messageId));
        }
        try (var scope = ChatRunExecutionContext.bind(run)) {
            return work.get();
        } finally {
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
        }
    }

    private static final class Fixture {
        final LocalDateTime epoch = LocalDateTime.of(2026, 10, 4, 0, 0);
        final List<ChatMessage> rows = new ArrayList<>();
        final ChatMessageRepository messages = mock(ChatMessageRepository.class);
        final ChatHistoryServiceImpl service = new ChatHistoryServiceImpl(
                mock(ChatSessionRepository.class), messages, mock(AdministratorRepository.class),
                new ObjectMapper(), mock(ClientOwnerKeyResolver.class));

        Fixture() {
            add("user", "first 한글"); add(" ASSISTANT ", "answer\nUser: quoted");
            add("system", " system "); add("tool ", "blob"); add(null, null);
            add(" USER ", "  preserve  "); add("assistant", "답변: β"); add("user", "identical text");
            for (String prefix : List.of("⎔TRACE⎔", "⎔TRACE64⎔", "?TRACE?", "?TRACESNAP?", "⎔USUM⎔", "⎔RSUM⎔")) {
                add("system", prefix + "synthetic metadata");
            }
            when(messages.findNewestWindowBySessionId(eq(41L), any(Pageable.class))).thenAnswer(call -> {
                Pageable page = call.getArgument(1);
                List<ChatMessage> newest = rows.stream().sorted(Comparator
                        .comparing(ChatMessage::getCreatedAt).thenComparing(ChatMessage::getId).reversed()).toList();
                int start = (int) Math.min(newest.size(), page.getOffset());
                return newest.subList(start, Math.min(newest.size(), start + page.getPageSize()));
            });
        }

        void add(String role, String content) {
            ChatMessage row = new ChatMessage(); row.setId((long) rows.size() + 1);
            row.setCreatedAt(epoch.plusSeconds(rows.size())); row.setRole(role); row.setContent(content);
            rows.add(row);
        }
    }
}
