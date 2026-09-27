package com.example.lms.service.trace;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.ensemble.EvidenceGroundedTriadicDebugAdjudicator;
import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A lightweight "debug copilot" that turns common TraceStore breadcrumbs into a short
 * (ranked) findings + commands summary for dbgSearch / boost / ablationBridge sessions.
 *
 * <p>Intentionally best-effort and fail-soft: never throws, never blocks the main request.</p>
 */
@Slf4j
@Service
public class DebugCopilotService {

    private static final int MAX_CAUSES = 3;
    private static final int MAX_COMMANDS = 24;

    private final Supplier<Map<String, Object>> localLlmSmokeSnapshotSupplier;
    private final EvidenceGroundedTriadicDebugAdjudicator triadicAdjudicator;
    private final DebugEventStore debugEventStore;
    private final AtomicReference<EvidenceGroundedTriadicDebugAdjudicator.Adjudication> latestTriadicResult =
            new AtomicReference<>();

    public DebugCopilotService() {
        this(() -> Map.of(), null, null);
    }

    public DebugCopilotService(LocalLlmSmokeHistoryDiagnosticsService localLlmSmokeHistoryDiagnosticsService) {
        this(() -> localLlmSmokeHistoryDiagnosticsService == null
                ? Map.of()
                : localLlmSmokeHistoryDiagnosticsService.snapshot(1),
                null,
                null);
    }

    @Autowired
    public DebugCopilotService(
            LocalLlmSmokeHistoryDiagnosticsService localLlmSmokeHistoryDiagnosticsService,
            EvidenceGroundedTriadicDebugAdjudicator triadicAdjudicator,
            DebugEventStore debugEventStore) {
        this(() -> localLlmSmokeHistoryDiagnosticsService == null
                        ? Map.of()
                        : localLlmSmokeHistoryDiagnosticsService.snapshot(1),
                triadicAdjudicator,
                debugEventStore);
    }

    DebugCopilotService(Supplier<Map<String, Object>> localLlmSmokeSnapshotSupplier) {
        this(localLlmSmokeSnapshotSupplier, null, null);
    }

    DebugCopilotService(
            Supplier<Map<String, Object>> localLlmSmokeSnapshotSupplier,
            EvidenceGroundedTriadicDebugAdjudicator triadicAdjudicator,
            DebugEventStore debugEventStore) {
        this.localLlmSmokeSnapshotSupplier = localLlmSmokeSnapshotSupplier == null
                ? () -> Map.of()
                : localLlmSmokeSnapshotSupplier;
        this.triadicAdjudicator = triadicAdjudicator;
        this.debugEventStore = debugEventStore;
    }

    /**
     * Explicit admin action. This is intentionally separate from
     * {@link #maybeEnrichTrace()} so ordinary chat requests never incur the
     * triadic model calls.
     */
    public EvidenceGroundedTriadicDebugAdjudicator.Adjudication adjudicateLatestPatchCandidate() {
        return adjudicateLatestPatchCandidate(null);
    }

    public EvidenceGroundedTriadicDebugAdjudicator.Adjudication adjudicateLatestPatchCandidate(
            EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate patchCandidate) {
        if (triadicAdjudicator == null || debugEventStore == null) {
            return rememberTriadicResult(EvidenceGroundedTriadicDebugAdjudicator.Adjudication.hold(
                    "triadic_service_unavailable", "none", 0, 0, 0.0d, 0.0d, 0));
        }
        try {
            String rid = firstNonBlank(
                    MDC.get("requestId"),
                    MDC.get("traceId"),
                    asString(TraceStore.get("trace.id")),
                    "debug-admin");
            var result = rememberTriadicResult(triadicAdjudicator.adjudicate(
                    triadicFingerprintEvidence(), patchCandidate, rid));
            try {
                debugEventStore.emit(
                        DebugProbeType.ORCHESTRATION,
                        result.decision() == com.example.lms.ensemble.EnsembleJudgeService.DebugPatchDecision.HOLD
                                ? DebugEventLevel.INFO
                                : DebugEventLevel.WARN,
                        "triadic-debug-adjudication",
                        "triadic debug adjudication",
                        "DebugCopilotService#adjudicateLatestPatchCandidate",
                        result.toSafeMap(),
                        null);
            } catch (CancellationException cancellation) {
                throw cancellation;
            } catch (RuntimeException publicationFailure) {
                CancellationException cancellation = cancellationFrom(publicationFailure);
                if (cancellation != null) {
                    throw cancellation;
                }
                log.debug("[DebugCopilot] triadic event publication failed (fail-soft). errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(publicationFailure)),
                        messageLength(publicationFailure));
            }
            return result;
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (RuntimeException failure) {
            CancellationException cancellation = cancellationFrom(failure);
            if (cancellation != null) {
                throw cancellation;
            }
            log.debug("[DebugCopilot] triadic adjudication failed (fail-soft). errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(failure)),
                    messageLength(failure));
            return rememberTriadicResult(EvidenceGroundedTriadicDebugAdjudicator.Adjudication.hold(
                    "copilot_fail_soft", "none", 0, 0, 0.0d, 0.0d, 0));
        }
    }

    private List<Map<String, Object>> triadicFingerprintEvidence() {
        List<Map<String, Object>> evidence = new ArrayList<>();
        try {
            Map<String, Object> snapshot = localLlmSmokeSnapshotSupplier.get();
            if (snapshot != null && !truthy(snapshot.get("reportStale"))) {
                Map<String, Object> latest = mapValue(snapshot.get("latest"));
                Map<String, Object> attempts = mapValue(latest.get("attemptScores"));
                Map<String, Object> openAi = mapValue(attempts.get("openAiCompatible"));
                Map<String, Object> nativeOllama = mapValue(attempts.get("nativeOllama"));
                int openAiStatus = asInt(openAi.get("status"), 0);
                int nativeStatus = asInt(nativeOllama.get("status"), 0);
                boolean openAiBlank = "blank_response".equals(openAi.get("verdict"))
                        && openAiStatus >= 200 && openAiStatus < 300;
                boolean nativeSuccess = "native_ollama".equals(latest.get("recommendedRoute"))
                        && nativeStatus >= 200 && nativeStatus < 300
                        && asInt(nativeOllama.get("score"), 0) > asInt(openAi.get("score"), 0)
                        && (asInt(nativeOllama.get("contentLength"), 0) > 0
                                || "usable".equals(nativeOllama.get("verdict")));
                if (openAiBlank && nativeSuccess) {
                    evidence.add(Map.of(
                            "fingerprint", "local-llm-smoke-native-success",
                            "routeFamily", "NATIVE_OLLAMA",
                            "routeOutcome", "SUCCESS",
                            "windowCount", 1L,
                            "total", 1L));
                    evidence.add(Map.of(
                            "fingerprint", "local-llm-smoke-openai-blank",
                            "routeFamily", "OPENAI_COMPATIBLE",
                            "routeOutcome", "BLANK",
                            "windowCount", 1L,
                            "total", 1L));
                }
            }
        } catch (RuntimeException failure) {
            traceSuppressed("triadicSmokeEvidence", failure);
        }
        evidence.addAll(debugEventStore.listFingerprints(12));
        return List.copyOf(evidence);
    }

    private static CancellationException cancellationFrom(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException cancellation) {
                return cancellation;
            }
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException("triadic_adjudication_interrupted");
                cancellation.initCause(failure);
                return cancellation;
            }
            current = current.getCause();
        }
        if (Thread.currentThread().isInterrupted()) {
            CancellationException cancellation = new CancellationException("triadic_adjudication_interrupted");
            cancellation.initCause(failure);
            return cancellation;
        }
        return null;
    }

    public EvidenceGroundedTriadicDebugAdjudicator.Adjudication latestTriadicAdjudication() {
        var remembered = latestTriadicResult.get();
        if (remembered != null) {
            return remembered;
        }
        return triadicAdjudicator == null
                ? EvidenceGroundedTriadicDebugAdjudicator.Adjudication.hold(
                        "triadic_service_unavailable", "none", 0, 0, 0.0d, 0.0d, 0)
                : triadicAdjudicator.latest();
    }

    private EvidenceGroundedTriadicDebugAdjudicator.Adjudication rememberTriadicResult(
            EvidenceGroundedTriadicDebugAdjudicator.Adjudication result) {
        var safe = result == null
                ? EvidenceGroundedTriadicDebugAdjudicator.Adjudication.hold(
                "adjudication_missing", "none", 0, 0, 0.0d, 0.0d, 0)
                : result;
        latestTriadicResult.set(safe);
        return safe;
    }

    public void maybeEnrichTrace() {
        try {
            publishLocalLlmSmokeOperatorAction();
            if (!shouldRun()) {
                return;
            }

            String traceId = firstNonBlank(MDC.get("traceId"), MDC.get("trace"), asString(TraceStore.get("trace.id")));
            String sid = firstNonBlank(MDC.get("sessionId"), MDC.get("sid"), asString(TraceStore.get("sid")));

            List<Cause> causes = new ArrayList<>();

            // ---- High-signal hard failures ----
            Object qtxCode = TraceStore.get("qtx.llm.error.code");
            if (qtxCode != null && "MODEL_REQUIRED".equalsIgnoreCase(String.valueOf(qtxCode))) {
                causes.add(Cause.of(
                        "qtx_model_required",
                        "QueryTransformer LLM unavailable (MODEL_REQUIRED) → heuristic/bypass path",
                        0.98,
                        List.of("qtx.llm.error.code=" + qtxCode),
                        List.of(
                                "# Check QueryTransformer model wiring / provider health",
                                cmdGrepTrace(traceId, "qtx.llm.error"),
                                "# Verify model config (provider/model) and rate limits"
                        )));
            }

            Object webSkipped = TraceStore.get("web.await.skipped.last");
            if (webSkipped != null && "missing_future".equalsIgnoreCase(String.valueOf(webSkipped))) {
                causes.add(Cause.of(
                        "web_missing_future",
                        "Web await skipped (missing_future) → async wiring bug likely",
                        0.92,
                        List.of("web.await.skipped.last=" + webSkipped),
                        List.of(
                                "# Find where Future is created/stored vs awaited",
                                cmdGrepTrace(traceId, "web.await.skipped"),
                                "# Verify executor/scheduler wiring for web search"
                        )));
            }

            // ---- Orchestration mode (quality cliffs) ----
            boolean strike = truthy(TraceStore.get("orch.strike"));
            boolean compression = truthy(TraceStore.get("orch.compression"));
            boolean bypass = truthy(TraceStore.get("orch.bypass"));
            Object orchReason = TraceStore.get("orch.reason");
            if (strike || bypass || compression) {
                double s = bypass ? 0.78 : (strike ? 0.72 : 0.55);
                causes.add(Cause.of(
                        "orch_mode",
                        "Orchestration entered " + (bypass ? "BYPASS" : (strike ? "STRIKE" : "COMPRESSION"))
                                + " (feature reduction / rerank changes)",
                        s,
                        List.of(
                                "orch.mode=" + asString(TraceStore.get("orch.mode")),
                                (orchReason != null ? ("orch.reason=" + asString(orchReason)) : null)
                        ),
                        List.of(
                                "# Inspect orchestration reasons + breaker/aux signals",
                                cmdGrepTrace(traceId, "orch."),
                                "# If this is a false positive, tune thresholds (irr/aux/web) or enable noise-gate escape"
                        )));
            }

            // ---- Aux blocks (breaker/guard) ----
            Object auxLast = TraceStore.get("aux.blocked.last");
            int auxCnt = asInt(TraceStore.get("aux.blocked.count"), 0);
            if (auxLast != null || auxCnt > 0 || truthy(TraceStore.get("aux.blocked"))) {
                causes.add(Cause.of(
                        "aux_blocked",
                        "Aux steps were blocked (breaker/guard)",
                        0.80,
                        List.of(
                                "aux.blocked.count=" + auxCnt,
                                auxLast != null ? ("aux.blocked.last=" + asString(auxLast)) : null,
                                "nightmare.mode=" + asString(TraceStore.get("nightmare.mode"))
                        ),
                        List.of(
                                "# Check breaker state (NightmareBreaker) and AuxBlockTracker events",
                                cmdGrepTrace(traceId, "aux.blocked"),
                                cmdGrepTrace(traceId, "nightmare.")
                        )));
            }

            // ---- Nightmare silent failures / final rescue ----
            Object silentEvents = TraceStore.get("nightmare.silent.events");
            boolean finalRescueUsed = truthy(TraceStore.get("nightmare.finalRescue.used"));
            int silentCount = eventCount(silentEvents);
            if (finalRescueUsed || silentCount > 0) {
                causes.add(Cause.of(
                        "nightmare_silent_failure",
                        "NightmareBreaker observed silent failure / final rescue",
                        finalRescueUsed ? 0.90 : 0.82,
                        List.of(
                                "nightmare.silent.eventCount=" + silentCount,
                                "nightmare.finalRescue.used=" + finalRescueUsed,
                                "nightmare.finalRescue.reason=" + asString(TraceStore.get("nightmare.finalRescue.reason")),
                                "nightmare.finalRescue.evidenceCount=" + asString(TraceStore.get("nightmare.finalRescue.evidenceCount")),
                                "nightmare.finalRescue.queryHash=" + asString(TraceStore.get("nightmare.finalRescue.queryHash"))
                        ),
                        List.of(
                                "# Inspect silent-failure / final-rescue breadcrumbs",
                                cmdGrepTrace(traceId, "nightmare.silent"),
                                cmdGrepTrace(traceId, "nightmare.finalRescue"),
                                cmdGrepTrace(traceId, "faultmask.")
                        )));
            }

            // ---- Keyword selection anomalies ----
            Object kwMode = TraceStore.get("keywordSelection.mode");
            if ("fallback_blank".equals(String.valueOf(kwMode))) {
                causes.add(Cause.of(
                        "keyword_fallback_blank",
                        "keywordSelection used blank fallback (empty extraction)",
                        0.70,
                        List.of(
                                "keywordSelection.mode=" + kwMode,
                                "keywordSelection.domainProfile=" + asString(TraceStore.get("keywordSelection.domainProfile")),
                                "aux.keywordSelection.degraded=" + asString(TraceStore.get("aux.keywordSelection.degraded")),
                                "aux.keywordSelection.degraded.reason=" + asString(TraceStore.get("aux.keywordSelection.degraded.reason"))
                        ),
                        List.of(
                                "# Verify KeywordSelectionService prompt inputs + language heuristics",
                                cmdGrepTrace(traceId, "keywordSelection."),
                                "# Check that NoiseRoutingGate/aux-blocking didn't force fallback"
                        )));
            } else if ("blocked".equalsIgnoreCase(String.valueOf(kwMode)) || "bypassed".equalsIgnoreCase(String.valueOf(kwMode))) {
                double s = "blocked".equalsIgnoreCase(String.valueOf(kwMode)) ? 0.62 : 0.50;
                causes.add(Cause.of(
                        "keyword_blocked_or_bypassed",
                        "keywordSelection " + String.valueOf(kwMode) + " (reduced query expansion)",
                        s,
                        List.of(
                                "keywordSelection.mode=" + kwMode,
                                "keywordSelection.reason=" + asString(TraceStore.get("keywordSelection.reason")),
                                "keywordSelection.bypass.reason=" + asString(TraceStore.get("keywordSelection.bypass.reason"))
                        ),
                        List.of(
                                "# Inspect keyword selection gating (COMPRESSION/STRIKE/breakers)",
                                cmdGrepTrace(traceId, "keywordSelection."),
                                cmdGrepTrace(traceId, "orch.")
                        )));
            }

            // ---- QueryTransformer degraded/bypass path ----
            boolean qtDegraded = truthy(TraceStore.get("aux.queryTransformer.degraded"));
            boolean qtBypass = truthy(TraceStore.get("qtx.bypass"));
            if (qtDegraded || qtBypass) {
                causes.add(Cause.of(
                        "qtx_degraded",
                        "QueryTransformer degraded/bypass active (cheap fallback)",
                        0.66,
                        List.of(
                                "qtx.bypass.trigger=" + asString(TraceStore.get("qtx.bypass.trigger")),
                                "qtx.bypass.reason=" + asString(TraceStore.get("qtx.bypass.reason")),
                                "aux.queryTransformer=" + asString(TraceStore.get("aux.queryTransformer")),
                                "aux.queryTransformer.degraded.reason=" + asString(TraceStore.get("aux.queryTransformer.degraded.reason")),
                                "orch.failsoft.queryAugment.reason=" + asString(TraceStore.get("orch.failsoft.queryAugment.reason")),
                                "orch.failsoft.queryAugment.used=" + asString(TraceStore.get("orch.failsoft.queryAugment.used"))
                        ),
                        List.of(
                                "# Inspect QueryTransformer bypass trigger",
                                cmdGrepTrace(traceId, "qtx.bypass")
                        )));
            }

            // ---- Noise escape (probabilistic override) ----
            boolean qtxNoiseEsc = truthy(TraceStore.get("qtx.noise.escape.used")) || truthy(TraceStore.get("qtx.noiseEscape"));
            boolean orchNoiseEsc = truthy(TraceStore.get("orch.noiseEscape.used"))
                    || truthy(TraceStore.get("orch.noiseEscape.bypassSilentFailure"));
            boolean auxNoiseOv = truthy(TraceStore.get("aux.noiseOverride"));
            if (qtxNoiseEsc || orchNoiseEsc || auxNoiseOv
                    || truthy(TraceStore.get("keywordSelection.noiseEscape"))
                    || truthy(TraceStore.get("keywordSelection.bypass.noiseEscape"))
                    || truthy(TraceStore.get("disambiguation.noiseEscape"))) {
                causes.add(Cause.of(
                        "noise_escape",
                        "Noise escape / noise override used (probabilistic bypass of blocking)",
                        0.38,
                        List.of(
                                qtxNoiseEsc ? "qtx.noiseEscape=true" : null,
                                orchNoiseEsc ? "orch.noiseEscape.used=true" : null,
                                auxNoiseOv ? "aux.noiseOverride=true" : null
                        ),
                        List.of(
                                "# Check NoiseRoutingGate configuration",
                                "# -Dorch.noiseGate.enabled=true",
                                "# Review AuxBlockTracker noiseOverride events: aux.noiseOverride.*",
                                cmdGrepTrace(traceId, "orch.noiseGate."),
                                cmdGrepTrace(traceId, "aux.noiseOverride")
                        )));
            }

            // ---- Embedding failover / cache invalidation ----
            boolean embedFailover = truthy(TraceStore.get("embed.failover.used")) || truthy(TraceStore.get("embed.failover.used.cur"));
            boolean cacheInvalidated = truthy(TraceStore.get("embed.cache.invalidate.failover"));
            if (embedFailover || cacheInvalidated) {
                causes.add(Cause.of(
                        "embed_failover",
                        "Embedding failover used (fallback model) → cache invalidated",
                        0.76,
                        List.of(
                                "embed.failover.used=" + asString(TraceStore.get("embed.failover.used")),
                                "embed.failover.stage.last=" + asString(TraceStore.get("embed.failover.stage.last")),
                                "embed.cache.invalidate.failover=" + asString(TraceStore.get("embed.cache.invalidate.failover"))
                        ),
                        List.of(
                                "# Inspect embedding provider errors / failover markers",
                                cmdGrepTrace(traceId, "embed.failover"),
                                cmdGrepTrace(traceId, "embed.error")
                        )));
            }

            // ---- Vector guards / quality drops ----
            int poisonDropped = asInt(TraceStore.get("vector.poison.dropped"), 0);
            int qualityDropped = asInt(TraceStore.get("vector.quality.dropped"), 0);
            int kbQualityDropped = asInt(TraceStore.get("kb.domain.quality.dropped"), 0);
            if (poisonDropped > 0 || qualityDropped > 0 || kbQualityDropped > 0) {
                causes.add(Cause.of(
                        "vector_drops",
                        "Vector results were filtered (poison/quality)",
                        0.46,
                        List.of(
                                poisonDropped > 0 ? ("vector.poison.dropped=" + poisonDropped) : null,
                                qualityDropped > 0 ? ("vector.quality.dropped=" + qualityDropped) : null,
                                kbQualityDropped > 0 ? ("kb.domain.quality.dropped=" + kbQualityDropped) : null
                        ),
                        List.of(
                                "# Inspect VectorPoisonGuard / VectorQualityGuard decisions",
                                cmdGrepTrace(traceId, "vector."),
                                "# Check verified/quarantine metadata in retrieved segments"
                        )));
            }

            // ---- LLM runtime health pressure (model route degradation) ----
            addLlmHealthPressureCause(causes, traceId);

            // ---- RAG failure blackbox (risk + restore decision) ----
            addBlackboxCause(causes, traceId);

            // ---- Self-Ask rewrite risk (risk-weighted requery diagnostics) ----
            addRewriteRiskCauses(causes, traceId);

            // ---- Ablation (ranked probability mass) ----
            addAblationCauses(causes, traceId);

            // Final ranking
            List<Cause> ranked = rankTop(causes, MAX_CAUSES);

            boolean ok = ranked.isEmpty();
            TraceStore.put("dbg.copilot.ok", ok);

            // Always provide correlation hints (best-effort)
            if (traceId != null) TraceStore.put("dbg.copilot.traceId", SafeRedactor.hashValue(traceId));
            if (sid != null) TraceStore.put("dbg.copilot.sid", SafeRedactor.hashValue(sid));

            if (ok) {
                TraceStore.put("dbg.copilot.summary",
                        "No obvious degradation signals detected (based on TraceStore breadcrumbs)."
                                + " (Try checking embedding provider health / vectorstore upserts / orchestration strike/bypass.)");
                TraceStore.put("dbg.copilot.actions", List.of(
                        "# If output still looks off, grep by trace id",
                        cmdGrepTrace(traceId, ""),
                        "# Then inspect: orch.*, qtx.*, aux.*, embed.*, vector.* keys"
                ));
                return;
            }

            // Structured causes for Trace UI
            List<Map<String, Object>> out = new ArrayList<>();
            int rank = 1;
            for (Cause c : ranked) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", rank++);
                row.put("id", c.id);
                row.put("score", round3(c.score));
                row.put("title", c.title);
                if (!c.evidence.isEmpty()) row.put("evidence", c.evidence);
                if (!c.commands.isEmpty()) row.put("commands", c.commands);
                out.add(row);
            }
            TraceStore.put("dbg.copilot.causes", out);

            // Short summary (top 1~3)
            StringBuilder sum = new StringBuilder();
            sum.append("Top causes: ");
            for (int i = 0; i < ranked.size(); i++) {
                if (i > 0) sum.append(" · ");
                sum.append(i + 1).append(") ").append(ranked.get(i).title);
            }
            TraceStore.put("dbg.copilot.summary", clip(sum.toString(), 900));

            // Flatten commands
            List<String> cmds = new ArrayList<>();
            if (traceId != null || sid != null) {
                cmds.add("export TRACE_ID_HASH=" + (traceId == null ? "" : SafeRedactor.hashValue(traceId)));
                cmds.add("export SID_HASH=" + (sid == null ? "" : SafeRedactor.hashValue(sid)));
            }
            for (Cause c : ranked) {
                for (String cmd : c.commands) {
                    if (cmd == null || cmd.isBlank()) continue;
                    cmds.add(cmd);
                }
            }
            TraceStore.put("dbg.copilot.actions", limitUnique(cmds, MAX_COMMANDS));

        } catch (Throwable t) {
            log.debug("[DebugCopilot] enrich failed (fail-soft). errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(t)), messageLength(t));
        }
    }

    private boolean shouldRun() {
        try {
            return truthy(TraceStore.get("dbg.search.enabled"))
                    || truthy(TraceStore.get("dbg.search.boost.active"))
                    || truthy(TraceStore.get("uaw.ablation.bridge"));
        } catch (Throwable ignore) {
            traceSuppressed("shouldRun", ignore);
            return false;
        }
    }

    private static void addBlackboxCause(List<Cause> causes, String traceId) {
        try {
            double risk = asDouble(TraceStore.get("blackbox.risk.riskScore"), 0.0d);
            String failure = asString(TraceStore.get("blackbox.risk.dominantFailure"));
            if (risk <= 0.0d || failure == null || failure.isBlank() || "none".equalsIgnoreCase(failure)) {
                return;
            }
            String action = asString(TraceStore.get("blackbox.risk.restoreAction"));
            String hotspot = asString(TraceStore.get("blackbox.risk.hotspot"));
            causes.add(Cause.of(
                    "rag_blackbox_" + failure,
                    "RAG failure blackbox ranked " + failure + " for " + action,
                    Math.max(0.55d, Math.min(0.98d, risk)),
                    List.of(
                            "blackbox.risk.riskScore=" + round3(risk),
                            "blackbox.risk.dominantFailure=" + failure,
                            "blackbox.risk.hotspot=" + hotspot,
                            "blackbox.risk.restoreAction=" + action,
                            "blackbox.risk.patternId=" + asString(TraceStore.get("blackbox.risk.patternId"))
                    ),
                    List.of(
                            "# Inspect blackbox projection and source diagnostic counts",
                            cmdGrepTrace(traceId, "blackbox.risk"),
                            cmdGrepTrace(traceId, "ablation.")
                    )));
        } catch (Throwable ignore) {
            traceSuppressed("blackboxCause", ignore);
        }
    }

    private void publishLocalLlmSmokeOperatorAction() {
        try {
            Map<String, Object> snapshot = localLlmSmokeSnapshotSupplier.get();
            if (snapshot == null || snapshot.isEmpty()) {
                return;
            }
            if (truthy(snapshot.get("reportStale"))
                    || "supporting_stale".equalsIgnoreCase(asString(snapshot.get("evidenceMode")))) {
                TraceStore.put("llm.localSmoke.operatorAction.stale", true);
                TraceStore.put("llm.localSmoke.operatorAction.evidenceMode", "supporting_stale");
                TraceStore.put("llm.localSmoke.operatorAction.staleReason", "smoke_report_stale");
                return;
            }
            Map<String, Object> latest = mapValue(snapshot.get("latest"));
            Map<String, Object> operatorAction = mapValue(latest.get("operatorAction"));
            if (operatorAction.isEmpty()) {
                return;
            }

            boolean triggered = truthy(operatorAction.get("triggered"));
            String triggerReason = safeTrim(asString(operatorAction.get("triggerReason")), 80);
            String failureClass = safeTrim(asString(operatorAction.get("failureClass")), 80);
            String nextAction = safeTrim(asString(operatorAction.get("nextAction")), 100);
            int actionScore = asInt(operatorAction.get("actionScore"), 0);
            int scoreDelta = asInt(operatorAction.get("scoreDelta"), 0);
            int negativeSignalCount = asInt(operatorAction.get("negativeSignalCount"), 0);
            if (!triggered && actionScore <= 0 && scoreDelta <= 0) {
                return;
            }

            TraceStore.put("llm.localSmoke.operatorAction.triggered", triggered);
            TraceStore.put("llm.localSmoke.operatorAction.triggerReason", firstNonBlank(triggerReason, "none"));
            TraceStore.put("llm.localSmoke.operatorAction.failureClass", firstNonBlank(failureClass, "none"));
            TraceStore.put("llm.localSmoke.operatorAction.nextAction", firstNonBlank(nextAction, "monitor_local_llm_route"));
            TraceStore.put("llm.localSmoke.operatorAction.actionScore", actionScore);
            TraceStore.put("llm.localSmoke.operatorAction.scoreDelta", scoreDelta);
            TraceStore.put("llm.localSmoke.operatorAction.negativeSignalCount", negativeSignalCount);

            Map<String, Object> cumulative = mapValue(latest.get("cumulativeSignals"));
            int sampleCount = asInt(cumulative.get("sampleCount"), Math.max(1, negativeSignalCount));
            double pressure = Math.max(0.0d, Math.min(1.0d, actionScore / 100.0d));
            double currentPressure = asDouble(TraceStore.get("llm.gateway.route.healthFailurePressure"), 0.0d);
            TraceStore.put("llm.gateway.route.healthFailurePressure", Math.max(currentPressure, pressure));
            TraceStore.put("llm.gateway.route.healthFailureCount", (long) Math.max(0, negativeSignalCount));
            TraceStore.put("llm.gateway.route.healthSampleCount", (long) Math.max(1, sampleCount));
            if ("prefer_native_ollama_route".equalsIgnoreCase(nextAction)
                    || "model_blank".equalsIgnoreCase(failureClass)) {
                TraceStore.put("llm.gateway.route.healthRoutingHint", "llm_route_degrade");
            }
        } catch (Throwable ignore) {
            traceSuppressed("localLlmSmokeOperatorAction", ignore);
        }
    }

    private static void addLlmHealthPressureCause(List<Cause> causes, String traceId) {
        try {
            double pressure = asDouble(TraceStore.get("llm.gateway.route.healthFailurePressure"), 0.0d);
            String hint = asString(TraceStore.get("llm.gateway.route.healthRoutingHint"));
            boolean degradeHint = "llm_route_degrade".equalsIgnoreCase(String.valueOf(hint));
            if (pressure < 0.50d && !degradeHint) {
                return;
            }
            causes.add(Cause.of(
                    "llm_health_pressure",
                    "LLM runtime health pressure exceeded threshold (route degradation suggested)",
                    Math.max(0.55d, Math.min(0.94d, pressure)),
                    List.of(
                            "llm.gateway.route.healthFailurePressure=" + round3(pressure),
                            "llm.gateway.route.healthFailureCount=" + asString(TraceStore.get("llm.gateway.route.healthFailureCount")),
                            "llm.gateway.route.healthSampleCount=" + asString(TraceStore.get("llm.gateway.route.healthSampleCount")),
                            "llm.gateway.route.healthRoutingHint=" + hint,
                            "localLlm.operatorAction.triggerReason=" + asString(TraceStore.get("llm.localSmoke.operatorAction.triggerReason")),
                            "localLlm.operatorAction.failureClass=" + asString(TraceStore.get("llm.localSmoke.operatorAction.failureClass")),
                            "localLlm.operatorAction.nextAction=" + asString(TraceStore.get("llm.localSmoke.operatorAction.nextAction")),
                            "localLlm.operatorAction.actionScore=" + asString(TraceStore.get("llm.localSmoke.operatorAction.actionScore")),
                            "localLlm.operatorAction.scoreDelta=" + asString(TraceStore.get("llm.localSmoke.operatorAction.scoreDelta")),
                            "localLlm.operatorAction.negativeSignalCount=" + asString(TraceStore.get("llm.localSmoke.operatorAction.negativeSignalCount"))
                    ),
                    List.of(
                            "# Inspect LLM route health, client failure, and native Ollama breadcrumbs",
                            cmdGrepTrace(traceId, "llm.gateway.route.health"),
                            cmdGrepTrace(traceId, "llm.client"),
                            cmdGrepTrace(traceId, "llm.ollamaNative")
                    )));
        } catch (Throwable ignore) {
            traceSuppressed("llmHealthPressureCause", ignore);
        }
    }

    private static void addRewriteRiskCauses(List<Cause> causes, String traceId) {
        try {
            String band = asString(TraceStore.get("ml.risk.rewrite.band"));
            String primary = asString(TraceStore.get("ml.risk.rewrite.primaryFactor"));
            double score = asDouble(firstNonNull(
                    TraceStore.get("ml.risk.rewrite.accumulatedScore"),
                    TraceStore.get("ml.risk.rewrite.score")), 0.0);
            double current = asDouble(TraceStore.get("ml.risk.rewrite.currentScore"), score);
            Object summary = TraceStore.get("selfask.requery.summary");
            if ("HIGH".equalsIgnoreCase(band) || score >= 0.70d) {
                causes.add(Cause.of(
                        "rewrite_risk_high",
                        "Self-Ask rewrite risk is high (risk-weighted requery active)",
                        Math.max(0.70d, Math.min(0.96d, score)),
                        List.of(
                                "ml.risk.rewrite.band=" + band,
                                "ml.risk.rewrite.score=" + round3(score),
                                "ml.risk.rewrite.currentScore=" + round3(current),
                                "ml.risk.rewrite.primaryFactor=" + primary,
                                summary != null ? "selfask.requery.summary=" + safeRequerySummary(summary) : null
                        ),
                        List.of(
                                "# Inspect risk-weighted Self-Ask breadcrumbs",
                                cmdGrepTrace(traceId, "ml.risk.rewrite"),
                                cmdGrepTrace(traceId, "selfask.requery")
                        )));
            }

            boolean starvation = "scarcity".equalsIgnoreCase(primary)
                    || "afterFilterStarvation".equalsIgnoreCase(primary)
                    || hasAttemptFailure("zero-results")
                    || hasAttemptFailure("timeout");
            if (starvation) {
                causes.add(Cause.of(
                        "rewrite_starvation",
                        "Self-Ask requery appears starved (scarcity/after-filter/timeout)",
                        Math.max(0.58d, Math.min(0.88d, score)),
                        List.of(
                                "primaryFactor=" + primary,
                                "selfask.requery.attempts.present=" + (TraceStore.get("selfask.requery.attempts") != null),
                                "web.naver.afterFilterCount=" + asString(TraceStore.get("web.naver.afterFilterCount")),
                                "web.brave.afterFilterCount=" + asString(TraceStore.get("web.brave.afterFilterCount")),
                                "web.serpapi.afterFilterCount=" + asString(TraceStore.get("web.serpapi.afterFilterCount")),
                                "web.tavily.afterFilterCount=" + asString(TraceStore.get("web.tavily.afterFilterCount"))
                        ),
                        List.of(
                                "# Inspect per-lane returnedCount/afterFilterCount",
                                cmdGrepTrace(traceId, "selfask.requery.attempts"),
                                "# Then inspect provider returnedCount/afterFilterCount",
                                cmdGrepTrace(traceId, "afterFilterCount")
                        )));
            }

            boolean providerFailure = "providerFailure".equalsIgnoreCase(primary)
                    || truthy(TraceStore.get("web.naver.providerDisabled"))
                    || truthy(TraceStore.get("web.brave.providerDisabled"))
                    || truthy(TraceStore.get("web.serpapi.providerDisabled"))
                    || truthy(TraceStore.get("web.tavily.providerDisabled"))
                    || TraceStore.get("selfask.3way.api.disabledReason") != null;
            if (providerFailure) {
                causes.add(Cause.of(
                        "rewrite_provider_failure",
                        "Self-Ask rewrite risk is dominated by provider disabled/failure signals",
                        Math.max(0.62d, Math.min(0.90d, score)),
                        List.of(
                                "primaryFactor=" + primary,
                                "web.naver.providerDisabled=" + asString(TraceStore.get("web.naver.providerDisabled")),
                                "web.brave.providerDisabled=" + asString(TraceStore.get("web.brave.providerDisabled")),
                                "web.serpapi.providerDisabled=" + asString(TraceStore.get("web.serpapi.providerDisabled")),
                                "web.tavily.providerDisabled=" + asString(TraceStore.get("web.tavily.providerDisabled")),
                                "selfask.3way.api.disabledReason=" + asString(TraceStore.get("selfask.3way.api.disabledReason"))
                        ),
                        List.of(
                                "# Inspect provider disabled reasons and key-source guards",
                                cmdGrepTrace(traceId, "providerDisabled"),
                                cmdGrepTrace(traceId, "disabledReason")
                        )));
            }

            Object components = TraceStore.get("ml.risk.rewrite.components");
            double contradictionPressure = Math.max(
                    componentValue(components, "contradiction"),
                    asDouble(TraceStore.get("overdrive.contradiction.mean"), 0.0d));
            if ("contradiction".equalsIgnoreCase(primary) || contradictionPressure > 0.0d) {
                causes.add(Cause.of(
                        "rewrite_contradiction_pressure",
                        "Self-Ask rewrite risk is driven by contradiction-check pressure",
                        Math.max(0.56d, Math.min(0.86d, Math.max(score, contradictionPressure))),
                        List.of(
                                "primaryFactor=" + primary,
                                "ml.risk.rewrite.components.contradiction="
                                        + round3(componentValue(components, "contradiction")),
                                "overdrive.contradiction.mean="
                                        + round3(asDouble(TraceStore.get("overdrive.contradiction.mean"), 0.0d)),
                                "selfask.3way.requery.confirmed="
                                        + asString(TraceStore.get("selfask.3way.requery.confirmed"))
                        ),
                        List.of(
                                "# Inspect contradiction and 3-way Self-Ask confirmation",
                                cmdGrepTrace(traceId, "contradiction"),
                                cmdGrepTrace(traceId, "selfask.3way")
                        )));
            }

            double latencyComponent = componentValue(components, "latencyPressure");
            double timeoutCount = asDouble(TraceStore.get("web.await.events.timeout.count"), 0.0d);
            boolean latencyPressure = "latencyPressure".equalsIgnoreCase(primary)
                    || latencyComponent > 0.0d
                    || timeoutCount > 0.0d;
            if (latencyPressure) {
                causes.add(Cause.of(
                        "rewrite_latency_pressure",
                        "Self-Ask rewrite risk is cooling search because latency pressure is present",
                        Math.max(0.54d, Math.min(0.84d, Math.max(score, latencyComponent))),
                        List.of(
                                "primaryFactor=" + primary,
                                "ml.risk.rewrite.components.latencyPressure=" + round3(latencyComponent),
                                "web.await.events.count=" + asString(TraceStore.get("web.await.events.count")),
                                "web.await.events.timeout.count=" + asString(TraceStore.get("web.await.events.timeout.count"))
                        ),
                        List.of(
                                "# Inspect latency pressure and cooled rewrite temperature",
                                cmdGrepTrace(traceId, "web.await"),
                                cmdGrepTrace(traceId, "ml.risk.rewrite.temperature")
                        )));
            }
        } catch (Throwable ignore) {
            traceSuppressed("providerFailureCause", ignore);
        }
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static double componentValue(Object components, String key) {
        if (!(components instanceof Map<?, ?> map) || key == null) {
            return 0.0d;
        }
        return asDouble(map.get(key), 0.0d);
    }

    private static String safeRequerySummary(Object summary) {
        if (!(summary instanceof Map<?, ?> map)) {
            return "<present>";
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("laneCoverage", "seedCount", "uniqueSeedCount",
                "requeryConfirmed", "primaryFactor", "policy")) {
            if (map.containsKey(key)) {
                safe.put(key, map.get(key));
            }
        }
        return safe.isEmpty() ? "<present>" : clip(String.valueOf(safe), 220);
    }

    private static boolean hasAttemptFailure(String failureClass) {
        Object attempts = TraceStore.get("selfask.requery.attempts");
        if (!(attempts instanceof Iterable<?> iterable) || failureClass == null) {
            return false;
        }
        for (Object attempt : iterable) {
            if (attempt instanceof Map<?, ?> map) {
                Object value = map.get("failureClass");
                if (failureClass.equalsIgnoreCase(String.valueOf(value))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int eventCount(Object events) {
        if (events == null) return 0;
        if (events instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object ignored : iterable) {
                count++;
            }
            return count;
        }
        return 1;
    }

    private static void addAblationCauses(List<Cause> causes, String traceId) {
        try {
            Object obj = TraceStore.get("ablation.probabilities");
            if (!(obj instanceof List<?> list) || list.isEmpty()) {
                return;
            }
            int added = 0;
            for (Object o : list) {
                if (added >= 3) break;
                if (!(o instanceof Map<?, ?> m)) continue;

                String step = safeTrim(asString(m.get("step")), 80);
                String guard = safeTrim(asString(m.get("guard")), 120);
                double p = asDouble(m.get("p"), 0.0);
                double delta = asDouble(m.get("delta"), 0.0);

                if (p <= 0.0) continue;

                String title = "Ablation: " + (step == null ? "<step>" : step) + " / " + (guard == null ? "<guard>" : guard);
                List<String> ev = new ArrayList<>();
                ev.add("p=" + round3(p));
                if (delta != 0.0) ev.add("delta=" + round3(delta));

                causes.add(Cause.of(
                        "ablation_" + (step == null ? "" : step) + "_" + (guard == null ? "" : guard),
                        title,
                        Math.min(0.65, Math.max(0.20, p)),
                        ev,
                        List.of(
                                "# Inspect ablation/attribution breakdown",
                                cmdGrepTrace(traceId, "ablation."),
                                "# Check guard/step implementation for degraded path"
                        )));

                added++;
            }
        } catch (Throwable ignore) {
            traceSuppressed("ablationCause", ignore);
        }
    }

    private static List<Cause> rankTop(List<Cause> in, int max) {
        if (in == null || in.isEmpty()) return List.of();
        List<Cause> copy = new ArrayList<>();
        for (Cause c : in) {
            if (c == null) continue;
            // drop empty title
            if (c.title == null || c.title.isBlank()) continue;
            copy.add(c);
        }
        if (copy.isEmpty()) return List.of();

        // Deduplicate by id (keep max score)
        Map<String, Cause> best = new LinkedHashMap<>();
        for (Cause c : copy) {
            String id = (c.id == null || c.id.isBlank()) ? c.title : c.id;
            Cause prev = best.get(id);
            if (prev == null || c.score > prev.score) {
                best.put(id, c);
            }
        }
        List<Cause> uniq = new ArrayList<>(best.values());
        uniq.sort((a, b) -> Double.compare(b.score, a.score));
        if (uniq.size() > max) {
            return new ArrayList<>(uniq.subList(0, max));
        }
        return uniq;
    }

    private static List<String> limitUnique(List<String> in, int max) {
        if (in == null || in.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : in) {
            if (s == null) continue;
            String t = s.strip();
            if (t.isEmpty()) continue;
            seen.add(t);
            if (seen.size() >= max) break;
        }
        return new ArrayList<>(seen);
    }

    private static String cmdGrepTrace(String traceId, String keyPrefix) {
        String tid = (traceId == null) ? "$TRACE_ID_HASH" : SafeRedactor.hashValue(traceId);
        String kp = (keyPrefix == null) ? "" : keyPrefix;
        // Keep commands generic: users may store logs elsewhere.
        if (kp.isBlank()) {
            return "rg \"" + escapeShell(tid) + "\" ./logs -n | tail -n 200";
        }
        return "rg \"" + escapeShell(tid) + "\" ./logs -n | rg \"" + escapeShell(kp) + "\" | tail -n 200";
    }

    private static String escapeShell(String s) {
        if (s == null) return "";
        // very small escape so commands remain copy-pastable
        return s.replace("\"", "\\\"");
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s);
    }

    private static int asInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return n.intValue();
            }
            traceSuppressed("asInt", "invalid_number");
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("asInt", ignore);
            return def;
        }
    }

    private static double asDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return numeric;
            }
            traceSuppressed("asDouble", "invalid_number");
            return def;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(v).trim());
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            traceSuppressed("asDouble", "invalid_number");
            return def;
        } catch (NumberFormatException ignore) {
            traceSuppressed("asDouble", ignore);
            return def;
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String errorType = failure instanceof NumberFormatException
                ? "invalid_number"
                : SafeRedactor.traceLabelOrFallback(failure == null ? null : failure.getClass().getSimpleName(), "unknown");
        traceSuppressed(stage, errorType);
    }

    private static void traceSuppressed(String stage, String errorType) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("debug.copilot.suppressed." + safeStage, true);
        TraceStore.put("debug.copilot.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String clip(String s, int maxLen) {
        if (s == null) return "";
        if (maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    private static String firstNonBlank(String... ss) {
        if (ss == null) return null;
        for (String s : ss) {
            if (s != null && !s.isBlank() && !"null".equalsIgnoreCase(s)) return s;
        }
        return null;
    }

    private static String asString(Object v) {
        if (v == null) return null;
        return SafeRedactor.traceLabel(v);
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String safeTrim(String s, int maxLen) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        if (maxLen > 0 && t.length() > maxLen) {
            return t.substring(0, Math.max(0, maxLen - 1)) + "...";
        }
        return t;
    }

    private static double round3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 1000.0d) / 1000.0d;
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        return messageOf(t).length();
    }

    private record Cause(String id, String title, double score, List<String> evidence, List<String> commands) {
        static Cause of(String id, String title, double score, List<String> evidence, List<String> commands) {
            List<String> ev = new ArrayList<>();
            if (evidence != null) {
                for (String e : evidence) {
                    if (e == null) continue;
                    String t = e.strip();
                    if (t.isEmpty() || "null".equalsIgnoreCase(t)) continue;
                    ev.add(t);
                }
            }
            List<String> cmds = (commands == null) ? List.of() : new ArrayList<>(commands);
            return new Cause(
                    (id == null ? "" : id),
                    (title == null ? "" : title),
                    score,
                    Collections.unmodifiableList(ev),
                    Collections.unmodifiableList(cmds)
            );
        }
    }
}
