package com.example.lms.orchestration;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.guard.GuardContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                boolean chatDown = nb != null && nb.isOpen(NightmareKeys.CHAT_DRAFT);

                // Soft: request-scoped degradation signals (blank/timeout/parse-fail)
                boolean auxDegraded = (ctx != null && ctx.isAuxDegraded());

                // Hard: breaker-open (or context hard-down) in aux stages
                boolean auxHardDown = (ctx != null && ctx.isAuxHardDown())
                                || (nb != null && nb.isAnyOpen(
                                                NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                                                NightmareKeys.DISAMBIGUATION_CLARIFY,
                                                NightmareKeys.KEYWORD_SELECTION_SELECT,
                                                NightmareKeys.FAST_LLM_COMPLETE));

                boolean auxLlmDown = auxDegraded || auxHardDown;

                boolean webLimited = nb != null && nb.isAnyOpen(
                                NightmareKeys.WEBSEARCH_BRAVE,
                                NightmareKeys.WEBSEARCH_NAVER,
                                NightmareKeys.WEBSEARCH_HYBRID);

                double irr = (ctx != null) ? ctx.getIrregularityScore() : 0.0;

                boolean frustration = looksFrustrated(query);

                // STRIKE: when the system is unstable, focus on short, evidence-first answers.
                boolean strike = auxLlmDown || webLimited || frustration || irr >= 0.35
                                || (ctx != null && ctx.isHighRiskQuery());
                boolean compression = strike;

                // BYPASS: when main draft is down, external providers are both down,
                // or the request is already showing "silent failure" symptoms (auxDown +
                // elevated irregularity).
                boolean webBothDown = nb != null
                                && nb.isOpen(NightmareKeys.WEBSEARCH_BRAVE)
                                && nb.isOpen(NightmareKeys.WEBSEARCH_NAVER);

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
                if (webLimited)
                        reasons.add("web_rate_limited");
                if (webBothDown)
                        reasons.add("web_both_down");
                if (bypassBySilentFailure)
                        reasons.add("bypass_silent_failure=" + String.format(Locale.ROOT, "%.2f", irr));
                if (frustration)
                        reasons.add("user_frustration");
                if (irr >= 0.35)
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
}
