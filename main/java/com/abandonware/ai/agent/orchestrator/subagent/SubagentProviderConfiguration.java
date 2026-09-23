package com.abandonware.ai.agent.orchestrator.subagent;

import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.TimedChatModelCaller;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** Production provider adapters for the explicit subagent flow. */
@Configuration(proxyBeanMethods = false)
public class SubagentProviderConfiguration {

    static final String GLM_PROVIDER_ID = "vercel-glm";
    static final String GLM_MODEL = "zai/glm-5.3-flash";
    static final String GLM_BASE_URL = "https://ai-gateway.vercel.sh/v1";
    static final String GLM_KEY_ENV = "AI_GATEWAY_API_KEY";
    private static final long RESPONSES_MIN_TIMEOUT_MS = 5_000L;

    static boolean glmDeadlineCanInvoke(long deadlineNanos, long nowNanos) {
        if (deadlineNanos <= nowNanos) {
            return false;
        }
        long remainingNanos = deadlineNanos - nowNanos;
        if (remainingNanos < 0L) {
            return true;
        }
        long remainingMs = remainingNanos / 1_000_000L;
        if (remainingNanos % 1_000_000L != 0L) {
            remainingMs++;
        }
        return glmTimeoutCanInvoke(remainingMs);
    }

    private static boolean glmTimeoutCanInvoke(long timeoutMs) {
        return timeoutMs >= RESPONSES_MIN_TIMEOUT_MS;
    }

    @Bean
    SubagentProviderChain subagentProviderChain(List<SubagentProvider> providers,
                                                ObjectProvider<LlmGatewayFailureClassifier> classifierProvider,
                                                Environment environment) {
        long cooldownMs = positiveLong(environment,
                "agent.subagent.provider-cooldown-ms", 60_000L);
        LlmGatewayFailureClassifier classifier = classifierProvider == null
                ? new LlmGatewayFailureClassifier()
                : classifierProvider.getIfAvailable(LlmGatewayFailureClassifier::new);
        return new SubagentProviderChain(providers, classifier, cooldownMs, System::nanoTime);
    }

    @Bean
    @ConditionalOnMissingBean(SubagentAgentMetrics.class)
    SubagentAgentMetrics subagentAgentMetrics() {
        return new SubagentAgentMetrics();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(ModelRuntimeHealthTracker.class)
    ModelRuntimeHealthTracker modelRuntimeHealthTracker() {
        return new ModelRuntimeHealthTracker();
    }

    @Bean
    @ConditionalOnMissingBean(GlmActivationStateMachine.class)
    GlmActivationStateMachine glmActivationStateMachine(Environment environment,
                                                         SubagentAgentMetrics metrics) {
        return new GlmActivationStateMachine(
                () -> enabled(environment, "agent.subagent.glm.enabled", false),
                () -> enabled(environment, "GLM_EXTERNAL_READY", false),
                () -> !ConfigValueGuards.isMissing(System.getenv(GLM_KEY_ENV)),
                metrics);
    }

    @Bean
    @ConditionalOnMissingBean(GlmAgentCore.class)
    GlmAgentCore glmAgentCore(SubagentProviderChain providerChain,
                              SubagentAgentMetrics metrics,
                              ModelRuntimeHealthTracker healthTracker,
                              GlmActivationStateMachine activation) {
        return new GlmAgentCore(providerChain, metrics, healthTracker, activation);
    }

    @Bean("glmSubagentProvider")
    @ConditionalOnMissingBean(name = "glmSubagentProvider")
    SubagentProvider glmSubagentProvider(Environment environment,
                                         ModelRuntimeHealthTracker healthTracker,
                                         GlmActivationStateMachine activation) {
        return glmProvider(environment,
                () -> System.getenv(GLM_KEY_ENV),
                (apiKey, task, timeoutMs) -> invokeGlmResponses(
                        environment, apiKey, task, timeoutMs, healthTracker),
                activation);
    }

    @Bean("openAiSubagentProvider")
    @ConditionalOnMissingBean(name = "openAiSubagentProvider")
    SubagentProvider openAiSubagentProvider(Environment environment,
                                            ObjectProvider<DynamicChatModelFactory> modelFactoryProvider) {
        return factoryProvider(
                "openai",
                20,
                () -> {
                    if (!enabled(environment, "llmrouter.models.openai-balanced.enabled", false)) {
                        return SubagentProvider.Availability.disabled("route_disabled");
                    }
                    String model = property(environment,
                            "llmrouter.models.openai-balanced.name", "gpt-5.4-mini");
                    DynamicChatModelFactory modelFactory = modelFactoryProvider == null
                            ? null : modelFactoryProvider.getIfAvailable();
                    return modelFactory != null && modelFactory.canServeQuietly(model)
                            ? SubagentProvider.Availability.enabled()
                            : SubagentProvider.Availability.disabled("provider_unavailable");
                },
                (task, timeoutMs) -> {
                    DynamicChatModelFactory modelFactory = modelFactoryProvider == null
                            ? null : modelFactoryProvider.getIfAvailable();
                    if (modelFactory == null) {
                        throw new LlmGatewayException("openai_factory_unavailable", LlmFailureClass.DISABLED);
                    }
                    return invokeFactoryModel(
                            modelFactory,
                            property(environment, "llmrouter.models.openai-balanced.name", "gpt-5.4-mini"),
                            task,
                            timeoutMs);
                });
    }

    @Bean("ollamaSubagentProvider")
    @ConditionalOnMissingBean(name = "ollamaSubagentProvider")
    SubagentProvider ollamaSubagentProvider(Environment environment,
                                            ObjectProvider<DynamicChatModelFactory> modelFactoryProvider) {
        return factoryProvider(
                "ollama",
                30,
                () -> {
                    if (!enabled(environment, "llmrouter.models.light.enabled", true)) {
                        return SubagentProvider.Availability.disabled("route_disabled");
                    }
                    String model = property(environment, "llm.fast.model", "qwen3.5:9b");
                    DynamicChatModelFactory modelFactory = modelFactoryProvider == null
                            ? null : modelFactoryProvider.getIfAvailable();
                    return modelFactory != null && modelFactory.canServeQuietly(model)
                            ? SubagentProvider.Availability.enabled()
                            : SubagentProvider.Availability.disabled("provider_unavailable");
                },
                (task, timeoutMs) -> {
                    DynamicChatModelFactory modelFactory = modelFactoryProvider == null
                            ? null : modelFactoryProvider.getIfAvailable();
                    if (modelFactory == null) {
                        throw new LlmGatewayException("ollama_factory_unavailable", LlmFailureClass.DISABLED);
                    }
                    return invokeFactoryModel(
                            modelFactory,
                            property(environment, "llm.fast.model", "qwen3.5:9b"),
                            task,
                            timeoutMs);
                });
    }

    static SubagentProvider glmProvider(Environment environment,
                                        Supplier<String> keySupplier,
                                        GlmInvoker invoker) {
        Objects.requireNonNull(keySupplier, "keySupplier");
        Objects.requireNonNull(invoker, "invoker");
        return new ConfiguredProvider(
                GLM_PROVIDER_ID,
                10,
                true,
                () -> {
                    if (!enabled(environment, "agent.subagent.glm.enabled", false)) {
                        return SubagentProvider.Availability.disabled("blocked_external");
                    }
                    String apiKey = keySupplier.get();
                    return ConfigValueGuards.isMissing(apiKey)
                            ? SubagentProvider.Availability.disabled("missing_ai_gateway_api_key")
                            : SubagentProvider.Availability.enabled();
                },
                (task, timeoutMs) -> {
                    if (!glmTimeoutCanInvoke(timeoutMs)) {
                        throw new LlmGatewayException("glm_deadline_too_short", LlmFailureClass.TIMEOUT_SOFT);
                    }
                    String apiKey = keySupplier.get();
                    if (ConfigValueGuards.isMissing(apiKey)) {
                        throw new LlmGatewayException("glm_key_unavailable", LlmFailureClass.AUTH_MISSING);
                    }
                    String output = invoker.complete(apiKey.trim(), task, timeoutMs);
                    throwIfExpectedFailure(output);
                    return output;
                });
    }

    static SubagentProvider glmProvider(Environment environment,
                                        Supplier<String> keySupplier,
                                        GlmInvoker invoker,
                                        GlmActivationStateMachine activation) {
        Objects.requireNonNull(keySupplier, "keySupplier");
        Objects.requireNonNull(invoker, "invoker");
        Objects.requireNonNull(activation, "activation");
        return new ConfiguredProvider(
                GLM_PROVIDER_ID,
                10,
                true,
                () -> {
                    if (!enabled(environment, "agent.subagent.glm.enabled", false)) {
                        return SubagentProvider.Availability.disabled("blocked_external");
                    }
                    activation.refresh(activation.snapshot().circuitState());
                    if (!activation.glmProviderAttemptAllowed()) {
                        return SubagentProvider.Availability.disabled(
                                activation.providerAvailabilityReason());
                    }
                    String apiKey = keySupplier.get();
                    return ConfigValueGuards.isMissing(apiKey)
                            ? SubagentProvider.Availability.disabled("missing_ai_gateway_api_key")
                            : SubagentProvider.Availability.enabled();
                },
                (task, timeoutMs) -> {
                    if (!activation.glmProviderAttemptAllowed()) {
                        throw new LlmGatewayException(
                                "glm_activation_not_allowed",
                                LlmFailureClass.DISABLED,
                                "activation_not_allowed");
                    }
                    if (!glmTimeoutCanInvoke(timeoutMs)) {
                        throw new LlmGatewayException(
                                "glm_deadline_too_short",
                                LlmFailureClass.TIMEOUT_SOFT,
                                "glm_deadline_too_short");
                    }
                    String apiKey = keySupplier.get();
                    if (ConfigValueGuards.isMissing(apiKey)) {
                        throw new LlmGatewayException(
                                "glm_key_unavailable",
                                LlmFailureClass.AUTH_MISSING,
                                "missing_ai_gateway_api_key");
                    }
                    String output = invoker.complete(apiKey.trim(), task, timeoutMs);
                    throwIfExpectedFailure(output);
                    return output;
                });
    }

    private static String invokeGlmResponses(Environment environment,
                                             String apiKey,
                                             SubagentTask task,
                                             long timeoutMs,
                                             ModelRuntimeHealthTracker healthTracker) {
        GlmModelSelection selection = glmModelSelection(environment, task);
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                GLM_BASE_URL,
                apiKey,
                selection.model(),
                timeoutMs,
                healthTracker,
                "primary",
                healthTracker.redactedRequestAttemptRoute(
                        "subagent_glm", selection.model(), GLM_BASE_URL + "/responses", "openai_responses"),
                selection.reasoningEffort());
        ChatResponse response = model.chat(List.of(UserMessage.from(task.prompt())));
        return response == null || response.aiMessage() == null
                ? ""
                : response.aiMessage().text();
    }

    static GlmModelSelection glmModelSelection(Environment environment, SubagentTask task) {
        boolean review = task != null && List.of("review", "counterexample_search", "falsify", "neutral", "synthesis")
                .contains(task.role().toLowerCase(Locale.ROOT));
        String model;
        String effort;
        try {
            model = property(environment, "agent.subagent.glm.model", GLM_MODEL);
            effort = property(environment, "agent.subagent.glm.reasoning-effort", "medium");
            if (review) {
                model = property(environment, "agent.subagent.glm.review-model", model);
                effort = property(environment, "agent.subagent.glm.review-reasoning-effort", "high");
            }
        } catch (IllegalArgumentException unresolvedProperty) {
            throw new LlmGatewayException("glm_model_configuration_invalid", LlmFailureClass.DISABLED,
                    "glm_model_configuration_invalid");
        }
        // Model configuration never changes the Vercel endpoint, credential or billing boundary.
        if (!model.matches("zai/glm-[a-z0-9][a-z0-9.-]{0,63}")
                || !List.of("low", "medium", "high").contains(effort)) {
            throw new LlmGatewayException("glm_model_configuration_invalid", LlmFailureClass.DISABLED,
                    "glm_model_configuration_invalid");
        }
        return new GlmModelSelection(model, effort);
    }

    record GlmModelSelection(String model, String reasoningEffort) { }

    private static String invokeFactoryModel(DynamicChatModelFactory modelFactory,
                                             String model,
                                             SubagentTask task,
                                             long timeoutMs) {
        int timeoutSeconds = timeoutSeconds(timeoutMs);
        ChatModel chatModel = modelFactory.lcWithTimeout(
                model,
                0.0d,
                null,
                null,
                null,
                512,
                timeoutSeconds,
                0);
        ChatResponse response = chatModel.chat(List.of(UserMessage.from(task.prompt())));
        String output = response == null || response.aiMessage() == null
                ? ""
                : response.aiMessage().text();
        throwIfExpectedFailure(output);
        return output;
    }

    private static void throwIfExpectedFailure(String output) {
        if (!TimedChatModelCaller.isExpectedFailureResponse(output)) {
            return;
        }
        String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        LlmFailureClass failureClass;
        if (lower.contains("httpstatus: 401") || lower.contains("httpstatus: 403")) {
            failureClass = LlmFailureClass.AUTH_MISSING;
        } else if (lower.contains("httpstatus: 404")) {
            failureClass = LlmFailureClass.MODEL_MISSING;
        } else if (lower.contains("httpstatus: 429")) {
            failureClass = LlmFailureClass.RATE_LIMIT_COOLDOWN;
        } else if (lower.contains("timeout")) {
            failureClass = LlmFailureClass.TIMEOUT_SOFT;
        } else {
            failureClass = LlmFailureClass.PROVIDER_ERROR;
        }
        throw new LlmGatewayException("subagent_provider_expected_failure", failureClass);
    }

    private static SubagentProvider factoryProvider(String id,
                                                     int order,
                                                     Supplier<SubagentProvider.Availability> availability,
                                                     ProviderInvoker invoker) {
        return new ConfiguredProvider(id, order, false, availability, invoker);
    }

    private static int timeoutSeconds(long timeoutMs) {
        long seconds = Math.max(1L, (Math.max(1L, timeoutMs) + 999L) / 1_000L);
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    private static boolean enabled(Environment environment, String key, boolean fallback) {
        if (environment == null) {
            return fallback;
        }
        Boolean value = environment.getProperty(key, Boolean.class);
        return value == null ? fallback : value;
    }

    private static long positiveLong(Environment environment, String key, long fallback) {
        if (environment == null) {
            return fallback;
        }
        Long value = environment.getProperty(key, Long.class);
        return value == null || value <= 0L ? fallback : value;
    }

    private static String property(Environment environment, String key, String fallback) {
        if (environment == null) {
            return fallback;
        }
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @FunctionalInterface
    interface GlmInvoker {
        String complete(String apiKey, SubagentTask task, long timeoutMs) throws Exception;
    }

    @FunctionalInterface
    private interface ProviderInvoker {
        String complete(SubagentTask task, long timeoutMs) throws Exception;
    }

    private static final class ConfiguredProvider implements SubagentProvider {
        private final String id;
        private final int order;
        private final boolean singleAttemptPerFlow;
        private final Supplier<Availability> availability;
        private final ProviderInvoker invoker;

        private ConfiguredProvider(String id,
                                   int order,
                                   boolean singleAttemptPerFlow,
                                   Supplier<Availability> availability,
                                   ProviderInvoker invoker) {
            this.id = id;
            this.order = order;
            this.singleAttemptPerFlow = singleAttemptPerFlow;
            this.availability = availability;
            this.invoker = invoker;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public boolean singleAttemptPerFlow() {
            return singleAttemptPerFlow;
        }

        @Override
        public boolean supportsSubscriptionOnly() {
            return "ollama".equals(id);
        }

        @Override
        public Availability availability() {
            return availability.get();
        }

        @Override
        public String execute(SubagentTask task, long timeoutMs) throws Exception {
            return invoker.complete(task, timeoutMs);
        }
    }
}
