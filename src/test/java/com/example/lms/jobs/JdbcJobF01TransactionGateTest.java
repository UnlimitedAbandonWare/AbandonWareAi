package com.example.lms.jobs;

import com.example.lms.config.JobConfig;
import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real chat entities, JPA and JDBC on one isolated H2 store; no provider or live DB. */
class JdbcJobF01TransactionGateTest {
    private static final String TYPE = "understanding_summary_v1";
    private LocalContainerEntityManagerFactoryBean factory;
    private AnnotationConfigApplicationContext context;
    private EntityManager em;
    private JpaTransactionManager manager;
    private TransactionTemplate chatTx;
    private JdbcTemplate jdbc;
    private JdbcJobService jobs;
    private Long sessionId;

    @BeforeEach void database() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:f01_gate_" + UUID.randomUUID()
                + ";MODE=MariaDB;DB_CLOSE_DELAY=-1", "sa", "");
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(ChatSession.class.getName(),
                ChatMessage.class.getName(), Administrator.class.getName(),
                com.example.lms.entity.TranslationMemory.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.show_sql", "false"));
        factory.afterPropertiesSet();
        var emf = Objects.requireNonNull(factory.getObject());
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        manager = new JpaTransactionManager(emf);
        assertSame(ds, manager.getDataSource(), "JPA must discover its JDBC DataSource");
        chatTx = new TransactionTemplate(manager);
        jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(
                new FileSystemResource("main/resources/db/migration/V20260912__durable_jobs.sql"),
                new FileSystemResource("main/resources/db/migration/V20260912_03__job_idempotency.sql"),
                new FileSystemResource("main/resources/db/migration/V20260929__f01_understanding_receipt.sql")).execute(ds);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("f01-fixture", Map.of(
                "jobs.enabled-types", TYPE, "jobs.callback-enabled-types", "")));
        context.registerBean(DataSource.class, () -> ds);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> manager);
        context.register(JobConfig.class);
        context.refresh();
        jobs = (JdbcJobService) context.getBean(JobService.class);
        sessionId = chatTx.execute(tx -> {
            var session = new ChatSession("synthetic F01 gate", "synthetic-owner", "ANON");
            em.persist(session);
            em.flush();
            return session.getId();
        });
    }

    @AfterEach void close() {
        if (context != null) context.close();
        if (factory != null) factory.destroy();
    }

    private TransactionTemplate jobTx() {
        return (TransactionTemplate) ReflectionTestUtils.getField(jobs, "transactions");
    }

    private com.example.lms.service.ChatHistoryServiceImpl strictHistory() {
        var sessions = org.mockito.Mockito.mock(com.example.lms.repository.ChatSessionRepository.class);
        var messages = org.mockito.Mockito.mock(com.example.lms.repository.ChatMessageRepository.class);
        org.mockito.Mockito.when(sessions.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> Optional.ofNullable(em.find(ChatSession.class, call.getArgument(0),
                        jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)));
        org.mockito.Mockito.when(messages.saveAndFlush(org.mockito.ArgumentMatchers.any(ChatMessage.class)))
                .thenAnswer(call -> { ChatMessage message = call.getArgument(0); em.persist(message); em.flush(); return message; });
        return new com.example.lms.service.ChatHistoryServiceImpl(sessions, messages,
                org.mockito.Mockito.mock(com.example.lms.repository.AdministratorRepository.class),
                new ObjectMapper(), org.mockito.Mockito.mock(com.example.lms.web.ClientOwnerKeyResolver.class));
    }

    @Test void strictHistoryRequiresOuterTransactionAndPropagatesInsertFailure() {
        var history = strictHistory();
        assertThrows(IllegalStateException.class,
                () -> history.appendMessageStrictReturningId(sessionId, "assistant", "synthetic"));
        var messages = (com.example.lms.repository.ChatMessageRepository) ReflectionTestUtils.getField(history, "messageRepository");
        var failure = new org.springframework.dao.DataIntegrityViolationException("synthetic_insert_failure");
        org.mockito.Mockito.when(messages.saveAndFlush(org.mockito.ArgumentMatchers.any(ChatMessage.class))).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> chatTx.execute(t ->
                history.appendMessageStrictReturningId(sessionId, "system", "⎔USUM⎔{}"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ChatMessage", Integer.class));
    }


    private com.example.lms.service.understanding.DeferredUnderstandingTask receiptTask(String run) {
        return new com.example.lms.service.understanding.DeferredUnderstandingTask(1, run, "a".repeat(64),
                sessionId, "GENERAL", 1, 10, 100, 20, 200, "UNDERSTANDING", "b".repeat(64), "c".repeat(64),
                com.example.lms.guard.GuardProfile.NORMAL, com.example.lms.domain.enums.MemoryMode.FULL,
                true, true, true, "synthetic", 500);
    }
    private com.example.lms.service.understanding.UnderstandingReceiptRepository receipts() {
        var messages = org.mockito.Mockito.mock(com.example.lms.repository.ChatMessageRepository.class);
        org.mockito.Mockito.when(messages.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> Optional.ofNullable(em.find(ChatMessage.class, call.getArgument(0))));
        return new com.example.lms.service.understanding.UnderstandingReceiptRepository(jdbc.getDataSource(), messages);
    }
    @Test void actualReceiptApiSurvivesJobTTLAndConfirmsTheSameUsumAcrossRuns() {
        var receipts = receipts(); var task = receiptTask("first-run");
        String id = jobs.enqueue(TYPE, Map.of(), Map.of("ownerHash", "synthetic-owner"), null);
        Long usumId = chatTx.execute(t -> {
            Long usum = strictHistory().appendMessageStrictReturningId(sessionId, "system", "⎔USUM⎔{}");
            receipts.insert(task, new JobService.DerivedClaim(id, "synthetic"), usum, "{}");
            return usum;
        });
        jdbc.update("UPDATE awx_jobs SET state='SUCCEEDED',expires_at=0 WHERE task_id=?", id);
        jobs.maintenance();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM awx_jobs", Integer.class));
        assertEquals(usumId, chatTx.execute(t -> receipts.findPersisted(receiptTask("different-run")).orElseThrow()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ChatMessage", Integer.class));
    }
    @Test void duplicateReceiptRollsBackTheSecondUsumAndWinnerCanBeReadSeparately() {
        var receipts = receipts(); var task = receiptTask("run");
        Long first = chatTx.execute(t -> {
            Long id = strictHistory().appendMessageStrictReturningId(sessionId, "system", "⎔USUM⎔{}");
            receipts.insert(task, new JobService.DerivedClaim("first-task", "synthetic"), id, "{}");
            return id;
        });
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> chatTx.executeWithoutResult(t -> {
            Long id = strictHistory().appendMessageStrictReturningId(sessionId, "system", "⎔USUM⎔{}");
            receipts.insert(task, new JobService.DerivedClaim("second-task", "synthetic"), id, "{}");
        }));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ChatMessage", Integer.class));
        assertEquals(first, chatTx.execute(t -> receipts.findPersisted(task).orElseThrow()));
    }
    @Test void matchingEffectHashStillRejectsStoredTupleMismatchOrInvalidatedReceipt() {
        var receipts = receipts(); var task = receiptTask("run");
        chatTx.executeWithoutResult(t -> {
            Long id = strictHistory().appendMessageStrictReturningId(sessionId, "system", "⎔USUM⎔{}");
            receipts.insert(task, new JobService.DerivedClaim("task", "synthetic"), id, "{}");
        });
        jdbc.update("UPDATE awx_understanding_receipts SET user_revision=101 WHERE effect_key=?", task.effectKey());
        assertThrows(JobService.DerivedRejected.class, () -> chatTx.execute(t -> receipts.findPersisted(task)));
        jdbc.update("UPDATE awx_understanding_receipts SET user_revision=100,receipt_state='INVALIDATED' WHERE effect_key=?", task.effectKey());
        assertThrows(JobService.DerivedRejected.class, () -> chatTx.execute(t -> receipts.findPersisted(task)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ChatMessage", Integer.class));
    }

    private record T3Fixture(com.example.lms.service.understanding.DeferredUnderstandingTask task,
            com.example.lms.service.understanding.AnswerUnderstandingService provider,
            com.example.lms.service.MemoryReinforcementService memory,
            com.example.lms.service.understanding.DeferredUnderstandingJobHandler handler) { }

    private T3Fixture t3Fixture() {
        jdbc.execute("CREATE TABLE f01_reinforce_effect(effect_key VARCHAR(64) PRIMARY KEY)");
        var sessions = org.mockito.Mockito.mock(com.example.lms.repository.ChatSessionRepository.class);
        var messages = org.mockito.Mockito.mock(com.example.lms.repository.ChatMessageRepository.class);
        org.mockito.Mockito.when(sessions.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(c -> Optional.ofNullable(em.find(ChatSession.class, c.getArgument(0),
                        jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)));
        org.mockito.Mockito.when(messages.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(c -> Optional.ofNullable(em.find(ChatMessage.class, c.getArgument(0))));
        var sources = new com.example.lms.service.rag.graph.GeneralGraphSourceAuthority(sessions, messages, new ObjectMapper());
        ReflectionTestUtils.setField(sources, "entityManager", em);
        var task = chatTx.execute(tx -> {
            var session = em.find(ChatSession.class, sessionId);
            var scope = sources.bindPolicy(com.example.lms.service.rag.graph.GeneralGraphScope
                    .authorize(session, null, "synthetic-owner").orElseThrow(),
                    com.example.lms.domain.enums.MemoryProfile.LIGHT).orElseThrow();
            var user = new ChatMessage(session, "user", "synthetic question");
            var assistant = new ChatMessage(session, "assistant", "synthetic answer");
            em.persist(user); em.persist(assistant); em.flush();
            return new com.example.lms.service.understanding.DeferredUnderstandingTask(1, "run-first",
                    scope.ownerNamespace(), sessionId, scope.channel(), scope.consentEpoch(),
                    user.getId(), sources.source(scope, user.getId()).orElseThrow().sourceRevision(),
                    assistant.getId(), sources.source(scope, assistant.getId()).orElseThrow().sourceRevision(),
                    "UNDERSTANDING", org.apache.commons.codec.digest.DigestUtils.sha256Hex(user.getContent()),
                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(assistant.getContent()),
                    com.example.lms.guard.GuardProfile.NORMAL, com.example.lms.domain.enums.MemoryMode.FULL,
                    true, true, true, "synthetic", 500);
        });
        var memory = org.mockito.Mockito.mock(com.example.lms.service.MemoryReinforcementService.class);
        org.mockito.Mockito.doAnswer(c -> {
            jdbc.update("INSERT INTO f01_reinforce_effect VALUES(?)", task.effectKey());
            return null;
        }).when(memory).reinforceUnderstandingLocal(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble());
        var commits = new com.example.lms.service.understanding.UnderstandingCommitService(jobs, sources,
                strictHistory(), receipts(), memory, new ObjectMapper(), manager);
        var provider = org.mockito.Mockito.mock(com.example.lms.service.understanding.AnswerUnderstandingService.class);
        org.mockito.Mockito.when(provider.understandDerived(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(syntheticOutcome());
        var handler = new com.example.lms.service.understanding.DeferredUnderstandingJobHandler(jobs, commits, provider, new ObjectMapper());
        ReflectionTestUtils.setField(handler, "enabled", true);
        handler.register();
        return new T3Fixture(task, provider, memory, handler);
    }
    private com.example.lms.service.understanding.AnswerUnderstandingService.Outcome syntheticOutcome() {
        return new com.example.lms.service.understanding.AnswerUnderstandingService.Outcome(
                com.example.lms.service.understanding.AnswerUnderstandingService.OutcomeKind.PROVIDER,
                new com.example.lms.dto.answer.AnswerUnderstanding("synthetic summary", List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), .95), "provider_result");
    }
    private com.example.lms.service.understanding.DeferredUnderstandingTask withRun(
            com.example.lms.service.understanding.DeferredUnderstandingTask t, String run) {
        return new com.example.lms.service.understanding.DeferredUnderstandingTask(t.schemaVersion(), run,
                t.ownerNamespace(), t.sessionId(), t.channel(), t.consentEpoch(), t.userMessageId(),
                t.userRevision(), t.assistantMessageId(), t.assistantRevision(), t.kind(),
                t.approvedQuestionHash(), t.approvedAnswerHash(), t.guardProfile(), t.memoryMode(),
                t.memorySaveAllowed(), t.requestUnderstanding(), t.sensitiveMemoryApproved(), t.modelId(), t.budgetMillis());
    }
    private record T0Fixture(com.example.lms.service.understanding.UnderstandingCommitService commits,
            com.example.lms.service.chat.ChatRunRegistry registry,
            com.example.lms.service.chat.ChatRunExecutionContext run,
            com.example.lms.service.rag.graph.GeneralGraphScope scope,
            com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor legacy,
            T3Fixture derived) implements AutoCloseable {
        @Override public void close() { ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
        boolean stage(boolean allowed) {
            return commits.stageDeferred(run, scope, "synthetic question", "synthetic answer",
                    com.example.lms.guard.GuardProfile.NORMAL, com.example.lms.domain.enums.MemoryMode.FULL,
                    allowed, true);
        }
        Long persist(String answer, boolean held) {
            var plan = commits.prepareTranscript(run, answer, held);
            assertTrue(run.markGenerationSucceeded());
            assertTrue(run.tryBeginTranscriptCommit());
            var id = new java.util.concurrent.atomic.AtomicReference<Long>();
            assertTrue(run.runTerminalSideEffect(() -> id.set(commits.persistOrigin(run, scope.sessionId(), answer, plan))));
            return id.get();
        }
    }
    private T0Fixture t0Fixture() {
        var f = t3Fixture();
        var commits = (com.example.lms.service.understanding.UnderstandingCommitService) ReflectionTestUtils.getField(f.handler(), "commits");
        var sources = (com.example.lms.service.rag.graph.GeneralGraphSourceAuthority) ReflectionTestUtils.getField(commits, "sources");
        var scope = chatTx.execute(tx -> sources.bindPolicy(com.example.lms.service.rag.graph.GeneralGraphScope
                .authorize(em.find(ChatSession.class, sessionId), null, "synthetic-owner").orElseThrow(),
                com.example.lms.domain.enums.MemoryProfile.LIGHT).orElseThrow());
        org.mockito.Mockito.when(f.provider().isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(f.provider().configuredModelId()).thenReturn("synthetic");
        org.mockito.Mockito.when(f.provider().configuredBudgetMillis()).thenReturn(500L);
        org.mockito.Mockito.when(f.memory().understandingMemoryApproved()).thenReturn(true);
        var legacy = org.mockito.Mockito.mock(com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor.class);
        ReflectionTestUtils.setField(commits, "deferredEnabled", true);
        ReflectionTestUtils.setField(commits, "understanding", f.provider());
        ReflectionTestUtils.setField(commits, "legacy", legacy);
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var run = registry.beginOrJoin(sessionId).context();
        assertTrue(run.bindPersistedUserMessage(f.task().userMessageId()));
        return new T0Fixture(commits, registry, run, scope, legacy, f);
    }
    @Test void t0CommitsOriginAndPendingIntentWithoutCallingEitherProviderPath() throws Exception {
        try (var f = t0Fixture()) {
            assertTrue(f.stage(true));
            Long id = f.persist("synthetic answer", false);
            assertNotNull(id);
            assertEquals(3, count("ChatMessage"));
            assertEquals(1, count("awx_jobs"));
            assertEquals(0, count("awx_understanding_receipts"));
            var task = new ObjectMapper().readValue(jdbc.queryForObject("SELECT payload FROM awx_jobs", String.class),
                    com.example.lms.service.understanding.DeferredUnderstandingTask.class);
            assertEquals(id.longValue(), task.assistantMessageId());
            assertEquals(f.run().clientToken(), task.originalRunId());
            assertTrue(task.sensitiveMemoryApproved());
            assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM awx_jobs", String.class));
            org.mockito.Mockito.verify(f.derived().provider(), org.mockito.Mockito.never()).understandDerived(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.verifyNoInteractions(f.legacy());
        }
    }
    @Test void t0JobInsertFailureRollsBackStrictAssistantAndNeverFallsBackToA() {
        try (var f = t0Fixture()) {
            assertTrue(f.stage(true));
            jdbc.execute("ALTER TABLE awx_jobs ADD CONSTRAINT synthetic_no_jobs CHECK(job_type <> 'understanding_summary_v1')");
            assertThrows(RuntimeException.class, () -> f.persist("synthetic answer", false));
            assertEquals(2, count("ChatMessage"));
            assertEquals(0, count("awx_jobs"));
            org.mockito.Mockito.verifyNoInteractions(f.legacy());
        }
    }
    @Test void t0AssistantFailureLeavesNoJob() {
        try (var f = t0Fixture()) {
            assertTrue(f.stage(true));
            var history = org.mockito.Mockito.mock(com.example.lms.service.ChatHistoryService.class);
            org.mockito.Mockito.when(history.appendMessageStrictReturningId(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("assistant"),
                    org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("synthetic_insert_failure"));
            ReflectionTestUtils.setField(f.commits(), "history", history);
            assertThrows(RuntimeException.class, () -> f.persist("synthetic answer", false));
            assertEquals(2, count("ChatMessage"));
            assertEquals(0, count("awx_jobs"));
        }
    }
    @Test void t0RechecksUserRevisionAfterApproval() {
        try (var f = t0Fixture()) {
            assertTrue(f.stage(true));
            chatTx.executeWithoutResult(tx -> em.find(ChatMessage.class, f.derived().task().userMessageId()).setContent("edited"));
            assertThrows(JobService.DerivedRejected.class, () -> f.persist("synthetic answer", false));
            assertEquals(2, count("ChatMessage"));
            assertEquals(0, count("awx_jobs"));
        }
    }
    @Test void projectionMismatchPreparesAOutsideTransactionBeforeAnyAdmission() {
        try (var f = t0Fixture()) {
            assertTrue(f.stage(true));
            org.mockito.Mockito.when(f.legacy().prepare("synthetic question", "synthetic answer", true)).thenAnswer(c -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(0, count("awx_jobs"));
                return syntheticOutcome().value();
            });
            var plan = f.commits().prepareTranscript(f.run(), "different persisted projection", false);
            assertFalse(plan.deferred());
            assertNotNull(plan.fallback());
            org.mockito.Mockito.verify(f.legacy()).prepare("synthetic question", "synthetic answer", true);
            assertEquals(0, count("awx_jobs"));
        }
    }
    @Test void heldProjectionNeverEnqueuesOrSummarizesTheRejectedDraft() {
        try (var f = t0Fixture()) {
            assertTrue(f.stage(true));
            var plan = f.commits().prepareTranscript(f.run(), "held notice", true);
            assertFalse(plan.deferred());
            assertNull(plan.fallback());
            org.mockito.Mockito.verifyNoInteractions(f.legacy());
            assertEquals(0, count("awx_jobs"));
        }
    }
    @Test void permissionAndFlagGatesLeaveAUnselectedAndExactRunBindingCannotRetarget() {
        try (var f = t0Fixture()) {
            assertFalse(f.stage(false));
            org.mockito.Mockito.when(f.derived().memory().understandingMemoryApproved()).thenReturn(false);
            assertFalse(f.stage(true));
            org.mockito.Mockito.when(f.derived().memory().understandingMemoryApproved()).thenReturn(true);
            ReflectionTestUtils.setField(f.commits(), "deferredEnabled", false);
            assertFalse(f.stage(true));
            assertFalse(f.run().bindPersistedUserMessage(999L));
            ReflectionTestUtils.setField(f.commits(), "deferredEnabled", true);
            assertTrue(f.stage(true));
            assertTrue(f.registry().cancelExact(sessionId, f.run().clientToken()));
            assertTrue(f.run().understandingIntent().isEmpty());
            assertNull(f.run().persistedUserMessageId());
            var next = f.registry().beginOrJoin(sessionId).context();
            assertFalse(next.sameRun(f.run()));
            assertTrue(next.understandingIntent().isEmpty());
            assertNull(next.persistedUserMessageId());
            assertFalse(f.stage(true));
        }
    }
    @Test void cancellationBetweenPlanAndTranscriptGatePreventsBothT0Rows() {
        try (var f = t0Fixture()) {
            assertTrue(f.stage(true));
            var plan = f.commits().prepareTranscript(f.run(), "synthetic answer", false);
            assertTrue(f.registry().cancelExact(sessionId, f.run().clientToken()));
            org.junit.jupiter.api.Assertions.assertFalse(f.run().tryBeginTranscriptCommit());
            org.junit.jupiter.api.Assertions.assertFalse(f.run().runTerminalSideEffect(() ->
                    f.commits().persistOrigin(f.run(), sessionId, "synthetic answer", plan)));
            assertEquals(2, count("ChatMessage"));
            assertEquals(0, count("awx_jobs"));
        }
    }
    @Test void actualTranslationMemoryRollsBackWithT3AndReceiptReplayDoesNotReinforceAgain() {
        var f = t3Fixture();
        var repo = org.mockito.Mockito.mock(com.example.lms.repository.TranslationMemoryRepository.class);
        org.mockito.Mockito.when(repo.findBySourceHash(org.mockito.ArgumentMatchers.anyString())).thenAnswer(c ->
                em.createQuery("select m from TranslationMemory m where m.sourceHash = :hash", com.example.lms.entity.TranslationMemory.class)
                        .setParameter("hash", c.getArgument(0)).getResultStream().findFirst());
        org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any(com.example.lms.entity.TranslationMemory.class)))
                .thenAnswer(c -> { com.example.lms.entity.TranslationMemory m = c.getArgument(0); em.persist(m); em.flush(); return m; });
        var vectors = org.mockito.Mockito.mock(com.example.lms.service.VectorStoreService.class);
        var memory = org.mockito.Mockito.spy(new com.example.lms.service.MemoryReinforcementService(repo, vectors,
                org.mockito.Mockito.mock(com.example.lms.service.reinforcement.SnippetPruner.class),
                org.mockito.Mockito.mock(com.example.lms.strategy.StrategyPerformanceRepository.class),
                org.mockito.Mockito.mock(com.example.lms.strategy.StrategyDecisionTracker.class),
                org.mockito.Mockito.mock(com.example.lms.service.config.HyperparameterService.class)));
        ReflectionTestUtils.setField(memory, "memoryEnabled", true);
        var commits = (com.example.lms.service.understanding.UnderstandingCommitService) ReflectionTestUtils.getField(f.handler(), "commits");
        ReflectionTestUtils.setField(commits, "memory", memory);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(c -> {
            c.callRealMethod();
            assertEquals(1, count("translation_memory"));
            if (first.getAndSet(false)) throw new IllegalStateException("after_actual_local_memory");
            return null;
        }).when(memory).reinforceUnderstandingLocal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble());
        var summary = new com.example.lms.dto.answer.AnswerUnderstanding(
                "Synthetic factual explanation with sufficient detail for ordinary local memory quality checks.",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), .95);
        org.mockito.Mockito.when(f.provider().understandDerived(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.example.lms.service.understanding.AnswerUnderstandingService.Outcome(
                        com.example.lms.service.understanding.AnswerUnderstandingService.OutcomeKind.PROVIDER, summary, "provider_result"));
        String id = admit(f.task()); jobs.runPendingOnce();
        assertEquals(0, count("translation_memory"));
        assertEquals(0, count("awx_understanding_receipts"));
        assertEquals(2, count("ChatMessage"));
        assertEquals("COMMIT", jdbc.queryForObject("SELECT phase FROM awx_jobs WHERE task_id=?", String.class, id));
        jdbc.update("UPDATE awx_jobs SET next_attempt_at=0 WHERE task_id=?", id);
        jobs.runPendingOnce();
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM awx_jobs WHERE task_id=?", String.class, id));
        assertEquals(1, count("translation_memory"));
        assertEquals(1, jdbc.queryForObject("SELECT hit_count FROM translation_memory", Integer.class));
        admit(withRun(f.task(), "run-replay")); jobs.runPendingOnce();
        assertEquals(1, count("translation_memory"));
        assertEquals(1, jdbc.queryForObject("SELECT hit_count FROM translation_memory", Integer.class));
        assertEquals(3, count("ChatMessage"));
        org.mockito.Mockito.verify(f.provider()).understandDerived(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoInteractions(vectors);
    }
    private String admit(com.example.lms.service.understanding.DeferredUnderstandingTask task) {
        return chatTx.execute(tx -> jobs.enqueueDerivedOnce(task, task.identity(),
                task.admissionKey(), task.requestFingerprint()).taskId());
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test void realT3FailureAfterReceiptRollsBackUsumReceiptAndLocalEffectThenRetriesWithoutProvider() {
        var fixture = t3Fixture();
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(c -> {
            jdbc.update("INSERT INTO f01_reinforce_effect VALUES(?)", fixture.task().effectKey());
            if (first.getAndSet(false)) throw new IllegalStateException("injected_after_receipt");
            return null;
        }).when(fixture.memory()).reinforceUnderstandingLocal(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble());
        String id = admit(fixture.task()); jobs.runPendingOnce();
        assertEquals(2, count("ChatMessage")); assertEquals(0, count("awx_understanding_receipts"));
        assertEquals(0, count("f01_reinforce_effect"));
        assertEquals("COMMIT", jdbc.queryForObject("SELECT phase FROM awx_jobs WHERE task_id=?", String.class, id));
        jdbc.update("UPDATE awx_jobs SET next_attempt_at=0 WHERE task_id=?", id);
        jobs.runPendingOnce();
        assertEquals(3, count("ChatMessage")); assertEquals(1, count("awx_understanding_receipts"));
        assertEquals(1, count("f01_reinforce_effect"));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM awx_jobs WHERE task_id=?", String.class, id));
        org.mockito.Mockito.verify(fixture.provider(), org.mockito.Mockito.times(1)).understandDerived(
                "synthetic answer", "synthetic question", 500, "synthetic");
    }

    @Test void realReceiptRecoveryAfterJobTTLDoesNotComputeOrReinforceAgain() {
        var fixture = t3Fixture(); String id = admit(fixture.task()); jobs.runPendingOnce();
        assertEquals(3, count("ChatMessage")); assertEquals(1, count("f01_reinforce_effect"));
        jdbc.update("UPDATE awx_jobs SET expires_at=0 WHERE task_id=?", id); jobs.maintenance();
        String replay = admit(withRun(fixture.task(), "run-replay")); jobs.runPendingOnce();
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM awx_jobs WHERE task_id=?", String.class, replay));
        assertEquals(3, count("ChatMessage")); assertEquals(1, count("awx_understanding_receipts"));
        assertEquals(1, count("f01_reinforce_effect"));
        org.mockito.Mockito.verify(fixture.provider(), org.mockito.Mockito.times(1)).understandDerived(
                "synthetic answer", "synthetic question", 500, "synthetic");
    }

    @Test void realTwoWorkerRacePersistsOneUsumReceiptAndLocalEffect() throws Exception {
        var fixture = t3Fixture();
        var rendezvous = new java.util.concurrent.CountDownLatch(2);
        org.mockito.Mockito.when(fixture.provider().understandDerived(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(c -> {
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    rendezvous.countDown();
                    assertTrue(rendezvous.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return syntheticOutcome();
                });
        String first = admit(fixture.task()), second = admit(withRun(fixture.task(), "run-second"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(jobs::runPendingOnce);
            var b = pool.submit(jobs::runPendingOnce);
            a.get(10, java.util.concurrent.TimeUnit.SECONDS); b.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(3, count("ChatMessage")); assertEquals(1, count("awx_understanding_receipts"));
        assertEquals(1, count("f01_reinforce_effect"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM awx_jobs WHERE state='SUCCEEDED'", Integer.class));
    }

    @Test void sourceMutationDuringComputeBlocksRealT3() {
        var fixture = t3Fixture();
        org.mockito.Mockito.when(fixture.provider().understandDerived(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(c -> {
                    chatTx.executeWithoutResult(tx -> em.find(ChatMessage.class, fixture.task().userMessageId())
                            .setContent("edited during compute"));
                    return syntheticOutcome();
                });
        String id = admit(fixture.task()); jobs.runPendingOnce();
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM awx_jobs WHERE task_id=?", String.class, id));
        assertEquals(2, count("ChatMessage")); assertEquals(0, count("awx_understanding_receipts"));
        assertEquals(0, count("f01_reinforce_effect"));
    }
    @Test void productionFactoryUsesTheSameJpaTransactionManager() {
        assertSame(manager, jobTx().getTransactionManager(), "no private TM on the configured job path");
    }

    @Test void springBootAutoConfigurationSuppliesTheSharedManagerWithoutManualBinding() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class))
                .withBean(PersistenceManagedTypes.class, () -> PersistenceManagedTypes.of(
                        ChatSession.class.getName(), ChatMessage.class.getName(), Administrator.class.getName()))
                .withBean(ObjectMapper.class, () -> new ObjectMapper())
                .withUserConfiguration(JobConfig.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:f01_boot_" + UUID.randomUUID(),
                        "spring.jpa.hibernate.ddl-auto=create-drop", "jobs.enabled-types=" + TYPE)
                .run(boot -> {
                    assertNull(boot.getStartupFailure());
                    var bootManager = boot.getBean(JpaTransactionManager.class);
                    var bootStore = boot.getBean(JobService.class);
                    assertSame(boot.getBean(DataSource.class), bootManager.getDataSource());
                    var bootTx = (TransactionTemplate) ReflectionTestUtils.getField(bootStore, "transactions");
                    assertSame(bootManager, bootTx.getTransactionManager());
                    assertTrue(bootStore.isTypeDisabled("task_ask"));
                });
    }

    @Test void failureAfterUsumBeforeReceiptRollsBackBoth() { rollbackProbe(false); }

    @Test void failureAfterReceiptBeforeSuccessRollsBackBoth() { rollbackProbe(true); }

    private void rollbackProbe(boolean afterReceipt) {
        var injected = new IllegalStateException("injected_f01_transaction_failure");
        var thrown = assertThrows(RuntimeException.class, () -> jobTx().executeWithoutResult(tx -> {
            Long usumId = strictHistory().appendMessageStrictReturningId(sessionId, "system", "⎔USUM⎔{}");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ChatMessage", Integer.class));
            if (afterReceipt) insertReceipt(usumId, "synthetic-task");
            throw injected;
        }));
        assertSame(injected, thrown, "must reach the actual failure boundary after JPA flush");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ChatMessage", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM awx_understanding_receipts", Integer.class));
    }

    private void insertReceipt(long usumId, String taskId) {
        jdbc.update("INSERT INTO awx_understanding_receipts(effect_key,owner_namespace,session_id,channel,"
                + "consent_epoch,user_message_id,user_revision,assistant_message_id,assistant_revision,kind,"
                + "original_run_id,job_task_id,usum_message_id,result_sha256,persisted_at,receipt_state)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PERSISTED')",
                "e".repeat(64), "a".repeat(64), sessionId, "GENERAL", 1, 1, 101, 2, 102,
                "UNDERSTANDING", "synthetic-run", taskId, usumId, "d".repeat(64), System.currentTimeMillis());
    }

    @Test void additiveReceiptSurvivesTerminalJobRetentionCleanup() {
        String id = jobs.enqueue(TYPE, Map.of(), Map.of("ownerHash", "synthetic-owner"), null);
        jdbc.update("UPDATE awx_jobs SET state='SUCCEEDED' WHERE task_id=?", id);
        jobTx().executeWithoutResult(tx -> {
            var usum = new ChatMessage(em.find(ChatSession.class, sessionId), "system", "[USUM] synthetic");
            em.persist(usum);
            em.flush();
            insertReceipt(usum.getId(), id);
        });
        jdbc.update("UPDATE awx_jobs SET expires_at=0 WHERE task_id=?", id);
        jobs.maintenance();
        assertEquals("NOT_FOUND", jobs.status(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM awx_job_results", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM awx_understanding_receipts", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ChatMessage", Integer.class));
    }

    @Test void receiptRequiresUsumAndKeepsOneRowPerEffect() {
        jobTx().executeWithoutResult(tx -> {
            var usum = new ChatMessage(em.find(ChatSession.class, sessionId), "system", "[USUM] synthetic");
            em.persist(usum);
            em.flush();
            insertReceipt(usum.getId(), "first");
        });
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> insertReceipt(99L, "replay"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM awx_understanding_receipts", Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE awx_understanding_receipts SET usum_message_id=NULL"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE awx_understanding_receipts SET receipt_state='SUCCEEDED'"));
    }

    @Test void jobInsertFailureRollsBackTheAssistantTranscript() {
        jdbc.execute("ALTER TABLE awx_jobs ADD CONSTRAINT reject_fixture CHECK (job_type <> 'understanding_summary_v1')");
        assertThrows(RuntimeException.class, () -> chatTx.executeWithoutResult(tx -> {
            strictHistory().appendMessageStrictReturningId(sessionId, "assistant", "synthetic answer");
            jobs.enqueueOnce(TYPE, Map.of(), Map.of("ownerHash", "synthetic-owner"), null, "run", "a".repeat(64));
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ChatMessage", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM awx_jobs", Integer.class));
    }

    @Test void taskAskAdmissionIsDeniedBeforeAnyRowIsWritten() {
        assertThrows(RejectedExecutionException.class,
                () -> jobs.enqueue("task_ask", Map.of(), Map.of("ownerHash", "synthetic-owner"), null));
        assertThrows(RejectedExecutionException.class,
                () -> jobs.enqueueOnce("task_ask", Map.of(), Map.of("ownerHash", "synthetic-owner"), null, "run", "a".repeat(64)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM awx_jobs", Integer.class));
    }

    @Test void disabledTaskAskApiReturns503BeforeGenerationOrCostAdmission() {
        var chat = org.mockito.Mockito.mock(com.example.lms.service.ChatService.class);
        var notifier = org.mockito.Mockito.mock(com.example.lms.integrations.n8n.N8nNotifier.class);
        var costs = org.mockito.Mockito.mock(com.example.lms.api.ChatGenerationAdmissionFilter.class);
        var api = new com.example.lms.api.TasksApiController(chat, jobs, notifier);
        ReflectionTestUtils.setField(api, "costs", costs);
        var request = new com.example.lms.api.TasksApiController.TaskAskRequest(
                "synthetic", null, false, false, null, null, null);
        assertEquals(503, api.askAsync(request).getStatusCode().value());
        assertEquals("job_type_disabled", api.askAsync(request, "run").getBody().get("error"));
        org.mockito.Mockito.verifyNoInteractions(chat, notifier, costs);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM awx_jobs", Integer.class));
    }

    @Test void persistedDisabledTypeAndCallbackNeverExecute() {
        var executes = new AtomicInteger();
        var callbacks = new AtomicInteger();
        jobs.registerHandler("task_ask", new JobService.JobHandler() {
            public String execute(String input) { executes.incrementAndGet(); return "{}"; }
            public boolean needsCompletion(String input) { return true; }
            public boolean completed(String id, String input, String output) { callbacks.incrementAndGet(); return true; }
        });
        jdbc.update("INSERT INTO awx_jobs(task_id,job_type,owner_hash,payload,state,created_at,updated_at) VALUES('pending','task_ask','synthetic-owner','{}','PENDING',0,0)");
        jdbc.update("INSERT INTO awx_jobs(task_id,job_type,owner_hash,payload,state,created_at,updated_at,expires_at,result_ref,callback_state) VALUES('finished','task_ask','synthetic-owner','{}','SUCCEEDED',0,0,?,'result','PENDING')", Long.MAX_VALUE);
        jdbc.update("INSERT INTO awx_job_results VALUES('result','finished','{}',?,2)", "b".repeat(64));
        jobs.runPendingOnce();
        assertEquals(0, executes.get());
        assertEquals(0, callbacks.get());
        assertEquals("PENDING", jobs.status("pending"));
    }

    @Test void understandingCannotUseLegacyCompletionHooks() {
        var completionHooks = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> jobs.registerHandler(TYPE, new JobService.JobHandler() {
            public String execute(String input) { return "{}"; }
            public boolean needsCompletion(String input) { completionHooks.incrementAndGet(); return true; }
            public boolean completed(String id, String input, String output) { completionHooks.incrementAndGet(); return true; }
        }));
        String id = jobs.enqueue(TYPE, Map.of(), Map.of("ownerHash", "synthetic-owner"), null);
        jobs.runPendingOnce();
        jobs.runPendingOnce();
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM awx_jobs WHERE task_id=?", String.class, id));
        assertEquals(0, completionHooks.get());
        assertEquals("NONE", jdbc.queryForObject("SELECT callback_state FROM awx_jobs WHERE task_id=?", String.class, id));
    }
}
