package com.example.lms.api;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.domain.enums.MemoryProfile;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatHistoryServiceImpl;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionWindowContractTest {

    @Test
    void sessionListClampsRequestedLimitAtRepositoryBoundary() {
        Fixture fixture = fixture();
        when(fixture.sessions.findByAdministrator_UsernameOrderByCreatedAtDesc(
                eq("admin-a"), any(Pageable.class)))
                .thenAnswer(invocation -> sessions(invocation.getArgument(1, Pageable.class).getPageSize()));

        List<ChatSession> result = fixture.service.getSessionsForUser("admin-a", 500);

        assertEquals(100, result.size());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(fixture.sessions).findByAdministrator_UsernameOrderByCreatedAtDesc(
                eq("admin-a"), pageable.capture());
        assertEquals(100, pageable.getValue().getPageSize());
    }

    @Test
    void detailLoadsNewestTwoHundredThenReturnsChronologicalOrder() {
        Fixture fixture = fixture();
        ChatSession session = new ChatSession("window");
        session.setId(7L);
        when(fixture.sessions.findById(7L)).thenReturn(Optional.of(session));
        when(fixture.messages.findNewestWindowBySessionId(
                eq(7L), any(Pageable.class)))
                .thenReturn(IntStream.rangeClosed(151, 350)
                        .map(i -> 501 - i)
                        .mapToObj(id -> message(session, (long) id))
                        .toList());

        ChatSession result = fixture.service.getSessionWithMessages(7L, 350);

        assertEquals(200, result.getMessages().size());
        assertEquals(151L, result.getMessages().get(0).getId());
        assertEquals(350L, result.getMessages().get(199).getId());
        assertTrue(IntStream.range(1, result.getMessages().size())
                .allMatch(i -> result.getMessages().get(i - 1).getCreatedAt()
                        .compareTo(result.getMessages().get(i).getCreatedAt()) <= 0));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(fixture.messages).findNewestWindowBySessionId(eq(7L), pageable.capture());
        assertEquals(200, pageable.getValue().getPageSize());
    }

    @Test
    void newestWindowQueryMakesNullAndTimestampTieOrderingDeterministic() throws Exception {
        Method method = ChatMessageRepository.class.getMethod(
                "findNewestWindowBySessionId", Long.class, Pageable.class);
        Query query = method.getAnnotation(Query.class);

        assertTrue(query != null, "the bounded detail query must be explicit");
        String normalized = query.value().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(normalized.contains("case when m.createdat is null then 1 else 0 end asc"));
        assertTrue(normalized.contains("m.createdat desc"));
        assertTrue(normalized.contains("m.id desc"));
    }

    @Test
    void anonymousOwnerQuotaRejectsBeforeSessionOrMessageSave() {
        Fixture fixture = fixture();
        when(fixture.sessions.countByOwnerKey("owner-a")).thenReturn(200L);

        ChatHistoryService.SessionQuotaExceededException failure = assertThrows(
                ChatHistoryService.SessionQuotaExceededException.class,
                () -> fixture.service.startNewSession(
                        "hello", "anonymousUser", null, "owner-a", MemoryProfile.LIGHT));

        assertEquals("session_quota_exceeded", failure.getMessage());
        verify(fixture.sessions, never()).save(any());
        verify(fixture.messages, never()).save(any());
    }

    @Test
    void administratorQuotaRejectsBeforeSessionSave() {
        Fixture fixture = fixture();
        when(fixture.sessions.countByAdministrator_Username("admin-a")).thenReturn(200L);

        assertThrows(ChatHistoryService.SessionQuotaExceededException.class,
                () -> fixture.service.startNewSession("hello", "admin-a", null));

        verify(fixture.sessions, never()).save(any());
    }

    @Test
    void concurrentPersistenceConflictRechecksQuotaBeforeClassifyingFailure() {
        Fixture fixture = fixture();
        when(fixture.sessions.countByOwnerKey("owner-a")).thenReturn(199L, 200L);
        when(fixture.sessions.save(any(ChatSession.class)))
                .thenThrow(new DataIntegrityViolationException("synthetic-conflict"));

        assertThrows(ChatHistoryService.SessionQuotaExceededException.class,
                () -> fixture.service.startNewSession(
                        "hello", "anonymousUser", null, "owner-a", MemoryProfile.LIGHT));

        verify(fixture.sessions).save(any(ChatSession.class));
        verify(fixture.messages, never()).save(any());
    }

    @Test
    void concurrentAnonymousCreatesAtCountOneHundredNinetyNineAdmitExactlyOne() throws Exception {
        Fixture fixture = fixture();
        when(fixture.sessions.countByOwnerKey("owner-a")).thenReturn(199L);

        AtomicInteger sessionSaveCalls = new AtomicInteger();
        CountDownLatch firstSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        when(fixture.sessions.save(any(ChatSession.class))).thenAnswer(invocation -> {
            int call = sessionSaveCalls.incrementAndGet();
            if (call == 1) {
                firstSaveEntered.countDown();
                assertTrue(releaseFirstSave.await(2, TimeUnit.SECONDS));
            }
            ChatSession saved = invocation.getArgument(0);
            saved.setId((long) call);
            return saved;
        });
        when(fixture.messages.save(any(ChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ChatSession>> first = executor.submit(() -> fixture.service.startNewSession(
                    "first", "anonymousUser", null, "owner-a", MemoryProfile.LIGHT));
            assertTrue(firstSaveEntered.await(2, TimeUnit.SECONDS));

            Future<Object> competing = executor.submit(() -> resultOrFailure(() ->
                    fixture.service.startNewSession(
                            "second", "anonymousUser", null, "owner-a", MemoryProfile.LIGHT)));
            Object competingOutcome = competing.get(2, TimeUnit.SECONDS);

            releaseFirstSave.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS).isPresent());
            assertInstanceOf(ChatHistoryService.SessionQuotaExceededException.class, competingOutcome);
        } finally {
            releaseFirstSave.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, sessionSaveCalls.get());
        verify(fixture.sessions, times(1)).save(any(ChatSession.class));
        verify(fixture.messages, times(1)).save(any(ChatMessage.class));
    }

    @Test
    void concurrentAdministratorCreatesAtCountOneHundredNinetyNineAdmitExactlyOne() throws Exception {
        Fixture fixture = fixture();
        Administrator administrator = new Administrator("admin-a", "unused", "Admin");
        when(fixture.sessions.countByAdministrator_Username("admin-a")).thenReturn(199L);
        when(fixture.administrators.findByUsername("admin-a")).thenReturn(Optional.of(administrator));

        AtomicInteger sessionSaveCalls = new AtomicInteger();
        CountDownLatch firstSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        when(fixture.sessions.save(any(ChatSession.class))).thenAnswer(invocation -> {
            int call = sessionSaveCalls.incrementAndGet();
            if (call == 1) {
                firstSaveEntered.countDown();
                assertTrue(releaseFirstSave.await(2, TimeUnit.SECONDS));
            }
            ChatSession saved = invocation.getArgument(0);
            saved.setId((long) call);
            return saved;
        });
        when(fixture.messages.save(any(ChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ChatSession>> first = executor.submit(
                    () -> fixture.service.startNewSession("first", "admin-a", null));
            assertTrue(firstSaveEntered.await(2, TimeUnit.SECONDS));

            Future<Object> competing = executor.submit(() -> resultOrFailure(
                    () -> fixture.service.startNewSession("second", "admin-a", null)));
            Object competingOutcome = competing.get(2, TimeUnit.SECONDS);

            releaseFirstSave.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS).isPresent());
            assertInstanceOf(ChatHistoryService.SessionQuotaExceededException.class, competingOutcome);
        } finally {
            releaseFirstSave.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, sessionSaveCalls.get());
        verify(fixture.sessions, times(1)).save(any(ChatSession.class));
        verify(fixture.messages, times(1)).save(any(ChatMessage.class));
    }

    @Test
    void bothTitleDerivationPathsFitEntityColumnLimit() {
        Fixture fixture = fixture();
        Administrator administrator = new Administrator("admin-a", "unused", "Admin");
        when(fixture.administrators.findByUsername("admin-a")).thenReturn(Optional.of(administrator));
        when(fixture.sessions.countByAdministrator_Username("admin-a")).thenReturn(0L);
        when(fixture.sessions.countByOwnerKey("owner-a")).thenReturn(0L);
        when(fixture.sessions.save(any(ChatSession.class))).thenAnswer(invocation -> {
            ChatSession saved = invocation.getArgument(0);
            saved.setId(saved.getAdministrator() == null ? 2L : 1L);
            return saved;
        });

        ChatSession administratorSession = fixture.service
                .startNewSession("x".repeat(500), "admin-a", null)
                .orElseThrow();
        ChatSession anonymousSession = fixture.service
                .startNewSession("y".repeat(500), "anonymousUser", null, "owner-a", MemoryProfile.LIGHT)
                .orElseThrow();

        assertTrue(administratorSession.getTitle().length() <= 120);
        assertTrue(anonymousSession.getTitle().length() <= 120);
        assertEquals(29, administratorSession.getTitle().length());
        assertEquals(29, anonymousSession.getTitle().length());
    }

    private static Fixture fixture() {
        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        AdministratorRepository administrators = mock(AdministratorRepository.class);
        ChatHistoryServiceImpl service = new ChatHistoryServiceImpl(
                sessions,
                messages,
                administrators,
                new ObjectMapper(),
                mock(ClientOwnerKeyResolver.class));
        return new Fixture(service, sessions, messages, administrators);
    }

    private static List<ChatSession> sessions(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    ChatSession session = new ChatSession("s-" + i);
                    session.setId((long) i + 1L);
                    session.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(i));
                    return session;
                })
                .toList();
    }

    private static ChatMessage message(ChatSession session, long id) {
        ChatMessage message = new ChatMessage(session, "assistant", "m-" + id);
        message.setId(id);
        message.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(id));
        return message;
    }

    private static Object resultOrFailure(SessionCreateAction action) {
        try {
            return action.run();
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface SessionCreateAction {
        Optional<ChatSession> run() throws Exception;
    }

    private record Fixture(
            ChatHistoryServiceImpl service,
            ChatSessionRepository sessions,
            ChatMessageRepository messages,
            AdministratorRepository administrators) {
    }
}
