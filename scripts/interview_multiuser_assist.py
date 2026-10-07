#!/usr/bin/env python3
"""Read-only companion checks for two Codex briefs (2026-10-07):

  A  INTERVIEW_FEATURE_STATUS_PRESENTATION_20261007
     Same-answer summary: preserve observedModel / prompt.citableEvidenceCount /
     orch.mode; add only same-answer observedProvider ("응답 제공자"); missing
     values stay 관측 없음/UNKNOWN; partial updates must not wipe earlier fields.
     Owning journal: codex-interview-feature-ee02e904 (scope-claimed 7 targets).

  B  SMALL_MULTIUSER_SEARCH_RESILIENCE_20261007
     2-5 user burst: local congestion must not promote to shared provider-down;
     bounded fair provider wait on existing seams; 429/fallback/half-open/
     shared-singleflight verification. WP0-WP4, offline mock/Clock first.

Devin assist lane (task devin-interview-multiuser-assist-e6de5cde):
  hashes      - brief SHA pins vs live tree (SAME/DRIFTED/MISSING)
  pins        - brief file:line anchors vs live tree (SAME/MOVED/ABSENT)
  state       - per-contract phase classify (PRE_BRIEF / IN_FLIGHT / cues)
  cover       - acceptance slots -> evidence found in live file/diff
  diff-review - allowed/forbidden path + forbidden-line cues (`git diff`
                or --diff <unified file>); flags are review cues, not verdicts
  lease       - fresh target-scoped lease status with CURRENT hashes
  snapshot    - write var/.../baseline-hashes.json (all watched files)
  journals    - active work journals intersecting watched paths (timing gate)
  status      - combined card (hashes + pins + state + journals + cover)

Product source stays with the owning Codex session(s). OBSERVED_IN_DIFF means
a seam exists in the working tree; it is NOT a product verdict - the owning
session's focused Gradle/Node runs decide.
"""
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.interview-multiuser-assist.v1"
CONTRACT_A = "INTERVIEW_FEATURE_STATUS_PRESENTATION_20261007"
CONTRACT_B = "SMALL_MULTIUSER_SEARCH_RESILIENCE_20261007"
VAR_DIR = "var/codex-assist-interview-multiuser-20261007"

# ---------------------------------------------------------------- paths ----
RESP_BUILDER = "main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java"
RESTORER = "main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java"
GEN_OBS = "main/java/com/example/lms/dto/GenerationObservation.java"
STREAM_EVENT = "main/java/com/example/lms/dto/ChatStreamEvent.java"
SIGNAL_BUILDER = "main/java/com/example/lms/api/ChatStreamSignalBuilder.java"
TRACE_UI = "main/resources/static/js/chat-trace-ui.js"
CHATJS = "main/resources/static/js/chat.js"
TRACE_CSS = "main/resources/static/css/chat-trace.css"
EVIDENCE_GRAPH = "main/resources/static/js/chat-evidence-graph.js"
CHAT_UI_HTML = "main/resources/templates/chat-ui.html"  # brief "chat-ui.html:79"
SETTINGS_BRIDGE = "main/resources/static/js/chat-settings-bridge.js"
PREF_SERVICE = "main/java/com/example/lms/service/ChatPreferenceService.java"
ORCH = "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"
ADMISSION_GUARD = "main/java/com/example/lms/api/PublicChatAdmissionGuard.java"
GEN_FILTER = "main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java"
BUDGET_GUARD = "main/java/com/example/lms/api/PublicRequestBudgetGuard.java"
API_CONTROLLER = "main/java/com/example/lms/api/ChatApiController.java"
RUN_CTX = "main/java/com/example/lms/service/chat/ChatRunExecutionContext.java"
RUN_REGISTRY = "main/java/com/example/lms/service/chat/ChatRunRegistry.java"
CHAT_WORKFLOW = "main/java/com/example/lms/service/ChatWorkflow.java"
SEARCH_EXEC_CFG = "main/java/com/example/lms/config/SearchExecutorConfig.java"
NOVA_AUTOCONF = "main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java"
NOVA_RETRIEVER = "main/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetriever.java"
SELF_ASK_BUDGET = "main/java/com/example/lms/service/rag/SelfAskSearchBudget.java"
HYBRID_PROVIDER = "main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"
HYBRID_EXEC = "main/java/com/example/lms/search/provider/HybridSearchExecution.java"
NAVER = "main/java/com/example/lms/service/NaverSearchService.java"
BRAVE = "main/java/com/example/lms/service/web/BraveSearchService.java"
ASPECT = "main/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspect.java"
COORDINATOR = "main/java/ai/abandonware/nova/orch/web/RateLimitBackoffCoordinator.java"
BREAKER = "main/java/com/example/lms/infra/resilience/NightmareBreaker.java"
GEMINI_GW = "main/java/com/example/lms/learning/gemini/GeminiGateway.java"
OAUTH_REG = "main/java/com/example/lms/llm/ChatGptOAuthRegistration.java"
PROMPT_BUILDER = "main/java/com/example/lms/prompt/StandardPromptBuilder.java"
BUILDER_TEST = "src/test/java/com/example/lms/api/ChatSessionDetailResponseBuilderTest.java"
TRACE_UI_TEST = "src/test/js/chat-trace-ui.test.cjs"
TRACE_RESTORE_TEST = "src/test/js/chat-trace-restore.test.cjs"
EVIDENCE_GRAPH_TEST = "src/test/js/chat-evidence-graph.test.cjs"
HTTP_CONTRACT_TEST = "src/test/java/com/example/lms/service/web/SearchProviderHttpContractTest.java"
SYNC_LIFECYCLE_TEST = "src/test/java/com/example/lms/api/ChatApiControllerSyncLifecycleTest.java"
ROLE_TEST = "src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java"

# ------------------------------------------------- brief hash snapshots ----
# Brief A prints full SHA-256 for 3 files (owner session may already be
# editing them -> DRIFTED is the expected in-flight signal, not an alarm).
SNAPSHOT_A = {
    TRACE_UI: "5cab549413256f1da112a9d90c9f85ac2a21392c635f9c310a1330ff6e6ea19d",
    CHATJS: "642e8cab3119be717c83f05b8cafb07038c61a93f06c39a273a9da910a717fa2",
    RESP_BUILDER: "21f7e2ade9e9a5129d0c1889ca916352cc6fd393254d06cd4a3e5a5bb9834d67",
}

# Brief B prints SHA-12 pins (read at 2026-10-07 07:16 UTC).
SNAPSHOT_B = {
    ADMISSION_GUARD: "619275dfcb2c",
    GEN_FILTER: "dc5b5b060a9c",
    BUDGET_GUARD: "c6138baa7dad",
    API_CONTROLLER: "1295fcfe300c",
    RUN_CTX: "2981988f5242",
    CHAT_WORKFLOW: "fd8fd72e064b",
    SEARCH_EXEC_CFG: "4ba42ea6d77b",
    NOVA_RETRIEVER: "8939dc55be0e",
    HYBRID_PROVIDER: "e57837f41980",
    HYBRID_EXEC: "e032a9c9f4f4",
    NAVER: "c73425fc2a53",
    BRAVE: "6bd645e9804e",
    SELF_ASK_BUDGET: "98ed3a487256",
    ASPECT: "1142247838dd",
    COORDINATOR: "e0efc6c975d7",
    GEMINI_GW: "82de3e133b82",
    PROMPT_BUILDER: "a21adb933458",
}

# ------------------------------------------------------------- anchors ----
# (file, line, regex, label). line 0 = whole-file search.
PINS_A = [
    (RESP_BUILDER, 198, r'List\.of\("observedModel"[^\n]*"orch\.mode"\)',
     "observed-field allowlist -> turnTraces.fields (post-fix adds observedProvider)"),
    (RESP_BUILDER, 194, r"mergeModelMetaField", "diagnostics->projection merge (turnTraces.fields upstream)"),
    (RESTORER, 84, r"prompt\.citableEvidenceCount", "typed diagnostics allowlist"),
    (RESTORER, 109, r"80-field cap|bounded summary", "summary-before-detail cap"),
    (RESTORER, 120, r'"prompt\.citableEvidenceCount", "orch\.mode"', "restore three-key list"),
    (GEN_OBS, 8, r"record GenerationObservation\(String observedProvider, String observedModel",
     "observation record (provider+model)"),
    (STREAM_EVENT, 38, r"JsonUnwrapped GenerationObservation observation", "observation into final event"),
    (SIGNAL_BUILDER, 210, r"agentWebSearchSnapshot", "typed agentWebSearch signal"),
    (SIGNAL_BUILDER, 212, r'"OK", "FAIL_SOFT", "SKIPPED"', "OK/FAIL_SOFT/SKIPPED status set"),
    (TRACE_UI, 85, r"function showOverview", "overview renderer"),
    (TRACE_UI, 88, r"NOT_OBSERVED", "missing model -> NOT_OBSERVED"),
    (TRACE_UI, 89, r"prompt\.citableEvidenceCount", "citable evidence count field"),
    (TRACE_UI, 92, r"orch\.mode", "orch.mode field"),
    (TRACE_UI, 101, r"function upsertSummary", "summary upsert seam"),
    (TRACE_UI, 502, r"function restore\(assistant, turnTrace\)", "same-answer restore seam"),
    (CHATJS, 1182, r"function validateTurnTraces", "turnTraces intake validation"),
    (CHATJS, 1272, r"function refreshVisibleTurnTraces", "visible-answer trace refresh"),
    (CHATJS, 6186, r'hasObservation = \["observedModel", "observed_model", "observedProvider"',
     "final observation detection"),
    (CHATJS, 6194, r"upsertSummary\(assistant, \{ observedModel",
     "summary handoff (wipe-risk seam; post-fix passes observedProvider too)"),
    (EVIDENCE_GRAPH, 59, r"graphKind: 'source-only'", "source-only citation graph"),
    (EVIDENCE_GRAPH, 94, r"실제 사용 미확인", "honest UNKNOWN citation label"),
    (CHAT_UI_HTML, 79, r'chatTraceToggle.*checked|checked.*chatTraceToggle', "default trace checkbox ON"),
    (SETTINGS_BRIDGE, 91, r"chatTraceEnabled", "trace pref key"),
    (SETTINGS_BRIDGE, 320, r"chatTraceEnabled", "trace pref save path"),
    (ASPECT, 206, r"provider.*producer|recordLocalRateLimit|recordRateLimited",
     "per-provider producer exists but not projected to answer summary"),
]

PINS_B = [
    (ADMISSION_GUARD, 58, r"public Optional<Lease> tryAcquire", "owner/global admission"),
    (ADMISSION_GUARD, 107, r"new Semaphore\(requestedGlobalLimit, true\)", "fair global semaphore"),
    (ADMISSION_GUARD, 149, r"Semaphore permits|AtomicBoolean|closed", "CAS close-once lease"),
    (ADMISSION_GUARD, 183, r"chat_admission_exceeded", "admission 429 reason"),
    (GEN_FILTER, 47, r"(?i)(rolling|fingerprint|replay|owner)", "replay fingerprint/run owner"),
    (API_CONTROLLER, 1419, r"(?i)(admission|owner|session)", "sync entry/owner/admission"),
    (API_CONTROLLER, 2529, r"(?i)(owner|result|fence|store)", "owner-bound result/cancel fence"),
    (RUN_CTX, 140, r"finite transport waits", "accepted-run ingress-split policy"),
    (NOVA_AUTOCONF, 227, r"(?i)(primary|retriever|conditional)", "conditional Primary retriever"),
    (SEARCH_EXEC_CFG, 183, r"ArrayBlockingQueue|SynchronousQueue", "bounded queue"),
    (SEARCH_EXEC_CFG, 192, r"AbortPolicy", "abort policy"),
    (NOVA_RETRIEVER, 476, r"(?i)(permit|deadline|lease)", "deadline-aware fair permit"),
    (SELF_ASK_BUDGET, 61, r"logical|wire|attempt", "logical vs wire budget split"),
    (SELF_ASK_BUDGET, 76, r"tryQueryAlias", "query alias budget"),
    (HYBRID_PROVIDER, 395, r'TRUE_ZERO', "TRUE_ZERO fallback ladder"),
    (HYBRID_PROVIDER, 414, r"SearchExpansion expansion", "Gemini expand-once seam"),
    (HYBRID_PROVIDER, 637, r"searchWithMeta|cache", "Brave cache-only -> searchWithMeta route"),
    (NAVER, 530, r"REQUEST_SEMAPHORE = new Semaphore\(MAX_CONCURRENT_API, true\)",
     "sync-path semaphore (does NOT guard async wire)"),
    (NAVER, 2150, r"Mono\.defer", "async wire defer"),
    (NAVER, 2498, r"subscriptionDelayMs|delaySubscription", "<=200ms rate delay before wire"),
    (NAVER, 3254, r"REQUEST_SEMAPHORE\.acquire\(\)", "sync facade acquire"),
    (NAVER, 4725, r"waiterFuture|Mono\.fromFuture", "singleflight child waiter"),
    (BRAVE, 716, r"(?i)cacheable|sync\s*=\s*true", "@Cacheable(sync=true) singleflight"),
    (BRAVE, 1172, r"rateLimiter\.tryAcquire", "singleton QPS gate"),
    (BRAVE, 1179, r"startCooldown", "local failure -> cooldown seam"),
    (BRAVE, 1186, r'"rate_limit_local"', "local pacing reason distinct from wire 429"),
    (ASPECT, 158, r"PROVIDER_BRAVE|shouldSkip", "Brave backoff advice"),
    (ASPECT, 183, r"recordLocalRateLimit", "local vs wire rate-limit split"),
    (ASPECT, 470, r"CANCELLED_NO_BREAKER", "cancel -> no breaker contract"),
    (COORDINATOR, 38, r'PROVIDER_BRAVE = "brave"', "provider-name shared state"),
    (COORDINATOR, 58, r"Decision shouldSkip", "shared skip decision"),
    (HYBRID_EXEC, 97, r"closed = true", "execution close / late-result fence"),
    (BREAKER, 587, r"public final class CallPermit", "breaker CallPermit"),
    (BREAKER, 96, r"CLOSED, OPEN, HALF_OPEN", "breaker mode ladder"),
    (CHAT_WORKFLOW, 3173, r"(?i)(PromptContext|web|history)", "prompt context assembly"),
    (CHAT_WORKFLOW, 14147, r"PromptBuilder\.build|build\(ctx", "PromptBuilder call site"),
    (RUN_REGISTRY, 567, r"Explicit Stop owns cancellation|Subscriber lifetime",
     "explicit Stop policy (not detach-cancel)"),
    (OAUTH_REG, 308, r"(?i)(cancelled|deadline|usage)", "oauth terminal classification"),
    (GEMINI_GW, 259, r'body\.put\("contents"', "search rewrite body contents"),
    (GEMINI_GW, 262, r'google_search', "search rewrite tools"),
    (GEMINI_GW, 268, r"expandSearchQueryOnce", "expansion once-only"),
    (HTTP_CONTRACT_TEST, 0, r"HttpServer|Retry-After", "loopback contract fixture"),
    (SYNC_LIFECYCLE_TEST, 0, r"ChatApiController|permit|delegate", "A/B lifecycle fixture"),
    (ROLE_TEST, 168, r"immediateSameOwnerFollowupUsesCurrentRelationAndSourceAtFinalModelBoundary",
     "A->B final-model boundary fixture"),
]

# Acceptance slots -> evidence markers (file, regex, label).
COVER_A = [
    ("A1 pref default + per-user OFF/ON persisted, no scope growth", [
        (CHAT_UI_HTML, r"chatTraceToggle.*checked|checked.*chatTraceToggle", "default checkbox"),
        (SETTINGS_BRIDGE, r"chatTraceEnabled", "pref key honored"),
        (PREF_SERVICE, r"(?i)class ChatPreferenceService", "validation seam exists"),
    ]),
    ("A2 three observed fields preserved; count=0 != missing", [
        (RESP_BUILDER, r'"observedModel"[^\n]*"prompt\.citableEvidenceCount"[^\n]*"orch\.mode"',
         "producer observed-field allowlist"),
        (TRACE_UI, r"prompt\.citableEvidenceCount", "view renders citable count"),
        (TRACE_UI, r"NOT_OBSERVED", "missing stays NOT_OBSERVED"),
    ]),
    ("A3 only same-answer observedProvider added as 응답 제공자; unknown stays", [
        (GEN_OBS, r"record GenerationObservation\(String observedProvider", "provider in observation"),
        (CHATJS, r'"observedProvider"', "final event detects provider"),
        (RESP_BUILDER, r"observedProvider|observed_provider", "provider projected to turnTraces (post-fix)"),
        (TRACE_UI, r"observedProvider|응답 제공자", "view renders provider (post-fix)"),
    ]),
    ("A4 partial update never wipes earlier fields / other-answer isolation", [
        (CHATJS, r"validateTurnTraces", "turnId-keyed intake"),
        (CHATJS, r"refreshVisibleTurnTraces", "visible-only refresh"),
        (TRACE_UI_TEST, r"(?i)(upsert|summary|preserve|partial)", "summary regression fixture"),
    ]),
    ("A5 detail OFF/403 adds no raw detail or request flood", [
        (RESTORER, r"80-field cap|bounded summary", "bounded summary"),
        (TRACE_RESTORE_TEST, r"(?i)(403|forbidden|off|timeout)", "OFF/403/timeout fixture"),
    ]),
    ("A6 no global raw log / prompt / keys / cross-user data", [
        (RESTORER, r"escapeHtmlAttr", "render escaping kept"),
        (RESP_BUILDER, r"turnTraces", "owned endpoint projection only"),
    ]),
    ("A7 focused JS + minimal producer checks runnable offline", [
        (TRACE_UI_TEST, r"(?i)test|describe|it\(", "chat-trace-ui tests present"),
        (TRACE_RESTORE_TEST, r"(?i)test|describe|it\(", "restore tests present"),
        (EVIDENCE_GRAPH_TEST, r"(?i)test|describe|it\(", "evidence-graph tests present"),
    ]),
]

COVER_B = [
    ("B-2to5 burst: owner/global bounds, pacing, B not starved", [
        (ADMISSION_GUARD, r"chat_admission_exceeded", "429 reason stable"),
        (SEARCH_EXEC_CFG, r"AbortPolicy", "bounded executor reject"),
        ("src/test/java/com/example/lms/api/PublicChatAdmissionGuardTest.java",
         r"(?i)class |@Test", "admission test class"),
        ("src/test/java/com/example/lms/service/chat/ChatRunAdmissionConcurrencyTest.java",
         r"(?i)class |@Test", "run concurrency test class"),
    ]),
    ("B-provider429: Retry-After parse, single retry owner, herd 0", [
        (COORDINATOR, r"parseRetryAfterMs|retryAfter", "retry-after parser"),
        (HTTP_CONTRACT_TEST, r"Retry-After|429", "429 wire fixture"),
        (BRAVE, r"MAX_429_COOLDOWN_MS", "cooldown cap"),
    ]),
    ("B-one/both down: honest fallback, no fake results", [
        (HYBRID_PROVIDER, r"TRUE_ZERO", "true-zero reason"),
        (HYBRID_PROVIDER, r'executed_empty|"NONE"|failure', "failure taxonomy"),
        ("src/test/java/com/example/lms/search/provider/HybridWebSearchProviderBoundedFallbackTest.java",
         r"(?i)class |@Test", "bounded fallback test"),
    ]),
    ("B-queued/running cancel: queued outbound0, exactly-once return", [
        (ASPECT, r"CANCELLED_NO_BREAKER", "cancel no-breaker"),
        (RUN_REGISTRY, r"cancelExact", "exact cancel API"),
        ("src/test/java/com/example/lms/infra/resilience/NightmareBreakerCallPermitTest.java",
         r"(?i)class |@Test", "call-permit test"),
        (SYNC_LIFECYCLE_TEST, r"(?i)class |@Test", "A/B lifecycle test"),
    ]),
    ("B-user isolation / singleflight: public same-key wire1 only", [
        (NAVER, r"waiterFuture|Mono\.fromFuture", "child waiter seam"),
        (BRAVE, r"(?i)cacheable|sync\s*=\s*true", "cache singleflight"),
        (GEN_FILTER, r"(?i)(owner|fingerprint)", "owner namespace"),
    ]),
    ("B-queue full / fallback deadline: fast local reason, honest end", [
        (SEARCH_EXEC_CFG, r"AbortPolicy", "reject on full"),
        (HYBRID_EXEC, r"budget\.expired\(\)|closed", "deadline/close fences"),
        ("src/test/java/com/example/lms/search/provider/HybridWebSearchDeadlineBudgetTest.java",
         r"(?i)class |@Test", "deadline budget test"),
    ]),
    ("B-half-open recover: fake-clock bounded probe, no herd", [
        (BREAKER, r"HALF_OPEN", "half-open mode"),
        (BREAKER, r"halfOpenMaxCalls|halfOpenSealed", "bounded probe cap"),
        ("src/test/java/com/example/lms/infra/resilience/NightmareBreakerCallPermitTest.java",
         r"(?i)class |@Test", "breaker test"),
    ]),
    ("B-first-2-turns A->B quality preserved", [
        (ROLE_TEST, r"immediateSameOwnerFollowupUsesCurrentRelationAndSourceAtFinalModelBoundary",
         "A->B boundary test"),
        ("src/test/java/com/example/lms/prompt/StandardPromptBuilderEvidenceMetadataTest.java",
         r"(?i)class |@Test", "evidence metadata test"),
    ]),
]

# Named test classes from brief B command block -> expected files.
NAMED_TESTS_B = [
    ("ai.abandonware.nova.orch.aop.ProviderRateLimitBackoffAspectTest",
     "src/test/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspectTest.java"),
    ("com.example.lms.service.web.NightmareBreakerProviderPermitBoundaryTest",
     "src/test/java/com/example/lms/service/web/NightmareBreakerProviderPermitBoundaryTest.java"),
    ("com.example.lms.infra.resilience.NightmareBreakerCallPermitTest",
     "src/test/java/com/example/lms/infra/resilience/NightmareBreakerCallPermitTest.java"),
    ("com.example.lms.search.provider.HybridWebSearchAdmissionContractTest",
     "src/test/java/com/example/lms/search/provider/HybridWebSearchAdmissionContractTest.java"),
    ("com.example.lms.search.provider.HybridWebSearchDeadlineBudgetTest",
     "src/test/java/com/example/lms/search/provider/HybridWebSearchDeadlineBudgetTest.java"),
    ("com.example.lms.search.provider.HybridWebSearchRequestBudgetTest",
     "src/test/java/com/example/lms/search/provider/HybridWebSearchRequestBudgetTest.java"),
    ("com.example.lms.search.provider.HybridWebSearchAttemptOwnershipTest",
     "src/test/java/com/example/lms/search/provider/HybridWebSearchAttemptOwnershipTest.java"),
    ("com.example.lms.search.provider.HybridSearchExecutionTest",
     "src/test/java/com/example/lms/search/provider/HybridSearchExecutionTest.java"),
    ("com.example.lms.service.NaverSearchSyncBudgetTest",
     "src/test/java/com/example/lms/service/NaverSearchSyncBudgetTest.java"),
    ("com.example.lms.service.NaverSearchServiceInterruptContractTest",
     "src/test/java/com/example/lms/service/NaverSearchServiceInterruptContractTest.java"),
    ("ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetrieverTimeoutTraceTest",
     "src/test/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetrieverTimeoutTraceTest.java"),
    ("com.example.lms.api.PublicChatAdmissionGuardTest",
     "src/test/java/com/example/lms/api/PublicChatAdmissionGuardTest.java"),
    ("com.example.lms.api.ChatGenerationAdmissionFilterTest",
     "src/test/java/com/example/lms/api/ChatGenerationAdmissionFilterTest.java"),
    ("com.example.lms.api.PublicRequestBudgetProjectionFocusedTest",
     "src/test/java/com/example/lms/api/PublicRequestBudgetProjectionFocusedTest.java"),
    ("com.example.lms.service.rag.SelfAskSearchBudgetTest",
     "src/test/java/com/example/lms/service/rag/SelfAskSearchBudgetTest.java"),
    ("com.example.lms.search.provider.HybridWebSearchProviderBoundedFallbackTest",
     "src/test/java/com/example/lms/search/provider/HybridWebSearchProviderBoundedFallbackTest.java"),
    ("com.example.lms.learning.gemini.GeminiGatewayContractTest",
     "src/test/java/com/example/lms/learning/gemini/GeminiGatewayContractTest.java"),
    ("com.example.lms.service.web.SearchProviderHttpContractTest",
     "src/test/java/com/example/lms/service/web/SearchProviderHttpContractTest.java"),
    ("com.example.lms.api.ChatApiControllerSyncLifecycleTest",
     "src/test/java/com/example/lms/api/ChatApiControllerSyncLifecycleTest.java"),
    ("com.example.lms.service.chat.ChatRunAdmissionConcurrencyTest",
     "src/test/java/com/example/lms/service/chat/ChatRunAdmissionConcurrencyTest.java"),
    ("com.example.lms.service.chat.ChatRunRegistryTerminalIsolationTest",
     "src/test/java/com/example/lms/service/chat/ChatRunRegistryTerminalIsolationTest.java"),
    ("com.example.lms.service.rag.WebSearchRetrieverDeadlineTest",
     "src/test/java/com/example/lms/service/rag/WebSearchRetrieverDeadlineTest.java"),
    ("com.example.lms.service.rag.extract.PageContentScraperTest",
     "src/test/java/com/example/lms/service/rag/extract/PageContentScraperTest.java"),
    ("com.example.lms.service.rag.WebSearchRetrieverRelationEvidenceTest",
     "src/test/java/com/example/lms/service/rag/WebSearchRetrieverRelationEvidenceTest.java"),
    ("com.example.lms.service.ChatWorkflowPromptMessageRoleTest",
     "src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java"),
    ("com.example.lms.prompt.StandardPromptBuilderConversationHistoryTest",
     "src/test/java/com/example/lms/prompt/StandardPromptBuilderConversationHistoryTest.java"),
    ("com.example.lms.prompt.StandardPromptBuilderEvidenceMetadataTest",
     "src/test/java/com/example/lms/prompt/StandardPromptBuilderEvidenceMetadataTest.java"),
    ("com.example.lms.search.provider.HybridWebSearchQueryBehaviorTest",
     "src/test/java/com/example/lms/search/provider/HybridWebSearchQueryBehaviorTest.java"),
    ("com.example.lms.service.ChatWorkflowStrictSingleAttemptHttpIntegrationTest",
     "src/test/java/com/example/lms/service/ChatWorkflowStrictSingleAttemptHttpIntegrationTest.java"),
    ("ai.abandonware.nova.orch.llm.ChatGptOAuthRedTeamContractTest",
     "src/test/java/ai/abandonware/nova/orch/llm/ChatGptOAuthRedTeamContractTest.java"),
]

# Diff scope per contract. Touches outside these product paths are flags
# (review cue, not a verdict). Tests/product JS are listed separately.
ALLOWED_A = {
    RESP_BUILDER, RESTORER, TRACE_UI, CHATJS, TRACE_CSS,
    BUILDER_TEST, TRACE_UI_TEST, TRACE_RESTORE_TEST, EVIDENCE_GRAPH_TEST,
}
ALLOWED_B = {
    ADMISSION_GUARD, GEN_FILTER, BUDGET_GUARD, API_CONTROLLER, RUN_CTX,
    RUN_REGISTRY, SEARCH_EXEC_CFG, NOVA_AUTOCONF, NOVA_RETRIEVER,
    SELF_ASK_BUDGET, HYBRID_PROVIDER, HYBRID_EXEC, NAVER, BRAVE, ASPECT,
    COORDINATOR, BREAKER,
} | {p for _, p in NAMED_TESTS_B}
PRODUCT_PREFIX = ("main/java/", "main/resources/", "src/test/")

# Files with known foreign/parallel leases at brief time -> touching them is an
# INFO cue ("check current lease"), not a violation.
FOREIGN_LEASE_CANDIDATES = {
    API_CONTROLLER: "session469-evidence-reload-green held api/js paths at brief time",
    PROMPT_BUILDER: "codex-p0-packing-* held StandardPromptBuilder/tests at brief time",
    "src/test/java/com/example/lms/prompt/StandardPromptBuilderEvidenceMetadataTest.java":
        "codex-p0-packing-* held StandardPromptBuilder/tests at brief time",
    "src/test/java/com/example/lms/prompt/StandardPromptBuilderConversationHistoryTest.java":
        "codex-p0-packing-* held StandardPromptBuilder/tests at brief time",
}

FORBIDDEN_LINES_A = [
    (r"(sk-|AIza|eyJ)[A-Za-z0-9_\-]{12,}", "secret-shaped literal"),
    (r"permitAll", "auth relaxation"),
    (r"class\s+\w*(FeatureCatalog|Dashboard|Orchestrator)\b", "new catalog/dashboard/orchestrator cue"),
    (r"System\.exit", "process exit in product code"),
    # Provider names rendered WITH an honest marker (NOT_OBSERVED/UNKNOWN/관측 없음)
    # are the brief-prescribed display; flag only bare success-looking claims.
    (r"\b(?:Brave|Naver)\b(?![^\n]*(?:NOT_OBSERVED|UNKNOWN|unavailable|관측 ?없음))",
     "provider badge fabrication risk (no honest NOT_OBSERVED/UNKNOWN marker)"),
    (r"fetch\([^)]*(raw|fullLog|allTraces)", "raw log auto-fetch cue"),
]
# B rules split: product-path-only cues (test fixtures may legitimately sleep/
# block) vs rules that apply everywhere.
FORBIDDEN_LINES_B = [
    (r"(sk-|AIza|eyJ)[A-Za-z0-9_\-]{12,}", "secret-shaped literal"),
    (r"permitAll", "auth relaxation"),
]
FORBIDDEN_LINES_B_PRODUCT = [
    (r"Thread\.sleep", "blocking sleep in search path"),
    (r"\b(Jedis|RedisClient|Lettuce|Redisson)\b", "new distributed infra (multi-replica HOLD)"),
    (r"class\s+\w*(Scheduler|Orchestrator|RateLimiterService)\b", "new public scheduler cue"),
    (r"\brouteEnabled\b|paidRoute|paid_route", "paid-route flag (budget HOLD)"),
    (r"System\.exit", "process exit in product code"),
]

WATCH = sorted(set(SNAPSHOT_A) | set(SNAPSHOT_B) |
               {RESTORER, GEN_OBS, STREAM_EVENT, SIGNAL_BUILDER, TRACE_CSS,
                EVIDENCE_GRAPH, CHAT_UI_HTML, SETTINGS_BRIDGE, PREF_SERVICE,
                ORCH, NOVA_AUTOCONF, RUN_REGISTRY, OAUTH_REG, PROMPT_BUILDER,
                BUILDER_TEST, TRACE_UI_TEST, TRACE_RESTORE_TEST,
                EVIDENCE_GRAPH_TEST, HTTP_CONTRACT_TEST, SYNC_LIFECYCLE_TEST,
                ROLE_TEST})


def sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_lines(path: Path):
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def _snapshot_for(root: Path, snap: dict, contract: str):
    rows, drifted, missing = [], 0, 0
    for rel, pin in snap.items():
        p = root / rel
        if not p.exists():
            rows.append({"path": rel, "status": "MISSING", "pin": pin[:12]})
            missing += 1
            continue
        cur = sha256(p)
        same = cur.startswith(pin)
        if not same:
            drifted += 1
        rows.append({"path": rel, "status": "SAME" if same else "DRIFTED",
                     "pin": pin[:12], "current": cur[:12],
                     "bytes": p.stat().st_size})
    return rows, drifted, missing


def cmd_hashes(root: Path):
    ra, da, ma = _snapshot_for(root, SNAPSHOT_A, CONTRACT_A)
    rb, db, mb = _snapshot_for(root, SNAPSHOT_B, CONTRACT_B)
    base_file = root / VAR_DIR / "baseline-hashes.json"
    base_rows = []
    if base_file.exists():
        try:
            base = json.loads(base_file.read_text(encoding="utf-8"))
            for rel, rec in base.get("files", {}).items():
                p = root / rel
                if not p.exists():
                    base_rows.append({"path": rel, "status": "MISSING"})
                    continue
                cur = sha256(p)
                base_rows.append({"path": rel,
                                  "status": "SAME" if cur == rec.get("sha256") else "DRIFTED_SINCE_BASELINE",
                                  "baseline": str(rec.get("sha256"))[:12], "current": cur[:12]})
        except Exception as e:  # noqa: BLE001
            base_rows.append({"error": str(e)})
    print(json.dumps({"schema": SCHEMA, "cmd": "hashes",
                      "note": "DRIFTED vs brief pin = expected while owner sessions patch; re-read before judging",
                      CONTRACT_A: {"drifted": da, "missing": ma, "rows": ra},
                      CONTRACT_B: {"drifted": db, "missing": mb, "rows": rb},
                      "baseline": {"file": str(base_file), "present": base_file.exists(),
                                   "rows": base_rows}}, indent=2))
    return 0


def _find(lines, idx, pat, window, whole_file):
    rx = re.compile(pat)
    if not whole_file and idx < len(lines) and rx.search(lines[idx]):
        return idx + 1
    lo = 0 if whole_file else max(0, idx - window)
    hi = len(lines) if whole_file else min(len(lines), idx + window + 1)
    return next((i + 1 for i in range(lo, hi) if rx.search(lines[i])), None)


def _pins_for(root: Path, pins, window=60):
    rows, bad = [], 0
    for rel, line, pat, label in pins:
        p = root / rel
        rec = {"anchor": f"{rel}:{line or '*'}", "label": label}
        if not p.exists():
            rec["status"] = "MISSING_FILE"
            bad += 1
            rows.append(rec)
            continue
        lines = read_lines(p)
        try:
            found = _find(lines, line - 1, pat, window, line == 0)
        except re.error as e:
            rec["status"] = "BAD_REGEX"
            rec["error"] = str(e)
            bad += 1
            rows.append(rec)
            continue
        if found is None:
            rec["status"] = "ABSENT"
            bad += 1
        elif found == line:
            rec["status"] = "SAME"
        else:
            rec["status"] = "MOVED"
            rec["nowLine"] = found
        rows.append(rec)
    return rows, bad


def cmd_pins(root: Path):
    ra, ba = _pins_for(root, PINS_A)
    rb, bb = _pins_for(root, PINS_B)
    print(json.dumps({"schema": SCHEMA, "cmd": "pins",
                      CONTRACT_A: {"anchors": len(ra), "problems": ba, "rows": ra},
                      CONTRACT_B: {"anchors": len(rb), "problems": bb, "rows": rb}},
                     indent=2))
    return 1 if (ba or bb) else 0


def _git_diff(root: Path, paths):
    try:
        out = subprocess.run(["git", "diff", "--"] + list(paths), cwd=root,
                             capture_output=True, encoding="utf-8",
                             errors="replace", timeout=90).stdout or ""
    except Exception:
        return [], []
    return _parse_unified(out)


def _parse_unified(text: str):
    added, touched = [], set()
    cur = None
    for ln in text.splitlines():
        if ln.startswith("+++ b/"):
            cur = ln[6:]
            touched.add(cur)
        elif ln.startswith("diff --git"):
            m = re.search(r" b/(\S+)", ln)
            if m:
                touched.add(m.group(1))
        elif ln.startswith("+") and not ln.startswith("+++"):
            added.append((cur, ln[1:]))
    return added, sorted(touched)


def _load_diff_arg(root: Path, diff_file):
    if diff_file is None:
        return _git_diff(root, WATCH + [BUILDER_TEST])
    p = Path(diff_file)
    if not p.is_absolute():
        p = root / p
    try:
        return _parse_unified(p.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return [], []


def _file_has(root: Path, rel: str, pat: str) -> bool:
    p = root / rel
    return p.exists() and re.search(pat, p.read_text(encoding="utf-8", errors="replace")) is not None


def _cover_for(root: Path, cover, added):
    items = []
    for name, checks in cover:
        evid = []
        for rel, pat, label in checks:
            try:
                in_file = _file_has(root, rel, pat)
            except re.error:
                in_file = False
            in_diff = any(f == rel and re.search(pat, ln) for f, ln in added)
            evid.append({"label": label, "file": rel,
                         "inFile": bool(in_file), "inAddedDiff": bool(in_diff)})
        items.append({"acceptance": name, "evidence": evid})
    return items


def cmd_cover(root: Path):
    added, _ = _git_diff(root, WATCH + [BUILDER_TEST] +
                         [p for _, p in NAMED_TESTS_B])
    named_missing = [{"fqcn": fq, "path": p} for fq, p in NAMED_TESTS_B
                     if not (root / p).exists()]
    print(json.dumps({"schema": SCHEMA, "cmd": "cover",
                      "note": "inFile/inAddedDiff are seam-presence cues; focused Gradle/Node runs are the verdict",
                      CONTRACT_A: _cover_for(root, COVER_A, added),
                      CONTRACT_B: _cover_for(root, COVER_B, added),
                      "namedTestsMissing": named_missing}, indent=2))
    return 0


def _contract_for_path(rel: str):
    in_a = rel in ALLOWED_A
    in_b = rel in ALLOWED_B
    if in_a and in_b:
        return "A+B"
    if in_a:
        return "A"
    if in_b:
        return "B"
    return None


def cmd_diff_review(root: Path, diff_file=None):
    added, touched = _load_diff_arg(root, diff_file)
    flags = []
    for rel in touched:
        if not rel.startswith(PRODUCT_PREFIX):
            continue
        c = _contract_for_path(rel)
        if c is None:
            note = FOREIGN_LEASE_CANDIDATES.get(rel)
            pin = SNAPSHOT_A.get(rel) or SNAPSHOT_B.get(rel)
            p = root / rel
            if note is None and pin is not None and p.exists() \
                    and sha256(p).startswith(pin):
                note = "pre-brief diff vs HEAD, brief pin still unchanged"
            flags.append({"file": rel, "line": "<file touched>",
                          "why": note or "product file outside both brief scopes",
                          "info": bool(note)})
    for f, ln in added:
        c = _contract_for_path(f or "")
        rules = []
        if c in ("A", "A+B"):
            rules += FORBIDDEN_LINES_A
        if c in ("B", "A+B"):
            rules += FORBIDDEN_LINES_B
            if (f or "").startswith("main/"):
                rules += FORBIDDEN_LINES_B_PRODUCT
        if c is None and (f or "").startswith(PRODUCT_PREFIX):
            rules = FORBIDDEN_LINES_A + FORBIDDEN_LINES_B  # out-of-scope: still scan
            if (f or "").startswith("main/"):
                rules += FORBIDDEN_LINES_B_PRODUCT
        for pat, why in rules:
            if re.search(pat, ln):
                flags.append({"file": f, "line": ln.strip()[:140], "why": why})
    real = [f for f in flags if not f.get("info")]
    print(json.dumps({"schema": SCHEMA, "cmd": "diff-review",
                      "verdict": "CLEAN" if not real else "FLAGS",
                      "touchedPaths": touched,
                      "note": "flags are review cues, not verdicts; info=known foreign-lease candidate",
                      "flags": flags}, indent=2))
    return 1 if real else 0


def cmd_state(root: Path):
    # Contract A phase cues
    builder_txt = (root / RESP_BUILDER).read_text(encoding="utf-8", errors="replace") \
        if (root / RESP_BUILDER).exists() else ""
    ui_txt = (root / TRACE_UI).read_text(encoding="utf-8", errors="replace") \
        if (root / TRACE_UI).exists() else ""
    a_drifted = sum(1 for rel, pin in SNAPSHOT_A.items()
                    if (root / rel).exists() and not sha256(root / rel).startswith(pin))
    provider_projected = "observedProvider" in builder_txt or "observed_provider" in builder_txt
    provider_rendered = "observedProvider" in ui_txt or "응답 제공자" in ui_txt
    if a_drifted == 0 and not provider_projected:
        phase_a = "PINNED_PRE_BRIEF"
    elif provider_projected and provider_rendered:
        phase_a = "PROVIDER_PROJECTED_AND_RENDERED (verify owner tests)"
    elif a_drifted or provider_projected:
        phase_a = "IN_FLIGHT"
    else:
        phase_a = "UNKNOWN"
    # Contract B phase cues
    b_drifted = sum(1 for rel, pin in SNAPSHOT_B.items()
                    if (root / rel).exists() and not sha256(root / rel).startswith(pin))
    seam_markers = []
    for rel, pat, label in [
            (NAVER, r"(?i)(queueWait|fairWait|ownerFair|permitQueue)", "naver fair-wait seam"),
            (BRAVE, r"(?i)(queueWait|fairWait|ownerFair|permitQueue)", "brave fair-wait seam"),
            (ASPECT, r"(?i)(localCongestion|local_queue|LOCAL_) ", "local-vs-shared split marker")]:
        if _file_has(root, rel, pat):
            seam_markers.append(label)
    phase_b = "IN_FLIGHT" if (b_drifted or seam_markers) else "PRE_BRIEF"
    print(json.dumps({"schema": SCHEMA, "cmd": "state",
                      CONTRACT_A: {"phase": phase_a, "briefPinsDrifted": a_drifted,
                                   "providerProjected": provider_projected,
                                   "providerRendered": provider_rendered,
                                   "ownerJournal": "codex-interview-feature-ee02e904"},
                      CONTRACT_B: {"phase": phase_b, "briefPinsDrifted": b_drifted,
                                   "newSeamMarkers": seam_markers,
                                   "note": "WP0-WP4 per brief; offline mock/Clock first"},
                      "note": "phase is a file-state classify; GREEN verdict = owning session's focused runs"},
                     indent=2))
    return 0


def cmd_lease(root: Path):
    targets = sorted(set(ALLOWED_A) | set(ALLOWED_B) | set(SNAPSHOT_B))
    manifest = {"targets": [{"path": rel,
                             "sha256": (sha256(root / rel) if (root / rel).exists() else None)}
                            for rel in targets]}
    out_dir = root / VAR_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    mpath = out_dir / "targets-current.json"
    mpath.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    ps1 = root / "__patch_drop__" / "source_edit_session.ps1"
    cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1),
           "-Action", "status", "-Json", "-TargetManifest", str(mpath)]
    try:
        res = subprocess.run(cmd, cwd=root, capture_output=True, encoding="utf-8",
                             errors="replace", timeout=120)
        print(res.stdout.strip() or res.stderr.strip())
        return res.returncode
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"schema": SCHEMA, "cmd": "lease", "error": str(e)}))
        return 2


def cmd_snapshot(root: Path):
    out_dir = root / VAR_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    files = {}
    for rel in WATCH:
        p = root / rel
        if p.exists():
            files[rel] = {"sha256": sha256(p), "bytes": p.stat().st_size}
        else:
            files[rel] = {"sha256": None, "bytes": None, "missing": True}
    rec = {"schema": SCHEMA, "cmd": "snapshot",
           "takenAtUtc": datetime.now(timezone.utc).isoformat(),
           "files": files}
    mpath = out_dir / "baseline-hashes.json"
    mpath.write_text(json.dumps(rec, indent=2), encoding="utf-8")
    print(json.dumps({"schema": SCHEMA, "cmd": "snapshot",
                      "written": str(mpath), "files": len(files)}, indent=2))
    return 0


def cmd_journals(root: Path):
    jroot = root / "data" / "agent-handoff" / "codex-autonomy"
    watched = set(WATCH) | set(ALLOWED_B)
    hits = []
    if jroot.exists():
        for jd in sorted(jroot.iterdir()):
            jf = jd / "journal.json"
            if not jf.exists():
                continue
            try:
                j = json.loads(jf.read_text(encoding="utf-8", errors="replace"))
            except Exception:
                continue
            if j.get("status") not in ("in_progress", "blocked"):
                continue
            scope = [s.lower() for s in j.get("plannedScope", [])]
            overlap = sorted(w for w in watched if any(w.lower() == s or w.lower().endswith(s)
                                                     for s in scope))
            hits.append({"taskId": j.get("taskId"), "agent": j.get("agent"),
                         "status": j.get("status"), "purpose": j.get("purpose"),
                         "updatedAtUtc": j.get("updatedAtUtc"),
                         "watchedPathOverlap": overlap})
    print(json.dumps({"schema": SCHEMA, "cmd": "journals",
                      "note": "timing gate: Gradle/server/smoke only after the owning journal's final report",
                      "active": hits}, indent=2))
    return 0


def cmd_status(root: Path):
    print("== hashes ==")
    cmd_hashes(root)
    print("== pins ==")
    cmd_pins(root)
    print("== state ==")
    cmd_state(root)
    print("== journals ==")
    cmd_journals(root)
    print("== cover ==")
    cmd_cover(root)
    return 0


def main(argv):
    root = Path(".")
    diff_file = None
    args = list(argv)
    if "--root" in args:
        i = args.index("--root")
        root = Path(args[i + 1])
        del args[i:i + 2]
    if "--diff" in args:
        i = args.index("--diff")
        diff_file = args[i + 1]
        del args[i:i + 2]
    cmd = args[0] if args else "status"
    if cmd == "diff-review":
        return cmd_diff_review(root, diff_file)
    fn = {"hashes": cmd_hashes, "pins": cmd_pins, "state": cmd_state,
          "cover": cmd_cover, "lease": cmd_lease, "snapshot": cmd_snapshot,
          "journals": cmd_journals, "status": cmd_status}.get(cmd)
    if fn is None:
        print(__doc__)
        return 2
    return fn(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
