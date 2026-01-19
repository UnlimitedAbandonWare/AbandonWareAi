package com.example.lms;

import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.routing.RouteSignal;
import org.junit.jupiter.api.Test;
import java.util.List;


import static org.junit.jupiter.api.Assertions.*;


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


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}