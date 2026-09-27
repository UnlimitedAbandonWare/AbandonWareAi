package com.example.lms.uaw.autolearn;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.orchestration.control.RagControlLearningGate;
import com.example.lms.orchestration.control.RagControlRuntimeAdapter;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.learn.CfvmKAllocationTuner;
import com.example.lms.search.TraceStore;
import com.example.lms.agent.CuriosityTriggerService;
import com.example.lms.agent.KnowledgeGapLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Minimal UAW autolearn "dataset accumulator".
 *
 * <p>
 * P2 (log-based sampling / plan DSL execution) can be layered on top later.
 */
@Service
public class UawAutolearnService {

    private static final Logger log = LoggerFactory.getLogger(UawAutolearnService.class);

    private final ChatService chatService;
    private final UawAutolearnProperties props;
    private final UawSeedSampler seedSampler;
    private final UawDatasetWriter datasetWriter;
    private final LearningSampleValidationMetadataBuilder validationMetadataBuilder;
    private final ObjectProvider<UawLearningAgentHandoffWriter> handoffWriter;
    private final UawAutolearnQualityTracker qualityTracker;
    private final ObjectProvider<MemoryReinforcementService> memoryReinforcementService;
    private final ObjectProvider<CfvmKAllocationTuner> cfvmKAllocationTuner;
    private final ObjectProvider<OpenCodeFreeQuotaGuard> externalQuotaGuard;
    private final ObjectProvider<RagControlLearningGate> ragControlLearningGate;
    private final Environment env;

    private final KnowledgeGapLogger gapLogger;
    private final ObjectProvider<CuriosityTriggerService> curiosity;

    @org.springframework.beans.factory.annotation.Autowired
    public UawAutolearnService(ChatService chatService,
            UawAutolearnProperties props,
            UawSeedSampler seedSampler,
            UawDatasetWriter datasetWriter,
            LearningSampleValidationMetadataBuilder validationMetadataBuilder,
            ObjectProvider<UawLearningAgentHandoffWriter> handoffWriter,
            UawAutolearnQualityTracker qualityTracker,
            ObjectProvider<MemoryReinforcementService> memoryReinforcementService,
            ObjectProvider<CfvmKAllocationTuner> cfvmKAllocationTuner,
            ObjectProvider<OpenCodeFreeQuotaGuard> externalQuotaGuard,
            Environment env,
            KnowledgeGapLogger gapLogger,
            ObjectProvider<CuriosityTriggerService> curiosity,
            ObjectProvider<RagControlLearningGate> ragControlLearningGate) {
        this.chatService = chatService;
        this.props = props;
        this.seedSampler = seedSampler;
        this.datasetWriter = datasetWriter;
        this.validationMetadataBuilder = validationMetadataBuilder;
        this.handoffWriter = handoffWriter;
        this.qualityTracker = qualityTracker == null ? new UawAutolearnQualityTracker(props, null, null) : qualityTracker;
        this.memoryReinforcementService = memoryReinforcementService;
        this.cfvmKAllocationTuner = cfvmKAllocationTuner;
        this.externalQuotaGuard = externalQuotaGuard;
        this.ragControlLearningGate = ragControlLearningGate;
        this.env = env;
        this.gapLogger = gapLogger;
        this.curiosity = curiosity;
    }

    public UawAutolearnService(ChatService chatService,
            UawAutolearnProperties props,
            UawSeedSampler seedSampler,
            UawDatasetWriter datasetWriter,
            LearningSampleValidationMetadataBuilder validationMetadataBuilder,
            ObjectProvider<UawLearningAgentHandoffWriter> handoffWriter,
            UawAutolearnQualityTracker qualityTracker,
            ObjectProvider<MemoryReinforcementService> memoryReinforcementService,
            ObjectProvider<CfvmKAllocationTuner> cfvmKAllocationTuner,
            ObjectProvider<OpenCodeFreeQuotaGuard> externalQuotaGuard,
            Environment env,
            KnowledgeGapLogger gapLogger,
            ObjectProvider<CuriosityTriggerService> curiosity) {
        this(chatService,
                props,
                seedSampler,
                datasetWriter,
                validationMetadataBuilder,
                handoffWriter,
                qualityTracker,
                memoryReinforcementService,
                cfvmKAllocationTuner,
                externalQuotaGuard,
                env,
                gapLogger,
                curiosity,
                null);
    }

    public AutoLearnCycleResult runCycle(File datasetFile,
            String sessionId,
            PreemptionToken token,
            long deadlineNanos) {
        // Cycle boundary marker: elapsed time and completion status are
        // recorded once per cycle in traceCycleSummary below.
        long cycleStartedNs = System.nanoTime();
        int attempted = 0;
        int accepted = 0;
        int zeroEvidenceAccepted = 0;
        boolean aborted = false;
        Map<String, Integer> phaseFailures = new LinkedHashMap<>();
        int externalQuotaCallsThisCycle = 0;

        List<String> seeds = buildSeeds();
        int n = Math.min(Math.max(0, props.getBatchSize()), seeds.size());

        for (int i = 0; i < n; i++) {
            if (System.nanoTime() > deadlineNanos)
                break;
            if (token != null && token.shouldAbort()) {
                aborted = true;
                mark(phaseFailures, "user_preempted");
                break;
            }

            String q = seeds.get(i);
            attempted++;

            ChatResult r;
            RagControlRuntimeAdapter.RuntimeInput presentationInput = null;
            GuardContext previousGuardContext = null;
            OpenCodeFreeQuotaGuard.Lease externalQuotaLease = null;
            String requestedModel = "";
            try {
                resetTrainingTrace();
                qualityTracker.seedThresholdTrace();
                previousGuardContext = installAutolearnGuardContext(q, sessionId, token, deadlineNanos);
                ChatRequestDto request = buildAutolearnRequest(q);
                request = clampExternalQuotaRequest(request);
                requestedModel = request == null ? "" : request.getModel();
                String privacyBlock = externalPrivacyBlockReason(requestedModel, q);
                if (!privacyBlock.isBlank()) {
                    mark(phaseFailures, privacyBlock);
                    qualityTracker.recordExternalError(privacyBlock);
                    TraceStore.put("uaw.autolearn.externalQuota.disabledReason", safeReason(privacyBlock));
                    recordHandoffSkippedSample(sessionId, q, "", requestedModel, 0, null, "SKIPPED", privacyBlock);
                    continue;
                }
                OpenCodeFreeQuotaGuard quotaGuard = externalQuotaGuard();
                if (quotaGuard != null) {
                    OpenCodeFreeQuotaGuard.Decision quota = quotaGuard.tryAcquire(
                            request.getModel(),
                            request.getMaxTokens(),
                            externalQuotaCallsThisCycle);
                    if (!quota.allowed()) {
                        String reason = quota.disabledReason() == null || quota.disabledReason().isBlank()
                                ? "external_quota_denied"
                                : quota.disabledReason();
                        mark(phaseFailures, reason);
                        qualityTracker.recordExternalError(reason);
                        log.debug("[UAW] external quota denied AutoLearn sample reason={} modelHash={} modelLength={}",
                                reason, com.example.lms.trace.SafeRedactor.hashValue(quota.model()), quota.model() == null ? 0 : quota.model().length());
                        recordHandoffSkippedSample(sessionId, q, "", quota.model(), 0, null, "SKIPPED", reason);
                        continue;
                    }
                    if (quota.consumed()) {
                        externalQuotaLease = quota.lease();
                        externalQuotaCallsThisCycle++;
                    }
                }
                r = chatService.continueChat(request);
            } catch (CancellationException e) {
                aborted = true;
                TraceStore.put("uaw.autolearn.cancelled", true);
                mark(phaseFailures, "user_preempted");
                log.debug("[UAW] AutoLearn sample preempted. errorHash={} errorLength={}",
                        com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                break;
            } catch (Exception e) {
                mark(phaseFailures, "chat_service_exception");
                qualityTracker.recordExternalError("chat_service_exception");
                log.debug("[UAW] ChatService.continueChat failed. errorHash={} errorLength={}",
                        com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                OpenCodeFreeQuotaGuard quotaGuard = externalQuotaGuard();
                if (quotaGuard != null) {
                    quotaGuard.recordFailure(externalQuotaLease, e);
                }
                recordHandoffSkippedSample(sessionId, q, "", "", 0, null, "ERROR", "chat_service_exception");
                continue;
            } finally {
                presentationInput = RagControlRuntimeAdapter.currentPresentationInput();
                RagControlRuntimeAdapter.capturePresentationInput(null);
                restoreGuardContext(previousGuardContext);
            }

            // [PATCH] Defensive: ChatService may (rarely) return null/blank; avoid NPE and
            // noisy samples.
            if (r == null || r.content() == null || r.content().isBlank()) {
                log.warn("[UAW] ask returned empty result; skip. queryHash={} queryLength={}", queryHash(q), safeLength(q));
                mark(phaseFailures, "empty_result");
                qualityTracker.recordExternalError("empty_result");
                TraceStore.put("uaw.autolearn.sampleSkipped.reason", "empty-response");
                recordHandoffSkippedSample(sessionId,
                        q,
                        r == null ? "" : r.content(),
                        r == null ? "" : r.modelUsed(),
                        0,
                        null,
                        "SKIPPED",
                        "empty-response");
                continue;
            }

            if (token != null && token.shouldAbort()) {
                aborted = true;
                mark(phaseFailures, "user_preempted");
                break;
            }

            int evidenceCount = (r.evidence() == null) ? 0 : r.evidence().size();
            String disabledReason = firstNonBlank(
                    TraceStore.getString("selfask.3way.api.disabledReason"),
                    TraceStore.getString("llmrouter.api.disabledReason"),
                    TraceStore.getString("web.naver.disabledReasonCanonical"),
                    TraceStore.getString("web.brave.disabledReasonCanonical"),
                    TraceStore.getString("web.serpapi.disabledReasonCanonical"),
                    TraceStore.getString("web.tavily.disabledReasonCanonical"),
                    TraceStore.getString("rag.eval.providerDisabledSignals"));
            ExternalFreeModelPolicy.Evaluation externalModelPolicy = ExternalFreeModelPolicy.evaluate(
                    props == null ? null : props.getExternalQuota(),
                    requestedModel,
                    r.modelUsed(),
                    trainingProvider());
            if (externalModelPolicy.curateOnly()) {
                disabledReason = firstNonBlank(disabledReason, safeReason(externalModelPolicy.disabledReason()));
                TraceStore.put("uaw.autolearn.externalQuota.canonicalTrainingPolicy",
                        externalModelPolicy.modelPolicy());
                TraceStore.put("uaw.autolearn.externalQuota.endpointFamily",
                        externalModelPolicy.endpointFamily());
                TraceStore.put("uaw.autolearn.externalQuota.disabledReason",
                        safeReason(externalModelPolicy.disabledReason()));
            }
            if (!disabledReason.isBlank()) {
                log.debug("[UAW] sample marked provider-disabled; dataset writer will reject reason={}", disabledReason);
            }
            if (evidenceCount < props.getMinEvidenceCount()) {
                // Safety pin: avoid "always skip" deadlocks when evidence pipeline is down.
                // We only allow a very small number of zero-evidence samples per cycle,
                // and only for static internal seeds (NOT gap/curiosity/user-derived prompts).
                boolean allowZero = (evidenceCount == 0)
                        && props.getSafetyPin().isAllowZeroEvidenceForStaticSeeds()
                        && (zeroEvidenceAccepted < Math.max(0,
                                props.getSafetyPin().getMaxZeroEvidenceAcceptedPerCycle()))
                        && isStaticInternalSeed(q);
                if (!allowZero) {
                    log.debug("[UAW] skip sample: insufficient evidence={} queryHash={} queryLength={}",
                            evidenceCount, queryHash(q), safeLength(q));
                    mark(phaseFailures, "insufficient_evidence");
                    qualityTracker.recordExternalError("insufficient_evidence");
                    recordHandoffSkippedSample(sessionId, q, r.content(), r.modelUsed(), evidenceCount, null,
                            "SKIPPED", "insufficient_evidence");
                    continue;
                }

                zeroEvidenceAccepted++;
                log.info("[UAW] SAFETY_PIN: accept zero-evidence sample cycleCap={} queryHash={} queryLength={}",
                        props.getSafetyPin().getMaxZeroEvidenceAcceptedPerCycle(), queryHash(q), safeLength(q));
            }

            double contextDiversity = contextDiversity(r.evidence());
            if (contextDiversity < Math.max(0.0d, props.getMinContextDiversity())) {
                log.debug("[UAW] skip sample: contextDiversity={} min={} queryHash={} queryLength={}",
                        contextDiversity, props.getMinContextDiversity(), queryHash(q), safeLength(q));
                mark(phaseFailures, "low_context_diversity");
                qualityTracker.recordExternalError("low_context_diversity");
                recordHandoffSkippedSample(sessionId, q, r.content(), r.modelUsed(), evidenceCount, null,
                        "SKIPPED", "low_context_diversity");
                continue;
            }

            int afterFilterCount = afterFilterCount(evidenceCount);
            boolean finalGate = evidenceCount >= props.getMinEvidenceCount()
                    && contextDiversity >= Math.max(0.0d, props.getMinContextDiversity());
            LearningSampleValidationMetadata validation = validationMetadataBuilder.build(
                    q,
                    r.content(),
                    r.modelUsed(),
                    evidenceCount,
                    afterFilterCount,
                    finalGate,
                    contextDiversity,
                    disabledReason);
            boolean providerDisabled = !disabledReason.isBlank();
            validation = qualityTracker.project(validation, providerDisabled, finalGate);
            if (validation != null
                    && shouldHoldLearningWrites(
                            r, evidenceCount, afterFilterCount, validation, presentationInput)) {
                mark(phaseFailures, "rag_control_hold");
                TraceStore.put("uaw.autolearn.ragControl.hold", true);
                recordHandoffHeldSample(
                        sessionId,
                        q,
                        r.content(),
                        r.modelUsed(),
                        evidenceCount);
                continue;
            }
            validation = feedbackCfvmPostValidation(validation);
            UawDatasetWriter.TrainingMetadata metadata = new UawDatasetWriter.TrainingMetadata(
                    "uaw_autolearn",
                    trainingProvider(),
                    disabledReason,
                    afterFilterCount,
                    finalGate,
                    contextDiversity,
                    validation);

            boolean ok = false;
            if (validation.accepted() && !externalModelPolicy.curateOnly()) {
                ok = datasetWriter.append(
                        datasetFile,
                        props.getDataset().getName(),
                        q,
                        r.content(),
                        r.modelUsed(),
                        evidenceCount,
                        sessionId,
                        metadata);
            } else {
                log.debug("[UAW] skip sample: validation rejected reason={} queryHash={} queryLength={}",
                        firstRejectReason(validation), queryHash(q), safeLength(q));
            }
            qualityTracker.recordSample(validation, sessionId, sampleHash(q, r.content(), r.modelUsed()),
                    providerDisabled, finalGate, ok);
            recordHandoffSample(sessionId, q, r, evidenceCount, metadata, ok);
            if (ok) {
                accepted++;
                reinforceAcceptedSample(sessionId, q, r.content(), validation);
            } else if (externalModelPolicy.curateOnly()) {
                mark(phaseFailures, ExternalFreeModelPolicy.CURATE_ONLY_REASON);
            } else if (providerDisabled) {
                mark(phaseFailures, "provider_disabled");
            } else if (!finalGate) {
                mark(phaseFailures, "final_gate_failed");
            } else if (validation != null && !validation.accepted()) {
                mark(phaseFailures, firstRejectReason(validation));
            } else {
                mark(phaseFailures, "writer_failed");
            }
        }

        String datasetPath = datasetFile == null ? null : datasetFile.getPath();
        UawAutolearnQualityTracker.CycleDiagnostics cycle = qualityTracker.finishCycle(
                sessionId, attempted, accepted, aborted, datasetPath);
        AutoLearnCycleResult result = summarizeCycle(
                attempted,
                accepted,
                aborted,
                datasetPath,
                cycle.errorRateWindow(),
                cycle.tuningDelta(),
                cycle.trainAllowed(),
                cycle.topProblem(),
                cycle.trainDecision(),
                phaseFailures);
        traceCycleSummary(result, cycleStartedNs, aborted, sessionId);
        recordHandoffCycle(sessionId, datasetPath, result, cycle);
        return result;
    }

    private List<String> buildSeeds() {
        int desired = Math.max(0, props.getBatchSize());
        LinkedHashSet<String> out = new LinkedHashSet<>();
        boolean staticSyntheticOnly = externalStaticSyntheticOnly();

        // 1) Consume recent "gap" events first (best-effort).
        if (!staticSyntheticOnly) try {
            int gapTake = Math.min(3, Math.max(1, desired));
            for (int i = 0; i < gapTake; i++) {
                if (gapLogger == null)
                    break;
                var evt = gapLogger.poll();
                if (evt == null || evt.isEmpty())
                    break;
                String q = evt.get().getQuery();
                if (q != null && !q.isBlank()) {
                    out.add("내부 자동학습: (gap) " + q.trim());
                }
            }
        } catch (Exception ignore) {
            traceSuppressed("seed.knowledgeGap");
        }

        // 2) Curiosity-based knowledge gap (optional).
        if (!staticSyntheticOnly) try {
            CuriosityTriggerService svc = (curiosity == null) ? null : curiosity.getIfAvailable();
            if (svc != null) {
                svc.findKnowledgeGap().ifPresent(gap -> {
                    String q = gap.initialQuery();
                    if (q != null && !q.isBlank()) {
                        out.add("내부 자동학습: (curiosity) " + q.trim());
                    }
                });
            }
        } catch (Exception ignore) {
            traceSuppressed("seed.curiosity");
        }

        // 3) Prefer configured seeds, otherwise built-in defaults.
        List<String> baseSeeds = props.getDefaultSeeds();
        if (baseSeeds == null || baseSeeds.isEmpty()) {
            baseSeeds = defaultSeeds();
        }

        // 4) Sample seeds from real user chat history if enabled (also can fill with
        // baseSeeds depending on cfg).
        if (!staticSyntheticOnly) try {
            if (seedSampler != null) {
                out.addAll(seedSampler.sampleSeeds(props, desired, baseSeeds));
            }
        } catch (Exception ignore) {
            traceSuppressed("seed.sampler");
        }

        // 5) Final fallback: static seeds if allowed.
        boolean allowStatic = true;
        try {
            if (props.getSeed() != null) {
                allowStatic = props.getSeed().isAllowStaticFallback();
            }
        } catch (Exception ignore) {
            traceSuppressed("seed.staticFallbackFlag");
        }
        if (allowStatic) {
            out.addAll(baseSeeds);
        }

        return new ArrayList<>(out);
    }

    private ChatRequestDto buildAutolearnRequest(String question) {
        int searchQueries = intProperty("uaw.autolearn.strict.search-queries", 12);
        int maxSources = intProperty("uaw.autolearn.strict.max-sources", 12);
        return ChatRequestDto.builder()
                .message(question)
                .model(autolearnStrictModel())
                .maxTokens(intProperty("uaw.autolearn.strict.max-tokens", 1024))
                .memoryMode(property("uaw.autolearn.strict.memory-mode", "ephemeral"))
                .domainProfile(property("uaw.autolearn.strict.domainProfile.general", "jul14"))
                .webProviders(parseCsv(property("uaw.autolearn.strict.web-providers", "NAVER,BRAVE")))
                .useWebSearch(true)
                .useRag(true)
                .useVerification(true)
                .officialSourcesOnly(true)
                .precisionSearch(true)
                .accumulation(false)
                .searchMode(resolveSearchMode(property("uaw.autolearn.strict.search-mode", null), searchQueries))
                .searchQueries(searchQueries)
                .webTopK(maxSources)
                .temperature(doubleProperty("uaw.autolearn.strict.temperature", 0.2d))
                .build();
    }

    private ChatRequestDto clampExternalQuotaRequest(ChatRequestDto request) {
        if (request == null || props == null || props.getExternalQuota() == null) {
            return request;
        }
        UawAutolearnProperties.ExternalQuota quota = props.getExternalQuota();
        if (!ExternalFreeModelPolicy.isExternalRoute(quota, request.getModel())) {
            return request;
        }
        int perCallCap = quota.getMaxOutputTokensPerCall();
        int current = request.getMaxTokens() == null || request.getMaxTokens() <= 0
                ? (perCallCap <= 0 ? 512 : perCallCap)
                : request.getMaxTokens();
        int clamped = perCallCap <= 0 ? current : Math.min(current, perCallCap);
        return request.toBuilder()
                .maxTokens(clamped)
                .useWebSearch(false)
                .useRag(false)
                .useVerification(false)
                .searchMode(SearchMode.OFF)
                .temperature(Math.min(request.getTemperature() == null ? 0.2d : request.getTemperature(), 0.2d))
                .build();
    }

    private boolean externalStaticSyntheticOnly() {
        if (props == null || props.getExternalQuota() == null) {
            return false;
        }
        UawAutolearnProperties.ExternalQuota quota = props.getExternalQuota();
        return ExternalFreeModelPolicy.isExternalRoute(
                quota,
                property("uaw.autolearn.strict.model", null))
                && ExternalFreeModelPolicy.isStaticSyntheticOnly(quota);
    }

    private String autolearnStrictModel() {
        String configured = trimToNull(property("uaw.autolearn.strict.model", null));
        return configured == null ? "llmrouter.light" : configured;
    }

    private String externalPrivacyBlockReason(String requestedModel, String seed) {
        if (props == null || props.getExternalQuota() == null) {
            return "";
        }
        UawAutolearnProperties.ExternalQuota quota = props.getExternalQuota();
        if (!ExternalFreeModelPolicy.isExternalRoute(quota, requestedModel)
                || !ExternalFreeModelPolicy.isStaticSyntheticOnly(quota)) {
            return "";
        }
        String q = seed == null ? "" : seed;
        if (q.contains("(gap)") || q.contains("(curiosity)")) {
            return ExternalFreeModelPolicy.PRIVACY_BLOCK_REASON;
        }
        return "";
    }

    private GuardContext installAutolearnGuardContext(String question,
            String sessionId,
            PreemptionToken token,
            long deadlineNanos) {
        GuardContext previous = null;
        try {
            previous = GuardContextHolder.get();
        } catch (Throwable ignore) {
            traceSuppressed("guardContext.read");
        }
        GuardContext ctx = previous == null ? GuardContext.defaultContext() : previous.copy();
        ctx.setUserQuery(question);
        ctx.setOfficialOnly(true);
        ctx.putPlanOverride("uaw.autolearn", true);
        ctx.putPlanOverride("uaw.autolearn.pipeline", "service-direct");
        ctx.putPlanOverride("uaw.autolearn.sessionId", sessionId);
        ctx.putPlanOverride("uaw.autolearn.deadlineNanos", deadlineNanos);
        BooleanSupplier supplier = () -> token != null && token.shouldAbort();
        ctx.putPlanOverride("uaw.autolearn.preemptionSupplier", supplier);
        GuardContextHolder.set(ctx);
        return previous;
    }

    private static void restoreGuardContext(GuardContext previous) {
        try {
            if (previous != null) {
                GuardContextHolder.set(previous);
            } else {
                GuardContextHolder.clear();
            }
        } catch (Throwable ignore) {
            traceSuppressed("guardContext.restore");
        }
    }

    private static List<String> defaultSeeds() {
        return Arrays.asList(
                "내부 자동학습: 이 시스템의 RAG 파이프라인을 한 문단으로 요약해줘.",
                "내부 자동학습: citation(근거)이 왜 중요한지 설명해줘.",
                "내부 자동학습: 검색 결과가 부족할 때 어떤 안전장치를 써야 하나?");
    }

    private OpenCodeFreeQuotaGuard externalQuotaGuard() {
        try {
            return externalQuotaGuard == null ? null : externalQuotaGuard.getIfAvailable();
        } catch (Exception ignore) {
            traceSuppressed("externalQuotaGuard.resolve");
            return null;
        }
    }

    private static int safeLength(String q) {
        return q == null ? 0 : q.length();
    }

    private static String queryHash(String q) {
        return sampleHash(q, "", "");
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void traceSuppressed(String stage) {
        log.debug("[UAW] suppressed stage={}",
                com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown"));
    }

    private static void resetTrainingTrace() {
        RagControlRuntimeAdapter.capturePresentationInput(null);
        TraceStore.put("uaw.autolearn.ragControl.present", null);
        TraceStore.put("uaw.autolearn.ragControl.hold", null);
        TraceStore.put("uaw.autolearn.ragControl.failureClass", null);
        TraceStore.put("uaw.autolearn.ragControl.errorType", null);
        TraceStore.put("ragControl.learning.boundary", null);
        TraceStore.put("ragControl.learning.action", null);
        TraceStore.put("ragControl.learning.rolloutMode", null);
        TraceStore.put("ragControl.learning.holdWrites", null);
        TraceStore.put("ragControl.learning.shadowOnly", null);
        TraceStore.put("ragControl.learning.failureClass", null);
        TraceStore.put("ragControl.learning.errorType", null);
        TraceStore.put("selfask.3way.api.disabledReason", null);
        TraceStore.put("selfask.3way.api.provider", null);
        TraceStore.put("selfask.3way.events", null);
        TraceStore.put("selfask.3way.requery.confirmed", null);
        TraceStore.put("llmrouter.api.disabledReason", null);
        TraceStore.put("llmrouter.api.provider", null);
        TraceStore.put("web.naver.disabledReasonCanonical", null);
        TraceStore.put("web.brave.disabledReasonCanonical", null);
        TraceStore.put("web.serpapi.disabledReasonCanonical", null);
        TraceStore.put("web.tavily.disabledReasonCanonical", null);
        TraceStore.put("rag.eval.providerDisabledSignals", null);
        TraceStore.put("web.naver.returnedCount", null);
        TraceStore.put("web.naver.afterFilterCount", null);
        TraceStore.put("web.brave.returnedCount", null);
        TraceStore.put("web.brave.afterFilterCount", null);
        TraceStore.put("web.serpapi.returnedCount", null);
        TraceStore.put("web.serpapi.afterFilterCount", null);
        TraceStore.put("web.tavily.returnedCount", null);
        TraceStore.put("web.tavily.afterFilterCount", null);
        TraceStore.put("webSearch.returnedCount", null);
        TraceStore.put("webSearch.afterFilterCount", null);
        TraceStore.put("rag.returnedCount", null);
        TraceStore.put("rag.afterFilterCount", null);
        TraceStore.put("cfvm.kalloc.key", null);
        TraceStore.put("cfvm.kalloc.arm", null);
        TraceStore.put("cfvm.reward.postValidation", null);
        TraceStore.put("cfvm.kalloc.postValidationFeedback", null);
        TraceStore.put("cfvm.reward.requeryPenalty", null);
        TraceStore.put("cfvm.reward.anomalyPenalty", null);
        TraceStore.put("learning.validation.questionType", null);
        TraceStore.put("learning.validation.selfAskLaneCoverage", null);
        TraceStore.put("learning.validation.refutabilityScore", null);
        TraceStore.put("learning.validation.riskScore", null);
        TraceStore.put("learning.validation.causalNeedScore", null);
        TraceStore.put("learning.validation.contradictionScore", null);
        TraceStore.put("learning.validation.contradictionCause", null);
        TraceStore.put("learning.validation.requeryRequired", null);
        TraceStore.put("learning.validation.requeryConfirmed", null);
        TraceStore.put("learning.validation.contaminationScore", null);
        TraceStore.put("learning.validation.legacyContextScore", null);
        TraceStore.put("learning.validation.sampleScore", null);
        TraceStore.put("learning.validation.decision", null);
        TraceStore.put("learning.validation.rejectReasons", null);
        TraceStore.put("learning.validation.contextContaminationScore", null);
        TraceStore.put("learning.threshold.sampleScoreMin", null);
        TraceStore.put("learning.threshold.contaminationMax", null);
        TraceStore.put("learning.threshold.contextContaminationMax", null);
        TraceStore.put("learning.threshold.contradictionMax", null);
        TraceStore.put("learning.threshold.requeryPenalty", null);
        TraceStore.put("learning.threshold.tuningDelta", null);
        TraceStore.put("learning.anomaly.flags", null);
        TraceStore.put("learning.anomaly.spike", null);
        TraceStore.put("learning.anomaly.drift", null);
        TraceStore.put("learning.feedback.vectorDecision", null);
        TraceStore.put("learning.metrics.externalError", null);
        TraceStore.put("learning.error.hotspot", null);
        TraceStore.put("overdrive.contradiction.mean", null);
        TraceStore.put("overdrive.reason", null);
        TraceStore.put("extremez.risk.contradictionScore", null);
        TraceStore.put("extremez.risk.contradictionMean", null);
        TraceStore.put("extremez.risk.primaryCause", null);
        TraceStore.put("extremez.activation.reason", null);
        TraceStore.put("rag.contradiction.score", null);
    }

    private static AutoLearnCycleResult summarizeCycle(int attempted,
            int accepted,
            boolean aborted,
            String datasetPath,
            double errorRateWindow,
            double thresholdTuningDelta,
            boolean trainAllowed,
            String topProblem,
            String trainDecision,
            Map<String, Integer> phaseFailures) {
        int errorCount = failureCount(phaseFailures);
        if (errorCount == 0) {
            errorCount = Math.max(0, attempted - accepted);
        }
        double errorRate = attempted <= 0 ? 0.0d : clamp01(errorCount / (double) attempted);
        String dominantFailure = dominant(phaseFailures);
        return new AutoLearnCycleResult(
                attempted,
                accepted,
                aborted,
                datasetPath,
                errorRateWindow,
                thresholdTuningDelta,
                trainAllowed,
                topProblem,
                trainDecision,
                errorCount,
                errorRate,
                dominantFailure,
                diagnose(dominantFailure),
                phaseFailures);
    }

    private static void traceCycleSummary(AutoLearnCycleResult result,
                                      long cycleStartedNs,
                                      boolean aborted,
                                      String sessionId) {
        try {
            if (result == null) {
                return;
            }
            long elapsedMs = Math.max(0L, (System.nanoTime() - cycleStartedNs) / 1_000_000L);
            String status = aborted ? "aborted" : "completed";
            TraceStore.put("learning.loop.errorCount", result.errorCount());
            TraceStore.put("learning.loop.errorRate", result.errorRate());
            TraceStore.put("learning.loop.dominantFailure", result.dominantFailure());
            TraceStore.put("learning.loop.diagnosis", result.diagnosis());
            TraceStore.put("learning.loop.phaseFailures", result.phaseFailures());
            // Cycle boundary fields: one record per runCycle call so a stalled
            // or failed cycle is locatable without per-sample noise.
            TraceStore.put("learning.loop.cycle.status", status);
            TraceStore.put("learning.loop.cycle.elapsedMs", elapsedMs);
            if (sessionId != null && !sessionId.isBlank()) {
                TraceStore.put("learning.loop.cycle.sessionIdHash12",
                        com.example.lms.trace.SafeRedactor.hash12(sessionId));
            }
            com.example.lms.search.RequestTrace.emit("uaw.autolearn", "cycle",
                    "status=" + status + ";elapsedMs=" + elapsedMs);
        } catch (Exception ignore) {
            traceSuppressed("cycleSummary.trace");
        }
    }

    private void recordHandoffSample(String sessionId,
                                     String question,
                                     ChatResult result,
                                     int evidenceCount,
                                     UawDatasetWriter.TrainingMetadata metadata,
                                     boolean writerOk) {
        try {
            UawLearningAgentHandoffWriter writer = handoffWriter == null ? null : handoffWriter.getIfAvailable();
            if (writer == null || result == null) {
                if (writer == null) {
                    TraceStore.put("uaw.handoffWriter.absent", true);
                }
                return;
            }
            writer.recordSample(
                    sessionId,
                    props == null || props.getDataset() == null ? "uaw-train" : props.getDataset().getName(),
                    question,
                    result.content(),
                    result.modelUsed(),
                    evidenceCount,
                    metadata,
                    writerOk);
        } catch (Exception e) {
            TraceStore.put("uaw.agent.handoff.status", "sample_write_failed");
            TraceStore.put("uaw.agent.handoff.error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
        }
    }

    private void recordHandoffSkippedSample(String sessionId,
                                            String question,
                                            String answer,
                                            String modelUsed,
                                            int evidenceCount,
                                            UawDatasetWriter.TrainingMetadata metadata,
                                            String outcome,
                                            String failureReason) {
        try {
            UawLearningAgentHandoffWriter writer = handoffWriter == null ? null : handoffWriter.getIfAvailable();
            if (writer == null) {
                TraceStore.put("uaw.handoffWriter.absent", true);
                return;
            }
            writer.recordSkippedSample(
                    sessionId,
                    props == null || props.getDataset() == null ? "uaw-train" : props.getDataset().getName(),
                    question,
                    answer,
                    modelUsed,
                    evidenceCount,
                    metadata,
                    outcome,
                    failureReason);
        } catch (Exception e) {
            TraceStore.put("uaw.agent.handoff.status", "sample_write_failed");
            TraceStore.put("uaw.agent.handoff.error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
        }
    }

    private void recordHandoffHeldSample(String sessionId,
                                         String question,
                                         String answer,
                                         String modelUsed,
                                         int evidenceCount) {
        try {
            UawLearningAgentHandoffWriter writer = handoffWriter == null ? null : handoffWriter.getIfAvailable();
            if (writer == null) {
                TraceStore.put("uaw.handoffWriter.absent", true);
                return;
            }
            String datasetName = props == null || props.getDataset() == null
                    ? "uaw-train"
                    : props.getDataset().getName();
            writer.recordHeldSample(
                    handoffHash((question == null ? "" : question)
                            + '\0' + (answer == null ? "" : answer)
                            + '\0' + (modelUsed == null ? "" : modelUsed)),
                    handoffHash(sessionId),
                    handoffHash(datasetName),
                    handoffHash(question),
                    handoffHash(answer),
                    handoffHash(modelUsed),
                    evidenceCount);
        } catch (Exception e) {
            TraceStore.put("uaw.agent.handoff.status", "sample_write_failed");
            TraceStore.put("uaw.agent.handoff.error",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
        }
    }

    private static String handoffHash(String value) {
        String hash = com.example.lms.trace.SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private void recordHandoffCycle(String sessionId,
                                    String datasetPath,
                                    AutoLearnCycleResult result,
                                    UawAutolearnQualityTracker.CycleDiagnostics cycle) {
        try {
            UawLearningAgentHandoffWriter writer = handoffWriter == null ? null : handoffWriter.getIfAvailable();
            if (writer != null) {
                writer.recordCycle(sessionId, datasetPath, result, cycle);
            } else {
                TraceStore.put("uaw.handoffWriter.absent", true);
            }
        } catch (Exception e) {
            TraceStore.put("uaw.agent.handoff.status", "cycle_write_failed");
            TraceStore.put("uaw.agent.handoff.error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
        }
    }

    private static void mark(Map<String, Integer> failures, String reason) {
        if (failures == null || reason == null || reason.isBlank()) {
            return;
        }
        failures.merge(reason.trim(), 1, Integer::sum);
    }

    private static int failureCount(Map<String, Integer> failures) {
        if (failures == null || failures.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Integer value : failures.values()) {
            count += value == null ? 0 : Math.max(0, value);
        }
        return count;
    }

    private static String firstRejectReason(LearningSampleValidationMetadata validation) {
        if (validation == null || validation.rejectReasons() == null || validation.rejectReasons().isEmpty()) {
            return "validation_rejected";
        }
        for (String reason : validation.rejectReasons()) {
            if (reason != null && !reason.isBlank()) {
                return reason.trim();
            }
        }
        return "validation_rejected";
    }

    private static String dominant(Map<String, Integer> failures) {
        if (failures == null || failures.isEmpty()) {
            return "none";
        }
        String key = "none";
        int max = 0;
        for (Map.Entry<String, Integer> entry : failures.entrySet()) {
            int value = entry.getValue() == null ? 0 : entry.getValue();
            if (value > max) {
                max = value;
                key = entry.getKey();
            }
        }
        return key == null || key.isBlank() ? "none" : key;
    }

    private static String diagnose(String dominantFailure) {
        return switch (dominantFailure == null ? "" : dominantFailure) {
            case "chat_service_exception" -> "chat_service_or_guard_context_error";
            case "empty_result" -> "llm_answer_generation_empty";
            case "insufficient_evidence" -> "evidence_search_gap";
            case "low_context_diversity" -> "retrieval_context_collapse";
            case "provider_disabled" -> "provider_or_api_key_disabled";
            case "final_gate_failed" -> "evidence_final_gate_failed";
            case "sample_score_threshold", "sample_score_below_threshold" -> "sample_quality_below_dynamic_threshold";
            case "context_contamination_threshold", "contamination_risk" -> "context_contamination_guard_rejected";
            case "legacy_context_risk" -> "legacy_context_contamination_guard_rejected";
            case "requery_unconfirmed", "unconfirmed_high_risk_requery" -> "requery_confirmation_missing";
            case "validation_rejected" -> "sample_threshold_or_contamination_rejected";
            case "writer_failed" -> "dataset_writer_or_training_filter_rejected";
            case "user_preempted" -> "user_returned_preemption";
            case "daily_call_limit", "daily_token_limit", "cycle_call_limit", "rate_limit_cooldown",
                    "non_free_model", "provider_host_mismatch", "missing_route_config", "route_disabled",
                    "missing_opencode_api_key", "opencode_api_key_conflict", "quota_state_persist_failed",
                    "external_privacy_block", "external_free_model_curate_only" ->
                    "external_quota_guard_denied";
            case "", "none" -> "";
            default -> "autolearn_phase_failure:" + dominantFailure;
        };
    }

    private static int afterFilterCount(int evidenceCount) {
        long total = TraceStore.getLong("web.naver.afterFilterCount")
                + TraceStore.getLong("web.brave.afterFilterCount")
                + TraceStore.getLong("web.serpapi.afterFilterCount")
                + TraceStore.getLong("web.tavily.afterFilterCount")
                + TraceStore.getLong("webSearch.afterFilterCount")
                + TraceStore.getLong("rag.afterFilterCount");
        if (traceHasAny("web.naver.afterFilterCount", "web.brave.afterFilterCount",
                "web.serpapi.afterFilterCount", "web.tavily.afterFilterCount",
                "webSearch.afterFilterCount", "rag.afterFilterCount")) {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, total));
        }
        if (total <= 0L) {
            return Math.max(0, evidenceCount);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static boolean traceHasAny(String... keys) {
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            if (TraceStore.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    private static String trainingProvider() {
        String p = firstNonBlank(
                TraceStore.getString("selfask.3way.api.provider"),
                TraceStore.getString("llmrouter.api.provider"));
        if (!p.isBlank()) {
            return p;
        }
        return TraceStore.get("selfask.3way.events") == null ? "local" : "mixed";
    }

    private static String safeReason(String reason) {
        return com.example.lms.trace.SafeRedactor.traceLabelOrFallback(reason, "");
    }

    private boolean shouldHoldLearningWrites(
            ChatResult result,
            int evidenceCount,
            int afterFilterCount,
            LearningSampleValidationMetadata validation,
            RagControlRuntimeAdapter.RuntimeInput presentationInput) {
        boolean hardGuardHeld = presentationInput != null
                && presentationInput.ragRequested()
                && presentationInput.hardGuardHeld();
        boolean finalBoundaryReached = presentationInput != null
                && presentationInput.ragRequested()
                && presentationInput.finalBoundaryReached();
        try {
            RagControlLearningGate gate = ragControlLearningGate == null
                    ? null
                    : ragControlLearningGate.getIfAvailable();
            if (gate == null) {
                TraceStore.put("uaw.autolearn.ragControl.present", false);
                TraceStore.put("uaw.autolearn.ragControl.hold", true);
                TraceStore.put("uaw.autolearn.ragControl.failureClass", "learning_gate_unavailable");
                return true;
            }
            TraceStore.put("uaw.autolearn.ragControl.present", true);
            TraceStore.put("uaw.autolearn.ragControl.failureClass", null);
            RagControlLearningGate.Decision decision = gate.evaluate(
                    RagControlLearningGate.Boundary.UAW_PRE_WRITE,
                    new RagControlRuntimeAdapter.RuntimeInput(
                            true,
                            Math.max(0, evidenceCount),
                            Math.max(0, afterFilterCount),
                            result == null || result.content() == null || result.content().isBlank(),
                            validation != null,
                            validation != null && validation.accepted(),
                            hardGuardHeld,
                            finalBoundaryReached));
            TraceStore.put("uaw.autolearn.ragControl.hold", decision.holdWrites());
            return decision.holdWrites();
        } catch (RuntimeException controlFailure) {
            TraceStore.put("uaw.autolearn.ragControl.present", false);
            TraceStore.put("uaw.autolearn.ragControl.hold", true);
            TraceStore.put("uaw.autolearn.ragControl.failureClass", "learning_gate_failed");
            TraceStore.put("uaw.autolearn.ragControl.errorType",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(
                            controlFailure.getClass().getSimpleName(), "runtime_exception"));
            return true;
        }
    }

    private LearningSampleValidationMetadata feedbackCfvmPostValidation(LearningSampleValidationMetadata validation) {
        if (validation == null || cfvmKAllocationTuner == null) {
            return validation;
        }
        double requeryPenalty = validation.thresholds().requeryPenalty();
        double anomalyPenalty = anomalyPenalty(validation.anomalies());
        double reward = validation.sampleScore()
                - 0.25d * validation.contextContaminationScore()
                - requeryPenalty
                - anomalyPenalty;
        reward = clamp01(reward);
        LearningSampleValidationMetadata updated = validation.withFeedback(
                new LearningSampleValidationMetadata.Feedback(reward, validation.feedback().vectorDecision()));
        TraceStore.put("cfvm.reward.postValidation", reward);
        TraceStore.put("cfvm.reward.requeryPenalty", requeryPenalty);
        TraceStore.put("cfvm.reward.anomalyPenalty", anomalyPenalty);

        Object keyObj = TraceStore.get("cfvm.kalloc.key");
        Object armObj = TraceStore.get("cfvm.kalloc.arm");
        if (keyObj == null || armObj == null) {
            return updated;
        }
        String key = String.valueOf(keyObj).trim();
        String arm = String.valueOf(armObj).trim();
        if (key.isBlank() || arm.isBlank()) {
            return updated;
        }
        CfvmKAllocationTuner tuner = cfvmKAllocationTuner.getIfAvailable();
        if (tuner == null) {
            return updated;
        }
        try {
            tuner.feedback(key, arm, reward);
            TraceStore.put("cfvm.kalloc.postValidationFeedback", true);
        } catch (Exception e) {
            log.debug("[UAW] CFVM post-validation feedback skipped. errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
        return updated;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static double contextDiversity(Set<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }
        HashSet<String> unique = new HashSet<>();
        int total = 0;
        for (String item : evidence) {
            String normalized = item == null ? "" : item.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.length() < 3) {
                unique.add(normalized);
                total++;
                continue;
            }
            for (int i = 0; i <= normalized.length() - 3; i++) {
                unique.add(normalized.substring(i, i + 3));
                total++;
            }
        }
        if (total <= 0) {
            return 0.0d;
        }
        return Math.min(1.0d, unique.size() / (double) total);
    }

    private String property(String key, String fallback) {
        if (env == null || key == null || key.isBlank()) {
            return fallback;
        }
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private int intProperty(String key, int fallback) {
        if (env == null) {
            return fallback;
        }
        Integer value = env.getProperty(key, Integer.class);
        return value == null ? fallback : value;
    }

    private Double doubleProperty(String key, double fallback) {
        if (env == null) {
            return fallback;
        }
        Double value = env.getProperty(key, Double.class);
        return value == null ? fallback : value;
    }

    private static SearchMode resolveSearchMode(String raw, int searchQueries) {
        if (raw != null && !raw.isBlank()) {
            try {
                return SearchMode.valueOf(raw.trim());
            } catch (Exception ignore) {
                traceSuppressed("searchMode.parse");
            }
        }
        return searchQueries >= 2 ? SearchMode.FORCE_DEEP : SearchMode.FORCE_LIGHT;
    }

    private static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (part != null && !part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return left.equalsIgnoreCase(right);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double anomalyPenalty(LearningSampleValidationMetadata.Anomalies anomalies) {
        if (anomalies == null || anomalies.flags().isEmpty()) {
            return 0.0d;
        }
        double penalty = 0.10d;
        if (anomalies.spike()) {
            penalty += 0.05d;
        }
        if (anomalies.drift()) {
            penalty += 0.05d;
        }
        return clamp01(penalty);
    }

    private static String sampleHash(String question, String answer, String modelUsed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((question == null ? "" : question).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update((answer == null ? "" : answer).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update((modelUsed == null ? "" : modelUsed).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(8, digest.length); i++) {
                out.append(String.format("%02x", digest[i] & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            traceSuppressed("sampleHash.digest");
            return "hash_unavailable";
        }
    }

    private void reinforceAcceptedSample(String sessionId,
                                         String question,
                                         String answer,
                                         LearningSampleValidationMetadata validation) {
        try {
            if (props == null
                    || props.getMemoryReinforcement() == null
                    || !props.getMemoryReinforcement().isEnabled()) {
                return;
            }
            MemoryReinforcementService svc = memoryReinforcementService == null
                    ? null
                    : memoryReinforcementService.getIfAvailable();
            if (svc == null) {
                return;
            }
            if (answer != null && "정보 없음".equals(answer.trim())) {
                return;
            }
            double score = validation == null ? 0.0d : validation.sampleScore();
            svc.reinforceWithSnippet(sessionId, question, answer, "UAW_AUTOLRN", score, validation);
        } catch (Exception e) {
            log.debug("[UAW] memory reinforcement skipped. errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    /**
     * 쿼리가 시스템 기본 시드인지 확인합니다.
     * gap/curiosity/사용자 유래 프롬프트가 아닌 정적 내부 시드만 true를 반환합니다.
     */
    private static boolean isStaticInternalSeed(String q) {
        if (q == null || q.isBlank())
            return false;
        // defaultSeeds()에 정의된 정적 시드는 "내부 자동학습:" 접두사를 가짐
        // gap/curiosity는 "(gap)" 또는 "(curiosity)" 태깅이 있으므로 제외
        if (!q.startsWith("내부 자동학습:"))
            return false;
        return !q.contains("(gap)") && !q.contains("(curiosity)");
    }
}
