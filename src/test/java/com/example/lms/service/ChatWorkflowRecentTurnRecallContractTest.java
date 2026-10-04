package com.example.lms.service;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline repository fixture; history formatting and recall use production implementations. */
class ChatWorkflowRecentTurnRecallContractTest {
    private static final String U1 = "하이젠베르크 불확정성 원리가 뭐냐?";
    private static final String U2 = "내가방금 뭐라고 헀냐?";
    private static final String U3 = "원신에서 스커크가 뭐냐?";
    private static final String U4 = "내가 방금 뭐라고 헀냐?";
    private static final String U5 = "내가 아까 뭐라고 헀더라..";

    @AfterEach void clearTrace() { TraceStore.clear(); }

    @Test void r1OriginalSequenceSelectsSkirkAndAssertsFiveAndEightMessageWindows() {
        Fixture f = firstRecall();
        for (int limit : new int[]{5, 8}) {
            f.assertWindow(limit, List.of(1L, 2L, 3L), List.of("user", "assistant", "user"));
            assertAnswer(U1, f.recall(U2, limit));
        }
        f.append("assistant", "synthetic recall answer");
        f.append("user", U3);
        f.append("assistant", "synthetic Skirk answer");
        f.append("user", U4);
        f.assertWindow(5, List.of(3L, 4L, 5L, 6L, 7L),
                List.of("user", "assistant", "user", "assistant", "user"));
        f.assertWindow(8, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L),
                List.of("user", "assistant", "user", "assistant", "user", "assistant", "user"));
        assertAnswer(U3, f.recall(U4, 5));
        assertAnswer(U3, f.recall(U4, 8));
    }

    @Test void r2aOrdinaryAssistantAndProgressRowsCanDisplaceUserButCannotRestoreOlderTopic() {
        Fixture f = beforeSkirkAnswer();
        f.append("assistant", "synthetic answer segment");
        f.append("system", "synthetic progress");
        f.append("assistant", "synthetic evidence row");
        f.append("assistant", "synthetic final segment");
        f.append("user", U4);
        f.assertWindow(5, List.of(6L, 7L, 8L, 9L, 10L),
                List.of("assistant", "system", "assistant", "assistant", "user"));
        assertNull(f.recall(U4, 5), "Displaced U3 must not invent U1 outside the window");
        assertAnswer(U3, f.recall(U4, 8));
    }

    @Test void r2bMissingOrDelayedAssistantDoesNotDropPersistedUserTurn() {
        Fixture f = beforeSkirkAnswer();
        f.append("user", U4); // A3 has not been stored: the user row remains visible.
        f.assertWindow(5, List.of(2L, 3L, 4L, 5L, 6L),
                List.of("assistant", "user", "assistant", "user", "user"));
        assertAnswer(U3, f.recall(U4, 5));
    }

    @Test void r2cOlderAssistantQuotingHeisenbergDoesNotOverrideNewerSkirkUser() {
        Fixture f = firstRecall();
        f.append("assistant", "synthetic quotation\nUser: " + U1);
        f.append("user", U3);
        f.append("assistant", "synthetic Skirk answer");
        f.append("user", U4);
        assertAnswer(U3, f.recall(U4, 5));
    }

    @Test void r2dStaleSuppliedPairsCannotProvideFreshPersistedSkirk() {
        Fixture f = originalSequence();
        ChatConversationContext stale = new ChatConversationContext(List.of(
                new ChatConversationContext.Turn(U1, "synthetic first answer"),
                new ChatConversationContext.Turn(U2, "synthetic recall answer")), "", List.of());
        String staleAnswer = ChatWorkflow.composeRecentHistoryFallback(U4,
                String.join("\n", stale.interpretationHistory()));
        System.out.println("R2D staleSelected=" + (staleAnswer == null ? "NULL"
                : staleAnswer.contains(U1) ? "U1" : staleAnswer.contains(U2) ? "U2" : "OTHER"));
        assertAll(() -> assertAnswer(U3, f.recall(U4, 5)),
                () -> assertAnswer(U2, staleAnswer));
    }

    @Test void r2eSameCreatedAtUsesIdTieAndKeepsNewestUser() {
        Fixture f = originalSequence();
        f.rows.forEach(row -> row.setCreatedAt(f.epoch));
        f.assertWindow(5, List.of(3L, 4L, 5L, 6L, 7L),
                List.of("user", "assistant", "user", "assistant", "user"));
        assertAnswer(U3, f.recall(U4, 5));
    }

    @Test void r3PreviousUserTurnIncludesThePreviousRecallQuestion() {
        Fixture f = originalSequence();
        f.append("assistant", "synthetic fourth answer");
        String query = "내가 직전에 보낸 사용자 메시지를 그대로 말해줘";
        f.append("user", query);
        assertAnswer(U4, f.recall(query, 5));
    }

    @Test void r3PreviousUserTurnIncludesLabelOnlyMessage() {
        Fixture f = beforeSkirkAnswer();
        f.append("assistant", "synthetic Skirk answer");
        f.append("user", "코드명");
        f.append("assistant", "synthetic label answer");
        String query = "내가 직전에 보낸 사용자 메시지를 그대로 말해줘";
        f.append("user", query);
        assertAnswer("코드명", f.recall(query, 5));
    }

    @Test void r3QualifiedCodeRecallKeepsValueSourcePolicy() {
        Fixture f = new Fixture();
        String source = "다음턴 자동스크롤 확인용 문장: 백금색-463.";
        f.append("user", source);
        f.append("assistant", "synthetic source answer");
        f.append("user", "방금 내가 뭐라고 했지?");
        f.append("assistant", "synthetic recall answer");
        String query = "새로고침한 뒤에도 내가 전에 말한 색상 코드를 뭐라고 했지? 짧게 답해줘.";
        f.append("user", query);
        String answer = f.recall(query, 5);
        assertNotNull(answer);
        assertTrue(answer.contains("백금색-463"));
        assertFalse(answer.contains("직전 사용자 메시지: 방금 내가 뭐라고 했지?"));
    }

    @Test void r3OnlyCurrentOccurrenceIsExcludedWhenOlderMessageHasSameText() {
        Fixture f = new Fixture();
        f.append("user", U3);
        f.append("assistant", "synthetic topic answer");
        f.append("user", U4);
        f.append("assistant", "synthetic recall answer");
        f.append("user", U4);
        f.currentRequest(5L);
        f.assertWindow(5, List.of(1L, 2L, 3L, 4L, 5L),
                List.of("user", "assistant", "user", "assistant", "user"));
        assertAnswer(U4, f.recall(U4, 5));
    }

    @Test void r3HistoryWithoutCurrentPreservesEqualPreviousMessage() {
        Fixture f = new Fixture();
        f.append("user", U3);
        f.append("assistant", "synthetic topic answer");
        f.append("user", U4);
        f.currentRequest(null);
        // Current request has not been appended; this equal-text row belongs to an older ID.
        assertAnswer(U4, f.recall(U4, 5));
    }

    @Test void r3ExplicitPreviousTopicSkipsRecallAndLabelOnlyTurns() {
        Fixture f = originalSequence();
        f.append("assistant", "synthetic recall answer");
        f.append("user", "코드명만");
        f.append("assistant", "synthetic label answer");
        String query = "직전 주제가 뭐였지?";
        f.append("user", query);
        assertTrue(f.recall(query, 8) != null && f.recall(query, 8).contains(U3),
                "Explicit topic recall must select U3 rather than U4");
    }

    @Test void r4EarlierAmbiguousQuestionIsNotRewrittenAsPreviousTurn() {
        Fixture f = originalSequence();
        f.append("assistant", "synthetic fourth answer");
        f.append("user", U5);
        f.assertWindow(8, List.of(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L),
                List.of("assistant", "user", "assistant", "user", "assistant", "user", "assistant", "user"));
        assertEquals(List.of(U2, U3, U4, U5), f.history(8).stream()
                .filter(line -> line.startsWith("User: ")).map(line -> line.substring(6)).toList());
        assertNull(f.recall(U5, 8), "Ambiguous earlier recall stays outside deterministic previous-turn selection");
    }

    @Test void sameQuestionsInSeparateSessionsRemainIsolated() {
        Fixture a = new Fixture(41L, "synthetic-owner-A");
        Fixture b = new Fixture(42L, "synthetic-owner-B");
        a.append("user", U1); b.append("user", U3);
        a.append("user", U4); b.append("user", U4);
        assertAnswer(U1, a.recall(U4, 5));
        assertAnswer(U3, b.recall(U4, 5));
        verify(a.messages, never()).findNewestWindowBySessionId(eq(42L), any(Pageable.class));
        verify(b.messages, never()).findNewestWindowBySessionId(eq(41L), any(Pageable.class));
    }

    @Test void traceMetadataDoesNotDisplaceVisibleUserFromBoundedWindow() {
        Fixture f = beforeSkirkAnswer();
        for (String prefix : List.of("⎔TRACE⎔", "⎔TRACE64⎔", "?TRACE?", "?TRACESNAP?", "⎔USUM⎔", "⎔RSUM⎔")) {
            f.append("system", prefix + "synthetic metadata");
        }
        f.append("user", U4);
        assertAnswer(U3, f.recall(U4, 5));
        verify(f.messages, never()).findBySessionIdOrderByCreatedAtAsc(anyLong());
    }

    private static Fixture firstRecall() {
        Fixture f = new Fixture();
        f.append("user", U1); f.append("assistant", "synthetic first answer"); f.append("user", U2);
        return f;
    }
    private static Fixture beforeSkirkAnswer() {
        Fixture f = firstRecall();
        f.append("assistant", "synthetic recall answer"); f.append("user", U3);
        return f;
    }
    private static Fixture originalSequence() {
        Fixture f = beforeSkirkAnswer();
        f.append("assistant", "synthetic Skirk answer"); f.append("user", U4);
        return f;
    }
    private static void assertAnswer(String expected, String answer) {
        assertNotNull(answer, "Recall must return the selected synthetic user turn");
        assertTrue(answer.startsWith("직전 사용자 메시지: " + expected + "\n"),
                "Recall selected the wrong synthetic user turn");
    }

    private static final class Fixture {
        final long sessionId;
        final List<ChatMessage> rows = new ArrayList<>();
        final LocalDateTime epoch = LocalDateTime.of(2026, 10, 4, 0, 0);
        final ChatMessageRepository messages = mock(ChatMessageRepository.class);
        final ChatHistoryServiceImpl service;
        boolean currentRequestBound;
        Long currentMessageId;
        void currentRequest(Long messageId) {
            currentRequestBound = true;
            currentMessageId = messageId;
        }
        Fixture() { this(41L, "synthetic-owner-A"); }
        Fixture(long id, String owner) {
            sessionId = id;
            ChatSession session = new ChatSession("synthetic recall fixture");
            session.setId(id); session.setOwnerKey(owner); session.setOwnerType("ANON");
            ChatSessionRepository sessions = mock(ChatSessionRepository.class);
            when(sessions.findById(id)).thenReturn(Optional.of(session));
            when(messages.save(any(ChatMessage.class))).thenAnswer(call -> {
                ChatMessage row = call.getArgument(0);
                row.setId((long) rows.size() + 1); row.setCreatedAt(epoch.plusSeconds(rows.size()));
                rows.add(row); return row;
            });
            when(messages.findNewestWindowBySessionId(eq(id), any(Pageable.class))).thenAnswer(call -> {
                Pageable page = call.getArgument(1);
                List<ChatMessage> newest = rows.stream().sorted(Comparator
                        .comparing(ChatMessage::getCreatedAt).thenComparing(ChatMessage::getId).reversed()).toList();
                int start = (int) Math.min(newest.size(), page.getOffset());
                return newest.subList(start, Math.min(newest.size(), start + page.getPageSize()));
            });
            service = new ChatHistoryServiceImpl(sessions, messages, mock(AdministratorRepository.class),
                    new ObjectMapper(), mock(ClientOwnerKeyResolver.class));
        }
        void append(String role, String content) {
            assertNotNull(service.appendMessageReturningId(sessionId, role, content));
        }
        List<String> history(int limit) { return service.getFormattedRecentHistory(sessionId, limit); }
        String recall(String query, int limit) {
            if (currentRequestBound) {
                ChatRunRegistry registry = new ChatRunRegistry();
                ReflectionTestUtils.setField(registry, "replayCapacity", 16);
                var run = registry.beginOrJoin(sessionId).context();
                if (currentMessageId != null) {
                    assertTrue(run.bindPersistedUserMessage(currentMessageId));
                }
                try (var scope = ChatRunExecutionContext.bind(run)) {
                    var window = ChatWorkflow.recentHistoryWindowForRecall(service, sessionId, limit).orElseThrow();
                    return ChatWorkflow.composeRecentHistoryFallback(query,
                            String.join("\n", window.stream().map(ChatHistoryService.RecentHistoryTurn::formattedLine).toList()),
                            sessionId, window);
                } finally {
                    ReflectionTestUtils.invokeMethod(registry, "shutdown");
                }
            }
            return ChatWorkflow.composeRecentHistoryFallback(query, String.join("\n", history(limit)));
        }
        void assertWindow(int limit, List<Long> ids, List<String> roles) {
            List<String> formatted = history(limit);
            assertEquals(roles.size(), formatted.size());
            List<ChatMessage> selected = ids.stream().map(id -> rows.stream()
                    .filter(row -> row.getId().equals(id)).findFirst().orElseThrow()).toList();
            assertEquals(ids, selected.stream().map(ChatMessage::getId).toList());
            assertEquals(roles, selected.stream().map(ChatMessage::getRole).toList());
            assertEquals(selected.stream().map(row -> {
                String role = row.getRole();
                return role.substring(0, 1).toUpperCase(Locale.ROOT) + role.substring(1) + ": " + row.getContent();
            }).toList(), formatted, "Actual service window must match asserted row identities and roles");
            for (int i = 1; i < selected.size(); i++) {
                ChatMessage previous = selected.get(i - 1), next = selected.get(i);
                int timeOrder = previous.getCreatedAt().compareTo(next.getCreatedAt());
                assertTrue(timeOrder < 0 || timeOrder == 0 && previous.getId() < next.getId());
            }
        }
    }
}
