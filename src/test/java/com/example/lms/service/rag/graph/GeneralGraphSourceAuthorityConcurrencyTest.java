package com.example.lms.service.rag.graph;

import com.example.lms.assist.MemoryEvidence;
import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.domain.enums.MemoryProfile;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class GeneralGraphSourceAuthorityConcurrencyTest {
    private static SessionFactory factory;
    private static EntityManager em;
    private static TransactionTemplate tx;
    private static GeneralGraphSourceAuthority authority;
    private ExecutorService workers;
    private GeneralGraphScope authorized;
    private GeneralGraphScope scope;
    private MemoryEvidence evidence;
    private long messageId;

    @BeforeAll
    static void database() {
        factory = new Configuration()
                .addAnnotatedClass(ChatSession.class).addAnnotatedClass(ChatMessage.class)
                .addAnnotatedClass(Administrator.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:graph_fence_" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.show_sql", "false")
                .buildSessionFactory();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        var manager = new JpaTransactionManager(factory);
        tx = new TransactionTemplate(manager);
        var repositories = new JpaRepositoryFactory(em);
        var target = new GeneralGraphSourceAuthority(
                repositories.getRepository(ChatSessionRepository.class),
                repositories.getRepository(ChatMessageRepository.class), new ObjectMapper());
        ReflectionTestUtils.setField(target, "entityManager", em);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        authority = (GeneralGraphSourceAuthority) proxy.getProxy();
    }

    @AfterAll
    static void closeDatabase() {
        if (factory != null) factory.close();
    }

    @BeforeEach
    void source() {
        workers = Executors.newFixedThreadPool(2);
        authorized = tx.execute(status -> {
            var session = new ChatSession("synthetic concurrency", "synthetic-owner", "ANON");
            em.persist(session);
            var message = new ChatMessage(session, "user", "Spark was considered but not purchased.");
            em.persist(message);
            em.flush();
            messageId = message.getId();
            return GeneralGraphScope.authorize(session, null, "synthetic-owner").orElseThrow();
        });
        scope = authority.bindPolicy(authorized, MemoryProfile.LIGHT).orElseThrow();
        evidence = authority.source(scope, messageId).orElseThrow();
    }

    @AfterEach
    void stopWorkers() throws Exception {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void consentOffWaitsForIndexCommitAndRejectsEveryLaterOldTask() throws Exception {
        race(() -> authority.bindPolicy(authorized, MemoryProfile.OFF));
        assertTrue(authority.source(scope, messageId).isEmpty());
        var newConsent = authority.bindPolicy(authorized, MemoryProfile.LIGHT).orElseThrow();
        assertTrue(newConsent.consentEpoch() > scope.consentEpoch());
        assertTrue(authority.withCurrentSource(scope, evidence, e -> true).isEmpty());
    }

    @Test
    void correctionCannotPassTheMessageLockWhileOldIndexIsBeingCommitted() throws Exception {
        race(() -> tx.executeWithoutResult(status ->
                em.find(ChatMessage.class, messageId).setContent("That condition is cancelled.")));
        var corrected = authority.source(scope, messageId).orElseThrow();
        assertNotEquals(evidence.sourceRevision(), corrected.sourceRevision());
        assertTrue(authority.withCurrentSource(scope, evidence, e -> true).isEmpty());
        assertEquals("That condition is cancelled.", corrected.text());
    }

    @Test
    void deletionCannotPassTheMessageLockAndCannotBeResurrected() throws Exception {
        race(() -> tx.executeWithoutResult(status -> em.remove(em.find(ChatMessage.class, messageId))));
        assertTrue(authority.source(scope, messageId).isEmpty());
        assertTrue(authority.withCurrentSource(scope, evidence, e -> true).isEmpty());
    }

    @Test
    void staleFirstLevelEntityIsRefreshedBeforeRevisionCheck() throws Exception {
        var cached = new CountDownLatch(1);
        var changed = new CountDownLatch(1);
        Future<Boolean> staleReader = workers.submit(() -> tx.execute(status -> {
            em.find(ChatMessage.class, messageId);
            cached.countDown();
            await(changed);
            return authority.withCurrentSource(scope, evidence, e -> true).isPresent();
        }));
        assertTrue(cached.await(5, TimeUnit.SECONDS));
        try {
            tx.executeWithoutResult(status ->
                    em.find(ChatMessage.class, messageId).setContent("Corrected original."));
        } finally {
            changed.countDown();
        }
        assertFalse(staleReader.get(5, TimeUnit.SECONDS));
    }

    private void race(Runnable mutation) throws Exception {
        var indexEntered = new CountDownLatch(1);
        var releaseIndex = new CountDownLatch(1);
        var mutationEntered = new CountDownLatch(1);
        var writes = new AtomicInteger();
        Future<Boolean> index = workers.submit(() -> authority.withCurrentSource(scope, evidence, e -> {
            indexEntered.countDown();
            await(releaseIndex);
            writes.incrementAndGet();
            return true;
        }).orElse(false));
        try {
            assertTrue(indexEntered.await(5, TimeUnit.SECONDS));
            Future<?> change = workers.submit(() -> {
                mutationEntered.countDown();
                mutation.run();
            });
            assertTrue(mutationEntered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> change.get(250, TimeUnit.MILLISECONDS),
                    "source or policy mutation must wait for the guarded index commit");
            releaseIndex.countDown();
            assertTrue(index.get(5, TimeUnit.SECONDS));
            change.get(5, TimeUnit.SECONDS);
            assertEquals(1, writes.get());
        } finally {
            releaseIndex.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic latch timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
