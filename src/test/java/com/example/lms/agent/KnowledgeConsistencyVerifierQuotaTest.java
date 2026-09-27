package com.example.lms.agent;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeConsistencyVerifierQuotaTest {
    private final DomainKnowledgeRepository repository = mock(DomainKnowledgeRepository.class);
    private final KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("agent.knowledge-consistency.enabled=true")
            .withBean(DomainKnowledgeRepository.class, () -> repository)
            .withBean(KnowledgeBaseService.class, () -> knowledgeBase)
            .withUserConfiguration(KnowledgeConsistencyVerifier.class);

    @Test
    void heuristicChecksLeaveActualExternalRequestPermitAvailable() {
        DomainKnowledge entity = new DomainKnowledge();
        entity.setDomain("fixture");
        entity.setEntityName("sample");
        when(repository.findAll()).thenReturn(List.of(entity));
        when(knowledgeBase.getAllRelationships(anyString(), anyString()))
                .thenReturn(Map.of("RELATIONSHIP_PREFERRED", Set.of("partner")));
        FreeTierApiThrottleService quota = new FreeTierApiThrottleService(1, 1);

        runner.withBean(FreeTierApiThrottleService.class, () -> quota).run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(KnowledgeConsistencyVerifier.class).verify();
            verify(knowledgeBase).getAllRelationships("fixture", "sample");
            assertTrue(quota.canProceed(), "heuristic-only work must leave the real request permit unused");
            assertFalse(quota.canProceed(), "the actual request still consumes the one-permit quota");
        });
    }

    @Test
    void heuristicVerifierStartsWithoutOptionalExternalThrottle() {
        runner.run(context -> assertThat(context)
                .hasNotFailed().hasSingleBean(KnowledgeConsistencyVerifier.class));
    }

    @Test
    void disabledVerifierKeepsItsExistingOptInBoundary() {
        runner.withPropertyValues("agent.knowledge-consistency.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(KnowledgeConsistencyVerifier.class));
    }
}
