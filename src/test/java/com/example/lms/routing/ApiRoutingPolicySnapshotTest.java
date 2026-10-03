package com.example.lms.routing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class ApiRoutingPolicySnapshotTest {
    @Test void separateResourcesKeepTierOrderAndProductionPolicy() {
        var policy = new ApiRoutingPolicySnapshot(new MockEnvironment());
        assertThat(policy.routes("search").stream().map(ApiRoutingPolicySnapshot.Route::id))
                .containsExactly("brave", "naver", "tavily", "serpapi");
        assertThat(policy.productionHardCaps()).isFalse();
        assertThat(policy.agentModeActive()).isFalse();
        assertThat(policy.tier("llm", "not-in-yaml")).isEqualTo(Integer.MAX_VALUE);
        assertThat(policy.chatModelAllowed("qwen3-embedding:4b")).isFalse();
        assertThat(policy.chatModelAllowed("gemma4:26b")).isTrue();
        assertThat(policy.canonicalModel("qwen3:8b")).isEqualTo("qwen3.5:9b");
    }
    @Test void profileAndEnvironmentActivationAreEquivalent() {
        MockEnvironment profile = new MockEnvironment();
        profile.setActiveProfiles("agent-spend-guard");
        assertThat(new ApiRoutingPolicySnapshot(profile).agentModeActive()).isTrue();
        assertThat(new ApiRoutingPolicySnapshot(new MockEnvironment().withProperty("AWX_AGENT_SPEND_GUARD", "true"))
                .agentModeActive()).isTrue();
        assertThat(new ApiRoutingPolicySnapshot(new MockEnvironment().withProperty("AWX_AGENT_HOST", "fixture"))
                .agentModeActive()).isTrue();
    }
    @Test void paidGuardIsAgentOnlyAndVisionDoesNotEnterChatPool() {
        var env = new MockEnvironment();
        var production = new ApiRoutingPolicySnapshot(env);
        assertThat(production.automaticModelAllowed("openai", "gpt-4o", false)).isTrue();
        assertThat(production.automaticModelAllowed("local", "qwen3-vl:8b", true, "vision")).isTrue();
        assertThat(production.automaticModelAllowed("local", "qwen3-vl:8b", true, "chat")).isFalse();
        env.setActiveProfiles("agent-spend-guard");
        assertThat(production.automaticModelAllowed("openai", "gpt-4o", false)).isFalse();
        assertThat(AgentApiSpendGuard.beforeCall(production, "llm_factory_build",
                "openai", "fixture-current-model", "test", "bounded", false).allow()).isFalse();
        assertThat(AgentApiSpendGuard.beforeCall(production, "llm_factory_build",
                "openai", "fixture-current-model", "test", "bounded", true).allow()).isTrue();
    }
}
