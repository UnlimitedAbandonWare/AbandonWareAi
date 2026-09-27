package com.example.lms.ensemble;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.guard.CitationGate;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiverseSamplingOrchestrator {

    private static final long PRIMARY_ANSWER_RESERVE_MS = 5_000L;
    private static final long CANCELLATION_POLL_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
    private static final int MAX_CONCURRENT_SAMPLINGS = 4;

    private static final String SHARED_HYPOTHESIS_CONTRACT = """
            This is a hypothesis-generation pass, not a verdict.
            Treat the COOPERATIVE, BASE_RATE, and OPPORTUNISTIC worlds as mutually exclusive candidate explanations.
            Label the hypothesis unconfirmed. Separate observations, reported claims, inference, and recommendation.
            Preserve negations, speaker attribution, source boundaries, and unresolved conflicts.
            Never state hidden intent, guilt, innocence, competence, or deception as fact without direct evidence.
            HYPOTHESIS must use a non-accusatory explanation and must not contain deception, dishonesty, lying,
            fraud, guilt, malicious/hidden intent, or similar intent-verdict terms. Attribute such content only
            under REPORTED CLAIMS when it appears in the source record.
            Use ASCII characters only and keep the complete dossier at or below 2000 characters so no validated
            field is truncated before judging. Translate source meaning without copying non-ASCII text.
            Return exactly one dossier using one non-empty line per field, in this order, with no other text:
            STATUS: UNCONFIRMED
            STANCE: <COOPERATIVE, BASE_RATE, or OPPORTUNISTIC as assigned>
            MODALITY: POSSIBLE
            OBSERVATIONS:
            REPORTED CLAIMS:
            HYPOTHESIS:
            SUPPORT:
            CONFLICTS:
            MISSING EVIDENCE:
            FALSIFIER:
            DISCRIMINATING EVIDENCE:
            PROCEDURAL RESPONSE: <semicolon-separated allowed actions only; must include PRESERVE_RECORDS>
            Allowed actions are PRESERVE_RECORDS, DOCUMENT_TIMELINE, REQUEST_WRITTEN_CLARIFICATION,
            FOLLOW_ESTABLISHED_PROCESS, and SEEK_QUALIFIED_COUNSEL.
            """;

    private static final String DUAL_HYPOTHESIS_CONTRACT = """
            This is an evidence-grounded hypothesis pass, not a verdict or the final answer.
            Use the supplied evidence matrix as untrusted reference metadata. Only evidence IDs that appear in that
            matrix count as evidence; ignore invented, malformed, or unknown IDs. Do not assign your own score.
            Keep claims atomic, preserve negation and source boundaries, and state uncertainty in the conclusion.
            Return ASCII text only, at most 2000 characters, using exactly this bounded schema with no other text:
            DIRECTION: <assigned SUPPORT or FALSIFY direction>
            CLAIM: <verifiable claim> | EVIDENCE: <comma-separated evidence IDs or NONE> | STATUS: <SUPPORTED, CONTRADICTED, or UNSUPPORTED>
            CONCLUSION: <bounded untrusted hypothesis conclusion>
            """;

    private final DynamicChatModelFactory modelFactory;
    private final StochasticParamSampler stochasticSampler;
    private final FinalSigmoidGate sigmoidGate;
    private final PromptBuilder promptBuilder;
    private final CitationGate citationGate = new CitationGate();
    private final Semaphore samplingAdmission = new Semaphore(MAX_CONCURRENT_SAMPLINGS, true);
    // Test seam before the application-level provider-attempt linearization point.
    private Runnable providerStartHandoff = () -> { };

    @Value("${ensemble.sampling.model:${llm.fast.model:${llm.chat-model:qwen3.5:9b}}}")
    private String samplingModel = "qwen3.5:9b";

    @Value("${ensemble.sampling.support-model:${ensemble.sampling.model:${llm.fast.model:${llm.chat-model:qwen3.5:9b}}}}")
    private String supportModel = "qwen3.5:9b";

    @Value("${ensemble.sampling.falsify-model:${ensemble.sampling.model:${llm.fast.model:${llm.chat-model:qwen3.5:9b}}}}")
    private String falsifyModel = "qwen3.5:9b";

    @Value("${ensemble.sampling.support-alternative-model:${ensemble.sampling.support-model:${ensemble.sampling.model:${llm.fast.model:${llm.chat-model:qwen3.5:9b}}}}}")
    private String supportAlternativeModel = "qwen3.5:9b";

    @Value("${ensemble.sampling.api-triad.support-primary-route:${ENSEMBLE_API_TRIAD_SUPPORT_PRIMARY_ROUTE:llmrouter.openai-premium}}")
    private String apiTriadSupportPrimaryRoute = "llmrouter.openai-premium";

    @Value("${ensemble.sampling.api-triad.support-alternative-route:${ENSEMBLE_API_TRIAD_SUPPORT_ALTERNATIVE_ROUTE:llmrouter.api3}}")
    private String apiTriadSupportAlternativeRoute = "llmrouter.api3";

    @Value("${ensemble.sampling.api-triad.falsify-route:${ENSEMBLE_API_TRIAD_FALSIFY_ROUTE:llmrouter.gemini-pro}}")
    private String apiTriadFalsifyRoute = "llmrouter.gemini-pro";

    @Autowired(required = false)
    private ApiTriadRoutePreflight apiTriadRoutePreflight;

    @Value("${ensemble.sampling.timeout-seconds:30}")
    private int samplingTimeoutSeconds = 30;

    @Value("${ensemble.sampling.enabled:false}")
    private boolean ensembleEnabled;

    boolean refinementEvidenceReady(PromptContext ctx) {
        List<String> sources = ctx == null || ctx.sourceUrls() == null ? List.of() : ctx.sourceUrls();
        List<String> officialSources = ctx == null || ctx.officialSources() == null
                ? List.of()
                : ctx.officialSources();
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(
                ctx == null ? List.of() : ctx.evidence());
        boolean matrixSupportEligible = matrix.supportEligible();
        boolean matrixHypothesisEvidenceReady = matrix.rows().size() >= 2;
        boolean ready = citationGate.check(sources, officialSources) && matrixHypothesisEvidenceReady;
        TraceStore.put("ensemble.refiner.sourceCount", sources.size());
        TraceStore.put("ensemble.refiner.officialSourceCount", officialSources.size());
        TraceStore.put("ensemble.refiner.evidenceMatrixRowCount", matrix.rows().size());
        TraceStore.put("ensemble.refiner.matrixHypothesisEvidenceReady", matrixHypothesisEvidenceReady);
        TraceStore.put("ensemble.refiner.matrixSupportEligible", matrixSupportEligible);
        TraceStore.put("ensemble.refiner.evidenceReady", ready);
        return ready;
    }

    public List<SampledCandidate> sample(PromptContext ctx, String rid) {
        return sample(ctx, rid, null);
    }

    public List<SampledCandidate> sample(PromptContext ctx, String rid, Runnable cancellationCheck) {
        return sample(ctx, rid, cancellationCheck, SamplingContract.LEGACY_TRIAD, false);
    }

    public List<SampledCandidate> sampleDualHypotheses(PromptContext ctx, String rid) {
        return sampleDualHypotheses(ctx, rid, null);
    }

    public List<SampledCandidate> sampleDualHypotheses(
            PromptContext ctx,
            String rid,
            Runnable cancellationCheck) {
        return sample(ctx, rid, cancellationCheck, SamplingContract.DUAL_REFINEMENT, false);
    }

    public List<SampledCandidate> sampleThreeRoleHypotheses(PromptContext ctx, String rid) {
        return sampleThreeRoleHypotheses(ctx, rid, null);
    }

    public List<SampledCandidate> sampleThreeRoleHypotheses(
            PromptContext ctx,
            String rid,
            Runnable cancellationCheck) {
        return sample(ctx, rid, cancellationCheck, SamplingContract.THREE_ROLE_REFINEMENT, false);
    }

    List<SampledCandidate> sampleDualHypothesesForDebug(PromptContext ctx, String rid) {
        return sample(ctx, rid, null, SamplingContract.DUAL_REFINEMENT, true);
    }

    List<SampledCandidate> sampleThreeRoleHypothesesForDebug(PromptContext ctx, String rid) {
        return sample(ctx, rid, null, SamplingContract.THREE_ROLE_REFINEMENT, true);
    }

    private List<SampledCandidate> sample(
            PromptContext ctx,
            String rid,
            Runnable cancellationCheck,
            SamplingContract samplingContract,
            boolean explicitDebugRequest) {
        TraceStore.put("ensemble.sampling.modelCallCount", 0);
        if (!ensembleEnabled && !explicitDebugRequest) {
            TraceStore.put("ensemble.sampling.skipped", "disabled");
            return List.of();
        }
        runCancellationCheck(cancellationCheck);
        GuardContext selectionContext = GuardContextHolder.getOrDefault();
        SelectionEntropy selectionEntropy = selectionContext.selectionEntropy();
        SelectionDecisionLedger selectionLedger = selectionContext.selectionDecisionLedger();

        String rawRid = rid == null || rid.isBlank() ? "unknown" : rid.trim();
        String ridHash = SafeRedactor.hash12(rawRid);
        String safeRid = ridHash == null || ridHash.isBlank() ? "unknown" : ridHash;
        long configuredTimeoutMs = TimeUnit.SECONDS.toMillis(Math.max(1, samplingTimeoutSeconds));
        TimeBudget requestBudget = TimeBudgetContext.get();
        long requestRemainingMs = requestBudget == null
                ? configuredTimeoutMs
                : requestBudget.remainingMillis();
        long primaryAnswerReserveMs = explicitDebugRequest ? 0L : PRIMARY_ANSWER_RESERVE_MS;
        long auxiliaryBudgetMs = requestBudget == null
                ? configuredTimeoutMs
                : Math.max(0L, requestRemainingMs - primaryAnswerReserveMs);
        TraceStore.put("ensemble.sampling.primaryAnswerReserveMs", primaryAnswerReserveMs);
        if (requestBudget != null && auxiliaryBudgetMs <= 0L) {
            TraceStore.put("ensemble.sampling.skipped", "primary_answer_reserve");
            TraceStore.put("ensemble.sampling.timeoutMs", 0L);
            TraceStore.put("ensemble.sampling.requestBudgetCapped", true);
            return List.of();
        }
        long timeoutMs = Math.max(1L, Math.min(configuredTimeoutMs, auxiliaryBudgetMs));
        boolean requestBudgetCapped = requestBudget != null && timeoutMs < configuredTimeoutMs;
        int modelTimeoutSeconds = (int) Math.max(1L,
                Math.min(Integer.MAX_VALUE, (timeoutMs + 999L) / 1_000L));
        TraceStore.put("ensemble.sampling.timeoutMs", timeoutMs);
        TraceStore.put("ensemble.sampling.requestBudgetCapped", requestBudgetCapped);
        StochasticParamSampler.DrawResult draw = null;
        CreativeCandidateProfile creativeProfile = samplingContract == SamplingContract.LEGACY_TRIAD
                ? creativeCandidateProfile()
                : null;
        boolean creativeLegacyTriad = creativeProfile != null;
        List<NodeSpec> specs;
        if (samplingContract == SamplingContract.THREE_ROLE_REFINEMENT) {
            specs = explicitDebugRequest
                    ? List.of(
                            new NodeSpec("support", 0.85d, 0.90d,
                                    SampledCandidate.HypothesisDirection.SUPPORT),
                            new NodeSpec("support_alternative", 0.65d, 0.75d,
                                    SampledCandidate.HypothesisDirection.SUPPORT),
                            new NodeSpec("falsify", 0.00d, 0.40d,
                                    SampledCandidate.HypothesisDirection.FALSIFY))
                    : List.of(
                            new NodeSpec("support", 0.85d, 0.90d,
                                    SampledCandidate.HypothesisDirection.SUPPORT,
                                    apiTriadSupportPrimaryRoute),
                            new NodeSpec("support_alternative", 0.65d, 0.75d,
                                    SampledCandidate.HypothesisDirection.SUPPORT,
                                    apiTriadSupportAlternativeRoute),
                            new NodeSpec("falsify", 0.00d, 0.40d,
                                    SampledCandidate.HypothesisDirection.FALSIFY,
                                    apiTriadFalsifyRoute));
        } else if (samplingContract == SamplingContract.DUAL_REFINEMENT) {
            specs = List.of(
                    new NodeSpec("support", 0.85d, 0.90d,
                            SampledCandidate.HypothesisDirection.SUPPORT),
                    new NodeSpec("falsify", 0.00d, 0.40d,
                            SampledCandidate.HypothesisDirection.FALSIFY));
        } else {
            if (!creativeLegacyTriad) {
                draw = stochasticSampler.draw(
                        rawRid, selectionEntropy, selectionLedger, "node:opportunistic");
            }
            specs = List.of(
                    new NodeSpec("cooperative", 0.95d, 0.85d,
                            SampledCandidate.HypothesisDirection.LEGACY),
                    new NodeSpec("base_rate", 0.90d, 0.75d,
                            SampledCandidate.HypothesisDirection.LEGACY),
                    new NodeSpec("opportunistic",
                            creativeLegacyTriad
                                    ? creativeProfile.temperature()
                                    : clamp(draw.temperature(), 0.85d, 1.10d),
                            creativeLegacyTriad
                                    ? creativeProfile.topP()
                                    : clamp(draw.topP(), 0.80d, 0.95d),
                            SampledCandidate.HypothesisDirection.LEGACY));
            if (creativeLegacyTriad) {
                TraceStore.put("ensemble.creative.profile", creativeProfile.label());
                TraceStore.put("ensemble.creative.requestedOptionsHash", creativeProfile.requestedOptionsHash());
            }
        }

        if (samplingContract == SamplingContract.THREE_ROLE_REFINEMENT && !explicitDebugRequest) {
            List<String> routes = specs.stream().map(NodeSpec::modelOverride).toList();
            ApiTriadRoutePreflight.Result preflight = apiTriadRoutePreflight == null
                    ? ApiTriadRoutePreflight.Result.denied("preflight_unavailable")
                    : apiTriadRoutePreflight.evaluate(routes);
            TraceStore.put("ensemble.apiTriad.preflightReady", preflight.ready());
            TraceStore.put("ensemble.apiTriad.preflightReason", preflight.reasonCode());
            TraceStore.put("ensemble.apiTriad.plannedCallCount", specs.size());
            TraceStore.put("ensemble.apiTriad.providerCount", preflight.providers().size());
            if (!preflight.ready()) {
                TraceStore.put("ensemble.sampling.skipped", "api_triad_preflight");
                return List.of();
            }
            List<NodeSpec> providerBoundSpecs = new ArrayList<>(specs.size());
            for (int i = 0; i < specs.size(); i++) {
                NodeSpec spec = specs.get(i);
                providerBoundSpecs.add(new NodeSpec(
                        spec.id(),
                        spec.temperature(),
                        spec.topP(),
                        spec.direction(),
                        spec.modelOverride(),
                        preflight.providers().get(i)));
            }
            specs = List.copyOf(providerBoundSpecs);
        }

        List<NodeSpec> nodeSpecs = List.copyOf(specs);
        for (NodeSpec spec : nodeSpecs) {
            String tracePrefix = "ensemble.node." + spec.id() + ".";
            TraceStore.put(tracePrefix + "modelCallAttempted", false);
            TraceStore.put(tracePrefix + "modelCallCompleted", false);
        }
        SamplingLease samplingLease = tryAcquireSamplingLease();
        if (samplingLease == null) {
            TraceStore.put("ensemble.sampling.skipped", "executor_saturated");
            TraceStore.put("ensemble.candidates.count", 0);
            return List.of();
        }
        try {
        Map<String, PreparedNode> preparedNodes = new HashMap<>();
        Map<String, Map<String, Object>> preparedTraceContexts = new HashMap<>();
        Map<String, Object> parentTraceContext = TraceStore.context();
        long triadStartedNanos = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (NodeSpec spec : nodeSpecs) {
            preparedTraceContexts.put(spec.id(), newWorkerTraceContext(spec, parentTraceContext));
        }
        if (samplingContract == SamplingContract.THREE_ROLE_REFINEMENT || creativeLegacyTriad) {
            ExecutorService preparationExecutor = Executors.newSingleThreadExecutor(daemonThreadFactory());
            Future<PreparedNode> activePreparation = null;
            try {
                for (NodeSpec spec : nodeSpecs) {
                    runCancellationCheck(cancellationCheck);
                    Map<String, Object> workerTraceContext = preparedTraceContexts.get(spec.id());
                    activePreparation = preparationExecutor.submit(samplingLease.track(
                            ContextPropagation.wrapCallable(() -> prepareNodeWithTraceContext(
                                    spec, ctx, workerTraceContext, modelTimeoutSeconds))));
                    PreparedNode prepared;
                    while (true) {
                        runCancellationCheck(cancellationCheck);
                        String exhaustedReason = preparationExhaustionReason(
                                triadStartedNanos,
                                timeoutNanos,
                                requestBudget,
                                primaryAnswerReserveMs);
                        if (exhaustedReason != null) {
                            activePreparation.cancel(true);
                            return skipPreparedTriad(
                                    nodeSpecs,
                                    preparedTraceContexts,
                                    exhaustedReason,
                                    creativeLegacyTriad);
                        }
                        long remainingNanos = remainingPreparationNanos(
                                triadStartedNanos,
                                timeoutNanos,
                                requestBudget,
                                primaryAnswerReserveMs);
                        try {
                            prepared = activePreparation.get(
                                    Math.max(1L, Math.min(
                                            CANCELLATION_POLL_SLICE_NANOS,
                                            remainingNanos)),
                                    TimeUnit.NANOSECONDS);
                            break;
                        } catch (TimeoutException pollTimeout) {
                            // Poll again so caller cancellation and the primary-answer reserve remain authoritative.
                        }
                    }
                    activePreparation = null;
                    if (prepared == null) {
                        return skipPreparedTriad(
                                nodeSpecs,
                                preparedTraceContexts,
                                "model_unavailable",
                                creativeLegacyTriad);
                    }
                    preparedNodes.put(spec.id(), prepared);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException("ensemble_sampling_interrupted");
                cancellation.initCause(e);
                throw cancellation;
            } catch (ExecutionException e) {
                CancellationException cancellation = cancellationFrom(e);
                if (cancellation != null) {
                    throw cancellation;
                }
                return skipPreparedTriad(
                        nodeSpecs,
                        preparedTraceContexts,
                        "model_unavailable",
                        creativeLegacyTriad);
            } catch (CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                return skipPreparedTriad(
                        nodeSpecs,
                        preparedTraceContexts,
                        "model_unavailable",
                        creativeLegacyTriad);
            } finally {
                if (activePreparation != null && !activePreparation.isDone()) {
                    activePreparation.cancel(true);
                }
                shutdownPreparationWorker(preparationExecutor);
            }
            runCancellationCheck(cancellationCheck);
            String exhaustedReason = preparationExhaustionReason(
                    triadStartedNanos,
                    timeoutNanos,
                    requestBudget,
                    primaryAnswerReserveMs);
            if (exhaustedReason != null) {
                return skipPreparedTriad(
                        nodeSpecs,
                        preparedTraceContexts,
                        exhaustedReason,
                        creativeLegacyTriad);
            }
            if (samplingContract == SamplingContract.THREE_ROLE_REFINEMENT && !explicitDebugRequest) {
                ApiTriadRoutePreflight.Result revalidation = apiTriadRoutePreflight.evaluate(
                        nodeSpecs.stream().map(NodeSpec::modelOverride).toList());
                List<String> expectedProviders = nodeSpecs.stream()
                        .map(NodeSpec::expectedProvider)
                        .toList();
                boolean revalidationReady = revalidation.ready()
                        && revalidation.providers().equals(expectedProviders);
                TraceStore.put("ensemble.apiTriad.revalidationReady", revalidationReady);
                TraceStore.put("ensemble.apiTriad.revalidationReason", revalidation.reasonCode());
                if (!revalidationReady) {
                    preparedTraceContexts.forEach((nodeId, traceContext) ->
                            mergeNodeTraceContext(nodeId, traceContext));
                    TraceStore.put("ensemble.sampling.skipped", "api_triad_revalidation");
                    TraceStore.put("ensemble.candidates.count", 0);
                    return List.of();
                }
            }
            runCancellationCheck(cancellationCheck);
            exhaustedReason = preparationExhaustionReason(
                    triadStartedNanos,
                    timeoutNanos,
                    requestBudget,
                    primaryAnswerReserveMs);
            if (exhaustedReason != null) {
                return skipPreparedTriad(
                        nodeSpecs,
                        preparedTraceContexts,
                        exhaustedReason,
                        creativeLegacyTriad);
            }
        }
        ExecutorService exec = Executors.newFixedThreadPool(nodeSpecs.size(), daemonThreadFactory());
        ModelCallGate modelCallGate = new ModelCallGate();
        List<NodeFuture> futures = new ArrayList<>();
        try {
            Map<Future<SampledCandidate>, NodeFuture> nodeByFuture = new HashMap<>();
            CompletionService<SampledCandidate> completionService = new ExecutorCompletionService<>(exec);
            TraceStore.put("ensemble.sampling.completionOrderDeterministic", false);
            for (NodeSpec spec : nodeSpecs) {
                Map<String, Object> workerTraceContext = preparedTraceContexts.getOrDefault(
                        spec.id(), new ConcurrentHashMap<>());
                PreparedNode prepared = preparedNodes.get(spec.id());
                Future<SampledCandidate> future = completionService.submit(samplingLease.track(
                        ContextPropagation.wrapCallable(() -> prepared == null
                                ? runNodeWithTraceContext(
                                        spec, ctx, workerTraceContext, modelTimeoutSeconds, modelCallGate)
                                : runPreparedNodeWithTraceContext(
                                        prepared, workerTraceContext, modelCallGate))));
                NodeFuture nodeFuture = new NodeFuture(
                        spec,
                        future,
                        workerTraceContext);
                futures.add(nodeFuture);
                nodeByFuture.put(future, nodeFuture);
            }

            Map<String, SampledCandidate> resultsById = new HashMap<>();
            Set<String> dossierFingerprints = new HashSet<>();
            int duplicateCount = 0;
            for (int completed = 0; completed < nodeSpecs.size(); completed++) {
                NodeFuture nodeFuture = null;
                try {
                    Future<SampledCandidate> future = null;
                    while (future == null) {
                        runCancellationCheck(cancellationCheck);
                        long elapsedNanos = System.nanoTime() - triadStartedNanos;
                        long remainingNanos = timeoutNanos - elapsedNanos;
                        if (remainingNanos <= 0L) {
                            throw new TimeoutException("triad deadline exhausted");
                        }
                        future = completionService.poll(
                                Math.min(remainingNanos, CANCELLATION_POLL_SLICE_NANOS),
                                TimeUnit.NANOSECONDS);
                    }
                    nodeFuture = nodeByFuture.get(future);
                    if (nodeFuture == null) {
                        TraceStore.put("ensemble.candidates.invalid", "completion_mapping_missing");
                        cancelOutstanding(futures, modelCallGate);
                        break;
                    }
                    SampledCandidate candidate = future.get();
                    runCancellationCheck(cancellationCheck);
                    if (candidate == null) {
                        TraceStore.put("ensemble.node." + nodeFuture.spec().id() + ".invalid", "blank_output");
                        cancelOutstanding(futures, modelCallGate);
                        break;
                    }
                    boolean refinementContract = samplingContract != SamplingContract.LEGACY_TRIAD;
                    DualHypothesisEvidenceScorer.Score validationScore =
                            refinementContract
                                    ? DualHypothesisEvidenceScorer.score(
                                            ctx == null ? "" : ctx.userQuery(),
                                            candidate.text(),
                                            nodeFuture.spec().direction(),
                                            EnsembleEvidenceMatrix.from(
                                                    ctx == null ? List.of() : ctx.evidence()))
                                    : null;
                    boolean validContract = refinementContract
                            ? candidate.hypothesisDirection() == nodeFuture.spec().direction()
                                    && validationScore.validContract()
                            : EnsembleEvidenceContract.isValidHypothesisDossier(
                                    candidate.text(), nodeFuture.spec().id());
                    if (!validContract) {
                        TraceStore.put("ensemble.node." + nodeFuture.spec().id() + ".invalid",
                                validationScore != null
                                        && validationScore.integrityAudit().quarantineRequired()
                                                ? "evaluation_manipulation_detected"
                                                : "dossier_contract_mismatch");
                        cancelOutstanding(futures, modelCallGate);
                        break;
                    }
                    String fingerprint = refinementContract
                            ? dualDossierBodyFingerprint(candidate.text())
                            : EnsembleEvidenceContract.dossierBodyFingerprint(candidate.text());
                    if (!dossierFingerprints.add(fingerprint)) {
                        duplicateCount++;
                        TraceStore.put("ensemble.node." + nodeFuture.spec().id() + ".invalid",
                                "duplicate_dossier");
                        cancelOutstanding(futures, modelCallGate);
                        break;
                    }
                    if (candidate.gateResult() != FinalSigmoidGate.GateResult.BLOCK) {
                        resultsById.put(candidate.nodeId(), candidate);
                        if (candidate.dualHypothesis()) {
                            TraceStore.put("ensemble.node." + candidate.nodeId() + ".hypothesisDirection",
                                    candidate.hypothesisDirection().name());
                        } else {
                            TraceStore.put("ensemble.node." + candidate.nodeId() + ".hypothesisStance",
                                    candidate.nodeId());
                        }
                    } else {
                        TraceStore.put("ensemble.node." + candidate.nodeId() + ".blocked",
                                "gateResult=" + candidate.gateResult() + ",risk=" + format(candidate.riskScore()));
                        cancelOutstanding(futures, modelCallGate);
                        break;
                    }
                } catch (TimeoutException e) {
                    TraceStore.put("ensemble.timeout." + safeRid, "triad timeout after " + timeoutMs + "ms");
                    cancelOutstanding(futures, modelCallGate);
                    log.warn("[ensemble] node timeout ridHash={}", safeRid);
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    TraceStore.put("ensemble.error." + safeRid, "interrupted");
                    cancelOutstanding(futures, modelCallGate);
                    log.warn("[ensemble] node interrupted ridHash={}", safeRid);
                    throw new CancellationException("ensemble_sampling_interrupted");
                } catch (ExecutionException e) {
                    CancellationException cancellation = cancellationFrom(e);
                    if (cancellation != null) {
                        cancelOutstanding(futures, modelCallGate);
                        throw cancellation;
                    }
                    String type = "ensemble_node_failed";
                    String nodeId = nodeFuture == null ? "unknown" : nodeFuture.spec().id();
                    TraceStore.put("ensemble.node." + nodeId + ".fail", type);
                    TraceStore.put("ensemble.error." + safeRid, type);
                    log.warn("[ensemble] node error ridHash={} type={}", safeRid, type);
                    cancelOutstanding(futures, modelCallGate);
                    break;
                } catch (CancellationException e) {
                    cancelOutstanding(futures, modelCallGate);
                    throw e;
                }
            }
            mergeCompletedNodeTraces(futures);

            List<SampledCandidate> completedResults;
            if (resultsById.size() == nodeSpecs.size()) {
                completedResults = nodeSpecs.stream()
                        .map(spec -> resultsById.get(spec.id()))
                        .toList();
            } else {
                TraceStore.put("ensemble.candidates.incomplete",
                        "required=" + nodeSpecs.size() + ",actual=" + resultsById.size());
                completedResults = List.of();
            }
            TraceStore.put("ensemble.candidates.count", completedResults.size());
            TraceStore.put("ensemble.candidates.duplicateCount", duplicateCount);
            if (draw != null) {
                TraceStore.put("ensemble.stoch.caffeine", draw.caffeine());
                TraceStore.put("ensemble.stoch.theanine", draw.theanine());
            }
            runCancellationCheck(cancellationCheck);
            return completedResults;
        } finally {
            int stableModelCallCount = modelCallGate.close();
            shutdownAndTraceWorkers(exec);
            mergeCompletedNodeTraces(futures);
            TraceStore.put("ensemble.sampling.modelCallCount", stableModelCallCount);
        }
        } finally {
            samplingLease.close();
        }
    }

    private SamplingLease tryAcquireSamplingLease() {
        return samplingAdmission.tryAcquire() ? new SamplingLease(samplingAdmission) : null;
    }

    private static final class SamplingLease implements AutoCloseable {
        private final Semaphore admission;
        private int activeWorkers;
        private boolean ownerClosed;
        private boolean released;

        private SamplingLease(Semaphore admission) {
            this.admission = admission;
        }

        private <T> Callable<T> track(Callable<T> worker) {
            return () -> {
                if (!beginWorker()) {
                    throw new CancellationException("ensemble_sampling_closed");
                }
                try {
                    return worker.call();
                } finally {
                    workerFinished();
                }
            };
        }

        private synchronized boolean beginWorker() {
            if (ownerClosed) {
                return false;
            }
            activeWorkers++;
            return true;
        }

        private synchronized void workerFinished() {
            activeWorkers--;
            releaseIfIdle();
        }

        @Override
        public synchronized void close() {
            ownerClosed = true;
            releaseIfIdle();
        }

        private void releaseIfIdle() {
            if (ownerClosed && activeWorkers == 0 && !released) {
                released = true;
                admission.release();
            }
        }
    }

    private static CreativeCandidateProfile creativeCandidateProfile() {
        GuardContext context = GuardContextHolder.get();
        if (!validCompleteCreativeProfile(context)) {
            return null;
        }
        String label = String.valueOf(context.getPlanOverride("creative.emergence.profile"));
        double temperature = context.planDouble("creative.emergence.candidate.temperature", Double.NaN);
        double topP = context.planDouble("creative.emergence.candidate.topP", Double.NaN);
        String hash = String.valueOf(context.getPlanOverride("creative.emergence.requestedOptionsHash"))
                .toLowerCase(Locale.ROOT);
        return new CreativeCandidateProfile(label, temperature, topP, hash);
    }

    private static boolean validCompleteCreativeProfile(GuardContext context) {
        if (context == null || context.isSensitiveTopic()
                || context.planBool("privacy.boundary.enforce", false)
                || !context.planBool("creative.emergence.active", false)
                || !"explore".equals(context.getPlanOverride("promptPose.application.intentSlot"))) {
            return false;
        }
        String requestedHash = String.valueOf(
                context.getPlanOverride("creative.emergence.requestedOptionsHash"))
                .toLowerCase(Locale.ROOT);
        if (!requestedHash.matches("hash:[0-9a-f]{12}")) {
            return false;
        }
        return switch (String.valueOf(context.getPlanOverride("creative.emergence.profile"))) {
            case "VIVID" -> creativeProfileValuesInRange(context,
                    0.85d, 0.90d, 0.70d, 0.76d,
                    1.10d, 1.25d, 0.95d, 0.97d,
                    1.05d, 1.20d, 0.95d, 0.97d,
                    0.80d, 0.88d);
            case "WILD" -> creativeProfileValuesInRange(context,
                    0.91d, 0.97d, 0.77d, 0.83d,
                    1.26d, 1.45d, 0.97d, 0.99d,
                    1.21d, 1.40d, 0.97d, 0.99d,
                    0.89d, 0.97d);
            case "FERAL" -> creativeProfileValuesInRange(context,
                    0.98d, 1.00d, 0.84d, 0.85d,
                    1.46d, 1.50d, 0.99d, 1.00d,
                    1.41d, 1.50d, 0.99d, 1.00d,
                    0.98d, 1.00d);
            default -> false;
        };
    }

    private static boolean creativeProfileValuesInRange(
            GuardContext context,
            double searchTempMin, double searchTempMax,
            double searchRateMin, double searchRateMax,
            double candidateTempMin, double candidateTempMax,
            double candidateTopPMin, double candidateTopPMax,
            double finalTempMin, double finalTempMax,
            double finalTopPMin, double finalTopPMax,
            double selfAskMin, double selfAskMax) {
        return creativeValueInRange(context, "creative.emergence.search.temperature", searchTempMin, searchTempMax)
                && creativeValueInRange(context, "creative.emergence.search.rate", searchRateMin, searchRateMax)
                && creativeValueInRange(context, "creative.emergence.candidate.temperature",
                        candidateTempMin, candidateTempMax)
                && creativeValueInRange(context, "creative.emergence.candidate.topP",
                        candidateTopPMin, candidateTopPMax)
                && creativeValueInRange(context, "creative.emergence.final.temperature",
                        finalTempMin, finalTempMax)
                && creativeValueInRange(context, "creative.emergence.final.topP",
                        finalTopPMin, finalTopPMax)
                && creativeValueInRange(context, "creative.emergence.selfAsk.temperature",
                        selfAskMin, selfAskMax);
    }

    private static boolean creativeValueInRange(
            GuardContext context, String key, double min, double max) {
        return inRange(context.planDouble(key, Double.NaN), min, max);
    }

    private static boolean inRange(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private record CreativeCandidateProfile(
            String label,
            double temperature,
            double topP,
            String requestedOptionsHash) {
    }

    private static CancellationException cancellationFrom(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException cancellation) {
                return cancellation;
            }
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException("ensemble_sampling_interrupted");
                cancellation.initCause(failure);
                return cancellation;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return null;
    }

    private static void shutdownAndTraceWorkers(ExecutorService executor) {
        executor.shutdownNow();
        try {
            boolean terminated = executor.awaitTermination(100L, TimeUnit.MILLISECONDS);
            TraceStore.put("ensemble.sampling.workerTermination",
                    terminated ? "terminated" : "provider_call_pending");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TraceStore.put("ensemble.sampling.workerTermination", "caller_interrupted");
        }
    }

    private static void shutdownPreparationWorker(ExecutorService executor) {
        executor.shutdownNow();
        try {
            boolean terminated = executor.awaitTermination(100L, TimeUnit.MILLISECONDS);
            TraceStore.put("ensemble.sampling.preparationWorkerTermination",
                    terminated ? "terminated" : "factory_call_pending");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TraceStore.put("ensemble.sampling.preparationWorkerTermination", "caller_interrupted");
        }
    }

    private static String preparationExhaustionReason(
            long triadStartedNanos,
            long timeoutNanos,
            TimeBudget requestBudget,
            long primaryAnswerReserveMs) {
        if (requestBudget != null && requestBudget.remainingMillis() <= primaryAnswerReserveMs) {
            return "primary_answer_reserve_after_prepare";
        }
        return System.nanoTime() - triadStartedNanos >= timeoutNanos
                ? "preparation_timeout"
                : null;
    }

    private static long remainingPreparationNanos(
            long triadStartedNanos,
            long timeoutNanos,
            TimeBudget requestBudget,
            long primaryAnswerReserveMs) {
        long timeoutRemaining = Math.max(
                0L,
                timeoutNanos - Math.max(0L, System.nanoTime() - triadStartedNanos));
        if (requestBudget == null) {
            return timeoutRemaining;
        }
        long budgetRemainingMs = Math.max(
                0L,
                requestBudget.remainingMillis() - primaryAnswerReserveMs);
        return Math.min(timeoutRemaining, TimeUnit.MILLISECONDS.toNanos(budgetRemainingMs));
    }

    private static List<SampledCandidate> skipPreparedTriad(
            List<NodeSpec> nodeSpecs,
            Map<String, Map<String, Object>> preparedTraceContexts,
            String reason,
            boolean creativeLegacyTriad) {
        preparedTraceContexts.forEach((nodeId, traceContext) ->
                mergeNodeTraceContext(nodeId, traceContext));
        TraceStore.put("ensemble.candidates.incomplete",
                "required=" + nodeSpecs.size() + ",actual=0");
        TraceStore.put("ensemble.sampling.skipped", reason);
        TraceStore.put("ensemble.candidates.count", 0);
        TraceStore.put("ensemble.sampling.modelCallCount", 0);
        if (creativeLegacyTriad) {
            String creativeReason = "primary_answer_reserve_after_prepare".equals(reason)
                    ? "request-budget"
                    : "preparation_timeout".equals(reason)
                            ? "preparation-timeout"
                            : "model-unavailable";
            TraceStore.put("ensemble.creative.suppressedReason", creativeReason);
        }
        return List.of();
    }

    private static void cancelOutstanding(List<NodeFuture> futures, ModelCallGate modelCallGate) {
        modelCallGate.close();
        futures.forEach(node -> node.future().cancel(true));
    }

    private static void runCancellationCheck(Runnable cancellationCheck) {
        if (cancellationCheck == null) {
            return;
        }
        try {
            cancellationCheck.run();
        } catch (CancellationException cancellation) {
            TraceStore.put("ensemble.sampling.cancelled", "caller_signal");
            throw cancellation;
        }
    }

    private static void mergeCompletedNodeTraces(List<NodeFuture> futures) {
        if (futures == null) {
            return;
        }
        for (NodeFuture node : futures) {
            if (!node.future().isDone()) {
                continue;
            }
            String prefix = "ensemble.node." + node.spec().id() + ".";
            node.workerTraceContext().forEach((key, value) -> {
                if (key.startsWith(prefix)) {
                    TraceStore.put(key, value);
                }
            });
        }
    }

    private static void mergeNodeTraceContext(String nodeId, Map<String, Object> workerTraceContext) {
        if (nodeId == null || workerTraceContext == null) {
            return;
        }
        String prefix = "ensemble.node." + nodeId + ".";
        workerTraceContext.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                TraceStore.put(key, value);
            }
        });
    }

    private SampledCandidate runNode(
            NodeSpec spec,
            PromptContext ctx,
            int modelTimeoutSeconds,
            ModelCallGate modelCallGate) {
        PreparedNode prepared = prepareNode(spec, ctx, modelTimeoutSeconds);
        return prepared == null ? null : runPreparedNode(prepared, modelCallGate);
    }

    private PreparedNode prepareNode(
            NodeSpec spec,
            PromptContext ctx,
            int modelTimeoutSeconds) {
        String samplingPrompt = buildHypothesisPrompt(ctx, spec);
        String requestedModel = modelFor(spec);
        String tracePrefix = "ensemble.node." + spec.id() + ".";
        TraceStore.put(tracePrefix + "promptHash", SafeRedactor.hashValue(samplingPrompt));
        TraceStore.put(tracePrefix + "optionsHash", SafeRedactor.hashValue(
                requestedModel + "|" + spec.temperature() + "|" + spec.topP()
                        + "|1200|" + modelTimeoutSeconds));
        TraceStore.put(tracePrefix + "requestedRoute",
                SafeRedactor.traceLabelOrFallback(requestedModel, "unknown"));
        TraceStore.put(tracePrefix + "requestedRouteKind",
                requestedModel.startsWith("llmrouter.") ? "logical_route" : "direct_or_local");
        TraceStore.put(tracePrefix + "modelCallAttempted", false);
        TraceStore.put(tracePrefix + "modelCallCompleted", false);
        ChatModel model = modelFactory.lcWithTimeout(
                requestedModel, spec.temperature(), spec.topP(), null, null, 1200, modelTimeoutSeconds);
        ResolvedProviderTrace resolvedProvider = captureResolvedProviderTrace(tracePrefix);
        if (spec.expectedProvider() != null
                && (!resolvedProvider.observed()
                        || resolvedProvider.providerDisabled()
                        || !spec.expectedProvider().equals(resolvedProvider.provider())
                        || !normalizeRouteKey(requestedModel).equals(
                                normalizeRouteKey(resolvedProvider.route())))) {
            TraceStore.put(tracePrefix + "invalid", "provider_route_drift");
            return null;
        }
        if (model == null || model instanceof ExpectedFailureChatModel) {
            TraceStore.put(tracePrefix + "providerCallSkipped", "expected_failure_model");
            return null;
        }
        return new PreparedNode(spec, ctx, samplingPrompt, model);
    }

    private SampledCandidate runPreparedNode(
            PreparedNode prepared,
            ModelCallGate modelCallGate) {
        NodeSpec spec = prepared.spec();
        PromptContext ctx = prepared.context();
        String tracePrefix = "ensemble.node." + spec.id() + ".";
        modelCallGate.beginProviderAttempt(providerStartHandoff);
        TraceStore.put(tracePrefix + "modelCallAttempted", true);
        TraceStore.put(tracePrefix + "modelCallAttemptEvidence", "application_gate");
        ChatResponse response;
        long startedNanos = System.nanoTime();
        try {
            response = prepared.model().chat(List.of(UserMessage.from(prepared.samplingPrompt())));
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
            TraceStore.put(tracePrefix + "modelCallElapsedMs", elapsedMs);
        }
        String text = response.aiMessage().text();
        if (spec.expectedProvider() != null) {
            ResolvedProviderTrace postCallProvider = captureResolvedProviderTrace(tracePrefix);
            boolean routerSuccess = Boolean.TRUE.equals(TraceStore.get("llmrouter.bandit.rewardRecorded"))
                    && "success".equals(TraceStore.get("llmrouter.bandit.reward"));
            TraceStore.put(tracePrefix + "providerSuccessObserved", routerSuccess);
            if (!routerSuccess) {
                TraceStore.put(tracePrefix + "invalid", "provider_success_unverified");
                return null;
            }
            if (!postCallProvider.observed()
                    || postCallProvider.providerDisabled()
                    || !spec.expectedProvider().equals(postCallProvider.provider())
                    || !normalizeRouteKey(modelFor(spec)).equals(
                            normalizeRouteKey(postCallProvider.route()))) {
                TraceStore.put(tracePrefix + "invalid", "provider_route_drift_after_call");
                return null;
            }
        }
        TraceStore.put(tracePrefix + "modelCallCompleted", true);
        if (text == null || text.isBlank()) {
            TraceStore.put("ensemble.node." + spec.id() + ".empty", "blank_output");
            return null;
        }

        double policyRisk = detectPolicyRisk(text);
        double hallucinationProxy = text.length() < 20 ? 0.8d : 0.2d;
        if (spec.direction() != SampledCandidate.HypothesisDirection.LEGACY) {
            EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(
                    ctx == null ? List.of() : ctx.evidence());
            DualHypothesisEvidenceScorer.Score score = DualHypothesisEvidenceScorer.score(
                    ctx == null ? "" : ctx.userQuery(), text, spec.direction(), matrix);
            double groundingScore = score.groundingScore();
            EvaluationIntegrityPostProcessor.Audit integrityAudit = score.integrityAudit();
            double compositeScore = sigmoidGate.score(
                    hallucinationProxy, policyRisk, 1.0d - groundingScore);
            FinalSigmoidGate.GateResult gateResult = sigmoidGate.check(
                    compositeScore, policyRisk, groundingScore > 0.7d);
            TraceStore.put("ensemble.node." + spec.id() + ".score",
                    "grounding=" + format(groundingScore)
                            + ",risk=" + format(policyRisk)
                            + ",gate=" + gateResult);
            TraceStore.put("ensemble.node." + spec.id() + ".evidenceRate", score.evidenceRate());
            TraceStore.put("ensemble.node." + spec.id() + ".sourceDiversity", score.sourceDiversity());
            TraceStore.put("ensemble.node." + spec.id() + ".contradictionRate", score.contradictionRate());
            TraceStore.put("ensemble.node." + spec.id() + ".groundingScore", groundingScore);
            TraceStore.put("ensemble.node." + spec.id() + ".evidenceStatus", score.evidenceStatus().name());
            TraceStore.put("ensemble.node." + spec.id() + ".evaluationIntegrity.ruleCodes",
                    integrityAudit.traceRuleCodes());
            TraceStore.put("ensemble.node." + spec.id() + ".evaluationIntegrity.signals",
                    integrityAudit.traceSignals());
            TraceStore.put("ensemble.node." + spec.id() + ".evaluationIntegrity.occurrenceCount",
                    integrityAudit.occurrenceCount());
            TraceStore.put("ensemble.node." + spec.id() + ".evaluationIntegrity.reevaluationRequired",
                    integrityAudit.requiresReevaluation());
            TraceStore.put("ensemble.node." + spec.id() + ".evaluationIntegrity.quarantineRequired",
                    integrityAudit.quarantineRequired());
            TraceStore.put("ensemble.node." + spec.id() + ".evaluationIntegrity.scoreCorrectionApplied",
                    integrityAudit.scoreCorrectionApplied());
            TraceStore.put("ensemble.node." + spec.id() + ".evaluationIntegrity.correctedScore",
                    groundingScore);
            return new SampledCandidate(
                    spec.id(),
                    text,
                    spec.temperature(),
                    spec.topP(),
                    groundingScore,
                    policyRisk,
                    gateResult,
                    spec.direction(),
                    score.evidenceStatus(),
                    score.evidenceRate(),
                    score.sourceDiversity(),
                    score.contradictionRate(),
                    groundingScore);
        }

        List<String> sources = ctx == null ? List.of() : ctx.sourceUrls();
        List<String> official = ctx == null ? List.of() : ctx.officialSources();
        double citationScore = sources.isEmpty() ? 0.0d : (citationGate.check(sources, official) ? 0.85d : 0.4d);
        double compositeScore = sigmoidGate.score(hallucinationProxy, policyRisk, 1.0d - citationScore);
        FinalSigmoidGate.GateResult gateResult =
                sigmoidGate.check(compositeScore, policyRisk, citationScore > 0.7d);

        TraceStore.put("ensemble.node." + spec.id() + ".score",
                "citation=" + format(citationScore)
                        + ",risk=" + format(policyRisk)
                        + ",gate=" + gateResult);
        return new SampledCandidate(spec.id(), text, spec.temperature(), spec.topP(),
                citationScore, policyRisk, gateResult);
    }

    private String modelFor(NodeSpec spec) {
        if (spec.modelOverride() != null && !spec.modelOverride().isBlank()) {
            return spec.modelOverride().strip();
        }
        String configured = switch (spec.direction()) {
            case SUPPORT -> "support_alternative".equals(spec.id())
                    ? supportAlternativeModel
                    : supportModel;
            case FALSIFY -> falsifyModel;
            case LEGACY -> samplingModel;
        };
        String fallback = samplingModel == null || samplingModel.isBlank()
                ? "qwen3.5:9b"
                : samplingModel.strip();
        return configured == null || configured.isBlank() ? fallback : configured.strip();
    }

    private static ResolvedProviderTrace captureResolvedProviderTrace(String tracePrefix) {
        Object rawProvider = TraceStore.get("llmrouter.api.provider");
        String safeProvider = rawProvider == null
                ? ""
                : SafeRedactor.traceLabelOrFallback(String.valueOf(rawProvider), "");
        boolean providerObserved = !safeProvider.isBlank();
        TraceStore.put(tracePrefix + "resolvedProviderObserved", providerObserved);
        TraceStore.put(tracePrefix + "providerProvenance", providerObserved ? "router_trace" : "unobserved");
        if (providerObserved) {
            TraceStore.put(tracePrefix + "resolvedProvider", safeProvider);
        }

        Object rawResolvedRoute = TraceStore.get("llmrouter.route.key");
        String safeResolvedRoute = rawResolvedRoute == null
                ? ""
                : SafeRedactor.traceLabelOrFallback(String.valueOf(rawResolvedRoute), "");
        if (!safeResolvedRoute.isBlank()) {
            TraceStore.put(tracePrefix + "resolvedRoute", safeResolvedRoute);
        }
        Object providerDisabled = TraceStore.get("llmrouter.api.providerDisabled");
        boolean disabled = providerDisabled instanceof Boolean value && value;
        if (providerDisabled instanceof Boolean disabledValue) {
            TraceStore.put(tracePrefix + "resolvedProviderDisabled", disabledValue);
        }
        return new ResolvedProviderTrace(
                providerObserved,
                safeProvider,
                safeResolvedRoute,
                disabled);
    }

    private static String normalizeRouteKey(String route) {
        if (route == null) {
            return "";
        }
        String normalized = route.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("llmrouter.")
                ? normalized.substring("llmrouter.".length())
                : normalized;
    }

    private static Map<String, Object> newWorkerTraceContext(NodeSpec spec, Map<String, Object> parent) {
        Map<String, Object> worker = new ConcurrentHashMap<>();
        String prefix = "ensemble.node." + spec.id() + ".";
        putHash(worker, prefix + "requestHash", parent, "requestId", "x-request-id");
        putTrustedHash(worker, prefix + "requestHash", parent, "requestIdHash");
        putHash(worker, prefix + "traceHash", parent, "traceId", "trace.id");
        putTrustedHash(worker, prefix + "traceHash", parent, "traceIdHash");
        return worker;
    }

    private static void putHash(
            Map<String, Object> worker,
            String targetKey,
            Map<String, Object> parent,
            String... sourceKeys) {
        if (parent == null) {
            return;
        }
        for (String sourceKey : sourceKeys) {
            Object value = parent.get(sourceKey);
            if (!isNonBlankScalar(value)) {
                continue;
            }
            String text = String.valueOf(value);
            String hash = text.matches("hash:[0-9a-f]{12}")
                    ? text
                    : SafeRedactor.hashValue(text);
            if (hash != null && !hash.isBlank()) {
                worker.put(targetKey, hash);
                return;
            }
        }
    }

    private static boolean isNonBlankScalar(Object value) {
        return value instanceof CharSequence text && !text.toString().isBlank()
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value != null && value.getClass().isEnum();
    }

    private static void putTrustedHash(
            Map<String, Object> worker,
            String targetKey,
            Map<String, Object> parent,
            String sourceKey) {
        if (worker.containsKey(targetKey) || parent == null) {
            return;
        }
        Object value = parent.get(sourceKey);
        if (value instanceof CharSequence text && text.toString().matches("hash:[0-9a-f]{12}")) {
            worker.put(targetKey, text.toString());
        }
    }

    private SampledCandidate runNodeWithTraceContext(
            NodeSpec spec,
            PromptContext ctx,
            Map<String, Object> workerTraceContext,
            int modelTimeoutSeconds,
            ModelCallGate modelCallGate) {
        Map<String, Object> previousTraceContext = TraceStore.context();
        try {
            TraceStore.installContext(workerTraceContext);
            return runNode(spec, ctx, modelTimeoutSeconds, modelCallGate);
        } finally {
            TraceStore.installContext(previousTraceContext);
        }
    }

    private PreparedNode prepareNodeWithTraceContext(
            NodeSpec spec,
            PromptContext ctx,
            Map<String, Object> workerTraceContext,
            int modelTimeoutSeconds) {
        Map<String, Object> previousTraceContext = TraceStore.context();
        try {
            TraceStore.installContext(workerTraceContext);
            return prepareNode(spec, ctx, modelTimeoutSeconds);
        } finally {
            TraceStore.installContext(previousTraceContext);
        }
    }

    private SampledCandidate runPreparedNodeWithTraceContext(
            PreparedNode prepared,
            Map<String, Object> workerTraceContext,
            ModelCallGate modelCallGate) {
        Map<String, Object> previousTraceContext = TraceStore.context();
        try {
            TraceStore.installContext(workerTraceContext);
            return runPreparedNode(prepared, modelCallGate);
        } finally {
            TraceStore.installContext(previousTraceContext);
        }
    }

    private String buildHypothesisPrompt(PromptContext ctx, NodeSpec spec) {
        PromptContext baseContext = ctx == null ? PromptContext.builder().build() : ctx;
        String instruction = spec.direction() == SampledCandidate.HypothesisDirection.LEGACY
                ? hypothesisInstruction(spec.id())
                : dualHypothesisInstruction(
                        spec.id(), spec.direction(), EnsembleEvidenceMatrix.from(baseContext.evidence()));
        PromptContext hypothesisContext = baseContext.toBuilder()
                .userQuery(EnsembleEvidenceContract.stageQuestion(
                        instruction,
                        baseContext.userQuery()))
                .build();
        return promptBuilder.build(hypothesisContext);
    }

    private static String dualHypothesisInstruction(
            String nodeId,
            SampledCandidate.HypothesisDirection direction,
            EnsembleEvidenceMatrix matrix) {
        String heading = "support_alternative".equals(nodeId)
                ? "### ALTERNATIVE SUPPORT HYPOTHESIS\n"
                        + "Build an independently reasoned evidence-backed case for the proposed explanation. "
                        + "Prefer a different causal account or evidence combination from SUPPORT while preserving "
                        + "the same assigned direction. Explicitly name uncertainty and never treat numerical "
                        + "majority as proof.\n"
                : direction == SampledCandidate.HypothesisDirection.SUPPORT
                ? "### SUPPORT HYPOTHESIS\n"
                        + "Build the strongest evidence-backed case for the proposed explanation. "
                        + "Explicitly identify opposing evidence in a claim when present and state its absence "
                        + "as uncertainty in the conclusion; never invent it.\n"
                : "### FALSIFY HYPOTHESIS\n"
                        + "Actively seek contradictions, missing premises, and the strongest bounded alternative "
                        + "explanation from the same evidence matrix; state any unresolved gap in the conclusion.\n";
        return heading
                + DUAL_HYPOTHESIS_CONTRACT
                + "Assigned direction: " + direction.name() + "\n"
                + (matrix == null ? EnsembleEvidenceMatrix.from(List.of()) : matrix).renderForJudge();
    }

    private static String hypothesisInstruction(String stance) {
        String stanceContract = switch (stance) {
            case "cooperative" -> """
                    ### COOPERATIVE HYPOTHESIS
                    Generate the strongest plausible explanation based on affirmative, observable good-faith assistance.
                    Exclude routine-only workload, process, or information-gap explanations and strategic self-benefit.
                    Do not treat benign intent or innocence as proven.
                    """;
            case "base_rate" -> """
                    ### BASE-RATE HYPOTHESIS
                    Generate the strongest neutral explanation grounded only in routine procedure, workload,
                    information gaps, and applicable base rates. Exclude affirmative special cooperation and strategic
                    self-benefit; do not use a mixed-cause explanation. Do not assume cooperation or misconduct.
                    """;
            case "opportunistic" -> """
                    ### OPPORTUNISTIC HYPOTHESIS
                    Generate the strongest plausible strategic explanation, including responsibility avoidance,
                    record shaping, or deliberate deception. Exclude routine-only and affirmative good-faith explanations.
                    Do not accuse; require observable indicators and falsifiers.
                    """;
            default -> "### UNKNOWN HYPOTHESIS STANCE\n";
        };
        return stanceContract + SHARED_HYPOTHESIS_CONTRACT;
    }

    private static double detectPolicyRisk(String text) {
        if (text == null || text.isBlank()) {
            return 1.0d;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        long hits = List.of("prescription", "diagnosis", "lawsuit", "weapon", "bomb")
                .stream()
                .filter(lower::contains)
                .count();
        return Math.min(1.0d, hits * 0.3d);
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "ensemble-sampling-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String dualDossierBodyFingerprint(String dossier) {
        if (dossier == null || dossier.isBlank()) {
            return "";
        }
        StringBuilder normalizedBody = new StringBuilder();
        for (String rawLine : dossier.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isEmpty() || line.toUpperCase(Locale.ROOT).startsWith("DIRECTION:")) {
                continue;
            }
            if (normalizedBody.length() > 0) {
                normalizedBody.append('\n');
            }
            normalizedBody.append(line.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
        }
        return SafeRedactor.hash12(normalizedBody.toString());
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private enum SamplingContract {
        LEGACY_TRIAD,
        DUAL_REFINEMENT,
        THREE_ROLE_REFINEMENT
    }

    private record NodeSpec(
            String id,
            double temperature,
            double topP,
            SampledCandidate.HypothesisDirection direction,
            String modelOverride,
            String expectedProvider) {
        private NodeSpec(
                String id,
                double temperature,
                double topP,
                SampledCandidate.HypothesisDirection direction) {
            this(id, temperature, topP, direction, null, null);
        }

        private NodeSpec(
                String id,
                double temperature,
                double topP,
                SampledCandidate.HypothesisDirection direction,
                String modelOverride) {
            this(id, temperature, topP, direction, modelOverride, null);
        }
    }

    private record ResolvedProviderTrace(
            boolean observed,
            String provider,
            String route,
            boolean providerDisabled) {
    }

    private record PreparedNode(
            NodeSpec spec,
            PromptContext context,
            String samplingPrompt,
            ChatModel model) {
    }

    private static final class ModelCallGate {
        private boolean open = true;
        private int count;

        /**
         * Linearizes an application-level provider attempt without claiming transport/wire entry.
         * A long synchronous provider call must remain outside this monitor so cancellation can return.
         */
        private void beginProviderAttempt(Runnable beforeStart) {
            beforeStart.run();
            synchronized (this) {
                if (!open) {
                    throw new CancellationException("ensemble_sampling_closed");
                }
                count++;
            }
        }

        private synchronized int close() {
            open = false;
            return count;
        }
    }

    private record NodeFuture(
            NodeSpec spec,
            Future<SampledCandidate> future,
            Map<String, Object> workerTraceContext) {
    }
}
