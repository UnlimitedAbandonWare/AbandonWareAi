package com.example.lms.service.rag.guard;

import com.example.lms.guard.GuardProfileProps;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EmptyEvidenceLocalContractTest {
    @AfterEach void clearTrace() { TraceStore.clear(); }
    private EvidenceGate gate(boolean allowEmpty) {
        EvidenceGate gate = new EvidenceGate(.05, .02, .6, .8, allowEmpty);
        GuardProfileProps profiles = new GuardProfileProps();
        profiles.setProfile("PROFILE_MEMORY");
        ReflectionTestUtils.setField(gate, "guardProfileProps", profiles);
        return gate;
    }
    @Test void localProfileExplicitlyBindsBothSoftGateSettings() throws Exception {
        var properties = new YamlPropertySourceLoader().load("local", new ClassPathResource("application-local.yml"));
        assertTrue(properties.stream().anyMatch(p -> Boolean.TRUE.equals(p.getProperty("gate.evidence.allow-empty"))));
        assertTrue(properties.stream().anyMatch(p -> Boolean.TRUE.equals(p.getProperty("gate.citation.log-only"))));
    }
    @Test void emptyEvidenceAllowedWithExplicitMissingEvidenceHint() {
        assertTrue(gate(true).hasSufficientCoverage("테스트 질문", List.of(), List.of(), List.of(), false));
        assertEquals(0, TraceStore.get("chat.evidence.count"));
        assertEquals(java.util.Map.of("status", "근거 없음"), TraceStore.get("guard.citation.hint"));
    }
    @Test void shortGreetingInLocalModeNeedsNoRetrievalEvidence() {
        for (String greeting : List.of("안녕", "반가워", "hi")) {
            assertTrue(gate(true).hasSufficientCoverage(greeting, List.of(), List.of(), List.of(), false));
        }
    }
    @Test void strictDefaultStillBlocksEmptyEvidence() {
        assertFalse(gate(false).hasSufficientCoverage("테스트 질문", List.of(), List.of(), List.of(), false));
    }
}
