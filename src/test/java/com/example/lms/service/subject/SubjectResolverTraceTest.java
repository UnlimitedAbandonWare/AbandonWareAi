package com.example.lms.service.subject;

import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubjectResolverTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void entityScanFailureRecordsSafeErrorType() {
        DomainKnowledgeRepository knowledgeRepo = mock(DomainKnowledgeRepository.class);
        when(knowledgeRepo.findAll()).thenThrow(new IllegalStateException("raw entity scan failure"));

        List<String> result = new SubjectResolver(mock(KnowledgeBaseService.class), knowledgeRepo)
                .resolveMultipleEntities("private entity query");

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("subject.resolver.suppressed.entityScan"));
        assertEquals("IllegalStateException", TraceStore.get("subject.resolver.suppressed.entityScan.errorType"));
        assertEquals("entityScan", TraceStore.get("subject.resolver.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("subject.resolver.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw entity scan failure"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private entity query"));
    }

    @Test
    void compoundSubjectsDropOnlyBoundedTopicParticles() {
        SubjectResolver resolver = new SubjectResolver((KnowledgeBaseService) null);

        assertEquals("메가학원", resolver.resolve("메가학원은 어때?", null).orElseThrow());
        assertEquals("Academy", resolver.resolve("Academy는 어때?", null).orElseThrow());
        assertEquals("메가학원부", resolver.resolve("메가학원부 소식", null).orElseThrow());
        assertEquals("메가학원", resolver.resolve("메가학원 어때?", null).orElseThrow());
        assertEquals("메가학원은하", resolver.resolve("메가학원은하 소식", null).orElseThrow());
        assertEquals("Academy는빛", resolver.resolve("Academy는빛", null).orElseThrow());
    }
}
