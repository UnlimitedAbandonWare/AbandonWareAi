package com.example.lms;

import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.routing.RouteSignal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class ModelEscalationTest {

    @Test
    void triggersEscalationWhenInfoNoneDespiteEvidence() {
        EvidenceAwareGuard guard = new EvidenceAwareGuard();
        List<EvidenceAwareGuard.EvidenceDoc> docs = List.of(
                new EvidenceAwareGuard.EvidenceDoc("W1", "스커크 아야카 조합", "추천 조합 상세")
        );

        final boolean[] escalated = {false};
        ModelRouter router = new ModelRouter() {
            @Override public dev.langchain4j.model.chat.ChatModel route(RouteSignal signal) { return null; }
            @Override public dev.langchain4j.model.chat.ChatModel escalate(RouteSignal signal) { escalated[0] = true; return null; }
        };

        EvidenceAwareGuard.Result r = guard.ensureCoverage("정보 없음", docs, router,
                new RouteSignal(0.3,0,0.2,0,null,null,4096,null,"test"), 1);

        assertTrue(escalated[0], "Should escalate once");
    }
}
