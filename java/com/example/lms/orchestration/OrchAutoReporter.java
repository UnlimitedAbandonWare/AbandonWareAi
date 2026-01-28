package com.example.lms.orchestration;

import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TraceStore-based auto reporter for orchestration regressions.
 *
 * <p>
 * Goal: turn raw trace keys into a <b>human-friendly RCA snapshot</b>.
 * It intentionally mixes three independent signal families:
 * <ul>
 *   <li><b>Ablation</b> (orch.debug.ablation.*) → "what actually pushed STRIKE/BYPASS probability"</li>
 *   <li><b>Breaker meta</b> (nightmare.breaker.openKind/openErrMsg) → "what broke and why"</li>
 *   <li><b>Irregularity timeline</b> (irregularity.events) → "why silent-failure gates fired"</li>
 * </ul>
 *
 * <p>
 * Output is stored under:
 * <ul>
 *   <li>{@code orch.autoReport.version}</li>
 *   <li>{@code orch.autoReport}</li>
 *   <li>{@code orch.autoReport.text} (short markdown-ish summary)</li>
 * </ul>
 */
public final class OrchAutoReporter {

    /** Bump this when the report schema changes. */
    public static final String VERSION = "v3";

    /** TraceStore key for the main report object. */
    public static final String TRACE_REPORT_KEY = "orch.autoReport";
    public static final String TRACE_REPORT_VERSION_KEY = "orch.autoReport.version";
    public static final String TRACE_REPORT_TEXT_KEY = "orch.autoReport.text";

    private OrchAutoReporter() {
    }

    /** Build report from the current request TraceStore and write it back to TraceStore. */
    public static void emitToTrace() {
        try {
            Map<String, Object> report = buildFrom(TraceStore::get);
            TraceStore.put(TRACE_REPORT_VERSION_KEY, VERSION);
            TraceStore.put(TRACE_REPORT_KEY, report);
            TraceStore.put(TRACE_REPORT_TEXT_KEY, renderText(report));
        } catch (Throwable ignore) {
            // best-effort only
        }
    }

    /** Build report from an arbitrary map-like trace snapshot (offline parsing use-case). */
    public static Map<String, Object> buildFromMap(Map<String, ?> trace) {
        if (trace == null) {
            return Map.of();
        }
        return buildFrom(trace::get);
    }

    /** Light abstraction so the same builder can be used with TraceStore or offline-parsed maps. */
    public interface Getter {
        Object get(String key);
    }

    /**
     * Build an auto-report from trace keys.
     *
     * <p>Never throws; returns an empty map on errors.</p>
     */
    public static Map<String, Object> buildFrom(Getter g) {
        if (g == null) {
            return Map.of();
        }

        try {
            // ---- Core mode ----
            String mode = str(g.get("orch.mode"));
            boolean strike = bool(g.get("orch.strike"));
            boolean bypass = bool(g.get("orch.bypass"));
            boolean compression = bool(g.get("orch.compression"));
            String reason = firstNonBlank(
                    str(g.get("orch.reason")),
                    joinCsvish(g.get("orch.reasons"))
            );
            double irregularity = dbl(g.get("orch.irregularity"), -1.0);

            // ---- Signals: ablation ----
            List<AblationHit> bypassAbl = parseAblation(g.get("orch.debug.ablation.bypass"), "bypass");
            List<AblationHit> strikeAbl = parseAblation(g.get("orch.debug.ablation.strike"), "strike");

            // ---- Signals: breaker open meta ----
            Map<String, String> openKind = parseBreakerMap(g.get("nightmare.breaker.openKind"));
            Map<String, String> openErr = parseBreakerMap(g.get("nightmare.breaker.openErrMsg"));

            // ---- Provider-level breakdown (openKind ↔ HTTP status correlation) ----
            List<Map<String, Object>> providerCorrelation = buildProviderCorrelation(g, openKind, openErr);

            // ---- Signals: irregularity events ----
            List<IrregularityEvent> irrEvents = parseIrregularityEvents(g.get("irregularity.events"));

            // ---- Additional policy signal: officialOnly stage mismatch ----
            boolean officialOnly = bool(firstNonNull(g.get("plan.officialOnly"), g.get("web.failsoft.officialOnly")));
            String stageOrderEff = str(g.get("web.failsoft.stageOrder.effective"));
            String stageCountsSelected = str(g.get("web.failsoft.stageCountsSelected"));
            boolean nofilterLeak = officialOnly && (
                    containsIgnoreCase(stageOrderEff, "NOFILTER")
                            || containsIgnoreCase(stageCountsSelected, "NOFILTER")
            );

            // ---- Collect raw signals ----
            List<Signal> signals = new ArrayList<>();

            // Ablation (bypass)
            for (AblationHit h : bypassAbl) {
                signals.add(Signal.ablation(h));
            }
            // Ablation (strike)
            for (AblationHit h : strikeAbl) {
                signals.add(Signal.ablation(h));
            }

            // Breakers
            List<BreakerHit> breakerHits = new ArrayList<>();
            if (!openKind.isEmpty()) {
                for (Map.Entry<String, String> e : openKind.entrySet()) {
                    String breakerKey = safe(e.getKey());
                    if (breakerKey.isBlank()) {
                        continue;
                    }
                    String kind = safe(e.getValue());
                    String msg = safe(openErr.get(breakerKey));
                    BreakerHit bh = BreakerHit.of(breakerKey, kind, msg, reason, bypassAbl, strikeAbl);
                    breakerHits.add(bh);
                    signals.add(Signal.breaker(bh));
                }
            }

            // Irregularity reason buckets
            List<IrregularityBucket> irrBuckets = bucketIrregularity(irrEvents);
            for (IrregularityBucket b : irrBuckets) {
                signals.add(Signal.irregularity(b));
            }

            // Policy mismatch (officialOnly leak)
            if (nofilterLeak) {
                signals.add(Signal.policyMismatch(officialOnly, stageOrderEff, stageCountsSelected));
            }

            // ---- Grouping (root cause buckets) ----
            Map<String, CauseGroup> groups = new LinkedHashMap<>();
            for (Signal s : signals) {
                if (s == null) {
                    continue;
                }
                String gk = safe(s.groupKey);
                if (gk.isBlank()) {
                    gk = GroupKey.OTHER;
                }
                groups.computeIfAbsent(gk, CauseGroup::new).items.add(s);
            }

            // compute group score + representatives
            List<Map<String, Object>> groupRows = new ArrayList<>();
            List<CauseGroup> orderedGroups = new ArrayList<>(groups.values());
            for (CauseGroup cg : orderedGroups) {
                cg.sortAndScore();
                groupRows.add(cg.toRow());
            }
            orderedGroups.sort(Comparator.comparingDouble((CauseGroup c) -> -c.score));
            groupRows.sort(Comparator.comparingDouble((Map<String, Object> m) -> -dbl(m.get("score"), 0.0)));

            // ---- Top-N (group-level) ----
            int topN = 5;
            List<Map<String, Object>> topCauses = new ArrayList<>();
            for (int i = 0; i < Math.min(topN, orderedGroups.size()); i++) {
                CauseGroup cg = orderedGroups.get(i);
                Map<String, Object> row = cg.toTopCauseRow(i + 1);
                topCauses.add(row);
            }

            // ---- Recommended probes (auto) ----
            List<Map<String, Object>> probes = new ArrayList<>();
            probes.add(probe("Mode ablation (what pushed probabilities)",
                    List.of(
                            "orch.debug.ablation.bypass",
                            "orch.debug.ablation.strike",
                            "orch.debug.score.factors",
                            "orch.reason",
                            "orch.mode"
                    ),
                    "BYPASS/STRIKE decision을 만든 top 요인을 deltaProb로 바로 확인"));
            probes.add(probe("Breaker open meta (why it broke)",
                    List.of(
                            "nightmare.breaker.openKind",
                            "nightmare.breaker.openErrMsg",
                            "nightmare.breaker.openAtMs",
                            "nightmare.breaker.openUntilMs",
                            "aux.blocked.events"
                    ),
                    "OPEN된 breaker의 FailureKind/메시지로 CONFIG/timeout/rate-limit 등을 즉시 분류"));
            probes.add(probe("Irregularity timeline (silent failure gate)",
                    List.of(
                            "irregularity.events",
                            "irregularity.score",
                            "irregularity.last",
                            "orch.irregularity"
                    ),
                    "irregularity 누적 이유/타임라인으로 bypass_silent_failure 게이트 원인 추적"));
            probes.add(probe("Web fail-soft stage selection",
                    List.of(
                            "web.failsoft.officialOnly",
                            "web.failsoft.stageOrder.configured",
                            "web.failsoft.stageOrder.effective",
                            "web.failsoft.stageOrder.clamped",
                            "web.failsoft.stageCountsSelected"
                    ),
                    "officialOnly인데 NOFILTER(_SAFE)가 섞이는 결선 끊김/클램프 누락을 확인"));

            probes.add(probe("Provider breakdown (breaker kind ↔ HTTP status)",
                    List.of(
                            "web.provider.events",
                            "web.provider.events.count",
                            "web.await.events",
                            "web.await.root.*",
                            "nightmare.breaker.openKind",
                            "nightmare.breaker.openErrMsg",
                            "nightmare.rateLimit.*"
                    ),
                    "provider별 RATE_LIMIT/TIMEOUT/CANCELLED 힌트를 HTTP status(예: 429)와 함께 묶어 RCA를 가속"));
            // Optional: gctx continuity scorecard (if installed by a later patch)
            if (g.get("web.gctx.scorecard") != null) {
                probes.add(probe("GuardContext continuity (thread boundary)",
                        List.of(
                                "web.gctx.scorecard",
                                "web.gctx.continuity.events",
                                "web.gctx.stage.exec.*",
                                "web.gctx.stage.discontinuity.*"
                        ),
                        "executor/reactor 경계에서 gctx가 끊기는지 점수/아블레이션 기반으로 확인"));
            }

            // ---- Trace shortcuts + grep commands ----
            Map<String, String> shortcuts = new LinkedHashMap<>();
            shortcuts.put("AutoReport", TRACE_REPORT_KEY);
            shortcuts.put("Ablation(bypass)", "orch.debug.ablation.bypass");
            shortcuts.put("Ablation(strike)", "orch.debug.ablation.strike");
            shortcuts.put("Score factors", "orch.debug.score.factors");
            shortcuts.put("Breaker open meta", "nightmare.breaker.openKind / nightmare.breaker.openErrMsg");
            shortcuts.put("Irregularity events", "irregularity.events");
            shortcuts.put("Web failsoft stage", "web.failsoft.stageOrder.* / web.failsoft.stageCounts*");
            shortcuts.put("Web await", "web.await.*");
            shortcuts.put("Web provider events", "web.provider.events* / web.await.events");

            Map<String, String> grep = new LinkedHashMap<>();
            grep.put("AutoReport", "grep -n \"orch\\.autoReport\" <EOR_LOG.txt>");
            grep.put("Ablation(all)", "grep -n \"orch\\.debug\\.ablation\" <EOR_LOG.txt>");
            grep.put("Score factors", "grep -n \"orch\\.debug\\.score\" <EOR_LOG.txt>");
            grep.put("Breaker open meta", "grep -n \"nightmare\\.breaker\\.openKind\\|nightmare\\.breaker\\.openErrMsg\" <EOR_LOG.txt>");
            grep.put("Irregularity timeline", "grep -n \"irregularity\\.events\\|irregularity\\.score\\|irregularity\\.last\" <EOR_LOG.txt>");
            grep.put("Web failsoft stage", "grep -n \"web\\.failsoft\\.stageOrder\\|web\\.failsoft\\.stageCounts\" <EOR_LOG.txt>");
            grep.put("Web await", "grep -n \"web\\.await\\.\" <EOR_LOG.txt>");
            grep.put("Web provider events", "grep -n \"web\\.provider\\.events\\|web\\.await\\.events\\|nightmare\\.rateLimit\" <EOR_LOG.txt>");
            grep.put("Aux blocked", "grep -n \"aux\\.blocked\" <EOR_LOG.txt>");

            // ---- Final report ----
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", VERSION);
            out.put("mode", safe(mode));
            out.put("strike", strike);
            out.put("bypass", bypass);
            out.put("compression", compression);
            out.put("reason", safe(reason));
            if (irregularity >= 0.0) {
                out.put("irregularity", irregularity);
            }
            out.put("topCauses", topCauses);
            out.put("causeGroups", groupRows);
            out.put("breakerSummary", toBreakerSummaryRows(breakerHits));
            out.put("irregularitySummary", toIrregularitySummaryRows(irrBuckets));
            out.put("providerCorrelation", providerCorrelation);
            out.put("recommendedProbes", probes);
            out.put("traceShortcuts", shortcuts);
            out.put("traceShortcutsGrep", grep);
            out.put("notes", List.of(
                    "Top-N은 (ablation + breaker + irregularity) 혼합 점수로 랭킹됩니다.",
                    "같은 증상이더라도 원인 분리가 중요하므로, breaker kind/msg와 irregularity.events를 함께 봐야 합니다."
            ));

            // Raw signals (debug-friendly): keep small
            List<Map<String, Object>> raw = new ArrayList<>();
            for (Signal s : signals) {
                raw.add(s.toRow());
                if (raw.size() >= 20) {
                    break;
                }
            }
            out.put("rawSignals", raw);

            return out;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    // ---------------------------------------------------------------------
    // Model: group + signals
    // ---------------------------------------------------------------------

    private static final class GroupKey {
        private static final String AUX_PIPELINE = "AUX_PIPELINE_FAILURE";
        private static final String WEB_PIPELINE = "WEB_PIPELINE_FAILURE";
        private static final String VECTOR_PIPELINE = "VECTOR_PIPELINE_FAILURE";
        private static final String RISK_GATES = "RISK_AND_POLICY_GATES";
        private static final String EVIDENCE_POLICY = "EVIDENCE_POLICY_MISMATCH";
        private static final String CORE_MODEL = "CORE_MODEL_FAILURE";
        private static final String OTHER = "OTHER";
    }

    private static final class Signal {
        final String type;
        final String groupKey;
        final String title;
        final double score;
        final Map<String, Object> evidence;
        final List<String> actions;
        final List<String> traceKeys;

        private Signal(String type,
                       String groupKey,
                       String title,
                       double score,
                       Map<String, Object> evidence,
                       List<String> actions,
                       List<String> traceKeys) {
            this.type = safe(type);
            this.groupKey = safe(groupKey);
            this.title = safe(title);
            this.score = score;
            this.evidence = (evidence == null) ? Map.of() : evidence;
            this.actions = (actions == null) ? List.of() : actions;
            this.traceKeys = (traceKeys == null) ? List.of() : traceKeys;
        }

        static Signal ablation(AblationHit h) {
            if (h == null) {
                return null;
            }
            String gk = groupForFactor(h.factor);
            String t = "Ablation(" + h.target + ") " + h.factor;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("target", h.target);
            ev.put("factor", h.factor);
            ev.put("deltaProb", round4(h.deltaProb));
            if (h.value != null) {
                ev.put("value", h.value);
            }
            if (h.weight != null) {
                ev.put("weight", h.weight);
            }
            return new Signal(
                    "ablation",
                    gk,
                    t,
                    clamp01(h.deltaProb),
                    ev,
                    actionHintsForFactor(h.factor),
                    List.of("orch.debug.ablation." + h.target)
            );
        }

        static Signal breaker(BreakerHit bh) {
            if (bh == null) {
                return null;
            }
            String gk = groupForBreakerKey(bh.breakerKey);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("breakerKey", bh.breakerKey);
            ev.put("kind", bh.kind);
            if (!bh.errMsg.isBlank()) {
                ev.put("errMsg", bh.errMsg);
            }
            if (bh.classification != null && !bh.classification.code.isBlank()) {
                ev.put("classification", bh.classification.toRow());
            }
            String title = "Breaker OPEN: " + bh.breakerKey;
            if (!bh.kind.isBlank()) {
                title += " (" + bh.kind + ")";
            }
            return new Signal(
                    "breaker",
                    gk,
                    title,
                    clamp01(bh.score),
                    ev,
                    bh.actions,
                    List.of(
                            "nightmare.breaker.openKind",
                            "nightmare.breaker.openErrMsg",
                            "nightmare.breaker.openAtMs",
                            "nightmare.breaker.openUntilMs"
                    )
            );
        }

        static Signal irregularity(IrregularityBucket b) {
            if (b == null) {
                return null;
            }
            String gk = groupForIrregularityReason(b.reason);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("reason", b.reason);
            ev.put("count", b.count);
            ev.put("sumDelta", round4(b.sumDelta));
            if (!b.lastTs.isBlank()) {
                ev.put("lastTs", b.lastTs);
            }
            if (b.lastScore != null) {
                ev.put("lastScore", b.lastScore);
            }
            String title = "Irregularity bump: " + b.reason;
            return new Signal(
                    "irregularity",
                    gk,
                    title,
                    clamp01(Math.min(0.60, b.sumDelta)),
                    ev,
                    List.of(
                            "Inspect irregularity.events to see who bumped it.",
                            "If bypass_silent_failure gate triggers too easily, tune bump delta or gate threshold."
                    ),
                    List.of("irregularity.events", "irregularity.score", "orch.irregularity")
            );
        }

        static Signal policyMismatch(boolean officialOnly, String stageOrderEff, String stageCountsSelected) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("officialOnly", officialOnly);
            if (!stageOrderEff.isBlank()) {
                ev.put("stageOrder.effective", stageOrderEff);
            }
            if (!stageCountsSelected.isBlank()) {
                ev.put("stageCountsSelected", stageCountsSelected);
            }
            return new Signal(
                    "policy",
                    GroupKey.EVIDENCE_POLICY,
                    "Evidence policy mismatch: officialOnly but NOFILTER(_SAFE) leaked",
                    0.45,
                    ev,
                    List.of(
                            "Clamp stageOrder when officialOnly=true (remove NOFILTER/NOFILTER_SAFE).",
                            "If OFFICIAL/DOCS starvation happens, add missing domains to official/docs lists or allow conditional NOFILTER_SAFE with high credibility only."
                    ),
                    List.of(
                            "plan.officialOnly",
                            "web.failsoft.officialOnly",
                            "web.failsoft.stageOrder.effective",
                            "web.failsoft.stageCountsSelected"
                    )
            );
        }

        Map<String, Object> toRow() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("group", groupKey);
            m.put("title", title);
            m.put("score", round4(score));
            if (!evidence.isEmpty()) {
                m.put("evidence", evidence);
            }
            return m;
        }
    }

    private static final class CauseGroup {
        final String key;
        final List<Signal> items = new ArrayList<>();
        double score;

        CauseGroup(String key) {
            this.key = safe(key);
        }

        void sortAndScore() {
            items.sort(Comparator.comparingDouble((Signal s) -> -s.score));
            // Weighted top-3 sum (prevents noisy long tails from dominating)
            double w = 1.0;
            double s = 0.0;
            for (int i = 0; i < Math.min(3, items.size()); i++) {
                s += items.get(i).score * w;
                w *= 0.55;
            }
            this.score = clamp01(s);
        }

        Map<String, Object> toRow() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", key);
            m.put("title", groupTitle(key));
            m.put("score", round4(score));

            List<Map<String, Object>> reps = new ArrayList<>();
            for (int i = 0; i < Math.min(3, items.size()); i++) {
                reps.add(items.get(i).toRow());
            }
            m.put("representatives", reps);
            return m;
        }

        Map<String, Object> toTopCauseRow(int rank) {
            Map<String, Object> m = toRow();
            m.put("rank", rank);
            // Merge action hints (group + representatives)
            LinkedHashSet<String> acts = new LinkedHashSet<>();
            acts.addAll(groupActions(key));
            for (int i = 0; i < Math.min(2, items.size()); i++) {
                acts.addAll(items.get(i).actions);
            }
            if (!acts.isEmpty()) {
                m.put("actions", new ArrayList<>(acts));
            }

            // Trace keys worth jumping to
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            keys.addAll(groupTraceKeys(key));
            for (int i = 0; i < Math.min(2, items.size()); i++) {
                keys.addAll(items.get(i).traceKeys);
            }
            if (!keys.isEmpty()) {
                m.put("traceKeys", new ArrayList<>(keys));
            }
            return m;
        }
    }

    // ---------------------------------------------------------------------
    // Ablation parsing
    // ---------------------------------------------------------------------

    private static final class AblationHit {
        final String target; // bypass/strike
        final String factor;
        final double deltaProb;
        final Double value;
        final Double weight;

        private AblationHit(String target, String factor, double deltaProb, Double value, Double weight) {
            this.target = safe(target);
            this.factor = safe(factor);
            this.deltaProb = deltaProb;
            this.value = value;
            this.weight = weight;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AblationHit> parseAblation(Object v, String target) {
        if (v == null) {
            return List.of();
        }
        List<AblationHit> out = new ArrayList<>();
        try {
            if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        String factor = safeString(m.get("factor"));
                        double delta = dbl(m.get("deltaProb"), 0.0);
                        Double val = dblOrNull(m.get("value"));
                        Double w = dblOrNull(m.get("w"));
                        if (!factor.isBlank() && delta > 0) {
                            out.add(new AblationHit(target, factor, delta, val, w));
                        }
                    }
                }
            } else if (v instanceof String s) {
                // Fallback: parse Java toString list-of-maps.
                // Example: [{factor=auxHardDown, deltaProb=0.123, value=1.0, w=1.2}, ...]
                Matcher mm = Pattern.compile("factor=([^,}]+)").matcher(s);
                Matcher md = Pattern.compile("deltaProb=([0-9.]+)").matcher(s);
                List<String> factors = new ArrayList<>();
                while (mm.find()) {
                    factors.add(mm.group(1).trim());
                }
                List<Double> deltas = new ArrayList<>();
                while (md.find()) {
                    try {
                        deltas.add(Double.parseDouble(md.group(1)));
                    } catch (NumberFormatException ignore) {
                        deltas.add(0.0);
                    }
                }
                int n = Math.min(factors.size(), deltas.size());
                for (int i = 0; i < n; i++) {
                    String f = safe(factors.get(i));
                    double d = deltas.get(i);
                    if (!f.isBlank() && d > 0) {
                        out.add(new AblationHit(target, f, d, null, null));
                    }
                }
            }
        } catch (Throwable ignore) {
            return List.of();
        }

        out.sort(Comparator.comparingDouble((AblationHit a) -> -a.deltaProb));
        if (out.size() > 12) {
            return new ArrayList<>(out.subList(0, 12));
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Breaker parsing + classification
    // ---------------------------------------------------------------------

    private static final class BreakerHit {
        final String breakerKey;
        final String kind;
        final String errMsg;
        final double score;
        final ErrClassification classification;
        final List<String> actions;

        private BreakerHit(String breakerKey,
                           String kind,
                           String errMsg,
                           double score,
                           ErrClassification classification,
                           List<String> actions) {
            this.breakerKey = safe(breakerKey);
            this.kind = safe(kind);
            this.errMsg = safe(errMsg);
            this.score = score;
            this.classification = classification;
            this.actions = (actions == null) ? List.of() : actions;
        }

        static BreakerHit of(String breakerKey,
                             String kind,
                             String errMsg,
                             String orchReason,
                             List<AblationHit> bypassAbl,
                             List<AblationHit> strikeAbl) {
            ErrClassification cls = ErrClassification.classify(breakerKey, kind, errMsg);
            double base = breakerBaseScore(breakerKey, kind, errMsg);
            double boost = 0.0;
            String r = safe(orchReason);

            if (!r.isBlank()) {
                if (containsIgnoreCase(r, "qt_open") && containsIgnoreCase(breakerKey, "query-transformer")) {
                    boost += 0.10;
                }
                if (containsIgnoreCase(r, "disambiguation_open") && containsIgnoreCase(breakerKey, "disambiguation")) {
                    boost += 0.10;
                }
                if (containsIgnoreCase(r, "aux_down") && containsIgnoreCase(breakerKey, "query-transformer")) {
                    boost += 0.08;
                }
                if (containsIgnoreCase(r, "vector") && containsIgnoreCase(breakerKey, "vector")) {
                    boost += 0.08;
                }
            }

            // If ablation says auxHardDown or webBothDown etc, reflect slightly.
            if (hasFactor(bypassAbl, "auxHardDown") && containsIgnoreCase(breakerKey, "query-transformer")) {
                boost += 0.05;
            }
            if (hasFactor(strikeAbl, "auxHardDown") && containsIgnoreCase(breakerKey, "query-transformer")) {
                boost += 0.05;
            }

            if (cls != null && cls.confidence >= 0.65) {
                boost += 0.06;
            }

            double score = clamp01(base + boost);

            LinkedHashSet<String> acts = new LinkedHashSet<>();
            acts.addAll(groupActions(groupForBreakerKey(breakerKey)));
            if (cls != null) {
                acts.addAll(cls.actions);
            }
            if (acts.isEmpty()) {
                acts.add("Inspect breaker kind/msg and upstream provider logs.");
            }
            return new BreakerHit(breakerKey, kind, errMsg, score, cls, new ArrayList<>(acts));
        }
    }

    private static boolean hasFactor(List<AblationHit> abl, String factor) {
        if (abl == null || abl.isEmpty() || factor == null) {
            return false;
        }
        for (AblationHit a : abl) {
            if (a != null && factor.equalsIgnoreCase(a.factor)) {
                return true;
            }
        }
        return false;
    }

    private static double breakerBaseScore(String breakerKey, String kind, String msg) {
        String k = safe(kind).toUpperCase(Locale.ROOT);
        if (containsIgnoreCase(breakerKey, "vector:poison")) {
            return 0.45;
        }
        if ("CONFIG".equals(k)) {
            return 0.40;
        }
        if ("HTTP_4XX".equals(k)) {
            return 0.35;
        }
        if ("HTTP_5XX".equals(k)) {
            return 0.33;
        }
        if ("RATE_LIMIT".equals(k)) {
            return 0.30;
        }
        if ("TIMEOUT".equals(k)) {
            return 0.28;
        }
        if ("REJECTED".equals(k)) {
            return 0.24;
        }
        if ("EMPTY_RESPONSE".equals(k)) {
            return 0.22;
        }
        if (!safe(msg).isBlank()) {
            return 0.18;
        }
        return 0.12;
    }

    private static final class ErrClassification {
        final String code;
        final String title;
        final double confidence;
        final List<String> actions;

        private ErrClassification(String code, String title, double confidence, List<String> actions) {
            this.code = safe(code);
            this.title = safe(title);
            this.confidence = confidence;
            this.actions = (actions == null) ? List.of() : actions;
        }

        Map<String, Object> toRow() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("title", title);
            m.put("confidence", round4(confidence));
            if (!actions.isEmpty()) {
                m.put("actions", actions);
            }
            return m;
        }

        static ErrClassification classify(String breakerKey, String kind, String errMsg) {
            String k = safe(kind).toUpperCase(Locale.ROOT);
            String msg = safe(errMsg);
            String msgLower = msg.toLowerCase(Locale.ROOT);

            // Highly specific patterns
            if (msgLower.contains("model is required") || msgLower.contains("missing required parameter") && msgLower.contains("model")) {
                return new ErrClassification(
                        "CONFIG:missing_model",
                        "Provider request schema missing model",
                        0.92,
                        List.of(
                                "Set the model name in configuration/request (e.g., chat model / qtx model).",
                                "Verify QueryTransformer/Disambiguation uses a non-empty model string.",
                                "If using a router, confirm model alias resolves to an actual deployed model."
                        )
                );
            }

            if (msgLower.contains("api key") && (msgLower.contains("missing") || msgLower.contains("required") || msgLower.contains("not set"))) {
                return new ErrClassification(
                        "AUTH:missing_api_key",
                        "Missing API key",
                        0.90,
                        List.of(
                                "Confirm API key env/property is set for the provider.",
                                "Check that the key is visible in the runtime environment (docker/k8s secret mount)."
                        )
                );
            }

            if (msgLower.contains("unauthorized") || msgLower.contains("401") || msgLower.contains("invalid api key")) {
                return new ErrClassification(
                        "AUTH:unauthorized",
                        "Unauthorized / invalid credential",
                        0.86,
                        List.of(
                                "Verify API key validity and provider account status.",
                                "Check if the request is routed to the expected provider/project."
                        )
                );
            }

            if (msgLower.contains("rate limit") || msgLower.contains("429")) {
                return new ErrClassification(
                        "RATE_LIMIT:provider",
                        "Provider rate-limited",
                        0.84,
                        List.of(
                                "Add backoff/retry and reduce concurrency for this stage.",
                                "Lower per-request load (shorter prompts, smaller k, fewer parallel calls).",
                                "If available, switch to a higher quota key/plan or another provider."
                        )
                );
            }

            if (msgLower.contains("timeout") || msgLower.contains("timed out") || "TIMEOUT".equals(k)) {
                return new ErrClassification(
                        "NETWORK:timeout",
                        "Timeout talking to provider",
                        "TIMEOUT".equals(k) ? 0.80 : 0.72,
                        List.of(
                                "Increase timeout or reduce payload size.",
                                "Add retry with jitter; verify network path and DNS.",
                                "If repeated, tighten breaker window so it recovers faster once stable."
                        )
                );
            }

            if (containsIgnoreCase(breakerKey, "vector:poison")) {
                return new ErrClassification(
                        "DATA:vector_poison",
                        "Vector retrieval poison guard triggered",
                        0.80,
                        List.of(
                                "Inspect the embedding store errors around the poison window.",
                                "If false-positive, relax poison criteria or whitelist the failing store.",
                                "If true, quarantine the corrupted index/shard and rebuild."
                        )
                );
            }

            // Kind-based fallback
            if ("CONFIG".equals(k)) {
                return new ErrClassification(
                        "CONFIG:generic",
                        "Configuration / request schema error",
                        0.60,
                        List.of(
                                "Check the error message for missing fields and verify provider config.",
                                "Validate model name, endpoint URL, and required request parameters."
                        )
                );
            }

            if ("HTTP_4XX".equals(k)) {
                return new ErrClassification(
                        "HTTP_4XX",
                        "HTTP 4xx from provider",
                        0.55,
                        List.of(
                                "Check request schema/credentials; 4xx often indicates client-side issues.",
                                "Confirm the endpoint and headers are correct."
                        )
                );
            }

            if ("HTTP_5XX".equals(k)) {
                return new ErrClassification(
                        "HTTP_5XX",
                        "Provider/server error",
                        0.55,
                        List.of(
                                "Retry with backoff; check provider incident status.",
                                "Consider failover to another provider until recovery."
                        )
                );
            }

            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseBreakerMap(Object v) {
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            m.forEach((k, val) -> {
                if (k != null && val != null) {
                    out.put(String.valueOf(k), String.valueOf(val));
                }
            });
            return out;
        }
        if (v instanceof String s) {
            return parseBreakerMapString(s);
        }
        return Map.of();
    }

    /**
     * Parse a Map.toString() that looks like:
     * <pre>
     * {query-transformer:runLLM=CONFIG, retrieval:vector:poison=TIMEOUT}
     * </pre>
     *
     * <p>
     * Error messages may contain commas, so we detect entry boundaries by searching
     * for patterns that look like "<breakerKey>=" where breakerKey contains at least one ':'
     * (as used by NightmareKeys).
     * </p>
     */
    private static Map<String, String> parseBreakerMapString(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) {
            return Map.of();
        }
        if (s.startsWith("{") && s.endsWith("}")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isBlank()) {
            return Map.of();
        }

        // find "key=" positions (key must contain ':' to avoid false splits)
        Pattern p = Pattern.compile("([a-zA-Z0-9_.-]+(?::[a-zA-Z0-9_.-]+)+)=");
        Matcher m = p.matcher(s);
        List<int[]> hits = new ArrayList<>();
        while (m.find()) {
            hits.add(new int[]{m.start(1), m.end(1), m.end()}); // keyStart, keyEnd, afterEquals
        }
        if (hits.isEmpty()) {
            // naive fallback
            Map<String, String> out = new LinkedHashMap<>();
            String[] parts = s.split(",\\s*");
            for (String part : parts) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                }
            }
            return out;
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            int[] h = hits.get(i);
            int keyStart = h[0];
            int keyEnd = h[1];
            int valStart = h[2];
            int valEnd = s.length();
            if (i + 1 < hits.size()) {
                valEnd = hits.get(i + 1)[0];
                // trim trailing comma+space
                if (valEnd >= 2 && s.charAt(valEnd - 2) == ',' && s.charAt(valEnd - 1) == ' ') {
                    valEnd -= 2;
                } else if (valEnd >= 1 && s.charAt(valEnd - 1) == ',') {
                    valEnd -= 1;
                }
            }
            String key = s.substring(keyStart, keyEnd).trim();
            String val = (valStart <= valEnd && valStart <= s.length())
                    ? s.substring(valStart, Math.min(valEnd, s.length())).trim()
                    : "";
            if (!key.isBlank()) {
                out.put(key, val);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Provider correlation (breaker kind ↔ HTTP status)
    // ---------------------------------------------------------------------

    private static final class ProviderAgg {
        final String engineKey;
        String engineDisplay;
        String breakerKey;
        String breakerOpenKind;
        String breakerOpenErr;

        int ok;
        int nonOk;
        int timeoutSignals;
        int cancelSignals;

        final Map<Integer, Integer> httpStatusCounts = new LinkedHashMap<>();
        final Map<String, Integer> providerStatusCounts = new LinkedHashMap<>();

        ProviderAgg(String engineKey, String engineDisplay) {
            this.engineKey = safe(engineKey);
            this.engineDisplay = safe(engineDisplay);
        }
    }

    /**
     * Build provider rows that correlate breaker open kind with observed HTTP status
     * (e.g., RATE_LIMIT ↔ 429) using TraceStore keys:
     * <ul>
     *   <li>{@code web.await.events}</li>
     *   <li>{@code web.provider.events}</li>
     *   <li>{@code nightmare.breaker.openKind / openErrMsg}</li>
     *   <li>{@code nightmare.rateLimit.once.* / nightmare.rateLimit.dup.*}</li>
     * </ul>
     */
    private static List<Map<String, Object>> buildProviderCorrelation(Getter g,
                                                                     Map<String, String> openKind,
                                                                     Map<String, String> openErr) {
        if (g == null) {
            return List.of();
        }

        // 1) Aggregate web.await.events (HTTP status + timeout/cancel signals)
        Map<String, ProviderAgg> byEngine = new LinkedHashMap<>();
        List<Map<String, Object>> awaitEvents = parseListOfMaps(g.get("web.await.events"));
        for (Map<String, Object> ev : awaitEvents) {
            if (ev == null) {
                continue;
            }
            String engine = safeString(ev.get("engine")).trim();
            if (engine.isBlank()) {
                continue;
            }
            String ek = engineKey(engine);
            ProviderAgg agg = byEngine.computeIfAbsent(ek, k -> new ProviderAgg(k, engine));
            if (agg.engineDisplay == null || agg.engineDisplay.isBlank()) {
                agg.engineDisplay = engine;
            }

            boolean nonOk = bool(ev.get("nonOk"));
            if (nonOk) {
                agg.nonOk++;
            } else {
                agg.ok++;
            }

            Integer hs = intOrNull(ev.get("httpStatus"));
            if (hs != null) {
                agg.httpStatusCounts.put(hs, agg.httpStatusCounts.getOrDefault(hs, 0) + 1);
            }

            if (bool(ev.get("timeout"))) {
                agg.timeoutSignals++;
            }
            if (bool(ev.get("interrupted"))) {
                agg.cancelSignals++;
            }
            String cause = safeString(ev.get("cause"));
            if (containsIgnoreCase(cause, "timeout")) {
                agg.timeoutSignals++;
            }
            if (containsIgnoreCase(cause, "cancel") || containsIgnoreCase(cause, "interrupt")) {
                agg.cancelSignals++;
            }
        }

        // 2) Aggregate web.provider.events (provider-level status distribution)
        List<Map<String, Object>> providerEvents = parseListOfMaps(g.get("web.provider.events"));
        for (Map<String, Object> ev : providerEvents) {
            if (ev == null) {
                continue;
            }
            String engine = safeString(ev.get("engine")).trim();
            if (engine.isBlank()) {
                continue;
            }
            String ek = engineKey(engine);
            ProviderAgg agg = byEngine.computeIfAbsent(ek, k -> new ProviderAgg(k, engine));
            if (agg.engineDisplay == null || agg.engineDisplay.isBlank()) {
                agg.engineDisplay = engine;
            }
            String status = safeString(ev.get("status")).trim();
            if (!status.isBlank()) {
                agg.providerStatusCounts.put(status, agg.providerStatusCounts.getOrDefault(status, 0) + 1);
            }
            Integer hs = intOrNull(ev.get("httpStatus"));
            if (hs != null) {
                agg.httpStatusCounts.put(hs, agg.httpStatusCounts.getOrDefault(hs, 0) + 1);
            }
        }

        // 3) Attach breaker open meta (best-effort) to each websearch provider
        if (openKind != null && !openKind.isEmpty()) {
            for (Map.Entry<String, String> e : openKind.entrySet()) {
                String breakerKey = safeString(e.getKey()).trim();
                if (breakerKey.isBlank()) {
                    continue;
                }
                if (!breakerKey.startsWith("websearch:")) {
                    continue;
                }
                String engine = breakerKey.substring("websearch:".length());
                if (engine.isBlank()) {
                    continue;
                }
                String ek = engineKey(engine);
                ProviderAgg agg = byEngine.computeIfAbsent(ek, k -> new ProviderAgg(k, engine));
                agg.breakerKey = breakerKey;
                agg.breakerOpenKind = safeString(e.getValue());
                agg.breakerOpenErr = safeString(openErr == null ? null : openErr.get(breakerKey));
            }
        }

        // 4) Normalize known providers so correlation shows up even if breaker isn't open
        normalizeKnownProviderKeys(byEngine, openKind, openErr);

        // 5) Render rows + recommendations
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProviderAgg agg : byEngine.values()) {
            // Only show rows that look relevant to websearch.
            if (agg == null) {
                continue;
            }
            if (agg.ok == 0 && agg.nonOk == 0 && (agg.breakerKey == null || agg.breakerKey.isBlank())) {
                continue;
            }

            String breakerKey = safeString(agg.breakerKey);
            long rateLimitSignals = (breakerKey.isBlank()) ? 0L : rateLimitSignals(g, breakerKey);
            int http429 = agg.httpStatusCounts.getOrDefault(429, 0);
            int http4xx = sumHttpRange(agg.httpStatusCounts, 400, 499);
            int http5xx = sumHttpRange(agg.httpStatusCounts, 500, 599);

            String openK = safeString(agg.breakerOpenKind);
            List<String> rec = new ArrayList<>();
            if ("RATE_LIMIT".equalsIgnoreCase(openK) || http429 > 0 || rateLimitSignals > 0) {
                rec.add("quota/backoff (respect Retry-After)");
            }
            if ("CONFIG".equalsIgnoreCase(openK) || (http4xx > 0 && http429 == 0)) {
                rec.add("request schema/credentials 확인");
            }
            if ("TIMEOUT".equalsIgnoreCase(openK) || agg.timeoutSignals > 0) {
                rec.add("timeout/budget 튜닝 (payload 축소/병렬도 축소)");
            }
            if ("INTERRUPTED".equalsIgnoreCase(openK) || agg.cancelSignals > 0) {
                rec.add("cancel/interrupt hygiene 확인");
            }
            if ("HTTP_5XX".equalsIgnoreCase(openK) || http5xx > 0) {
                rec.add("provider 장애 가능: failover/retry");
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("engine", safeString(agg.engineDisplay));
            if (!breakerKey.isBlank()) {
                r.put("breakerKey", breakerKey);
            }
            if (!openK.isBlank()) {
                r.put("breakerOpenKind", openK);
            }
            if (!safeString(agg.breakerOpenErr).isBlank()) {
                r.put("breakerOpenErr", agg.breakerOpenErr);
            }
            r.put("ok", agg.ok);
            r.put("nonOk", agg.nonOk);
            r.put("timeoutSignals", agg.timeoutSignals);
            r.put("cancelSignals", agg.cancelSignals);
            r.put("rateLimitSignals", rateLimitSignals);
            r.put("http429", http429);
            r.put("http4xx", http4xx);
            r.put("http5xx", http5xx);
            if (!agg.providerStatusCounts.isEmpty()) {
                r.put("providerStatus", agg.providerStatusCounts);
            }
            if (!agg.httpStatusCounts.isEmpty()) {
                r.put("httpStatus", agg.httpStatusCounts);
            }
            if (!rec.isEmpty()) {
                r.put("recommend", String.join("; ", rec));
            }
            rows.add(r);
        }

        // Stable ordering: primarily by nonOk desc, then engine name.
        rows.sort((a, b) -> {
            int na = (int) dbl(a.get("nonOk"), 0.0);
            int nb = (int) dbl(b.get("nonOk"), 0.0);
            if (na != nb) {
                return Integer.compare(nb, na);
            }
            return safeString(a.get("engine")).compareToIgnoreCase(safeString(b.get("engine")));
        });
        return rows;
    }

    private static void normalizeKnownProviderKeys(Map<String, ProviderAgg> byEngine,
                                                   Map<String, String> openKind,
                                                   Map<String, String> openErr) {
        if (byEngine == null) {
            return;
        }
        // Known engines (ensure breakerKey presence for correlation)
        Map<String, String> engineToBreaker = Map.of(
                "naver", NightmareKeys.WEBSEARCH_NAVER,
                "brave", NightmareKeys.WEBSEARCH_BRAVE,
                "hybrid", NightmareKeys.WEBSEARCH_HYBRID
        );

        for (Map.Entry<String, String> e : engineToBreaker.entrySet()) {
            String ek = e.getKey();
            ProviderAgg agg = byEngine.get(ek);
            if (agg == null) {
                continue;
            }
            if (agg.breakerKey == null || agg.breakerKey.isBlank()) {
                agg.breakerKey = e.getValue();
            }
            if ((agg.breakerOpenKind == null || agg.breakerOpenKind.isBlank()) && openKind != null) {
                agg.breakerOpenKind = safeString(openKind.get(agg.breakerKey));
            }
            if ((agg.breakerOpenErr == null || agg.breakerOpenErr.isBlank()) && openErr != null) {
                agg.breakerOpenErr = safeString(openErr.get(agg.breakerKey));
            }
        }
    }

    private static String engineKey(String engine) {
        String s = safe(engine).trim();
        if (s.isBlank()) {
            return "";
        }
        s = s.toLowerCase(Locale.ROOT);
        // Normalize common variants
        if (s.contains("naver")) {
            return "naver";
        }
        if (s.contains("brave")) {
            return "brave";
        }
        if (s.contains("hybrid")) {
            return "hybrid";
        }
        return s;
    }

    private static long rateLimitSignals(Getter g, String breakerKey) {
        if (g == null || breakerKey == null || breakerKey.isBlank()) {
            return 0L;
        }
        String onceKey = "nightmare.rateLimit.once." + breakerKey;
        String dupKey = "nightmare.rateLimit.dup." + breakerKey;
        long once = (g.get(onceKey) != null) ? 1L : 0L;
        long dup = (long) dbl(g.get(dupKey), 0.0);
        if (dup < 0L) {
            dup = 0L;
        }
        return once + dup;
    }

    private static int sumHttpRange(Map<Integer, Integer> m, int lo, int hi) {
        if (m == null || m.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (Map.Entry<Integer, Integer> e : m.entrySet()) {
            if (e == null) {
                continue;
            }
            Integer k = e.getKey();
            Integer v = e.getValue();
            if (k == null || v == null) {
                continue;
            }
            if (k >= lo && k <= hi) {
                sum += v;
            }
        }
        return sum;
    }

    private static Integer intOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return null;
            }
            return Integer.parseInt(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseListOfMaps(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    m.forEach((k, val) -> {
                        if (k != null) {
                            row.put(String.valueOf(k), val);
                        }
                    });
                    out.add(row);
                } else if (o instanceof String s) {
                    Map<String, Object> row = new LinkedHashMap<>(parseGenericMapString(s));
                    if (!row.isEmpty()) {
                        out.add(row);
                    }
                }
            }
            return out;
        }
        if (v instanceof String s) {
            return parseListOfMapsString(s);
        }
        return List.of();
    }

    private static List<Map<String, Object>> parseListOfMapsString(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) {
            return List.of();
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isBlank()) {
            return List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        // Extract top-level {...} blocks (Map.toString() style)
        int depth = 0;
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String block = s.substring(start, i + 1);
                    Map<String, String> kv = parseGenericMapString(block);
                    if (!kv.isEmpty()) {
                        out.add(new LinkedHashMap<>(kv));
                    }
                    start = -1;
                }
            }
        }

        // Fallback: single map without braces scanning
        if (out.isEmpty() && s.contains("=")) {
            Map<String, String> kv = parseGenericMapString(s);
            if (!kv.isEmpty()) {
                out.add(new LinkedHashMap<>(kv));
            }
        }
        return out;
    }

    /** Parse Map.toString() / log-friendly key=value pairs with comma separation. */
    private static Map<String, String> parseGenericMapString(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) {
            return Map.of();
        }
        if (s.startsWith("{") && s.endsWith("}")) {
            s = s.substring(1, s.length() - 1);
        }
        s = s.trim();
        if (s.isBlank()) {
            return Map.of();
        }

        Pattern p = Pattern.compile("([a-zA-Z0-9_.-]+)=");
        Matcher m = p.matcher(s);
        List<int[]> hits = new ArrayList<>();
        while (m.find()) {
            hits.add(new int[]{m.start(1), m.end(1), m.end()}); // keyStart, keyEnd, afterEquals
        }
        if (hits.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            int[] h = hits.get(i);
            int keyStart = h[0];
            int keyEnd = h[1];
            int valStart = h[2];
            int valEnd = s.length();
            if (i + 1 < hits.size()) {
                valEnd = hits.get(i + 1)[0];
                // trim trailing comma+space
                if (valEnd >= 2 && s.charAt(valEnd - 2) == ',' && s.charAt(valEnd - 1) == ' ') {
                    valEnd -= 2;
                } else if (valEnd >= 1 && s.charAt(valEnd - 1) == ',') {
                    valEnd -= 1;
                }
            }
            String key = s.substring(keyStart, keyEnd).trim();
            String val = (valStart <= valEnd && valStart <= s.length())
                    ? s.substring(valStart, Math.min(valEnd, s.length())).trim()
                    : "";
            if (!key.isBlank()) {
                out.put(key, val);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Irregularity parsing
    // ---------------------------------------------------------------------

    private static final class IrregularityEvent {
        final String ts;
        final double delta;
        final Double score;
        final String reason;

        private IrregularityEvent(String ts, double delta, Double score, String reason) {
            this.ts = safe(ts);
            this.delta = delta;
            this.score = score;
            this.reason = safe(reason);
        }
    }

    private static final class IrregularityBucket {
        final String reason;
        int count;
        double sumDelta;
        String lastTs;
        Double lastScore;

        private IrregularityBucket(String reason) {
            this.reason = safe(reason);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<IrregularityEvent> parseIrregularityEvents(Object v) {
        if (v == null) {
            return List.of();
        }
        try {
            if (v instanceof List<?> list) {
                List<IrregularityEvent> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        String ts = safeString(m.get("ts"));
                        double delta = dbl(m.get("delta"), 0.0);
                        Double score = dblOrNull(m.get("score"));
                        String reason = safeString(m.get("reason"));
                        if (!safe(reason).isBlank()) {
                            out.add(new IrregularityEvent(ts, delta, score, reason));
                        }
                    }
                }
                return out;
            }
            if (v instanceof String s) {
                // Regex-based extraction from toString: {ts=..., delta=0.1, score=0.2, reason=...}
                List<IrregularityEvent> out = new ArrayList<>();
                Matcher mr = Pattern.compile("reason=([^,}]+)").matcher(s);
                Matcher md = Pattern.compile("delta=([0-9.]+)").matcher(s);
                Matcher mts = Pattern.compile("ts=([^,}]+)").matcher(s);
                Matcher ms = Pattern.compile("score=([0-9.]+)").matcher(s);

                List<String> reasons = new ArrayList<>();
                while (mr.find()) {
                    reasons.add(mr.group(1).trim());
                }
                List<Double> deltas = new ArrayList<>();
                while (md.find()) {
                    deltas.add(parseDouble(md.group(1), 0.0));
                }
                List<String> tss = new ArrayList<>();
                while (mts.find()) {
                    tss.add(mts.group(1).trim());
                }
                List<Double> scores = new ArrayList<>();
                while (ms.find()) {
                    scores.add(parseDouble(ms.group(1), Double.NaN));
                }

                int n = Math.min(reasons.size(), deltas.size());
                for (int i = 0; i < n; i++) {
                    String r = safe(reasons.get(i));
                    double d = deltas.get(i);
                    String ts = (i < tss.size() ? tss.get(i) : "");
                    Double sc = (i < scores.size() && !Double.isNaN(scores.get(i))) ? scores.get(i) : null;
                    if (!r.isBlank()) {
                        out.add(new IrregularityEvent(ts, d, sc, r));
                    }
                }
                return out;
            }
        } catch (Throwable ignore) {
            return List.of();
        }
        return List.of();
    }

    private static List<IrregularityBucket> bucketIrregularity(List<IrregularityEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Map<String, IrregularityBucket> m = new LinkedHashMap<>();
        for (IrregularityEvent e : events) {
            if (e == null || e.reason.isBlank()) {
                continue;
            }
            IrregularityBucket b = m.computeIfAbsent(e.reason, IrregularityBucket::new);
            b.count++;
            b.sumDelta += e.delta;
            if (!e.ts.isBlank()) {
                b.lastTs = e.ts;
            }
            if (e.score != null) {
                b.lastScore = e.score;
            }
        }

        List<IrregularityBucket> out = new ArrayList<>(m.values());
        out.sort(Comparator.comparingDouble((IrregularityBucket b) -> -b.sumDelta));
        if (out.size() > 10) {
            return new ArrayList<>(out.subList(0, 10));
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Report rows
    // ---------------------------------------------------------------------

    private static List<Map<String, Object>> toBreakerSummaryRows(List<BreakerHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BreakerHit h : hits) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("breakerKey", h.breakerKey);
            if (!h.kind.isBlank()) {
                r.put("kind", h.kind);
            }
            if (!h.errMsg.isBlank()) {
                r.put("errMsg", h.errMsg);
            }
            r.put("score", round4(h.score));
            if (h.classification != null) {
                r.put("classification", h.classification.toRow());
            }
            out.add(r);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> -dbl(m.get("score"), 0.0)));
        if (out.size() > 12) {
            return new ArrayList<>(out.subList(0, 12));
        }
        return out;
    }

    private static List<Map<String, Object>> toIrregularitySummaryRows(List<IrregularityBucket> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (IrregularityBucket b : buckets) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("reason", b.reason);
            r.put("count", b.count);
            r.put("sumDelta", round4(b.sumDelta));
            if (b.lastScore != null) {
                r.put("lastScore", b.lastScore);
            }
            if (b.lastTs != null && !b.lastTs.isBlank()) {
                r.put("lastTs", b.lastTs);
            }
            out.add(r);
        }
        return out;
    }

    private static Map<String, Object> probe(String name, List<String> keys, String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", safe(name));
        m.put("why", safe(why));
        m.put("traceKeys", (keys == null) ? List.of() : keys);
        return m;
    }

    // ---------------------------------------------------------------------
    // Group mapping + action hints
    // ---------------------------------------------------------------------

    private static String groupForFactor(String factor) {
        String f = safe(factor);
        if (f.isBlank()) {
            return GroupKey.OTHER;
        }
        if (f.equalsIgnoreCase("chatDown")) {
            return GroupKey.CORE_MODEL;
        }
        if (f.equalsIgnoreCase("webBothDown") || f.equalsIgnoreCase("webRateLimited")) {
            return GroupKey.WEB_PIPELINE;
        }
        if (f.equalsIgnoreCase("irregularity") || f.equalsIgnoreCase("userFrustration") || f.equalsIgnoreCase("highRisk")
                || f.equalsIgnoreCase("silentBypassGate")) {
            return GroupKey.RISK_GATES;
        }
        if (f.toLowerCase(Locale.ROOT).contains("aux") || f.equalsIgnoreCase("qtOpen") || f.equalsIgnoreCase("disambOpen")) {
            return GroupKey.AUX_PIPELINE;
        }
        return GroupKey.OTHER;
    }

    private static String groupForBreakerKey(String breakerKey) {
        String k = safe(breakerKey).toLowerCase(Locale.ROOT);
        if (k.isBlank()) {
            return GroupKey.OTHER;
        }
        if (k.contains("chat-draft") || k.contains("chat_draft")) {
            return GroupKey.CORE_MODEL;
        }
        if (k.contains("query-transformer") || k.contains("disambiguation") || k.contains("keyword") || k.contains("fast-llm")) {
            return GroupKey.AUX_PIPELINE;
        }
        if (k.contains("retrieval:web") || (k.contains("retrieval") && k.contains("web")) || k.contains("search")) {
            return GroupKey.WEB_PIPELINE;
        }
        if (k.contains("retrieval:vector") || k.contains("vector")) {
            return GroupKey.VECTOR_PIPELINE;
        }
        return GroupKey.OTHER;
    }

    private static String groupForIrregularityReason(String reason) {
        String r = safe(reason).toLowerCase(Locale.ROOT);
        if (r.contains("disambigu") || r.contains("qtx") || r.contains("query-transform")) {
            return GroupKey.AUX_PIPELINE;
        }
        if (r.contains("vector") || r.contains("embedding") || r.contains("poison")) {
            return GroupKey.VECTOR_PIPELINE;
        }
        if (r.contains("web") || r.contains("search") || r.contains("brave") || r.contains("naver") || r.contains("google")) {
            return GroupKey.WEB_PIPELINE;
        }
        return GroupKey.RISK_GATES;
    }

    private static String groupTitle(String groupKey) {
        String k = safe(groupKey);
        return switch (k) {
            case GroupKey.AUX_PIPELINE -> "Aux pipeline failure (QueryTransformer/Disambiguation/KeywordSelector)";
            case GroupKey.WEB_PIPELINE -> "Web pipeline failure (provider/down/await/fail-soft)";
            case GroupKey.VECTOR_PIPELINE -> "Vector pipeline failure (retrieval/poison/index)";
            case GroupKey.RISK_GATES -> "Routing gates (irregularity/silent-failure/risk)";
            case GroupKey.EVIDENCE_POLICY -> "Evidence policy mismatch (officialOnly vs stage selection)";
            case GroupKey.CORE_MODEL -> "Core model failure (chat-draft breaker)";
            default -> "Other";
        };
    }

    private static List<String> groupActions(String groupKey) {
        String k = safe(groupKey);
        return switch (k) {
            case GroupKey.AUX_PIPELINE -> List.of(
                    "Check open breakers for query-transformer/disambiguation and confirm FailureKind + errMsg.",
                    "If CONFIG issues: verify model/endpoint configuration for aux stages.",
                    "Confirm auxHardDown is only used for truly hard-critical stages (keyword selection / fast LLM), not optional breakers."
            );
            case GroupKey.WEB_PIPELINE -> List.of(
                    "Inspect web.await.events for timeout/nonOk and provider-specific failure patterns.",
                    "Check web.failsoft.stageOrder.* and stageCountsSelected to confirm fallback behavior."
            );
            case GroupKey.VECTOR_PIPELINE -> List.of(
                    "If retrieval:vector:poison is open, treat it as a data/index incident and verify embeddings/index health.",
                    "Temporarily reduce reliance on vector retrieval (or quarantine failing store) until stable."
            );
            case GroupKey.RISK_GATES -> List.of(
                    "Inspect irregularity.events: identify the bump reasons and their deltas.",
                    "If silent-failure gate triggers too often, tune bump weights or raise gate thresholds."
            );
            case GroupKey.EVIDENCE_POLICY -> List.of(
                    "When officialOnly=true, clamp stage order to OFFICIAL/DOCS/DEV_COMMUNITY and block NOFILTER(_SAFE).",
                    "If starvation occurs, enrich official/docs domain lists or allow conditional NOFILTER_SAFE only with high credibility."
            );
            case GroupKey.CORE_MODEL -> List.of(
                    "Check chat-draft breaker; verify provider connectivity, model name, and quotas.",
                    "If this is systemic, lower breaker open window to recover faster after transient failures."
            );
            default -> List.of();
        };
    }

    private static List<String> groupTraceKeys(String groupKey) {
        String k = safe(groupKey);
        return switch (k) {
            case GroupKey.AUX_PIPELINE -> List.of(
                    "aux.blocked.events",
                    "aux.down.events",
                    "nightmare.breaker.openKind",
                    "nightmare.breaker.openErrMsg",
                    "orch.debug.ablation.bypass",
                    "orch.debug.ablation.strike"
            );
            case GroupKey.WEB_PIPELINE -> List.of(
                    "web.await.events",
                    "web.failsoft.*",
                    "nightmare.breaker.openKind",
                    "nightmare.breaker.openErrMsg"
            );
            case GroupKey.VECTOR_PIPELINE -> List.of(
                    "nightmare.breaker.openKind",
                    "nightmare.breaker.openErrMsg",
                    "nightmare.breaker.openAtMs",
                    "nightmare.breaker.openUntilMs"
            );
            case GroupKey.RISK_GATES -> List.of(
                    "irregularity.events",
                    "orch.irregularity",
                    "orch.reason",
                    "orch.debug.score.factors"
            );
            case GroupKey.EVIDENCE_POLICY -> List.of(
                    "plan.officialOnly",
                    "web.failsoft.officialOnly",
                    "web.failsoft.stageOrder.effective",
                    "web.failsoft.stageCountsSelected"
            );
            case GroupKey.CORE_MODEL -> List.of(
                    "nightmare.breaker.openKind",
                    "nightmare.breaker.openErrMsg",
                    "orch.mode",
                    "orch.reason"
            );
            default -> List.of();
        };
    }

    private static List<String> actionHintsForFactor(String factor) {
        String f = safe(factor);
        if (f.equalsIgnoreCase("silentBypassGate")) {
            return List.of(
                    "silentBypassGate is usually (auxHardDown && irregularity>=threshold).",
                    "Inspect irregularity.events and auxHardDown source; tune threshold or reclassify auxHardDown."
            );
        }
        if (f.equalsIgnoreCase("auxHardDown")) {
            return List.of(
                    "auxHardDown is a strong BYPASS/STRIKE trigger. Verify it only means truly hard-down aux stages.",
                    "Inspect breaker kind/msg: if CONFIG, treat as fixable configuration rather than hard-down escalation."
            );
        }
        if (f.equalsIgnoreCase("irregularity")) {
            return List.of(
                    "Inspect irregularity.events to see which subsystem bumped it (and by how much).",
                    "If 'soft' anomalies dominate, reduce bump delta or cap cumulative irregularity."
            );
        }
        if (f.equalsIgnoreCase("webBothDown")) {
            return List.of(
                    "Confirm both providers truly failed (timeouts vs partial nonOk).",
                    "Inspect web.await.events and breaker meta for provider-specific root cause."
            );
        }
        return List.of();
    }

    // ---------------------------------------------------------------------
    // Text summary
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    /** Render a short markdown-ish summary (useful for quick greps in plain text logs). */
    public static String renderText(Map<String, Object> report) {
        if (report == null || report.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Orchestration Auto Report (" + VERSION + ")\n\n");
        sb.append("- mode: ").append(safeString(report.get("mode"))).append("\n");
        sb.append("- strike: ").append(report.get("strike")).append("\n");
        sb.append("- bypass: ").append(report.get("bypass")).append("\n");
        sb.append("- compression: ").append(report.get("compression")).append("\n");
        String reason = safeString(report.get("reason"));
        if (!reason.isBlank()) {
            sb.append("- reason: ").append(reason).append("\n");
        }
        Object irr = report.get("irregularity");
        if (irr != null) {
            sb.append("- irregularity: ").append(irr).append("\n");
        }
        sb.append("\n## Top causes\n");
        Object tc = report.get("topCauses");
        if (tc instanceof List<?> list && !list.isEmpty()) {
            int i = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                i++;
                sb.append(i).append(". ").append(safeString(m.get("title")))
                        .append(" (score=").append(safeString(m.get("score"))).append(")\n");
                Object reps = m.get("representatives");
                if (reps instanceof List<?> rl && !rl.isEmpty()) {
                    Object r0 = rl.get(0);
                    if (r0 instanceof Map<?, ?> rm) {
                        sb.append("   - ").append(safeString(rm.get("title")))
                                .append(" (score=").append(safeString(rm.get("score"))).append(")\n");
                    }
                }
                if (i >= 5) {
                    break;
                }
            }
        } else {
            sb.append("(no structured signals found)\n");
        }

        // Provider-level breakdown: tie breaker kind (RATE_LIMIT/TIMEOUT/CANCEL) to
        // observed HTTP status (e.g., 429) so oncall can immediately pick the right lever.
        sb.append("\n## Provider correlation (breaker kind ↔ HTTP status)\n");
        Object pc = report.get("providerCorrelation");
        if (pc instanceof List<?> list && !list.isEmpty()) {
            int shown = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String engine = safeString(m.get("engine"));
                if (engine.isBlank()) {
                    engine = safeString(m.get("breakerKey"));
                }
                String ok = safeString(m.get("ok"));
                String nonOk = safeString(m.get("nonOk"));
                String openKind = safeString(m.get("breakerOpenKind"));
                String rl = safeString(m.get("rateLimitSignals"));
                String http429 = safeString(m.get("http429"));
                String timeoutSignals = safeString(m.get("timeoutSignals"));
                String cancelSignals = safeString(m.get("cancelSignals"));
                String rec = safeString(m.get("recommend"));

                sb.append("- ").append(engine);
                if (!openKind.isBlank()) {
                    sb.append(" openKind=").append(openKind);
                }
                if (!rl.isBlank() && !"0".equals(rl)) {
                    sb.append(" RATE_LIMIT=").append(rl);
                }
                if (!http429.isBlank() && !"0".equals(http429)) {
                    sb.append(" http429=").append(http429);
                }
                if (!timeoutSignals.isBlank() && !"0".equals(timeoutSignals)) {
                    sb.append(" timeout=").append(timeoutSignals);
                }
                if (!cancelSignals.isBlank() && !"0".equals(cancelSignals)) {
                    sb.append(" cancelled=").append(cancelSignals);
                }
                if (!ok.isBlank() || !nonOk.isBlank()) {
                    sb.append(" (ok=").append(ok.isBlank() ? "0" : ok)
                            .append(", nonOk=").append(nonOk.isBlank() ? "0" : nonOk)
                            .append(")");
                }
                if (!rec.isBlank()) {
                    sb.append(" → ").append(rec);
                }
                sb.append("\n");
                shown++;
                if (shown >= 10) {
                    break;
                }
            }
        } else {
            sb.append("(none)\n");
        }

        sb.append("\n## Grep shortcuts\n");
        Object gs = report.get("traceShortcutsGrep");
        if (gs instanceof Map<?, ?> gm && !gm.isEmpty()) {
            for (Map.Entry<?, ?> e : gm.entrySet()) {
                sb.append("- ").append(String.valueOf(e.getKey())).append(": ")
                        .append(String.valueOf(e.getValue())).append("\n");
            }
        } else {
            sb.append("(none)\n");
        }

        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private static Object firstNonNull(Object a, Object b) {
        return (a != null) ? a : b;
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static String safeString(Object v) {
        if (v == null) {
            return "";
        }
        return String.valueOf(v);
    }

    private static String str(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        return String.valueOf(v);
    }

    private static boolean bool(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return false;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static double dbl(Object v, double def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static Double dblOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static double parseDouble(String s, double def) {
        if (s == null) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) {
            return "";
        }
        for (String s : xs) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String joinCsvish(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return String.join(",", out);
        }
        return String.valueOf(v);
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        if (hay == null || needle == null) {
            return false;
        }
        return hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

}
