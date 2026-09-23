package ai.abandonware.nova.boot;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaPropertyAliasEnvironmentPostProcessorTest {

    @Test
    void nonFatalAliasCatchesUseRedactedBreadcrumbHelper() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/NovaPropertyAliasEnvironmentPostProcessor.java"))
                .replace("\r\n", "\n");

        assertTrue(source.contains("traceSuppressed(\"propertySources.firstNonBlank\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"forceBlankIfPresent.getProperty\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"firstNonBlankFromEnv.getProperty\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"warnIfConflicting.getProperty\", ignore);"));
        assertFalse(source.contains("error.getMessage()"),
                "Alias suppression breadcrumbs must not log raw exception messages");
    }

    @Test
    void approvedBraveMainOverridesStaleAliases() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("BRAVE_API_KEY", "approved-brave-main")
                .withProperty("BRAVE_SUBSCRIPTION_TOKEN", "retired-brave-token")
                .withProperty("gpt-search.brave.api-key", "stale-file-value");
        new NovaPropertyAliasEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertEquals("approved-brave-main", env.getProperty("gpt-search.brave.api-key"));
        assertEquals("approved-brave-main", env.getProperty("BRAVE_API_KEY"));
        assertEquals("retired-brave-token", env.getProperty("BRAVE_SUBSCRIPTION_TOKEN"));
        assertEquals("false", env.getProperty("nova.provider.brave.key.multi"));
        assertEquals("1", env.getProperty("nova.provider.brave.key.present.count"));
    }

    @Test
    void retiredTokenCannotResurrectMissingOrBlankMain() {
        for (boolean blankMain : new boolean[]{false, true}) {
            MockEnvironment env = new MockEnvironment()
                    .withProperty("BRAVE_SUBSCRIPTION_TOKEN", "retired-brave-token")
                    .withProperty("gpt-search.brave.subscription-token", "stale-file-value");
            if (blankMain) env.withProperty("BRAVE_API_KEY", "")
                    .withProperty("gpt-search.brave.api-key", "stale-main-value");
            new NovaPropertyAliasEnvironmentPostProcessor().postProcessEnvironment(env, null);
            assertEquals("retired-brave-token", env.getProperty("BRAVE_SUBSCRIPTION_TOKEN"));
            assertEquals("", env.getProperty("gpt-search.brave.api-key"));
            assertEquals("0", env.getProperty("nova.provider.brave.key.present.count"));
        }
    }

    @Test
    void canonicalPropertySupportsIsolatedVerification() {
        MockEnvironment env = new MockEnvironment().withProperty("gpt-search.brave.api-key", "fixture-main-key");
        new NovaPropertyAliasEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertEquals("fixture-main-key", env.getProperty("BRAVE_API_KEY"));
    }

    @Test
    void macMiniGpuGatewayBindsGuardedRuntimeRoutesToDesktopGpuEndpoints() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "false")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1")
                .withProperty("awx.gpu-gateway.fast-base-url", "http://desktop-gpu.internal:11436/v1")
                .withProperty("awx.gpu-gateway.embedding-base-url", "http://desktop-gpu.internal:11436/api/embed")
                .withProperty("awx.gpu-gateway.allowed-hosts", "desktop-gpu.internal:11435,desktop-gpu.internal:11436")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "true")
                .withProperty("llm.owner-token", "owner-proxy-secret-value")
                .withProperty("llm.chat-model", "gemma4:26b")
                .withProperty("llm.fast.model", "qwen3:8b");

        new NovaPropertyAliasEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://desktop-gpu.internal:11435/v1", env.getProperty("llm.base-url"));
        assertEquals("http://desktop-gpu.internal:11435/v1", env.getProperty("llm.high.base-url"));
        assertEquals("http://desktop-gpu.internal:11436/v1", env.getProperty("llm.fast.base-url"));
        assertEquals("http://desktop-gpu.internal:11436/api/embed", env.getProperty("embedding.base-url"));
        assertEquals("true", env.getProperty("llm.provider-guard.allow-private-remote"));
        assertEquals("desktop-gpu.internal:11435,desktop-gpu.internal:11436",
                env.getProperty("llm.provider-guard.allowed-hosts"));
        assertEquals("true", env.getProperty("llm.provider-guard.require-auth-for-remote"));
        assertEquals("true", env.getProperty("llmrouter.models.gemma.enabled"));
        assertEquals("gemma4:26b", env.getProperty("llmrouter.models.gemma.name"));
        assertEquals("http://desktop-gpu.internal:11435/v1", env.getProperty("llmrouter.models.gemma.base-url"));
        assertEquals("true", env.getProperty("llmrouter.models.light.enabled"));
        assertEquals("qwen3:8b", env.getProperty("llmrouter.models.light.name"));
        assertEquals("http://desktop-gpu.internal:11436/v1", env.getProperty("llmrouter.models.light.base-url"));
        assertNull(env.getProperty("llmrouter.models.gemma.owner-token"));
    }

    @Test
    void disabledOrHeavyNodeDoesNotOverrideRuntimeRoutes() {
        MockEnvironment disabled = new MockEnvironment()
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "false")
                .withProperty("awx.gpu-gateway.enabled", "false")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1");
        new NovaPropertyAliasEnvironmentPostProcessor().postProcessEnvironment(disabled, null);
        assertNull(disabled.getProperty("llm.base-url"));
        assertNull(disabled.getProperty("llm.provider-guard.allow-private-remote"));

        MockEnvironment desktop = new MockEnvironment()
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "true")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1");
        new NovaPropertyAliasEnvironmentPostProcessor().postProcessEnvironment(desktop, null);
        assertNull(desktop.getProperty("llm.base-url"));
        assertNull(desktop.getProperty("llm.provider-guard.allow-private-remote"));
    }

    @Test
    void promptPoseLegacyAliasesMapOnlyWhenCanonicalIsAbsent() {
        MockEnvironment legacy = new MockEnvironment()
                .withProperty("NOVA_ORCH_PROMPT_POSER_ENABLED", "true")
                .withProperty("nova.orch.prompt-poser.max-queryburst-count", "7")
                .withProperty("nova.orch.prompt-poser.max-selfask-count", "2");
        new NovaPropertyAliasEnvironmentPostProcessor().postProcessEnvironment(legacy, null);
        assertEquals("true", legacy.getProperty("prompt-pose.enabled"));
        assertEquals("7", legacy.getProperty("prompt-pose.policy.max-queryburst-count"));
        assertEquals("2", legacy.getProperty("prompt-pose.policy.max-selfask-count"));

        MockEnvironment canonical = new MockEnvironment()
                .withProperty("prompt-pose.enabled", "false")
                .withProperty("NOVA_ORCH_PROMPT_POSER_ENABLED", "true");
        new NovaPropertyAliasEnvironmentPostProcessor().postProcessEnvironment(canonical, null);
        assertEquals("false", canonical.getProperty("prompt-pose.enabled"));
    }
}
