package com.example.lms.service;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.ensemble.ApiTriadRoutePreflight;
import com.example.lms.ensemble.DiverseSamplingOrchestrator;
import com.example.lms.ensemble.EnsembleFinalAnswerService;
import com.example.lms.ensemble.EnsembleJudgeService;
import com.example.lms.ensemble.SampledCandidate;
import com.example.lms.ensemble.StochasticParamSampler;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.nlp.QueryDomainClassifier;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.postprocess.FinalAnswerPostProcessor;
import com.example.lms.service.postprocess.OutputSanitizer;
import com.example.lms.service.rag.detector.UniversalDomainDetector;
import com.example.lms.service.rag.RagEvidenceAttributionService;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.strategy.DomainStrategyFactory;
import com.example.lms.service.subject.SubjectAnalysis;
import com.example.lms.service.subject.SubjectCategory;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.service.verbosity.SectionSpecGenerator;
import com.example.lms.service.verbosity.VerbosityDetector;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatWorkflowS8PromptBoundaryContractTest {

    private static final String S8_QUERY = "Fictional budgeting scenario. Return exactly two labeled lines. "
            + "OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;"
            + "riskTolerance=low;purchaseCost=high. "
            + "INFERENCE: discretionaryBudget=unknown. Do not invent or repeat any exact financial amount.";

    private static final String UNLABELED_PARAPHRASE = """
            Debt is present.
            Cash flow is tight.
            The spending limit is restricted.
            Risk tolerance is low.
            The purchase cost is high.
            Discretionary budget remains unknown.
            """;

    private static final String COMPLIANT_RESPONSE = """
            OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;riskTolerance=low;purchaseCost=high
            INFERENCE: discretionaryBudget=unknown
            """;

    private static final String S8_WITH_OFFICIAL_METADATA_QUERY = S8_QUERY
            + " Use OpenAI official sources only as citation metadata for this test.";
    private static final String S8_HOLD =
            "HOLD\n한계: S8 응답 계약이 완전하지 않아 제약이나 추론을 자동 생성하지 않았습니다.";

    @Test
    void creativeFinalSamplingDefersEffectiveHashUntilProviderSanitization() {
        clearRequestState();
        try {
            GuardContext context = completeWildCreativeContext();
            context.putPlanOverride("llm.answer.temperature.max", 0.34d);
            GuardContextHolder.set(context);
            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ChatRequestDto base = ChatRequestDto.builder().temperature(0.20d).topP(0.80d).build();

            ChatRequestDto effective = ReflectionTestUtils.invokeMethod(
                    workflow, "applyFinalAnswerSamplingOverrides", base);

            assertEquals(0.34d, effective.getTemperature());
            assertEquals(0.98d, effective.getTopP());
            assertEquals("WILD", TraceStore.get("creative.emergence.profile"));
            assertEquals(SafeRedactor.hashValue("full-wild-profile"),
                    TraceStore.get("creative.emergence.requestedOptionsHash"));
            assertEquals(0.34d, context.planDouble(
                    "creative.emergence.final.effectiveTemperature", Double.NaN));
            assertEquals(0.98d, context.planDouble(
                    "creative.emergence.final.effectiveTopP", Double.NaN));
            assertTrue(context.planBool("creative.emergence.final.providerSamplingPending", false));
            assertEquals(null, TraceStore.get("creative.emergence.effectiveOptionsHash"));
            assertEquals(null, TraceStore.get("llm.answer.overrides"));
            String publicTrace = TraceStore.getAll().toString();
            assertFalse(publicTrace.contains("1.36"));
            assertFalse(publicTrace.contains("0.98"));
        } finally {
            clearRequestState();
        }
    }

    @Test
    void partialCreativeFinalProfileFailsSoftWithoutSynthesizingLineage() {
        clearRequestState();
        try {
            GuardContext context = new GuardContext();
            context.putPlanOverride("creative.emergence.active", true);
            context.putPlanOverride("creative.emergence.profile", "WILD");
            context.putPlanOverride("creative.emergence.final.temperature", 1.36d);
            context.putPlanOverride("creative.emergence.final.topP", 0.98d);
            context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
            context.putPlanOverride("promptPose.application.intentSlot", "explore");
            GuardContextHolder.set(context);
            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ChatRequestDto base = ChatRequestDto.builder().temperature(0.20d).topP(0.80d).build();

            ChatRequestDto effective = ReflectionTestUtils.invokeMethod(
                    workflow, "applyFinalAnswerSamplingOverrides", base);

            assertEquals(0.20d, effective.getTemperature());
            assertEquals(0.80d, effective.getTopP());
            assertEquals("incomplete-profile", TraceStore.get("creative.emergence.suppressedReason"));
            assertEquals(null, context.getPlanOverride("creative.emergence.final.effectiveTemperature"));
            assertEquals(null, TraceStore.get("creative.emergence.effectiveOptionsHash"));
        } finally {
            clearRequestState();
        }
    }

    @Test
    void lateSensitiveDetectionSuppressesCreativeFinalOptionsAndKeepsMandatoryLane() {
        clearRequestState();
        try {
            GuardContext context = new GuardContext();
            context.setSensitiveTopic(true);
            context.putPlanOverride("creative.emergence.active", true);
            context.putPlanOverride("creative.emergence.profile", "WILD");
            context.putPlanOverride("creative.emergence.final.temperature", 1.36d);
            context.putPlanOverride("creative.emergence.final.topP", 0.98d);
            context.putPlanOverride("promptPose.application.intentSlot", "explore");
            context.putPlanOverride("llm.answer.temperature", 0.15d);
            context.putPlanOverride("llm.answer.temperature.max", 0.15d);
            GuardContextHolder.set(context);
            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ChatRequestDto base = ChatRequestDto.builder().temperature(0.70d).topP(0.80d).build();

            ChatRequestDto effective = ReflectionTestUtils.invokeMethod(
                    workflow, "applyFinalAnswerSamplingOverrides", base);

            assertEquals(0.15d, effective.getTemperature());
            assertEquals(0.80d, effective.getTopP());
            assertEquals("sensitive-topic", TraceStore.get("creative.emergence.suppressedReason"));
            assertEquals(null, TraceStore.get("creative.emergence.effectiveOptionsHash"));
        } finally {
            clearRequestState();
        }
    }

    @Test
    void incompleteCreativeProfileCannotEnableFinalEnsembleJudgeMode() {
        GuardContext partial = new GuardContext();
        partial.putPlanOverride("creative.emergence.active", true);
        partial.putPlanOverride("creative.emergence.profile", "WILD");
        partial.putPlanOverride("promptPose.application.intentSlot", "explore");
        List<SampledCandidate> candidates = List.of(
                candidate("cooperative"),
                candidate("base_rate"),
                candidate("opportunistic"));

        Boolean eligible = ReflectionTestUtils.invokeMethod(
                ChatWorkflow.class, "creativeEmergenceTriad", partial, candidates);

        assertEquals(Boolean.FALSE, eligible);
    }

    @Test
    void recordingModelPreservesS8InputAndIncompleteDraftBecomesExplicitHold() {
        Fixture fixture = fixture(UNLABELED_PARAPHRASE);

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(s8Request(), ignored -> List.of());

            assertEquals(1, fixture.model().calls.get(),
                    "retrieval-off S8 must use one primary logical model invocation");
            assertTrue(fixture.model().lastMessages.get(fixture.model().lastMessages.size() - 1)
                    instanceof UserMessage);
            assertEquals(S8_QUERY,
                    ((UserMessage) fixture.model().lastMessages
                            .get(fixture.model().lastMessages.size() - 1)).singleText());
            assertTrue(fixture.model().lastMessages.stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .map(SystemMessage::text)
                    .anyMatch(text -> text.contains("OBSERVED_CONSTRAINTS: debt=present")
                            && text.contains("INFERENCE: discretionaryBudget=unknown")),
                    "PromptBuilder context must retain the exact structured S8 protocol");
            assertEquals(S8_HOLD, result.content());
            assertEquals("s8_incomplete_hold", TraceStore.get("finalAnswer.postprocess.reason"));
        } finally {
            clearRequestState();
        }
    }

    @Test
    void s8IntegrityTraceSeparatesPrimaryAndExpansionWithoutRawContent() {
        Fixture fixture = fixture(COMPLIANT_RESPONSE);
        ReflectionTestUtils.setField(
                fixture.workflow(),
                "lengthVerifier",
                new com.example.lms.service.answer.LengthVerifierService() {
                    @Override
                    public boolean isShort(String text, int minWords) {
                        return true;
                    }
                });
        ReflectionTestUtils.setField(
                fixture.workflow(),
                "answerExpander",
                new com.example.lms.service.answer.AnswerExpanderService(new StandardPromptBuilder()) {
                    @Override
                    public String expandWithLc(
                            String draft,
                            com.example.lms.service.verbosity.VerbosityProfile profile,
                            ChatModel model) {
                        return UNLABELED_PARAPHRASE;
                    }
                });

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(s8Request(), ignored -> List.of());

            assertEquals(S8_HOLD, result.content());
            assertEquals(5, TraceStore.get("finalAnswer.s8Integrity.primary.preservedObservationCount"));
            assertEquals(5, TraceStore.get("finalAnswer.s8Integrity.preExpansion.preservedObservationCount"));
            assertEquals(0, TraceStore.get("finalAnswer.s8Integrity.postExpansion.preservedObservationCount"));
            assertEquals(5, TraceStore.get("finalAnswer.s8Integrity.primary.requiredObservationCount"));
            assertEquals(0, TraceStore.get("finalAnswer.s8Integrity.primary.droppedObservationCount"));
            assertEquals(5, TraceStore.get("finalAnswer.s8Integrity.postExpansion.droppedObservationCount"));
            assertEquals(1, TraceStore.get("finalAnswer.s8Integrity.primary.preservedInferenceCount"));
            assertEquals(0, TraceStore.get("finalAnswer.s8Integrity.postExpansion.preservedInferenceCount"));
            assertEquals(true, TraceStore.get("finalAnswer.s8Integrity.primary.orderedTwoLineContract"));
            assertEquals(false, TraceStore.get("finalAnswer.s8Integrity.postExpansion.orderedTwoLineContract"));
            assertEquals(true, TraceStore.get("finalAnswer.s8Integrity.primary.complete"));
            assertEquals(false, TraceStore.get("finalAnswer.s8Integrity.postExpansion.complete"));

            String trace = TraceStore.getAll().toString();
            assertFalse(trace.contains(COMPLIANT_RESPONSE));
            assertFalse(trace.contains(UNLABELED_PARAPHRASE));
            assertTrue(String.valueOf(TraceStore.get("finalAnswer.s8Integrity.primary.contentHash"))
                    .matches("[0-9a-f]{64}"));

            @SuppressWarnings("unchecked")
            Map<String, Object> ledgerIntegrity = (Map<String, Object>) fixture.ledger()
                    .snapshot().get("s8Integrity");
            assertTrue(ledgerIntegrity != null, "count-only S8 integrity must be visible in chat usage");
            assertEquals("query_hash_latest_stage", ledgerIntegrity.get("correlationScope"));
            @SuppressWarnings("unchecked")
            Map<String, Object> ledgerPrimary =
                    (Map<String, Object>) ledgerIntegrity.get("primary");
            @SuppressWarnings("unchecked")
            Map<String, Object> ledgerPostExpansion =
                    (Map<String, Object>) ledgerIntegrity.get("postExpansion");
            assertEquals(5, ledgerPrimary.get("preservedObservationCount"));
            assertEquals(0, ledgerPostExpansion.get("preservedObservationCount"));
            assertFalse(ledgerIntegrity.toString().contains(COMPLIANT_RESPONSE));
            assertFalse(ledgerIntegrity.toString().contains(UNLABELED_PARAPHRASE));
        } finally {
            clearRequestState();
        }
    }

    @Test
    void missingTriadCredentialMakesZeroAuxiliaryCallsAndStillEndsInRedactedS8Hold() {
        AtomicInteger auxiliaryCalls = new AtomicInteger();
        EnsembleFinalAnswerService ensemble = credentialMissingEnsemble(auxiliaryCalls);
        RagEvidenceAttributionService attribution = mock(RagEvidenceAttributionService.class);
        when(attribution.promoteForPrompt(
                anyString(), isNull(), isNull(), anyList(), any(), anyBoolean()))
                .thenReturn(officialFixtureEvidence());
        Fixture fixture = fixture(UNLABELED_PARAPHRASE, true, ensemble, attribution);

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(
                    s8Request(S8_WITH_OFFICIAL_METADATA_QUERY), ignored -> List.of());

            assertEquals(0, auxiliaryCalls.get(),
                    "credential denial must happen before any triad model construction or call");
            assertEquals("credential_missing", TraceStore.get("ensemble.apiTriad.preflightReason"),
                    () -> "safe gate state: wiredSources="
                            + TraceStore.get("ensemble.refiner.wiredSourceCount")
                            + ",wiredOfficial=" + TraceStore.get("ensemble.refiner.wiredOfficialSourceCount")
                            + ",evidenceReady=" + TraceStore.get("ensemble.refiner.evidenceReady")
                            + ",disabledReason=" + TraceStore.get("ensemble.refiner.disabledReason"));
            assertEquals(0, TraceStore.get("ensemble.sampling.modelCallCount"));
            assertEquals("provider_disabled", TraceStore.get("prompt.context.refiner.reason"));
            assertEquals("credential_missing", TraceStore.get("prompt.context.refiner.disabledReason"));
            assertEquals(true, TraceStore.get("prompt.context.refiner.providerDisabled"));
            assertEquals(true, TraceStore.get("prompt.context.refiner.failSoft"));
            assertEquals(1, fixture.model().calls.get(),
                    "credential fail-soft must preserve exactly one final wrapper call");
            assertEquals(S8_HOLD, result.content());
            String publicTrace = TraceStore.getAll().toString();
            assertFalse(publicTrace.contains("unit-openai-secret-value"));
            assertFalse(publicTrace.contains("unit-groq-secret-value"));
            assertFalse(publicTrace.contains("https://api.openai.com"));
            assertFalse(publicTrace.contains("https://api.groq.com"));
            assertFalse(publicTrace.contains(S8_WITH_OFFICIAL_METADATA_QUERY));
        } finally {
            clearRequestState();
        }
    }

    @Test
    void nonProviderRefinerSkipsPreserveReasonWithoutClaimingProviderDisabled() {
        for (String disabledReason : List.of(
                "citation_context_unavailable",
                "primary_answer_reserve",
                "model_unavailable",
                "unsafe_or_incomplete_hypothesis_set")) {
            EnsembleFinalAnswerService ensemble = mock(EnsembleFinalAnswerService.class);
            when(ensemble.sampleCandidatesForRefinement(
                    any(), nullable(Long.class), any(Runnable.class))).thenAnswer(ignored -> {
                        TraceStore.put("ensemble.refiner.disabledReason", disabledReason);
                        TraceStore.put("ensemble.refiner.candidateCount", 0);
                        return List.of();
                    });
            Fixture fixture = fixture(UNLABELED_PARAPHRASE, true, ensemble, null);

            clearRequestState();
            try {
                ChatResult result = fixture.workflow().continueChat(s8Request(), ignored -> List.of());

                assertEquals(disabledReason, TraceStore.get("prompt.context.refiner.reason"));
                assertEquals(disabledReason, TraceStore.get("prompt.context.refiner.disabledReason"));
                assertEquals(false, TraceStore.get("prompt.context.refiner.providerDisabled"));
                assertEquals(true, TraceStore.get("prompt.context.refiner.failSoft"));
                assertEquals(1, fixture.model().calls.get());
                assertEquals(S8_HOLD, result.content());
            } finally {
                clearRequestState();
            }
        }
    }

    @Test
    void credentialLossDuringTriadRevalidationFailsSoftBeforeAuxiliaryChat() {
        AtomicInteger auxiliaryConstructions = new AtomicInteger();
        AtomicInteger auxiliaryChats = new AtomicInteger();
        MockEnvironment credentials = new MockEnvironment()
                .withProperty("OPENAI_API_KEY", "unit-openai-secret-value")
                .withProperty("GROQ_API_KEY", "unit-groq-secret-value")
                .withProperty("GEMINI_API_KEY", "unit-gemini-secret-value");
        EnsembleFinalAnswerService ensemble = revalidationCredentialMissingEnsemble(
                credentials, auxiliaryConstructions, auxiliaryChats);
        RagEvidenceAttributionService attribution = mock(RagEvidenceAttributionService.class);
        when(attribution.promoteForPrompt(
                anyString(), isNull(), isNull(), anyList(), any(), anyBoolean()))
                .thenReturn(officialFixtureEvidence());
        Fixture fixture = fixture(UNLABELED_PARAPHRASE, true, ensemble, attribution);

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(
                    s8Request(S8_WITH_OFFICIAL_METADATA_QUERY), ignored -> List.of());

            assertEquals("ready", TraceStore.get("ensemble.apiTriad.preflightReason"));
            assertEquals("credential_missing", TraceStore.get("ensemble.apiTriad.revalidationReason"));
            assertEquals(3, auxiliaryConstructions.get());
            assertEquals(0, auxiliaryChats.get());
            assertEquals(0, TraceStore.get("ensemble.sampling.modelCallCount"));
            assertEquals("provider_disabled", TraceStore.get("prompt.context.refiner.reason"));
            assertEquals("credential_missing", TraceStore.get("prompt.context.refiner.disabledReason"));
            assertEquals(true, TraceStore.get("prompt.context.refiner.providerDisabled"));
            assertEquals(true, TraceStore.get("prompt.context.refiner.failSoft"));
            assertEquals(1, fixture.model().calls.get());
            assertEquals(S8_HOLD, result.content());
        } finally {
            clearRequestState();
        }
    }

    @Test
    void creativeRequestsAreExcludedFromTheOneArgumentResponseCache() throws Exception {
        Cacheable cacheable = ChatWorkflow.class
                .getMethod("continueChat", ChatRequestDto.class)
                .getAnnotation(Cacheable.class);

        assertTrue(cacheable != null);
        assertTrue(cacheable.condition().contains("creative.emergence.active"), cacheable.condition());
        assertTrue(cacheable.condition().contains("GuardContextHolder"), cacheable.condition());
    }

    @Test
    void traceResetRehydratesOnlyRedactedCreativeSuppression() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", false);
        context.putPlanOverride("creative.emergence.suppressedReason", "invalid-profile-draw");
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        TraceStore.clear();

        ReflectionTestUtils.invokeMethod(ChatWorkflow.class, "rehydrateCreativeEmergenceTrace", context);

        assertEquals(false, TraceStore.get("creative.emergence.active"));
        assertEquals("invalid-profile-draw", TraceStore.get("creative.emergence.suppressedReason"));
        assertEquals(null, TraceStore.get("creative.emergence.profile"));
        assertEquals(null, TraceStore.get("creative.emergence.requestedOptionsHash"));
    }

    @Test
    void providerFallbackClearsAnyPreviouslyEffectiveCreativeHash() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", true);
        context.putPlanOverride("creative.emergence.effectiveOptionsHash", "hash:0123456789ab");
        context.putPlanOverride("creative.emergence.provider.effectiveTemperature", 1.2d);
        context.putPlanOverride("creative.emergence.provider.effectiveTopP", 0.98d);
        context.putPlanOverride("creative.emergence.final.providerSamplingPending", true);
        GuardContextHolder.set(context);
        TraceStore.put("creative.emergence.effectiveOptionsHash", "hash:0123456789ab");

        ReflectionTestUtils.invokeMethod(ChatWorkflow.class, "markCreativeSamplingUnproven", "provider-default");

        assertEquals(null, context.getPlanOverride("creative.emergence.effectiveOptionsHash"));
        assertEquals(null, context.getPlanOverride("creative.emergence.provider.effectiveTemperature"));
        assertEquals(null, context.getPlanOverride("creative.emergence.final.providerSamplingPending"));
        assertEquals(null, TraceStore.get("creative.emergence.effectiveOptionsHash"));
        assertEquals("provider-default", TraceStore.get("creative.emergence.suppressedReason"));
        clearRequestState();
    }

    @Test
    void successfulFinalCallWithoutDynamicFactoryCannotLeaveSamplingMarkerUnclaimed() {
        Fixture fixture = fixture("final-without-rebuilt-model");
        GuardContext context = completeWildCreativeContext();
        context.putPlanOverride("creative.emergence.final.providerSamplingPending", true);
        GuardContextHolder.set(context);

        String out = ReflectionTestUtils.invokeMethod(
                fixture.workflow(),
                "callWithRetry",
                fixture.model(),
                List.of(UserMessage.from("bounded creative fixture")),
                s8Request());

        assertEquals("final-without-rebuilt-model", out);
        assertEquals(null,
                context.getPlanOverride("creative.emergence.final.providerSamplingPending"));
        assertEquals(null, context.getPlanOverride("creative.emergence.effectiveOptionsHash"));
        assertEquals("sampling-option-unproven",
                TraceStore.get("creative.emergence.suppressedReason"));
        clearRequestState();
    }

    private static Fixture fixture(String response) {
        return fixture(response, false, mock(EnsembleFinalAnswerService.class), null);
    }

    private static Fixture fixture(
            String response,
            boolean promptContextRefinerEnabled,
            EnsembleFinalAnswerService ensembleFinalAnswerService,
            RagEvidenceAttributionService ragEvidenceAttributionService) {
        RecordingModel model = new RecordingModel(response);
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.route(
                anyString(), nullable(String.class), anyString(), anyInt(), anyString()))
                .thenReturn(model);
        when(modelRouter.resolveModelName(model)).thenReturn("s8-recording-fake");

        SubjectResolver subjectResolver = mock(SubjectResolver.class);
        when(subjectResolver.analyze(anyString(), anyList(), any())).thenReturn(
                SubjectAnalysis.builder().category(SubjectCategory.GENERAL).build());
        UniversalDomainDetector domainDetector = mock(UniversalDomainDetector.class);
        when(domainDetector.detect(anyString(), any())).thenReturn("GENERAL");
        QueryContextPreprocessor preprocessor = mock(QueryContextPreprocessor.class);
        when(preprocessor.getInteractionRules(anyString())).thenReturn(Map.of());

        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "interactionPolicyMode", "off");
        ReflectionTestUtils.setField(workflow, "promptContextRefinerEnabled", promptContextRefinerEnabled);
        ReflectionTestUtils.setField(workflow, "queryDomainClassifier", new QueryDomainClassifier());
        ReflectionTestUtils.setField(workflow, "guardProfileProps", new GuardProfileProps());
        ReflectionTestUtils.setField(workflow, "subjectResolver", subjectResolver);
        ReflectionTestUtils.setField(workflow, "domainDetector", domainDetector);
        ReflectionTestUtils.setField(workflow, "domainStrategyFactory", new DomainStrategyFactory());
        ReflectionTestUtils.setField(workflow, "verbosityDetector", new VerbosityDetector());
        ReflectionTestUtils.setField(workflow, "sectionSpecGenerator", new SectionSpecGenerator());
        ReflectionTestUtils.setField(workflow, "qcPreprocessor", preprocessor);
        ReflectionTestUtils.setField(workflow, "evidenceAwareGuard", mock(EvidenceAwareGuard.class));
        ReflectionTestUtils.setField(workflow, "promptBuilder", new StandardPromptBuilder());
        ReflectionTestUtils.setField(workflow, "modelRouter", modelRouter);
        ReflectionTestUtils.setField(workflow, "lengthVerifier",
                mock(com.example.lms.service.answer.LengthVerifierService.class));
        ReflectionTestUtils.setField(workflow, "verifier", mock(FactVerifierService.class));
        ReflectionTestUtils.setField(workflow, "answerExpander",
                mock(com.example.lms.service.answer.AnswerExpanderService.class));
        ReflectionTestUtils.setField(workflow, "ensembleFinalAnswerService", ensembleFinalAnswerService);
        ReflectionTestUtils.setField(workflow, "ragEvidenceAttributionService", ragEvidenceAttributionService);
        ReflectionTestUtils.setField(workflow, "finalAnswerPostProcessor",
                new FinalAnswerPostProcessor(new OutputSanitizer()));
        ChatUsageLedger ledger = new ChatUsageLedger();
        ReflectionTestUtils.setField(workflow, "chatUsageLedger", ledger);
        ReflectionTestUtils.setField(workflow, "llmProvider", "local");
        ReflectionTestUtils.setField(workflow, "defaultModel", "s8-recording-fake");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 0);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
        ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 10);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);

        return new Fixture(workflow, model, ledger);
    }

    private static ChatRequestDto s8Request() {
        return s8Request(S8_QUERY);
    }

    private static ChatRequestDto s8Request(String query) {
        return ChatRequestDto.builder()
                .message(query)
                .model("s8-recording-fake")
                .maxTokens(256)
                .mode("FACT")
                .memoryMode("EPHEMERAL")
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .useVerification(false)
                .build();
    }

    private static EnsembleFinalAnswerService credentialMissingEnsemble(AtomicInteger auxiliaryCalls) {
        DynamicChatModelFactory recordingFactory = new DynamicChatModelFactory(null, null) {
            @Override
            public ChatModel lcWithTimeout(
                    String modelName,
                    Double temperature,
                    Double topP,
                    Double frequencyPenalty,
                    Double presencePenalty,
                    Integer maxTokens,
                    int timeoutSeconds) {
                auxiliaryCalls.incrementAndGet();
                throw new AssertionError("credential preflight must prevent auxiliary model construction");
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                recordingFactory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                new StandardPromptBuilder());
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "apiTriadRoutePreflight", credentialMissingPreflight());

        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(
                orchestrator, mock(EnsembleJudgeService.class));
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        ReflectionTestUtils.setField(service, "alternativeSupportEnabled", true);
        return service;
    }

    private static EnsembleFinalAnswerService revalidationCredentialMissingEnsemble(
            MockEnvironment credentials,
            AtomicInteger auxiliaryConstructions,
            AtomicInteger auxiliaryChats) {
        DynamicChatModelFactory recordingFactory = new DynamicChatModelFactory(null, null) {
            @Override
            public ChatModel lcWithTimeout(
                    String modelName,
                    Double temperature,
                    Double topP,
                    Double frequencyPenalty,
                    Double presencePenalty,
                    Integer maxTokens,
                    int timeoutSeconds) {
                int construction = auxiliaryConstructions.incrementAndGet();
                String route = modelName.substring("llmrouter.".length());
                String provider = switch (route) {
                    case "openai-premium" -> "openai";
                    case "api3" -> "groq";
                    case "gemini-pro" -> "gemini";
                    default -> throw new AssertionError("unexpected route: " + route);
                };
                TraceStore.put("llmrouter.api.provider", provider);
                TraceStore.put("llmrouter.route.key", route);
                TraceStore.put("llmrouter.api.providerDisabled", false);
                if (construction == 1) {
                    credentials.setProperty("GEMINI_API_KEY", "");
                }
                ChatModel model = mock(ChatModel.class);
                when(model.chat(anyList())).thenAnswer(ignored -> {
                    auxiliaryChats.incrementAndGet();
                    throw new AssertionError("revalidation must prevent auxiliary chat");
                });
                return model;
            }
        };
        DiverseSamplingOrchestrator orchestrator = new DiverseSamplingOrchestrator(
                recordingFactory,
                new StochasticParamSampler(),
                new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft"),
                new StandardPromptBuilder());
        ReflectionTestUtils.setField(orchestrator, "ensembleEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "apiTriadRoutePreflight",
                triadPreflight(credentials));

        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(
                orchestrator, mock(EnsembleJudgeService.class));
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        ReflectionTestUtils.setField(service, "alternativeSupportEnabled", true);
        return service;
    }

    private static ApiTriadRoutePreflight credentialMissingPreflight() {
        return triadPreflight(new MockEnvironment()
                .withProperty("OPENAI_API_KEY", "unit-openai-secret-value")
                .withProperty("GROQ_API_KEY", "unit-groq-secret-value"));
    }

    private static ApiTriadRoutePreflight triadPreflight(MockEnvironment credentials) {
        LlmRouterProperties router = new LlmRouterProperties();
        router.setFallbackWhenOpenAiMissing(false);
        router.setModels(Map.of(
                "openai-premium", route("openai", "gpt-5.5", "https://api.openai.com/v1"),
                "api3", route("groq", "openai/gpt-oss-120b", "https://api.groq.com/openai/v1"),
                "gemini-pro", route("gemini", "gemini-2.5-pro",
                        "https://generativelanguage.googleapis.com/v1beta/openai")));

        LlmGatewayProperties gatewayProperties = new LlmGatewayProperties();
        gatewayProperties.setEnabled(false);
        HybridLlmGatewayProbeService gatewayProbe = new HybridLlmGatewayProbeService(
                gatewayProperties, null, null, null, new MockEnvironment());
        KeyResolver keys = new KeyResolver(credentials);
        return new ApiTriadRoutePreflight(router, gatewayProbe, keys);
    }

    private static LlmRouterProperties.ModelConfig route(String provider, String model, String baseUrl) {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true);
        cfg.setProvider(provider);
        cfg.setName(model);
        cfg.setBaseUrl(baseUrl);
        cfg.setFallbackOnly(true);
        return cfg;
    }

    private static List<RagEvidenceMetadata> officialFixtureEvidence() {
        return List.of(
                new RagEvidenceMetadata("W1", "WEB", "OpenAI official fixture A",
                        "https://developers.openai.com/resources/fixture-a", null,
                        1, 1, 1, 0.9d, "fixture"),
                new RagEvidenceMetadata("W2", "WEB", "OpenAI official fixture B",
                        "https://developers.openai.com/resources/fixture-b", null,
                        1, 1, 2, 0.9d, "fixture"));
    }

    private static SampledCandidate candidate(String nodeId) {
        return new SampledCandidate(
                nodeId,
                "bounded hypothesis",
                0.8d,
                0.8d,
                0.9d,
                0.1d,
                FinalSigmoidGate.GateResult.PASS);
    }

    private static GuardContext completeWildCreativeContext() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", true);
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.search.temperature", 0.94d);
        context.putPlanOverride("creative.emergence.search.rate", 0.80d);
        context.putPlanOverride("creative.emergence.candidate.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.candidate.topP", 0.98d);
        context.putPlanOverride("creative.emergence.final.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.final.topP", 0.98d);
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        context.putPlanOverride("creative.emergence.requestedOptionsHash",
                SafeRedactor.hashValue("full-wild-profile"));
        context.putPlanOverride("promptPose.application.intentSlot", "explore");
        return context;
    }

    private static void clearRequestState() {
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    private record Fixture(ChatWorkflow workflow, RecordingModel model, ChatUsageLedger ledger) {
    }

    private static final class RecordingModel implements ChatModel {
        private final String response;
        private final AtomicInteger calls = new AtomicInteger();
        private List<ChatMessage> lastMessages = List.of();

        private RecordingModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            calls.incrementAndGet();
            lastMessages = List.copyOf(messages);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }
}
