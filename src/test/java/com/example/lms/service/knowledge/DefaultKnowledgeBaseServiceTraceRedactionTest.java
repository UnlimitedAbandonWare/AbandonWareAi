package com.example.lms.service.knowledge;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.rag.graph.KnowledgeDeltaGraphProjector;
import com.example.lms.service.vector.VectorSidService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultKnowledgeBaseServiceTraceRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void fallbackDomainMismatchTraceUsesHashAndLengthOnly() {
        String rawDomain = "privateLearningDomain";
        DomainKnowledge knowledge = new DomainKnowledge();
        knowledge.setDomain(rawDomain);
        knowledge.setEntityName("Lesson42");

        DomainKnowledgeRepository repo = mock(DomainKnowledgeRepository.class);
        when(repo.findByDomainAndEntityNameIgnoreCase("GENERAL", "Lesson42")).thenReturn(Optional.empty());
        when(repo.findByEntityNameIgnoreCase("Lesson42")).thenReturn(Optional.of(knowledge));

        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeDeltaGraphProjector> provider = mock(ObjectProvider.class);
        DefaultKnowledgeBaseService service = new DefaultKnowledgeBaseService(
                repo,
                new ObjectMapper(),
                mock(VectorStoreService.class),
                mock(VectorSidService.class),
                provider);

        assertEquals(Optional.of(knowledge), service.find("GENERAL", "Lesson42"));
        assertEquals("domain_mismatch", TraceStore.get("kb.find.fallback"));
        assertNotNull(TraceStore.get("kb.find.fallback.domainHash"));
        assertEquals(rawDomain.length(), TraceStore.get("kb.find.fallback.domainLength"));
        assertFalse(TraceStore.getAll().containsKey("kb.find.fallback.domain"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawDomain));
    }

    @Test
    void defaultKnowledgeBaseServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/knowledge/DefaultKnowledgeBaseService.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .results()
                        .count(),
                "Knowledge base fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void defaultKnowledgeBaseFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/knowledge/DefaultKnowledgeBaseService.java"));

        assertKbStage(source, "getDomains");
        assertKbStage(source, "getEntityTypes");
        assertKbStage(source, "listEntities");
        assertKbStage(source, "find");
        assertKbStage(source, "evidenceSnippets");
        assertKbStage(source, "stringifyJsonValue");
        assertKbStage(source, "omSafeJson");
        assertKbStage(source, "getLastAccessedAt");
        assertKbStage(source, "getConfidenceScore");
        assertKbStage(source, "getAttribute");
        assertKbStage(source, "getAllRelationships");
        assertKbStage(source, "findMentionedEntities");
    }

    private static void assertKbStage(String source, String stage) {
        org.junit.jupiter.api.Assertions.assertTrue(
                source.contains("log.debug(\"[KB] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing KB fail-soft stage: " + stage);
    }
}
