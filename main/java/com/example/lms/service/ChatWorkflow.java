package com.example.lms.service;
import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.agent.context.AgentPipelineHealthController;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.llm.LlmFastBailoutException;
import com.example.lms.llm.RequestedModelTimeoutPolicy;
import com.example.lms.llm.TimedChatModelCaller;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.LlmConfigurationException;
import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.example.lms.llm.OpenAiCompatBaseUrl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.example.lms.infra.resilience.IrregularityProfiler;
import com.example.lms.infra.resilience.BypassRoutingService;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.FinalizedMemoryPersistence;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeChatMessageLog;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.HashUtil;
import com.example.lms.util.QueryTypeHeuristics;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugProbeType;
import com.abandonware.ai.agent.integrations.TextUtils;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.prompt.PromptContext;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.guard.ConversationFrameResolver;
import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.domain.enums.MemoryGateProfile;
import com.example.lms.learning.chat.LearningSignal;
import com.example.lms.learning.chat.LearningSignalKind;
import com.example.lms.learning.context.RagLearningSupportContext;
import com.example.lms.learning.context.LearningContextSourceService;
import com.example.lms.nlp.QueryDomainClassifier;
import com.example.lms.orchestration.OrchStageKeys;
import com.example.lms.orchestration.StagePolicyProperties;
import com.example.lms.probe.needle.NeedleProbeEngine;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.InfoFailurePatterns;
import com.example.lms.service.routing.RouteSignal;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.service.rag.EvidenceAnswerComposer;
import com.example.lms.service.rag.RagEvidenceAttributionService;
import com.example.lms.service.verbosity.VerbosityDetector;
import com.example.lms.service.verbosity.VerbosityProfile;
import com.example.lms.service.verbosity.SectionSpecGenerator;
import com.example.lms.service.answer.LengthVerifierService;
import com.example.lms.service.answer.AnswerExpanderService;
import com.example.lms.service.postprocess.FinalAnswerPostProcessor;
import com.example.lms.service.postprocess.OutputSanitizer;
import com.example.lms.util.HtmlTextUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.HttpException;
import com.example.lms.search.QueryHygieneFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.RerankKnobResolver;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import com.example.lms.service.disambiguation.QueryDisambiguationService;
import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.service.subject.SubjectAnalysis;
import com.example.lms.service.rag.detector.UniversalDomainDetector;
import com.example.lms.service.strategy.DomainStrategyFactory;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.MemoryReinforcementService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import java.util.stream.Collectors;
import dev.langchain4j.data.message.UserMessage;
import com.example.lms.service.rag.LangChainRAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.CollectionUtils;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import com.example.lms.service.ChatResult;
import java.util.regex.Pattern;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import com.example.lms.util.MLCalibrationUtil;
import com.example.lms.util.FutureTechDetector;
import com.example.lms.search.SmartQueryPlanner;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.search.probe.NeedleContribution;
import com.example.lms.search.probe.NeedleContributionEvaluator;
import com.example.lms.search.probe.NeedleOutcomeRewarder;
import com.example.lms.search.probe.NeedleProbeProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.MlaBreadcrumb;
import com.example.lms.service.routing.plan.RoutingPlanService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.AttachmentService;
import com.example.lms.artplate.NineArtPlateGate;
import com.example.lms.artplate.ArtPlateSpec;
import com.example.lms.artplate.PlateContext;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import static com.example.lms.service.rag.LangChainRAGService.META_SID;
import java.util.function.Function;

import com.example.lms.service.FactVerifierService; // 寃利??쒕퉬??二쇱엯

/* ---------- LangChain4j ---------- */
import java.util.stream.Stream; // buildUnifiedContext ?ъ슜

// === Modularisation components (extracted from ChatService) ===

/* ---------- RAG ---------- */

import com.example.lms.transform.QueryTransformer;
//  hybrid retrieval content classes
import dev.langchain4j.data.document.Metadata; // [HARDENING]
import java.util.Map; // [HARDENING]

// (dedup) Qualifier already imported above
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment; // ??for evidence regen

/**
 * 以묒븰 ?덈툕 - OpenAI-Java 쨌 LangChain4j 쨌 RAG ?듯빀. (v7.2, RAG ?곗꽑 ?⑥튂 ?곸슜)
 * <p>
 * - LangChain4j 1.0.1 API ???
 * - "??RAG ?곗꽑" 4-Point ?⑥튂(?꾨＼?꾪듃 媛뺥솕 / 硫붿떆吏 ?쒖꽌 / RAG 湲몄씠 ?쒗븳 / ?붾쾭洹?濡쒓렇) 諛섏쁺
 * </p>
 *
 * <p>
 * 2024-08-06: ML 湲곕컲 蹂댁젙/蹂닿컯/?뺤젣/利앷컯 湲곕뒫???꾩엯?덉뒿?덈떎. ?덈줈???꾨뱶
 * {@code mlAlpha}, {@code mlBeta}, {@code mlGamma}, {@code mlMu},
 * {@code mlLambda} 諛?{@code mlD0} ? application.yml ?먯꽌 議곗젙????
 * ?덉뒿?덈떎. {@link MLCalibrationUtil} 瑜??ъ슜?섏뿬 LLM ?뚰듃 寃???먮뒗
 * 硫붾え由?媛뺥솕瑜??꾪븳 媛以묒튂瑜?怨꾩궛?????덉쑝硫? 蹂??덉젣?먯꽌??
 * {@link #reinforceAssistantAnswer(String, String, String)} ?댁뿉??
 * 臾몄옄??湲몄씠瑜?嫄곕━ d 濡??ъ슜?섏뿬 媛以묒튂 ?먯닔瑜?蹂댁젙?⑸땲??
 * ?ㅼ젣 ?ъ슜 ?쒖뿉???꾨찓?몄뿉 留욌뒗 d 媛믪쓣 ?낅젰??二쇱꽭??
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ChatWorkflow {
    private static final Logger log = LoggerFactory.getLogger(ChatWorkflow.class);
    private static final ObjectMapper OPENAI_COMPAT_MAPPER = new ObjectMapper();
    private static final ConversationFrameResolver FALLBACK_CONVERSATION_FRAME_RESOLVER =
            new ConversationFrameResolver();
    private static final List<String> HIGH_RISK_TERMS = List.of(
            "\uC9C4\uB2E8",
            "\uCC98\uBC29",
            "\uC99D\uC0C1",
            "\uBC95\uB960",
            "\uC18C\uC1A1",
            "\uD615\uB7C9",
            "\uD22C\uC790",
            "\uC218\uC775\uB960",
            "\uBCF4\uD5D8\uAE08");
    private static final List<String> KOREAN_DIRECT_LITERAL_ANSWER_DIRECTIVES = List.of(
            "\uB2F5\uBCC0",
            "\uB300\uB2F5",
            "\uB2F5\uD574",
            "\uC54C\uB824",
            "\uB9D0\uD574",
            "\uCD9C\uB825",
            "\uC368",
            "\uBD80\uD0C1",
            "\uBCF4\uC5EC");
    private static final Set<String> DIRECT_LITERAL_DIRECTIVE_TOKENS = Set.of(
            "answer",
            "respond",
            "please",
            "\uB2F5\uD574",
            "\uB2F5\uD574\uC918",
            "\uB300\uB2F5",
            "\uB300\uB2F5\uD574",
            "\uB2F5\uBCC0",
            "\uB2F5\uBCC0\uD574",
            "\uC54C\uB824",
            "\uC54C\uB824\uC918",
            "\uB9D0\uD574",
            "\uB9D0\uD574\uC918",
            "\uCD9C\uB825",
            "\uCD9C\uB825\uD574",
            "\uC368",
            "\uC368\uC918",
            "\uBD80\uD0C1",
            "\uBD80\uD0C1\uD574",
            "\uBCF4\uC5EC",
            "\uBCF4\uC5EC\uC918",
            "\uD574",
            "\uD574\uC918");
    private static final Set<String> DIRECT_LITERAL_GENERIC_OUTPUT_LABELS = Set.of(
            "token",
            "code",
            "value",
            "word");
    private static final List<String> KOREAN_ASK_LATER_VALUE_MARKERS = List.of(
            "\uBB3C\uC5B4\uBCF4\uB77C\uACE0",
            "\uBB3C\uC5B4\uBCF4\uB77C\uB358",
            "\uBB3C\uC5B4\uBCF8\uB2E4\uB358",
            "\uBB3C\uC5B4\uBCF8\uB2E4\uACE0 \uD55C",
            "\uBB3C\uC5B4\uBD10\uB2EC\uB77C\uACE0 \uD55C",
            "\uBB3C\uC5B4\uBD10 \uB2EC\uB77C\uACE0 \uD55C",
            "\uBB3C\uC5B4\uBD10\uB2EC\uB77C\uB358",
            "\uBB3C\uC5B4\uBD10 \uB2EC\uB77C\uB358",
            "\uBB3C\uC5B4\uBD10\uB2EC\uB7AC\uB358",
            "\uBB3C\uC5B4\uBD10 \uB2EC\uB7AC\uB358",
            "\uBB3C\uC5B4\uBD10\uB2EC\uB7AC\uC796\uC544",
            "\uBB3C\uC5B4\uBD10 \uB2EC\uB7AC\uC796\uC544",
            "\uB2F5\uD574\uB2EC\uB77C\uACE0 \uD588\uB358",
            "\uB2F5\uD574 \uB2EC\uB77C\uACE0 \uD588\uB358",
            "\uB2F5\uD558\uB77C\uACE0 \uD588\uB358",
            "\uB2F5\uD558\uB77C\uACE0\uD588\uB358",
            "\uB300\uB2F5\uD558\uB77C\uB358",
            "\uB300\uB2F5\uD558\uB77C\uACE0 \uD55C",
            "\uBB3C\uC5B4\uBCFC \uB54C",
            "\uBB3C\uC5B4\uBCFC\uB54C",
            "\uBB3C\uC5B4\uBCF4\uBA74");
    private static final List<String> KOREAN_NEXT_QUESTION_REFERENCE_MARKERS = List.of(
            "\uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C",
            "\uB2E4\uC74C\uC9C8\uBB38\uC5D0\uC11C",
            "\uB2E4\uC74C \uC9C8\uBB38",
            "\uB2E4\uC74C\uC9C8\uBB38",
            "\uD6C4\uC18D \uC9C8\uBB38\uC5D0\uC11C",
            "\uD6C4\uC18D\uC9C8\uBB38\uC5D0\uC11C",
            "\uD6C4\uC18D \uC9C8\uBB38",
            "\uD6C4\uC18D\uC9C8\uBB38");
    private static final List<String> KOREAN_FIRST_MESSAGE_REFERENCE_MARKERS = List.of(
            "\uB9E8 \uCC98\uC74C",
            "\uCC98\uC74C \uBCF4\uB0B8",
            "\uCC98\uC74C \uC785\uB825\uD55C",
            "\uCC98\uC74C \uC4F4",
            "\uCC98\uC74C \uB9D0\uD55C",
            "\uCCAB \uBC88\uC9F8",
            "\uCCAB\uBC88\uC9F8",
            "\uCCAB \uC9C8\uBB38",
            "\uCCAB\uC9C8\uBB38",
            "\uCCAB \uBA54\uC2DC\uC9C0",
            "\uCCAB\uBA54\uC2DC\uC9C0",
            "\uCC98\uC74C \uC9C8\uBB38",
            "\uCC98\uC74C \uBA54\uC2DC\uC9C0");
    private static final List<String> KOREAN_FIRST_MESSAGE_NOUN_MARKERS = List.of(
            "\uBB38\uC7A5",
            "\uC9C8\uBB38",
            "\uB0B4\uC6A9",
            "\uBA54\uC2DC\uC9C0");
    private static final List<String> KOREAN_FIRST_MESSAGE_SENT_ACTION_MARKERS = List.of(
            "\uBCF4\uB0B8",
            "\uB9D0\uD55C",
            "\uC785\uB825\uD55C",
            "\uC4F4");
    private static final List<String> KOREAN_REPEAT_REQUEST_MARKERS = List.of(
            "\uADF8\uB300\uB85C",
            "\uB2E4\uC2DC",
            "\uB9D0\uD574",
            "\uC54C\uB824",
            "\uBCF4\uC5EC",
            "\uB300\uB2F5",
            "\uB2F5\uD574");
    @Value("${openai.retry.max-attempts:0}")
    private int llmMaxAttempts;

    /** Opt-in process policy: reuse the existing strict path, including SDK and self-heal gates. */
    @Value("${openai.retry.strict-single-attempt:false}")
    private boolean llmStrictSingleAttempt;

    @Value("${openai.retry.backoff-ms:350}")
    private long llmBackoffMs;

    /**
     * Hard cap to avoid pathological retry+timeout accumulation.
     * <p>
     * 0 means "auto": cap ~= (timeout + backoff + small overhead).
     */
    @Value("${openai.retry.max-total-ms:0}")
    private long llmRetryMaxTotalMs;

    /**
     * When evidence is already present, a timeout is usually best handled by fast
     * evidence-only fallback.
     */
    @Value("${openai.retry.fast-bailout-on-timeout-with-evidence:true}")
    private boolean llmFastBailoutOnTimeoutWithEvidence;

    @Value("${openai.retry.fast-bailout-min-timeout-hits-with-evidence:1}")
    private int llmFastBailoutMinTimeoutHitsWithEvidence;

    @Value("${llm.timeout-seconds:12}")
    private int llmTimeoutSeconds;
    @Value("${llm.requested-model.timeout-seconds:180}")
    private int requestedModelTimeoutSeconds;
    @Value("${interaction.evidence-neutral.mode:off}")
    private String interactionPolicyMode;
    @Value("${conversation.harmony.mode:off}")
    private String conversationHarmonyMode;
    @Value("${llm.cost.trace.warn-input-tokens:12000}")
    private int costTraceWarnInputTokens;
    @Value("${llm.openai.endpoint-compat.fallback-to-completions:${nova.llm.endpoint-compat.fallback-to-completions:true}}")
    private boolean openAiFallbackToCompletions;

    @Value("${llm.openai.endpoint-compat.fallback-to-responses:${nova.llm.endpoint-compat.fallback-to-responses:true}}")
    private boolean openAiFallbackToResponses;

    @Value("${llm.openai.endpoint-compat.fallback-debug:${nova.llm.endpoint-compat.fallback-debug:false}}")
    private boolean openAiEndpointCompatDebug;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IrregularityProfiler irregularityProfiler;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RagEvidenceAttributionService ragEvidenceAttributionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.auth.DomainProfileLoader promptDomainProfiles;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DynamicContextCompressor promptContextCompressor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LearningContextSourceService learningContextSourceService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelRuntimeHealthTracker modelRuntimeHealthTracker;
    @Autowired(required = false)
    private ChatModelCatalogService chatModelCatalogService;

    // Optional: deep web search retriever (SmartQueryPlanner 湲곕컲)
    @Autowired(required = false)
    private AnalyzeWebSearchRetriever analyzeWebSearchRetriever;

    // Planner Nexus: auto-select plan id when absent
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.orchestration.WorkflowOrchestrator workflowOrchestrator;

    @Autowired(required = false)
    private com.example.lms.plan.PlanHintApplier planHintApplier;

    // Pipeline DSL (projection_agent.v1.yaml)
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanDslLoader planDslLoader;
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanPolicyMapper planPolicyMapper;
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanModelResolver planModelResolver;
    @Autowired(required = false)
    private com.example.lms.service.prompt.PromptAssetService promptAssetService;

    @Autowired(required = false)
    private com.example.lms.service.rag.ProjectionMergeService projectionMergeService;

    // (UAW) Needle probe: tiny 2-pass web detour when evidence quality is weak.
    @Autowired(required = false)
    private NeedleProbeEngine needleProbeEngine;

    // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_WIRE
    @Autowired(required = false)
    private StagePolicyProperties stagePolicy;
    private final @Qualifier("queryTransformer") QueryTransformer queryTransformer;
    @Autowired(required = false)
    private java.util.Map<String, CrossEncoderReranker> rerankers;
    // NOTE: CircuitBreaker/TimeLimiter beans were previously injected but never
    // used.
    // Keeping the retry logic simple and explicit avoids "phantom" dependencies.
    private final QueryDomainClassifier queryDomainClassifier = new QueryDomainClassifier();
    private final GuardProfileProps guardProfileProps;
    @Value("${abandonware.reranker.backend:embedding-model}")
    private String rerankBackend;

    @Value("${abandonware.reranker.onnx.runtime-enabled:false}")
    private boolean onnxRuntimeEnabled;

    @Value("${abandonware.reranker.onnx.allow-auto:false}")
    private boolean onnxAutoSelectionEnabled;

    /**
     * Determine the active reranker.
     *
     * Supports per-plan override via PlanHintApplier(meta/planOverrides):
     * - rerank_backend / rerank.backend: onnx-runtime|embedding-model|noop|auto
     * - onnx.enabled: false will prevent selecting the ONNX backend in auto mode
     *
     * Fail-soft:
     * - If no matching bean is present, falls back to embedding or any available
     * reranker.
     */
    private CrossEncoderReranker reranker(String backendOverride, Boolean onnxEnabledOverride,
            boolean crossEncoderEnabled) {
        if (rerankers == null || rerankers.isEmpty()) {
            return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
        }

        String backend = backendOverride;
        if (backend == null || backend.isBlank())
            backend = rerankBackend;
        backend = (backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT));

        boolean onnxAllowed = onnxRuntimeEnabled && onnxEnabledOverride != Boolean.FALSE;
        boolean onnxBreakerOpen = false;
        try {
            onnxBreakerOpen = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.RERANK_ONNX));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("rerank.onnxBreakerOpen", ignore);
        }

        boolean hasOnnx = rerankers.containsKey("onnxCrossEncoderReranker");
        boolean hasEmbedding = rerankers.containsKey("embeddingCrossEncoderReranker");
        boolean hasNoop = rerankers.containsKey("noopCrossEncoderReranker");
        boolean onnxUsable = crossEncoderEnabled && onnxAllowed && hasOnnx && !onnxBreakerOpen;
        boolean autoOnnxUsable = onnxAutoSelectionEnabled && onnxUsable;

        String key;
        switch (backend) {
            case "", "auto" -> {
                // aggressive auto:
                // - CE disabled -> noop
                // - ONNX usable -> ONNX
                // - else -> embedding (if present) else noop
                if (!crossEncoderEnabled) {
                    key = "noopCrossEncoderReranker";
                } else if (autoOnnxUsable) {
                    key = "onnxCrossEncoderReranker";
                } else if (hasEmbedding) {
                    key = "embeddingCrossEncoderReranker";
                } else {
                    key = "noopCrossEncoderReranker";
                }
            }
            case "onnx-runtime", "onnx" -> {
                if (onnxUsable) {
                    key = "onnxCrossEncoderReranker";
                } else {
                    // explicit onnx requested but not usable ??fail-soft fallback
                    key = hasEmbedding ? "embeddingCrossEncoderReranker"
                            : "noopCrossEncoderReranker";
                }
            }
            case "embedding-model", "embedding", "bi-encoder", "biencoder" -> key = "embeddingCrossEncoderReranker";
            case "noop", "none", "disabled" -> key = "noopCrossEncoderReranker";
            default -> key = "embeddingCrossEncoderReranker";
        }

        CrossEncoderReranker r = rerankers.get(key);
        if (r != null)
            return r;

        // final fallback order: embedding -> noop
        if (hasEmbedding)
            return rerankers.get("embeddingCrossEncoderReranker");
        if (hasNoop)
            return rerankers.get("noopCrossEncoderReranker");
        return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
    }

    /* ????????????????????????????? DI ?????????????????????????????? */

    private final ChatHistoryService chatHistoryService;
    private final EvidenceAwareGuard evidenceAwareGuard;
    private final QueryDisambiguationService disambiguationService;
    private final SubjectResolver subjectResolver;
    private final UniversalDomainDetector domainDetector;
    private final DomainStrategyFactory domainStrategyFactory;
    // The OpenAI-Java SDK has been removed. The application now exclusively uses
    // LangChain4j's ChatModel. To retain the original field order and ensure
    // Spring can still construct this class via constructor injection, we leave
    // a shim field here. It is never initialised or used.
    private final ChatModel chatModel; // 湲곕낯 LangChain4j ChatModel
    private final DynamicChatModelFactory dynamicChatModelFactory;
    private final KeyResolver keyResolver;
    private final @Qualifier("openaiWebClient") WebClient openaiWebClient;
    private final MemoryReinforcementService memorySvc;
    private final FactVerifierService verifier; // ???좉퇋 二쇱엯

    // - 泥댁씤 罹먯떆 ??젣
    // private final com.github.benmanes.caffeine.cache.LoadingCache<String,
    // ConversationalRetrievalChain> chains = /* ... */

    private final LangChainRAGService ragSvc;

    // ?대? ?덈뒗 DI ?꾨뱶 ?꾨옒履쎌뿉 異붽?
    private final WebSearchProvider webSearchProvider;
    private final QueryContextPreprocessor qcPreprocessor; // ???숈쟻 洹쒖튃 ?꾩쿂由ш린

    private final SmartQueryPlanner smartQueryPlanner; // 燧낉툘 NEW DI
    // Centralised planner facade (caching + stability)
    private final RoutingPlanService routingPlanService;
    // Search policy tuning (mode-based slicing/topK/expansion)
    private final SearchPolicyEngine searchPolicyEngine;
    // Needle probe (2-pass retrieval) orchestration
    private final NeedleProbeProperties needleProbeProperties;
    private final NeedleContributionEvaluator needleContributionEvaluator;
    private final NeedleOutcomeRewarder needleOutcomeRewarder;
    private final AuthorityScorer authorityScorer;
    // Inject Spring environment for guard checks. This allows reading
    // guard.evidence_regen.enabled.
    private final Environment env;
    // ?뵻 NEW: ?ㅼ감???꾩쟻쨌蹂닿컯쨌?⑹꽦湲?
    // ?뵻 ?⑥씪 ?⑥뒪 ?ㅼ??ㅽ듃?덉씠?섏쓣 ?꾪빐 泥댁씤 罹먯떆???쒓굅

    private final HybridRetriever hybridRetriever;
    private final WebSearchRetriever webSearchRetriever;
    private final NineArtPlateGate nineArtPlateGate;
    private final PromptBuilder promptBuilder;
    private final ConversationFrameResolver conversationFrameResolver;
    private final ModelRouter modelRouter;
    // ??Verbosity & Expansion
    private final VerbosityDetector verbosityDetector;
    private final SectionSpecGenerator sectionSpecGenerator;
    private final LengthVerifierService lengthVerifier;
    private final AnswerExpanderService answerExpander;
    private final FinalAnswerPostProcessor finalAnswerPostProcessor =
            new FinalAnswerPostProcessor(new OutputSanitizer());
    private final EvidenceAnswerComposer evidenceAnswerComposer;
    private final BypassRoutingService bypassRoutingService;
    private final com.example.lms.ensemble.EnsembleFinalAnswerService ensembleFinalAnswerService;
    @Value("${prompt.context.refiner.enabled:false}")
    private boolean promptContextRefinerEnabled;
    // ??Memory evidence I/O
    private final com.example.lms.service.rag.handler.MemoryHandler memoryHandler;
    private final com.example.lms.service.rag.handler.MemoryWriteInterceptor memoryWriteInterceptor;
    // ?좉퇋: ?숈뒿 湲곕줉 ?명꽣?됲꽣
    private final com.example.lms.learning.gemini.LearningWriteInterceptor learningWriteInterceptor;
    // ?좉퇋: ?댄빐 ?붿빟 諛?湲곗뼲 紐⑤뱢 ?명꽣?됲꽣
    private final com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor understandAndMemorizeInterceptor;
    @Autowired(required = false)
    private com.example.lms.debug.ai.DebugAiMetricsService debugAiMetricsService;
    @Autowired(required = false)
    private ChatUsageLedger chatUsageLedger;
    @Autowired(required = false)
    private LocalLlmSmokeHistoryDiagnosticsService localLlmSmokeHistoryDiagnosticsService;
    @Autowired(required = false)
    private AgentPipelineHealthController agentPipelineHealthController;
    /** In-flight cancel flags per session (best-effort) */
    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    // [METRICS] 媛꾨떒??in-memory 移댁슫??(Micrometer ?곕룞 ?꾧퉴吏 ?꾩떆)
    private final AtomicLong rescueCount = new AtomicLong();
    private final AtomicLong emptyTopDocsCount = new AtomicLong();
    private final AtomicLong freeIdeaCount = new AtomicLong();

    @Value("${rag.hybrid.top-k:50}")
    private int hybridTopK;
    @Value("${rag.rerank.top-n:10}")
    private int rerankTopN;
    // ??reranker keep-top-n by verbosity
    @Value("${reranker.keep-top-n.brief:5}")
    private int keepNBrief;
    @Value("${reranker.keep-top-n.standard:8}")
    private int keepNStd;
    @Value("${reranker.keep-top-n.deep:12}")
    private int keepNDeep;
    @Value("${reranker.keep-top-n.ultra:16}")
    private int keepNUltra;
    /**
     * ?섏씠釉뚮━???고쉶(吏꾨떒??: true硫?HybridRetriever瑜?嫄대꼫?곌퀬 ?⑥씪?⑥뒪濡?泥섎━
     */
    @Value("${debug.hybrid.bypass:false}")
    private boolean bypassHybrid;

    // [FUTURE_TECH FIX] Feature flags for unreleased / next-gen product handling
    @Value("${rag.latest-tech.enabled:true}")
    private boolean latestTechEnabled;

    @Value("${rag.latest-tech.auto-disable-vector:true}")
    private boolean latestTechAutoDisableVector;

    @Value("${rag.latest-tech.skip-memory-read:true}")
    private boolean latestTechSkipMemoryRead;

    @Value("${naver.reinforce-assistant:false}")
    private boolean enableAssistantReinforcement;

    /* ??????????????????????? ?ㅼ젙 (application.yml) ??????????????????????? */
    // 湲곗〈 ?곸닔 吏?뚮룄 ?섍퀬 洹몃?濡??щ룄 ?곴??놁쓬

    @Value("${openai.web-context.max-tokens:8000}")
    private int defaultWebCtxMaxTokens; // ?뙋 Live-Web 理쒕? ?좏겙

    @Value("${openai.mem-context.max-tokens:7500}")
    private int defaultMemCtxMaxTokens; // ??

    @Value("${openai.rag-context.max-tokens:5000}")
    private int defaultRagCtxMaxTokens; // ??
    // Resolve the API key from configuration or environment. Prefer the
    // `openai.api.key` property and fall back to OPENAI_API_KEY. Do not
    // include other vendor keys (e.g. GROQ_API_KEY) to prevent invalid
    // authentication.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openaiApiKey;
    @Value("${llm.chat-model:gemma4:26b}")
    private String defaultModel;
    @Value("${llm.provider:local}")
    private String llmProvider;
    @Value("${openai.fine-tuning.custom-model-id:}")
    private String tunedModelId;
    @Value("${openai.api.temperature.default:${llm.chat.temperature:0.3}}")
    private double defaultTemp;
    @Value("${openai.api.top-p.default:1.0}")
    private double defaultTopP;
    @Value("${openai.api.history.max-messages:6}")
    private int maxHistory;
    // ChatService ?대옒???꾨뱶 ?뱀뀡??
    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    /* ???????????????? Memory ?⑥튂: ?꾨＼?꾪듃 ???????????????? */

    /* ?뵺 怨듭떇 異쒖쿂 ?꾨찓???붿씠?몃━?ㅽ듃(?⑥튂/怨듭?瑜? */
    @Value("${search.official.domains:genshin.hoyoverse.com,hoyolab.com,youtube.com/@GenshinImpact,x.com/GenshinImpact}")
    private String officialDomainsCsv;

    // Legacy inline prompt constants removed; final prompt assembly is PromptBuilder-owned.
    /**
     * Additional safety boundary for sensitive topics.
     *
     * <p>
     * Injected as a system message only at the final answer related steps
     * (draft answer / final polish), so creative/exploration steps are not
     * unintentionally constrained.
     * </p>
     */
    private static final String PRIVACY_BOUNDARY_SYS = """
            [PRIVACY_BOUNDARY]
            - Do NOT claim to remember the user, past chats, or any personal details beyond what is explicitly provided in this conversation.
            - Do NOT invent personal stories, experiences, or "memories".
            - Do NOT infer or guess identities, addresses, phone numbers, emails, or other private details.
            - When uncertain, say so and give safe, general guidance.
            - If the user message contains sensitive personal information, avoid repeating it verbatim.
            """;

    /* ?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧??ML 蹂댁젙 ?뚮씪誘명꽣 ?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧??*/
    /**
     * Machine learning based correction parameters. These values can be
     * configured via application.yml using keys under the prefix
     * {@code ml.correction.*}. They correspond to the 慣, 棺, 款, 關,
     * 貫, and d? coefficients described in the specification. See
     * {@link MLCalibrationUtil} for details.
     */
    @Value("${ml.correction.alpha:0.0}")
    private double mlAlpha;
    @Value("${ml.correction.beta:0.0}")
    private double mlBeta;
    @Value("${ml.correction.gamma:0.0}")
    private double mlGamma;
    @Value("${ml.correction.mu:0.0}")
    private double mlMu;
    @Value("${ml.correction.lambda:1.0}")
    private double mlLambda;
    @Value("${ml.correction.d0:0.0}")
    private double mlD0;
    // 寃利?湲곕낯 ?쒖꽦???뚮옒洹?(application.yml: verification.enabled=true)
    @org.springframework.beans.factory.annotation.Value("${verification.enabled:true}")
    private boolean verificationEnabled;

    // ???????????? Guard detour cheap retry (one-shot) ?????????????????
    // When we emitted a detour due to insufficient citations, try ONE additional
    // cheap web search
    // (site-hinted) to recover citations without asking the user to re-ask.
    @Value("${guard.detour.cheap-retry.enabled:true}")
    private boolean detourCheapRetryEnabled;

    @Value("${guard.detour.cheap-retry.web-top-k:8}")
    private int detourCheapRetryWebTopK;

    @Value("${guard.detour.cheap-retry.web-budget-ms:1500}")
    private long detourCheapRetryWebBudgetMs;

    @Value("${guard.detour.cheap-retry.max-added-docs:6}")
    private int detourCheapRetryMaxAddedDocs;

    @Value("${guard.detour.cheap-retry.max-sites:1}")
    private int detourCheapRetryMaxSites;

    @Value("${guard.detour.cheap-retry.combine-sites-with-or:false}")
    private boolean detourCheapRetryCombineSitesWithOr;

    @Value("${guard.detour.cheap-retry.regen-llm.enabled:false}")
    private boolean detourCheapRetryRegenLlmEnabled;

    /**
     * Cost-control knob for probe deployments.
     * <p>
     * Even when {@code forceEscalate} is requested (entity/definitional + insufficient citations),
     * operators can disable the extra LLM regeneration call.
     * When disabled, the detour still attempts the cheap web retry and then falls back to
     * evidence-only composition.
     */
    @Value("${guard.detour.force-escalate.regen-llm.enabled:true}")
    private boolean detourForceEscalateRegenLlmEnabled;

    @Value("${guard.detour.cheap-retry.regen-llm.temperature:0.2}")
    private double detourCheapRetryRegenLlmTemperature;

    @Value("${guard.detour.cheap-retry.regen-llm.max-tokens:900}")
    private int detourCheapRetryRegenLlmMaxTokens;

    @Value("${guard.detour.cheap-retry.regen-llm.only-if-low-risk:true}")
    private boolean detourCheapRetryRegenLlmOnlyIfLowRisk;

    @Value("${guard.detour.cheap-retry.site-hints:wikipedia.org,namu.wiki,hoyolab.com}")
    private String detourCheapRetrySiteHintsCsv;

    // ???????????? Attachment injection ?????????????????
    /**
     * Service used to resolve uploaded attachment identifiers into prompt context
     * documents. Injected via constructor to allow attachments to be
     * incorporated into the PromptContext without manual bean lookup.
     */
    private final AttachmentService attachmentService;

    // =========================================================================
    // [SECTION 1] SSE/request orchestration
    // Comment-only roadmap; no extraction in this safe patch.
    // Future extraction candidate: ChatWorkflowSseManager.
    // =========================================================================

    /* ?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧??PUBLIC ENTRY ?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧??*/

    /**
     * ?⑥씪 ?붾뱶?ъ씤?? ?붿껌 ?듭뀡???곕씪 RAG, OpenAI-Java, LangChain4j ?뚯씠?꾨씪?몄쑝濡?遺꾧린.
     */
    /* ????????????????????????? NEW ENTRY ????????????????????????? */
    /** RAG 쨌 Web 寃?됱쓣 紐⑤몢 ?쇱썙?ｌ쓣 ???덈뒗 ?뺤옣???붾뱶?ъ씤??*/
    // ???몃? 而⑦뀓?ㅽ듃 ?놁씠 ?곕뒗 ?⑥씪 踰꾩쟾?쇰줈 援먯껜
    // ChatService.java

    /**
     * RAG 쨌 WebSearch 쨌 Stand-Alone 쨌 Retrieval OFF 紐⑤몢 泥섎━?섎뒗 ?듯빀 硫붿꽌??
     */
    // ??1-?몄옄 ?섑띁 ? 而⑦듃濡ㅻ윭媛 ?몄텧
    @Cacheable(value = "chatResponses",
            // 罹먯떆 ?ㅻ뒗 ?몄뀡怨?紐⑤뜽蹂꾨줈 寃⑸━: ?숈씪 硫붿떆吏?쇰룄 ?몄뀡쨌紐⑤뜽???ㅻⅤ硫?蹂꾨룄 ???
            // Use a static helper to build the key without string concatenation
            key = "T(com.example.lms.service.ChatService).cacheKey(#req)",
            condition = "T(com.example.lms.service.ChatService).isCacheSafe(#req)"
                    + " && (T(com.example.lms.service.guard.GuardContextHolder).get() == null"
                    + " || !T(com.example.lms.service.guard.GuardContextHolder).get()"
                    + ".planBool('creative.emergence.active', false))")
    public ChatResult continueChat(ChatRequestDto req) {
        int webK = (req.getWebTopK() == null || req.getWebTopK() <= 0) ? 5 : req.getWebTopK();
        Function<String, List<String>> defaultProvider = q -> webSearchProvider.search(q, webK); // ?ㅼ씠踰?Top-K
        return continueChat(req, defaultProvider); // ???〓줈 ?꾩엫
    }

    // ?? intent/risk/濡쒓퉭 ?좏떥 ?????????????????????????????????????
    private String inferIntent(String q) {
        try {
            return qcPreprocessor.inferIntent(q);
        } catch (Exception e) {
            log.debug("[inferIntent] query transformer bypassed err={}", e.getClass().getSimpleName());
            TraceStore.putIfAbsent("queryTransformer.bypassed", "true");
            TraceStore.putIfAbsent("queryTransformer.reason", "infer_intent_failed");
            return "GENERAL";
        }
    }

    private String detectRisk(String q) {
        if (q == null)
            return null;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return HIGH_RISK_TERMS.stream().anyMatch(s::contains) ? "HIGH" : null;
    }

    /**
     * [Dual-Vision] VisionMode 寃곗젙 濡쒖쭅
     *
     * ?곗꽑?쒖쐞:
     * 1. 怨좎쐞???꾨찓????STRICT 媛뺤젣
     * 2. ?ъ슜??紐낆떆???붿껌 ??洹몃?濡??ъ슜
     * 3. ?꾨찓??湲곕컲 ?먮룞 寃곗젙
     */
    private VisionMode decideVision(QueryDomain domain, String riskLevel, ChatRequestDto req) {
        // 湲곗〈 ?쒓렇?덉쿂??planId 媛 ?녿뒗 ?몄텧遺瑜??꾪빐 ?좎??섍퀬,
        // ?대??곸쑝濡쒕뒗 planId=null ???ｌ뼱 ?좉퇋 濡쒖쭅???ъ슜?쒕떎.
        return decideVision(domain, riskLevel, req, null);
    }

    /**
     * [Dual-Vision] VisionMode 寃곗젙 濡쒖쭅 v2
     *
     * ?곗꽑?쒖쐞:
     * 1. 怨좎쐞???꾨찓????STRICT 媛뺤젣
     * 2. ?ъ슜??紐낆떆???붿껌 ??洹몃?濡??ъ슜
     * 3. Plan ?ㅼ젙?먯꽌 吏?뺣맂 紐⑤뱶
     * 4. ?꾨찓??湲곕컲 ?먮룞 寃곗젙
     */
    private VisionMode decideVision(QueryDomain domain,
            String riskLevel,
            ChatRequestDto req,
            String planId) {
        // 1. HIGH risk ??臾댁“嫄?STRICT (?덉쟾留?
        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            return VisionMode.STRICT;
        }

        // 2. ?ъ슜??紐낆떆???붿껌 (硫붿떆吏 ???뱀닔 而ㅻ㎤??湲곕컲)
        String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
        if (userQuery.contains("/strict") || userQuery.contains("?꾧꺽?섍쾶")) {
            return VisionMode.STRICT;
        }
        if (userQuery.contains("/free") || userQuery.contains("?먯쑀濡?쾶")) {
            return VisionMode.FREE;
        }

        // 3. [NEW] Plan 湲곕컲 紐⑤뱶 寃곗젙
        if (planId != null && !planId.isBlank()) {
            String lower = planId.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("zero_break") || lower.contains("hypernova")) {
                return VisionMode.FREE;
            }
            if (lower.contains("safe") || lower.contains("strict")) {
                return VisionMode.STRICT;
            }
        }

        // 4. ?꾨찓??湲곕컲 ?먮룞 寃곗젙
        return switch (domain) {
            case GAME, SUBCULTURE -> VisionMode.FREE;
            case STUDY, SENSITIVE -> VisionMode.STRICT;
            default -> VisionMode.HYBRID;
        };
    }

    private static void applyDefensiveInteractionContext(
            InteractionEvidencePolicy.Decision decision,
            com.example.lms.service.guard.GuardContext context,
            AnswerMode answerMode,
            GuardProfile guardProfile) {
        if (decision == null || !decision.defensive() || context == null) {
            return;
        }
        context.setHeaderMode("strict");
        context.setMode((answerMode == null ? AnswerMode.FACT : answerMode).name());
        context.setPlanId("safe_autorun.v1");
        context.setMemoryProfile("NONE");
        context.setGuardLevel((guardProfile == null ? GuardProfile.STRICT : guardProfile).name());
        context.putPlanOverride("interaction.securityStance", decision.securityStance().name());
        context.putPlanOverride("memory.forceOff", true);
    }

    static InteractionEvidencePolicy.Decision resolveInteractionPolicyDecision(
            String userQuery,
            com.example.lms.service.guard.GuardContext context,
            String configuredMode) {
        InteractionEvidencePolicy.InteractionObservation interactionObservation =
                InteractionEvidencePolicy.observeRequest(userQuery);
        if (context != null) {
            interactionObservation = interactionObservation
                    .withAdditionalFacts(context.getInteractionPolicyDecision().confirmedManipulationFacts())
                    .withAdditionalFacts(context.getInteractionPolicyFacts());
        }
        InteractionEvidencePolicy.FeatureMode interactionFeatureMode =
                InteractionEvidencePolicy.FeatureMode.parse(configuredMode);
        return InteractionEvidencePolicy.evaluate(interactionObservation, interactionFeatureMode);
    }

    static boolean allowsConversationShortCircuit(
            boolean interactionShortCircuitAllowed,
            ConversationFrameV1 frame) {
        return interactionShortCircuitAllowed && safeConversationFrame(frame).allowsDirectShortCircuit();
    }

    static boolean allowsConversationRefinement(
            boolean refinerFeatureEnabled,
            ConversationFrameV1 frame) {
        return refinerFeatureEnabled && safeConversationFrame(frame).allowsOptionalRefinement();
    }

    static boolean allowsConversationExpansion(ConversationFrameV1 frame) {
        return safeConversationFrame(frame).allowsOptionalExpansion();
    }

    static boolean deniesConversationMemoryWrite(
            boolean interactionPolicyDenied,
            ConversationFrameV1 frame) {
        return interactionPolicyDenied || safeConversationFrame(frame).suppressesMemoryWrites();
    }

    static ConversationFrameV1 resolveConversationFrame(
            ConversationFrameResolver resolver,
            String userText,
            boolean multimodalInputPresent,
            ConversationFrameV1.Mode mode) {
        ConversationFrameResolver safeResolver = resolver == null
                ? FALLBACK_CONVERSATION_FRAME_RESOLVER
                : resolver;
        return safeResolver.resolve(userText, multimodalInputPresent, mode);
    }

    static boolean hasObservedConversationWireAttempt(
            List<? extends Map<String, ?>> attemptLedger) {
        if (attemptLedger == null || attemptLedger.isEmpty()) {
            return false;
        }
        return attemptLedger.stream()
                .filter(Objects::nonNull)
                .anyMatch(row -> Boolean.TRUE.equals(row.get("wireAttemptObserved")));
    }

    static dev.langchain4j.data.message.UserMessage primaryUserMessage(
            String finalQuery,
            ChatRequestDto request,
            ConversationFrameV1 frame) {
        String text = finalQuery == null ? "" : finalQuery;
        ConversationFrameV1 safeFrame = safeConversationFrame(frame);
        if (!safeFrame.enforcementActive()
                || !safeFrame.multimodalInputPresent()
                || request == null
                || !StringUtils.hasText(request.getImageBase64())) {
            return dev.langchain4j.data.message.UserMessage.from(text);
        }
        return dev.langchain4j.data.message.UserMessage.from(
                dev.langchain4j.data.message.TextContent.from(text),
                dev.langchain4j.data.message.ImageContent.from(
                        request.getImageBase64().trim(),
                        request.resolvedImageMediaType()));
    }

    static Optional<String> verifiedVisionModel(
            com.example.lms.service.rag.plan.PlanModelResolver resolver,
            String configuredVisionModel) {
        if (resolver == null || unavailableVisionModel(configuredVisionModel)) {
            return Optional.empty();
        }
        String resolved = resolver.resolveRequestedModel("llmrouter.vision");
        if (unavailableVisionModel(resolved)) {
            return Optional.empty();
        }
        return Optional.of(resolved.trim());
    }

    private static boolean unavailableVisionModel(String model) {
        if (!StringUtils.hasText(model)) {
            return true;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("${")
                || normalized.startsWith("llmrouter.")
                || normalized.equals("off")
                || normalized.equals("disabled")
                || normalized.equals("none")
                || normalized.equals("null")
                || normalized.equals("test")
                || normalized.equals("changeme");
    }

    private static ConversationFrameV1 safeConversationFrame(ConversationFrameV1 frame) {
        return frame == null ? ConversationFrameV1.off(false) : frame;
    }

    private static void appendInteractionPolicyBreadcrumb(
            InteractionEvidencePolicy.Decision interactionPolicyDecision,
            String failureStage) {
        if (interactionPolicyDecision == null || !interactionPolicyDecision.shouldTrace()) {
            return;
        }
        try {
            MlaBreadcrumb.appendInteractionPolicyTransition(interactionPolicyDecision);
        } catch (Throwable failure) {
            ChatWorkflowTraceSuppressions.traceSuppressed(failureStage, failure);
        }
    }

    private static void appendConversationFrameBreadcrumb(
            ConversationFrameV1 conversationFrame,
            String failureStage) {
        if (conversationFrame == null || !conversationFrame.shouldTrace()) {
            return;
        }
        try {
            MlaBreadcrumb.appendConversationFrameTransition(conversationFrame);
        } catch (Throwable failure) {
            ChatWorkflowTraceSuppressions.traceSuppressed(failureStage, failure);
        }
    }

    private void refreshConversationFrameAttemptCoverage(
            ConversationFrameV1 conversationFrame,
            String failureStage) {
        if (conversationFrame == null || !conversationFrame.shouldTrace()) {
            return;
        }
        try {
            Object timelineId = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
            if (modelRuntimeHealthTracker != null
                    && timelineId != null
                    && hasObservedConversationWireAttempt(
                            modelRuntimeHealthTracker.redactedRequestAttemptLedger(String.valueOf(timelineId)))) {
                TraceStore.put("conversation.frame.wireAttemptCoverage", "observed");
            }
            MlaBreadcrumb.appendConversationFrameTransition(conversationFrame);
        } catch (Throwable failure) {
            ChatWorkflowTraceSuppressions.traceSuppressed(failureStage, failure);
        }
    }

    /**
     * [Dual-Vision] 理쒖떊/誘몃옒 Tech 荑쇰━ 媛먯?
     * ?숈뒿 而룹삤???댄썑 湲곌린???대씪?곕뱶/怨좎꽦??紐⑤뜽濡??곗꽑 ?쇱슦??
     */
    private boolean isLatestTechQuery(String query) {
        // [FUTURE_TECH FIX] Centralized detection for unreleased/next-gen tech product
        // queries
        return FutureTechDetector.isFutureTechQuery(query);
    }

    private static String getModelName(dev.langchain4j.model.chat.ChatModel m) {
        return (m == null) ? "unknown" : m.getClass().getSimpleName();
    }

    /**
     * Observable request scope for {@code plan.when} evaluation on the chat
     * path. Only channels with a real request binding are exposed; metrics are
     * never synthesized, so unobserved conditions evaluate UNKNOWN.
     */
    static java.util.Map<String, Object> chatPlanScope(GuardContext gctx) {
        java.util.Map<String, Object> scope = new java.util.LinkedHashMap<>();
        // The raw X-Brave-Mode writers (web.TraceFilter, novaRuleBreakInterceptor)
        // are not registered on the chat path; the controller resolves that header
        // into GuardContext.headerMode, which is the live binding here.
        boolean brave = com.example.lms.nova.NovaRequestContext.isBrave() || trace.TraceContext.isBrave()
                || (gctx != null && "brave".equalsIgnoreCase(gctx.getHeaderMode()));
        scope.put("request.header.x-brave-mode", brave ? "on" : "off");
        if (gctx != null && gctx.getHeaderMode() != null && !gctx.getHeaderMode().isBlank()) {
            scope.put("request.context.header_mode", gctx.getHeaderMode());
        }
        return scope;
    }

    /** Plan-driven expansion override keys (bound to expansion pipeline stages). */
    static boolean isPlanExpansionKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("selfask.") || lower.startsWith("expand.")
                || lower.startsWith("queryburst.") || lower.startsWith("overdrive.")
                || lower.startsWith("extremez.");
    }

    static void stripPlanExpansionKeys(java.util.Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.keySet().removeIf(ChatWorkflow::isPlanExpansionKey);
    }

    static Number traceNumber(String key) {
        Object value = TraceStore.get(key);
        return value instanceof Number n ? n : null;
    }

    /**
     * Post-retrieval metrics observed on the chat path, mapped to plan-eval
     * scope. Only genuinely observed values are exposed; unproduced metrics
     * (e.g. {@code metrics.initial_recall}) stay absent so they evaluate
     * UNKNOWN rather than a fabricated zero.
     */
    static java.util.Map<String, Object> chatPlanObservedMetrics() {
        java.util.Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        Number fused = traceNumber("rag.fusion.final.totalCount");
        if (fused != null) {
            metrics.put("metrics.result_count", fused);
            metrics.put("metrics.empty_result", fused.intValue() <= 0);
        }
        Object confidence = TraceStore.get("rag.answerQuality.confidence");
        if (confidence != null) {
            metrics.put("metrics.retrieval_confidence", confidence);
        }
        return metrics;
    }

    /**
     * Runtime evidence for the plan stage ledger on the chat path: translates
     * the markers this path actually writes ({@code rag.fusion.sizes.*},
     * {@code rag.selfask.count}, {@code retrieval.web.skipped}, {@code rerank.*})
     * into the shared ledger evidence keys. Absent evidence stays absent —
     * the ledger reports DECLARED/UNAVAILABLE rather than claiming execution.
     */
    static java.util.Map<String, Object> chatStageEvidence(
            java.util.List<dev.langchain4j.rag.content.Content> topDocs,
            String rerankBackend) {
        java.util.Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        Number selfAskCount = traceNumber("rag.selfask.count");
        if (selfAskCount != null && selfAskCount.intValue() > 0) {
            evidence.put("selfAsk", "enabled");
        }
        if (Boolean.TRUE.equals(TraceStore.get("retrieval.web.skipped"))) {
            evidence.put("stage.web", "disabled");
        } else {
            Number webSize = traceNumber("rag.fusion.sizes.web");
            Number webOnly = traceNumber("fallback.webOnly.count");
            int webTotal = (webSize == null ? 0 : webSize.intValue())
                    + (webOnly == null ? 0 : webOnly.intValue());
            if (webTotal > 0) {
                evidence.put("stage.web", "success:" + webTotal);
            }
        }
        Number vecSize = traceNumber("rag.fusion.sizes.vector");
        if (vecSize != null && vecSize.intValue() > 0) {
            evidence.put("stage.vector", "success:" + vecSize.intValue());
        }
        Number kgSize = traceNumber("rag.fusion.sizes.kg");
        if (kgSize != null && kgSize.intValue() > 0) {
            evidence.put("stage.kg", "success:" + kgSize.intValue());
        }
        Object fusedCount = TraceStore.get("rag.fusion.final.totalCount");
        if (fusedCount instanceof Number) {
            evidence.put("stage.fuse", fusedCount);
        }
        Object rerankSkip = TraceStore.get("rerank");
        if ("skipped_by_plate".equals(rerankSkip) || "skipped_by_plan".equals(rerankSkip)) {
            evidence.put("stage.onnx", "skipped:" + rerankSkip);
        } else if (Boolean.TRUE.equals(TraceStore.get("rerank.fallback"))) {
            evidence.put("stage.onnx", "error:rerank_fallback");
        } else if (topDocs != null && !topDocs.isEmpty()
                && "onnx".equalsIgnoreCase(String.valueOf(rerankBackend))) {
            evidence.put("stage.onnx", Integer.valueOf(topDocs.size()));
        }
        return evidence;
    }

    static com.example.lms.plan.PlanExecutionSpec.StageFlags chatStageFlags(
            OrchestrationHints hints,
            java.util.Map<String, Object> metaHints,
            com.example.lms.plan.PlanExecutionSpec.WhenVerdict planWhen) {
        boolean selfAskOn = hints != null && hints.isEnableSelfAsk();
        boolean onnxOn = hints != null && hints.isEnableCrossEncoder();
        Object dpp = metaHints == null ? null : metaHints.get("diversity.dpp.enabled");
        boolean diversityOn = dpp instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(dpp));
        boolean expansionEligible = planWhen == null
                || planWhen.state() != com.example.lms.plan.PlanExecutionSpec.TriState.FALSE;
        return new com.example.lms.plan.PlanExecutionSpec.StageFlags(
                selfAskOn, false, onnxOn, diversityOn, expansionEligible);
    }

    /**
     * Build a composite cache key from a chat request. This helper avoids
     * string concatenation in the SpEL expression by delegating the
     * composition to Java code. Each component is converted to a string
     * and joined with a colon separator. When the request is null or
     * fields are absent empty strings are used.
     *
     * @param req the chat request
     * @return a stable key of the form sessionId:model:message:useRag:useWebSearch
     */
    public static String cacheKey(com.example.lms.dto.ChatRequestDto req) {
        if (req == null)
            return "";
        String sid = String.valueOf(req.getSessionId());
        String model = String.valueOf(req.getModel());
        String msg = String.valueOf(SafeRedactor.hash12(req.getMessage()));
        String rag = String.valueOf(req.isUseRag());
        String web = String.valueOf(req.isUseWebSearch());
        return String.format("%s:%s:%s:%s:%s", sid, model, msg, rag, web);
    }

    private void reinforce(String sessionKey, String query, String answer,
            VisionMode visionMode,
            GuardProfile guardProfile,
            MemoryMode memoryMode) {
        try {
            reinforceAssistantAnswerWithProfile(sessionKey, query, answer, 0.5, null, visionMode, guardProfile,
                    memoryMode);
        } catch (Throwable t) {
            log.debug("[reinforce] memory reinforcement failed sessionHash={} err={}",
                    SafeRedactor.hashValue(sessionKey), t.getClass().getSimpleName());
            TraceStore.inc("memory.reinforce.failed");
        }
    }

    /**
     * ?섎룄 遺꾩꽍???듯빐 理쒖쥌 寃??荑쇰━瑜?寃곗젙?쒕떎.
     */
    /**
     * ?ъ슜?먯쓽 ?먮낯 荑쇰━? LLM???ъ옉?깊븳 荑쇰━ 以?理쒖쥌?곸쑝濡??ъ슜??荑쇰━瑜?寃곗젙?⑸땲??
     * ?ъ옉?깅맂 荑쇰━媛 ?좏슚?섍퀬, 紐⑤뜽??洹?寃곌낵???먯떊媛먯쓣 蹂댁씪 ?뚮쭔 ?ъ옉?깅맂 荑쇰━瑜??ъ슜?⑸땲??
     *
     * @param originalQuery ?ъ슜?먯쓽 ?먮낯 ?낅젰 荑쇰━
     * @param r             QueryRewriteResult, ?ъ옉?깅맂 荑쇰━? ?좊ː???먯닔瑜??ы븿
     * @return 理쒖쥌?곸쑝濡?RAG 寃?됱뿉 ?ъ슜??荑쇰━ 臾몄옄??
     */

    private String decideFinalQuery(String originalQuery, Long sessionId) {
        if (originalQuery == null || originalQuery.isBlank())
            return originalQuery;
        List<String> history = (sessionId != null)
                ? chatHistoryService.getFormattedRecentHistory(sessionId, 5)
                : java.util.Collections.emptyList();

        DisambiguationResult r = disambiguationService.clarify(originalQuery, history);
        if (r != null && r.isConfident() && r.getRewrittenQuery() != null && !r.getRewrittenQuery().isBlank()) {
            return r.getRewrittenQuery();
        }
        return originalQuery; // ????以꾩씠 諛섎뱶???덉뼱????
    }

    public ChatResult continueChat(ChatRequestDto req,
            Function<String, List<String>> externalCtxProvider) {
        return continueChat(req,externalCtxProvider,ChatConversationContext.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.example.lms.llm.spec.ModelSpecRegistry focusModelSpecs;

    public ChatResult continueChat(ChatRequestDto req,
            Function<String,List<String>> externalCtxProvider,ChatConversationContext conversationContext) {
        Objects.requireNonNull(conversationContext);
        boolean ownsGuardContext = GuardContextHolder.get() == null;
        if (ownsGuardContext) {
            GuardContextHolder.set(GuardContext.defaultContext());
        }
        try {
            return continueChatInRequestContext(req, externalCtxProvider,conversationContext);
        } finally {
            if (ownsGuardContext) {
                GuardContextHolder.clear();
            }
        }
    }

    private ChatResult continueChatInRequestContext(ChatRequestDto req,
            Function<String, List<String>> externalCtxProvider,ChatConversationContext conversationContext) {

        final ChatRequestDto.RetrievalRequestIntent rawRetrievalIntent = req.getRetrievalRequestIntent() != null
                ? req.getRetrievalRequestIntent()
                : new ChatRequestDto.RetrievalRequestIntent(req.getUseWebSearch(), req.getUseRag());

        Object requestTimelineIdBeforeTraceClear =
                TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);

        // ?? ?몄뀡???뺢퇋???⑥씪 ???꾪뙆) ???????????????????????????????
        String sessionKey = Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> {
                    if (s.startsWith("chat-"))
                        return s;
                    if (s.matches("\\d+")) {
                        return String.format("chat-%s", s);
                    }
                    return s;
                })
                .orElse(UUID.randomUUID().toString());

        // Ensure any ThreadLocal trace values from a previous request are cleared
        // before starting this run. SmartQueryPlanner also clears TraceStore, but
        // Web/RAG-only flows may bypass it.
        try {
            TraceStore.clear();
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("traceStore.clear", ignore);
        }
        if (requestTimelineIdBeforeTraceClear != null) {
            TraceStore.putInternal(
                    ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY,
                    requestTimelineIdBeforeTraceClear);
            recordModelRequestTimelinePhase("pending", req == null ? null : req.getModel(), "none");
        }
        rehydrateCreativeEmergenceTrace(GuardContextHolder.get());
        com.example.lms.orchestration.control.RagControlRuntimeAdapter.capturePresentationInput(
                com.example.lms.orchestration.control.RagControlRuntimeAdapter.RuntimeInput.evidenceNeeded(
                        req != null && (Boolean.TRUE.equals(req.getUseWebSearch())
                                || Boolean.TRUE.equals(req.getUseRag()))));

        ChatWorkflowRequestTraceEnvelope.seed(req, sessionKey);
        com.example.lms.llm.RequestedModelSelection.begin(req.isStrictModelSelection() ? req.getModel() : null);
        if (req.isStrictModelSelection()) {
            var choice = chatModelCatalogService == null ? null
                    : chatModelCatalogService.resolve(req.getModel()).orElse(null);
            if (choice == null || !choice.selectable()) {
                throw new com.example.lms.llm.ModelSelectionException(
                        choice != null && choice.reason().contains("disabled")
                                ? "provider_not_configured" : "model_unavailable");
            }
        }

        // ?? 0) ?ъ슜???낅젰 ?뺣낫 ?????????????????????????????????????
        final String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
        final boolean evidenceReleaseRequired =
                EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(userQuery);
        final String requestedModel = Optional.ofNullable(req.getModel()).orElse("");
        final boolean forceLightSearchMode = req != null
                && req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT;
        final boolean directRetrievalOffMode = req != null
                && (req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
                || Boolean.FALSE.equals(req.getUseWebSearch()))
                && Boolean.FALSE.equals(req.getUseRag());

        if (userQuery.isBlank()) {
            return ChatResult.of("?뺣낫 ?놁쓬", String.format("lc:%s", chatModel.getClass().getSimpleName()), true);
        }

        var gctx = GuardContextHolder.getOrDefault();
        if (gctx != null
                && attachmentService != null
                && req.getAttachmentIds() != null
                && InteractionEvidencePolicy.FeatureMode.parse(interactionPolicyMode)
                        != InteractionEvidencePolicy.FeatureMode.OFF) {
            attachmentService.observeInteractionEvidence(
                    req.getAttachmentIds(),
                    req.getSessionId() == null ? null : String.valueOf(req.getSessionId()),
                    gctx);
        }
        final InteractionEvidencePolicy.Decision interactionPolicyDecision =
                resolveInteractionPolicyDecision(
                        userQuery,
                        gctx,
                        interactionPolicyMode);
        final ConversationFrameV1 conversationFrame = resolveConversationFrame(
                conversationFrameResolver,
                userQuery,
                req != null && StringUtils.hasText(req.getImageBase64()),
                ConversationFrameV1.Mode.parse(conversationHarmonyMode));
        final boolean interactionShortCircuitAllowed = allowsConversationShortCircuit(
                !interactionPolicyDecision.defensive(),
                conversationFrame);
        appendInteractionPolicyBreadcrumb(interactionPolicyDecision, "interactionPolicy.breadcrumb");
        appendConversationFrameBreadcrumb(conversationFrame, "conversationFrame.breadcrumb");

        // Domain classification for this query
        QueryDomain queryDomain = queryDomainClassifier.classify(userQuery);

        // [NEW] AnswerMode / MemoryMode from HTTP request (null-safe)
        AnswerMode requestedAnswerMode = AnswerMode.fromString(req.getMode());
        AnswerMode answerMode = interactionPolicyDecision.enforceAnswerMode(requestedAnswerMode);
        MemoryMode memoryMode = MemoryMode.fromString(req.getMemoryMode());

        GuardProfile guardProfile;
        // ?ъ슜?먭? mode瑜?紐낆떆??寃쎌슦 AnswerMode 湲곕컲 GuardProfile濡?留ㅽ븨
        if (req.getMode() != null && !req.getMode().isBlank()) {
            guardProfile = GuardProfile.fromAnswerMode(answerMode);
        } else {
            // QueryDomain 湲곕컲 湲곕낯 GuardProfile 寃곗젙 (?쒖꽑1/2/3 ?듯빀)
            guardProfile = guardProfileProps.profileFor(queryDomain);
        }
        guardProfile = interactionPolicyDecision.enforceGuardProfile(guardProfile);
        // EvidenceAwareGuard ?먯꽌 ?ъ슜???꾩옱 ?꾨줈?뚯씪 ?깅줉
        guardProfileProps.setCurrentProfile(guardProfile);

        // ??GuardContextHolder ?묐ぉ: 而⑦듃濡ㅻ윭媛 set??而⑦뀓?ㅽ듃瑜??ㅼ??ㅽ듃?덉씠?섏뿉??蹂닿컯
        // (?놁쑝硫?嫄대뱶由ъ? ?딆쓬; ThreadLocal?대?濡??ш린???앹꽦/clear???섏? ?딅뒗??)
        // ??gctx null 諛⑹?: 而⑦듃濡ㅻ윭/?꾪꽣媛 GuardContext瑜????ъ? 寃쎈줈?먯꽌??NPE 諛⑹뼱
        com.example.lms.service.rag.plan.ProjectionAgentPlanSpec projectionPlan = null;
        boolean projectionPipeline = false;
        com.example.lms.plan.PlanHints planHints = null;
        com.example.lms.plan.PlanExecutionSpec planExecSpec = null;
        com.example.lms.plan.PlanExecutionSpec.WhenVerdict planWhen = null;
        if (gctx != null) {
            gctx.setInteractionPolicyDecision(interactionPolicyDecision);
            gctx.setEntityQueryFromQuestion(userQuery);
            applyDefensiveInteractionContext(interactionPolicyDecision, gctx, answerMode, guardProfile);
            if (gctx.getMode() == null || gctx.getMode().isBlank())
                gctx.setMode(answerMode.name());
            // planId媛 鍮꾩뼱?덉쑝硫?WorkflowOrchestrator濡??먮룞 ?좏깮
            if (workflowOrchestrator != null) {
                try {
                    boolean hasDocumentEvidence = req != null
                            && req.getAttachmentIds() != null
                            && !req.getAttachmentIds().isEmpty();
                    workflowOrchestrator.ensurePlanSelected(gctx, answerMode, queryDomain, userQuery, hasDocumentEvidence);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("workflow.planSelected", ignore); }
            }
            if (gctx.getPlanId() == null || gctx.getPlanId().isBlank())
                gctx.setPlanId("safe_autorun.v1");

            try {
                if (planHintApplier != null) {
                    planHints = planHintApplier.load(gctx.getPlanId());
                    planHintApplier.applyToGuardContext(planHints, gctx);
                    // plan.when execution gate (chat path): the plan's own activation
                    // condition is evaluated against observable request scope. A
                    // conclusively FALSE verdict suppresses plan-driven expansion
                    // overrides; UNKNOWN never fabricates metric evidence.
                    planExecSpec = planHintApplier.loadExecutionSpec(gctx.getPlanId());
                    if (planExecSpec != null && !planExecSpec.isEmpty()) {
                        planWhen = planExecSpec.evaluateWhen(chatPlanScope(gctx));
                        TraceStore.put("plan.when", planWhen.state().name().toLowerCase(Locale.ROOT));
                        TraceStore.put("plan.when.conditions", planWhen.debugView());
                        if (!planExecSpec.pipeline().isEmpty()) {
                            TraceStore.put("plan.pipeline.declared", planExecSpec.pipeline());
                        }
                        if (planWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.FALSE
                                && planExecSpec.declaresExpansion()) {
                            stripPlanExpansionKeys(gctx.getPlanOverrides());
                            TraceStore.put("plan.expansion.gated", "when_false");
                        }
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("planHints.applyGuardContext", ignore); }

            // Pipeline DSL: projection_agent.v1.yaml (dual-view + merge)
            if (planDslLoader != null && gctx.getPlanId() != null) {
                projectionPlan = planDslLoader.loadProjectionAgent(gctx.getPlanId()).orElse(null);
                projectionPipeline = (projectionPlan != null);
            }

            if (projectionPipeline && planPolicyMapper != null && guardProfileProps != null) {
                // Strict branch is the primary execution context in projection_agent.v1
                var strict = projectionPlan.viewMemorySafe() != null ? projectionPlan.viewMemorySafe() : null;
                String guardProfileStr = strict != null ? strict.guardProfile()
                        : (projectionPlan.defaults() != null ? projectionPlan.defaults().guardProfile() : null);
                guardProfile = planPolicyMapper.resolveGuardProfile(guardProfileStr, guardProfile);

                // If user explicitly provided memoryMode, do not override.
                boolean userExplicitMemoryMode = (req.getMemoryMode() != null && !req.getMemoryMode().isBlank());
                String memoryProfileStr = strict != null ? strict.memoryProfile()
                        : (projectionPlan.defaults() != null ? projectionPlan.defaults().memoryProfile() : null);
                if (!userExplicitMemoryMode) {
                    memoryMode = planPolicyMapper.resolveMemoryMode(memoryProfileStr, memoryMode);
                }

                guardProfile = interactionPolicyDecision.enforceGuardProfile(guardProfile);
                guardProfileProps.setCurrentProfile(guardProfile);
            }

            applyDefensiveInteractionContext(interactionPolicyDecision, gctx, answerMode, guardProfile);
            if (gctx.getGuardLevel() == null || gctx.getGuardLevel().isBlank())
                gctx.setGuardLevel(guardProfile.name());
            if (gctx.getMemoryProfile() == null || gctx.getMemoryProfile().isBlank()) {
                gctx.setMemoryProfile(memoryMode == MemoryMode.EPHEMERAL ? "NONE" : "MEMORY");
            }
            gctx.setCheapSearchMode(forceLightSearchMode);
            if (forceLightSearchMode) {
                try {
                    TraceStore.put("search.mode.lightAuxBypass", true);
                    TraceStore.put("search.mode.lightAuxBypass.reason", "force_light");
                } catch (RuntimeException ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("searchMode.lightAuxBypass", ignore);
                }
            }
        }
        final boolean memoryReadEnabled = memoryMode == null || memoryMode.isReadEnabled();

        // Per-plan overrides (projection_agent.v1.yaml): model / traits / token budget
        // / verification
        String effectiveRequestedModel = requestedModel;
        ChatRequestDto llmReq = req;
        if (projectionPipeline && projectionPlan != null) {
            var strictCfg = projectionPlan.viewMemorySafe() != null ? projectionPlan.viewMemorySafe() : null;
            if (strictCfg != null) {
                if (planModelResolver != null) {
                    String resolved = planModelResolver.resolveRequestedModel(strictCfg.model());
                    if (StringUtils.hasText(resolved) && !req.isStrictModelSelection()) {
                        effectiveRequestedModel = resolved;
                        llmReq = llmReq.toBuilder().model(resolved).build();
                    }
                }
                if (strictCfg.maxTokens() != null && strictCfg.maxTokens() > 0) {
                    llmReq = llmReq.toBuilder().maxTokens(strictCfg.maxTokens()).build();
                }
                if (strictCfg.traits() != null && !strictCfg.traits().isEmpty()) {
                    llmReq = llmReq.toBuilder().traits(strictCfg.traits()).build();
                }
                boolean citationsEnabled = projectionPlan.defaults() != null
                        && Boolean.TRUE.equals(projectionPlan.defaults().citations());
                if (citationsEnabled) {
                    // In this codebase, verification only runs if request flag is true.
                    llmReq = llmReq.toBuilder().useVerification(true).build();
                }
            }
        }

        if (conversationFrame.enforcementActive() && conversationFrame.multimodalInputPresent()) {
            String configuredVisionModel = env == null ? null : env.getProperty("llm.vision.model");
            Optional<String> visionModel = verifiedVisionModel(planModelResolver, configuredVisionModel);
            if (visionModel.isEmpty()) {
                TraceStore.put("conversation.frame.visionRouteSelected", false);
                TraceStore.put("conversation.frame.wireAttemptCoverage", "not_observed");
                appendConversationFrameBreadcrumb(
                        conversationFrame,
                        "conversationFrame.visionUnavailableBreadcrumb");
                return ChatResult.of(
                        "evidence_needed: vision_model_unavailable / verify with PlanModelResolverTest and active llm profile",
                        "vision:unavailable",
                        false);
            }
            if (req.isStrictModelSelection() && !requestedModel.equals(visionModel.get())) {
                throw new com.example.lms.llm.ModelSelectionException("protocol_unsupported");
            }
            effectiveRequestedModel = visionModel.get();
            llmReq = llmReq.toBuilder().model(visionModel.get()).build();
            TraceStore.put("conversation.frame.visionRouteSelected", true);
            TraceStore.put("conversation.frame.wireAttemptCoverage", "not_observed");
            appendConversationFrameBreadcrumb(
                    conversationFrame,
                    "conversationFrame.visionSelectedBreadcrumb");
        }

        // Final copy for lambda expressions
        final String effectiveRequestedModelFinal = effectiveRequestedModel;

        // [Dual-Vision] VisionMode 寃곗젙
        String riskLevel = detectRisk(userQuery);
        VisionMode requestedVisionMode = decideVision(queryDomain, riskLevel, req, gctx != null ? gctx.getPlanId() : null);
        VisionMode visionMode = interactionPolicyDecision.enforceVision(requestedVisionMode);
        log.debug("[DualVision] queryDomain={}, visionMode={}", queryDomain, visionMode);

        // ?? 0-A) ?몄뀡ID ?뺢퇋??& 荑쇰━ ?ъ옉??Disambiguation) ?????????
        Long sessionIdLong = parseNumericSessionId(req.getSessionId());
        throwIfCancelled(sessionIdLong); // ??異붽?

        java.util.List<String> recentHistory = conversationContext.present()?conversationContext.interpretationHistory():(memoryReadEnabled && sessionIdLong != null)
                ? chatHistoryService.getFormattedRecentHistory(sessionIdLong, 5)
                : java.util.Collections.emptyList();

        String earlyRecentHistoryFallback = composeRecentHistoryFallback(userQuery, String.join("\n", recentHistory));
        if (interactionShortCircuitAllowed && earlyRecentHistoryFallback != null && !earlyRecentHistoryFallback.isBlank()) {
            try {
                TraceStore.put("chat.historyFallback.shortCircuit", true);
                TraceStore.put("chat.historyFallback.shortCircuitBefore", "disambiguation");
            } catch (RuntimeException ex) {
                ChatWorkflowTraceSuppressions.traceSuppressed("chat.historyFallback.shortCircuit.trace", ex);
            }
            return ChatResult.of(earlyRecentHistoryFallback, "history:fallback:recent", false);
        }

        String earlyDirectLiteralFallback = composeDirectLiteralAnswerFallback(userQuery);
        if (interactionShortCircuitAllowed && earlyDirectLiteralFallback != null && !earlyDirectLiteralFallback.isBlank()) {
            try {
                TraceStore.put("chat.directLiteralFallback.shortCircuit", true);
                TraceStore.put("chat.directLiteralFallback.shortCircuitBefore", "disambiguation");
            } catch (RuntimeException ex) {
                ChatWorkflowTraceSuppressions.traceSuppressed("chat.directLiteralFallback.shortCircuit.trace", ex);
            }
            return ChatResult.of(earlyDirectLiteralFallback, "direct:literal", false);
        }

        String earlyCurrentTurnMemoryFallback = composeCurrentTurnMemoryFallback(userQuery);
        if (interactionShortCircuitAllowed && earlyCurrentTurnMemoryFallback != null && !earlyCurrentTurnMemoryFallback.isBlank()) {
            try {
                TraceStore.put("chat.currentTurnMemoryFallback.shortCircuit", true);
                TraceStore.put("chat.currentTurnMemoryFallback.shortCircuitBefore", "disambiguation");
            } catch (RuntimeException ex) {
                ChatWorkflowTraceSuppressions.traceSuppressed("chat.currentTurnMemoryFallback.shortCircuit.trace", ex);
            }
            return ChatResult.of(earlyCurrentTurnMemoryFallback, "history:fallback:current-turn", false);
        }

        if (interactionShortCircuitAllowed && isExternalProofOnlyAnswerRequest(userQuery)) {
            mirrorAgentDbContextAvailabilityForAgentDebug(agentPipelineHealthController);
            mirrorLocalLlmSmokeHistoryOperatorActionForAgentDebug(localLlmSmokeHistoryDiagnosticsService);
            AgentVisibleDebugEvidenceBuilder.Snapshot earlyExternalProofSnapshot =
                    AgentVisibleDebugEvidenceBuilder.buildSnapshot(userQuery, debugAiMetricsService);
            String earlyExternalProofAnswer = composeAgentVisibleDebugFallback(
                    userQuery, earlyExternalProofSnapshot);
            if (earlyExternalProofAnswer != null && !earlyExternalProofAnswer.isBlank()) {
                try {
                    TraceStore.put("chat.agentDebugEvidence.externalProofOnlyShortCircuit", true);
                    TraceStore.put("chat.agentDebugEvidence.shortCircuit", true);
                    TraceStore.put("chat.agentDebugEvidence.shortCircuitBefore", "disambiguation");
                    TraceStore.put("chat.llmFallback.mode", "agent_debug_external_proof_only");
                } catch (RuntimeException ex) {
                    ChatWorkflowTraceSuppressions.traceSuppressed(
                            "chat.agentDebugEvidence.externalProofOnlyShortCircuit.trace", ex);
                }
                return ChatResult.of(earlyExternalProofAnswer, "agent-debug:fallback:evidence", false);
            }
        }

        if (interactionShortCircuitAllowed && isSupabaseOperationalJudgmentRequest(userQuery)) {
            mirrorAgentDbContextAvailabilityForAgentDebug(agentPipelineHealthController);
            mirrorLocalLlmSmokeHistoryOperatorActionForAgentDebug(localLlmSmokeHistoryDiagnosticsService);
            AgentVisibleDebugEvidenceBuilder.Snapshot earlySupabaseOperationalSnapshot =
                    AgentVisibleDebugEvidenceBuilder.buildSnapshot(userQuery, debugAiMetricsService);
            String earlySupabaseOperationalAnswer =
                    composeAgentVisibleDebugFallback(userQuery, earlySupabaseOperationalSnapshot);
            if (earlySupabaseOperationalAnswer != null && !earlySupabaseOperationalAnswer.isBlank()) {
                try {
                    TraceStore.put("chat.agentDebugEvidence.supabaseOperationalShortCircuit", true);
                    TraceStore.put("chat.agentDebugEvidence.shortCircuit", true);
                    TraceStore.put("chat.agentDebugEvidence.shortCircuitBefore", "disambiguation");
                    TraceStore.put("chat.llmFallback.mode", "agent_debug_supabase_operational");
                } catch (RuntimeException ex) {
                    ChatWorkflowTraceSuppressions.traceSuppressed(
                            "chat.agentDebugEvidence.supabaseOperationalShortCircuit.trace", ex);
                }
                return ChatResult.of(earlySupabaseOperationalAnswer, "agent-debug:supabase-operational:evidence", false);
            }
        }

        String earlyCurrentModeStatusFallback = composeCurrentModeStatusFallback(userQuery, req);
        if (interactionShortCircuitAllowed && earlyCurrentModeStatusFallback != null && !earlyCurrentModeStatusFallback.isBlank()) {
            try {
                TraceStore.put("chat.uiModeStatus.directAnswer", true);
                TraceStore.put("chat.uiModeStatus.shortCircuit", true);
                TraceStore.put("chat.uiModeStatus.shortCircuitBefore", "disambiguation");
                TraceStore.put("chat.uiModeStatus.searchMode",
                        req != null && req.getSearchMode() != null ? req.getSearchMode().name() : "AUTO");
                TraceStore.put("chat.uiModeStatus.useWebSearch", req != null && req.isUseWebSearch());
                TraceStore.put("chat.uiModeStatus.useRag", req != null && req.isUseRag());
                TraceStore.put("answer.mode", "ui-mode:local:evidence");
            } catch (RuntimeException ex) {
                ChatWorkflowTraceSuppressions.traceSuppressed("chat.uiModeStatus.shortCircuit.trace", ex);
            }
            return ChatResult.of(earlyCurrentModeStatusFallback, "ui-mode:local:evidence",
                    req != null && req.isUseRag());
        }

        if (interactionShortCircuitAllowed && AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(userQuery)) {
            mirrorAgentDbContextAvailabilityForAgentDebug(agentPipelineHealthController);
            mirrorLocalLlmSmokeHistoryOperatorActionForAgentDebug(localLlmSmokeHistoryDiagnosticsService);
            AgentVisibleDebugEvidenceBuilder.Snapshot earlyAgentDebugSnapshot =
                    AgentVisibleDebugEvidenceBuilder.buildSnapshot(userQuery, debugAiMetricsService);
            String earlyAgentDebugDirectAnswer = composeAgentVisibleDebugFallback(
                    userQuery, earlyAgentDebugSnapshot);
            if (earlyAgentDebugDirectAnswer != null && !earlyAgentDebugDirectAnswer.isBlank()) {
                try {
                    TraceStore.put("chat.agentDebugEvidence.directAnswer", true);
                    TraceStore.put("chat.agentDebugEvidence.shortCircuit", true);
                    TraceStore.put("chat.agentDebugEvidence.shortCircuitBefore", "disambiguation");
                    TraceStore.put("chat.llmFallback.mode", "agent_debug_evidence");
                } catch (RuntimeException ex) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("chat.agentDebugEvidence.shortCircuit.trace", ex);
                }
                return ChatResult.of(earlyAgentDebugDirectAnswer, "agent-debug:fallback:evidence", false);
            }
        }

        DisambiguationResult dr;
        // 蹂댁“ LLM ?뚮줈媛 ?대? OPEN?대㈃ 遺덊븘?뷀븳 ?몄텧???섏? ?딄퀬 ?먮Ц?쇰줈 吏꾪뻾
        if (directRetrievalOffMode) {
            dr = new DisambiguationResult();
            dr.setRewrittenQuery(userQuery);
            dr.setConfidence("low");
            dr.setScore(0.0);
            try {
                TraceStore.put("chat.disambiguation.skipped", true);
                TraceStore.put("chat.disambiguation.skipReason", "retrieval_off_direct");
            } catch (RuntimeException ex) {
                ChatWorkflowTraceSuppressions.traceSuppressed("chat.disambiguation.skip.trace", ex);
            }
        } else if (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.DISAMBIGUATION_CLARIFY)) {
            dr = new DisambiguationResult();
            dr.setRewrittenQuery(userQuery);
            dr.setConfidence("low");
            dr.setScore(0.0);
        } else {
            dr = disambiguationService.clarify(userQuery, recentHistory);
        }

        final String finalQuery;
        if (dr != null && dr.isConfident()
                && dr.getRewrittenQuery() != null && !dr.getRewrittenQuery().isBlank()) {
            finalQuery = dr.getRewrittenQuery();
        } else {
            finalQuery = userQuery;
        }

        // 0-B) Subject / Domain / Strategy 遺꾩꽍 (?쒖닔 ?먮컮)
        SubjectAnalysis analysis = subjectResolver.analyze(finalQuery, recentHistory, dr);
        String domain = domainDetector.detect(finalQuery, dr);
        DomainStrategyFactory.SearchStrategy searchStrategy = domainStrategyFactory.createStrategy(analysis, domain);

        if (log.isDebugEnabled()) {
            // SLF4J placeholder??臾몄옄??由ы꽣???덉뿉???ъ슜?댁빞 ?섎ŉ, 遺덊븘?뷀븳 ?곗샂??")???쒓굅?⑸땲??
            log.debug("[Domain] queryHash={}, queryLength={}, category={}, domain={}, profile={}",
                    SafeRedactor.hash12(finalQuery),
                    finalQuery == null ? 0 : finalQuery.length(),
                    analysis.getCategory(), domain, searchStrategy.getSearchProfile());
        }

        // ?? 0-1) Verbosity 媛먯? & ?뱀뀡 ?ㅽ럺 ?????????????????????????
        VerbosityProfile detectedVp = verbosityDetector.detect(finalQuery);
        String intent;
        if (directRetrievalOffMode) {
            intent = "GENERAL";
            try {
                TraceStore.put("queryTransformer.bypassed", "true");
                TraceStore.put("queryTransformer.reason", "retrieval_off_direct");
                TraceStore.put("chat.intentInference.skipped", true);
                TraceStore.put("chat.intentInference.skipReason", "retrieval_off_direct");
            } catch (RuntimeException ex) {
                ChatWorkflowTraceSuppressions.traceSuppressed("chat.intentInference.skip.trace", ex);
            }
        } else {
            intent = inferIntent(finalQuery);
        }
        // Pass detected domain into section spec generator so domain-specific templates
        // can be applied.
        List<String> sections = sectionSpecGenerator.generate(intent, domain, detectedVp.hint());

        // ?? 1) 寃???듯빀: Self-Ask ??HybridRetriever ??Cross-Encoder Rerank ?
        // 0-2) Retrieval ?뚮옒洹?

        boolean useWeb = req.isUseWebSearch() || searchStrategy.isUseWebSearch();
        boolean useRag = req.isUseRag() || searchStrategy.isUseVectorStore();
        if (req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
                || Boolean.FALSE.equals(req.getUseWebSearch())) {
            useWeb = false;
        }
        if (Boolean.FALSE.equals(req.getUseRag())) {
            useRag = false;
        }

        // [FUTURE_TECH FIX] 理쒖떊/誘몄텧??李⑥꽭?) ?쒗뭹 荑쇰━????理쒖떊???곗꽑 + 援щ쾭??Vector ?ㅼ뿼 諛⑹?
        boolean futureTech = latestTechEnabled && isLatestTechQuery(finalQuery);
        if (futureTech && latestTechAutoDisableVector) {
            useWeb = true;
            useRag = false;
            log.info("[FutureTech] Web forced ON, Vector forced OFF. queryHash={}, queryLength={}",
                    SafeRedactor.hash12(finalQuery),
                    finalQuery == null ? 0 : finalQuery.length());
        }

        // plan hints: cap allowWeb/allowRag
        if (planHints != null) {
            if (planHints.allowWeb() != null && !planHints.allowWeb())
                useWeb = false;
            if (planHints.allowRag() != null && !planHints.allowRag())
                useRag = false;
        }
        final RetrievalReleaseContract retrievalReleaseContract = buildRetrievalReleaseContract(
                req,
                rawRetrievalIntent,
                useWeb,
                useRag,
                evidenceReleaseRequired);

        // 1) (?듭뀡) ??寃??怨꾪쉷 諛??ㅽ뻾
        // ?? 蹂댁“ LLM ?μ븷 ?좏샇瑜?癒쇱? 怨꾩궛?섏뿬 ?뚮옒??以묐웾 ?④퀎瑜??ъ쟾 李⑤떒 ??
        // ?? Orchestration signal bus (STRIKE/COMPRESSION/BYPASS) ????????????
        OrchestrationSignals sig = OrchestrationSignals.compute(finalQuery, nightmareBreaker, gctx);
        if (gctx != null) {
            gctx.setStrikeMode(sig.strikeMode());
            gctx.setCompressionMode(sig.compressionMode());
            gctx.setBypassMode(sig.bypassMode());
            gctx.setWebRateLimited(sig.webRateLimited());
            if (gctx.getBypassReason() == null || gctx.getBypassReason().isBlank()) {
                gctx.setBypassReason(sig.reason());
            }
        }
        // vp??final ?좎뼵 ??議곌굔遺濡?????踰덈쭔 珥덇린?????뚮떎?먯꽌 effectively final濡??ъ슜 媛??        final VerbosityProfile vp;
        final VerbosityProfile vp;
        if (sig.strikeMode()) {
            // STRIKE 紐⑤뱶: 異쒕젰? 吏㏐퀬 ?듭떖留???꾩븘???덉씠?몃━諛??곹솴?먯꽌 fail-fast)
            int maxTokens = Math.min(detectedVp.targetTokenBudgetOut(), 768);
            int minWords = Math.min(detectedVp.minWordCount(), 90);
            vp = new VerbosityProfile("brief", minWords, maxTokens, detectedVp.audience(), detectedVp.citationStyle(),
                    detectedVp.sections());
        } else {
            vp = detectedVp;
        }
        OrchestrationHints hints = null;
        Map<String, Object> metaHints = null;

        List<String> planned = List.of();
        SearchPolicyDecision searchPolicyDecision = null;
        List<dev.langchain4j.rag.content.Content> fused = List.of();
        // Needle probe (2-pass) state (used for trace + outcome reward)
        EvidenceSignals needleBeforeSignals = EvidenceSignals.empty();
        EvidenceSignals needleAfterSignals = EvidenceSignals.empty();
        List<String> needlePlanned = List.of();
        java.util.Set<String> needleUrls = java.util.Set.of();
        java.util.List<Content> needleDocsForReward = java.util.List.of();
        boolean needleExecuted = false;
        boolean preLlmRetrievalBudgetLow = false;
        if (useWeb) {

            // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_PLANNER_GATE
            boolean allowPlannerByPolicy = true;
            try {
                if (stagePolicy != null && stagePolicy.isEnabled() && sig != null) {
                    allowPlannerByPolicy = stagePolicy.isStageEnabled(OrchStageKeys.PLAN_QUERY_PLANNER, sig.modeLabel(),
                            true);
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("stagePolicy.plannerGate", ignore); }

            // SearchPolicy: decide once per request (mode-based slicing/topK/expansion)
            try {
                boolean nightmareModeForPolicy = nightmareBreaker != null
                        && nightmareBreaker.isOpen(NightmareKeys.CHAT_DRAFT);
                Map<String, Object> spMeta = new HashMap<>();
                if (sig != null) {
                    spMeta.put("strikeMode", sig.strikeMode());
                    spMeta.put("compressionMode", sig.compressionMode());
                    spMeta.put("bypassMode", sig.bypassMode());
                    spMeta.put("webRateLimited", sig.webRateLimited());
                }
                spMeta.put("nightmareMode", nightmareModeForPolicy);
                if (req != null && req.getSearchMode() != null) {
                    spMeta.put("searchMode", req.getSearchMode().name());
                }
                if (creativeEmergenceEligible(gctx)) {
                    spMeta.put("creative.emergence.active", true);
                    spMeta.put("creative.emergence.profile", gctx.getPlanOverride("creative.emergence.profile"));
                    spMeta.put("creative.emergence.requestedOptionsHash",
                            gctx.getPlanOverride("creative.emergence.requestedOptionsHash"));
                    spMeta.put("promptPose.application.intentSlot",
                            gctx.getPlanOverride("promptPose.application.intentSlot"));
                    spMeta.put("privacy.boundary.enforce",
                            gctx.planBool("privacy.boundary.enforce", false));
                    spMeta.put("creative.emergence.search.temperature",
                            gctx.getPlanOverride("creative.emergence.search.temperature"));
                    spMeta.put("creative.emergence.search.rate",
                            gctx.getPlanOverride("creative.emergence.search.rate"));
                    spMeta.put("creative.emergence.candidate.temperature",
                            gctx.getPlanOverride("creative.emergence.candidate.temperature"));
                    spMeta.put("creative.emergence.candidate.topP",
                            gctx.getPlanOverride("creative.emergence.candidate.topP"));
                    spMeta.put("creative.emergence.final.temperature",
                            gctx.getPlanOverride("creative.emergence.final.temperature"));
                    spMeta.put("creative.emergence.final.topP",
                            gctx.getPlanOverride("creative.emergence.final.topP"));
                    spMeta.put("creative.emergence.selfAsk.temperature",
                            gctx.getPlanOverride("creative.emergence.selfAsk.temperature"));
                }
                searchPolicyDecision = searchPolicyEngine.decide(finalQuery, spMeta);
                if (searchPolicyDecision != null) {
                    String policyMode = searchPolicyDecision.mode().name();
                    String rewriteTemperatureProfile = SafeRedactor.traceLabelOrFallback(
                            searchPolicyDecision.rewriteTemperatureProfile(), "unknown");
                    TraceStore.put("search.policy.mode", policyMode);
                    TraceStore.put("search.policy.reason", SafeRedactor.traceLabelOrFallback(searchPolicyDecision.reason(), "unknown"));
                    TraceStore.put("search.policy.rewriteTemperatureProfile", rewriteTemperatureProfile);
                    TraceStore.put("search.policy.rewriteValidationTemperature",
                            searchPolicyDecision.rewriteValidationTemperature());
                    TraceStore.put("search.policy.rewriteExplorationTemperature",
                            searchPolicyDecision.rewriteExplorationTemperature());
                    TraceStore.put("search.policy.rewriteExplorationRate",
                            searchPolicyDecision.rewriteExplorationRate());
                    TraceStore.put("web.query.rewrite.recallModeRequested", "RECALL".equalsIgnoreCase(policyMode));
                    TraceStore.put("web.query.rewrite.requestedTemperatureProfile", rewriteTemperatureProfile);
                    TraceStore.put("web.query.rewrite.requestedValidationTemperature",
                            searchPolicyDecision.rewriteValidationTemperature());
                    TraceStore.put("web.query.rewrite.requestedExplorationTemperature",
                            searchPolicyDecision.rewriteExplorationTemperature());
                    TraceStore.put("web.query.rewrite.requestedExplorationRate",
                            searchPolicyDecision.rewriteExplorationRate());
                }
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("searchPolicy.decide", ignore);
            }
            if (futureTech && latestTechAutoDisableVector) {
                planned = List.of(finalQuery);
            } else if (sig != null && sig.auxLlmDown()) {
                // Aux LLM is degraded/hard-down: bypass planner and use the original query.
                planned = List.of(finalQuery);
            } else if (!allowPlannerByPolicy) {
                planned = List.of(finalQuery);
            } else if (forceLightSearchMode) {
                planned = List.of(finalQuery);
                try {
                    TraceStore.put("search.mode.lightPlannerFanout.skipped", true);
                    TraceStore.put("search.mode.lightPlannerFanout.skipReason", "force_light");
                } catch (RuntimeException ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("searchMode.lightPlannerFanout", ignore);
                }
            } else {
                int maxBranches = gctx != null ? gctx.planInt("expand.queryBurst.count", 2) : 2;
                // safety clamp: keep planner branching bounded
                maxBranches = Math.max(2, Math.min(maxBranches, 32));
                if (searchPolicyDecision != null) {
                    maxBranches = searchPolicyEngine.tunePlannerMaxQueries(maxBranches, searchPolicyDecision);
                }
                planned = routingPlanService.plan(finalQuery, /* assistantDraft */ null, /* maxBranches */ maxBranches);
                if (planned == null || planned.isEmpty()) {
                    planned = List.of(finalQuery);
                }
            }

            // Apply policy variants (deterministic; does not call LLMs)
            if (searchPolicyDecision != null) {
                planned = searchPolicyEngine.apply(planned, finalQuery, searchPolicyDecision);
                if (planned == null || planned.isEmpty()) {
                    planned = List.of(finalQuery);
                }
            }

            // Planner can trip request-scoped aux-down / irregularity signals (e.g.
            // QueryTransformer soft-timeouts).
            // Recompute orchestration signals so plate + downstream handlers see up-to-date
            // flags.
            sig = OrchestrationSignals.compute(finalQuery, nightmareBreaker, gctx);
            if (gctx != null) {
                gctx.setStrikeMode(sig.strikeMode());
                gctx.setCompressionMode(sig.compressionMode());
                gctx.setBypassMode(sig.bypassMode());
                gctx.setWebRateLimited(sig.webRateLimited());
                if (gctx.getBypassReason() == null || gctx.getBypassReason().isBlank()) {
                    gctx.setBypassReason(sig.reason());
                }
            }
            // Nine Art Plate: decide (apply is request-scoped via metadata hints)
            PlateContext plateCtx = new PlateContext(
                    useWeb, useRag,
                    /* sessionRecur */ 0, /* evidenceCount */ 0,
                    /* authority */ 0.0, /* noisy */ false,
                    /* webGate */ (useWeb ? 0.55 : 0.30),
                    /* vectorGate */ (useRag ? 0.65 : 0.30),
                    /* memoryGate */ 0.30,
                    /* recallNeed */ (useRag ? 0.70 : 0.50));
            ArtPlateSpec plate = nineArtPlateGate.decide(plateCtx);

            boolean nightmareMode = nightmareBreaker != null
                    && nightmareBreaker.isOpen(NightmareKeys.CHAT_DRAFT);

            boolean auxLlmDown = sig.auxLlmDown();
            boolean auxDegraded = sig.auxDegraded();
            boolean auxHardDown = sig.auxHardDown();

            hints = OrchestrationHints.builder()
                    .plateId(plate.id())
                    .webTopK(plate.webTopK())
                    .vecTopK(plate.vecTopK())
                    .webBudgetMs((long) plate.webBudgetMs())
                    .vecBudgetMs((long) plate.vecBudgetMs())
                    // Soft aux degradation should not fully disable analysis/rerank.
                    // Only hard-down disables these building blocks; strike/compression gating
                    // happens below.
                    .enableSelfAsk(!nightmareMode && !auxHardDown)
                    .enableAnalyze(!nightmareMode && !auxHardDown)
                    .enableCrossEncoder(plate.crossEncoderOn() && !nightmareMode && !auxHardDown)
                    .nightmareMode(nightmareMode)
                    .auxLlmDown(auxLlmDown)
                    .allowWeb(useWeb)
                    .allowRag(useRag)
                    .build();

            // SearchPolicy: tune retrieval breadth (topK) for this request.
            // (Query slicing/expansion was already applied to the planned list.)
            if (searchPolicyDecision != null && hints != null) {
                try {
                    Integer baseWebTopK = hints.getWebTopK();
                    Integer baseVecTopK = hints.getVecTopK();
                    int tunedWeb = (baseWebTopK == null) ? 5
                            : searchPolicyEngine.tuneTopK(baseWebTopK, searchPolicyDecision);
                    int tunedVec = (baseVecTopK == null) ? 10
                            : searchPolicyEngine.tuneVecTopK(baseVecTopK, searchPolicyDecision);
                    hints = hints.toBuilder()
                            .webTopK(tunedWeb)
                            .vecTopK(tunedVec)
                            .build();
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("searchPolicy.tuneHints", ignore); }
            }

            // STRIKE/COMPRESSION/BYPASS mode wiring (same signal bus for all components)
            if (hints != null) {
                boolean strikeMode = sig.strikeMode();
                boolean compressionMode = sig.compressionMode();
                boolean bypassMode = sig.bypassMode();
                hints = hints.toBuilder()
                        .strikeMode(strikeMode)
                        .compressionMode(compressionMode)
                        .bypassMode(bypassMode)
                        .webRateLimited(sig.webRateLimited())
                        .bypassReason(gctx != null ? gctx.getBypassReason() : sig.reason())
                        // STRIKE/BYPASS: hard safety/escape-hatch => disable heavy steps.
                        // COMPRESSION: budget-saving mode; keep core reasoning (Analyze, rerank)
                        // available.
                        .enableSelfAsk(hints.isEnableSelfAsk() && !strikeMode && !compressionMode && !bypassMode)
                        .enableAnalyze(hints.isEnableAnalyze() && !strikeMode && !bypassMode)
                        .enableCrossEncoder(hints.isEnableCrossEncoder() && !strikeMode && !bypassMode)
                        .build();
            }

            metaHints = new HashMap<>();
            metaHints.put("plateId", hints.getPlateId());
            // [PATCH] Propagate request searchMode (OFF/FORCE_*) so retrieval handlers can
            // honor it.
            if (req != null && req.getSearchMode() != null) {
                metaHints.put("searchMode", req.getSearchMode().name());
            }
            metaHints.put("webTopK", hints.getWebTopK());
            metaHints.put("vecTopK", hints.getVecTopK());
            metaHints.put("webBudgetMs", hints.getWebBudgetMs());
            metaHints.put("vecBudgetMs", hints.getVecBudgetMs());
            // ?쇰? ?쇱씠釉뚮윭由щ뒗 boolean??metadata濡??꾨떖?????댁뒋媛 ?덉뼱 臾몄옄?대줈 ???
            metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
            metaHints.put("enableAnalyze", String.valueOf(hints.isEnableAnalyze()));
            metaHints.put("enableCrossEncoder", String.valueOf(hints.isEnableCrossEncoder()));
            metaHints.put("nightmareMode", String.valueOf(hints.isNightmareMode()));
            metaHints.put("auxLlmDown", String.valueOf(hints.isAuxLlmDown()));
            // UAW: expose aux soft/hard health for downstream diagnostics
            metaHints.put("auxDegraded", String.valueOf(sig.auxDegraded()));
            metaHints.put("auxHardDown", String.valueOf(sig.auxHardDown()));
            metaHints.put("allowWeb", String.valueOf(hints.isAllowWeb()));
            metaHints.put("allowRag", String.valueOf(hints.isAllowRag()));

            // Surface policy decision to downstream retrievers (no hard dependency).
            if (searchPolicyDecision != null) {
                try {
                    searchPolicyEngine.enrichMeta(metaHints, searchPolicyDecision);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("searchPolicy.enrichMeta", ignore); }
            }

            final boolean callerEnableSelfAsk = hints != null && hints.isEnableSelfAsk();
            try {
                if (planHintApplier != null && planHints != null) {
                    planHintApplier.applyToHintsAndMeta(planHints, hints, metaHints);
                    // Surface guard knobs to retrieval via metadata (used by WebSearchRetriever
                    // siteFilter skip policy).
                    if (gctx != null && gctx.getMinCitations() != null) {
                        metaHints.putIfAbsent("minCitations", gctx.getMinCitations());
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("planHints.applyMeta", ignore); }
            if (hints != null && "true".equalsIgnoreCase(String.valueOf(metaHints.get("selfask.enabled")))) {
                boolean safeSelfAskOverride = !nightmareMode
                        && !auxHardDown
                        && hints.isAllowWeb()
                        && (sig == null || (!sig.strikeMode() && !sig.compressionMode() && !sig.bypassMode()));
                hints.setEnableSelfAsk(safeSelfAskOverride);
                metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
                if (!safeSelfAskOverride) {
                    metaHints.put("selfask.disabled.reason", "safety-gate");
                }
            }

            // plan.when gate (chat path): a conclusively FALSE verdict suppresses
            // plan-driven expansion while preserving caller-set flags; UNKNOWN and
            // TRUE keep the plan's expansion knobs.
            if (planExecSpec != null && planWhen != null
                    && planWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.FALSE
                    && planExecSpec.declaresExpansion()
                    && hints != null && metaHints != null) {
                if (!callerEnableSelfAsk && hints.isEnableSelfAsk()) {
                    hints.setEnableSelfAsk(false);
                    TraceStore.put("plan.selfAsk.gated", "when_false");
                    metaHints.put("plan.selfAsk.gated", "when_false");
                }
                metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
                stripPlanExpansionKeys(metaHints);
                metaHints.put("plan.expansion.gated", "when_false");
            }

            // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_CLAMP
            applyStagePolicyClamp(sig, hints, metaHints, nightmareMode, auxHardDown);

            // [UAW] Policy-level conditional demotion:
            // - When web is effectively hard-down (both engines skipped / hybrid breaker
            // open),
            // disable the web stage for this request (fail-soft: rely on vector / other).
            // - Also disable web-dependent Analyze/SelfAsk so we don't spin on empty web
            // merges.
            boolean webHardDownStageOff = false;
            try {
                webHardDownStageOff = (sig != null && sig.webRateLimited())
                        || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited.effective"))
                        || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited"));
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("webHardDown.stageOffCheck", ignore); webHardDownStageOff = (sig != null && sig.webRateLimited());
            }
            if (webHardDownStageOff && hints != null) {
                try {
                    TraceStore.put("orch.webHardDown.stageOff", true);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("webHardDown.stageOffTrace", ignore); }
                hints.setAllowWeb(false);
                hints.setEnableAnalyze(false);
                hints.setEnableSelfAsk(false);
                metaHints.put("allowWeb", "false");
                metaHints.put("enableAnalyze", "false");
                metaHints.put("enableSelfAsk", "false");
            }

            preLlmRetrievalBudgetLow = false;
            TimeBudget preLlmRequestBudget = TimeBudgetContext.get();
            final long preLlmRemainingMs = preLlmRequestBudget == null
                    ? Long.MAX_VALUE : preLlmRequestBudget.remainingMillis();
            if (preLlmRemainingMs != Long.MAX_VALUE) {
                try {
                    TraceStore.put("retrieval.preLlm.requestBudget.remainingMs", preLlmRemainingMs);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("retrieval.preLlmBudgetTrace", ignore); }

                final long answerReserveMs = 5_000L;
                final long retrievalBudgetPoolMs = Math.max(0L, preLlmRemainingMs - answerReserveMs);
                int preLlmMaxQueries = planned == null || planned.isEmpty() ? 1 : planned.size();
                if (preLlmRemainingMs <= 20_000L) {
                    preLlmMaxQueries = 1;
                } else if (preLlmRemainingMs <= 45_000L) {
                    preLlmMaxQueries = Math.min(preLlmMaxQueries, 2);
                } else if (preLlmRemainingMs <= 90_000L) {
                    preLlmMaxQueries = Math.min(preLlmMaxQueries, 3);
                }
                if (planned != null && planned.size() > preLlmMaxQueries) {
                    planned = planned.stream().limit(preLlmMaxQueries).toList();
                    try {
                        TraceStore.put("retrieval.preLlm.queryFanout.capped", true);
                        TraceStore.put("retrieval.preLlm.queryFanout.applied", preLlmMaxQueries);
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("retrieval.preLlmQueryFanoutTrace", ignore); }
                }

                long constrainedStageMaxMs = preLlmRemainingMs <= 20_000L ? 1_200L
                        : preLlmRemainingMs <= 45_000L ? 2_000L
                        : preLlmRemainingMs <= 90_000L ? 3_000L
                        : 5_000L;
                long activeRetrievalLanes = Math.max(1L,
                        (hints != null && hints.isAllowWeb() ? 1L : 0L)
                                + (hints != null && hints.isAllowRag() ? 1L : 0L));
                long perLaneBudgetMs = retrievalBudgetPoolMs <= 0L ? 0L
                        : Math.min(constrainedStageMaxMs,
                        Math.max(300L, retrievalBudgetPoolMs / Math.max(1L, activeRetrievalLanes * preLlmMaxQueries)));
                Long cappedWebBudgetMs = hints == null || hints.getWebBudgetMs() == null
                        ? perLaneBudgetMs : Math.min(hints.getWebBudgetMs(), perLaneBudgetMs);
                Long cappedVecBudgetMs = hints == null || hints.getVecBudgetMs() == null
                        ? perLaneBudgetMs : Math.min(hints.getVecBudgetMs(), perLaneBudgetMs);
                preLlmRetrievalBudgetLow = retrievalBudgetPoolMs < 600L;
                if (hints != null) {
                    hints = hints.toBuilder()
                            .webBudgetMs(cappedWebBudgetMs)
                            .vecBudgetMs(cappedVecBudgetMs)
                            .allowWeb(hints.isAllowWeb() && !preLlmRetrievalBudgetLow)
                            .allowRag(hints.isAllowRag() && !preLlmRetrievalBudgetLow)
                            .enableAnalyze(hints.isEnableAnalyze() && !preLlmRetrievalBudgetLow)
                            .enableSelfAsk(hints.isEnableSelfAsk() && !preLlmRetrievalBudgetLow)
                            .enableCrossEncoder(hints.isEnableCrossEncoder() && !preLlmRetrievalBudgetLow)
                            .build();
                    metaHints.put("webBudgetMs", hints.getWebBudgetMs());
                    metaHints.put("vecBudgetMs", hints.getVecBudgetMs());
                    metaHints.put("allowWeb", String.valueOf(hints.isAllowWeb()));
                    metaHints.put("allowRag", String.valueOf(hints.isAllowRag()));
                    metaHints.put("enableAnalyze", String.valueOf(hints.isEnableAnalyze()));
                    metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
                    metaHints.put("enableCrossEncoder", String.valueOf(hints.isEnableCrossEncoder()));
                }
                try {
                    TraceStore.put("retrieval.preLlm.budgetGuard.applied", true);
                    TraceStore.put("retrieval.preLlm.budgetGuard.poolMs", retrievalBudgetPoolMs);
                    TraceStore.put("retrieval.preLlm.webBudgetMs", cappedWebBudgetMs);
                    TraceStore.put("retrieval.preLlm.vecBudgetMs", cappedVecBudgetMs);
                    TraceStore.put("retrieval.preLlm.budgetLow", preLlmRetrievalBudgetLow);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("retrieval.preLlmBudgetGuardTrace", ignore); }
            }

            // ??PERF: controller媛 ?대? ?섑뻾??web search 寃곌낵(Trace ?ы븿)瑜??ъ궗?⑺빐
            // WebSearchRetriever/HybridRetriever?먯꽌 ?숈씪 荑쇰━ ?ш??됱쓣 諛⑹??쒕떎.
            if (!preLlmRetrievalBudgetLow && (hints.isAllowWeb() || forceLightSearchMode) && externalCtxProvider != null) {
                try {
                    String q0 = (planned != null && !planned.isEmpty()) ? planned.get(0) : finalQuery;
                    List<String> prefetched = externalCtxProvider.apply(q0);
                    if (prefetched != null && !prefetched.isEmpty()) {
                        metaHints.put("prefetch.web.query", q0);
                        metaHints.put("prefetch.web.snippets", prefetched);
                        addPrefetchDiagnostics(metaHints, q0, prefetched);
                    }
                } catch (Exception e) {
                    log.debug("[WebPrefetch] externalCtxProvider failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                }
            }

            // Domain/tile hints for retrieval & alias correction (all-rounder stability)
            metaHints.put("intent.domain", domain);
            metaHints.put("vp.topTile", mapDomainToTile(domain));

            // UAW: Orchestration mode flags
            metaHints.put("strikeMode", String.valueOf(hints.isStrikeMode()));
            metaHints.put("compressionMode", String.valueOf(hints.isCompressionMode()));
            metaHints.put("bypassMode", String.valueOf(hints.isBypassMode()));
            metaHints.put("webRateLimited", String.valueOf(hints.isWebRateLimited()));
            if (hints.getBypassReason() != null && !hints.getBypassReason().isBlank()) {
                metaHints.put("bypassReason", hints.getBypassReason());
            }

            try {
                TraceStore.put("plate.id", hints.getPlateId());
                TraceStore.put("plate.webTopK", hints.getWebTopK());
                TraceStore.put("plate.vecTopK", hints.getVecTopK());
                TraceStore.put("plate.webBudgetMs", hints.getWebBudgetMs());
                TraceStore.put("plate.vecBudgetMs", hints.getVecBudgetMs());
                TraceStore.put("plate.crossEncoder", hints.isEnableCrossEncoder());
                TraceStore.put("nightmare.mode", hints.isNightmareMode());
                TraceStore.put("aux.llm.down", hints.isAuxLlmDown());
                TraceStore.put("aux.llm.degraded", auxDegraded);
                TraceStore.put("aux.llm.hardDown", auxHardDown);

                // ??UX: trace/diagnostics?먯꽌 "??BYPASS/STRIKE媛 耳쒖죱?붿?"瑜????붾㈃?먯꽌 ?뺤씤?????덇쾶
                // OrchestrationSignals 湲곕컲???붿빟/?ъ쑀瑜?硫뷀?濡쒕룄 ?④릿??
                TraceStore.put("orch.mode", (sig != null ? sig.modeLabel() : ""));
                TraceStore.put("orch.strike", hints.isStrikeMode());
                TraceStore.put("orch.compression", hints.isCompressionMode());
                TraceStore.put("orch.bypass", hints.isBypassMode());
                TraceStore.put("orch.webRateLimited", hints.isWebRateLimited());
                TraceStore.put("orch.auxLlmDown", hints.isAuxLlmDown());
                TraceStore.put("orch.auxDegraded", auxDegraded);
                TraceStore.put("orch.auxHardDown", auxHardDown);
                if (sig != null) {
                    TraceStore.put("orch.highRisk", sig.highRisk());
                    TraceStore.put("orch.irregularity", sig.irregularity());
                    TraceStore.put("orch.reasons", sig.reasons() == null ? java.util.List.of() : sig.reasons().stream()
                            .map(reason -> SafeRedactor.traceLabelOrFallback(reason, "unknown"))
                            .toList());
                    TraceStore.put("orch.reason", SafeRedactor.traceLabelOrFallback(sig.reason(), "unknown"));

                    // MERGE_HOOK:PROJ_AGENT::ORCH_PARTS_TABLE_CALL
                    boolean plannerUsed = planned != null && planned.size() > 1;
                    boolean plannerAllowedByStagePolicy = stagePolicy == null
                            || stagePolicy.isStageEnabled(OrchStageKeys.PLAN_QUERY_PLANNER, sig.modeLabel(), true);
                    boolean qtxAllowedByStagePolicy = stagePolicy == null
                            || stagePolicy.isStageEnabled(OrchStageKeys.QUERY_TRANSFORMER, sig.modeLabel(), true);

                    sig.emitPartsPlanToTrace(
                            useWeb,
                            useRag,
                            hints,
                            plannerUsed,
                            plannerAllowedByStagePolicy,
                            qtxAllowedByStagePolicy,
                            planned != null ? planned.size() : 0,
                            effectiveVerificationEnabled(llmReq != null ? llmReq.getUseVerification() : null));

                    // Debug: quantitative mode score + leave-one-out ablation
                    sig.emitDebugScorecardToTrace();

                    // Debug: auto report (Top-N causes + probe shortcuts)
                    com.example.lms.orchestration.OrchAutoReporter.emitToTrace();
                } else if (hints.getBypassReason() != null && !hints.getBypassReason().isBlank()) {
                    TraceStore.put("orch.reason", SafeRedactor.traceLabelOrFallback(hints.getBypassReason(), "unknown"));
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("orch.modeTrace", ignore); }

            int plateLimit = Math.max(hybridTopK, Math.max(plate.webTopK(), plate.vecTopK()) * 3);

            if (preLlmRetrievalBudgetLow) {
                fused = List.of();
                try {
                    TraceStore.put("retrieval.preLlm.heavyRetrieval.skipped", true);
                    TraceStore.put("retrieval.preLlm.heavyRetrieval.skipReason", "request_budget_low");
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("retrieval.preLlmHeavySkipTrace", ignore); }
            } else if (futureTech && latestTechAutoDisableVector) {
                // Web-only retrieval (still plate-scoped via metadata hints)
                var qObj = QueryUtils.buildQuery(finalQuery, sessionIdLong, null, metaHints);

                List<Content> tmp = null;

                boolean webHardDownNow = false;
                try {
                    long skipped = TraceStore.getLong("web.await.skipped.count");
                    webHardDownNow = (hints != null && hints.isWebRateLimited())
                            || Boolean.TRUE.equals(TraceStore.get("web.hardDown"))
                            || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited.effective"))
                            || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited"))
                            || skipped >= 2;
                } catch (Exception ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("webHardDown.nowCheck", ignore); webHardDownNow = (hints != null && hints.isWebRateLimited());
                }

                if (hints != null && hints.isAllowWeb() && !webHardDownNow) {
                    if (shouldUseAnalyzeWeb(metaHints) && analyzeWebSearchRetriever != null) {
                        try {
                            tmp = analyzeWebSearchRetriever.retrieve(qObj);
                        } catch (Exception e) {
                            TraceStore.put("retrieval.analyzeWeb.error", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
                        }
                    }
                    if (tmp == null || tmp.isEmpty()) {
                        tmp = webSearchRetriever.retrieve(qObj);
                    }
                } else {
                    try {
                        TraceStore.put("retrieval.web.skipped", true);
                        TraceStore.put("retrieval.web.skipped.reason",
                                webHardDownNow ? "webHardDown" : "allowWeb=false");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("retrieval.webSkippedTrace", ignore); }
                }
                fused = tmp;
            } else if (forceLightSearchMode) {
                List<dev.langchain4j.rag.content.Content> prefetchedLight =
                        prefetchedWebContents(metaHints, Math.max(1, Math.min(plateLimit, 8)));
                if (!prefetchedLight.isEmpty()) {
                    fused = prefetchedLight;
                    try {
                        TraceStore.put("search.mode.lightPrefetch.hybridBypass", true);
                        TraceStore.put("search.mode.lightPrefetch.hybridBypass.count", prefetchedLight.size());
                        TraceStore.put("search.mode.lightPrefetch.hybridBypass.reason", "prefetched_web_evidence");
                    } catch (RuntimeException ignore) {
                        ChatWorkflowTraceSuppressions.traceSuppressed("searchMode.lightPrefetchHybridBypass", ignore);
                    }
                } else {
                    fused = hybridRetriever.retrieveAll(planned, plateLimit, sessionIdLong, metaHints);
                }
            } else {
                fused = hybridRetriever.retrieveAll(planned, plateLimit, sessionIdLong, metaHints);
            }
            if (forceLightSearchMode && (fused == null || fused.isEmpty())) {
                List<dev.langchain4j.rag.content.Content> prefetchedLight =
                        prefetchedWebContents(metaHints, Math.max(1, Math.min(plateLimit, 8)));
                if (!prefetchedLight.isEmpty()) {
                    fused = prefetchedLight;
                    try {
                        TraceStore.put("search.mode.lightPrefetch.fusedFallback", true);
                        TraceStore.put("search.mode.lightPrefetch.fusedFallback.count", prefetchedLight.size());
                    } catch (RuntimeException ignore) {
                        ChatWorkflowTraceSuppressions.traceSuppressed("searchMode.lightPrefetchFallback", ignore);
                    }
                }
            }

            // ---- FAIL-SOFT (UAW): web 紐⑤뱶?몃뜲 ?꾨낫媛 0?대㈃ web-only濡?理쒖냼 ?꾨낫瑜?蹂듭썝 ----
            // ?쇰? ?꾨찓???꾪꽣 議고빀?먯꽌 fused媛 0?쇰줈 ?섎졃?섎㈃ ?댄썑 rerank/topDocs媛 鍮꾩뼱
            // citations.min??留욎텛吏 紐삵븯怨?Guard媛 BLOCK?쇰줈 ?곗뇙?섎뒗 ?⑦꽩???덉뿀??
            boolean webHardDownNow = false;
            try {
                long skipped = TraceStore.getLong("web.await.skipped.count");
                webHardDownNow = (hints != null && hints.isWebRateLimited())
                        || Boolean.TRUE.equals(TraceStore.get("web.hardDown"))
                        || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited.effective"))
                        || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited"))
                        || skipped >= 2;
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("webHardDown.fallbackCheck", ignore); webHardDownNow = (hints != null && hints.isWebRateLimited());
            }

            if (!preLlmRetrievalBudgetLow && useWeb && (fused == null || fused.isEmpty()) && hints != null && hints.isAllowWeb()
                    && !webHardDownNow) {
                try {
                    TraceStore.put("fallback.webOnly", true);
                    var qObj = QueryUtils.buildQuery(finalQuery, sessionIdLong, null, metaHints);
                    List<dev.langchain4j.rag.content.Content> webOnly = null;
                    if (shouldUseAnalyzeWeb(metaHints) && analyzeWebSearchRetriever != null) {
                        try {
                            webOnly = analyzeWebSearchRetriever.retrieve(qObj);
                        } catch (Exception e) {
                            TraceStore.put("fallback.webOnly.analyzeError", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
                        }
                    }
                    if (webOnly == null || webOnly.isEmpty()) {
                        webOnly = webSearchRetriever.retrieve(qObj);
                    }
                    if (webOnly != null && !webOnly.isEmpty()) {
                        fused = webOnly;
                        TraceStore.put("fallback.webOnly.count", webOnly.size());
                    }
                } catch (Exception e) {
                    TraceStore.put("fallback.webOnly.error", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
                }
            } else if (useWeb && (fused == null || fused.isEmpty()) && webHardDownNow) {
                try {
                    TraceStore.put("fallback.webOnly.skipped", true);
                    TraceStore.put("fallback.webOnly.skipped.reason", "webHardDown");
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("fallback.webOnlySkippedTrace", ignore); }
            }
        }
        // planned / fused ?앹꽦???ㅼ쓬易?
        throwIfCancelled(sessionIdLong); // ??異붽?
        Map<String, Set<String>> rules = qcPreprocessor.getInteractionRules(finalQuery);

        int keepN = switch (Objects.toString(vp.hint(), "standard").toLowerCase(Locale.ROOT)) {
            case "brief" -> keepNBrief;
            case "deep" -> Math.max(rerankTopN, keepNDeep);
            case "ultra" -> Math.max(rerankTopN, keepNUltra);
            default -> keepNStd;
        };

        // Rerank knobs: read ONLY from metaHints (PlanHintApplier already injects
        // canonical keys).
        // This removes drift caused by ChatWorkflow reading PlanHints directly.
        RerankKnobResolver.Resolved rerankKnobs = RerankKnobResolver.resolve(metaHints);

        // Plan knob: rerank.topK / rerank_top_k
        // If explicitly set, respect as an override (but keep emergency clamps below).
        try {
            if (rerankKnobs.topK() != null && rerankKnobs.topK() > 0) {
                keepN = Math.max(1, rerankKnobs.topK());
                TraceStore.put("rerank.keepN.override", keepN);
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.keepNOverrideTrace", ignore); }
        // 蹂댁“ LLM ?μ븷/?섏씠?몃찓??STRIKE/?뺤텞 ?곹솴?먯꽌??而⑦뀓?ㅽ듃瑜?媛뺤젣 ?뺤텞
        if ((sig != null && sig.auxLlmDown())
                || (hints != null && (hints.isNightmareMode() || hints.isCompressionMode() || hints.isStrikeMode()))
                || sig.compressionMode()) {
            keepN = Math.min(keepN, 3);
        }

        List<dev.langchain4j.rag.content.Content> topDocs;
        if (useWeb && fused != null && !fused.isEmpty()) {
            boolean doRerank = (hints == null || hints.isEnableCrossEncoder());
            if (doRerank) {
                // Additional cost-control: optionally cap the number of candidates sent to the
                // cross-encoder.
                // - rerank_ce_top_k / rerank_candidate_k: explicit candidate cap (strongest
                // control)
                // - rerank_top_k: keepN override; if candidate cap is absent, derive a
                // conservative cap (~2x keepN)
                List<dev.langchain4j.rag.content.Content> rerankInput = fused;
                int candidateCap = fused.size();
                try {
                    if (rerankKnobs.ceTopK() != null && rerankKnobs.ceTopK() > 0) {
                        // Explicit candidate cap: score at most N docs.
                        candidateCap = Math.min(candidateCap, rerankKnobs.ceTopK());
                        // ensure enough candidates to keep keepN
                        candidateCap = Math.max(candidateCap, keepN);
                        if (candidateCap < fused.size()) {
                            rerankInput = fused.subList(0, candidateCap);
                        }
                        TraceStore.put("rerank.ce.candidateCap", candidateCap);
                        TraceStore.put("rerank.ce.candidateCap.override", rerankKnobs.ceTopK());
                    } else if (rerankKnobs.topK() != null && rerankKnobs.topK() > 0) {
                        // Derived cap: score at most ~2x the kept docs.
                        candidateCap = Math.min(candidateCap, Math.max(keepN * 2, keepN));
                        candidateCap = Math.max(candidateCap, keepN);
                        if (candidateCap < fused.size()) {
                            rerankInput = fused.subList(0, candidateCap);
                        }
                        TraceStore.put("rerank.ce.candidateCap", candidateCap);
                    }
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.candidateCapTrace", ignore); }

                // Backend selection: allow per-plan override + "auto" mode.
                String backendOverride = rerankKnobs.backend();
                Boolean onnxEnabledOverride = rerankKnobs.onnxEnabled();
                try {
                    topDocs = reranker(backendOverride, onnxEnabledOverride, true)
                            .rerank(finalQuery, rerankInput, keepN, rules);
                } catch (Exception e) {
                    // UAW fail-soft: if rerank fails, keep the pipeline moving with the original
                    // candidates.
                    try {
                        TraceStore.put("rerank.fallback", true);
                        TraceStore.put("rerank.fallback.reason", "exception");
                        TraceStore.put("rerank.fallback.error", "reranker_failed");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.exceptionFallbackTrace", ignore); }
                    log.warn("[ChatService] Reranker failed. Falling back to unreranked candidates. err={}",
                            String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                    topDocs = List.of();
                }

                // UAW fail-soft: rerank output can be empty (e.g., too strict filters). Do not
                // allow topDocs=0.
                if ((topDocs == null || topDocs.isEmpty()) && rerankInput != null && !rerankInput.isEmpty()) {
                    try {
                        TraceStore.put("rerank.fallback", true);
                        TraceStore.putIfAbsent("rerank.fallback.reason", "empty");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.emptyFallbackTrace", ignore); }
                    topDocs = rerankInput.stream().limit(Math.max(1, keepN)).toList();
                }
            } else {
                topDocs = fused.stream().limit(Math.max(1, keepN)).toList();
                try {
                    String why = "skipped_by_plate";
                    if (Boolean.FALSE.equals(rerankKnobs.crossEncoderEnabled())) {
                        why = "skipped_by_plan";
                    }
                    TraceStore.put("rerank", why);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.skippedTrace", ignore); }
            }
        } else {
            topDocs = List.of();
        }

        if (useWeb) {
            if (topDocs == null || topDocs.isEmpty()) {
                long cnt = emptyTopDocsCount.incrementAndGet();
                log.warn("[ChatService] ?좑툘 ??寃??紐⑤뱶?대굹 topDocs 鍮꾩뼱?덉쓬! fused={} ??reranked=0. "
                        + "?꾪꽣留?怨쇰룄?? emptyTopDocsCount={}",
                        (fused != null ? fused.size() : 0),
                        cnt);
            } else {
                log.debug("[ChatService] Reranker: fused={} ??topDocs={}",
                        (fused != null ? fused.size() : 0),
                        (topDocs != null ? topDocs.size() : 0));
            }
        }

        // ?? (Needle Probe) 2-pass merge/rerank
        // When pass-1 evidence quality looks weak, run a tiny second-pass web detour
        // (1~2 high-authority site-filtered queries), then merge + rerank again.
        if (useWeb && needleProbeEngine != null
                && sig != null
                && !sig.strikeMode() && !sig.bypassMode()
                && !sig.webRateLimited()) {
            try {
                java.util.Map<String, Object> baseMeta = new java.util.HashMap<>();
                if (metaHints != null) {
                    baseMeta.putAll(metaHints);
                }
                needleBeforeSignals = EvidenceSignals.compute(finalQuery, topDocs, authorityScorer);

                NeedleProbeEngine.Result needle = needleProbeEngine.maybeProbe(
                        finalQuery,
                        topDocs,
                        keepN,
                        sessionIdLong,
                        baseMeta);

                if (needle != null && needle.triggered()
                        && needle.needleDocs() != null && !needle.needleDocs().isEmpty()) {
                    needleExecuted = true;
                    needlePlanned = needle.plan() == null ? java.util.List.of() : needle.plan().needleQueries();
                    needleUrls = needle.needleUrls() == null ? java.util.Set.of() : needle.needleUrls();
                    needleDocsForReward = java.util.List.copyOf(needle.needleDocs());
                    TraceStore.put("needle.triggered", true);
                    TraceStore.put("needle.plan.reason", SafeRedactor.traceLabelOrFallback(needle.plan() == null ? "unknown" : needle.plan().reason(), "unknown"));
                    TraceStore.put("needle.plan.queryCount", needlePlanned == null ? 0 : needlePlanned.size());
                    TraceStore.put("needle.plan.queryHashes", safeHashList(needlePlanned));
                    TraceStore.put("needle.plan.siteHintCount",
                            needle.plan() == null || needle.plan().siteHints() == null ? 0 : needle.plan().siteHints().size());
                    TraceStore.put("needle.plan.keywordCount",
                            needle.plan() == null || needle.plan().coreKeywords() == null ? 0 : needle.plan().coreKeywords().size());
                    java.util.List<Content> firstPassTopDocs =
                            topDocs == null ? java.util.List.of() : java.util.List.copyOf(topDocs);

                    java.util.List<Content> merged = mergeNeedleCandidates(
                            topDocs,
                            needle.needleDocs(),
                            fused,
                            needleProbeEngine.maxCandidatePool(keepN));
                    fused = merged;

                    // 2-pass rerank if cross-encoder enabled
                    boolean doRerank2 = (hints == null || hints.isEnableCrossEncoder());
                    long secondPassRemainingMs = -1L;
                    try {
                        throwIfCancelled(sessionIdLong);
                        com.abandonware.ai.addons.budget.TimeBudget tb =
                                com.abandonware.ai.addons.budget.TimeBudgetContext.get();
                        if (tb != null) {
                            secondPassRemainingMs = tb.remainingMillis();
                            TraceStore.put("rerank.secondPass.remainingMs", secondPassRemainingMs);
                            if (secondPassRemainingMs < 80L) {
                                doRerank2 = false;
                                TraceStore.put("rerank.secondPass.guard.reason", SafeRedactor.traceLabelOrFallback("budget_low", "unknown"));
                                TraceStore.put("rerank.secondPass.guard.applied", true);
                                com.example.lms.trace.AblationContributionTracker.recordPenaltyOnce(
                                        "rerank.secondPass.budget_low",
                                        "rerank.secondPass",
                                        "budget_low",
                                        0.20d,
                                        "second pass skipped because remaining budget was below 80ms");
                            }
                        }
                    } catch (java.util.concurrent.CancellationException ce) {
                        TraceStore.put("rerank.secondPass.guard.reason", SafeRedactor.traceLabelOrFallback("cancelled", "unknown"));
                        TraceStore.put("rerank.secondPass.guard.applied", true);
                        throw ce;
                    } catch (Exception ignore) {
                        ChatWorkflowTraceSuppressions.traceSuppressed("rerank.secondPass.guard", ignore);
                    }
                    if (doRerank2 && merged.size() > keepN) {
                        int cap = needleProbeEngine.secondPassCandidateCap(keepN, merged.size(), rerankKnobs);
                        java.util.List<Content> rerankInput2 = merged.subList(0, cap);

                        String backendOverride2 = rerankKnobs.backend();
                        Boolean onnxEnabledOverride2 = rerankKnobs.onnxEnabled();

                        topDocs = reranker(backendOverride2, onnxEnabledOverride2, true)
                                .rerank(finalQuery, rerankInput2, keepN, rules);

                        TraceStore.put("needle.rerank.secondPass", true);
                        TraceStore.put("needle.rerank.candidateCap", cap);
                        double secondPassDropRatio = retainedDropRatio(firstPassTopDocs, topDocs);
                        double secondPassScoreDelta = secondPassDropRatio;
                        TraceStore.put("rerank.secondPass.scoreDelta", secondPassScoreDelta);
                        TraceStore.put("rerank.secondPass.dropRatio", secondPassDropRatio);
                        TraceStore.put("rerank.secondPass.beforeCount", firstPassTopDocs.size());
                        TraceStore.put("rerank.secondPass.afterCount", topDocs == null ? 0 : topDocs.size());
                        if ((topDocs == null || topDocs.isEmpty())
                                || secondPassDropRatio >= 0.50d
                                || secondPassScoreDelta >= 0.20d) {
                            TraceStore.put("rerank.secondPass.guard.reason",
                                    SafeRedactor.traceLabelOrFallback((topDocs == null || topDocs.isEmpty()) ? "empty_second_pass" : "score_drop", "unknown"));
                            TraceStore.put("rerank.secondPass.guard.applied", true);
                            com.example.lms.trace.AblationContributionTracker.recordPenaltyOnce(
                                    "rerank.secondPass.score_drop",
                                    "rerank.secondPass",
                                    String.valueOf(TraceStore.get("rerank.secondPass.guard.reason")),
                                    secondPassScoreDelta,
                                    "second pass rollback to first-pass evidence");
                            topDocs = firstPassTopDocs;
                        }
                    } else {
                        topDocs = firstPassTopDocs.isEmpty()
                                ? merged.stream().limit(Math.max(1, keepN)).toList()
                                : firstPassTopDocs;
                    }
                    if ((topDocs == null || topDocs.isEmpty()) && merged != null && !merged.isEmpty()) {
                        topDocs = merged.stream().limit(Math.max(1, keepN)).toList();
                        TraceStore.put("needle.rerank.fallback", true);
                    }
                    needleAfterSignals = EvidenceSignals.compute(finalQuery, topDocs, authorityScorer);

                    int needleTopDocHits = needle.countTopDocsHits(topDocs);
                    TraceStore.put("needle.topDocs.hits", needleTopDocHits);

                    // MERGE_HOOK:PROJ_AGENT::NEEDLE_KEPT_RATIO_V1
                    // "keptRatio" := needle媛 理쒖쥌 ?곸쐞 利앷굅(topDocs)??湲곗뿬??鍮꾩쑉
                    int denom = Math.max(1, topDocs.size());
                    double keptRatio = ((double) needleTopDocHits) / denom;
                    TraceStore.put("needle.keptRatio", keptRatio);
                    TraceStore.put("needle.keptRatioDenom", denom);
                }
            } catch (Exception e) {
                ChatWorkflowTraceSuppressions.traceSuppressed("needle.probe", e); log.debug("[Needle] probe failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            }
        }
        // 1-b) (?듭뀡) RAG(Vector) 議고쉶
        List<dev.langchain4j.rag.content.Content> vectorDocs = List.of();
        boolean forceLightVectorBypass = forceLightSearchMode
                && useWeb
                && topDocs != null
                && !topDocs.isEmpty();
        if (useRag && preLlmRetrievalBudgetLow) {
            try {
                TraceStore.put("retrieval.preLlm.vector.skipped", true);
                TraceStore.put("retrieval.preLlm.vector.skipReason", "request_budget_low");
            } catch (RuntimeException ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("retrieval.preLlmVectorBudgetSkip", ignore);
            }
        } else if (useRag && forceLightVectorBypass) {
            try {
                TraceStore.put("search.mode.lightVector.skipped", true);
                TraceStore.put("search.mode.lightVector.skipReason", "web_evidence_ready");
                TraceStore.put("search.mode.lightVector.webEvidenceCount", topDocs.size());
            } catch (RuntimeException ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("searchMode.lightVectorBypass", ignore);
            }
        } else if (useRag) {
            // Propagate request-scoped orchestration hints into vector retriever metadata.
            // This enables dynamic vecTopK (and future tuning knobs) without changing the
            // handler chain.
            java.util.Map<String, Object> vMeta = new java.util.HashMap<>();
            vMeta.put(
                    com.example.lms.service.rag.LangChainRAGService.META_SID,
                    (req.getSessionId() == null) ? "__TRANSIENT__" : req.getSessionId());
            // Orchestration flags (for downstream dynamic handlers)
            vMeta.put("auxLlmDown", String.valueOf(sig.auxLlmDown()));
            vMeta.put("auxDegraded", String.valueOf(sig.auxDegraded()));
            vMeta.put("auxHardDown", String.valueOf(sig.auxHardDown()));
            vMeta.put("strikeMode", String.valueOf(sig.strikeMode()));
            vMeta.put("compressionMode", String.valueOf(sig.compressionMode()));
            vMeta.put("bypassMode", String.valueOf(sig.bypassMode()));

            // Domain/tile hints (keep vector path consistent with hybrid retrieval)
            vMeta.put("intent.domain", domain);
            vMeta.put("vp.topTile", mapDomainToTile(domain));
            if (hints != null && hints.getVecTopK() != null) {
                vMeta.put("vecTopK", hints.getVecTopK());
                // Compatibility key for some retrievers.
                vMeta.put("vectorTopK", hints.getVecTopK());
            }
            vectorDocs = ragSvc.asContentRetriever(pineconeIndexName)
                    .retrieve(
                            QueryUtils.buildQuery(finalQuery, vMeta));
        }

        // Expose the final evidence sets (post rerank / retrieval) to the UI
        // layer. Controllers may read these from TraceStore to render the
        // "理쒖쥌 而⑦뀓?ㅽ듃" section without re-running retrieval.
        try {
            // Preserve "enabled" signal for the trace UI:
            // - null : disabled (feature not used)
            // - empty : enabled but no results
            TraceStore.put("finalWebTopK",
                    useWeb ? ((topDocs == null) ? java.util.Collections.emptyList() : topDocs) : null);
            TraceStore.put("finalVectorTopK",
                    useRag ? ((vectorDocs == null) ? java.util.Collections.emptyList() : vectorDocs) : null);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalEvidence.trace", ignore);
        }

        // plan.pipeline stage ledger (chat path): each declared stage is mapped to
        // real runtime evidence only; unmapped or unevidenced stages stay honest.
        try {
            if (planExecSpec != null && !planExecSpec.isEmpty()) {
                java.util.Map<String, Object> postScope = chatPlanScope(gctx);
                postScope.putAll(chatPlanObservedMetrics());
                com.example.lms.plan.PlanExecutionSpec.WhenVerdict postWhen =
                        planExecSpec.evaluateWhen(postScope);
                TraceStore.put("plan.when.post",
                        postWhen.state().name().toLowerCase(Locale.ROOT));
                if (planWhen != null
                        && planWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.UNKNOWN
                        && postWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.TRUE) {
                    TraceStore.put("plan.when.lateActivation", true);
                }
                if (planExecSpec.pipeline() != null && !planExecSpec.pipeline().isEmpty()) {
                    java.util.List<java.util.Map<String, Object>> planLedger = planExecSpec
                            .stageLedger(
                                    chatStageEvidence(topDocs, rerankKnobs == null ? null : rerankKnobs.backend()),
                                    chatStageFlags(hints, metaHints, planWhen))
                            .stream()
                            .map(com.example.lms.plan.PlanExecutionSpec.StageEntry::debugView)
                            .toList();
                    TraceStore.put("plan.stageLedger", planLedger);
                    if (metaHints != null) {
                        metaHints.put("plan.stageLedger", planLedger);
                    }
                }
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("plan.stageLedger", ignore);
        }

        // 1-c) 硫붾え由?而⑦뀓?ㅽ듃(??긽 ?쒕룄) - ?꾨떞 ?몃뱾???ъ슜
        String memoryCtx = conversationContext.present()?conversationContext.memoryText():null;
        try {
            if (conversationContext.present()) {
                // This explicitly scoped data stays available with web and global memory off.
            } else if (futureTech && latestTechSkipMemoryRead) {
                log.debug("[FutureTech] skip memory context load to avoid stale contamination. sessionHash={}",
                        SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
            } else if (memoryReadEnabled) {
                memoryCtx = memoryHandler.loadForSession(req.getSessionId());
            } else {
                log.debug("[MemoryMode] {} -> skip memory context load for sessionHash={}", memoryMode, SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
            }
        } catch (Exception ex) {
            log.debug("[Memory] failed to load memory context: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
        }

        // ?? 2) 紐낆떆??留λ씫 ?앹꽦(Verbosity-aware) ????????????????????????
        // ?몄뀡 ID(Long) ?뚯떛: 理쒓렐 assistant ?듬? & ?덉뒪?좊━ 議고쉶???ъ슜

        String lastAnswer = (!memoryReadEnabled || sessionIdLong == null)
                ? null
                : chatHistoryService.getLastAssistantMessage(sessionIdLong).orElse(null);
        String historyStr = (!memoryReadEnabled || sessionIdLong == null)
                ? ""
                : String.join("\n", chatHistoryService.getFormattedRecentHistory(sessionIdLong,
                        Math.max(2, Math.min(maxHistory, 8))));
        if(conversationContext.present()){
            historyStr=String.join("\n",conversationContext.interpretationHistory());
            if(!conversationContext.recent().isEmpty())lastAnswer=conversationContext.recent().get(conversationContext.recent().size()-1).answer();
        }

        // PromptContext??紐⑤뱺 ?곹깭瑜?'紐낆떆?곸쑝濡? ?섏쭛
        // =========================================================================
        // [SECTION 2] Prompt context assembly
        // Future extraction candidate: PromptContextAssembler.
        // =========================================================================
        List<Content> promptWebDocs = useWeb ? filterPromptEligibleSelfAsk(topDocs) : null;
        promptWebDocs = filterRequestScopedOfficialPromptWebDocs(
                req, metaHints, finalQuery, promptWebDocs, "pre_compression");
        List<Content> promptVectorDocs = useRag ? filterPromptEligibleSelfAsk(vectorDocs) : null;
        java.util.Set<String> interactionSuspectEvidenceIds = gctx == null
                ? java.util.Set.of()
                : gctx.getInteractionSuspectEvidenceIds();
        if (evidenceAwareGuard.requiresRetrievedEvidenceQuarantine(interactionPolicyDecision)) {
            int inputCount = (promptWebDocs == null ? 0 : promptWebDocs.size())
                    + (promptVectorDocs == null ? 0 : promptVectorDocs.size());
            if (interactionSuspectEvidenceIds.isEmpty()) {
                promptWebDocs = java.util.List.of();
                promptVectorDocs = java.util.List.of();
                TraceStore.put("interaction.policy.containment.status", "ENFORCEMENT_FAILED");
            } else {
                promptWebDocs = filterSuspectPromptContents(
                        promptWebDocs, interactionSuspectEvidenceIds, false);
                promptVectorDocs = filterSuspectPromptContents(
                        promptVectorDocs, interactionSuspectEvidenceIds, true);
                TraceStore.put("interaction.policy.containment.status", "QUARANTINED");
            }
            int cleanCount = (promptWebDocs == null ? 0 : promptWebDocs.size())
                    + (promptVectorDocs == null ? 0 : promptVectorDocs.size());
            int quarantinedCount = Math.max(0, inputCount - cleanCount);
            TraceStore.put("interaction.policy.containment.inputCount", inputCount);
            TraceStore.put("interaction.policy.containment.quarantinedCount", quarantinedCount);
            TraceStore.put("interaction.policy.containment.cleanCount", cleanCount);
            TraceStore.put("interaction.policy.containment.blockRequired",
                    "ENFORCEMENT_FAILED".equals(TraceStore.getString("interaction.policy.containment.status")));
            appendInteractionPolicyBreadcrumb(
                    interactionPolicyDecision,
                    "interactionPolicy.promptContainmentBreadcrumb");
        }
        DynamicContextCompressor.PromptContextComposition promptComposition = null;
        if (promptContextCompressor != null) {
            try {
                promptComposition = promptContextCompressor.composeForPrompt(finalQuery, promptWebDocs, promptVectorDocs);
                if (promptComposition != null) {
                    promptWebDocs = promptComposition.web();
                    promptVectorDocs = promptComposition.rag();
                    promptWebDocs = filterRequestScopedOfficialPromptWebDocs(
                            req, metaHints, finalQuery, promptWebDocs, "post_compression");
                }
            } catch (Throwable ex) {
                TraceStore.put("prompt.context.composer.failSoft", true);
                TraceStore.put("prompt.context.composer.reason", "chatworkflow_exception_original_returned");
                TraceStore.put("prompt.context.composer.exception", "prompt_context_composer_failed");
            }
            try {
                memoryCtx = promptContextCompressor.compressMemoryForPrompt(finalQuery, memoryCtx);
            } catch (Throwable ex) {
                TraceStore.put("prompt.memory.compressor.activated", false);
                TraceStore.put("prompt.memory.compressor.reason", "chatworkflow_exception_original_returned");
                TraceStore.put("prompt.memory.compressor.exception", "memory_compressor_failed");
            }
        }
        if (debugEventStore != null && promptComposition != null && promptComposition.decision() != null) {
            try {
                debugEventStore.emit(
                        DebugProbeType.PROMPT,
                        promptComposition.decision().failSoft() ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                        "prompt.context.composer",
                        "Ablation-guided prompt context composition evaluated.",
                        "ChatWorkflow.promptContextComposer",
                        promptComposition.decision().toTraceMap(),
                        null);
            } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.contextComposerDebugEvent", ignore); }
        }

        RagLearningSupportContext ragSupportContext = RagLearningSupportContext.empty();
        if (learningContextSourceService != null) {
            try {
                RagLearningSupportContext loaded = learningContextSourceService.buildRagSupportForCurrentActor();
                if (loaded != null) {
                    ragSupportContext = loaded;
                }
            } catch (RuntimeException ex) {
                TraceStore.put("prompt.learning.failSoft", true);
                TraceStore.put("prompt.ragSupport.failSoft", true);
                TraceStore.put("prompt.learning.exception", "learning_context_failed");
                TraceStore.put("prompt.ragSupport.exception", "learning_context_failed");
                ragSupportContext = RagLearningSupportContext.degraded("learning_context_failed");
            }
        }

        var ctxBuilder = com.example.lms.prompt.PromptContext.builder()
                // Use the rewritten/final query so retrieval signals, section templates and
                // follow-up checks stay consistent.
                .userQuery(finalQuery)
                .lastAssistantAnswer(lastAnswer)
                .history(historyStr)
                .intent(intent)
                .domain(domain)
                .subject(analysis != null ? analysis.getTargetObject() : null)
                .ragEnabled(useWeb || useRag)
                // Keep null to represent "disabled"; empty list means enabled but no results.
                .web(promptWebDocs)
                .rag(promptVectorDocs)
                .memory(memoryCtx) // ?몄뀡 ?κ린 硫붾え由??붿빟
                .interactionRules(rules) // ?숈쟻 愿怨?洹쒖튃
                .interactionPolicyDecision(interactionPolicyDecision)
                .conversationFrame(conversationFrame)
                .verbosityHint(vp.hint()) // brief|standard|deep|ultra
                .minWordCount(vp.minWordCount())
                .targetTokenBudgetOut(vp.targetTokenBudgetOut())
                .sectionSpec(sections)
                .citationStyle("inline")
                .queryDomain(queryDomain)
                .guardProfile(guardProfile)
                .visionMode(visionMode)
                .answerMode(answerMode)
                .memoryMode(memoryMode)
                .resourceTier(safeTraceString("resource.tier"))
                .resourceValueScore(safeTraceDouble("resource.valueScore"))
                .resourceOptimismScore(safeTraceDouble("resource.optimismScore"))
                .resourceRiskAdjustedConfidence(safeTraceDouble("resource.riskAdjustedConfidence"))
                .resourceRewriteTemperature(safeTraceDouble("resource.rewriteTemperature"))
                .resourceSearchRangeMultiplier(safeTraceDouble("resource.searchRangeMultiplier"))
                .learningRole(ragSupportContext.actorRole())
                .learningSignals(ragSupportContext.contextSignals())
                .learningContextSummary(ragSupportContext.contextSummary())
                .contextRefinementSummary(promptComposition == null ? null : promptComposition.contextRefinementSummary())
                .contextRefinementSignals(promptComposition == null ? null : promptComposition.contextRefinementSignals());
        java.util.List<dev.langchain4j.data.document.Document> promptLocalDocs = java.util.Collections.emptyList();
        // Inject uploaded attachments into the prompt context. Only when
        // attachment identifiers are present to avoid unnecessary overhead.
        java.util.List<String> __ids = (req == null) ? null : req.getAttachmentIds();
        if (__ids != null && !__ids.isEmpty()) {
            AttachmentOwnerIdentity attachmentOwnerIdentity = req.getAttachmentOwnerIdentity();
            if (attachmentOwnerIdentity == null) {
                TraceStore.put("attachment.ownerFilter.applied", false);
                TraceStore.put("attachment.ownerFilter.reason", "missing_owner");
            } else {
                try {
                    var localDocs = attachmentService.asDocumentsForSession(
                            __ids,
                            sessionIdLong == null ? null : String.valueOf(sessionIdLong),
                            attachmentOwnerIdentity);
                    localDocs = filterSuspectPromptDocuments(localDocs, interactionSuspectEvidenceIds);
                    if (localDocs != null && !localDocs.isEmpty()) {
                        promptLocalDocs = localDocs;
                        ctxBuilder.localDocs(localDocs);
                    }
                    TraceStore.put("attachment.ownerFilter.applied", true);
                } catch (Exception ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("prompt.localDocs", ignore);
                }
            }
        }
        mirrorAgentDbContextAvailabilityForAgentDebug(agentPipelineHealthController);
        mirrorLocalLlmSmokeHistoryOperatorActionForAgentDebug(localLlmSmokeHistoryDiagnosticsService);
        String agentDebugQuery = (userQuery != null && !userQuery.isBlank()) ? userQuery : finalQuery;
        boolean agentDebugPromptRelevant = AgentVisibleDebugEvidenceBuilder.isAgentDebugPromptEvidenceQuery(
                agentDebugQuery);
        AgentVisibleDebugEvidenceBuilder.Snapshot agentDebugSnapshot = agentDebugPromptRelevant
                ? AgentVisibleDebugEvidenceBuilder.buildSnapshot(agentDebugQuery, debugAiMetricsService)
                : AgentVisibleDebugEvidenceBuilder.Snapshot.empty();
        java.util.List<dev.langchain4j.data.document.Document> agentDebugDocs = agentDebugSnapshot.documents();
        TraceStore.put("prompt.agentDebugEvidence.injected", agentDebugPromptRelevant);
        if (agentDebugPromptRelevant && agentDebugDocs != null && !agentDebugDocs.isEmpty()) {
            java.util.List<dev.langchain4j.data.document.Document> mergedLocalDocs =
                    new java.util.ArrayList<>(promptLocalDocs);
            mergedLocalDocs.addAll(agentDebugDocs);
            promptLocalDocs = java.util.List.copyOf(mergedLocalDocs);
            ctxBuilder.localDocs(promptLocalDocs);
        }
        com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult promotionResult =
                com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult.unavailable();
        java.util.List<RagEvidenceMetadata> citableEvidence = java.util.Collections.emptyList();
        boolean lateUnattributedEvidenceAdded = false;
        try {
            if (ragEvidenceAttributionService != null) {
                promotionResult = ragEvidenceAttributionService.promoteForPromptDetailed(
                        finalQuery,
                        promptWebDocs,
                        promptVectorDocs,
                        promptLocalDocs,
                        queryDomain,
                        lastAnswer != null && !lastAnswer.isBlank());
                if (promotionResult == null) {
                    promotionResult = legacyPromotionResult(ragEvidenceAttributionService.promoteForPrompt(
                            finalQuery,
                            promptWebDocs,
                            promptVectorDocs,
                            promptLocalDocs,
                            queryDomain,
                            lastAnswer != null && !lastAnswer.isBlank()));
                }
                citableEvidence = java.util.List.copyOf(filterOfficialSourceEvidenceMetadata(
                        userQuery,
                        promotionResult.evidence()));
                ctxBuilder.evidence(citableEvidence);
                attachEnsembleCitationSources(ctxBuilder, userQuery, citableEvidence);
            }
        } catch (Throwable ex) {
            TraceStore.put("rag.evidence.promotion.failSoft", true);
            TraceStore.put("rag.evidence.promotion.exception", "evidence_promotion_failed");
            promotionResult = com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult.callerFailure();
            citableEvidence = java.util.Collections.emptyList();
        }
        if (allowsConversationRefinement(promptContextRefinerEnabled, conversationFrame)) {
            try {
                var refinerSeedCtx = ctxBuilder.build();
                double contamination = 0.0d;
                if (refinerSeedCtx.contextRefinementSignals() != null) {
                    contamination = Math.max(0.0d, Math.min(1.0d,
                            refinerSeedCtx.contextRefinementSignals().getOrDefault("contextContamination", 0.0d)));
                }
                if (contamination >= 0.65d) {
                    TraceStore.put("prompt.context.refiner.failSoft", true);
                    TraceStore.put("prompt.context.refiner.reason", "contamination_risk");
                    TraceStore.put("prompt.context.refiner.contaminationScore", contamination);
                } else {
                    throwIfCancelled(sessionIdLong);
                    java.util.List<com.example.lms.ensemble.SampledCandidate> refinementCandidates =
                            ensembleFinalAnswerService.sampleCandidatesForRefinement(refinerSeedCtx, sessionIdLong,
                                    () -> throwIfCancelled(sessionIdLong));
                    if (refinementCandidates != null && !refinementCandidates.isEmpty()) {
                        ctxBuilder.ensembleCandidates(refinementCandidates);
                        boolean creativeTriad = creativeEmergenceTriad(gctx, refinementCandidates);
                        if (creativeTriad) {
                            ctxBuilder.ensembleJudgeMode(true);
                        }
                        TraceStore.put("prompt.context.refiner.activated", true);
                        TraceStore.put("prompt.context.refiner.reason", "ensemble_candidates_attached");
                        TraceStore.put("prompt.context.refiner.candidateCount", Math.min(3, refinementCandidates.size()));
                        TraceStore.put("prompt.context.refiner.selectedCandidate",
                                SafeRedactor.traceLabelOrFallback(
                                        String.valueOf(TraceStore.get("ensemble.refiner.selectionDecision")),
                                        "underdetermined"));
                        TraceStore.put("prompt.context.refiner.providerDisabled", false);
                        TraceStore.put("prompt.context.refiner.failSoft", false);
                        TraceStore.put("prompt.context.refiner.ensembleJudgeMode", creativeTriad);
                    } else {
                        String ensembleDisabledReason = switch (String.valueOf(
                                TraceStore.get("ensemble.refiner.disabledReason"))) {
                            case "provider_disabled",
                                    "citation_context_unavailable",
                                    "primary_answer_reserve",
                                    "model_unavailable",
                                    "unsafe_or_incomplete_hypothesis_set" -> String.valueOf(
                                            TraceStore.get("ensemble.refiner.disabledReason"));
                            default -> "model_unavailable";
                        };
                        Object revalidationReason = TraceStore.get(
                                "ensemble.apiTriad.revalidationReason");
                        String latestProviderGateReason = String.valueOf(
                                revalidationReason == null
                                        ? TraceStore.get("ensemble.apiTriad.preflightReason")
                                        : revalidationReason);
                        String providerDisabledReason = switch (latestProviderGateReason) {
                            case "credential_missing",
                                    "router_disabled",
                                    "route_disabled_or_incomplete" -> latestProviderGateReason;
                            default -> "";
                        };
                        boolean providerDisabled = "provider_disabled".equals(ensembleDisabledReason)
                                || !providerDisabledReason.isEmpty();
                        String refinerReason = providerDisabled
                                ? "provider_disabled"
                                : ensembleDisabledReason;
                        String refinerDisabledReason = providerDisabledReason.isEmpty()
                                ? ensembleDisabledReason
                                : providerDisabledReason;
                        TraceStore.put("prompt.context.refiner.activated", false);
                        TraceStore.put("prompt.context.refiner.reason", refinerReason);
                        TraceStore.put("prompt.context.refiner.disabledReason", refinerDisabledReason);
                        TraceStore.put("prompt.context.refiner.candidateCount", 0);
                        TraceStore.put("prompt.context.refiner.selectedCandidate", "none");
                        TraceStore.put("prompt.context.refiner.providerDisabled", providerDisabled);
                        TraceStore.put("prompt.context.refiner.failSoft", true);
                    }
                }
            } catch (CancellationException ce) {
                throw ce;
            } catch (Throwable ex) {
                TraceStore.put("prompt.context.refiner.failSoft", true);
                TraceStore.put("prompt.context.refiner.reason", "chatworkflow_exception_original_returned");
                TraceStore.put("prompt.context.refiner.exception", "context_refiner_failed");
                ChatWorkflowTraceSuppressions.traceSuppressed("prompt.contextRefiner", ex);
            }
        }
        appendConversationFrameBreadcrumb(conversationFrame, "conversationFrame.refinerBreadcrumb");
        var ctx = ctxBuilder.build();
        String verifierEvidenceContext = buildVerifierEvidenceContext(
                promptWebDocs, promptVectorDocs, promptLocalDocs);
        String ragSupportRoleLabel = ctx.learningRole() == null ? "ANONYMOUS" : ctx.learningRole().trainingRagLabel();

        // (Safety) Mirror the *actual* evidence lists used in the prompt back into
        // TraceStore
        // so the UI can display the same values even if upstream lists were altered.
        try {
            TraceStore.put("finalWebTopK", ctx.web());
            TraceStore.put("finalVectorTopK", ctx.rag());
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.finalEvidenceMirror", ignore);
        }
        // (F) Prompt build boundary observability: record minimal composition meta.
        try {
            int webCount = (ctx.web() != null) ? ctx.web().size() : 0;
            int ragCount = (ctx.rag() != null) ? ctx.rag().size() : 0;
            int localDocsCount = (ctx.localDocs() != null) ? ctx.localDocs().size() : 0;
            int evidenceCount = (ctx.evidence() != null) ? ctx.evidence().size() : 0;
            String mem = ctx.memory();
            boolean memPresent = (mem != null && !mem.isBlank());
            TraceStore.put("prompt.webCount", webCount);
            TraceStore.put("prompt.ragCount", ragCount);
            TraceStore.put("prompt.localDocsCount", localDocsCount);
            TraceStore.put("prompt.citableEvidenceCount", evidenceCount);
            TraceStore.put("prompt.memoryPresent", memPresent);
            TraceStore.put("prompt.memoryLen", (mem != null) ? mem.length() : 0);
            TraceStore.put("prompt.learningRole", ragSupportRoleLabel);
            TraceStore.put("prompt.learningSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
            TraceStore.put("prompt.learningSummaryPresent",
                    ctx.learningContextSummary() != null && !ctx.learningContextSummary().isBlank());
            TraceStore.put("prompt.learningSourceTags", ragSupportContext.sourceTags());
            String learningDegradedReason = ragSupportContext.degradedReason();
            if (learningDegradedReason == null || learningDegradedReason.isBlank()) {
                learningDegradedReason = learningDegradedReason(ctx.learningSignals());
            }
            String safeLearningDegradedReason = SafeRedactor.traceLabelOrFallback(learningDegradedReason, "unknown");
            TraceStore.put("prompt.learningDegraded", !learningDegradedReason.isBlank());
            if (!learningDegradedReason.isBlank()) {
                TraceStore.put("prompt.learningDegradedReason", safeLearningDegradedReason);
            }
            TraceStore.put("prompt.ragSupport.role", ragSupportRoleLabel);
            TraceStore.put("prompt.ragSupport.signalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
            TraceStore.put("prompt.ragSupport.summaryPresent",
                    ctx.learningContextSummary() != null && !ctx.learningContextSummary().isBlank());
            TraceStore.put("prompt.ragSupport.sourceTags", ragSupportContext.sourceTags());
            TraceStore.put("prompt.ragSupport.degraded", !learningDegradedReason.isBlank());
            if (!learningDegradedReason.isBlank()) {
            TraceStore.put("prompt.ragSupport.degradedReason", safeLearningDegradedReason);
            }
            TraceStore.put("prompt.mode.verbosity", vp.hint());
            TraceStore.put("prompt.intent", SafeRedactor.diagnosticValue("prompt.intent", ctx.intent(), 160));
            TraceStore.put("prompt.domain", SafeRedactor.diagnosticValue("prompt.domain", ctx.domain(), 160));
            if (ctx.answerMode() != null)
                TraceStore.put("prompt.answerMode", String.valueOf(ctx.answerMode()));
            if (ctx.visionMode() != null)
                TraceStore.put("prompt.visionMode", String.valueOf(ctx.visionMode()));
            if (ctx.memoryMode() != null)
                TraceStore.put("prompt.memoryMode", String.valueOf(ctx.memoryMode()));
            TraceStore.put("prompt.sectionSpec.count", (ctx.sectionSpec() != null) ? ctx.sectionSpec().size() : 0);
            java.util.Map<String, Object> pev = new java.util.LinkedHashMap<>();
            pev.put("seq", TraceStore.nextSequence("prompt.events"));
            pev.put("ts", java.time.Instant.now().toString());
            pev.put("step", "PromptBuilder.build.enter");
            pev.put("webCount", webCount);
            pev.put("ragCount", ragCount);
            pev.put("localDocsCount", localDocsCount);
            pev.put("citableEvidenceCount", evidenceCount);
            pev.put("memoryPresent", memPresent);
            pev.put("learningRole", ragSupportRoleLabel);
            pev.put("learningSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
            pev.put("learningSourceTags", ragSupportContext.sourceTags());
            pev.put("learningDegraded", !learningDegradedReason.isBlank());
            if (!learningDegradedReason.isBlank()) {
                pev.put("learningDegradedReason", safeLearningDegradedReason);
            }
            pev.put("ragSupportRole", ragSupportRoleLabel);
            pev.put("ragSupportSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
            pev.put("ragSupportSourceTags", ragSupportContext.sourceTags());
            pev.put("ragSupportDegraded", !learningDegradedReason.isBlank());
            if (!learningDegradedReason.isBlank()) {
            pev.put("ragSupportDegradedReason", safeLearningDegradedReason);
            }
            pev.put("verbosity", vp.hint());
            if (ctx.intent() != null)
                pev.put("intent", SafeRedactor.diagnosticValue("intent", ctx.intent(), 160));
            if (ctx.domain() != null)
                pev.put("domain", SafeRedactor.diagnosticValue("domain", ctx.domain(), 160));
            TraceStore.append("prompt.events", pev);
        } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.contextTrace", ignore); }

        // PromptBuilder媛 而⑦뀓?ㅽ듃 蹂몃Ц怨??쒖뒪???몄뒪?몃윮?섏쓣 遺꾨━ ?앹꽦
        long promptBuildStartedNs = System.nanoTime();
        String ctxText = promptBuilder.build(ctx);
        String instrTxt = promptBuilder.buildInstructions(ctx);
        TraceStore.put("chatWorkflow.promptBuilderUsed", true);
        if (gctx != null && gctx.planBool("promptBuilder.required", false)) {
            try {
                TraceStore.put("prompt.builder.required", true);
                TraceStore.put("prompt.builder.required.enforced", ctxText != null);
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("prompt.builderRequired", ignore);
            }
        }
        long promptBuildMs = Math.max(0L, (System.nanoTime() - promptBuildStartedNs) / 1_000_000L);
        // (F) Prompt build boundary observability: store hashes/lengths and emit a
        // DebugEvent.
        try {
            TraceStore.put("prompt.ctx.len", (ctxText != null) ? ctxText.length() : 0);
            TraceStore.put("prompt.instr.len", (instrTxt != null) ? instrTxt.length() : 0);
            if (instrTxt != null && !instrTxt.isBlank()) {
                TraceStore.put("prompt.instr.sha1", TextUtils.sha1(instrTxt));
            }
            if (ctxText != null && !ctxText.isBlank()) {
                // Avoid hashing the full evidence body; hash only a prefix for a stable
                // template fingerprint.
                int cap = Math.min(2048, ctxText.length());
                TraceStore.put("prompt.ctx.prefix.sha1", TextUtils.sha1(ctxText.substring(0, cap)));
            }
            int webCount = (ctx.web() != null) ? ctx.web().size() : 0;
            int ragCount = (ctx.rag() != null) ? ctx.rag().size() : 0;
            int localDocsCount = (ctx.localDocs() != null) ? ctx.localDocs().size() : 0;
            int evidenceCount = (ctx.evidence() != null) ? ctx.evidence().size() : 0;
            int sourceDiversity = 0;
            if (webCount > 0) sourceDiversity++;
            if (ragCount > 0) sourceDiversity++;
            if (localDocsCount > 0) sourceDiversity++;
            if (ctx.memory() != null && !ctx.memory().isBlank()) sourceDiversity++;
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("queryHash", SafeRedactor.hash12(finalQuery));
            input.put("queryLen", safeLen(finalQuery));
            input.put("requestedTopK", webCount + ragCount + localDocsCount);
            input.put("mode", "prompt_builder");
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("returnedCount", webCount + ragCount + localDocsCount);
            output.put("afterFilterCount", webCount + ragCount + localDocsCount);
            output.put("selectedCount", evidenceCount);
            output.put("promotedCount", evidenceCount);
            output.put("stageMs", promptBuildMs);
            output.put("sourceDiversity", sourceDiversity);
            Map<String, Object> control = new LinkedHashMap<>();
            control.put("action", "promote");
            control.put("applied", true);
            control.put("reasonCode", "prompt_build");
            emitRagPipelineEvent("prompt", "prompt_build", "complete", "ChatWorkflow.promptBuild",
                    "ok", input, output, Map.of(), control);
        } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.buildPipelineEvent", ignore); }
        try {
            com.example.lms.metrics.FaithfulnessPromptTracePublisher.publishBeforeLlm(
                    finalQuery,
                    useWeb,
                    useRag,
                    ctx.web() == null ? 0 : ctx.web().size(),
                    ctx.rag() == null ? 0 : ctx.rag().size(),
                    ctx.evidence() == null ? 0 : ctx.evidence().size(),
                    Math.max(1, keepN),
                    Boolean.TRUE.equals(TraceStore.get("fallback.webOnly"))
                            || Boolean.TRUE.equals(TraceStore.get("rerank.fallback")));
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("faithfulness.promptTrace", ignore);
        }
        if (debugEventStore != null) {
            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("webCount", (ctx.web() != null) ? ctx.web().size() : 0);
                dd.put("ragCount", (ctx.rag() != null) ? ctx.rag().size() : 0);
                dd.put("localDocsCount", (ctx.localDocs() != null) ? ctx.localDocs().size() : 0);
                dd.put("citableEvidenceCount", (ctx.evidence() != null) ? ctx.evidence().size() : 0);
                dd.put("memoryPresent", ctx.memory() != null && !ctx.memory().isBlank());
                dd.put("learningRole", ragSupportRoleLabel);
                dd.put("learningSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
                dd.put("learningSummaryPresent",
                        ctx.learningContextSummary() != null && !ctx.learningContextSummary().isBlank());
                dd.put("learningSourceTags", ragSupportContext.sourceTags());
                String debugLearningDegradedReason = ragSupportContext.degradedReason();
                if (debugLearningDegradedReason == null || debugLearningDegradedReason.isBlank()) {
                    debugLearningDegradedReason = learningDegradedReason(ctx.learningSignals());
                }
                String safeDebugLearningDegradedReason = SafeRedactor.traceLabelOrFallback(debugLearningDegradedReason, "unknown");
                dd.put("learningDegraded", !debugLearningDegradedReason.isBlank());
                if (!debugLearningDegradedReason.isBlank()) {
                    dd.put("learningDegradedReason", safeDebugLearningDegradedReason);
                }
                dd.put("ragSupportRole", ragSupportRoleLabel);
                dd.put("ragSupportSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
                dd.put("ragSupportSummaryPresent",
                        ctx.learningContextSummary() != null && !ctx.learningContextSummary().isBlank());
                dd.put("ragSupportSourceTags", ragSupportContext.sourceTags());
                dd.put("ragSupportDegraded", !debugLearningDegradedReason.isBlank());
                if (!debugLearningDegradedReason.isBlank()) {
                    dd.put("ragSupportDegradedReason", safeDebugLearningDegradedReason);
                }
                dd.put("verbosity", vp.hint());
                dd.put("intent", SafeRedactor.diagnosticValue("intent", ctx.intent(), 160));
                dd.put("domain", SafeRedactor.diagnosticValue("domain", ctx.domain(), 160));
                dd.put("answerMode", (ctx.answerMode() != null) ? String.valueOf(ctx.answerMode()) : null);
                dd.put("visionMode", (ctx.visionMode() != null) ? String.valueOf(ctx.visionMode()) : null);
                dd.put("memoryMode", (ctx.memoryMode() != null) ? String.valueOf(ctx.memoryMode()) : null);
                dd.put("instrLen", (instrTxt != null) ? instrTxt.length() : 0);
                dd.put("ctxLen", (ctxText != null) ? ctxText.length() : 0);
                dd.put("instrSha1", (instrTxt != null && !instrTxt.isBlank()) ? TextUtils.sha1(instrTxt) : "");
                dd.put("queryLen", (finalQuery != null) ? finalQuery.length() : 0);
                dd.put("querySha1", (finalQuery != null) ? TextUtils.sha1(finalQuery) : "");
                debugEventStore.emit(
                        DebugProbeType.PROMPT,
                        DebugEventLevel.INFO,
                        "prompt.built",
                        "PromptBuilder.build(ctx) executed (prompt composition boundary).",
                        "ChatWorkflow.promptBuild",
                        dd,
                        null);
            } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.builtDebugEvent", ignore); }
        }
        // (湲곗〈 異쒕젰 ?뺤콉怨?蹂묓빀 - ?뱀뀡 媛뺤젣 ??
        // The output policy is now derived by the prompt orchestrator. Manual
        // string concatenation via StringBuilder/String.format has been removed
        // to comply with the prompt composition rules. A non-empty output
        // policy would be appended here if required; at present the policy
        // section is left blank to allow the PromptBuilder to manage all
        // contextual guidance.
        try {
            com.example.lms.resilience.RagFailureBlackboxService.projectCurrentTrace(
                    "ChatWorkflow.promptBuild.postProjection");
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("promptBuild.postProjection", ignore);
        }
        String outputPolicy = "";
        String unifiedCtx = ctxText; // 而⑦뀓?ㅽ듃??蹂꾨룄 System 硫붿떆吏濡?

        // ?? 3) 紐⑤뜽 ?쇱슦???곸꽭??由ъ뒪???섎룄) ???????????????????????
        boolean agentDebugAnswerRequested = AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(userQuery);
        String agentDebugDirectAnswer = agentDebugAnswerRequested
                ? composeAgentVisibleDebugFallback(userQuery, agentDebugSnapshot)
                : null;
        if (interactionShortCircuitAllowed && agentDebugDirectAnswer != null && !agentDebugDirectAnswer.isBlank()) {
            TraceStore.put("chat.agentDebugEvidence.directAnswer", true);
            TraceStore.put("chat.llmFallback.mode", "agent_debug_evidence");
            return ChatResult.of(agentDebugDirectAnswer, "agent-debug:fallback:evidence", useRag);
        }

        ChatModel model = modelRouter.route(
                intent,
                detectRisk(userQuery), // "HIGH"|"LOW"|etc. (湲곗〈 ?ы띁)
                vp.hint(), // brief|standard|deep|ultra
                vp.targetTokenBudgetOut(), // 異쒕젰 ?좏겙 ?덉궛 ?뚰듃
                effectiveRequestedModel);

        final String resolvedModelName = modelRouter.resolveModelName(model);
        if (vp.minWordCount() > 0
                && OpenAiTokenParamCompat.usesMaxCompletionTokens(resolvedModelName)) {
            outputPolicy = buildOutputLengthPolicy(resolvedModelName, vp.hint(), answerMode, vp.targetTokenBudgetOut());
        }

        // ?? 4) 硫붿떆吏 援ъ꽦(異쒕젰?뺤콉 ?ы븿) ????????????????????????????
        var msgs = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        // IMPORTANT: instruction/trait/system policies must be injected BEFORE the raw
        // context.
        // Otherwise the model may follow the context formatting first and drift from
        // the template.
        if (org.springframework.util.StringUtils.hasText(instrTxt)) {
            msgs.add(dev.langchain4j.data.message.SystemMessage.from(instrTxt));
        }

        // ??1) Plan/Request level extra system snippets (traits + systemPrompt)
        if (promptAssetService != null) {
            String requestedSystemPrompt = llmReq.getSystemPrompt();
            String extraSys = promptAssetService.resolveSystemPromptText(requestedSystemPrompt);
            if (!org.springframework.util.StringUtils.hasText(extraSys)
                    && org.springframework.util.StringUtils.hasText(requestedSystemPrompt)) {
                traceRejectedPublicSystemPrompt(requestedSystemPrompt);
            }
            String traitSys = promptAssetService.renderTraits(llmReq.getTraits());
            if (org.springframework.util.StringUtils.hasText(extraSys)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(extraSys));
            }
            if (org.springframework.util.StringUtils.hasText(traitSys)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(traitSys));
            }
        }
        if (org.springframework.util.StringUtils.hasText(outputPolicy)) {
            msgs.add(dev.langchain4j.data.message.SystemMessage.from(outputPolicy));
        }

        // Sensitive topic: add extra privacy boundary right before evidences.
        // (Avoid injecting this into creative/explore calls to reduce unintended
        // constraints.)
        try {
            gctx = GuardContextHolder.get();
            if (gctx != null && (gctx.isSensitiveTopic() || gctx.planBool("privacy.boundary.enforce", false))) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(PRIVACY_BOUNDARY_SYS));
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.privacyBoundaryMessage", ignore); }

        // Context (evidence) should come last among system messages.
        int conversationContextIndex=msgs.size();
        msgs.add(dev.langchain4j.data.message.SystemMessage.from(unifiedCtx));
        msgs.addAll(conversationContext.roleMessages());
        // The original current question remains last; rewritten queries are retrieval inputs.

        // ???ъ슜??吏덈Ц
        msgs.add(primaryUserMessage(conversationContext.present()?userQuery:finalQuery, llmReq, conversationFrame));
        if(conversationContext.present()){
            var cap=focusModelSpecs==null?java.util.OptionalInt.empty():focusModelSpecs.snapshots().stream()
                .filter(spec->resolvedModelName.equalsIgnoreCase(spec.model())&&spec.contextTokens()!=null&&spec.contextTokens()>0
                    &&spec.observedAt().isAfter(java.time.Instant.now().minusSeconds(86400)))
                .mapToInt(spec->spec.contextTokens()).min();
            TraceStore.put("focus.context.estimate","utf8_bytes_plus_framing");
            TraceStore.put("focus.context.modelCap",cap.isPresent()?cap.getAsInt():"not_observed");
            if(cap.isPresent()){
                var bounded=conversationContext.fit(msgs,conversationContextIndex,ctx,promptBuilder,cap.getAsInt(),
                    Math.max(llmReq.getMaxTokens()==null?1024:llmReq.getMaxTokens(),vp.targetTokenBudgetOut()));
                msgs.clear();msgs.addAll(bounded);
            }
        }
        recordCostZone(
                "prompt_context",
                resolvedModelName,
                estimateChatMessageChars(msgs),
                0,
                false,
                "ChatWorkflow.promptToModel");

        // ?? 5) ?⑥씪 ?몄텧 ??珥덉븞 ?????????????????????????????????????
        // 紐⑤뜽 ?쇱슦?낆쓣 留덉튇 ?? ?ㅼ젣 chat() ?몄텧 諛붾줈 吏곸쟾
        throwIfCancelled(sessionIdLong); // ??異붽?

        // 紐⑤뜽紐낆쓣 癒쇱? ?댁꽍?섏뿬 諛깆뿏?쒕퀎 釉뚮젅?댁빱 ???앹꽦
        final String breakerKey = NightmareKeys.chatDraftKey(resolvedModelName);

        // ??chat:draft ?쒗궥???ㅽ뵂?섏뼱 ?덉쑝硫?LLM ?몄텧 ?놁씠 利앷굅 湲곕컲?쇰줈 ?고쉶
        if (nightmareBreaker != null) {
            if (shouldUseChatDraftConfigBreakerFallback(breakerKey)) {
                throwIfCancelled(sessionIdLong);
                clearCancel(sessionIdLong);
                return sanitizeFallbackResult(NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModelName, useRag, promptWebDocs, promptVectorDocs, promptLocalDocs,
                        composeEvidenceFallback(finalQuery, promptWebDocs, promptVectorDocs, promptLocalDocs,
                                agentDebugSnapshot, historyStr,
                                queryDomain.isLowRisk()),
                        citableEvidence), finalQuery);
            }
        }

        String draft;
        // Expose evidence presence for retry fast-bailout decisions.
        try {
            int evidenceCount = 0;
            if (promptWebDocs != null)
                evidenceCount += promptWebDocs.size();
            if (promptVectorDocs != null)
                evidenceCount += promptVectorDocs.size();
            TraceStore.put("chat.evidence.count", evidenceCount);
            TraceStore.put("chat.evidence.present", evidenceCount > 0);
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("chat.evidenceTrace", ignore); }
        TimeBudget finalModelRequestBudget = TimeBudgetContext.get();
        if (finalModelRequestBudget != null && finalModelRequestBudget.expired()) {
            String requestBudgetFallbackQuestion = EvidenceAnswerComposer.preserveExplicitConditionalHold(
                    finalQuery,
                    userQuery);
            try {
                TraceStore.put("llm.final.skipped", "request_budget_exhausted");
                TraceStore.put("llm.final.requestBudgetRemainingMs", 0L);
                TraceStore.put("chat.evidenceFallback.conditionalConstraintCarried",
                        !Objects.equals(requestBudgetFallbackQuestion, finalQuery));
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("llm.finalRequestBudgetTrace", ignore);
            }
            throwIfCancelled(sessionIdLong);
            clearCancel(sessionIdLong);
            return sanitizeFallbackResult(NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModelName, useRag,
                    promptWebDocs, promptVectorDocs, promptLocalDocs,
                    composeEvidenceFallback(requestBudgetFallbackQuestion, promptWebDocs, promptVectorDocs,
                            promptLocalDocs, agentDebugSnapshot, historyStr,
                            queryDomain.isLowRisk()),
                    citableEvidence), requestBudgetFallbackQuestion);
        }
        NightmareBreaker.CallPermit primaryPermit = null;
        if (nightmareBreaker != null) {
            try {
                primaryPermit = nightmareBreaker.acquire(breakerKey, "chat-draft-primary");
            } catch (NightmareBreaker.OpenCircuitException oce) {
                ChatWorkflowTraceSuppressions.traceSuppressed("chat.draftBreakerOpen", oce);
                if (irregularityProfiler != null) {
                    irregularityProfiler.markHighRisk(GuardContextHolder.get(), "chat_open");
                }
                throwIfCancelled(sessionIdLong);
                clearCancel(sessionIdLong);
                return sanitizeFallbackResult(NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModelName,
                        useRag, promptWebDocs, promptVectorDocs, promptLocalDocs,
                        composeEvidenceFallback(finalQuery, promptWebDocs, promptVectorDocs, promptLocalDocs,
                                agentDebugSnapshot, historyStr, queryDomain.isLowRisk()),
                        citableEvidence), finalQuery);
            }
        }
        long started = System.nanoTime();
        try {
            ChatRequestDto finalReq = applyFinalAnswerSamplingOverrides(llmReq);
            AtomicReference<LlmCallSuccess> primarySuccessRef = new AtomicReference<>();
            if (ctx.ensembleCandidates() != null && !ctx.ensembleCandidates().isEmpty()) {
                TraceStore.put("ensemble.judge.skipped", "reference_only");
            }
            boolean strictSingleAttempt = hasThreeRoleRefinementCandidates(ctx);
            TraceStore.put(
                    "conversation.frame.primaryModelCallCount",
                    TraceStore.getLong("conversation.frame.primaryModelCallCount") + 1L);
            appendConversationFrameBreadcrumb(conversationFrame, "conversationFrame.primaryCallBreadcrumb");
            try {
                draft = callWithRetryReportingSuccess(
                        model,
                        msgs,
                        finalReq,
                        primarySuccessRef::set,
                        strictSingleAttempt,
                        vp == null ? null : vp.targetTokenBudgetOut());
            } finally {
                refreshConversationFrameAttemptCoverage(
                        conversationFrame,
                        "conversationFrame.requestAttemptBreadcrumb");
            }
            throwIfCancelled(sessionIdLong);
            traceS8Integrity("primary", finalQuery, draft);
            TraceStore.put("ensemble.finalAnswerOwner", "primary_model");
            LlmCallSuccess primarySuccess = primarySuccessRef.get();
            if (primarySuccess != null) {
                recordModelSuccess(primarySuccess.modelId(), primarySuccess.endpoint());
            }
            if (primaryPermit != null) {
                long ms = (System.nanoTime() - started) / 1_000_000L;
                if (draft == null || draft.isBlank()) {
                    primaryPermit.completeBlank("chat-draft-primary");
                } else {
                    primaryPermit.completeSuccess(ms);
                }
            }
        } catch (CancellationException ce) {
            if (primaryPermit != null) {
                primaryPermit.completeCancelled(ce, "chat-draft-primary");
            }
            if (!(ce instanceof ClientCancellationException) && !isCancelled(sessionIdLong)) {
                throw ce;
            }
            clearCancel(sessionIdLong);
            log.info("[Chat] cancelled. sessionHash={}", SafeRedactor.hashValue(String.valueOf(sessionIdLong)));
            return ChatResult.of("?붿껌??痍⑥냼?섏뿀?듬땲??", "cancelled", useRag);
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.chatDraft", e); String resolvedModel = resolvedModelName;
            var responseTerminal = com.example.lms.llm.gateway.LlmResponseTerminalException.find(e);
            if (responseTerminal != null) {
                if (primaryPermit != null) {
                    if (responseTerminal.failureClass().hardBreakerFailure())
                        primaryPermit.completeFailure(NightmareBreaker.classify(e), e, "chat-draft-primary");
                    else primaryPermit.completeAbandoned("chat-draft-primary", responseTerminal.reasonCode());
                }
                throw responseTerminal;
            }

            LlmFastBailoutException fastBail = unwrapFastBail(e);
            String fastBailReason = fastBail == null
                    ? ""
                    : String.valueOf(fastBail.getMessage()).toLowerCase(java.util.Locale.ROOT);
            boolean requestBudgetFastBail = fastBailReason.contains("request budget");
            if (primaryPermit != null) {
                NightmareBreaker.FailureKind permitKind = NightmareBreaker.classify(e);
                if (requestBudgetFastBail) {
                    primaryPermit.completeAbandoned("chat-draft-primary", "request-budget");
                } else if (fastBail != null && fastBailReason.contains("blank")) {
                    primaryPermit.completeBlank("chat-draft-primary");
                } else if (permitKind == NightmareBreaker.FailureKind.INTERRUPTED) {
                    primaryPermit.completeCancelled(e, "chat-draft-primary");
                } else {
                    primaryPermit.completeFailure(permitKind, e, "chat-draft-primary");
                }
            }

            if (req.isStrictModelSelection()) {
                throw com.example.lms.llm.ModelSelectionException.failure(e);
            }
            LlmConfigurationException cfg = unwrapLlmConfigurationException(e);
            if (cfg != null) {
                String userMsg = cfg.getUserMessage();
                if (userMsg == null || userMsg.isBlank()) {
                    userMsg = "?좑툘 LLM ?ㅼ젙 ?ㅻ쪟濡??붿껌??泥섎━?????놁뒿?덈떎. (愿由ъ옄: 紐⑤뜽/?붾뱶?ъ씤???ㅼ젙 ?뺤씤)";
                }
                TraceStore.put("llm.config.code", cfg.getCode());
                TraceStore.put("llm.config.modelHash", SafeRedactor.hashValue(cfg.getModel()));
                TraceStore.put("llm.config.endpointHost", hostOf(cfg.getEndpoint()));
                TraceStore.put("llm.config.endpointHash", SafeRedactor.hashValue(cfg.getEndpoint()));
                log.error("[LLM_CONFIG] code={} modelHash={} endpointHost={} endpointHash={}{}",
                        cfg.getCode(), SafeRedactor.hashValue(cfg.getModel()), hostOf(cfg.getEndpoint()), SafeRedactor.hashValue(cfg.getEndpoint()), LogCorrelation.suffix());
                String usedModel = (cfg.getModel() != null && !cfg.getModel().isBlank()) ? cfg.getModel()
                        : resolvedModel;
                throwIfCancelled(sessionIdLong);
                clearCancel(sessionIdLong);
                return ChatResult.of(userMsg, usedModel + ":fail:" + cfg.getCode(), useRag);
            }

            String modelFailureFallbackQuestion = requestBudgetFastBail
                    ? EvidenceAnswerComposer.preserveExplicitConditionalHold(finalQuery, userQuery)
                    : finalQuery;

            if (irregularityProfiler != null && !requestBudgetFastBail) {
                irregularityProfiler.markHighRisk(GuardContextHolder.get(), "chat_failed");
            }

            if (fastBail != null) {
                try {
                    if (requestBudgetFastBail) TraceStore.put("llm.fastBailReason", "request_budget_exhausted"); else if (fastBailReason.contains("upstream")) TraceStore.put("llm.fastBailUpstream5xx", true); else if (fastBailReason.contains("blank")) TraceStore.put("llm.fastBailBlankResponse", true); else TraceStore.put("llm.fastBailTimeout", true);
                    TraceStore.put("llm.fastBailTimeout.timeoutHits", fastBail.getTimeoutHits());
                    TraceStore.put("llm.fastBailTimeout.attempt", fastBail.getAttempt());
                    TraceStore.put("llm.fastBailTimeout.maxAttempts", fastBail.getMaxAttempts());
                    TraceStore.put("chat.evidenceFallback.conditionalConstraintCarried",
                            requestBudgetFastBail
                                    && !Objects.equals(modelFailureFallbackQuestion, finalQuery));
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.fastBailTrace", ignore); }
                String fastBailFailureClass = requestBudgetFastBail
                        ? "request_budget_exhausted"
                        : fastBailReason.contains("upstream")
                        ? "model_upstream_5xx"
                        : fastBailReason.contains("blank")
                        ? "model_blank_response"
                        : "model_timeout";
                if (!requestBudgetFastBail) {
                    recordLocalLlmOperatorAction(
                            "llm_fast_bail",
                            fastBailFailureClass,
                            "inspect_model_route_or_start_local_llm",
                            100,
                            Math.max(1, fastBail.getTimeoutHits()));
                    log.warn(
                            "[LLM_FAST_BAIL_TIMEOUT] degrade-to-evidence. sessionHash={}, modelHash={}, timeoutHits={} attempt={}/{}",
                            SafeRedactor.hashValue(String.valueOf(sessionIdLong)), SafeRedactor.hashValue(resolvedModel), fastBail.getTimeoutHits(), fastBail.getAttempt(),
                            fastBail.getMaxAttempts());
                } else {
                    log.info("[LLM_REQUEST_BUDGET_EXHAUSTED] degrade-to-evidence. sessionHash={} modelHash={}",
                            SafeRedactor.hashValue(String.valueOf(sessionIdLong)), SafeRedactor.hashValue(resolvedModel));
                }
            } else {
                recordLocalLlmOperatorAction("llm_unavailable_after_retries", "model_unavailable", "inspect_model_route_or_start_local_llm", 100, 1);
                log.error("[LLM] unavailable after retries. sessionHash={}, modelHash={} err={}",
                        SafeRedactor.hashValue(String.valueOf(sessionIdLong)), SafeRedactor.hashValue(resolvedModel), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            }

            // ??(UAW: Bypass Routing) LLM ?ㅽ뙣 ?? 利앷굅媛 ?덉쑝硫?evidence 湲곕컲 ?듬??쇰줈 sidetrain
            throwIfCancelled(sessionIdLong);
            clearCancel(sessionIdLong);
            return sanitizeFallbackResult(NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModel, useRag, promptWebDocs, promptVectorDocs, promptLocalDocs,
                    composeEvidenceFallback(modelFailureFallbackQuestion, promptWebDocs, promptVectorDocs,
                            promptLocalDocs, agentDebugSnapshot, historyStr,
                            queryDomain.isLowRisk()),
                    citableEvidence), modelFailureFallbackQuestion);
        }

        boolean verifierFollowUp = isFollowUpQuery(finalQuery, lastAnswer);
        String verifierMemoryContext = verifierFollowUp ? memoryCtx : "";
        String verifierEligibilityEvidence = verifierEligibilityContext(
                verifierEvidenceContext, memoryCtx, verifierFollowUp);
        boolean verifyAnswer = shouldVerify(verifierEligibilityEvidence, llmReq, sig);
        String verified = draft;
        boolean finalAnswerCreativeApplied = false;
        boolean finalAnswerFallbackApplied = false;
        boolean finalAnswerMemoryDeniedByPolicy = deniesConversationMemoryWrite(
                interactionPolicyDecision.suppressMemoryWrites(),
                conversationFrame);

        // ??Evidence-aware Guard: ensure entity coverage before expansion.
        // When evidence snippets are available, verify that the answer mentions key
        // entities from the evidence. If
        // insufficient coverage is detected, the guard will regenerate the answer using
        // a higher-tier model via
        // modelRouter.route(). This is executed on the verified draft prior to any
        // expansion.
        // =========================================================================
        // [SECTION 4] Quality gate and final answer chain
        // Future extraction candidate: QualityGateChain.
        // =========================================================================
        if (((useWeb || useRag) && env != null) || interactionPolicyDecision.defensive()) {
            try {
                java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs = new java.util.ArrayList<>();
                int evidIndex = 1;
                if (useWeb && promptWebDocs != null) {
                    for (var c : promptWebDocs) {
                        String docUrl = extractUrlOrFallback(c, evidIndex, false);
                        evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(docUrl, safeTitle(c), safeSnippet(c)));
                        evidIndex++;
                    }
                }
                if (useRag && promptVectorDocs != null) {
                    for (var c : promptVectorDocs) {
                        String docUrl = extractUrlOrFallback(c, evidIndex, true);
                        evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(docUrl, safeTitle(c), safeSnippet(c)));
                        evidIndex++;
                    }
                }
                if (interactionPolicyDecision.defensive() && promptLocalDocs != null) {
                    for (var document : promptLocalDocs) {
                        if (document == null) {
                            continue;
                        }
                        String localId = localDocumentEvidenceId(document, evidIndex);
                        evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(
                                localId,
                                localDocumentTitle(document),
                                localDocumentSnippet(document)));
                        evidIndex++;
                    }
                }
                evidenceDocs = filterOfficialSourceEvidenceDocs(finalQuery, evidenceDocs);
                if (!evidenceDocs.isEmpty() || interactionPolicyDecision.defensive()) {
                    var guard = evidenceAwareGuard;
                    java.util.Set<String> suspectEvidenceIds = interactionSuspectEvidenceIds;
                    EvidenceAwareGuard.InteractionEvidencePreparation interactionPreparation =
                            evidenceAwareGuard.prepareInteractionEvidence(
                                    interactionPolicyDecision,
                                    evidenceDocs,
                                    suspectEvidenceIds);
                    appendInteractionPolicyBreadcrumb(
                            interactionPolicyDecision,
                            "interactionPolicy.guardContainmentBreadcrumb");
                    evidenceDocs = new java.util.ArrayList<>(interactionPreparation.cleanEvidence());
                    if (!interactionPreparation.memoryWriteAllowed()) {
                        finalAnswerMemoryDeniedByPolicy = true;
                    }
                    if (interactionPreparation.blockRequired()) {
                        finalAnswerFallbackApplied = true;
                        verified = "evidence_needed: clean evidence unavailable / verify containment and retrieval evidence";
                    } else {

                    // 1) 珥덉븞 而ㅻ쾭由ъ? 蹂댁젙 (湲곗〈 ensureCoverage 濡쒖쭅 ?좎?)
                    if (!evidenceDocs.isEmpty()) {
                        var coverageRes = guard.ensureCoverage(verified, evidenceDocs,
                                s -> modelRouter.route("PAIRING", "HIGH", vp.hint(), 2048, effectiveRequestedModelFinal),
                                new RouteSignal(0.3, 0, 0.2, 0, null, null, 2048, null, "evidence-guard"),
                                2);
                        if (coverageRes.regeneratedText() != null) {
                            verified = coverageRes.regeneratedText();
                        }
                    }

                    // 2) ?쒖꽑1/?쒖꽑2 GuardAction 湲곕컲 理쒖쥌 ?먮떒
                    final String draftBeforeGuard = verified;
                    try {
                        com.example.lms.resilience.RagFailureBlackboxService.projectCurrentTrace(
                                "ChatWorkflow.evidenceGuard.preDecision");
                    } catch (Throwable ignore) {
                        ChatWorkflowTraceSuppressions.traceSuppressed("evidenceGuard.preDecisionProjection", ignore);
                    }
                    EvidenceAwareGuard.GuardDecision decision = guard.guardWithEvidence(draftBeforeGuard, evidenceDocs,
                            2,
                            interactionPolicyDecision.strictEvidence() ? VisionMode.STRICT : visionMode);

                    // [TRACE] Record guard outcome in a structured form (fail-soft).
                    try {
                        TraceStore.put("guard.action", (decision != null && decision.action() != null)
                                ? decision.action().name()
                                : "");
                        if (decision != null && decision.action() != null
                                && decision.action().name().equals("REWRITE")) {
                            TraceStore.put("guard.degradedToEvidence", true);
                            TraceStore.put("answer.mode", "EVIDENCE_ONLY");
                        }
                        String actionName = (decision != null && decision.action() != null)
                                ? decision.action().name()
                                : "UNKNOWN";
                        int selectedEvidenceCount = decision != null && decision.evidenceList() != null
                                ? decision.evidenceList().size()
                                : evidenceDocs.size();
                        boolean blocked = "REWRITE".equals(actionName) || "BLOCK".equals(actionName);
                        Map<String, Object> input = new LinkedHashMap<>();
                        input.put("queryHash", SafeRedactor.hash12(finalQuery));
                        input.put("queryLen", safeLen(finalQuery));
                        input.put("requestedTopK", evidenceDocs.size());
                        input.put("mode", "evidence_guard");
                        Map<String, Object> output = new LinkedHashMap<>();
                        output.put("returnedCount", evidenceDocs.size());
                        output.put("afterFilterCount", selectedEvidenceCount);
                        output.put("selectedCount", selectedEvidenceCount);
                        output.put("promotedCount", selectedEvidenceCount);
                        output.put("sourceDiversity", evidenceDocs.isEmpty() ? 0 : 1);
                        Map<String, Object> failure = new LinkedHashMap<>();
                        if (blocked) {
                            failure.put("reasonCode", actionName.toLowerCase(Locale.ROOT));
                            failure.put("failureClass", "evidence_guard");
                            failure.put("exceptionType", "None");
                        }
                        Map<String, Object> control = new LinkedHashMap<>();
                        control.put("action", blocked ? "block" : "promote");
                        control.put("applied", true);
                        control.put("reasonCode", actionName.toLowerCase(Locale.ROOT));
                        emitRagPipelineEvent("guard", "evidence_guard", "complete",
                                "ChatWorkflow.evidenceGuard", blocked ? "blocked" : "ok",
                                input, output, failure, control);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.outcomeTrace", ignore);
        }

                    switch (decision.action()) {
                        case ALLOW -> {
                            // ?쒖꽑1: ?듬? ?ъ슜 + 硫붾え由?媛뺥솕 ?덉슜
                            verified = decision.finalDraft();
                        }
                        case ALLOW_NO_MEMORY -> {
                            // ?쒖꽑2: ?듬? ?ъ슜, 硫붾え由?媛뺥솕 湲덉?
                            finalAnswerMemoryDeniedByPolicy = true;
                            String out = decision.finalDraft();
                            // If this is a citations-detour case, try a one-shot cheap web retry to recover
                            // citations.
                            int detourEvidenceCountBefore = evidenceDocs == null ? 0 : evidenceDocs.size();
                            try {
                                DetourRetryResult retryResult = tryDetourCheapRetry(finalQuery, queryDomain, metaHints,
                                        sessionIdLong, visionMode, evidenceDocs, draftBeforeGuard, model, llmReq,
                                        breakerKey);
                                lateUnattributedEvidenceAdded = detectLateUnattributedEvidence(
                                        detourEvidenceCountBefore,
                                        evidenceDocs == null ? 0 : evidenceDocs.size(),
                                        retryResult.unattributedEvidenceAdded());
                                if (retryResult.content() != null && !retryResult.content().isBlank()) {
                                    out = retryResult.content();
                                }
                            } catch (Exception ignore) {
                                ChatWorkflowTraceSuppressions.traceSuppressed("guard.detourCheapRetry", ignore);
                            } finally {
                                lateUnattributedEvidenceAdded = lateUnattributedEvidenceAdded
                                        || detectLateUnattributedEvidence(
                                                detourEvidenceCountBefore,
                                                evidenceDocs == null ? 0 : evidenceDocs.size(),
                                                false);
                            }
                            verified = out;
                            log.debug("[ChatService] GuardAction: ALLOW_NO_MEMORY (Vision 2)");
                        }
                        case REWRITE -> {
                            finalAnswerFallbackApplied = true;
                            // Prompt 臾몄옄?댁쓣 吏곸젒 議곕┰?섏? ?딅뒗??
                            // Evidence 湲곕컲 ?듬? 而댄룷?濡??ъ옉?깊븳??
                            log.debug("[ChatWorkflow] GuardAction: REWRITE -> evidence-only answer composer");
                            verified = composeEvidenceOnlyAnswer(evidenceDocs, finalQuery);
                        }
                        case BLOCK -> {
                            finalAnswerFallbackApplied = true;
                            // ?듬? 李⑤떒: STRIKE/?뺤텞/?고쉶 ?곹솴?대㈃ '?덉쟾??????듬?'?쇰줈 ?섎졃
                            if (sig.bypassMode() || sig.strikeMode() || sig.compressionMode()
                                    || (hints != null && hints.isBypassMode())) {
                                verified = bypassRoutingService.renderSafeAlternative(
                                        finalQuery,
                                        decision.evidenceList(),
                                        queryDomain.isLowRisk(),
                                        sig);
                                log.debug("[ChatService] GuardAction: BLOCK -> BypassRouting ({})", sig.modeLabel());
                            } else {
                                // 湲곕낯: guard媛 留뚮뱺 safe draft ?좎?
                                verified = decision.finalDraft();
                                log.debug("[ChatService] GuardAction: BLOCK -> Guard finalDraft");
                            }
                        }
                        default -> {
                            // no-op
                        }
                    }

                    // 3) [FAIL-SAFE] 理쒖쥌 ?묐떟 吏곸쟾 寃利?
                    if (evidenceDocs != null
                            && !evidenceDocs.isEmpty()
                            && com.example.lms.service.guard.EvidenceAwareGuard.looksNoEvidenceTemplate(verified)) {
                        finalAnswerFallbackApplied = true;
                        recordFinalRescueSilentFailure(finalQuery, breakerKey, evidenceDocs.size(),
                                "evidence_guard_no_info_with_evidence");
                        log.error("[RESCUE] Final output is still 'No Info' despite evidence! Forcing fallback.");
                        verified = guard.degradeToEvidenceList(evidenceDocs);
                    }
                    }
                }
            } catch (Exception e) {
                if (interactionPolicyDecision.failClosed()) {
                    finalAnswerMemoryDeniedByPolicy = true;
                    finalAnswerFallbackApplied = true;
                    verified = "evidence_needed: defensive enforcement failed / verify guard containment";
                    TraceStore.put("interaction.policy.containment.status", "ENFORCEMENT_FAILED");
                    TraceStore.put("interaction.policy.containment.inputCount", 0);
                    TraceStore.put("interaction.policy.containment.cleanCount", 0);
                    TraceStore.put("interaction.policy.containment.quarantinedCount", 0);
                    TraceStore.put("interaction.policy.containment.blockRequired", true);
                    appendInteractionPolicyBreadcrumb(
                            interactionPolicyDecision,
                            "interactionPolicy.enforcementFailureBreadcrumb");
                } else {
                    log.debug("[guard] evidence-aware coverage failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                }
            }
        }

        // ?쇄뼹??[RESCUE LOGIC PHASE 2] 理쒖쥌 ?뚰뵾 ?듬? 媛뺤젣 ?꾪솚 ?쇄뼹??
        boolean hasAnyEvidence = (useWeb && topDocs != null && !topDocs.isEmpty())
                || (useRag && vectorDocs != null && !vectorDocs.isEmpty());
        if (hasAnyEvidence && isDefinitiveFailure(verified)) {
            finalAnswerFallbackApplied = true;
            long rescueNo = rescueCount.incrementAndGet();
            int rescueEvidenceCount = (useWeb && topDocs != null ? topDocs.size() : 0)
                    + (useRag && vectorDocs != null ? vectorDocs.size() : 0);
            String rescueQueryHash = SafeRedactor.hashValue(finalQuery);
            recordFinalRescueSilentFailure(finalQuery, breakerKey, rescueEvidenceCount,
                    "definitive_failure_with_evidence");
            log.info("[Rescue]#{} redacted queryHash={} queryLen={}",
                    rescueNo,
                    rescueQueryHash,
                    finalQuery == null ? 0 : finalQuery.length());
            log.info("[Rescue]#{}, visionMode={}, ?듬???'?뺣낫 遺議? ?⑦꽩?쇰줈 ?먮퀎?섏뿀?쇰굹 利앷굅媛 議댁옱??"
                    + "(useWeb={}, topDocs={}, useRag={}, vectorDocs={}). EvidenceComposer濡?媛뺤젣 ?꾪솚?⑸땲?? (queryHash={})",
                    rescueNo,
                    visionMode,
                    useWeb, (topDocs != null ? topDocs.size() : 0),
                    useRag, (vectorDocs != null ? vectorDocs.size() : 0),
                    rescueQueryHash);

            java.util.List<com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc> rescueDocs = new java.util.ArrayList<>();
            try {
                int _idx = 1;
                if (useWeb && topDocs != null) {
                    for (var c : topDocs) {
                        rescueDocs.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                extractUrlOrFallback(c, _idx, false),
                                safeTitle(c),
                                safeSnippet(c)));
                        _idx++;
                    }
                }
                if (useRag && vectorDocs != null) {
                    for (var c : vectorDocs) {
                        rescueDocs.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                extractUrlOrFallback(c, _idx, true),
                                safeTitle(c),
                                safeSnippet(c)));
                        _idx++;
                    }
                }
                rescueDocs = filterOfficialSourceEvidenceDocs(finalQuery, rescueDocs);

                boolean lowRisk = isLowRiskDomain(rescueDocs);
                verified = evidenceAnswerComposer.compose(finalQuery, rescueDocs, lowRisk);
                if (verified != null) {
                    log.debug("[Rescue]#{} 利앷굅 湲곕컲 ?듬? ?앹꽦 ?꾨즺 (length={})", rescueNo, verified.length());
                }
            } catch (Exception e) {
                log.warn("[Rescue]#{} EvidenceComposer ?ㅽ뙣, Evidence 由ъ뒪?몃줈 Fallback ?쒕룄: {}", rescueNo, String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                // Fallback: 理쒖냼??利앷굅 紐⑸줉?대씪??蹂댁뿬二쇨린
                try {
                    com.example.lms.service.guard.EvidenceAwareGuard guard = evidenceAwareGuard;
                    verified = guard.degradeToEvidenceList(rescueDocs);
                } catch (Exception e2) {
                    // 理쒖쥌 Fallback
                    log.warn("[Rescue]#{} Evidence 由ъ뒪???앹꽦???ㅽ뙣: {}", rescueNo, String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e2)), String.valueOf(e2).length()));
                    verified = "寃??寃곌낵媛 議댁옱?섎굹 ?듬? ?앹꽦???ㅽ뙣?덉뒿?덈떎. ?ㅼ떆 ?쒕룄??二쇱꽭??";
                }
            }
        } else if (!hasAnyEvidence && visionMode == VisionMode.FREE) {
            // 利앷굅媛 ?녿뒗 寃쎌슦 FREE 紐⑤뱶?먯꽌??異붿륫/李쎌옉???섏? ?딄퀬 紐낆떆?곸쑝濡?'?뺣낫 ?놁쓬'?쇰줈 ?묐떟
            if (isDefinitiveFailure(verified)) {
                verified = "?뺣낫 ?놁쓬";
            }
        }
        // ?꿎뼯??[END RESCUE LOGIC] ?꿎뼯??

        // ?? 6) 湲몄씠 寃利???議곌굔遺 1???뺤옣 ???????????????????????????
        String out = verified;
        // ??Weak-draft suppression: if output still looks empty/"?뺣낫 ?놁쓬", degrade to
        // evidence list instead of leaking
        try {
            if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(out) && (useWeb || useRag)) {
                finalAnswerFallbackApplied = true;
                boolean hasWebEvidence = topDocs != null && !topDocs.isEmpty();
                boolean hasVectorEvidence = vectorDocs != null && !vectorDocs.isEmpty();
                if (hasWebEvidence || hasVectorEvidence) {
                    java.util.List<com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc> _ev = new java.util.ArrayList<>();
                    int _i = 1;
                    if (hasWebEvidence) {
                        for (var d : topDocs) {
                            String docUrl = extractUrlOrFallback(d, _i, false);
                            _ev.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                    docUrl,
                                    safeTitle(d),
                                    safeSnippet(d)));
                            _i++;
                        }
                    }
                    if (hasVectorEvidence) {
                        for (var d : vectorDocs) {
                            String docUrl = extractUrlOrFallback(d, _i, true);
                            _ev.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                    docUrl,
                                    safeTitle(d),
                                    safeSnippet(d)));
                            _i++;
                        }
                    }
                    _ev = filterOfficialSourceEvidenceDocs(finalQuery, _ev);
                    boolean lowRisk = isLowRiskDomain(_ev);
                    try {
                        out = evidenceAnswerComposer.compose(finalQuery, _ev, lowRisk);
                    } catch (Exception composerError) {
                        log.debug("[guard] evidence composer failed, falling back to evidence list: {}",
                                String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(composerError)), String.valueOf(composerError).length()));
                        out = evidenceAwareGuard.degradeToEvidenceList(_ev);
                    }
                } else {
                    out = "충분한 증거를 찾지 못했습니다. 더 구체적인 키워드나 맥락을 알려주시면 정확도가 올라갑니다.";
                }
            } else if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(out)) {
                TraceStore.put("answer.guardRecovery.skipped", "retrieval_off_direct");
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("answer.guardRecovery", ignore);
        }

        traceS8Integrity("preExpansion", finalQuery, out);
        if (allowsConversationExpansion(conversationFrame)
                && lengthVerifier.isShort(out, vp.minWordCount())) {
            out = Optional.ofNullable(answerExpander.expandWithLc(out, vp, model)).orElse(out);
        }
        traceS8Integrity("postExpansion", finalQuery, out);

        // Evidence-aware regeneration guard (legacy) removed: the pipeline either
        // rewrites using evidence-only answers
        // or expands with the configured answerExpander.
        // [Dual-Vision] View2 2李??⑥뒪
        // - 湲곕낯: (GAME/SUBCULTURE)?먯꽌留?free idea
        // - projection_agent.v1: GENERAL源뚯? ?뺤옣 + merge + final polish
        if (allowsConversationExpansion(conversationFrame)
                && visionMode != VisionMode.STRICT
                && (riskLevel == null || !"HIGH".equals(riskLevel))) {
            boolean allowProjectionAgent = projectionPipeline
                    && projectionPlan != null
                    && queryDomain != null
                    && queryDomain.isLowRisk()
                    && answerMode != AnswerMode.FACT;

            if (allowProjectionAgent) {
                try {
                    String creative = generateProjectionDraftFromPlan(
                            finalQuery,
                            out, // strictAnswer
                            ctxText,
                            vp,
                            llmReq,
                            projectionPlan);

                    if (StringUtils.hasText(creative)) {
                        if (projectionMergeService != null) {
                            // merge() config瑜??쒖슜?섏?留? mergeDualView??2?몄옄留?諛쏆쑝誘濡?湲곕낯 援ы쁽 ?ъ슜
                            out = projectionMergeService.mergeDualView(out, creative);
                            finalAnswerCreativeApplied = true;
                            if (freeIdeaCount != null) {
                                freeIdeaCount.incrementAndGet();
                            }

                            // Final answer pass (projection.final)
                            out = finalizeProjectionAnswerFromPlan(
                                    finalQuery,
                                    out,
                                    vp,
                                    llmReq,
                                    projectionPlan);
                        } else {
                            TraceStore.put("prompt.projection.creative.skipped", "no_merge_service");
                            log.debug("[ProjectionAgent] creative section suppressed: projectionMergeService=null");
                        }
                    }
                } catch (Exception e) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("projection.view2.pipeline", e); log.debug("[ProjectionAgent] View2 pipeline failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                }
            } else {
                boolean lowRiskDomain = (queryDomain == QueryDomain.GAME || queryDomain == QueryDomain.SUBCULTURE);
                if (lowRiskDomain && answerMode != AnswerMode.FACT) {
                    try {
                        String creative = generateFreeIdeaDraft(
                                finalQuery,
                                out, // strictAnswer
                                ctxText,
                                modelRouter,
                                vp,
                                effectiveRequestedModel);
                        if (StringUtils.hasText(creative)) {
                            if (projectionMergeService != null) {
                                out = projectionMergeService.mergeDualView(out, creative);
                                finalAnswerCreativeApplied = true;
                                if (freeIdeaCount != null) {
                                    freeIdeaCount.incrementAndGet();
                                }
                                log.debug("[DualVision] View2 creative section merged (length={})", creative.length());
                            } else {
                                TraceStore.put("prompt.dualvision.creative.skipped", "no_merge_service");
                                log.debug("[DualVision] creative section suppressed: projectionMergeService=null");
                            }
                        }
                    } catch (Exception e) {
                        ChatWorkflowTraceSuppressions.traceSuppressed("dualVision.view2.generation", e); log.debug("[DualVision] View2 generation failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                    }
                }
            }
        }

        // ?? 7) ?꾩쿂由?媛뺥솕/由ы꽩 ??????????????????????????????????????
        // (??긽 ??? - ?명꽣?됲꽣 + 湲곗〈 媛뺥솕 濡쒖쭅 蹂묓뻾 ?덉슜

        try {
            String groundedFieldAnswer = composeOpenAiResponsesWebSearchFieldAnswer(
                    (userQuery != null && !userQuery.isBlank()) ? userQuery : finalQuery,
                    promptWebDocs,
                    citableEvidence);
            if (groundedFieldAnswer != null && !groundedFieldAnswer.isBlank()) {
                out = groundedFieldAnswer;
                TraceStore.put("answer.exactFieldValue.grounded", true);
                TraceStore.put("answer.exactFieldValue.source", "citable_web_evidence");
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("answer.exactFieldValue", ignore);
        }

        // ???ㅼ젣 紐⑤뜽紐낆쑝濡?蹂닿퀬 (?ㅽ뙣 ???덉쟾 ?대갚)
        String modelUsed;
        try {
            modelUsed = modelRouter.resolveModelName(model);
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("model.resolveName", e); modelUsed = String.format("lc:%s", getModelName(model));
        }

        // Semantic blank rescue must happen before the single final verifier.
        boolean emptyAnswerFallbackApplied = out == null || out.isBlank();
        out = emptyAnswerGuard(out, finalQuery, topDocs, vectorDocs);
        if (emptyAnswerFallbackApplied && out != null && !out.isBlank()) {
            finalAnswerFallbackApplied = true;
            if (modelUsed == null || modelUsed.isBlank()) {
                modelUsed = "unknown";
            }
            modelUsed = modelUsed + ":fallback:empty-answer";
        }
        final boolean priorFallbackApplied = finalAnswerFallbackApplied;
        final String priorFallbackContent = priorFallbackApplied ? out : null;

        boolean finalVerificationOutcomeKnown = false;
        boolean finalVerificationAcceptedForMemory = false;
        String finalVerificationStatus = "not_run";
        // Sole policy-gated final-verifier call site: runtime cardinality per workflow invocation is 0..1.
        throwIfCancelled(sessionIdLong);
        if (verifyAnswer) {
            recordCostZone(
                    "cross_verify",
                    resolvedModelName,
                    safeLen(finalQuery) + safeLen(verifierEvidenceContext) + safeLen(verifierMemoryContext) + safeLen(out),
                    1,
                    false,
                    "ChatWorkflow.verifier");
            FactVerifierService.DetailedVerificationResult verification = verifier.verifyDetailed(
                    finalQuery,
                    /* context */ verifierEvidenceContext,
                    /* memory */ verifierMemoryContext,
                    out,
                    resolvedModelName,
                    verifierFollowUp);
            out = verification.answer();
            finalVerificationOutcomeKnown = verification.outcomeKnown();
            finalVerificationAcceptedForMemory = verification.acceptedForMemory();
            finalVerificationStatus = verification.status();
        }
        throwIfCancelled(sessionIdLong);
        FinalVerificationReleaseDecision releaseDecision = applyFinalVerificationReleaseGate(
                out,
                verifyAnswer,
                finalVerificationStatus,
                finalVerificationOutcomeKnown,
                finalVerificationAcceptedForMemory);
        if (priorFallbackApplied && releaseDecision.releaseAllowed()) {
            releaseDecision = new FinalVerificationReleaseDecision(
                    priorFallbackContent,
                    releaseDecision.releaseStatus(),
                    releaseDecision.reasonCode(),
                    true,
                    false);
        }
        FinalVerificationReleaseDecision baseReleaseDecision = releaseDecision;
        EvidenceReleaseState evidenceReleaseState = deriveEvidenceReleaseState(
                promotionResult,
                citableEvidence,
                lateUnattributedEvidenceAdded,
                retrievalReleaseContract);
        releaseDecision = applyEvidenceReleasePolicy(
                baseReleaseDecision,
                evidenceReleaseState,
                evidenceReleaseRequired,
                priorFallbackApplied,
                retrievalReleaseContract.explicitDirectOff());
        out = releaseDecision.content();
        if (releaseDecision.evidencePolicyApplied()) {
            finalAnswerMemoryDeniedByPolicy = true;
            finalAnswerFallbackApplied = true;
        }
        boolean protectedBaseContent = !baseReleaseDecision.releaseAllowed() || priorFallbackApplied;
        boolean finalAnswerWeakResult = EvidenceAwareGuard.looksWeak(out);
        String finalAnswerForMemoryCandidate = out;
        if (!protectedBaseContent && !releaseDecision.evidencePolicyApplied()) {
            out = appendFinalEvidenceOnce(
                    out,
                    topDocs,
                    vectorDocs,
                    userQuery,
                    ragEvidenceAttributionService,
                    citableEvidence);
        }

        throwIfCancelled(sessionIdLong);
        FinalAnswerPostProcessor.Result finalized = finalAnswerPostProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        out,
                        finalAnswerForMemoryCandidate,
                        finalVerificationOutcomeKnown,
                        finalVerificationAcceptedForMemory,
                        memoryMode != null && memoryMode.isWriteEnabled(),
                        finalAnswerMemoryDeniedByPolicy || visionMode == VisionMode.FREE,
                        finalAnswerCreativeApplied,
                        finalAnswerFallbackApplied,
                        finalAnswerWeakResult,
                        protectedBaseContent ? null : userQuery));
        out = finalized.content();
        throwIfCancelled(sessionIdLong);
        try {
            TraceStore.put("finalAnswer.memorySaveAllowed", finalized.memorySaveAllowed());
            TraceStore.put("finalAnswer.memoryDenyReason", finalized.memoryDenyReason());
            TraceStore.put("finalAnswer.verificationOutcomeKnown", finalVerificationOutcomeKnown);
            TraceStore.put("finalAnswer.verificationAcceptedForMemory", finalVerificationAcceptedForMemory);
            TraceStore.put("finalAnswer.verificationStatus", finalVerificationStatus);
            TraceStore.put("finalAnswer.releaseStatus", releaseDecision.releaseStatus());
            TraceStore.put("finalAnswer.releaseReason", releaseDecision.reasonCode());
            TraceStore.put("finalAnswer.releaseAllowed", releaseDecision.releaseAllowed());
            TraceStore.put("finalAnswer.evidenceReleaseRequired", evidenceReleaseRequired);
            TraceStore.put("finalAnswer.evidenceReleaseState",
                    evidenceReleaseState.name().toLowerCase(Locale.ROOT));
            TraceStore.put("finalAnswer.evidenceReleaseApplied", releaseDecision.evidencePolicyApplied());
            TraceStore.put("finalAnswer.postprocess.reason", finalized.reasonCode());
            TraceStore.put("finalAnswer.postprocess.contentHash", HashUtil.sha256(out));
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalAnswer.postprocess.trace", ignore);
        }

        if (("blank_content".equals(finalized.reasonCode())
                || "diagnostics_removed_empty".equals(finalized.reasonCode()))
                && (modelUsed == null || !modelUsed.contains(":fallback:empty-answer"))) {
            modelUsed = (modelUsed == null || modelUsed.isBlank() ? "unknown" : modelUsed)
                    + ":fallback:empty-answer";
        }

        throwIfCancelled(sessionIdLong);
        if (finalized.memorySaveAllowed()) {
            ChatRunExecutionContext exactRun = ChatRunExecutionContext.current();
            String finalAnswerForMemory = finalized.memoryContent();
            GuardProfile terminalGuardProfile = guardProfile;
            MemoryMode terminalMemoryMode = memoryMode;
            FinalizedMemoryPersistence.persist(
                    exactRun,
                    ClientCancellationException::new,
                    ChatWorkflowTraceSuppressions::traceSuppressed,
                    new FinalizedMemoryPersistence.Stage(
                            "memory.learningWriteInterceptor",
                            () -> learningWriteInterceptor.ingest(
                                    sessionKey, userQuery, finalAnswerForMemory, /* score */ 0.5)),
                    new FinalizedMemoryPersistence.Stage(
                            "memory.writeInterceptor",
                            () -> memoryWriteInterceptor.save(
                                    sessionKey, userQuery, finalAnswerForMemory, /* score */ 0.5)),
                    new FinalizedMemoryPersistence.Stage(
                            "memory.understandAndMemorize",
                            () -> understandAndMemorizeInterceptor.afterVerified(
                                    sessionKey,
                                    userQuery,
                                    finalAnswerForMemory,
                                    req.isUnderstandingEnabled(),
                                    exactRun)),
                    new FinalizedMemoryPersistence.Stage(
                            "memory.reinforce",
                            () -> reinforce(
                                    sessionKey,
                                    userQuery,
                                    finalAnswerForMemory,
                                    visionMode,
                                    terminalGuardProfile,
                                    terminalMemoryMode)));
        } else {
            log.debug("[FinalAnswer] memory save skipped reason={}", finalized.memoryDenyReason());
        }

        // ?? Needle probe outcome reward (does needle evidence actually contribute?) ??
        throwIfCancelled(sessionIdLong);
        if (needleExecuted && needleContributionEvaluator != null) {
            try {
                NeedleContribution contrib = needleContributionEvaluator.evaluate(
                        needleDocsForReward,
                        needleUrls,
                        topDocs,
                        needleBeforeSignals,
                        needleAfterSignals);
                TraceStore.put("probe.needle.executed", true);
                TraceStore.put("probe.needle.contribution.docsAdded", contrib.docsAdded());
                TraceStore.put("probe.needle.contribution.docsUsedInTopN", contrib.docsUsedInTopN());
                TraceStore.put("probe.needle.contribution.qualityDelta", contrib.qualityDelta());
                TraceStore.put("probe.needle.contribution.triggered", contrib.triggered());
                TraceStore.put("probe.needle.contribution.effective", contrib.isEffective());

                if (needleOutcomeRewarder != null) {
                    double reward = needleOutcomeRewarder.computeReward(contrib);
                    TraceStore.put("probe.needle.reward", reward);
                }
            } catch (Exception e) {
                log.debug("[NeedleProbeReward] {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            }
        }

        // 利앷굅 吏묓빀 ?뺣━
        java.util.LinkedHashSet<String> evidence = new java.util.LinkedHashSet<>();
        if (useWeb && !topDocs.isEmpty())
            evidence.add("WEB");
        if (useRag && !vectorDocs.isEmpty())
            evidence.add("RAG");
        if (memoryCtx != null && !memoryCtx.isBlank())
            evidence.add("MEMORY");
        boolean ragUsed = evidence.contains("WEB") || evidence.contains("RAG");
        Map<String, Object> answerInput = new LinkedHashMap<>();
        answerInput.put("queryHash", SafeRedactor.hash12(finalQuery));
        answerInput.put("queryLen", safeLen(finalQuery));
        answerInput.put("requestedTopK", evidence.size());
        answerInput.put("mode", "final_answer");
        Map<String, Object> answerOutput = new LinkedHashMap<>();
        answerOutput.put("returnedCount", evidence.size());
        answerOutput.put("afterFilterCount", evidence.size());
        answerOutput.put("selectedCount", (out != null && !out.isBlank()) ? 1 : 0);
        answerOutput.put("promotedCount", citableEvidence == null ? 0 : citableEvidence.size());
        answerOutput.put("sourceDiversity", evidence.size());
        Map<String, Object> answerFailure = new LinkedHashMap<>();
        if (out == null || out.isBlank()) {
            answerFailure.put("reasonCode", "silent_failure");
            answerFailure.put("failureClass", "silent_failure");
            answerFailure.put("exceptionType", "None");
        }
        Map<String, Object> answerControl = new LinkedHashMap<>();
        answerControl.put("action", (out != null && !out.isBlank()) ? "promote" : "fail_soft_fallback");
        answerControl.put("applied", true);
        answerControl.put("reasonCode", (out != null && !out.isBlank()) ? "final_answer" : "silent_failure");
        throwIfCancelled(sessionIdLong);
        emitRagPipelineEvent("answer", "final_answer", "complete", "ChatWorkflow.finalAnswer",
                (out != null && !out.isBlank()) ? "ok" : "fallback",
                answerInput, answerOutput, answerFailure, answerControl);
        long responseStageMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        TraceStore.put("rag.pipeline.response.latencyMs", responseStageMs);
        TraceStore.put("rag.pipeline.response.characters", out == null ? 0 : out.length());
        org.slf4j.LoggerFactory.getLogger("rag.pipeline").debug(
                "[rag-pipeline] stage=final-response elapsedMs={} characters={} evidenceCount={}",
                responseStageMs, out == null ? 0 : out.length(), evidence.size());
        clearCancel(sessionIdLong); // ??異붽?

        throwIfCancelled(sessionIdLong);
        java.util.List<RagEvidenceMetadata> visibleEvidenceMetadata =
                filterOfficialSourceEvidenceMetadata(userQuery,
                        citableEvidence == null ? java.util.List.of() : citableEvidence);
        int retrievedCount = (topDocs == null ? 0 : topDocs.size())
                + (vectorDocs == null ? 0 : vectorDocs.size());
        int citableCount = citableEvidence == null ? 0 : citableEvidence.size();
        recordModelRequestTimelinePhase("final_boundary", SafeRedactor.hashValue(out), "none");
        com.example.lms.orchestration.control.RagControlRuntimeAdapter.capturePresentationInput(
                new com.example.lms.orchestration.control.RagControlRuntimeAdapter.RuntimeInput(
                        useWeb || useRag,
                        retrievedCount,
                        citableCount,
                        out == null || out.isBlank(),
                        finalVerificationOutcomeKnown,
                        finalVerificationAcceptedForMemory,
                        releaseDecision.evidencePolicyApplied() || !releaseDecision.releaseAllowed()));
        return ChatResult.of(out, modelUsed, ragUsed,
                java.util.Collections.unmodifiableSet(evidence),
                visibleEvidenceMetadata == null ? java.util.List.of() : visibleEvidenceMetadata);
    } // ??硫붿꽌???? ?먥쁾??諛섎뱶???ル뒗 以묎큵???뺤씤

    /**
     * Final safety net for the chat pipeline: prevent 'silent empty' answers.
     *
     * <p>
     * When the final answer becomes blank (e.g., LLM transient EMPTY/blank), SSE
     * streaming
     * emits no tokens and the client may appear frozen. This guard ensures we
     * always
     * return a non-empty response by stepping down to an evidence-only answer (when
     * available).
     */
    // =========================================================================
    // [SECTION 3] Retrieval/evidence processing
    // Future extraction candidate: RetrievalResultProcessor.
    // =========================================================================
    private String appendFinalEvidenceOnce(
            String answer,
            java.util.List<dev.langchain4j.rag.content.Content> topDocs,
            java.util.List<dev.langchain4j.rag.content.Content> vectorDocs,
            String finalQuery,
            RagEvidenceAttributionService attributionService,
            java.util.List<RagEvidenceMetadata> citableEvidence) {
        try {
            if (attributionService != null) {
                if (citableEvidence != null && !citableEvidence.isEmpty()) {
                    return attributionService.appendFinalEvidenceAppendix(answer, citableEvidence);
                }
                TraceStore.put("rag.evidence.appendix.skipped", "no_promoted_evidence");
            }
            return appendEvidenceReferencesIfNeeded(
                    answer,
                    topDocs == null ? java.util.List.of() : topDocs,
                    vectorDocs == null ? java.util.List.of() : vectorDocs,
                    finalQuery);
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidence.appendix", ignore);
            return answer;
        }
    }

    private String emptyAnswerGuard(
            String out,
            String finalQuery,
            java.util.List<dev.langchain4j.rag.content.Content> topDocs,
            java.util.List<dev.langchain4j.rag.content.Content> vectorDocs) {
        if (out != null && !out.trim().isEmpty()) {
            return out;
        }

        try {
            TraceStore.put("chat.emptyAnswerGuard.triggered", true);
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.triggerTrace", ignore);
        }

        java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs = new java.util.ArrayList<>();

        int idx = 1;
        if (topDocs != null) {
            for (dev.langchain4j.rag.content.Content c : topDocs) {
                if (c == null)
                    continue;
                if (evidenceDocs.size() >= 8)
                    break;
                String url = extractUrlOrFallback(c, idx, false);
                evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, safeTitle(c), safeSnippet(c)));
                idx++;
            }
        }

        idx = 1;
        if (vectorDocs != null) {
            for (dev.langchain4j.rag.content.Content c : vectorDocs) {
                if (c == null)
                    continue;
                if (evidenceDocs.size() >= 8)
                    break;
                String url = extractUrlOrFallback(c, idx, true);
                evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, safeTitle(c), safeSnippet(c)));
                idx++;
            }
        }
        evidenceDocs = filterOfficialSourceEvidenceDocs(finalQuery, evidenceDocs);

        if (!evidenceDocs.isEmpty()) {
            try {
                TraceStore.put("chat.emptyAnswerGuard.evidenceDocs", evidenceDocs.size());
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.evidenceDocsTrace", ignore);
            }

            String fb = composeEvidenceOnlyAnswer(evidenceDocs, finalQuery == null ? "" : finalQuery);
            if (fb != null && !fb.isBlank()) {
                try {
                    TraceStore.put("chat.emptyAnswerGuard.fallback", "evidence_only");
                } catch (Throwable ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.fallbackTrace", ignore);
                }
                return fb;
            }
        }

        try {
            TraceStore.put("chat.emptyAnswerGuard.fallback", "composer_blank");
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.finalFallbackTrace", ignore);
        }

        return emptyAnswerNoEvidenceFallback(finalQuery);
    }

    static String emptyAnswerNoEvidenceFallback(String finalQuery) {
        String q = sourceEvidenceSearchText(finalQuery);
        if (officialOrChangelogEvidenceIntent(finalQuery) || q.contains("evidence_needed")) {
            return "evidence_needed: official/changelog evidence is missing from the current retrieved evidence. "
                    + "Do not treat this as a final answer until official source proof is available.";
        }
        String selfAskRewrite = NoEvidenceChatFallback.selfAskRewriteFallbackOrNull(finalQuery);
        if (selfAskRewrite != null) {
            return selfAskRewrite;
        }
        return "The answer could not be generated. The model may be temporarily unavailable, "
                + "or the retrieved evidence may be insufficient. Please refine the question and try again.";
    }

    /**
     * EvidenceAwareGuard媛 REWRITE瑜??붿껌?덉쓣 ?? LLM???ы샇異쒗븯吏 ?딄퀬
     * ?대? ?섏쭛??evidence(snippets)留뚯쑝濡?蹂댁닔?곸씤 ?듬???援ъ꽦?⑸땲??
     * <p>
     * - Guard媛 "利앷굅 而ㅻ쾭由ъ? 遺議????먮떒??寃쎌슦?먮쭔 ?ъ슜
     * - ?꾪뿕?꾧? ??? ?꾨찓??寃뚯엫/?꾪궎/而ㅻ??덊떚 ???먯꽌??臾멸뎄瑜??꾪솕
     */
    private String composeEvidenceOnlyAnswer(java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            String query) {
        try {
            boolean lowRisk = isLowRiskDomain(evidenceDocs);
            if (evidenceAnswerComposer == null) {
                // Should not happen (DI), but fail-soft.
                return "寃?됰맂 ?먮즺瑜?諛뷀깢?쇰줈 ?뺣━?덉쑝?? ?듬? 而댄룷?媛 ?놁뼱 ?붿빟??援ъ꽦?섏? 紐삵뻽?듬땲??";
            }
            return evidenceAnswerComposer.compose(query, evidenceDocs, lowRisk);
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidenceOnly.compose", e); return "寃??寃곌낵媛 異⑸텇?섏? ?딆븘 ?듬???援ъ꽦?섍린 ?대졄?듬땲??";
        }
    }

    /**
     * Guard detour媛 insufficient citations濡??⑥뼱吏?耳?댁뒪???쒗빐??
     * user ?ъ쭏臾??놁씠 citationMin??梨꾩슦湲??꾪븳 "cheap retry"瑜?1???쒕룄?⑸땲??
     *
     * ?꾨왂:
     * - finalQuery??site: ?뚰듃瑜?1媛?遺숈뿬 webSearchRetriever瑜???踰????몄텧
     * - ?덈줈??EvidenceDoc瑜??⑹퀜 citationMin??留뚯”?섎㈃ evidence-only ?듬??쇰줈 利됱떆 蹂듭썝
     * - ?ㅽ뙣?섎㈃ null (湲곗〈 detour 硫붿떆吏 ?좎?)
     */

    private DetourRetryResult tryDetourCheapRetry(
            String finalQuery,
            QueryDomain queryDomain,
            Map<String, Object> metaHints,
            long sessionIdLong,
            VisionMode visionMode,
            List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            String draftBeforeGuard,
            ChatModel model,
            ChatRequestDto llmReq,
            String breakerKey) {

        if (!this.detourCheapRetryEnabled) {
            TraceStore.put("guard.detour.cheapRetry.skip", "disabled");
            return DetourRetryResult.empty();
        }
        String detourReason = (String) TraceStore.get("guard.detour");
        if (!"insufficient_citations".equals(detourReason)) {
            return DetourRetryResult.empty();
        }
        TraceStore.put("rag.critic.triggered", true);
        TraceStore.put("rag.critic.reason", SafeRedactor.traceLabelOrFallback(detourReason, "unknown"));
        TraceStore.put("rag.critic.retry.count", 0);

        int needAtLeast = 2; // default when minCitations is unknown
        try {
            Object req = TraceStore.get("guard.minCitations.required");
            if (req instanceof Number n && n.intValue() > 0) {
                needAtLeast = n.intValue();
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.requiredCitationsTrace", ignore);
        }
        try {
            com.example.lms.service.guard.GuardContext gctx0 = com.example.lms.service.guard.GuardContextHolder.get();
            Integer mc = (gctx0 != null ? gctx0.getMinCitations() : null);
            if (mc != null && mc > 0) {
                needAtLeast = mc;
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.contextCitationsTrace", ignore);
        }

        boolean forceEscalate = false;
        try {
            Object forced = TraceStore.get("guard.detour.forceEscalate");
            boolean forcedFlag = (forced instanceof Boolean b) ? b
                    : "true".equalsIgnoreCase(String.valueOf(forced));
            Object trig0 = TraceStore.get("web.failsoft.starvationFallback.trigger");
            if (trig0 == null || String.valueOf(trig0).isBlank()) {
                trig0 = TraceStore.get("starvationFallback.trigger");
            }
            boolean belowMin = QueryTypeHeuristics.isBelowMinCitationsTrigger(trig0);
            com.example.lms.service.guard.GuardContext gctx = com.example.lms.service.guard.GuardContextHolder.get();
            String uq = (gctx != null && gctx.getUserQuery() != null && !gctx.getUserQuery().isBlank())
                    ? gctx.getUserQuery()
                    : finalQuery;
            boolean ctxEntity = (gctx != null && gctx.isEntityQuery());
            boolean heurEntity = QueryTypeHeuristics.looksLikeEntityQuery(uq);
            boolean heurDef = QueryTypeHeuristics.isDefinitional(uq);

            String by = forcedFlag
                    ? "forcedFlag"
                    : (ctxEntity ? "ctx.entityQuery" : (heurDef ? "heur.definitional" : "heur.entity"));

            forceEscalate = forcedFlag || (belowMin && (ctxEntity || heurEntity || heurDef));
            TraceStore.put("guard.detour.cheapRetry.forceEscalate", forceEscalate);
            TraceStore.put("guard.detour.cheapRetry.forceEscalate.by", by);
            TraceStore.put("guard.detour.cheapRetry.forceEscalate.trigger", String.valueOf(trig0));
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.forceEscalateTrace", ignore);
        }

        if (evidenceDocs == null) {
            return DetourRetryResult.empty();
        }
        TraceStore.put("rag.critic.docs.before", evidenceDocs.size());
        if (!forceEscalate && evidenceDocs.size() >= needAtLeast) {
            return DetourRetryResult.empty();
        }

        final List<String> sites = chooseDetourRetrySites(finalQuery, queryDomain);
        if (sites.isEmpty()) {
            TraceStore.put("guard.detour.cheapRetry.skip", "no_sites");
            // [PATCH src111_merge15/merge15] Even without site hints, entity/definitional
            // queries can still be regenerated from the existing evidence (if any) to avoid
            // returning an evidence-list detour only.
            if (forceEscalate && evidenceDocs != null && !evidenceDocs.isEmpty()) {
                String regen = tryDetourCheapRetryLlmRegen(finalQuery, draftBeforeGuard, evidenceDocs,
                        model, llmReq, breakerKey, queryDomain, true);
                if (regen != null && !regen.isBlank()) {
                    TraceStore.put("guard.detour.cheapRetry.output", "llm_regen_forced");
                    return new DetourRetryResult(regen, false);
                }
                // ForceEscalate mode: if we can't regen, keep the current draft (avoid evidence-only collapse).
                TraceStore.put("guard.detour.cheapRetry.output", "keep_draft_forced");
                return DetourRetryResult.empty();
            }
            return DetourRetryResult.empty();
        }
        TraceStore.put("guard.detour.cheapRetry.sites", String.join(",", sites));

        final int totalBudgetMs = (int) Math.max(150, this.detourCheapRetryWebBudgetMs);
        final int topK = Math.max(1, this.detourCheapRetryWebTopK);
        final int maxToAdd = Math.max(1, this.detourCheapRetryMaxAddedDocs);

        int before = evidenceDocs.size();
        int addedTotal = 0;

        final boolean combineSitesWithOr = shouldDetourCheapRetryCombineSitesWithOr(finalQuery, sites);
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr", combineSitesWithOr);

        if (combineSitesWithOr && sites.size() > 1) {
            String retryQuery = finalQuery + " " + buildSiteOrClause(sites);
            addedTotal += runDetourSearchAttempt(retryQuery, metaHints, sessionIdLong, visionMode,
                    evidenceDocs, topK, totalBudgetMs, maxToAdd - addedTotal);
        } else {
            final int perAttemptBudgetMs = Math.max(150, totalBudgetMs / Math.max(1, sites.size()));
            for (String site : sites) {
                if (addedTotal >= maxToAdd || evidenceDocs.size() >= needAtLeast) {
                    break;
                }
                String retryQuery = finalQuery + " site:" + site;
                int added = runDetourSearchAttempt(retryQuery, metaHints, sessionIdLong, visionMode,
                        evidenceDocs, topK, perAttemptBudgetMs, maxToAdd - addedTotal);
                addedTotal += added;
            }
        }

        TraceStore.put("guard.detour.cheapRetry.addedDocs", Math.max(0, evidenceDocs.size() - before));
        TraceStore.put("rag.critic.retry.count", 1);
        boolean unattributedEvidenceAdded = evidenceDocs.size() > before;
        evidenceDocs = filterOfficialSourceEvidenceDocs(finalQuery, evidenceDocs);
        TraceStore.put("rag.critic.docs.after", evidenceDocs.size());
        boolean reachedMin = evidenceDocs.size() >= needAtLeast;
        if (reachedMin) {
            TraceStore.put("guard.detour.cheapRetry.recovered", true);
        }

        // [PATCH src111_merge15/merge15] If forceEscalate is active (entity/definitional + BELOW_MIN_CITATIONS),
        // attempt an evidence-grounded LLM regen even if we couldn't reach the citation minimum. This keeps
        // the response from collapsing into an evidence-list-only detour.
        if (reachedMin || (forceEscalate && evidenceDocs != null && !evidenceDocs.isEmpty())) {
            String regen = tryDetourCheapRetryLlmRegen(finalQuery, draftBeforeGuard, evidenceDocs,
                    model, llmReq, breakerKey, queryDomain, forceEscalate);
            if (regen != null && !regen.isBlank()) {
                TraceStore.put("guard.detour.cheapRetry.output", forceEscalate ? "llm_regen_forced" : "llm_regen");
                return new DetourRetryResult(regen, unattributedEvidenceAdded);
            }

            if (forceEscalate) {
                // ForceEscalate mode: if we can't regen, keep the current draft (allow citation-poor draft).
                TraceStore.put("guard.detour.cheapRetry.output", "keep_draft_forced");
                return new DetourRetryResult(null, unattributedEvidenceAdded);
            }

            TraceStore.put("guard.detour.cheapRetry.output", "evidence_only");
            return new DetourRetryResult(
                    composeEvidenceOnlyAnswer(evidenceDocs, finalQuery),
                    unattributedEvidenceAdded);
        }

        return new DetourRetryResult(null, unattributedEvidenceAdded);
    }

    private record DetourRetryResult(String content, boolean unattributedEvidenceAdded) {
        private static DetourRetryResult empty() {
            return new DetourRetryResult(null, false);
        }
    }

    private int runDetourSearchAttempt(
            String retryQuery,
            Map<String, Object> baseMetaHints,
            long sessionIdLong,
            VisionMode visionMode,
            List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            int webTopK,
            int webBudgetMs,
            int maxToAdd) {

        if (maxToAdd <= 0) {
            return 0;
        }

        Map<String, Object> md = new HashMap<>();
        if (baseMetaHints != null) {
            md.putAll(baseMetaHints);
        }
        md.put("useWeb", "true");
        md.put("webTopK", webTopK);
        md.put("webBudgetMs", webBudgetMs);
        md.putIfAbsent("siteFilter.minDocsToSkipSearch", Math.min(webTopK, Math.max(1, maxToAdd)));

        List<Content> docs;
        try {
            TraceStore.inc("guard.detour.cheapRetry.web.calls");
            docs = webSearchRetriever.retrieve(QueryUtils.buildQuery(retryQuery, sessionIdLong, null, md));
        } catch (Exception e) {
            TraceStore.put("guard.detour.cheapRetry.web.error", "cheap_retry_web_failed");
            return 0;
        }
        if (docs == null || docs.isEmpty()) {
            return 0;
        }

        Set<String> existingIds = evidenceDocs.stream()
                .map(EvidenceAwareGuard.EvidenceDoc::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int added = 0;
        for (Content d : docs) {
            if (added >= maxToAdd) {
                break;
            }
            if (d == null || d.textSegment() == null) {
                continue;
            }
            var meta = d.textSegment().metadata();
            String docId = (meta != null) ? meta.getString("docId") : null;
            String url = (meta != null) ? meta.getString("url") : null;
            String id = (docId != null && !docId.isBlank()) ? docId : url;
            if (id == null || id.isBlank()) {
                continue;
            }
            if (existingIds.contains(id)) {
                continue;
            }
            String title = (meta != null) ? meta.getString("title") : null;
            String snippet = d.textSegment().text();
            if (snippet != null && snippet.length() > 800) {
                snippet = snippet.substring(0, 800) + "...";
            }
            evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(id, title, snippet));
            existingIds.add(id);
            added++;
        }
        return added;
    }

    private List<String> chooseDetourRetrySites(String query, QueryDomain queryDomain) {
        final int maxSites = Math.max(1, this.detourCheapRetryMaxSites);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        String primary = chooseDetourRetrySite(query, queryDomain);
        if (primary != null && !primary.isBlank()) {
            out.add(normalizeSiteHint(primary));
        }

        String q = (query == null) ? "" : query;
        String qLower = q.toLowerCase(Locale.ROOT);
        boolean hasKorean = q.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);

        // Intent-specific high-signal sites
        boolean genshin = q.contains("?먯떊") || qLower.contains("genshin");
        if (genshin) {
            out.add("hoyolab.com");
            out.add("hoyoverse.com");
        }

        if (queryDomain == QueryDomain.GAME || queryDomain == QueryDomain.SUBCULTURE) {
            out.add("namu.wiki");
            out.add("wikipedia.org");
        } else if (queryDomain == QueryDomain.STUDY) {
            out.add("docs.oracle.com");
            out.add("developer.mozilla.org");
            out.add("docs.spring.io");
            out.add("github.com");
        } else if (queryDomain == QueryDomain.GENERAL) {
            out.add("wikipedia.org");
            out.add("terms.naver.com");
        }

        if (hasKorean) {
            out.add("terms.naver.com");
            out.add("namu.wiki");
        }
        out.add("wikipedia.org");

        // Configured site hints (lowest priority)
        for (String s : parseCsv(this.detourCheapRetrySiteHintsCsv)) {
            out.add(normalizeSiteHint(s));
        }

        return out.stream()
                .filter(s -> s != null && !s.isBlank())
                .limit(maxSites)
                .collect(Collectors.toList());
    }

    /**
     * Decide whether to combine multiple "site:" hints using a single
     * {@code (site:a OR site:b)} clause.
     *
     * <p>
     * We support explicit override via
     * {@code guard.detour.cheap-retry.combine-sites-with-or}.
     * When not explicitly configured, we choose an "auto" default based on provider
     * capability and
     * query language (Hangul tends to route through Naver where boolean OR behavior
     * is less reliable).
     */
    private boolean shouldDetourCheapRetryCombineSitesWithOr(String finalQuery, List<String> sites) {
        if (sites == null || sites.size() < 2) {
            return false;
        }

        // If the operator explicitly set the property, honor it.
        try {
            if (this.env != null && this.env.containsProperty("guard.detour.cheap-retry.combine-sites-with-or")) {
                TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.mode", "explicit");
                return this.detourCheapRetryCombineSitesWithOr;
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.combineSitesModeTrace", ignore);
        }

        final boolean hasHangul = finalQuery != null && finalQuery.matches(".*[\\uAC00-\\uD7A3].*");
        boolean providerSupportsOr = false;
        try {
            providerSupportsOr = this.webSearchProvider != null && this.webSearchProvider.supportsSiteOrSyntax();
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.providerSupportsOr", ignore); providerSupportsOr = false;
        }

        final boolean auto = !hasHangul && providerSupportsOr;
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.mode", "auto");
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.auto", auto);
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.hasHangul", hasHangul);
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.providerSupportsOr", providerSupportsOr);
        return auto;
    }

    private String buildSiteOrClause(List<String> sites) {
        List<String> cleaned = sites.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .map(s -> s.startsWith("site:") ? s.substring("site:".length()) : s)
                .map(s -> "site:" + s)
                .distinct()
                .collect(Collectors.toList());

        if (cleaned.isEmpty()) {
            return "";
        }
        if (cleaned.size() == 1) {
            return cleaned.get(0);
        }
        return "(" + String.join(" OR ", cleaned) + ")";
    }

    private static String normalizeSiteHint(String site) {
        if (site == null) {
            return "";
        }
        String s = site.trim();
        if (s.startsWith("site:")) {
            s = s.substring("site:".length());
        }
        s = s.replace("https://", "").replace("http://", "");
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String s = p.trim();
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private String tryDetourCheapRetryLlmRegen(
            String finalQuery,
            String draftBeforeGuard,
            List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            ChatModel model,
            ChatRequestDto llmReq,
            String breakerKey,
            QueryDomain queryDomain, boolean forceEscalate) {

        if (!this.detourCheapRetryRegenLlmEnabled) {
            if (!forceEscalate) {
                return null;
            }
            if (!this.detourForceEscalateRegenLlmEnabled) {
                TraceStore.put("guard.detour.cheapRetry.regen.skip", "forceEscalate_regen_disabled");
                return null;
            }
        }
        if (model == null || llmReq == null) {
            TraceStore.put("guard.detour.cheapRetry.regen.skip", "no_model");
            return null;
        }

        var gctx = GuardContextHolder.get();
        if (gctx != null && gctx.isStrikeMode()) {
            TraceStore.put("guard.detour.cheapRetry.regen.skip", "strike_mode");
            return null;
        }
        // Even when we forceEscalate over degradation, keep high-risk queries gated.
        if (forceEscalate && gctx != null && gctx.isHighRiskQuery()) {
            TraceStore.put("guard.detour.cheapRetry.regen.skip", "forceEscalate_but_high_risk_query");
            return null;
        }
        if (this.detourCheapRetryRegenLlmOnlyIfLowRisk && !forceEscalate) {
            if (queryDomain != null && !queryDomain.isLowRisk()) {
                TraceStore.put("guard.detour.cheapRetry.regen.skip", "non_low_risk_domain");
                return null;
            }
            if (gctx != null && gctx.isHighRiskQuery()) {
                TraceStore.put("guard.detour.cheapRetry.regen.skip", "high_risk_query");
                return null;
            }
        }

        String evidenceBlock = buildEvidenceListForPrompt(evidenceDocs, 8, 480);

        String system;
        String user;
        if (forceEscalate) {
            system = """
                    ??븷: ?뱀떊? '洹쇨굅 湲곕컲 ?듬? ?묒꽦湲??낅땲??
                    紐⑺몴: ?ъ슜??吏덈Ц?????'?듭떖 ?듬?'???묒꽦?섎릺, ?꾨옒 '洹쇨굅 紐⑸줉'???덈뒗 ?뺣낫留??ъ슜?섏꽭??

                    洹쒖튃:
                    - 洹쇨굅 紐⑸줉???녿뒗 ?덈줈???ъ떎/?섏튂/?좎쭨瑜?異붽??섏? 留덉꽭??
                    - 遺덊솗?ㅽ븯嫄곕굹 洹쇨굅媛 遺議깊븳 遺遺꾩? ??젣?섍굅??'洹쇨굅 遺議??쇰줈 ?쒖떆?섏꽭??
                    - 媛?臾몄옣/??ぉ ?앹뿉 洹쇨굅 踰덊샇瑜?[n] ?뺤떇?쇰줈 ?몃씪???몄슜?섏꽭?? (n? 洹쇨굅 紐⑸줉 踰덊샇)
                    - 異쒕젰? 媛꾧껐?섍쾶: (1) ?듭떖 ?듬? (2) 二쇱슂 ?ъ씤??遺덈┸)
                    - 理쒖쥌 ?듬?留?異쒕젰?섏꽭?? (?ㅻ챸/?ш낵/硫뷀? 肄붾찘??湲덉?)
                    """;

            user = """
                    ?ъ슜??吏덈Ц:
                    %s

                    李멸퀬 珥덉븞(?덈떎硫?:
                    %s

                    洹쇨굅 紐⑸줉:
                    %s

                    ?붿껌: ??洹쇨굅 紐⑸줉留??ъ슜??理쒖쥌 ?듬????묒꽦??二쇱꽭??
                    """.formatted(finalQuery, (draftBeforeGuard == null ? "" : draftBeforeGuard), evidenceBlock);
        } else {
            system = """
                    ??븷: ?뱀떊? '珥덉븞 ?몄쭛湲??낅땲??
                    紐⑺몴: ?꾨옒 '珥덉븞'??理쒕????좎??섎㈃?? ?쒓났??'洹쇨굅 紐⑸줉'留??ъ슜???ъ떎???뺤씤/?섏젙?섍퀬 ?몄슜???쎌엯?섏꽭??

                    洹쒖튃:
                    - 洹쇨굅 紐⑸줉???녿뒗 ?덈줈???ъ떎/?섏튂/?좎쭨瑜?異붽??섏? 留덉꽭??
                    - 遺덊솗?ㅽ븯嫄곕굹 洹쇨굅媛 遺議깊븳 遺遺꾩? ??젣?섍굅??'洹쇨굅 遺議??쇰줈 ?쒖떆?섏꽭??
                    - 媛?臾몄옣/??ぉ ?앹뿉 洹쇨굅 踰덊샇瑜?[n] ?뺤떇?쇰줈 ?몃씪???몄슜?섏꽭?? (n? 洹쇨굅 紐⑸줉 踰덊샇)
                    - 珥덉븞????臾몃떒/紐⑸줉 援ъ“瑜?媛?ν븳 ???좎??섏꽭??
                    - 理쒖쥌 ?듬?留?異쒕젰?섏꽭?? (?ㅻ챸/?ш낵/硫뷀? 肄붾찘??湲덉?)
                    """;

            user = """
                    ?ъ슜??吏덈Ц:
                    %s

                    珥덉븞:
                    %s

                    洹쇨굅 紐⑸줉:
                    %s

                    ?붿껌: ??珥덉븞??湲곕컲?쇰줈 理쒖쥌 ?듬????묒꽦??二쇱꽭??
                    """.formatted(finalQuery, (draftBeforeGuard == null ? "" : draftBeforeGuard), evidenceBlock);
        }

        List<ChatMessage> msgs = buildPostOrchestrationPromptMessages(
                "guard.detour.cheapRetry.regen", system, user);

        ChatRequestDto regenReq = llmReq.toBuilder()
                .temperature(this.detourCheapRetryRegenLlmTemperature)
                .maxTokens(this.detourCheapRetryRegenLlmMaxTokens)
                .build();

        NightmareBreaker.CallPermit detourPermit = null;
        if (nightmareBreaker != null) {
            try {
                detourPermit = nightmareBreaker.acquire(breakerKey, "guard-detour-regen");
            } catch (NightmareBreaker.OpenCircuitException e) {
                TraceStore.put("guard.detour.cheapRetry.regen.skip", "nightmare_open");
                return null;
            }
        }

        try {
            TraceStore.inc("guard.detour.cheapRetry.regen.calls");
            long st = System.currentTimeMillis();
            ChatModel regenModel = model;
            if (dynamicChatModelFactory != null) {
                regenModel = dynamicChatModelFactory.lc(
                        regenReq.getModel(),
                        this.detourCheapRetryRegenLlmTemperature,
                        null,
                        this.detourCheapRetryRegenLlmMaxTokens);
            }
            int regenTimeoutSeconds = RequestedModelTimeoutPolicy.timeoutSeconds(
                    regenReq.getModel(),
                    regenReq.getModel(),
                    llmTimeoutSeconds,
                    requestedModelTimeoutSeconds);
            Duration regenTimeout = requestBudgetBoundedLlmTimeout(
                    TimeUnit.SECONDS.toMillis(Math.max(1, regenTimeoutSeconds)),
                    "guard_detour_regen");
            ChatUsageLedger.ModelAttempt usageAttempt = beginChatUsageAttempt(
                    ChatUsageLedger.ModelPurpose.RETRY,
                    regenModel,
                    null,
                    regenReq.getMaxTokens(),
                    ChatUsageLedger.CapSource.ROUTER_MODEL);
            String out = TimedChatModelCaller.chat(
                    regenModel,
                    msgs,
                    regenTimeout,
                    "guard_detour_regen",
                    regenReq.getModel(),
                    usageAttempt).text();
            long latencyMs = System.currentTimeMillis() - st;
            TraceStore.put("guard.detour.cheapRetry.regen.ms", latencyMs);
            if (out == null || out.isBlank()) {
                if (detourPermit != null) {
                    detourPermit.completeBlank("guard-detour-regen");
                }
                return null;
            }
            if (detourPermit != null) {
                detourPermit.completeSuccess(latencyMs);
            }
            return out.trim();
        } catch (Exception e) {
            TraceStore.put("guard.detour.cheapRetry.regen.error", "cheap_retry_regen_failed");
            if (detourPermit != null) {
                NightmareBreaker.FailureKind kind = NightmareBreaker.classify(e);
                LlmFastBailoutException fastBail = unwrapFastBail(e);
                String reason = fastBail == null ? ""
                        : String.valueOf(fastBail.getMessage()).toLowerCase(java.util.Locale.ROOT);
                if (reason.contains("request budget")) {
                    detourPermit.completeAbandoned("guard-detour-regen", "request-budget");
                } else if (kind == NightmareBreaker.FailureKind.INTERRUPTED) {
                    detourPermit.completeCancelled(e, "guard-detour-regen");
                } else {
                    detourPermit.completeFailure(kind, e, "guard-detour-regen");
                }
            }
            return null;
        }
    }

    private String buildEvidenceListForPrompt(List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs, int maxDocs,
            int maxSnippetChars) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (EvidenceAwareGuard.EvidenceDoc ev : evidenceDocs) {
            if (ev == null) {
                continue;
            }
            if (i > maxDocs) {
                break;
            }
            String id = ev.id() == null ? "" : ev.id().trim();
            String title = ev.title() == null ? "" : ev.title().trim();
            String snippet = ev.snippet() == null ? "" : ev.snippet().trim();
            if (snippet.length() > maxSnippetChars) {
                snippet = snippet.substring(0, maxSnippetChars) + "...";
            }

            sb.append('[').append(i).append("] ");
            sb.append(title.isBlank() ? "(no title)" : title);
            if (!id.isBlank()) {
                sb.append(" ??").append(id);
            }
            if (!snippet.isBlank()) {
                sb.append("\n    ").append(snippet);
            }
            sb.append("\n");
            i++;
        }
        return sb.toString();
    }

    private String chooseDetourRetrySite(String query, QueryDomain queryDomain) {
        java.util.List<String> sites = new java.util.ArrayList<>();

        // (1) 寃뚯엫 ?꾨찓?몄씠硫?officialDomains瑜??곗꽑 ?꾨낫濡?
        if (queryDomain == QueryDomain.GAME && officialDomainsCsv != null && !officialDomainsCsv.isBlank()) {
            for (String s : officialDomainsCsv.split(",")) {
                if (s == null) {
                    continue;
                }
                String t = s.trim();
                if (t.isBlank()) {
                    continue;
                }
                // site:???꾨찓?멸퉴吏留??덉슜 (path???쒓굅)
                int slash = t.indexOf('/');
                if (slash > 0) {
                    t = t.substring(0, slash);
                }
                // ?덈Т ?쇰컲?곸씤 ?꾨찓???뚯뀥? ?쒖쇅 (?꾩슂 ??config濡?異붽?)
                if (t.startsWith("youtube.") || t.equals("youtube.com") || t.equals("x.com")
                        || t.equals("twitter.com")) {
                    continue;
                }
                if (t.contains(".")) {
                    sites.add(t);
                }
            }
        }

        // (2) 湲곕낯 ?꾨낫(?ㅼ젙媛?
        for (String s : detourCheapRetrySiteHintsCsv.split(",")) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (!t.isBlank()) {
                // site: prefix媛 ?ㅼ뼱?ㅻ㈃ ?쒓굅
                if (t.startsWith("site:")) {
                    t = t.substring("site:".length());
                }
                sites.add(t);
            }
        }

        // 湲곕낯媛?蹂닿컯 (config媛 鍮꾩뿀嫄곕굹 ?ㅽ깉?먯씪 ??
        if (sites.isEmpty()) {
            sites.add("wikipedia.org");
            sites.add("namu.wiki");
            sites.add("hoyolab.com");
        }

        String q = (query == null ? "" : query);
        boolean hasHangul = q.matches(".*[\uAC00-\uD7A3].*");
        boolean genshin = q.contains("?먯떊") || q.toLowerCase().contains("genshin");

        // ?곗꽑?쒖쐞: (?먯떊/寃뚯엫) -> (?쒓?) -> (?곷Ц)
        if (genshin) {
            for (String s : sites) {
                if (s.contains("hoyolab") || s.contains("hoyoverse")) {
                    return s;
                }
            }
        }

        if (hasHangul) {
            for (String s : sites) {
                if (s.contains("namu.wiki")) {
                    return s;
                }
            }
        }

        for (String s : sites) {
            if (s.contains("wikipedia.org")) {
                return s;
            }
        }

        // fallback: 泥??꾨낫
        return sites.get(0);
    }

    /**
     * ?몄뀡 ID(Object) ??Long 蹂?? "123" ?뺥깭留?Long, 洹몄쇅??null.
     */

    /**
     * Extract URL from document metadata for EvidenceAwareGuard domain detection.
     * Falls back to index-based ID if no URL/source found.
     *
     * @param doc    RAG/web content document (may contain metadata such as "url" or
     *               "source")
     * @param index  fallback numeric index when metadata is missing
     * @param vector true if this is a vector/RAG document (uses "vector:" prefix)
     * @return actual URL if available, otherwise fallback index-based string
     */
    private static final Pattern URL_IN_TEXT = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    // Answer-side evidence marker: [W1], [V2], [D3]
    private static final Pattern EVIDENCE_MARKER_PATTERN = Pattern.compile("\\[(W|V|D)(\\d+)\\]");
    private static final Pattern EXACT_FIELD_TYPE_PATTERN = Pattern.compile(
            "(?iu)(?:\"type\"\\s*:\\s*\"([a-z0-9_.-]+)\"|\\btype\\s*[:=]\\s*`?\"?([a-z0-9_.-]+))");
    private static final Pattern EXACT_FIELD_MODEL_PATTERN = Pattern.compile(
            "(?iu)(?:\"model\"\\s*:\\s*\"([a-z0-9_.:-]+)\"|\\bmodel\\s*[:=]\\s*`?\"?([a-z0-9_.:-]+))");

    /**
     * Needle 2-pass merge: extract a canonical URL if possible; otherwise return
     * null.
     *
     * <p>
     * Unlike extractUrlOrFallback(...), this helper never returns an index-based
     * fallback
     * because we use it for URL-based de-duplication.
     */
    private static String needleExtractUrlOrNull(dev.langchain4j.rag.content.Content doc) {
        if (doc == null) {
            return null;
        }
        try {
            var segment = doc.textSegment();
            if (segment == null) {
                return null;
            }
            try {
                var metadata = segment.metadata();
                if (metadata != null) {
                    String url = metadata.getString("url");
                    if (url == null || url.isBlank()) {
                        url = metadata.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(url);
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.sourceUrlMetadata", ignore); }

            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    String href = HtmlTextUtil.extractFirstHref(text);
                    if (href != null && !href.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(href);
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.sourceUrlHref", ignore); }

            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    java.util.regex.Matcher m = URL_IN_TEXT.matcher(text);
                    if (m.find()) {
                        return HtmlTextUtil.normalizeUrl(m.group(1));
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.sourceUrlRegex", ignore); }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.sourceUrlOuter", ignore); }
        return null;
    }

    private static java.util.List<String> safeHashList(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String value : values) {
            String hash = SafeRedactor.hash12(value);
            if (hash != null && !hash.isBlank()) {
                out.add(hash);
            }
            if (out.size() >= 16) {
                break;
            }
        }
        return java.util.List.copyOf(out);
    }

    private static void addPrefetchDiagnostics(Map<String, Object> metaHints,
                                               String query,
                                               java.util.List<String> snippets) {
        if (metaHints == null) {
            return;
        }
        metaHints.put("prefetch.web.queryHash", SafeRedactor.hashValue(query));
        metaHints.put("prefetch.web.queryLength", query == null ? 0 : query.length());
        metaHints.put("prefetch.web.queryTokenBucket", queryTokenBucket(query));
        metaHints.put("prefetch.web.snippetCount", snippets == null ? 0 : snippets.size());
        metaHints.put("prefetch.web.snippetHash12", safeHashList(snippets));
    }

    private static List<dev.langchain4j.rag.content.Content> prefetchedWebContents(
            Map<String, Object> metaHints,
            int limit) {
        if (metaHints == null || limit <= 0) {
            return java.util.List.of();
        }
        Object raw = metaHints.get("prefetch.web.snippets");
        if (!(raw instanceof java.util.List<?> values) || values.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<dev.langchain4j.rag.content.Content> out = new java.util.ArrayList<>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String snippet = String.valueOf(value).trim();
            if (snippet.isBlank()) {
                continue;
            }
            String sourceUrl = prefetchedWebSourceUrl(snippet);
            if (sourceUrl != null && !sourceUrl.isBlank()) {
                java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
                metadata.put("url", sourceUrl);
                metadata.put("source", sourceUrl);
                metadata.put("retrieval_lane", "prefetch.web");
                metadata.put("prefetch.web.source", "controller_prefetch");
                out.add(dev.langchain4j.rag.content.Content.from(
                        dev.langchain4j.data.segment.TextSegment.from(
                                snippet,
                                dev.langchain4j.data.document.Metadata.from(metadata))));
            } else {
                out.add(dev.langchain4j.rag.content.Content.from(snippet));
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return java.util.List.copyOf(out);
    }

    private static String prefetchedWebSourceUrl(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return null;
        }
        try {
            String href = HtmlTextUtil.extractFirstHref(snippet);
            if (href != null && !href.isBlank()) {
                return HtmlTextUtil.normalizeUrl(href);
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prefetch.web.hrefUrl", ignore);
        }
        try {
            java.util.regex.Matcher matcher = URL_IN_TEXT.matcher(snippet);
            if (matcher.find()) {
                return HtmlTextUtil.normalizeUrl(matcher.group(1));
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prefetch.web.regexUrl", ignore);
        }
        return null;
    }

    private static String queryTokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) {
            return "1-4";
        }
        if (tokens <= 12) {
            return "5-12";
        }
        if (tokens <= 32) {
            return "13-32";
        }
        return "33+";
    }

    /**
     * Needle 2-pass merge: combine (topDocs + needleDocs + fused) and deduplicate
     * by URL
     * (preferred) or short normalized text key. Order matters because we cap the
     * rerank candidates.
     */
    private static java.util.List<dev.langchain4j.rag.content.Content> mergeNeedleCandidates(
            java.util.List<dev.langchain4j.rag.content.Content> topDocs,
            java.util.List<dev.langchain4j.rag.content.Content> needleDocs,
            java.util.List<dev.langchain4j.rag.content.Content> fused,
            int maxPool) {

        java.util.LinkedHashMap<String, dev.langchain4j.rag.content.Content> uniq = new java.util.LinkedHashMap<>();
        int cap = maxPool > 0 ? maxPool : Integer.MAX_VALUE;
        int needleCount = needleDocs == null ? 0 : needleDocs.size();
        int reserve = needleCount <= 0 ? 0
                : Math.min(needleCount, Math.max(1, cap == Integer.MAX_VALUE ? needleCount : cap / 4));

        needleAddSome(uniq, topDocs, 1, cap);
        needleAddSome(uniq, needleDocs, reserve, cap);
        needleAddAll(uniq, topDocs, cap);
        needleAddAll(uniq, fused, cap);
        needleAddAll(uniq, needleDocs, cap);

        java.util.ArrayList<dev.langchain4j.rag.content.Content> out = new java.util.ArrayList<>(uniq.values());
        if (maxPool > 0 && out.size() > maxPool) {
            return out.subList(0, maxPool);
        }
        return out;
    }

    private static void needleAddAll(
            java.util.LinkedHashMap<String, dev.langchain4j.rag.content.Content> uniq,
            java.util.List<dev.langchain4j.rag.content.Content> list,
            int maxPool) {
        needleAddSome(uniq, list, Integer.MAX_VALUE, maxPool);
    }

    private static void needleAddSome(
            java.util.LinkedHashMap<String, dev.langchain4j.rag.content.Content> uniq,
            java.util.List<dev.langchain4j.rag.content.Content> list,
            int sourceLimit,
            int maxPool) {
        if (uniq == null || list == null || list.isEmpty()) {
            return;
        }
        if (sourceLimit == 0) {
            return;
        }
        int added = 0;
        for (dev.langchain4j.rag.content.Content c : list) {
            if (c == null || c.textSegment() == null || c.textSegment().text() == null) {
                continue;
            }
            String text = c.textSegment().text();
            if (text.isBlank()) {
                continue;
            }
            String url = needleExtractUrlOrNull(c);
            String key;
            if (url != null && !url.isBlank()) {
                key = "url:" + url;
            } else {
                String t = text.strip();
                if (t.length() > 160) {
                    t = t.substring(0, 160);
                }
                key = "txt:" + t;
            }
            if (!uniq.containsKey(key)) {
                uniq.put(key, c);
                added++;
            }
            if (sourceLimit > 0 && added >= sourceLimit) {
                return;
            }
            if (maxPool > 0 && uniq.size() >= maxPool) {
                return;
            }
        }
    }

    private static double retainedDropRatio(
            java.util.List<dev.langchain4j.rag.content.Content> before,
            java.util.List<dev.langchain4j.rag.content.Content> after) {
        if (before == null || before.isEmpty()) {
            return 0.0d;
        }
        if (after == null || after.isEmpty()) {
            return 1.0d;
        }
        java.util.HashSet<String> afterKeys = new java.util.HashSet<>();
        for (int i = 0; i < after.size(); i++) {
            String key = stableNeedleKey(after.get(i), i);
            if (key != null && !key.isBlank()) {
                afterKeys.add(key);
            }
        }
        int retained = 0;
        for (int i = 0; i < before.size(); i++) {
            String key = stableNeedleKey(before.get(i), i);
            if (key != null && afterKeys.contains(key)) {
                retained++;
            }
        }
        double ratio = 1.0d - (retained / (double) Math.max(1, before.size()));
        return Math.max(0.0d, Math.min(1.0d, ratio));
    }

    private static String stableNeedleKey(dev.langchain4j.rag.content.Content doc, int index) {
        String url = needleExtractUrlOrNull(doc);
        if (url != null && !url.isBlank()) {
            return "url:" + url;
        }
        try {
            String text = (doc != null && doc.textSegment() != null && doc.textSegment().text() != null)
                    ? doc.textSegment().text()
                    : "";
            text = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (text.length() > 220) {
                text = text.substring(0, 220);
            }
            return text.isBlank() ? "idx:" + index : "txt:" + text;
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidence.keyForContent", ignore); return "idx:" + index;
        }
    }

    private static String extractUrlOrFallback(dev.langchain4j.rag.content.Content doc, int index, boolean vector) {
        String fallback = vector ? "vector:" + index : String.valueOf(index);
        if (doc == null) {
            return fallback;
        }
        try {
            var segment = doc.textSegment();
            if (segment == null) {
                return fallback;
            }
            try {
                var metadata = segment.metadata();
                if (metadata != null) {
                    // LangChain4j 1.0.1: Metadata#get(...) is not available ??use getString(...)
                    String url = metadata.getString("url");
                    if (url == null || url.isBlank()) {
                        url = metadata.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(url);
                    }
                }
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("evidence.urlMetadata", ignore);
            }

            // 1.5) If body contains an HTML anchor ("- <a href=...>..."), prefer href.
            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    String href = HtmlTextUtil.extractFirstHref(text);
                    if (href != null && !href.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(href);
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("evidence.displayHref", ignore); }

            // 2) Fallback: parse URL directly from the text body/header
            // (e.g., "[title | provider | https://... ]")
            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    java.util.regex.Matcher m = URL_IN_TEXT.matcher(text);
                    if (m.find()) {
                        return HtmlTextUtil.normalizeUrl(m.group(1));
                    }
                }
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("evidence.urlText", ignore);
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidence.urlOuter", ignore);
        }
        return fallback;
    }

    private static java.util.Locale guessLocaleForNeedle(String text) {
        if (text == null || text.isBlank()) {
            return java.util.Locale.ROOT;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\uAC00' && ch <= '\uD7A3') {
                return java.util.Locale.KOREAN;
            }
        }
        return java.util.Locale.ENGLISH;
    }

    private static String extractHttpUrlOrNull(dev.langchain4j.rag.content.Content doc) {
        if (doc == null) {
            return null;
        }
        try {
            var seg = doc.textSegment();
            if (seg == null) {
                return null;
            }
            String raw = null;
            try {
                var md = seg.metadata();
                if (md != null) {
                    raw = md.getString("url");
                    if (raw == null || raw.isBlank()) {
                        raw = md.getString("source");
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("extractHttpUrl.metadata", ignore); }
            if (raw == null || raw.isBlank()) {
                raw = seg.text();
            }
            if (raw == null || raw.isBlank()) {
                return null;
            }
            java.util.regex.Matcher m = URL_IN_TEXT.matcher(raw);
            if (m.find()) {
                return HtmlTextUtil.normalizeUrl(m.group(1));
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("extractHttpUrl.outer", ignore); }
        return null;
    }

    private static java.util.Set<String> collectNormalizedUrls(
            java.util.List<dev.langchain4j.rag.content.Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (dev.langchain4j.rag.content.Content c : docs) {
            String u = extractHttpUrlOrNull(c);
            if (u != null && !u.isBlank()) {
                out.add(u);
            }
        }
        return out;
    }

    private static java.util.List<dev.langchain4j.rag.content.Content> mergeDedupeByUrlThenText(
            java.util.List<dev.langchain4j.rag.content.Content> base,
            java.util.List<dev.langchain4j.rag.content.Content> extra,
            int cap) {

        java.util.LinkedHashMap<String, dev.langchain4j.rag.content.Content> merged = new java.util.LinkedHashMap<>();
        java.util.List<dev.langchain4j.rag.content.Content> first = (base == null ? java.util.List.of() : base);
        java.util.List<dev.langchain4j.rag.content.Content> second = (extra == null ? java.util.List.of() : extra);

        java.util.function.Function<dev.langchain4j.rag.content.Content, String> keyFn = c -> {
            String url = extractHttpUrlOrNull(c);
            if (url != null && !url.isBlank()) {
                return "u:" + url;
            }
            try {
                String t = (c != null && c.textSegment() != null && c.textSegment().text() != null)
                        ? c.textSegment().text()
                        : "";
                t = t.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
                if (t.length() > 220) {
                    t = t.substring(0, 220);
                }
                return "t:" + t;
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("content.dedupeKey", ignore); return "t:";
            }
        };

        for (dev.langchain4j.rag.content.Content c : first) {
            merged.putIfAbsent(keyFn.apply(c), c);
            if (cap > 0 && merged.size() >= cap) {
                break;
            }
        }
        if (cap <= 0 || merged.size() < cap) {
            for (dev.langchain4j.rag.content.Content c : second) {
                merged.putIfAbsent(keyFn.apply(c), c);
                if (cap > 0 && merged.size() >= cap) {
                    break;
                }
            }
        }
        return new java.util.ArrayList<>(merged.values());
    }

    private static int countDocsWithAnyUrlInSet(
            java.util.List<dev.langchain4j.rag.content.Content> docs,
            java.util.Set<String> urlSet) {
        if (docs == null || docs.isEmpty() || urlSet == null || urlSet.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (dev.langchain4j.rag.content.Content c : docs) {
            String u = extractHttpUrlOrNull(c);
            if (u != null && urlSet.contains(u)) {
                count++;
            }
        }
        return count;
    }

    /**
     * ?듬? ?띿뒪?몄뿉 ?ы븿??[W1]/[V2]/[D3] ?몄슜 留덉빱瑜??ㅼ젣 URL/?쒕ぉ 紐⑸줉?쇰줈 "李멸퀬 ?먮즺" ?뱀뀡??遺숈씤??
     *
     * <ul>
     * <li>留덉빱媛 ?대? ?덉?留?"李멸퀬 ?먮즺" 紐⑸줉???녿뒗 寃쎌슦(=?ㅼ??ㅽ듃?덉씠???꾨씫)瑜?蹂댁셿</li>
     * <li>留덉빱媛 ?녿뜑?쇰룄 evidence媛 ?덉쑝硫??곸쐞 1~3媛쒕? ?몄텧(怨쇰룄??湲몄씠 諛⑹?)</li>
     * <li>UI媛 留덉빱瑜?蹂꾨룄濡??뚮뜑留곹븯?붾씪???щ엺??吏곸젒 ?뺤씤 媛?ν븳 理쒖냼 異쒖쿂 留듯븨???쒓났</li>
     * </ul>
     */
    private static String appendEvidenceReferencesIfNeeded(
            String answer,
            java.util.List<dev.langchain4j.rag.content.Content> webDocs,
            java.util.List<dev.langchain4j.rag.content.Content> vectorDocs) {
        return appendEvidenceReferencesIfNeeded(answer, webDocs, vectorDocs, null);
    }

    static java.util.List<dev.langchain4j.rag.content.Content> filterSuspectPromptContents(
            java.util.List<dev.langchain4j.rag.content.Content> docs,
            java.util.Set<String> suspectEvidenceIds,
            boolean vector) {
        if (docs == null || docs.isEmpty() || suspectEvidenceIds == null || suspectEvidenceIds.isEmpty()) {
            return docs;
        }
        java.util.ArrayList<dev.langchain4j.rag.content.Content> clean = new java.util.ArrayList<>(docs.size());
        int index = 1;
        for (dev.langchain4j.rag.content.Content doc : docs) {
            String evidenceId = extractUrlOrFallback(doc, index, vector);
            if (!suspectEvidenceIds.contains(evidenceId)) {
                clean.add(doc);
            }
            index++;
        }
        return clean.size() == docs.size() ? docs : java.util.List.copyOf(clean);
    }

    private static java.util.List<dev.langchain4j.data.document.Document> filterSuspectPromptDocuments(
            java.util.List<dev.langchain4j.data.document.Document> docs,
            java.util.Set<String> suspectEvidenceIds) {
        if (docs == null || docs.isEmpty() || suspectEvidenceIds == null || suspectEvidenceIds.isEmpty()) {
            return docs;
        }
        java.util.ArrayList<dev.langchain4j.data.document.Document> clean = new java.util.ArrayList<>(docs.size());
        int index = 1;
        for (dev.langchain4j.data.document.Document doc : docs) {
            if (!suspectEvidenceIds.contains(localDocumentEvidenceId(doc, index))) {
                clean.add(doc);
            }
            index++;
        }
        return clean.size() == docs.size() ? docs : java.util.List.copyOf(clean);
    }

    private static String localDocumentEvidenceId(
            dev.langchain4j.data.document.Document document,
            int index) {
        try {
            if (document != null && document.metadata() != null) {
                String attachmentId = document.metadata().getString("attachmentId");
                if (attachmentId != null && !attachmentId.isBlank()) {
                    return attachmentId.trim();
                }
            }
        } catch (RuntimeException metadataFailure) {
            ChatWorkflowTraceSuppressions.traceSuppressed("interactionPolicy.localEvidenceId", metadataFailure);
        }
        return "local:" + Math.max(1, index);
    }

    private static String localDocumentTitle(dev.langchain4j.data.document.Document document) {
        try {
            if (document != null && document.metadata() != null) {
                String name = document.metadata().getString("name");
                if (name != null && !name.isBlank()) {
                    return SafeRedactor.safeMessage(name, 240);
                }
            }
        } catch (RuntimeException metadataFailure) {
            ChatWorkflowTraceSuppressions.traceSuppressed("interactionPolicy.localEvidenceTitle", metadataFailure);
        }
        return "Local attachment";
    }

    private static String localDocumentSnippet(dev.langchain4j.data.document.Document document) {
        try {
            return document == null ? "" : SafeRedactor.safeMessage(document.text(), 1200);
        } catch (RuntimeException documentFailure) {
            ChatWorkflowTraceSuppressions.traceSuppressed("interactionPolicy.localEvidenceSnippet", documentFailure);
            return "";
        }
    }

    private static String appendEvidenceReferencesIfNeeded(
            String answer,
            java.util.List<dev.langchain4j.rag.content.Content> webDocs,
            java.util.List<dev.langchain4j.rag.content.Content> vectorDocs,
            String referenceQuery) {

        if (answer == null || answer.isBlank()) {
            return answer;
        }

        String trimmed = answer.trim();
        if (trimmed.isEmpty()) {
            return answer;
        }

        // Avoid double-append (idempotent)
        if (RagEvidenceAttributionService.hasFinalEvidenceAppendixHeading(trimmed)) {
            return answer;
        }

        java.util.List<dev.langchain4j.rag.content.Content> referenceWebDocs =
                webDocs == null ? java.util.List.of() : webDocs;
        java.util.List<dev.langchain4j.rag.content.Content> referenceVectorDocs =
                vectorDocs == null ? java.util.List.of() : vectorDocs;
        if (strictOfficialSourcePrompt(referenceQuery)) {
            referenceWebDocs = filterOfficialSourcePromptWebDocs(referenceQuery, referenceWebDocs);
            referenceVectorDocs = filterOfficialSourcePromptWebDocs(referenceQuery, referenceVectorDocs);
            try {
                TraceStore.put("rag.evidence.references.officialFilterApplied", true);
                TraceStore.put("rag.evidence.references.webAfterOfficialFilter", referenceWebDocs.size());
                TraceStore.put("rag.evidence.references.vectorAfterOfficialFilter", referenceVectorDocs.size());
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("appendEvidenceReferences.officialFilterTrace", ignore);
            }
        }

        boolean hasWeb = !referenceWebDocs.isEmpty();
        boolean hasVec = !referenceVectorDocs.isEmpty();
        if (!hasWeb && !hasVec) {
            return answer;
        }

        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = EVIDENCE_MARKER_PATTERN.matcher(trimmed);
        while (m.find()) {
            used.add(m.group(1) + m.group(2));
        }

        int maxLines = used.isEmpty() ? 3 : 12;
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.LinkedHashSet<>();

        // Web sources
        if (hasWeb) {
            for (int i = 0; i < referenceWebDocs.size() && lines.size() < maxLines; i++) {
                int idx = i + 1;
                String marker = "W" + idx;
                if (!used.isEmpty() && !used.contains(marker)) {
                    continue;
                }

                dev.langchain4j.rag.content.Content d = referenceWebDocs.get(i);
                String url = sanitizeEvidenceReferenceUrl(extractUrlOrFallback(d, idx, false));
                if (url == null || url.isBlank() || !seenUrls.add(url)) {
                    continue;
                }

                String title = safeTitle(d);
                if (title != null) {
                    title = title.replaceAll("\\s+", " ").trim();
                }

                lines.add((title == null || title.isBlank())
                        ? ("- [" + marker + "] " + url)
                        : ("- [" + marker + "] " + title + " - " + url));
            }
        }

        // Vector sources
        if (hasVec && lines.size() < maxLines) {
            for (int i = 0; i < referenceVectorDocs.size() && lines.size() < maxLines; i++) {
                int idx = i + 1;
                String marker = "V" + idx;
                if (!used.isEmpty() && !used.contains(marker)) {
                    continue;
                }

                dev.langchain4j.rag.content.Content d = referenceVectorDocs.get(i);
                String url = sanitizeEvidenceReferenceUrl(extractUrlOrFallback(d, idx, true));
                if (url == null || url.isBlank() || !seenUrls.add(url)) {
                    continue;
                }

                String title = safeTitle(d);
                if (title != null) {
                    title = title.replaceAll("\\s+", " ").trim();
                }

                lines.add((title == null || title.isBlank())
                        ? ("- [" + marker + "] " + url)
                        : ("- [" + marker + "] " + title + " - " + url));
            }
        }

        // If markers exist but nothing matched (index mismatch etc.), show the top
        // source as a fail-soft reference.
        if (lines.isEmpty()) {
            if (hasWeb) {
                dev.langchain4j.rag.content.Content d = referenceWebDocs.get(0);
                String url = sanitizeEvidenceReferenceUrl(extractUrlOrFallback(d, 1, false));
                if (url != null && !url.isBlank()) {
                    String title = safeTitle(d);
                    if (title != null) {
                        title = title.replaceAll("\\s+", " ").trim();
                    }
                    lines.add((title == null || title.isBlank())
                            ? ("- [W1] " + url)
                            : ("- [W1] " + title + " - " + url));
                }
            } else if (hasVec) {
                dev.langchain4j.rag.content.Content d = referenceVectorDocs.get(0);
                String url = sanitizeEvidenceReferenceUrl(extractUrlOrFallback(d, 1, true));
                if (url != null && !url.isBlank()) {
                    String title = safeTitle(d);
                    if (title != null) {
                        title = title.replaceAll("\\s+", " ").trim();
                    }
                    lines.add((title == null || title.isBlank())
                            ? ("- [V1] " + url)
                            : ("- [V1] " + title + " - " + url));
                }
            }
        }

        if (lines.isEmpty()) {
            return answer;
        }

        StringBuilder sb = new StringBuilder(trimmed);
        sb.append("\n\n---\n### Sources\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static String sanitizeEvidenceReferenceUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return raw;
            }
            URI clean = new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    host,
                    uri.getPort(),
                    uri.getRawPath(),
                    null,
                    null);
            return clean.toString();
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidence.referenceUrlSanitize", ignore);
            return raw;
        }
    }

    private static Long parseNumericSessionId(Object raw) {
        if (raw == null)
            return null;
        String s = String.valueOf(raw).trim();
        return s.matches("\\d+") ? Long.valueOf(s) : null;
    }

    // ------------------------------------------------------------------------

    private static String buildOutputPolicy(VerbosityProfile vp, List<String> sections) {
        // Output policies are now derived by the PromptOrchestrator. Returning an empty
        // string here delegates all guidance to the orchestrator and avoids manual
        // concatenation of policy instructions.
        return "";
    }

    // (??젣) loadMemoryContext(/* ... */) - MemoryHandler濡??쇱썝??

    /* ????????????????????????? BACKWARD-COMPAT ????????????????????????? */

    /**
     * (?명솚?? ?몃? 而⑦뀓?ㅽ듃 ?놁씠 ?ъ슜?섎뜕 湲곗〈 ?쒓렇?덉쿂
     */

    /* ---------- ?몄쓽 one-shot ---------- */
    public ChatResult ask(String userMsg) {
        // Internal convenience entry-point: do not force the DTO's default model.
        // Let routing/config pick the best available default.
        return continueChat(ChatRequestDto.builder()
                .message(userMsg)
                .model("")
                .build());
    }

    // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_CLAMP_IMPL
    private void applyStagePolicyClamp(OrchestrationSignals sig,
            OrchestrationHints hints,
            java.util.Map<String, Object> metaHints,
            boolean nightmareMode,
            boolean auxHardDown) {
        if (stagePolicy == null || !stagePolicy.isEnabled() || sig == null || hints == null) {
            return;
        }

        String mode = sig.modeLabel();

        // Retrieval toggles
        hints.setAllowWeb(stagePolicy.isStageEnabled(OrchStageKeys.RETRIEVAL_WEB, mode, hints.isAllowWeb()));
        hints.setAllowRag(stagePolicy.isStageEnabled(OrchStageKeys.RETRIEVAL_VECTOR, mode, hints.isAllowRag()));

        // Hard gates remain hard: do NOT re-enable if nightmare/auxHardDown.
        boolean safe = !nightmareMode && !auxHardDown;
        hints.setEnableSelfAsk(
                safe && stagePolicy.isStageEnabled(OrchStageKeys.RETRIEVAL_SELF_ASK, mode, hints.isEnableSelfAsk()));
        hints.setEnableAnalyze(
                safe && stagePolicy.isStageEnabled(OrchStageKeys.RETRIEVAL_ANALYZE, mode, hints.isEnableAnalyze()));
        hints.setEnableCrossEncoder(safe
                && stagePolicy.isStageEnabled(OrchStageKeys.RERANK_CROSS_ENCODER, mode, hints.isEnableCrossEncoder()));

        if (metaHints != null) {
            metaHints.put("stagePolicy.enabled", "true");
            metaHints.put("stagePolicy.mode", mode);
            metaHints.put("allowWeb", String.valueOf(hints.isAllowWeb()));
            metaHints.put("allowRag", String.valueOf(hints.isAllowRag()));
            metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
            metaHints.put("enableAnalyze", String.valueOf(hints.isEnableAnalyze()));
            metaHints.put("enableCrossEncoder", String.valueOf(hints.isEnableCrossEncoder()));
        }
    }

    // 寃利??щ? 寃곗젙 ?ы띁
    // MERGE_HOOK:PROJ_AGENT::ORCH_VERIFY_STAGE_POLICY
    private boolean effectiveVerificationEnabled(Boolean flag) {
        return flag == null ? verificationEnabled : Boolean.TRUE.equals(flag);
    }

    static String buildVerifierEvidenceContext(
            java.util.List<dev.langchain4j.rag.content.Content> webDocs,
            java.util.List<dev.langchain4j.rag.content.Content> vectorDocs,
            java.util.List<dev.langchain4j.data.document.Document> localDocs) {
        StringBuilder evidence = new StringBuilder();
        appendVerifierContentEvidence(evidence, "WEB", webDocs);
        appendVerifierContentEvidence(evidence, "VECTOR", vectorDocs);
        appendVerifierDocumentEvidence(evidence, "LOCAL", localDocs);
        return evidence.toString().trim();
    }

    static String verifierEligibilityContext(
            String retrievedEvidenceContext,
            String memoryContext,
            boolean followUp) {
        if (org.springframework.util.StringUtils.hasText(retrievedEvidenceContext)) {
            return retrievedEvidenceContext;
        }
        return followUp && org.springframework.util.StringUtils.hasText(memoryContext)
                ? memoryContext
                : "";
    }

    private static void appendVerifierContentEvidence(
            StringBuilder evidence,
            String source,
            java.util.List<dev.langchain4j.rag.content.Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        int count = 0;
        for (dev.langchain4j.rag.content.Content doc : docs) {
            if (doc == null || doc.textSegment() == null) {
                continue;
            }
            appendVerifierEvidenceText(evidence, source, doc.textSegment().text());
            if (++count >= 8 || evidence.length() >= 8_000) {
                return;
            }
        }
    }

    private static void appendVerifierDocumentEvidence(
            StringBuilder evidence,
            String source,
            java.util.List<dev.langchain4j.data.document.Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        int count = 0;
        for (dev.langchain4j.data.document.Document doc : docs) {
            if (doc == null) {
                continue;
            }
            appendVerifierEvidenceText(evidence, source, doc.text());
            if (++count >= 8 || evidence.length() >= 8_000) {
                return;
            }
        }
    }

    private static void appendVerifierEvidenceText(StringBuilder evidence, String source, String rawText) {
        if (rawText == null || rawText.isBlank() || evidence.length() >= 8_000) {
            return;
        }
        String text = rawText.strip();
        if (text.length() > 1_200) {
            text = text.substring(0, 1_200);
        }
        String row = "[" + source + "]\n" + text + "\n";
        int remaining = 8_000 - evidence.length();
        evidence.append(row, 0, Math.min(row.length(), remaining));
    }

    private boolean shouldVerify(String joinedContext, com.example.lms.dto.ChatRequestDto req,
            OrchestrationSignals sig) {
        boolean hasContext = org.springframework.util.StringUtils.hasText(joinedContext);
        Boolean flag = (req != null ? req.getUseVerification() : null); // null 媛??
        boolean enabled = effectiveVerificationEnabled(flag);
        if (!hasContext || !enabled) {
            return false;
        }

        // Skip in STRIKE/BYPASS to avoid extra expensive calls.
        if (sig != null && (sig.strikeMode() || sig.bypassMode())) {
            return false;
        }

        if (stagePolicy != null && stagePolicy.isEnabled()) {
            String mode = (sig != null ? sig.modeLabel() : "NORMAL");
            if (!stagePolicy.isStageEnabled(OrchStageKeys.VERIFY_FACT, mode, true)) {
                return false;
            }
        }

        return true;
    }

    /*
     * Legacy fallback pipelines (OpenAI-Java / alternate LC message builders) were
     * removed.
     */

    private static String truncate(String text, int max) {
        if (text == null || text.isBlank())
            return "";
        if (max <= 0)
            return "";
        if (text.length() <= max)
            return text;
        return text.substring(0, max);
    }

    /**
     * Apply plan overrides intended for the final answer stage only.
     *
     * <p>
     * We intentionally do NOT apply these overrides inside
     * {@link #callWithRetry(ChatModel, List, ChatRequestDto)} because that method
     * is also reused by creative/exploration steps.
     * </p>
     */
    private ChatRequestDto applyFinalAnswerSamplingOverrides(ChatRequestDto base) {
        var gctx = GuardContextHolder.get();
        if (gctx == null) {
            return base;
        }
        gctx.getPlanOverrides().remove("creative.emergence.final.providerSamplingPending");

        // Detect presence (null means no override). Fail-soft numeric parsing is done
        // inside GuardContext.
        Double ovTemp = gctx.planDouble("llm.answer.temperature");
        Double ovTopP = gctx.planDouble("llm.answer.top_p");
        if (ovTopP == null) {
            ovTopP = gctx.planDouble("llm.answer.topP");
        }
        Integer ovMaxTokens = null;
        if (gctx.getPlanOverride("llm.answer.max_tokens") != null) {
            int v = gctx.planInt("llm.answer.max_tokens", -1);
            if (v > 0)
                ovMaxTokens = v;
        } else if (gctx.getPlanOverride("llm.answer.maxTokens") != null) {
            int v = gctx.planInt("llm.answer.maxTokens", -1);
            if (v > 0)
                ovMaxTokens = v;
        }

        Double ovFreq = gctx.planDouble("llm.answer.frequency_penalty");
        Double ovPres = gctx.planDouble("llm.answer.presence_penalty");

        boolean creativeDeclared = gctx.planBool("creative.emergence.active", false);
        boolean creativeApplied = false;
        if (creativeDeclared) {
            gctx.getPlanOverrides().remove("creative.emergence.effectiveOptionsHash");
            TraceStore.put("creative.emergence.effectiveOptionsHash", null);
            String suppressionReason = creativeFinalSuppressionReason(gctx);
            String profile = String.valueOf(gctx.getPlanOverride("creative.emergence.profile"));
            if (suppressionReason == null) {
                Double requestedTempValue = gctx.planDouble("creative.emergence.final.temperature");
                Double requestedTopPValue = gctx.planDouble("creative.emergence.final.topP");
                if (!creativeFinalOptionsValid(profile, requestedTempValue, requestedTopPValue)) {
                    suppressionReason = "invalid-final-options";
                } else {
                    double requestedTemp = quantizeSampling(requestedTempValue);
                    double requestedTopP = quantizeSampling(requestedTopPValue);
                    Double mandatoryTempCap = finitePlanDouble(gctx, "llm.answer.temperature.max");
                    Double mandatoryTopPCap = firstFinitePlanDouble(
                            gctx, "llm.answer.top_p.max", "llm.answer.topP.max");
                    double effectiveTemp = quantizeSamplingDown(Math.min(
                            requestedTemp,
                            Math.min(2.0d, mandatoryTempCap == null ? 2.0d : mandatoryTempCap)));
                    double effectiveTopP = quantizeSamplingDown(Math.min(
                            requestedTopP,
                            Math.min(1.0d, mandatoryTopPCap == null ? 1.0d : mandatoryTopPCap)));
                    ovTemp = effectiveTemp;
                    ovTopP = effectiveTopP;
                    String requestedHash = safeCreativeOptionsHash(
                            gctx.getPlanOverride("creative.emergence.requestedOptionsHash"));
                    if (requestedHash == null) {
                        suppressionReason = "incomplete-profile";
                    } else {
                        gctx.putPlanOverride("creative.emergence.final.effectiveTemperature", effectiveTemp);
                        gctx.putPlanOverride("creative.emergence.final.effectiveTopP", effectiveTopP);
                        gctx.putPlanOverride("creative.emergence.final.providerSamplingPending", true);
                        TraceStore.put("creative.emergence.profile", profile);
                        TraceStore.put("creative.emergence.requestedOptionsHash", requestedHash);
                        creativeApplied = true;
                    }
                }
            }
            if (suppressionReason != null) {
                gctx.putPlanOverride("creative.emergence.suppressedReason", suppressionReason);
                TraceStore.put("creative.emergence.suppressedReason", suppressionReason);
            }
        }

        if (!creativeApplied) {
            Double mandatoryTempCap = finitePlanDouble(gctx, "llm.answer.temperature.max");
            if (mandatoryTempCap != null) {
                Double current = ovTemp != null
                        ? ovTemp
                        : base == null ? null : base.getTemperature();
                if (current != null && Double.isFinite(current)) {
                    ovTemp = Math.min(current, mandatoryTempCap);
                }
            }
            Double mandatoryTopPCap = firstFinitePlanDouble(
                    gctx, "llm.answer.top_p.max", "llm.answer.topP.max");
            if (mandatoryTopPCap != null) {
                Double current = ovTopP != null
                        ? ovTopP
                        : base == null ? null : base.getTopP();
                if (current != null && Double.isFinite(current)) {
                    ovTopP = Math.min(current, mandatoryTopPCap);
                }
            }
        }

        boolean changed = (ovTemp != null) || (ovTopP != null) || (ovMaxTokens != null)
                || (ovFreq != null) || (ovPres != null);
        if (!changed) {
            return base;
        }

        try {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            if (ovTemp != null)
                m.put("temperature", ovTemp);
            if (ovTopP != null)
                m.put("topP", ovTopP);
            if (ovMaxTokens != null)
                m.put("maxTokens", ovMaxTokens);
            if (ovFreq != null)
                m.put("frequencyPenalty", ovFreq);
            if (ovPres != null)
                m.put("presencePenalty", ovPres);
            if (!m.isEmpty() && !creativeDeclared) {
                TraceStore.put("llm.answer.overrides", m);
                log.debug("[SamplingOverrides] answer overrides applied: {}", m);
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.answerOverridesTrace", ignore);
        }

        ChatRequestDto.Builder b = (base != null) ? base.toBuilder() : ChatRequestDto.builder();
        if (ovTemp != null)
            b.temperature(ovTemp);
        if (ovTopP != null)
            b.topP(ovTopP);
        if (ovMaxTokens != null)
            b.maxTokens(ovMaxTokens);
        if (ovFreq != null)
            b.frequencyPenalty(ovFreq);
        if (ovPres != null)
            b.presencePenalty(ovPres);
        return b.build();
    }

    private static String creativeFinalSuppressionReason(GuardContext context) {
        if (context == null) {
            return "context-unavailable";
        }
        if (context.isSensitiveTopic() || context.planBool("privacy.boundary.enforce", false)) {
            return "sensitive-topic";
        }
        if (!"explore".equals(context.getPlanOverride("promptPose.application.intentSlot"))) {
            return "intent-mismatch";
        }
        String profile = String.valueOf(context.getPlanOverride("creative.emergence.profile"));
        if (!profile.matches("VIVID|WILD|FERAL")) {
            return "invalid-profile";
        }
        return creativeEmergenceEligible(context) ? null : "incomplete-profile";
    }

    private static boolean creativeFinalOptionsValid(String profile, Double temperature, Double topP) {
        if (temperature == null || topP == null
                || !Double.isFinite(temperature) || !Double.isFinite(topP)) {
            return false;
        }
        return switch (profile) {
            case "VIVID" -> inSamplingRange(temperature, 1.05d, 1.20d)
                    && inSamplingRange(topP, 0.95d, 0.97d);
            case "WILD" -> inSamplingRange(temperature, 1.21d, 1.40d)
                    && inSamplingRange(topP, 0.97d, 0.99d);
            case "FERAL" -> inSamplingRange(temperature, 1.41d, 1.50d)
                    && inSamplingRange(topP, 0.99d, 1.00d);
            default -> false;
        };
    }

    private static boolean inSamplingRange(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private static Double finitePlanDouble(GuardContext context, String key) {
        Double value = context == null ? null : context.planDouble(key);
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static Double firstFinitePlanDouble(GuardContext context, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            Double value = finitePlanDouble(context, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static double quantizeSampling(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static double quantizeSamplingDown(double value) {
        return Math.floor((value + 1.0e-9d) * 100.0d) / 100.0d;
    }

    private static String safeCreativeOptionsHash(Object candidate) {
        String value = candidate == null ? "" : String.valueOf(candidate).toLowerCase(Locale.ROOT);
        return value.matches("hash:[0-9a-f]{12}") ? value : null;
    }

    /**
     * Apply exploration-stage caps (e.g. clamp temperature) without affecting
     * final answer sampling.
     */
    private ChatRequestDto applyExploreSamplingCaps(ChatRequestDto base) {
        var gctx = GuardContextHolder.get();
        if (gctx == null) {
            return base;
        }
        Double cap = gctx.planDouble("llm.explore.temperature.max");
        if (cap == null) {
            return base;
        }
        double cur = (base != null && base.getTemperature() != null) ? base.getTemperature() : defaultTemp;
        if (cur <= cap) {
            return base;
        }
        ChatRequestDto.Builder b = (base != null) ? base.toBuilder() : ChatRequestDto.builder();
        b.temperature(cap);
        return b.build();
    }

    private int estimateChatMessageChars(List<? extends ChatMessage> msgs) {
        return SafeChatMessageLog.safeTextChars(msgs);
    }

    private static int estimateTokensFromChars(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(chars / 4.0d));
    }

    private static int safeLen(String value) {
        return value == null ? 0 : value.length();
    }

    private static void emitRagPipelineEvent(
            String phase,
            String stage,
            String step,
            String component,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            Map<String, Object> failure,
            Map<String, Object> control) {
        try {
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    phase,
                    stage,
                    step,
                    component,
                    status,
                    input,
                    output,
                    failure,
                    control);
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("rag.pipelineEvent", ignore);
        }
    }

    private void recordCostZone(String zone, String model, int inputChars, int attempt,
            boolean retryOrFallback, String where) {
        String safeZone = (zone == null || zone.isBlank()) ? "unknown" : zone.trim().replaceAll("[^a-zA-Z0-9_.-]+", "_");
        int chars = Math.max(0, inputChars);
        int tokens = estimateTokensFromChars(chars);
        try {
            TraceStore.put("cost.zone." + safeZone + ".inputChars", chars);
            TraceStore.put("cost.zone." + safeZone + ".approxInputTokens", tokens);
            TraceStore.put("cost.zone." + safeZone + ".modelHash", SafeRedactor.hashValue(model));
            TraceStore.put("cost.zone." + safeZone + ".attempt", attempt);
            TraceStore.put("cost.zone." + safeZone + ".retryOrFallback", retryOrFallback);
            TraceStore.inc("cost.zone." + safeZone + ".calls");
            TraceStore.append("cost.zone.events", Map.of(
                    "zone", safeZone,
                    "modelHash", SafeRedactor.hashValue(model),
                    "inputChars", chars,
                    "approxInputTokens", tokens,
                    "attempt", attempt,
                    "retryOrFallback", retryOrFallback));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("cost.zoneTrace", ignore);
        }
        if (debugEventStore != null && tokens >= Math.max(1, costTraceWarnInputTokens)) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("zone", safeZone);
                data.put("modelHash", SafeRedactor.hashValue(model));
                data.put("inputChars", chars);
                data.put("approxInputTokens", tokens);
                data.put("attempt", attempt);
                data.put("retryOrFallback", retryOrFallback);
                debugEventStore.emit(
                        DebugProbeType.ORCHESTRATION,
                        DebugEventLevel.WARN,
                        "cost.zone." + safeZone + ".high_input",
                        "High estimated LLM input token burn",
                        where,
                        data,
                        null);
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("cost.zoneDebugEvent", ignore);
            }
        }
    }

    private static boolean hasThreeRoleRefinementCandidates(PromptContext ctx) {
        if (ctx == null || ctx.ensembleCandidates() == null || ctx.ensembleCandidates().size() != 3) {
            return false;
        }
        Map<String, com.example.lms.ensemble.SampledCandidate.HypothesisDirection> directions = new HashMap<>();
        for (com.example.lms.ensemble.SampledCandidate candidate : ctx.ensembleCandidates()) {
            if (candidate == null || candidate.nodeId() == null
                    || directions.put(candidate.nodeId(), candidate.hypothesisDirection()) != null) {
                return false;
            }
        }
        return directions.get("support") == com.example.lms.ensemble.SampledCandidate.HypothesisDirection.SUPPORT
                && directions.get("support_alternative")
                        == com.example.lms.ensemble.SampledCandidate.HypothesisDirection.SUPPORT
                && directions.get("falsify") == com.example.lms.ensemble.SampledCandidate.HypothesisDirection.FALSIFY;
    }

    private static boolean creativeEmergenceEligible(GuardContext context) {
        if (context == null || context.isSensitiveTopic()
                || context.planBool("privacy.boundary.enforce", false)
                || !context.planBool("creative.emergence.active", false)
                || !"explore".equals(context.getPlanOverride("promptPose.application.intentSlot"))) {
            return false;
        }
        String profile = String.valueOf(context.getPlanOverride("creative.emergence.profile"));
        String requestedHash = String.valueOf(
                context.getPlanOverride("creative.emergence.requestedOptionsHash")).toLowerCase(Locale.ROOT);
        if (!requestedHash.matches("hash:[0-9a-f]{12}")) {
            return false;
        }
        return switch (profile) {
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

    private static void rehydrateCreativeEmergenceTrace(GuardContext context) {
        if (context == null) {
            return;
        }
        boolean active = context.planBool("creative.emergence.active", false);
        TraceStore.put("creative.emergence.active", active);
        String profile = String.valueOf(context.getPlanOverride("creative.emergence.profile"));
        if (active && profile.matches("VIVID|WILD|FERAL")) {
            TraceStore.put("creative.emergence.profile", profile);
        }
        String requestedHash = String.valueOf(
                context.getPlanOverride("creative.emergence.requestedOptionsHash")).toLowerCase(Locale.ROOT);
        if (active && requestedHash.matches("hash:[0-9a-f]{12}")) {
            TraceStore.put("creative.emergence.requestedOptionsHash", requestedHash);
        }
        String reason = SafeRedactor.traceLabelOrFallback(
                context.getPlanOverride("creative.emergence.suppressedReason"), "none");
        if (!active && !"none".equals(reason)) {
            TraceStore.put("creative.emergence.suppressedReason", reason);
        }
    }

    private static void markCreativeSamplingUnproven(String reason) {
        GuardContext context = GuardContextHolder.get();
        if (context == null || !context.planBool("creative.emergence.active", false)) {
            return;
        }
        context.getPlanOverrides().remove("creative.emergence.effectiveOptionsHash");
        context.getPlanOverrides().remove("creative.emergence.provider.effectiveTemperature");
        context.getPlanOverrides().remove("creative.emergence.provider.effectiveTopP");
        context.getPlanOverrides().remove("creative.emergence.final.providerSamplingPending");
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "sampling-option-unproven");
        context.putPlanOverride("creative.emergence.suppressedReason", safeReason);
        TraceStore.put("creative.emergence.effectiveOptionsHash", null);
        TraceStore.put("creative.emergence.suppressedReason", safeReason);
    }

    private static void clearPendingCreativeSamplingIfUnclaimed(String reason) {
        GuardContext context = GuardContextHolder.get();
        if (context != null
                && context.planBool("creative.emergence.final.providerSamplingPending", false)) {
            markCreativeSamplingUnproven(reason);
        }
    }

    private static boolean creativeProviderSamplingPending() {
        GuardContext context = GuardContextHolder.get();
        return context != null
                && context.planBool("creative.emergence.final.providerSamplingPending", false);
    }

    private static boolean creativeEffectiveSamplingRecorded() {
        GuardContext context = GuardContextHolder.get();
        if (context == null) {
            return false;
        }
        String hash = String.valueOf(
                context.getPlanOverride("creative.emergence.effectiveOptionsHash"))
                .toLowerCase(Locale.ROOT);
        return hash.matches("hash:[0-9a-f]{12}")
                && Double.isFinite(context.planDouble(
                        "creative.emergence.provider.effectiveTemperature", Double.NaN))
                && Double.isFinite(context.planDouble(
                        "creative.emergence.provider.effectiveTopP", Double.NaN));
    }

    private static void markCreativeSamplingUnprovenPreservingReason(String fallbackReason) {
        GuardContext context = GuardContextHolder.get();
        if (context == null || !context.planBool("creative.emergence.active", false)) {
            return;
        }
        String existing = String.valueOf(
                context.getPlanOverride("creative.emergence.suppressedReason")).trim();
        String reason = existing.isBlank()
                || "null".equalsIgnoreCase(existing)
                || "none".equalsIgnoreCase(existing)
                ? fallbackReason
                : existing;
        markCreativeSamplingUnproven(reason);
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
        return inSamplingRange(
                        context.planDouble("creative.emergence.search.temperature", Double.NaN),
                        searchTempMin, searchTempMax)
                && inSamplingRange(
                        context.planDouble("creative.emergence.search.rate", Double.NaN),
                        searchRateMin, searchRateMax)
                && inSamplingRange(
                        context.planDouble("creative.emergence.candidate.temperature", Double.NaN),
                        candidateTempMin, candidateTempMax)
                && inSamplingRange(
                        context.planDouble("creative.emergence.candidate.topP", Double.NaN),
                        candidateTopPMin, candidateTopPMax)
                && inSamplingRange(
                        context.planDouble("creative.emergence.final.temperature", Double.NaN),
                        finalTempMin, finalTempMax)
                && inSamplingRange(
                        context.planDouble("creative.emergence.final.topP", Double.NaN),
                        finalTopPMin, finalTopPMax)
                && inSamplingRange(
                        context.planDouble("creative.emergence.selfAsk.temperature", Double.NaN),
                        selfAskMin, selfAskMax);
    }

    private static boolean creativeEmergenceTriad(
            GuardContext context,
            java.util.List<com.example.lms.ensemble.SampledCandidate> candidates) {
        if (!creativeEmergenceEligible(context) || candidates == null || candidates.size() != 3) {
            return false;
        }
        if (!"creative_synthesis".equals(TraceStore.get("ensemble.refiner.selectionDecision"))) {
            return false;
        }
        java.util.Set<String> nodeIds = candidates.stream()
                .filter(java.util.Objects::nonNull)
                .map(com.example.lms.ensemble.SampledCandidate::nodeId)
                .collect(java.util.stream.Collectors.toSet());
        return nodeIds.equals(java.util.Set.of("cooperative", "base_rate", "opportunistic"));
    }

    private String callWithRetry(ChatModel model,
            List<dev.langchain4j.data.message.ChatMessage> msgs,
            ChatRequestDto dto) {
        return callWithRetryReportingSuccess(model, msgs, dto,
                success -> recordModelSuccess(success.modelId(), success.endpoint()));
    }

    private record LlmCallSuccess(String modelId, OpenAiEndpointCompatibility.Endpoint endpoint) {
    }

    private ChatUsageLedger.ModelAttempt beginChatUsageAttempt(
            ChatUsageLedger.ModelPurpose purpose,
            ChatModel model,
            Integer profileTarget,
            Integer normalizedRequestCap,
            ChatUsageLedger.CapSource fallbackSource) {
        if (chatUsageLedger == null) {
            return null;
        }
        ChatUsageLedger.ConfiguredCap configured = DynamicChatModelFactory.configuredTokenBudget(model);
        ChatUsageLedger.CapSource source = configured.source() == ChatUsageLedger.CapSource.UNKNOWN
                ? fallbackSource
                : configured.source();
        return chatUsageLedger.beginModelInvocation(
                purpose,
                configured.withRequestContext(profileTarget, normalizedRequestCap, source));
    }

    private String callWithRetryReportingSuccess(ChatModel model,
            List<dev.langchain4j.data.message.ChatMessage> msgs,
            ChatRequestDto dto,
            Consumer<LlmCallSuccess> successSink) {
        return callWithRetryReportingSuccess(model, msgs, dto, successSink, false);
    }

    private String callWithRetryReportingSuccess(ChatModel model,
            List<dev.langchain4j.data.message.ChatMessage> msgs,
            ChatRequestDto dto,
            Consumer<LlmCallSuccess> successSink,
            boolean strictSingleAttempt) {
        return callWithRetryReportingSuccess(model, msgs, dto, successSink, strictSingleAttempt, null);
    }

    private String callWithRetryReportingSuccess(ChatModel model,
            List<dev.langchain4j.data.message.ChatMessage> msgs,
            ChatRequestDto dto,
            Consumer<LlmCallSuccess> successSink,
            boolean strictSingleAttempt,
            Integer profileTarget) {
        boolean finalSamplingCall = creativeProviderSamplingPending();
        try {
            String out = callWithRetryReportingSuccessCore(
                    model, msgs, dto, successSink,
                    strictSingleAttempt || llmStrictSingleAttempt || (dto != null && dto.isStrictModelSelection()), profileTarget);
            if (finalSamplingCall) {
                if (out == null || out.isBlank()) {
                    markCreativeSamplingUnprovenPreservingReason("provider-blank-response");
                } else if (!creativeEffectiveSamplingRecorded()) {
                    markCreativeSamplingUnprovenPreservingReason("sampling-option-unproven");
                }
            }
            return out;
        } catch (CancellationException cancelled) {
            if (finalSamplingCall) {
                markCreativeSamplingUnprovenPreservingReason("provider-call-cancelled");
            }
            throw cancelled;
        } catch (RuntimeException | Error failure) {
            if (finalSamplingCall) {
                markCreativeSamplingUnprovenPreservingReason("provider-call-failed");
            }
            throw failure;
        }
    }

    private String callWithRetryReportingSuccessCore(ChatModel model,
            List<dev.langchain4j.data.message.ChatMessage> msgs,
            ChatRequestDto dto,
            Consumer<LlmCallSuccess> successSink,
            boolean strictSingleAttempt,
            Integer profileTarget) {
        if (model == null) {
            throw new IllegalStateException("ChatModel is not configured");
        }

        String requestedModel = (dto != null && dto.getModel() != null) ? dto.getModel().trim() : null;
        if (requestedModel != null && requestedModel.isBlank()) {
            requestedModel = null;
        }
        requestedModel = localSafeModelOrNull(requestedModel, "requested");

        String routedModel = (modelRouter == null) ? null : modelRouter.resolveModelName(model);
        if (routedModel != null) {
            routedModel = routedModel.trim();
            if (routedModel.isBlank() || "unknown".equalsIgnoreCase(routedModel)) {
                routedModel = null;
            }
            // Guard: some routers fall back to class simpleName. Treat it as invalid.
            if (routedModel != null) {
                String cls = model.getClass().getSimpleName();
                if (routedModel.equals(cls)) {
                    routedModel = null;
                }
                if (routedModel != null && !routedModel.isEmpty()
                        && Character.isUpperCase(routedModel.charAt(0))
                        && routedModel.matches("[A-Za-z0-9_$]+")) {
                    routedModel = null;
                }
            }
        }
        routedModel = localSafeModelOrNull(routedModel, "routed");

        String resolved = requestedModel;
        if (resolved == null || resolved.isBlank()) {
            resolved = routedModel;
        }
        if (resolved == null || resolved.isBlank()) {
            resolved = defaultModel;
        }
        if (resolved == null || resolved.isBlank()) {
            resolved = ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL;
        }
        final int callTimeoutSeconds = dto == null
                ? llmTimeoutSeconds
                : RequestedModelTimeoutPolicy.timeoutSeconds(dto.getModel(), resolved, llmTimeoutSeconds,
                        requestedModelTimeoutSeconds);
        TimeBudget requestBudget = TimeBudgetContext.get();
        final long callerRemainingMs = requestBudget == null ? Long.MAX_VALUE : requestBudget.remainingMillis();
        final long defaultCallTimeoutMs = Math.max(1L, TimeUnit.SECONDS.toMillis(Math.max(1, callTimeoutSeconds)));
        final long callTimeoutBudgetMs = callerRemainingMs == Long.MAX_VALUE
                ? defaultCallTimeoutMs
                : Math.max(1L, Math.min(defaultCallTimeoutMs, callerRemainingMs));
        final int callTimeoutBudgetSeconds = (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                (callTimeoutBudgetMs + 999L) / 1000L));
        if (callerRemainingMs != Long.MAX_VALUE && callerRemainingMs < defaultCallTimeoutMs) {
            try {
                TraceStore.put("llm.call.timeout.cappedByRequestBudget", true);
                TraceStore.put("llm.call.timeout.requestRemainingMs", callerRemainingMs);
                TraceStore.put("llm.call.timeout.appliedMs", callTimeoutBudgetMs);
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.callTimeoutRequestBudgetTrace", ignore); }
        }
        if (isLocalProvider() && ModelCapabilities.isRemoteLookingModelId(resolved)
                && (dynamicChatModelFactory == null || !dynamicChatModelFactory.canServeQuietly(resolved))) {
            log.warn("[AWX2AF2][model-policy] ignored {} modelHash={} provider={} reason=local_provider",
                    "resolved", SafeRedactor.hashValue(resolved), llmProvider);
            try {
                TraceStore.put("llm.model.policy.blocked", true);
                TraceStore.put("llm.model.policy.blocked.stage", "resolved");
                TraceStore.put("llm.model.policy.blocked.modelHash", SafeRedactor.hashValue(resolved));
                TraceStore.put("llm.model.policy.blocked.provider", safeProviderName());
                TraceStore.put("llm.model.policy.blocked.reason", "local_provider_remote_model");
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.modelPolicyBlockedResolved", ignore); }
            resolved = localFallbackModelId();
        }

        try {
            TraceStore.put("llm.call.modelHash", SafeRedactor.hashValue(resolved));
            TraceStore.put("llm.call.inputChars", estimateChatMessageChars(msgs));
            TraceStore.put("llm.call.approxInputTokens", estimateTokensFromChars(estimateChatMessageChars(msgs)));
            TraceStore.put("llm.call.maxAttempts", strictSingleAttempt ? 1 : llmMaxAttempts + 1);
            TraceStore.put("llm.call.strictSingleAttempt", strictSingleAttempt);
            if (requestedModel != null) {
                TraceStore.put("llm.call.model.requestedHash", SafeRedactor.hashValue(requestedModel));
            }
            if (routedModel != null) {
                TraceStore.put("llm.call.model.routedHash", SafeRedactor.hashValue(routedModel));
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.callModelTrace", ignore); }

        ChatModel modelForCall = model;
        if (dto != null && dynamicChatModelFactory != null) {
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);
            try {
                modelForCall = strictSingleAttempt
                        ? dynamicChatModelFactory.lcWithTimeout(
                                resolved,
                                dto.getTemperature(),
                                dto.getTopP(),
                                dto.getFrequencyPenalty(),
                                dto.getPresencePenalty(),
                                dto.getMaxTokens(),
                                callTimeoutBudgetSeconds,
                                0)
                        : dynamicChatModelFactory.lcWithTimeout(
                                resolved,
                                dto.getTemperature(),
                                dto.getTopP(),
                                dto.getFrequencyPenalty(),
                                dto.getPresencePenalty(),
                                dto.getMaxTokens(),
                                callTimeoutBudgetSeconds);
            } catch (IllegalStateException guard) {
                // Fail-soft: ProviderGuard(?? OpenAI ???놁쓬)濡??숈쟻 ?ъ깮?깆씠 ?ㅽ뙣?섎㈃ ?먮낯 紐⑤뜽 ?좎?
                log.warn("[ChatWorkflow] dynamic model rebuild blocked: reason={} originalModelHash={} originalModelLength={}",
                        SafeRedactor.safeMessage(guard.getMessage(), 180), SafeRedactor.hashValue(resolved), resolved == null ? 0 : resolved.length());
                if (strictSingleAttempt
                        || com.example.lms.llm.gateway.LlmGatewayFailureClassifier.hasNonReplayableReason(guard)) {
                    throw guard;
                }
                markCreativeSamplingUnproven("model-rebuild-fallback");
                modelForCall = model;
            } finally {
                TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, null);
                clearPendingCreativeSamplingIfUnclaimed("sampling-option-unproven");
            }
        }

        recordModelRequestTimelinePhase("pending", resolved, "none");

        // Endpoint-compat preflight: completion-only models shouldn't hit
        // /v1/chat/completions.
        if (!strictSingleAttempt && openAiFallbackToCompletions
                && OpenAiEndpointCompatibility.isLikelyCompletionsOnlyModelId(resolved)) {
            String baseUrlUsed = safeTraceString("llm.factory.baseUrl");
            boolean local = safeTraceBool("llm.factory.local");
            try {
                TraceStore.put("llm.endpoint.compat.preflight", "completions");
                log.warn("[LLM_ENDPOINT_COMPAT] preflight modelHash={} -> /v1/completions", SafeRedactor.hashValue(resolved));
                recordCostZone(
                        "llm.endpoint_preflight",
                        resolved,
                        estimateChatMessageChars(msgs),
                        1,
                        true,
                        "ChatWorkflow.endpointCompatPreflight");
                String out = callCompletionsFallback(resolved, msgs, dto, baseUrlUsed, local);
                successSink.accept(new LlmCallSuccess(resolved,
                        OpenAiEndpointCompatibility.Endpoint.COMPLETIONS));
                return out;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (LlmFastBailoutException budgetExhausted) {
                throw budgetExhausted;
            } catch (Exception pre) {
                rethrowIfRequestBudgetExhausted("completions_preflight", pre);
                recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.COMPLETIONS, reasonFromException(pre));
                log.warn("[LLM_ENDPOINT_COMPAT] preflight /v1/completions failed; will try chat. modelHash={} err={}",
                        SafeRedactor.hashValue(resolved), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(pre)), String.valueOf(pre).length()));
            }
        }

        Throwable last = null;

        // Retry budget: prevent pathological timeout accumulation.
        final long startedAtMs = System.currentTimeMillis();
        boolean ep = false; // ?꾩떆 蹂?? final ?ы븷???ㅻ쪟 諛⑹?
        try {
            Object p = TraceStore.get("chat.evidence.present");
            Object c = TraceStore.get("chat.evidence.count");
            boolean pBool = p != null && Boolean.parseBoolean(String.valueOf(p));
            int cInt = 0;
            if (c != null) {
                try {
                    cInt = Integer.parseInt(String.valueOf(c));
                } catch (NumberFormatException ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("chat.evidenceCountParse", ignore); cInt = 0;
                }
            }
            ep = pBool || cInt > 0;
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("chat.evidencePresentTrace", ignore);
        }
        final boolean evidencePresent = ep;
        final long configuredRetryBudgetMs = (llmRetryMaxTotalMs > 0)
                ? llmRetryMaxTotalMs
                : Math.max(1500L, (long) callTimeoutSeconds * 1000L + llmBackoffMs + 250L);
        final long budgetMs = callerRemainingMs == Long.MAX_VALUE
                ? configuredRetryBudgetMs
                : Math.max(1L, Math.min(configuredRetryBudgetMs, callerRemainingMs));
        if (callerRemainingMs != Long.MAX_VALUE && callerRemainingMs < configuredRetryBudgetMs) {
            try {
                TraceStore.put("llm.retryBudget.cappedByRequestBudget", true);
                TraceStore.put("llm.retryBudget.requestRemainingMs", callerRemainingMs);
                TraceStore.put("llm.retryBudget.configuredMs", configuredRetryBudgetMs);
                TraceStore.put("llm.retryBudget.appliedMs", budgetMs);
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.retryBudgetRequestTrace", ignore); }
        }
        int timeoutHits = 0, upstream5xxHits = 0, blankHits = 0;
        boolean selfHealed = false;
        boolean modelHealed = false;
        int callInputChars = estimateChatMessageChars(msgs);
        final int maxAttempts = strictSingleAttempt ? 0 : llmMaxAttempts;
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            requestBudgetBoundedLlmTimeout(defaultCallTimeoutMs, "retry_attempt_guard");
            try {
                recordCostZone(
                        "llm.draft",
                        resolved,
                        callInputChars,
                        attempt + 1,
                        attempt > 0,
                        "ChatWorkflow.callWithRetry");
                Duration chatDraftTimeout = requestBudgetBoundedLlmTimeout(defaultCallTimeoutMs, "chat_draft");
                ChatUsageLedger.ModelAttempt usageAttempt = beginChatUsageAttempt(
                        attempt == 0 ? ChatUsageLedger.ModelPurpose.PRIMARY : ChatUsageLedger.ModelPurpose.RETRY,
                        modelForCall,
                        profileTarget,
                        dto == null ? null : dto.getMaxTokens(),
                        ChatUsageLedger.CapSource.ROUTER_MODEL);
                dev.langchain4j.data.message.AiMessage ai = TimedChatModelCaller.chat(
                        modelForCall,
                        msgs,
                        chatDraftTimeout,
                        "chat_draft",
                        resolved,
                        usageAttempt);
                String out = ai == null ? "" : (ai.text() == null ? "" : ai.text());
                successSink.accept(new LlmCallSuccess(resolved,
                        OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS));
                return out;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (LlmFastBailoutException budgetExhausted) {
                throw budgetExhausted;
            } catch (Exception e) {
                // Model-not-found / endpoint mismatch??鍮꾩씪?쒖쟻 ??利됱떆 fail-fast (+ endpoint-compat
                // failover)
                rethrowIfRequestBudgetExhausted("chat_draft", e);
                if (com.example.lms.llm.gateway.LlmGatewayFailureClassifier.hasNonReplayableReason(e)) {
                    TraceStore.put("llm.error.code", "NON_REPLAYABLE");
                    TraceStore.put("llm.error.retryable", false);
                    if (e instanceof RuntimeException runtime) throw runtime;
                    throw new RuntimeException("LLM non-replayable gateway failure", e);
                }
                dev.langchain4j.exception.ModelNotFoundException mnfe = unwrapModelNotFound(e);
                String hintMsg = (mnfe != null ? mnfe.getMessage() : e.getMessage());

                boolean hintCompletions = OpenAiEndpointCompatibility.isChatEndpointMismatchMessage(hintMsg);
                boolean hintResponses = OpenAiEndpointCompatibility.isResponsesEndpointSuggestionMessage(hintMsg);
                boolean chatEndpointMissing = OpenAiEndpointCompatibility
                        .isChatCompletionsEndpointMissingMessage(hintMsg);

                if (!strictSingleAttempt && (hintCompletions || hintResponses || chatEndpointMissing)) {
                    recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                            chatEndpointMissing ? "chat_endpoint_missing" : "endpoint_mismatch");
                    String summary = OpenAiEndpointCompatibility.summarizeForLog(hintMsg, 240);
                    String baseUrlUsed = safeTraceString("llm.factory.baseUrl");
                    boolean local = safeTraceBool("llm.factory.local");

                    try {
                        TraceStore.put("llm.endpoint.compat.mismatch", true);
                        TraceStore.put("llm.endpoint.compat.hint",
                                hintCompletions ? "completions"
                                        : (hintResponses ? "responses" : "chat_endpoint_missing"));
                        TraceStore.put("llm.endpoint.compat.detail", summary);
                        if (baseUrlUsed != null) {
                            TraceStore.put("llm.endpoint.compat.baseUrlHost", hostOf(baseUrlUsed));
                            TraceStore.put("llm.endpoint.compat.baseUrlHash", SafeRedactor.hashValue(baseUrlUsed));
                        }
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatMismatchTrace", ignore); }

                    String hint = hintCompletions ? "/v1/completions"
                            : (hintResponses ? "/v1/responses" : "missing /v1/chat/completions");
                    log.warn("[LLM_ENDPOINT_COMPAT] detected modelHash={} primary=/v1/chat/completions hint={} detail={}{}",
                            SafeRedactor.hashValue(resolved), hint, summary, LogCorrelation.suffix());

                    ChatUsageLedger.ConfiguredCap fallbackCap =
                            DynamicChatModelFactory.configuredTokenBudget(modelForCall);
                    String out = hintCompletions
                            ? tryEndpointCompatFallback(resolved, msgs, dto, baseUrlUsed, local,
                                    fallbackCap, successSink, "completions", "responses")
                            : tryEndpointCompatFallback(resolved, msgs, dto, baseUrlUsed, local,
                                    fallbackCap, successSink, "responses", "completions");

                    if (out != null) {
                        return out;
                    }

                    String attempted = safeTraceString("llm.endpoint.compat.attempted");
                    String userMsg = OpenAiEndpointCompatibility.userFacingEndpointMismatch(resolved);
                    if (attempted != null && !attempted.isBlank()) {
                        userMsg = userMsg + "\n- ?쒕룄: " + attempted;
                    }
                    userMsg = userMsg + "\n- 愿由ъ옄: " + LogCorrelation.suffix().trim();

                    throw new LlmConfigurationException(
                            "MODEL_ENDPOINT_MISMATCH",
                            userMsg,
                            resolved,
                            "/v1/chat/completions",
                            (mnfe != null ? mnfe : e));
                }

                if (mnfe != null) {
                    recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                            "model_not_found");
                    String raw = mnfe.getMessage();
                    String summary = OpenAiEndpointCompatibility.summarizeForLog(raw, 240);
                    log.warn("[LLM] non-retryable (model not found). modelHash={} detail={}{}",
                            SafeRedactor.hashValue(resolved), SafeRedactor.safeMessage(summary, 180), LogCorrelation.suffix());
                    String userMsg = OpenAiEndpointCompatibility.userFacingModelNotFound(resolved)
                            + "\n- 愿由ъ옄: " + LogCorrelation.suffix().trim();
                    throw new LlmConfigurationException(
                            "MODEL_NOT_FOUND",
                            userMsg,
                            resolved,
                            null,
                            mnfe);
                }

                last = e;
                log.warn("[LLM] attempt {}/{} failed: {}", attempt + 1, llmMaxAttempts + 1, String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));

                // Self-heal: unsupported param 媛먯? ??max_tokens / temperature ?? ?덉쟾 ?뚮씪誘명꽣濡?1???ъ떆??
                boolean unsupportedMaxTokens = OpenAiTokenParamCompat.isUnsupportedMaxTokens(e);
                boolean unsupportedSampling = OpenAiTokenParamCompat.isUnsupportedSampling(e);
                if (!strictSingleAttempt && !selfHealed && (unsupportedMaxTokens || unsupportedSampling)) {
                    selfHealed = true;
                    try {
                        String healModelId = resolved;
                        if (healModelId == null || healModelId.isBlank()) {
                            healModelId = (defaultModel == null || defaultModel.isBlank()) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL
                                    : defaultModel;
                        }
                        if (dynamicChatModelFactory == null) {
                            throw new IllegalStateException("dynamicChatModelFactory is null");
                        }

                        // ?덉쟾 ?뚮씪誘명꽣濡??щ퉴?? temperature/topP/maxTokens 紐⑤몢 null(?쒕쾭 湲곕낯媛??ъ슜)
                        markCreativeSamplingUnproven("provider-default");
                        ChatModel healed = dynamicChatModelFactory.lcWithTimeout(
                                healModelId, null, null, null, null, null, callTimeoutBudgetSeconds);
                        recordCostZone(
                                "llm.self_heal",
                                healModelId,
                                callInputChars,
                                attempt + 1,
                                true,
                                "ChatWorkflow.callWithRetry");
                        Duration selfHealTimeout = requestBudgetBoundedLlmTimeout(defaultCallTimeoutMs, "chat_self_heal");
                        ChatUsageLedger.ModelAttempt usageAttempt = beginChatUsageAttempt(
                                ChatUsageLedger.ModelPurpose.SELF_HEAL,
                                healed,
                                profileTarget,
                                dto == null ? null : dto.getMaxTokens(),
                                ChatUsageLedger.CapSource.SELF_HEAL);
                        dev.langchain4j.data.message.AiMessage ai = TimedChatModelCaller.chat(
                                healed,
                                msgs,
                                selfHealTimeout,
                                "chat_self_heal",
                                healModelId,
                                usageAttempt);
                        String out = ai == null ? "" : (ai.text() == null ? "" : ai.text());
                        successSink.accept(new LlmCallSuccess(healModelId,
                                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS));
                        return out;
                    } catch (CancellationException cancelled) {
                        throw cancelled;
                    } catch (LlmFastBailoutException budgetExhausted) {
                        throw budgetExhausted;
                    } catch (Exception healEx) {
                        com.example.lms.llm.gateway.LlmResponseTerminalException.rethrowIfPresent(healEx);
                        rethrowIfRequestBudgetExhausted("chat_self_heal", healEx);
                        recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                                reasonFromException(healEx));
                        last = healEx;
                        log.warn("[LLM] self-heal retry failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(healEx)), String.valueOf(healEx).length()));
                    }
                }

                // Non-retryable / configuration errors should stop the retry loop early.
                com.example.lms.llm.LlmErrorClassifier.Result cls = com.example.lms.llm.LlmErrorClassifier.classify(e);
                try {
                    TraceStore.put("llm.error.code", cls.code());
                    TraceStore.put("llm.error.retryable", cls.retryable());
                    if (cls.statusCode() != null) {
                        TraceStore.put("llm.error.statusCode", cls.statusCode());
                    }
                    TraceStore.put("llm.error.message", cls.shortMessage());
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.errorClassifierTrace", ignore); }

                boolean isTimeout = "TIMEOUT".equals(cls.code()), isUpstream5xx = "UPSTREAM_5XX".equals(cls.code()), isBlankResponse = "BLANK_RESPONSE".equals(cls.code());
                if (isTimeout) { recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "timeout"); timeoutHits++; }
                if (isUpstream5xx) { recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "upstream_5xx"); upstream5xxHits++; }
                if (isBlankResponse) { recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "blank_response"); blankHits++; }

                int minFastBailTimeoutHits = Math.max(1, llmFastBailoutMinTimeoutHitsWithEvidence);
                boolean fastBailTimeout = isTimeout && ((!evidencePresent && timeoutHits >= 1)
                        || (evidencePresent && llmFastBailoutOnTimeoutWithEvidence
                                && timeoutHits >= minFastBailTimeoutHits));
                if (fastBailTimeout) { log.warn("[LLM_FAST_BAIL_TIMEOUT] evidencePresent={} timeoutHits={} attempt={}/{}{}", evidencePresent, timeoutHits, attempt, llmMaxAttempts, LogCorrelation.suffix()); throw new LlmFastBailoutException("LLM timeout fast-bail", e, timeoutHits, attempt, llmMaxAttempts); }
                if (isUpstream5xx && !evidencePresent && upstream5xxHits >= 1) { log.warn("[LLM_FAST_BAIL_UPSTREAM_5XX] evidencePresent={} upstream5xxHits={} attempt={}/{}{}", evidencePresent, upstream5xxHits, attempt, llmMaxAttempts, LogCorrelation.suffix()); throw new LlmFastBailoutException("LLM upstream fast-bail", e, upstream5xxHits, attempt, llmMaxAttempts); }
                if (isBlankResponse && !evidencePresent && blankHits >= 1) { log.warn("[LLM_FAST_BAIL_BLANK_RESPONSE] evidencePresent={} blankHits={} attempt={}/{}{}", evidencePresent, blankHits, attempt, llmMaxAttempts, LogCorrelation.suffix()); throw new LlmFastBailoutException("LLM blank response fast-bail", e, blankHits, attempt, llmMaxAttempts); }

                long elapsedMs = System.currentTimeMillis() - startedAtMs;
                if (budgetMs > 0 && elapsedMs > budgetMs) {
                    LlmRetryBudgetTrace.emit(elapsedMs, budgetMs, attempt, llmMaxAttempts, cls.code()); log.warn("[LLM_RETRY_BUDGET_EXCEEDED] elapsedMs={} budgetMs={} attempt={}/{} code={}{}",
                            elapsedMs, budgetMs, attempt, llmMaxAttempts, cls.code(), LogCorrelation.suffix());
                    throw new RuntimeException("LLM retry budget exceeded", e);
                }

                // Self-heal for "model is required": try once with configured default model +
                // safe params.
                if (!strictSingleAttempt && !modelHealed && "MODEL_REQUIRED".equals(cls.code())
                        && dynamicChatModelFactory != null) {
                    modelHealed = true;
                    try {
                        String fallbackModel = (defaultModel == null || defaultModel.isBlank()) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL
                                : defaultModel;
                        markCreativeSamplingUnproven("provider-default");
                        ChatModel healed = dynamicChatModelFactory.lcWithTimeout(
                                fallbackModel, null, null, null, null, null, callTimeoutBudgetSeconds);
                        recordCostZone(
                                "llm.model_required_heal",
                                fallbackModel,
                                callInputChars,
                                attempt + 1,
                                true,
                                "ChatWorkflow.callWithRetry");
                        Duration modelHealTimeout = requestBudgetBoundedLlmTimeout(defaultCallTimeoutMs, "chat_model_required_heal");
                        ChatUsageLedger.ModelAttempt usageAttempt = beginChatUsageAttempt(
                                ChatUsageLedger.ModelPurpose.MODEL_REQUIRED_HEAL,
                                healed,
                                profileTarget,
                                dto == null ? null : dto.getMaxTokens(),
                                ChatUsageLedger.CapSource.SELF_HEAL);
                        dev.langchain4j.data.message.AiMessage ai = TimedChatModelCaller.chat(
                                healed,
                                msgs,
                                modelHealTimeout,
                                "chat_model_required_heal",
                                fallbackModel,
                                usageAttempt);
                        String out = ai == null ? "" : (ai.text() == null ? "" : ai.text());
                        successSink.accept(new LlmCallSuccess(fallbackModel,
                                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS));
                        return out;
                    } catch (CancellationException cancelled) {
                        throw cancelled;
                    } catch (LlmFastBailoutException budgetExhausted) {
                        throw budgetExhausted;
                    } catch (Exception healEx) {
                        com.example.lms.llm.gateway.LlmResponseTerminalException.rethrowIfPresent(healEx);
                        rethrowIfRequestBudgetExhausted("chat_model_required_heal", healEx);
                        last = healEx;
                        log.warn("[LLM] model-required self-heal retry failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(healEx)), String.valueOf(healEx).length()));
                    }
                }

                if (!cls.retryable()) {
                    log.warn("[LLM] non-retryable ({}). stop retries: {}", cls.code(), cls.shortMessage());
                    throw new RuntimeException("LLM non-retryable: " + cls.code() + " - " + cls.shortMessage(), e);
                }
                if (attempt >= maxAttempts) {
                    break;
                }
                try {
                    long boundedBackoffMs = requestBudgetBoundedBackoffMillis(llmBackoffMs);
                    if (boundedBackoffMs > 0L) {
                        Thread.sleep(boundedBackoffMs);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("LLM call interrupted", ie);
                }
            }
        }

        throw new RuntimeException("LLM unavailable after retries", last);
    }

    private static Duration requestBudgetBoundedLlmTimeout(long defaultTimeoutMs, String stage) {
        long safeDefaultMs = Math.max(1L, defaultTimeoutMs);
        TimeBudget requestBudget = TimeBudgetContext.get();
        if (requestBudget == null) {
            return Duration.ofMillis(safeDefaultMs);
        }
        long remainingMs = requestBudget.remainingMillis();
        if (remainingMs <= 1L) {
            try {
                TraceStore.put("llm.final.skipped", "request_budget_exhausted");
                TraceStore.put("llm.requestBudget.exhausted.stage", stage);
                TraceStore.put("llm.requestBudget.remainingMs", Math.max(0L, remainingMs));
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("llm.requestBudgetExhaustedTrace", ignore);
            }
            throw new LlmFastBailoutException("LLM request budget exhausted", null, 0, 0, 0);
        }
        long appliedMs = Math.max(1L, Math.min(safeDefaultMs, remainingMs));
        try {
            TraceStore.put("llm.requestBudget.timeout.stage", stage);
            TraceStore.put("llm.requestBudget.timeout.appliedMs", appliedMs);
            TraceStore.put("llm.requestBudget.timeout.capped", remainingMs <= safeDefaultMs);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.requestBudgetTimeoutTrace", ignore);
        }
        return Duration.ofMillis(appliedMs);
    }

    private static long requestBudgetBoundedBackoffMillis(long configuredBackoffMs) {
        if (configuredBackoffMs <= 0L) {
            return 0L;
        }
        TimeBudget requestBudget = TimeBudgetContext.get();
        if (requestBudget == null) {
            return configuredBackoffMs;
        }
        long remainingMs = requestBudget.remainingMillis();
        if (remainingMs <= 1L) {
            requestBudgetBoundedLlmTimeout(1L, "retry_backoff");
        }
        return Math.min(configuredBackoffMs, Math.max(1L, remainingMs - 1L));
    }

    private static void rethrowIfRequestBudgetExhausted(String stage, Throwable cause) {
        TimeBudget requestBudget = TimeBudgetContext.get();
        if (requestBudget == null) {
            return;
        }
        long remainingMs = requestBudget.remainingMillis();
        boolean requestCappedTimeout = false;
        try {
            Object capped = TraceStore.get("llm.requestBudget.timeout.capped");
            Object cappedStage = TraceStore.get("llm.requestBudget.timeout.stage");
            requestCappedTimeout = Boolean.parseBoolean(String.valueOf(capped))
                    && Objects.equals(stage, String.valueOf(cappedStage))
                    && TimedChatModelCaller.isHardTimeout(cause);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.requestBudgetCappedTimeoutClassify", ignore);
        }
        if (remainingMs > 1L && !requestCappedTimeout) {
            return;
        }
        try {
            TraceStore.put("llm.final.skipped", "request_budget_exhausted");
            TraceStore.put("llm.requestBudget.exhausted.stage", stage);
            TraceStore.put("llm.requestBudget.remainingMs", Math.max(0L, remainingMs));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.requestBudgetRethrowTrace", ignore);
        }
        throw new LlmFastBailoutException("LLM request budget exhausted", cause, 0, 0, 0);
    }

    private void traceS8Integrity(String stage, String query, String candidate) {
        try {
            FinalAnswerPostProcessor.S8Integrity integrity =
                    finalAnswerPostProcessor.inspectS8Integrity(query, candidate);
            if (!integrity.applicable()) {
                return;
            }
            String prefix = "finalAnswer.s8Integrity." + stage;
            TraceStore.put(prefix + ".requiredObservationCount", integrity.requiredObservationCount());
            TraceStore.put(prefix + ".preservedObservationCount", integrity.preservedObservationCount());
            TraceStore.put(prefix + ".droppedObservationCount",
                    integrity.requiredObservationCount() - integrity.preservedObservationCount());
            TraceStore.put(prefix + ".requiredInferenceCount", integrity.requiredInferenceCount());
            TraceStore.put(prefix + ".preservedInferenceCount", integrity.preservedInferenceCount());
            TraceStore.put(prefix + ".droppedInferenceCount",
                    integrity.requiredInferenceCount() - integrity.preservedInferenceCount());
            TraceStore.put(prefix + ".orderedTwoLineContract", integrity.orderedTwoLineContract());
            TraceStore.put(prefix + ".complete", integrity.complete());
            String contentHash = HashUtil.sha256(candidate == null ? "" : candidate);
            TraceStore.put(prefix + ".contentHash", contentHash);
            if (chatUsageLedger != null) {
                chatUsageLedger.recordS8Integrity(
                        stage,
                        HashUtil.sha256(query == null ? "" : query),
                        integrity.requiredObservationCount(),
                        integrity.preservedObservationCount(),
                        integrity.requiredInferenceCount(),
                        integrity.preservedInferenceCount(),
                        integrity.orderedTwoLineContract(),
                        integrity.complete(),
                        contentHash);
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalAnswer.s8Integrity", ignore);
        }
    }

    private ChatResult sanitizeFallbackResult(ChatResult fallback) {
        return sanitizeFallbackResult(fallback, finalAnswerPostProcessor);
    }

    private ChatResult sanitizeFallbackResult(ChatResult fallback, String query) {
        return sanitizeFallbackResult(fallback, finalAnswerPostProcessor, query);
    }

    enum EvidenceReleaseState {
        NOT_APPLICABLE,
        EVIDENCE_PRESENT,
        CONFIRMED_EMPTY,
        METADATA_INCOMPLETE
    }

    record RetrievalReleaseContract(
            boolean retrievalContractRequested,
            boolean webRequested,
            boolean ragRequested,
            boolean effectiveWeb,
            boolean effectiveRag,
            boolean explicitDirectOff) {
    }

    static RetrievalReleaseContract buildRetrievalReleaseContract(
            ChatRequestDto req,
            ChatRequestDto.RetrievalRequestIntent intent,
            boolean effectiveWeb,
            boolean effectiveRag,
            boolean evidenceReleaseRequired) {
        ChatRequestDto.RetrievalRequestIntent rawIntent = intent != null
                ? intent
                : new ChatRequestDto.RetrievalRequestIntent(
                        req == null ? null : req.getUseWebSearch(),
                        req == null ? null : req.getUseRag());
        com.example.lms.gptsearch.dto.SearchMode mode = req == null
                ? null
                : req.getSearchMode();
        boolean forcedWeb = mode == com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT
                || mode == com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP;
        boolean explicitOff = !forcedWeb
                && ((mode == com.example.lms.gptsearch.dto.SearchMode.OFF
                && req != null
                && Boolean.FALSE.equals(req.getUseRag()))
                || (Boolean.FALSE.equals(rawIntent.webSearch())
                && Boolean.FALSE.equals(rawIntent.rag())));
        boolean webRequested = Boolean.TRUE.equals(rawIntent.webSearch()) || forcedWeb;
        boolean ragRequested = Boolean.TRUE.equals(rawIntent.rag());
        if (evidenceReleaseRequired && !explicitOff) {
            webRequested = webRequested || effectiveWeb;
            ragRequested = ragRequested || effectiveRag;
        }
        boolean retrievalRequested = webRequested || ragRequested || evidenceReleaseRequired;
        return new RetrievalReleaseContract(
                retrievalRequested,
                webRequested,
                ragRequested,
                effectiveWeb,
                effectiveRag,
                explicitOff);
    }

    static EvidenceReleaseState deriveEvidenceReleaseState(
            com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult promotionResult,
            java.util.List<RagEvidenceMetadata> finalEvidence,
            boolean lateUnattributedEvidenceAdded,
            RetrievalReleaseContract contract) {
        RetrievalReleaseContract safeContract = Objects.requireNonNull(contract, "contract");
        if (safeContract.explicitDirectOff()
                || (!safeContract.retrievalContractRequested()
                && !safeContract.effectiveWeb()
                && !safeContract.effectiveRag())) {
            return EvidenceReleaseState.NOT_APPLICABLE;
        }
        if (promotionResult == null
                || promotionResult.status()
                == com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.FAILED
                || promotionResult.status()
                == com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.UNAVAILABLE
                || lateUnattributedEvidenceAdded
                || (safeContract.retrievalContractRequested()
                && !safeContract.effectiveWeb()
                && !safeContract.effectiveRag())
                || (safeContract.webRequested() && !safeContract.effectiveWeb())
                || (safeContract.ragRequested() && !safeContract.effectiveRag())
                || (safeContract.webRequested() && promotionResult.webCitableLocatorCount() <= 0)
                || (safeContract.ragRequested() && promotionResult.vectorCitableLocatorCount() <= 0)) {
            return EvidenceReleaseState.METADATA_INCOMPLETE;
        }

        java.util.List<RagEvidenceMetadata> evidence = finalEvidence == null
                ? java.util.List.of()
                : finalEvidence;
        if (!evidence.isEmpty()) {
            boolean webPresent = evidence.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(item -> "WEB".equals(item.kind()));
            boolean vectorPresent = evidence.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(item -> "VECTOR".equals(item.kind()));
            if ((safeContract.webRequested() && !webPresent)
                    || (safeContract.ragRequested() && !vectorPresent)) {
                return EvidenceReleaseState.METADATA_INCOMPLETE;
            }
            return EvidenceReleaseState.EVIDENCE_PRESENT;
        }

        if (promotionResult.status()
                == com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.PROMOTED
                || promotionResult.status()
                == com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY) {
            return EvidenceReleaseState.CONFIRMED_EMPTY;
        }
        return EvidenceReleaseState.METADATA_INCOMPLETE;
    }

    static com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult legacyPromotionResult(
            java.util.List<RagEvidenceMetadata> evidence) {
        java.util.List<RagEvidenceMetadata> safeEvidence = evidence == null
                ? java.util.List.of()
                : java.util.List.copyOf(evidence);
        if (safeEvidence.isEmpty()) {
            return com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult.callerFailure();
        }
        int webCandidates = 0;
        int webLocators = 0;
        int vectorCandidates = 0;
        int vectorLocators = 0;
        int localCandidates = 0;
        int localLocators = 0;
        for (RagEvidenceMetadata item : safeEvidence) {
            if (item == null) {
                continue;
            }
            boolean hasLocator = (item.source() != null && !item.source().isBlank())
                    || (item.filePath() != null && !item.filePath().isBlank());
            switch (String.valueOf(item.kind())) {
                case "WEB" -> {
                    webCandidates++;
                    if (hasLocator) webLocators++;
                }
                case "VECTOR" -> {
                    vectorCandidates++;
                    if (hasLocator) vectorLocators++;
                }
                case "LOCAL_DOC" -> {
                    localCandidates++;
                    if (hasLocator) localLocators++;
                }
                default -> {
                    // Unknown legacy kinds carry no release authority.
                }
            }
        }
        return new com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult(
                com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.PROMOTED,
                com.example.lms.service.rag.RagEvidenceAttributionService.PromotionReason.PROMOTED,
                safeEvidence,
                webCandidates,
                webLocators,
                vectorCandidates,
                vectorLocators,
                localCandidates,
                localLocators);
    }

    static FinalVerificationReleaseDecision applyEvidenceReleasePolicy(
            FinalVerificationReleaseDecision base,
            EvidenceReleaseState state,
            boolean evidenceReleaseRequired,
            boolean priorFallbackApplied,
            boolean explicitDirectOff) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(state, "state");
        if (!base.releaseAllowed()) {
            return base;
        }
        if (priorFallbackApplied) {
            return new FinalVerificationReleaseDecision(
                    base.content(),
                    base.releaseStatus(),
                    base.reasonCode(),
                    false,
                    false);
        }
        if (explicitDirectOff
                || state == EvidenceReleaseState.NOT_APPLICABLE
                || state == EvidenceReleaseState.EVIDENCE_PRESENT) {
            return base;
        }
        if (state == EvidenceReleaseState.METADATA_INCOMPLETE) {
            return new FinalVerificationReleaseDecision(
                    "evidence_needed: attribution unavailable / verify retrieval evidence",
                    "HOLD",
                    "evidence_release_metadata_incomplete",
                    false,
                    true);
        }
        if (state == EvidenceReleaseState.CONFIRMED_EMPTY && evidenceReleaseRequired) {
            return new FinalVerificationReleaseDecision(
                    "evidence_needed",
                    "HOLD",
                    "evidence_required_empty",
                    false,
                    true);
        }
        return base;
    }

    static boolean detectLateUnattributedEvidence(int beforeCount, int afterCount, boolean returnedFlag) {
        return returnedFlag || afterCount > beforeCount;
    }

    static FinalVerificationReleaseDecision applyFinalVerificationReleaseGate(
            String candidate,
            boolean verificationRequired,
            String verificationStatus,
            boolean outcomeKnown,
            boolean acceptedForMemory) {
        String safeCandidate = candidate == null ? "" : candidate;
        if (!verificationRequired) {
            return new FinalVerificationReleaseDecision(
                    safeCandidate,
                    "NOT_REQUIRED",
                    "verification_not_required",
                    true,
                    false);
        }

        String normalizedStatus = verificationStatus == null
                ? "unknown"
                : verificationStatus.trim().toLowerCase(Locale.ROOT);
        if (!outcomeKnown) {
            return new FinalVerificationReleaseDecision(
                    "evidence_needed: final verification outcome unknown / retry with verifiable evidence",
                    "HOLD",
                    "verification_outcome_unknown",
                    false,
                    false);
        }
        if (acceptedForMemory) {
            if ("pass".equals(normalizedStatus) || "corrected".equals(normalizedStatus)) {
                return new FinalVerificationReleaseDecision(
                        safeCandidate,
                        "APPROVE",
                        "verification_accepted",
                        true,
                        false);
            }
            return inconsistentFinalVerificationState();
        }
        if ("insufficient".equals(normalizedStatus)) {
            return new FinalVerificationReleaseDecision(
                    "evidence_needed: final verification found insufficient evidence / retry with verifiable evidence",
                    "HOLD",
                    "verification_insufficient",
                    false,
                    false);
        }
        if ("rejected".equals(normalizedStatus)) {
            return new FinalVerificationReleaseDecision(
                    "Information unavailable: final verification rejected the draft.",
                    "REJECT",
                    "verification_rejected",
                    false,
                    false);
        }
        return inconsistentFinalVerificationState();
    }

    private static FinalVerificationReleaseDecision inconsistentFinalVerificationState() {
        return new FinalVerificationReleaseDecision(
                "evidence_needed: final verification state inconsistent / retry with verifiable evidence",
                "HOLD",
                "verification_state_inconsistent",
                false,
                false);
    }

    record FinalVerificationReleaseDecision(
            String content,
            String releaseStatus,
            String reasonCode,
            boolean releaseAllowed,
            boolean evidencePolicyApplied) {
    }

    static ChatResult sanitizeFallbackResult(
            ChatResult fallback,
            FinalAnswerPostProcessor postProcessor) {
        return sanitizeFallbackResult(fallback, postProcessor, null);
    }

    static ChatResult sanitizeFallbackResult(
            ChatResult fallback,
            FinalAnswerPostProcessor postProcessor,
            String query) {
        if (fallback == null) {
            return ChatResult.of("The fallback result was unavailable. Please retry the request.",
                    "fallback:unavailable", false);
        }
        FinalAnswerPostProcessor.Result sanitized = Objects.requireNonNull(postProcessor, "postProcessor").process(
                new FinalAnswerPostProcessor.Request(
                        fallback.content(),
                        fallback.content(),
                        false,
                        false,
                        false,
                        true,
                        false,
                        true,
                        false,
                        query));
        try {
            TraceStore.put("answer.postprocess.sanitized", sanitized.changed());
            TraceStore.put("answer.postprocess.reason", sanitized.reasonCode());
            TraceStore.put("memory.save.deniedReason", sanitized.memoryDenyReason());
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("answer.fallbackPostprocessTrace", ignore);
        }
        boolean emptyAnswer = "blank_content".equals(sanitized.reasonCode())
                || "diagnostics_removed_empty".equals(sanitized.reasonCode());
        String modelUsed = fallback.modelUsed();
        Set<String> evidence = fallback.evidence();
        List<RagEvidenceMetadata> evidenceMetadata = fallback.evidenceMetadata();
        if (emptyAnswer) {
            String baseModel = modelUsed == null ? "" : modelUsed.trim();
            int fallbackMarker = baseModel.indexOf(":fallback:");
            if (fallbackMarker >= 0) {
                baseModel = baseModel.substring(0, fallbackMarker);
            }
            modelUsed = (baseModel.isBlank() ? "unknown" : baseModel) + ":fallback:empty-answer";
            evidence = Set.of();
            evidenceMetadata = List.of();
            try {
                TraceStore.put("answer.postprocess.evidenceCleared", true);
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("answer.fallbackEvidenceClearTrace", ignore);
            }
        }
        return ChatResult.of(
                sanitized.content(),
                modelUsed,
                fallback.ragUsed(),
                evidence,
                evidenceMetadata);
    }

    private static void recordLocalLlmOperatorAction(String triggerReason,
                                                     String failureClass,
                                                     String nextAction,
                                                     int actionScore,
                                                     int negativeSignalCount) {
        try {
            int score = Math.max(0, actionScore);
            TraceStore.put("llm.localSmoke.operatorAction.triggered", true);
            TraceStore.put("llm.localSmoke.operatorAction.triggerReason",
                    SafeRedactor.traceLabelOrFallback(triggerReason, "llm_failure"));
            TraceStore.put("llm.localSmoke.operatorAction.failureClass",
                    SafeRedactor.traceLabelOrFallback(failureClass, "model_unavailable"));
            TraceStore.put("llm.localSmoke.operatorAction.nextAction",
                    SafeRedactor.traceLabelOrFallback(nextAction, "inspect_model_route"));
            TraceStore.put("llm.localSmoke.operatorAction.actionScore", score);
            TraceStore.put("llm.localSmoke.operatorAction.scoreDelta", score);
            TraceStore.put("llm.localSmoke.operatorAction.negativeSignalCount", Math.max(1, negativeSignalCount));
        } catch (RuntimeException traceEx) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.operatorActionTrace", traceEx);
        }
    }

    private static List<Content> filterPromptEligibleSelfAsk(List<Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }
        List<Content> out = new ArrayList<>(docs.size());
        int gated = 0;
        int kept = 0;
        int removed = 0;
        for (Content doc : docs) {
            if (!isGrandasManagedPromptCandidate(doc)) {
                out.add(doc);
                kept++;
                continue;
            }
            gated++;
            if (isPromptEligible(doc)) {
                out.add(doc);
                kept++;
            } else {
                removed++;
            }
        }
        try {
            TraceStore.put("selfask.laneGate.promptFilter.gatedCount", gated);
            TraceStore.put("selfask.laneGate.promptFilter.keptCount", kept);
            TraceStore.put("selfask.laneGate.promptFilter.removedCount", removed);
            TraceStore.put("selfask.laneGate.promptEligibleCount", Math.max(0, gated - removed));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("selfask.promptFilterTrace", ignore);
        }
        return removed == 0 ? docs : out;
    }

    private static List<Content> filterOfficialSourcePromptWebDocs(String query, List<Content> docs) {
        if (docs == null || docs.isEmpty() || !strictOfficialSourcePrompt(query)) {
            return docs;
        }
        List<String> domains = officialSourcePromptDomains(query);
        if (domains.isEmpty()) {
            return docs;
        }
        List<Content> out = new ArrayList<>(docs.size());
        int removed = 0;
        for (Content doc : docs) {
            if (sourceMatchesAnyOfficialDomain(needleExtractUrlOrNull(doc), domains)) {
                out.add(doc);
            } else {
                removed++;
            }
        }
        if (out.isEmpty()) {
            traceOfficialPromptFilter(true, 0, removed, "no_official_match_filtered_empty");
            return List.of();
        }
        traceOfficialPromptFilter(removed > 0, out.size(), removed, "filtered_named_official_sources");
        return removed == 0 ? docs : List.copyOf(out);
    }

    private List<Content> filterRequestScopedOfficialPromptWebDocs(
            ChatRequestDto request,
            Map<String, Object> metaHints,
            String query,
            List<Content> docs,
            String stage) {
        List<Content> queryFiltered = filterOfficialSourcePromptWebDocs(query, docs);
        if (queryFiltered == null || queryFiltered.isEmpty()) {
            return queryFiltered;
        }

        boolean requestOfficial = request != null
                && Boolean.TRUE.equals(request.getOfficialSourcesOnly());
        String requestProfile = request == null ? null : request.getDomainProfile();
        Object metaOfficialValue = metaHints == null ? null : metaHints.get("officialOnly");
        boolean metaOfficial = metaOfficialValue instanceof Boolean value
                ? value
                : metaOfficialValue != null
                        && Boolean.parseBoolean(String.valueOf(metaOfficialValue));
        String metaProfile = metaHints == null || metaHints.get("domainProfile") == null
                ? null
                : String.valueOf(metaHints.get("domainProfile"));
        String profile = requestProfile == null || requestProfile.isBlank()
                ? metaProfile
                : requestProfile;
        boolean hardPolicy = requestOfficial || metaOfficial
                || (profile != null && !profile.isBlank());
        if (!hardPolicy) {
            return queryFiltered;
        }
        if (profile == null || profile.isBlank()) {
            profile = "official";
        }

        long started = System.nanoTime();
        int inputCount = queryFiltered.size();
        int urlMissingCount = 0;
        int policyDeniedCount = 0;
        boolean resolverUnavailable = promptDomainProfiles == null;
        List<Content> filtered = new ArrayList<>(inputCount);
        if (!resolverUnavailable) {
            for (Content doc : queryFiltered) {
                String url = needleExtractUrlOrNull(doc);
                if (url == null || url.isBlank()) {
                    urlMissingCount++;
                    continue;
                }
                try {
                    if (promptDomainProfiles.isAllowedByProfile(url, profile)) {
                        filtered.add(doc);
                    } else {
                        policyDeniedCount++;
                    }
                } catch (RuntimeException unavailable) {
                    resolverUnavailable = true;
                    filtered.clear();
                    ChatWorkflowTraceSuppressions.traceSuppressed(
                            "prompt.requestDomainPolicy", unavailable);
                    break;
                }
            }
        }

        String safeStage = "post_compression".equals(stage) ? "post_compression" : "pre_compression";
        int filteredCount = inputCount - filtered.size();
        long elapsedMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        String stagePrefix = "retrieval.integrity.stage." + safeStage + ".";
        TraceStore.put(stagePrefix + "inputCount", inputCount);
        TraceStore.put(stagePrefix + "urlMissingCount", urlMissingCount);
        TraceStore.put(stagePrefix + "policyDeniedCount", policyDeniedCount);
        TraceStore.put(stagePrefix + "filteredCount", filteredCount);
        TraceStore.put(stagePrefix + "finalUsedCount", filtered.size());
        TraceStore.put(stagePrefix + "elapsedMs", elapsedMs);
        TraceStore.put("retrieval.integrity.source", "chat_prompt_web");
        addRetrievalIntegrityCount("retrieval.integrity.inputCount", inputCount);
        addRetrievalIntegrityCount("retrieval.integrity.urlMissingCount", urlMissingCount);
        addRetrievalIntegrityCount("retrieval.integrity.policyDeniedCount", policyDeniedCount);
        addRetrievalIntegrityCount("retrieval.integrity.filteredCount", filteredCount);
        TraceStore.put("retrieval.integrity.finalUsedCount", filtered.size());
        TraceStore.put("retrieval.integrity.fallbackStage", "none");
        TraceStore.put("retrieval.integrity.emptyReason", filtered.isEmpty()
                ? (resolverUnavailable ? "policy_resolver_unavailable" : "hard_policy_filtered_empty")
                : null);
        addRetrievalIntegrityCount("retrieval.integrity.elapsedMs", elapsedMs);
        return filtered.isEmpty() ? List.of() : List.copyOf(filtered);
    }

    private static void addRetrievalIntegrityCount(String key, long delta) {
        long current = Math.max(0L, TraceStore.getLong(key));
        long safeDelta = Math.max(0L, delta);
        TraceStore.put(key, current > Long.MAX_VALUE - safeDelta
                ? Long.MAX_VALUE
                : current + safeDelta);
    }

    private static List<EvidenceAwareGuard.EvidenceDoc> filterOfficialSourceEvidenceDocs(
            String query,
            List<EvidenceAwareGuard.EvidenceDoc> docs) {
        if (docs == null || docs.isEmpty() || !strictOfficialSourcePrompt(query)) {
            return docs;
        }
        List<String> domains = officialSourcePromptDomains(query);
        if (domains.isEmpty()) {
            return docs;
        }
        List<EvidenceAwareGuard.EvidenceDoc> out = new ArrayList<>(docs.size());
        int removed = 0;
        for (EvidenceAwareGuard.EvidenceDoc doc : docs) {
            String source = null;
            if (doc != null) {
                source = doc.url() == null || doc.url().isBlank() ? doc.id() : doc.url();
            }
            if (sourceMatchesAnyOfficialDomain(source, domains)) {
                out.add(doc);
            } else {
                removed++;
            }
        }
        if (out.isEmpty()) {
            traceOfficialEvidenceDocFilter(true, 0, removed, "no_official_match_filtered_empty");
            return List.of();
        }
        traceOfficialEvidenceDocFilter(removed > 0, out.size(), removed, "filtered_named_official_sources");
        return removed == 0 ? docs : List.copyOf(out);
    }

    private static List<RagEvidenceMetadata> filterOfficialSourceEvidenceMetadata(
            String query,
            List<RagEvidenceMetadata> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return evidence;
        }
        if (!strictOfficialSourcePrompt(query)) {
            return prioritizeOfficialOrChangelogEvidenceMetadata(query, evidence);
        }
        List<String> domains = officialSourcePromptDomains(query);
        if (domains.isEmpty()) {
            return prioritizeOfficialOrChangelogEvidenceMetadata(query, evidence);
        }
        List<RagEvidenceMetadata> out = new ArrayList<>(evidence.size());
        int removed = 0;
        for (RagEvidenceMetadata item : evidence) {
            String source = item == null ? null : item.source();
            if (sourceMatchesAnyOfficialDomain(source, domains)) {
                out.add(item);
            } else {
                removed++;
            }
        }
        if (out.isEmpty()) {
            traceOfficialEvidenceMetadataFilter(true, 0, removed, "no_official_match_filtered_empty");
            return List.of();
        }
        traceOfficialEvidenceMetadataFilter(removed > 0, out.size(), removed, "filtered_official_source_metadata");
        List<RagEvidenceMetadata> filtered = removed == 0 ? evidence : List.copyOf(out);
        return prioritizeOfficialOrChangelogEvidenceMetadata(query, filtered);
    }

    static void attachEnsembleCitationSources(
            PromptContext.Builder builder,
            String query,
            List<RagEvidenceMetadata> evidence) {
        if (builder == null) {
            return;
        }
        LinkedHashSet<String> distinctSources = new LinkedHashSet<>();
        if (evidence != null) {
            for (RagEvidenceMetadata item : evidence) {
                String source = canonicalEnsembleCitationSource(item == null ? null : item.source());
                if (source != null) {
                    distinctSources.add(source);
                }
                if (distinctSources.size() >= 12) {
                    break;
                }
            }
        }
        List<String> sourceUrls = List.copyOf(distinctSources);
        // A user-scoped allowlist may constrain retrieval, but it is not evidence that
        // the named domain is vendor/government authoritative. Only repo-recognized
        // named official domains may satisfy CitationGate's official-source lane.
        List<String> officialDomains = namedOfficialPromptDomains(query);
        List<String> officialSources = sourceUrls.stream()
                .filter(source -> sourceMatchesAnyOfficialDomain(source, officialDomains))
                .toList();
        builder.sourceUrls(sourceUrls).officialSources(officialSources);
        TraceStore.put("ensemble.refiner.wiredSourceCount", sourceUrls.size());
        TraceStore.put("ensemble.refiner.wiredOfficialSourceCount", officialSources.size());
    }

    private static String canonicalEnsembleCitationSource(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String path = normalizeEnsembleCitationPath(uri.getRawPath());
            String renderedHost = host.contains(":") ? "[" + host + "]" : host;
            return scheme + "://" + renderedHost + (port < 0 ? "" : ":" + port) + path;
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("ensemble.citationSource", ignore);
            return null;
        }
    }

    private static String normalizeEnsembleCitationPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        String decodedUnreserved = normalizeEnsembleCitationEscapes(rawPath);
        String absolutePath = decodedUnreserved.startsWith("/")
                ? decodedUnreserved
                : "/" + decodedUnreserved;
        String normalizedPath = URI.create("https://canonical.invalid" + absolutePath)
                .normalize()
                .getRawPath();
        return normalizedPath == null || normalizedPath.isBlank() ? "/" : normalizedPath;
    }

    private static String normalizeEnsembleCitationEscapes(String rawPath) {
        StringBuilder normalized = new StringBuilder(rawPath.length());
        for (int i = 0; i < rawPath.length(); i++) {
            char current = rawPath.charAt(i);
            if (current == '%' && i + 2 < rawPath.length()) {
                int high = Character.digit(rawPath.charAt(i + 1), 16);
                int low = Character.digit(rawPath.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    char decoded = (char) ((high << 4) + low);
                    if (isEnsembleCitationUnreserved(decoded)) {
                        normalized.append(decoded);
                    } else {
                        normalized.append('%')
                                .append(Character.toUpperCase(rawPath.charAt(i + 1)))
                                .append(Character.toUpperCase(rawPath.charAt(i + 2)));
                    }
                    i += 2;
                    continue;
                }
            }
            normalized.append(current);
        }
        return normalized.toString();
    }

    private static boolean isEnsembleCitationUnreserved(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    private static List<RagEvidenceMetadata> prioritizeOfficialOrChangelogEvidenceMetadata(
            String query,
            List<RagEvidenceMetadata> evidence) {
        if (evidence == null || evidence.size() < 2 || !officialOrChangelogEvidenceIntent(query)) {
            return evidence;
        }
        List<RagEvidenceMetadata> prioritized = new ArrayList<>(evidence);
        prioritized.sort(Comparator.comparingInt(
                (RagEvidenceMetadata item) -> officialOrChangelogEvidenceScore(item, query)).reversed());
        return List.copyOf(prioritized);
    }

    private static boolean officialOrChangelogEvidenceIntent(String query) {
        String q = sourceEvidenceSearchText(query);
        if (q.isBlank()) {
            return false;
        }
        return containsAnySourceEvidenceTerm(q,
                "official", "official source", "primary source", "\uACF5\uC2DD",
                "changelog", "change log", "release note", "release notes", "release-notes",
                "what's new", "whats new", "latest changes", "\uCD5C\uC2E0 \uBCC0\uACBD");
    }

    private static int officialOrChangelogEvidenceScore(RagEvidenceMetadata item, String query) {
        if (item == null) {
            return 0;
        }
        String q = sourceEvidenceSearchText(query);
        String text = sourceEvidenceSearchText(String.join(" ",
                String.valueOf(item.title()),
                String.valueOf(item.source()),
                String.valueOf(item.kind())));
        boolean wantsOfficial = containsAnySourceEvidenceTerm(q,
                "official", "official source", "primary source", "\uACF5\uC2DD");
        boolean wantsChangelog = containsAnySourceEvidenceTerm(q,
                "changelog", "change log", "release note", "release notes", "release-notes",
                "what's new", "whats new", "latest changes", "\uCD5C\uC2E0 \uBCC0\uACBD");
        int score = 0;
        if (wantsChangelog && containsAnySourceEvidenceTerm(text,
                "changelog", "change log", "release note", "release notes", "release-notes",
                "/releases", "releases/", "what's new", "whats new")) {
            score += 8;
        }
        if (wantsOfficial && containsAnySourceEvidenceTerm(text,
                "official", "documentation", "api reference", "developer", "developers",
                "platform.openai.com/docs", "help.openai.com", "developers.openai.com",
                "learn.microsoft.com", "developer.apple.com", "cloud.google.com",
                "docs.aws.amazon.com", "docs.github.com")) {
            score += 6;
        }
        if (containsAnySourceEvidenceTerm(text,
                "reddit", "stackoverflow", "medium.com", "velog", "tistory",
                "wikipedia", "community", "forum", "personal")) {
            score = Math.min(score - 5, 0);
        }
        return score;
    }

    private static String sourceEvidenceSearchText(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAnySourceEvidenceTerm(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean strictOfficialSourcePrompt(String query) {
        return strictNamedOfficialSourcePrompt(query) || strictExplicitAllowedSourcePrompt(query);
    }

    private static boolean strictNamedOfficialSourcePrompt(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return !q.isBlank()
                && !hasExplicitPromptEvidenceDomain(q)
                && !namedOfficialPromptDomains(q).isEmpty()
                && (q.contains("official source")
                || q.contains("official sources")
                || q.contains("official/external")
                || q.contains("external/official")
                || q.contains("official evidence")
                || looksLikeNamedOfficialFreshnessPrompt(q)
                || looksLikeNamedOfficialSourceDomainPrompt(q));
    }

    private static boolean strictExplicitAllowedSourcePrompt(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (q.isBlank() || explicitPromptEvidenceDomains(q).isEmpty()) {
            return false;
        }
        boolean sourceScoped = q.contains("allowed source")
                || q.contains("allowed sources")
                || q.contains("allowed domain")
                || q.contains("allowed domains")
                || q.contains("\uD5C8\uC6A9 \uCD9C\uCC98")
                || q.contains("\uD5C8\uC6A9 \uB3C4\uBA54\uC778")
                || q.contains("\uCD9C\uCC98\uB294")
                || q.contains("\uB3C4\uBA54\uC778\uC740")
                || q.contains("source domain")
                || q.contains("source domains");
        boolean restrictive = q.contains(" only")
                || q.contains("only.")
                || q.contains("exclude")
                || q.contains("unofficial")
                || q.contains("official")
                || q.contains("\uBFD0")
                || q.contains("\uB9CC")
                || q.contains("\uC81C\uC678")
                || q.contains("\uBE44\uACF5\uC2DD")
                || q.contains("\uACF5\uC2DD");
        return sourceScoped && restrictive;
    }

    private static boolean looksLikeNamedOfficialSourceDomainPrompt(String q) {
        String normalized = q == null ? "" : q.toLowerCase(Locale.ROOT);
        boolean namedOfficialTarget = normalized.contains("openai") || normalized.contains("supabase");
        boolean sourceDomainPrompt = normalized.contains("source domain")
                || normalized.contains("source domains")
                || normalized.contains("\uCD9C\uCC98 \uB3C4\uBA54\uC778")
                || (normalized.contains("\uB3C4\uBA54\uC778")
                && (normalized.contains("\uCD9C\uCC98")
                || normalized.contains("\uADFC\uAC70")
                || normalized.contains("\uAC80\uC99D")));
        return namedOfficialTarget && sourceDomainPrompt;
    }

    private static boolean looksLikeNamedOfficialFreshnessPrompt(String q) {
        String normalized = q == null ? "" : q.toLowerCase(Locale.ROOT);
        boolean hasOpenAi = normalized.contains("openai");
        boolean hasSupabase = normalized.contains("supabase");
        boolean namedOfficialTarget = hasOpenAi || hasSupabase;
        boolean strictComparison = (hasOpenAi && hasSupabase) || normalized.contains("evidence_needed");
        boolean evidenceScoped = normalized.contains("evidence")
                || normalized.contains("evidence_needed")
                || normalized.contains("source")
                || normalized.contains("\uADFC\uAC70")
                || normalized.contains("\uCD9C\uCC98");
        boolean officialOrFresh = normalized.contains("official")
                || normalized.contains("changelog")
                || normalized.contains("release notes")
                || normalized.contains("latest")
                || normalized.contains("current")
                || normalized.contains("\uACF5\uC2DD")
                || normalized.contains("\uCD5C\uC2E0")
                || normalized.contains("\uBCC0\uACBD")
                || normalized.contains("\uC5C5\uB370\uC774\uD2B8");
        return namedOfficialTarget && strictComparison && evidenceScoped && officialOrFresh;
    }

    private static boolean looksLikeOpenAiFreshnessPrompt(String q) {
        String normalized = q == null ? "" : q.toLowerCase(Locale.ROOT);
        return normalized.contains("openai")
                && (normalized.contains("changelog")
                || normalized.contains("release notes")
                || normalized.contains("\uCD5C\uC2E0")
                || normalized.contains("\uBCC0\uACBD")
                || normalized.contains("\uC5C5\uB370\uC774\uD2B8"));
    }

    private static boolean hasExplicitPromptEvidenceDomain(String query) {
        String q = query == null ? "" : query;
        return Pattern.compile("\\b(?:site:)?[a-z0-9][a-z0-9-]*\\.(?:com|org|net|io|ai|dev|co|kr|edu|gov)\\b",
                Pattern.CASE_INSENSITIVE).matcher(q).find();
    }

    private static List<String> namedOfficialPromptDomains(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<String> domains = new ArrayList<>();
        if (q.contains("openai")) {
            domains.add("developers.openai.com");
            if (looksLikeOpenAiFreshnessPrompt(q)) {
                domains.add("openai.com");
            }
        }
        if (q.contains("supabase")) {
            domains.add("supabase.com");
        }
        return domains;
    }

    private static List<String> officialSourcePromptDomains(String query) {
        List<String> explicit = explicitPromptEvidenceDomains(query);
        if (!explicit.isEmpty() && strictExplicitAllowedSourcePrompt(query)) {
            return explicit;
        }
        return namedOfficialPromptDomains(query);
    }

    private static List<String> explicitPromptEvidenceDomains(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            return List.of();
        }
        String scoped = explicitPromptEvidenceDomainScope(q);
        if (scoped.isBlank()) {
            return List.of();
        }
        ArrayList<String> domains = new ArrayList<>();
        java.util.regex.Matcher matcher = Pattern
                .compile("\\b(?:site:)?((?:[a-z0-9][a-z0-9-]*\\.)+[a-z]{2,})\\b",
                        Pattern.CASE_INSENSITIVE)
                .matcher(scoped);
        while (matcher.find()) {
            String domain = matcher.group(1);
            if (domain != null && !domain.isBlank() && !domains.contains(domain)) {
                domains.add(domain);
            }
        }
        return domains.isEmpty() ? List.of() : List.copyOf(domains);
    }

    private static String explicitPromptEvidenceDomainScope(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            return "";
        }
        String[] markers = {
                "allowed sources are",
                "allowed source is",
                "allowed sources:",
                "allowed source:",
                "allowed domains are",
                "allowed domain is",
                "allowed domains:",
                "allowed domain:",
                "허용 출처는",
                "허용 출처:",
                "허용 출처",
                "허용 도메인은",
                "허용 도메인:",
                "허용 도메인"
        };
        int start = -1;
        for (String marker : markers) {
            int idx = q.indexOf(marker);
            if (idx >= 0 && (start < 0 || idx < start)) {
                start = idx + marker.length();
            }
        }
        if (start < 0 || start >= q.length()) {
            return "";
        }
        String scoped = q.substring(start);
        String[] stops = {
                " only",
                " exclude",
                " excluding",
                " unofficial",
                " 비공식",
                " 제외",
                "뿐",
                "만",
                "\r",
                "\n"
        };
        int end = scoped.length();
        for (String stop : stops) {
            int idx = scoped.indexOf(stop);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        return scoped.substring(0, end);
    }

    private static boolean sourceMatchesAnyOfficialDomain(String source, List<String> domains) {
        if (source == null || source.isBlank() || domains == null || domains.isEmpty()) {
            return false;
        }
        String host;
        try {
            host = URI.create(source).getHost();
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.officialSourceFilter.host", ignore);
            return false;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        for (String domain : domains) {
            String normalizedDomain = domain == null ? "" : domain.toLowerCase(Locale.ROOT);
            if (normalizedHost.equals(normalizedDomain) || normalizedHost.endsWith("." + normalizedDomain)) {
                return true;
            }
        }
        return false;
    }

    private static void traceOfficialPromptFilter(boolean applied, int kept, int removed, String reason) {
        try {
            TraceStore.put("prompt.officialSourceFilter.applied", applied);
            TraceStore.put("prompt.officialSourceFilter.keptCount", Math.max(0, kept));
            TraceStore.put("prompt.officialSourceFilter.removedCount", Math.max(0, removed));
            TraceStore.put("prompt.officialSourceFilter.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.officialSourceFilter.trace", ignore);
        }
    }

    private static void traceOfficialEvidenceDocFilter(boolean applied, int kept, int removed, String reason) {
        try {
            TraceStore.put("prompt.officialSourceEvidenceFilter.applied", applied);
            TraceStore.put("prompt.officialSourceEvidenceFilter.keptCount", Math.max(0, kept));
            TraceStore.put("prompt.officialSourceEvidenceFilter.removedCount", Math.max(0, removed));
            TraceStore.put("prompt.officialSourceEvidenceFilter.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.officialSourceEvidenceFilter.trace", ignore);
        }
    }

    private static void traceOfficialEvidenceMetadataFilter(boolean applied, int kept, int removed, String reason) {
        try {
            TraceStore.put("prompt.officialSourceMetadataFilter.applied", applied);
            TraceStore.put("prompt.officialSourceMetadataFilter.keptCount", Math.max(0, kept));
            TraceStore.put("prompt.officialSourceMetadataFilter.removedCount", Math.max(0, removed));
            TraceStore.put("prompt.officialSourceMetadataFilter.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.officialSourceMetadataFilter.trace", ignore);
        }
    }

    private static boolean isGrandasManagedPromptCandidate(Content doc) {
        if (doc == null || doc.textSegment() == null) {
            return false;
        }
        Map<String, Object> metadata = com.example.lms.util.MetadataUtils.toMap(doc.textSegment().metadata());
        if (metadata.containsKey("selfask_lane_gate_enabled")) {
            return true;
        }
        if (metadata.containsKey("grandas_managed")) {
            return true;
        }
        String stage = String.valueOf(metadata.getOrDefault("retrieval_stage", ""));
        return stage.startsWith("selfask") && metadata.containsKey("promptEligible");
    }

    private static boolean isPromptEligible(Content doc) {
        if (doc == null || doc.textSegment() == null) {
            return false;
        }
        Map<String, Object> metadata = com.example.lms.util.MetadataUtils.toMap(doc.textSegment().metadata());
        Object value = metadata.get("promptEligible");
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String safeTraceString(String key) {
        try {
            Object v = TraceStore.get(key);
            return (v == null) ? null : String.valueOf(v);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("trace.safeString", ignore); return null;
        }
    }

    private static String hostOf(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("trace.hostOf", ignore); return null;
        }
    }

    private static String learningDegradedReason(List<LearningSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return "";
        }
        String degradedKind = LearningSignalKind.CONTEXT_DEGRADED.value();
        for (LearningSignal signal : signals) {
            if (signal != null && degradedKind.equals(signal.kind())) {
                return signal.value() == null ? "" : signal.value();
            }
        }
        return "";
    }

    private static Double safeTraceDouble(String key) {
        try {
            Object v = TraceStore.get(key);
            if (v == null) {
                return null;
            }
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            String s = String.valueOf(v).trim();
            return s.isBlank() ? null : Double.valueOf(s);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("trace.safeDouble", ignore); return null;
        }
    }

    private static boolean safeTraceBool(String key) {
        try {
            Object v = TraceStore.get(key);
            if (v == null)
                return false;
            if (v instanceof Boolean b)
                return b;
            return Boolean.parseBoolean(String.valueOf(v));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("trace.safeBool", ignore); return false;
        }
    }

    private boolean isLocalProvider() {
        return "local".equalsIgnoreCase(safeProviderName());
    }

    private String safeProviderName() {
        return llmProvider == null ? "" : llmProvider.trim();
    }

    private String localSafeModelOrNull(String modelId, String stage) {
        if (!isLocalProvider() || modelId == null || modelId.isBlank()) {
            return modelId;
        }
        if (!ModelCapabilities.isRemoteLookingModelId(modelId)) return modelId;
        if (dynamicChatModelFactory != null && dynamicChatModelFactory.canServeQuietly(modelId)) return modelId;
        log.warn("[AWX2AF2][model-policy] ignored {} modelHash={} provider={} reason=local_provider",
                stage, SafeRedactor.hashValue(modelId), safeProviderName());
        try {
            TraceStore.put("llm.model.policy.blocked", true);
            TraceStore.put("llm.model.policy.blocked.stage", stage);
            TraceStore.put("llm.model.policy.blocked.modelHash", SafeRedactor.hashValue(modelId));
            TraceStore.put("llm.model.policy.blocked.provider", safeProviderName());
            TraceStore.put("llm.model.policy.blocked.reason", "local_provider_remote_model");
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.modelPolicyBlockedHelper", ignore); }
        return null;
    }

    private String localFallbackModelId() {
        String fallback = (defaultModel == null || defaultModel.isBlank()) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL : defaultModel.trim();
        return ModelCapabilities.isRemoteLookingModelId(fallback) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL : fallback;
    }

    /**
     * Endpoint-compat failover ladder.
     *
     * <p>
     * Order is controlled by the caller (hint-first). Each endpoint is attempted at
     * most once, and
     * failures are recorded into {@link TraceStore} to avoid "silent" no-output
     * states.
     * </p>
     */
    private String tryEndpointCompatFallback(String modelId,
            List<ChatMessage> msgs,
            ChatRequestDto dto,
            String baseUrlUsed,
            boolean local,
            ChatUsageLedger.ConfiguredCap fallbackCap,
            Consumer<LlmCallSuccess> successSink,
            String... order) {
        ArrayList<String> attempted = new ArrayList<>();
        if (order == null || order.length == 0) {
            return null;
        }

        for (String ep : order) {
            if (ep == null) {
                continue;
            }
            String epl = ep.trim().toLowerCase(java.util.Locale.ROOT);
            if (epl.isBlank()) {
                continue;
            }

            if ("responses".equals(epl) && !openAiFallbackToResponses) {
                continue;
            }
            if ("completions".equals(epl) && !openAiFallbackToCompletions) {
                continue;
            }

            attempted.add(epl);
            try {
                TraceStore.put("llm.endpoint.compat.attempted", String.join("->", attempted));
                TraceStore.put("llm.endpoint.compat.attempt.now", epl);
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatAttemptedTrace", ignore); }

            try {
                if ("responses".equals(epl)) {
                    log.warn("[LLM_ENDPOINT_COMPAT] trying /v1/responses modelHash={}{}",
                            SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                    markCreativeSamplingUnproven("provider-default");
                    String out = callResponsesFallback(modelId, msgs, dto, baseUrlUsed, local, fallbackCap);
                    try {
                        TraceStore.put("llm.endpoint.compat.healedBy", "responses");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatHealedResponses", ignore); }
                    successSink.accept(new LlmCallSuccess(modelId,
                            OpenAiEndpointCompatibility.Endpoint.RESPONSES));
                    log.warn("[LLM_ENDPOINT_COMPAT] healed via /v1/responses modelHash={}{}",
                            SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                    return out;
                }
                if ("completions".equals(epl)) {
                    log.warn("[LLM_ENDPOINT_COMPAT] trying /v1/completions modelHash={}{}",
                            SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                    String out = callCompletionsFallback(modelId, msgs, dto, baseUrlUsed, local);
                    try {
                        TraceStore.put("llm.endpoint.compat.healedBy", "completions");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatHealedCompletions", ignore); }
                    successSink.accept(new LlmCallSuccess(modelId,
                            OpenAiEndpointCompatibility.Endpoint.COMPLETIONS));
                    log.warn("[LLM_ENDPOINT_COMPAT] healed via /v1/completions modelHash={}{}",
                            SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                    return out;
                }
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (LlmFastBailoutException budgetExhausted) {
                throw budgetExhausted;
            } catch (Exception ex) {
                com.example.lms.llm.gateway.LlmResponseTerminalException.rethrowIfPresent(ex);
                rethrowIfRequestBudgetExhausted("endpoint_compat_" + epl, ex);
                ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatAttempt", ex); recordEndpointCompatFailure(epl, modelId, ex);
            }
        }

        try {
            TraceStore.put("llm.endpoint.compat.attempted", String.join("->", attempted));
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatAttemptedFinal", ignore); }
        return null;
    }

    private void recordEndpointCompatFailure(String endpoint, String modelId, Exception ex) {
        try {
            String ep = (endpoint == null ? "unknown" : endpoint);
            String msg = OpenAiEndpointCompatibility.summarizeForLog(ex.getMessage(), 260);
            TraceStore.put("llm.endpoint.compat." + ep + ".errorHash", SafeRedactor.hashValue(msg));
            TraceStore.put("llm.endpoint.compat." + ep + ".errorLength", msg == null ? 0 : msg.length());
            recordModelFailure(modelId, endpointFromString(ep), reasonFromException(ex));

            if (ex instanceof WebClientResponseException w) {
                TraceStore.put("llm.endpoint.compat." + ep + ".status", w.getRawStatusCode());
                String rawBody = w.getResponseBodyAsString();
                TraceStore.put("llm.endpoint.compat." + ep + ".bodyHash", SafeRedactor.hashValue(rawBody));
                TraceStore.put("llm.endpoint.compat." + ep + ".bodyLength", rawBody == null ? 0 : rawBody.length());
                String ra = w.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                if (ra != null && !ra.isBlank()) {
                    TraceStore.put("llm.endpoint.compat." + ep + ".retryAfter", ra);
                }

                if (openAiEndpointCompatDebug || log.isDebugEnabled()) {
                    log.warn("[LLM_ENDPOINT_COMPAT] /v1/{} failed status={} modelHash={} bodyHash={} bodyLength={}{}",
                            ep, w.getRawStatusCode(), SafeRedactor.hashValue(modelId), SafeRedactor.hashValue(rawBody), rawBody == null ? 0 : rawBody.length(), LogCorrelation.suffix());
                } else {
                    log.warn("[LLM_ENDPOINT_COMPAT] /v1/{} failed status={} modelHash={}{}",
                            ep, w.getRawStatusCode(), SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                }
                return;
            }

            log.warn("[LLM_ENDPOINT_COMPAT] /v1/{} failed modelHash={} err={}{}",
                    ep, SafeRedactor.hashValue(modelId), SafeRedactor.safeMessage(msg, 180), LogCorrelation.suffix());
        } catch (Exception ignore) {
            // Do not let diagnostics crash the workflow
            log.warn("[LLM_ENDPOINT_COMPAT] fallback failed modelHash={} ex={}{}",
                    SafeRedactor.hashValue(modelId), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()), LogCorrelation.suffix());
        }
    }

    /**
     * When a selected model is not compatible with /v1/chat/completions, try
     * /v1/completions once
     * to avoid the "silent/empty" failure mode. This is a best-effort compatibility
     * path.
     */
    private String callCompletionsFallback(String modelId, List<ChatMessage> msgs, ChatRequestDto dto,
            String baseUrlUsed, boolean local) throws Exception {
        String base = OpenAiCompatBaseUrl.sanitize(
                (baseUrlUsed != null && !baseUrlUsed.isBlank())
                        ? baseUrlUsed
                        : env.getProperty("llm.base-url-openai",
                                env.getProperty("llm.openai.base-url", "https://api.openai.com/v1")));

        String apiKey = local ? keyResolver.resolveLocalApiKeyStrict() : keyResolver.resolveOpenAiApiKeyStrict();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key for completions fallback (local=" + local + ")");
        }

        String completionsFallbackPrompt = OpenAiEndpointCompatibility.toCompletionsPrompt(msgs);

        Map<String, Object> payload = OpenAiEndpointCompatibility.completionsPayload(
                modelId,
                completionsFallbackPrompt,
                dto != null ? dto.getMaxTokens() : null,
                dto != null ? dto.getTemperature() : null,
                dto != null ? dto.getTopP() : null,
                dto != null ? dto.getFrequencyPenalty() : null,
                dto != null ? dto.getPresencePenalty() : null);

        String url = base + "/completions";

        Duration completionsTimeout = requestBudgetBoundedLlmTimeout(
                TimeUnit.SECONDS.toMillis(Math.max(1, llmTimeoutSeconds)),
                "completions_fallback");
        ChatUsageLedger.ModelAttempt usageAttempt = null;
        if (chatUsageLedger != null) {
            Object appliedCap = payload.get("max_tokens");
            ChatUsageLedger.ConfiguredCap configuredCap = appliedCap instanceof Number number
                    && number.intValue() > 0
                    ? ChatUsageLedger.ConfiguredCap.explicit(
                            null,
                            dto == null ? null : dto.getMaxTokens(),
                            number.intValue(),
                            ChatUsageLedger.ParameterKind.MAX_TOKENS,
                            ChatUsageLedger.CapSource.COMPLETIONS_FALLBACK)
                    : ChatUsageLedger.ConfiguredCap.omitted(
                            null,
                            dto == null ? null : dto.getMaxTokens(),
                            ChatUsageLedger.CapSource.COMPLETIONS_FALLBACK);
            usageAttempt = chatUsageLedger.beginModelInvocation(
                    ChatUsageLedger.ModelPurpose.COMPLETIONS_FALLBACK,
                    configuredCap);
        }
        String json = null;
        boolean requestAttemptStarted = false;
        long requestAttemptStartedNanos = 0L;
        String requestAttemptOutcome = "failed";
        String requestAttemptFailureClass = "provider_error";
        String requestAttemptTerminalClass = "error";
        String requestAttemptResponseText = null;
        try {
            requestAttemptStarted = true;
            requestAttemptStartedNanos = System.nanoTime();
            json = openaiWebClient
                    .post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(completionsTimeout);

            if (json == null || json.isBlank()) {
                if (usageAttempt != null) {
                    usageAttempt.responseReceived(null);
                }
                throw new IllegalStateException("Empty response from /v1/completions fallback");
            }

            JsonNode root = OPENAI_COMPAT_MAPPER.readTree(json);
            if (usageAttempt != null) {
                usageAttempt.responseReceived(providerTokenUsage(root));
            }
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode c0 = choices.get(0);
                String text = c0.path("text").asText(null);
                if (text != null && !text.isBlank()) {
                    if (TimedChatModelCaller.isExpectedFailureResponse(text)) {
                        throw new IllegalStateException("Completions fallback returned an expected-failure route marker");
                    }
                    if (usageAttempt != null) {
                        usageAttempt.markSuccessful();
                    }
                    requestAttemptResponseText = text.trim();
                    requestAttemptOutcome = "success";
                    requestAttemptFailureClass = "none";
                    requestAttemptTerminalClass = "success";
                    return requestAttemptResponseText;
                }

                // Some OpenAI-compatible gateways may still return chat-like payloads.
                String alt = c0.path("message").path("content").asText(null);
                if (alt != null && !alt.isBlank()) {
                    if (TimedChatModelCaller.isExpectedFailureResponse(alt)) {
                        throw new IllegalStateException("Completions fallback returned an expected-failure route marker");
                    }
                    if (usageAttempt != null) {
                        usageAttempt.markSuccessful();
                    }
                    requestAttemptResponseText = alt.trim();
                    requestAttemptOutcome = "success";
                    requestAttemptFailureClass = "none";
                    requestAttemptTerminalClass = "success";
                    return requestAttemptResponseText;
                }
            }

            String err = root.path("error").path("message").asText(null);
            if (err != null && !err.isBlank()) {
                throw new IllegalStateException("Completion fallback failed errorHash=" + SafeRedactor.hashValue(err) + " errorLength=" + err.length());
            }
            throw new IllegalStateException("Unexpected response shape from /v1/completions fallback");
        } catch (CancellationException cancelled) {
            requestAttemptOutcome = "cancelled";
            requestAttemptFailureClass = "cancelled_neutral";
            requestAttemptTerminalClass = "cancelled";
            if (usageAttempt != null) {
                usageAttempt.cancelled();
            }
            throw cancelled;
        } catch (Exception failure) {
            if (usageAttempt != null) {
                if (json == null) {
                    usageAttempt.failedBeforeResponse();
                } else {
                    usageAttempt.responseReceived(null);
                }
            }
            throw failure;
        } finally {
            if (requestAttemptStarted) {
                recordCompletionsFallbackRequestAttempt(
                        modelId,
                        msgs,
                        payload,
                        base,
                        local,
                        completionsTimeout,
                        requestAttemptOutcome,
                        requestAttemptFailureClass,
                        requestAttemptTerminalClass,
                        requestAttemptResponseText,
                        Math.max(0L, (System.nanoTime() - requestAttemptStartedNanos) / 1_000_000L));
            }
        }
    }

    /**
     * /v1/responses fallback for OpenAI "Responses API" or compatible gateways.
     *
     * <p>
     * Best-effort: payload is intentionally minimal, and output parsing accepts
     * multiple
     * response shapes to support OpenAI-compatible servers.
     * </p>
     */
    private String callResponsesFallback(String modelId, List<ChatMessage> msgs, ChatRequestDto dto,
            String baseUrlUsed, boolean local, ChatUsageLedger.ConfiguredCap fallbackCap) throws Exception {

        // Preserve the actual primary model policy, not a possibly larger raw DTO request.
        Integer maxOutputTokens = fallbackCap != null
                && fallbackCap.state() == ChatUsageLedger.CapState.EXPLICIT
                && fallbackCap.configuredCap() != null && fallbackCap.configuredCap() > 0
                        ? fallbackCap.configuredCap() : null;

        String base = OpenAiCompatBaseUrl.sanitize(
                (baseUrlUsed != null && !baseUrlUsed.isBlank())
                        ? baseUrlUsed
                        : env.getProperty("llm.base-url-openai",
                                env.getProperty("llm.openai.base-url", "https://api.openai.com/v1")));

        String apiKey = local ? keyResolver.resolveLocalApiKeyStrict() : keyResolver.resolveOpenAiApiKeyStrict();
        if (apiKey == null || apiKey.isBlank()) {
            recordResponsesFallbackDisabledAttempt(modelId, msgs, dto, base, local, maxOutputTokens);
            throw new IllegalStateException("Missing API key for /v1/responses fallback (local=" + local + ")");
        }

        Duration responsesTimeout = requestBudgetBoundedLlmTimeout(
                TimeUnit.SECONDS.toMillis(Math.max(1, llmTimeoutSeconds)),
                "responses_fallback");
        OpenAiResponsesChatModel responsesModel = new OpenAiResponsesChatModel(
                base,
                apiKey,
                modelId,
                responsesTimeout.toMillis(),
                modelRuntimeHealthTracker, "primary", null, null, maxOutputTokens);
        ChatUsageLedger.ModelAttempt usageAttempt = chatUsageLedger == null
                ? null
                : chatUsageLedger.beginModelInvocation(
                        ChatUsageLedger.ModelPurpose.RESPONSES_FALLBACK,
                        maxOutputTokens == null
                                ? ChatUsageLedger.ConfiguredCap.omitted(
                                        fallbackCap == null ? null : fallbackCap.profileTarget(),
                                        dto == null ? null : dto.getMaxTokens(),
                                        ChatUsageLedger.CapSource.RESPONSES_FALLBACK)
                                : ChatUsageLedger.ConfiguredCap.explicit(
                                        fallbackCap.profileTarget(),
                                        dto == null ? null : dto.getMaxTokens(),
                                        maxOutputTokens,
                                        ChatUsageLedger.ParameterKind.MAX_OUTPUT_TOKENS,
                                        ChatUsageLedger.CapSource.RESPONSES_FALLBACK));
        try {
            dev.langchain4j.model.chat.response.ChatResponse response = responsesModel.chat(msgs);
            if (usageAttempt != null) {
                usageAttempt.responseReceived(response == null ? null : response.tokenUsage());
            }
            dev.langchain4j.data.message.AiMessage ai = response == null ? null : response.aiMessage();
            String out = ai == null ? "" : (ai.text() == null ? "" : ai.text().trim());
            if (out.isBlank()) {
                throw new IllegalStateException("Empty response from /v1/responses fallback");
            }
            if ("(empty responses output)".equals(out)) {
                throw new IllegalStateException("Unusable response shape from /v1/responses fallback");
            }
            if (TimedChatModelCaller.isExpectedFailureResponse(out)) {
                throw new IllegalStateException("Responses fallback failed: "
                        + OpenAiEndpointCompatibility.summarizeForLog(out, 160));
            }
            if (usageAttempt != null) {
                usageAttempt.markSuccessful();
            }
            return out;
        } catch (com.example.lms.llm.gateway.LlmResponseTerminalException terminal) {
            if (usageAttempt != null) usageAttempt.responseReceived(terminal.metadata().tokenUsage());
            throw terminal;
        } catch (CancellationException cancelled) {
            if (usageAttempt != null) {
                usageAttempt.cancelled();
            }
            throw cancelled;
        } catch (Exception failure) {
            if (usageAttempt != null) {
                usageAttempt.failedBeforeResponse();
            }
            throw failure;
        }
    }

    private void recordResponsesFallbackDisabledAttempt(
            String modelId,
            List<ChatMessage> messages,
            ChatRequestDto dto,
            String baseUrl,
            boolean local,
            Integer maxOutputTokens) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            Map<String, Object> ownedOptions = new LinkedHashMap<>();
            ownedOptions.put("temperature", dto == null ? null : dto.getTemperature());
            ownedOptions.put("topP", dto == null ? null : dto.getTopP());
            ownedOptions.put("frequencyPenalty", dto == null ? null : dto.getFrequencyPenalty());
            ownedOptions.put("presencePenalty", dto == null ? null : dto.getPresencePenalty());
            ownedOptions.put("maxOutputTokens", maxOutputTokens);
            ownedOptions.put("timeoutMs", TimeUnit.SECONDS.toMillis(Math.max(1, llmTimeoutSeconds)));
            ownedOptions.put("maxRetries", 0);
            ownedOptions.put("fallbackEnabled", true);
            ownedOptions.put("fallbackKey", "responses");
            Map<String, Object> optionEnvelope = ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                    local ? "local_openai_compatible" : "openai",
                    modelId,
                    "openai_responses",
                    ownedOptions);
            modelRuntimeHealthTracker.expectedFailureAttemptEvidence(
                            "fallback",
                            modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                                    "responses_fallback", modelId, baseUrl, "openai_responses"),
                            optionEnvelope)
                    .record(messages, null);
        } catch (RuntimeException evidenceFailure) {
            ChatWorkflowTraceSuppressions.traceSuppressed(
                    "llm.responsesFallback.requestAttemptEvidence", evidenceFailure);
        }
    }

    private void recordCompletionsFallbackRequestAttempt(
            String modelId,
            List<ChatMessage> messages,
            Map<String, Object> payload,
            String baseUrl,
            boolean local,
            Duration timeout,
            String outcome,
            String failureClass,
            String terminalClass,
            String assistantText,
            long elapsedMs) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        Object rawTimelineId = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId).trim();
        if (timelineId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> ownedOptions = new LinkedHashMap<>();
            ownedOptions.put("temperature", payload == null ? null : payload.get("temperature"));
            ownedOptions.put("topP", payload == null ? null : payload.get("top_p"));
            ownedOptions.put("frequencyPenalty", payload == null ? null : payload.get("frequency_penalty"));
            ownedOptions.put("presencePenalty", payload == null ? null : payload.get("presence_penalty"));
            ownedOptions.put("maxTokens", payload == null ? null : payload.get("max_tokens"));
            ownedOptions.put("timeoutMs", timeout == null ? null : timeout.toMillis());
            ownedOptions.put("maxRetries", 0);
            ownedOptions.put("fallbackEnabled", true);
            ownedOptions.put("fallbackKey", "completions");
            Map<String, Object> optionEnvelope = ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                    local ? "local_openai_compatible" : "openai",
                    modelId,
                    "openai_completions",
                    ownedOptions);
            ModelRuntimeHealthTracker.RequestAttemptAppEvidence appEvidence =
                    modelRuntimeHealthTracker.computeRequestAttemptAppEvidence(
                            messages,
                            optionEnvelope);
            boolean responseObserved = assistantText != null;
            modelRuntimeHealthTracker.recordRequestAttemptEvidence(
                    timelineId,
                    "fallback",
                    modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                            "completions_fallback", modelId, baseUrl, "openai_completions"),
                    outcome,
                    failureClass,
                    terminalClass,
                    elapsedMs,
                    appEvidence.promptHash(),
                    appEvidence.optionsHash(),
                    appEvidence.promptItemCount(),
                    appEvidence.optionItemCount(),
                    responseObserved ? SafeRedactor.hashValue(assistantText) : "hash:unknown",
                    responseObserved ? assistantText.length() : 0,
                    appEvidence.promptUtf8ByteCount(),
                    appEvidence.optionsUtf8ByteCount(),
                    responseObserved ? assistantText.getBytes(StandardCharsets.UTF_8).length : 0,
                    true,
                    false,
                    false,
                    false,
                    false,
                    responseObserved);
        } catch (RuntimeException evidenceFailure) {
            ChatWorkflowTraceSuppressions.traceSuppressed(
                    "llm.completionsFallback.requestAttemptEvidence", evidenceFailure);
        }
    }

    private static dev.langchain4j.model.output.TokenUsage providerTokenUsage(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode usage = root.path("usage");
        if (!usage.isObject()) {
            return null;
        }
        Integer input = nonNegativeInt(usage, "prompt_tokens", "input_tokens");
        Integer output = nonNegativeInt(usage, "completion_tokens", "output_tokens");
        Integer total = nonNegativeInt(usage, "total_tokens");
        if (input == null && output == null && total == null) {
            return null;
        }
        return new dev.langchain4j.model.output.TokenUsage(input, output, total);
    }

    private static Integer nonNegativeInt(JsonNode object, String... fieldNames) {
        if (object == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = object.path(fieldName);
            if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
                return value.intValue();
            }
        }
        return null;
    }

    private void recordModelSuccess(String modelId, OpenAiEndpointCompatibility.Endpoint endpoint) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            modelRuntimeHealthTracker.recordAttemptSuccess(safeProviderName(), modelId, endpoint);
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("modelRuntimeHealth.success", ignore); }
    }

    private void recordModelFailure(String modelId, OpenAiEndpointCompatibility.Endpoint endpoint, String reason) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            modelRuntimeHealthTracker.recordCurrentRequestRouteFailure(modelId, reason);
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("modelRuntimeHealth.failure", ignore); }
    }

    private void recordModelRequestTimelinePhase(String phase, String modelId, String terminalClass) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            Object rawTimelineId = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
            String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId);
            modelRuntimeHealthTracker.recordRequestPhase(
                    timelineId,
                    phase,
                    modelId,
                    null,
                    terminalClass);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("modelRuntimeHealth.requestTimeline", ignore);
        }
    }

    private static OpenAiEndpointCompatibility.Endpoint endpointFromString(String endpoint) {
        if (endpoint == null) {
            return OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS;
        }
        String ep = endpoint.trim().toLowerCase(java.util.Locale.ROOT);
        if ("responses".equals(ep)) {
            return OpenAiEndpointCompatibility.Endpoint.RESPONSES;
        }
        if ("completions".equals(ep)) {
            return OpenAiEndpointCompatibility.Endpoint.COMPLETIONS;
        }
        if ("blocked".equals(ep)) {
            return OpenAiEndpointCompatibility.Endpoint.BLOCKED;
        }
        return OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS;
    }

    private static String reasonFromException(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        if (ex instanceof WebClientResponseException w) {
            return "http_" + w.getRawStatusCode();
        }
        String msg = ex.getMessage();
        if (OpenAiEndpointCompatibility.isChatEndpointMismatchMessage(msg)
                || OpenAiEndpointCompatibility.isCompletionsEndpointMismatchMessage(msg)
                || OpenAiEndpointCompatibility.isResponsesEndpointSuggestionMessage(msg)) {
            return "endpoint_mismatch";
        }
        String simple = ex.getClass().getSimpleName();
        return simple == null || simple.isBlank() ? "unknown" : simple;
    }

    private static dev.langchain4j.exception.ModelNotFoundException unwrapModelNotFound(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof dev.langchain4j.exception.ModelNotFoundException) {
                return (dev.langchain4j.exception.ModelNotFoundException) cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static LlmFastBailoutException unwrapFastBail(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof LlmFastBailoutException) {
                return (LlmFastBailoutException) cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static LlmConfigurationException unwrapLlmConfigurationException(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 12) {
            if (cur instanceof LlmConfigurationException cfg) {
                return cfg;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private void reinforceAssistantAnswerWithProfile(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen,
            VisionMode visionMode,
            GuardProfile guardProfile,
            MemoryMode memoryMode) {
        // [FUTURE_TECH FIX] ?ㅼ젙??OFF硫?ASSISTANT ?듬? ?κ린媛뺥솕 ?먯껜瑜?湲덉?
        if (!enableAssistantReinforcement) {
            return;
        }
        // [FUTURE_TECH FIX] 理쒖떊/誘몄텧???쒗뭹 吏덉쓽??猷⑤㉧/?좎텧 ?듬????κ린 硫붾え由ъ뿉 ?ㅼ뿼?섎뒗 寃껋쓣 諛⑹?
        if (latestTechEnabled && isLatestTechQuery(query)) {
            log.info("[FutureTech] Skipping memory reinforcement to prevent rumor contamination.");
            return;
        }
        if (memoryMode != null && !memoryMode.isWriteEnabled()) {
            log.debug("[MemoryMode] {} -> write disabled, skip reinforcement for sessionHash={}", memoryMode, SafeRedactor.hashValue(String.valueOf(sessionKey)));
            return;
        }
        if (!StringUtils.hasText(answer) || "?뺣낫 ?놁쓬".equals(answer.trim())) {
            return;
        }
        if (visionMode == VisionMode.FREE) {
            // ?쒖꽑2(PRO_FREE) 紐⑤뱶: 硫붾え由?媛뺥솕/??μ쓣 ?섑뻾?섏? ?딆뒿?덈떎.
            return;
        }
        /*
         * 湲곗〈?먮뒗 怨좎젙??媛먯뇿 媛以묒튂(?? 0.18)瑜??곸슜?덉뒿?덈떎. ?댁젣??
         * MLCalibrationUtil???듯빐 ?숈쟻?쇰줈 蹂댁젙??媛믪쓣 ?ъ슜?⑸땲??
         * ?꾩옱 援ы쁽?먯꽌??吏덈Ц 臾몄옄??湲몄씠瑜?嫄곕━ d 濡?媛꾩＜?섏뿬
         * 蹂댁젙媛믪쓣 怨꾩궛?⑸땲?? ?ㅼ젣 ?섍꼍?먯꽌??吏덉쓽??以묒슂?꾨굹 ?ㅻⅨ
         * 嫄곕━ 痢≪젙媛믪쓣 ?낅젰?섏뿬 ?붿슧 ?뺢탳??媛以묒튂瑜??살쓣 ???덉뒿?덈떎.
         */
        double d = (query != null ? query.length() : 0);
        boolean add = true;
        double score = com.example.lms.util.MLCalibrationUtil.finalCorrection(
                d, mlAlpha, mlBeta, mlGamma, mlD0, mlMu, mlLambda, add);

        // ML 蹂댁젙媛믨낵 而⑦뀓?ㅽ듃 ?ㅼ퐫???덉땐(0.5:0.5)
        double normalizedScore = Math.max(0.0, Math.min(1.0, 0.5 * score + 0.5 * contextualScore));

        MemoryGateProfile profile = decideMemoryGateProfile(visionMode, guardProfile);

        try {
            memorySvc.reinforceWithSnippet(sessionKey, query, answer, "ASSISTANT", normalizedScore, profile,
                    memoryMode);
        } catch (Throwable t) {
            log.debug("[Memory] reinforceWithSnippet ?ㅽ뙣: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length()));
        }
    }

    private void reinforceAssistantAnswer(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen,
            MemoryMode memoryMode) {
        // 湲곕낯 寃쎈줈: VisionMode/GuardProfile ?뺣낫瑜??????놁쑝誘濡?
        // 蹂댁닔?곸씤 STRICT / STRICT 議고빀?쇰줈 硫붾え由?寃뚯씠???꾨줈?뚯씪???곸슜?쒕떎.
        reinforceAssistantAnswerWithProfile(sessionKey, query, answer, contextualScore, chosen,
                VisionMode.STRICT, GuardProfile.STRICT, memoryMode);
    }

    // Legacy overload for backward-compatibility (assumes FULL memory mode)
    private void reinforceAssistantAnswer(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen) {
        reinforceAssistantAnswer(sessionKey, query, answer, contextualScore, chosen, MemoryMode.FULL);
    }

    /** ?몄뀡 ???뺢퇋???좏떥 */
    private static String extractSessionKey(ChatRequestDto req) {
        return Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-" + s : s))
                .orElse(UUID.randomUUID().toString());
    }

    // 湲곗〈 ?몄텧遺(3-?몄옄)????섏쐞?명솚???꾪븳 ?ㅻ쾭濡쒕뱶
    private void reinforceAssistantAnswer(String sessionKey, String query, String answer) {
        // 湲곕낯媛? 而⑦뀓?ㅽ듃 ?먯닔 0.5, ?꾨왂 ?뺣낫???꾩쭅 ?놁쑝誘濡?null
        reinforceAssistantAnswer(sessionKey, query, answer, 0.5, null, MemoryMode.FULL);
    }

    /** ?꾩냽 吏덈Ц(?붾줈?? 媛먯?: 留덉?留??듬? 議댁옱 + ?⑦꽩 湲곕컲 */
    private static boolean isFollowUpQuery(String q, String lastAnswer) {
        if (q == null || q.isBlank())
            return false;
        if (lastAnswer == null || lastAnswer.isBlank())
            return false;
        String s = q.toLowerCase(java.util.Locale.ROOT).trim();
        return s.matches("^(더|조금|좀)\\s*자세히(?:\\s*말해줘)?[\\s?!.]*$")
                || s.matches("^자세히\\s*말해줘[\\s?!.]*$")
                || s.matches("^예시(도|를)\\s*들(어|어서)?\\s*줘[\\s?!.]*$")
                || s.matches("^왜\\s+그렇(게|지)[\\s?!.]*$")
                || s.matches("^근거(는|가)\\s*뭐(야|지)[\\s?!.]*$")
                || s.matches("^(tell me more|more details|give me an example|why is that)[\\s?!.]*$");
    }

    /** Called by /api/chat/cancel */
    public void cancelSession(Long sessionId) {
        if (sessionId == null)
            return;
        cancelFlags.computeIfAbsent(sessionId, id -> new AtomicBoolean(false)).set(true);
    }

    private boolean isCancelled(Long sessionId) {
        ChatRunExecutionContext exactRun = ChatRunExecutionContext.current();
        if (exactRun != null) {
            return exactRun.isCancellationRequested();
        }
        AtomicBoolean f = (sessionId == null) ? null : cancelFlags.get(sessionId);
        return f != null && f.get();
    }

    private void clearCancel(Long sessionId) {
        if (ChatRunExecutionContext.current() != null) {
            return;
        }
        if (sessionId != null)
            cancelFlags.remove(sessionId);
    }

    private void throwIfCancelled(Long sessionId) {
        if (isCancelled(sessionId)) {
            clearCancel(sessionId);
            throw new ClientCancellationException("cancelled by client");
        }
        throwIfAutolearnPreempted();
    }

    private static final class ClientCancellationException extends CancellationException {
        private ClientCancellationException(String message) {
            super(message);
        }
    }

    private void throwIfAutolearnPreempted() {
        var ctx = GuardContextHolder.get();
        if (ctx == null || !ctx.planBool("uaw.autolearn", false)) {
            return;
        }
        long deadlineNanos = ctx.planLong("uaw.autolearn.deadlineNanos", 0L);
        if (deadlineNanos > 0L && System.nanoTime() > deadlineNanos) {
            TraceStore.put("uaw.autolearn.preempted", "deadline");
            throw new CancellationException("uaw autolearn deadline exceeded");
        }
        Object supplier = ctx.getPlanOverride("uaw.autolearn.preemptionSupplier");
        if (supplier instanceof java.util.function.BooleanSupplier preempted) {
            boolean abort;
            try {
                abort = preempted.getAsBoolean();
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("uaw.autolearn.preemptionSupplier", ignore); abort = false;
            }
            if (abort) {
                TraceStore.put("uaw.autolearn.preempted", "user_returned");
                throw new CancellationException("uaw autolearn preempted");
            }
        }
    }

    private static String safeTitle(dev.langchain4j.rag.content.Content c) {
        if (c == null)
            return "(?쒕ぉ ?놁쓬)";
        try {
            var seg = c.textSegment();
            if (seg != null) {
                // 1) Prefer explicit metadata title when available
                try {
                    var md = seg.metadata();
                    if (md != null) {
                        String t = md.getString("title");
                        if (t != null && !t.isBlank()) {
                            return truncate(HtmlTextUtil.stripAndCollapse(t), 80);
                        }
                    }
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeTitleMetadata", ignore); }

                // 2) Parse the first line / header format: "[title | provider | url]"
                try {
                    String text = seg.text();
                    if (text != null) {
                        String line1 = text.strip();
                        if (!line1.isEmpty()) {
                            line1 = line1.split("\\r?\\n", 2)[0].strip();
                            if (line1.startsWith("[") && line1.contains("]")) {
                                String inside = line1.substring(1, line1.indexOf(']'));
                                String[] parts = inside.split("\\s*\\|\\s*");
                                if (parts.length > 0 && !parts[0].isBlank()) {
                                    return truncate(HtmlTextUtil.stripAndCollapse(parts[0]), 80);
                                }
                            }
                            // Common web snippet format: "- <a href=...>TITLE</a>: DESC"
                            String aText = HtmlTextUtil.extractAnchorText(line1);
                            if (aText != null && !aText.isBlank()) {
                                return truncate(aText, 80);
                            }
                            return truncate(HtmlTextUtil.stripAndCollapse(line1), 80);
                        }
                    }
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeTitleHeader", ignore); }
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeTitleOuter", ignore); }
        try {
            String s = String.valueOf(c);
            if (s != null && !s.isBlank())
                return truncate(s, 80);
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeTitleFallback", ignore); }
        return "(?쒕ぉ ?놁쓬)";
    }

    private static String safeSnippet(dev.langchain4j.rag.content.Content c) {
        if (c == null)
            return "";
        try {
            var seg = c.textSegment();
            if (seg != null && seg.text() != null) {
                String t = seg.text();
                if (t != null) {
                    String s = t.strip();
                    if (!s.isEmpty()) {
                        // Drop a bracket header line if present
                        String[] lines = s.split("\\r?\\n", 2);
                        if (lines.length == 2) {
                            String first = lines[0].strip();
                            if (first.startsWith("[") && first.contains("]")) {
                                s = lines[1].strip();
                            }
                        }
                        String after = HtmlTextUtil.afterAnchor(s);
                        return truncate(HtmlTextUtil.stripAndCollapse(after), 160);
                    }
                }
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeSnippet", ignore); }
        return "";
    }

    private static String composeOpenAiResponsesWebSearchFieldAnswer(
            String query,
            java.util.List<dev.langchain4j.rag.content.Content> webDocs,
            java.util.List<RagEvidenceMetadata> citableEvidence) {
        if (!asksOpenAiResponsesWebSearchFieldValues(query)) {
            return null;
        }

        String missingReason = null;
        ExactFieldValue toolType = null;
        ExactFieldValue model = null;
        if (webDocs == null || webDocs.isEmpty() || citableEvidence == null || citableEvidence.isEmpty()) {
            missingReason = "no_citable_evidence";
        } else {
            toolType = findExactFieldValue(webDocs, citableEvidence,
                    EXACT_FIELD_TYPE_PATTERN, true);
            model = findExactFieldValue(webDocs, citableEvidence,
                    EXACT_FIELD_MODEL_PATTERN, false);
            if (toolType == null && model == null) {
                missingReason = "missing_exact_fields";
            }
        }

        try {
            TraceStore.put("answer.exactFieldValue.requested", true);
            TraceStore.put("answer.exactFieldValue.toolTypeFound", toolType != null);
            TraceStore.put("answer.exactFieldValue.modelFound", model != null);
            if (missingReason != null) {
                TraceStore.put("answer.exactFieldValue.missingReason", missingReason);
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("answer.exactFieldValue.trace", ignore);
        }

        StringBuilder out = new StringBuilder(240);
        out.append("### \uADFC\uAC70 \uAE30\uBC18 \uD544\uB4DC\uAC12\n");
        out.append("- tool type: ");
        out.append(toolType == null
                ? "evidence_needed (citable evidence\uC5D0\uC11C `type` \uAC12\uC744 \uD655\uC778\uD558\uC9C0 \uBABB\uD568)"
                : "`" + toolType.value() + "` [" + toolType.marker() + "]");
        out.append('\n');
        out.append("- example model: ");
        out.append(model == null
                ? "evidence_needed (citable evidence\uC5D0\uC11C `model` \uAC12\uC744 \uD655\uC778\uD558\uC9C0 \uBABB\uD568)"
                : "`" + model.value() + "` [" + model.marker() + "]");
        return out.toString();
    }

    private static boolean asksOpenAiResponsesWebSearchFieldValues(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean openAiResponses = text.contains("openai") && text.contains("responses");
        boolean webSearch = text.contains("web search") || text.contains("web_search")
                || (text.contains("\uC6F9") && text.contains("\uAC80\uC0C9"));
        boolean fieldValues = text.contains("tool type") || text.contains("tool_type")
                || text.contains("type\uACFC") || text.contains("type\uC744")
                || text.contains("model value") || text.contains("model \uAC12")
                || text.contains("\uBAA8\uB378\uAC12") || text.contains("\uB3C4\uAD6C");
        return openAiResponses && webSearch && fieldValues;
    }

    private static ExactFieldValue findExactFieldValue(
            java.util.List<dev.langchain4j.rag.content.Content> webDocs,
            java.util.List<RagEvidenceMetadata> citableEvidence,
            Pattern pattern,
            boolean requireWebSearchType) {
        if (webDocs == null || citableEvidence == null || pattern == null) {
            return null;
        }
        for (int i = 0; i < webDocs.size(); i++) {
            dev.langchain4j.rag.content.Content doc = webDocs.get(i);
            String marker = citableWebMarkerForContent(citableEvidence, doc, i + 1);
            if (marker == null || marker.isBlank()) {
                continue;
            }
            String text = contentTextForExactFields(doc, 6_000);
            if (text == null || text.isBlank()) {
                continue;
            }
            java.util.regex.Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String value = firstNonBlankGroup(matcher);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String normalized = value.trim().replaceAll("[`\"',;)}\\]]+$", "");
                if (normalized.isBlank()) {
                    continue;
                }
                if (requireWebSearchType && !normalized.toLowerCase(java.util.Locale.ROOT).startsWith("web_search")) {
                    continue;
                }
                return new ExactFieldValue(normalized, marker);
            }
        }
        return null;
    }

    private static String citableWebMarkerForContent(
            java.util.List<RagEvidenceMetadata> citableEvidence,
            dev.langchain4j.rag.content.Content content,
            int rank) {
        String byRank = citableWebMarkerForRank(citableEvidence, rank);
        if (byRank != null && !byRank.isBlank()) {
            return byRank;
        }
        String contentUrlKey = publicUrlKey(needleExtractUrlOrNull(content));
        if (contentUrlKey == null || contentUrlKey.isBlank() || citableEvidence == null) {
            return null;
        }
        for (RagEvidenceMetadata item : citableEvidence) {
            if (item == null || item.marker() == null || item.marker().isBlank()) {
                continue;
            }
            if (!"WEB".equalsIgnoreCase(String.valueOf(item.kind()))) {
                continue;
            }
            String evidenceUrlKey = publicUrlKey(item.source());
            if (contentUrlKey.equals(evidenceUrlKey)) {
                return item.marker();
            }
        }
        return null;
    }

    private static String citableWebMarkerForRank(
            java.util.List<RagEvidenceMetadata> citableEvidence,
            int rank) {
        if (citableEvidence == null || rank <= 0) {
            return null;
        }
        for (RagEvidenceMetadata item : citableEvidence) {
            if (item == null || item.marker() == null || item.marker().isBlank()) {
                continue;
            }
            if (!"WEB".equalsIgnoreCase(String.valueOf(item.kind()))) {
                continue;
            }
            if (item.rank() != null && item.rank() == rank) {
                return item.marker();
            }
        }
        return null;
    }

    private static String publicUrlKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            return scheme.toLowerCase(java.util.Locale.ROOT)
                    + "://"
                    + host.toLowerCase(java.util.Locale.ROOT)
                    + path;
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("answer.exactFieldValue.urlKey", ignore);
            return null;
        }
    }

    private static String contentTextForExactFields(
            dev.langchain4j.rag.content.Content content,
            int maxChars) {
        if (content == null) {
            return "";
        }
        try {
            var segment = content.textSegment();
            String text = segment == null ? "" : segment.text();
            if (text == null || text.isBlank()) {
                return "";
            }
            int limit = Math.max(0, maxChars);
            return limit > 0 && text.length() > limit ? text.substring(0, limit) : text;
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("content.exactFieldText", ignore);
            return "";
        }
    }

    private static String firstNonBlankGroup(java.util.regex.Matcher matcher) {
        if (matcher == null) {
            return "";
        }
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String value = matcher.group(i);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record ExactFieldValue(String value, String marker) {
    }

    /**
     * Build a compact hint string from web and vector evidences so that
     * high-tier regeneration models cannot ignore retrieved context.
     * This is intentionally short to stay within token budgets.
     */
    private String buildEvidenceHint(
            java.util.List<dev.langchain4j.rag.content.Content> web,
            java.util.List<dev.langchain4j.rag.content.Content> vector) {

        StringBuilder sb = new StringBuilder();

        if (web != null && !web.isEmpty()) {
            sb.append("??寃??寃곌낵:\n");
            int limit = Math.min(5, web.size());
            for (int i = 0; i < limit; i++) {
                sb.append("[W").append(i + 1).append("] ")
                        .append(safeSnippet(web.get(i)))
                        .append("\n");
            }
        }

        if (vector != null && !vector.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("踰≫꽣 寃??寃곌낵:\n");
            int limit = Math.min(3, vector.size());
            for (int i = 0; i < limit; i++) {
                sb.append("[V").append(i + 1).append("] ")
                        .append(safeSnippet(vector.get(i)))
                        .append("\n");
            }
        }

        return sb.toString();
    }

    // ??硫붿꽌?? LLM ?놁씠??珥덉븞??留뚮뱾 ???덈뒗 ?덉쟾???泥?(媛꾨떒 ?대━?ㅽ떛/?뱀뀡 ?쒗뵆由?

    /**
     * (UAW: Bypass Routing) LLM ?앹꽦 ?ㅽ뙣/?ㅽ뵂 ?? 利앷굅媛 ?덉쑝硫?deterministic composer濡??고쉶.
     */
    private String composeEvidenceFallback(String query,
            java.util.List<Content> topDocs,
            java.util.List<Content> vectorDocs,
            java.util.List<dev.langchain4j.data.document.Document> localDocs,
            AgentVisibleDebugEvidenceBuilder.Snapshot agentDebugSnapshot,
            String recentHistory,
            boolean lowRisk) {
        String currentTurnMemoryFallback = composeCurrentTurnMemoryFallback(query);
        if (currentTurnMemoryFallback != null && !currentTurnMemoryFallback.isBlank()) {
            return currentTurnMemoryFallback;
        }

        String recentHistoryFallback = composeRecentHistoryFallback(query, recentHistory);
        if (recentHistoryFallback != null && !recentHistoryFallback.isBlank()) {
            return recentHistoryFallback;
        }

        boolean agentDebugFallbackRequested = AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(query);
        if (agentDebugFallbackRequested && isEmptyEvidence(topDocs) && isEmptyEvidence(vectorDocs)) {
            String agentDebugFallback = composeAgentVisibleDebugFallback(query, agentDebugSnapshot);
            if (agentDebugFallback != null && !agentDebugFallback.isBlank()) {
                return agentDebugFallback;
            }
        }

        var rescueDocs = new java.util.ArrayList<EvidenceAwareGuard.EvidenceDoc>();
        int idx = 1;

        if (topDocs != null) {
            for (var c : topDocs) {
                String url = extractUrlOrFallback(c, idx, false);
                String title = safeTitle(c);
                String snippet = safeSnippet(c);
                rescueDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, title, snippet));
                if (idx++ >= 6)
                    break;
            }
        }
        if (vectorDocs != null) {
            for (var c : vectorDocs) {
                String url = extractUrlOrFallback(c, idx, true);
                String title = safeTitle(c);
                String snippet = safeSnippet(c);
                rescueDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, title, snippet));
                if (idx++ >= 10)
                    break;
            }
        }
        if (agentDebugFallbackRequested && localDocs != null) {
            for (var d : localDocs) {
                String snippet = d == null ? "" : SafeRedactor.safeMessage(d.text(), 900);
                if (snippet == null || snippet.isBlank()) {
                    continue;
                }
                rescueDocs.add(new EvidenceAwareGuard.EvidenceDoc(
                        "local://agent-visible-debug/" + idx,
                        "Agent visible debug heartbeat",
                        snippet));
                if (idx++ >= 12)
                    break;
            }
        }

        if (rescueDocs.isEmpty()) {
            return "?쇱떆?곸쑝濡??듬????앹꽦?????놁뒿?덈떎. ?좎떆 ???ㅼ떆 ?쒕룄?댁＜?몄슂.";
        }

        try {
            return evidenceAnswerComposer.compose(query, rescueDocs, lowRisk);
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalRescue.compose", e); return evidenceAwareGuard.degradeToEvidenceList(rescueDocs);
        }
    }

    private static boolean isEmptyEvidence(java.util.List<Content> docs) {
        return docs == null || docs.isEmpty();
    }

    static String composeDirectLiteralAnswerFallback(String query) {
        String value = directLiteralAnswerValue(query);
        if (value == null || value.isBlank()) {
            return null;
        }
        String safeValue = SafeRedactor.safeMessage(value, 80);
        if (safeValue == null || safeValue.isBlank()) {
            return null;
        }
        try {
            TraceStore.put("chat.directLiteralFallback.used", true);
            TraceStore.put("chat.directLiteralFallback.valueHash", SafeRedactor.hash12(value));
            TraceStore.put("chat.directLiteralFallback.valueLength", value.length());
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("chat.directLiteralFallback.trace", ex);
        }
        return safeValue;
    }

    public static boolean isDirectLiteralAnswerRequest(String query) {
        String value = directLiteralAnswerValue(query);
        return value != null && !value.isBlank();
    }

    private static String directLiteralAnswerValue(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String text = query.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (hasMaterialFollowUpTaskAfterDirectLiteralClause(text)) {
            return null;
        }
        if (isConditionalEvidenceNeededFallbackInstruction(lower, text)) {
            return null;
        }
        if (isConditionalRetrievalQualityInstruction(lower, text)) {
            return null;
        }
        if (isStructuredCandidateOutputInstruction(lower, text)) {
            return null;
        }
        String multiplicationLiteral = directNumberOnlyMultiplicationLiteral(text);
        if (multiplicationLiteral != null) {
            return multiplicationLiteral;
        }
        String countedKoreanLiteral = directKoreanCharacterCountLiteral(text);
        if (countedKoreanLiteral != null) {
            return countedKoreanLiteral;
        }
        boolean answerDirective = hasDirectLiteralAnswerDirective(lower, text);
        if (answerDirective) {
            String postposedOnlyLiteral = directKoreanPostposedOnlyLiteral(text);
            if (postposedOnlyLiteral != null) {
                return postposedOnlyLiteral;
            }
            String quotedAnswerLiteral = directKoreanQuotedAnswerLiteral(text);
            if (quotedAnswerLiteral != null) {
                return quotedAnswerLiteral;
            }
            String tokenOnlyLiteral = directKoreanTokenOnlyLiteral(text);
            if (tokenOnlyLiteral != null) {
                return tokenOnlyLiteral;
            }
            String namedTokenOnlyLiteral = directKoreanNamedMachineTokenOnlyLiteral(text);
            if (namedTokenOnlyLiteral != null) {
                return namedTokenOnlyLiteral;
            }
            String oneWordOnlyLiteral = directKoreanOneWordOnlyLiteral(text);
            if (oneWordOnlyLiteral != null) {
                return oneWordOnlyLiteral;
            }
        }
        boolean exactMarker = lower.contains("exactly") || text.contains("\uC815\uD655\uD788");
        boolean boundedDirective = lower.contains(" only")
                || text.contains("\uB9CC")
                || text.contains("\uB2E8\uC5B4");
        if (!exactMarker || !answerDirective || !boundedDirective) {
            return null;
        }
        int start = lower.indexOf("exactly");
        int markerLength = "exactly".length();
        int koreanStart = text.indexOf("\uC815\uD655\uD788");
        if (koreanStart >= 0 && (start < 0 || koreanStart < start)) {
            start = koreanStart;
            markerLength = "\uC815\uD655\uD788".length();
        }
        if (start < 0) {
            return null;
        }
        String markerTail = text.substring(start + markerLength);
        if (startsWithKoreanOneWordFormat(markerTail)
                && isRecentHistoryQuestion(text)) {
            return null;
        }
        if (startsWithKoreanOutputShapeDirective(markerTail)) {
            return null;
        }
        String candidate = directLiteralCandidateAfterMarker(text, start, markerLength);
        if (candidate != null) {
            if (isRecentHistoryLabelOnlyLiteralCandidate(candidate, text)) {
                return null;
            }
            return candidate;
        }
        candidate = directLiteralCandidateBeforeMarker(text, start);
        if (isRecentHistoryLabelOnlyLiteralCandidate(candidate, text)) {
            return null;
        }
        return candidate;
    }

    private static boolean hasMaterialFollowUpTaskAfterDirectLiteralClause(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        java.util.regex.Matcher connector = Pattern.compile(
                "(?iu)(?:^|[\\s,;.!?\\u3002\\uFF0C\\uFF1B\\uFF01\\uFF1F])"
                        + "(?:\\uADF8\\uB9AC\\uACE0|\\uC774\\uC5B4\\uC11C|\\uB610\\uD55C|"
                        + "\\uCD94\\uAC00\\uB85C|\\uB3D9\\uC2DC\\uC5D0|and(?:\\s+then)?|then|also|additionally)"
                        + "(?:[\\s,:;.!?\\u3002\\uFF0C\\uFF1B\\uFF01\\uFF1F]|$)")
                .matcher(text);
        while (connector.find()) {
            String tail = text.substring(connector.end());
            boolean englishTask = Pattern.compile(
                    "(?iu)\\b(?:explain|compare|analy[sz]e|summari[sz]e|evaluate|justify|"
                            + "translate|calculate|search|verify|investigate)\\b")
                    .matcher(tail)
                    .find();
            boolean koreanTask = Pattern.compile(
                    "(?iu)(?:\\uC124\\uBA85|\\uBE44\\uAD50|\\uBD84\\uC11D|\\uC694\\uC57D|"
                            + "\\uD3C9\\uAC00|\\uAC80\\uD1A0|\\uBC88\\uC5ED|\\uACC4\\uC0B0|"
                            + "\\uAC80\\uC0C9|\\uD655\\uC778)(?:\\uD558|\\uD574)")
                    .matcher(tail)
                    .find();
            if (englishTask || koreanTask) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStructuredCandidateOutputInstruction(String lower, String text) {
        String normalizedLower = lower == null ? "" : lower;
        String raw = text == null ? "" : text;
        boolean candidateNoun = normalizedLower.contains("candidate")
                || normalizedLower.contains("option")
                || normalizedLower.contains("alternative")
                || raw.contains("\uD6C4\uBCF4")
                || raw.contains("\uB300\uC548");
        if (!candidateNoun) {
            return false;
        }
        int markerStart = normalizedLower.indexOf("exactly");
        int markerLength = "exactly".length();
        int koreanStart = raw.indexOf("\uC815\uD655\uD788");
        if (koreanStart >= 0 && (markerStart < 0 || koreanStart < markerStart)) {
            markerStart = koreanStart;
            markerLength = "\uC815\uD655\uD788".length();
        }
        if (markerStart < 0) {
            return false;
        }
        String instruction = raw.substring(markerStart + markerLength);
        int instructionEnd = instruction.length();
        for (char boundary : new char[] {
                '.', '!', '?', ';', '\n', '\r', '\u3002', '\uFF01', '\uFF1F', '\uFF1B'
        }) {
            int boundaryIndex = instruction.indexOf(boundary);
            if (boundaryIndex >= 0) {
                instructionEnd = Math.min(instructionEnd, boundaryIndex);
            }
        }
        instruction = instruction.substring(0, instructionEnd);
        String normalizedInstruction = instruction.toLowerCase(Locale.ROOT);
        return normalizedInstruction.contains("compare")
                || normalizedInstruction.contains("evaluate")
                || normalizedInstruction.contains("explain")
                || normalizedInstruction.contains("evidence")
                || normalizedInstruction.contains("reason")
                || normalizedInstruction.contains("counterexample")
                || normalizedInstruction.contains("rank")
                || normalizedInstruction.contains("choose")
                || instruction.contains("\uBE44\uAD50")
                || instruction.contains("\uD3C9\uAC00")
                || instruction.contains("\uC124\uBA85")
                || instruction.contains("\uADFC\uAC70")
                || instruction.contains("\uBC18\uB840")
                || instruction.contains("\uC2EC\uD310")
                || instruction.contains("\uC21C\uC704")
                || instruction.contains("\uC120\uD0DD");
    }

    private static boolean isConditionalEvidenceNeededFallbackInstruction(String lower, String text) {
        if (lower == null || !lower.contains("evidence_needed")) {
            return false;
        }
        String raw = text == null ? "" : text;
        boolean uncertaintyScoped = lower.contains("uncertain")
                || lower.contains("unsure")
                || lower.contains("not sure")
                || raw.contains("\uBD88\uD655\uC2E4");
        boolean conditional = lower.contains("if ")
                || lower.contains("not visible")
                || lower.contains("not available")
                || lower.contains("not found")
                || lower.contains("missing")
                || uncertaintyScoped
                || raw.contains("\uC5C6\uC73C\uBA74")
                || raw.contains("\uC5C6\uB2E4\uBA74")
                || raw.contains("\uC5C6\uC744")
                || raw.contains("\uBCF4\uC774\uC9C0")
                || raw.contains("\uD655\uC778\uD558\uC9C0 \uBABB")
                || raw.contains("\uCD94\uCE21\uD558\uC9C0");
        if (!conditional) {
            return false;
        }
        return uncertaintyScoped
                || lower.contains("source marker")
                || lower.contains("source markers")
                || lower.contains("citation")
                || lower.contains("citable evidence")
                || lower.contains("visible in evidence")
                || raw.contains("\uADFC\uAC70")
                || raw.contains("\uCD9C\uCC98")
                || raw.contains("\uC99D\uAC70");
    }

    private static boolean isConditionalRetrievalQualityInstruction(String lower, String text) {
        String safeLower = String.valueOf(lower);
        String raw = text == null ? "" : text;
        boolean retrievalScoped = safeLower.contains("rag")
                || safeLower.contains("web_search")
                || safeLower.contains("web search")
                || safeLower.contains("search")
                || safeLower.contains("site:")
                || safeLower.contains("evidence")
                || safeLower.contains("source")
                || raw.contains("\uAC80\uC0C9")
                || raw.contains("\uADFC\uAC70")
                || raw.contains("\uCD9C\uCC98")
                || raw.contains("\uC99D\uAC70")
                || raw.contains("\uB3C4\uBA54\uC778");
        if (!retrievalScoped) {
            return false;
        }
        boolean evidenceScoped = safeLower.contains("evidence")
                || safeLower.contains("citation")
                || safeLower.contains("source")
                || raw.contains("\uADFC\uAC70")
                || raw.contains("\uCD9C\uCC98")
                || raw.contains("\uC99D\uAC70");
        boolean domainScoped = safeLower.contains("site:")
                || safeLower.contains("http://")
                || safeLower.contains("https://")
                || safeLower.matches(".*\\b[a-z0-9][a-z0-9-]*\\.(com|org|net|io|ai|dev|co|kr|edu|gov)\\b.*")
                || safeLower.contains("official doc")
                || safeLower.contains("official source")
                || safeLower.contains("official sources")
                || safeLower.contains("official/external")
                || safeLower.contains("external/official")
                || safeLower.contains("official evidence")
                || safeLower.contains("official url")
                || safeLower.contains("official urls")
                || raw.contains("\uACF5\uC2DD \uBB38\uC11C");
        if (evidenceScoped && domainScoped) {
            return true;
        }
        boolean evidenceAttachmentProbe = (safeLower.contains("evidence")
                || safeLower.contains("citation")
                || raw.contains("\uADFC\uAC70")
                || raw.contains("\uCD9C\uCC98")
                || raw.contains("\uC99D\uAC70"))
                && (safeLower.contains("attach")
                || safeLower.contains("attached")
                || safeLower.contains("citable")
                || raw.contains("\uBD99\uB294\uC9C0")
                || raw.contains("\uBD99\uC5C8\uB294\uC9C0")
                || raw.contains("\uBD99\uC5B4")
                || raw.contains("\uAC80\uC99D")
                || raw.contains("\uD655\uC778\uD574")
                || raw.contains("\uD655\uC778\uD558\uB294"));
        if (evidenceAttachmentProbe) {
            return true;
        }
        boolean conditional = safeLower.contains("if ")
                || safeLower.contains("otherwise")
                || safeLower.contains("other domain")
                || raw.contains("\uC774\uBA74")
                || raw.contains("\uB77C\uBA74")
                || raw.contains("\uC5C6\uC73C\uBA74")
                || raw.contains("\uC5C6\uB2E4\uBA74")
                || raw.contains("\uB2E4\uB978")
                || raw.contains("\uC544\uB2C8\uBA74")
                || raw.contains("\uACBD\uC6B0");
        if (!conditional) {
            return false;
        }
        return safeLower.contains("say ")
                || safeLower.contains("tell ")
                || safeLower.contains("report ")
                || raw.contains("\uB9D0\uD574")
                || raw.contains("\uB2F5\uD574")
                || raw.contains("\uB2F5\uBCC0")
                || raw.contains("\uC368\uC918")
                || raw.contains("\uBB38\uC81C\uB77C\uACE0");
    }

    private static String directLiteralCandidateAfterMarker(String text, int start, int markerLength) {
        String candidate = text.substring(start + markerLength).trim();
        candidate = stripLiteralCandidateEdges(candidate);
        int end = firstLiteralCandidateDelimiter(candidate);
        if (end >= 0) {
            candidate = candidate.substring(0, end).trim();
        }
        candidate = stripLiteralCandidateEdges(candidate);
        if (isDirectLiteralDirectiveToken(candidate) || !isSafeDirectLiteralCandidate(candidate)) {
            return null;
        }
        return candidate;
    }

    private static boolean isRecentHistoryLabelOnlyLiteralCandidate(String candidate, String query) {
        if (candidate == null || candidate.isBlank() || query == null || query.isBlank()) {
            return false;
        }
        String value = stripLiteralCandidateEdges(stripKoreanLiteralCandidateSuffix(candidate));
        if (value.isBlank() || isSafeMachineTokenLiteralCandidate(value)) {
            return false;
        }
        return isRecentHistoryQuestion(query)
                && (requestedValueLabel(query) != null || asksForRememberedValues(query) || containsHangul(value));
    }

    private static String directLiteralCandidateBeforeMarker(String text, int markerStart) {
        String prefix = stripLiteralCandidateEdges(text.substring(0, markerStart));
        if (prefix.isBlank()) {
            return null;
        }
        int boundary = lastLiteralCandidateBoundary(prefix);
        String rawCandidate = boundary >= 0 ? prefix.substring(boundary + 1) : prefix;
        String candidate = rawCandidate;
        candidate = stripLiteralCandidateEdges(stripKoreanLiteralCandidateSuffix(candidate));
        if (isBareGenericDirectLiteralOutputLabel(rawCandidate, candidate)) {
            return null;
        }
        return isSafeDirectLiteralCandidate(candidate) ? candidate : null;
    }

    private static boolean isBareGenericDirectLiteralOutputLabel(String rawCandidate, String candidate) {
        if (rawCandidate == null || candidate == null) {
            return false;
        }
        String raw = rawCandidate.trim();
        if (!raw.matches("(?iu)[a-z]+\uB9CC")) {
            return false;
        }
        return DIRECT_LITERAL_GENERIC_OUTPUT_LABELS.contains(candidate.toLowerCase(Locale.ROOT));
    }

    private static String directKoreanPostposedOnlyLiteral(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern
                .compile("(?iu)(?:\\uC774\\uB77C\\uACE0|\\uB77C\\uACE0)\\uB9CC")
                .matcher(text);
        while (matcher.find()) {
            String candidate = directLiteralCandidateBeforeMarker(text, matcher.start());
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String directKoreanQuotedAnswerLiteral(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern
                .compile("(?iu)(?:^|[\\s:\\uFF1A\\\"'`\\u201C\\u201D\\u2018\\u2019\\[\\](){}<>])([\\p{L}\\p{N}][\\p{L}\\p{N}._:-]{0,79})\\s*(?:\\uC774\\uB77C\\uACE0|\\uB77C\\uACE0)\\s*(?:\\uB2F5\\uD574|\\uB2F5\\uBCC0|\\uB300\\uB2F5|\\uB9D0\\uD574|\\uCD9C\\uB825|\\uC368)(?:\\uC918|\\uC8FC|\\uD574)?")
                .matcher(text);
        while (matcher.find()) {
            String candidate = stripLiteralCandidateEdges(matcher.group(1));
            if (isDirectLiteralDirectiveToken(candidate) || isRecentHistoryLabelOnlyLiteralCandidate(candidate, text)) {
                continue;
            }
            if (isSafeDirectLiteralCandidate(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String directKoreanTokenOnlyLiteral(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern
                .compile("(?iu)(?:\\uD1A0\\uD070|\\uCF54\\uB4DC(?:\\s*\\uC774\\uB984)?)\\s*\\uB9CC")
                .matcher(text);
        while (matcher.find()) {
            String candidate = directLiteralCandidateBeforeMarker(text, matcher.start());
            if (isSafeMachineTokenLiteralCandidate(candidate)) {
                return candidate;
            }
            candidate = lastMachineTokenLiteralBeforeMarker(text, matcher.start());
            if (isSafeMachineTokenLiteralCandidate(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String directKoreanNamedMachineTokenOnlyLiteral(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern
                .compile("(?i)(?:\\bASCII\\s+)?(?:\\uD1A0\\uD070|\\uCF54\\uB4DC(?:\\s*\\uC774\\uB984)?)\\s+"
                        + "([A-Za-z0-9][A-Za-z0-9._:-]{1,79})\\s*(?:\\uD558\\uB098)?\\uB9CC\\s*"
                        + "(?:\\uB2F5\\uBCC0|\\uB300\\uB2F5|\\uB2F5\\uD574|\\uC54C\\uB824|\\uB9D0\\uD574|\\uCD9C\\uB825|\\uC368|\\uBCF4\\uC5EC)"
                        + "(?:\\uD558\\uB77C|\\uD574\\uB77C|\\uD558\\uC138\\uC694|\\uD574(?:\\s*(?:\\uC918|\\uC8FC\\uC138\\uC694))?|\\uC918|\\uC8FC\\uC138\\uC694)?"
                        + "\\s*[.!?]?\\s*$")
                .matcher(text);
        while (matcher.find()) {
            String candidate = stripLiteralCandidateEdges(matcher.group(1));
            if (hasNegatedOrMetaNamedMachineTokenPrefix(text, matcher.start())) {
                continue;
            }
            if (candidate.matches("[A-Za-z0-9][A-Za-z0-9._:-]{1,79}")
                    && isSafeMachineTokenLiteralCandidate(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasNegatedOrMetaNamedMachineTokenPrefix(String text, int markerStart) {
        if (text == null || text.isBlank() || markerStart <= 0) {
            return false;
        }
        String prefix = text.substring(0, Math.min(markerStart, text.length())).trim();
        java.util.regex.Matcher safeContrastiveOutputExclusion = Pattern.compile(
                "(?iu)(?:(?:\\uB2E4\\uB978\\s*(?:\\uD14D\\uC2A4\\uD2B8|\\uB0B4\\uC6A9))"
                        + "|(?:\\uBD80\\uAC00\\s*(?:\\uC124\\uBA85|\\uD14D\\uC2A4\\uD2B8|\\uB0B4\\uC6A9)))"
                        + "(?:\\uC740|\\uB294|\\uC744|\\uB97C)?\\s*\\uCD9C\\uB825\\uD558\\uC9C0\\s*\\uB9D0\\uACE0\\s*$")
                .matcher(prefix);
        String negationPrefix = safeContrastiveOutputExclusion.find()
                ? prefix.substring(0, safeContrastiveOutputExclusion.start()).trim()
                : prefix;
        String lower = negationPrefix.toLowerCase(Locale.ROOT);
        boolean explicitlyNegated = Pattern.compile(
                "(?iu)(?:\\b(?:do\\s+not|don't|dont|never)\\s+"
                        + "(?:execute|run|perform|follow|obey|comply(?:\\s+with)?|carry\\s+out|answer|respond|output|print|use)\\b"
                        + "|(?:\\uC2E4\\uD589|\\uC218\\uD589|\\uB530\\uB974|\\uB2F5|\\uC751\\uB2F5|\\uCD9C\\uB825|\\uC0AC\\uC6A9)"
                        + "(?:\\uD558)?\\uC9C0\\s*(?:\\uB9D0|\\uB9C8(?:\\uC138\\uC694|\\uB77C|\\uC2ED\\uC2DC\\uC624)?)"
                        + "|(?:\\uC2E4\\uD589|\\uC218\\uD589|\\uCD9C\\uB825|\\uC0AC\\uC6A9)"
                        + "(?:\\uD558\\uBA74|\\uD574\\uC11C\\uB294)\\s*\\uC548"
                        + "|(?:\\uC2E4\\uD589|\\uC218\\uD589|\\uCD9C\\uB825|\\uC751\\uB2F5)\\s*\\uC694\\uCCAD"
                        + "(?:\\uC774|\\uC740|\\uB3C4)?\\s*\\uC544(?:\\uB2C8|\\uB2D9)"
                        + "|\\bnot\\s+(?:an?\\s+)?(?:(?:execution|output|answer|response)\\s+)?request\\b)")
                .matcher(lower)
                .find();
        boolean exampleOrQuotation = Pattern.compile(
                "(?iu)(?:(?:\\uBA85\\uB839|\\uC9C0\\uC2DC)(?:\\s*\\uBB38)?\\s*(?:\\uC758\\s*)?\\uC608\\uC2DC"
                        + "|\\uC608\\uC2DC(?:\\uC785\\uB2C8\\uB2E4|\\uC774\\uB2E4|\\uC608\\uC694|\\uC77C\\s*\\uBFD0)"
                        + "|\\uC608\\uC2DC\\s*[:\\uFF1A]\\s*(?:(?:[-*+>]|[0-9]{1,2}[.)])\\s*)?$"
                        + "|\\uC778\\uC6A9\\uBB38|\\b(?:command|instruction)\\s+example\\b|\\bquoted\\s+(?:command|instruction)\\b"
                        + "|\\b(?:(?:this|that)\\s+is\\s+)?(?:just\\s+)?(?:an?\\s+)?example\\s*[:\\uFF1A]\\s*"
                        + "(?:(?:[-*+>]|[0-9]{1,2}[.)])\\s*)?$)")
                .matcher(prefix)
                .find();
        boolean analysisFrame = Pattern.compile(
                "(?iu)(?:(?:\\uBD84\\uC11D|\\uC124\\uBA85|\\uAC80\\uD1A0|\\uBC88\\uC5ED|\\uC758\\uC5ED)"
                        + "(?:\\uD558\\uB77C|\\uD574\\uB77C|\\uD558\\uC138\\uC694|\\uD574(?:\\s*(?:\\uC918|\\uC8FC\\uC138\\uC694))?)"
                        + "|(?:\\uBB38\\uC7A5|\\uBB38\\uAD6C|\\uBA85\\uB839\\uD615\\s*\\uB3D9\\uC0AC|\\uC9C0\\uC2DC|\\uBA85\\uB839)"
                        + "(?:\\uC5D0\\uC11C|\\uC758|\\uC744|\\uB97C|\\uC740|\\uB294|\\s)*"
                        + "(?:\\uCC3E\\uC544|\\uC2DD\\uBCC4\\uD574|\\uBD84\\uB958\\uD574)(?:\\s*(?:\\uC918|\\uC8FC\\uC138\\uC694))?"
                        + "|\\b(?:analy[sz]e|explain|review|translate|paraphrase)"
                        + "(?:\\s+(?:this|the\\s+following)(?:\\s+(?:command|instruction|sentence|text))?)?)"
                        + "\\s*[.:;!?\\uFF1A\\u3002\\uFF01\\uFF1F]?\\s*$")
                .matcher(prefix)
                .find();
        return explicitlyNegated || exampleOrQuotation || analysisFrame;
    }

    private static String directKoreanOneWordOnlyLiteral(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern
                .compile("(?iu)(?:^|[\\s:\\uFF1A\\\"'`\\u201C\\u201D\\u2018\\u2019\\[\\](){}<>])([\\p{L}\\p{N}][\\p{L}\\p{N}._:-]{0,79})\\s*(?:\\uC774\\uB77C\\uB294|\\uB77C\\uB294|\\uC774\\uB77C\\uACE0|\\uB77C\\uACE0)\\s*\\uD55C\\s*\\uB2E8\\uC5B4\\s*\\uB9CC")
                .matcher(text);
        while (matcher.find()) {
            String candidate = stripLiteralCandidateEdges(matcher.group(1));
            if (isSafeDirectLiteralCandidate(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String lastMachineTokenLiteralBeforeMarker(String text, int markerStart) {
        if (text == null || text.isBlank() || markerStart <= 0) {
            return null;
        }
        String prefix = text.substring(0, Math.min(markerStart, text.length()));
        java.util.regex.Matcher matcher = Pattern
                .compile("(?iu)(?:^|[\\s:\\uFF1A\\\"'`\\u201C\\u201D\\u2018\\u2019\\[\\](){}<>])([A-Za-z0-9][A-Za-z0-9._:-]{1,79})")
                .matcher(prefix);
        String candidate = null;
        while (matcher.find()) {
            String value = normalizeCodeLikeValue(matcher.group(1));
            if (isSafeMachineTokenLiteralCandidate(value)) {
                candidate = value;
            }
        }
        return candidate;
    }

    private static String directNumberOnlyMultiplicationLiteral(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean numberOnly = text.contains("\uC22B\uC790\uB9CC")
                || lower.contains("number only")
                || lower.contains("only the number");
        boolean numberOnlyNegated = Pattern.compile(
                "\\b(?:(?:do\\s+not|don't|dont)\\s+(?:answer|respond|give)(?:\\s+with)?\\s+"
                        + "|not\\s+)(?:only\\s+the\\s+number|number\\s+only)\\b")
                .matcher(lower)
                .find();
        boolean arithmeticQuestion = text.contains("\uC5BC\uB9C8")
                || text.contains("\uACC4\uC0B0")
                || lower.contains("what is")
                || lower.contains("calculate");
        if (!numberOnly || numberOnlyNegated || !arithmeticQuestion) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?<![\\p{L}\\p{N}.,+\\-*/^%\\u00d7xX])"
                + "([+-]?\\d{1,9})\\s*(?:[\\u00d7xX*]|\\uACF1\\uD558\\uAE30)\\s*([+-]?\\d{1,9})"
                        + "(?![\\p{N}A-Za-z+\\-*/^%\\u00d7xX]|[.,]\\d)")
                .matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String beforeExpression = text.substring(0, matcher.start());
        String afterExpression = text.substring(matcher.end());
        boolean chainedBefore = Pattern.compile(
                "[\\d)]\\s*[+\\-*/^%\\u00d7xX]\\s*\\(*\\s*$")
                .matcher(beforeExpression)
                .find();
        boolean chainedAfter = Pattern.compile(
                "^\\s*\\)*\\s*[+\\-*/^%\\u00d7xX]\\s*\\(*\\s*[+\\-]?\\d")
                .matcher(afterExpression)
                .find();
        if (chainedBefore || chainedAfter) {
            return null;
        }
        long left = Long.parseLong(matcher.group(1));
        long right = Long.parseLong(matcher.group(2));
        if (matcher.find()) {
            return null;
        }
        return String.valueOf(Math.multiplyExact(left, right));
    }

    private static boolean hasDirectLiteralAnswerDirective(String lower, String text) {
        return String.valueOf(lower).contains("answer")
                || String.valueOf(lower).contains("respond")
                || containsAny(text, KOREAN_DIRECT_LITERAL_ANSWER_DIRECTIVES);
    }

    private static String directKoreanCharacterCountLiteral(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern
                .compile("(?iu)(?:^|[\\s:\\uFF1A\\\"'`\\u201C\\u201D\\u2018\\u2019\\[\\](){}<>])([\\p{L}\\p{N}]{1,20})\\s*(?:\\uC774|\\uAC00)?\\s*(\\uD55C|\\uB450|\\uC138|\\uB124|[1-9][0-9]?)\\s*(?:\\uAE00\\uC790|\\uBB38\\uC790)\\uB9CC\\s*(?:\\uCD9C\\uB825|\\uB2F5\\uBCC0|\\uB300\\uB2F5|\\uB9D0\\uD574|\\uC368|\\uD574\\uC918|\\uD574|\\uC8FC|\\uC918)?")
                .matcher(text);
        while (matcher.find()) {
            String candidate = stripLiteralCandidateEdges(matcher.group(1));
            Integer expectedCount = koreanLiteralCharacterCount(matcher.group(2));
            if (expectedCount != null
                    && candidate.codePointCount(0, candidate.length()) == expectedCount
                    && isSafeDirectLiteralCandidate(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Integer koreanLiteralCharacterCount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim()) {
            case "\uD55C" -> 1;
            case "\uB450" -> 2;
            case "\uC138" -> 3;
            case "\uB124" -> 4;
            default -> {
                try {
                    int parsed = Integer.parseInt(value.trim());
                    yield parsed > 0 && parsed <= 20 ? parsed : null;
                } catch (NumberFormatException ex) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("chat.directLiteralFallback.characterCountParse", ex);
                    yield null;
                }
            }
        };
    }

    private static int firstLiteralCandidateDelimiter(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        int end = -1;
        for (String delimiter : java.util.List.of(
                " \uD55C \uB2E8\uC5B4",
                "\uD55C \uB2E8\uC5B4\uB9CC",
                " \uB2E8\uC5B4",
                "\uB2E8\uC5B4\uB9CC",
                "\uB77C\uACE0",
                "\uC774\uB77C\uACE0",
                "\uB9CC ",
                "\uB9CC.",
                "\uB9CC?",
                "\uB9CC!",
                " only",
                " word",
                " words")) {
            int at = lower.indexOf(delimiter.toLowerCase(Locale.ROOT));
            if (at >= 0 && (end < 0 || at < end)) {
                end = at;
            }
        }
        return end;
    }

    private static boolean startsWithKoreanOneWordFormat(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = stripLiteralCandidateEdges(value).replaceAll("\\s+", "");
        return compact.startsWith("\uD55C\uB2E8\uC5B4\uB85C")
                || compact.startsWith("\uD55C\uB2E8\uC5B4\uB9CC");
    }

    private static boolean startsWithKoreanOutputShapeDirective(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return Pattern.compile(
                        "(?iu)^(?:[1-9][0-9]?|\uD55C|\uB450|\uC138|\uB124)\\s*"
                                + "(?:\uC904|\uD589|\uBB38\uC7A5|\uD56D\uBAA9)"
                                + "(?:\uB9CC|\uB85C|\uC73C\uB85C|\uC529|\\s|[.,:!?]|$)")
                .matcher(stripLiteralCandidateEdges(value))
                .find();
    }

    private static int lastLiteralCandidateBoundary(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        int boundary = -1;
        String delimiters = " \t\r\n:：,，;；/|([{<>'\"`“”‘’";
        for (int i = 0; i < value.length(); i++) {
            if (delimiters.indexOf(value.charAt(i)) >= 0) {
                boundary = i;
            }
        }
        return boundary;
    }

    private static String stripKoreanLiteralCandidateSuffix(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        for (String suffix : java.util.List.of(
                "\uC774\uB77C\uACE0",
                "\uB77C\uACE0",
                "\uC774\uC57C",
                "\uC57C",
                "\uB9CC")) {
            if (out.endsWith(suffix) && out.length() > suffix.length()) {
                out = out.substring(0, out.length() - suffix.length()).trim();
                break;
            }
        }
        return out;
    }

    private static boolean isDirectLiteralDirectiveToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = stripLiteralCandidateEdges(value).toLowerCase(Locale.ROOT);
        return DIRECT_LITERAL_DIRECTIVE_TOKENS.contains(text);
    }

    private static boolean isSafeMachineTokenLiteralCandidate(String value) {
        if (!isSafeDirectLiteralCandidate(value)) {
            return false;
        }
        String candidate = stripLiteralCandidateEdges(value);
        if (!candidate.matches("(?iu)[a-z0-9][a-z0-9._:-]{1,79}")) {
            return false;
        }
        return candidate.matches(".*[A-Za-z].*")
                && candidate.matches(".*[0-9._:-].*");
    }

    private static String stripLiteralCandidateEdges(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        while (!out.isEmpty() && ":：,，-–—'\"`“”‘’[](){}<>".indexOf(out.charAt(0)) >= 0) {
            out = out.substring(1).trim();
        }
        while (!out.isEmpty() && ".,!?。？！'\"`“”‘’[](){}<>".indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }

    private static boolean isSafeDirectLiteralCandidate(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.length() > 80) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("://")) {
            return false;
        }
        if (value.contains("\n") || value.contains("\r") || value.contains("?") || value.contains("\uFF1F")) {
            return false;
        }
        return value.split("\\s+").length <= 6;
    }

    static String composeCurrentTurnMemoryFallback(String query) {
        java.util.List<String> values = currentTurnMemoryValues(query);
        if (values.isEmpty()) {
            return null;
        }
        String value = values.size() == 1 ? values.get(0) : String.join(", ", values);
        String safeValue = SafeRedactor.safeMessage(value, 120);
        if (safeValue == null || safeValue.isBlank()) {
            return null;
        }
        try {
            TraceStore.put("chat.currentTurnMemoryFallback.used", true);
            TraceStore.put("chat.currentTurnMemoryFallback.source", "current_user_turn");
            TraceStore.put("chat.currentTurnMemoryFallback.valueHash", SafeRedactor.hash12(value));
            TraceStore.put("chat.currentTurnMemoryFallback.valueLength", value.length());
            TraceStore.put("chat.currentTurnMemoryFallback.valueCount", values.size());
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("chat.currentTurnMemoryFallback.trace", ex);
        }
        if (containsHangul(query)) {
            if (values.size() > 1) {
                return "\uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12 "
                        + values.size()
                        + "\uAC1C\uB97C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: "
                        + safeValue
                        + "\n\uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uAC12\uB4E4\uC744 \uAE30\uC900\uC73C\uB85C \uB2F5\uD558\uACA0\uC2B5\uB2C8\uB2E4.";
            }
            return "\uC774\uBC88 \uC138\uC158\uC758 \uCD5C\uADFC \uB300\uD654 \uAC12\uC73C\uB85C \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4: "
                    + safeValue
                    + "\n\uB2E4\uC74C \uC9C8\uBB38\uC5D0\uC11C \uC774 \uAC12\uC744 \uAE30\uC900\uC73C\uB85C \uB2F5\uD558\uACA0\uC2B5\uB2C8\uB2E4.";
        }
        if (values.size() > 1) {
            return "Noted " + values.size() + " session values: " + safeValue
                    + "\nAsk next and I will answer from recent session history.";
        }
        return "Noted for this session: " + safeValue
                + "\nAsk next and I will answer from recent session history.";
    }

    private static String currentTurnMemoryValue(String query) {
        java.util.List<String> values = currentTurnMemoryValues(query);
        return values.isEmpty() ? null : values.get(0);
    }

    private static java.util.List<String> currentTurnMemoryValues(String query) {
        if (!asksToRememberForNextTurn(query)) {
            return java.util.List.of();
        }
        java.util.List<String> labeledValues = labeledMemoryValues(query);
        java.util.List<String> codeValues = codeLikeValues(query);
        String requestedLabelValue = !asksForCodeOnly(query) ? labeledRecentHistoryValue(query, query) : null;
        if (requestedLabelValue != null && !requestedLabelValue.isBlank()
                && labeledValues.contains(requestedLabelValue)) {
            return java.util.List.of(requestedLabelValue);
        }
        return (asksForCodeOnly(query) || String.valueOf(query).toLowerCase(java.util.Locale.ROOT).contains("code")
                || String.valueOf(query).contains("\uCF54\uB4DC"))
                && !codeValues.isEmpty()
                ? codeValues
                : (labeledValues.isEmpty() ? codeValues : labeledValues);
    }

    private static boolean asksToRememberForNextTurn(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean remember = text.contains("remember")
                || text.contains("memorize")
                || text.contains("\uAE30\uC5B5")
                || text.contains("\uAE30\uB85D")
                || text.contains("\uBA54\uBAA8\uD574")
                || text.contains("\uBA54\uBAA8 \uD574")
                || text.contains("\uC800\uC7A5")
                || text.contains("\uBCF4\uAD00")
                || text.contains("\uAC04\uC9C1")
                || text.contains("\uAE4C\uBA39\uC9C0 \uB9D0")
                || text.contains("\uAE4C\uBA39\uC9C0\uB9D0")
                || text.contains("\uAE4C\uBA39\uC73C\uBA74 \uC548")
                || text.contains("\uAE4C\uBA39\uC73C\uBA74\uC548")
                || text.contains("\uC78A\uC9C0 \uB9D0")
                || text.contains("\uC78A\uC9C0\uB9D0")
                || text.contains("\uC78A\uC73C\uBA74 \uC548")
                || text.contains("\uC78A\uC73C\uBA74\uC548")
                || text.contains("\uC801\uC5B4\uC918")
                || text.contains("\uC801\uC5B4\uB450")
                || text.contains("\uB0A8\uACA8\uC918")
                || text.contains("\uB0A8\uACA8\uB450")
                || text.contains("\uB0A8\uACA8\uB46C")
                || text.contains("\uB0A8\uACA8\uB454")
                || text.contains("\uB0A8\uACA8\uB194")
                || text.contains("\uB0A8\uACA8 \uB194")
                || text.contains("\uB0A8\uACA8\uB193")
                || text.contains("\uBCF4\uAD00\uD574\uC918")
                || text.contains("\uBCF4\uAD00\uD574\uB450")
                || text.contains("\uBCF4\uAD00\uD574 \uB450")
                || text.contains("\uBCF4\uC874\uD574\uC918")
                || text.contains("\uBCF4\uC874\uD574\uB450")
                || text.contains("\uBCF4\uC874\uD574\uB46C")
                || text.contains("\uBCF4\uC874\uD574\uB454")
                || text.contains("\uBCF4\uC874\uD574 \uB46C")
                || text.contains("\uBCF4\uC874\uD574 \uB454")
                || text.contains("\uBCF4\uC874\uD574 \uB450")
                || text.contains("\uAC04\uC9C1\uD574\uC918")
                || text.contains("\uAC04\uC9C1\uD574\uB450")
                || text.contains("\uAC04\uC9C1\uD574 \uB450")
                || text.contains("\uCC59\uACA8\uC918")
                || text.contains("\uCC59\uACA8\uB450")
                || text.contains("\uCC59\uACA8 \uB46C")
                || text.contains("\uCC59\uACA8 \uB454")
                || text.contains("\uCC59\uACA8 \uB450")
                || text.contains("\uC678\uC6CC\uB46C")
                || text.contains("\uC678\uC6CC \uB46C")
                || text.contains("\uC678\uC6CC \uB454")
                || text.contains("\uC678\uC6CC\uB450")
                || text.contains("\uC678\uC6CC \uB450")
                || text.contains("\uC554\uAE30\uD574\uB46C")
                || text.contains("\uC554\uAE30\uD574 \uB46C")
                || text.contains("\uC554\uAE30\uD574 \uB454")
                || text.contains("\uC554\uAE30\uD574\uB450")
                || text.contains("\uC554\uAE30\uD574 \uB450")
                || (text.contains("\uBB3C\uC5B4\uBCF4\uBA74")
                && (text.contains("\uB2F5\uD574") || text.contains("\uB9D0\uD574")));
        remember = remember
                || asksForKoreanDeferredAnswerDirective(text)
                || asksForEnglishDeferredAnswerDirective(text);
        boolean nextTurn = text.contains("next")
                || text.contains("\uB125\uC2A4\uD2B8")
                || text.contains("later")
                || text.contains("follow-up")
                || text.contains("\uD6C4\uC18D")
                || text.contains("\uC774\uC5B4\uC11C")
                || text.contains("\uC774\uC5B4\uC9C0\uB294")
                || text.contains("\uACC4\uC18D\uD574\uC11C")
                || text.contains("\uB2E4\uC74C")
                || text.contains("\uB098\uC911")
                || text.contains("\uC774\uB530")
                || text.contains("\uC788\uB2E4\uAC00")
                || text.contains("\uC7A0\uC2DC \uD6C4")
                || text.contains("\uC7A0\uC2DC\uD6C4")
                || text.contains("\uC774\uD6C4")
                || text.contains("\uCD94\uD6C4")
                || text.contains("\uCC28\uD6C4")
                || text.contains("\uC5B8\uC820\uAC00")
                || text.contains("\uADF8\uB54C")
                || text.contains("\uADF8 \uC2DC\uC810")
                || text.contains("\uADF8\uC2DC\uC810")
                || text.contains("\uADF8 \uC21C\uAC04")
                || text.contains("\uADF8\uC21C\uAC04")
                || text.contains("\uADF8 \uD0C0\uC774\uBC0D")
                || text.contains("\uADF8\uD0C0\uC774\uBC0D")
                || text.contains("\uD0C0\uC774\uBC0D \uB9DE\uCDB0")
                || text.contains("\uD0C0\uC774\uBC0D\uB9DE\uCDB0")
                || text.contains("\uD0C0\uC774\uBC0D\uC5D0 \uB9DE\uCDB0")
                || text.contains("\uD0C0\uC774\uBC0D\uC5D0\uB9DE\uCDB0")
                || text.contains("\uB4A4");
        boolean boundedCodeValue = text.contains("code")
                || text.contains("\uCF54\uB4DC")
                || firstCodeLikeValue(query) != null
                || !labeledMemoryValues(query).isEmpty();
        return remember && boundedCodeValue && (nextTurn || asksToStoreCurrentTurnMemoryValue(text));
    }

    private static boolean asksToStoreCurrentTurnMemoryValue(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.contains("?") || text.contains("\uFF1F")) {
            return false;
        }
        return text.contains("\uAE30\uC5B5\uD574")
                || text.contains("\uAE30\uC5B5 \uD574")
                || text.contains("\uAE30\uB85D\uD574")
                || text.contains("\uAE30\uB85D \uD574")
                || text.contains("\uBA54\uBAA8\uD574")
                || text.contains("\uBA54\uBAA8 \uD574")
                || text.contains("\uC800\uC7A5\uD574")
                || text.contains("\uC800\uC7A5 \uD574")
                || text.contains("\uC801\uC5B4\uC918")
                || text.contains("\uC801\uC5B4 \uC918")
                || text.contains("\uB0A8\uACA8\uC918")
                || text.contains("\uB0A8\uACA8 \uC918")
                || text.contains("\uBCF4\uAD00\uD574")
                || text.contains("\uBCF4\uAD00 \uD574")
                || text.contains("\uAC04\uC9C1\uD574")
                || text.contains("\uAC04\uC9C1 \uD574")
                || Pattern.compile("(?iu)\\b(?:remember|memorize|save|store|note)\\b")
                        .matcher(text)
                        .find();
    }

    static String composeRecentHistoryFallback(String query, String recentHistory) {
        if (!currentTurnMemoryValues(query).isEmpty()) {
            return null;
        }
        if (directLiteralAnswerValue(query) != null) {
            return null;
        }
        if (isExternalProofOnlyAnswerRequest(query)) {
            return null;
        }
        if (isCurrentModeStatusRequest(query)) {
            return null;
        }
        boolean recentHistoryQuestion = isRecentHistoryQuestion(query);
        boolean firstMessageQuestion = asksForKoreanFirstMessageRepeat(query);
        String previousUserMessage = firstMessageQuestion
                ? firstUserMessageAnswerFromHistory(recentHistory, query)
                : previousUserMessageFromHistory(recentHistory, query);
        if (firstMessageQuestion && (previousUserMessage == null || previousUserMessage.isBlank())) {
            previousUserMessage = firstUserMessageFromHistory(recentHistory, query);
        }
        if (previousUserMessage == null || previousUserMessage.isBlank()) {
            return null;
        }
        if (!recentHistoryQuestion && !isDeferredLabelOnlyFollowUp(query, previousUserMessage)) {
            return null;
        }
        String safeMessage = SafeRedactor.safeMessage(previousUserMessage, 360);
        if (safeMessage == null || safeMessage.isBlank()) {
            return null;
        }
        try {
            TraceStore.put("chat.historyFallback.used", true);
            TraceStore.put("chat.historyFallback.source", "recent_history");
            TraceStore.put("chat.historyFallback.messageHash", SafeRedactor.hash12(previousUserMessage));
            TraceStore.put("chat.historyFallback.messageLength", previousUserMessage.length());
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("chat.historyFallback.trace", ex);
        }
        ExactRecentHistoryValue labeledHistoryCode = labeledCodeLikeValueFromHistory(query, recentHistory);
        if (labeledHistoryCode != null && labeledHistoryCode.value() != null && !labeledHistoryCode.value().isBlank()) {
            traceExactRecentHistoryFallback(labeledHistoryCode);
            return labeledHistoryCode.value();
        }
        ExactRecentHistoryValue labeledHistoryValue = labeledRecentHistoryValueFromHistory(query, recentHistory);
        if (labeledHistoryValue != null && labeledHistoryValue.value() != null && !labeledHistoryValue.value().isBlank()) {
            traceExactRecentHistoryFallback(labeledHistoryValue);
            return labeledHistoryValue.value();
        }
        ExactRecentHistoryValue exactValue = exactRecentHistoryValueForQuery(query, safeMessage);
        if (exactValue != null && exactValue.value() != null && !exactValue.value().isBlank()) {
            traceExactRecentHistoryFallback(exactValue);
            return exactValue.value();
        }
        ExactRecentHistoryValue rememberedValues = rememberedRecentHistoryValuesForQuery(query, previousUserMessage);
        if (rememberedValues != null && rememberedValues.value() != null && !rememberedValues.value().isBlank()) {
            traceExactRecentHistoryFallback(rememberedValues);
            return rememberedValues.value();
        }
        if (containsHangul(query)) {
            String messageLabel = firstMessageQuestion
                    ? "\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0: "
                    : "\uC9C1\uC804 \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0: ";
            return messageLabel + safeMessage
                    + "\n\n출처: 세션 최근 기록";
        }
        return "Previous user message: " + safeMessage
                + "\n\nSource: session recent history";
    }

    private record ExactRecentHistoryValue(String value, String answerKind) {
    }

    private static void traceExactRecentHistoryFallback(ExactRecentHistoryValue exactValue) {
        try {
            String value = exactValue == null ? null : exactValue.value();
            String answerKind = exactValue == null ? "exact_value_only" : exactValue.answerKind();
            if ("exact_code_only".equals(answerKind)) {
                TraceStore.put("chat.historyFallback.exactCodeOnly", true);
            } else {
                TraceStore.put("chat.historyFallback.exactValueOnly", true);
            }
            TraceStore.put("chat.historyFallback.answerKind", answerKind);
            TraceStore.put("chat.historyFallback.valueHash", SafeRedactor.hash12(value));
            TraceStore.put("chat.historyFallback.valueLength", value == null ? 0 : value.length());
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("chat.historyFallback.exact.trace", ex);
        }
    }

    private static ExactRecentHistoryValue exactRecentHistoryValueForQuery(String query, String safeMessage) {
        if (safeMessage == null || safeMessage.isBlank()) {
            return null;
        }
        if (asksForCodeOnly(query)) {
            String requestedLabel = requestedCodeLabel(query);
            String codeValue = labeledCodeLikeValue(query, safeMessage);
            if ((codeValue == null || codeValue.isBlank())
                    && (requestedLabel == null || requestedLabel.isBlank())) {
                codeValue = firstCodeLikeValue(safeMessage);
            }
            if (codeValue == null || codeValue.isBlank()) {
                return null;
            }
            return new ExactRecentHistoryValue(SafeRedactor.safeMessage(codeValue, 120), "exact_code_only");
        }
        String value = labeledRecentHistoryValue(query, safeMessage);
        if (value == null || value.isBlank()) {
            return null;
        }
        return new ExactRecentHistoryValue(SafeRedactor.safeMessage(value, 120), "exact_value_only");
    }

    private static ExactRecentHistoryValue labeledCodeLikeValueFromHistory(String query, String recentHistory) {
        if (!asksForCodeOnly(query)
                || recentHistory == null
                || recentHistory.isBlank()) {
            return null;
        }
        String requestedLabel = requestedCodeLabel(query);
        String current = normalizeHistoryCompare(query);
        String[] lines = recentHistory.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.regionMatches(true, 0, "User:", 0, "User:".length())) {
                continue;
            }
            String content = line.substring("User:".length()).trim();
            if (content.isBlank() || (!current.isBlank() && normalizeHistoryCompare(content).equals(current))) {
                continue;
            }
            String safeMessage = SafeRedactor.safeMessage(content, 360);
            String codeValue = requestedLabel == null
                    ? (containsCodeMarker(safeMessage) ? firstCodeLikeValue(safeMessage) : null)
                    : labeledCodeLikeValue(query, safeMessage);
            if (codeValue != null && !codeValue.isBlank()) {
                return new ExactRecentHistoryValue(SafeRedactor.safeMessage(codeValue, 120), "exact_code_only");
            }
        }
        return null;
    }

    private static ExactRecentHistoryValue labeledRecentHistoryValueFromHistory(String query, String recentHistory) {
        if (recentHistory == null || recentHistory.isBlank()) {
            return null;
        }
        if (requestedValueLabel(query) == null && !asksForRememberedValues(query)) {
            return null;
        }
        String current = normalizeHistoryCompare(query);
        String[] lines = recentHistory.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.regionMatches(true, 0, "User:", 0, "User:".length())) {
                continue;
            }
            String content = line.substring("User:".length()).trim();
            if (content.isBlank() || (!current.isBlank() && normalizeHistoryCompare(content).equals(current))) {
                continue;
            }
            if (isStandaloneLabelOnlyFollowUp(content) || isRecentHistoryQuestion(content)) {
                continue;
            }
            String safeMessage = SafeRedactor.safeMessage(content, 360);
            ExactRecentHistoryValue exactValue = exactRecentHistoryValueForQuery(query, safeMessage);
            if (exactValue != null && exactValue.value() != null && !exactValue.value().isBlank()) {
                return exactValue;
            }
            ExactRecentHistoryValue rememberedValues = rememberedRecentHistoryValuesForQuery(query, content);
            if (rememberedValues != null && rememberedValues.value() != null && !rememberedValues.value().isBlank()) {
                return rememberedValues;
            }
        }
        return null;
    }

    private static boolean containsCodeMarker(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("code") || text.contains("\uCF54\uB4DC");
    }

    private static ExactRecentHistoryValue rememberedRecentHistoryValuesForQuery(String query, String previousUserMessage) {
        if (!asksForRememberedValues(query)) {
            return null;
        }
        java.util.List<String> values = currentTurnMemoryValues(previousUserMessage);
        if (values.isEmpty()) {
            return null;
        }
        String value = values.size() == 1 ? values.get(0) : String.join(", ", values);
        String safeValue = SafeRedactor.safeMessage(value, 120);
        if (safeValue == null || safeValue.isBlank()) {
            return null;
        }
        return new ExactRecentHistoryValue(safeValue, "exact_remembered_values");
    }

    private static boolean isDeferredLabelOnlyFollowUp(String query, String previousUserMessage) {
        if (query == null || query.isBlank()
                || previousUserMessage == null || previousUserMessage.isBlank()
                || !asksToRememberForNextTurn(previousUserMessage)) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        boolean repeatDirective = lower.contains(" only")
                || lower.contains(" just ")
                || hasKoreanLabelOnlyMarker(query)
                || containsAny(query, KOREAN_REPEAT_REQUEST_MARKERS)
                || isBareDeferredLabelQuestion(query);
        if (!repeatDirective) {
            return false;
        }
        if (asksForCodeOnly(query)) {
            String codeValue = labeledCodeLikeValue(query, previousUserMessage);
            if ((codeValue == null || codeValue.isBlank()) && requestedCodeLabel(query) == null) {
                codeValue = firstCodeLikeValue(previousUserMessage);
            }
            return codeValue != null && !codeValue.isBlank();
        }
        String label = requestedValueLabel(query);
        if (label == null || label.isBlank()) {
            return false;
        }
        String value = labeledRecentHistoryValue(query, previousUserMessage);
        return value != null && !value.isBlank();
    }

    private static boolean isBareDeferredLabelQuestion(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String text = query.trim();
        return Pattern.compile("(?iu)^(?:the\\s+)?[a-z][a-z0-9_-]{1,20}(?:\\s+[a-z][a-z0-9_-]{1,20}){0,2}\\s*[?.!]?$")
                .matcher(text)
                .matches()
                || Pattern.compile("(?iu)^[\\p{L}\\p{N}]{1,20}(?:\\s+[\\p{L}\\p{N}]{1,20}){0,2}\\s*(?:\\uB9CC)?\\s*[.?!\\u3002\\uFF1F\\uFF01]?$")
                .matcher(text)
                .matches();
    }

    private static boolean hasKoreanLabelOnlyMarker(String query) {
        return query != null && query.contains("\uB9CC") && requestedValueLabel(query) != null;
    }

    private static boolean asksForRememberedValues(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean remember = text.contains("remember")
                || text.contains("memorize")
                || text.contains("\uAE30\uC5B5")
                || text.contains("\uAE30\uB85D")
                || text.contains("\uBA54\uBAA8")
                || text.contains("\uC800\uC7A5")
                || text.contains("\uBCF4\uAD00")
                || text.contains("\uAC04\uC9C1")
                || text.contains("\uAE4C\uBA39\uC9C0 \uB9D0")
                || text.contains("\uAE4C\uBA39\uC9C0\uB9D0")
                || text.contains("\uAE4C\uBA39\uC73C\uBA74 \uC548")
                || text.contains("\uAE4C\uBA39\uC73C\uBA74\uC548")
                || text.contains("\uC78A\uC9C0 \uB9D0")
                || text.contains("\uC78A\uC9C0\uB9D0")
                || text.contains("\uC78A\uC73C\uBA74 \uC548")
                || text.contains("\uC78A\uC73C\uBA74\uC548")
                || containsAny(text, KOREAN_ASK_LATER_VALUE_MARKERS)
                || asksForKoreanMentionedValueQuestion(text)
                || text.contains("\uC801\uC5B4\uC918")
                || text.contains("\uC801\uC5B4\uB480\uB358")
                || text.contains("\uC801\uC5B4 \uB480\uB358")
                || text.contains("\uC801\uC5B4\uB454")
                || text.contains("\uC801\uC5B4 \uB454")
                || text.contains("\uC801\uC5B4\uB193\uC740")
                || text.contains("\uC801\uC740")
                || text.contains("\uC801\uC5B4\uB1A8\uB358")
                || text.contains("\uC368\uB454")
                || text.contains("\uC368 \uB193\uC740")
                || text.contains("\uC368\uB1A8\uB358")
                || text.contains("\uC368 \uB1A8\uB358")
                || text.contains("\uC368 \uB450\uC5C8\uB358")
                || text.contains("\uC791\uC131\uD574 \uB454")
                || text.contains("\uC791\uC131\uD574\uB454")
                || text.contains("\uAE30\uC785\uD574 \uB454")
                || text.contains("\uAE30\uC785\uD574\uB454")
                || text.contains("\uC785\uB825\uD574 \uB454")
                || text.contains("\uC785\uB825\uD574\uB454")
                || text.contains("\uC785\uB825\uD55C")
                || text.contains("\uBCF4\uB0B8")
                || text.contains("\uD0C0\uC774\uD551\uD574 \uB454")
                || text.contains("\uD0C0\uC774\uD551\uD574\uB454")
                || text.contains("\uD0C0\uC774\uD551\uD55C")
                || text.contains("\uC801\uC5B4\uB450\uB77C\uACE0")
                || text.contains("\uC801\uC5B4\uB2EC")
                || text.contains("\uC801\uC5B4 \uB2EC")
                || text.contains("\uB0A8\uACA8\uC918")
                || text.contains("\uB0A8\uACA8\uB46C")
                || text.contains("\uB0A8\uACA8\uB454")
                || text.contains("\uB0A8\uAE34")
                || text.contains("\uB0A8\uACA8\uB450\uB77C\uACE0")
                || text.contains("\uB0A8\uACA8\uB2EC")
                || text.contains("\uB0A8\uACA8 \uB2EC")
                || text.contains("\uB0A8\uACA8\uB193\uC73C\uB77C\uACE0")
                || text.contains("\uB0A8\uACA8\uB194")
                || text.contains("\uB0A8\uACA8 \uB194")
                || text.contains("\uB0A8\uACA8\uB193")
                || text.contains("\uBCF4\uAD00\uD558\uB77C\uACE0")
                || text.contains("\uBCF4\uAD00\uD574\uB450\uB77C\uACE0")
                || text.contains("\uBCF4\uAD00\uD574\uB2EC")
                || text.contains("\uBCF4\uAD00\uD574 \uB2EC")
                || text.contains("\uBCF4\uC874\uD558\uB77C\uACE0")
                || text.contains("\uBCF4\uC874\uD574\uB450\uB77C\uACE0")
                || text.contains("\uBCF4\uC874\uD574\uB46C")
                || text.contains("\uBCF4\uC874\uD574\uB454")
                || text.contains("\uBCF4\uC874\uD574 \uB46C")
                || text.contains("\uBCF4\uC874\uD574 \uB454")
                || text.contains("\uBCF4\uC874\uD574\uB2EC")
                || text.contains("\uBCF4\uC874\uD574 \uB2EC")
                || text.contains("\uAC04\uC9C1\uD558\uB77C\uACE0")
                || text.contains("\uAC04\uC9C1\uD574\uB450\uB77C\uACE0")
                || text.contains("\uAC04\uC9C1\uD574\uB454")
                || text.contains("\uAC04\uC9C1\uD574 \uB454")
                || text.contains("\uAC04\uC9C1\uD574\uB2EC")
                || text.contains("\uAC04\uC9C1\uD574 \uB2EC")
                || text.contains("\uCC59\uAE30\uB77C\uACE0")
                || text.contains("\uCC59\uACA8\uB450\uB77C\uACE0")
                || text.contains("\uCC59\uACA8\uB454")
                || text.contains("\uCC59\uACA8 \uB46C")
                || text.contains("\uCC59\uACA8 \uB454")
                || text.contains("\uCC59\uACA8\uB2EC")
                || text.contains("\uCC59\uACA8 \uB2EC")
                || text.contains("\uC678\uC6CC\uB450\uB77C\uACE0")
                || text.contains("\uC678\uC6CC\uB454")
                || text.contains("\uC678\uC6CC \uB46C")
                || text.contains("\uC678\uC6CC \uB454")
                || text.contains("\uC678\uC6CC\uB2EC")
                || text.contains("\uC678\uC6CC \uB2EC")
                || text.contains("\uC554\uAE30\uD574\uB450\uB77C\uACE0")
                || text.contains("\uC554\uAE30\uD574\uB454")
                || text.contains("\uC554\uAE30\uD574 \uB46C")
                || text.contains("\uC554\uAE30\uD574 \uB454")
                || text.contains("\uC554\uAE30\uD558\uB77C\uACE0")
                || text.contains("\uC554\uAE30\uD574\uB2EC")
                || text.contains("\uC554\uAE30\uD574 \uB2EC")
                || text.contains("\uBB3C\uC5B4\uBCF4\uB77C\uACE0")
                || text.contains("\uBB3C\uC5B4\uBCF4\uBA74");
        boolean koreanPronounValueRepeat = asksForKoreanPronounValueRepeat(text);
        boolean koreanDeicticWhatValueQuestion = asksForKoreanDeicticWhatValueQuestion(text);
        boolean asksWhat = text.contains("what did i")
                || text.contains("what did you")
                || text.contains("what values")
                || text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7");
        return (remember && asksWhat) || koreanPronounValueRepeat || koreanDeicticWhatValueQuestion;
    }

    private static boolean asksForCodeOnly(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean labeledCodeOnly = requestedCodeLabel(query) != null
                && (text.contains(" only")
                || text.contains("answer with")
                || text.contains("answer only")
                || text.contains("just ")
                || text.trim().endsWith("?"));
        return text.contains("code only")
                || text.contains("only the code")
                || text.contains("just the code")
                || labeledCodeOnly
                || text.contains("\uCF54\uB4DC\uB9CC")
                || text.contains("\uCF54\uB4DC\uB97C\uB9CC")
                || text.contains("\uCF54\uB4DC\uAC12\uB9CC")
                || (text.contains("\uCF54\uB4DC\uAC12") && text.contains("\uD558\uB098\uB9CC"));
    }

    private static String labeledCodeLikeValue(String query, String text) {
        String label = requestedCodeLabel(query);
        if (label == null || label.isBlank() || text == null || text.isBlank()) {
            return null;
        }
        String quotedLabel = Pattern.quote(label);
        String explicitCodeValue = "([A-Za-z0-9][A-Za-z0-9_-]{1,31}|[\\p{L}\\p{N}]+[-_][\\p{L}\\p{N}]+)"
                + "(?=\\s*(?:\\uC774\\uACE0|\\uACE0\\s+|\\uC774\\uC57C|\\uC57C|\\uC785\\uB2C8\\uB2E4|\\uC774\\uC5D0\\uC694|\\uC608\\uC694|[,.;!?]|$)|\\s+(?:and\\b|then\\b|for\\b))";
        String koreanPattern = "(?iu)" + quotedLabel
                + "\\s*\\uCF54\\uB4DC(?:\\uAC12)?(?:\\uB294|\\uC740|\\uB97C|\\uAC00)?\\s*(?:is|=|:)?\\s*" + explicitCodeValue;
        java.util.regex.Matcher koreanMatcher = Pattern.compile(koreanPattern).matcher(text);
        if (koreanMatcher.find()) {
            return normalizeCodeLikeValue(koreanMatcher.group(1));
        }
        String englishPattern = "(?iu)\\b" + quotedLabel
                + "\\s+code(?:\\b|\\uB294|\\uC740|\\uB97C|\\uAC00)\\s*(?:is|=|:)?\\s*" + explicitCodeValue;
        java.util.regex.Matcher englishMatcher = Pattern.compile(englishPattern).matcher(text);
        return englishMatcher.find() ? normalizeCodeLikeValue(englishMatcher.group(1)) : null;
    }

    private static String labeledRecentHistoryValue(String query, String text) {
        String label = requestedValueLabel(query);
        if (label == null || label.isBlank() || text == null || text.isBlank()) {
            return null;
        }
        String quotedLabel = Pattern.quote(label);
        String englishPattern = "(?iu)\\b" + quotedLabel
                + "(?:\\s+is\\s+|\\s*(?:->|=>|\\u2192|[=:])\\s*)([^\\n,.;!?]{1,80}?)(?=\\s+(?:and\\b|then\\b)|[,.;!?]|$)";
        java.util.regex.Matcher englishMatcher = Pattern.compile(englishPattern).matcher(text);
        while (englishMatcher.find()) {
            String value = normalizeLabeledMemoryValue(englishMatcher.group(1));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        String koreanPattern = "(?iu)" + quotedLabel
                + "(?:\\uC740|\\uB294|\\uC744|\\uB97C|\\uC774|\\uAC00)?\\s*(?:is|=|:)?\\s*"
                + "([^\\n,.;!?]{1,80}?)(?:\\s*(?:\\uC774\\uB77C\\uACE0|\\uB77C\\uACE0|\\uC774\\uB77C\\s+(?:\\uAE30\\uC5B5\\uD574\\uC918|\\uAE30\\uC5B5\\uD574|\\uAE30\\uB85D\\uD574\\uC918|\\uAE30\\uB85D\\uD574|\\uBA54\\uBAA8\\uD574\\uC918|\\uBA54\\uBAA8\\uD574|\\uC800\\uC7A5\\uD574\\uC918|\\uC800\\uC7A5\\uD574)|\\uC774\\uACE0|\\uACE0\\s+|\\uC774\\uC57C|\\uC57C|\\uC785\\uB2C8\\uB2E4|\\uC774\\uC5D0\\uC694|\\uC608\\uC694)|[,.;!?]|$)";
        java.util.regex.Matcher koreanMatcher = Pattern.compile(koreanPattern).matcher(text);
        while (koreanMatcher.find()) {
            String value = normalizeLabeledMemoryValue(koreanMatcher.group(1));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String requestedValueLabel(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String label = requestedCompoundValueLabel(query);
        if (label != null) {
            return label;
        }
        label = null;
        java.util.regex.Matcher labelOnlyMatcher = Pattern
                .compile("(?iu)([\\p{L}\\p{N}]{1,20})\\s*\\uB9CC")
                .matcher(query);
        while (labelOnlyMatcher.find()) {
            String candidate = normalizeValueLabel(labelOnlyMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishMyMatcher = Pattern
                .compile("(?iu)\\bwhat\\s+(?:was|is)\\s+my\\s+(?:(?:favorite|preferred|saved|remembered|stored)\\s+)?([a-z][a-z0-9_-]{1,20})\\b")
                .matcher(query);
        while (englishMyMatcher.find()) {
            String candidate = normalizeValueLabel(englishMyMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishWhatMatcher = Pattern
                .compile("(?iu)\\bwhat\\s+(?:was|is)\\s+(?:the\\s+)?([a-z][a-z0-9_-]{1,20})\\b")
                .matcher(query);
        while (englishWhatMatcher.find()) {
            String candidate = normalizeValueLabel(englishWhatMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishOnlyMatcher = Pattern
                .compile("(?iu)\\b(?:answer\\s+only\\s+(?:the\\s+)?|only\\s+(?:the\\s+)?|just\\s+(?:the\\s+)?)([a-z][a-z0-9_-]{1,20})\\b")
                .matcher(query);
        while (englishOnlyMatcher.find()) {
            String candidate = normalizeValueLabel(englishOnlyMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishPostposedOnlyMatcher = Pattern
                .compile("(?iu)\\b(?:the\\s+)?([a-z][a-z0-9_-]{1,20})\\s+only\\b")
                .matcher(query);
        while (englishPostposedOnlyMatcher.find()) {
            String candidate = normalizeValueLabel(englishPostposedOnlyMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher bareLabelQuestionMatcher = Pattern
                .compile("(?iu)^\\s*(?:the\\s+)?([a-z][a-z0-9_-]{1,20}|[\\p{L}\\p{N}]{1,20})\\s*(?:\\uB9CC)?\\s*[.?!\\u3002\\uFF1F\\uFF01]?\\s*$")
                .matcher(query);
        while (bareLabelQuestionMatcher.find()) {
            String candidate = normalizeValueLabel(bareLabelQuestionMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishWhichMatcher = Pattern
                .compile("(?iu)\\bwhich\\s+(?:was\\s+)?(?:the\\s+)?([a-z][a-z0-9_-]{1,20})\\s+did\\s+i\\s+(?:mention|say|give)\\b")
                .matcher(query);
        while (englishWhichMatcher.find()) {
            String candidate = normalizeValueLabel(englishWhichMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishDidIMatcher = Pattern
                .compile("(?iu)\\bwhat\\s+(?:was\\s+)?(?:the\\s+)?([a-z][a-z0-9_-]{1,20})\\s+did\\s+i\\b")
                .matcher(query);
        while (englishDidIMatcher.find()) {
            String candidate = normalizeValueLabel(englishDidIMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher koreanMatcher = Pattern
                .compile("(?iu)([\\p{L}\\p{N}]{1,20})(?:\\uC740|\\uB294|\\uC744|\\uB97C|\\uC774|\\uAC00)\\s*(?:\\uBB50|\\uBB34\\uC5C7|what)")
                .matcher(query);
        while (koreanMatcher.find()) {
            String candidate = normalizeValueLabel(koreanMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        return label;
    }

    private static String requestedCompoundValueLabel(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String label = null;
        java.util.regex.Matcher koreanOnlyCommandMatcher = Pattern
                .compile("(?iu)^\\s*(?:\\uBC29\\uAE08\\s+)?(?:\\uB0B4\\s+)?(?:\\uB9D0\\uD55C\\s+)?([\\p{L}\\p{N}]{1,20}(?:\\s+[\\p{L}\\p{N}]{1,20}){0,2})\\s*\\uB9CC\\s*(?:\\uC54C\\uB824\\uC918|\\uB2F5\\uD574\\uC918|\\uB2F5\\uD574|\\uB9D0\\uD574\\uC918)?\\s*[.?!\\u3002\\uFF1F\\uFF01]?\\s*$")
                .matcher(query);
        while (koreanOnlyCommandMatcher.find()) {
            String candidate = normalizeValueLabel(koreanOnlyCommandMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishPostposedOnlyCommandMatcher = Pattern
                .compile("(?iu)^\\s*(?:the\\s+)?([a-z][a-z0-9_-]{1,20}(?:\\s+[a-z][a-z0-9_-]{1,20}){1,2})\\s+only\\s*[.?!]?\\s*$")
                .matcher(query);
        while (englishPostposedOnlyCommandMatcher.find()) {
            String candidate = normalizeValueLabel(englishPostposedOnlyCommandMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher bareCompoundLabelMatcher = Pattern
                .compile("(?iu)^\\s*(?:the\\s+)?([a-z][a-z0-9_-]{1,20}(?:\\s+[a-z][a-z0-9_-]{1,20}){1,2}|[\\p{L}\\p{N}]{1,20}(?:\\s+[\\p{L}\\p{N}]{1,20}){1,2})\\s*(?:\\uB9CC)?\\s*[.?!\\u3002\\uFF1F\\uFF01]?\\s*$")
                .matcher(query);
        while (bareCompoundLabelMatcher.find()) {
            String candidate = normalizeValueLabel(bareCompoundLabelMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        return label;
    }

    private static String normalizeValueLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String safe = label.trim().replaceAll("\\s+", " ");
        if (!safe.matches("(?iu)[\\p{L}\\p{N}]{1,20}(?:\\s+[\\p{L}\\p{N}]{1,20}){0,2}")) {
            return null;
        }
        java.util.Set<String> blockedLabels = java.util.Set.of("the", "only", "just", "my", "last", "previous", "this", "that", "it",
                "answer", "value", "values", "question", "next", "remember", "say", "said", "session",
                "test", "memory", "english",
                "\uC774", "\uADF8", "\uC800", "\uB0B4", "\uBC29\uAE08", "\uC9C1\uC804",
                "\uB2E4\uC74C", "\uC9C8\uBB38", "\uC9C8\uBB38\uC6A9", "\uC774\uB530", "\uC788\uB2E4", "\uAC12", "\uAC12\uB4E4", "\uB2F5", "\uB300\uB2F5", "\uC5B8\uC820",
                "\uD55C", "\uB2E8\uC5B4", "\uB2E8\uC5B4\uB85C", "\uC9E7\uAC8C",
                "\uB9D0\uD55C", "\uBB3C\uC5B4\uBCF4\uBA74", "\uC774\uC5B4\uC9C0", "\uC774\uC5B4\uC9C0\uB294");
        for (String token : safe.toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
            if (blockedLabels.contains(token)) {
                return null;
            }
        }
        return safe;
    }

    private static String requestedCodeLabel(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String label = requestedCompoundCodeLabel(query);
        if (label != null) {
            return label;
        }
        label = null;
        java.util.regex.Matcher koreanMatcher = Pattern
                .compile("(?iu)([\\p{L}\\p{N}]{1,20})\\s*\\uCF54\\uB4DC(?:\\uB294|\\uC740|\\uB97C|\\uAC00|\\uB9CC)?")
                .matcher(query);
        while (koreanMatcher.find()) {
            String candidate = normalizeCodeLabel(koreanMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishMatcher = Pattern.compile("(?iu)\\b([a-z0-9]{2,20})\\s+code\\b")
                .matcher(query);
        while (englishMatcher.find()) {
            String candidate = normalizeCodeLabel(englishMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        return label;
    }

    private static String requestedCompoundCodeLabel(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String label = null;
        java.util.regex.Matcher koreanMatcher = Pattern
                .compile("(?iu)([\\p{L}\\p{N}]{1,20}(?:\\s+[\\p{L}\\p{N}]{1,20}){0,3})\\s*\\uCF54\\uB4DC(?:\\uB294|\\uC740|\\uB97C|\\uAC00|\\uB9CC)?")
                .matcher(query);
        while (koreanMatcher.find()) {
            String candidate = normalizeCodeLabel(koreanMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        if (label != null) {
            return label;
        }
        java.util.regex.Matcher englishMatcher = Pattern
                .compile("(?iu)\\b([a-z0-9][a-z0-9_-]{1,20}(?:\\s+[a-z0-9][a-z0-9_-]{1,20}){0,2})\\s+code\\b")
                .matcher(query);
        while (englishMatcher.find()) {
            String candidate = normalizeCodeLabel(englishMatcher.group(1));
            if (candidate != null) {
                label = candidate;
            }
        }
        return label;
    }

    private static String normalizeCodeLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String safe = label.trim().replaceAll("\\s+", " ");
        java.util.Set<String> blockedLabels = java.util.Set.of("the", "only", "just", "my", "last", "previous", "this", "that",
                "answer", "value", "values", "question", "next", "remember", "say", "said", "session",
                "test", "memory", "english",
                "\uC774", "\uADF8", "\uC800", "\uB0B4", "\uB0B4\uAC00", "\uBC29\uAE08", "\uC9C1\uC804",
                "\uC9C0\uAE08", "\uC774\uBC88", "\uC138\uC158", "\uC138\uC158\uC758",
                "\uB2E4\uC74C", "\uC9C8\uBB38", "\uC9C8\uBB38\uC5D0\uC11C", "\uB9D0\uD55C",
                "\uBCF4\uB0B8", "\uC785\uB825\uD55C", "\uC4F4", "\uBD99\uC778",
                "\uB2F5", "\uB300\uB2F5", "\uC54C\uB824\uC918");
        java.util.List<String> tokens = new java.util.ArrayList<>(
                java.util.Arrays.asList(safe.toLowerCase(java.util.Locale.ROOT).split("\\s+")));
        while (!tokens.isEmpty() && blockedLabels.contains(tokens.get(0))) {
            tokens.remove(0);
        }
        if (tokens.isEmpty() || tokens.stream().anyMatch(blockedLabels::contains)) {
            return null;
        }
        safe = String.join(" ", tokens);
        if (!safe.matches("(?iu)[\\p{L}\\p{N}]{1,20}(?:\\s+[\\p{L}\\p{N}]{1,20}){0,2}")) {
            return null;
        }
        return safe;
    }

    private static String firstCodeLikeValue(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String explicitCodeValue = "([A-Za-z0-9][A-Za-z0-9._:-]{1,79}|[\\p{L}\\p{N}]+[-_][\\p{L}\\p{N}]+)"
                + "(?=\\s*(?:\\uC774\\uACE0|\\uACE0\\s+|\\uC774\\uC57C|\\uC57C|\\uAE30\\uC5B5|\\uAE30\\uB85D|\\uBA54\\uBAA8|\\uC800\\uC7A5|\\uC785\\uB2C8\\uB2E4|\\uC774\\uC5D0\\uC694|\\uC608\\uC694|[,.;!?]|$)|\\s+(?:and\\b|then\\b|for\\b|remember\\b|memorize\\b|save\\b|store\\b|note\\b))";
        String markerPattern = "(?iu)(?:\\bcode\\b|\\uCF54\\uB4DC(?:\\uAC12)?(?:\\uB294|\\uC740|\\uB97C)?)\\s*(?:is|=|:)?\\s*" + explicitCodeValue;
        java.util.regex.Matcher markerMatcher = Pattern.compile(markerPattern).matcher(text);
        if (markerMatcher.find()) {
            return normalizeCodeLikeMarkerValue(markerMatcher.group(1));
        }
        java.util.regex.Matcher fallbackMatcher = Pattern
                .compile("(?iu)(?:^|[\\s:\\uFF1A\\\"'`\\u201C\\u201D\\u2018\\u2019\\[\\](){}<>])([A-Za-z0-9][A-Za-z0-9._:-]{1,79}|[\\p{L}\\p{N}]+[-_][\\p{L}\\p{N}]+)")
                .matcher(text);
        while (fallbackMatcher.find()) {
            String value = normalizeCodeLikeHistoryValue(fallbackMatcher.group(1));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static java.util.List<String> codeLikeValues(String text) {
        if (text == null || text.isBlank()) {
            return java.util.List.of();
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher fallbackMatcher = Pattern
                .compile("(?iu)(?:^|[\\s:\\uFF1A\\\"'`\\u201C\\u201D\\u2018\\u2019\\[\\](){}<>])([A-Za-z0-9][A-Za-z0-9._:-]{1,79}|[\\p{L}\\p{N}]+[-_][\\p{L}\\p{N}]+)")
                .matcher(text);
        while (fallbackMatcher.find()) {
            String value = normalizeCodeLikeHistoryValue(fallbackMatcher.group(1));
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return java.util.List.copyOf(values);
    }

    private static String normalizeCodeLikeHistoryValue(String value) {
        String normalized = normalizeCodeLikeValue(value);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (normalized.endsWith(":") || normalized.endsWith("\uFF1A")) {
            return null;
        }
        if (isSafeMachineTokenLiteralCandidate(normalized)) {
            return normalized;
        }
        return normalized.matches("(?iu)[\\p{L}\\p{N}]+[-_][\\p{L}\\p{N}]+") ? normalized : null;
    }

    private static String normalizeCodeLikeMarkerValue(String value) {
        String normalized = normalizeCodeLikeValue(value);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return normalized.endsWith(":") || normalized.endsWith("\uFF1A") ? null : normalized;
    }

    private static java.util.List<String> labeledMemoryValues(String text) {
        if (text == null || text.isBlank()) {
            return java.util.List.of();
        }
        String parseText = text.replaceAll(
                "(?iu)(?:\\uC880\\s*)?\\uC788\\uB2E4\\uAC00\\s+(?=[\\p{L}\\p{N}]{1,20}\\s*:)",
                "");
        parseText = parseText.replaceAll(
                "(?iu)(?:^|\\s)(?:\\uB2E4\\uC74C\\s*\\uC9C8\\uBB38|\\uD6C4\\uC18D\\s*\\uC9C8\\uBB38)(?:\\uC744|\\uB97C)?\\s*(?:\\uC704\\uD574|\\uC704\\uD574\\uC11C|\\uC6A9\\uC73C\\uB85C|\\uC6A9|\\uB54C|\\uC5D0)\\s+",
                " ");
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        addCompoundKoreanMemoryValues(values, parseText);
        String labeledPattern = "(?iu)([\\p{L}\\p{N}]{1,20})(?:\\uC740|\\uB294|\\uC744|\\uB97C|\\uC774|\\uAC00)\\s+"
                + "([^\\n,.;!?:]{1,80}?)(?:\\s*(?:\\uC774\\uB77C\\uACE0|\\uB77C\\uACE0|\\uC774\\uB77C\\s+(?:\\uAE30\\uC5B5\\uD574\\uC918|\\uAE30\\uC5B5\\uD574|\\uAE30\\uB85D\\uD574\\uC918|\\uAE30\\uB85D\\uD574|\\uBA54\\uBAA8\\uD574\\uC918|\\uBA54\\uBAA8\\uD574|\\uC800\\uC7A5\\uD574\\uC918|\\uC800\\uC7A5\\uD574)|\\uC774\\uACE0|\\uACE0\\s+|\\uC774\\uC57C|\\uC57C|\\uC785\\uB2C8\\uB2E4|\\uC774\\uC5D0\\uC694|\\uC608\\uC694)|[,.;!?]|$)";
        java.util.regex.Matcher matcher = Pattern.compile(labeledPattern).matcher(parseText);
        while (matcher.find()) {
            String label = normalizeValueLabel(matcher.group(1));
            String value = normalizeLabeledMemoryValue(matcher.group(2));
            if (label != null && value != null && !value.isBlank()
                    && !looksLikeKoreanQuestionValue(value)) {
                values.add(value);
            }
        }
        String englishPattern = "(?iu)(?=(?<![-_])\\b(?!(?:seed|trace|request|correlation)[-_][a-z0-9_-]+\\b)(?!(?:test|memory|english|remember|answer|value|values|question|next|session|probe|continuation)\\b)"
                + "([a-z][a-z0-9_-]{1,20})(?:\\s+is\\s+|\\s*(?:->|=>|\\u2192|[=:])\\s*)"
                + "([^\\n,.;!?]{1,80}?)(?=\\s+(?:and\\b|then\\b)|[,.;!?]|$))";
        java.util.regex.Matcher englishMatcher = Pattern.compile(englishPattern).matcher(parseText);
        while (englishMatcher.find()) {
            String label = normalizeValueLabel(englishMatcher.group(1));
            String value = normalizeLabeledMemoryValue(englishMatcher.group(2));
            if (label != null && value != null && !value.isBlank()
                    && !startsWithEnglishLabeledAssignment(value)
                    && !looksLikeNestedKoreanAssignmentValue(value, values)) {
                values.add(value);
            }
        }
        return java.util.List.copyOf(values);
    }

    private static void addCompoundKoreanMemoryValues(java.util.Set<String> values, String parseText) {
        if (values == null || parseText == null || parseText.isBlank()) {
            return;
        }
        String valueEnd = "(?:\\s*(?:\\uC774\\uB77C\\uACE0|\\uB77C\\uACE0|"
                + "\\uC774\\uB77C\\s+(?:\\uAE30\\uC5B5\\uD574\\uC918|\\uAE30\\uC5B5\\uD574|"
                + "\\uAE30\\uB85D\\uD574\\uC918|\\uAE30\\uB85D\\uD574|\\uBA54\\uBAA8\\uD574\\uC918|"
                + "\\uBA54\\uBAA8\\uD574|\\uC800\\uC7A5\\uD574\\uC918|\\uC800\\uC7A5\\uD574)|"
                + "\\uC774\\uACE0|\\uACE0\\s+|\\uC774\\uC57C|\\uC57C|"
                + "\\uC785\\uB2C8\\uB2E4|\\uC774\\uC5D0\\uC694|\\uC608\\uC694)|[,.;!?]|$)";
        String pattern = "(?iu)([\\p{L}\\p{N}]{1,20}\\s+(?:\\uB2E8\\uC5B4|\\uAC12|\\uB0B4\\uC6A9))"
                + "(?:\\uC740|\\uB294|\\uC744|\\uB97C|\\uC774|\\uAC00)\\s+"
                + "([^\\n,.;!?:]{1,80}?)"
                + valueEnd;
        java.util.regex.Matcher matcher = Pattern.compile(pattern).matcher(parseText);
        while (matcher.find()) {
            if (!isSpecificCompoundKoreanMemoryLabel(matcher.group(1))) {
                continue;
            }
            String value = normalizeLabeledMemoryValue(matcher.group(2));
            if (value != null && !value.isBlank()
                    && !looksLikeKoreanQuestionValue(value)) {
                values.add(value);
            }
        }
    }

    private static boolean isSpecificCompoundKoreanMemoryLabel(String label) {
        if (label == null || label.isBlank()) {
            return false;
        }
        String[] tokens = label.trim().split("\\s+");
        if (tokens.length < 2) {
            return false;
        }
        String first = tokens[0].toLowerCase(Locale.ROOT);
        return !java.util.Set.of("\uC774", "\uADF8", "\uC800", "\uB0B4", "\uB2E4\uC74C",
                "\uC9C8\uBB38", "\uC9C8\uBB38\uC6A9", "\uD6C4\uC18D", "\uC138\uC158")
                .contains(first);
    }

    private static boolean looksLikeKoreanQuestionValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        return text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4")
                || text.endsWith("?");
    }

    private static boolean startsWithEnglishLabeledAssignment(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return Pattern.compile("(?iu)^\\s*[a-z][a-z0-9_-]{1,20}(?:\\s+is\\s+|\\s*(?:->|=>|\\u2192|[=:])\\s*)\\S")
                .matcher(value)
                .find();
    }

    private static boolean looksLikeNestedKoreanAssignmentValue(String value,
            java.util.Collection<String> existingValues) {
        if (value == null || value.isBlank() || existingValues == null || existingValues.isEmpty()) {
            return false;
        }
        if (!Pattern.compile("(?iu)[\\p{L}\\p{N}]{1,20}(?:\\uC740|\\uB294|\\uC744|\\uB97C|\\uC774|\\uAC00)\\s+\\S")
                .matcher(value)
                .find()) {
            return false;
        }
        String haystack = value.trim();
        for (String existing : existingValues) {
            if (existing != null && !existing.isBlank() && haystack.contains(existing.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeLabeledMemoryValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim()
                .replaceAll("(?iu)^[=:：\\s]+", "")
                .replaceAll("(?iu)[\\s,.;!?]+$", "");
        trimmed = trimmed.replaceAll("(?iu)\\s+for\\s+(?:the\\s+)?next\\s+question$", "").trim();
        if (isKoreanMemoryDirectiveOnlyValue(trimmed)) {
            return null;
        }
        java.util.regex.Matcher koreanSentenceSuffix = Pattern.compile(
                "(?iu)^(.+?)(?:\\uC774\\uACE0|\\uC774\\uC57C|\\uC57C|\\uC785\\uB2C8\\uB2E4|\\uC774\\uC5D0\\uC694|\\uC608\\uC694)$")
                .matcher(trimmed);
        if (koreanSentenceSuffix.matches()) {
            trimmed = koreanSentenceSuffix.group(1).trim();
        }
        java.util.regex.Matcher koreanMemoryDirectiveSuffix = Pattern.compile(
                "(?iu)^(.+?)(?:\\s*(?:\\uC774\\uB77C\\uACE0|\\uB77C\\uACE0|\\uC774\\uB77C|\\uC73C\\uB85C|\\uB85C|\\uC744|\\uB97C)?\\s*(?:\\uAE30\\uC5B5\\uD574\\uC918|\\uAE30\\uC5B5\\uD574|\\uAE30\\uB85D\\uD574\\uC918|\\uAE30\\uB85D\\uD574|\\uBA54\\uBAA8\\uD574\\uC918|\\uBA54\\uBAA8\\uD574|\\uC800\\uC7A5\\uD574\\uC918|\\uC800\\uC7A5\\uD574))$")
                .matcher(trimmed);
        if (koreanMemoryDirectiveSuffix.matches()) {
            trimmed = koreanMemoryDirectiveSuffix.group(1).trim();
        }
        if (trimmed.length() > 80 || trimmed.contains("\n") || trimmed.contains("\r")) {
            return null;
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private static boolean isKoreanMemoryDirectiveOnlyValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.replaceAll("\\s+", "");
        return java.util.Set.of(
                "\uAE30\uC5B5\uD574",
                "\uAE30\uC5B5\uD574\uC918",
                "\uAE30\uB85D\uD574",
                "\uAE30\uB85D\uD574\uC918",
                "\uBA54\uBAA8\uD574",
                "\uBA54\uBAA8\uD574\uC918",
                "\uC800\uC7A5\uD574",
                "\uC800\uC7A5\uD574\uC918",
                "\uC801\uC5B4\uC918",
                "\uB0A8\uACA8\uC918",
                "\uBCF4\uAD00\uD574",
                "\uBCF4\uAD00\uD574\uC918",
                "\uAC04\uC9C1\uD574",
                "\uAC04\uC9C1\uD574\uC918").contains(compact);
    }

    private static String normalizeCodeLikeValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = stripLiteralCandidateEdges(value);
        java.util.regex.Matcher koreanParticleSuffix = Pattern.compile(
                "(?iu)^(.+[-_][A-Za-z0-9][A-Za-z0-9_-]*)(?:\\uC744|\\uB97C|\\uB77C|\\uC774\\uB77C)$")
                .matcher(trimmed);
        if (koreanParticleSuffix.matches()) {
            trimmed = koreanParticleSuffix.group(1);
        }
        java.util.regex.Matcher asciiCodeHangulSuffix = Pattern.compile(
                "(?iu)^([A-Za-z0-9][A-Za-z0-9_-]*[-_][A-Za-z0-9][A-Za-z0-9_-]*)(?:\\p{IsHangul}{1,3})$")
                .matcher(trimmed);
        if (asciiCodeHangulSuffix.matches()) {
            trimmed = asciiCodeHangulSuffix.group(1);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (isDirectLiteralDirectiveToken(trimmed)
                || java.util.Set.of("only", "just", "the", "a", "an", "code", "codes", "for", "next", "question")
                        .contains(lower)) {
            return null;
        }
        java.util.regex.Matcher koreanSentenceSuffix = Pattern.compile(
                "(?iu)^(.+[-_][\\p{L}\\p{N}]*?)(?:\\uC774\\uACE0|\\uACE0|\\uC774\\uC57C|\\uC57C|\\uC774\\uC5C8\\uC5B4|\\uC600\\uC5B4|\\uC785\\uB2C8\\uB2E4|\\uC694)$")
                .matcher(trimmed);
        return koreanSentenceSuffix.matches() ? koreanSentenceSuffix.group(1) : trimmed;
    }

    private static boolean containsHangul(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                        || (codePoint >= 0x1100 && codePoint <= 0x11FF)
                        || (codePoint >= 0x3130 && codePoint <= 0x318F));
    }

    private static boolean containsAny(String text, List<String> markers) {
        if (text == null || text.isBlank() || markers == null || markers.isEmpty()) {
            return false;
        }
        return markers.stream().anyMatch(text::contains);
    }

    private static boolean isRecentHistoryQuestion(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean englishPronounQuestion = Pattern.compile("(?iu)\\bwhat\\s+(?:was|is)\\s+(?:it|that)\\b")
                .matcher(text)
                .find();
        boolean englishPronounExactValue = englishPronounQuestion
                && (asksForCodeOnly(query) || requestedValueLabel(query) != null);
        boolean englishLabeledCodeRepeat = requestedCodeLabel(query) != null
                && (asksForCodeOnly(query)
                || Pattern.compile("(?iu)\\bcode\\s*[?.!]?$").matcher(text.trim()).find());
        boolean englishMyValueQuestion = Pattern.compile("(?iu)\\bwhat\\s+(?:was|is)\\s+my\\s+(?:(?:favorite|preferred|saved|remembered|stored)\\s+)?[a-z][a-z0-9_-]{1,20}\\b")
                .matcher(text)
                .find();
        boolean english = text.contains("what did i")
                || text.contains("what i said")
                || text.contains("what was my previous")
                || text.contains("previous message")
                || text.contains("last message")
                || text.contains("previous turn")
                || text.contains("last turn")
                || text.contains("just say")
                || text.contains("just said")
                || text.contains("did i mention")
                || text.contains("repeat what i said")
                || englishMyValueQuestion
                || englishPronounExactValue
                || englishLabeledCodeRepeat;
        boolean koreanPronounValueRepeat = asksForKoreanPronounValueRepeat(text);
        boolean koreanDeicticWhatValueQuestion = asksForKoreanDeicticWhatValueQuestion(text);
        boolean koreanSubject = text.contains("\uBC29\uAE08")
                || text.contains("\uC774\uC804")
                || text.contains("\uC9C1\uC804")
                || text.contains("\uC9C0\uB09C \uD134")
                || text.contains("\uC55E\uC11C")
                || text.contains("\uC0C8\uB85C\uACE0\uCE68")
                || text.contains("\uB9AC\uB85C\uB4DC");
        boolean koreanAction = text.contains("\uB0B4\uAC00")
                || text.contains("\uBCF4\uB0B8")
                || text.contains("\uB9D0\uD574")
                // A sentence-count output format alone does not request message recall.
                || Pattern.compile("\uBB38\uC7A5(?!\\s*\uC73C\uB85C)").matcher(text).find()
                || text.contains("\uC9C8\uBB38")
                || text.contains("\uAE30\uC5B5");
        boolean koreanRememberedValueQuestion = (text.contains("\uAE30\uC5B5\uD558\uB77C\uACE0")
                || text.contains("\uAE30\uC5B5\uD574\uB450\uB77C\uACE0")
                || text.contains("\uAE30\uC5B5\uD574\uB2EC")
                || text.contains("\uAE30\uC5B5\uD574 \uB2EC")
                || text.contains("\uAE30\uC5B5\uD558\uACE0 \uC788\uC73C\uB77C\uACE0")
                || text.contains("\uAE30\uC5B5\uD558\uACE0\uC788\uC73C\uB77C\uACE0")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC73C\uB77C\uACE0")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC73C\uB77C\uACE0")
                || text.contains("\uAE30\uC5B5\uD574 \uB454\uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB454 \uAC70")
                || text.contains("\uAE30\uC5B5\uD574\uB454\uAC70")
                || text.contains("\uAE30\uC5B5\uD574\uB454 \uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB454\uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574 \uB454 \uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574\uB454\uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574\uB454 \uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574 \uB454\uAC12")
                || text.contains("\uAE30\uC5B5\uD574 \uB454 \uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB454\uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB454 \uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB1A8\uB358\uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB1A8\uB358\uAC70")
                || text.contains("\uAE30\uC5B5\uD574\uB1A8\uB358 \uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB1A8\uB358 \uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB194\uB480\uB358\uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574 \uB194\uB480\uB358 \uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574\uB194\uB480\uB358\uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574\uB194\uB480\uB358 \uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574 \uB194\uB454\uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574 \uB194\uB454 \uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574\uB194\uB454\uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574\uB194\uB454 \uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574 \uB194\uB454\uAC12")
                || text.contains("\uAE30\uC5B5\uD574 \uB194\uB454 \uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB194\uB454\uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB194\uB454 \uAC12")
                || text.contains("\uAE30\uC5B5\uD574 \uB194\uB480\uB358\uAC12")
                || text.contains("\uAE30\uC5B5\uD574 \uB194\uB480\uB358 \uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB194\uB480\uB358\uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB194\uB480\uB358 \uAC12")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC740\uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC740 \uAC70")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC740\uAC70")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC740 \uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC558\uB358\uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC558\uB358 \uAC70")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC558\uB358\uAC70")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC558\uB358 \uAC70")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC558\uB358\uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC558\uB358 \uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC558\uB358\uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC558\uB358 \uB0B4\uC6A9")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC558\uB358\uAC12")
                || text.contains("\uAE30\uC5B5\uD574 \uB193\uC558\uB358 \uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC558\uB358\uAC12")
                || text.contains("\uAE30\uC5B5\uD574\uB193\uC558\uB358 \uAC12")
                || text.contains("\uAE30\uB85D\uD558\uB77C\uACE0")
                || text.contains("\uAE30\uB85D\uD55C\uAC70")
                || text.contains("\uAE30\uB85D\uD55C \uAC70")
                || text.contains("\uAE30\uB85D\uD55C\uAC12")
                || text.contains("\uAE30\uB85D\uD55C \uAC12")
                || text.contains("\uAE30\uB85D\uD55C\uB0B4\uC6A9")
                || text.contains("\uAE30\uB85D\uD55C \uB0B4\uC6A9")
                || text.contains("\uAE30\uB85D\uD55C \uAC83\uB4E4")
                || text.contains("\uAE30\uB85D\uB4E4")
                || text.contains("\uAE30\uB85D\uD574\uB454")
                || text.contains("\uAE30\uB85D\uD574 \uB454")
                || text.contains("\uAE30\uB85D\uD588\uB358")
                || text.contains("\uAE30\uB85D \uD588\uB358")
                || text.contains("\uAE30\uB85D\uD574\uB2EC")
                || text.contains("\uAE30\uB85D\uD574 \uB2EC")
                || text.contains("\uBA54\uBAA8\uD558\uB77C\uACE0")
                || text.contains("\uBA54\uBAA8\uB4E4")
                || text.contains("\uBA54\uBAA8\uD588\uB358")
                || text.contains("\uBA54\uBAA8 \uD588\uB358")
                || text.contains("\uBA54\uBAA8\uD574\uB193\uC740")
                || text.contains("\uBA54\uBAA8\uD574 \uB454")
                || text.contains("\uBA54\uBAA8\uD574\uB454")
                || text.contains("\uBA54\uBAA8\uD574\uB2EC")
                || text.contains("\uBA54\uBAA8\uD574 \uB2EC")
                || text.contains("\uAE4C\uBA39\uC9C0 \uB9D0")
                || text.contains("\uAE4C\uBA39\uC9C0\uB9D0")
                || text.contains("\uAE4C\uBA39\uC73C\uBA74 \uC548")
                || text.contains("\uAE4C\uBA39\uC73C\uBA74\uC548")
                || text.contains("\uC78A\uC9C0 \uB9D0")
                || text.contains("\uC78A\uC9C0\uB9D0")
                || text.contains("\uC78A\uC73C\uBA74 \uC548")
                || text.contains("\uC78A\uC73C\uBA74\uC548")
                || text.contains("\uC554\uAE30\uD574\uB450\uB77C\uACE0")
                || text.contains("\uC554\uAE30\uD574\uB454")
                || text.contains("\uC554\uAE30\uD574 \uB46C")
                || text.contains("\uC554\uAE30\uD574 \uB454")
                || text.contains("\uC554\uAE30\uD558\uB77C\uACE0")
                || text.contains("\uC554\uAE30\uD574\uB2EC")
                || text.contains("\uC554\uAE30\uD574 \uB2EC")
                || text.contains("\uC800\uC7A5\uD558\uB77C\uACE0")
                || text.contains("\uC800\uC7A5\uD574\uB450\uB77C\uACE0")
                || text.contains("\uC800\uC7A5\uD574\uB454")
                || text.contains("\uC800\uC7A5\uD574 \uB454")
                || text.contains("\uC800\uC7A5\uD588\uB358")
                || text.contains("\uC800\uC7A5 \uD588\uB358")
                || text.contains("\uC800\uC7A5\uD574\uB2EC")
                || text.contains("\uC800\uC7A5\uD574 \uB2EC"))
                && (text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4"));
        boolean koreanWriteDownValueQuestion = (text.contains("적어두라고")
                || text.contains("\uC801\uC5B4\uB480\uB358")
                || text.contains("\uC801\uC5B4 \uB480\uB358")
                || text.contains("\uC801\uC5B4\uB454")
                || text.contains("\uC801\uC5B4 \uB454")
                || text.contains("\uC801\uC5B4\uB193\uC740")
                || text.contains("\uC801\uC740")
                || text.contains("\uC801\uC5B4\uB1A8\uB358")
                || text.contains("\uC368\uB454")
                || text.contains("\uC368 \uB193\uC740")
                || text.contains("\uC368\uB1A8\uB358")
                || text.contains("\uC368 \uB1A8\uB358")
                || text.contains("\uC368 \uB450\uC5C8\uB358")
                || text.contains("\uC791\uC131\uD574 \uB454")
                || text.contains("\uC791\uC131\uD574\uB454")
                || text.contains("\uAE30\uC785\uD574 \uB454")
                || text.contains("\uAE30\uC785\uD574\uB454")
                || text.contains("\uC785\uB825\uD574 \uB454")
                || text.contains("\uC785\uB825\uD574\uB454")
                || text.contains("\uC785\uB825\uD55C")
                || text.contains("\uD0C0\uC774\uD551\uD574 \uB454")
                || text.contains("\uD0C0\uC774\uD551\uD574\uB454")
                || text.contains("\uD0C0\uC774\uD551\uD55C")
                || text.contains("적어달")
                || text.contains("적어 달"))
                && (text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4"));
        boolean koreanLeaveValueQuestion = (text.contains("\uB0A8\uACA8\uB450\uB77C\uACE0")
                || text.contains("\uB0A8\uACA8\uB46C")
                || text.contains("\uB0A8\uACA8\uB454")
                || text.contains("\uB0A8\uACA8\uB2EC")
                || text.contains("\uB0A8\uACA8 \uB2EC")
                || text.contains("\uB0A8\uAE34")
                || text.contains("\uB0A8\uACA8\uB193\uC73C\uB77C\uACE0")
                || text.contains("\uB0A8\uACA8\uB194")
                || text.contains("\uB0A8\uACA8 \uB194")
                || text.contains("\uB0A8\uACA8\uB193"))
                && (text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4"));
        boolean koreanStoreAwayValueQuestion = (text.contains("\uBCF4\uAD00\uD558\uB77C\uACE0")
                || text.contains("\uBCF4\uAD00\uD574\uB450\uB77C\uACE0")
                || text.contains("\uBCF4\uAD00\uD574\uB454")
                || text.contains("\uBCF4\uAD00\uD574 \uB46C")
                || text.contains("\uBCF4\uAD00\uD574 \uB454")
                || text.contains("\uBCF4\uAD00\uD574\uB2EC")
                || text.contains("\uBCF4\uAD00\uD574 \uB2EC")
                || text.contains("\uBCF4\uC874\uD558\uB77C\uACE0")
                || text.contains("\uBCF4\uC874\uD574\uB450\uB77C\uACE0")
                || text.contains("\uBCF4\uC874\uD574\uB46C")
                || text.contains("\uBCF4\uC874\uD574\uB454")
                || text.contains("\uBCF4\uC874\uD574 \uB46C")
                || text.contains("\uBCF4\uC874\uD574 \uB454")
                || text.contains("\uBCF4\uC874\uD574\uB2EC")
                || text.contains("\uBCF4\uC874\uD574 \uB2EC")
                || text.contains("\uAC04\uC9C1\uD558\uB77C\uACE0")
                || text.contains("\uAC04\uC9C1\uD574\uB450\uB77C\uACE0")
                || text.contains("\uAC04\uC9C1\uD574\uB454")
                || text.contains("\uAC04\uC9C1\uD574 \uB454")
                || text.contains("\uAC04\uC9C1\uD574\uB2EC")
                || text.contains("\uAC04\uC9C1\uD574 \uB2EC")
                || text.contains("\uCC59\uAE30\uB77C\uACE0")
                || text.contains("\uCC59\uACA8\uB450\uB77C\uACE0")
                || text.contains("\uCC59\uACA8\uB454")
                || text.contains("\uCC59\uACA8 \uB46C")
                || text.contains("\uCC59\uACA8 \uB454")
                || text.contains("\uCC59\uACA8\uB2EC")
                || text.contains("\uCC59\uACA8 \uB2EC")
                || text.contains("\uC678\uC6CC\uB450\uB77C\uACE0")
                || text.contains("\uC678\uC6CC\uB454")
                || text.contains("\uC678\uC6CC \uB46C")
                || text.contains("\uC678\uC6CC \uB454")
                || text.contains("\uC678\uC6CC\uB2EC")
                || text.contains("\uC678\uC6CC \uB2EC"))
                && (text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4"));
        boolean koreanAskLaterValueQuestion = containsAny(text, KOREAN_ASK_LATER_VALUE_MARKERS)
                && (text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4"));
        boolean koreanMentionedValueQuestion = asksForKoreanMentionedValueQuestion(text);
        boolean koreanSentValueQuestion = text.contains("\uBCF4\uB0B8")
                && (text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4"));
        boolean koreanMyPreferredLabeledValueQuestion = requestedValueLabel(query) != null
                && (text.contains("\uB0B4\uAC00")
                || text.contains("\uB0B4 ")
                || text.contains("\uB098\uC758")
                || text.contains("\uC81C\uAC00")
                || text.contains("\uC81C "))
                && (text.contains("\uC88B\uC544\uD558\uB294")
                || text.contains("\uC88B\uC544\uD558\uB358")
                || text.contains("\uC120\uD638\uD558\uB294")
                || text.contains("\uC120\uD638\uD558\uB358"))
                && (text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4"));
        boolean koreanRecentLabeledValueOnlyRequest = requestedValueLabel(query) != null
                && (text.contains("\uBC29\uAE08")
                || text.contains("\uC544\uAE4C")
                || text.contains("\uC9C1\uC804")
                || text.contains("\uC55E\uC11C")
                || text.contains("\uC774\uC804"))
                && containsAny(text, KOREAN_REPEAT_REQUEST_MARKERS);
        boolean koreanSessionCodeValueOnlyRequest = asksForCodeOnly(query)
                && (text.contains("\uC9C0\uAE08 \uC138\uC158")
                || text.contains("\uC774\uBC88 \uC138\uC158")
                || text.contains("\uC138\uC158\uC758"))
                && (text.contains("\uB9D0\uD574")
                || text.contains("\uC54C\uB824")
                || text.contains("\uB2F5\uD574")
                || text.contains("\uB300\uB2F5")
                || text.contains("\uBCF4\uC5EC")
                || text.contains("\uD558\uB098\uB9CC"));
        return english || koreanPronounValueRepeat || koreanDeicticWhatValueQuestion
                || koreanRememberedValueQuestion || koreanWriteDownValueQuestion || koreanLeaveValueQuestion
                || koreanStoreAwayValueQuestion || koreanAskLaterValueQuestion
                || koreanMentionedValueQuestion
                || koreanSentValueQuestion
                || koreanMyPreferredLabeledValueQuestion
                || koreanRecentLabeledValueOnlyRequest
                || koreanSessionCodeValueOnlyRequest
                || asksForKoreanFirstMessageRepeat(text)
                || (koreanSubject && koreanAction);
    }

    private static boolean asksForKoreanMentionedValueQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean recentReference = text.contains("\uBC29\uAE08")
                || text.contains("\uC544\uAE4C")
                || text.contains("\uC9C1\uC804")
                || text.contains("\uC774\uC804")
                || text.contains("\uC55E\uC11C");
        boolean mentionedAction = text.contains("\uB9D0\uD55C")
                || text.contains("\uB9D0\uD588\uB358")
                || text.contains("\uC785\uB825\uD55C")
                || text.contains("\uBCF4\uB0B8")
                || text.contains("\uC4F4");
        boolean valueReference = text.contains("\uAC12")
                || text.contains("\uB0B4\uC6A9")
                || text.contains("\uB2E8\uC5B4");
        boolean asksWhat = text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uC5B4\uB5A4");
        return recentReference && mentionedAction && valueReference && asksWhat;
    }

    private static boolean asksForKoreanFirstMessageRepeat(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean firstReference = hasKoreanFirstMessageReference(text);
        boolean messageNoun = containsAny(text, KOREAN_FIRST_MESSAGE_NOUN_MARKERS);
        boolean sentAction = containsAny(text, KOREAN_FIRST_MESSAGE_SENT_ACTION_MARKERS);
        boolean repeatRequest = containsAny(text, KOREAN_REPEAT_REQUEST_MARKERS);
        boolean nounEmbeddedReference = text.contains("\uCCAB \uC9C8\uBB38")
                || text.contains("\uCCAB\uC9C8\uBB38")
                || text.contains("\uCCAB \uBA54\uC2DC\uC9C0")
                || text.contains("\uCCAB\uBA54\uC2DC\uC9C0")
                || text.contains("\uCC98\uC74C \uC9C8\uBB38")
                || text.contains("\uCC98\uC74C \uBA54\uC2DC\uC9C0");
        return firstReference && messageNoun && repeatRequest && (sentAction || nounEmbeddedReference);
    }

    private static boolean hasKoreanFirstMessageReference(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text, KOREAN_FIRST_MESSAGE_REFERENCE_MARKERS)
                || Pattern
                .compile("(?iu)(?:\\uB0B4\\uAC00\\s+|\\uC81C\\uAC00\\s+|\\uB0B4\\s+|\\uC81C\\s+)?\\uCC98\\uC74C(?:\\uC5D0|\\uC73C\\uB85C)?\\s*(?:\\uB0B4\\uAC00\\s+|\\uC81C\\uAC00\\s+|\\uB0B4\\s+|\\uC81C\\s+)?(?:\\uBCF4\\uB0B8|\\uB9D0\\uD55C|\\uC785\\uB825\\uD55C|\\uC4F4)")
                .matcher(text)
                .find();
    }

    private static boolean asksForKoreanDeferredAnswerDirective(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean forNextQuestion = Pattern
                .compile("(?iu)(?:\\uB2E4\\uC74C\\s*\\uC9C8\\uBB38|\\uD6C4\\uC18D\\s*\\uC9C8\\uBB38)(?:\\uC744|\\uB97C)?\\s*(?:\\uC704\\uD574|\\uC704\\uD574\\uC11C|\\uC6A9\\uC73C\\uB85C|\\uC6A9|\\uB54C|\\uC5D0)")
                .matcher(text)
                .find();
        return containsAny(text, KOREAN_NEXT_QUESTION_REFERENCE_MARKERS)
                && (containsAny(text, KOREAN_REPEAT_REQUEST_MARKERS) || forNextQuestion);
    }

    private static boolean asksForEnglishDeferredAnswerDirective(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return Pattern.compile("(?iu)\\b(?:for|in|on)\\s+(?:the\\s+)?next\\s+(?:question|turn|message|prompt)\\b")
                .matcher(text)
                .find()
                || Pattern.compile("(?iu)\\bwhen\\s+i\\s+ask\\s+(?:next|later)\\b")
                .matcher(text)
                .find();
    }

    private static boolean asksForKoreanDeicticWhatValueQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean deictic = text.contains("\uADF8\uAC70")
                || text.contains("\uADF8\uAC83")
                || text.contains("\uC774\uAC70")
                || text.contains("\uC774\uAC83")
                || text.contains("\uADF8 \uAC12")
                || text.contains("\uADF8\uAC12")
                || text.contains("\uC774 \uAC12")
                || text.contains("\uC774\uAC12");
        boolean mentionedValue = (text.contains("\uB9D0\uD588")
                || text.contains("\uB9D0\uD55C")
                || text.contains("\uC598\uAE30\uD588")
                || text.contains("\uC598\uAE30\uD55C")
                || text.contains("\uC774\uC57C\uAE30\uD588")
                || text.contains("\uC774\uC57C\uAE30\uD55C")
                || text.contains("\uC5B8\uAE09\uD588")
                || text.contains("\uC5B8\uAE09\uD55C"))
                && (text.contains("\uAC12")
                || text.contains("\uB0B4\uC6A9")
                || text.contains("\uAC70")
                || text.contains("\uAC83")
                || text.contains("\uC774\uC57C\uAE30"));
        boolean recent = text.contains("\uC544\uAE4C")
                || text.contains("\uBC29\uAE08")
                || text.contains("\uC774\uC804")
                || text.contains("\uC9C1\uC804")
                || text.contains("\uC55E\uC11C")
                || text.contains("\uC804\uC5D0")
                || text.contains("\uADF8\uB54C");
        boolean asksWhat = text.contains("\uBB50")
                || text.contains("\uBB34\uC5C7")
                || text.contains("\uBB50\uC600")
                || text.contains("\uBB50\uC600\uC9C0")
                || text.contains("\uBB50\uB354\uB77C")
                || text.contains("\uBB50\uC57C");
        boolean asksRepeat = text.contains("\uB2E4\uC2DC")
                || text.contains("\uB610")
                || text.contains("\uD55C\uBC88")
                || text.contains("\uD55C \uBC88")
                || text.contains("\uC7AC\uD655\uC778")
                || text.contains("\uB9D0\uD574")
                || text.contains("\uC54C\uB824")
                || text.contains("\uB300\uB2F5")
                || text.contains("\uB2F5\uD574")
                || text.contains("\uBCF4\uC5EC");
        boolean valueOnly = text.contains("\uAC12")
                || text.contains("\uAC12\uB9CC")
                || text.contains("\uB0B4\uC6A9")
                || text.contains("\uB0B4\uC6A9\uB9CC")
                || text.contains("\uB2F5\uB9CC")
                || text.contains("\uB300\uB2F5\uB9CC")
                || asksForCodeOnly(text);
        return (deictic || mentionedValue) && recent && (asksWhat || asksRepeat) && valueOnly;
    }

    private static boolean asksForKoreanPronounValueRepeat(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean pronounValue = text.contains("\uADF8 \uAC12")
                || text.contains("\uADF8\uAC12")
                || text.contains("\uC774 \uAC12")
                || text.contains("\uC774\uAC12")
                || text.contains("\uADF8 \uAC12\uB4E4")
                || text.contains("\uADF8\uAC12\uB4E4")
                || text.contains("\uC774 \uAC12\uB4E4")
                || text.contains("\uC774\uAC12\uB4E4")
                || text.contains("\uADF8\uAC70")
                || text.contains("\uADF8\uAC83")
                || text.contains("\uC774\uAC70")
                || text.contains("\uC774\uAC83");
        boolean repeat = text.contains("\uB2E4\uC2DC")
                || text.contains("\uB610")
                || text.contains("\uD55C\uBC88")
                || text.contains("\uD55C \uBC88")
                || text.contains("\uC7AC\uD655\uC778");
        boolean action = text.contains("\uB9D0\uD574")
                || text.contains("\uC54C\uB824")
                || text.contains("\uB300\uB2F5")
                || text.contains("\uB2F5\uD574")
                || text.contains("\uBCF4\uC5EC");
        return pronounValue && repeat && action;
    }

    private static String previousUserMessageFromHistory(String recentHistory, String currentQuery) {
        if (recentHistory == null || recentHistory.isBlank()) {
            return null;
        }
        String current = normalizeHistoryCompare(currentQuery);
        String[] lines = recentHistory.split("\\R");
        String fallback = null;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.regionMatches(true, 0, "User:", 0, "User:".length())) {
                continue;
            }
            String content = line.substring("User:".length()).trim();
            if (content.isBlank()) {
                continue;
            }
            if (!current.isBlank() && normalizeHistoryCompare(content).equals(current)) {
                continue;
            }
            boolean transientLabelFollowUp = isStandaloneLabelOnlyFollowUp(content);
            if (fallback == null && !transientLabelFollowUp) {
                fallback = content;
            }
            if (!isRecentHistoryQuestion(content) && !transientLabelFollowUp) {
                return content;
            }
        }
        return fallback;
    }

    private static String firstUserMessageFromHistory(String recentHistory, String currentQuery) {
        if (recentHistory == null || recentHistory.isBlank()) {
            return null;
        }
        String current = normalizeHistoryCompare(currentQuery);
        String fallback = null;
        String[] lines = recentHistory.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!line.regionMatches(true, 0, "User:", 0, "User:".length())) {
                continue;
            }
            String content = line.substring("User:".length()).trim();
            if (content.isBlank()) {
                continue;
            }
            if (!current.isBlank() && normalizeHistoryCompare(content).equals(current)) {
                continue;
            }
            boolean transientLabelFollowUp = isStandaloneLabelOnlyFollowUp(content);
            if (fallback == null && !transientLabelFollowUp) {
                fallback = content;
            }
            if (!isRecentHistoryQuestion(content) && !transientLabelFollowUp) {
                return content;
            }
        }
        return fallback;
    }

    private static String firstUserMessageAnswerFromHistory(String recentHistory, String currentQuery) {
        if (recentHistory == null || recentHistory.isBlank()) {
            return null;
        }
        String current = normalizeHistoryCompare(currentQuery);
        String[] lines = recentHistory.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.regionMatches(true, 0, "Assistant:", 0, "Assistant:".length())) {
                continue;
            }
            String content = line.substring("Assistant:".length()).trim();
            String firstMessage = firstUserMessageFromAssistantAnswer(content);
            if (firstMessage == null || firstMessage.isBlank()) {
                continue;
            }
            if (!current.isBlank() && normalizeHistoryCompare(firstMessage).equals(current)) {
                continue;
            }
            return firstMessage;
        }
        return null;
    }

    private static String firstUserMessageFromAssistantAnswer(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String marker = "\uCC98\uC74C \uC0AC\uC6A9\uC790 \uBA54\uC2DC\uC9C0:";
        int index = text.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String value = text.substring(index + marker.length()).trim();
        int sourceIndex = value.indexOf("\uCD9C\uCC98:");
        if (sourceIndex >= 0) {
            value = value.substring(0, sourceIndex).trim();
        }
        return value.isBlank() ? null : value;
    }

    private static boolean isStandaloneLabelOnlyFollowUp(String query) {
        if (query == null || query.isBlank() || !currentTurnMemoryValues(query).isEmpty()) {
            return false;
        }
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        return requestedValueLabel(query) != null
                && (lower.contains(" only")
                || lower.contains(" just ")
                || query.contains("\uB9CC")
                || isBareDeferredLabelQuestion(query));
    }

    private static String normalizeHistoryCompare(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    static void mirrorLocalLlmSmokeHistoryOperatorActionForAgentDebug(
            LocalLlmSmokeHistoryDiagnosticsService smokeHistoryDiagnosticsService) {
        if (smokeHistoryDiagnosticsService == null) {
            return;
        }
        try {
            Map<String, Object> snapshot = smokeHistoryDiagnosticsService.snapshot(1);
            if (Boolean.TRUE.equals(snapshot == null ? null : snapshot.get("reportStale"))) {
                TraceStore.putIfAbsent("llm.localSmoke.operatorAction.stale", true);
                TraceStore.putIfAbsent("llm.localSmoke.operatorAction.evidenceMode", "supporting_stale");
                TraceStore.putIfAbsent("llm.localSmoke.operatorAction.staleReason", "smoke_report_stale");
                return;
            }
            Object latest = snapshot == null ? null : snapshot.get("latest");
            if (!(latest instanceof Map<?, ?> latestMap)) {
                return;
            }
            Object operatorAction = latestMap.get("operatorAction");
            if (!(operatorAction instanceof Map<?, ?> actionMap)) {
                return;
            }
            for (String key : java.util.List.of(
                    "triggered",
                    "triggerReason",
                    "failureClass",
                    "nextAction",
                    "actionScore",
                    "scoreDelta",
                    "negativeSignalCount",
                    "upstreamStatus",
                    "upstreamFailureClass",
                    "upstreamNextAction")) {
                Object raw = actionMap.get(key);
                Object safe = safeLocalLlmOperatorActionValue(key, raw);
                if (safe != null) {
                    TraceStore.putIfAbsent("llm.localSmoke.operatorAction." + key, safe);
                }
            }
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed(
                    "prompt.agentDebugEvidence.localLlmSmokeHistory", ex);
        }
    }

    private static Object safeLocalLlmOperatorActionValue(String key, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String label = SafeRedactor.traceLabel(value);
        return label == null || label.isBlank() ? null : label;
    }

    static void mirrorAgentDbContextAvailabilityForAgentDebug(
            AgentPipelineHealthController pipelineHealthController) {
        if (pipelineHealthController == null) {
            TraceStore.putIfAbsent("agent.dbContext.agentVisible.status", "DISABLED");
            TraceStore.putIfAbsent("agent.dbContext.agentVisible.reason", "agent_db_context_disabled");
            TraceStore.putIfAbsent("agent.dbContext.agentVisible.nextAction",
                    "enable_agent_db_context_for_full_pipeline_health");
            return;
        }
        TraceStore.putIfAbsent("agent.dbContext.agentVisible.status", "OK");
        TraceStore.putIfAbsent("agent.dbContext.agentVisible.reason", "agent_pipeline_health_controller_present");
        TraceStore.putIfAbsent("agent.dbContext.agentVisible.nextAction", "inspect_agent_db_context_pipeline_health");
    }

    private static String composeAgentVisibleDebugFallback(
            java.util.List<dev.langchain4j.data.document.Document> localDocs) {
        return composeAgentVisibleDebugFallback(
                null, AgentVisibleDebugEvidenceBuilder.fromDocuments(localDocs));
    }

    private static String composeAgentVisibleDebugFallback(
            String query,
            java.util.List<dev.langchain4j.data.document.Document> localDocs) {
        return composeAgentVisibleDebugFallback(
                query, AgentVisibleDebugEvidenceBuilder.fromDocuments(localDocs));
    }

    private static String composeAgentVisibleDebugFallback(
            String query,
            AgentVisibleDebugEvidenceBuilder.Snapshot snapshot) {
        if (snapshot == null
                || snapshot.heartbeatText() == null
                || snapshot.heartbeatText().isBlank()) {
            return null;
        }
        if (query != null
                && !query.isBlank()
                && !AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(query)
                && !isExternalProofOnlyAnswerRequest(query)
                && !isSupabaseOperationalJudgmentRequest(query)) {
            return null;
        }
        String summary = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.SUMMARY);
        String browser = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.BROWSER_STATUS);
        String browserEvidenceNeeded = snapshot.value(
                AgentVisibleDebugEvidenceBuilder.Field.BROWSER_EVIDENCE_NEEDED);
        String browserStale = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.BROWSER_STALE);
        String browserBlocking = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.BROWSER_BLOCKING);
        String browserScope = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.BROWSER_SCOPE);
        String computer = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_STATUS);
        String computerEvidenceNeeded = snapshot.value(
                AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_EVIDENCE_NEEDED);
        String computerStale = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_STALE);
        String computerBlocking = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_BLOCKING);
        String computerScope = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_SCOPE);
        String computerCountOnly = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.COMPUTER_COUNT_ONLY);
        String supabase = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.SUPABASE_EVIDENCE_NEEDED);
        String agentDbStatus = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.AGENT_DB_STATUS);
        String agentDbReason = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.AGENT_DB_REASON);
        String agentDbNext = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.AGENT_DB_NEXT_ACTION);
        String localLlmFailure = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.LOCAL_LLM_FAILURE_CLASS);
        String localLlmNext = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.LOCAL_LLM_NEXT_ACTION);
        String localLlmUpstreamStatus = snapshot.value(
                AgentVisibleDebugEvidenceBuilder.Field.LOCAL_LLM_UPSTREAM_STATUS);
        String localLlmUpstreamFailure = snapshot.value(
                AgentVisibleDebugEvidenceBuilder.Field.LOCAL_LLM_UPSTREAM_FAILURE_CLASS);
        String localLlmUpstreamNext = snapshot.value(
                AgentVisibleDebugEvidenceBuilder.Field.LOCAL_LLM_UPSTREAM_NEXT_ACTION);
        String matrixCount = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.MATRIX_COUNT);
        String matrixChunks = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.MATRIX_CHUNKS);
        String matrixDecision = snapshot.value(AgentVisibleDebugEvidenceBuilder.Field.MATRIX_DECISION);

        if (isLocalExternalMarkerProbe(query) || isLocalFeaturePoolEvidenceProbe(query)) {
            return composeSupabaseLocalExternalMarkerFallback(
                    browser,
                    browserEvidenceNeeded,
                    browserStale,
                    computer,
                    computerEvidenceNeeded,
                    computerStale,
                    computerCountOnly,
                    defaultText(supabase, "evidence_needed"));
        }
        if (isExternalProofOnlyAnswerRequest(query)) {
            return composeExternalProofOnlyDebugFallback(
                    browser,
                    browserEvidenceNeeded,
                    browserStale,
                    computer,
                    computerEvidenceNeeded,
                    computerStale,
                    computerCountOnly,
                    supabase);
        }
        if (isSupabaseOperationalJudgmentRequest(query)) {
            return composeSupabaseOperationalJudgmentFallback(
                    query,
                    browser,
                    browserEvidenceNeeded,
                    browserStale,
                    computer,
                    computerEvidenceNeeded,
                    computerStale,
                    computerCountOnly,
                    supabase,
                    agentDbStatus,
                    agentDbReason,
                    agentDbNext);
        }

        StringBuilder out = new StringBuilder(512);
        out.append("현재 디버그 heartbeat 기준 상태입니다.\n");
        if (summary != null && !summary.isBlank()) {
            out.append("- 요약: ").append(summary).append('\n');
        }
        out.append("- 챗봇: local debug evidence가 프롬프트 경로에 주입되어 agent-visible evidence fallback으로 응답 중입니다.\n");
        appendExternalProofLine(out, "Browser", browser, browserEvidenceNeeded, browserStale,
                browserBlocking, browserScope);
        appendExternalProofLine(out, "Computer", computer, computerEvidenceNeeded, computerStale,
                computerBlocking, computerScope);
        out.append("- Supabase: ").append(defaultText(supabase, "evidence_needed")).append('\n');
        if (agentDbStatus != null || agentDbReason != null || agentDbNext != null) {
            out.append("- DB context: ").append(defaultText(agentDbStatus, "UNKNOWN"))
                    .append(" reason=").append(defaultText(agentDbReason, "unknown"))
                    .append(" next=").append(defaultText(agentDbNext, "unknown"))
                    .append('\n');
        }
        out.append("- Debug matrix: ").append(defaultText(matrixCount, "0"))
                .append(" virtual matrices / ").append(defaultText(matrixChunks, "0"))
                .append(" chunks, decision=").append(defaultText(matrixDecision, "unknown")).append('\n');
        if (localLlmFailure != null || localLlmUpstreamFailure != null || localLlmNext != null || localLlmUpstreamNext != null) {
            out.append("- LLM upstream: ")
                    .append(defaultText(localLlmUpstreamFailure, defaultText(localLlmFailure, "unknown")))
                    .append(" status=").append(defaultText(localLlmUpstreamStatus, "unknown"))
                    .append(" next=").append(defaultText(localLlmUpstreamNext, defaultText(localLlmNext, "unknown")))
                    .append('\n');
        }
        out.append("\n근거: local://agent-visible-debug/1");
        return SafeRedactor.safeMessage(out.toString(), 1_200);
    }

    private static final Pattern SUPABASE_PROJECT_REF_MARKER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])project(?:_|\\s+)ref(?![\\p{L}\\p{N}_])");
    private static final Pattern SUPABASE_OPERATIONAL_INTENT_MARKER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:defer(?:red)?|(?:dis)?allow(?:ed)?|operational|judgment|decision)(?![\\p{L}\\p{N}_])");

    public static boolean isSupabaseOperationalJudgmentRequest(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean mentionsSupabaseBoundary = text.contains("supabase")
                || SUPABASE_PROJECT_REF_MARKER.matcher(text).find()
                || text.contains("\uC218\uD37C\uBCA0\uC774\uC2A4");
        if (!mentionsSupabaseBoundary) {
            return false;
        }
        if (isConditionalRetrievalQualityInstruction(text, query)) {
            return false;
        }
        return SUPABASE_OPERATIONAL_INTENT_MARKER.matcher(text).find()
                || isLocalExternalMarkerProbe(text)
                || isLocalFeaturePoolEvidenceProbe(text)
                || text.contains("\uBCF4\uB958")
                || text.contains("\uD5C8\uC6A9")
                || text.contains("\uC6B4\uC601")
                || text.contains("\uD310\uB2E8");
    }

    private static String composeSupabaseOperationalJudgmentFallback(
            String query,
            String browser,
            String browserEvidenceNeeded,
            String browserStale,
            String computer,
            String computerEvidenceNeeded,
            String computerStale,
            String computerCountOnly,
            String supabase,
            String agentDbStatus,
            String agentDbReason,
            String agentDbNext) {
        String supabaseReason = defaultText(supabase, "evidence_needed");
        if (isLocalExternalMarkerProbe(query) || isLocalFeaturePoolEvidenceProbe(query)) {
            return composeSupabaseLocalExternalMarkerFallback(
                    browser,
                    browserEvidenceNeeded,
                    browserStale,
                    computer,
                    computerEvidenceNeeded,
                    computerStale,
                    computerCountOnly,
                    supabaseReason);
        }
        StringBuilder out = new StringBuilder(1_000);
        out.append("Supabase operational judgment (local debug evidence):\n");
        out.append("Deferred:\n");
        out.append("1. live Supabase schema/advisor reads - evidence_needed: ")
                .append(supabaseReason).append(".\n");
        out.append("2. live SQL, migration, auth, storage, realtime, or vector DB mutations - project/auth proof is missing.\n");
        out.append("3. Supabase-backed RAG/vector validation - no project-scoped DB result is verified.\n");
        out.append("4. DB context enrichment - status=")
                .append(defaultText(agentDbStatus, "UNKNOWN"))
                .append(" reason=").append(defaultText(agentDbReason, "unknown"))
                .append(" next=").append(defaultText(agentDbNext, "unknown")).append(".\n");
        out.append("5. completion claims from Supabase evidence - keep them as evidence_needed/supporting.\n");
        out.append("Allowed:\n");
        out.append("1. Desktop chat, RAG UI, and listener probing - Browser/Computer local UI proof refresh is supporting.\n");
        out.append("2. read-only source_scan, heartbeat, and debug trace checks - no Supabase mutation required.\n");
        out.append("3. count-only Computer proof and Browser localhost proof - browser=")
                .append(defaultText(browser, "unknown"))
                .append(" needed=").append(defaultText(browserEvidenceNeeded, "none"))
                .append(" stale=").append(defaultText(browserStale, "unknown"))
                .append("; computer=").append(defaultText(computer, "unknown"))
                .append(" needed=").append(defaultText(computerEvidenceNeeded, "none"))
                .append(" stale=").append(defaultText(computerStale, "unknown"))
                .append(" countOnly=").append(defaultText(computerCountOnly, "unknown")).append(".\n");
        out.append("Evidence: ").append(supabaseReason)
                .append("; local://agent-visible-debug/1");
        return SafeRedactor.safeMessage(out.toString(), 1_600);
    }

    private static boolean isLocalExternalMarkerProbe(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        return text.contains("[local]")
                && text.contains("[external]")
                && (text.contains("evidence_needed") || text.contains("missing"));
    }

    private static boolean isLocalFeaturePoolEvidenceProbe(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean featurePool = text.contains("feature pool")
                || text.contains("feature-pool")
                || text.contains("featurepool");
        boolean authMode = text.contains("login/logout")
                || (text.contains("login") && text.contains("logout"))
                || text.contains("auth disabled")
                || text.contains("disabled-test");
        boolean asksLocalEvidenceSplit = text.contains("evidence lane")
                || text.contains("local/external")
                || text.contains("local evidence")
                || text.contains("external")
                || text.contains("status")
                || text.contains("diagnos")
                || text.contains("open")
                || text.contains("disabled");
        return featurePool && authMode && asksLocalEvidenceSplit;
    }

    private static String composeSupabaseLocalExternalMarkerFallback(
            String browser,
            String browserEvidenceNeeded,
            String browserStale,
            String computer,
            String computerEvidenceNeeded,
            String computerStale,
            String computerCountOnly,
            String supabaseReason) {
        StringBuilder out = new StringBuilder(900);
        out.append("[local] feature pool open: Desktop chat UI/vibe probes can stay enabled from local evidence.\n");
        out.append("[local] login/logout disabled: authMode disabled-test keeps login/logout disabled for this test pass.\n");
        out.append("[local] ops nav Brain/Pipeline/RAG Ops/Vector/Models: auth disabled test mode keeps protected surfaces guarded; ")
                .append("clickability is local UI evidence until admin/model HTTP proof is opened.\n");
        out.append("[local] Browser localhost proof and Computer count-only proof: browser=")
                .append(defaultText(browser, "unknown"))
                .append(" needed=").append(defaultText(browserEvidenceNeeded, "none"))
                .append(" stale=").append(defaultText(browserStale, "unknown"))
                .append("; computer=").append(defaultText(computer, "unknown"))
                .append(" needed=").append(defaultText(computerEvidenceNeeded, "none"))
                .append(" stale=").append(defaultText(computerStale, "unknown"))
                .append(" countOnly=").append(defaultText(computerCountOnly, "unknown")).append(".\n");
        out.append("[external] Supabase live schema/advisor reads: evidence_needed project_ref/auth missing; ")
                .append(supabaseReason).append(".\n");
        out.append("[external] Supabase SQL/auth/storage/realtime/vector mutations: evidence_needed until project-scoped read-only proof exists.\n");
        out.append("[external] Mac mini/Notebook/PatchDrop producer sidecars: supporting only unless current sidecar evidence is present.\n");
        out.append("Evidence: ").append(supabaseReason).append("; local://agent-visible-debug/1");
        return SafeRedactor.safeMessage(out.toString(), 1_600);
    }

    private static boolean isExternalProofOnlyAnswerRequest(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (!(text.contains("browser") && text.contains("computer") && text.contains("supabase"))) {
            return false;
        }
        boolean oneParagraphEvidenceStatus = (text.contains("one paragraph")
                || text.contains("paragraph")
                || text.contains("\uD55C \uBB38\uB2E8")
                || text.contains("\uD55C\uBB38\uB2E8"))
                && (text.contains("evidence status")
                || text.contains("proof status")
                || text.contains("\uC99D\uAC70 \uC0C1\uD0DC"));
        boolean laneLocalExternalSplit = text.contains("lane")
                && (text.contains("local proof") || text.contains("local evidence"))
                && (text.contains("external evidence")
                || text.contains("external proof")
                || text.contains("evidence_needed"))
                && (text.contains("repo") || text.contains("command") || text.contains("\uBA85\uB839"));
        boolean proofLaneSplit = text.contains("lane")
                && (text.contains("proof") || text.contains("evidence") || text.contains("evidence_needed"))
                && (text.contains("split")
                || text.contains("separate")
                || text.contains("separately")
                || text.contains("\uBD84\uB9AC")
                || text.contains("\uAC01\uAC01"));
        return text.contains("3")
                || text.contains("three")
                || text.contains("only")
                || text.contains("\uC0C1\uD0DC\uB9CC")
                || text.contains("\uC904\uB85C")
                || text.contains("\uC904\uB9CC")
                || text.contains("\uC694\uC57D")
                || oneParagraphEvidenceStatus
                || laneLocalExternalSplit
                || proofLaneSplit;
    }

    private static String composeExternalProofOnlyDebugFallback(
            String browser,
            String browserEvidenceNeeded,
            String browserStale,
            String computer,
            String computerEvidenceNeeded,
            String computerStale,
            String computerCountOnly,
            String supabaseEvidenceNeeded) {
        StringBuilder out = new StringBuilder(240);
        out.append("- Browser: ")
                .append(defaultText(browser, "UNKNOWN"))
                .append(" (evidence_needed=")
                .append(userFacingExternalEvidenceNeeded("browser", browserEvidenceNeeded))
                .append("; stale=")
                .append(defaultText(browserStale, "unknown"))
                .append(")\n");
        out.append("- Computer: ")
                .append(defaultText(computer, "UNKNOWN"))
                .append(" (evidence_needed=")
                .append(userFacingExternalEvidenceNeeded("computer", computerEvidenceNeeded))
                .append("; stale=")
                .append(defaultText(computerStale, "unknown"));
        if (computerCountOnly != null && !computerCountOnly.isBlank()) {
            out.append("; count-only=").append(computerCountOnly);
        }
        out.append(")\n");

        String supabase = userFacingExternalEvidenceNeeded("supabase", supabaseEvidenceNeeded);
        out.append("- Supabase: ");
        if (isNoEvidenceNeeded(supabase)) {
            out.append("OK (read-only proof current)");
        } else {
            out.append("evidence_needed (")
                    .append(supabase)
                    .append("; read-only only; DB verification not claimed)");
        }
        return safeMultilineMessage(out.toString(), 600);
    }

    private static String userFacingExternalEvidenceNeeded(String lane, String evidenceNeeded) {
        String value = defaultText(evidenceNeeded, "unknown");
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("browser".equals(lane)
                && (normalized.equals("run_browser_local_ui_smoke")
                || normalized.equals("rerun_browser_local_ui_smoke")
                || normalized.equals("start_local_server_then_rerun_browser_local_ui_smoke"))) {
            return "browser_ui_smoke:evidence_needed";
        }
        if ("computer".equals(lane)
                && (normalized.equals("run_computer_use_lightweight_smoke")
                || normalized.equals("rerun_computer_use_lightweight_smoke"))) {
            return "computer_use_smoke:evidence_needed";
        }
        if ("supabase".equals(lane)
                && (normalized.equals("inspect_supabase_shadow_snapshot")
                || normalized.equals("run_supabase_schema_snapshot"))) {
            return "supabase_context_probe:evidence_needed";
        }
        return value;
    }

    public static boolean isCurrentModeStatusRequest(String query) {
        String text = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        if (isExternalProofOnlyAnswerRequest(text)) {
            return false;
        }
        if (isProjectFeatureSourceMarkerInventoryRequest(text)) {
            return false;
        }
        if (isConditionalRetrievalQualityInstruction(text, text)) {
            return false;
        }
        boolean mentionsSearch = text.contains("search")
                || text.contains("web search")
                || text.contains("websearch")
                || text.contains("\uAC80\uC0C9");
        boolean mentionsRag = text.contains("rag")
                || text.contains("vector")
                || text.contains("\uBCA1\uD130")
                || text.contains("\uAC80\uC0C9\uC99D\uAC15");
        boolean mentionsMode = containsEnglishWord(text, "mode")
                || containsEnglishWord(text, "toggle")
                || containsEnglishWord(text, "rail")
                || text.contains("\uBAA8\uB4DC")
                || text.contains("\uD1A0\uAE00");
        boolean mentionsUiSurface = text.contains("ui")
                || text.contains("screen")
                || text.contains("control")
                || text.contains("checkbox")
                || text.contains("check box")
                || text.contains("\uD654\uBA74")
                || text.contains("\uCEE8\uD2B8\uB864")
                || text.contains("\uCCB4\uD06C\uBC15\uC2A4");
        boolean asksStatus = text.contains("current")
                || text.contains("status")
                || text.contains("check")
                || text.contains("enabled")
                || containsEnglishWord(text, "on")
                || containsEnglishWord(text, "off")
                || text.contains("evidence")
                || text.contains("\uD604\uC7AC")
                || text.contains("\uC0C1\uD0DC")
                || text.contains("\uD655\uC778")
                || text.contains("\uC99D\uAC70")
                || text.contains("\uCF1C")
                || text.contains("\uAEBC");
        if (isModeQualifiedContentRequest(text)) {
            return false;
        }
        return asksStatus
                && (mentionsSearch || mentionsRag)
                && (mentionsMode || mentionsUiSurface || (mentionsSearch && mentionsRag));
    }

    private static boolean isProjectFeatureSourceMarkerInventoryRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean projectScoped = text.contains("dynamic rag orchestration platform")
                || text.contains("demo-1")
                || text.contains("abandonware");
        if (!projectScoped) {
            return false;
        }
        int featureHits = 0;
        if (text.contains("plan dsl")) featureHits++;
        if (text.contains("moe strategy") || text.contains("mixture-of-experts")) featureHits++;
        if (text.contains("graphrag") || text.contains("graph rag")) featureHits++;
        if (text.contains("cfvm")) featureHits++;
        if (text.contains("hypernova")) featureHits++;
        if (text.contains("matryoshka")) featureHits++;
        boolean asksForSourceMarkers = text.contains("source marker")
                || text.contains("source-marker")
                || text.contains("evidence_needed")
                || text.contains("project-local")
                || text.contains("local source");
        return featureHits >= 2 && asksForSourceMarkers;
    }

    private static boolean containsEnglishWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return false;
        }
        int from = 0;
        while (from < text.length()) {
            int idx = text.indexOf(word, from);
            if (idx < 0) {
                return false;
            }
            int end = idx + word.length();
            boolean leftBoundary = idx == 0 || !isEnglishWordChar(text.charAt(idx - 1));
            boolean rightBoundary = end >= text.length() || !isEnglishWordChar(text.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = end;
        }
        return false;
    }

    private static boolean isEnglishWordChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '_';
    }

    private static boolean isModeQualifiedContentRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean explicitSearchRagModeReadback = (text.contains("search/rag")
                || (text.contains("search") && text.contains("rag")))
                && (text.contains("mode") || text.contains("\uBAA8\uB4DC") || text.contains("\uC124\uC815"))
                && (text.contains("current")
                || text.contains("\uD604\uC7AC")
                || text.contains("\uC9C0\uAE08")
                || text.contains("\uBC29\uAE08")
                || text.contains("\uC124\uC815 \uAE30\uC900"));
        if (explicitSearchRagModeReadback) {
            return false;
        }
        boolean asksAnswerOrAdvice = text.contains("answer")
                || text.contains("explain")
                || text.contains("summarize")
                || text.contains("how to")
                || text.contains("tip")
                || text.contains("recommend")
                || text.contains("list")
                || text.contains("defer")
                || text.contains("deferred")
                || text.contains("allowed")
                || text.contains("allow")
                || text.contains("operational")
                || text.contains("judgment")
                || text.contains("\uB2F5\uD574")
                || text.contains("\uB2F5\uBCC0")
                || text.contains("\uBC29\uBC95")
                || text.contains("\uC124\uBA85")
                || text.contains("\uC694\uC57D")
                || text.contains("\uCD94\uCC9C");
        if (!asksAnswerOrAdvice) {
            return false;
        }
        boolean asksChatAction = text.contains("enter")
                || text.contains("send")
                || text.contains("submit")
                || text.contains("stop")
                || text.contains("cancel")
                || text.contains("abort")
                || text.contains("composer")
                || text.contains("\uC804\uC1A1")
                || text.contains("\uC785\uB825")
                || text.contains("\uC911\uC9C0")
                || text.contains("\uCDE8\uC18C")
                || text.contains("\uC815\uC9C0")
                || text.contains("\uBA48\uCD94");
        if (asksChatAction) {
            return true;
        }
        boolean asksCitationMarkerOutput = text.contains("cite")
                || text.contains("[w")
                || text.contains("[v")
                || text.contains("\uB05D\uC5D0")
                || text.contains("\uBD99\uC5EC")
                || text.contains("\uBD99\uC5EC\uC918");
        if (asksCitationMarkerOutput) {
            return true;
        }
        boolean asksFeatureOrOperationalExplanation = text.contains("feature")
                || text.contains("function")
                || text.contains("provider")
                || text.contains("guard")
                || text.contains("trace")
                || text.contains("supabase")
                || text.contains("mcp")
                || text.contains("defer")
                || text.contains("deferred")
                || text.contains("\uAE30\uB2A5")
                || text.contains("\uC5ED\uD560")
                || text.contains("\uBCF4\uB958");
        if (asksFeatureOrOperationalExplanation) {
            return true;
        }
        boolean explicitModeStatusCheck = text.contains("status")
                || text.contains("check")
                || text.contains("enabled")
                || text.contains("disabled")
                || text.contains("whether")
                || text.contains("what is")
                || text.contains("which")
                || text.contains("evidence")
                || text.contains("\uC0C1\uD0DC")
                || text.contains("\uD655\uC778")
                || text.contains("\uC99D\uAC70")
                || text.contains("\uCF1C")
                || text.contains("\uAEBC");
        if (explicitModeStatusCheck) {
            return false;
        }
        boolean asksStructuredContentOutput = (text.contains("sentence")
                || text.contains("paragraph")
                || text.contains("\uBB38\uC7A5"))
                && (text.contains("explain")
                || text.contains("summarize")
                || text.contains("how to")
                || text.contains("tip")
                || text.contains("recommend")
                || text.contains("\uC124\uBA85")
                || text.contains("\uBC29\uBC95")
                || text.contains("\uC694\uC57D")
                || text.contains("\uCD94\uCC9C"));
        boolean asksBodyOutputShape = asksStructuredContentOutput;
        if (asksBodyOutputShape) {
            return true;
        }
        return true;
    }

    private static String composeCurrentModeStatusFallback(String query, ChatRequestDto request) {
        if (!isCurrentModeStatusRequest(query)) {
            return null;
        }
        com.example.lms.gptsearch.dto.SearchMode searchMode =
                request != null && request.getSearchMode() != null
                        ? request.getSearchMode()
                        : com.example.lms.gptsearch.dto.SearchMode.AUTO;
        boolean requestedWebSearch = request != null && request.isUseWebSearch();
        boolean effectiveWebSearch = requestedWebSearch
                && searchMode != com.example.lms.gptsearch.dto.SearchMode.OFF;
        boolean useRag = request != null && request.isUseRag();
        boolean koreanPrompt = containsHangul(query);
        StringBuilder out = new StringBuilder(240);
        out.append(koreanPrompt
                ? "\uD604\uC7AC UI \uBAA8\uB4DC \uC0C1\uD0DC (\uC694\uCCAD \uAE30\uC900 \uB85C\uCEEC \uC99D\uAC70):\n"
                : "Current UI mode status (request-local evidence):\n");
        out.append("- Search: ").append(displaySearchMode(searchMode))
                .append(" (webSearch=").append(effectiveWebSearch)
                .append("; requested=").append(requestedWebSearch)
                .append(")\n");
        out.append("- RAG: ").append(useRag ? "ON" : "OFF")
                .append(" (useRag=").append(useRag)
                .append(")\n");
        out.append(koreanPrompt
                ? "\uCD9C\uCC98: \uD604\uC7AC \uC694\uCCAD \uCEE8\uD2B8\uB864\uB9CC \uC0AC\uC6A9; \uC678\uBD80 \uC99D\uAC70\uB294 \uC8FC\uC7A5\uD558\uC9C0 \uC54A\uC74C."
                : "Source: current request controls; no external proof claimed.");
        return safeMultilineMessage(out.toString(), 500);
    }

    private static String displaySearchMode(com.example.lms.gptsearch.dto.SearchMode searchMode) {
        if (searchMode == com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT) {
            return "LIGHT";
        }
        if (searchMode == com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP) {
            return "DEEP";
        }
        return searchMode == null ? "AUTO" : searchMode.name();
    }

    private static boolean isNoEvidenceNeeded(String value) {
        String label = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return label.isBlank() || "none".equals(label) || "null".equals(label) || "ok".equals(label);
    }

    private static String safeMultilineMessage(String value, int maxStringLen) {
        if (value == null) {
            return null;
        }
        String redacted = SafeRedactor.redact(value);
        String trimmed = redacted == null ? "" : redacted.trim();
        int limit = Math.max(0, maxStringLen);
        return limit > 0 && trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void appendExternalProofLine(StringBuilder out,
            String label,
            String status,
            String evidenceNeeded,
            String stale,
            String blocking,
            String scope) {
        String safeStatus = defaultText(status, "UNKNOWN");
        out.append("- ").append(label).append(" proof: ").append(safeStatus);
        if ("SUPPORTING_EVIDENCE_MISSING".equals(safeStatus)) {
            out.append(" (optional supporting evidence; not blocking Desktop chat");
            if (scope != null && !scope.isBlank()) {
                out.append("; scope=").append(scope);
            }
            out.append("; blocking=").append(defaultText(blocking, "false")).append(')');
        } else if ((scope != null && !scope.isBlank()) || (blocking != null && !blocking.isBlank())) {
            out.append(" (scope=").append(defaultText(scope, "unknown"))
                    .append("; blocking=").append(defaultText(blocking, "unknown")).append(')');
        }
        out.append('\n');
        out.append("- ").append(label).append(" evidence_needed: ")
                .append(defaultText(evidenceNeeded, "unknown"))
                .append(" stale=").append(defaultText(stale, "unknown")).append('\n');
    }

    private void recordFinalRescueSilentFailure(String finalQuery,
            String breakerKey,
            int evidenceCount,
            String reason) {
        String safeReason = (reason == null || reason.isBlank()) ? "final_rescue" : reason;
        String queryHash = SafeRedactor.hashValue(finalQuery);
        int queryLen = finalQuery == null ? 0 : finalQuery.length();
        int count = Math.max(0, evidenceCount);
        try {
            TraceStore.put("nightmare.finalRescue.used", true);
            TraceStore.put("nightmare.finalRescue.reason", safeReason);
            TraceStore.put("nightmare.finalRescue.evidenceCount", count);
            TraceStore.put("nightmare.finalRescue.queryHash", queryHash);
            TraceStore.put("nightmare.finalRescue.queryLen", queryLen);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalRescue.trace", ignore);
        }
        try {
            if (nightmareBreaker != null) {
                String key = (breakerKey == null || breakerKey.isBlank()) ? NightmareKeys.CHAT_DRAFT : breakerKey;
                String context = "component=ChatWorkflow;stage=finalRescue;evidenceCount=" + count
                        + ";queryLen=" + queryLen;
                nightmareBreaker.signalSilentFailure(key, context, safeReason);
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalRescue.breaker", ignore);
        }
    }

    private boolean shouldUseChatDraftConfigBreakerFallback(String breakerKey) {
        if (nightmareBreaker == null || breakerKey == null || breakerKey.isBlank()) {
            return false;
        }
        try {
            NightmareBreaker.StateView view = nightmareBreaker.inspect(breakerKey);
            if (view == null
                    || !view.open
                    || view.lastKind != NightmareBreaker.FailureKind.CONFIG) {
                return false;
            }
            TraceStore.put("llm.chatDraft.configBreakerOpenFallback", true);
            TraceStore.put("llm.chatDraft.configBreakerKeyHash", SafeRedactor.hashValue(breakerKey));
            TraceStore.put("llm.chatDraft.configBreakerRemainingMs", Math.max(0L, view.remainingMs));
            recordLocalLlmOperatorAction(
                    "llm_config_breaker_open",
                    "model_configuration_missing",
                    "inspect_model_route_or_start_local_llm",
                    100,
                    1);
            return true;
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("chat.draftConfigBreakerFallback", ex);
            return false;
        }
    }

    private static String finalQueryTraceContext(String finalQuery) {
        return "finalQueryHash=" + SafeRedactor.hashValue(finalQuery)
                + ";finalQueryLen=" + (finalQuery == null ? 0 : finalQuery.length());
    }

    private String webOnlyDraft(String query, String ctx) {
        var title = "?붿빟 珥덉븞(LLM-OFF)";
        var bullet = (ctx == null || ctx.isBlank()) ? "- context none" : "- context summary available";
        return title + "\n" + bullet + "\n- 吏덉쓽: " + query;
    }

    private List<ChatMessage> buildPostOrchestrationPromptMessages(String stage, String system, String user) {
        PromptContext ctx = PromptContext.builder()
                .systemInstruction(system == null ? "" : system)
                .userQuery(user == null ? "" : user)
                .build();
        String built = promptBuilder.build(ctx);
        try {
            TraceStore.put("prompt.postOrchestration.usesPromptBuilder", true);
            TraceStore.put("prompt.postOrchestration.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("prompt.builderRequired", ignore);
            }
        return List.of(UserMessage.from(built));
    }

    private static void appendSystemBlock(StringBuilder target, String block) {
        if (target == null || block == null || block.isBlank()) {
            return;
        }
        if (!target.isEmpty() && target.charAt(target.length() - 1) != '\n') {
            target.append('\n');
        }
        target.append(block.strip()).append("\n");
    }

    /**
     * [Dual-Vision] View2 Free-Idea 珥덉븞 ?앹꽦
     * STRICT ?듬? ?댄썑, ??꾪뿕 ?꾨찓?몄뿉?쒕쭔 ?몄텧
     */
    private String generateFreeIdeaDraft(
            String userQuery,
            String strictAnswer,
            String ctxText,
            ModelRouter modelRouter,
            com.example.lms.service.verbosity.VerbosityProfile vp,
            String requestedModel) {
        // Free-Idea??紐⑤뜽 ?좏깮 (?⑤룄 ??
        ChatModel creativeModel = modelRouter.route(
                "FREE_IDEA",
                "LOW", // 由ъ뒪????쾶 媛뺤젣
                "deep",
                vp != null ? vp.targetTokenBudgetOut() : 2048,
                requestedModel);
        String sys = """
                You are Jammini's View2 (Free-Idea mode).
                - The strict answer has already been generated.
                - Your job is to propose CREATIVE, SPECULATIVE ideas,
                  alternative angles, or story-style elaborations.
                - Mark clearly that this part is '異붿륫/鍮꾧났???꾩씠?붿뼱'.
                - Do NOT contradict hard facts from strict answer.
                - ?듬?? ?쒓뎅?대줈, 吏㏃? ?⑤씫 2~3媛??대궡.
                """;
        String user = """
                [USER QUESTION]
                %s
                [STRICT ANSWER]
                %s
                [OPTIONAL CONTEXT SUMMARY]
                %s
                """.formatted(userQuery, strictAnswer, truncate(ctxText, 1500));
        List<ChatMessage> msgs = buildPostOrchestrationPromptMessages(
                "projection.free-idea.legacy", sys, user);
        try {
            return creativeModel.chat(msgs).aiMessage().text();
        } catch (Exception e) {
            log.debug("[FreeIdea] creative draft failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            return null;
        }
    }

    /**
     * projection_agent.v1.yaml: View2 (free projection) branch.
     *
     * This is a plan-driven variant of
     * {@link #generateFreeIdeaDraft(String, String, String, ModelRouter, VerbosityProfile, String)}
     * that supports plan-defined model/traits/maxTokens.
     */
    private String generateProjectionDraftFromPlan(
            String userQuery,
            String strictAnswer,
            String ctxText,
            VerbosityProfile vp,
            ChatRequestDto baseReq,
            com.example.lms.service.rag.plan.ProjectionAgentPlanSpec planSpec) {
        if (planSpec == null || planSpec.viewFreeProjection() == null)
            return null;
        var cfg = planSpec.viewFreeProjection();

        String resolvedModel = null;
        if (planModelResolver != null) {
            resolvedModel = planModelResolver.resolveRequestedModel(cfg.model());
        }

        // If the plan says "auto"/blank, let the router pick.
        String requestedModel = (resolvedModel == null || resolvedModel.isBlank()
                || "auto".equalsIgnoreCase(resolvedModel))
                        ? ""
                        : resolvedModel;

        int tokenHint = (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                ? cfg.maxTokens()
                : Math.max(512, vp.targetTokenBudgetOut());

        var creativeModel = modelRouter.route(
                "FREE_IDEA",
                detectRisk(userQuery),
                vp.hint(),
                tokenHint,
                requestedModel);

        ChatRequestDto stepReq = baseReq;
        try {
            if (baseReq != null) {
                var b = baseReq.toBuilder();
                if (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                    b.maxTokens(cfg.maxTokens());
                if (cfg.traits() != null && !cfg.traits().isEmpty())
                    b.traits(cfg.traits());
                if (StringUtils.hasText(resolvedModel) && !"auto".equalsIgnoreCase(resolvedModel))
                    b.model(resolvedModel);
                stepReq = b.build();
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("projection.freeIdea.stepRequest", ignore); stepReq = baseReq;
        }

        String clippedCtx = truncate(ctxText, 1800);

        StringBuilder system = new StringBuilder("""
                ?덈뒗 '??踰덉㎏ ?쒖젏(View2)'?먯꽌 ?듯븯???꾨줈?앹뀡 ?먯씠?꾪듃?대떎.

                - ?꾨옒 'STRICT ANSWER'??洹쇨굅 湲곕컲 1李??듬??대떎.
                - ?덈뒗 洹??듬???諛뷀깢?쇰줈 **?먯깋??媛???꾩씠?붿뼱/?쒕굹由ъ삤**瑜??쒖븞?쒕떎.
                - ?? ?ъ떎泥섎읆 ?⑥젙?섏? 留먭퀬, 遺덊솗?ㅽ븯硫?遺덊솗?ㅽ븯?ㅺ퀬 ?쒖떆?쒕떎.
                - ?꾪뿕/?섎즺/踰뺣쪧/湲덉쟾 ??怨좎쐞???곸뿭?먯꽌???덉쟾??踰붿쐞?먯꽌 ?쇰컲濡좎쑝濡쒕쭔 留먰븯怨?
                  ?꾨Ц 議곗뼵???泥댄븯吏 ?딅뒗?ㅺ퀬 紐낆떆?쒕떎.
                - 異쒕젰? ?쒓뎅?대줈.
                """);

        if (promptAssetService != null) {
            String traitSys = promptAssetService.renderTraits(cfg.traits());
            if (StringUtils.hasText(traitSys)) {
                appendSystemBlock(system, traitSys);
            }
        }

        String user = """
                [QUESTION]
                %s

                [STRICT ANSWER]
                %s

                [OPTIONAL CONTEXT SUMMARY]
                %s
                """.formatted(userQuery, strictAnswer, clippedCtx);
        List<ChatMessage> msgs = buildPostOrchestrationPromptMessages(
                "projection.free-idea.plan", system.toString(), user);

        try {
            ChatRequestDto exploreReq = applyExploreSamplingCaps(stepReq);
            return callWithRetry(creativeModel, msgs, exploreReq).trim();
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("projection.freeIdea.draft", e); log.debug("[ProjectionAgent] free projection draft failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            return null;
        }
    }

    /**
     * projection_agent.v1.yaml: Final pass (polish merged answer).
     */
    private String finalizeProjectionAnswerFromPlan(
            String userQuery,
            String mergedAnswer,
            VerbosityProfile vp,
            ChatRequestDto baseReq,
            com.example.lms.service.rag.plan.ProjectionAgentPlanSpec planSpec) {
        if (planSpec == null || planSpec.finalAnswer() == null)
            return mergedAnswer;
        var cfg = planSpec.finalAnswer();

        String resolvedModel = null;
        if (planModelResolver != null) {
            resolvedModel = planModelResolver.resolveRequestedModel(cfg.model());
        }
        String requestedModel = (resolvedModel == null || resolvedModel.isBlank()
                || "auto".equalsIgnoreCase(resolvedModel))
                        ? ""
                        : resolvedModel;

        int tokenHint = (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                ? cfg.maxTokens()
                : Math.max(768, vp.targetTokenBudgetOut());

        var finalModel = modelRouter.route(
                "FINAL_ANSWER",
                detectRisk(userQuery),
                vp.hint(),
                tokenHint,
                requestedModel);

        ChatRequestDto stepReq = baseReq;
        try {
            if (baseReq != null) {
                var b = baseReq.toBuilder();
                if (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                    b.maxTokens(cfg.maxTokens());
                if (StringUtils.hasText(cfg.systemPrompt()))
                    b.systemPrompt(cfg.systemPrompt());
                if (StringUtils.hasText(resolvedModel) && !"auto".equalsIgnoreCase(resolvedModel))
                    b.model(resolvedModel);
                stepReq = b.build();
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("projection.final.stepRequest", ignore); stepReq = baseReq;
        }

        StringBuilder system = new StringBuilder();

        if (promptAssetService != null) {
            String sys = promptAssetService.resolveTrustedSystemPromptText(cfg.systemPrompt());
            if (StringUtils.hasText(sys)) {
                appendSystemBlock(system, sys);
            }
        }

        // Output length policy helps a bit for OpenAI-like models.
        try {
            String outputPolicy = buildOutputLengthPolicy(resolvedModel, vp.hint(), AnswerMode.ALL_ROUNDER,
                    vp.targetTokenBudgetOut());
            if (StringUtils.hasText(outputPolicy)) {
                appendSystemBlock(system, outputPolicy);
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.outputPolicyAppend", ignore); }

        // Sensitive topic: add extra privacy boundary for the final polish step.
        try {
            var gctx = GuardContextHolder.get();
            if (gctx != null && (gctx.isSensitiveTopic() || gctx.planBool("privacy.boundary.enforce", false))) {
                appendSystemBlock(system, PRIVACY_BOUNDARY_SYS);
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.privacyBoundaryAppend", ignore); }

        String user = """
                ?ъ슜??吏덈Ц:
                %s

                ?꾨옒??1李??⑹꽦 寃곌낵?대떎. 以묐났???쒓굅?섍퀬, 洹쇨굅 湲곕컲 遺遺꾩쓣 ?곗꽑?섎ŉ,
                媛??異붿젙? 紐낇솗??援щ텇?댁꽌 理쒖쥌 ?듬????묒꽦?대씪.

                [MERGED ANSWER]
                %s
                """.formatted(userQuery, mergedAnswer);
        List<ChatMessage> msgs = buildPostOrchestrationPromptMessages(
                "projection.final", system.toString(), user);

        try {
            ChatRequestDto finalReq = applyFinalAnswerSamplingOverrides(stepReq);
            String out = callWithRetry(finalModel, msgs, finalReq);
            if (out == null || out.isBlank()) {
                return mergedAnswer;
            }
            return out.trim();
        } catch (Exception e) {
            log.debug("[ProjectionAgent] final answer polish failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            return mergedAnswer;
        }
    }

    /**
     * [Dual-Vision] VisionMode / GuardProfile 湲곕컲 硫붾え由?寃뚯씠???꾨줈?뚯씪 寃곗젙
     */
    private MemoryGateProfile decideMemoryGateProfile(VisionMode visionMode, GuardProfile guardProfile) {
        if (visionMode == VisionMode.FREE) {
            // FREE 紐⑤뱶?먯꽌???먯튃?곸쑝濡?硫붾え由???μ쓣 ?섏? ?딅뒗??
            // 留뚯빟 ??ν븳?ㅻ㈃ 媛???꾪솕???꾨줈?뚯씪???ъ슜?쒕떎.
            return MemoryGateProfile.RELAXED;
        }

        if (visionMode == VisionMode.STRICT) {
            return (guardProfile == GuardProfile.STRICT)
                    ? MemoryGateProfile.HARD
                    : MemoryGateProfile.BALANCED;
        }

        // HYBRID
        return switch (guardProfile) {
            case STRICT -> MemoryGateProfile.HARD;
            case SUBCULTURE -> MemoryGateProfile.RELAXED;
            default -> MemoryGateProfile.BALANCED;
        };
    }

    private static void traceRejectedPublicSystemPrompt(String requestedSystemPrompt) {
        if (!StringUtils.hasText(requestedSystemPrompt)) {
            return;
        }
        String trimmed = requestedSystemPrompt.trim();
        String reason = (trimmed.contains("\n") || trimmed.matches(".*\\s+.*"))
                ? "literal_public_rejected"
                : "asset_not_found";
        try {
            TraceStore.put("prompt.systemPrompt.rejected", true);
            TraceStore.put("prompt.systemPrompt.rejectedReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            TraceStore.put("prompt.systemPrompt.rejectedHash12", SafeRedactor.hash12(trimmed));
            TraceStore.put("prompt.systemPrompt.rejectedLength", trimmed.length());
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.systemPromptRejectionTrace", ignore);
        }
    }

    private boolean isLowRiskDomain(java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs) {
        if (evidenceDocs == null || evidenceDocs.isEmpty()) {
            return false;
        }
        for (EvidenceAwareGuard.EvidenceDoc doc : evidenceDocs) {
            if (doc == null || doc.id() == null) {
                continue;
            }
            String lower = doc.id().toLowerCase();
            if (lower.contains("namu.wiki")
                    || lower.contains("tistory.com")
                    || lower.contains("gamedot.org")
                    || lower.contains("inven.co.kr")
                    || lower.contains("fandom.com")
                    || lower.contains("hoyolab.com")
                    || lower.contains("arca.live")
                    || lower.contains("ruliweb.com")
                    || lower.contains("ruliweb.co.kr")) {
                return true;
            }
        }
        return false;
    }

    /**
     * LLM/Guard媛 留뚮뱾?대궦 理쒖쥌 ?띿뒪?멸?
     * ?ъ떎??"?뺣낫 ?놁쓬/?먮즺 遺議? 瑜섏쓽 ?ㅽ뙣 ?쒗뵆由우씤吏 ?먮퀎?쒕떎.
     *
     * - 愿??: ?κ린 硫붾え由???? ?꾩옱 Evidence濡쒕씪???듯빐???섎뒗吏 ?щ?瑜?媛瑜대뒗 湲곗?.
     */

    /**
     * InfoFailurePatterns? ?숈씪??湲곗??쇰줈
     * "?뺣낫 ?놁쓬/利앷굅 遺議?瑜??뚰뵾???듬???媛뺥븯寃??먯젙.
     *
     * EvidenceAwareGuard, PromptBuilder??洹쒖튃怨??섎??곸쑝濡??쇱튂?쒖폒
     * Guard/Prompt/Service ?덉씠?닿? ?숈씪??failure 媛쒕뀗???ъ슜?섍쾶 ?쒕떎.
     */
    private boolean isDefinitiveFailure(String text) {
        return InfoFailurePatterns.looksLikeFailure(text);
    }
    /**
     * Map a detected domain label into one of the 9 UI tiles.
     * This is used as a stable hint for alias correction and retrieval routing.
     */
    private static String mapDomainToTile(String domain) {
        if (domain == null || domain.isBlank())
            return "misc";
        String d = domain.toUpperCase(java.util.Locale.ROOT);
        if (d.contains("GENSHIN") || d.contains("GAME") || d.contains("SUBCULTURE"))
            return "games";
        if (d.contains("STUDY") || d.contains("EDU") || d.contains("EMPLOY") || d.contains("TRAIN"))
            return "tech";
        if (d.contains("FINANCE") || d.contains("STOCK") || d.contains("ECON"))
            return "finance";
        if (d.contains("LAW") || d.contains("REGULATION"))
            return "law";
        if (d.contains("HEALTH") || d.contains("MED"))
            return "health";
        if (d.contains("SCI") || d.contains("MATH"))
            return "science";
        if (d.contains("MEDIA") || d.contains("NEWS") || d.contains("CULTURE"))
            return "media";
        if (d.contains("ANIMAL") || d.contains("LIVING") || d.contains("PET"))
            return "animals";
        if (d.contains("TECH") || d.contains("IT") || d.contains("DEV") || d.contains("CODE") || d.contains("DEVICE"))
            return "tech";
        return "misc";
    }

    /**
     * GPT-5/o-series泥섎읆 transport-level ?좏겙 ?쒗븳 ?뚮씪誘명꽣媛 臾댁떆/嫄곕??????덈뒗 紐⑤뜽?먯꽌,
     * ?꾨＼?꾪듃 ?뺤콉?쇰줈 異쒕젰 湲몄씠/援ъ“瑜?媛뺤젣?쒕떎.
     *
     * <p>
     * AnswerMode/verbosityHint蹂꾨줈 ?뱀뀡 ?덉궛????珥섏킌?섍쾶 李⑤벑 ?곸슜?쒕떎.
     */
    private static String buildOutputLengthPolicy(String modelName,
            String verbosityHint,
            AnswerMode answerMode,
            Integer targetTokensOut) {
        final int tok = (targetTokensOut == null || targetTokensOut <= 0) ? 1024 : targetTokensOut;
        final String vh = (verbosityHint == null || verbosityHint.isBlank()) ? "standard"
                : verbosityHint.trim().toLowerCase(Locale.ROOT);
        final AnswerMode am = (answerMode == null) ? AnswerMode.ALL_ROUNDER : answerMode;

        final boolean isOSeries = OpenAiTokenParamCompat.isOSeriesModel(modelName);
        final boolean isGpt5 = OpenAiTokenParamCompat.isGpt5Family(modelName);
        final String modelTag = isOSeries ? "o-series" : (isGpt5 ? "gpt-5.*" : "openai");

        // --- Base preset by verbosityHint ---
        int hardCharCap;
        int summaryMinLines;
        int summaryMaxLines;
        int coreMinLines;
        int coreMaxLines;
        int evidenceBullets;
        int explainBullets;
        int nextBullets;

        switch (vh) {
            case "brief" -> {
                hardCharCap = 1300;
                summaryMinLines = 2;
                summaryMaxLines = 3;
                coreMinLines = 4;
                coreMaxLines = 7;
                evidenceBullets = 3;
                explainBullets = 4;
                nextBullets = 2;
            }
            case "deep" -> {
                hardCharCap = 2700;
                summaryMinLines = 3;
                summaryMaxLines = 5;
                coreMinLines = 8;
                coreMaxLines = 14;
                evidenceBullets = 7;
                explainBullets = 10;
                nextBullets = 4;
            }
            case "ultra" -> {
                hardCharCap = 3600;
                summaryMinLines = 3;
                summaryMaxLines = 6;
                coreMinLines = 10;
                coreMaxLines = 18;
                evidenceBullets = 9;
                explainBullets = 14;
                nextBullets = 5;
            }
            default -> { // standard
                hardCharCap = 1900;
                summaryMinLines = 2;
                summaryMaxLines = 4;
                coreMinLines = 6;
                coreMaxLines = 10;
                evidenceBullets = 5;
                explainBullets = 7;
                nextBullets = 3;
            }
        }

        // Token budget -> char cap scaling (Korean chars per token is variable; keep
        // conservative)
        int scaledCharCap = (int) Math.round(tok * 1.8);
        if (scaledCharCap < 900)
            scaledCharCap = 900;
        // Prefer the stricter of the two caps.
        hardCharCap = Math.min(hardCharCap, scaledCharCap);

        // --- AnswerMode adjustments ---
        switch (am) {
            case FACT -> {
                evidenceBullets = Math.min(12, evidenceBullets + 2);
                explainBullets = Math.max(3, explainBullets - 1);
            }
            case BALANCED -> {
                evidenceBullets = Math.min(12, evidenceBullets + 1);
            }
            case CREATIVE -> {
                evidenceBullets = Math.max(1, evidenceBullets - 2);
                explainBullets = Math.min(18, explainBullets + 2);
                nextBullets = Math.min(8, nextBullets + 1);
            }
            default -> {
                // ALL_ROUNDER: no changes
            }
        }

        // --- o-series adjustments (more aggressive compression; these models tend to
        // be verbose) ---
        if (isOSeries) {
            hardCharCap = (int) Math.round(hardCharCap * 0.85);
            explainBullets = Math.max(3, explainBullets - 2);
            coreMaxLines = Math.max(coreMinLines, coreMaxLines - 2);
        }

        // Final clamps
        if (hardCharCap < 800)
            hardCharCap = 800;
        if (evidenceBullets < 0)
            evidenceBullets = 0;

        String extraRules = "";
        if (am == AnswerMode.FACT) {
            extraRules = "- FACT 紐⑤뱶: ?듭떖 二쇱옣?먮뒗 媛?ν븳 ??[W#]/[V#] 洹쇨굅 留덉빱瑜?遺숈씠怨? 洹쇨굅媛 ?쏀븯硫?'異붿젙/?뺤씤 ?꾩슂'濡?紐낆떆.\n";
        } else if (am == AnswerMode.CREATIVE) {
            extraRules = "- CREATIVE 紐⑤뱶: 異붿륫/?꾩씠?붿뼱??'(異붿륫)' ?먮뒗 '(?꾩씠?붿뼱)'濡??쇰꺼留? 怨쇱옣/?ν솴??湲덉?.\n";
        }

        return """
                ### OUTPUT LENGTH POLICY (prompt-enforced)
                - model-group: %s
                - profile: verbosity=%s, answerMode=%s, targetTokensOut=%d
                - Hard cap: ~%d Korean characters. If you exceed, compress aggressively.
                - Keep the same section order. If short on space: preserve '?붿빟' and '洹쇨굅' first.
                - Do NOT output internal chain-of-thought. Output final answer only.
                - Section budgets (tight):
                  1) ?붿빟: %d~%d以?
                  2) ?듭떖 ?듬?: %d~%d以?
                  3) 洹쇨굅(Evidence): 理쒕? %d媛?bullet (媛?bullet 1以?
                  4) 異붽? ?ㅻ챸/鍮꾧탳: 理쒕? %d媛?bullet
                  5) ?ㅼ쓬 ?④퀎: 理쒕? %d媛?bullet
                - Avoid filler. No long preambles. No repetition.
                %s
                """.formatted(modelTag, vh, am.name(), tok,
                hardCharCap, summaryMinLines, summaryMaxLines, coreMinLines, coreMaxLines,
                evidenceBullets, explainBullets, nextBullets, extraRules);
    }

    private static boolean shouldUseAnalyzeWeb(Map<String, Object> metaHints) {
        if (metaHints == null) {
            return false;
        }
        try {
            Object sm = metaHints.get("searchMode");
            String mode = (sm == null ? "" : sm.toString());
            if ("OFF".equalsIgnoreCase(mode)) {
                return false;
            }
            if ("FORCE_LIGHT".equalsIgnoreCase(mode)) {
                return false;
            }
            if ("FORCE_DEEP".equalsIgnoreCase(mode)) {
                return true;
            }

            Object ea = metaHints.get("enableAnalyze");
            if (ea == null) {
                // AUTO + no flag -> default to false (stay cheap)
                return false;
            }
            if (ea instanceof Boolean b) {
                return b;
            }
            String s = ea.toString();
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("selfask.planBool", ignore); return false;
        }
    }

}
