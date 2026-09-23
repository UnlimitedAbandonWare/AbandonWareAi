package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubagentProviderChainTest {

    @Test
    void fallsBackInGlmOpenAiOllamaOrderAndStopsAfterSuccess() {
        List<String> calls = new ArrayList<>();
        FakeProvider glm = provider("vercel-glm", 10, true, true, calls,
                task -> { throw new LlmGatewayException("glm_failed", LlmFailureClass.PROVIDER_ERROR); });
        FakeProvider openai = provider("openai", 20, false, true, calls, task -> "openai answer");
        FakeProvider ollama = provider("ollama", 30, false, true, calls,
                task -> { throw new AssertionError("ollama must not run after OpenAI succeeds"); });
        SubagentProviderChain chain = chain(List.of(ollama, openai, glm));

        SubagentResult result = chain.execute(task("req-1", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(result.status()).isEqualTo(SubagentResult.Status.SUCCESS);
        assertThat(result.selectedProvider()).isEqualTo("openai");
        assertThat(result.output()).isEqualTo("openai answer");
        assertThat(calls).containsExactly("vercel-glm", "openai");
        assertThat(result.attempts())
                .extracting(SubagentResult.Attempt::provider, SubagentResult.Attempt::outcome)
                .containsSubsequence(
                        org.assertj.core.groups.Tuple.tuple("vercel-glm", SubagentResult.AttemptOutcome.FAILED),
                        org.assertj.core.groups.Tuple.tuple("openai", SubagentResult.AttemptOutcome.SUCCESS));
        assertThat(chain.circuitState("vercel-glm")).isEqualTo("open");
        assertThat(result.attempts())
                .filteredOn(attempt -> attempt.provider().equals("vercel-glm")
                        && attempt.outcome() == SubagentResult.AttemptOutcome.FAILED)
                .singleElement()
                .extracting(SubagentResult.Attempt::reasonCode)
                .isEqualTo("provider_failed");
    }

    @Test
    void disabledAvailabilityFailureKeepsCircuitClosedAndMarksReasonAsNonCircuit() {
        SubagentProvider unavailable = new FakeProvider(
                "vercel-glm", 10, false, SubagentProvider.Availability.enabled(), task -> "must not execute") {
            @Override
            public Availability availability() {
                throw new LlmGatewayException("route_disabled", LlmFailureClass.DISABLED);
            }
        };
        SubagentProvider fallback = new FakeProvider(
                "openai", 20, false, SubagentProvider.Availability.enabled(), task -> "fallback answer");
        SubagentProviderChain chain = chain(List.of(unavailable, fallback));

        SubagentResult result = chain.execute(task("req-availability-disabled", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(result.selectedProvider()).isEqualTo("openai");
        assertThat(chain.circuitState("vercel-glm")).isEqualTo("closed");
        assertThat(result.attempts())
                .filteredOn(attempt -> attempt.provider().equals("vercel-glm"))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(SubagentResult.AttemptOutcome.FAILED);
                    assertThat(attempt.failureClass()).isEqualTo(LlmFailureClass.DISABLED);
                    assertThat(attempt.reasonCode()).isEqualTo("availability_failed_no_circuit");
                });
    }

    @Test
    void skipsDisabledProvidersAndUsesOllama() {
        List<String> calls = new ArrayList<>();
        FakeProvider glm = provider("vercel-glm", 10, true, false, calls, task -> "unexpected");
        FakeProvider openai = provider("openai", 20, false, false, calls, task -> "unexpected");
        FakeProvider ollama = provider("ollama", 30, false, true, calls, task -> "local answer");
        SubagentProviderChain chain = chain(List.of(glm, openai, ollama));

        SubagentResult result = chain.execute(task("req-2", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(result.status()).isEqualTo(SubagentResult.Status.SUCCESS);
        assertThat(result.selectedProvider()).isEqualTo("ollama");
        assertThat(calls).containsExactly("ollama");
        assertThat(result.attempts())
                .extracting(SubagentResult.Attempt::provider, SubagentResult.Attempt::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("vercel-glm", SubagentResult.AttemptOutcome.SKIPPED),
                        org.assertj.core.groups.Tuple.tuple("openai", SubagentResult.AttemptOutcome.SKIPPED),
                        org.assertj.core.groups.Tuple.tuple("ollama", SubagentResult.AttemptOutcome.SELECTED),
                        org.assertj.core.groups.Tuple.tuple("ollama", SubagentResult.AttemptOutcome.SUCCESS));
    }

    @Test
    void cancellationStopsTheChainWithoutFallback() {
        List<String> calls = new ArrayList<>();
        FakeProvider glm = provider("vercel-glm", 10, false, true, calls,
                task -> { throw new CancellationException("cancelled"); });
        FakeProvider openai = provider("openai", 20, false, true, calls, task -> "must not run");
        SubagentProviderChain chain = chain(List.of(glm, openai));

        SubagentResult result = chain.execute(task("req-3", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(result.status()).isEqualTo(SubagentResult.Status.CANCELLED);
        assertThat(result.failureClass()).isEqualTo(LlmFailureClass.CANCELLED_NEUTRAL);
        assertThat(calls).containsExactly("vercel-glm");
    }

    @Test
    void authFailureOpensCircuitAndPreventsASecondGlmCall() {
        List<String> calls = new ArrayList<>();
        FakeProvider glm = provider("vercel-glm", 10, true, true, calls,
                task -> { throw new LlmGatewayException("auth_blocked", LlmFailureClass.AUTH_MISSING); });
        FakeProvider openai = provider("openai", 20, false, true, calls, task -> "fallback");
        SubagentProviderChain chain = chain(List.of(glm, openai));

        SubagentResult first = chain.execute(task("req-4a", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });
        SubagentResult second = chain.execute(task("req-4b", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(first.selectedProvider()).isEqualTo("openai");
        assertThat(second.selectedProvider()).isEqualTo("openai");
        assertThat(glm.invocations()).isEqualTo(1);
        assertThat(second.attempts())
                .anySatisfy(attempt -> {
                    assertThat(attempt.provider()).isEqualTo("vercel-glm");
                    assertThat(attempt.outcome()).isEqualTo(SubagentResult.AttemptOutcome.SKIPPED);
                    assertThat(attempt.reasonCode()).isEqualTo("circuit_auth_blocked");
                });
    }

    @Test
    void singleAttemptBudgetAllowsOnlyOneGlmAttemptPerFlow() {
        List<String> calls = new ArrayList<>();
        FakeProvider glm = provider("vercel-glm", 10, true, true, calls, task -> "glm answer");
        FakeProvider openai = provider("openai", 20, false, true, calls, task -> "openai answer");
        SubagentProviderChain chain = chain(List.of(glm, openai));
        SubagentProviderChain.AttemptBudget budget = new SubagentProviderChain.AttemptBudget();

        SubagentResult first = chain.execute(task("req-5", 0), budget, deadlineAfterMs(5_000), ignored -> { });
        SubagentResult second = chain.execute(task("req-5", 1), budget, deadlineAfterMs(5_000), ignored -> { });

        assertThat(first.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(second.selectedProvider()).isEqualTo("openai");
        assertThat(glm.invocations()).isEqualTo(1);
        assertThat(second.attempts().get(0).reasonCode()).isEqualTo("flow_attempt_limit");
    }

    @Test
    void passesOnlyTheRemainingDeadlineToTheProvider() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        AtomicLong observedTimeoutMs = new AtomicLong(-1L);
        SubagentProvider provider = new FakeProvider("ollama", 30, false,
                SubagentProvider.Availability.enabled(), task -> "answer") {
            @Override
            public String execute(SubagentTask task, long timeoutMs) {
                observedTimeoutMs.set(timeoutMs);
                return "answer";
            }
        };
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier(), 1_000L, clock::get);

        SubagentResult result = chain.execute(task("req-6", 0),
                new SubagentProviderChain.AttemptBudget(), 1_250_000_000L, ignored -> { });

        assertThat(result.status()).isEqualTo(SubagentResult.Status.SUCCESS);
        assertThat(observedTimeoutMs).hasValue(250L);
    }

    @Test
    void rejectsAProviderResultThatFinishesAfterTheDeadline() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        long deadline = 1_010_000_000L;
        SubagentProvider provider = new FakeProvider("ollama", 30, false,
                SubagentProvider.Availability.enabled(), task -> {
                    clock.set(deadline + 1L);
                    return "late answer";
                });
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier(), 1_000L, clock::get);

        SubagentResult result = chain.execute(task("req-late", 0),
                new SubagentProviderChain.AttemptBudget(), deadline, ignored -> { });

        assertThat(result.status()).isEqualTo(SubagentResult.Status.TIMEOUT);
        assertThat(result.output()).isNull();
        assertThat(result.failureClass()).isEqualTo(LlmFailureClass.TIMEOUT_SOFT);
        assertThat(result.attempts()).anySatisfy(attempt -> {
            assertThat(attempt.outcome()).isEqualTo(SubagentResult.AttemptOutcome.TIMEOUT);
            assertThat(attempt.reasonCode()).isEqualTo("deadline_exhausted");
        });
    }

    @Test
    void disabledFallbacksDoNotMaskAnEarlierConcreteFailure() {
        List<String> calls = new ArrayList<>();
        FakeProvider glm = provider("vercel-glm", 10, false, true, calls,
                task -> { throw new LlmGatewayException("auth_blocked", LlmFailureClass.AUTH_MISSING); });
        FakeProvider openai = provider("openai", 20, false, false, calls, task -> "unexpected");
        FakeProvider ollama = provider("ollama", 30, false, false, calls, task -> "unexpected");

        SubagentResult result = chain(List.of(glm, openai, ollama)).execute(task("req-mask", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(result.status()).isEqualTo(SubagentResult.Status.FAILED);
        assertThat(result.failureClass()).isEqualTo(LlmFailureClass.AUTH_MISSING);
        assertThat(calls).containsExactly("vercel-glm");
    }

    @Test
    void jvmErrorsPropagateInsteadOfTriggeringProviderFallback() {
        List<String> calls = new ArrayList<>();
        FakeProvider glm = provider("vercel-glm", 10, false, true, calls,
                task -> { throw new AssertionError("fatal provider defect"); });
        FakeProvider openai = provider("openai", 20, false, true, calls, task -> "must not run");
        SubagentProviderChain chain = chain(List.of(glm, openai));

        assertThatThrownBy(() -> chain.execute(task("req-error", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { }))
                .isInstanceOf(AssertionError.class);
        assertThat(calls).containsExactly("vercel-glm");
    }

    @Test
    void transientCircuitCoolsDownAndThenAllowsAProbe() {
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        AtomicInteger glmCalls = new AtomicInteger();
        SubagentProvider glm = new FakeProvider("vercel-glm", 10, false,
                SubagentProvider.Availability.enabled(), task -> {
                    if (glmCalls.getAndIncrement() == 0) {
                        throw new LlmGatewayException("temporary", LlmFailureClass.HEALTH_DOWN);
                    }
                    return "recovered";
                });
        SubagentProvider openai = new FakeProvider("openai", 20, false,
                SubagentProvider.Availability.enabled(), task -> "fallback");
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm, openai), new LlmGatewayFailureClassifier(), 1_000L, clock::get);

        SubagentResult first = chain.execute(task("req-7a", 0),
                new SubagentProviderChain.AttemptBudget(), clock.get() + 5_000_000_000L, ignored -> { });
        SubagentResult duringCooldown = chain.execute(task("req-7b", 0),
                new SubagentProviderChain.AttemptBudget(), clock.get() + 5_000_000_000L, ignored -> { });
        clock.addAndGet(1_100_000_000L);
        SubagentResult afterCooldown = chain.execute(task("req-7c", 0),
                new SubagentProviderChain.AttemptBudget(), clock.get() + 5_000_000_000L, ignored -> { });

        assertThat(first.selectedProvider()).isEqualTo("openai");
        assertThat(duringCooldown.selectedProvider()).isEqualTo("openai");
        assertThat(afterCooldown.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(glmCalls).hasValue(2);
    }

    @Test
    void disabledGlmDoesNotReadTheEnvironmentKeyOrInvokeTheWireAdapter() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.subagent.glm.enabled", "false");
        AtomicInteger keyReads = new AtomicInteger();
        AtomicInteger wireCalls = new AtomicInteger();
        Supplier<String> keySupplier = () -> {
            keyReads.incrementAndGet();
            return "credential-shaped-sentinel";
        };
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                environment,
                keySupplier,
                (apiKey, task, timeoutMs) -> {
                    wireCalls.incrementAndGet();
                    return "unexpected";
                });
        SubagentProvider fallback = provider("ollama", 30, false, true,
                new ArrayList<>(), task -> "local answer");

        SubagentResult result = chain(List.of(glm, fallback)).execute(task("req-8", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(result.selectedProvider()).isEqualTo("ollama");
        assertThat(keyReads).hasValue(0);
        assertThat(wireCalls).hasValue(0);
        assertThat(result.attempts().toString()).doesNotContain("credential-shaped-sentinel");
    }

    @Test
    void glm403IsCategoricalAndOpensTheNoRepeatAuthCircuit() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.subagent.glm.enabled", "true");
        AtomicInteger wireCalls = new AtomicInteger();
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                environment,
                () -> "credential-shaped-sentinel",
                (apiKey, task, timeoutMs) -> {
                    wireCalls.incrementAndGet();
                    return "code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH\nhttpStatus: 403\n";
                });
        SubagentProvider fallback = provider("ollama", 30, false, true,
                new ArrayList<>(), task -> "local answer");
        SubagentProviderChain chain = chain(List.of(glm, fallback));

        SubagentResult first = chain.execute(task("req-9a", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });
        SubagentResult second = chain.execute(task("req-9b", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(first.selectedProvider()).isEqualTo("ollama");
        assertThat(second.selectedProvider()).isEqualTo("ollama");
        assertThat(wireCalls).hasValue(1);
        assertThat(first.attempts())
                .anySatisfy(attempt -> assertThat(attempt.failureClass()).isEqualTo(LlmFailureClass.AUTH_MISSING));
        assertThat(second.attempts())
                .anySatisfy(attempt -> assertThat(attempt.reasonCode()).isEqualTo("circuit_auth_blocked"));
        assertThat(first.attempts().toString()).doesNotContain("credential-shaped-sentinel");
        assertThat(second.attempts().toString()).doesNotContain("credential-shaped-sentinel");
    }

    @Test
    void preservesAllowlistedHttpReasonCodeThroughFallbackWithoutRawFailureText() {
        SubagentProvider glm = new FakeProvider(
                "vercel-glm",
                10,
                true,
                SubagentProvider.Availability.enabled(),
                task -> {
                    throw new LlmGatewayException(
                            "RAW_HTTP_FAILURE_SENTINEL",
                            LlmFailureClass.AUTH_MISSING,
                            "responses_http_403");
                });
        SubagentProvider fallback = provider(
                "ollama", 30, false, true, new ArrayList<>(), task -> "local answer");

        SubagentResult result = chain(List.of(glm, fallback)).execute(
                task("req-http-reason", 0),
                new SubagentProviderChain.AttemptBudget(),
                deadlineAfterMs(5_000),
                ignored -> { });

        assertThat(result.selectedProvider()).isEqualTo("ollama");
        assertThat(result.attempts())
                .anySatisfy(attempt -> assertThat(attempt)
                        .extracting(SubagentResult.Attempt::provider,
                                SubagentResult.Attempt::failureClass,
                                SubagentResult.Attempt::reasonCode)
                        .containsExactly("vercel-glm", LlmFailureClass.AUTH_MISSING,
                                "responses_http_403"));
        assertThat(result.attempts().toString()).doesNotContain("RAW_HTTP_FAILURE_SENTINEL");
    }

    @Test
    void retriesRateLimitOnceOnTheSameProviderBeforeFallback() {
        AtomicInteger glmCalls = new AtomicInteger();
        SubagentProvider glm = new FakeProvider("vercel-glm", 10, false,
                SubagentProvider.Availability.enabled(), task -> {
                    if (glmCalls.getAndIncrement() == 0) {
                        throw new LlmGatewayException("httpStatus: 429", LlmFailureClass.RATE_LIMIT_COOLDOWN);
                    }
                    return "retry recovered";
                });
        SubagentProvider fallback = new FakeProvider("openai", 20, false,
                SubagentProvider.Availability.enabled(), task -> "must not run");
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm, fallback), new LlmGatewayFailureClassifier(),
                1_000L, System::nanoTime, 1, 1L);

        SubagentResult result = chain.execute(task("req-retry-429", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(result.status()).isEqualTo(SubagentResult.Status.SUCCESS);
        assertThat(result.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(glmCalls).hasValue(2);
        assertThat(result.attempts())
                .filteredOn(attempt -> attempt.provider().equals("vercel-glm")
                        && attempt.outcome() == SubagentResult.AttemptOutcome.SELECTED)
                .hasSize(2);
    }

    @Test
    void retriesTransient5xxAtMostOnceThenFallsBack() {
        AtomicInteger glmCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        SubagentProvider glm = new FakeProvider("vercel-glm", 10, false,
                SubagentProvider.Availability.enabled(), task -> {
                    glmCalls.incrementAndGet();
                    throw new LlmGatewayException("httpStatus: 503", LlmFailureClass.HEALTH_DOWN);
                });
        SubagentProvider fallback = new FakeProvider("openai", 20, false,
                SubagentProvider.Availability.enabled(), task -> {
                    fallbackCalls.incrementAndGet();
                    return "fallback answer";
                });
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm, fallback), new LlmGatewayFailureClassifier(),
                1_000L, System::nanoTime, 1, 1L);

        SubagentResult result = chain.execute(task("req-retry-503", 0),
                new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(5_000), ignored -> { });

        assertThat(result.selectedProvider()).isEqualTo("openai");
        assertThat(glmCalls).hasValue(2);
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void retryBackoffIsInterruptibleAndDoesNotFallBackAfterCancellation() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        SubagentProvider glm = new FakeProvider("vercel-glm", 10, false,
                SubagentProvider.Availability.enabled(), task -> {
                    throw new LlmGatewayException("httpStatus: 429", LlmFailureClass.RATE_LIMIT_COOLDOWN);
                });
        SubagentProvider fallback = new FakeProvider("openai", 20, false,
                SubagentProvider.Availability.enabled(), task -> {
                    fallbackCalls.incrementAndGet();
                    return "must not run";
                });
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm, fallback), new LlmGatewayFailureClassifier(),
                1_000L, System::nanoTime, 1, 5_000L);

        Thread.currentThread().interrupt();
        try {
            SubagentResult result = chain.execute(task("req-retry-cancel", 0),
                    new SubagentProviderChain.AttemptBudget(), deadlineAfterMs(10_000), ignored -> { });

            assertThat(result.status()).isEqualTo(SubagentResult.Status.CANCELLED);
            assertThat(result.failureClass()).isEqualTo(LlmFailureClass.CANCELLED_NEUTRAL);
            assertThat(fallbackCalls).hasValue(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static SubagentProviderChain chain(List<SubagentProvider> providers) {
        return new SubagentProviderChain(providers, new LlmGatewayFailureClassifier());
    }

    private static SubagentTask task(String requestId, int ordinal) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("requestId", requestId);
        context.put("ordinal", ordinal);
        return new SubagentTask(requestId, ordinal, "task-" + ordinal, "analysis",
                "bounded prompt", context);
    }

    private static long deadlineAfterMs(long millis) {
        return System.nanoTime() + millis * 1_000_000L;
    }

    private static FakeProvider provider(String id,
                                         int order,
                                         boolean singleAttempt,
                                         boolean enabled,
                                         List<String> calls,
                                         Invocation invocation) {
        return new FakeProvider(id, order, singleAttempt,
                enabled ? SubagentProvider.Availability.enabled()
                        : SubagentProvider.Availability.disabled("disabled_for_test"),
                task -> {
                    calls.add(id);
                    return invocation.call(task);
                });
    }

    @FunctionalInterface
    private interface Invocation {
        String call(SubagentTask task) throws Exception;
    }

    private static class FakeProvider implements SubagentProvider {
        private final String id;
        private final int order;
        private final boolean singleAttempt;
        private final Availability availability;
        private final Invocation invocation;
        private final AtomicInteger invocations = new AtomicInteger();

        private FakeProvider(String id,
                             int order,
                             boolean singleAttempt,
                             Availability availability,
                             Invocation invocation) {
            this.id = id;
            this.order = order;
            this.singleAttempt = singleAttempt;
            this.availability = availability;
            this.invocation = invocation;
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
            return singleAttempt;
        }

        @Override
        public Availability availability() {
            return availability;
        }

        @Override
        public String execute(SubagentTask task, long timeoutMs) throws Exception {
            invocations.incrementAndGet();
            return invocation.call(task);
        }

        private int invocations() {
            return invocations.get();
        }
    }
}
