package com.example.lms.ensemble;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiverseSamplingApiRoleRoutingTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void dualHypothesesPublishRequestedRoutesWithoutClaimingUnobservedProviderProvenance() {
        RecordingFactory factory = new RecordingFactory();
        PromptBuilder promptBuilder = new PromptBuilder() {
            @Override
            public String build(List<PromptContext> contexts, String question) {
                return question;
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                promptBuilder);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "samplingTimeoutSeconds", 2);
        ReflectionTestUtils.setField(orchestrator, "supportModel", "llmrouter.api3");
        ReflectionTestUtils.setField(orchestrator, "falsifyModel", "llmrouter.gemini-pro");

        PromptContext context = PromptContext.builder()
                .userQuery("Which bounded hypothesis is better supported?")
                .sourceUrls(List.of("https://official.example/a", "https://independent.example/b"))
                .officialSources(List.of("https://official.example/a"))
                .evidence(List.of(
                        evidence("A", "https://official.example/a"),
                        evidence("B", "https://independent.example/b")))
                .build();

        List<SampledCandidate> candidates = orchestrator.sampleDualHypotheses(context, "api-role-routing");

        assertEquals(2, candidates.size());
        assertEquals(List.of(
                        "llmrouter.api3/0.85/0.90",
                        "llmrouter.gemini-pro/0.00/0.40"),
                factory.requests.stream().sorted().toList());
        assertEquals("llmrouter.api3", TraceStore.get("ensemble.node.support.requestedRoute"));
        assertEquals("llmrouter.gemini-pro", TraceStore.get("ensemble.node.falsify.requestedRoute"));
        assertEquals("logical_route", TraceStore.get("ensemble.node.support.requestedRouteKind"));
        assertEquals("logical_route", TraceStore.get("ensemble.node.falsify.requestedRouteKind"));
        assertEquals(false, TraceStore.get("ensemble.node.support.resolvedProviderObserved"));
        assertEquals(false, TraceStore.get("ensemble.node.falsify.resolvedProviderObserved"));
        assertEquals("unobserved", TraceStore.get("ensemble.node.support.providerProvenance"));
        assertEquals("unobserved", TraceStore.get("ensemble.node.falsify.providerProvenance"));
        assertEquals(true, TraceStore.get("ensemble.node.support.modelCallAttempted"));
        assertEquals(true, TraceStore.get("ensemble.node.falsify.modelCallAttempted"));
        assertEquals(true, TraceStore.get("ensemble.node.support.modelCallCompleted"));
        assertEquals(true, TraceStore.get("ensemble.node.falsify.modelCallCompleted"));
    }

    @Test
    void dualHypothesesMirrorRouterOwnedProviderTraceWhenPresent() {
        RecordingFactory factory = new RecordingFactory(true);
        PromptBuilder promptBuilder = new PromptBuilder() {
            @Override
            public String build(List<PromptContext> contexts, String question) {
                return question;
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                promptBuilder);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "samplingTimeoutSeconds", 2);
        ReflectionTestUtils.setField(orchestrator, "supportModel", "llmrouter.api3");
        ReflectionTestUtils.setField(orchestrator, "falsifyModel", "llmrouter.gemini-pro");

        PromptContext context = PromptContext.builder()
                .userQuery("Which bounded hypothesis is better supported?")
                .sourceUrls(List.of("https://official.example/a", "https://independent.example/b"))
                .officialSources(List.of("https://official.example/a"))
                .evidence(List.of(
                        evidence("A", "https://official.example/a"),
                        evidence("B", "https://independent.example/b")))
                .build();

        assertEquals(2, orchestrator.sampleDualHypotheses(context, "router-trace-mirror").size());
        assertEquals("groq", TraceStore.get("ensemble.node.support.resolvedProvider"));
        assertEquals("gemini", TraceStore.get("ensemble.node.falsify.resolvedProvider"));
        assertEquals("llmrouter.api3", TraceStore.get("ensemble.node.support.resolvedRoute"));
        assertEquals("llmrouter.gemini-pro", TraceStore.get("ensemble.node.falsify.resolvedRoute"));
        assertEquals(true, TraceStore.get("ensemble.node.support.resolvedProviderObserved"));
        assertEquals(true, TraceStore.get("ensemble.node.falsify.resolvedProviderObserved"));
        assertEquals("router_trace", TraceStore.get("ensemble.node.support.providerProvenance"));
        assertEquals("router_trace", TraceStore.get("ensemble.node.falsify.providerProvenance"));
    }

    @Test
    void threeRoleHypothesesUseTwoDistinctSupportPromptsAndOneFalsifierInCanonicalOrder() {
        RecordingFactory factory = new RecordingFactory(true);
        PromptBuilder promptBuilder = new PromptBuilder() {
            @Override
            public String build(List<PromptContext> contexts, String question) {
                return question;
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                promptBuilder);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "samplingTimeoutSeconds", 2);
        ApiTriadRoutePreflight preflight = mock(ApiTriadRoutePreflight.class);
        when(preflight.evaluate(anyList())).thenReturn(ApiTriadRoutePreflight.Result.ready(
                List.of("openai", "groq", "gemini")));
        ReflectionTestUtils.setField(orchestrator, "apiTriadRoutePreflight", preflight);

        PromptContext context = PromptContext.builder()
                .userQuery("Which bounded hypothesis is better supported?")
                .sourceUrls(List.of("https://official.example/a", "https://independent.example/b"))
                .officialSources(List.of("https://official.example/a"))
                .evidence(List.of(
                        evidence("A", "https://official.example/a"),
                        evidence("B", "https://independent.example/b")))
                .build();

        List<SampledCandidate> candidates = orchestrator.sampleThreeRoleHypotheses(
                context, "api-three-role-routing");

        assertEquals(List.of("support", "support_alternative", "falsify"),
                candidates.stream().map(SampledCandidate::nodeId).toList());
        assertEquals(List.of(
                        "llmrouter.api3/0.65/0.75",
                        "llmrouter.gemini-pro/0.00/0.40",
                        "llmrouter.openai-premium/0.85/0.90"),
                factory.requests.stream().sorted().toList());
        assertEquals(3, TraceStore.get("ensemble.sampling.modelCallCount"));
    }

    @Test
    void threeRolePreflightDenialMakesZeroModelCalls() {
        RecordingFactory factory = new RecordingFactory();
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                (contexts, question) -> question);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ApiTriadRoutePreflight preflight = mock(ApiTriadRoutePreflight.class);
        when(preflight.evaluate(anyList())).thenReturn(
                ApiTriadRoutePreflight.Result.denied("credential_missing"));
        ReflectionTestUtils.setField(orchestrator, "apiTriadRoutePreflight", preflight);

        List<SampledCandidate> candidates = orchestrator.sampleThreeRoleHypotheses(
                PromptContext.builder().userQuery("q").build(), "preflight-denied");

        assertEquals(List.of(), candidates);
        assertEquals(List.of(), factory.requests);
        assertEquals("api_triad_preflight", TraceStore.get("ensemble.sampling.skipped"));
        assertEquals("credential_missing", TraceStore.get("ensemble.apiTriad.preflightReason"));
        assertEquals(0, TraceStore.get("ensemble.sampling.modelCallCount"));
    }

    @Test
    void threeRoleRejectsResolvedProviderDriftBeforeChat() {
        RecordingFactory factory = new RecordingFactory(true, true);
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                (contexts, question) -> question);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ApiTriadRoutePreflight preflight = mock(ApiTriadRoutePreflight.class);
        when(preflight.evaluate(anyList())).thenReturn(ApiTriadRoutePreflight.Result.ready(
                List.of("openai", "groq", "gemini")));
        ReflectionTestUtils.setField(orchestrator, "apiTriadRoutePreflight", preflight);

        List<SampledCandidate> candidates = orchestrator.sampleThreeRoleHypotheses(
                PromptContext.builder()
                        .userQuery("q")
                        .evidence(List.of(
                                evidence("A", "https://official.example/a"),
                                evidence("B", "https://independent.example/b")))
                        .build(),
                "provider-drift");

        assertEquals(List.of(), candidates);
        assertEquals(0, TraceStore.get("ensemble.sampling.modelCallCount"));
        assertEquals("provider_route_drift", TraceStore.get("ensemble.node.support.invalid"));
    }

    @Test
    void threeRoleRevalidatesFallbackPolicyAfterPreparingAllModelsAndBeforeChat() {
        RecordingFactory factory = new RecordingFactory(true);
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                (contexts, question) -> question);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ApiTriadRoutePreflight preflight = mock(ApiTriadRoutePreflight.class);
        when(preflight.evaluate(anyList())).thenReturn(
                ApiTriadRoutePreflight.Result.ready(List.of("openai", "groq", "gemini")),
                ApiTriadRoutePreflight.Result.denied("router_fallback_forbidden"));
        ReflectionTestUtils.setField(orchestrator, "apiTriadRoutePreflight", preflight);

        List<SampledCandidate> candidates = orchestrator.sampleThreeRoleHypotheses(
                PromptContext.builder()
                        .userQuery("q")
                        .evidence(List.of(
                                evidence("A", "https://official.example/a"),
                                evidence("B", "https://independent.example/b")))
                        .build(),
                "fallback-policy-drift");

        assertEquals(List.of(), candidates);
        assertEquals(0, TraceStore.get("ensemble.sampling.modelCallCount"));
        assertEquals("router_fallback_forbidden", TraceStore.get("ensemble.apiTriad.revalidationReason"));
    }

    @Test
    void threeRoleRejectsMissingPostChatRouterSuccessEvidence() {
        RecordingFactory factory = new RecordingFactory(true, false, false);
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                (contexts, question) -> question);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ApiTriadRoutePreflight preflight = mock(ApiTriadRoutePreflight.class);
        when(preflight.evaluate(anyList())).thenReturn(ApiTriadRoutePreflight.Result.ready(
                List.of("openai", "groq", "gemini")));
        ReflectionTestUtils.setField(orchestrator, "apiTriadRoutePreflight", preflight);

        List<SampledCandidate> candidates = orchestrator.sampleThreeRoleHypotheses(
                PromptContext.builder()
                        .userQuery("q")
                        .evidence(List.of(
                                evidence("A", "https://official.example/a"),
                                evidence("B", "https://independent.example/b")))
                        .build(),
                "missing-router-success");

        assertEquals(List.of(), candidates);
        List<String> nodeIds = List.of("support", "support_alternative", "falsify");
        assertTrue(nodeIds.stream().anyMatch(nodeId -> "provider_success_unverified".equals(
                TraceStore.get("ensemble.node." + nodeId + ".invalid"))));
        assertFalse(nodeIds.stream().anyMatch(nodeId -> Boolean.TRUE.equals(
                TraceStore.get("ensemble.node." + nodeId + ".modelCallCompleted"))));
    }

    @Test
    void syntheticExpectedFailureIsNotCountedAsCompletedProviderCall() {
        DynamicChatModelFactory factory = new DynamicChatModelFactory(null, null) {
            @Override
            public ChatModel lcWithTimeout(
                    String modelName,
                    Double temperature,
                    Double topP,
                    Double frequencyPenalty,
                    Double presencePenalty,
                    Integer maxTokens,
                    int timeoutSeconds) {
                return new ExpectedFailureChatModel("safe expected failure", "model-hash");
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                (contexts, question) -> question);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);

        assertEquals(List.of(), orchestrator.sampleDualHypotheses(
                PromptContext.builder().userQuery("q").build(), "expected-failure"));
        assertEquals(0, TraceStore.get("ensemble.sampling.modelCallCount"));
        assertEquals(false, TraceStore.get("ensemble.node.support.modelCallAttempted"));
        assertEquals(false, TraceStore.get("ensemble.node.support.modelCallCompleted"));
    }

    @Test
    void cancelledSyntheticExpectedFailureNodePublishesExplicitNotAttemptedMarkers() {
        CountDownLatch cancelledSupport = new CountDownLatch(1);
        DynamicChatModelFactory factory = new DynamicChatModelFactory(null, null) {
            @Override
            public ChatModel lcWithTimeout(
                    String modelName,
                    Double temperature,
                    Double topP,
                    Double frequencyPenalty,
                    Double presencePenalty,
                    Integer maxTokens,
                    int timeoutSeconds) {
                if (temperature != null && temperature > 0.5d) {
                    try {
                        cancelledSupport.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new ExpectedFailureChatModel("safe expected failure", "model-hash");
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                (contexts, question) -> question);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);

        assertEquals(List.of(), orchestrator.sampleDualHypotheses(
                PromptContext.builder().userQuery("q").build(), "cancelled-expected-failure"));
        assertEquals(0, TraceStore.get("ensemble.sampling.modelCallCount"));
        assertEquals(false, TraceStore.get("ensemble.node.support.modelCallAttempted"));
        assertEquals(false, TraceStore.get("ensemble.node.support.modelCallCompleted"));
    }

    @Test
    void cancelledStartedNodeRetainsAttemptedTrace() {
        CountDownLatch supportCallStarted = new CountDownLatch(1);
        CountDownLatch keepSupportCallOpen = new CountDownLatch(1);
        DynamicChatModelFactory factory = new DynamicChatModelFactory(null, null) {
            @Override
            public ChatModel lcWithTimeout(
                    String modelName,
                    Double temperature,
                    Double topP,
                    Double frequencyPenalty,
                    Double presencePenalty,
                    Integer maxTokens,
                    int timeoutSeconds) {
                if (temperature != null && temperature > 0.5d) {
                    return new ChatModel() {
                        @Override
                        public ChatResponse chat(List<ChatMessage> messages) {
                            supportCallStarted.countDown();
                            try {
                                if (!keepSupportCallOpen.await(2, TimeUnit.SECONDS)) {
                                    throw new AssertionError("support call was not cancelled");
                                }
                                throw new AssertionError("support call must remain blocked until cancellation");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new CancellationException("support call cancelled");
                            }
                        }
                    };
                }
                try {
                    if (!supportCallStarted.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("support provider call did not start");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("falsify preparation interrupted");
                }
                return new ExpectedFailureChatModel("safe expected failure", "model-hash");
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                (contexts, question) -> question);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);

        assertEquals(List.of(), orchestrator.sampleDualHypotheses(
                PromptContext.builder().userQuery("q").build(), "cancelled-started-node"));
        assertEquals(1, TraceStore.get("ensemble.sampling.modelCallCount"));
        assertEquals(true, TraceStore.get("ensemble.node.support.modelCallAttempted"));
    }

    @Test
    void cancelledWorkerTraceIsRemergedAfterTermination() {
        CountDownLatch supportCallStarted = new CountDownLatch(1);
        CountDownLatch keepSupportCallOpen = new CountDownLatch(1);
        DynamicChatModelFactory factory = new DynamicChatModelFactory(null, null) {
            @Override
            public ChatModel lcWithTimeout(
                    String modelName,
                    Double temperature,
                    Double topP,
                    Double frequencyPenalty,
                    Double presencePenalty,
                    Integer maxTokens,
                    int timeoutSeconds) {
                if (temperature != null && temperature > 0.5d) {
                    return new ChatModel() {
                        @Override
                        public ChatResponse chat(List<ChatMessage> messages) {
                            supportCallStarted.countDown();
                            try {
                                if (!keepSupportCallOpen.await(2, TimeUnit.SECONDS)) {
                                    throw new AssertionError("support call was not cancelled");
                                }
                            } catch (InterruptedException interrupted) {
                                try {
                                    Thread.sleep(20L);
                                } catch (InterruptedException repeatedInterrupt) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from("cancelled provider completed cleanup"))
                                    .build();
                        }
                    };
                }
                try {
                    if (!supportCallStarted.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("support provider call did not start");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("falsify preparation interrupted");
                }
                return new ExpectedFailureChatModel("safe expected failure", "model-hash");
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                factory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                (contexts, question) -> question);
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);

        assertEquals(List.of(), orchestrator.sampleDualHypotheses(
                PromptContext.builder().userQuery("q").build(), "cancelled-worker-cleanup"));
        assertEquals(1, TraceStore.get("ensemble.sampling.modelCallCount"));
        assertEquals(true, TraceStore.get("ensemble.node.support.modelCallAttempted"));
        assertEquals(true, TraceStore.get("ensemble.node.support.modelCallCompleted"));
        assertEquals("terminated", TraceStore.get("ensemble.sampling.workerTermination"));
    }

    private static RagEvidenceMetadata evidence(String marker, String source) {
        return new RagEvidenceMetadata(marker, "WEB", "title", source, null,
                1, 1, 1, 0.8d, "score");
    }

    private static final class RecordingFactory extends DynamicChatModelFactory {
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final boolean publishRouterTrace;
        private final boolean providerDrift;
        private final boolean publishSuccessTrace;

        private RecordingFactory() {
            this(false, false, false);
        }

        private RecordingFactory(boolean publishRouterTrace) {
            this(publishRouterTrace, false, publishRouterTrace);
        }

        private RecordingFactory(boolean publishRouterTrace, boolean providerDrift) {
            this(publishRouterTrace, providerDrift, publishRouterTrace);
        }

        private RecordingFactory(
                boolean publishRouterTrace,
                boolean providerDrift,
                boolean publishSuccessTrace) {
            super(null, null);
            this.publishRouterTrace = publishRouterTrace;
            this.providerDrift = providerDrift;
            this.publishSuccessTrace = publishSuccessTrace;
        }

        @Override
        public ChatModel lcWithTimeout(
                String modelName,
                Double temperature,
                Double topP,
                Double frequencyPenalty,
                Double presencePenalty,
                Integer maxTokens,
                int timeoutSeconds) {
            requests.add(String.format(Locale.ROOT, "%s/%.2f/%.2f", modelName, temperature, topP));
            if (publishRouterTrace) {
                TraceStore.put("llmrouter.route.key", modelName);
                TraceStore.put("llmrouter.api.provider",
                        providerDrift
                                ? "groq"
                                : modelName != null && modelName.contains("gemini")
                                        ? "gemini"
                                        : modelName != null && modelName.contains("openai-premium")
                                                ? "openai"
                                                : "groq");
                TraceStore.put("llmrouter.api.providerDisabled", false);
            }
            return new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    if (publishSuccessTrace) {
                        TraceStore.put("llmrouter.bandit.rewardRecorded", true);
                        TraceStore.put("llmrouter.bandit.reward", "success");
                    }
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(dossier(messages)))
                            .build();
                }
            };
        }
    }

    private static String dossier(List<ChatMessage> messages) {
        String prompt = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .findFirst()
                .orElse("");
        Matcher matcher = Pattern.compile("ev1:[0-9a-f]{12}", Pattern.CASE_INSENSITIVE).matcher(prompt);
        List<String> ids = new ArrayList<>();
        while (matcher.find() && ids.size() < 2) {
            String id = matcher.group().toLowerCase(Locale.ROOT);
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.size() < 2) {
            throw new AssertionError("expected two evidence ids");
        }
        boolean alternativeSupport = prompt.contains("### ALTERNATIVE SUPPORT HYPOTHESIS");
        boolean support = alternativeSupport || prompt.contains("### SUPPORT HYPOTHESIS");
        return "DIRECTION: " + (support ? "SUPPORT" : "FALSIFY") + "\n"
                + "CLAIM: " + (alternativeSupport ? "independent bounded evidence claim" : "bounded evidence claim")
                + " | EVIDENCE: "
                + (support ? String.join(",", ids) : ids.get(0))
                + " | STATUS: SUPPORTED\n"
                + "CONCLUSION: untrusted evidence-scored reference";
    }
}
