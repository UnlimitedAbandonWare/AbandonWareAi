package com.example.lms.orchestration;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestration signal "bus" that aggregates disparate failure/health signals
 * into a small set of mode flags. This enables consistent routing decisions
 * across planner/retriever/guard/finalizer.
 */
public record OrchestrationSignals(
                boolean nightmareMode,
                boolean auxLlmDown,
                boolean auxDegraded,
                boolean auxHardDown,
                boolean compressionMode,
                boolean strikeMode,
                boolean bypassMode,
                boolean webRateLimited,
                String reason,
                // ChatWorkflow TraceStore 호환 필드
                boolean highRisk,
                double irregularity,
                double userFrustrationScore,
                List<String> reasons) {
        /**
         * Return a human-readable label for the current orchestration mode.
         * Priority: BYPASS > STRIKE > COMPRESSION > NORMAL
         */
        public String modeLabel() {
                if (bypassMode)
                        return "BYPASS";
                if (strikeMode)
                        return "STRIKE";
                if (compressionMode)
                        return "COMPRESSION";
                return "NORMAL";
        }

        public static OrchestrationSignals compute(String query, NightmareBreaker nb, GuardContext ctx) {
                // Breaker keys are often parameterized (e.g. "chat-draft:<model>").
                // Treat any open prefix as chat-down to avoid missing real OPEN states.
                boolean chatDown = nb != null && (nb.isOpenOrHalfOpen(NightmareKeys.CHAT_DRAFT)
                    || nb.isAnyOpenPrefix(NightmareKeys.CHAT_DRAFT + ":"));

                // Soft: request-scoped degradation signals (blank/timeout/parse-fail)
                // [PATCH] Optional aux stages (QueryTransformer/Disambiguation) breaker-open should be
                // treated as request-scoped degradation (COMPRESSION), not hard-down (STRIKE/BYPASS).
                // This avoids quality collapse when optional stages are flaky.
                boolean queryTransformerOpen = (nb != null && nb.isOpen(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM));
                boolean disambiguationOpen = (nb != null && nb.isOpen(NightmareKeys.DISAMBIGUATION_CLARIFY));
                boolean auxDegraded = (ctx != null && ctx.isAuxDegraded())
                                || queryTransformerOpen
                                || disambiguationOpen;

                // Hard: breaker-open (or context hard-down) in aux stages
                // NOTE: QueryTransformer and Disambiguation are *optional* pre-processing stages.
                // If their breakers open, do not escalate to global auxHardDown/STRIKE.
                // MERGE_HOOK:PROJ_AGENT::AUX_HARDDOWN_IGNORE_QT_V1
                boolean auxHardDown = (ctx != null && ctx.isAuxHardDown())
                                || (nb != null && nb.isAnyOpen(
                                                NightmareKeys.KEYWORD_SELECTION_SELECT,
                                                NightmareKeys.FAST_LLM_COMPLETE));

                boolean auxLlmDown = auxDegraded || auxHardDown;

                boolean webLimited = nb != null && nb.isAnyOpen(
                                NightmareKeys.WEBSEARCH_BRAVE,
                                NightmareKeys.WEBSEARCH_NAVER,
                                NightmareKeys.WEBSEARCH_HYBRID);

                boolean webBothDown = nb != null
                                && nb.isOpen(NightmareKeys.WEBSEARCH_BRAVE)
                                && nb.isOpen(NightmareKeys.WEBSEARCH_NAVER);

                double irr = (ctx != null) ? ctx.getIrregularityScore() : 0.0;

                boolean frustration = looksFrustrated(query);

                // ✅ STRIKE: hard-failure 중심 (soft-degrade만으로는 STRIKE 금지)
                // - STRIKE = safe 플랜 강제 + Guard 강화 + 기능 대폭 축소
                // [PATCH]
                // - webLimited(한 provider down)는 compression으로 처리
                // - irreg 임계치 상향으로 보조단 미작동 시에도 STRIKE 진입 억제
                boolean strike = auxHardDown || webBothDown || frustration || irr >= 0.60
                                || (ctx != null && ctx.isHighRiskQuery());

                // ✅ COMPRESSION: strike + soft-degrade 포함
                // - 비용/지연 절감 모드이나, 기능 자체는 유지
                boolean compression = strike || webLimited || auxDegraded || irr >= 0.35;

                // BYPASS: when main draft is down, external providers are both down,
                // or the request is already showing "silent failure" symptoms (auxDown +
                // elevated irregularity).

                // BYPASS only on hard-down, not soft degrade
                boolean bypassBySilentFailure = auxHardDown && irr >= 0.25;

                boolean bypass = chatDown
                                || webBothDown
                                || bypassBySilentFailure
                                || (ctx != null && ctx.isBypassMode());

                List<String> reasons = new ArrayList<>();
                if (chatDown)
                        reasons.add("chat_down");
                if (auxHardDown)
                        reasons.add("aux_down_hard");
                else if (auxDegraded)
                        reasons.add("aux_down_soft");
                if (queryTransformerOpen)
                        reasons.add("qt_open");
                if (disambiguationOpen)
                        reasons.add("disambiguation_open");
                if (webLimited)
                        reasons.add("web_rate_limited");
                if (webBothDown)
                        reasons.add("web_both_down");
                if (bypassBySilentFailure)
                        reasons.add("bypass_silent_failure=" + String.format(Locale.ROOT, "%.2f", irr));
                if (frustration)
                        reasons.add("user_frustration");
                if (irr > 0.0)
                        reasons.add("irregularity=" + String.format(Locale.ROOT, "%.2f", irr));
                if (ctx != null && ctx.isHighRiskQuery())
                        reasons.add("high_risk");

                String reason = String.join(",", reasons);

                return new OrchestrationSignals(
                                chatDown,
                                auxLlmDown,
                                auxDegraded,
                                auxHardDown,
                                compression,
                                strike,
                                bypass,
                                webLimited,
                                reason,
                                // 새로 추가된 필드들
                                (ctx != null && ctx.isHighRiskQuery()), // highRisk
                                irr, // irregularity
                                frustration ? 1.0 : 0.0, // userFrustrationScore
                                reasons); // reasons (List<String>)
        }


        /**
         * Debug-only: emit quantitative mode scores + leave-one-out ablation contributions.
         *
         * <p>Does not affect routing decisions. Intended to make STRIKE/BYPASS regressions easier to diagnose
         * from TraceStore alone (no need to reproduce locally).</p>
         */
        public void emitDebugScorecardToTrace() {
                try {
                        boolean webBothDown = hasReasonExact("web_both_down");
                        boolean qtOpen = hasReasonExact("qt_open");
                        boolean disambOpen = hasReasonExact("disambiguation_open");
                        boolean silentBypassGate = hasReasonPrefix("bypass_silent_failure=");

                        double irr = clamp01(this.irregularity);
                        double fr = clamp01(this.userFrustrationScore);
                        double hr = this.highRisk ? 1.0 : 0.0;

                        List<Factor> factors = new ArrayList<>();
                        factors.add(new Factor("chatDown", this.nightmareMode ? 1.0 : 0.0, 3.0, 3.0,
                                        "NightmareKeys.CHAT_DRAFT open"));
                        factors.add(new Factor("webBothDown", webBothDown ? 1.0 : 0.0, 2.6, 2.6,
                                        "WEB providers both down"));
                        factors.add(new Factor("webRateLimited", this.webRateLimited ? 1.0 : 0.0, 0.4, 0.3,
                                        "WEB provider rate-limited"));
                        factors.add(new Factor("auxHardDown", this.auxHardDown ? 1.0 : 0.0, 2.0, 1.2,
                                        "aux hard-down (keyword-select/fast-llm)"));
                        factors.add(new Factor("auxDegraded", this.auxDegraded ? 1.0 : 0.0, 0.8, 0.4,
                                        "aux degraded (soft)"));
                        factors.add(new Factor("qtOpen", qtOpen ? 1.0 : 0.0, 0.4, 0.1,
                                        "QueryTransformer breaker-open"));
                        factors.add(new Factor("disambOpen", disambOpen ? 1.0 : 0.0, 0.3, 0.1,
                                        "Disambiguation breaker-open"));
                        factors.add(new Factor("irregularity", irr, 2.0, 1.3,
                                        "irregularity score"));
                        factors.add(new Factor("userFrustration", fr, 1.0, 0.3,
                                        "user frustration heuristic"));
                        factors.add(new Factor("highRisk", hr, 1.2, 0.6,
                                        "high-risk query"));
                        factors.add(new Factor("silentBypassGate", silentBypassGate ? 1.0 : 0.0, 0.0, 1.5,
                                        "auxHardDown && irregularity>=0.25 gate"));

                        // logistic-style scores (debug only)
                        double strikeLogit = -2.0;
                        double bypassLogit = -2.0;
                        for (Factor f : factors) {
                                strikeLogit += f.value * f.wStrike;
                                bypassLogit += f.value * f.wBypass;
                        }
                        double strikeProb = sigmoid(strikeLogit);
                        double bypassProb = sigmoid(bypassLogit);

                        TraceStore.put("orch.debug.score.version", "v1");
                        TraceStore.put("orch.debug.score.strike.logit", String.format(Locale.ROOT, "%.4f", strikeLogit));
                        TraceStore.put("orch.debug.score.strike.prob", String.format(Locale.ROOT, "%.4f", strikeProb));
                        TraceStore.put("orch.debug.score.bypass.logit", String.format(Locale.ROOT, "%.4f", bypassLogit));
                        TraceStore.put("orch.debug.score.bypass.prob", String.format(Locale.ROOT, "%.4f", bypassProb));

                        List<Map<String, Object>> factorRows = new ArrayList<>();
                        for (Factor f : factors) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("name", f.name);
                                row.put("value", f.value);
                                row.put("wStrike", f.wStrike);
                                row.put("cStrike", f.value * f.wStrike);
                                row.put("wBypass", f.wBypass);
                                row.put("cBypass", f.value * f.wBypass);
                                row.put("note", f.note);
                                factorRows.add(row);
                        }
                        TraceStore.put("orch.debug.score.factors", factorRows);

                        List<Map<String, Object>> ablationStrike = new ArrayList<>();
                        List<Map<String, Object>> ablationBypass = new ArrayList<>();
                        for (Factor f : factors) {
                                if (f.value != 0.0 && f.wStrike != 0.0) {
                                        double probNo = sigmoid(strikeLogit - f.value * f.wStrike);
                                        double delta = strikeProb - probNo;
                                        if (delta > 1e-9) {
                                                Map<String, Object> r = new LinkedHashMap<>();
                                                r.put("factor", f.name);
                                                r.put("deltaProb", delta);
                                                r.put("value", f.value);
                                                r.put("w", f.wStrike);
                                                ablationStrike.add(r);
                                        }
                                }
                                if (f.value != 0.0 && f.wBypass != 0.0) {
                                        double probNo = sigmoid(bypassLogit - f.value * f.wBypass);
                                        double delta = bypassProb - probNo;
                                        if (delta > 1e-9) {
                                                Map<String, Object> r = new LinkedHashMap<>();
                                                r.put("factor", f.name);
                                                r.put("deltaProb", delta);
                                                r.put("value", f.value);
                                                r.put("w", f.wBypass);
                                                ablationBypass.add(r);
                                        }
                                }
                        }

                        ablationStrike.sort(Comparator.comparingDouble(m -> -((Number) m.get("deltaProb")).doubleValue()));
                        ablationBypass.sort(Comparator.comparingDouble(m -> -((Number) m.get("deltaProb")).doubleValue()));

                        if (ablationStrike.size() > 8) ablationStrike = ablationStrike.subList(0, 8);
                        if (ablationBypass.size() > 8) ablationBypass = ablationBypass.subList(0, 8);

                        TraceStore.put("orch.debug.ablation.strike", ablationStrike);
                        TraceStore.put("orch.debug.ablation.bypass", ablationBypass);
                } catch (Throwable ignore) {
                        // best-effort
                }
        }

        private boolean hasReasonExact(String r) {
                if (r == null || r.isBlank() || this.reasons == null) return false;
                for (String s : this.reasons) {
                        if (r.equals(s)) return true;
                }
                return false;
        }

        private boolean hasReasonPrefix(String prefix) {
                if (prefix == null || prefix.isBlank() || this.reasons == null) return false;
                for (String s : this.reasons) {
                        if (s != null && s.startsWith(prefix)) return true;
                }
                return false;
        }

        private static double clamp01(double v) {
                if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
                if (v < 0.0) return 0.0;
                if (v > 1.0) return 1.0;
                return v;
        }

        private static double sigmoid(double x) {
                if (x > 60) return 1.0;
                if (x < -60) return 0.0;
                return 1.0 / (1.0 + Math.exp(-x));
        }

        private static record Factor(String name, double value, double wStrike, double wBypass, String note) {
        }

        private static boolean looksFrustrated(String q) {
                if (q == null)
                        return false;
                String s = q.trim();
                if (s.isEmpty())
                        return false;
                String lower = s.toLowerCase(Locale.ROOT);
                // KO + EN quick heuristics
                return lower.contains("왜") && (lower.contains("안") || lower.contains("안돼"))
                                || lower.contains("또")
                                || lower.contains("빨리")
                                || lower.contains("제발")
                                || lower.contains("에러")
                                || lower.contains("망했")
                                || lower.contains("timeout")
                                || lower.contains("timed out")
                                || lower.contains("error")
                                || lower.contains("failed")
                                || lower.contains("cannot")
                                || lower.contains("can't")
                                || lower.contains("stuck");
        }


        // MERGE_HOOK:PROJ_AGENT::ORCH_PARTS_TABLE_EMIT
        /**
         * Emit a human-readable "parts build-up" table so operators can see which
         * optional/critical modules were activated for this request.
         */
        public void emitPartsPlanToTrace(
                        boolean useWeb,
                        boolean useRag,
                        OrchestrationHints hints,
	                    boolean plannerUsed,
	                    boolean plannerAllowedByStagePolicy,
	                    boolean queryTransformerAllowedByStagePolicy,
                        int plannedQueries,
                        boolean verifyFlag) {
                try {
                        String mode = modeLabel();
                        List<String> rows = new ArrayList<>();

	                    String plannerState = (!plannerAllowedByStagePolicy) ? "OFF(policy)" : (plannerUsed ? "ON" : "OFF");
	                    rows.add(row("AMPLIFY", "QueryPlanner", "plan:queryPlanner",
	                                    plannerState,
	                                    "useWeb=" + useWeb + ", planned=" + plannedQueries + ", mode=" + mode));

	                    String qtxState;
	                    if (!queryTransformerAllowedByStagePolicy) {
	                            qtxState = "OFF(policy)";
	                    } else if ("BYPASS".equals(mode)) {
	                            qtxState = "OFF(mode)";
	                    } else if (auxLlmDown()) {
	                            qtxState = "OFF(auxDown)";
	                    } else {
	                            qtxState = plannerUsed ? "COND" : "OFF";
	                    }
	                    rows.add(row("AMPLIFY", "QueryTransformer", "query-transformer:*",
	                                    qtxState,
	                                    "planner=" + plannerUsed + ", auxLlmDown=" + auxLlmDown() + ", mode=" + mode));

                        boolean allowWeb = useWeb && (hints == null || hints.isAllowWeb());
                        boolean allowVec = useRag && (hints == null || hints.isAllowRag());
                        rows.add(row("RETRIEVE", "Web", "retrieval:web",
                                        allowWeb ? "ON" : "OFF",
                                        "useWeb=" + useWeb + ", webRateLimited=" + webRateLimited()));
                        rows.add(row("RETRIEVE", "Vector", "retrieval:vector",
                                        allowVec ? "ON" : "OFF",
                                        "useRag=" + useRag));

                        rows.add(row("RETRIEVE", "SelfAsk", "retrieval:selfAsk",
                                        (hints != null && hints.isEnableSelfAsk()) ? "ON" : "OFF",
                                        "hint=" + (hints != null && hints.isEnableSelfAsk())));

                        rows.add(row("REFINE", "CrossEncoder", "rerank:crossEncoder",
                                        (hints != null && hints.isEnableCrossEncoder()) ? "ON" : "OFF",
                                        "mode=" + mode));
                        rows.add(row("REFINE", "FactVerify", "verify:factVerifier",
                                        verifyFlag ? "COND" : "OFF",
                                        "reqFlag=" + verifyFlag));

                        rows.add(row("GUARD", "EvidenceGuard", "guard:evidence",
                                        "ON", "highRisk=" + highRisk()));
                        rows.add(row("REINFORCE", "Memory", "memory:reinforcement",
                                        "COND", "dependsOn=memoryEnabled"));

	                    TraceStore.put("orch.parts.summary", "mode=" + mode
	                                    + ", planner=" + plannerState
                                        + ", web=" + (allowWeb ? "ON" : "OFF")
                                        + ", vec=" + (allowVec ? "ON" : "OFF")
                                        + ", rerank=" + (hints != null && hints.isEnableCrossEncoder() ? "ON" : "OFF"));
                        TraceStore.put("orch.parts.table", rows);

                        // [PATCH] UAW: lightweight failure-pattern signature for clustering.
                        // (fail-soft; if TraceStore is empty or keys are missing, signature becomes "empty")
                        try {
                            long pid = com.example.lms.cfvm.RawSlotExtractor.patternIdFromTrace(TraceStore.getAll());
                            TraceStore.put("cfvm.patternId", pid);
                        } catch (Exception ignore) {
                            // ignore
                        }
                } catch (Throwable ignore) {
                }
        }

        private static String row(String group, String part, String stageKey, String state, String reason) {
                return group + " :: " + part + " {" + stageKey + "} = " + state + " (" + reason + ")";
        }

}