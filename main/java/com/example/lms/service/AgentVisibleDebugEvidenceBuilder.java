package com.example.lms.service;

import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.SelectionEntropyTraceSupport;
import com.example.lms.trace.TraceMemoryFingerprintProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Map.entry;

public final class AgentVisibleDebugEvidenceBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TEXT_CHARS = 2_400;
    private static final Path BROWSER_SMOKE_PATH = Path.of("var/codex-smoke/browser-ui-smoke.json");
    private static final Path COMPUTER_SMOKE_PATH = Path.of("var/codex-smoke/computer-use-smoke.json");
    private static final Path SUPABASE_SCHEMA_SNAPSHOT_PATH =
            Path.of("data/db-gap-report/supabase-schema-snapshot.json");
    private static final TraceMemoryFingerprintProbe FALLBACK_TRACE_MEMORY_PROBE =
            new TraceMemoryFingerprintProbe(null, null, null);
    private static final String[] DIRECT_DEBUG_SUBJECT_MARKERS = {
            "debug", "trace", "heartbeat", "browser", "computer", "supabase", "matrix",
            "evidence_needed",
            "\uB514\uBC84\uADF8", "\uB514\uBC84\uAE45", "\uD750\uC801",
            "\uBE0C\uB77C\uC6B0\uC800", "\uCEF4\uD4E8\uD130", "\uC288\uD37C\uBCA0\uC774\uC2A4",
            "\uB9E4\uD2B8\uB9AD\uC2A4"
    };
    private static final Pattern DIRECT_DEBUG_UI_TOKEN_PATTERN =
            Pattern.compile("(?<![\\p{L}\\p{N}_])ui(?![\\p{L}\\p{N}_])");
    private static final String[] DIRECT_CHAT_STATUS_PHRASES = {
            "chatbot status", "chat status", "chatbot state", "chat state",
            "chatbot health", "chat health", "chatbot current status", "chat current status",
            "\uCC57\uBD07 \uC0C1\uD0DC", "\uCC44\uD305 \uC0C1\uD0DC", "\uCC57\uBD07 \uC810\uAC80", "\uCC44\uD305 \uC810\uAC80",
            "\uCC57\uBD07 \uD604\uC7AC \uC0C1\uD0DC", "\uCC44\uD305 \uD604\uC7AC \uC0C1\uD0DC"
    };
    private static final String[] DIRECT_MODEL_STATUS_PHRASES = {
            "model health", "model status", "model state",
            "llm health", "llm status", "llm state",
            "provider health", "provider status", "provider state",
            "\uBAA8\uB378 \uC0C1\uD0DC", "llm \uC0C1\uD0DC",
            "\uACF5\uAE09\uC790 \uC0C1\uD0DC", "\uD504\uB85C\uBC14\uC774\uB354 \uC0C1\uD0DC"
    };
    private static final String[] DIRECT_DEBUG_STATUS_MARKERS = {
            "status", "state", "health", "diagnostic", "diagnostics", "inspect", "inspection",
            "report", "check", "show", "tell", "current", "stale", "operator", "why",
            "\uC0C1\uD0DC", "\uC810\uAC80", "\uAC80\uC0AC", "\uBCF4\uC5EC", "\uC54C\uB824",
            "\uD604\uC7AC", "\uC6E8", "\uC65C", "\uB9AC\uD3EC\uD2B8"
    };
    private static final String[] DIRECT_DEBUG_OUTPUT_SUPPRESSION_MARKERS = {
            "without debug", "no debug", "debug-free",
            "\uB514\uBC84\uADF8 \uC124\uBA85 \uC5C6\uC774",
            "\uB514\uBC84\uADF8 \uB85C\uADF8 \uC5C6\uC774",
            "\uB514\uBC84\uADF8 \uCD9C\uB825 \uC5C6\uC774",
            "\uB514\uBC84\uAE45 \uC124\uBA85 \uC5C6\uC774"
    };
    private static final String[] DIRECT_DEBUG_CITATION_OUTPUT_MARKERS = {
            "cite", "citation", "source marker", "source markers", "source citation", "source citations", "[w", "[v",
            "\uB05D\uC5D0", "\uBD99\uC5EC", "\uBD99\uC5EC\uC918"
    };
    private static final String[] DIRECT_DEBUG_STRUCTURED_OUTPUT_MARKERS = {
            "sentence", "paragraph", "\uBB38\uC7A5"
    };
    private static final String[] DIRECT_DEBUG_EXPLANATION_MARKERS = {
            "explain", "summarize", "how to", "tip", "recommend",
            "\uC124\uBA85", "\uBC29\uBC95", "\uC694\uC57D", "\uCD94\uCC9C"
    };
    private static final Pattern EXPLICIT_EXTERNAL_SOURCE_TOKEN_PATTERN =
            Pattern.compile("(?iu)(?:\\b(?:urls?|links?|sources?|citations?)\\b|링크|출처|인용)");
    private static final Pattern NEGATED_EXTERNAL_SOURCE_OUTPUT_PATTERN =
            Pattern.compile("(?iu)\\b(?:do\\s+not|don['’]?t|without)\\b.{0,40}"
                    + "(?:\\bofficial\\b|공식).{0,24}"
                    + "(?:\\b(?:urls?|links?|sources?|citations?)\\b|링크|출처|인용)");

    private AgentVisibleDebugEvidenceBuilder() {
    }

    enum Field {
        SUMMARY,
        BROWSER_STATUS,
        BROWSER_EVIDENCE_NEEDED,
        BROWSER_STALE,
        BROWSER_BLOCKING,
        BROWSER_SCOPE,
        COMPUTER_STATUS,
        COMPUTER_EVIDENCE_NEEDED,
        COMPUTER_STALE,
        COMPUTER_BLOCKING,
        COMPUTER_SCOPE,
        COMPUTER_COUNT_ONLY,
        SUPABASE_EVIDENCE_NEEDED,
        AGENT_DB_STATUS,
        AGENT_DB_REASON,
        AGENT_DB_NEXT_ACTION,
        LOCAL_LLM_TRIGGERED,
        LOCAL_LLM_TRIGGER_REASON,
        LOCAL_LLM_FAILURE_CLASS,
        LOCAL_LLM_NEXT_ACTION,
        LOCAL_LLM_ACTION_SCORE,
        LOCAL_LLM_SCORE_DELTA,
        LOCAL_LLM_NEGATIVE_SIGNAL_COUNT,
        LOCAL_LLM_UPSTREAM_STATUS,
        LOCAL_LLM_UPSTREAM_FAILURE_CLASS,
        LOCAL_LLM_UPSTREAM_NEXT_ACTION,
        MATRIX_COUNT,
        MATRIX_CHUNKS,
        MATRIX_DECISION,
        SELECTION_ENTROPY_MODE,
        SELECTION_ENTROPY_ALGORITHM,
        SELECTION_ENTROPY_COHERENCE,
        SELECTION_ENTROPY_DECISION_COUNT,
        SELECTION_ENTROPY_DRAW_COUNT,
        SELECTION_ENTROPY_STABLE_TIE_COUNT,
        SELECTION_ENTROPY_CANDIDATE_DRIFT_COUNT,
        SELECTION_ENTROPY_ROUTER_DRAW_COUNT,
        SELECTION_ENTROPY_STRATEGY_DRAW_COUNT,
        SELECTION_ENTROPY_ENSEMBLE_DRAW_COUNT,
        SELECTION_ENTROPY_COMPLETION_ORDER,
        SELECTION_ENTROPY_REASON
    }

    /**
     * Allowlisted typed projection used by deterministic composers. The heartbeat
     * remains a compatibility/prompt document and is never parsed on the active path.
     */
    record Snapshot(String heartbeatText, Map<Field, String> fields) {
        Snapshot {
            String safeHeartbeat = SafeRedactor.redact(heartbeatText == null ? "" : heartbeatText);
            if (safeHeartbeat == null) {
                safeHeartbeat = "";
            }
            EnumMap<Field, String> copy = new EnumMap<>(Field.class);
            if (fields != null) {
                fields.forEach((field, value) -> {
                    if (field != null && value != null && !value.isBlank()) {
                        String safeValue = SafeRedactor.redact(value);
                        if (safeValue != null && !safeValue.isBlank()) {
                            copy.put(field, safeValue.length() <= 320
                                    ? safeValue
                                    : safeValue.substring(0, 320));
                        }
                    }
                });
            }
            heartbeatText = boundedHeartbeat(safeHeartbeat, copy);
            fields = Map.copyOf(copy);
        }

        static Snapshot empty() {
            return new Snapshot("", Map.of());
        }

        static Snapshot fromLegacyHeartbeat(String heartbeatText) {
            if (heartbeatText == null || heartbeatText.isBlank()) {
                return empty();
            }
            EnumMap<Field, String> values = new EnumMap<>(Field.class);
            putLegacy(values, Field.SUMMARY, heartbeatText, "summary=");
            putLegacy(values, Field.BROWSER_STATUS, heartbeatText, "external.browser.status=");
            putLegacy(values, Field.BROWSER_EVIDENCE_NEEDED, heartbeatText,
                    "external.browser.evidenceNeeded=");
            putLegacy(values, Field.BROWSER_STALE, heartbeatText, "external.browser.stale=");
            putLegacy(values, Field.BROWSER_BLOCKING, heartbeatText, "external.browser.blocking=");
            putLegacy(values, Field.BROWSER_SCOPE, heartbeatText, "external.browser.evidenceScope=");
            putLegacy(values, Field.COMPUTER_STATUS, heartbeatText, "external.computer-use.status=");
            putLegacy(values, Field.COMPUTER_EVIDENCE_NEEDED, heartbeatText,
                    "external.computer-use.evidenceNeeded=");
            putLegacy(values, Field.COMPUTER_STALE, heartbeatText, "external.computer-use.stale=");
            putLegacy(values, Field.COMPUTER_BLOCKING, heartbeatText, "external.computer-use.blocking=");
            putLegacy(values, Field.COMPUTER_SCOPE, heartbeatText,
                    "external.computer-use.evidenceScope=");
            putLegacy(values, Field.COMPUTER_COUNT_ONLY, heartbeatText,
                    "external.computer-use.countOnly=");
            putLegacy(values, Field.SUPABASE_EVIDENCE_NEEDED, heartbeatText,
                    "external.supabase.evidenceNeeded=");
            putLegacy(values, Field.AGENT_DB_STATUS, heartbeatText, "agentDbContext.status=");
            putLegacy(values, Field.AGENT_DB_REASON, heartbeatText, "agentDbContext.reason=");
            putLegacy(values, Field.AGENT_DB_NEXT_ACTION, heartbeatText, "agentDbContext.nextAction=");
            putLegacy(values, Field.LOCAL_LLM_TRIGGERED, heartbeatText,
                    "localLlm.operatorAction.triggered=");
            putLegacy(values, Field.LOCAL_LLM_TRIGGER_REASON, heartbeatText,
                    "localLlm.operatorAction.triggerReason=");
            putLegacy(values, Field.LOCAL_LLM_FAILURE_CLASS, heartbeatText,
                    "localLlm.operatorAction.failureClass=");
            putLegacy(values, Field.LOCAL_LLM_NEXT_ACTION, heartbeatText,
                    "localLlm.operatorAction.nextAction=");
            putLegacy(values, Field.LOCAL_LLM_ACTION_SCORE, heartbeatText,
                    "localLlm.operatorAction.actionScore=");
            putLegacy(values, Field.LOCAL_LLM_SCORE_DELTA, heartbeatText,
                    "localLlm.operatorAction.scoreDelta=");
            putLegacy(values, Field.LOCAL_LLM_NEGATIVE_SIGNAL_COUNT, heartbeatText,
                    "localLlm.operatorAction.negativeSignalCount=");
            putLegacy(values, Field.LOCAL_LLM_UPSTREAM_STATUS, heartbeatText,
                    "localLlm.operatorAction.upstreamStatus=");
            putLegacy(values, Field.LOCAL_LLM_UPSTREAM_FAILURE_CLASS, heartbeatText,
                    "localLlm.operatorAction.upstreamFailureClass=");
            putLegacy(values, Field.LOCAL_LLM_UPSTREAM_NEXT_ACTION, heartbeatText,
                    "localLlm.operatorAction.upstreamNextAction=");
            putLegacy(values, Field.MATRIX_COUNT, heartbeatText,
                    "debug.ai.metrics.virtualMatrix.count=");
            putLegacy(values, Field.MATRIX_CHUNKS, heartbeatText,
                    "debug.ai.metrics.virtualMatrix.chunkCount=");
            putLegacy(values, Field.MATRIX_DECISION, heartbeatText,
                    "debug.ai.metrics.virtualMatrix.decision=");
            putLegacy(values, Field.SELECTION_ENTROPY_MODE, heartbeatText,
                    "selectionEntropy.mode=");
            putLegacy(values, Field.SELECTION_ENTROPY_ALGORITHM, heartbeatText,
                    "selectionEntropy.algorithmVersion=");
            putLegacy(values, Field.SELECTION_ENTROPY_COHERENCE, heartbeatText,
                    "selectionEntropy.coherenceStatus=");
            putLegacy(values, Field.SELECTION_ENTROPY_DECISION_COUNT, heartbeatText,
                    "selectionEntropy.decisionCount=");
            putLegacy(values, Field.SELECTION_ENTROPY_DRAW_COUNT, heartbeatText,
                    "selectionEntropy.drawCount=");
            putLegacy(values, Field.SELECTION_ENTROPY_STABLE_TIE_COUNT, heartbeatText,
                    "selectionEntropy.stableTieBreakCount=");
            putLegacy(values, Field.SELECTION_ENTROPY_CANDIDATE_DRIFT_COUNT, heartbeatText,
                    "selectionEntropy.candidateDriftCount=");
            putLegacy(values, Field.SELECTION_ENTROPY_ROUTER_DRAW_COUNT, heartbeatText,
                    "selectionEntropy.routerDrawCount=");
            putLegacy(values, Field.SELECTION_ENTROPY_STRATEGY_DRAW_COUNT, heartbeatText,
                    "selectionEntropy.strategyDrawCount=");
            putLegacy(values, Field.SELECTION_ENTROPY_ENSEMBLE_DRAW_COUNT, heartbeatText,
                    "selectionEntropy.ensembleDrawCount=");
            putLegacy(values, Field.SELECTION_ENTROPY_COMPLETION_ORDER, heartbeatText,
                    "selectionEntropy.completionOrderDeterministic=");
            putLegacy(values, Field.SELECTION_ENTROPY_REASON, heartbeatText,
                    "selectionEntropy.reasonCode=");
            putLegacy(values, Field.SELECTION_ENTROPY_MODE, heartbeatText, "q0=");
            putLegacy(values, Field.SELECTION_ENTROPY_ALGORITHM, heartbeatText, "q1=");
            putLegacy(values, Field.SELECTION_ENTROPY_COHERENCE, heartbeatText, "q2=");
            putLegacy(values, Field.SELECTION_ENTROPY_DECISION_COUNT, heartbeatText, "q3=");
            putLegacy(values, Field.SELECTION_ENTROPY_DRAW_COUNT, heartbeatText, "q4=");
            putLegacy(values, Field.SELECTION_ENTROPY_STABLE_TIE_COUNT, heartbeatText, "q5=");
            putLegacy(values, Field.SELECTION_ENTROPY_CANDIDATE_DRIFT_COUNT, heartbeatText, "q6=");
            putLegacy(values, Field.SELECTION_ENTROPY_ROUTER_DRAW_COUNT, heartbeatText, "q7=");
            putLegacy(values, Field.SELECTION_ENTROPY_STRATEGY_DRAW_COUNT, heartbeatText, "q8=");
            putLegacy(values, Field.SELECTION_ENTROPY_ENSEMBLE_DRAW_COUNT, heartbeatText, "q9=");
            putLegacy(values, Field.SELECTION_ENTROPY_COMPLETION_ORDER, heartbeatText, "qa=");
            putLegacy(values, Field.SELECTION_ENTROPY_REASON, heartbeatText, "qb=");
            return new Snapshot(heartbeatText, values);
        }

        String value(Field field) {
            return field == null ? null : fields.get(field);
        }

        List<Document> documents() {
            return heartbeatText.isBlank() ? List.of() : List.of(Document.from(heartbeatText));
        }

        private static void putLegacy(
                EnumMap<Field, String> values,
                Field field,
                String text,
                String prefix) {
            String value = legacyLineValue(text, prefix);
            if (value != null && !value.isBlank()) {
                values.put(field, value);
            }
        }
    }

    private static String boundedHeartbeat(String safeHeartbeat, Map<Field, String> fields) {
        if (safeHeartbeat.length() <= MAX_TEXT_CHARS) {
            return safeHeartbeat;
        }
        String marker = "truncated=true\n";
        String suffix = compactLegacySuffix(fields, MAX_TEXT_CHARS - marker.length());
        int prefixBudget = Math.max(0, MAX_TEXT_CHARS - marker.length() - suffix.length());
        String prefix = safeHeartbeat.substring(0, Math.min(prefixBudget, safeHeartbeat.length()));
        int lastLineBreak = prefix.lastIndexOf('\n');
        prefix = lastLineBreak >= 0 ? prefix.substring(0, lastLineBreak + 1) : "";
        return prefix + marker + suffix;
    }

    private static String compactLegacySuffix(Map<Field, String> fields, int budget) {
        List<Field> present = new ArrayList<>();
        int structuralChars = 0;
        for (Field field : Field.values()) {
            if (fields.containsKey(field)) {
                present.add(field);
                structuralChars += legacyPrefix(field).length() + 1;
            }
        }
        if (present.isEmpty()) {
            return "";
        }
        int valueChars = Math.max(0, budget - structuralChars);
        int requiredValueChars = present.stream()
                .mapToInt(field -> fields.get(field).replace('\r', ' ').replace('\n', ' ').length())
                .sum();
        boolean allValuesFit = requiredValueChars <= valueChars;
        int charsPerValue = valueChars / present.size();
        int remainder = valueChars % present.size();
        StringBuilder out = new StringBuilder(Math.min(budget, structuralChars + valueChars));
        for (int index = 0; index < present.size(); index++) {
            Field field = present.get(index);
            String value = fields.get(field).replace('\r', ' ').replace('\n', ' ');
            int allowance = allValuesFit
                    ? value.length()
                    : charsPerValue + (index < remainder ? 1 : 0);
            if (value.length() > allowance) {
                value = value.substring(0, allowance);
            }
            out.append(legacyPrefix(field)).append(value).append('\n');
        }
        return out.toString();
    }

    private static String legacyPrefix(Field field) {
        return switch (field) {
            case SUMMARY -> "summary=";
            case BROWSER_STATUS -> "external.browser.status=";
            case BROWSER_EVIDENCE_NEEDED -> "external.browser.evidenceNeeded=";
            case BROWSER_STALE -> "external.browser.stale=";
            case BROWSER_BLOCKING -> "external.browser.blocking=";
            case BROWSER_SCOPE -> "external.browser.evidenceScope=";
            case COMPUTER_STATUS -> "external.computer-use.status=";
            case COMPUTER_EVIDENCE_NEEDED -> "external.computer-use.evidenceNeeded=";
            case COMPUTER_STALE -> "external.computer-use.stale=";
            case COMPUTER_BLOCKING -> "external.computer-use.blocking=";
            case COMPUTER_SCOPE -> "external.computer-use.evidenceScope=";
            case COMPUTER_COUNT_ONLY -> "external.computer-use.countOnly=";
            case SUPABASE_EVIDENCE_NEEDED -> "external.supabase.evidenceNeeded=";
            case AGENT_DB_STATUS -> "agentDbContext.status=";
            case AGENT_DB_REASON -> "agentDbContext.reason=";
            case AGENT_DB_NEXT_ACTION -> "agentDbContext.nextAction=";
            case LOCAL_LLM_TRIGGERED -> "localLlm.operatorAction.triggered=";
            case LOCAL_LLM_TRIGGER_REASON -> "localLlm.operatorAction.triggerReason=";
            case LOCAL_LLM_FAILURE_CLASS -> "localLlm.operatorAction.failureClass=";
            case LOCAL_LLM_NEXT_ACTION -> "localLlm.operatorAction.nextAction=";
            case LOCAL_LLM_ACTION_SCORE -> "localLlm.operatorAction.actionScore=";
            case LOCAL_LLM_SCORE_DELTA -> "localLlm.operatorAction.scoreDelta=";
            case LOCAL_LLM_NEGATIVE_SIGNAL_COUNT -> "localLlm.operatorAction.negativeSignalCount=";
            case LOCAL_LLM_UPSTREAM_STATUS -> "localLlm.operatorAction.upstreamStatus=";
            case LOCAL_LLM_UPSTREAM_FAILURE_CLASS -> "localLlm.operatorAction.upstreamFailureClass=";
            case LOCAL_LLM_UPSTREAM_NEXT_ACTION -> "localLlm.operatorAction.upstreamNextAction=";
            case MATRIX_COUNT -> "debug.ai.metrics.virtualMatrix.count=";
            case MATRIX_CHUNKS -> "debug.ai.metrics.virtualMatrix.chunkCount=";
            case MATRIX_DECISION -> "debug.ai.metrics.virtualMatrix.decision=";
            case SELECTION_ENTROPY_MODE -> "q0=";
            case SELECTION_ENTROPY_ALGORITHM -> "q1=";
            case SELECTION_ENTROPY_COHERENCE -> "q2=";
            case SELECTION_ENTROPY_DECISION_COUNT -> "q3=";
            case SELECTION_ENTROPY_DRAW_COUNT -> "q4=";
            case SELECTION_ENTROPY_STABLE_TIE_COUNT -> "q5=";
            case SELECTION_ENTROPY_CANDIDATE_DRIFT_COUNT -> "q6=";
            case SELECTION_ENTROPY_ROUTER_DRAW_COUNT -> "q7=";
            case SELECTION_ENTROPY_STRATEGY_DRAW_COUNT -> "q8=";
            case SELECTION_ENTROPY_ENSEMBLE_DRAW_COUNT -> "q9=";
            case SELECTION_ENTROPY_COMPLETION_ORDER -> "qa=";
            case SELECTION_ENTROPY_REASON -> "qb=";
        };
    }

    static List<Document> buildLocalDocs(String query, DebugAiMetricsService debugAiMetricsService) {
        return buildSnapshot(query, debugAiMetricsService).documents();
    }

    static Snapshot fromDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Snapshot.empty();
        }
        for (Document document : documents) {
            String text = document == null ? null : SafeRedactor.redact(document.text());
            if (text != null && text.startsWith("AGENT_VISIBLE_DEBUG_HEARTBEAT")) {
                return Snapshot.fromLegacyHeartbeat(text);
            }
        }
        return Snapshot.empty();
    }

    static Snapshot buildSnapshot(String query, DebugAiMetricsService debugAiMetricsService) {
        boolean debugEvidenceQuery = isDebugEvidenceQuery(query);
        Map<String, Object> metrics = compactMetrics(debugAiMetricsService);
        Map<String, Object> browser = agentVisibleExternal("browser", browserEvidence());
        Map<String, Object> computer = agentVisibleExternal("computer-use", computerEvidence());
        Map<String, Object> supabase = supabaseEvidence();
        Map<String, Object> turn = turnEvidence();
        Map<String, Object> harmony = chatHarmonyEvidence(debugAiMetricsService);
        Map<String, Object> mla = isAgentDebugPromptEvidenceQuery(query)
                ? chatMlaEvidence(debugAiMetricsService)
                : Map.of();
        Map<String, Object> agentDbContext = agentDbContextEvidence();
        Map<String, Object> localLlm = localLlmOperatorActionEvidence();
        ensureTraceMemoryCheckpoint(
                query,
                debugEvidenceQuery,
                metrics,
                browser,
                computer,
                supabase,
                turn,
                harmony,
                agentDbContext,
                localLlm);
        Map<String, Object> traceMemory = traceMemoryEvidence();
        Map<String, Object> selectionEntropy = selectionEntropyEvidence(debugEvidenceQuery);
        String text = buildEvidenceText(
                query,
                metrics,
                browser,
                computer,
                supabase,
                turn,
                harmony,
                mla,
                traceMemory,
                agentDbContext,
                localLlm,
                selectionEntropy);
        if (text.isBlank()) {
            return Snapshot.empty();
        }
        try {
            TraceStore.put("prompt.agentDebugEvidence.present", true);
            TraceStore.put("prompt.agentDebugEvidence.localDocsCount", 1);
            TraceStore.put("prompt.agentDebugEvidence.source", "chat-workflow-pre-llm");
            TraceStore.put("prompt.agentDebugEvidence.queryMatched", debugEvidenceQuery);
            traceAgentVisibleMetrics(metrics);
            traceAgentVisibleTurn(turn);
            traceAgentVisibleHarmony(harmony);
            traceAgentVisibleMla(mla);
            traceAgentVisibleTraceMemory(traceMemory);
            traceAgentVisibleAgentDbContext(agentDbContext);
            traceAgentVisibleLocalLlm(localLlm);
            traceAgentVisibleExternal("browser", browser);
            traceAgentVisibleExternal("computerUse", computer);
            traceAgentVisibleExternal("supabase", supabase);
            traceAgentVisibleWebPrefetch(webPrefetchEvidence());
        } catch (RuntimeException ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.agentDebugEvidence.trace", ignore);
        }
        return snapshot(text, metrics, browser, computer, supabase, agentDbContext, localLlm,
                selectionEntropy);
    }

    private static void ensureTraceMemoryCheckpoint(String query,
                                                    boolean debugEvidenceQuery,
                                                    Map<String, Object> metrics,
                                                    Map<String, Object> browser,
                                                    Map<String, Object> computer,
                                                    Map<String, Object> supabase,
                                                    Map<String, Object> turn,
                                                    Map<String, Object> harmony,
                                                    Map<String, Object> agentDbContext,
                                                    Map<String, Object> localLlm) {
        boolean hasTraceMemoryCheckpoint = TraceStore.get("traceMemory.fingerprint.current") != null
                || TraceStore.get("traceMemory.triggered") != null
                || TraceStore.get("traceMemory.recoveryMode") != null
                || TraceStore.get("traceMemory.recovery.action") != null
                || TraceStore.get("traceMemory.rawSnapshot.supabaseShadowCount") != null
                || TraceStore.get("traceMemory.rawSnapshot.supabaseShadowArtifact") != null;
        boolean hasVirtualCheckpoint = TraceStore.get("traceMemory.virtualCheckpoint.latestKey") != null
                && TraceStore.get("traceMemory.virtualCheckpoint.latestStage") != null
                && TraceStore.get("traceMemory.virtualCheckpoint.latestPhase") != null;
        if (hasTraceMemoryCheckpoint && hasVirtualCheckpoint) {
            return;
        }
        if (hasTraceMemoryCheckpoint) {
            ensureVirtualCheckpointFromExistingTrace();
            return;
        }
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("source", "agent_visible_debug_evidence");
            raw.put("phase", "agent_visible_debug_evidence");
            raw.put("queryLength", query == null ? 0 : query.length());
            raw.put("queryHash", SafeRedactor.hashValue(query));
            raw.put("debugEvidenceQuery", debugEvidenceQuery);
            raw.put("metrics", metrics);
            raw.put("browser", browser);
            raw.put("computerUse", computer);
            raw.put("supabaseShadow", supabase);
            raw.put("turn", turn);
            raw.put("harmony", harmony);
            raw.put("agentDbContext", agentDbContext);
            raw.put("localLlm", localLlm);
            FALLBACK_TRACE_MEMORY_PROBE.checkpoint("raw_snapshot", "AgentVisibleDebugEvidenceBuilder", raw);
            FALLBACK_TRACE_MEMORY_PROBE.checkpoint("load", "AgentVisibleDebugEvidenceBuilder", raw);
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.agentDebugEvidence.traceMemoryCheckpoint", ex);
        }
    }

    private static void ensureVirtualCheckpointFromExistingTrace() {
        String stage = SafeRedactor.traceLabelOrFallback(TraceStore.get("traceMemory.checkpoint.stage"), "load");
        String phase = SafeRedactor.traceLabelOrFallback(TraceStore.get("traceMemory.checkpoint.phase"),
                "agent_visible_debug_evidence");
        String baseKey = "traceMemory.virtualCheckpoint." + stage;
        TraceStore.put("traceMemory.virtualCheckpoint.latestKey", baseKey);
        TraceStore.put("traceMemory.virtualCheckpoint.latestStage", stage);
        TraceStore.put("traceMemory.virtualCheckpoint.latestPhase", phase);
        TraceStore.putIfAbsent(baseKey + ".stage", stage);
        TraceStore.putIfAbsent(baseKey + ".phase", phase);
        TraceStore.putIfAbsent(baseKey + ".fingerprint", TraceStore.get("traceMemory.fingerprint.current"));
        TraceStore.putIfAbsent(baseKey + ".previousFingerprint", TraceStore.get("traceMemory.fingerprint.previous"));
        TraceStore.putIfAbsent(baseKey + ".delta.changedCount", TraceStore.get("traceMemory.delta.changedCount"));
        TraceStore.putIfAbsent(baseKey + ".delta.droppedBreadcrumbCount",
                TraceStore.get("traceMemory.delta.droppedBreadcrumbCount"));
    }

    static String buildEvidenceTextForTest(String query,
                                           Map<String, Object> metrics,
                                           Map<String, Object> browser,
                                           Map<String, Object> computer,
                                           Map<String, Object> supabase) {
        return buildSnapshotForTest(query, metrics, browser, computer, supabase).heartbeatText();
    }

    static Snapshot buildSnapshotForTest(String query,
                                          Map<String, Object> metrics,
                                          Map<String, Object> browser,
                                          Map<String, Object> computer,
                                          Map<String, Object> supabase) {
        Map<String, Object> visibleBrowser = agentVisibleExternal("browser", browser);
        Map<String, Object> visibleComputer = agentVisibleExternal("computer-use", computer);
        Map<String, Object> selectionEntropy = selectionEntropyEvidence(isDebugEvidenceQuery(query));
        String text = buildEvidenceText(query, metrics, visibleBrowser, visibleComputer, supabase,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), selectionEntropy);
        return snapshot(text, metrics, visibleBrowser, visibleComputer, supabase, Map.of(), Map.of(),
                selectionEntropy);
    }

    private static String buildEvidenceText(String query,
                                             Map<String, Object> metrics,
                                             Map<String, Object> browser,
                                             Map<String, Object> computer,
                                             Map<String, Object> supabase,
                                             Map<String, Object> turn,
                                             Map<String, Object> harmony,
                                             Map<String, Object> mla,
                                             Map<String, Object> traceMemory,
                                             Map<String, Object> agentDbContext,
                                             Map<String, Object> localLlm,
                                             Map<String, Object> selectionEntropy) {
        browser = agentVisibleExternal("browser", browser);
        computer = agentVisibleExternal("computer-use", computer);
        StringBuilder out = new StringBuilder(768);
        out.append("AGENT_VISIBLE_DEBUG_HEARTBEAT\n");
        out.append("source=chat-workflow-pre-llm\n");
        appendMetric(out, "query.debugEvidenceRequested", isDebugEvidenceQuery(query));
        appendMetricIfPresent(out, "selectionEntropy.mode", selectionEntropy.get("mode"));
        appendMetricIfPresent(out, "selectionEntropy.algorithmVersion",
                selectionEntropy.get("algorithmVersion"));
        appendMetricIfPresent(out, "selectionEntropy.coherenceStatus",
                selectionEntropy.get("coherenceStatus"));
        appendMetricIfPresent(out, "selectionEntropy.decisionCount",
                selectionEntropy.get("decisionCount"));
        appendMetricIfPresent(out, "selectionEntropy.drawCount", selectionEntropy.get("drawCount"));
        appendMetricIfPresent(out, "selectionEntropy.stableTieBreakCount",
                selectionEntropy.get("stableTieBreakCount"));
        appendMetricIfPresent(out, "selectionEntropy.candidateDriftCount",
                selectionEntropy.get("candidateDriftCount"));
        appendMetricIfPresent(out, "selectionEntropy.routerDrawCount",
                selectionEntropy.get("routerDrawCount"));
        appendMetricIfPresent(out, "selectionEntropy.strategyDrawCount",
                selectionEntropy.get("strategyDrawCount"));
        appendMetricIfPresent(out, "selectionEntropy.ensembleDrawCount",
                selectionEntropy.get("ensembleDrawCount"));
        appendMetricIfPresent(out, "selectionEntropy.completionOrderDeterministic",
                selectionEntropy.get("completionOrderDeterministic"));
        appendMetricIfPresent(out, "selectionEntropy.reasonCode",
                selectionEntropy.get("reasonCode"));
        out.append("summary=").append(summary(metrics, browser, computer, supabase)).append('\n');
        Map<String, Object> webPrefetch = webPrefetchEvidence();
        appendMetricIfPresent(out, "web.prefetch.phase", webPrefetch.get("phase"));
        appendMetricIfPresent(out, "web.prefetch.requestUseWeb", webPrefetch.get("requestUseWeb"));
        appendMetricIfPresent(out, "web.prefetch.resolvedUseWeb", webPrefetch.get("resolvedUseWeb"));
        appendMetricIfPresent(out, "web.prefetch.resolvedUseRag", webPrefetch.get("resolvedUseRag"));
        appendMetricIfPresent(out, "web.prefetch.searchMode", webPrefetch.get("searchMode"));
        appendMetricIfPresent(out, "web.prefetch.topK", webPrefetch.get("topK"));
        appendMetricIfPresent(out, "web.prefetch.snippetCount", webPrefetch.get("snippetCount"));
        appendMetricIfPresent(out, "web.prefetch.providerQuerySiteFilter", webPrefetch.get("providerQuerySiteFilter"));
        appendMetricIfPresent(out, "web.prefetch.providerQuerySiteDomain", webPrefetch.get("providerQuerySiteDomain"));
        appendMetricIfPresent(out, "web.prefetch.providerQueryHash", webPrefetch.get("providerQueryHash"));
        appendMetricIfPresent(out, "web.prefetch.providerQueryLength", webPrefetch.get("providerQueryLength"));
        appendMetricIfPresent(out, "chat.turn.sessionIdHash", turn.get("sessionIdHash"));
        appendMetricIfPresent(out, "chat.turn.requestIdHash", turn.get("requestIdHash"));
        appendMetricIfPresent(out, "chat.turn.traceIdHash", turn.get("traceIdHash"));
        appendMetricIfPresent(out, "chat.turn.timelineKey", turn.get("timelineKey"));
        appendMetricIfPresent(out, "chat.harmony.agentVisible", harmony.get("agentVisible"));
        appendMetricIfPresent(out, "chat.harmony.applied", harmony.get("applied"));
        appendMetricIfPresent(out, "chat.harmony.degraded", harmony.get("degraded"));
        appendMetricIfPresent(out, "chat.harmony.decision", harmony.get("decision"));
        appendMetricIfPresent(out, "chat.harmony.reason", harmony.get("reason"));
        appendMetricIfPresent(out, "chat.harmony.weightedScore", harmony.get("weightedScore"));
        appendMetricIfPresent(out, "chat.harmony.evidenceCount", harmony.get("evidenceCount"));
        appendMetricIfPresent(out, "chat.harmony.nextAction", harmony.get("nextAction"));
        appendMetricIfPresent(out, "chat.harmony.nextReason", harmony.get("nextReason"));
        appendMetricIfPresent(out, "chat.mla.breadcrumbCount", mla.get("breadcrumbCount"));
        appendMetricIfPresent(out, "chat.mla.queryRedacted", mla.get("queryRedacted"));
        appendMetricIfPresent(out, "trace.memory.triggered", traceMemory.get("triggered"));
        appendMetricIfPresent(out, "trace.memory.reason", traceMemory.get("reason"));
        appendMetricIfPresent(out, "trace.memory.recoveryAction", traceMemory.get("recoveryAction"));
        appendMetricIfPresent(out, "trace.memory.recoveryRoute", traceMemory.get("recoveryRoute"));
        appendMetricIfPresent(out, "trace.memory.routeDecision", traceMemory.get("routeDecision"));
        appendMetricIfPresent(out, "trace.memory.failureClass", traceMemory.get("failureClass"));
        appendMetricIfPresent(out, "trace.memory.quarantine", traceMemory.get("quarantine"));
        appendMetricIfPresent(out, "trace.memory.errorBreakRisk", traceMemory.get("errorBreakRisk"));
        appendMetricIfPresent(out, "trace.memory.cfvmOffered", traceMemory.get("cfvmOffered"));
        appendMetricIfPresent(out, "trace.memory.cfvmPatternId", traceMemory.get("cfvmPatternId"));
        appendMetricIfPresent(out, "trace.memory.checkpointPhase", traceMemory.get("checkpointPhase"));
        appendMetricIfPresent(out, "trace.memory.checkpointHistorySize", traceMemory.get("checkpointHistorySize"));
        appendMetricIfPresent(out, "trace.memory.checkpointJsonRoute", traceMemory.get("checkpointJsonRoute"));
        appendMetricIfPresent(out, "trace.memory.virtualCheckpointLatestKey", traceMemory.get("virtualCheckpointLatestKey"));
        appendMetricIfPresent(out, "trace.memory.virtualCheckpointLatestStage", traceMemory.get("virtualCheckpointLatestStage"));
        appendMetricIfPresent(out, "trace.memory.virtualCheckpointLatestPhase", traceMemory.get("virtualCheckpointLatestPhase"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowCount", traceMemory.get("supabaseShadowCount"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowArtifact", traceMemory.get("supabaseShadowArtifact"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowProjectScope", traceMemory.get("supabaseShadowProjectScope"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowAvailable", traceMemory.get("supabaseShadowAvailable"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowComplete", traceMemory.get("supabaseShadowComplete"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowReadOnly", traceMemory.get("supabaseShadowReadOnly"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowMutationAllowed", traceMemory.get("supabaseShadowMutationAllowed"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowEvidenceNeededCount", traceMemory.get("supabaseShadowEvidenceNeededCount"));
        appendMetricIfPresent(out, "trace.memory.supabaseShadowNextAction", traceMemory.get("supabaseShadowNextAction"));
        appendMetricIfPresent(out, "trace.memory.changed", traceMemory.get("changed"));
        appendMetricIfPresent(out, "trace.memory.nextAction", traceMemory.get("nextAction"));
        appendMetricIfPresent(out, "trace.memory.nextReason", traceMemory.get("nextReason"));
        appendMetricIfPresent(out, "agentDbContext.status", agentDbContext.get("status"));
        appendMetricIfPresent(out, "agentDbContext.reason", agentDbContext.get("reason"));
        appendMetricIfPresent(out, "agentDbContext.nextAction", agentDbContext.get("nextAction"));
        appendMetricIfPresent(out, "localLlm.operatorAction.triggered", localLlm.get("triggered"));
        appendMetricIfPresent(out, "localLlm.operatorAction.triggerReason", localLlm.get("triggerReason"));
        appendMetricIfPresent(out, "localLlm.operatorAction.failureClass", localLlm.get("failureClass"));
        appendMetricIfPresent(out, "localLlm.operatorAction.nextAction", localLlm.get("nextAction"));
        appendMetricIfPresent(out, "localLlm.operatorAction.actionScore", localLlm.get("actionScore"));
        appendMetricIfPresent(out, "localLlm.operatorAction.scoreDelta", localLlm.get("scoreDelta"));
        appendMetricIfPresent(out, "localLlm.operatorAction.negativeSignalCount", localLlm.get("negativeSignalCount"));
        appendMetricIfPresent(out, "localLlm.operatorAction.upstreamStatus", localLlm.get("upstreamStatus"));
        appendMetricIfPresent(out, "localLlm.operatorAction.upstreamFailureClass", localLlm.get("upstreamFailureClass"));
        appendMetricIfPresent(out, "localLlm.operatorAction.upstreamNextAction", localLlm.get("upstreamNextAction"));
        appendExternal(out, "browser", browser);
        appendExternal(out, "computer-use", computer);
        appendExternal(out, "supabase", supabase);
        appendMetric(out, "debug.ai.metrics.virtualMatrix.count", metrics.get("virtualMatrixCount"));
        appendMetric(out, "debug.ai.metrics.virtualMatrix.chunkCount", metrics.get("virtualMatrixChunkCount"));
        appendMetric(out, "debug.ai.metrics.virtualMatrix.weightedScore", metrics.get("virtualMatrixWeightedScore"));
        appendMetric(out, "debug.ai.metrics.virtualMatrix.decision", metrics.get("virtualMatrixDecision"));
        appendMetric(out, "debug.ai.metrics.virtualMatrix.hotChunkIndex", metrics.get("virtualMatrixHotChunkIndex"));
        appendMetric(out, "debug.ai.metrics.virtualMatrix.hotChunkRiskScore", metrics.get("virtualMatrixHotChunkRiskScore"));
        appendMetric(out, "debug.ai.metrics.nextAction", metrics.get("nextDebugAction"));
        appendMetric(out, "debug.ai.metrics.nextReason", metrics.get("nextDebugReason"));
        appendMetric(out, "debug.ai.metrics.totalEvents", metrics.get("totalEvents"));
        appendMetric(out, "debug.ai.metrics.warnEvents", metrics.get("warnEvents"));
        appendMetric(out, "debug.ai.metrics.errorEvents", metrics.get("errorEvents"));
        appendMetric(out, "debug.ai.metrics.topTile", metrics.get("topTile"));
        appendMetric(out, "debug.ai.metrics.topFailureClass", metrics.get("topFailureClass"));
        out.append("instruction=Keep this as an agent-visible redacted runtime/debug breadcrumb for this chat turn. Do not use it in the user-facing answer unless the user explicitly asks for current debug/status/health/report details. Do not claim live Supabase health beyond evidence_needed.\n");
        String text = SafeRedactor.redact(out.toString());
        if (text == null) {
            return "";
        }
        return text;
    }

    private static Map<String, Object> turnEvidence() {
        String sessionHash = hashTraceIdentifier(firstTraceValue("sid", "sessionId"));
        String requestHash = hashTraceIdentifier(firstTraceValue("requestId", "x-request-id", "rid"));
        String traceHash = hashTraceIdentifier(firstTraceValue("trace.id", "traceId", "trace"));
        String timelineKey = timelineKey(sessionHash, requestHash, traceHash);
        if (sessionHash == null && requestHash == null && traceHash == null && timelineKey == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (sessionHash != null) {
            out.put("sessionIdHash", sessionHash);
        }
        if (requestHash != null) {
            out.put("requestIdHash", requestHash);
        }
        if (traceHash != null) {
            out.put("traceIdHash", traceHash);
        }
        if (timelineKey != null) {
            out.put("timelineKey", timelineKey);
        }
        return out;
    }

    private static Map<String, Object> agentDbContextEvidence() {
        Map<String, Object> out = new LinkedHashMap<>();
        putEvidenceScalar(out, "status", TraceStore.get("agent.dbContext.agentVisible.status"));
        putEvidenceScalar(out, "reason", TraceStore.get("agent.dbContext.agentVisible.reason"));
        putEvidenceScalar(out, "nextAction", TraceStore.get("agent.dbContext.agentVisible.nextAction"));
        if (!out.isEmpty()) {
            return out;
        }
        Object promptInjected = TraceStore.get("agent.dbContext.prompt.injected");
        if (Boolean.TRUE.equals(promptInjected) || "true".equalsIgnoreCase(String.valueOf(promptInjected))) {
            putEvidenceScalar(out, "status", "OK");
            putEvidenceScalar(out, "reason", "db_context_prompt_injected");
            putEvidenceScalar(out, "nextAction", "inspect_agent_db_context_trace");
            return out;
        }
        Object promptFailSoft = TraceStore.get("agent.dbContext.prompt.failSoft");
        if (Boolean.TRUE.equals(promptFailSoft) || "true".equalsIgnoreCase(String.valueOf(promptFailSoft))) {
            putEvidenceScalar(out, "status", "WARN");
            putEvidenceScalar(out, "reason",
                    firstNonBlank(label(TraceStore.get("agent.dbContext.prompt.reason")),
                            "db_context_snapshot_unavailable"));
            putEvidenceScalar(out, "nextAction", "inspect_agent_db_context_prompt_injector");
        }
        return out;
    }

    private static Map<String, Object> localLlmOperatorActionEvidence() {
        Map<String, Object> out = new LinkedHashMap<>();
        putEvidenceScalar(out, "triggered", TraceStore.get("llm.localSmoke.operatorAction.triggered"));
        putEvidenceScalar(out, "triggerReason", TraceStore.get("llm.localSmoke.operatorAction.triggerReason"));
        putEvidenceScalar(out, "failureClass", TraceStore.get("llm.localSmoke.operatorAction.failureClass"));
        putEvidenceScalar(out, "nextAction", TraceStore.get("llm.localSmoke.operatorAction.nextAction"));
        putEvidenceScalar(out, "actionScore", TraceStore.get("llm.localSmoke.operatorAction.actionScore"));
        putEvidenceScalar(out, "scoreDelta", TraceStore.get("llm.localSmoke.operatorAction.scoreDelta"));
        putEvidenceScalar(out, "negativeSignalCount",
                TraceStore.get("llm.localSmoke.operatorAction.negativeSignalCount"));
        putEvidenceScalar(out, "upstreamStatus", TraceStore.get("llm.localSmoke.operatorAction.upstreamStatus"));
        putEvidenceScalar(out, "upstreamFailureClass",
                TraceStore.get("llm.localSmoke.operatorAction.upstreamFailureClass"));
        putEvidenceScalar(out, "upstreamNextAction",
                TraceStore.get("llm.localSmoke.operatorAction.upstreamNextAction"));
        if (out.isEmpty()) {
            return Map.of();
        }
        Object triggered = out.get("triggered");
        boolean meaningfulTrigger = triggered instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(triggered));
        if (!meaningfulTrigger
                && numberOrFallback(out.get("actionScore"), 0) <= 0
                && numberOrFallback(out.get("scoreDelta"), 0) <= 0
                && "none".equalsIgnoreCase(firstNonBlank(label(out.get("failureClass")), "none"))) {
            return Map.of();
        }
        return out;
    }

    private static Map<String, Object> webPrefetchEvidence() {
        for (String phase : List.of("stream", "sync", "stream.retry", "sync.retry")) {
            String prefix = "chatApi.web.prefetch." + phase + ".";
            boolean present = TraceStore.get(prefix + "snippetCount") != null
                    || TraceStore.get(prefix + "resolvedUseWeb") != null
                    || TraceStore.get(prefix + "providerQueryHash") != null;
            if (!present) {
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            putEvidenceScalar(out, "phase", phase);
            putEvidenceScalar(out, "requestUseWeb", TraceStore.get(prefix + "requestUseWeb"));
            putEvidenceScalar(out, "resolvedUseWeb", TraceStore.get(prefix + "resolvedUseWeb"));
            putEvidenceScalar(out, "resolvedUseRag", TraceStore.get(prefix + "resolvedUseRag"));
            putEvidenceScalar(out, "searchMode", TraceStore.get(prefix + "searchMode"));
            putEvidenceScalar(out, "topK", TraceStore.get(prefix + "topK"));
            putEvidenceScalar(out, "snippetCount", TraceStore.get(prefix + "snippetCount"));
            putEvidenceScalar(out, "providerQuerySiteFilter", TraceStore.get(prefix + "providerQuerySiteFilter"));
            putEvidenceScalar(out, "providerQuerySiteDomain", TraceStore.get(prefix + "providerQuerySiteDomain"));
            putEvidenceScalar(out, "providerQueryHash", TraceStore.get(prefix + "providerQueryHash"));
            putEvidenceScalar(out, "providerQueryLength", TraceStore.get(prefix + "providerQueryLength"));
            return out;
        }
        return Map.of();
    }

    private static Map<String, Object> chatHarmonyEvidence(DebugAiMetricsService debugAiMetricsService) {
        Map<String, Object> out = new LinkedHashMap<>();
        putEvidenceScalar(out, "agentVisible", TraceStore.get("chat.harmony.postprocess.agentVisible"));
        putEvidenceScalar(out, "applied", TraceStore.get("chat.harmony.postprocess.applied"));
        putEvidenceScalar(out, "degraded", TraceStore.get("chat.harmony.postprocess.degraded"));
        putEvidenceScalar(out, "decision", TraceStore.get("chat.harmony.postprocess.decision"));
        putEvidenceScalar(out, "reason", TraceStore.get("chat.harmony.postprocess.reason"));
        putEvidenceScalar(out, "weightedScore", TraceStore.get("chat.harmony.postprocess.weightedScore"));
        putEvidenceScalar(out, "evidenceCount", TraceStore.get("chat.harmony.postprocess.evidenceCount"));
        if (out.isEmpty() && debugAiMetricsService != null) {
            try {
                out.putAll(debugAiMetricsService.latestChatHarmonyEvidence());
            } catch (RuntimeException ex) {
                ChatWorkflowTraceSuppressions.traceSuppressed(
                        "prompt.agentDebugEvidence.chatHarmonySnapshot", ex);
            }
        }
        if (out.isEmpty()) {
            return Map.of();
        }
        String decision = label(out.get("decision"));
        String reason = label(out.get("reason"));
        String nextAction = firstNonBlank(
                label(out.get("nextAction")),
                firstNonBlank(
                        label(TraceStore.get("debug.ai.metrics.nextAction")),
                        harmonyActionFromDecision(decision)));
        String nextReason = firstNonBlank(
                label(out.get("nextReason")),
                firstNonBlank(
                        label(TraceStore.get("debug.ai.metrics.nextReason")),
                        harmonyNextReason(decision, reason)));
        putEvidenceScalar(out, "nextAction", nextAction);
        putEvidenceScalar(out, "nextReason", nextReason);
        return out;
    }

    private static Map<String, Object> chatMlaEvidence(DebugAiMetricsService debugAiMetricsService) {
        if (debugAiMetricsService == null) {
            return Map.of();
        }
        try {
            return debugAiMetricsService.latestChatMlaEvidence();
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed(
                    "prompt.agentDebugEvidence.chatMlaSnapshot", ex);
            return Map.of();
        }
    }

    private static Map<String, Object> traceMemoryEvidence() {
        Map<String, Object> out = new LinkedHashMap<>();
        putEvidenceScalar(out, "triggered", TraceStore.get("traceMemory.triggered"));
        putEvidenceScalar(out, "reason", TraceStore.get("traceMemory.trigger.reason"));
        putEvidenceScalar(out, "recoveryAction", TraceStore.get("traceMemory.recovery.action"));
        putEvidenceScalar(out, "recoveryRoute", TraceStore.get("traceMemory.recovery.route"));
        putEvidenceScalar(out, "routeDecision", TraceStore.get("traceMemory.recovery.routeDecision"));
        putEvidenceScalar(out, "failureClass", TraceStore.get("traceMemory.recovery.failureClass"));
        putEvidenceScalar(out, "quarantine", TraceStore.get("traceMemory.recovery.quarantine"));
        putEvidenceScalar(out, "retry", TraceStore.get("traceMemory.recovery.retry"));
        putEvidenceScalar(out, "failSoft", TraceStore.get("traceMemory.recovery.failSoft"));
        putEvidenceScalar(out, "errorBreakRisk", TraceStore.get("traceMemory.errorBreak.risk"));
        putEvidenceScalar(out, "cfvmOffered", TraceStore.get("traceMemory.cfvm.offered"));
        putEvidenceScalar(out, "cfvmPatternId", TraceStore.get("traceMemory.cfvm.patternId"));
        putEvidenceScalar(out, "checkpointStage", TraceStore.get("traceMemory.checkpoint.stage"));
        putEvidenceScalar(out, "checkpointPhase", TraceStore.get("traceMemory.checkpoint.phase"));
        putEvidenceScalar(out, "checkpointIndex", TraceStore.get("traceMemory.checkpoint.index"));
        putEvidenceScalar(out, "checkpointHistorySize", TraceStore.get("traceMemory.checkpoint.historySize"));
        putEvidenceScalar(out, "checkpointJsonRoute", DebugAiMetricsService.TRACE_MEMORY_CHECKPOINTS_ROUTE);
        putEvidenceScalar(out, "virtualCheckpointLatestKey", TraceStore.get("traceMemory.virtualCheckpoint.latestKey"));
        putEvidenceScalar(out, "virtualCheckpointLatestStage", TraceStore.get("traceMemory.virtualCheckpoint.latestStage"));
        putEvidenceScalar(out, "virtualCheckpointLatestPhase", TraceStore.get("traceMemory.virtualCheckpoint.latestPhase"));
        putEvidenceScalar(out, "changed", TraceStore.get("traceMemory.delta.changed"));
        putEvidenceScalar(out, "changedCount", TraceStore.get("traceMemory.delta.changedCount"));
        putEvidenceScalar(out, "droppedBreadcrumbCount", TraceStore.get("traceMemory.delta.droppedBreadcrumbCount"));
        putEvidenceScalar(out, "supabaseShadowCount", TraceStore.get("traceMemory.rawSnapshot.supabaseShadowCount"));
        putEvidenceScalar(out, "supabaseShadowArtifact",
                TraceStore.get("traceMemory.rawSnapshot.supabaseShadowArtifact"));
        putEvidenceScalar(out, "supabaseShadowProjectScope",
                TraceStore.get("traceMemory.rawSnapshot.supabaseShadowProjectScope"));
        putEvidenceScalar(out, "supabaseShadowAvailable",
                TraceStore.get("traceMemory.rawSnapshot.supabaseShadowAvailable"));
        putEvidenceScalar(out, "supabaseShadowComplete",
                TraceStore.get("traceMemory.rawSnapshot.supabaseShadowComplete"));
        putEvidenceScalar(out, "supabaseShadowReadOnly",
                TraceStore.get("traceMemory.rawSnapshot.supabaseShadowReadOnly"));
        putEvidenceScalar(out, "supabaseShadowMutationAllowed",
                TraceStore.get("traceMemory.rawSnapshot.supabaseShadowMutationAllowed"));
        putEvidenceScalar(out, "supabaseShadowEvidenceNeededCount",
                TraceStore.get("traceMemory.rawSnapshot.supabaseShadowEvidenceNeededCount"));
        putEvidenceScalar(out, "supabaseShadowNextAction",
                TraceStore.get("traceMemory.rawSnapshot.supabaseShadowNextAction"));
        if (out.isEmpty()) {
            return Map.of();
        }
        String risk = label(out.get("errorBreakRisk"));
        String quarantine = label(out.get("quarantine"));
        String triggered = label(out.get("triggered"));
        String reason = label(out.get("reason"));
        String action = label(out.get("recoveryAction"));
        String routeDecision = label(out.get("routeDecision"));
        boolean active = "true".equalsIgnoreCase(triggered);
        String nextAction;
        if ("BREAK".equalsIgnoreCase(risk) || "true".equalsIgnoreCase(quarantine)) {
            nextAction = "inspect_trace_memory_quarantine";
        } else if (active) {
            nextAction = "continue_trace_memory_recovery";
        } else {
            nextAction = "continue_trace_memory_checkpointing";
        }
        putEvidenceScalar(out, "nextAction", nextAction);
        putEvidenceScalar(out, "nextReason",
                firstNonBlank(firstNonBlank(reason, firstNonBlank(routeDecision, action)),
                        firstNonBlank(risk,
                                firstNonBlank(label(out.get("checkpointPhase")), label(out.get("checkpointStage"))))));
        return out;
    }

    private static Map<String, Object> compactMetrics(DebugAiMetricsService service) {
        if (service == null) {
            return Map.ofEntries(
                    entry("virtualMatrixCount", 0),
                    entry("virtualMatrixChunkCount", 0),
                    entry("virtualMatrixWeightedScore", 0.0d),
                    entry("virtualMatrixDecision", "unavailable"),
                    entry("virtualMatrixHotChunkIndex", -1),
                    entry("virtualMatrixHotChunkRiskScore", 0.0d),
                    entry("nextDebugAction", "debug_ai_metrics_unavailable"),
                    entry("nextDebugReason", "debug_ai_metrics_service_missing"),
                    entry("totalEvents", 0),
                    entry("warnEvents", 0),
                    entry("errorEvents", 0),
                    entry("topTile", "none"),
                    entry("topFailureClass", "none"));
        }
        try {
            Map<String, Object> compact = service.compactSnapshot(80, 300_000L);
            Map<String, Object> scorecard = mapValue(compact.get("scorecard"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("virtualMatrixCount", compact.get("virtualMatrixCount"));
            out.put("virtualMatrixChunkCount", compact.get("virtualMatrixChunkCount"));
            out.put("virtualMatrixWeightedScore", compact.get("virtualMatrixWeightedScore"));
            out.put("virtualMatrixDecision", compact.get("virtualMatrixDecision"));
            Map<String, Object> hotChunk = firstMap(compact.get("virtualMatrixHotChunks"));
            String matrixDecision = firstNonBlank(label(compact.get("virtualMatrixDecision")), "observe");
            out.put("virtualMatrixHotChunkIndex", numberOrFallback(hotChunk.get("chunkIndex"), -1));
            out.put("virtualMatrixHotChunkRiskScore", doubleOrFallback(hotChunk.get("riskScore"), 0.0d));
            out.put("nextDebugAction", matrixActionFromDecision(matrixDecision));
            out.put("nextDebugReason", matrixDecision);
            out.put("totalEvents", compact.get("totalEvents"));
            out.put("warnEvents", compact.get("warnEvents"));
            out.put("errorEvents", compact.get("errorEvents"));
            out.put("topTile", firstNonBlank(label(scorecard.get("hotTile")), "none"));
            out.put("topFailureClass", firstNonBlank(label(scorecard.get("anomalyFailureClass")), "none"));
            return out;
        } catch (RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.agentDebugEvidence.metrics", ex);
            return Map.ofEntries(
                    entry("virtualMatrixCount", 0),
                    entry("virtualMatrixChunkCount", 0),
                    entry("virtualMatrixWeightedScore", 0.0d),
                    entry("virtualMatrixDecision", "unavailable"),
                    entry("virtualMatrixHotChunkIndex", -1),
                    entry("virtualMatrixHotChunkRiskScore", 0.0d),
                    entry("nextDebugAction", "debug_ai_metrics_unavailable"),
                    entry("nextDebugReason", "metrics_unavailable"),
                    entry("totalEvents", 0),
                    entry("warnEvents", 0),
                    entry("errorEvents", 0),
                    entry("topTile", "none"),
                    entry("topFailureClass", "metrics_unavailable"));
        }
    }

    private static void traceAgentVisibleMetrics(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        putTraceScalar("prompt.agentDebugEvidence.virtualMatrix.count", metrics.get("virtualMatrixCount"));
        putTraceScalar("prompt.agentDebugEvidence.virtualMatrix.chunkCount", metrics.get("virtualMatrixChunkCount"));
        putTraceScalar("prompt.agentDebugEvidence.virtualMatrix.weightedScore", metrics.get("virtualMatrixWeightedScore"));
        putTraceScalar("prompt.agentDebugEvidence.virtualMatrix.decision", metrics.get("virtualMatrixDecision"));
        putTraceScalar("prompt.agentDebugEvidence.virtualMatrix.hotChunkIndex", metrics.get("virtualMatrixHotChunkIndex"));
        putTraceScalar("prompt.agentDebugEvidence.virtualMatrix.hotChunkRiskScore", metrics.get("virtualMatrixHotChunkRiskScore"));
        putTraceScalar("prompt.agentDebugEvidence.nextAction", metrics.get("nextDebugAction"));
        putTraceScalar("prompt.agentDebugEvidence.nextReason", metrics.get("nextDebugReason"));
    }

    private static void traceAgentVisibleTurn(Map<String, Object> turn) {
        if (turn == null || turn.isEmpty()) {
            return;
        }
        putTraceScalar("prompt.agentDebugEvidence.turn.sessionIdHash", turn.get("sessionIdHash"));
        putTraceScalar("prompt.agentDebugEvidence.turn.requestIdHash", turn.get("requestIdHash"));
        putTraceScalar("prompt.agentDebugEvidence.turn.traceIdHash", turn.get("traceIdHash"));
        putTraceScalar("prompt.agentDebugEvidence.turn.timelineKey", turn.get("timelineKey"));
    }

    private static void traceAgentVisibleHarmony(Map<String, Object> harmony) {
        if (harmony == null || harmony.isEmpty()) {
            return;
        }
        String prefix = "prompt.agentDebugEvidence.chatHarmony.";
        String mirrorPrefix = "debug.ai.agentDebugEvidence.chatHarmony.";
        for (Map.Entry<String, Object> entry : harmony.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = normalizeTraceKey(entry.getKey());
            if (key == null) {
                continue;
            }
            putTraceScalar(prefix + key, entry.getValue());
            putTraceScalar(mirrorPrefix + key, entry.getValue());
        }
    }

    private static void traceAgentVisibleMla(Map<String, Object> mla) {
        if (mla == null || mla.isEmpty()) {
            return;
        }
        putTraceScalar("prompt.agentDebugEvidence.chatMla.breadcrumbCount", mla.get("breadcrumbCount"));
        putTraceScalar("prompt.agentDebugEvidence.chatMla.queryRedacted", mla.get("queryRedacted"));
        putTraceScalar("debug.ai.agentDebugEvidence.chatMla.breadcrumbCount", mla.get("breadcrumbCount"));
        putTraceScalar("debug.ai.agentDebugEvidence.chatMla.queryRedacted", mla.get("queryRedacted"));
    }

    private static void traceAgentVisibleTraceMemory(Map<String, Object> traceMemory) {
        if (traceMemory == null || traceMemory.isEmpty()) {
            return;
        }
        String prefix = "prompt.agentDebugEvidence.traceMemory.";
        String mirrorPrefix = "debug.ai.agentDebugEvidence.traceMemory.";
        for (Map.Entry<String, Object> entry : traceMemory.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = normalizeTraceKey(entry.getKey());
            if (key == null) {
                continue;
            }
            putTraceScalar(prefix + key, entry.getValue());
            putTraceScalar(mirrorPrefix + key, entry.getValue());
        }
    }

    private static void traceAgentVisibleAgentDbContext(Map<String, Object> agentDbContext) {
        if (agentDbContext == null || agentDbContext.isEmpty()) {
            return;
        }
        String prefix = "prompt.agentDebugEvidence.agentDbContext.";
        String mirrorPrefix = "debug.ai.agentDebugEvidence.agentDbContext.";
        for (Map.Entry<String, Object> entry : agentDbContext.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = normalizeTraceKey(entry.getKey());
            if (key == null) {
                continue;
            }
            putTraceScalar(prefix + key, entry.getValue());
            putTraceScalar(mirrorPrefix + key, entry.getValue());
        }
    }

    private static void traceAgentVisibleLocalLlm(Map<String, Object> localLlm) {
        if (localLlm == null || localLlm.isEmpty()) {
            return;
        }
        String prefix = "prompt.agentDebugEvidence.localLlm.operatorAction.";
        String mirrorPrefix = "debug.ai.agentDebugEvidence.localLlm.operatorAction.";
        for (Map.Entry<String, Object> entry : localLlm.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = normalizeTraceKey(entry.getKey());
            if (key == null) {
                continue;
            }
            putTraceScalar(prefix + key, entry.getValue());
            putTraceScalar(mirrorPrefix + key, entry.getValue());
        }
    }

    private static void traceAgentVisibleWebPrefetch(Map<String, Object> webPrefetch) {
        if (webPrefetch == null || webPrefetch.isEmpty()) {
            return;
        }
        String prefix = "prompt.agentDebugEvidence.webPrefetch.";
        String mirrorPrefix = "debug.ai.agentDebugEvidence.webPrefetch.";
        for (Map.Entry<String, Object> entry : webPrefetch.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = normalizeTraceKey(entry.getKey());
            if (key == null) {
                continue;
            }
            putTraceScalar(prefix + key, entry.getValue());
            putTraceScalar(mirrorPrefix + key, entry.getValue());
        }
    }

    private static void traceAgentVisibleExternal(String service, Map<String, Object> row) {
        String safeService = normalizeTraceKey(service);
        if (safeService == null || row == null || row.isEmpty()) {
            return;
        }
        String prefix = "prompt.agentDebugEvidence.external." + safeService + ".";
        String mirrorPrefix = "debug.ai.agentDebugEvidence.external." + safeService + ".";
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String safeKey = normalizeTraceKey(entry.getKey());
            if (safeKey != null) {
                putExternalTraceScalar(prefix + safeKey, entry.getValue());
                putExternalTraceScalar(mirrorPrefix + safeKey, entry.getValue());
            }
        }
    }

    private static void putEvidenceScalar(Map<String, Object> row, String key, Object value) {
        if (row == null || key == null || key.isBlank() || value == null) {
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            row.put(key, value);
            return;
        }
        String safe = label(value);
        if (safe != null && !safe.isBlank()) {
            row.put(key, safe);
        }
    }

    private static void putTraceScalar(String key, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            TraceStore.put(key, value);
            return;
        }
        String safe = label(value);
        if (safe != null && !safe.isBlank()) {
            TraceStore.put(key, safe);
        }
    }

    private static void putExternalTraceScalar(String key, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            TraceStore.put(key, value);
            return;
        }
        String safe = externalValue(value);
        if (safe != null && !safe.isBlank()) {
            TraceStore.put(key, safe);
        }
    }

    private static void appendMetricIfPresent(StringBuilder out, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = scalar(value);
        if (!text.isBlank() && !"unknown".equals(text)) {
            appendMetric(out, key, text);
        }
    }

    private static Map<String, Object> browserEvidence() {
        JsonNode root = readJson(BROWSER_SMOKE_PATH);
        if (root == null) {
            return external("WARN", "browser_ui_smoke_missing", "run_browser_local_ui_smoke");
        }
        int secretHits = count(root, "secretHits") + count(root, "rawSecretPatternHits");
        boolean stale = isExternalEvidenceStale(root);
        boolean ok = !stale
                && (root.path("ok").asBoolean(false)
                || (root.path("reachable").asBoolean(false)
                && root.path("targetAccepted").asBoolean(false)
                && root.path("targetContentVisible").asBoolean(false)
                && root.path("screenshotCaptured").asBoolean(false)
                && secretHits == 0
                && isNone(root.path("evidenceNeeded").asText("none"))));
        String evidenceNeeded = stale
                ? "browser_ui_smoke_stale"
                : firstNonBlank(label(root.path("evidenceNeeded").asText("")), "browser_ui_smoke_not_ok");
        Map<String, Object> out = external(ok ? "OK" : "WARN",
                ok ? "none" : evidenceNeeded,
                ok ? "browser_ui_smoke_current" : "run_browser_local_ui_smoke");
        out.put("reachable", root.path("reachable").asBoolean(false));
        out.put("targetContentVisible", root.path("targetContentVisible").asBoolean(false));
        out.put("screenshotCaptured", root.path("screenshotCaptured").asBoolean(false));
        out.put("surface", firstNonBlank(label(root.path("browserSurface").asText("")), "unknown"));
        out.put("stale", stale);
        out.put("secretHits", secretHits);
        return out;
    }

    private static Map<String, Object> computerEvidence() {
        JsonNode root = readJson(COMPUTER_SMOKE_PATH);
        if (root == null) {
            return external("WARN", "computer_use_smoke_missing", "run_computer_use_lightweight_smoke");
        }
        int secretHits = count(root, "secretHits") + count(root, "rawSecretPatternHits");
        int appCount = count(root, "appCount");
        int runningCount = count(root, "runningCount");
        int targetableWindowCount = count(root, "targetableWindowCount");
        if (targetableWindowCount <= 0) {
            targetableWindowCount = count(root, "windowCount");
        }
        boolean countEvidence = appCount > 0 || runningCount > 0 || targetableWindowCount > 0;
        boolean sourceOk = root.path("ok").asBoolean(false);
        boolean storesRawAppNames = root.path("storesRawAppNames").asBoolean(false);
        boolean storesAppNames = root.path("storesAppNames").asBoolean(storesRawAppNames);
        boolean guiOnly = root.has("guiOnly")
                ? root.path("guiOnly").asBoolean(false)
                : sourceOk && countEvidence;
        boolean noTerminalAutomation = root.has("noTerminalAutomation")
                ? root.path("noTerminalAutomation").asBoolean(false)
                : sourceOk && countEvidence;
        boolean supportingOnly = root.has("supportingOnly")
                ? root.path("supportingOnly").asBoolean(false)
                : sourceOk && countEvidence;
        boolean countOnly = !storesRawAppNames
                && !storesAppNames
                && !root.path("storesWindowTitles").asBoolean(false);
        boolean stale = isExternalEvidenceStale(root);
        boolean ok = !stale
                && sourceOk
                && root.path("reachable").asBoolean(sourceOk && countEvidence)
                && guiOnly
                && noTerminalAutomation
                && supportingOnly
                && countOnly
                && secretHits == 0;
        String evidenceNeeded = stale
                ? "computer_use_smoke_stale"
                : firstNonBlank(label(root.path("evidenceNeeded").asText("")), "computer_use_smoke_not_ok");
        Map<String, Object> out = external(ok ? "OK" : "WARN",
                ok ? "none" : evidenceNeeded,
                ok ? "computer_use_supporting_evidence_current" : "run_computer_use_lightweight_smoke");
        out.put("reachable", root.path("reachable").asBoolean(sourceOk && countEvidence));
        out.put("guiOnly", guiOnly);
        out.put("noTerminalAutomation", noTerminalAutomation);
        out.put("supportingOnly", supportingOnly);
        out.put("countOnly", countOnly);
        out.put("targetableWindowCount", targetableWindowCount);
        out.put("stale", stale);
        out.put("secretHits", secretHits);
        return out;
    }

    private static Map<String, Object> supabaseEvidence() {
        boolean projectRef = hasText(System.getenv("SUPABASE_PROJECT_REF"));
        boolean auth = hasText(System.getenv("SUPABASE_ACCESS_TOKEN"));
        boolean mcp = Files.isRegularFile(Path.of(".mcp.json"));
        Map<String, Object> out = external("WARN",
                projectRef && auth && mcp ? "supabase_live_probe_unverified" : "supabase_project_scope_or_auth_unverified",
                projectRef && auth && mcp ? "run_readonly_supabase_context_probe" : "authenticate_supabase_mcp_or_cli");
        out.put("projectRefEnvStatus", projectRef ? "present" : "missing");
        out.put("authEnvStatus", auth ? "present" : "missing");
        out.put("mcpConfigStatus", mcp ? "configured" : "missing");
        appendSupabaseSchemaSnapshotEvidence(out);
        return out;
    }

    private static void appendSupabaseSchemaSnapshotEvidence(Map<String, Object> out) {
        JsonNode root = readJson(SUPABASE_SCHEMA_SNAPSHOT_PATH);
        if (root == null) {
            out.put("schemaSnapshotArtifact", "missing");
            return;
        }
        out.put("schemaSnapshotArtifact", "present");
        putEvidenceScalar(out, "snapshotDecision", root.path("decision").asText(""));
        putEvidenceScalar(out, "schemaSnapshotAvailable", root.path("schemaSnapshotAvailable").asBoolean(false));
        putEvidenceScalar(out, "schemaSnapshotComplete", root.path("schemaSnapshotComplete").asBoolean(false));
        putEvidenceScalar(out, "readOnly", root.path("readOnly").asBoolean(false));
        putEvidenceScalar(out, "mutationAllowed", root.path("mutationAllowed").asBoolean(true));
        putEvidenceScalar(out, "snapshotBytes", root.path("snapshotBytes").asInt(0));
        putEvidenceScalar(out, "schemaSnapshotSecretHits", root.path("rawSecretPatternHits").asInt(0));
        putEvidenceScalar(out, "evidenceNeededCount", arraySize(root, "evidence_needed"));
        putEvidenceScalar(out, "nextActionCount", arraySize(root, "nextActions"));

        JsonNode projectScope = root.path("projectScope");
        putEvidenceScalar(out, "projectScopeStatus", projectScope.path("status").asText(""));
        putEvidenceScalar(out, "projectScopeSource", projectScope.path("projectScopeSource").asText(""));
        putEvidenceScalar(out, "projectScopedMode", projectScope.path("projectScopedMode").asBoolean(false));

        JsonNode authPlan = root.path("authPlan");
        putEvidenceScalar(out, "mcpOAuthRequired", authPlan.path("mcpOAuthRequired").asBoolean(false));
        putEvidenceScalar(out, "cliPresent", authPlan.path("cliPresent").asBoolean(false));

        JsonNode advisors = root.path("advisors");
        putEvidenceScalar(out, "advisorsAvailable", advisors.path("available").asBoolean(false));
        putEvidenceScalar(out, "advisorCount", arraySize(advisors, "rows"));

        JsonNode snapshotImport = root.path("snapshotImport");
        putEvidenceScalar(out, "snapshotImportStatus", snapshotImport.path("status").asText(""));
        putEvidenceScalar(out, "expectedResultCount", snapshotImport.path("expectedResultCount").asInt(0));
        putEvidenceScalar(out, "importedResultCount", snapshotImport.path("importedResultCount").asInt(0));
        putEvidenceScalar(out, "missingResultCount", snapshotImport.path("missingResultCount").asInt(0));
        putEvidenceScalar(out, "resultSetComplete", snapshotImport.path("resultSetComplete").asBoolean(false));
        putEvidenceScalar(out, "storedRawRows", snapshotImport.path("storedRawRows").asBoolean(false));
    }

    private static Map<String, Object> external(String status, String evidenceNeeded, String nextAction) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", firstNonBlank(label(status), "WARN"));
        out.put("evidenceNeeded", firstNonBlank(externalValue(evidenceNeeded), "evidence_needed"));
        out.put("nextAction", firstNonBlank(externalValue(nextAction), "collect_evidence"));
        return out;
    }

    private static Map<String, Object> agentVisibleExternal(String service, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(row);
        if (!isSupportingEvidenceMissing(service, out)) {
            return out;
        }
        Object sourceStatus = out.get("status");
        out.putIfAbsent("sourceStatus", sourceStatus == null ? "WARN" : sourceStatus);
        out.put("status", "SUPPORTING_EVIDENCE_MISSING");
        out.put("blocking", false);
        out.putIfAbsent("evidenceScope", defaultEvidenceScope(service));
        return out;
    }

    private static boolean isSupportingEvidenceMissing(String service, Map<String, Object> row) {
        if (service == null || row == null) {
            return false;
        }
        String status = label(row.get("status"));
        if (!"WARN".equalsIgnoreCase(status)) {
            return false;
        }
        String evidenceNeeded = firstNonBlank(externalValue(row.get("evidenceNeeded")), "evidence_needed");
        String normalizedService = service.trim().toLowerCase(Locale.ROOT);
        if ("browser".equals(normalizedService)) {
            return !evidenceNeeded.contains("secret");
        }
        if ("computer-use".equals(normalizedService) || "computeruse".equals(normalizedService)) {
            return !evidenceNeeded.contains("secret")
                    && !evidenceNeeded.contains("privacy")
                    && !evidenceNeeded.contains("boundary");
        }
        return false;
    }

    private static String defaultEvidenceScope(String service) {
        if (service != null && service.trim().equalsIgnoreCase("browser")) {
            return "local-ui-proof";
        }
        return "gui-supporting-only";
    }

    private static void appendMetric(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(scalar(value)).append('\n');
    }

    private static void appendExternal(StringBuilder out, String service, Map<String, Object> row) {
        out.append("external.").append(service).append(".status=").append(externalScalar(row.get("status"))).append('\n');
        out.append("external.").append(service).append(".evidenceNeeded=").append(externalScalar(row.get("evidenceNeeded"))).append('\n');
        out.append("external.").append(service).append(".nextAction=").append(externalScalar(row.get("nextAction"))).append('\n');
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if ("status".equals(key) || "evidenceNeeded".equals(key) || "nextAction".equals(key)) {
                continue;
            }
            out.append("external.").append(service).append('.').append(key).append('=')
                    .append(externalScalar(entry.getValue())).append('\n');
        }
    }

    private static JsonNode readJson(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return JSON.readTree(text.startsWith("\uFEFF") ? text.substring(1) : text);
        } catch (IOException | RuntimeException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.agentDebugEvidence.readJson", ex);
            return null;
        }
    }

    static boolean isDebugEvidenceQuery(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return containsAny(text,
                "debug", "trace", "heartbeat", "browser", "computer", "supabase", "matrix", "ui", "300",
                "디버그", "디버깅", "브라우저", "컴퓨터", "챗봇", "채팅", "상태", "점검", "매트릭스",
                "디버", "흔적", "브라우저", "컴퓨터", "수파베이스", "슈파베이스", "매트릭스",
                "챗봇", "채팅", "작동", "되나", "상태", "점검", "테스트", "에이전트");
    }

    static boolean isAgentDebugPromptEvidenceQuery(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        boolean statusIntent = containsAny(text,
                "status", "state", "health", "heartbeat", "check current", "show current",
                "current debug", "current trace", "current browser", "current computer", "current supabase",
                "상태", "점검", "현재 디버그", "현재 흔적", "현재 브라우저", "현재 컴퓨터",
                "현재 수파베이스", "현재 슈파베이스");
        return !containsAny(text, DIRECT_DEBUG_OUTPUT_SUPPRESSION_MARKERS)
                && !containsAny(text, DIRECT_DEBUG_CITATION_OUTPUT_MARKERS)
                && hasDirectDebugSubject(text)
                && statusIntent;
    }

    public static boolean isDirectDebugAnswerQuery(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return !containsAny(text, DIRECT_DEBUG_OUTPUT_SUPPRESSION_MARKERS)
                && !asksBodyOutputInsteadOfDebugStatus(text)
                && !asksRetrievalQualityProbeInsteadOfDebugStatus(text)
                && hasDirectDebugSubject(text)
                && containsAny(text, DIRECT_DEBUG_STATUS_MARKERS);
    }

    private static boolean hasDirectDebugSubject(String text) {
        return containsAny(text, DIRECT_DEBUG_SUBJECT_MARKERS)
                || DIRECT_DEBUG_UI_TOKEN_PATTERN.matcher(text).find()
                || containsAny(text, DIRECT_CHAT_STATUS_PHRASES)
                || containsAny(text, DIRECT_MODEL_STATUS_PHRASES);
    }

    private static boolean asksBodyOutputInsteadOfDebugStatus(String text) {
        if (containsAny(text, DIRECT_DEBUG_CITATION_OUTPUT_MARKERS)
                && !NEGATED_EXTERNAL_SOURCE_OUTPUT_PATTERN.matcher(text).find()) {
            return true;
        }
        return containsAny(text, DIRECT_DEBUG_STRUCTURED_OUTPUT_MARKERS)
                && containsAny(text, DIRECT_DEBUG_EXPLANATION_MARKERS);
    }

    private static boolean asksRetrievalQualityProbeInsteadOfDebugStatus(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean explicitDebugStatus = containsAny(text,
                "debug status", "trace status", "heartbeat status",
                "browser status", "computer status", "supabase status",
                "current debug", "current trace", "current heartbeat",
                "current model health", "current model status");
        boolean explicitOfficialSourceRequest = containsAny(text, "official", "\uACF5\uC2DD")
                && EXPLICIT_EXTERNAL_SOURCE_TOKEN_PATTERN.matcher(text).find()
                && !NEGATED_EXTERNAL_SOURCE_OUTPUT_PATTERN.matcher(text).find();
        if (explicitOfficialSourceRequest) {
            return true;
        }
        boolean rejectsDebugStatusAnswer = containsAny(text,
                "do not answer as current",
                "don't answer as current",
                "not answer as current",
                "not as current")
                && containsAny(text, "debug status", "trace status", "heartbeat status", "current debug");
        if (explicitDebugStatus && !rejectsDebugStatusAnswer) {
            return false;
        }
        boolean officialDocumentation = containsAny(text,
                "official documentation", "official docs", "provider documentation")
                || (text.contains("official") && text.contains("documentation"))
                || (text.contains("\uACF5\uC2DD") && text.contains("\uBB38\uC11C"));
        boolean modelIdentity = containsAny(text,
                "model id", "model ids", "model code", "model codes", "\uBAA8\uB378 id");
        boolean verificationIntent = containsAny(text,
                "valid", "validity", "verify", "confirm", "\uC720\uD6A8", "\uD655\uC778");
        if (officialDocumentation && modelIdentity && verificationIntent) {
            return true;
        }
        boolean retrievalArtifact = containsAny(text,
                "site:", "web_search", "web search", "file_search", "computer_use",
                "responses api", "project_ref", "read_only", "mcp",
                "rag", "\uC6F9\uAC80\uC0C9", "\uC6F9 \uAC80\uC0C9", "\uAC80\uC0C9");
        if (!retrievalArtifact) {
            return false;
        }
        return containsAny(text,
                "quality", "compare", "domain", "domains", "source", "evidence",
                "tool", "tools", "setup", "config", "configuration",
                "\uD488\uC9C8", "\uBE44\uAD50", "\uB3C4\uBA54\uC778", "\uCD9C\uCC98", "\uADFC\uAC70",
                "\uAC80\uC99D", "\uC9C4\uB2E8");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNone(String value) {
        String label = label(value);
        return label == null || label.isBlank() || "none".equals(label) || "null".equals(label);
    }

    static boolean isExternalEvidenceStale(JsonNode root, Instant now) {
        if (root == null) {
            return true;
        }
        if (root.path("stale").asBoolean(false)) {
            return true;
        }
        String generatedAt = root.path("generatedAt").asText("");
        if (generatedAt == null || generatedAt.isBlank()) {
            return false;
        }
        long ttlMinutes = Math.max(1L, root.path("staleAfterMinutes").asLong(60L));
        Instant basis = now == null ? Instant.now() : now;
        try {
            Instant generated = Instant.parse(generatedAt.trim());
            return generated.plus(Duration.ofMinutes(ttlMinutes)).isBefore(basis);
        } catch (DateTimeParseException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.agentDebugEvidence.generatedAt", ex);
            return true;
        }
    }

    private static boolean isExternalEvidenceStale(JsonNode root) {
        return isExternalEvidenceStale(root, Instant.now());
    }

    private static int count(JsonNode root, String field) {
        return Math.max(0, root.path(field).asInt(0));
    }

    private static int arraySize(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isArray() ? value.size() : 0;
    }

    private static String firstTraceValue(String... keys) {
        if (keys == null || keys.length == 0) {
            return null;
        }
        for (String key : keys) {
            Object value = TraceStore.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String hashTraceIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.matches("(?i)hash:[a-f0-9]{12,64}")) {
            return text.toLowerCase(Locale.ROOT);
        }
        return SafeRedactor.hashValue(text);
    }

    private static String timelineKey(String sessionHash, String requestHash, String traceHash) {
        List<String> parts = new ArrayList<>(3);
        if (sessionHash != null && !sessionHash.isBlank()) {
            parts.add(sessionHash);
        }
        if (requestHash != null && !requestHash.isBlank()) {
            parts.add(requestHash);
        }
        if (traceHash != null && !traceHash.isBlank()) {
            parts.add(traceHash);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return SafeRedactor.hashValue(String.join("|", parts));
    }

    private static Snapshot snapshot(
            String heartbeatText,
            Map<String, Object> metrics,
            Map<String, Object> browser,
            Map<String, Object> computer,
            Map<String, Object> supabase,
            Map<String, Object> agentDbContext,
            Map<String, Object> localLlm,
            Map<String, Object> selectionEntropy) {
        EnumMap<Field, String> values = new EnumMap<>(Field.class);
        values.put(Field.SUMMARY, summary(metrics, browser, computer, supabase));
        putExternal(values, Field.BROWSER_STATUS, browser, "status", true);
        putExternal(values, Field.BROWSER_EVIDENCE_NEEDED, browser, "evidenceNeeded", true);
        putExternal(values, Field.BROWSER_STALE, browser, "stale", false);
        putExternal(values, Field.BROWSER_BLOCKING, browser, "blocking", false);
        putExternal(values, Field.BROWSER_SCOPE, browser, "evidenceScope", false);
        putExternal(values, Field.COMPUTER_STATUS, computer, "status", true);
        putExternal(values, Field.COMPUTER_EVIDENCE_NEEDED, computer, "evidenceNeeded", true);
        putExternal(values, Field.COMPUTER_STALE, computer, "stale", false);
        putExternal(values, Field.COMPUTER_BLOCKING, computer, "blocking", false);
        putExternal(values, Field.COMPUTER_SCOPE, computer, "evidenceScope", false);
        putExternal(values, Field.COMPUTER_COUNT_ONLY, computer, "countOnly", false);
        putExternal(values, Field.SUPABASE_EVIDENCE_NEEDED, supabase, "evidenceNeeded", true);
        putMetric(values, Field.AGENT_DB_STATUS, agentDbContext, "status");
        putMetric(values, Field.AGENT_DB_REASON, agentDbContext, "reason");
        putMetric(values, Field.AGENT_DB_NEXT_ACTION, agentDbContext, "nextAction");
        putMetric(values, Field.LOCAL_LLM_TRIGGERED, localLlm, "triggered");
        putMetric(values, Field.LOCAL_LLM_TRIGGER_REASON, localLlm, "triggerReason");
        putMetric(values, Field.LOCAL_LLM_FAILURE_CLASS, localLlm, "failureClass");
        putMetric(values, Field.LOCAL_LLM_NEXT_ACTION, localLlm, "nextAction");
        putMetric(values, Field.LOCAL_LLM_ACTION_SCORE, localLlm, "actionScore");
        putMetric(values, Field.LOCAL_LLM_SCORE_DELTA, localLlm, "scoreDelta");
        putMetric(values, Field.LOCAL_LLM_NEGATIVE_SIGNAL_COUNT, localLlm, "negativeSignalCount");
        putMetric(values, Field.LOCAL_LLM_UPSTREAM_STATUS, localLlm, "upstreamStatus");
        putMetric(values, Field.LOCAL_LLM_UPSTREAM_FAILURE_CLASS, localLlm, "upstreamFailureClass");
        putMetric(values, Field.LOCAL_LLM_UPSTREAM_NEXT_ACTION, localLlm, "upstreamNextAction");
        putMetric(values, Field.MATRIX_COUNT, metrics, "virtualMatrixCount", true);
        putMetric(values, Field.MATRIX_CHUNKS, metrics, "virtualMatrixChunkCount", true);
        putMetric(values, Field.MATRIX_DECISION, metrics, "virtualMatrixDecision", true);
        putMetric(values, Field.SELECTION_ENTROPY_MODE, selectionEntropy, "mode");
        putMetric(values, Field.SELECTION_ENTROPY_ALGORITHM, selectionEntropy, "algorithmVersion");
        putMetric(values, Field.SELECTION_ENTROPY_COHERENCE, selectionEntropy, "coherenceStatus");
        putMetric(values, Field.SELECTION_ENTROPY_DECISION_COUNT, selectionEntropy, "decisionCount");
        putMetric(values, Field.SELECTION_ENTROPY_DRAW_COUNT, selectionEntropy, "drawCount");
        putMetric(values, Field.SELECTION_ENTROPY_STABLE_TIE_COUNT,
                selectionEntropy, "stableTieBreakCount");
        putMetric(values, Field.SELECTION_ENTROPY_CANDIDATE_DRIFT_COUNT,
                selectionEntropy, "candidateDriftCount");
        putMetric(values, Field.SELECTION_ENTROPY_ROUTER_DRAW_COUNT,
                selectionEntropy, "routerDrawCount");
        putMetric(values, Field.SELECTION_ENTROPY_STRATEGY_DRAW_COUNT,
                selectionEntropy, "strategyDrawCount");
        putMetric(values, Field.SELECTION_ENTROPY_ENSEMBLE_DRAW_COUNT,
                selectionEntropy, "ensembleDrawCount");
        putMetric(values, Field.SELECTION_ENTROPY_COMPLETION_ORDER,
                selectionEntropy, "completionOrderDeterministic");
        putMetric(values, Field.SELECTION_ENTROPY_REASON, selectionEntropy, "reasonCode");
        return new Snapshot(heartbeatText, values);
    }

    private static Map<String, Object> selectionEntropyEvidence(boolean debugEvidenceQuery) {
        if (!debugEvidenceQuery) {
            return Map.of();
        }
        return SelectionEntropyTraceSupport.fromTrace(TraceStore.getAll())
                .map(value -> {
                    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                    out.put("mode", value.mode());
                    out.put("algorithmVersion", value.algorithmVersion());
                    out.put("coherenceStatus", value.coherenceStatus());
                    out.put("decisionCount", value.decisionCount());
                    out.put("drawCount", value.drawCount());
                    out.put("stableTieBreakCount", value.stableTieBreakCount());
                    out.put("candidateDriftCount", value.candidateDriftCount());
                    out.put("routerDrawCount", value.routerDrawCount());
                    out.put("strategyDrawCount", value.strategyDrawCount());
                    out.put("ensembleDrawCount", value.ensembleDrawCount());
                    out.put("completionOrderDeterministic",
                            value.completionOrderDeterministic());
                    if (!value.reasonCode().isBlank()) {
                        out.put("reasonCode", value.reasonCode());
                    }
                    return Map.copyOf(out);
                })
                .orElseGet(Map::of);
    }

    private static String summary(
            Map<String, Object> metrics,
            Map<String, Object> browser,
            Map<String, Object> computer,
            Map<String, Object> supabase) {
        return "browser:" + scalar(value(browser, "status"))
                + " computer-use:" + scalar(value(computer, "status"))
                + " supabase:" + scalar(value(supabase, "evidenceNeeded"))
                + " matrix:" + scalar(value(metrics, "virtualMatrixCount"))
                + "/" + scalar(value(metrics, "virtualMatrixChunkCount"))
                + " decision:" + scalar(value(metrics, "virtualMatrixDecision"));
    }

    private static void putExternal(
            EnumMap<Field, String> values,
            Field field,
            Map<String, Object> source,
            String key,
            boolean alwaysPresent) {
        if (alwaysPresent || source != null && source.containsKey(key)) {
            values.put(field, externalScalar(value(source, key)));
        }
    }

    private static void putMetric(
            EnumMap<Field, String> values,
            Field field,
            Map<String, Object> source,
            String key) {
        putMetric(values, field, source, key, false);
    }

    private static void putMetric(
            EnumMap<Field, String> values,
            Field field,
            Map<String, Object> source,
            String key,
            boolean alwaysPresent) {
        if (alwaysPresent || source != null && source.containsKey(key)) {
            values.put(field, scalar(value(source, key)));
        }
    }

    private static Object value(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    private static String legacyLineValue(String text, String prefix) {
        if (text == null || prefix == null) {
            return null;
        }
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static String scalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return firstNonBlank(label(value), "unknown");
    }

    private static String externalScalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return firstNonBlank(externalValue(value), "unknown");
    }

    private static String label(Object value) {
        if (value == null) {
            return null;
        }
        return SafeRedactor.traceLabel(String.valueOf(value));
    }

    private static String externalValue(Object value) {
        if (value == null) {
            return null;
        }
        return SafeRedactor.traceLabel(String.valueOf(value));
    }

    private static String normalizeTraceKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean upperNext = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                out.append(upperNext && out.length() > 0 ? Character.toUpperCase(c) : c);
                upperNext = false;
            } else {
                upperNext = out.length() > 0;
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry != null && entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static Map<String, Object> firstMap(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return Map.of();
        }
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry != null && entry.getKey() != null) {
                        out.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return out;
            }
        }
        return Map.of();
    }

    private static int numberOrFallback(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.agentDebugEvidence.number", ex);
            return fallback;
        }
    }

    private static double doubleOrFallback(Object value, double fallback) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            return Double.isFinite(parsed) ? parsed : fallback;
        }
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ex) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.agentDebugEvidence.double", ex);
            return fallback;
        }
    }

    private static String matrixActionFromDecision(String matrixDecision) {
        String decision = firstNonBlank(label(matrixDecision), "observe");
        if ("mitigate_now".equals(decision)) {
            return "mitigate_debug_ai_hot_chunk";
        }
        if ("investigate_hot_chunk".equals(decision)) {
            return "investigate_debug_ai_hot_chunk";
        }
        return "continue_observing_chat_harmony";
    }

    private static String harmonyActionFromDecision(String harmonyDecision) {
        String decision = firstNonBlank(label(harmonyDecision), "observed");
        if (!"smooth_chat".equalsIgnoreCase(decision)) {
            return "inspect_chat_harmony_trace";
        }
        return "continue_observing_chat_harmony";
    }

    private static String harmonyNextReason(String decision, String reason) {
        String safeDecision = firstNonBlank(label(decision), "observed");
        String safeReason = firstNonBlank(label(reason), safeDecision);
        if (!"smooth_chat".equalsIgnoreCase(safeDecision)) {
            return "chat_harmony." + safeReason;
        }
        return safeDecision;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
