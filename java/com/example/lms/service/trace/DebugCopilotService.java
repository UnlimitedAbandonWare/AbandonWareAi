package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import lombok.extern.slf4j.Slf4j;
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

    public void maybeEnrichTrace() {
        try {
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
                                (orchReason != null ? ("orch.reason=" + clip(String.valueOf(orchReason), 160)) : null)
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
                                auxLast != null ? ("aux.blocked.last=" + clip(String.valueOf(auxLast), 180)) : null,
                                "nightmare.mode=" + asString(TraceStore.get("nightmare.mode"))
                        ),
                        List.of(
                                "# Check breaker state (NightmareBreaker) and AuxBlockTracker events",
                                cmdGrepTrace(traceId, "aux.blocked"),
                                cmdGrepTrace(traceId, "nightmare.")
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

            // ---- Ablation (ranked probability mass) ----
            addAblationCauses(causes, traceId);

            // Final ranking
            List<Cause> ranked = rankTop(causes, MAX_CAUSES);

            boolean ok = ranked.isEmpty();
            TraceStore.put("dbg.copilot.ok", ok);

            // Always provide correlation hints (best-effort)
            if (traceId != null) TraceStore.put("dbg.copilot.traceId", traceId);
            if (sid != null) TraceStore.put("dbg.copilot.sid", sid);

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
                cmds.add("export TRACE_ID=" + (traceId == null ? "" : traceId));
                cmds.add("export SID=" + (sid == null ? "" : sid));
            }
            for (Cause c : ranked) {
                for (String cmd : c.commands) {
                    if (cmd == null || cmd.isBlank()) continue;
                    cmds.add(cmd);
                }
            }
            TraceStore.put("dbg.copilot.actions", limitUnique(cmds, MAX_COMMANDS));

        } catch (Throwable t) {
            log.debug("[DebugCopilot] enrich failed (fail-soft): {}", t.toString());
        }
    }

    private boolean shouldRun() {
        try {
            return truthy(TraceStore.get("dbg.search.enabled"))
                    || truthy(TraceStore.get("dbg.search.boost.active"))
                    || truthy(TraceStore.get("uaw.ablation.bridge"));
        } catch (Throwable ignore) {
            return false;
        }
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
            // fail-soft
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
        String tid = (traceId == null) ? "$TRACE_ID" : traceId;
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
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static double asDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ignore) {
            return def;
        }
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
        String s = String.valueOf(v);
        return s == null ? null : s;
    }

    private static String safeTrim(String s, int maxLen) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        if (maxLen > 0 && t.length() > maxLen) {
            return t.substring(0, Math.max(0, maxLen - 1)) + "…";
        }
        return t;
    }

    private static double round3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 1000.0d) / 1000.0d;
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
