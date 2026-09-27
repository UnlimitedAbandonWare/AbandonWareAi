package com.example.lms.routing;

import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.DynamicChatModelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring contract for {@link AgentApiSpendGuard} at the dynamic model seam.
 * No network: a blocked decision throws before credential resolution, and the
 * local path is never intercepted by the spend gate.
 */
class AgentApiSpendGuardWiringTest {

    private static final String AGENT_PROP = "awx.agent.spend-guard";

    @AfterEach
    void clearAgentMode() {
        System.clearProperty(AGENT_PROP);
    }

    @Test
    void agentModeBlocksStalePaidModelWithWhyCode() {
        System.setProperty(AGENT_PROP, "true");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory().lcWithTimeout("gpt-4o", null, null, null, null, 32, 1));
        assertTrue(String.valueOf(failure.getMessage()).contains("agent spend guard"));
    }

    @Test
    void guardAllowsLocalProviderInAgentMode() {
        System.setProperty(AGENT_PROP, "true");
        AgentApiSpendGuard.Decision decision = AgentApiSpendGuard.beforeCall(
                "llm_factory_build", "ollama", "qwen3:8b", "test",
                "probe-local-" + System.nanoTime(), false);
        assertTrue(decision.allow());
    }

    @Test
    void agentModeDoesNotSpendBlockLocalModel() {
        System.setProperty(AGENT_PROP, "true");
        try {
            assertNotNull(factory().lcWithTimeout("qwen3:8b", null, null, null, null, 32, 1));
        } catch (Exception e) {
            // Construction/health failures are acceptable here; the spend gate must not fire.
            assertFalse(String.valueOf(e.getMessage()).contains("agent spend guard"));
        }
    }

    @Test
    void userModeIsNeverSpendBlocked() {
        try {
            factory().lcWithTimeout("gpt-4o", null, null, null, null, 32, 1);
        } catch (Exception e) {
            assertFalse(String.valueOf(e.getMessage()).contains("agent spend guard"));
        }
    }

    @Test
    void successfulVerificationReplayIsSkipped() {
        System.setProperty(AGENT_PROP, "true");
        String probe = "wiring-" + System.nanoTime();
        AgentApiSpendGuard.afterSuccess("verify", "openai", "fixture-model", "test", probe, 1, 1, "llm_paid");
        AgentApiSpendGuard.Decision decision = AgentApiSpendGuard.beforeCall(
                "verify", "openai", "fixture-model", "test", probe, false);
        assertFalse(decision.allow());
        assertEquals("verification_replay_blocked", decision.why());
    }

    private static DynamicChatModelFactory factory() {
        MockEnvironment env = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(env, new KeyResolver(env));
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "openAiBaseUrl", "https://api.openai.com/v1");
        return factory;
    }
}
