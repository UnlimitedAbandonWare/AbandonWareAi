package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.orch.aop.ChatWorkflowFastBailoutMinHitsPostProcessor;
import ai.abandonware.nova.orch.aop.EvidenceListTraceInjectionAspect;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowFastBailoutMinHitsPostProcessorConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FastBailPostProcessorGateConfig.class);

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void postProcessorBeanHasExplicitEnableGate() throws Exception {
        Method method = NovaOrchestrationAutoConfiguration.class.getDeclaredMethod(
                "chatWorkflowFastBailoutMinHitsPostProcessor",
                Environment.class);

        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(condition);
        assertEquals("nova.orch.chat-workflow-fast-bail-postprocessor", condition.prefix());
        assertArrayEquals(new String[] { "enabled" }, condition.name());
        assertEquals("true", condition.havingValue());
        assertTrue(condition.matchIfMissing());
    }

    @Test
    void disabledPropertySkipsPostProcessorBean() {
        contextRunner
                .withPropertyValues("nova.orch.chat-workflow-fast-bail-postprocessor.enabled=false")
                .run(context -> assertFalse(context.containsBean("chatWorkflowFastBailoutMinHitsPostProcessor")));
    }

    @Test
    void missingPropertyKeepsPostProcessorBeanEnabled() {
        contextRunner.run(context -> assertTrue(context.containsBean("chatWorkflowFastBailoutMinHitsPostProcessor")));
    }

    @Test
    void evidenceListTraceInjectionDefaultsOnToMatchSnippetFallback() throws Exception {
        Method method = NovaOrchestrationAutoConfiguration.class.getDeclaredMethod(
                "evidenceListTraceInjectionAspect",
                Environment.class);

        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(condition);
        assertEquals("nova.orch.evidence-list.trace-injection", condition.prefix());
        assertArrayEquals(new String[] { "enabled" }, condition.name());
        assertEquals("true", condition.havingValue());
        assertTrue(condition.matchIfMissing());
    }

    @Test
    void postProcessorTraceFallbackLeavesNonRecursiveBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ChatWorkflowFastBailoutMinHitsPostProcessor.java"));

        assertFalse(source.contains("catch (Throwable ignore)"),
                "post-processor trace fallback should name suppressed failures");
        assertTrue(source.contains("traceSuppressed(\"emitTrace\", traceError)"));
        assertTrue(source.contains("[ChatWorkflowFastBailoutMinHitsPostProcessor] trace skipped stage={} errorType={}"));
    }

    @Test
    void invalidFastBailConfigLeavesRedactedSuppressedBreadcrumb() throws Exception {
        ChatWorkflowFastBailoutMinHitsPostProcessor postProcessor =
                new ChatWorkflowFastBailoutMinHitsPostProcessor(new MockEnvironment()
                        .withProperty("openai.retry.fast-bailout-min-timeout-hits-with-evidence",
                                "ownerToken=raw-fast-bail-secret"));
        Method method = ChatWorkflowFastBailoutMinHitsPostProcessor.class.getDeclaredMethod("emitTrace");
        method.setAccessible(true);

        method.invoke(postProcessor);

        assertEquals(Boolean.TRUE, TraceStore.get("chatWorkflow.fastBailPostProcessor.suppressed"));
        assertEquals("emitTrace", TraceStore.get("chatWorkflow.fastBailPostProcessor.suppressed.stage"));
        assertEquals("ConversionFailedException", TraceStore.get("chatWorkflow.fastBailPostProcessor.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=raw-fast-bail-secret"));
    }

    @Configuration(proxyBeanMethods = false)
    static class FastBailPostProcessorGateConfig {
        @Bean
        @ConditionalOnProperty(prefix = "nova.orch.chat-workflow-fast-bail-postprocessor", name = "enabled", havingValue = "true", matchIfMissing = true)
        ChatWorkflowFastBailoutMinHitsPostProcessor chatWorkflowFastBailoutMinHitsPostProcessor(Environment env) {
            return new ChatWorkflowFastBailoutMinHitsPostProcessor(env);
        }

        @Bean
        @ConditionalOnProperty(prefix = "nova.orch.evidence-list.trace-injection", name = "enabled", havingValue = "true", matchIfMissing = true)
        EvidenceListTraceInjectionAspect evidenceListTraceInjectionAspect(Environment env) {
            return new EvidenceListTraceInjectionAspect(env);
        }
    }
}
