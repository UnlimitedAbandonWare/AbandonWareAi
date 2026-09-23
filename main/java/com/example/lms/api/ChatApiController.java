package com.example.lms.api;

import java.util.Optional;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.dto.LearningContextMetadata;
import com.example.lms.gptsearch.decision.SearchDecision;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyException;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.infra.selection.SelectionEntropyProjection;
import com.example.lms.infra.selection.SelectionEntropyReason;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.orchestration.control.RagControlPresentationBoundary;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.service.rag.chain.impl.ChainRunner;
import com.example.lms.service.AdaptiveTranslationService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.ChatWorkflow;
import com.example.lms.service.AgentVisibleDebugEvidenceBuilder;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.guard.SensitiveTopicDetector;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.service.trace.DebugCopilotService;
import com.example.lms.trace.SearchTraceConsoleLogger;
import com.example.lms.trace.FailureTagNormalizer;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.SelectionEntropyTraceSupport;
import com.example.lms.trace.StageBoundaryBreadcrumbs;
import com.example.lms.trace.TraceContext;
import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.SettingsService;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.AttachmentOwnerIdentity;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import reactor.util.context.Context;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.Disposable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.scheduler.Schedulers;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.rag.content.Content;

import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.web.OwnerKeyResolver;
import com.example.lms.planning.artplate.MoEGate;
import com.example.lms.planning.artplate.ArtPlate;
import com.example.lms.planning.ComplexityScore;
import com.example.lms.planning.StrategyTelemetry;
import com.example.lms.orchestration.WorkflowOrchestrator;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.rag.model.QueryDomain;
// [HARDENING]

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatApiController {
    private static final Logger log = LoggerFactory.getLogger(ChatApiController.class);
    // ===== constants =====
    private static final String FALLBACK_MODEL = ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL;
    private static final String KEY_DEFAULT_MODEL = SettingsService.KEY_OPENAI_MODEL;
    private static final String DEFAULT_MODEL_WAIT_STATUS_CODE = "waiting_for_default_model";
    private static final String SESSION_EXPIRED_MESSAGE = "세션이 만료되었습니다.";
    private static final String ATTACHMENT_LOAD_FAILURE_FORMAT = "첨부 %d개 중 %d개 로드 실패";
    private static final long CHAT_RUN_CLIENT_ACK_TIMEOUT_MILLIS = 3_000L;
    private static final String DEFAULT_MODEL_WAIT_STATUS_MESSAGE =
            "기본 모델 응답을 기다리는 중입니다. 시간이 걸릴 수 있어요. 답변이 도착하면 이어서 전송합니다.";
    static final String EMPTY_OFFICIAL_EVIDENCE_FALLBACK =
            "evidence_needed: official/changelog evidence is missing from the current runtime answer path.";
    private static final String EMPTY_GENERAL_ANSWER_FALLBACK =
            "기본 모델 응답이 지금 안정적으로 생성되지 않아 로컬 안전 응답으로 먼저 안내드립니다. 질문은 접수했습니다. 현재 사용할 근거가 없어 확정 답변은 제한됩니다. 원하는 출력 형식이나 추가 근거를 알려주시면 이어서 도와드리겠습니다.";
    private static final java.util.regex.Pattern SEARCH_PROBE_PREFIX = java.util.regex.Pattern.compile(
            "^\\s*(LIGHT|DEEP)\\s+검색\\s+점검\\s*:\\s*",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern SEARCH_PROBE_OUTPUT_INSTRUCTION = java.util.regex.Pattern.compile(
            "\\s*(?:을|를)\\s+(?:한|두)\\s*(?:문장|줄)으로.*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    // ??? 嶺뚮∥?? ?熬곣뱿遊????츩(?筌뤾쑬????????
    private static final java.util.regex.Pattern EXPLICIT_EVIDENCE_DOMAIN = java.util.regex.Pattern.compile(
            "(?<![a-z0-9-])((?:[a-z0-9][a-z0-9-]*\\.)+(?:com|org|net|io|ai|dev|co|kr|edu|gov))(?![a-z0-9-]|\\.[a-z0-9-])",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String MODEL_META_PREFIX = "?MODEL?";
    // FE ?筌뤾쑵?????녹맠
    private static final String EXPOSE_HEADERS = "X-Model-Used,X-RAG-Used,X-User,X-Session-Owner,X-Session-Id,X-Request-Id,X-Trace-Snapshot-Id";

    @ExceptionHandler(PublicRequestBudgetGuard.Rejection.class)
    public ResponseEntity<Map<String, Object>> publicRequestBudgetRejected(
            PublicRequestBudgetGuard.Rejection rejection) {
        return ResponseEntity.status(rejection.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", rejection.status().value(),
                        "reasonCode", rejection.reasonCode()));
    }

    @ExceptionHandler(PublicChatAdmissionGuard.Rejection.class)
    public ResponseEntity<Map<String, Object>> publicChatAdmissionRejected(
            PublicChatAdmissionGuard.Rejection rejection) {
        return ResponseEntity.status(rejection.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", rejection.status().value(),
                        "reasonCode", rejection.reasonCode()));
    }

    @ExceptionHandler(ChatHistoryService.SessionQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> sessionQuotaExceeded(
            ChatHistoryService.SessionQuotaExceededException ignored) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", HttpStatus.BAD_REQUEST.value(),
                        "reasonCode", "session_quota_exceeded"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> publicRequestBodyInvalid(
            HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", HttpStatus.BAD_REQUEST.value(),
                        "reasonCode", "chat_body_invalid"));
    }

    static boolean hasAttachments(ChatRequestDto request) {
        return request != null
                && request.getAttachmentIds() != null
                && !request.getAttachmentIds().isEmpty();
    }

    static void markCheapSearchMode(GuardContext context, SearchMode searchMode, String phase) {
        boolean forceLight = searchMode == SearchMode.FORCE_LIGHT;
        if (context != null) {
            context.setCheapSearchMode(forceLight);
        }
        if (forceLight) {
            TraceStore.put("search.mode.lightAuxBypass", true);
            TraceStore.put("search.mode.lightAuxBypass.reason", "force_light");
            TraceStore.putInternal("search.mode.lightAuxBypass.phase",
                    SafeRedactor.traceLabelOrFallback(phase, "unknown"));
        } else {
            TraceStore.put("search.mode.lightAuxBypass", null);
            TraceStore.put("search.mode.lightAuxBypass.reason", null);
            TraceStore.putInternal("search.mode.lightAuxBypass.phase", null);
        }
    }

    static boolean shouldUseWebForSearchMode(
            String query,
            SearchMode searchMode,
            boolean finalUseWeb,
            boolean finalUseRag,
            SearchDecisionService decisions,
            Integer topK) {
        if (!finalUseWeb) {
            return false;
        }
        SearchMode mode = searchMode == null ? SearchMode.AUTO : searchMode;
        return switch (mode) {
            case OFF -> false;
            case FORCE_LIGHT, FORCE_DEEP -> true;
            case AUTO -> {
                if (looksLikeDomainEvidenceSearchIntent(query)) {
                    TraceStore.put("chat.search.autoDecision.domainEvidenceProbe", true);
                    yield true;
                }
                SearchDecisionService service = decisions == null ? new SearchDecisionService() : decisions;
                try {
                    SearchDecision decision = service.decide(query, SearchMode.AUTO, null, topK);
                    yield decision != null && decision.shouldSearch();
                } catch (RuntimeException ex) {
                    TraceStore.put("chat.search.autoDecision.failed", true);
                    TraceStore.put("chat.search.autoDecision.failureType",
                            SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
                    yield false;
                }
            }
        };
    }

    static String emptyFinalTextFallback(String query) {
        String raw = query == null ? "" : query;
        String q = raw.toLowerCase(java.util.Locale.ROOT);
        if (strictNamedOfficialEvidenceProbe(raw) || q.contains("evidence_needed")) {
            TraceStore.put("chatApi.emptyFinalText.evidenceNeeded", true);
            TraceStore.put("chatApi.emptyFinalText.reason", "official_evidence_missing");
            return EMPTY_OFFICIAL_EVIDENCE_FALLBACK;
        }
        return EMPTY_GENERAL_ANSWER_FALLBACK;
    }

    static boolean isEmptyFinalTextFallback(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim();
        return EMPTY_GENERAL_ANSWER_FALLBACK.equals(normalized)
                || EMPTY_OFFICIAL_EVIDENCE_FALLBACK.equals(normalized);
    }

    private static boolean looksLikeDomainEvidenceSearchIntent(String query) {
        String q = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (q.isBlank()) {
            return false;
        }
        boolean evidenceScoped = q.contains("evidence")
                || q.contains("citation")
                || q.contains("source")
                || q.contains("\uADFC\uAC70")
                || q.contains("\uCD9C\uCC98")
                || q.contains("\uC99D\uAC70");
        if (!evidenceScoped) {
            return false;
        }
        return q.contains("site:")
                || q.contains("http://")
                || q.contains("https://")
                || hasExplicitEvidenceDomain(q)
                || q.contains("official doc")
                || q.contains("official source")
                || q.contains("official/external")
                || q.contains("official evidence")
                || q.contains("\uACF5\uC2DD \uBB38\uC11C")
                || looksLikeNamedOfficialSourceDomainProbe(q)
                || looksLikeNamedOfficialFreshnessProbe(q);
    }

    static String domainEvidenceSearchQuery(String query) {
        String raw = query == null ? "" : query;
        if (!looksLikeDomainEvidenceSearchIntent(raw)) {
            return raw;
        }
        String q = raw.toLowerCase(java.util.Locale.ROOT);
        if (q.contains("site:")) {
            return raw;
        }
        List<String> explicitDomains = explicitEvidenceDomains(q);
        if (explicitDomains.isEmpty()) {
            List<String> namedDomains = namedOfficialEvidenceDomains(raw);
            if (namedDomains.isEmpty()) {
                return raw;
            }
            TraceStore.put("chat.search.providerQuery.siteFilter", true);
            TraceStore.put("chat.search.providerQuery.namedOfficialDomains", namedDomains.size());
            return siteFilterQuery(namedDomains, raw);
        }
        List<String> preferredDomains = new ArrayList<>();
        for (String domain : explicitDomains) {
            addDomain(preferredDomains, preferredEvidenceSearchDomain(domain, raw));
        }
        TraceStore.put("chat.search.providerQuery.siteFilter", true);
        return siteFilterQuery(preferredDomains, raw);
    }

    static String providerSearchQuery(String query) {
        String raw = query == null ? "" : query;
        java.util.regex.Matcher prefix = SEARCH_PROBE_PREFIX.matcher(raw);
        if (!prefix.find()) {
            return domainEvidenceSearchQuery(raw);
        }
        String probeBody = prefix.replaceFirst("").trim();
        String factualQuery = SEARCH_PROBE_OUTPUT_INSTRUCTION.matcher(probeBody).replaceFirst("").trim();
        if (factualQuery.isBlank()) {
            factualQuery = probeBody;
        }
        TraceStore.put("chat.search.providerQuery.probeInstructionRemoved", true);
        TraceStore.put("chat.search.providerQuery.probeMode", prefix.group(1).toUpperCase(java.util.Locale.ROOT));
        TraceStore.put("chat.search.providerQuery.probeQueryHash", SafeRedactor.hashValue(factualQuery));
        TraceStore.put("chat.search.providerQuery.probeQueryLength", factualQuery.length());
        return domainEvidenceSearchQuery(factualQuery);
    }

    static SearchMode effectiveSearchMode(String query, SearchMode requested) {
        SearchMode mode = requested == null ? SearchMode.AUTO : requested;
        if (mode != SearchMode.AUTO) {
            return mode;
        }
        java.util.regex.Matcher prefix = SEARCH_PROBE_PREFIX.matcher(query == null ? "" : query);
        if (!prefix.find()) {
            return mode;
        }
        SearchMode inferred = "DEEP".equalsIgnoreCase(prefix.group(1))
                ? SearchMode.FORCE_DEEP
                : SearchMode.FORCE_LIGHT;
        TraceStore.put("chat.search.modeInferredFromProbePrefix", true);
        TraceStore.put("chat.search.inferredMode", inferred.name());
        return inferred;
    }

    private static String preferredEvidenceSearchDomain(String domain, String query) {
        String normalizedDomain = domain == null ? "" : domain.toLowerCase(java.util.Locale.ROOT);
        String normalizedQuery = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if ("openai.com".equals(normalizedDomain)
                && (normalizedQuery.contains("api")
                || normalizedQuery.contains("model")
                || normalizedQuery.contains("docs")
                || normalizedQuery.contains("documentation")
                || normalizedQuery.contains("\uBB38\uC11C")
                || normalizedQuery.contains("\uBAA8\uB378"))) {
            TraceStore.put("chat.search.providerQuery.preferredDomain", "developers.openai.com");
            return "developers.openai.com";
        }
        return normalizedDomain;
    }

    static List<String> prioritizeDomainEvidenceSnippets(String query, List<String> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return snippets == null ? List.of() : snippets;
        }
        List<String> domains = requestedEvidenceDomains(query);
        if (domains.isEmpty()) {
            return snippets;
        }
        List<String> matching = new ArrayList<>();
        List<String> remainder = new ArrayList<>();
        for (String snippet : snippets) {
            if (snippetSourceUrlMatchesAnyDomain(snippet, domains)) {
                matching.add(snippet);
            } else {
                remainder.add(snippet);
            }
        }
        if (strictNamedOfficialEvidenceProbe(query)) {
            TraceStore.put("chat.search.providerQuery.namedOfficialFiltered", true);
            TraceStore.put("chat.search.providerQuery.namedOfficialFilteredCount", matching.size());
            return matching;
        }
        if (matching.isEmpty()) {
            return snippets;
        }
        List<String> ordered = new ArrayList<>(snippets.size());
        ordered.addAll(matching);
        ordered.addAll(remainder);
        TraceStore.put("chat.search.providerQuery.domainPrioritized", true);
        TraceStore.put("chat.search.providerQuery.domainPrioritizedCount", matching.size());
        return ordered;
    }

    static List<String> completeNamedOfficialCoverageSnippets(
            String query,
            List<String> currentSnippets,
            java.util.function.Function<String, List<String>> rescueSearch) {
        List<String> current = currentSnippets == null ? List.of() : currentSnippets;
        if (!strictNamedOfficialEvidenceProbe(query) || rescueSearch == null) {
            return current;
        }
        List<String> domains = namedOfficialEvidenceDomains(query);
        if (domains.size() < 2) {
            return current;
        }

        List<String> merged = new ArrayList<>(current);
        int attempts = 0;
        int added = 0;
        int failures = 0;
        for (String domain : domains) {
            if (snippetListContainsDomain(merged, domain)) {
                continue;
            }
            attempts++;
            try {
                List<String> rescueSnippets = rescueSearch.apply(namedOfficialRescueQueryForDomain(query, domain));
                if (rescueSnippets == null || rescueSnippets.isEmpty()) {
                    continue;
                }
                for (String snippet : rescueSnippets) {
                    if (snippetSourceUrlMatchesDomain(snippet, domain) && !merged.contains(snippet)) {
                        merged.add(snippet);
                        added++;
                    }
                }
            } catch (RuntimeException ex) {
                failures++;
                TraceStore.put("chat.search.providerQuery.namedOfficialCoverageRescueFailureType",
                        SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            }
        }
        if (attempts > 0) {
            TraceStore.put("chat.search.providerQuery.namedOfficialCoverageRescueAttemptCount", attempts);
            TraceStore.put("chat.search.providerQuery.namedOfficialCoverageRescueAddedCount", added);
            TraceStore.put("chat.search.providerQuery.namedOfficialCoverageRescueFailureCount", failures);
        }
        return added > 0 ? prioritizeDomainEvidenceSnippets(query, merged) : current;
    }

    private static String namedOfficialRescueQueryForDomain(String query, String domain) {
        String focused = query == null ? "" : query.trim();
        String normalizedDomain = domain == null ? "" : domain.toLowerCase(java.util.Locale.ROOT);
        if (normalizedDomain.contains("supabase.com")) {
            focused = removeProviderNoise(focused,
                    "\\bopenai\\b",
                    "responses\\s+api",
                    "\\bweb_search\\b",
                    "\\bfile_search\\b",
                    "\\bcomputer_use\\b");
            if (!focused.toLowerCase(java.util.Locale.ROOT).contains("supabase")) {
                focused = "Supabase " + focused;
            }
        } else if (normalizedDomain.contains("openai.com")) {
            focused = removeProviderNoise(focused,
                    "\\bsupabase\\b",
                    "\\bmcp\\b",
                    "\\bread[_ -]?only\\b",
                    "\\bproject[_ -]?ref\\b");
            if (!focused.toLowerCase(java.util.Locale.ROOT).contains("openai")) {
                focused = "OpenAI " + focused;
            }
        }
        focused = removeOfficialEvidenceInstructionNoise(focused);
        focused = focused.replaceAll("\\s+", " ").trim();
        if (focused.isBlank()) {
            focused = query == null ? "" : query;
        }
        return siteFilterQuery(List.of(domain), focused);
    }

    private static String removeOfficialEvidenceInstructionNoise(String text) {
        String out = text == null ? "" : text;
        out = out.replaceAll("(?i)\\banswer\\s+only\\s+from\\b", " ");
        out = out.replaceAll("(?i)\\bif\\s+official\\s+evidence\\s+is\\s+missing\\s+say\\s+evidence_needed\\b", " ");
        out = out.replaceAll("(?i)\\bofficial\\s+evidence\\s+is\\s+missing\\b", " ");
        out = out.replaceAll("(?i)\\bevidence_needed\\b", " ");
        return out.replaceAll("\\s+([,;:.])", "$1")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String removeProviderNoise(String text, String... patterns) {
        String out = text == null ? "" : text;
        if (patterns != null) {
            for (String pattern : patterns) {
                if (pattern == null || pattern.isBlank()) {
                    continue;
                }
                out = out.replaceAll("(?i)" + pattern, " ");
            }
        }
        return out.replaceAll("\\s+([,;:])", "$1")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    static String domainEvidenceFallbackSearchQuery(String query, String providerQuery, List<?> snippets) {
        String raw = query == null ? "" : query.trim();
        String provider = providerQuery == null ? "" : providerQuery.trim();
        if (raw.isBlank() || provider.isBlank() || raw.equals(provider)) {
            return null;
        }
        if (!looksLikeDomainEvidenceSearchIntent(raw)) {
            return null;
        }
        if (!provider.toLowerCase(java.util.Locale.ROOT).startsWith("site:")) {
            return null;
        }
        if (snippets != null && !snippets.isEmpty()) {
            return null;
        }
        TraceStore.put("chat.search.providerQuery.siteFilterFallback", true);
        return raw;
    }

    static List<String> acceptDomainEvidenceFallbackSnippets(
            String query,
            List<String> fallbackSnippets,
            List<String> currentSnippets) {
        List<String> current = currentSnippets == null ? List.of() : currentSnippets;
        List<String> fallback = fallbackSnippets == null ? List.of() : fallbackSnippets;
        if (fallback.isEmpty()) {
            return current;
        }
        if (!looksLikeDomainEvidenceSearchIntent(query)) {
            return fallback;
        }
        List<String> domains = requestedEvidenceDomains(query);
        if (domains.isEmpty()) {
            return fallback;
        }
        List<String> prioritized = prioritizeDomainEvidenceSnippets(query, fallback);
        boolean hasRequestedSource = prioritized.stream()
                .anyMatch(snippet -> snippetSourceUrlMatchesAnyDomain(snippet, domains));
        if (hasRequestedSource) {
            return prioritized;
        }
        TraceStore.put("chat.search.providerQuery.domainFallbackRejected", true);
        TraceStore.put("chat.search.providerQuery.domainFallbackRejectedCount", fallback.size());
        return current;
    }

    private static boolean snippetListContainsDomain(List<String> snippets, String domain) {
        if (snippets == null || snippets.isEmpty()) {
            return false;
        }
        for (String snippet : snippets) {
            if (snippetSourceUrlMatchesDomain(snippet, domain)) {
                return true;
            }
        }
        return false;
    }

    static boolean prefetchQueryIdentityMatches(String prefetchedProviderQuery, String requestedProviderQuery) {
        return com.example.lms.service.rag.WebSearchRetriever.matchesPrefetchQueryIdentity(
                prefetchedProviderQuery, requestedProviderQuery);
    }

    private static void recordPrefetchIdentityDecision(
            String prefetchedProviderQuery,
            String requestedProviderQuery,
            boolean queryMatches,
            boolean scopeMatches,
            String decision) {
        String prefetchedFingerprint = com.example.lms.service.rag.WebSearchRetriever
                .prefetchQueryFingerprint12(prefetchedProviderQuery);
        TraceStore.put("chatApi.web.prefetch.identityPresent", prefetchedFingerprint != null);
        TraceStore.put("chatApi.web.prefetch.identityMatch", queryMatches && scopeMatches);
        TraceStore.put("chatApi.web.prefetch.decision", decision);
        if (!queryMatches || !scopeMatches) {
            TraceStore.put("chatApi.web.prefetch.mismatchReason",
                    prefetchedFingerprint == null ? "identity_missing"
                            : (!queryMatches ? "query_mismatch" : "filter_scope_mismatch"));
        }
        String fingerprint = com.example.lms.service.rag.WebSearchRetriever
                .prefetchQueryFingerprint12(requestedProviderQuery);
        if (fingerprint != null) {
            TraceStore.put("chatApi.web.prefetch.queryFingerprint12", fingerprint);
        }
    }

    static void recordWebPrefetchTrace(
            String phase,
            boolean requestUseWeb,
            boolean resolvedUseWeb,
            boolean resolvedUseRag,
            SearchMode searchMode,
            Integer topK,
            String providerQuery,
            List<String> snippets) {
        String safePhase = SafeRedactor.traceLabelOrFallback(phase, "unknown");
        if (safePhase == null || safePhase.isBlank()) {
            safePhase = "unknown";
        }
        String prefix = "chatApi.web.prefetch." + safePhase;
        String safeQuery = providerQuery == null ? "" : providerQuery;
        TraceStore.put(prefix + ".requestUseWeb", requestUseWeb);
        TraceStore.put(prefix + ".resolvedUseWeb", resolvedUseWeb);
        TraceStore.put(prefix + ".resolvedUseRag", resolvedUseRag);
        TraceStore.put(prefix + ".searchMode", searchMode == null ? SearchMode.AUTO.name() : searchMode.name());
        TraceStore.put(prefix + ".topK", topK == null ? 0 : Math.max(0, topK));
        TraceStore.put(prefix + ".providerQueryHash", SafeRedactor.hashValue(safeQuery));
        TraceStore.put(prefix + ".providerQueryLength", safeQuery.length());
        TraceStore.put(prefix + ".providerQuerySiteFilter", safeQuery.toLowerCase(java.util.Locale.ROOT).startsWith("site:"));
        String siteDomain = providerQuerySiteDomain(safeQuery);
        if (siteDomain != null && !siteDomain.isBlank()) {
            TraceStore.put(prefix + ".providerQuerySiteDomain", siteDomain);
        }
        TraceStore.put(prefix + ".snippetCount", snippets == null ? 0 : snippets.size());
    }

    static void recordSearchModeRewriteHint(SearchMode searchMode) {
        SearchMode mode = searchMode == null ? SearchMode.AUTO : searchMode;
        boolean recallRequested = mode == SearchMode.FORCE_DEEP;
        String profile = switch (mode) {
            case FORCE_DEEP -> "exploratory";
            case FORCE_LIGHT -> "conservative";
            case OFF -> "disabled";
            case AUTO -> "balanced";
        };
        double validationTemperature = switch (mode) {
            case FORCE_LIGHT, OFF -> 0.10d;
            case FORCE_DEEP, AUTO -> 0.15d;
        };
        double explorationTemperature = switch (mode) {
            case FORCE_DEEP -> 0.70d;
            case AUTO -> 0.55d;
            case FORCE_LIGHT -> 0.25d;
            case OFF -> 0.10d;
        };
        double explorationRate = switch (mode) {
            case FORCE_DEEP -> 0.45d;
            case AUTO -> 0.35d;
            case FORCE_LIGHT -> 0.10d;
            case OFF -> 0.0d;
        };
        int verificationLaneCount = mode == SearchMode.OFF ? 0 : 1;
        int explorationLaneCount = switch (mode) {
            case FORCE_DEEP, AUTO -> 2;
            case FORCE_LIGHT, OFF -> 0;
        };
        TraceStore.put("chatApi.web.searchMode", mode.name());
        TraceStore.put("chatApi.web.recallModeRequested", recallRequested);
        TraceStore.put("web.query.rewrite.requestedTemperatureProfile",
                SafeRedactor.traceLabelOrFallback(profile, "auto"));
        TraceStore.put("web.query.rewrite.requestedValidationTemperature", validationTemperature);
        TraceStore.put("web.query.rewrite.requestedExplorationTemperature", explorationTemperature);
        TraceStore.put("web.query.rewrite.requestedExplorationRate", explorationRate);
        TraceStore.put("web.query.rewrite.requestedVerificationLaneCount", verificationLaneCount);
        TraceStore.put("web.query.rewrite.requestedExplorationLaneCount", explorationLaneCount);
    }

    private static String providerQuerySiteDomain(String providerQuery) {
        if (providerQuery == null || providerQuery.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\bsite:([a-z0-9][a-z0-9.-]*\\.(?:com|org|net|io|ai|dev|co|kr|edu|gov))\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(providerQuery);
        if (!matcher.find()) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(
                matcher.group(1).toLowerCase(java.util.Locale.ROOT),
                null);
    }

    private static String siteFilterQuery(List<String> domains, String raw) {
        StringBuilder sb = new StringBuilder();
        for (String domain : domains) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" OR ");
            }
            sb.append("site:").append(domain);
        }
        if (sb.length() == 0) {
            return raw;
        }
        return sb.append(' ').append(raw).toString();
    }

    private static String requestedEvidenceDomain(String query) {
        List<String> domains = requestedEvidenceDomains(query);
        return domains.isEmpty() ? null : domains.get(0);
    }

    private static List<String> requestedEvidenceDomains(String query) {
        String raw = query == null ? "" : query;
        if (!looksLikeDomainEvidenceSearchIntent(raw)) {
            return List.of();
        }
        List<String> domains = new ArrayList<>();
        java.util.regex.Matcher siteMatcher = java.util.regex.Pattern
                .compile("\\bsite:([a-z0-9][a-z0-9-]*\\.(?:com|org|net|io|ai|dev|co|kr|edu|gov))\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(raw);
        while (siteMatcher.find()) {
            addDomain(domains, siteMatcher.group(1));
        }
        for (String domain : explicitEvidenceDomains(raw)) {
            addDomain(domains, domain);
        }
        if (domains.isEmpty()) {
            domains.addAll(namedOfficialEvidenceDomains(raw));
        }
        return domains;
    }

    private static void addDomain(List<String> domains, String domain) {
        if (domain == null || domain.isBlank()) {
            return;
        }
        String normalized = domain.toLowerCase(java.util.Locale.ROOT);
        if (!domains.contains(normalized)) {
            domains.add(normalized);
        }
    }

    private static List<String> explicitEvidenceDomains(String query) {
        String raw = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        raw = raw.replaceAll(
                        "(?<![\\p{L}]\\.[\\p{L}])(?<=[\\p{L}\\p{N}])[.!?\\u3002\\uFF01\\uFF1F][\\\"'\\p{Pe}\\p{Pf}]*(?=[\\s\\p{Z}]+)",
                        ";")
                .replaceAll("(?<=[\\p{L}\\p{N}])[\\\"'\\p{Pe}\\p{Pf}]+[.!?\\u3002\\uFF01\\uFF1F](?=[\\s\\p{Z}]+)", ";")
                .replaceAll("(?<=[\\p{L}\\p{N}])[\\u3002\\uFF01\\uFF1F][\\\"'\\p{Pe}\\p{Pf}]*(?=[\\p{L}\\p{N}])", ";")
                .replaceAll("(?<=[\\p{L}\\p{N}])[\\\"'\\p{Pe}\\p{Pf}]+[\\u3002\\uFF01\\uFF1F](?=[\\p{L}\\p{N}])", ";");
        List<String> contextual = new ArrayList<>();
        addDomainsFromOfficialSourceContext(
                contextual,
                raw,
                "\\bofficial\\b([^;\\n]{0,320}?)\\b(?:sources?|domains?|evidence)\\b");
        addDomainsFromOfficialSourceContext(
                contextual,
                raw,
                "\\bofficial\\s+(?:sources?|domains?|evidence)\\b([^;\\n]{0,320})");
        addDomainsFromOfficialSourceContext(
                contextual,
                raw,
                "(?:^|[;\\n])([^;\\n]{0,320}?)\\bofficial\\s+(?:sources?|domains?|evidence)\\b");
        addDomainsFromOfficialSourceContext(
                contextual,
                raw,
                "(?:^|[;\\n])([^;\\n]{0,320}?)\uACF5\uC2DD\\s*(?:\uCD9C\uCC98|\uADFC\uAC70|\uBB38\uC11C)");
        addDomainsFromOfficialSourceContext(
                contextual,
                raw,
                "\uACF5\uC2DD\\s*(?:\uCD9C\uCC98|\uADFC\uAC70|\uBB38\uC11C)([^;\\n]{0,320})");
        if (!contextual.isEmpty()) {
            return contextual;
        }
        List<String> all = new ArrayList<>();
        addExplicitDomains(all, raw);
        return all;
    }

    private static void addDomainsFromOfficialSourceContext(
            List<String> domains,
            String query,
            String contextPattern) {
        java.util.regex.Matcher contextMatcher = java.util.regex.Pattern
                .compile(contextPattern)
                .matcher(query);
        while (contextMatcher.find()) {
            addExplicitDomains(domains, contextMatcher.group(1));
        }
    }

    private static void addExplicitDomains(List<String> domains, String text) {
        java.util.regex.Matcher matcher = EXPLICIT_EVIDENCE_DOMAIN.matcher(text);
        while (matcher.find()) {
            addDomain(domains, matcher.group(1));
        }
    }

    private static boolean hasExplicitEvidenceDomain(String query) {
        String raw = query == null ? "" : query;
        return EXPLICIT_EVIDENCE_DOMAIN.matcher(raw).find();
    }

    private static List<String> namedOfficialEvidenceDomains(String query) {
        String q = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        List<String> domains = new ArrayList<>();
        if (q.contains("openai")) {
            addDomain(domains, "developers.openai.com");
            if (looksLikeOpenAiFreshnessProbe(q)) {
                addDomain(domains, "openai.com");
            }
        }
        if (q.contains("supabase")) {
            addDomain(domains, "supabase.com");
        }
        return domains;
    }

    private static boolean strictNamedOfficialEvidenceProbe(String query) {
        String q = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        return looksLikeDomainEvidenceSearchIntent(query)
                && !hasExplicitEvidenceDomain(query)
                && !namedOfficialEvidenceDomains(query).isEmpty()
                && (q.contains("official source")
                || q.contains("official/external")
                || q.contains("official evidence")
                || q.contains("official sources")
                || looksLikeNamedOfficialFreshnessProbe(q)
                || looksLikeNamedOfficialSourceDomainProbe(q));
    }

    /**
     * Conservative upper bound for one controller-owned web-search phase.
     * It mirrors the initial call, both coverage-rescue passes, and the
     * generated site-filter fallback without executing or tracing a search.
     */
    static int maxWebSearchCallsPerPhase(String query) {
        String raw = query == null ? "" : query.trim();
        int rescueDomains = strictNamedOfficialEvidenceProbe(raw)
                && namedOfficialEvidenceDomains(raw).size() >= 2
                ? namedOfficialEvidenceDomains(raw).size()
                : 0;
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        boolean generatedSiteFilter = !raw.isBlank()
                && looksLikeDomainEvidenceSearchIntent(raw)
                && !lower.contains("site:")
                && (!explicitEvidenceDomains(lower).isEmpty()
                    || !namedOfficialEvidenceDomains(raw).isEmpty());
        return 1 + (2 * rescueDomains) + (generatedSiteFilter ? 1 : 0);
    }

    private static boolean looksLikeNamedOfficialFreshnessProbe(String q) {
        String normalized = q == null ? "" : q.toLowerCase(java.util.Locale.ROOT);
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

    private static boolean looksLikeOpenAiFreshnessProbe(String q) {
        String normalized = q == null ? "" : q.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("openai")
                && (normalized.contains("changelog")
                || normalized.contains("release notes")
                || normalized.contains("\uCD5C\uC2E0")
                || normalized.contains("\uBCC0\uACBD")
                || normalized.contains("\uC5C5\uB370\uC774\uD2B8"));
    }

    private static boolean looksLikeNamedOfficialSourceDomainProbe(String q) {
        String normalized = q == null ? "" : q.toLowerCase(java.util.Locale.ROOT);
        boolean namedOfficialTarget = normalized.contains("openai") || normalized.contains("supabase");
        boolean sourceDomainProbe = normalized.contains("source domain")
                || normalized.contains("source domains")
                || normalized.contains("\uCD9C\uCC98 \uB3C4\uBA54\uC778")
                || (normalized.contains("\uB3C4\uBA54\uC778")
                && (normalized.contains("\uCD9C\uCC98")
                || normalized.contains("\uADFC\uAC70")
                || normalized.contains("\uAC80\uC99D")));
        return namedOfficialTarget && sourceDomainProbe;
    }

    private static boolean snippetMatchesDomain(String snippet, String domain) {
        if (snippet == null || snippet.isBlank() || domain == null || domain.isBlank()) {
            return false;
        }
        String lower = snippet.toLowerCase(java.util.Locale.ROOT);
        String normalizedDomain = domain.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("://" + normalizedDomain)
                || lower.contains("." + normalizedDomain)
                || lower.contains("/" + normalizedDomain)
                || lower.contains(" " + normalizedDomain);
    }

    private static boolean snippetSourceUrlMatchesDomain(String snippet, String domain) {
        if (snippet == null || snippet.isBlank() || domain == null || domain.isBlank()) {
            return false;
        }
        String normalizedDomain = domain.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                .compile("https?://([^\\s)\\]}>,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(snippet);
        boolean sawUrl = false;
        while (urlMatcher.find()) {
            sawUrl = true;
            String host = urlMatcher.group(1);
            int slash = host.indexOf('/');
            if (slash >= 0) {
                host = host.substring(0, slash);
            }
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.equals(normalizedDomain) || host.endsWith("." + normalizedDomain)) {
                return true;
            }
        }
        return !sawUrl && snippetMatchesDomain(snippet, normalizedDomain);
    }

    private static boolean snippetSourceUrlMatchesAnyDomain(String snippet, List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return false;
        }
        for (String domain : domains) {
            if (snippetSourceUrlMatchesDomain(snippet, domain)) {
                return true;
            }
        }
        return false;
    }

    // When false (default) the API will not include traceHtml in /state
    // responses unless explicitly requested via the debug parameter.
    @org.springframework.beans.factory.annotation.Value("${abandonware.web.trace.expose:false}")
    private boolean exposeTrace;

    // ===== services =====
    private final ChatHistoryService historyService;
    private final ChatService chatService;
    private final AdaptiveTranslationService adaptiveService;
    private final SettingsService settingsService;

    /**
     * Low-level Naver search service used for trace HTML rendering and
     * compatibility helpers. The actual web search / fallback logic is
     * delegated to {@link WebSearchProvider}.
     */
    private final NaverSearchService searchService;

    /**
     * High-level web search provider that encapsulates Naver ??Brave
     * fallback logic. Controllers and orchestrators should use this
     * abstraction instead of talking to concrete engines directly.
     */
    private final WebSearchProvider webSearchProvider;

    private final SensitiveTopicDetector sensitiveTopicDetector;

    // Planner Nexus (plan auto-select) + plan YAML hint applier
    private final WorkflowOrchestrator workflowOrchestrator;
    private final PlanHintApplier planHintApplier;

    /**
     * Search trace HTML builder used to render the "?롪틵?????λ닔?? UI with a
     * split view: (A) raw web snippets and (B) final TopK context.
     */
    private final TraceHtmlBuilder traceHtmlBuilder;
    private final DebugCopilotService debugCopilotService;
    private final SearchTraceConsoleLogger searchTraceConsoleLogger;

    // In-memory snapshot store (optional; fail-soft in minimal builds)
    @Autowired(required = false)
    private TraceSnapshotStore traceSnapshotStore;

    @Autowired(required = false)
    private com.example.lms.debug.ai.DebugAiMetricsService debugAiMetricsService;

    @Autowired(required = false)
    private SearchDecisionService searchDecisionService;

    @Autowired(required = false)
    private com.example.lms.service.MemoryReinforcementService memoryReinforcementService;

    @Autowired(required = false)
    private com.example.lms.diag.RetrievalDiagnosticsCollector retrievalDiagnosticsCollector;

    @Autowired(required = false)
    private com.example.lms.debug.DebugEventTracePromotionService debugEventTracePromotionService;

    // 채팅 세션 종료 증거 번들 기록기 (var/debug/chat-session-traces). 없으면 fail-soft.
    @Autowired(required = false)
    private com.example.lms.debug.ChatSessionTraceRecorder chatSessionTraceRecorder;

    @Autowired(required = false)
    private ModelRuntimeHealthTracker modelRuntimeHealthTracker;

    @Autowired(required = false)
    private com.example.lms.orchestration.control.RagControlPresentationBoundary ragControlPresentationBoundary;

    @Autowired(required = false)
    private PublicRequestBudgetGuard publicRequestBudgetGuard = new PublicRequestBudgetGuard();

    @Autowired(required = false)
    private PublicChatAdmissionGuard publicChatAdmissionGuard = new PublicChatAdmissionGuard();

    @Autowired(required = false)
    private SelectionReplayRequestResolver selectionReplayRequestResolver;
    /**
     * Location service for intent detection and personalised location
     * responses. Injected to allow early interception of "where am I"
     * queries before invoking the language model or performing any
     * network searches.
     */
    private final com.example.lms.location.LocationService locationService;
    // [HARDENING] hybrid retriever for curated traces
    private final HybridRetriever hybridRetriever;

    /**
     * Attachment service used to resolve uploaded files into documents. This
     * field is injected via constructor thanks to {@link RequiredArgsConstructor}.
     */
    private final AttachmentService attachmentService;

    /**
     * Emitter used to push additional events such as understanding summaries
     * to the client over SSE. The controller registers and unregisters a
     * sink per session to receive asynchronous events from downstream
     * services. This bean is optional so that the application can run
     * without the understanding feature enabled.
     */
    private final ChatStreamEmitter chatStreamEmitter;
    private final ObjectMapper objectMapper;

    /**
     * Runner for the lightweight pre-processing chain. This is injected via
     * constructor thanks to {@link RequiredArgsConstructor}. The chain
     * combines location interception, attachment context injection and image
     * prompt grounding. It is executed prior to the main chat logic to
     * allow immediate responses (e.g. personalised location) and meta data
     * enrichment without interfering with the core chat flow.
     */
    private final ChainRunner chainRunner;

    /**
     * Registry of active chat runs. Used to support SSE replay on reconnection and
     * to track running sessions. Each run stores a replay sink allowing
     * multiple subscribers to join an in-flight generation without spawning
     * duplicate tasks.
     */
    private final com.example.lms.service.chat.ChatRunRegistry runRegistry;

    @Autowired(required = false)
    private ChatCancellationCommandHandler cancellationCommandHandler = new ChatCancellationCommandHandler();

    private final com.example.lms.web.ClientOwnerKeyResolver ownerKeyResolver;
    // === Default RAG toggle ===
    // Use server-side default when the client does not explicitly set useRag.
    // This property is defined in application.yml under chat.defaults.useRag and
    // defaults to true.
    @org.springframework.beans.factory.annotation.Value("${chat.defaults.useRag:true}")
    private boolean defaultUseRag;

    @org.springframework.beans.factory.annotation.Value("${memory.summary.shadow-vector-enabled:true}")
    private boolean sessionSummaryShadowVectorEnabled;

    @org.springframework.beans.factory.annotation.Value("${memory.summary.shadow-vector-score:0.72}")
    private double sessionSummaryShadowVectorScore;

    // ???? ?怨뺣깹???롪틵???嶺뚮ㅄ維獄??リ옇???泥??熬곣뫁夷???逾? ????
    /**
     * Master toggle for accumulation mode. When false the controller will
     * ignore any accumulation hints supplied by the client. Defaults to
     * disabled to avoid unintentional broad crawling.
     */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.enabled:false}")
    private boolean accumulationEnabled;

    /** Default provider top-k when accumulation mode is enabled. */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.web-top-k:30}")
    private int accumulationTopK;

    /**
     * Relatedness cutoff applied in accumulation mode. A lower value admits
     * more pages into the aggregated context.
     */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.min-relatedness:0.35}")
    private double accumulationMinRel;

    /** Page content fetch timeout (ms) when accumulation mode is enabled. */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.per-page-ms:4500}")
    private int accumulationPerPageMs;

    /**
     * Comma-separated list of provider IDs to prefer when accumulation mode is
     * active. When empty the handler uses all configured providers.
     */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.providers:}")
    private String accumulationProvidersCsv;

    /**
     * Cancel the currently running chat streaming for the given session. This
     * endpoint can be
     * invoked by the client when the user clicks a "Stop generation" button to
     * terminate long
     * running operations. The current implementation delegates to
     * {@link ChatService#cancelSession(Long)}
     * which performs best-effort cancellation of any in-flight tasks. This method
     * always returns
     * HTTP 200 OK regardless of whether there was an active stream to cancel.
     *
     * @param sessionId the session identifier to cancel; may be {@code null}
     * @return 200 OK
     */
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
                                       @RequestParam(required = false) Long sessionId,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader(name = "X-Chat-Run-Token", required = false) String runToken,
                                       Authentication authentication) {
        Long resolvedSessionId = firstNonNull(sessionId, longFromBody(body, "sessionId"));
        String resolvedRunToken = firstNonBlank(runToken, stringFromBody(body, "runToken"));
        ChatCancellationCommandHandler.Result result = cancellationCommandHandler.cancel(
                resolvedSessionId,
                resolvedRunToken,
                () -> authorizeCancellation(resolvedSessionId, authentication),
                runRegistry,
                () -> {
                    try {
                        historyService.appendMessage(resolvedSessionId, "assistant", "Response stopped");
                    } catch (Throwable failure) {
                        logSuppressed("cancel.appendStoppedMarker");
                    }
                });
        if ("cancel_failed".equals(result.reason())) {
            logSuppressed("cancel.cancelSession");
        }
        return ResponseEntity.ok(result.body());
    }

    private boolean authorizeCancellation(Long sessionId, Authentication authentication) {
        try {
            ChatSession session = historyService.getSessionWithMessages(sessionId);
            return session == null || canAccessSession(session, authentication);
        } catch (Exception failure) {
            log.warn("Failed to authorize /cancel for sessionHash={}: {}",
                    SafeRedactor.hashValue(String.valueOf(sessionId)), errorSummary(failure));
            return false;
        }
    }

    @PostMapping("/ack")
    public ResponseEntity<Map<String, Object>> acknowledgeRun(
            @RequestParam(required = false) Long sessionId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(name = "X-Chat-Run-Token", required = false) String runToken,
            Authentication authentication) {
        Long resolvedSessionId = firstNonNull(sessionId, longFromBody(body, "sessionId"));
        String resolvedRunToken = firstNonBlank(runToken, stringFromBody(body, "runToken"));
        String phase = firstNonBlank(stringFromBody(body, "phase"), "ready");
        if (resolvedSessionId == null || resolvedRunToken == null) {
            return ResponseEntity.ok(Map.of(
                    "acknowledged", false,
                    "reason", "run_not_found_or_not_acknowledgeable"));
        }
        try {
            ChatSession session = historyService.getSessionWithMessages(resolvedSessionId);
            if (session == null || !canAccessSession(session, authentication)) {
                return ResponseEntity.ok(Map.of(
                        "acknowledged", false,
                        "reason", "run_not_found_or_not_acknowledgeable"));
            }
            boolean finalPhase = "final".equalsIgnoreCase(phase);
            boolean recoveryPhase = "recovery".equalsIgnoreCase(phase);
            boolean acknowledged = runRegistry != null
                    && (finalPhase
                            ? runRegistry.acknowledgeFinalDeliveryExact(resolvedSessionId, resolvedRunToken)
                            : recoveryPhase
                                    ? runRegistry.acknowledgeRecoveredDeliveryExact(
                                            resolvedSessionId, resolvedRunToken)
                            : "ready".equalsIgnoreCase(phase)
                                    && runRegistry.acknowledgeExact(resolvedSessionId, resolvedRunToken));
            return ResponseEntity.ok(Map.of(
                    "acknowledged", acknowledged,
                    "phase", finalPhase ? "final" : recoveryPhase ? "recovery" : "ready",
                    "reason", acknowledged
                            ? finalPhase
                                    ? "final_acknowledged"
                                    : recoveryPhase ? "recovery_acknowledged" : "acknowledged"
                            : "run_not_found_or_not_acknowledgeable"));
        } catch (Exception failure) {
            logSuppressed("ack.exactRun");
            return ResponseEntity.ok(Map.of(
                    "acknowledged", false,
                    "reason", "run_not_found_or_not_acknowledgeable"));
        }
    }

    /**
     * Retrieve the current state of a chat session. This endpoint returns whether
     * the session is still running along with the last assistant message,
     * the model used and any trace HTML metadata embedded in the session. It is
     * used by the client to decide whether to attach to an in-flight run when
     * reloading the page. When the session does not exist the returned
     * {@code running} flag will be false and the other values may be null.
     *
     * @param sessionId the session identifier to query
     * @return a JSON map containing running/modelUsed/lastAssistant/traceHtml
     */
    @GetMapping("/state")
    public ResponseEntity<java.util.Map<String, Object>> state(@RequestParam Long sessionId,
            @RequestParam(name = "debug", defaultValue = "false") boolean debug,
            @RequestHeader(name = "X-Chat-Run-Token", required = false) String runToken,
            Authentication authentication) {
        ChatSession session = null;
        try {
            session = historyService.getSessionWithMessages(sessionId);
        } catch (Exception ignore) {
            logSuppressed("state.sessionLookup");
        }
        if (session == null || !canAccessSession(session, authentication)) {
            return neutralChatRunState(debug);
        }
        Optional<ChatRunRegistry.RunView> exactRun = Optional.empty();
        try {
            if (runRegistry != null && runToken != null && !runToken.isBlank()) {
                exactRun = runRegistry.describeExact(sessionId, runToken);
            }
        } catch (Exception ignore) {
            logSuppressed("state.exactRun");
        }
        if (runToken != null && !runToken.isBlank() && exactRun.isEmpty()) {
            return neutralChatRunState(debug);
        }
        boolean running = exactRun
                .map(view -> view.status() == ChatRunRegistry.Status.RUNNING
                        || view.status() == ChatRunRegistry.Status.COMMITTING)
                .orElseGet(() -> {
                    try {
                        return runRegistry != null && runRegistry.isRunning(sessionId);
                    } catch (Exception ignore) {
                        logSuppressed("state.running");
                        return false;
                    }
                });
        ChatRunRegistry.RunOutcomeView outcome = exactRun.map(ChatRunRegistry.RunView::outcome).orElse(null);
        boolean exactRunCanRestorePersistedAnswer = exactRun
                .map(view -> view.current() && view.outcome() != null && view.outcome().persisted())
                .orElse(false);
        boolean noExactRunRequested = runToken == null || runToken.isBlank();
        var last = (session == null || (!noExactRunRequested && !exactRunCanRestorePersistedAnswer))
                ? null
                : historyService.getLastAssistantMessage(sessionId).orElse(null);
        // Extract model and trace metadata from the session history
        String modelUsed = null;
        String traceHtml = null;
        try {
            if (session != null && session.getMessages() != null) {
                for (var m : session.getMessages()) {
                    var c = m.getContent();
                    if (c == null)
                        continue;
                    var mu = ChatModelMetaSupport.extractModelUsed(c);
                    if (mu != null)
                        modelUsed = mu;
                    var th = ChatModelMetaSupport.extractTraceHtml(c);
                    if (th != null)
                        traceHtml = th;
                }
            }
        } catch (Exception ignore) {
            logSuppressed("state.metaExtract");
        }
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        java.util.Map<String, Object> restoredSettings = java.util.Collections.emptyMap();
        try {
            if (session != null) {
                String meta = session.getSessionMeta();
                if (meta != null && !meta.isBlank()) {
                    restoredSettings = objectMapper.readValue(meta, java.util.Map.class);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to restore session_meta in /state for session {}: {}",
                    SafeRedactor.hashValue(String.valueOf(sessionId)), errorSummary(e));
        }

        out.put("running", running);
        out.put("modelUsed", modelUsed);
        out.put("lastAssistant", last);
        out.put("traceHtml", exposeTrace
                ? "traceHtml=" + SafeRedactor.diagnosticText("traceHtml", traceHtml, 12000)
                : null);
        out.put("traceDebugRequested", debug);
        out.put("runStatus", exactRun.map(view -> view.status().name().toLowerCase(java.util.Locale.ROOT))
                .orElse("missing_or_replaced"));
        out.put("attachable", exactRun.isPresent() && (outcome == null || !outcome.persisted()));
        out.put("terminal", exactRun.map(ChatRunRegistry.RunView::terminal).orElse(false));
        out.put("currentRun", exactRun.map(ChatRunRegistry.RunView::current).orElse(false));
        putRunOutcome(out, outcome);
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<java.util.Map<String, Object>> neutralChatRunState(boolean debug) {
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("running", false);
        out.put("modelUsed", null);
        out.put("lastAssistant", null);
        out.put("traceHtml", null);
        out.put("traceDebugRequested", debug);
        out.put("runStatus", "missing_or_replaced");
        out.put("attachable", false);
        out.put("terminal", false);
        out.put("currentRun", false);
        putRunOutcome(out, null);
        return ResponseEntity.ok(out);
    }

    private static void putRunOutcome(
            java.util.Map<String, Object> out,
            ChatRunRegistry.RunOutcomeView outcome) {
        out.put("runIdentityHash", outcome == null ? null : outcome.runIdentityHash());
        out.put("generationOutcome", outcome == null ? "not_observed" : outcome.generationOutcome());
        out.put("generationSucceeded", outcome != null && outcome.generationSucceeded());
        out.put("commitAttempted", outcome != null && outcome.commitAttempted());
        out.put("commitAccepted", outcome != null && outcome.commitAccepted());
        out.put("commitRejected", outcome != null && outcome.commitRejected());
        out.put("persisted", outcome != null && outcome.persisted());
        out.put("persistenceCount", outcome == null ? 0 : outcome.persistenceCount());
        out.put("finalEmitResult", outcome == null ? "not_observed" : outcome.finalEmitResult());
        out.put("finalDeliveryAccepted", outcome != null && outcome.finalDeliveryAccepted());
        out.put("finalDeliveryFailureReason",
                outcome == null ? "not_observed" : outcome.finalDeliveryFailureReason());
        out.put("duplicateSuppressed", outcome == null ? 0 : outcome.duplicateSuppressed());
        out.put("terminalReason", outcome == null ? "not_observed" : outcome.terminalReason());
        out.put("terminalEventCount", outcome == null ? 0 : outcome.terminalEventCount());
        out.put("elapsedMs", outcome == null ? 0L : outcome.elapsedMs());
    }

    private SelectionReplayRequestResolver.Resolved resolveSelectionReplayRequest(
            HttpServletRequest request) {
        if (selectionReplayRequestResolver != null) {
            return selectionReplayRequestResolver.resolve(request);
        }
        Enumeration<String> values = request == null
                ? null
                : request.getHeaders(SelectionReplayRequestResolver.HEADER);
        if (values != null && values.hasMoreElements()) {
            throw SelectionReplayRequestException.initializationFailed();
        }
        return SelectionReplayRequestResolver.Resolved.standard();
    }

    static void attachSelectionState(
            GuardContext context,
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger) {
        Objects.requireNonNull(context, "context").attachSelectionEntropy(
                Objects.requireNonNull(entropy, "entropy"),
                Objects.requireNonNull(ledger, "ledger"));
        if (entropy.mode() == SelectionEntropyMode.REPLAY
                && (context.selectionEntropy() != entropy
                || context.selectionDecisionLedger() != ledger)) {
            throw new SelectionEntropyException(SelectionEntropyReason.CONTEXT_MISSING);
        }
    }

    // === sync chat (blocking) ===
    @PostMapping("/sync")
    public ResponseEntity<ChatResponseDto> chatSync(@RequestBody @Valid ChatRequestDto dto,
            @AuthenticationPrincipal UserDetails principal,
            HttpServletRequest request) {
        if (dto == null) {
            return ResponseEntity.badRequest().body(new ChatResponseDto("bad_request", null, "bad_request", false));
        }
        publicRequestBudgetGuard.validateChat(dto);
        validateEffectiveBudgetEarly(dto);
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        String ownerKey = ownerKeyResolver.ownerKey();
        ResponseEntity<ChatResponseDto> denied = ChatSessionAccessGuard.authorize(
                historyService, dto.getSessionId(), username, ownerKey, log);
        if (denied != null) {
            return denied;
        }
        // /api/chat/sync is a live path (display-core.js). Apply the same
        // request-header plan/guard policies as the reactive and SSE entries.
        final String jamminiMode = resolveJamminiMode(
                request.getHeader("X-Jammini-Mode"), request.getHeader("X-Brave-Mode"));
        final String guardLevel = request.getHeader("X-Guard-Level");
        try (PublicChatAdmissionGuard.Lease ignored = requireChatAdmission(username, ownerKey)) {
            ChatResponseDto body = handleChat(
                    dto, username, null, ownerKey, jamminiMode, guardLevel,
                    java.util.UUID.randomUUID().toString(), "display");
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            String modelHdr = (body.getModelUsed() != null && !body.getModelUsed().isBlank())
                    ? body.getModelUsed()
                    : settingsService.getAllSettings().getOrDefault(KEY_DEFAULT_MODEL, FALLBACK_MODEL);
            ok.header("X-Model-Used", modelHdr);
            if (body.getSessionId() != null) {
                ok.header("X-Session-Id", String.valueOf(body.getSessionId()));
            }
            if (body.isRagUsed())
                ok.header("X-RAG-Used", "true");
            ok.header("X-User", username);
            ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
            return ok.body(body);
        } catch (java.util.concurrent.CancellationException cancelled) {
            String reason = "run_active".equals(cancelled.getMessage()) ? "run_active" : "request_cancelled";
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ChatResponseDto(reason, null, "cancelled", false));
        }
    }

    // ===== sync chat =====
    @PostMapping
    public Mono<ResponseEntity<ChatResponseDto>> chat(@RequestBody @Valid ChatRequestDto req,
            @AuthenticationPrincipal UserDetails principal,
            HttpServletRequest request) {
        if (req == null) {
            return Mono.just(ResponseEntity.badRequest().body(new ChatResponseDto("bad_request", null, "bad_request", false)));
        }
        SelectionReplayRequestResolver.Resolved selectionState =
                resolveSelectionReplayRequest(request);
        final SelectionEntropy selectionEntropy = selectionState.entropy();
        final SelectionDecisionLedger selectionDecisionLedger = selectionState.ledger();
        publicRequestBudgetGuard.validateChat(req);
        validateEffectiveBudgetEarly(req);
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        // Capture the client IP early to avoid IllegalStateException when running on
        // non-request threads. Prefer the X-Forwarded-For header when present.
        String clientIp;
        try {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                clientIp = xff.split(",")[0].trim();
            } else {
                clientIp = request.getRemoteAddr();
            }
        } catch (Exception e) {
            clientIp = "unknown";
            logSuppressed("chat.clientIp");
        }

        // [MoE] ???х뙴?꾨Ь?嶺뚯쉳????熬곣뫖??ownerKey / ?筌뤾쑬?????녹맠 ??ル∥??
        final String preResolvedOwnerKey = ownerKeyResolver.ownerKey();
        final String sessionIdHeader = request.getHeader("X-Session-Id");
        final String conversationIdHeader = request.getHeader("X-Conversation-Id");
        final String requestIdHeader = request.getHeader("X-Request-Id");
        applyRequestSessionId(req, sessionIdHeader, conversationIdHeader);

        // bug_xa: header sessionId?띠럾? ???덈츎??DTO?띠럾? null??????貫?꾥????逾?熬곣뫁逾?筌뤾쑴逾???袁⑥춸 ?????깅さ亦껋깢????낅슣???
        ResponseEntity<ChatResponseDto> denied = ChatSessionAccessGuard.authorize(
                historyService, req.getSessionId(), username, preResolvedOwnerKey, log);
        if (denied != null) {
            return Mono.just(denied);
        }
        final String jamminiMode = resolveJamminiMode(
                request.getHeader("X-Jammini-Mode"), request.getHeader("X-Brave-Mode"));
        final String guardLevel = request.getHeader("X-Guard-Level");

        // bug_xa: header???筌뤾쑬??????덈츎??DTO sessionId?띠럾? null?????
        // ??????덈콦???깅턄??嶺뚮∥???꾨뎨????곕츩??ル벣遊??怨뺣깹????븐슙???貫?꾥뚭였寃?????袁⑥춸 ?????덈펲.
        // MERGE_HOOK:PROJ_AGENT::controller_session_attachment_inject
        // 嶺뚳퐘維? 嶺뚯쉶?꾣룇?筌뤾퍓??attachmentIds ???㈑??筌뤾쑬???嶺뚳퐘維??띠럾? ???깅さ嶺????吏??낅슣???+ Fail-soft 嶺뚳퐣瑗??
        if ((req.getAttachmentIds() == null || req.getAttachmentIds().isEmpty())
                && ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(req.getMessage())) {

            try {
                String sid = req.getSessionId() == null ? null : String.valueOf(req.getSessionId());
                if (sid != null && !sid.isBlank()) {
                    java.util.List<String> ids = attachmentService.findIdsBySession(
                            sid,
                            publicRequestBudgetGuard.attachmentLookaheadLimit(),
                            AttachmentOwnerIdentity.forActor(username, preResolvedOwnerKey));
                    if (ids != null && !ids.isEmpty()) {
                        req.setAttachmentIds(ids);
                        log.info("[ChatApi] Auto-injected {} attachments from sessionHash={}", ids.size(), SafeRedactor.hashValue(sid));
                    }
                }
            } catch (Exception ignore) {
                logSuppressed("chat.attachments.autoInject");
                // ?筌뤾쑬???브퀗??????덉넮 ??戮?뱺??嶺뚳퐘維? ???⑸츎 ?롪퍒????뿉??띠룄?당쳥???겶? Fail-soft??嶺뚯쉳?듸쭛?
            }

            // ?????嶺뚳퐘維??띠럾? ??怨몃さ嶺??롪퍔???彛???節뗢뵛????怨쀫틮 嶺뚯쉶?꾣룇??怨쀬Ŧ 嶺뚳퐣瑗??
            if (req.getAttachmentIds() == null || req.getAttachmentIds().isEmpty()) {
                String __msg = String.valueOf(req.getMessage());
                log.warn("[ChatApi] Attachment question but no attachments found. messageHash={} messageLength={}",
                        SafeRedactor.hash12(__msg), __msg.length());
                // BAD_REQUEST?????嶺뚯솘? ??袁ぢ??熬곣뫁????怨쀫틮 嶺?嶺뚳퐣瑗?怨レ뿉???ｌ뫒??嶺뚯쉳?듸쭛?
            }
        }

        validateEffectiveBudgetEarly(req);

        final PublicChatAdmissionGuard.Lease admissionLease =
                requireChatAdmission(username, preResolvedOwnerKey);

        if (req.isUseAdaptive()) {
            return Mono.defer(() -> adaptiveService.translate(req.getMessage(), "ko", "en"))
                    .map(t -> new ChatResponseDto(t, null, "Adaptive-Translator", false))
                    .map(body -> ResponseEntity.ok()
                            .header("X-Model-Used", "Adaptive-Translator")
                            .header("X-User", username)
                            .header("Access-Control-Expose-Headers", EXPOSE_HEADERS)
                            .body(body))
                    .doFinally(ignored -> admissionLease.close());
        }

        final String _username = username;
        final String _clientIp = clientIp;
        final String _ownerKey = preResolvedOwnerKey;
        final String _jamminiMode = jamminiMode;
        final String _guardLevel = guardLevel;
        final String _applicationRequestId = firstNonBlank(
                requestIdHeader, MDC.get("x-request-id"), java.util.UUID.randomUUID().toString());
        Mono<ResponseEntity<ChatResponseDto>> mono = Mono.fromCallable(
                com.example.lms.infra.exec.ContextPropagation.wrapCallable(() -> {
            ChatResponseDto body = handleChat(
                    req,
                    _username,
                    _clientIp,
                    _ownerKey,
                    _jamminiMode,
                    _guardLevel,
                    _applicationRequestId,
                    new SelectionReplayRequestResolver.Resolved(
                            selectionEntropy, selectionDecisionLedger),
                    "chat");

            // ???? ???揶??筌뤾쑬??嶺뚮씞?뗩뇡?????
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            // ?????깆젷 ????嶺뚮ㅄ維??숈춻??놁떳 ?リ옇?▽빳?(??臾먯뱺嶺??ル??, ???껇????????깆젧?띠룆????뿉????揶?
            String modelHdr = (body.getModelUsed() != null && !body.getModelUsed().isBlank())
                    ? body.getModelUsed()
                    : settingsService.getAllSettings().getOrDefault(KEY_DEFAULT_MODEL, FALLBACK_MODEL);
            ok.header("X-Model-Used", modelHdr);
        if (body.getSessionId() != null) {
            ok.header("X-Session-Id", String.valueOf(body.getSessionId()));
        }
            if (body.isRagUsed())
                ok.header("X-RAG-Used", "true");
            ok.header("X-User", username);
            ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
            return ok.body(body);
        }));
        // Offload the blocking call to a bounded elastic scheduler and attach a common
        // error handler.
        mono = mono.subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    if (ex instanceof com.example.lms.llm.ModelSelectionException selection) {
                        return Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                                .body(new ChatResponseDto(selection.code(), null, "error-model", false)));
                    }
                    if (ex instanceof java.util.concurrent.CancellationException cancelled) {
                        String reason = "run_active".equals(cancelled.getMessage()) ? "run_active" : "request_cancelled";
                        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(new ChatResponseDto(reason, null, "cancelled", false)));
                    }
                    if (ex instanceof SelectionReplayRequestException selectionFailure) {
                        return Mono.error(selectionFailure);
                    }
                    if (selectionEntropy.mode() == SelectionEntropyMode.REPLAY
                            && ex instanceof SelectionEntropyException selectionFailure) {
                        return Mono.error(SelectionReplayRequestException.from(
                                selectionFailure.reason()));
                    }
                    if (ex instanceof PublicRequestBudgetGuard.Rejection rejection) {
                        return Mono.error(rejection);
                    }
                    if (ex instanceof ChatHistoryService.SessionQuotaExceededException quotaExceeded) {
                        return Mono.error(quotaExceeded);
                    }
                    log.error("[AWX][chat] chat-failed type={} error={}", ex == null ? "unknown" : ex.getClass().getSimpleName(), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
                    String rawError = ex == null ? null : ex.getMessage();
                    String formatted = String.format("Error: errorHash=%s errorLength=%d", SafeRedactor.hashValue(rawError), rawError == null ? 0 : rawError.length());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ChatResponseDto(formatted, null, "error-model", false)));
                });
        // Propagate the client IP in the Reactor context. Downstream components can
        // retrieve this value via Mono.deferContextual if needed.
        return mono
                .doFinally(ignored -> admissionLease.close())
                .contextWrite(Context.of("clientIp", clientIp));
    }

    // ===== streaming chat (SSE) =====
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(@RequestBody @Valid ChatRequestDto req,
            @RequestParam(name = "attach", required = false, defaultValue = "false") boolean attach,
            @RequestParam(name = "debug", required = false, defaultValue = "false") boolean debug,
            @AuthenticationPrincipal UserDetails principal,
            HttpServletRequest request) {
        if (req == null) {
            return Flux.just(sse(ChatStreamEvent.error("bad_request")));
        }
        SelectionReplayRequestResolver.Resolved selectionState =
                resolveSelectionReplayRequest(request);
        final SelectionEntropy selectionEntropy = selectionState.entropy();
        final SelectionDecisionLedger selectionDecisionLedger = selectionState.ledger();
        publicRequestBudgetGuard.validateChatForStream(req, attach);
        if (!attach) {
            validateEffectiveBudgetEarly(req);
        }
        final String jamminiMode = resolveJamminiMode(
                request.getHeader("X-Jammini-Mode"), request.getHeader("X-Brave-Mode"));
        final String guardLevel = request.getHeader("X-Guard-Level");
        if (!attach) {
            validateProjectedBudgetBeforeStream(req, jamminiMode, guardLevel);
        }
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        // Capture the client IP early to avoid IllegalStateException when running on
        // non-request threads. Prefer the X-Forwarded-For header when present.
        String clientIp;
        try {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                clientIp = xff.split(",")[0].trim();
            } else {
                clientIp = request.getRemoteAddr();
            }
        } catch (Exception e) {
            clientIp = "unknown";
            logSuppressed("stream.clientIp");
        }

        // [MoE] ???х뙴?꾨Ь?嶺뚯쉳????熬곣뫖??ownerKey / ?筌뤾쑬?????녹맠 ??ル∥??
        final String preResolvedOwnerKey = ownerKeyResolver.ownerKey();
        final String sessionIdHeader = request.getHeader("X-Session-Id");
        final String conversationIdHeader = request.getHeader("X-Conversation-Id");
        final String requestIdHeader = request.getHeader("X-Request-Id");
        final String runTokenHeader = request.getHeader("X-Chat-Run-Token");
        final String runAckRequiredHeader = request.getHeader("X-Chat-Run-Ack-Required");
        final java.util.function.Consumer<Object> requestCompletion = ChatGenerationAdmissionFilter.completion(request);
        final boolean clientAckRequired = "1".equals(runAckRequiredHeader)
                || Boolean.parseBoolean(runAckRequiredHeader);
        applyRequestSessionId(req, sessionIdHeader, conversationIdHeader);

        // Keep malformed exact-attach requests neutral before authorization so a
        // missing token cannot distinguish a foreign session from a missing one.
        if (attach && (req.getSessionId() == null
                || runTokenHeader == null
                || runTokenHeader.isBlank())) {
            return Flux.just(sse(ChatStreamEvent.error("run_not_found_or_replaced")));
        }

        ResponseEntity<ChatResponseDto> denied = ChatSessionAccessGuard.authorize(
                historyService, req.getSessionId(), username, preResolvedOwnerKey, log);
        if (denied != null) {
            return Flux.just(sse(ChatStreamEvent.error(
                    attach ? "run_not_found_or_replaced" : "session_forbidden")));
        }
        // bug_xa: header sessionId?띠럾? ???덈츎??DTO sessionId?띠럾? null????? attach/嶺뚮∥???꾨뎨????곕츩??ル벣遊??怨뺣깹????袁⑥춸 ??        // ???깅쾳
        // Attach is an exact, read-only replay operation. It never starts generation.
        if (attach) {
            if (runRegistry == null) {
                return Flux.just(sse(ChatStreamEvent.error("run_registry_unavailable")));
            }
            return runRegistry.attachInteractiveExact(req.getSessionId(), runTokenHeader)
                    .orElseGet(() -> Flux.just(
                            sse(ChatStreamEvent.error("run_not_found_or_replaced"))));
        }
        final PublicChatAdmissionGuard.Lease admissionLease =
                requireChatAdmission(username, preResolvedOwnerKey);
        final AtomicBoolean admissionTransferredToWorker = new AtomicBoolean(false);
        try {
        bindAttachmentOwnerIfPresent(req, username, preResolvedOwnerKey);
        final AtomicReference<ChatRunRegistry.BeginResult> initialRun = new AtomicReference<>();
        if (req.getSessionId() != null && runRegistry != null) {
            ChatRunRegistry.BeginResult started = runRegistry.beginOrJoin(req.getSessionId());
            if (!started.owner()) {
                return Flux.just(sse(ChatStreamEvent.error("run_active")));
            }
            initialRun.set(started);
        }
        // Use a bounded replay sink so that early emissions are not lost when the
        // HTTP layer subscribes a few milliseconds later ("zero-subscriber" race),
        // and to avoid silent token/event drops under bursty emission.
        //
        // This sink fan-outs to both:
        //  - the client SSE subscriber (returned Flux)
        //  - an internal bridge subscriber (to feed ChatRunRegistry for resume/attach)
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink = Sinks.many()
                .replay()
                .limit(4096);

        // Holder for the computed session key so that it can be referenced in finally
        final String[] currentSessionKeyHolder = new String[1];
        // Track current session id to allow cancellation propagation
        final AtomicReference<Long> currentSessionId = new AtomicReference<>(req.getSessionId());

        // Reference to the per-session replay sink. Once the session is
        // initialised the sink is obtained from the ChatRunRegistry. This
        // reference allows completion handlers (e.g. onComplete, onCancel)
        // to emit final events or mark the run as done/cancelled.
        final AtomicReference<ChatRunExecutionContext> runContextRef = new AtomicReference<>(
                initialRun.get() == null ? null : initialRun.get().context());
        final AtomicReference<Disposable> runWorkerRef = new AtomicReference<>();
        final AtomicBoolean replayBridgeInstalled = new AtomicBoolean(false);
        final AtomicBoolean clientDetached = new AtomicBoolean(false);
        // 세션 디버그 증거: 워커 finally/외부 catch 어느 경로든 한 번만 기록.
        final AtomicBoolean sessionTraceOnce = new AtomicBoolean(false);
        final AtomicReference<String> streamOutcomeRef = new AtomicReference<>();
        final AtomicReference<String> streamModelRef = new AtomicReference<>();
        final AtomicReference<java.util.Map<String, Object>> streamTraceMetaRef = new AtomicReference<>();
        final ChatRunRegistry.InteractiveClient interactiveClient = runRegistry.interactiveClient();
        if (runContextRef.get() != null) interactiveClient.bind(runContextRef.get());
        // Capture local variables for use within lambda; lambda parameters must be
        // final or effectively final
        final String _username = username;
        final String _clientIp = clientIp;
        final String _jamminiMode = jamminiMode;
        final String _guardLevel = guardLevel;

        // Capture correlation identifiers from the request thread.
        final String __capturedSid = firstNonBlank(MDC.get("sid"), MDC.get("sessionId"), sessionIdHeader, conversationIdHeader);
        String __tmpTrace = firstNonBlank(MDC.get("traceId"), MDC.get("trace"), requestIdHeader);
        if (__tmpTrace == null || __tmpTrace.isBlank()) {
            __tmpTrace = java.util.UUID.randomUUID().toString();
        }
        final String __capturedTrace = __tmpTrace;
        final String __capturedRequestId = firstNonBlank(MDC.get("x-request-id"), requestIdHeader, __capturedTrace);
        final boolean __capturedDbgSearch = SearchTraceConsoleLogger.isRequestEnabled();
        final String __capturedDbgSrc = MDC.get("dbgSearchSrc");
        final String __capturedDbgEngines = MDC.get("dbgSearchBoostEngines");

        final String __httpMethod = request.getMethod();
        final String __httpPath = request.getRequestURI();
        final String __httpQuery = request.getQueryString();
        final String __httpUa = request.getHeader("User-Agent");
        final com.abandonware.ai.addons.budget.TimeBudget __capturedBudget =
                com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        final Object __capturedBodyBytesValue = TraceStore.get("public.request.budget.bodyBytes");
        final Long __capturedBodyBytes = __capturedBodyBytesValue instanceof Number number
                ? Math.max(0L, number.longValue())
                : null;
        final long __streamStartedNs = System.nanoTime();
        try {
            Long remainingMs = __capturedBudget == null ? null : __capturedBudget.remainingMillis();
            ChatStreamEvent.StatusSignal startedSignal = ChatStreamEvent.StatusSignal.of(
                    "stream", "started", "stream started", remainingMs, 0L, false);
            sink.tryEmitNext(sse(ChatStreamEvent.status(startedSignal)));
            sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                    ChatStreamSignalBuilder.buildTransformerBlocks(
                            java.util.Map.of(), startedSignal, null, null, null, null))));
        } catch (Throwable ignore) {
            logSuppressed("stream.status.started");
        }
        Disposable d = Mono.fromRunnable(() -> {
            ChatRunExecutionContext.Scope runScope = null;
            try {
                if (__capturedBudget != null) {
                    com.abandonware.ai.addons.budget.TimeBudgetContext.set(__capturedBudget);
                }
            // ??SSE ???덈콦?源녿닔? boundedElastic????????덈뺄?????ThreadLocal 嶺뚮ㅏ援????낅슣????熬곣뫗??
            try (TraceContext __tc = attachStreamTraceContext(__capturedSid, __capturedTrace)) {
                try {
                    if (__capturedRequestId != null && !__capturedRequestId.isBlank()) {
                        MDC.put("x-request-id", __capturedRequestId);
                    }
                    if (__capturedDbgSearch) {
                        MDC.put("dbgSearch", "1");
                        if (__capturedDbgSrc != null && !__capturedDbgSrc.isBlank()) {
                            MDC.put("dbgSearchSrc", __capturedDbgSrc);
                        }
                        if (__capturedDbgEngines != null && !__capturedDbgEngines.isBlank()) {
                            MDC.put("dbgSearchBoostEngines", __capturedDbgEngines);
                        }
                    } else {
                        MDC.remove("dbgSearch");
                        MDC.remove("dbgSearchSrc");
                        MDC.remove("dbgSearchBoostEngines");
                    }
                } catch (Throwable ignore) {
                    logSuppressed("stream.mdc.rehydrate");
                }

                traceClear("stream.trace.clear");

                if (__capturedBodyBytes != null) {
                    tracePutIfAbsent("public.request.budget.bodyBytes", __capturedBodyBytes);
                }
                // Rehydrate a minimal envelope so background breadcrumbs can be correlated.
                tracePutIfAbsent("trace.id", SafeRedactor.hashValue(__capturedTrace));
                if (__capturedSid != null && !__capturedSid.isBlank()) tracePutIfAbsent("sid", SafeRedactor.hashValue(__capturedSid));
                if (__httpMethod != null && !__httpMethod.isBlank()) tracePutIfAbsent("http.method", __httpMethod);
                if (__httpPath != null && !__httpPath.isBlank()) tracePutIfAbsent("http.path", SafeRedactor.diagnosticValue("http.path", __httpPath));
                if (__httpQuery != null && !__httpQuery.isBlank()) tracePutIfAbsent("http.query", SafeRedactor.diagnosticValue("http.query", __httpQuery));
                if (__httpUa != null && !__httpUa.isBlank()) tracePutIfAbsent("http.ua", SafeRedactor.diagnosticValue("http.ua", __httpUa));
                com.example.lms.debug.DebugEventTracePromotionService.seedRequestedExternalEvidenceLanes(req.getMessage());
            GuardContext gctx = GuardContext.defaultContext();
            attachSelectionState(gctx, selectionEntropy, selectionDecisionLedger);
            if (_jamminiMode != null && !_jamminiMode.isBlank()) {
                gctx.setHeaderMode(_jamminiMode);
                gctx.setMode(_jamminiMode);
                gctx.setPlanId(_jamminiMode);
                if ("S1".equalsIgnoreCase(_jamminiMode) || "safe".equalsIgnoreCase(_jamminiMode)) {
                    gctx.setMemoryProfile("MEMORY");
                } else if ("S2".equalsIgnoreCase(_jamminiMode)
                        || "brave".equalsIgnoreCase(_jamminiMode)
                        || "free".equalsIgnoreCase(_jamminiMode)
                        || "zero_break".equalsIgnoreCase(_jamminiMode)) {
                    gctx.setMemoryProfile("NONE");
                }
            }
            if (_guardLevel != null && !_guardLevel.isBlank()) {
                gctx.setGuardLevel(_guardLevel);
            }
            if (req != null && req.getMessage() != null) {
                gctx.setEntityQueryFromQuestion(req.getMessage());
				// UAW: propagate raw user query for downstream orchestration/unmasking/autolearn hooks
				gctx.setUserQuery(req.getMessage());
            }
            GuardContextHolder.set(gctx);
            String requestTimelineId = null;
            try {
                // 1) ???깆젧 ?곌랜理묌뜮?
                ChatRequestDto dto = mergeWithSettings(req);
                publicRequestBudgetGuard.validateChatEffective(dto);
                final boolean __hasAttachments = hasAttachments(dto);
                final boolean __looksLikeAttachmentQ =
                        ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(dto.getMessage());

                // [PATCH] Streaming path should also apply sensitive-topic overrides (fail-soft)
                try {
                    if (sensitiveTopicDetector != null) {
                        sensitiveTopicDetector.applyTo(gctx, dto);
                    }
                } catch (Exception e) {
                    log.debug("[SensitiveTopicDetector] applyTo failed in chatStream: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                }

                // DROP: apply plan selection + guard hints BEFORE web prefetch/search.
                PlanHints __planHints = null;
                boolean __allowWebCap = true;
                boolean __allowRagCap = true;
                try {
                    AnswerMode __am = AnswerMode.fromString(dto.getMode());
                    QueryDomain __qd = (gctx != null && gctx.isSensitiveTopic()) ? QueryDomain.SENSITIVE : QueryDomain.GENERAL;
                    if (workflowOrchestrator != null) {
                        workflowOrchestrator.ensurePlanSelected(gctx, __am, __qd, dto.getMessage(), __hasAttachments);
                    }
                    if (planHintApplier != null && gctx != null && gctx.getPlanId() != null) {
                        __planHints = planHintApplier.load(gctx.getPlanId());
                        planHintApplier.applyToGuardContext(__planHints, gctx);
                    }
                    __allowWebCap = (__planHints == null || __planHints.allowWeb() != Boolean.FALSE);
                    __allowRagCap = (__planHints == null || __planHints.allowRag() != Boolean.FALSE);
                    TraceStore.put("plan.id.preSearch", (gctx == null ? null : gctx.getPlanId()));
                    TraceStore.put("plan.allowWeb.cap", __allowWebCap);
                    TraceStore.put("plan.allowRag.cap", __allowRagCap);
                } catch (Exception ignorePlan) {
                    logSuppressed("plan.preSearch.stream");
                }

                // === 嶺뚳퐘維? ???쳜????덈콦 ?낅슣??????獄??????吏?OFF ===
                // Compose the message for the call by prepending extracted attachment texts.
                // When a
                // user uploads files and explicitly asks about them (determined via the
                // heuristic),
                // the web search is disabled to avoid leaking the query to external providers.
                // attachments.inline.legacy-prepend-enabled is deprecated/no-op. Attachment evidence
                // flows through ChatWorkflow -> PromptContext.localDocs -> PromptBuilder.build(ctx).
                // Determine the final value of useWebSearch after considering attachments.
                // Explicit true
                // values are honoured when no attachment context question is detected. Null is
                // treated as false.
                boolean __reqUseWeb = Boolean.TRUE.equals(dto.isUseWebSearch());
                final boolean __directDebugAnswer =
                        AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(dto.getMessage());
                final boolean __directLiteralAnswer =
                        ChatWorkflow.isDirectLiteralAnswerRequest(dto.getMessage());
                final boolean __directUiModeStatusAnswer =
                        ChatWorkflow.isCurrentModeStatusRequest(dto.getMessage());
                final boolean __directSupabaseOperationalJudgment =
                        ChatWorkflow.isSupabaseOperationalJudgmentRequest(dto.getMessage());
                boolean __finalUseWeb = (__hasAttachments && __looksLikeAttachmentQ) ? false : __reqUseWeb;
                __finalUseWeb = __finalUseWeb && !__directDebugAnswer;
                __finalUseWeb = __finalUseWeb && !__directLiteralAnswer;
                __finalUseWeb = __finalUseWeb && !__directUiModeStatusAnswer;
                __finalUseWeb = __finalUseWeb && !__directSupabaseOperationalJudgment;
                // Plan cap: allowWeb/allowRag (applied before prefetch/search)
                __finalUseWeb = __finalUseWeb && __allowWebCap;
                final boolean __finalUseRag = __allowRagCap
                        && Boolean.TRUE.equals(dto.isUseRag())
                        && !__directDebugAnswer
                        && !__directLiteralAnswer
                        && !__directUiModeStatusAnswer
                        && !__directSupabaseOperationalJudgment;
                final boolean __workflowUseWeb = __directUiModeStatusAnswer ? __reqUseWeb : __finalUseWeb;
                final boolean __workflowUseRag = __directUiModeStatusAnswer ? Boolean.TRUE.equals(dto.isUseRag()) : __finalUseRag;

                publicRequestBudgetGuard.validateChatProjected(
                        dto,
                        __planHints,
                        gctx == null ? null : gctx.getPlanId(),
                        __workflowUseWeb,
                        __workflowUseRag);

                // 2) ?筌뤾쑬??upsert
                boolean sessionCreated = req.getSessionId() == null;
                ChatSession session = (req.getSessionId() == null)
                        ? historyService
                                .startNewSession(dto.getMessage(), _username, _clientIp, preResolvedOwnerKey,
                                        dto.getMemoryProfile())
                                .orElseThrow(() -> new IllegalStateException("?筌뤾쑬????諛댁뎽 ???덉넮"))
                        : historyService.getSessionWithMessages(req.getSessionId());
                if (session == null && req.getSessionId() != null) {
                    traceSessionNotFound(req.getSessionId());
                    session = historyService
                            .startNewSession(dto.getMessage(), _username, _clientIp, preResolvedOwnerKey,
                                    dto.getMemoryProfile())
                            .orElseThrow(() -> new IllegalStateException("session recovery failed"));
                    sessionCreated = true;
                }
                java.util.Map<String, Object> sessionMeta = mergeSessionMetaIntoRequest(session, req);
                try {
                    session.setSessionMeta(objectMapper.writeValueAsString(sessionMeta));
                    historyService.updateSessionMeta(session.getId(), sessionMeta);
                } catch (Exception e) {
                    log.warn("Failed to persist stream session_meta for session {}: {}",
                            SafeRedactor.hashValue(String.valueOf(session.getId())), errorSummary(e));
                }
                // ???? ???揶??筌뤾쑬??嶺뚮씞?뗩뇡?????
                // If a new session was created and attachments are present, map the
                // attachments to this session. Without this association the
                // AttachmentContextHandler (which relies on findBySession) will not
                // return uploaded documents.
                try {
                    if (sessionCreated
                            && __hasAttachments
                            && session != null && session.getId() != null) {
                        attachmentService.attachToSession(
                                String.valueOf(session.getId()),
                                req.getAttachmentIds(),
                                AttachmentOwnerIdentity.forActor(_username, preResolvedOwnerKey));
                    }
                } catch (Exception ex) {
                    log.debug("Failed to attach uploaded files to new session (SSE): {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
                }
                // Propagate real session id so that it can be cancelled later
                if (session != null && session.getId() != null) {
                    currentSessionId.set(session.getId());
                }
                // Initialise the replay sink and bridge events
                if (session != null && session.getId() != null) {
                    ChatRunRegistry.BeginResult started = initialRun.get();
                    if (started != null && !started.context().belongsToSession(session.getId())) {
                        // Session recovery created a different canonical ID. Retire the
                        // unexposed old-ID claim and bind execution only to the real session.
                        runRegistry.markDone(started.context());
                        runContextRef.compareAndSet(started.context(), null);
                        started = null;
                    }
                    if (started == null) {
                        started = runRegistry.beginOrJoin(session.getId());
                    }
                    if (!started.owner()) {
                        sink.tryEmitNext(sse(ChatStreamEvent.error("run_active")));
                        return;
                    }
                    ChatRunExecutionContext runContext = started.context();
                    runContextRef.set(runContext);
                    Disposable workerHandle = runWorkerRef.get();
                    if (workerHandle != null) {
                        runContext.registerCancellationHandle(workerHandle);
                    }
                    runScope = ChatRunExecutionContext.bind(runContext);
                    if (clientAckRequired && clientDetached.get()) {
                        emitPreAcknowledgementCancellation(
                                sink,
                                runContext,
                                selectionEntropy,
                                selectionDecisionLedger,
                                __capturedBudget,
                                __streamStartedNs);
                        return;
                    }
                    interactiveClient.bind(runContext);
                    if (runContext.isCancellationRequested()) {
                        emitPreAcknowledgementCancellation(
                                sink,
                                runContext,
                                selectionEntropy,
                                selectionDecisionLedger,
                                __capturedBudget,
                                __streamStartedNs);
                        return;
                    }
                    // Bridge all producer events to this exact run's replay buffer.
                    sink.asFlux().subscribe(event -> {
                        try {
                            runRegistry.emit(runContext, event);
                        } catch (Throwable ignore) {
                            logSuppressed("stream.runSink.forward");
                        }
                    }, err -> {
                        // Propagate an error event into the replay sink. Any downstream
                        // subscribers will receive this before the run is marked done.
                        try {
                            // Use String.format to build the error message instead of concatenation
                            String errMsg = String.format("Chat stream failed errorHash=%s errorLength=%d", SafeRedactor.hashValue(err == null ? null : err.getMessage()), err == null || err.getMessage() == null ? 0 : err.getMessage().length());
                            runRegistry.emit(runContext, sse(ChatStreamEvent.error(errMsg)));
                        } catch (Throwable ignore) {
                            logSuppressed("stream.runSink.error");
                        }
                    }, () -> {
                        // When the unicast sink completes, mark this run as done. This
                        // allows subsequent attach attempts to replay the completed
                        // conversation without spawning a new generation.
                        runRegistry.markDone(runContext);
                    });
                    replayBridgeInstalled.set(true);
                }

                // 2-a) ?筌뤾쑬??????ｌ뫒亦???SSE sink ?繹먮굞夷?
                String sessionKey;
                if (session != null && session.getId() != null) {
                    String s = String.valueOf(session.getId());
                    // Use String.format instead of string concatenation to build the session key
                    sessionKey = s.startsWith("chat-") ? s : (s.matches("\\d+") ? String.format("chat-%s", s) : s);
                } else {
                    sessionKey = java.util.UUID.randomUUID().toString();
                }
                // store for later cleanup
                currentSessionKeyHolder[0] = sessionKey;
                ChatRunExecutionContext emitterRunContext = runContextRef.get();
                try {
                    if (emitterRunContext != null && chatStreamEmitter != null) {
                        if (!chatStreamEmitter.registerSink(emitterRunContext, sink)) {
                            throw new IllegalStateException("exact run producer registration rejected");
                        }
                    } else if (emitterRunContext == null && chatStreamEmitter != null) {
                        chatStreamEmitter.registerSink(sessionKey, sink);
                    }
                } catch (Throwable t) {
                    log.debug("Failed to register SSE sink: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length()));
                    if (emitterRunContext != null) {
                        throw new IllegalStateException("exact run producer registration failed", t);
                    }
                }


                // [PATCH] Update breadcrumbs to the resolved session id and emit it early.
                try {
                    if (sessionKey != null && !sessionKey.isBlank()) {
                        try {
                            org.slf4j.MDC.put("sid", sessionKey);
                            org.slf4j.MDC.put("sessionId", sessionKey);
                        } catch (Throwable ignoreMdc) {
                            logSuppressed("stream.sessionBreadcrumb.mdc");
                        }
                        try {
                            com.example.lms.search.TraceStore.put("sid", SafeRedactor.hashValue(sessionKey));
                        } catch (Throwable ignoreTrace) {
                            logSuppressed("stream.sessionBreadcrumb.trace");
                        }
                    }
                } catch (Exception ignore) {
                    logSuppressed("stream.sessionBreadcrumb.outer");
                }

                // Emit session id early so SSE clients can persist it even if the stream is interrupted.
                try {
                    if (session != null && session.getId() != null) {
                        ChatRunExecutionContext runContext = runContextRef.get();
                        sink.tryEmitNext(sse(ChatStreamEvent.sessionReady(
                                session.getId(),
                                runContext == null ? null : runContext.clientToken())));
                    }
                } catch (Exception ignore) {
                    logSuppressed("stream.sessionReady");
                }

                if (clientAckRequired) {
                    ChatRunExecutionContext acknowledgedRun = runContextRef.get();
                    boolean acknowledged = acknowledgedRun != null
                            && !clientDetached.get()
                            && acknowledgedRun.awaitClientAcknowledgement(CHAT_RUN_CLIENT_ACK_TIMEOUT_MILLIS);
                    if (!acknowledged) {
                        boolean cancelledUnacknowledged = emitPreAcknowledgementCancellation(
                                sink,
                                acknowledgedRun,
                                selectionEntropy,
                                selectionDecisionLedger,
                                __capturedBudget,
                                __streamStartedNs);
                        // ACK can win after the bounded wait but before cancellation
                        // acquires the run gate. Continue that same run instead of
                        // finishing an empty DONE replay.
                        boolean lateAcknowledgementWon = !cancelledUnacknowledged
                                && acknowledgedRun != null
                                && acknowledgedRun.awaitClientAcknowledgement(0L);
                        if (!lateAcknowledgementWon) {
                            return;
                        }
                    }
                }

                writeSelectionEntropyProjection(
                        selectionEntropy, selectionDecisionLedger, false);
                emitSelectionEntropy(sink);

                if (!sessionCreated) {
                    historyService.appendMessage(session.getId(), "user", dto.getMessage());
                }

                //
                // Run lightweight chain (location intercept / attachment context / image
                // grounding)
                try {
                    String userId = (principal != null ? principal.getUsername() : "anonymous");
                    // Execute the lightweight pre-processing chain. Use the injected
                    // ChatStreamEmitter rather than an undefined variable. Any
                    // exceptions are swallowed to avoid blocking the primary chat flow.
                    chainRunner.run(
                            sessionKey,
                            userId,
                            req.getMessage(),
                            chatStreamEmitter,
                            runContextRef.get(),
                            AttachmentOwnerIdentity.forActor(userId, preResolvedOwnerKey));
                } catch (Exception ignore) {
                    logSuppressed("stream.chainRunner");
                }
                // Emit an initial thought event so the client knows the agent has started
                // processing.
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("嶺뚳퐣瑗?怨?ご???戮곗굚??紐껊퉵??* ... *&#47;")));
                }

                // 3) ??⑤객臾?
                // Broadcast both status and thought updates so that the UI can display the
                // same message in the status line and the thought process panel. Each
                // call to status() is immediately followed by a corresponding call to
                // thought() with the same message to satisfy the requirement of
                // streaming thought events for every step of the agent???work.
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.status("?臾믩닑???釉뚯뫒??繞?* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("?臾믩닑???釉뚯뫒??繞?* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.status("????瑜곷턄??곗뒧????롪틵???繞벿뮻??* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("????瑜곷턄??곗뒧????롪틵???繞벿뮻??* ... *&#47;")));
                }

                // 4) ???롪틵????怨뺣뾼??
                // ???롪틵???? 嶺뚣끉裕뉏펺?useWebSearch ????뗥윜?__finalUseWeb)?띠럾? true???굿??롪틵???嶺뚮ㅄ維獄?쑚泥? OFF?띠럾? ?熬곣뫀鍮????異???臾먮뺄??類ｋ펲.
                // 嶺뚳퐘維? 嶺뚯쉶?꾣룇???롪퍔???__finalUseWeb??false???????롪틵?????節띉????紐꾩끋??類ｋ펲.
                int topKParam = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
                com.example.lms.gptsearch.dto.SearchMode sm = dto.getSearchMode();
                // treat null as AUTO for compatibility
                if (sm == null)
                    sm = com.example.lms.gptsearch.dto.SearchMode.AUTO;
                final com.example.lms.gptsearch.dto.SearchMode effectiveSearchMode =
                        effectiveSearchMode(dto.getMessage(), sm);
                recordSearchModeRewriteHint(effectiveSearchMode);
                final boolean allowWeb = shouldUseWebForSearchMode(
                        dto.getMessage(), effectiveSearchMode, __finalUseWeb, __finalUseRag, searchDecisionService, topKParam);
                markCheapSearchMode(gctx, effectiveSearchMode, "stream.preSearch");
                final String __providerSearchQuery = providerSearchQuery(dto.getMessage());
                NaverSearchService.SearchResult sr;
                NaverSearchService.SearchTrace rawTrace = null;
                List<String> rawSnips = java.util.Collections.emptyList();
                String traceHtml = null;
                if (allowWeb) {
                    try {
                        Long remainingMs = __capturedBudget == null ? null : __capturedBudget.remainingMillis();
                        long tookMs = Math.max(0L, (System.nanoTime() - __streamStartedNs) / 1_000_000L);
                        ChatStreamEvent.StatusSignal webSearchSignal = ChatStreamEvent.StatusSignal.of(
                                "web", "web_search_running", "web search running", remainingMs, tookMs, false);
                        sink.tryEmitNext(sse(ChatStreamEvent.status(webSearchSignal)));
                    } catch (Throwable ignore) {
                        logSuppressed("stream.status.webSearchRunning");
                    }
                    // Signal that we are planning and executing a search
                    if (debug) {
                        sink.tryEmitNext(sse(ChatStreamEvent.status("?롪틵?????ｌ뫓????濡〓뎡/* ... *&#47;")));
                    }
                    // Execute live search with trace enabled (Hybrid provider handles fallback
                    // internally)
                    sr = webSearchProvider.searchWithTrace(__providerSearchQuery, topKParam);

                    rawTrace = sr.trace();
                    rawSnips = prioritizeDomainEvidenceSnippets(
                            dto.getMessage(),
                            (sr.snippets() == null) ? java.util.Collections.emptyList() : sr.snippets());
                    rawSnips = completeNamedOfficialCoverageSnippets(
                            dto.getMessage(),
                            rawSnips,
                            rescueQuery -> {
                                NaverSearchService.SearchResult rescueSr =
                                        webSearchProvider.searchWithTrace(rescueQuery, topKParam);
                                return rescueSr == null || rescueSr.snippets() == null
                                        ? java.util.Collections.emptyList()
                                        : rescueSr.snippets();
                            });
                    String __domainFallbackQuery = domainEvidenceFallbackSearchQuery(
                            dto.getMessage(), __providerSearchQuery, rawSnips);
                    if (__domainFallbackQuery != null) {
                        NaverSearchService.SearchResult fallbackSr =
                                webSearchProvider.searchWithTrace(__domainFallbackQuery, topKParam);
                        if (fallbackSr != null && fallbackSr.snippets() != null && !fallbackSr.snippets().isEmpty()) {
                            List<String> acceptedFallback = acceptDomainEvidenceFallbackSnippets(
                                    dto.getMessage(),
                                    prioritizeDomainEvidenceSnippets(dto.getMessage(), fallbackSr.snippets()),
                                    rawSnips);
                            if (!acceptedFallback.equals(rawSnips)) {
                                sr = fallbackSr;
                                rawTrace = fallbackSr.trace();
                                rawSnips = acceptedFallback;
                            } else {
                                rawSnips = acceptedFallback;
                            }
                        }
                    }
                    rawSnips = completeNamedOfficialCoverageSnippets(
                            dto.getMessage(),
                            rawSnips,
                            rescueQuery -> {
                                NaverSearchService.SearchResult rescueSr =
                                        webSearchProvider.searchWithTrace(rescueQuery, topKParam);
                                return rescueSr == null || rescueSr.snippets() == null
                                        ? java.util.Collections.emptyList()
                                        : rescueSr.snippets();
                            });
                    sr = new NaverSearchService.SearchResult(rawSnips, rawTrace);

                    if (sr.snippets() == null || sr.snippets().isEmpty()) {
                        log.info("[ChatApi] All search providers failed. RAG-only fallback.");
                    }

                    if (rawTrace != null) {
                        // (A) Raw web snippets are shown immediately.
                        // (B) Final TopK context is added later after the chat workflow finishes.
                        try {
                            traceHtml = traceHtmlBuilder.buildSplitPanel(rawTrace, rawSnips, null, null);
                        } catch (Exception e) {
                            traceHtml = "";
                            logSuppressed("stream.traceHtml.prefetch");
                        }
                        if ((debug || exposeTrace) && traceHtml != null && !traceHtml.isBlank()) {
                            if (debug || exposeTrace) {
                                sink.tryEmitNext(sse(ChatStreamEvent.trace(traceHtml,
                                        ChatStreamSignalBuilder.buildTraceSignal(TraceStore.getAll(), __capturedTrace, __capturedRequestId, currentSessionKeyHolder[0]))));
                            }
                        }
                    }
                } else {
                    // Skip web search entirely and inform the client
                    sr = new NaverSearchService.SearchResult(List.of(), null);
                    if (debug) {
                        sink.tryEmitNext(sse(ChatStreamEvent.status("web search skipped")));
                    }
                }
                recordWebPrefetchTrace(
                        "stream",
                        __reqUseWeb,
                        allowWeb,
                        __workflowUseRag,
                        effectiveSearchMode,
                        topKParam,
                        __providerSearchQuery,
                        sr.snippets());

                final NaverSearchService.SearchResult srFinal = sr;
                final boolean __workflowUseWebForCall = __directUiModeStatusAnswer ? __workflowUseWeb : allowWeb;
                // 5) ???筌뤾쑵??
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.status("??瑜곷턄??곗뒧????롪틵?????????????쳜????덈콦 ??뚮봽??* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("??瑜곷턄??곗뒧????롪틵?????????????쳜????덈콦 ??뚮봽??* ... *&#47;")));
                }
                ChatRequestDto dtoForCall = dto.toBuilder()
                        .sessionId(session.getId())
                        .useRag(__workflowUseRag)
                        // Override useWebSearch based on attachment heuristic + plan cap
                        .useWebSearch(__workflowUseWebForCall)
                        .searchMode(effectiveSearchMode)
                        .build();

                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.status("??? ??諛댁뎽 繞?* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("??? ??諛댁뎽 繞?* ... *&#47;")));
                }
                // ChatWorkflow may change guard flags (officialOnly/domainProfile/minCitations) after this controller
                // prefetches web snippets (e.g. hatches / strictness adjustments). In that case, re-run
                // web search lazily so the evidence path stays consistent.
                final java.util.List<String> __prefetched = (srFinal == null ? java.util.List.of() : srFinal.snippets());
                final boolean __prefetchOfficial = gctx != null && gctx.isOfficialOnly();
                final String __prefetchDomainProfile = (gctx == null ? null : gctx.getDomainProfile());
                final Integer __prefetchMinCitations = (gctx == null ? null : gctx.getMinCitations());
                final java.util.concurrent.atomic.AtomicBoolean __queryRewriteTransformerEmitted =
                        new java.util.concurrent.atomic.AtomicBoolean(false);

                java.util.function.Function<String, java.util.List<String>> __webSupplier = (q) -> {
                    GuardContext __ctx;
                    try {
                        __ctx = GuardContextHolder.get();
                    } catch (Exception ignore) {
                        __ctx = null;
                        logSuppressed("stream.guardContext.webSupplier");
                    }

                    boolean __nowOfficial = (__ctx != null) ? __ctx.isOfficialOnly() : __prefetchOfficial;
                    String __nowDomainProfile = (__ctx != null) ? __ctx.getDomainProfile() : __prefetchDomainProfile;
                    Integer __nowMinCitations = (__ctx != null) ? __ctx.getMinCitations() : __prefetchMinCitations;
                    String __requestedQuery = q == null || q.isBlank() ? dto.getMessage() : q;
                    String __requestedProviderQuery = providerSearchQuery(__requestedQuery);
                    boolean __prefetchQueryMatches = prefetchQueryIdentityMatches(
                            __providerSearchQuery, __requestedProviderQuery);
                    boolean __prefetchScopeMatches = __nowOfficial == __prefetchOfficial
                            && java.util.Objects.equals(__nowDomainProfile, __prefetchDomainProfile)
                            && java.util.Objects.equals(__nowMinCitations, __prefetchMinCitations);

                    if (__prefetchQueryMatches && __prefetchScopeMatches) {
                        recordPrefetchIdentityDecision(
                                __providerSearchQuery, __requestedProviderQuery, true, true, "reuse");
                        emitStreamQueryRewriteTransformer(
                                sink,
                                __queryRewriteTransformerEmitted,
                                __capturedTrace,
                                __capturedRequestId,
                                currentSessionKeyHolder[0]);
                        return __prefetched;
                    }
                    recordPrefetchIdentityDecision(
                            __providerSearchQuery,
                            __requestedProviderQuery,
                            __prefetchQueryMatches,
                            __prefetchScopeMatches,
                            "fresh_search");
                    tracePut("chatApi.web.prefetch.invalidated", true);

                    try {
                        String providerQuery = __requestedProviderQuery;
                        List<String> snippets = prioritizeDomainEvidenceSnippets(
                                __requestedQuery,
                                webSearchProvider.search(providerQuery, topKParam));
                        snippets = completeNamedOfficialCoverageSnippets(
                                __requestedQuery,
                                snippets,
                                rescueQuery -> webSearchProvider.search(rescueQuery, topKParam));
                        String fallbackQuery = domainEvidenceFallbackSearchQuery(
                                __requestedQuery, providerQuery, snippets);
                        snippets = fallbackQuery == null
                                ? snippets
                                : acceptDomainEvidenceFallbackSnippets(
                                        __requestedQuery,
                                        prioritizeDomainEvidenceSnippets(
                                                __requestedQuery,
                                                webSearchProvider.search(fallbackQuery, topKParam)),
                                        snippets);
                        snippets = completeNamedOfficialCoverageSnippets(
                                __requestedQuery,
                                snippets,
                                rescueQuery -> webSearchProvider.search(rescueQuery, topKParam));
                        recordWebPrefetchTrace(
                                "stream.retry",
                                true,
                                true,
                                __workflowUseRag,
                                effectiveSearchMode,
                                topKParam,
                                providerQuery,
                                snippets);
                        emitStreamQueryRewriteTransformer(
                                sink,
                                __queryRewriteTransformerEmitted,
                                __capturedTrace,
                                __capturedRequestId,
                                currentSessionKeyHolder[0]);
                        return snippets == null ? java.util.List.of() : snippets;
                    } catch (Exception e) {
                        log.warn("[webSupplier] re-search failed; returning fail-soft empty: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                        return java.util.List.of();
                    }
                };

                // ChatResult is a top-level record (extracted from ChatService).
                emitDefaultModelWaitStatus(sink, __capturedBudget, __streamStartedNs);
                try {
                    debugCopilotService.maybeEnrichTrace();
                    StageBoundaryBreadcrumbs.recordFromCurrentTrace("pre_llm");
                    java.util.Map<String, Object> preLlmMeta = TraceStore.getAll();
                    promoteStageBoundaryDebugEvents("pre_llm", preLlmMeta, "ChatApiController.stream.preLlm");
                    attachDebugAiMatrixTrace(preLlmMeta, "stream.preLlm");
                    promoteDebugEvents("pre_llm", preLlmMeta, "ChatApiController.stream.preLlm");
                    ChatStreamEvent.TraceSignal preLlmTraceSignal =
                            ChatStreamSignalBuilder.buildTraceSignal(
                                    preLlmMeta, __capturedTrace, __capturedRequestId, currentSessionKeyHolder[0]);
                    ChatStreamEvent.PipelineSnapshot preLlmPipelineSnapshot =
                            ChatStreamSignalBuilder.buildPipelineSnapshot(preLlmMeta, null, null, preLlmTraceSignal);
                    ChatStreamEvent preLlmDebugFxEvent =
                            buildDebugFxEvent(preLlmMeta, preLlmTraceSignal, preLlmPipelineSnapshot);
                    if (preLlmDebugFxEvent != null) {
                        sink.tryEmitNext(sse(preLlmDebugFxEvent));
                        sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                                ChatStreamSignalBuilder.buildTransformerBlocks(
                                        preLlmMeta,
                                        null,
                                        preLlmPipelineSnapshot,
                                        preLlmTraceSignal,
                                        null,
                                        preLlmDebugFxEvent.debugFxSignal()))));
                    }
                } catch (Exception ignore) {
                    logSuppressed("stream.preLlmDebugFx");
                }
                requestTimelineId = beginModelRequestTimeline(
                        __capturedRequestId, currentSessionKeyHolder[0], dtoForCall.getModel());
                ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);
                ChatRunExecutionContext generatedRun = runContextRef.get();
                if (generatedRun != null) {
                    generatedRun.markGenerationSucceeded();
                }
                String semanticFinalText = result.content();
                String modelUsedFinal = ChatModelMetaSupport.resolveModelUsed(
                        result.modelUsed(), dto.getModel(), FALLBACK_MODEL);
                streamModelRef.set(modelUsedFinal);
                boolean streamCancelledFinal =
                        "cancelled".equalsIgnoreCase(ChatModelMetaSupport.safeTrim(result.modelUsed()))
                                || "cancelled".equalsIgnoreCase(ChatModelMetaSupport.safeTrim(modelUsedFinal));

                Long finalSessionId = session == null ? null : session.getId();
                if (streamCancelledFinal || isStreamRunCancelled(runContextRef.get(), finalSessionId)) {
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    emitSelectionEntropy(sink);
                    recordRunTerminal(runContextRef.get(), "cancelled");
                    recordModelRequestTerminal("cancelled", requestTimelineId, modelUsedFinal);
                    emitStreamCancelledStatus(sink, __capturedBudget, __streamStartedNs);
                    try {
                        stashStreamTraceSnapshot(streamTraceMetaRef);
                        TraceStore.clear();
                    } catch (Throwable ignore) {
                        logSuppressed("stream.cancelledBeforeFinal.clear");
                    }
                    return;
                }

                // Defensive: never stream an empty answer (would render as a blank bubble in the UI).
                // Even if upstream LLM fails or a post-processor trims everything, ensure the client
                // receives a user-visible fallback.
                if (semanticFinalText == null || semanticFinalText.isBlank()) {
                    tracePut("chatApi.emptyFinalText", true);
                    semanticFinalText = emptyFinalTextFallback(dto.getMessage());
                }
                semanticFinalText = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                        TraceStore.getAll(), dtoForCall.getMessage(), semanticFinalText);
                RagControlPresentationBoundary.Projection streamRagControlProjection = projectRagControlForUser(
                        semanticFinalText, __workflowUseWebForCall || __workflowUseRag);
                String visibleFinalText = streamRagControlProjection.visibleAnswer();
                String persistableFinalText = streamRagControlProjection.persistableAnswer();

                if (result.evidenceMetadata() != null && !result.evidenceMetadata().isEmpty()) {
                    sink.tryEmitNext(sse(ChatStreamEvent.evidence(result.evidenceMetadata())));
                }

                // (UI) answer.mode for fallback badges
                String answerModeFinal = null;

                // After the workflow finishes, pull the *actual* TopK evidence
                // sets used to build the prompt (web rerank + vector/RAG). This
                // allows the UI to show (A) raw web snippets and (B) final context
                // without re-running retrieval.
                LearningContextMetadata learningContextMeta = LearningContextMetadata.empty();
                ChatStreamEvent.PipelineSnapshot finalPipelineSnapshot = null;
                String traceHtmlForSnapshot = null;
                java.util.Map<String, Object> traceMetaForSnapshot = java.util.Map.of();
                java.util.Map<String, Object> finalTransformerMeta = java.util.Map.of();
                try {
                    // Preserve "enabled" signal: null means disabled, empty list means enabled but
                    // no results.
                    try { debugCopilotService.maybeEnrichTrace(); } catch (Exception ignore) { logSuppressed("debugCopilot.maybeEnrichTrace"); }
                    try { com.example.lms.trace.AblationContributionTracker.finalizeTraceIfNeeded(); } catch (Exception ignore) { logSuppressed("ablation.finalizeTrace"); }
                    StageBoundaryBreadcrumbs.recordFromCurrentTrace("final");
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    java.util.Map<String, Object> extraMeta = TraceStore.getAll();
                    attachModelRequestAttemptSummary(modelRuntimeHealthTracker, extraMeta, requestTimelineId);
                    mirrorAgentVisibleDebugEvidenceForStream(extraMeta, "stream.final");
                    promoteStageBoundaryDebugEvents("final", extraMeta, "ChatApiController.stream.final");
                    attachDebugAiMatrixTrace(extraMeta, "stream.final");
                    learningContextMeta = LearningContextMetadata.fromTrace(extraMeta);

                    // Capture answer mode (fail-soft). Used for UI fallback badges.
                    try {
                        Object __am = extraMeta.get("answer.mode");
                        if (__am != null) {
                            String __s = String.valueOf(__am).trim();
                            if (!__s.isBlank()) answerModeFinal = __s;
                        }
                        if (answerModeFinal == null || answerModeFinal.isBlank()) {
                            String __mu = result.modelUsed();
                            if (__mu != null && __mu.toLowerCase(java.util.Locale.ROOT).contains("fallback:evidence")) {
                                answerModeFinal = "FALLBACK_EVIDENCE";
                            }
                        }
                    } catch (Exception ignoreMode) {
                        logSuppressed("stream.answerMode");
                    }

                    try {
                        java.util.List<String> failureTags = FailureTagNormalizer.normalize(extraMeta, result.modelUsed(), null);
                        if (failureTags != null && !failureTags.isEmpty()) {
                            extraMeta.put("failureTags", failureTags);
                        }
                    } catch (Exception ignoreTags) {
                        logSuppressed("stream.failureTags");
                    }
                    ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalText, answerModeFinal);
                    if (!isStreamRunCancelled(runContextRef.get(), finalSessionId)
                            && !streamRagControlProjection.held()) {
                        clearLocalLlmOperatorActionAfterVisibleSuccess(extraMeta, persistableFinalText, modelUsedFinal);
                    }
                    promoteDebugEvents("final", extraMeta, "ChatApiController.stream.final");
                    ChatStreamEvent.TraceSignal finalTraceSignal =
                            ChatStreamSignalBuilder.buildTraceSignal(extraMeta, __capturedTrace, __capturedRequestId, currentSessionKeyHolder[0]);
                    finalPipelineSnapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(extraMeta, answerModeFinal, null, finalTraceSignal);
                    ChatStreamEvent finalDebugFxEvent =
                            buildDebugFxEvent(extraMeta, finalTraceSignal, finalPipelineSnapshot);
                    finalDebugFxEvent = ensureFinalAgentVisibleDebugFxFallbacks(finalDebugFxEvent, extraMeta);
                    if (finalDebugFxEvent != null) {
                        sink.tryEmitNext(sse(finalDebugFxEvent));
                    }
                    ChatStreamEvent.ScoreDeltaSignal finalScoreDeltaSignal = ChatStreamSignalBuilder.buildScoreDeltaSignal(extraMeta);
                    if (finalScoreDeltaSignal != null) {
                        sink.tryEmitNext(sse(ChatStreamEvent.scoreDelta(finalScoreDeltaSignal)));
                    }
                    finalTransformerMeta = java.util.Map.copyOf(extraMeta);
                    traceMetaForSnapshot = new java.util.LinkedHashMap<>(extraMeta);
                    sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                            ChatStreamSignalBuilder.buildTransformerBlocks(
                                    extraMeta,
                                    null,
                                    finalPipelineSnapshot,
                                    finalTraceSignal,
                                    finalScoreDeltaSignal,
                                    finalDebugFxEvent == null ? null : finalDebugFxEvent.debugFxSignal()))));
                    boolean finalTraceSignalEmitted = false;
                    java.util.List<Content> finalWebTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalWebTopK"));
                    java.util.List<Content> finalVectorTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalVectorTopK"));

                    // Console diagnostics: dump search trace + planner meta without exposing it to the client
                    try {
                        searchTraceConsoleLogger.maybeLog("stream", rawTrace, rawSnips, finalWebTopK, finalVectorTopK, extraMeta);
                    } catch (Exception ignoreLog) {
                        logSuppressed("stream.searchTraceConsole");
                    }

                    // clear to avoid cross-request contamination
                    stashStreamTraceSnapshot(streamTraceMetaRef);
                    TraceStore.clear();

                    if (rawTrace != null) {
                        String finalTraceHtml = traceHtmlBuilder.buildSplitPanel(rawTrace, rawSnips,
                                finalWebTopK,
                                finalVectorTopK,
                                extraMeta);
                        if (finalTraceHtml != null && !finalTraceHtml.isBlank()) {
                            traceHtml = finalTraceHtml;
                            // Emit again: streaming UI will replace the existing panel.
                            if (debug || exposeTrace) {
                                sink.tryEmitNext(sse(ChatStreamEvent.trace(finalTraceHtml, finalTraceSignal, finalPipelineSnapshot)));
                                finalTraceSignalEmitted = true;
                            }

                            java.util.Map<String, Object> snapMeta = new java.util.LinkedHashMap<>(
                                    extraMeta == null ? java.util.Map.of() : extraMeta);
                            snapMeta.put("ui.traceHtml.kind", "splitPanel");
                            snapMeta.put("ui.traceHtml.length", finalTraceHtml.length());
                            traceMetaForSnapshot = snapMeta;
                            traceHtmlForSnapshot = finalTraceHtml;
                        }
                    }
                    if (!finalTraceSignalEmitted && finalTraceSignal != null) {
                        sink.tryEmitNext(sse(ChatStreamEvent.trace(null, finalTraceSignal, finalPipelineSnapshot)));
                    }
                } catch (Exception ignore) {
                    logSuppressed("stream.finalTraceMeta");
                    try {
                        stashStreamTraceSnapshot(streamTraceMetaRef);
                        TraceStore.clear();
                    } catch (Exception ignore2) {
                        logSuppressed("stream.finalTraceMeta.clear");
                    }
                }

                // 6) ??ルㅎ荑????덈콦?洹먮맩鍮?嶺?野?
                if (isStreamRunCancelled(runContextRef.get(), finalSessionId)) {
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    emitSelectionEntropy(sink);
                    recordRunTerminal(runContextRef.get(), "cancelled");
                    recordModelRequestTerminal("cancelled", requestTimelineId, modelUsedFinal);
                    emitStreamCancelledStatus(sink, __capturedBudget, __streamStartedNs);
                    try {
                        stashStreamTraceSnapshot(streamTraceMetaRef);
                        TraceStore.clear();
                    } catch (Throwable ignore) {
                        logSuppressed("stream.cancelledBeforeVisibleFinal.clear");
                    }
                    return;
                }

                boolean cancelledBeforePersist = false;
                for (String c : chunk(visibleFinalText, 60)) {
                    if (isStreamRunCancelled(runContextRef.get(), finalSessionId)) {
                        cancelledBeforePersist = true;
                        break;
                    }
                    Sinks.EmitResult tokenEmitResult = emitTokenStreamEvent(
                            sink, sse(ChatStreamEvent.token(c)));
                    if (!tokenEmitResult.isSuccess()) {
                        TraceStore.put("chat.stream.outcome.tokenEmitFailureReason",
                                tokenEmitResult.name().toLowerCase(java.util.Locale.ROOT));
                    }
                }

                if (!cancelledBeforePersist
                        && isStreamRunCancelled(runContextRef.get(), finalSessionId)) {
                    cancelledBeforePersist = true;
                }
                ChatRunExecutionContext committingRun = runContextRef.get();
                if (!cancelledBeforePersist
                        && committingRun != null
                        && !committingRun.tryBeginTranscriptCommit()) {
                    cancelledBeforePersist = true;
                }
                if (cancelledBeforePersist) {
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    emitSelectionEntropy(sink);
                    recordRunTerminal(runContextRef.get(), "cancelled");
                    recordModelRequestTerminal("cancelled", requestTimelineId, modelUsedFinal);
                    emitStreamCancelledStatus(sink, __capturedBudget, __streamStartedNs);
                    try {
                        stashStreamTraceSnapshot(streamTraceMetaRef);
                        TraceStore.clear();
                    } catch (Throwable ignore) {
                        logSuppressed("stream.cancelledBeforePersist.clear");
                    }
                    return;
                }

                // 7) ?筌뤾쑬??????+ 嶺뚮ㅄ維???筌뤾퍔???怨룸츩 嶺뚮∥??
                Long persistenceSessionId = session.getId();
                String persistenceAnswerMode = answerModeFinal;
                java.util.Map<String, Object> persistenceTraceMeta = traceMetaForSnapshot;
                String persistenceTraceHtml = traceHtmlForSnapshot;
                ChatStreamEvent.PipelineSnapshot pipelineSnapshotBeforePersistence = finalPipelineSnapshot;
                AtomicReference<Long> traceTurnIdRef = new AtomicReference<>();
                AtomicReference<ChatStreamEvent.PipelineSnapshot> persistedPipelineSnapshotRef =
                        new AtomicReference<>(pipelineSnapshotBeforePersistence);
                Runnable durablePersistence = () -> {
                    Long assistantMessageId = historyService.appendMessageReturningId(
                            persistenceSessionId, "assistant", persistableFinalText);
                    if (assistantMessageId != null && committingRun != null && !committingRun.markPersisted()) {
                        throw new IllegalStateException("exact transcript persistence outcome rejected");
                    }
                    if (!streamRagControlProjection.held()) {
                        updateRollingSummaryAndMaybePromote(
                                persistenceSessionId, assistantMessageId, req);
                    }

                    historyService.appendMessage(persistenceSessionId, "system",
                            String.format("%s%s", MODEL_META_PREFIX, modelUsedFinal));

                    Long persistedTraceTurnId = ChatTraceSnapshotPointerPersister.persist(
                            persistenceSessionId,
                            "chat.trace_html.final",
                            "SSE",
                            (__httpPath == null ? "/api/chat/stream" : __httpPath),
                            persistenceTraceMeta,
                            persistenceTraceHtml,
                            traceSnapshotStore,
                            historyService,
                            log);
                    traceTurnIdRef.set(persistedTraceTurnId);
                    persistedPipelineSnapshotRef.set(ChatStreamSignalBuilder.withTraceTurnId(
                            pipelineSnapshotBeforePersistence,
                            persistenceAnswerMode,
                            persistedTraceTurnId));

                    // Persist answer.mode + traceTurnId snapshot so that the session list can be
                    // restored cross-device and the sidebar can auto-open the exact trace panel.
                    try {
                        historyService.updateSessionAnswerModeAndTrace(
                                persistenceSessionId,
                                persistenceAnswerMode,
                                persistedTraceTurnId);
                    } catch (Exception ignore) {
                        logSuppressed("stream.answerModeTracePersist");
                    }
                };
                boolean durablePersistenceAccepted;
                if (committingRun == null) {
                    durablePersistence.run();
                    durablePersistenceAccepted = true;
                } else {
                    durablePersistenceAccepted =
                            committingRun.runTerminalSideEffect(durablePersistence);
                }
                if (!durablePersistenceAccepted) {
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    emitSelectionEntropy(sink);
                    recordRunTerminal(runContextRef.get(), "cancelled");
                    recordModelRequestTerminal("cancelled", requestTimelineId, modelUsedFinal);
                    emitStreamCancelledStatus(sink, __capturedBudget, __streamStartedNs);
                    return;
                }
                Long traceTurnId = traceTurnIdRef.get();
                finalPipelineSnapshot = persistedPipelineSnapshotRef.get();

                try {
                    Long remainingMs = __capturedBudget == null ? null : __capturedBudget.remainingMillis();
                    long tookMs = Math.max(0L, (System.nanoTime() - __streamStartedNs) / 1_000_000L);
                    ChatStreamEvent.StatusSignal completeSignal = ChatStreamEvent.StatusSignal.of(
                            "stream",
                            streamCancelledFinal ? "cancelled" : "finalizing",
                            streamCancelledFinal ? "stream cancelled" : "stream finalizing",
                            remainingMs,
                            tookMs,
                            streamCancelledFinal);
                    sink.tryEmitNext(sse(ChatStreamEvent.status(completeSignal)));
                    sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                            ChatStreamSignalBuilder.buildTransformerBlocks(
                                    finalTransformerMeta,
                                    completeSignal,
                                    finalPipelineSnapshot,
                                    null,
                                    null,
                                    null))));
                } catch (Throwable ignore) {
                    logSuppressed("stream.status.complete");
                }

                writeSelectionEntropyProjection(
                        selectionEntropy, selectionDecisionLedger, true);
                emitSelectionEntropy(sink);
                ChatStreamEvent completedRequestEvent = ChatStreamEvent.doneWithAnswer(
                                visibleFinalText,
                                modelUsedFinal,
                                result.ragUsed(),
                                session.getId(),
                                answerModeFinal,
                                traceTurnId,
                                learningContextMeta,
                                result.evidenceMetadata(),
                                finalPipelineSnapshot);
                requestCompletion.accept(completedRequestEvent);
                Sinks.EmitResult finalEmitResult = emitFinalStreamEvent(
                        committingRun,
                        sink,
                        sse(completedRequestEvent),
                        clientDetached.get());
                boolean cancelledAfterFinalEmit = isStreamRunCancelled(runContextRef.get(), finalSessionId);
                recordVisibleModelSemanticOutcome(
                        traceMetaForSnapshot,
                        persistableFinalText,
                        modelUsedFinal,
                        requestTimelineId,
                        cancelledAfterFinalEmit
                                ? ModelRuntimeHealthTracker.SemanticTerminalState.CANCELLED
                                : finalEmitResult.isSuccess()
                                        ? ModelRuntimeHealthTracker.SemanticTerminalState.DELIVERY_PENDING
                                        : ModelRuntimeHealthTracker.SemanticTerminalState.FAILED,
                        true,
                        false,
                        streamRagControlProjection.held());
                recordModelRequestTerminal(
                        cancelledAfterFinalEmit
                                ? "cancelled"
                                : finalEmitResult.isSuccess()
                                        ? "generated_persisted_delivery_pending"
                                        : "generated_persisted_delivery_failed",
                        requestTimelineId,
                        modelUsedFinal);
                streamOutcomeRef.compareAndSet(null,
                        cancelledAfterFinalEmit ? "cancelled" : "completed");
            } catch (Exception ex) {
                if (selectionEntropy.mode() == SelectionEntropyMode.REPLAY
                        && ex instanceof SelectionEntropyException selectionFailure) {
                    SelectionReplayRequestException safeFailure =
                            SelectionReplayRequestException.from(selectionFailure.reason());
                    selectionDecisionLedger.markFailure(selectionFailure.reason());
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    emitSelectionEntropy(sink);
                    recordRunTerminal(runContextRef.get(), "error");
                    recordModelRequestTerminal("error", requestTimelineId, null);
                    sink.tryEmitNext(sse(ChatStreamEvent.error(safeFailure.code())));
                    return;
                }
                if (ex instanceof ChatHistoryService.SessionQuotaExceededException) {
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    emitSelectionEntropy(sink);
                    recordRunTerminal(runContextRef.get(), "error");
                    recordModelRequestTerminal("error", requestTimelineId, null);
                    sink.tryEmitNext(sse(ChatStreamEvent.error("session_quota_exceeded")));
                    return;
                }
                if (isStreamCancellation(ex, runContextRef.get())) {
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    emitSelectionEntropy(sink);
                    recordRunTerminal(runContextRef.get(), "cancelled");
                    recordModelRequestTerminal("cancelled", requestTimelineId, null);
                    emitStreamCancelledStatus(sink, __capturedBudget, __streamStartedNs);
                    return;
                }
                var responseTerminal = com.example.lms.llm.gateway.LlmResponseTerminalException.find(ex);
                if (responseTerminal != null) {
                    var run = runContextRef.get();
                    String terminalTimelineId = requestTimelineId;
                    if (run != null) run.admitCall(() -> {
                        recordRunTerminal(run, responseTerminal.reasonCode());
                        recordModelRequestTerminal(responseTerminal.reasonCode(), terminalTimelineId,
                                responseTerminal.metadata().modelName());
                        streamOutcomeRef.compareAndSet(null, responseTerminal.reasonCode());
                        sink.tryEmitNext(sse(ChatStreamEvent.terminal(
                                ChatResponseDto.terminal(responseTerminal, currentSessionId.get()))));
                    });
                    return;
                }
                recordRunTerminal(runContextRef.get(), "error");
                recordModelRequestTerminal("error", requestTimelineId, null);
                log.error("[AWX][chat] stream-failed type={} error={}", ex.getClass().getSimpleName(), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
                // Avoid direct string concatenation when building error messages
                String errMsg = ex instanceof com.example.lms.llm.ModelSelectionException selection
                        ? selection.code() : "stream_failed";
                try {
                    Long remainingMs = __capturedBudget == null ? null : __capturedBudget.remainingMillis();
                    long tookMs = Math.max(0L, (System.nanoTime() - __streamStartedNs) / 1_000_000L);
                    ChatStreamEvent.StatusSignal errorSignal = ChatStreamEvent.StatusSignal.of(
                            "stream", "error", "stream error", remainingMs, tookMs, false);
                    sink.tryEmitNext(sse(ChatStreamEvent.status(errorSignal)));
                    sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                            ChatStreamSignalBuilder.buildTransformerBlocks(
                                    java.util.Map.of(),
                                    errorSignal,
                                    null,
                                    null,
                                    null,
                                    null))));
                } catch (Throwable ignore) {
                    logSuppressed("stream.status.error");
                }
                writeSelectionEntropyProjection(
                        selectionEntropy, selectionDecisionLedger, true);
                emitSelectionEntropy(sink);
                sink.tryEmitNext(sse(ChatStreamEvent.error(errMsg)));
            } finally {
                // 세션 디버그 증거: TraceContext 정리 전에 메타를 스냅샷한다.
                try {
                    ChatRunExecutionContext traceRun = runContextRef.get();
                    String outcome = streamOutcomeRef.get();
                    if (outcome == null) {
                        outcome = traceRun != null && traceRun.isCancellationRequested()
                                ? "cancelled" : "error";
                    }
                    emitChatSessionTrace(sessionTraceOnce, "chat", currentSessionId.get(),
                            traceRun, req.getModel(), streamModelRef.get(),
                            req.getUseRag(),
                            outcome, mergeStreamTraceMeta(streamTraceMetaRef.get()));
                } catch (Throwable ignore) {
                    logSuppressed("chat.sessionTrace.stream");
                }
                // ?????댁읉??? ??亦???????쳜????덈콦 ?熬곣뫖???꾩렮維?
                GuardContextHolder.clear();
                try {
                    TraceContext.cleanupCurrentThread();
                } catch (Throwable ignore) {
                    logSuppressed("traceContext.cleanup");
                }
                try {
                    if (__capturedBudget != null) {
                        com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
                    }
                } catch (Throwable ignore) {
                    logSuppressed("timeBudget.clear");
                }
                try {
                    if (retrievalDiagnosticsCollector != null) {
                        retrievalDiagnosticsCollector.reset();
                    }
                } catch (Throwable ignore) {
                    logSuppressed("retrievalDiagnostics.reset");
                }
                // ?熬곣뫁???SSE sink ?繹먮굞夷???怨몄젷 ?????덈콦???熬곣뫁??
                try {
                    ChatRunExecutionContext runContext = runContextRef.get();
                    if (runContext != null) {
                        chatStreamEmitter.unregisterSink(runContext, sink);
                    } else {
                        String sKey = currentSessionKeyHolder[0];
                        if (sKey != null) {
                            chatStreamEmitter.unregisterSink(sKey, sink);
                        }
                    }
                } catch (Throwable t) {
                    log.debug("Failed to unregister SSE sink: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length()));
                }
                try {
                    if (runScope != null) {
                        runScope.close();
                    }
                } catch (Throwable t) {
                    log.debug("Failed to clear exact run scope: {}", errorSummary(t));
                }
                try {
                    ChatRunExecutionContext runContext = runContextRef.get();
                    if (runContext != null) {
                        runRegistry.markDone(runContext);
                    }
                } catch (Throwable t) {
                    log.debug("Failed to finish exact run: {}", errorSummary(t));
                }
                sink.tryEmitComplete();
            }
        }
            } catch (Throwable lifecycleFailure) {
                log.error("[AWX][chat] stream-lifecycle-failed type={} error={}",
                        lifecycleFailure.getClass().getSimpleName(), errorSummary(lifecycleFailure));
                String errorMessage = String.format(
                        "Chat stream failed errorHash=%s errorLength=%d",
                        SafeRedactor.hashValue(lifecycleFailure.getMessage()),
                        lifecycleFailure.getMessage() == null ? 0 : lifecycleFailure.getMessage().length());
                ChatRunExecutionContext failedRun = runContextRef.get();
                // 워커 본문 진입 전 실패도 세션 증거로 남긴다(내부 finally가 이미 기록했으면 no-op).
                try {
                    String outcome = streamOutcomeRef.get();
                    if (outcome == null) {
                        outcome = failedRun != null && failedRun.isCancellationRequested()
                                ? "cancelled" : "error";
                    }
                    emitChatSessionTrace(sessionTraceOnce, "chat", currentSessionId.get(),
                            failedRun, req.getModel(), streamModelRef.get(),
                            req.getUseRag(),
                            outcome, mergeStreamTraceMeta(streamTraceMetaRef.get()));
                } catch (Throwable ignore) {
                    logSuppressed("chat.sessionTrace.lifecycle");
                }
                try {
                    writeSelectionEntropyProjection(
                            selectionEntropy, selectionDecisionLedger, true);
                    emitSelectionEntropy(sink);
                    ServerSentEvent<ChatStreamEvent> lifecycleError = sse(ChatStreamEvent.error(errorMessage));
                    sink.tryEmitNext(lifecycleError);
                    if (failedRun != null && !replayBridgeInstalled.get()) {
                        runRegistry.emit(failedRun, lifecycleError);
                    }
                } catch (Throwable ignore) {
                    logSuppressed("stream.lifecycle.errorEmit");
                }
                GuardContextHolder.clear();
                try {
                    TraceContext.cleanupCurrentThread();
                } catch (Throwable ignore) {
                    logSuppressed("stream.lifecycle.traceCleanup");
                }
                try {
                    com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
                } catch (Throwable ignore) {
                    logSuppressed("stream.lifecycle.budgetCleanup");
                }
                try {
                    if (runScope != null) {
                        runScope.close();
                    }
                } catch (Throwable ignore) {
                    logSuppressed("stream.lifecycle.runScopeCleanup");
                }
                try {
                    if (failedRun != null) {
                        runRegistry.markDone(failedRun);
                    }
                } catch (Throwable ignore) {
                    logSuppressed("stream.lifecycle.runCleanup");
                }
                sink.tryEmitComplete();
            }
        })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(ignored -> admissionLease.close())
                .subscribe();
        admissionTransferredToWorker.set(true);
        runWorkerRef.set(d);
        ChatRunExecutionContext prestartedContext = runContextRef.get();
        if (prestartedContext != null) {
            prestartedContext.registerCancellationHandle(d);
        }
        // Build the response flux with cancellation, error and finalisation hooks. Do
        // not
        // return immediately so that we can attach Reactor context below.
        Flux<ServerSentEvent<ChatStreamEvent>> flux = sink.asFlux()
                .doOnCancel(() -> {
                    clientDetached.set(true);
                    ChatRunExecutionContext detachedRun = runContextRef.get();
                    boolean cancelledUnacknowledged = clientAckRequired
                            && runRegistry != null
                            && detachedRun != null
                            && emitPreAcknowledgementCancellation(
                                    sink,
                                    detachedRun,
                                    selectionEntropy,
                                    selectionDecisionLedger,
                                    __capturedBudget,
                                    __streamStartedNs);
                    interactiveClient.disconnect();
                    Long sid = currentSessionId.get();
                    try {
                        if (sid != null) {
                            TraceStore.put("chat.sse.detach", true);
                            TraceStore.put("chat.sse.resumePreserved",
                                    detachedRun != null && !detachedRun.isCancellationRequested());
                        }
                    } catch (Throwable ignore) {
                        logSuppressed("sse.detach.trace");
                    }
                    log.info("SSE stream detached by client (sessionHash={}, resumePreserved={})",
                            sid == null ? null : SafeRedactor.hashValue(String.valueOf(sid)),
                            detachedRun != null && !detachedRun.isCancellationRequested());
                })
                .doOnError(e -> log.warn("SSE stream error (sessionHash={}): {}",
                        SafeRedactor.hashValue(String.valueOf(currentSessionId.get())), errorSummary(e)));
        // Attach the captured client IP to the Reactor context to allow downstream
        // components to derive the caller identity on non-request threads.
        return runRegistry.interactiveSource(interactiveClient, flux)
                .contextWrite(Context.of("clientIp", clientIp));
        } finally {
            if (!admissionTransferredToWorker.get()) {
                admissionLease.close();
            }
        }
    }

    private boolean isStreamRunCancelled(ChatRunExecutionContext context, Long sessionId) {
        if (context != null) {
            return context.isCancellationRequested();
        }
        return sessionId != null && runRegistry != null && runRegistry.isCancelled(sessionId);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            TraceStore.put("chat.api.suppressed.parse.asLong", true);
            TraceStore.put("chat.api.suppressed.parse.asLong.errorType", "invalid_number");
            logSuppressed("parse.asLong");
            return null;
        }
    }

    private static Long longFromBody(Map<String, Object> body, String key) {
        if (body == null || key == null) {
            return null;
        }
        return asLong(body.get(key));
    }

    private static String stringFromBody(Map<String, Object> body, String key) {
        if (body == null || key == null) {
            return null;
        }
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean canAccessSession(ChatSession session, Authentication authentication) {
        if (session == null) {
            return false;
        }
        if (isAdmin(authentication)) {
            return true;
        }
        String username = authentication != null && authentication.isAuthenticated() ? authentication.getName() : null;
        var owner = session.getAdministrator();
        if (owner != null) {
            return username != null && owner.getUsername().equals(username);
        }
        String currentKey = ownerKeyResolver.ownerKey();
        return session.getOwnerKey() != null && session.getOwnerKey().equals(currentKey);
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static ServerSentEvent<ChatStreamEvent> sse(ChatStreamEvent e) {
        return ServerSentEvent.<ChatStreamEvent>builder(e).event(e.type()).build();
    }

    private static SelectionEntropyProjection writeSelectionEntropyProjection(
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger,
            boolean terminal) {
        SelectionEntropyProjection projection =
                SelectionEntropyProjection.from(entropy, ledger, terminal, false);
        SelectionEntropyTraceSupport.write(projection);
        return projection;
    }

    private static ServerSentEvent<ChatStreamEvent> selectionEntropyEvent() {
        ChatStreamEvent.SelectionEntropySignal signal =
                ChatStreamSignalBuilder.buildSelectionEntropySignal(TraceStore.getAll());
        return signal == null ? null : sse(ChatStreamEvent.selectionEntropy(signal));
    }

    private static void emitSelectionEntropy(
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink) {
        ServerSentEvent<ChatStreamEvent> event = selectionEntropyEvent();
        if (sink != null && event != null) {
            sink.tryEmitNext(event);
        }
    }

    private boolean emitPreAcknowledgementCancellation(
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
            ChatRunExecutionContext runContext,
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger,
            com.abandonware.ai.addons.budget.TimeBudget budget,
            long streamStartedNs) {
        List<ServerSentEvent<ChatStreamEvent>> events = new ArrayList<>();
        try {
            writeSelectionEntropyProjection(entropy, ledger, false);
            ServerSentEvent<ChatStreamEvent> selectionEvent = selectionEntropyEvent();
            if (selectionEvent != null) {
                events.add(selectionEvent);
            }
        } catch (Throwable ignore) {
            logSuppressed("stream.preAckCancellation");
        }
        events.addAll(streamCancelledEvents(budget, streamStartedNs));
        boolean cancelled = runContext != null
                && runRegistry != null
                && runRegistry.cancelIfUnacknowledged(runContext, events);
        if (!cancelled) {
            return false;
        }
        for (ServerSentEvent<ChatStreamEvent> event : events) {
            if (sink != null) {
                sink.tryEmitNext(event);
            }
        }
        recordRunTerminal(runContext, "cancelled");
        return true;
    }

    TraceContext attachStreamTraceContext(String sid, String traceId) {
        return TraceContext.attach(sid, traceId);
    }

    static ChatStreamEvent buildDebugFxEvent(
            java.util.Map<String, Object> meta,
            ChatStreamEvent.TraceSignal traceSignal,
            ChatStreamEvent.PipelineSnapshot pipelineSnapshot) {
        ChatStreamEvent.DebugFxSignal signal =
                ChatStreamSignalBuilder.buildDebugFxSignal(meta, traceSignal, pipelineSnapshot);
        return signal == null ? null : ChatStreamEvent.debugFx(signal);
    }

    private void attachDebugAiMatrixTrace(java.util.Map<String, Object> meta, String stage) {
        if (meta == null || debugAiMetricsService == null) {
            return;
        }
        try {
            java.util.Map<String, Object> compact = debugAiMetricsService.compactSnapshot(80, 300_000L);
            mirrorDebugAiMetricsCompact(meta, compact);
            mirrorDebugAiTraceMemoryCompact(meta, compact);
        } catch (Exception ignore) {
            logSuppressed(stage + ".debugAiMatrixTrace");
        }
    }

    private static void mirrorDebugAiMetricsCompact(
            java.util.Map<String, Object> meta,
            java.util.Map<String, Object> compact) {
        if (meta == null || compact == null || compact.isEmpty()) {
            return;
        }
        putMetaAndTrace(meta, "debug.ai.metrics.virtualMatrix.count", compact.get("virtualMatrixCount"));
        putMetaAndTrace(meta, "debug.ai.metrics.virtualMatrix.chunkSize", compact.get("virtualMatrixChunkSize"));
        putMetaAndTrace(meta, "debug.ai.metrics.virtualMatrix.chunkCount", compact.get("virtualMatrixChunkCount"));
        putMetaAndTrace(meta, "debug.ai.metrics.virtualMatrix.weightedScore", compact.get("virtualMatrixWeightedScore"));
        putMetaAndTrace(meta, "debug.ai.metrics.virtualMatrix.decision", compact.get("virtualMatrixDecision"));
        java.util.Map<String, Object> hotChunk = firstMap(compact.get("virtualMatrixHotChunks"));
        putMetaAndTrace(meta, "debug.ai.metrics.virtualMatrix.hotChunkIndex", hotChunk.get("chunkIndex"));
        putMetaAndTrace(meta, "debug.ai.metrics.virtualMatrix.hotChunkRiskScore", hotChunk.get("riskScore"));
        if (meta.containsKey("debug.ai.metrics.virtualMatrix.count")
                || meta.containsKey("debug.ai.metrics.virtualMatrix.decision")) {
            putMetaAndTrace(meta, "debug.ai.metrics.virtualMatrix.agentVisible", Boolean.TRUE);
        }
    }

    private static void mirrorDebugAiTraceMemoryCompact(
            java.util.Map<String, Object> meta,
            java.util.Map<String, Object> compact) {
        if (meta == null || compact == null || compact.isEmpty()) {
            return;
        }
        java.util.Map<String, Object> diagnostics = mapValue(compact.get("traceMemoryDiagnostics"));
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.traceMemory.routeDecision",
                diagnostics.get("routeDecision"));
        putMetaAndTrace(meta, "debug.ai.agentDebugEvidence.traceMemory.routeDecision",
                diagnostics.get("routeDecision"));
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.traceMemory.cfvmOffered",
                diagnostics.get("cfvmOffered"));
        putMetaAndTrace(meta, "debug.ai.agentDebugEvidence.traceMemory.cfvmOffered",
                diagnostics.get("cfvmOffered"));
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.traceMemory.cfvmPatternId",
                diagnostics.get("cfvmPatternId"));
        putMetaAndTrace(meta, "debug.ai.agentDebugEvidence.traceMemory.cfvmPatternId",
                diagnostics.get("cfvmPatternId"));
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestKey",
                diagnostics.get("virtualCheckpointKey"));
        putMetaAndTrace(meta, "debug.ai.agentDebugEvidence.traceMemory.virtualCheckpointLatestKey",
                diagnostics.get("virtualCheckpointKey"));
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestStage",
                diagnostics.get("virtualCheckpointStage"));
        putMetaAndTrace(meta, "debug.ai.agentDebugEvidence.traceMemory.virtualCheckpointLatestStage",
                diagnostics.get("virtualCheckpointStage"));
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestPhase",
                diagnostics.get("virtualCheckpointPhase"));
        putMetaAndTrace(meta, "debug.ai.agentDebugEvidence.traceMemory.virtualCheckpointLatestPhase",
                diagnostics.get("virtualCheckpointPhase"));
    }

    private static void mirrorAgentVisibleDebugEvidenceForStream(
            java.util.Map<String, Object> meta,
            String stage) {
        if (meta == null) {
            return;
        }
        try {
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.browser.status",
                    "debug.ai.agentDebugEvidence.external.browser.status");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.browser.evidenceNeeded",
                    "debug.ai.agentDebugEvidence.external.browser.evidenceNeeded");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.browser.nextAction",
                    "debug.ai.agentDebugEvidence.external.browser.nextAction");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.computerUse.status",
                    "debug.ai.agentDebugEvidence.external.computerUse.status");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.computerUse.evidenceNeeded",
                    "debug.ai.agentDebugEvidence.external.computerUse.evidenceNeeded");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.computerUse.nextAction",
                    "debug.ai.agentDebugEvidence.external.computerUse.nextAction");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.supabase.status",
                    "debug.ai.agentDebugEvidence.external.supabase.status");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.supabase.evidenceNeeded",
                    "debug.ai.agentDebugEvidence.external.supabase.evidenceNeeded");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.external.supabase.nextAction",
                    "debug.ai.agentDebugEvidence.external.supabase.nextAction");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.agentDbContext.status",
                    "debug.ai.agentDebugEvidence.agentDbContext.status");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.agentDbContext.reason",
                    "debug.ai.agentDebugEvidence.agentDbContext.reason");
            mirrorTracePair(meta,
                    "prompt.agentDebugEvidence.agentDbContext.nextAction",
                    "debug.ai.agentDebugEvidence.agentDbContext.nextAction");
        } catch (Exception ignore) {
            logSuppressed(stage + ".agentVisibleMirror");
        }
    }

    private static ChatStreamEvent ensureFinalAgentVisibleDebugFxFallbacks(
            ChatStreamEvent finalDebugFxEvent,
            java.util.Map<String, Object> extraMeta) {
        if (finalDebugFxEvent != null) {
            return finalDebugFxEvent;
        }
        return buildDebugFxEvent(extraMeta, null, null);
    }

    private void recordVisibleModelSemanticOutcome(
            java.util.Map<String, Object> finalMeta,
            String answer,
            String modelUsed,
            String timelineId,
            ModelRuntimeHealthTracker.SemanticTerminalState terminalState,
            boolean persistenceAccepted,
            boolean deliveryAccepted,
            boolean ragControlHeld) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            ModelRuntimeHealthTracker.VerificationPolicy verificationPolicy =
                    semanticVerificationPolicy(finalMeta);
            boolean verifierAccepted = Boolean.TRUE.equals(finalMeta == null
                    ? null
                    : finalMeta.get("finalAnswer.verificationAcceptedForMemory"));
            boolean releaseAllowed = Boolean.TRUE.equals(finalMeta == null
                    ? null
                    : finalMeta.get("finalAnswer.releaseAllowed"));
            modelRuntimeHealthTracker.recordSemanticOutcome(
                    timelineId,
                    modelUsed,
                    new ModelRuntimeHealthTracker.SemanticOutcome(
                            isEligibleSemanticModelAnswer(answer, modelUsed),
                            terminalState,
                            verificationPolicy,
                            verifierAccepted,
                            releaseAllowed && !ragControlHeld,
                            persistenceAccepted,
                            deliveryAccepted));
        } catch (RuntimeException ex) {
            logSuppressed("modelRuntimeHealth.semanticOutcome");
        }
    }

    private static ModelRuntimeHealthTracker.VerificationPolicy semanticVerificationPolicy(
            java.util.Map<String, Object> finalMeta) {
        if (finalMeta == null) {
            return null;
        }
        String releaseStatus = normalizedSemanticMeta(finalMeta.get("finalAnswer.releaseStatus"));
        String releaseReason = normalizedSemanticMeta(finalMeta.get("finalAnswer.releaseReason"));
        if ("not_required".equals(releaseStatus)
                && "verification_not_required".equals(releaseReason)) {
            return ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED;
        }
        if ("approve".equals(releaseStatus)
                && "verification_accepted".equals(releaseReason)) {
            return ModelRuntimeHealthTracker.VerificationPolicy.REQUIRED;
        }
        return null;
    }

    private static String normalizedSemanticMeta(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isEligibleSemanticModelAnswer(String answer, String modelUsed) {
        if (answer == null || answer.isBlank() || modelUsed == null || modelUsed.isBlank()) {
            return false;
        }
        String lower = modelUsed.trim().toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("fallback")
                && !lower.contains("cancel")
                && !lower.contains("error")
                && !lower.contains("fail")
                && !lower.contains("embedding");
    }

    private String beginModelRequestTimeline(String requestId, String sessionId, String modelId) {
        if (modelRuntimeHealthTracker == null) {
            return null;
        }
        try {
            String timelineId = modelRuntimeHealthTracker.beginRequestTimeline(requestId, sessionId);
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            modelRuntimeHealthTracker.recordRequestPhase(
                    timelineId, "dispatch", modelId, null, "none");
            return timelineId;
        } catch (RuntimeException ex) {
            logSuppressed("modelRequestTimeline.dispatch");
            return null;
        }
    }

    static void attachModelRequestAttemptSummary(
            ModelRuntimeHealthTracker tracker,
            java.util.Map<String, Object> meta,
            String timelineId) {
        if (tracker == null || meta == null || timelineId == null || timelineId.isBlank()) {
            return;
        }
        try {
            java.util.List<java.util.Map<String, Object>> summary =
                    ChatStreamSignalBuilder.summarizeRequestAttempts(
                            tracker.redactedRequestAttemptLedger(timelineId));
            if (!summary.isEmpty()) {
                meta.put(ChatStreamSignalBuilder.REQUEST_ATTEMPT_SUMMARY_KEY, summary);
            }
        } catch (RuntimeException ex) {
            logSuppressed("modelRequestTimeline.summary");
        }
    }

    private void recordModelRequestTerminal(String terminalClass, String timelineId, String modelId) {
        if (modelRuntimeHealthTracker == null || timelineId == null || timelineId.isBlank()) {
            return;
        }
        try {
            modelRuntimeHealthTracker.recordRequestPhase(
                    timelineId, "terminal", modelId, null, terminalClass);
        } catch (RuntimeException ex) {
            logSuppressed("modelRequestTimeline.terminal");
        }
    }

    Sinks.EmitResult emitStreamEvent(
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
            ServerSentEvent<ChatStreamEvent> event) {
        return sink == null ? Sinks.EmitResult.FAIL_TERMINATED : sink.tryEmitNext(event);
    }

    Sinks.EmitResult emitTokenStreamEvent(
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
            ServerSentEvent<ChatStreamEvent> event) {
        return sink == null ? Sinks.EmitResult.FAIL_TERMINATED : sink.tryEmitNext(event);
    }

    Sinks.EmitResult emitFinalStreamEvent(
            ChatRunExecutionContext runContext,
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
            ServerSentEvent<ChatStreamEvent> event,
            boolean clientDetached) {
        if (runContext != null && !runContext.claimTerminalEvent("delivery_pending")) {
            TraceStore.put("chat.stream.outcome.duplicateSuppressed", true);
            return Sinks.EmitResult.FAIL_CANCELLED;
        }
        Sinks.EmitResult result = emitStreamEvent(sink, event);
        if (runContext != null) {
            runContext.recordFinalEmit(result.name(), clientDetached);
        }
        TraceStore.put("chat.stream.outcome.finalEmitResult",
                result.name().toLowerCase(java.util.Locale.ROOT));
        TraceStore.put("chat.stream.outcome.finalDeliveryAccepted", false);
        TraceStore.put("chat.stream.outcome.finalDeliveryFailureReason",
                result.isSuccess() ? clientDetached ? "client_detached" : "ack_pending" : "final_emit_failed");
        return result;
    }

    private static void recordRunTerminal(ChatRunExecutionContext runContext, String reason) {
        if (runContext != null) {
            runContext.recordTerminalWithoutFinal(reason);
        }
    }

    /**
     * 세션 디버그 증거를 exactly-once로 기록한다. 여러 종료 경로(성공/취소/오류/
     * 라이프사이클 실패)가 동시에 호출해도 once 가드가 첫 호출만 통과시킨다.
     * 레코더가 없거나 쓰기 실패여도 채팅 흐름에 영향을 주지 않는다.
     */
    /**
     * 요청 중간의 TraceStore.clear()가 세션 증거를 비우지 않도록 지우기 직전
     * 스냅샷을 보관한다(마지막으로 관측된 비어있지 않은 맵이 남는다).
     */
    private static void stashStreamTraceSnapshot(
            AtomicReference<java.util.Map<String, Object>> stash) {
        try {
            java.util.Map<String, Object> meta = TraceStore.getAll();
            if (stash != null && meta != null && !meta.isEmpty()) {
                stash.set(meta);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 종료 시점의 live 트레이스와 보관된 스냅샷을 합친다(동일 키는 live 우선). */
    private static java.util.Map<String, Object> mergeStreamTraceMeta(
            java.util.Map<String, Object> stashed) {
        java.util.Map<String, Object> live = TraceStore.getAll();
        if (stashed == null || stashed.isEmpty()) {
            return live;
        }
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>(stashed);
        if (live != null) {
            merged.putAll(live);
        }
        return merged;
    }

    private void emitChatSessionTrace(AtomicBoolean once, String surface, Long sessionId,
            ChatRunExecutionContext run, String requestedModel, String effectiveModel,
            Boolean ragEnabled, String outcome, java.util.Map<String, Object> meta) {
        try {
            if (chatSessionTraceRecorder == null || once == null || !once.compareAndSet(false, true)) {
                return;
            }
            chatSessionTraceRecorder.recordTerminal(surface, sessionId,
                    run == null ? null : run.clientToken(), requestedModel, effectiveModel,
                    ragEnabled, outcome, meta);
        } catch (Throwable ignore) {
            logSuppressed("chat.sessionTrace.emit");
        }
    }

    private static boolean isVisibleLocalModelSuccess(String answer, String modelUsed) {
        if (answer == null || answer.isBlank() || modelUsed == null || modelUsed.isBlank()) {
            return false;
        }
        String lower = modelUsed.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fallback")
                || lower.contains("cancel")
                || lower.contains("error")
                || lower.contains("fail")
                || lower.contains("embedding")
                || ModelCapabilities.isRemoteLookingModelId(lower)) {
            return false;
        }
        boolean qwenFamily = lower.contains("qwen");
        boolean gemmaFamily = lower.contains("gemma");
        boolean directOllamaTag = (qwenFamily || gemmaFamily)
                && (lower.startsWith("qwen") || lower.startsWith("gemma"))
                && lower.contains(":")
                && !lower.contains("/");
        return lower.contains("ollama") || directOllamaTag;
    }

    private static void clearLocalLlmOperatorActionAfterVisibleSuccess(
            java.util.Map<String, Object> meta,
            String answer,
            String modelUsed) {
        if (meta == null
                || !isVisibleLocalModelSuccess(answer, modelUsed)
                || hasObservedRequestFallback(meta)) {
            return;
        }
        putMetaAndTrace(meta, "llm.localSmoke.operatorAction.triggered", Boolean.FALSE);
        putMetaAndTrace(meta, "llm.localSmoke.operatorAction.failureClass", "none");
        putMetaAndTrace(meta, "llm.localSmoke.operatorAction.nextAction", "none");
        putMetaAndTrace(meta, "llm.localSmoke.operatorAction.actionScore", 0);
        putMetaAndTrace(meta, "llm.localSmoke.operatorAction.triggerReason", "native_success");
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.localLlm.operatorAction.triggered", Boolean.FALSE);
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.localLlm.operatorAction.failureClass", "none");
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.localLlm.operatorAction.nextAction", "none");
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.localLlm.operatorAction.actionScore", 0);
        putMetaAndTrace(meta, "prompt.agentDebugEvidence.localLlm.operatorAction.triggerReason", "native_success");
    }

    private static boolean hasObservedRequestFallback(java.util.Map<String, Object> meta) {
        Object value = meta.get(ChatStreamSignalBuilder.REQUEST_ATTEMPT_SUMMARY_KEY);
        if (!(value instanceof java.util.Collection<?> rows)) {
            return false;
        }
        int inspected = 0;
        for (Object candidate : rows) {
            if (inspected++ >= 8) {
                break;
            }
            if (candidate instanceof java.util.Map<?, ?> row
                    && Boolean.TRUE.equals(row.get("trusted"))
                    && Boolean.TRUE.equals(row.get("attemptObserved"))
                    && "fallback".equals(String.valueOf(row.get("lane")))) {
                return true;
            }
        }
        return false;
    }

    private static void mirrorTracePair(java.util.Map<String, Object> meta, String promptKey, String mirrorKey) {
        Object value = firstNonNullValue(meta.get(promptKey), TraceStore.get(promptKey), meta.get(mirrorKey), TraceStore.get(mirrorKey));
        putMetaAndTrace(meta, promptKey, value);
        putMetaAndTrace(meta, mirrorKey, value);
    }

    private static void putMetaAndTrace(java.util.Map<String, Object> meta, String key, Object value) {
        if (meta == null || key == null || value == null) {
            return;
        }
        Object safe = safeDebugMetaValue(key, value);
        if (safe == null) {
            return;
        }
        meta.put(key, safe);
        try {
            TraceStore.put(key, safe);
        } catch (Exception ignore) {
            logSuppressed(key);
        }
    }

    private static Object safeDebugMetaValue(String key, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String safe = SafeRedactor.traceLabelOrFallback(value, null);
        if (safe == null || safe.isBlank()) {
            return null;
        }
        return safe;
    }

    private static Object firstNonNullValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static java.util.Map<String, Object> mapValue(Object value) {
        if (!(value instanceof java.util.Map<?, ?> map)) {
            return java.util.Map.of();
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry != null && entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static java.util.Map<String, Object> firstMap(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return java.util.Map.of();
        }
        for (Object item : iterable) {
            if (item instanceof java.util.Map<?, ?> map) {
                return mapValue(map);
            }
        }
        return java.util.Map.of();
    }

    private static void emitDefaultModelWaitStatus(
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
            com.abandonware.ai.addons.budget.TimeBudget budget,
            long streamStartedNs) {
        if (sink == null) {
            return;
        }
        try {
            Long remainingMs = budget == null ? null : budget.remainingMillis();
            long tookMs = Math.max(0L, (System.nanoTime() - streamStartedNs) / 1_000_000L);
            ChatStreamEvent.StatusSignal waitSignal = ChatStreamEvent.StatusSignal.of(
                    "llm",
                    DEFAULT_MODEL_WAIT_STATUS_CODE,
                    DEFAULT_MODEL_WAIT_STATUS_MESSAGE,
                    remainingMs,
                    tookMs,
                    false);
            TraceStore.put("chat.stream.defaultModel.waitStatus", true);
            TraceStore.put("chat.stream.defaultModel.waitStatus.code", DEFAULT_MODEL_WAIT_STATUS_CODE);
            java.util.Map<String, Object> waitTransformerMeta = java.util.Map.of(
                    "llm.defaultModel.waitStatus", true,
                    "llm.defaultModel.waitStatus.code", DEFAULT_MODEL_WAIT_STATUS_CODE);
            sink.tryEmitNext(sse(ChatStreamEvent.status(waitSignal)));
            sink.tryEmitNext(sse(ChatStreamEvent.thought(DEFAULT_MODEL_WAIT_STATUS_MESSAGE)));
            sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                    ChatStreamSignalBuilder.buildTransformerBlocks(
                            waitTransformerMeta, waitSignal, null, null, null, null))));
        } catch (Throwable ignore) {
            logSuppressed("stream.status.defaultModelWait");
        }
    }

    private static void emitStreamQueryRewriteTransformer(
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
            java.util.concurrent.atomic.AtomicBoolean emitted,
            String traceId,
            String requestId,
            String sessionId) {
        if (sink == null || emitted == null) {
            return;
        }
        try {
            java.util.Map<String, Object> meta = TraceStore.getAll();
            ChatStreamEvent.TraceSignal traceSignal =
                    ChatStreamSignalBuilder.buildTraceSignal(meta, traceId, requestId, sessionId);
            ChatStreamEvent.PipelineSnapshot pipelineSnapshot =
                    ChatStreamSignalBuilder.buildPipelineSnapshot(meta, null, null, traceSignal);
            java.util.List<ChatStreamEvent.TransformerBlockSignal> blocks =
                    ChatStreamSignalBuilder.buildTransformerBlocks(
                            meta,
                            null,
                            pipelineSnapshot,
                            traceSignal,
                            null,
                            null);
            boolean hasRewrite = blocks.stream().anyMatch(block -> "rewrite".equals(block.id()));
            if (!hasRewrite || !emitted.compareAndSet(false, true)) {
                return;
            }
            sink.tryEmitNext(sse(ChatStreamEvent.transformer(blocks)));
        } catch (Exception ignore) {
            logSuppressed("stream.queryRewriteTransformer");
        }
    }

    private static void emitStreamCancelledStatus(
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
            com.abandonware.ai.addons.budget.TimeBudget budget,
            long streamStartedNs) {
        if (sink == null) {
            return;
        }
        for (ServerSentEvent<ChatStreamEvent> event : streamCancelledEvents(budget, streamStartedNs)) {
            sink.tryEmitNext(event);
        }
    }

    private static List<ServerSentEvent<ChatStreamEvent>> streamCancelledEvents(
            com.abandonware.ai.addons.budget.TimeBudget budget,
            long streamStartedNs) {
        try {
            Long remainingMs = budget == null ? null : budget.remainingMillis();
            long tookMs = Math.max(0L, (System.nanoTime() - streamStartedNs) / 1_000_000L);
            ChatStreamEvent.StatusSignal cancelSignal = ChatStreamEvent.StatusSignal.of(
                    "stream",
                    "cancelled",
                    "stream cancelled",
                    remainingMs,
                    tookMs,
                    true);
            java.util.Map<String, Object> cancelMeta = java.util.Map.of(
                    "chat.stream.finalSuppressedAfterCancel", true,
                    "chat.stream.cancelReason", "run_registry_cancelled");
            return List.of(
                    sse(ChatStreamEvent.status(cancelSignal)),
                    sse(ChatStreamEvent.transformer(
                            ChatStreamSignalBuilder.buildTransformerBlocks(
                                    cancelMeta, cancelSignal, null, null, null, null))));
        } catch (Throwable ignore) {
            logSuppressed("stream.status.cancelledBeforeFinal");
            return List.of();
        }
    }

    private static boolean isStreamCancellation(
            Throwable error,
            ChatRunExecutionContext runContext) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof java.util.concurrent.CancellationException) {
                return runContext != null && runContext.isCancellationRequested();
            }
            if (cursor instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("connection reset")
                        || normalized.contains("broken pipe")
                        || normalized.contains("asynccontext after an error")
                        || normalized.contains("client disconnected")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static List<String> chunk(String s, int size) {
        if (s == null)
            return List.of();
        int n = Math.max(1, size);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += n) {
            out.add(s.substring(i, Math.min(s.length(), i + n)));
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * Header-mode resolution for plan selection. Explicit {@code X-Jammini-Mode}
     * wins; the documented {@code X-Brave-Mode: on} switch (see plans/brave.v1.yaml
     * {@code when: request.header.X-Brave-Mode == "on"}) maps to the same "brave"
     * lane so the advertised header is not silently inert.
     */
    static String resolveJamminiMode(String jamminiModeHeader, String braveModeHeader) {
        if (jamminiModeHeader != null && !jamminiModeHeader.isBlank()) {
            return jamminiModeHeader;
        }
        if ("on".equalsIgnoreCase(braveModeHeader == null ? null : braveModeHeader.trim())) {
            return "brave";
        }
        return jamminiModeHeader;
    }

    private static void applyRequestSessionId(ChatRequestDto req, String sessionIdHeader, String conversationIdHeader) {
        if (req == null || req.getSessionId() != null) {
            return;
        }
        Long resolved = normalizeChatSessionId(firstNonBlank(sessionIdHeader, conversationIdHeader));
        if (resolved != null) {
            req.setSessionId(resolved);
        }
    }

    private static Long normalizeChatSessionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "chat-", 0, 5)) {
            value = value.substring(5).trim();
        }
        if (!value.matches("\\d+")) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignore) {
            logSuppressed("parse.normalizeChatSessionId");
            return null;
        }
    }

    private static void traceSessionNotFound(Long sessionId) {
        try {
            TraceStore.put("memory.rehydrate.reason", "session_not_found");
            if (sessionId != null) {
                TraceStore.put("memory.rehydrate.sessionHash", SafeRedactor.hash12(String.valueOf(sessionId)));
            }
        } catch (Throwable ignore) {
            logSuppressed("memory.rehydrate.traceSessionNotFound");
        }
    }

    private void updateRollingSummaryAndMaybePromote(
            Long sessionId,
            Long assistantMessageId,
            ChatRequestDto request) {
        if (sessionId == null) {
            return;
        }
        String previousPromotionHash = "";
        try {
            previousPromotionHash = historyService.getConversationMemorySnapshot(sessionId).promotionHash();
        } catch (Exception ignore) {
            logSuppressed("memory.rollingSummary.previousSnapshot");
        }
        try {
            historyService.updateRollingSummary(sessionId, assistantMessageId);
        } catch (Exception ignore) {
            logSuppressed("memory.rollingSummary.update");
            traceShadowVectorQueued(false);
            return;
        }
        boolean queued = false;
        try {
            ChatHistoryService.ConversationMemorySnapshot snapshot =
                    historyService.getConversationMemorySnapshot(sessionId);
            boolean promoted = snapshot.promoted();
            String nextPromotionHash = snapshot.promotionHash() == null ? "" : snapshot.promotionHash();
            boolean changed = promoted && !nextPromotionHash.isBlank()
                    && !java.util.Objects.equals(previousPromotionHash, nextPromotionHash);
            com.example.lms.domain.enums.MemoryMode memoryMode =
                    com.example.lms.domain.enums.MemoryMode.fromString(request == null ? null : request.getMemoryMode());
            if (sessionSummaryShadowVectorEnabled
                    && changed
                    && memoryMode != com.example.lms.domain.enums.MemoryMode.EPHEMERAL
                    && memoryReinforcementService != null
                    && snapshot.hasMemory()) {
                String sessionKey = String.format("chat-%s", sessionId);
                queued = memoryReinforcementService.reinforceSessionSummaryShadow(
                        sessionKey,
                        snapshot.shadowSnippet(),
                        nextPromotionHash,
                        sessionSummaryShadowVectorScore);
            }
        } catch (Exception ignore) {
            logSuppressed("memory.rollingSummary.shadowVector");
            queued = false;
        }
        traceShadowVectorQueued(queued);
    }

    private static void traceShadowVectorQueued(boolean queued) {
        try {
            TraceStore.put("memory.session.shadowVectorQueued", queued);
        } catch (Throwable ignore) {
            logSuppressed("memory.shadowVectorQueued.trace");
        }
    }

    // ===== internal =====
    /**
     * Overload that wires Jammini / guard headers into a GuardContext and exposes
     * it
     * via {@link GuardContextHolder} for downstream services (RAG, search, memory).
     */
    private ChatResponseDto handleChat(ChatRequestDto uiReq,
            String username,
            String clientIp,
            String preResolvedOwnerKey,
            String jamminiMode,
            String guardLevel,
            String applicationRequestId,
            String surface) {
        return handleChat(
                uiReq,
                username,
                clientIp,
                preResolvedOwnerKey,
                jamminiMode,
                guardLevel,
                applicationRequestId,
                SelectionReplayRequestResolver.Resolved.standard(),
                surface);
    }

    private ChatResponseDto handleChat(ChatRequestDto uiReq,
            String username,
            String clientIp,
            String preResolvedOwnerKey,
            String jamminiMode,
            String guardLevel,
            String applicationRequestId,
            SelectionReplayRequestResolver.Resolved selectionState,
            String surface) {
        bindAttachmentOwnerIfPresent(uiReq, username, preResolvedOwnerKey);
        SelectionEntropy selectionEntropy =
                Objects.requireNonNull(selectionState, "selectionState").entropy();
        SelectionDecisionLedger selectionDecisionLedger = selectionState.ledger();
        GuardContext ctx = GuardContext.defaultContext();
        attachSelectionState(
                ctx,
                selectionEntropy,
                selectionDecisionLedger);
        if (jamminiMode != null && !jamminiMode.isBlank()) {
            ctx.setHeaderMode(jamminiMode);
            ctx.setMode(jamminiMode);
            // Simple plan mapping; can be refined to safe_autorun.v1 / brave.v1 etc.
            ctx.setPlanId(jamminiMode);
            if ("S1".equalsIgnoreCase(jamminiMode) || "safe".equalsIgnoreCase(jamminiMode)) {
                ctx.setMemoryProfile("MEMORY");
            } else if ("S2".equalsIgnoreCase(jamminiMode)
                    || "brave".equalsIgnoreCase(jamminiMode)
                    || "free".equalsIgnoreCase(jamminiMode)
                    || "zero_break".equalsIgnoreCase(jamminiMode)) {
                ctx.setMemoryProfile("NONE");
            }
        }
        if (guardLevel != null && !guardLevel.isBlank()) {
            ctx.setGuardLevel(guardLevel);
        }
        if (uiReq != null && uiReq.getMessage() != null) {
            ctx.setEntityQueryFromQuestion(uiReq.getMessage());
			// UAW: propagate raw user query for downstream orchestration/unmasking/autolearn hooks
			ctx.setUserQuery(uiReq.getMessage());
        }
        try {
            sensitiveTopicDetector.applyTo(ctx, uiReq);
        } catch (Exception ignore) {
            logSuppressed("sensitiveTopic.apply");
        }

        GuardContextHolder.set(ctx);
        try {
            writeSelectionEntropyProjection(
                    selectionEntropy, selectionDecisionLedger, false);
            return handleChat(
                    uiReq,
                    username,
                    clientIp,
                    preResolvedOwnerKey,
                    applicationRequestId,
                    selectionEntropy,
                    selectionDecisionLedger,
                    surface);
        } finally {
            GuardContextHolder.clear();
        }
    }

    private ChatResponseDto handleChat(ChatRequestDto uiReq, String username, String clientIp,
            String preResolvedOwnerKey, String applicationRequestId,
            SelectionEntropy selectionEntropy,
            SelectionDecisionLedger selectionDecisionLedger,
            String surface) {
        com.example.lms.debug.DebugEventTracePromotionService.seedRequestedExternalEvidenceLanes(
                uiReq == null ? null : uiReq.getMessage());
        // 0) ?熬곣뫚????쒖굣???LLM ?筌뤾쑵????怨몄쓧???브퀗?쀧뵳???얜Ŧ堉?嶺뚳퐣瑗???類ｋ펲. ?띠룆흮????롪퍔???
        // consent, last location and reverse geocoding are evaluated via
        // LocationService.
        try {
            com.example.lms.location.intent.LocationIntent intent = locationService.detectIntent(uiReq.getMessage());
            if (intent == com.example.lms.location.intent.LocationIntent.WHERE_AM_I) {
                // Resolve the user identifier for the location lookup. Prefer the
                // authenticated principal's username (passed as 'username') and fall back
                // to any identifier encoded in the request if such a property exists.
                String userId = (username != null && !username.isBlank()) ? username : null;
                var msgOpt = locationService.answerWhereAmI(userId);
                if (msgOpt.isPresent()) {
                    // Immediate deterministic response; avoid session creation and web search.
                    return new ChatResponseDto(msgOpt.get(), null, "location:deterministic", false);
                }
                // When the personalised location message cannot be produced (no consent,
                // missing coordinate etc.), continue to the standard flow below.
            }
        } catch (Exception e) {
            // Log but do not interrupt the standard chat flow
            log.debug("handleChat: location interception failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
        }

        // 1) ???깆젧 ?곌랜理묌뜮?
        ChatRequestDto dto = mergeWithSettings(uiReq);
        publicRequestBudgetGuard.validateChatEffective(dto);
        final boolean __hasAttachments = hasAttachments(dto);
        final boolean __looksLikeAttachmentQ =
                ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(dto.getMessage());

        // DROP: apply plan selection + guard hints BEFORE web prefetch/search.
        PlanHints __planHints = null;
        boolean __allowWebCap = true;
        boolean __allowRagCap = true;
        try {
            GuardContext __gctx = GuardContextHolder.get();
            if (__gctx != null) {
                AnswerMode __am = AnswerMode.fromString(dto.getMode());
                QueryDomain __qd = (__gctx.isSensitiveTopic()) ? QueryDomain.SENSITIVE : QueryDomain.GENERAL;
                if (workflowOrchestrator != null) {
                    workflowOrchestrator.ensurePlanSelected(__gctx, __am, __qd, dto.getMessage(), __hasAttachments);
                }
                if (planHintApplier != null && __gctx.getPlanId() != null) {
                    __planHints = planHintApplier.load(__gctx.getPlanId());
                    planHintApplier.applyToGuardContext(__planHints, __gctx);
                }
            }
            __allowWebCap = (__planHints == null || __planHints.allowWeb() != Boolean.FALSE);
            __allowRagCap = (__planHints == null || __planHints.allowRag() != Boolean.FALSE);
            TraceStore.put("plan.id.preSearch", (__gctx == null ? null : __gctx.getPlanId()));
            TraceStore.put("plan.allowWeb.cap", __allowWebCap);
            TraceStore.put("plan.allowRag.cap", __allowRagCap);
        } catch (Exception ignorePlan) {
            logSuppressed("plan.preSearch");
        }

        // === 嶺뚳퐘維? ???쳜????덈콦 ?낅슣??????獄??????吏?OFF ===
        // Build a composed message by injecting attachment contents before the user
        // question. When
        // the user specifically references the uploaded file(s) the web search flag is
        // forced
        // off. Use dynamic limits from settings with sensible fallbacks for document
        // count,
        // bytes and character thresholds.
        // attachments.inline.legacy-prepend-enabled is deprecated/no-op. Attachment evidence
        // flows through ChatWorkflow -> PromptContext.localDocs -> PromptBuilder.build(ctx).
        boolean __reqUseWeb = Boolean.TRUE.equals(dto.isUseWebSearch());
        final boolean __directDebugAnswer =
                AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(dto.getMessage());
        final boolean __directLiteralAnswer =
                ChatWorkflow.isDirectLiteralAnswerRequest(dto.getMessage());
        final boolean __directUiModeStatusAnswer =
                ChatWorkflow.isCurrentModeStatusRequest(dto.getMessage());
        final boolean __directSupabaseOperationalJudgment =
                ChatWorkflow.isSupabaseOperationalJudgmentRequest(dto.getMessage());
        boolean __finalUseWeb = (__hasAttachments && __looksLikeAttachmentQ) ? false : __reqUseWeb;
        __finalUseWeb = __finalUseWeb && !__directDebugAnswer;
        __finalUseWeb = __finalUseWeb && !__directLiteralAnswer;
        __finalUseWeb = __finalUseWeb && !__directUiModeStatusAnswer;
        __finalUseWeb = __finalUseWeb && !__directSupabaseOperationalJudgment;
        // Plan cap: allowWeb/allowRag
        __finalUseWeb = __finalUseWeb && __allowWebCap;
        final boolean __finalUseRag = __allowRagCap
                && Boolean.TRUE.equals(dto.isUseRag())
                && !__directDebugAnswer
                && !__directLiteralAnswer
                && !__directUiModeStatusAnswer
                && !__directSupabaseOperationalJudgment;
        final boolean __workflowUseWeb = __directUiModeStatusAnswer ? __reqUseWeb : __finalUseWeb;
        final boolean __workflowUseRag = __directUiModeStatusAnswer ? Boolean.TRUE.equals(dto.isUseRag()) : __finalUseRag;

        GuardContext __budgetContext = GuardContextHolder.get();
        publicRequestBudgetGuard.validateChatProjected(
                dto,
                __planHints,
                __budgetContext == null ? null : __budgetContext.getPlanId(),
                __workflowUseWeb,
                __workflowUseRag);

        // 2) ?筌뤾쑬??upsert
        boolean sessionCreated = uiReq.getSessionId() == null;
        ChatSession session = (uiReq.getSessionId() == null)
                ? historyService
                        .startNewSession(dto.getMessage(), username, clientIp, preResolvedOwnerKey,
                                dto.getMemoryProfile())
                        .orElseThrow(() -> new IllegalStateException("?筌뤾쑬????諛댁뎽 ???덉넮"))
                : historyService.getSessionWithMessages(uiReq.getSessionId());

        // [PATCH] ??μ쪠???筌뤾쑬???꾩렮維쀥젆??β돦裕뉐퐲??怨뺣뼺?
        if (session == null && uiReq.getSessionId() != null) {
            traceSessionNotFound(uiReq.getSessionId());
            log.warn("Requested session was not found; creating a new session. sessionHash={}",
                    SafeRedactor.hash12(String.valueOf(uiReq.getSessionId())));
            session = historyService
                    .startNewSession(dto.getMessage(), username, clientIp, preResolvedOwnerKey, dto.getMemoryProfile())
                    .orElseThrow(() -> new IllegalStateException("?筌뤾쑬???곌랜踰????諛댁뎽 ???덉넮"));
            sessionCreated = true;
        }


        ChatRunRegistry.BeginResult syncStarted = runRegistry.beginOrJoin(session.getId());
        if (!syncStarted.owner()) {
            throw new java.util.concurrent.CancellationException("run_active");
        }
        ChatRunExecutionContext syncRun = syncStarted.context();
        // 세션 디버그 증거: 어느 종료 경로든 한 번만 기록한다.
        final AtomicBoolean syncTraceOnce = new AtomicBoolean(false);
        final AtomicReference<java.util.Map<String, Object>> syncTraceMetaRef = new AtomicReference<>();
        final AtomicReference<String> syncModelRef = new AtomicReference<>();
        final AtomicReference<String> syncOutcomeRef = new AtomicReference<>();
        try (ChatRunExecutionContext.Scope ignoredRunScope = ChatRunExecutionContext.bind(syncRun)) {
        // [PATCH] Ensure MDC/TraceStore session breadcrumbs follow the resolved chat session.
        try {
            if (session != null && session.getId() != null) {
                String __s = String.valueOf(session.getId());
                String __sessionKey = __s.startsWith("chat-") ? __s : (__s.matches("\\d+") ? String.format("chat-%s", __s) : __s);
                try {
                    org.slf4j.MDC.put("sid", __sessionKey);
                    org.slf4j.MDC.put("sessionId", __sessionKey);
                } catch (Throwable ignoreMdc) {
                    logSuppressed("sync.sessionBreadcrumb.mdc");
                }
                try {
                    com.example.lms.search.TraceStore.put("sid", SafeRedactor.hashValue(__sessionKey));
                } catch (Throwable ignoreTrace) {
                    logSuppressed("sync.sessionBreadcrumb.trace");
                }
            }
        } catch (Exception ignore) {
            logSuppressed("sync.sessionBreadcrumb.outer");
        }
        // [Jammini Memory Hook] session metadata merge/persist
        java.util.Map<String, Object> sessionMeta = mergeSessionMetaIntoRequest(session, uiReq);
        try {
            session.setSessionMeta(objectMapper.writeValueAsString(sessionMeta));
            historyService.updateSessionMeta(session.getId(), sessionMeta);
        } catch (Exception e) {
            log.warn("Failed to persist session_meta for session {}: {}",
                    SafeRedactor.hashValue(String.valueOf(session.getId())), errorSummary(e));
        }

        // Bind pre-session uploads before shared sync/normal generation and later session lookup.
        try {
            if (sessionCreated
                    && uiReq.getAttachmentIds() != null
                    && !uiReq.getAttachmentIds().isEmpty()
                    && session != null && session.getId() != null) {
                attachmentService.attachToSession(
                        String.valueOf(session.getId()),
                        uiReq.getAttachmentIds(),
                        AttachmentOwnerIdentity.forActor(username, preResolvedOwnerKey));
            }
        } catch (Exception ex) {
            log.debug("Failed to attach uploaded files to new session: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
        }

        // Session creation already stored the first user turn.
        if (session != null && !sessionCreated) {
            historyService.appendMessage(session.getId(), "user", dto.getMessage());
        }

        // 3) ???롪틵???        // ?롪틵???嶺뚮ㅄ維獄??ChatRequestDto.searchMode????臾먰돵 ??戮?꽑??類ｋ펲. OFF??????롪틵???源녿굵 濾곌쑬????⑤슦??
        // FORCE_LIGHT/DEEP??怨뺤┣ 嶺뚣끉裕뉏펺?useWebSearch?띠럾? false??????롪틵???源녿굵 濾곌쑬??????? AUTO 嶺뚮ㅄ維獄??????        // __finalUseWeb???잙갭梨????????類ｋ펲. topK??webTopK ?熬곣뫀援????臾먰돵 嶺뚯솘??筌먲퐢彛??
        int topKParam = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
        com.example.lms.gptsearch.dto.SearchMode sm = dto.getSearchMode();
        if (sm == null)
            sm = com.example.lms.gptsearch.dto.SearchMode.AUTO;
        final com.example.lms.gptsearch.dto.SearchMode effectiveSearchMode =
                effectiveSearchMode(dto.getMessage(), sm);
        boolean performSearch = shouldUseWebForSearchMode(
                dto.getMessage(), effectiveSearchMode, __finalUseWeb, __finalUseRag, searchDecisionService, topKParam);
        GuardContext __preSearchCtx = GuardContextHolder.get();
        markCheapSearchMode(__preSearchCtx, effectiveSearchMode, "sync.preSearch");
        final String __providerSearchQuery = providerSearchQuery(dto.getMessage());
        if (performSearch) {
            recordSearchModeRewriteHint(effectiveSearchMode);
        }
        NaverSearchService.SearchResult sr = performSearch
                ? webSearchProvider.searchWithTrace(__providerSearchQuery, topKParam)
                : new NaverSearchService.SearchResult(List.of(), null);
        if (performSearch && sr != null) {
            List<String> rawSnips = prioritizeDomainEvidenceSnippets(dto.getMessage(), sr.snippets());
            rawSnips = completeNamedOfficialCoverageSnippets(
                    dto.getMessage(),
                    rawSnips,
                    rescueQuery -> {
                        NaverSearchService.SearchResult rescueSr =
                                webSearchProvider.searchWithTrace(rescueQuery, topKParam);
                        return rescueSr == null || rescueSr.snippets() == null
                                ? java.util.Collections.emptyList()
                                : rescueSr.snippets();
                    });
            String __domainFallbackQuery = domainEvidenceFallbackSearchQuery(
                    dto.getMessage(), __providerSearchQuery, rawSnips);
            if (__domainFallbackQuery != null) {
                NaverSearchService.SearchResult fallbackSr =
                        webSearchProvider.searchWithTrace(__domainFallbackQuery, topKParam);
                if (fallbackSr != null && fallbackSr.snippets() != null && !fallbackSr.snippets().isEmpty()) {
                    List<String> acceptedFallback = acceptDomainEvidenceFallbackSnippets(
                            dto.getMessage(),
                            prioritizeDomainEvidenceSnippets(dto.getMessage(), fallbackSr.snippets()),
                            rawSnips);
                    if (!acceptedFallback.equals(rawSnips)) {
                        sr = fallbackSr;
                        rawSnips = acceptedFallback;
                    } else {
                        rawSnips = acceptedFallback;
                    }
                }
            }
            rawSnips = completeNamedOfficialCoverageSnippets(
                    dto.getMessage(),
                    rawSnips,
                    rescueQuery -> {
                        NaverSearchService.SearchResult rescueSr =
                                webSearchProvider.searchWithTrace(rescueQuery, topKParam);
                        return rescueSr == null || rescueSr.snippets() == null
                                ? java.util.Collections.emptyList()
                                : rescueSr.snippets();
                    });
            sr = new NaverSearchService.SearchResult(
                    rawSnips,
                    sr.trace());
        }
        recordWebPrefetchTrace(
                "sync",
                __reqUseWeb,
                performSearch,
                __workflowUseRag,
                effectiveSearchMode,
                topKParam,
                __providerSearchQuery,
                sr == null ? List.of() : sr.snippets());

        // 4) LLM ?筌뤾쑵??
        final boolean __workflowUseWebForCall = __directUiModeStatusAnswer ? __workflowUseWeb : performSearch;
        ChatRequestDto dtoForCall = dto.toBuilder()
                .sessionId(session.getId())
                .useRag(__workflowUseRag)
                // Override useWebSearch with the final value after attachment heuristic + plan cap
                .useWebSearch(__workflowUseWebForCall)
                .searchMode(effectiveSearchMode)
                .build();

        // ChatWorkflow may change guard flags (officialOnly/domainProfile/minCitations) after this controller
        // prefetches web snippets. In that case, re-run web search lazily.
        final NaverSearchService.SearchResult __srFinal = sr;
        final java.util.List<String> __prefetched = (__srFinal == null ? java.util.List.of() : __srFinal.snippets());

        GuardContext __prefetchCtx;
        try {
            __prefetchCtx = GuardContextHolder.get();
        } catch (Exception ignore) {
            __prefetchCtx = null;
            logSuppressed("sync.prefetchGuardContext");
        }
        final boolean __prefetchOfficial = __prefetchCtx != null && __prefetchCtx.isOfficialOnly();
        final String __prefetchDomainProfile = (__prefetchCtx == null ? null : __prefetchCtx.getDomainProfile());
        final Integer __prefetchMinCitations = (__prefetchCtx == null ? null : __prefetchCtx.getMinCitations());

        java.util.function.Function<String, java.util.List<String>> __webSupplier = (q) -> {
            GuardContext __ctx;
            try {
                __ctx = GuardContextHolder.get();
            } catch (Exception ignore) {
                __ctx = null;
                logSuppressed("sync.webSupplierGuardContext");
            }
            boolean __nowOfficial = (__ctx != null) ? __ctx.isOfficialOnly() : __prefetchOfficial;
            String __nowDomainProfile = (__ctx != null) ? __ctx.getDomainProfile() : __prefetchDomainProfile;
            Integer __nowMinCitations = (__ctx != null) ? __ctx.getMinCitations() : __prefetchMinCitations;
            String __requestedQuery = q == null || q.isBlank() ? dto.getMessage() : q;
            String __requestedProviderQuery = providerSearchQuery(__requestedQuery);
            boolean __prefetchQueryMatches = prefetchQueryIdentityMatches(
                    __providerSearchQuery, __requestedProviderQuery);
            boolean __prefetchScopeMatches = __nowOfficial == __prefetchOfficial
                    && java.util.Objects.equals(__nowDomainProfile, __prefetchDomainProfile)
                    && java.util.Objects.equals(__nowMinCitations, __prefetchMinCitations);

            if (__prefetchQueryMatches && __prefetchScopeMatches) {
                recordPrefetchIdentityDecision(
                        __providerSearchQuery, __requestedProviderQuery, true, true, "reuse");
                return __prefetched;
            }
            recordPrefetchIdentityDecision(
                    __providerSearchQuery,
                    __requestedProviderQuery,
                    __prefetchQueryMatches,
                    __prefetchScopeMatches,
                    "fresh_search");
            tracePut("chatApi.web.prefetch.invalidated", true);

            try {
                String providerQuery = __requestedProviderQuery;
                List<String> snippets = prioritizeDomainEvidenceSnippets(
                        __requestedQuery,
                        webSearchProvider.search(providerQuery, topKParam));
                snippets = completeNamedOfficialCoverageSnippets(
                        __requestedQuery,
                        snippets,
                        rescueQuery -> webSearchProvider.search(rescueQuery, topKParam));
                String fallbackQuery = domainEvidenceFallbackSearchQuery(
                        __requestedQuery, providerQuery, snippets);
                snippets = fallbackQuery == null
                        ? snippets
                        : acceptDomainEvidenceFallbackSnippets(
                                __requestedQuery,
                                prioritizeDomainEvidenceSnippets(
                                        __requestedQuery,
                                        webSearchProvider.search(fallbackQuery, topKParam)),
                                snippets);
                snippets = completeNamedOfficialCoverageSnippets(
                        __requestedQuery,
                        snippets,
                        rescueQuery -> webSearchProvider.search(rescueQuery, topKParam));
                recordWebPrefetchTrace(
                        "sync.retry",
                        true,
                        true,
                        __workflowUseRag,
                        effectiveSearchMode,
                        topKParam,
                        providerQuery,
                        snippets);
                return snippets == null ? java.util.List.of() : snippets;
            } catch (Exception e) {
                log.warn("[webSupplier] re-search failed; returning fail-soft empty: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                return java.util.List.of();
            }
        };

        // ChatResult is a top-level record (extracted from ChatService).
        try {
            debugCopilotService.maybeEnrichTrace();
            StageBoundaryBreadcrumbs.recordFromCurrentTrace("pre_llm");
            java.util.Map<String, Object> preLlmMeta = TraceStore.getAll();
            promoteDebugEvents("pre_llm", preLlmMeta, "ChatApiController.sync.preLlm");
        } catch (Exception ignore) {
            logSuppressed("sync.preLlmDebugEvent");
        }
        String requestTimelineId = beginModelRequestTimeline(
                applicationRequestId,
                session == null || session.getId() == null ? null : String.valueOf(session.getId()),
                dtoForCall.getModel());
        try {
        if (syncRun.isCancellationRequested()) {
            throw new java.util.concurrent.CancellationException("request_cancelled");
        }
        ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);
        String semanticFinalContent = result.content();
        semanticFinalContent = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                TraceStore.getAll(), dtoForCall.getMessage(), semanticFinalContent);
        RagControlPresentationBoundary.Projection syncRagControlProjection = projectRagControlForUser(
                semanticFinalContent, __workflowUseWebForCall || __workflowUseRag);
        String visibleFinalContent = syncRagControlProjection.visibleAnswer();
        String persistableFinalContent = syncRagControlProjection.persistableAnswer();
        try {
            debugCopilotService.maybeEnrichTrace();
        } catch (Exception ignore) {
            logSuppressed("sync.finalDebugCopilot");
        }
        StageBoundaryBreadcrumbs.recordFromCurrentTrace("final");

        if (!syncRun.markGenerationSucceeded() || !syncRun.tryBeginTranscriptCommit()) {
            throw new java.util.concurrent.CancellationException("request_cancelled");
        }
        final ChatSession completedSession = session;
        final boolean completedUseWeb = __finalUseWeb;
        java.util.concurrent.atomic.AtomicReference<ChatResponseDto> completedResponse =
                new java.util.concurrent.atomic.AtomicReference<>();
        boolean durableAccepted = syncRun.runTerminalSideEffect(() -> {
        // 5) Persist assistant turn.
        Long assistantMessageId = historyService.appendMessageReturningId(
                completedSession.getId(), "assistant", persistableFinalContent);
        boolean syncPersistenceAccepted = assistantMessageId != null;
        if (syncPersistenceAccepted) {
            syncRun.markPersisted();
        }

        String modelUsedFinal = ChatModelMetaSupport.resolveModelUsed(result.modelUsed(), dto.getModel(), FALLBACK_MODEL);
        syncModelRef.set(modelUsedFinal);
        if (!syncRagControlProjection.held()) {
            updateRollingSummaryAndMaybePromote(completedSession.getId(), assistantMessageId, dtoForCall);
        }

        historyService.appendMessage(completedSession.getId(), "system",
                String.format("%s%s", MODEL_META_PREFIX, modelUsedFinal));

        // Pull the evidence sets captured by ChatWorkflow so that the saved trace
        // panel can show "raw web snippets" and "final context" separately.
        // Preserve "enabled" signal: null means disabled, empty list means enabled but
        // no results.
        java.util.Map<String, Object> extraMeta = java.util.Collections.emptyMap();
        String answerModeFinal = null;
        LearningContextMetadata learningContextMeta = LearningContextMetadata.empty();
        java.util.List<Content> finalWebTopK = null;
        java.util.List<Content> finalVectorTopK = null;
        SelectionEntropyProjection syncSelectionEntropy = null;
        try {
            if (selectionEntropy.mode() == SelectionEntropyMode.REPLAY) {
                syncSelectionEntropy = writeSelectionEntropyProjection(
                        selectionEntropy, selectionDecisionLedger, true);
            }
            extraMeta = TraceStore.getAll();
            syncTraceMetaRef.set(extraMeta);
            attachModelRequestAttemptSummary(modelRuntimeHealthTracker, extraMeta, requestTimelineId);
            promoteStageBoundaryDebugEvents("final", extraMeta, "ChatApiController.sync.final");
            attachDebugAiMatrixTrace(extraMeta, "sync.final");
            learningContextMeta = LearningContextMetadata.fromTrace(extraMeta);
            // Capture answer mode (fail-soft). Used for UI fallback badges.
            try {
                Object __am = extraMeta.get("answer.mode");
                if (__am != null) {
                    String __s = String.valueOf(__am).trim();
                    if (!__s.isBlank()) answerModeFinal = __s;
                }
                if (answerModeFinal == null || answerModeFinal.isBlank()) {
                    String __mu = result.modelUsed();
                    if (__mu != null && __mu.toLowerCase(java.util.Locale.ROOT).contains("fallback:evidence")) {
                        answerModeFinal = "FALLBACK_EVIDENCE";
                    }
                }
            } catch (Exception ignoreMode) {
                logSuppressed("sync.answerMode");
            }

            try {
                java.util.List<String> failureTags = FailureTagNormalizer.normalize(extraMeta, result.modelUsed(), null);
                if (failureTags != null && !failureTags.isEmpty()) {
                    extraMeta.put("failureTags", failureTags);
                }
            } catch (Exception ignoreTags) {
                logSuppressed("sync.failureTags");
            }
            ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalContent, answerModeFinal);
            if (!syncRagControlProjection.held()) {
                clearLocalLlmOperatorActionAfterVisibleSuccess(extraMeta, persistableFinalContent, modelUsedFinal);
            }
            promoteDebugEvents("final", extraMeta, "ChatApiController.sync.final");
            finalWebTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalWebTopK"));
            finalVectorTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalVectorTopK"));

            // Console diagnostics: dump search trace + planner meta without exposing it to the client
            try {
                searchTraceConsoleLogger.maybeLog("sync", (__srFinal == null ? null : __srFinal.trace()), (__srFinal == null ? null : __srFinal.snippets()), finalWebTopK, finalVectorTopK, extraMeta);
            } catch (Exception ignoreLog) {
                logSuppressed("sync.searchTraceConsole");
            }
        } catch (Exception ignore) {
            logSuppressed("sync.finalTraceMeta");
        } finally {
            try {
                TraceStore.clear();
            } catch (Exception ignore2) {
                logSuppressed("sync.finalTraceMeta.clear");
            }}

        String traceHtmlForSnapshot = null;
        if (completedUseWeb && __srFinal.trace() != null) {
            String traceHtml = "";
            try {
                java.util.List<String> rawSnips = (__srFinal.snippets() == null)
                        ? java.util.Collections.emptyList()
                        : __srFinal.snippets();
                traceHtml = traceHtmlBuilder.buildSplitPanel(__srFinal.trace(), rawSnips, finalWebTopK, finalVectorTopK, extraMeta);
            } catch (Exception ignore) {
                traceHtml = "";
                logSuppressed("sync.traceHtml.final");
            }
            if (traceHtml != null && !traceHtml.isBlank()) {
                traceHtmlForSnapshot = traceHtml;
            }
        }

        Long traceTurnId = ChatTraceSnapshotPointerPersister.persist(
                completedSession.getId(),
                "chat.trace_html.final",
                "POST",
                "/api/chat",
                extraMeta,
                traceHtmlForSnapshot,
                traceSnapshotStore,
                historyService,
                log);

        // Persist answer.mode + traceTurnId snapshot for cross-device badges and deterministic trace open.
        try {
            historyService.updateSessionAnswerModeAndTrace(completedSession.getId(), answerModeFinal, traceTurnId);
        } catch (Exception ignore) {
            logSuppressed("sync.answerModeTracePersist");
        }
        ChatStreamEvent.PipelineSnapshot syncPipelineSnapshot =
                ChatStreamSignalBuilder.buildPipelineSnapshot(extraMeta, answerModeFinal, traceTurnId, null);
        // (sync path) 嶺뚯빘鍮볠뤃?????逾?? SSE ?熬곣뫗??sink)??????????類ｋ츎 ??紐꾩끋.
        // ?熬곣뫗????ChatResponseDto??evidence ?熬곣뫀援???怨뺣뼺?????얜Ŧ堉??꾩룆??얜????熬곣뫀堉??琉얠돪??

        // ???? 嶺뚳퐘維? ?β돦裕녽????덉넮 嶺뚮∥?? ????
        // If any attachments failed to load, record a system message noting how many
        // attachments could not be processed. This aids debugging of missing
        // context when some uploaded files were unreadable or absent. The count
        // is computed by comparing the number of requested attachment IDs and the
        // number of documents successfully extracted by AttachmentService.
        try {
            java.util.List<String> __idsForMeta = uiReq.getAttachmentIds();
            if (__idsForMeta != null && !__idsForMeta.isEmpty()) {
                int __total = __idsForMeta.size();
                int __loaded = 0;
                try {
                    AttachmentOwnerIdentity __attachmentOwner = uiReq.getAttachmentOwnerIdentity();
                    var __docsForMeta = __attachmentOwner == null
                            ? java.util.List.<dev.langchain4j.data.document.Document>of()
                            : attachmentService.asDocumentsForSession(
                                    __idsForMeta,
                                    completedSession == null || completedSession.getId() == null
                                            ? null
                                            : String.valueOf(completedSession.getId()),
                                    __attachmentOwner);
                    if (__docsForMeta != null)
                        __loaded = __docsForMeta.size();
                } catch (Exception ignore) {
                    logSuppressed("sync.attachmentMeta.extract");
                }
                int __failed = __total - __loaded;
                if (__failed > 0) {
                    String metaMsg = String.format(ATTACHMENT_LOAD_FAILURE_FORMAT, __total, __failed);
                    historyService.appendMessage(completedSession.getId(), "system", metaMsg);
                }
            }
        } catch (Exception ignore) {
            logSuppressed("sync.attachmentMeta");
        }

        ChatResponseDto response = new ChatResponseDto(visibleFinalContent, completedSession.getId(), modelUsedFinal, result.ragUsed(),
                answerModeFinal, traceTurnId, learningContextMeta, result.evidenceMetadata(),
                syncPipelineSnapshot, syncSelectionEntropy);
        // A synchronous endpoint has no post-return client ACK. Its bounded
        // delivery-acceptance boundary is successful response construction at
        // the immediate Spring MVC handoff; the stream path never uses this rule.
        boolean syncResponseHandoffAccepted = response != null;
        recordVisibleModelSemanticOutcome(
                extraMeta,
                persistableFinalContent,
                modelUsedFinal,
                requestTimelineId,
                ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                syncPersistenceAccepted,
                syncResponseHandoffAccepted,
                syncRagControlProjection.held());
        recordModelRequestTerminal("success", requestTimelineId, modelUsedFinal);
        syncRun.recordTerminalWithoutFinal("completed");
        syncOutcomeRef.compareAndSet(null, "completed");
        completedResponse.set(response);
        });
        if (!durableAccepted) {
            throw new java.util.concurrent.CancellationException("request_cancelled");
        }
        return completedResponse.get();
        } catch (java.util.concurrent.CancellationException cancelled) {
            writeSelectionEntropyProjection(
                    selectionEntropy, selectionDecisionLedger, true);
            recordModelRequestTerminal("cancelled", requestTimelineId, null);
            throw cancelled;
        } catch (RuntimeException failure) {
            if (selectionEntropy.mode() == SelectionEntropyMode.REPLAY
                    && failure instanceof SelectionEntropyException selectionFailure) {
                selectionDecisionLedger.markFailure(selectionFailure.reason());
            }
            writeSelectionEntropyProjection(
                    selectionEntropy, selectionDecisionLedger, true);
            var responseTerminal = com.example.lms.llm.gateway.LlmResponseTerminalException.find(failure);
            if (responseTerminal != null) {
                java.util.concurrent.atomic.AtomicReference<ChatResponseDto> terminalResponse =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Long terminalSessionId = session == null ? null : session.getId();
                boolean accepted = syncRun.admitCall(() -> {
                    recordModelRequestTerminal(responseTerminal.reasonCode(), requestTimelineId,
                            responseTerminal.metadata().modelName());
                    syncOutcomeRef.compareAndSet(null, responseTerminal.reasonCode());
                    syncRun.recordTerminalWithoutFinal(responseTerminal.reasonCode());
                    terminalResponse.set(ChatResponseDto.terminal(responseTerminal,
                            terminalSessionId));
                });
                if (!accepted) throw new java.util.concurrent.CancellationException("request_cancelled");
                return terminalResponse.get();
            }
            recordModelRequestTerminal("error", requestTimelineId, null);
            throw failure;
        }
        } catch (java.util.concurrent.CancellationException cancelled) {
            syncOutcomeRef.compareAndSet(null, "cancelled");
            syncRun.recordTerminalWithoutFinal("cancelled");
            throw cancelled;
        } catch (RuntimeException failure) {
            syncOutcomeRef.compareAndSet(null, "error");
            syncRun.recordTerminalWithoutFinal("error");
            throw failure;
        } finally {
            try {
                java.util.Map<String, Object> traceMeta = syncTraceMetaRef.get();
                emitChatSessionTrace(syncTraceOnce, surface,
                        session == null ? null : session.getId(), syncRun,
                        uiReq == null ? null : uiReq.getModel(), syncModelRef.get(),
                        __workflowUseRag, syncOutcomeRef.get(),
                        traceMeta != null ? traceMeta : TraceStore.getAll());
            } catch (Throwable ignore) {
                logSuppressed("chat.sessionTrace.sync");
            }
            runRegistry.markDone(syncRun);
        }
    }

        // "lc:OpenAiChatModel:..." ?筌먐븍Ф ?꾩렮維쀥젆?
    private RagControlPresentationBoundary.Projection projectRagControlForUser(
            String semanticAnswer,
            boolean ragRequested) {
        if (!ragRequested) {
            return RagControlPresentationBoundary.Projection.passThrough(semanticAnswer);
        }
        try {
            if (ragControlPresentationBoundary != null) {
                return ragControlPresentationBoundary.projectResult(semanticAnswer, true);
            }
            tracePut("ragControl.presentation.controllerFailure", true);
            tracePut("ragControl.presentation.controllerFailureType", "missing_boundary");
        } catch (RuntimeException failure) {
            tracePut("ragControl.presentation.controllerFailure", true);
            tracePut("ragControl.presentation.controllerFailureType",
                    SafeRedactor.traceLabelOrFallback(
                            failure.getClass().getSimpleName(), "runtime_exception"));
        }
        return RagControlPresentationBoundary.Projection.failClosed(null);
    }

    // ===== settings merge =====
    private ChatRequestDto mergeWithSettings(ChatRequestDto ui) {
        return ChatRequestSettingsMerger.merge(ui, settingsService.getAllSettings(), defaultUseRag, log);
    }

    private static void bindAttachmentOwnerIfPresent(
            ChatRequestDto request,
            String username,
            String ownerKey) {
        if (request == null
                || request.getAttachmentIds() == null
                || request.getAttachmentIds().isEmpty()) {
            return;
        }
        request.bindAttachmentOwnerIdentity(AttachmentOwnerIdentity.forActor(username, ownerKey));
    }

    private PublicChatAdmissionGuard.Lease requireChatAdmission(String username, String ownerKey) {
        String ownerHash = AttachmentOwnerIdentity.forActor(username, ownerKey).hash();
        return publicChatAdmissionGuard.tryAcquire(ownerHash)
                .orElseThrow(PublicChatAdmissionGuard::rejection);
    }

    private void validateEffectiveBudgetEarly(ChatRequestDto request) {
        try {
            publicRequestBudgetGuard.validateChatEffective(mergeWithSettings(request));
        } catch (PublicRequestBudgetGuard.Rejection rejection) {
            throw rejection;
        } catch (RuntimeException deferredSettingsFailure) {
            TraceStore.put("public.request.budget.effectiveValidationDeferred", true);
            TraceStore.put("public.request.budget.effectiveValidationDeferredReason", "settings_unavailable");
        }
    }

    private void validateProjectedBudgetBeforeStream(ChatRequestDto request,
                                                     String jamminiMode,
                                                     String guardLevel) {
        ChatRequestDto dto = mergeWithSettings(request);
        GuardContext context = GuardContext.defaultContext();
        if (jamminiMode != null && !jamminiMode.isBlank()) {
            context.setHeaderMode(jamminiMode);
            context.setMode(jamminiMode);
            context.setPlanId(jamminiMode);
            if ("S1".equalsIgnoreCase(jamminiMode) || "safe".equalsIgnoreCase(jamminiMode)) {
                context.setMemoryProfile("MEMORY");
            } else if ("S2".equalsIgnoreCase(jamminiMode)
                    || "brave".equalsIgnoreCase(jamminiMode)
                    || "free".equalsIgnoreCase(jamminiMode)
                    || "zero_break".equalsIgnoreCase(jamminiMode)) {
                context.setMemoryProfile("NONE");
            }
        }
        if (guardLevel != null && !guardLevel.isBlank()) {
            context.setGuardLevel(guardLevel);
        }
        if (dto.getMessage() != null) {
            context.setEntityQueryFromQuestion(dto.getMessage());
            context.setUserQuery(dto.getMessage());
        }

        boolean hasAttachments = hasAttachments(dto);
        boolean attachmentQuestion = ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(dto.getMessage());
        if (sensitiveTopicDetector != null) {
            try {
                sensitiveTopicDetector.applyTo(context, dto);
            } catch (Exception suppressed) {
                logSuppressed("budget.preStream.sensitiveTopic");
            }
        }

        PlanHints planHints = null;
        boolean allowWeb = true;
        boolean allowRag = true;
        try {
            AnswerMode answerMode = AnswerMode.fromString(dto.getMode());
            QueryDomain queryDomain = context.isSensitiveTopic() ? QueryDomain.SENSITIVE : QueryDomain.GENERAL;
            if (workflowOrchestrator != null) {
                workflowOrchestrator.ensurePlanSelected(
                        context, answerMode, queryDomain, dto.getMessage(), hasAttachments);
            }
            if (planHintApplier != null && context.getPlanId() != null) {
                planHints = planHintApplier.load(context.getPlanId());
                planHintApplier.applyToGuardContext(planHints, context);
            }
            allowWeb = planHints == null || planHints.allowWeb() != Boolean.FALSE;
            allowRag = planHints == null || planHints.allowRag() != Boolean.FALSE;
        } catch (PublicRequestBudgetGuard.Rejection rejection) {
            throw rejection;
        } catch (Exception suppressed) {
            logSuppressed("budget.preStream.plan");
        }

        boolean requestedWeb = Boolean.TRUE.equals(dto.isUseWebSearch());
        boolean directDebug = AgentVisibleDebugEvidenceBuilder.isDirectDebugAnswerQuery(dto.getMessage());
        boolean directLiteral = ChatWorkflow.isDirectLiteralAnswerRequest(dto.getMessage());
        boolean directModeStatus = ChatWorkflow.isCurrentModeStatusRequest(dto.getMessage());
        boolean directSupabase = ChatWorkflow.isSupabaseOperationalJudgmentRequest(dto.getMessage());
        boolean finalWeb = !(hasAttachments && attachmentQuestion)
                && requestedWeb
                && !directDebug
                && !directLiteral
                && !directModeStatus
                && !directSupabase
                && allowWeb;
        boolean finalRag = allowRag
                && Boolean.TRUE.equals(dto.isUseRag())
                && !directDebug
                && !directLiteral
                && !directModeStatus
                && !directSupabase;
        boolean workflowWeb = directModeStatus ? requestedWeb : finalWeb;
        boolean workflowRag = directModeStatus ? Boolean.TRUE.equals(dto.isUseRag()) : finalRag;

        publicRequestBudgetGuard.validateChatProjected(
                dto, planHints, context.getPlanId(), workflowWeb, workflowRag);
    }

    // ===== other APIs =====

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    @GetMapping("/sessions")
    public java.util.List<SessionInfo> sessions(
            @AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails principal,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            jakarta.servlet.http.HttpServletRequest request) {
        boolean isAdmin = principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        String user = principal != null ? principal.getUsername() : "anonymousUser";
        String clientIp = resolveClientIp(request);

        java.util.List<ChatSession> list;
        if (isAdmin) {
            list = historyService.getAllSessionsForAdmin(limit);
        } else {
            list = historyService.getSessionsForUser(user, clientIp, limit);
        }
        return list.stream()
                .map(s -> new SessionInfo(
                        s.getId(),
                        s.getTitle(),
                        ChatModelMetaSupport.safeTrim(s.getLastAnswerMode()),
                        s.getLastTraceTurnId()))
                .toList();
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable Long id, Authentication authentication) {
        String username = (authentication != null && authentication.isAuthenticated())
                ? authentication.getName()
                : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);

        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "action", "RESET_SESSION",
                            "message", SESSION_EXPIRED_MESSAGE,
                            "error", "SESSION_NOT_FOUND"));
        }

        if (!isAdmin) {
            var owner = session.getAdministrator();
            if (owner == null) {
                // Guest session: allow only when ownerKey matches current request
                String currentKey = ownerKeyResolver.ownerKey();
                if (session.getOwnerKey() == null || !session.getOwnerKey().equals(currentKey)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            } else {
                if (username == null || !owner.getUsername().equals(username)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
        }

        if (runRegistry != null) {
            try {
                runRegistry.cancelSessionForDeletion(id);
            } catch (ChatRunRegistry.SessionDeletionFenceException fenceFailure) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "action", "RETRY",
                                "error", "SESSION_DELETION_FENCE_UNAVAILABLE",
                                "reason", fenceFailure.reason()));
            }
        }
        historyService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/chat/sessions/{id} */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(
            @PathVariable Long id,
            @RequestParam(name = "restoreProbe", defaultValue = "false") boolean restoreProbe,
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            Authentication authentication) {
        return getSessionResponse(
                id,
                restoreProbe,
                authentication,
                historyService.getSessionWithMessages(id, limit));
    }

    public ResponseEntity<?> getSession(
            Long id,
            boolean restoreProbe,
            Authentication authentication) {
        return getSessionResponse(
                id,
                restoreProbe,
                authentication,
                historyService.getSessionWithMessages(id));
    }

    private ResponseEntity<?> getSessionResponse(
            Long id,
            boolean restoreProbe,
            Authentication authentication,
            ChatSession session) {
        String username = authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        if (session == null) {
            if (restoreProbe) {
                return restoreProbeReset("SESSION_UNAVAILABLE");
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "action", "RESET_SESSION",
                            "message", SESSION_EXPIRED_MESSAGE,
                            "error", "SESSION_NOT_FOUND"));
        }

        if (!isAdmin) {
            var owner = session.getAdministrator();
            if (owner == null) {
                // Guest session: allow only when ownerKey matches current request
                String currentKey = ownerKeyResolver.ownerKey();
                if (session.getOwnerKey() == null || !session.getOwnerKey().equals(currentKey)) {
                    if (restoreProbe) {
                        return restoreProbeReset("SESSION_UNAVAILABLE");
                    }
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            } else {
                if (username == null || !owner.getUsername().equals(username)) {
                    if (restoreProbe) {
                        return restoreProbeReset("SESSION_UNAVAILABLE");
                    }
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
        }

        return ChatSessionDetailResponseBuilder.build(
                session,
                username,
                objectMapper,
                settingsService.getAllSettings(),
                exposeTrace,
                log);
    }

    private static ResponseEntity<Map<String, Object>> restoreProbeReset(String error) {
        return ResponseEntity.ok(Map.of(
                "found", false,
                "action", "RESET_SESSION",
                "error", error));
    }

    // ===== helpers =====
    private static void tracePut(String key, Object value) {
        try {
            TraceStore.put(key, value);
        } catch (Exception ignore) {
            logSuppressed(key);
        }
    }

    private static void tracePutIfAbsent(String key, Object value) {
        try {
            TraceStore.putIfAbsent(key, value);
        } catch (Exception ignore) {
            logSuppressed(key);
        }
    }

    private static void traceClear(String stage) {
        try {
            TraceStore.clear();
        } catch (Throwable ignore) {
            logSuppressed(stage);
        }
    }

    private void promoteDebugEvents(String phase, java.util.Map<String, Object> traceMeta, String where) {
        try {
            if (debugEventTracePromotionService != null) {
                debugEventTracePromotionService.promoteChatTrace(phase, traceMeta, where);
            }
        } catch (Exception ignore) {
            logSuppressed("debugEvent.promote");
        }
    }

    private void promoteStageBoundaryDebugEvents(
            String phase, java.util.Map<String, Object> traceMeta, String where) {
        try {
            if (debugEventTracePromotionService != null) {
                debugEventTracePromotionService.promoteStageBoundaryBreadcrumbsOnly(phase, traceMeta, where);
            }
        } catch (Exception ignore) {
            logSuppressed("debugEvent.stageBoundary.promote");
        }
    }

    private static void logSuppressed(String stage) {
        log.debug("[ChatApi] suppressed stage={}", safeTraceStage(stage));
    }

    private static String errorSummary(Throwable error) {
        String message = SafeRedactor.safeMessage(error == null ? null : error.getMessage(), 512);
        return String.format("errorHash=%s errorLength=%d",
                SafeRedactor.hashValue(message),
                message == null ? 0 : message.length());
    }

    private static String safeTraceStage(String stage) {
        String label = SafeRedactor.traceLabel(stage);
        return (label == null || label.isBlank()) ? "unknown" : label;
    }

    // ===== DTO records =====
    public record MessageDto(Long turnId, String role, String content, LocalDateTime timestamp) {
    }

    static Optional<MessageDto> restoreTraceMetaMessage(Long turnId, String content, LocalDateTime timestamp,
            boolean exposeTrace) {
        return ChatTraceMetaMessageRestorer.restore(turnId, content, timestamp, exposeTrace);
    }

    // MERGE_HOOK:PROJ_AGENT::src111_MEMORY
    /**
     * ChatSession.sessionMeta(JSON)??UI ??븐슙??DTO???곌랜理묌뜮???類ｋ펲.
     * - UI?띠럾? 嶺뚮ㅏ援????띠룆??????깅さ嶺?嶺뚮∥????????????(???????濡レ┣ ??⑥ろ맖).
     * - UI?띠럾? null/?リ옇???泥롨첋?뚮턄嶺?嶺뚮∥?? ?띠룆???DTO???낅슣????類ｋ펲.
     */
    private java.util.Map<String, Object> mergeSessionMetaIntoRequest(ChatSession session, ChatRequestDto uiReq) {
        return ChatSessionMetaMerger.merge(objectMapper, session, uiReq, log);
    }
    // MERGE_HOOK END

    public record SessionDetail(Long id, String title, LocalDateTime createdAt, List<MessageDto> messages,
            String modelUsed, java.util.Map<String, Object> settings) {
    }

    public record SessionInfo(Long id, String title, String answerMode, Long lastTraceTurnId) {
    }

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    private String resolveClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

}
