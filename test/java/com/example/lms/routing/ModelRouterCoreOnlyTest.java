package com.example.lms.routing;

import com.example.lms.service.routing.ModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;



import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that when the legacy router profile is not active only the core
 * ModelRouter bean is present in the application context.  The adapter
 * bean "modelRouter" should be absent under the default profile.  This
 * test complements {@link ModelRouterBeanTest} which exercises the
 * legacy-router profile.
 */
@SpringBootTest
public class ModelRouterCoreOnlyTest {

    @Autowired(required = false)
    @Qualifier("modelRouterCore")
    private ModelRouter core;

    @Autowired(required = false)
    @Qualifier("modelRouter")
    private Object adapter;

    @Test
    public void onlyCoreBeanPresent() {
        assertNotNull(core, "core ModelRouter bean should be present by default");
        assertNull(adapter, "adapter ModelRouter bean should not be present when legacy-router profile is inactive");
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}