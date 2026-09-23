package com.example.lms.service.rag.plan;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanModelResolverTest {

    @Test
    void preservesDirectRouterAliasesForSpecializedModels() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.coder.model", "qwen3-coder:30b")
                .withProperty("llm.judge.model", "qwen3:30b")
                .withProperty("llm.vision.model", "qwen3-vl:8b")
                .withProperty("llm.chat-model", "gemma4:26b")
                .withProperty("llm.fast.model", "qwen3:8b");
        PlanModelResolver resolver = new PlanModelResolver(env);

        assertEquals("qwen3-coder:30b", resolver.resolveRequestedModel("llmrouter.coder"));
        assertEquals("qwen3:30b", resolver.resolveRequestedModel("llmrouter.judge"));
        assertEquals("qwen3-vl:8b", resolver.resolveRequestedModel("llmrouter.vision"));
        assertEquals("llmrouter.macmini", resolver.resolveRequestedModel("llmrouter.macmini"));
        assertEquals("llmrouter.external", resolver.resolveRequestedModel("llmrouter.external"));
        assertEquals("llmrouter.unknown", resolver.resolveRequestedModel("llmrouter.unknown"));
    }
}
