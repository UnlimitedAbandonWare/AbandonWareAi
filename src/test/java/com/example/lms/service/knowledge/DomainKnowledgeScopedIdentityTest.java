package com.example.lms.service.knowledge;

import ai.abandonware.nova.orch.aop.KnowledgeBasePersistenceAspect;
import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.domain.knowledge.EntityAttribute;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.rag.graph.KnowledgeDeltaGraphProjector;
import com.example.lms.service.vector.VectorSidService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.lms.service.knowledge.KnowledgeBaseService.IntegrationStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DomainKnowledgeScopedIdentityTest {
    private StandardServiceRegistry registry;
    private SessionFactory factory;
    private EntityManager em;
    private DomainKnowledgeRepository repo;
    private DefaultKnowledgeBaseService service;

    @BeforeEach
    void schemaFromActualEntities() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:kb_identity_" + UUID.randomUUID())
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .applySetting("hibernate.show_sql", false)
                .build();
        factory = new MetadataSources(registry).addAnnotatedClass(DomainKnowledge.class)
                .addAnnotatedClass(EntityAttribute.class).buildMetadata().buildSessionFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        repo = new JpaRepositoryFactory(em).getRepository(DomainKnowledgeRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeDeltaGraphProjector> graph = mock(ObjectProvider.class);
        service = new DefaultKnowledgeBaseService(repo, new ObjectMapper(),
                mock(VectorStoreService.class), mock(VectorSidService.class), graph);
        ReflectionTestUtils.setField(service, "persistEnabled", true);
        ReflectionTestUtils.setField(service, "indexEnabled", false);
        ReflectionTestUtils.setField(service, "minConfidence", 0.65d);
    }

    @AfterEach
    void closeTemporarySchema() {
        try {
            if (em != null) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                em.close();
            }
        } finally {
            try { if (factory != null) factory.close(); }
            finally { if (registry != null) StandardServiceRegistryBuilder.destroy(registry); }
            TraceStore.clear();
        }
    }

    @Test
    void normalServicePersistsSameNameInTwoDomains() {
        assertEquals(CREATED, integrate("DOMAIN_A", "alpha"));
        assertEquals(CREATED, integrate("DOMAIN_B", "beta"));
        em.flush();
        em.clear();
        assertEquals(2, repo.count());
        assertEquals("alpha", value("DOMAIN_A"));
        assertEquals("beta", value("DOMAIN_B"));
    }

    @Test
    void normalScopedUpdateRetainsOtherDomainAndExistingIdentity() {
        integrate("DOMAIN_A", "alpha");
        integrate("DOMAIN_B", "beta");
        Long first = repo.findByDomainAndEntityNameIgnoreCase("DOMAIN_A", "SharedName").orElseThrow().getId();
        assertEquals(UPDATED, integrate("DOMAIN_A", "revised"));
        em.flush();
        em.clear();
        assertEquals(2, repo.count());
        assertEquals(first, repo.findByDomainAndEntityNameIgnoreCase("DOMAIN_A", "SharedName").orElseThrow().getId());
        assertEquals("revised", value("DOMAIN_A"));
        assertEquals("beta", value("DOMAIN_B"));
    }

    @Test
    void exactDomainWinsAndAmbiguousGlobalFallbackSelectsNeither() {
        integrate("DOMAIN_A", "alpha");
        integrate("DOMAIN_B", "beta");
        em.flush();
        em.clear();
        assertEquals("DOMAIN_A", service.find("DOMAIN_A", "sharedname").orElseThrow().getDomain());
        assertEquals("DOMAIN_B", service.find("DOMAIN_B", "sharedname").orElseThrow().getDomain());
        assertTrue(service.find("GENERAL", "sharedname").isEmpty());
        assertEquals("DOMAIN_A", repo.findByDomainAndEntityNameIgnoreCase("DOMAIN_A", "SharedName").orElseThrow().getDomain());
        assertEquals("DOMAIN_B", repo.findByDomainAndEntityNameIgnoreCase("DOMAIN_B", "SharedName").orElseThrow().getDomain());
    }

    @Test
    void uniqueLegacyClassifierMismatchFallbackStillReturnsItsOnlyCandidate() {
        integrate("DOMAIN_A", "alpha");
        em.flush();
        em.clear();
        assertEquals("DOMAIN_A", service.find("GENERAL", "sharedname").orElseThrow().getDomain());
        assertEquals("domain_mismatch", TraceStore.get("kb.find.fallback"));
    }

    @Test
    void sameDomainAndExactNameDuplicateRemainsDatabaseRejected() {
        integrate("DOMAIN_A", "alpha");
        DomainKnowledge duplicate = knowledge("DOMAIN_A", null);
        assertThrows(RuntimeException.class, () -> { repo.save(duplicate); em.flush(); });
    }

    @Test
    void enabledLegacyOverlayCreatesAnotherDomainWithoutMovingExistingRow() throws Throwable {
        integrate("DOMAIN_A", "alpha");
        assertEquals(CREATED, aspect(repo).persistVerifiedKnowledge(skippedCall()));
        em.flush();
        em.clear();
        assertEquals(2, repo.count());
        assertEquals("alpha", value("DOMAIN_A"));
        assertEquals("overlay", value("DOMAIN_B"));
    }

    @Test
    void legacyConstraintFailureDoesNotStealAnotherDomainsRecord() throws Throwable {
        DomainKnowledgeRepository failing = mock(DomainKnowledgeRepository.class);
        DomainKnowledge other = knowledge("DOMAIN_A", 11L);
        when(failing.findByDomainAndEntityNameIgnoreCase("DOMAIN_B", "SharedName")).thenReturn(Optional.empty());
        when(failing.findByEntityNameIgnoreCase("SharedName")).thenReturn(Optional.of(other));
        when(failing.save(any(DomainKnowledge.class))).thenThrow(new DataIntegrityViolationException("fixture_constraint"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(SKIPPED, aspect(failing).persistVerifiedKnowledge(skippedCall()));
        assertEquals("DOMAIN_A", other.getDomain());
        assertTrue(other.getAttributes().isEmpty());
        verify(failing, never()).findByEntityNameIgnoreCase(anyString());
        verify(failing, times(1)).save(any(DomainKnowledge.class));
    }

    @Test
    void concurrentScopedInsertRetryUpdatesOnlyRequestedDomain() throws Throwable {
        DomainKnowledgeRepository racing = mock(DomainKnowledgeRepository.class);
        DomainKnowledge other = knowledge("DOMAIN_A", 11L);
        DomainKnowledge scoped = knowledge("DOMAIN_B", 12L);
        when(racing.findByDomainAndEntityNameIgnoreCase("DOMAIN_B", "SharedName"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(scoped));
        when(racing.findByEntityNameIgnoreCase("SharedName")).thenReturn(Optional.of(other));
        when(racing.save(any(DomainKnowledge.class))).thenThrow(new DataIntegrityViolationException("fixture_constraint"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(UPDATED, aspect(racing).persistVerifiedKnowledge(skippedCall()));
        assertEquals("DOMAIN_A", other.getDomain());
        assertTrue(other.getAttributes().isEmpty());
        assertEquals("DOMAIN_B", scoped.getDomain());
        assertEquals("overlay", scoped.getAttributes().iterator().next().getAttributeValue());
        verify(racing, never()).findByEntityNameIgnoreCase(anyString());
        verify(racing).save(scoped);
    }

    private KnowledgeBaseService.IntegrationStatus integrate(String domain, String value) {
        return service.integrateVerifiedKnowledge(domain, "SharedName",
                "{\"attributes\":{\"kind\":\"" + value + "\"}}", List.of(), 0.9d);
    }

    private String value(String domain) {
        return repo.findByDomainAndEntityNameIgnoreCase(domain, "SharedName").orElseThrow()
                .getAttributes().stream().filter(a -> "kind".equals(a.getAttributeKey()))
                .map(EntityAttribute::getAttributeValue).findFirst().orElseThrow();
    }

    private static DomainKnowledge knowledge(String domain, Long id) {
        DomainKnowledge result = new DomainKnowledge();
        result.setDomain(domain);
        result.setEntityName("SharedName");
        result.setId(id);
        return result;
    }

    private static KnowledgeBasePersistenceAspect aspect(DomainKnowledgeRepository repository) {
        KnowledgeBasePersistenceAspect result = new KnowledgeBasePersistenceAspect(repository, new ObjectMapper());
        ReflectionTestUtils.setField(result, "overlayEnabled", true);
        ReflectionTestUtils.setField(result, "persistEnabled", true);
        ReflectionTestUtils.setField(result, "maxAttributes", 50);
        return result;
    }

    private static ProceedingJoinPoint skippedCall() throws Throwable {
        ProceedingJoinPoint result = mock(ProceedingJoinPoint.class);
        when(result.proceed()).thenReturn(SKIPPED);
        when(result.getArgs()).thenReturn(new Object[] {"DOMAIN_B", "SharedName",
                "{\"attributes\":[{\"name\":\"kind\",\"value\":\"overlay\"}]}", List.of(), 0.9d});
        return result;
    }
}
