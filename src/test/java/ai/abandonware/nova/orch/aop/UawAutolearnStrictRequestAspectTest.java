package ai.abandonware.nova.orch.aop;

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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UawAutolearnStrictRequestAspectTest {

    @AfterEach
    void clear() {
        GuardContextHolder.clear();
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void optionalTemperatureParseCatchUsesSuppressionBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"temperature.parse\");"));
    }

    @Test
    void failClosedStrictRequestRecordsBlockedReasonBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"uaw.strictRequest.blocked\""),
                "strict UAW fail-closed paths should expose a stable blocked reason trace key");
    }

    @Test
    void nonHardStrictFailureDoesNotReplayChatWorkflow() throws Throwable {
        String prefix = "uaw:";
        UawAutolearnStrictRequestAspect aspect = new UawAutolearnStrictRequestAspect(
                new MockEnvironment().withProperty("uaw.autolearn.strict.prefix", prefix),
                null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatWorkflow workflow = mock(ChatWorkflow.class, org.mockito.Answers.CALLS_REAL_METHODS);
        AtomicInteger workflowExecutions = new AtomicInteger();
        when(pjp.getArgs()).thenReturn(new Object[]{prefix + "bounded legacy training query"});
        when(pjp.getThis()).thenReturn(workflow);
        doAnswer(invocation -> {
            if (workflowExecutions.incrementAndGet() == 1) {
                throw new IllegalStateException("strict pipeline failed after workflow execution started");
            }
            return ChatResult.of("replayed", "replayed", false);
        }).when(workflow).continueChat(any(ChatRequestDto.class));
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArgument(0);
            return workflow.ask((String) args[0]);
        });

        Object result = aspect.aroundAsk(pjp);

        assertEquals(1, workflowExecutions.get(),
                "a failed direct strict continueChat call must not replay ask and duplicate provider or persistence work");
        assertEquals(ChatResult.of("", "fallback:evidence:uaw-pipeline-failed", false), result);
        assertEquals(true, TraceStore.get("uaw.strict.failClosed"));
        assertEquals("pipeline-failed", TraceStore.get("uaw.strict.failClosed.reason"));
    }

    @Test
    void cancellationAfterStrictWorkflowStartPropagatesWithoutReplay() throws Throwable {
        CancellationException failure = new CancellationException("strict workflow cancelled");
        StrictFailureFixture fixture = strictFailureFixture(failure);

        CancellationException thrown = assertThrows(CancellationException.class,
                () -> fixture.aspect().aroundAsk(fixture.pjp()));

        assertSame(failure, thrown);
        verify(fixture.pjp(), never()).proceed(any(Object[].class));
    }

    @Test
    void interruptRootedFailureRestoresInterruptAndPropagatesWithoutReplay() throws Throwable {
        RuntimeException failure = new RuntimeException(new InterruptedException("strict workflow interrupted"));
        StrictFailureFixture fixture = strictFailureFixture(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.aspect().aroundAsk(fixture.pjp()));

        assertSame(failure, thrown);
        assertTrue(Thread.currentThread().isInterrupted());
        verify(fixture.pjp(), never()).proceed(any(Object[].class));
    }

    @Test
    void errorAfterStrictWorkflowStartPropagatesWithoutReplay() throws Throwable {
        AssertionError failure = new AssertionError("strict workflow fatal failure");
        StrictFailureFixture fixture = strictFailureFixture(failure);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> fixture.aspect().aroundAsk(fixture.pjp()));

        assertSame(failure, thrown);
        verify(fixture.pjp(), never()).proceed(any(Object[].class));
    }

    private StrictFailureFixture strictFailureFixture(Throwable failure) {
        String prefix = "uaw:";
        UawAutolearnStrictRequestAspect aspect = new UawAutolearnStrictRequestAspect(
                new MockEnvironment().withProperty("uaw.autolearn.strict.prefix", prefix),
                null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatWorkflow workflow = mock(ChatWorkflow.class);
        when(pjp.getArgs()).thenReturn(new Object[]{prefix + "bounded legacy training query"});
        when(pjp.getThis()).thenReturn(workflow);
        doAnswer(invocation -> {
            throw failure;
        }).when(workflow).continueChat(any(ChatRequestDto.class));
        return new StrictFailureFixture(aspect, pjp);
    }

    private record StrictFailureFixture(
            UawAutolearnStrictRequestAspect aspect,
            ProceedingJoinPoint pjp) {
    }
}
