package com.example.lms.strategy;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.guard.rulebreak.RuleBreakContextHolder;
import com.example.lms.guard.rulebreak.RuleBreakPolicy;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Determines the order in which retrieval sources (Web, Vector, Knowledge Graph)
 * should be invoked.
 *
 * <p>
 * Default is fixed Web → Vector → KG (deterministic).
 * If retrieval.order.mode is set to heuristic/dynamic, it will reorder based on:
 * - RuleBreakPolicy (SpeedFirst)
 * - GuardContext (strike/compression/bypass, webRateLimited)
 * - FailurePatternOrchestrator cooldowns
 * </p>
 */
@Service
public class RetrievalOrderService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalOrderService.class);

    public enum Source { WEB, VECTOR, KG }

    @Value("${retrieval.order.mode:fixed}")
    private String mode;

    @Autowired(required = false)
    private FailurePatternOrchestrator failurePatterns;

    /**
     * Decide the retrieval order for a given query text.
     */
    public List<Source> decideOrder(String queryText) {
        String m = (mode == null ? "fixed" : mode.trim()).toLowerCase(Locale.ROOT);
        if (m.isBlank()) {
            m = "fixed";
        }

        // 0) fixed: deterministic order
        if ("fixed".equals(m)) {
            return List.of(Source.WEB, Source.VECTOR, Source.KG);
        }

        // 1) Rule-break policy takes highest priority
        try {
            RuleBreakContext rb = RuleBreakContextHolder.get();
            if (rb != null && rb.isValid()) {
                RuleBreakPolicy p = rb.getPolicy();
                if (p == RuleBreakPolicy.SPEED_FIRST) {
                    return List.of(Source.VECTOR, Source.KG, Source.WEB);
                }
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        // 2) Guard context (budget / strike / rate-limit) hints
        try {
            GuardContext gc = GuardContextHolder.get();
            if (gc != null) {
                // If web is rate-limited or we're in a "cheap mode", prefer vector first.
                if (gc.isWebRateLimited() || gc.isStrikeMode() || gc.isCompressionMode() || gc.isBypassMode()) {
                    return List.of(Source.VECTOR, Source.KG, Source.WEB);
                }
                // If official-only, go web first (domain filtering happens later).
                if (gc.isOfficialOnly()) {
                    return List.of(Source.WEB, Source.VECTOR, Source.KG);
                }
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        // 3) Failure-pattern cooldowns
        boolean webCd = isCoolingDown("web");
        boolean vecCd = isCoolingDown("vector");
        if (webCd && !vecCd) {
            return List.of(Source.VECTOR, Source.KG, Source.WEB);
        }
        if (vecCd && !webCd) {
            return List.of(Source.WEB, Source.KG, Source.VECTOR);
        }
        if (webCd && vecCd) {
            return List.of(Source.KG, Source.VECTOR, Source.WEB);
        }

        // 4) Lightweight heuristic fallback
        String q = queryText == null ? "" : queryText.trim();
        if (q.length() >= 120) {
            // Long / verbose queries tend to benefit from vector retrieval first.
            return List.of(Source.VECTOR, Source.WEB, Source.KG);
        }
        if (looksLikeFactoid(q)) {
            // factoid-ish: KG can be a fast win; otherwise web
            return List.of(Source.KG, Source.WEB, Source.VECTOR);
        }

        // default heuristic
        return List.of(Source.WEB, Source.VECTOR, Source.KG);
    }

    private boolean isCoolingDown(String source) {
        try {
            return failurePatterns != null && failurePatterns.isCoolingDown(source);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksLikeFactoid(String q) {
        if (q == null) {
            return false;
        }
        String s = q.toLowerCase(Locale.ROOT);
        // English wh-words or short direct questions
        if (s.matches(".*\\b(what|who|when|where|why|how)\\b.*")) {
            return true;
        }
        // Korean interrogatives (very rough)
        if (s.contains("뭐") || s.contains("무엇") || s.contains("누구") || s.contains("언제") || s.contains("어디") || s.contains("왜") || s.contains("어떻게")) {
            return true;
        }
        // Short question mark
        return s.length() <= 60 && (s.endsWith("?") || s.contains("?"));
    }
}
