package com.example.lms.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.lms.domain.ChatSession;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;

class ChatSessionAccessGuardInteractionPolicyTest {

    @AfterEach
    void clearContext() {
        GuardContextHolder.clear();
    }

    @Test
    void deniedForeignSessionProducesBoundedAuthorizationFactWhileSameOwnerRemainsNeutral() {
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatSession session = mock(ChatSession.class);
        when(history.getSessionWithMessages(7L, 1)).thenReturn(session);
        when(session.getAdministrator()).thenReturn(null);
        when(session.getOwnerKey()).thenReturn("owner-a");

        GuardContext deniedContext = GuardContext.defaultContext();
        GuardContextHolder.set(deniedContext);
        var denied = ChatSessionAccessGuard.authorize(history, 7L, "anonymous", "owner-b", null);

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertTrue(deniedContext.getInteractionPolicyFacts().stream().anyMatch(fact ->
                fact.proofKind() == InteractionEvidencePolicy.ProofKind.AUTHORIZATION_DENIED));

        GuardContext allowedContext = GuardContext.defaultContext();
        GuardContextHolder.set(allowedContext);
        assertNull(ChatSessionAccessGuard.authorize(history, 7L, "anonymous", "owner-a", null));
        assertTrue(allowedContext.getInteractionPolicyFacts().isEmpty());
    }

    @Test
    @org.junit.jupiter.api.Timeout(90)
    void longSessionAuthorizationAndRecentHistoryUseBoundedExecutedQueryRows() throws Exception {
        try (HistoryJdbcFixture fixture = new HistoryJdbcFixture()) {
            long sessionId = fixture.seed(5_000);
            fixture.resetReadCounts();
            assertEquals(5_000, fixture.messages.findBySessionIdOrderByCreatedAtAsc(sessionId).size());
            assertEquals(5_000L, fixture.data.messageRows.get(), "positive control must count actual JDBC rows");

            fixture.resetReadCounts();
            var denied = ChatSessionAccessGuard.authorize(fixture.history, sessionId, null, "synthetic-foreign", null);
            long deniedRows = fixture.data.messageRows.get();
            long deniedQueries = fixture.data.messageQueries.get();
            fixture.resetReadCounts();
            var allowed = ChatSessionAccessGuard.authorize(fixture.history, sessionId, null, "synthetic-owner", null);
            long allowedRows = fixture.data.messageRows.get();
            fixture.resetReadCounts();
            var recent = fixture.history.getFormattedRecentHistory(sessionId, 3);
            long recentRows = fixture.data.messageRows.get();
            fixture.resetReadCounts();
            var detail = fixture.history.getSessionWithMessages(sessionId, 350);
            int detailSize = detail.getMessages().size();
            long detailRows = fixture.data.messageRows.get();
            System.out.println("API_HISTORY_QUERY_COUNTS seededRows=5000 deniedMessageRows=" + deniedRows
                    + " deniedMessageQueries=" + deniedQueries + " allowedMessageRows=" + allowedRows
                    + " recentMessageRows=" + recentRows + " detailMessageRows=" + detailRows);
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode()),
                    () -> assertNull(allowed),
                    () -> assertTrue(deniedRows <= 1, "authorization must not materialize the transcript: " + deniedRows),
                    () -> assertTrue(allowedRows <= 1, "same-owner authorization must stay bounded: " + allowedRows),
                    () -> assertEquals(java.util.List.of("User: turn-4998", "User: turn-4999", "User: turn-5000"), recent),
                    () -> assertEquals(3L, recentRows),
                    () -> assertEquals(200, detailSize),
                    () -> assertEquals(200L, detailRows));
        } finally {
            GuardContextHolder.clear();
            com.example.lms.search.TraceStore.clear();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.Timeout(45)
    void streamPausedBeforeTranscriptCommitPreservesRepositoryOutcome(boolean deleteBeforeCommit) throws Exception {
        try (HistoryJdbcFixture fixture = new HistoryJdbcFixture(true)) {
            long sessionId = fixture.seed(1);
            ChatHistoryService trackedHistory = mock(ChatHistoryService.class,
                    org.mockito.AdditionalAnswers.delegatesTo(fixture.history));
            var chatService = mock(com.example.lms.service.ChatService.class);
            var settings = mock(com.example.lms.service.SettingsService.class);
            var ownerKeys = mock(com.example.lms.web.ClientOwnerKeyResolver.class);
            var runs = org.mockito.Mockito.spy(new com.example.lms.service.chat.ChatRunRegistry());
            org.springframework.test.util.ReflectionTestUtils.setField(runs, "replayCapacity", 32);
            org.springframework.test.util.ReflectionTestUtils.setField(runs, "ttlSeconds", 60);
            var controller = org.mockito.Mockito.spy(new ChatApiController(
                    trackedHistory, chatService, null, settings, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper(), null, runs, ownerKeys));
            String answer = "A synthetic persisted answer. ".repeat(8);
            when(settings.getAllSettings()).thenReturn(java.util.Map.of());
            when(ownerKeys.ownerKey()).thenReturn("synthetic-owner");
            when(chatService.continueChat(org.mockito.ArgumentMatchers.any(com.example.lms.dto.ChatRequestDto.class),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(com.example.lms.service.ChatResult.of(answer, "mock-model", false));
            var firstToken = new java.util.concurrent.CountDownLatch(1);
            var releaseStream = new java.util.concurrent.CountDownLatch(1);
            var workerFinished = new java.util.concurrent.CountDownLatch(1);
            var tokenCalls = new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation -> {
                Object emitted = invocation.callRealMethod();
                if (tokenCalls.incrementAndGet() == 1) {
                    firstToken.countDown();
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                    boolean interrupted = false;
                    try {
                        while (releaseStream.getCount() != 0) {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0) throw new AssertionError("stream release deadline exceeded");
                            try { releaseStream.await(remaining, java.util.concurrent.TimeUnit.NANOSECONDS); }
                            catch (InterruptedException cancellationSignal) { interrupted = true; }
                        }
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
                return emitted;
            }).when(controller).emitTokenStreamEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(invocation -> {
                try { return invocation.callRealMethod(); }
                finally { workerFinished.countDown(); }
            }).when(runs).markDone(org.mockito.ArgumentMatchers.any(com.example.lms.service.chat.ChatRunExecutionContext.class));
            java.util.concurrent.CompletableFuture<java.util.List<org.springframework.http.codec.ServerSentEvent<
                    com.example.lms.dto.ChatStreamEvent>>> response = null;
            try {
                response = controller.chatStream(com.example.lms.dto.ChatRequestDto.builder()
                                .message("synthetic stream lifecycle").sessionId(sessionId)
                                .useRag(false).useWebSearch(false).build(),
                                false, false, null, new org.springframework.mock.web.MockHttpServletRequest())
                        .collectList().toFuture();
                assertTrue(firstToken.await(5, java.util.concurrent.TimeUnit.SECONDS), "actual SSE token must be emitted");
                assertTrue(runs.isRunning(sessionId), "the same owned session must have an active run");
                org.mockito.Mockito.verify(trackedHistory).getSessionWithMessages(sessionId, 1);
                org.mockito.Mockito.verify(trackedHistory, org.mockito.Mockito.never())
                        .appendMessageReturningId(sessionId, "assistant", answer);
                var preCommitRows = fixture.messages.findBySessionIdOrderByCreatedAtAsc(sessionId);
                assertEquals(2, preCommitRows.size(), "seed row plus the current user input precede assistant commit");
                assertTrue(preCommitRows.stream().allMatch(row -> "user".equals(row.getRole())));
                if (deleteBeforeCommit) {
                    assertEquals(HttpStatus.NO_CONTENT, controller.deleteSession(sessionId, null).getStatusCode());
                    fixture.entityManager.clear();
                    assertNull(fixture.entityManager.find(ChatSession.class, sessionId));
                    assertTrue(fixture.messages.findBySessionIdOrderByCreatedAtAsc(sessionId).isEmpty());
                }
                releaseStream.countDown();
                assertTrue(workerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS),
                        "wait for the producer finally block, not only an early cancellation response");
                var events = response.get(5, java.util.concurrent.TimeUnit.SECONDS);
                fixture.entityManager.clear();
                var remainingSession = fixture.entityManager.find(ChatSession.class, sessionId);
                var rows = fixture.messages.findBySessionIdOrderByCreatedAtAsc(sessionId);
                long assistantRows = rows.stream().filter(row -> "assistant".equals(row.getRole())).count();
                long finals = events.stream().filter(event -> event.data() != null && "final".equals(event.data().type())).count();
                org.junit.jupiter.api.Assertions.assertFalse(runs.isRunning(sessionId));
                assertEquals(deleteBeforeCommit, runs.isCancelled(sessionId));
                if (deleteBeforeCommit) {
                    assertNull(remainingSession);
                    assertTrue(rows.isEmpty(), "no transcript may be recreated after deletion and stream release");
                    assertEquals(0L, finals);
                    org.mockito.Mockito.verify(trackedHistory).deleteSession(sessionId);
                    org.mockito.Mockito.verify(trackedHistory, org.mockito.Mockito.never())
                            .appendMessageReturningId(sessionId, "assistant", answer);
                } else {
                    org.junit.jupiter.api.Assertions.assertNotNull(remainingSession);
                    assertEquals(1L, assistantRows, "positive control must commit an actual assistant row");
                    assertTrue(rows.stream().anyMatch(row -> "assistant".equals(row.getRole()) && answer.equals(row.getContent())));
                    assertEquals(1L, finals);
                    org.mockito.Mockito.verify(trackedHistory).appendMessageReturningId(sessionId, "assistant", answer);
                }
                System.out.println("API_STREAM_REPOSITORY_COUNTS deletion=" + deleteBeforeCommit
                        + " sessionRows=" + (remainingSession == null ? 0 : 1)
                        + " messageRows=" + rows.size() + " assistantRows=" + assistantRows
                        + " finalEvents=" + finals + " running=" + runs.isRunning(sessionId)
                        + " cancelled=" + runs.isCancelled(sessionId));
            } finally {
                releaseStream.countDown();
                if (firstToken.getCount() == 0) {
                    assertTrue(workerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS), "owned producer must finish before DB cleanup");
                }
                if (response != null && !response.isDone()) response.cancel(true);
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(runs, "shutdown");
            }
        } finally {
            GuardContextHolder.clear();
            com.example.lms.search.TraceStore.clear();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"sync,false", "sync,true", "chat,false", "chat,true"})
    @org.junit.jupiter.api.Timeout(45)
    void sameSessionSyncStreamOverlapKeepsOneGenerationAndOneTranscript(String syncRoute, boolean streamFirst)
            throws Exception {
        try (HistoryJdbcFixture fixture = new HistoryJdbcFixture(true)) {
            long sessionId = fixture.seed(1);
            var chatService = mock(com.example.lms.service.ChatService.class);
            var model = mock(dev.langchain4j.model.chat.ChatModel.class);
            var settings = mock(com.example.lms.service.SettingsService.class);
            var ownerKeys = mock(com.example.lms.web.ClientOwnerKeyResolver.class);
            var runs = org.mockito.Mockito.spy(new com.example.lms.service.chat.ChatRunRegistry());
            org.springframework.test.util.ReflectionTestUtils.setField(runs, "replayCapacity", 32);
            org.springframework.test.util.ReflectionTestUtils.setField(runs, "ttlSeconds", 60);
            var controller = new ChatApiController(fixture.history, chatService, null, settings, null, null, null, null, null, null, null, null, null, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper(), null, runs, ownerKeys);
            when(settings.getAllSettings()).thenReturn(java.util.Map.of());
            when(ownerKeys.ownerKey()).thenReturn("synthetic-owner");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var workerFinished = new java.util.concurrent.CountDownLatch(1);
            var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
            var ownerClaims = new java.util.concurrent.atomic.AtomicInteger();
            var rejectedClaims = new java.util.concurrent.atomic.AtomicInteger();
            var doneCalls = new java.util.concurrent.atomic.AtomicInteger();
            var ownerContext = new java.util.concurrent.atomic.AtomicReference<
                    com.example.lms.service.chat.ChatRunExecutionContext>();
            org.mockito.Mockito.doAnswer(invocation -> {
                com.example.lms.service.chat.ChatRunRegistry.BeginResult result = (com.example.lms.service.chat.ChatRunRegistry.BeginResult) invocation.callRealMethod();
                if (result.owner()) {
                    ownerClaims.incrementAndGet();
                    ownerContext.set(result.context());
                } else {
                    rejectedClaims.incrementAndGet();
                }
                return result;
            }).when(runs).beginOrJoin(sessionId);
            org.mockito.Mockito.doAnswer(invocation -> {
                try {
                    org.junit.jupiter.api.Assertions.assertSame(ownerContext.get(), invocation.getArgument(0));
                    return invocation.callRealMethod();
                } finally {
                    doneCalls.incrementAndGet();
                    workerFinished.countDown();
                }
            }).when(runs).markDone(org.mockito.ArgumentMatchers.any(
                    com.example.lms.service.chat.ChatRunExecutionContext.class));
            String answer = "Synthetic serialized answer.";
            when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
                modelCalls.incrementAndGet();
                org.junit.jupiter.api.Assertions.assertSame(ownerContext.get(),
                        com.example.lms.service.chat.ChatRunExecutionContext.current());
                entered.countDown();
                assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS), "release the owned model double");
                return answer;
            });
            when(chatService.continueChat(org.mockito.ArgumentMatchers.any(com.example.lms.dto.ChatRequestDto.class),
                    org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                    com.example.lms.service.ChatResult.of(model.chat("synthetic model input"), "mock-model", false));
            java.util.function.Function<String, com.example.lms.dto.ChatRequestDto> request = message ->
                    com.example.lms.dto.ChatRequestDto.builder().message(message).sessionId(sessionId)
                            .useRag(false).useWebSearch(false).build();
            java.util.function.Function<String, org.springframework.http.ResponseEntity<
                    com.example.lms.dto.ChatResponseDto>> nonstream = message -> "sync".equals(syncRoute)
                    ? controller.chatSync(request.apply(message), null, new org.springframework.mock.web.MockHttpServletRequest())
                    : controller.chat(request.apply(message), null, new org.springframework.mock.web.MockHttpServletRequest())
                            .block(java.time.Duration.ofSeconds(8));
            java.util.function.Function<String, java.util.concurrent.CompletableFuture<java.util.List<
                    org.springframework.http.codec.ServerSentEvent<com.example.lms.dto.ChatStreamEvent>>>> stream = message ->
                    controller.chatStream(request.apply(message), false, false, null,
                            new org.springframework.mock.web.MockHttpServletRequest()).collectList().toFuture();
            var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<org.springframework.http.ResponseEntity<com.example.lms.dto.ChatResponseDto>>
                    syncResponse = null;
            java.util.concurrent.CompletableFuture<java.util.List<org.springframework.http.codec.ServerSentEvent<
                    com.example.lms.dto.ChatStreamEvent>>> streamResponse = null;
            try {
                if (streamFirst) streamResponse = stream.apply("synthetic owner turn");
                else syncResponse = caller.submit(() -> nonstream.apply("synthetic owner turn"));
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "owner must enter actual model-double call");
                assertTrue(runs.isRunning(sessionId));
                var originalToken = runs.currentRunToken(sessionId).orElseThrow();
                var beforeOverlap = fixture.messages.findBySessionIdOrderByCreatedAtAsc(sessionId);
                assertEquals(2, beforeOverlap.size(), "seed plus exactly one admitted user input");
                if (streamFirst) {
                    var rejected = nonstream.apply("synthetic rejected overlap");
                    assertEquals(HttpStatus.CONFLICT, rejected.getStatusCode());
                    assertEquals("run_active", rejected.getBody().getContent());
                } else {
                    var rejected = stream.apply("synthetic rejected overlap").get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(1, rejected.size());
                    assertEquals("error", rejected.get(0).data().type());
                    assertEquals("run_active", rejected.get(0).data().data());
                }
                assertEquals(originalToken, runs.currentRunToken(sessionId).orElseThrow());
                assertTrue(runs.isRunning(sessionId));
                org.junit.jupiter.api.Assertions.assertFalse(runs.isCancelled(sessionId));
                assertEquals(1, ownerClaims.get());
                assertEquals(1, rejectedClaims.get());
                assertEquals(1, modelCalls.get());
                assertEquals(0, doneCalls.get(), "rejected peer must not finish the blocked owner");
                assertEquals(2, fixture.messages.findBySessionIdOrderByCreatedAtAsc(sessionId).size(),
                        "rejected peer must not add any transcript or diagnostic row");
                release.countDown();
                if (streamFirst) {
                    var events = streamResponse.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(1L, events.stream().filter(event -> event.data() != null
                            && "final".equals(event.data().type())).count());
                } else {
                    var accepted = syncResponse.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(HttpStatus.OK, accepted.getStatusCode());
                    assertEquals(answer, accepted.getBody().getContent());
                }
                assertTrue(workerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS));
                fixture.entityManager.clear();
                var rows = fixture.messages.findBySessionIdOrderByCreatedAtAsc(sessionId);
                var transcript = rows.stream().filter(row -> "user".equals(row.getRole())
                        || "assistant".equals(row.getRole())).toList();
                assertEquals(java.util.List.of("turn-1", "synthetic owner turn", answer),
                        transcript.stream().map(com.example.lms.domain.ChatMessage::getContent).toList());
                assertEquals(1L, transcript.stream().filter(row -> "assistant".equals(row.getRole())).count());
                assertEquals(1, modelCalls.get());
                org.mockito.Mockito.verify(chatService).continueChat(
                        org.mockito.ArgumentMatchers.any(com.example.lms.dto.ChatRequestDto.class), org.mockito.ArgumentMatchers.any());
                org.mockito.Mockito.verify(model).chat("synthetic model input");
                org.junit.jupiter.api.Assertions.assertFalse(runs.isRunning(sessionId));
                org.junit.jupiter.api.Assertions.assertFalse(runs.isCancelled(sessionId));
                System.out.println("API_CONCURRENCY_COUNTS syncRoute=" + syncRoute + " streamFirst=" + streamFirst
                        + " modelCalls=" + modelCalls.get() + " ownerClaims=" + ownerClaims.get()
                        + " rejectedClaims=" + rejectedClaims.get() + " doneCalls=" + doneCalls.get()
                        + " messageRows=" + rows.size() + " transcriptRows=" + transcript.size()
                        + " assistantRows=1 rejectedInputRows=0 running=false cancelled=false");
            } finally {
                release.countDown();
                try {
                    if (entered.getCount() == 0) assertTrue(workerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    if (syncResponse != null && !syncResponse.isDone()) syncResponse.cancel(true);
                    if (streamResponse != null && !streamResponse.isDone()) streamResponse.cancel(true);
                } finally {
                    caller.shutdownNow();
                    assertTrue(caller.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(runs, "shutdown");
                }
            }
        } finally {
            GuardContextHolder.clear();
            com.example.lms.search.TraceStore.clear();
        }
    }

    private static final class HistoryJdbcFixture implements AutoCloseable {
        final RowCountingDataSource data;
        org.hibernate.boot.registry.StandardServiceRegistry registry;
        org.hibernate.SessionFactory factory;
        jakarta.persistence.EntityManager entityManager;
        org.springframework.context.annotation.AnnotationConfigApplicationContext context;
        com.example.lms.repository.ChatMessageRepository messages;
        com.example.lms.service.ChatHistoryService history;

        HistoryJdbcFixture() { this(false); }

        HistoryJdbcFixture(boolean transactional) {
            var actual = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:history_rows_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            data = new RowCountingDataSource(actual);
            try {
            registry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                    .applySetting("hibernate.connection.datasource", data)
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .applySetting("hibernate.show_sql", false)
                    .applySetting("hibernate.format_sql", false)
                    .build();
            factory = new org.hibernate.boot.MetadataSources(registry)
                    .addAnnotatedClass(com.example.lms.domain.ChatSession.class)
                    .addAnnotatedClass(com.example.lms.domain.ChatMessage.class)
                    .addAnnotatedClass(com.example.lms.domain.Administrator.class)
                    .buildMetadata().buildSessionFactory();
            entityManager = factory.createEntityManager();
            var repositoryManager = transactional
                    ? org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory)
                    : entityManager;
            var repositories = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(repositoryManager);
            messages = repositories.getRepository(com.example.lms.repository.ChatMessageRepository.class);
            var sessions = repositories.getRepository(com.example.lms.repository.ChatSessionRepository.class);
            context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
            context.getEnvironment().setActiveProfiles("shim");
            context.registerBean(com.example.lms.repository.ChatMessageRepository.class, () -> messages);
            context.registerBean(com.example.lms.repository.ChatSessionRepository.class, () -> sessions);
            context.registerBean(com.example.lms.repository.AdministratorRepository.class,
                    () -> mock(com.example.lms.repository.AdministratorRepository.class));
            context.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                    () -> new com.fasterxml.jackson.databind.ObjectMapper());
            context.registerBean(com.example.lms.web.ClientOwnerKeyResolver.class,
                    () -> mock(com.example.lms.web.ClientOwnerKeyResolver.class));
            context.register(com.example.lms.service.ChatHistoryServiceImpl.class,
                    com.example.lms.service.DefaultChatHistoryService.class,
                    com.example.lms.service.chat.JpaChatHistorySummaryService.class);
            context.refresh();
            history = context.getBean(com.example.lms.service.ChatHistoryService.class);
            assertEquals(com.example.lms.service.ChatHistoryServiceImpl.class, history.getClass());
            org.junit.jupiter.api.Assertions.assertFalse(com.example.lms.service.ChatHistoryService.class
                    .isAssignableFrom(com.example.lms.service.chat.JpaChatHistorySummaryService.class));
            if (transactional) {
                var proxy = new org.springframework.aop.framework.ProxyFactory(history);
                proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                        new org.springframework.orm.jpa.JpaTransactionManager(factory),
                        new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
                history = (ChatHistoryService) proxy.getProxy();
            }
            } catch (RuntimeException | Error startupFailure) {
                try { close(); } catch (Exception cleanupFailure) { startupFailure.addSuppressed(cleanupFailure); }
                throw startupFailure;
            }
        }

        long seed(int rows) {
            entityManager.getTransaction().begin();
            ChatSession session = new ChatSession("synthetic long history", "synthetic-owner", "ANON");
            entityManager.persist(session);
            long id = session.getId();
            for (int i = 1; i <= rows; i++) {
                var owner = entityManager.getReference(ChatSession.class, id);
                entityManager.persist(new com.example.lms.domain.ChatMessage(owner, "user", "turn-" + i));
                if (i % 250 == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }
            entityManager.getTransaction().commit();
            resetReadCounts();
            return id;
        }

        void resetReadCounts() {
            entityManager.clear();
            data.messageRows.set(0);
            data.messageQueries.set(0);
        }

        @Override
        public void close() throws Exception {
            try {
                if (context != null) context.close();
            } finally {
                try {
                    if (entityManager != null && entityManager.isOpen()) {
                        if (entityManager.getTransaction().isActive()) entityManager.getTransaction().rollback();
                        entityManager.close();
                    }
                } finally {
                    try { if (factory != null) factory.close(); }
                    finally {
                        if (registry != null) org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
                        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
                            statement.execute("SHUTDOWN");
                        }
                    }
                }
            }
        }
    }

    private static final class RowCountingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final java.util.concurrent.atomic.AtomicLong messageRows = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong messageQueries = new java.util.concurrent.atomic.AtomicLong();

        RowCountingDataSource(javax.sql.DataSource target) { super(target); }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException { return counted(super.getConnection()); }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            return counted(super.getConnection(username, password));
        }

        private java.sql.Connection counted(java.sql.Connection target) {
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.Connection.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(target, method, args);
                        if (result instanceof java.sql.PreparedStatement statement && args != null && args.length > 0
                                && args[0] instanceof String sql) {
                            String shape = sql.toLowerCase(java.util.Locale.ROOT).replace("_", "");
                            boolean messageQuery = shape.contains(" from chatmessage ");
                            return counted(statement, messageQuery);
                        }
                        return result;
                    });
        }

        private java.sql.PreparedStatement counted(java.sql.PreparedStatement target, boolean messageQuery) {
            return (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.PreparedStatement.class.getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                    (proxy, method, args) -> {
                        Object result = invoke(target, method, args);
                        if (messageQuery && result instanceof java.sql.ResultSet rows) {
                            if (method.getName().equals("executeQuery")) messageQueries.incrementAndGet();
                            return java.lang.reflect.Proxy.newProxyInstance(java.sql.ResultSet.class.getClassLoader(),
                                    new Class<?>[]{java.sql.ResultSet.class}, (resultProxy, rowMethod, rowArgs) -> {
                                        Object value = invoke(rows, rowMethod, rowArgs);
                                        if (rowMethod.getName().equals("next") && Boolean.TRUE.equals(value)) messageRows.incrementAndGet();
                                        return value;
                                    });
                        }
                        return result;
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
