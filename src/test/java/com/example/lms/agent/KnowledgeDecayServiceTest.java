package com.example.lms.agent;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.repository.SynergyStatRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDecayServiceTest {

    @Test
    void oneDecayRunLoadsSynergySnapshotOnceForAllKnowledgeRows() {
        DomainKnowledgeRepository knowledgeRepo = mock(DomainKnowledgeRepository.class);
        SynergyStatRepository synergyRepo = mock(SynergyStatRepository.class);
        List<DomainKnowledge> knowledge = List.of(
                staleKnowledge("entity-a"),
                staleKnowledge("entity-b"),
                staleKnowledge("entity-c"));
        when(knowledgeRepo.findAll()).thenReturn(knowledge);
        when(synergyRepo.findAll()).thenReturn(List.of());

        new KnowledgeDecayService(knowledgeRepo, synergyRepo).decay();

        verify(synergyRepo, times(1)).findAll();
        for (DomainKnowledge item : knowledge) {
            verify(knowledgeRepo).save(item);
        }
    }

    @Test
    void synergySnapshotFailureRemainsFailSoftForKnowledgeDecay() {
        DomainKnowledgeRepository knowledgeRepo = mock(DomainKnowledgeRepository.class);
        SynergyStatRepository synergyRepo = mock(SynergyStatRepository.class);
        DomainKnowledge knowledge = staleKnowledge("entity-fail-soft");
        when(knowledgeRepo.findAll()).thenReturn(List.of(knowledge));
        when(synergyRepo.findAll()).thenThrow(new IllegalStateException("synthetic lookup failure"));

        new KnowledgeDecayService(knowledgeRepo, synergyRepo).decay();

        verify(knowledgeRepo).save(knowledge);
        verify(synergyRepo, times(1)).findAll();
    }

    private static DomainKnowledge staleKnowledge(String entityName) {
        DomainKnowledge item = new DomainKnowledge();
        item.setDomain("synthetic-domain");
        item.setEntityName(entityName);
        item.setConfidenceScore(1.0d);
        item.setLastAccessedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return item;
    }
}
