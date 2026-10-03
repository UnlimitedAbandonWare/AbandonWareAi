package com.example.lms.settings;

import com.example.lms.domain.UserPreferenceProfile;
import com.example.lms.service.ChatPreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatPreferencePersistenceTest {
    EntityManagerFactory emf; ChatPreferenceService service;
    final String owner = "a".repeat(64);
    @BeforeEach void open() {
        emf = new Configuration().addAnnotatedClass(UserPreferenceProfile.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:prefs" + System.nanoTime() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.show_sql", "false").buildSessionFactory();
        service = new ChatPreferenceService(new JpaTransactionManager(emf), new ObjectMapper());
        ReflectionTestUtils.setField(service, "entityManager", SharedEntityManagerCreator.createSharedEntityManager(emf));
    }
    @AfterEach void close() { emf.close(); }
    @Test void readsDoNotInsertAndFalseZeroRemainSparse() {
        assertEquals(0, service.read(owner).revision());
        var em = emf.createEntityManager();
        try { assertEquals(0L, em.createQuery("select count(p) from UserPreferenceProfile p", Long.class).getSingleResult()); }
        finally { em.close(); }
        var saved = service.patch(owner, Map.of("useRag", false, "temperature", 0), List.of(), 0, null);
        assertEquals(Map.of("useRag", false, "temperature", 0.0), saved.overrides());
        assertEquals(saved, service.read(owner));
    }
    @Test void staleRevisionAndHashCannotOverwrite() {
        var saved = service.patch(owner, Map.of("topP", 0.42), List.of(), 0, null);
        assertThrows(ChatPreferenceService.Conflict.class, () -> service.patch(owner, Map.of("topP", 1), List.of(), 0, null));
        assertThrows(ChatPreferenceService.Conflict.class, () -> service.patch(owner, Map.of(), List.of("topP"), 1, "wrong"));
        assertEquals(saved, service.read(owner));
    }
    @Test void partialPatchPreservesUnrelatedProfileFieldsAndUnsetInherits() {
        var em = emf.createEntityManager();
        try {
            em.getTransaction().begin(); var row = new UserPreferenceProfile(); row.setOwnerKey(owner);
            row.setProfileMeta("{\"lensSettings\":{\"enabled\":false}}"); em.persist(row); em.getTransaction().commit();
        } finally { em.close(); }
        var saved = service.patch(owner, Map.of("useRag", false), List.of(), 0, null);
        var reset = service.patch(owner, Map.of(), List.of("useRag"), saved.revision(), saved.hash());
        assertTrue(reset.overrides().isEmpty());
        em = emf.createEntityManager();
        try { assertTrue(em.createQuery("select p from UserPreferenceProfile p", UserPreferenceProfile.class).getSingleResult()
                .getProfileMeta().contains("\"lensSettings\":{\"enabled\":false}")); } finally { em.close(); }
    }
    @Test void whitelistAndRangesRejectUnsafeWrites() {
        assertThrows(IllegalArgumentException.class, () -> service.patch(owner, Map.of("owner", "b"), List.of(), 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.patch(owner, Map.of("topP", Double.NaN), List.of(), 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.patch(owner, Map.of("maxTokens", 1.2), List.of(), 0, null));
        assertEquals(0, service.read(owner).revision());
    }
    @Test void incompatiblePartialModelPairDoesNotCommit() {
        var first = service.patch(owner, Map.of("model", "llmrouter.auto", "modelSelectionMode", "auto"), List.of(), 0, null);
        assertThrows(IllegalArgumentException.class, () -> service.patch(owner, Map.of("modelSelectionMode", "strict"), List.of(), first.revision(), first.hash()));
        assertEquals(first, service.read(owner));
    }
    @Test void concurrentFirstSaveAcceptsExactlyOne() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        Callable<Boolean> call = () -> { start.await(); try {
            service.patch(owner, Map.of("useRag", false), List.of(), 0, null); return true;
        } catch (ChatPreferenceService.Conflict expected) { return false; } };
        try {
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            assertEquals(1, (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, service.read(owner).revision());
        } finally { pool.shutdownNow(); }
    }
}
