package com.example.lms.routing;

import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.routing.RouteSignal;
import com.example.lms.service.routing.RouteSignal.*;
import com.example.lms.telemetry.SseEventPublisher;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests exercising the routing heuristics implemented in
 * {@link ModelRouter}.  These tests instantiate the router directly
 * with dummy {@link ChatModel} instances to avoid Spring wiring and
 * external service dependencies.  They verify that the upgrade logic
 * behaves as expected across several combinations of signal inputs.
 */
public class ModelRouterRoutingTest {

    /**
     * Create a dummy ChatModel using a dynamic proxy.  All method
     * invocations return null.  Distinct proxies are used for the mini
     * and high models so that equality checks can discriminate
     * between them.
     */
    private static ChatModel newDummyChatModel() {
        return (ChatModel) Proxy.newProxyInstance(
                ChatModel.class.getClassLoader(),
                new Class[]{ChatModel.class},
                (proxy, method, args) -> null
        );
    }

    /**
     * A no-op SSE publisher used to satisfy the router constructor.
     */
    private static final SseEventPublisher NOOP_SSE = (type, payload) -> { /* ignore */ };

    @Test
    public void upgradeOnComplexityExceedsGamma() {
        ChatModel mini = newDummyChatModel();
        ChatModel high = newDummyChatModel();
        ModelRouter router = new ModelRouter(mini, high, NOOP_SSE);
        RouteSignal sig = new RouteSignal(
                1.0,   // complexity
                0.5,   // gamma
                0.0,   // uncertainty
                0.0,   // theta
                Intent.GENERAL,
                Verbosity.STANDARD,
                0,
                Preference.MINI,
                "test",
                false,
                false
        );
        assertSame(high, router.route(sig), "should upgrade when complexity > gamma");
    }

    @Test
    public void noUpgradeWhenSignalsBelowThreshold() {
        ChatModel mini = newDummyChatModel();
        ChatModel high = newDummyChatModel();
        ModelRouter router = new ModelRouter(mini, high, NOOP_SSE);
        RouteSignal sig = new RouteSignal(
                0.1,
                0.5,
                0.1,
                0.5,
                Intent.GENERAL,
                Verbosity.STANDARD,
                100,
                Preference.MINI,
                null,
                false,
                false
        );
        assertSame(mini, router.route(sig), "should not upgrade when all signals are below thresholds");
    }

    @Test
    public void upgradeWhenRiskHighViaOverloadedMethod() {
        ChatModel mini = newDummyChatModel();
        ChatModel high = newDummyChatModel();
        ModelRouter router = new ModelRouter(mini, high, NOOP_SSE);
        ChatModel chosen = router.route(null, "HIGH", "standard", 100);
        assertSame(high, chosen, "should upgrade when riskLevel is HIGH");
    }

    @Test
    public void upgradeWhenVerbosityDeep() {
        ChatModel mini = newDummyChatModel();
        ChatModel high = newDummyChatModel();
        ModelRouter router = new ModelRouter(mini, high, NOOP_SSE);
        // intent and risk null; deep verbosity should trigger upgrade
        ChatModel chosen = router.route(null, null, "deep", 100);
        assertSame(high, chosen, "should upgrade when verbosity is deep");
    }

    @Test
    public void upgradeWhenMaxTokensLarge() {
        ChatModel mini = newDummyChatModel();
        ChatModel high = newDummyChatModel();
        ModelRouter router = new ModelRouter(mini, high, NOOP_SSE);
        ChatModel chosen = router.route(null, null, "standard", 2000);
        assertSame(high, chosen, "should upgrade when maxTokens is ≥ 1536");
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}