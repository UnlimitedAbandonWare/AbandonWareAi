package com.example.lms.service.routing;

import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.search.TraceStore;
import com.github.benmanes.caffeine.cache.Cache;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** The supported configuration revision is context recreation, not live refresh. */
class ConfigRevisionInvalidatesOldRequestedClientTest {
    enum ChangedInput { ENDPOINT, CREDENTIAL, MODEL_CONFIGURATION }

    @AfterEach void clearTrace() { TraceStore.clear(); }

    @ParameterizedTest
    @EnumSource(ChangedInput.class)
    void restartedContextDoesNotReuseClientFromEarlierConfiguration(ChangedInput changed) {
        PolicyBasedModelRouter oldRouter;
        Cache<?, ?> oldCache;
        ChatModel oldClient;
        List<Snapshot> oldBuilds;
        try (AnnotationConfigApplicationContext first = context(ChangedInput.ENDPOINT, false)) {
            oldRouter = first.getBean(PolicyBasedModelRouter.class);
            oldCache = cache(oldRouter);
            oldClient = request(oldRouter);
            assertThat(request(oldRouter)).isSameAs(oldClient);
            oldBuilds = first.getBean(RecordingFactory.class).builds;
            assertThat(oldBuilds).containsExactly(new Snapshot(
                    "https://first.invalid/v1", "openai-context-first-value", "synthetic-default-first"));
            assertThat(oldCache.estimatedSize()).isEqualTo(1);
        }

        try (AnnotationConfigApplicationContext second = context(changed, true)) {
            PolicyBasedModelRouter newRouter = second.getBean(PolicyBasedModelRouter.class);
            Cache<?, ?> newCache = cache(newRouter);
            assertThat(newRouter).isNotSameAs(oldRouter);
            assertThat(newCache).isNotSameAs(oldCache);
            assertThat(newCache.estimatedSize()).isZero();
            ChatModel newClient = request(newRouter);
            assertThat(newClient).isNotSameAs(oldClient);
            assertThat(request(newRouter)).isSameAs(newClient);
            assertThat(second.getBean(RecordingFactory.class).builds).containsExactly(new Snapshot(
                    changed == ChangedInput.ENDPOINT ? "https://second.invalid/v1" : "https://first.invalid/v1",
                    changed == ChangedInput.CREDENTIAL ? "openai-context-second-value" : "openai-context-first-value",
                    changed == ChangedInput.MODEL_CONFIGURATION ? "synthetic-default-second" : "synthetic-default-first"));
            assertThat(oldBuilds).hasSize(1);
            assertThat(newCache.asMap().keySet().toString())
                    .doesNotContain("openai-context-first-value", "openai-context-second-value");
            verifyNoInteractions(oldClient, newClient);
        }
    }

    private static AnnotationConfigApplicationContext context(ChangedInput changed, boolean second) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.base-url-openai",
                        second && changed == ChangedInput.ENDPOINT ? "https://second.invalid/v1" : "https://first.invalid/v1")
                .withProperty("llm.api-key-openai",
                        second && changed == ChangedInput.CREDENTIAL ? "openai-context-second-value" : "openai-context-first-value")
                .withProperty("llm.chat-model",
                        second && changed == ChangedInput.MODEL_CONFIGURATION ? "synthetic-default-second" : "synthetic-default-first")
                .withProperty("llm.fast.timeout-seconds", "3")
                .withProperty("llm.requested-model.timeout-seconds", "3");
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // MockEnvironment has no process environment property source: synthetic inputs only.
        context.setEnvironment(environment);
        context.registerBean(RecordingFactory.class, () -> new RecordingFactory(environment));
        context.registerBean(PolicyBasedModelRouter.class, () -> new PolicyBasedModelRouter(
                mock(ChatModel.class), null, null, null, context.getBean(RecordingFactory.class)));
        context.refresh();
        return context;
    }

    private static ChatModel request(PolicyBasedModelRouter router) {
        try { return router.route("qa", "low", "brief", 128, "synthetic-requested-chat"); }
        finally { TraceStore.clear(); }
    }

    private static Cache<?, ?> cache(PolicyBasedModelRouter router) {
        return (Cache<?, ?>) ReflectionTestUtils.getField(router, "requestedCache");
    }

    private record Snapshot(String endpoint, String syntheticCredential, String configuredModel) { }

    static final class RecordingFactory extends DynamicChatModelFactory {
        final KeyResolver syntheticResolver;
        final List<Snapshot> builds = new ArrayList<>();
        RecordingFactory(MockEnvironment environment) {
            super(environment, new KeyResolver(environment));
            syntheticResolver = new KeyResolver(environment);
        }
        @Override public boolean canServe(String model) { return true; }
        @Override public ChatModel lcWithTimeout(String model, Double temperature, Double topP,
                                                Integer maxTokens, int timeoutSeconds) {
            // Record real inherited @Value binding and central credential resolution,
            // replacing only client construction so no provider or network call occurs.
            builds.add(new Snapshot(
                    (String) ReflectionTestUtils.getField(this, "openAiBaseUrl"),
                    syntheticResolver.resolveOpenAiApiKeyStrict(),
                    (String) ReflectionTestUtils.getField(this, "defaultModelName")));
            return mock(ChatModel.class);
        }
    }
}
