package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatHistoryServiceImpl;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatApiControllerExtraQuotaTest {
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ClientOwnerKeyResolver owners = mock(ClientOwnerKeyResolver.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(owners.ownerKey()).thenReturn("owner-a");
        when(sessions.save(any(ChatSession.class))).thenAnswer(invocation -> saved(invocation.getArgument(0), 1L));
        ChatHistoryServiceImpl service = new ChatHistoryServiceImpl(
                sessions, messages, mock(AdministratorRepository.class), mapper, owners);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(ChatSessionRepository.class, () -> sessions);
        context.registerBean(ClientOwnerKeyResolver.class, () -> owners);
        context.registerBean(ChatHistoryServiceImpl.class, () -> service);
        context.registerBean(ChatApiControllerExtra.class);
        context.refresh();
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(ChatApiControllerExtra.class))
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();
    }

    @AfterEach
    void close() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            completeTransaction();
        }
        context.close();
    }

    @Test
    void fullOwnerIsRejectedBeforeAnySessionOrMessageSave() throws Exception {
        when(sessions.countByOwnerKey("owner-a")).thenReturn(200L);
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"draft\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.reasonCode").value("session_quota_exceeded"));
        verify(sessions, never()).save(any());
        verifyNoInteractions(messages);
    }

    @Test
    void successfulEmptySessionPreservesTitleOwnerAndDto() throws Exception {
        String body = mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  draft  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("  draft  "))
                .andExpect(jsonPath("$.id").value(1)).andReturn().getResponse().getContentAsString();
        assertEquals(3, mapper.readTree(body).size());
        verify(sessions).save(argThat(session -> "owner-a".equals(session.getOwnerKey())
                && "ANON".equals(session.getOwnerType()) && "  draft  ".equals(session.getTitle())));
        verifyNoInteractions(messages);
    }

    @Test
    void missingTitleUsesExistingDefaultAndMissingOwnerRemainsBadRequest() throws Exception {
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("New Session"));
        clearInvocations(sessions);
        when(owners.ownerKey()).thenReturn(null);
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        verifyNoInteractions(sessions, messages);
    }

    @Test
    void fullOwnerDoesNotConsumeAnotherOwnersQuota() throws Exception {
        when(sessions.countByOwnerKey("owner-a")).thenReturn(200L);
        when(owners.ownerKey()).thenReturn("owner-a", "owner-b");
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(sessions).save(argThat(session -> "owner-b".equals(session.getOwnerKey())));
        verifyNoInteractions(messages);
    }

    @Test
    void twoConcurrentRequestsAt199AdmitExactlyOneEmptySession() throws Exception {
        when(sessions.countByOwnerKey("owner-a")).thenReturn(199L);
        AtomicInteger saves = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(sessions.save(any(ChatSession.class))).thenAnswer(invocation -> {
            int call = saves.incrementAndGet();
            if (call == 1) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return saved(invocation.getArgument(0), call);
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> mvc.perform(post("/api/chat-extra/sessions")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            int competing = mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
            release.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS));
            assertEquals(400, competing);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, saves.get());
        verifyNoInteractions(messages);
    }

    @Test
    void persistenceConflictAtLimitKeepsExistingQuotaResponse() throws Exception {
        when(sessions.countByOwnerKey("owner-a")).thenReturn(199L, 200L);
        when(sessions.save(any(ChatSession.class))).thenThrow(new DataIntegrityViolationException("synthetic-conflict"));
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reasonCode").value("session_quota_exceeded"));
        verifyNoInteractions(messages);
    }

    @Test
    void reservationSurvivesReturnUntilTransactionCompletionAndThenReleases() throws Exception {
        when(sessions.countByOwnerKey("owner-a")).thenReturn(199L);
        TransactionSynchronizationManager.initSynchronization();
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        completeTransaction();
        mvc.perform(post("/api/chat-extra/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(sessions, times(2)).save(any(ChatSession.class));
        verifyNoInteractions(messages);
    }

    @Test
    void existingGetRoutesKeepOwnerIsolation() throws Exception {
        ChatSession own = saved(new ChatSession("draft", "owner-a", "ANON"), 1);
        when(sessions.findByOwnerKeyOrderByCreatedAtDesc("owner-a")).thenReturn(List.of(own));
        when(sessions.findById(1L)).thenReturn(Optional.of(own));
        mvc.perform(get("/api/chat-extra/sessions")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(1));
        mvc.perform(get("/api/chat-extra/sessions/1")).andExpect(status().isOk());
        when(owners.ownerKey()).thenReturn("owner-b");
        mvc.perform(get("/api/chat-extra/sessions/1")).andExpect(status().isNotFound());
        verify(sessions, never()).save(any());
        verifyNoInteractions(messages);
    }

    private static ChatSession saved(ChatSession session, long id) {
        session.setId(id);
        session.setCreatedAt(LocalDateTime.of(2026, 9, 12, 0, 0));
        return session;
    }

    private static void completeTransaction() {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }
}
