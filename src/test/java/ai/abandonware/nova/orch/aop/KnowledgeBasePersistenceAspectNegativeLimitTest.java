package ai.abandonware.nova.orch.aop;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBasePersistenceAspectNegativeLimitTest {

    @Test
    void negativeAttributeLimitKeepsPersistenceFailSoft() throws Throwable {
        DomainKnowledgeRepository repo = mock(DomainKnowledgeRepository.class);
        when(repo.findByDomainAndEntityNameIgnoreCase("fixture-domain", "fixture-entity"))
                .thenReturn(Optional.empty());
        when(repo.findByEntityNameIgnoreCase("fixture-entity")).thenReturn(Optional.empty());

        KnowledgeBasePersistenceAspect aspect = new KnowledgeBasePersistenceAspect(repo, new ObjectMapper());
        ReflectionTestUtils.setField(aspect, "overlayEnabled", true);
        ReflectionTestUtils.setField(aspect, "persistEnabled", true);
        ReflectionTestUtils.setField(aspect, "maxAttributes", -1);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(KnowledgeBaseService.IntegrationStatus.SKIPPED);
        when(pjp.getArgs()).thenReturn(new Object[] {
                "fixture-domain",
                "fixture-entity",
                "{\"attributes\":[{\"name\":\"a\",\"value\":\"1\"},{\"name\":\"b\",\"value\":\"2\"}]}",
                List.of(),
                0.8d
        });

        Object result = aspect.persistVerifiedKnowledge(pjp);

        assertEquals(KnowledgeBaseService.IntegrationStatus.CREATED, result);
        var saved = org.mockito.ArgumentCaptor.forClass(DomainKnowledge.class);
        verify(repo).save(saved.capture());
        assertEquals(0, saved.getValue().getAttributes().size());
    }
}
