package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatWorkflow;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UawIdleAutoTrainingPipelineAspectTest {

    @AfterEach
    void clear() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void uawIdleAutoTrainingPipelineAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "UAW idle auto-training needs fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("traceSuppressed(\"breaker.prefix\");"));
        assertTrue(source.contains("traceSuppressed(\"temperature.resolve\");"));
    }

    @Test
    void planTraceStoresCountsAndDomainProfileHashOnly() throws Throwable {
        String prefix = "uaw:";
        String rawQuery = "private-uaw-plan-step search query";
        String rawDomainProfile = "private-domain-profile";
        UawIdleAutoTrainingPipelineAspect aspect = new UawIdleAutoTrainingPipelineAspect(
                new MockEnvironment()
                        .withProperty("uaw.autolearn.strict.prefix", prefix)
                        .withProperty("uaw.autolearn.strict.domainProfile.general", rawDomainProfile),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()),
                null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatWorkflow workflow = mock(ChatWorkflow.class);
        when(pjp.getArgs()).thenReturn(new Object[]{prefix + rawQuery});
        when(pjp.getThis()).thenReturn(workflow);
        when(workflow.continueChat(any(ChatRequestDto.class))).thenReturn(ChatResult.of("ok", "model", true));

        Object result = aspect.aroundAsk(pjp);

        assertEquals(ChatResult.of("ok", "model", true), result);
        assertFalse(TraceStore.getAll().containsKey("uaw.pipeline.plan.searchQueries"),
                String.valueOf(TraceStore.getAll()));
        assertInstanceOf(Number.class, TraceStore.get("uaw.pipeline.plan.searchQueryCount"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(rawDomainProfile), trace);
        assertFalse(trace.contains("searchQueries=["), trace);
        assertTrue(String.valueOf(TraceStore.get("uaw.pipeline.plan.steps")).contains("searchQueryCount="));
        assertTrue(String.valueOf(TraceStore.get("uaw.pipeline.plan.steps")).contains("domainProfileHash="));
    }

    @Test
    void nonHardPipelineFailureDoesNotReplayChatWorkflow() throws Throwable {
        String prefix = "uaw:";
        UawIdleAutoTrainingPipelineAspect aspect = new UawIdleAutoTrainingPipelineAspect(
                new MockEnvironment().withProperty("uaw.autolearn.strict.prefix", prefix),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()),
                null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatWorkflow workflow = mock(ChatWorkflow.class, org.mockito.Answers.CALLS_REAL_METHODS);
        AtomicInteger workflowExecutions = new AtomicInteger();
        when(pjp.getArgs()).thenReturn(new Object[]{prefix + "bounded training query"});
        when(pjp.getThis()).thenReturn(workflow);
        doAnswer(invocation -> {
            if (workflowExecutions.incrementAndGet() == 1) {
                throw new IllegalStateException("pipeline failed after workflow execution started");
            }
            return ChatResult.of("replayed", "replayed", false);
        }).when(workflow).continueChat(any(ChatRequestDto.class));
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArgument(0);
            return workflow.ask((String) args[0]);
        });

        Object result = aspect.aroundAsk(pjp);

        assertEquals(1, workflowExecutions.get(),
                "a failed direct continueChat call must not replay ask and duplicate provider or persistence work");
        assertEquals(ChatResult.of("", "fallback:evidence:uaw-pipeline-failed", false), result);
    }
}
