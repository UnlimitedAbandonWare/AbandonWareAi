package com.example.lms.service.knowledge;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.Triple;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.rag.graph.KnowledgeDeltaGraphProjector;
import com.example.lms.service.vector.VectorSidService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultKnowledgeBaseServiceGraphProjectionTest {

    @Test
    void applyReturnsUpdatedWhenGraphProjectionSucceedsWithoutVectorMemories() {
        KnowledgeDeltaGraphProjector projector = mock(KnowledgeDeltaGraphProjector.class);
        when(projector.project(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new KnowledgeDeltaGraphProjector.ProjectReport(true, 1, "", ""));

        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeDeltaGraphProjector> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(projector);

        DefaultKnowledgeBaseService service = new DefaultKnowledgeBaseService(
                mock(DomainKnowledgeRepository.class),
                new ObjectMapper(),
                mock(VectorStoreService.class),
                mock(VectorSidService.class),
                provider);

        KnowledgeDelta delta = new KnowledgeDelta(
                List.of(new Triple("GraphRAG", "USES", "Neo4j", "manual://test")),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertEquals(KnowledgeBaseService.IntegrationStatus.UPDATED, service.apply(delta));
    }
}
