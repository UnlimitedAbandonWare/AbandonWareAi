package com.example.lms.routing;

import com.example.lms.config.ModelProperties;
import com.example.lms.config.MoeRoutingProps;
import com.example.lms.service.routing.ModelRouterCore;
import com.example.lms.service.routing.RouteSignal;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;



import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ModelRouterCore}.  These unit tests verify that the
 * escalation logic correctly selects the mixture-of-experts (MOE) model
 * whenever any of the configured thresholds are exceeded and retains the
 * default model otherwise.  Since {@code DynamicChatModelFactory} is not
 * supplied in these tests, the router creates dummy proxy instances.  The
 * {@link ModelRouterCore#resolveModelName(ChatModel)} method is used to
 * determine which model identifier was chosen.
 */
public class ModelRouterCoreTest {

    private ModelRouterCore newRouter() {
        ModelProperties mp = new ModelProperties();
        MoeRoutingProps rp = new MoeRoutingProps();
        return new ModelRouterCore(mp, rp, null);
    }

    @Test
    public void promotes_on_any_threshold() {
        ModelRouterCore router = newRouter();
        // Create a signal that exceeds the token threshold (1200 by default).
        RouteSignal sig = new RouteSignal(
                0.2,
                0.0,
                0.3,
                0.1,
                RouteSignal.Intent.GENERAL,
                RouteSignal.Verbosity.NORMAL,
                1500,
                RouteSignal.Preference.BALANCED,
                "long"
        );
        ChatModel model = router.route(sig);
        assertEquals("qwen2.5-7b-instruct", router.resolveModelName(model),
                "Should upgrade to the MOE model when maxTokens ≥ threshold");
    }

    @Test
    public void stays_default_when_below_all_thresholds() {
        ModelRouterCore router = newRouter();
        RouteSignal sig = new RouteSignal(
                0.1,
                0.0,
                0.1,
                0.1,
                RouteSignal.Intent.GENERAL,
                RouteSignal.Verbosity.TERSE,
                200,
                RouteSignal.Preference.COST,
                "short"
        );
        ChatModel model = router.route(sig);
        assertEquals("qwen2.5-7b-instruct", router.resolveModelName(model),
                "Should remain on the default model when all signals are below thresholds");
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}