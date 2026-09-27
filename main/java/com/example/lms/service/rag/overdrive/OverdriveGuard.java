package com.example.lms.service.rag.overdrive;

import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class OverdriveGuard {

    private static final Logger log = LoggerFactory.getLogger(OverdriveGuard.class);

    private final AuthorityScorer authority;
    private final ContradictionScorer contradiction;
    private final ObjectProvider<RagFailureBlackboxService> blackboxProvider;

    private boolean enabled = true;
    private int minPool = 4;
    private double minAuthorityAvg = 0.55d;
    private double contradictionTh = 0.60d;
    private double scoreThreshold = 0.55d;
    private double errorRateTh = 0.35d;
    private double errorWeight = 0.10d;
    private double starvationTh = 0.50d;
    private double starvationWeight = 0.08d;
    private double retrievalFailureWeight = 0.08d;

    @Autowired
    public OverdriveGuard(@Qualifier("authAuthorityScorer") AuthorityScorer authority,
                          ContradictionScorer contradiction,
                          OverdriveProperties properties,
                          ObjectProvider<RagFailureBlackboxService> blackboxProvider) {
        this.authority = authority;
        this.contradiction = contradiction;
        this.blackboxProvider = blackboxProvider;
        applyProperties(properties);
    }

    public OverdriveGuard(AuthorityScorer authority, ContradictionScorer contradiction) {
        this(authority, contradiction, new OverdriveProperties(), null);
    }

    public OverdriveGuard(AuthorityScorer authority,
                          ContradictionScorer contradiction,
                          ObjectProvider<RagFailureBlackboxService> blackboxProvider) {
        this(authority, contradiction, new OverdriveProperties(), blackboxProvider);
    }

    public boolean shouldActivate(String query, List<Content> candidates) {
        List<Content> safeCandidates = candidates == null ? List.of() : candidates;
        if (!enabled) {
            traceDecision(false, "disabled", 0.0d, 0.0d, 0.0d, 0.0d, 0.0d,
                    safeCandidates, query, false, false, false, 0.0d, 0.0d);
            return false;
        }

        double sparse = safeCandidates.size() < minPool
                ? (minPool - safeCandidates.size()) / (double) Math.max(1, minPool)
                : 0.0d;

        double avgAuth = safeCandidates.stream()
                .map(content -> decayForUrl(extractUrl(text(content))))
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.25d);
        double lowAuthority = avgAuth < minAuthorityAvg && minAuthorityAvg > 0.0d
                ? (minAuthorityAvg - avgAuth) / minAuthorityAvg
                : 0.0d;

        double baseScore = 0.5d * sparse + 0.3d * lowAuthority;
        GuardContext ctx = GuardContextHolder.get();
        boolean aggressive = ctx != null && ctx.planBool("overdrive.aggressive", false);
        double effectiveThreshold = aggressive ? round4(scoreThreshold * 0.60d) : scoreThreshold;

        if (ctx != null && (ctx.isAuxDown() || ctx.isStrikeMode() || ctx.isCompressionMode()
                || !ctx.planBool("overdrive.enabled", true))) {
            traceDecision(false, "guard_context_skip", baseScore, avgAuth, sparse, lowAuthority, 0.0d,
                    safeCandidates, query, aggressive, false, false, 0.0d, effectiveThreshold);
            return false;
        }

        double score = baseScore;
        double errorRate = errorRate();
        boolean errorTriggered = errorRate >= errorRateTh;
        if (errorTriggered) {
            score += errorWeight;
        }

        boolean starvationTriggered = starvationPressure() >= starvationTh;
        if (starvationTriggered) {
            score += starvationWeight + retrievalFailureWeight;
        } else if (retrievalFailureSignal()) {
            score += retrievalFailureWeight;
        }

        BlackboxContribution blackbox = blackboxContribution();
        score += blackbox.score();

        double contrad = pairwiseContradictionMean(safeCandidates, 3);
        double highContrad = contrad > contradictionTh
                ? (contrad - contradictionTh) / Math.max(0.0001d, 1.0d - contradictionTh)
                : 0.0d;
        if (highContrad > 0.0d) {
            score += 0.2d * highContrad;
        }

        boolean activated = score >= effectiveThreshold;
        String reason = reasonFor(activated, baseScore, effectiveThreshold, highContrad,
                blackbox, starvationTriggered, errorTriggered);
        traceDecision(activated, reason, score, avgAuth, sparse, lowAuthority, contrad,
                safeCandidates, query, aggressive, starvationTriggered, highContrad > 0.0d,
                errorRate, effectiveThreshold);
        return activated;
    }

    public double getScoreThreshold() {
        return scoreThreshold;
    }

    private void applyProperties(OverdriveProperties properties) {
        OverdriveProperties safe = properties == null ? new OverdriveProperties() : properties;
        enabled = safe.isEnabled();
        minPool = safe.getMinPool();
        minAuthorityAvg = safe.getMinAuthority();
        contradictionTh = safe.getContradictionTh();
        scoreThreshold = safe.getScoreTh();
        errorRateTh = safe.getErrorRateTh();
        errorWeight = safe.getErrorWeight();
        starvationTh = safe.getStarvationTh();
        starvationWeight = safe.getStarvationWeight();
        retrievalFailureWeight = safe.getRetrievalFailureWeight();
    }

    private static String text(Content content) {
        return content == null || content.textSegment() == null ? null : content.textSegment().text();
    }

    private static String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf("http");
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(' ', start);
        return end > start ? text.substring(start, end) : text.substring(start);
    }

    private Double decayForUrl(String url) {
        if (url == null) {
            return 0.25d;
        }
        return authority.decayFor(authority.getSourceCredibility(url));
    }

    private double pairwiseContradictionMean(List<Content> contents, int maxPairs) {
        List<String> texts = contents.stream().map(OverdriveGuard::text).filter(Objects::nonNull).toList();
        int used = 0;
        double sum = 0.0d;
        for (int i = 0; i < texts.size() && used < maxPairs; i++) {
            for (int j = i + 1; j < texts.size() && used < maxPairs; j++) {
                sum += contradiction.score(texts.get(i), texts.get(j));
                used++;
            }
        }
        return used == 0 ? 0.0d : sum / used;
    }

    private double errorRate() {
        long total = safeLong("web.await.events.count");
        long timeout = safeLong("web.await.events.timeout.count");
        long rateLimited = safeLong("web.await.events.rateLimit.count");
        long cancelled = safeLong("web.await.events.cancelled.count");
        if (total <= 0L) {
            return 0.0d;
        }
        return round4(Math.min(1.0d, (timeout + rateLimited + cancelled) / (double) total));
    }

    private double starvationPressure() {
        return afterFilterStarved("web.naver.filter.rawCount", "web.naver.afterFilterCount")
                || afterFilterStarved("web.naver.returnedCount", "web.naver.afterFilterCount")
                || afterFilterStarved("web.brave.returnedCount", "web.brave.afterFilterCount")
                || afterFilterStarved("web.serpapi.returnedCount", "web.serpapi.afterFilterCount")
                || afterFilterStarved("web.tavily.returnedCount", "web.tavily.afterFilterCount")
                || safeLong("web.failsoft.starvationFallback.count") > 0L
                || safeLong("starvationFallback.count") > 0L
                ? 1.0d : 0.0d;
    }

    private boolean retrievalFailureSignal() {
        return truthy("web.naver.providerDisabled")
                || truthy("web.brave.providerDisabled")
                || truthy("web.serpapi.providerDisabled")
                || truthy("web.tavily.providerDisabled")
                || truthy("web.naver.zeroResults")
                || truthy("web.brave.zeroResults")
                || truthy("web.serpapi.zeroResults")
                || truthy("web.tavily.zeroResults");
    }

    private boolean afterFilterStarved(String rawKey, String afterKey) {
        return safeLong(rawKey) > 0L && TraceStore.getAll().containsKey(afterKey) && safeLong(afterKey) <= 0L;
    }

    private boolean truthy(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        String s = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private BlackboxContribution blackboxContribution() {
        if (blackboxProvider == null) {
            traceBlackboxUnavailable("provider_unavailable");
            return BlackboxContribution.none();
        }
        try {
            RagFailureBlackboxService service = blackboxProvider.getIfAvailable();
            if (service == null) {
                traceBlackboxUnavailable("provider_unavailable");
                return BlackboxContribution.none();
            }
            RagFailureBlackboxService.Snapshot snapshot = service.currentOrRefresh("overdrive");
            if (snapshot == null || snapshot.riskScore() <= 0.0d) {
                TraceStore.put("overdrive.blackbox.available", false);
                TraceStore.put("overdrive.blackbox.disabledReason",
                        snapshot == null ? "snapshot_unavailable" : SafeRedactor.traceLabelOrFallback(snapshot.decisionReason(), "none"));
                return BlackboxContribution.none();
            }
            TraceStore.put("overdrive.blackbox.available", true);
            TraceStore.put("overdrive.blackbox.absent", false);
            TraceStore.put("overdrive.blackbox.riskScore", round4(snapshot.riskScore()));
            TraceStore.put("overdrive.blackbox.priorityScore", round4(snapshot.priorityScore()));
            TraceStore.put("overdrive.blackbox.dominantFailure",
                    SafeRedactor.traceLabelOrFallback(snapshot.dominantFailure(), "none"));
            TraceStore.put("overdrive.blackbox.restoreAction",
                    SafeRedactor.traceLabelOrFallback(snapshot.restoreAction(), "observe_only"));
            double contribution = snapshot.highRisk() ? retrievalFailureWeight : 0.0d;
            return new BlackboxContribution(round4(contribution), snapshot.restoreAction());
        } catch (RuntimeException ex) {
            TraceStore.put("overdrive.blackbox.refresh.failed", true);
            TraceStore.put("overdrive.blackbox.refresh.errorType", ex.getClass().getSimpleName());
            log.debug("[OverdriveGuard] blackbox refresh failed stage=blackbox.refresh err=trace-failure type={}",
                    ex.getClass().getSimpleName());
            return BlackboxContribution.none();
        }
    }

    private static void traceBlackboxUnavailable(String reason) {
        TraceStore.put("overdrive.blackbox.absent", true);
        TraceStore.put("overdrive.blackbox.available", false);
        TraceStore.put("overdrive.blackbox.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
    }

    private String reasonFor(boolean activated,
                             double baseScore,
                             double effectiveThreshold,
                             double highContrad,
                             BlackboxContribution blackbox,
                             boolean starvationTriggered,
                             boolean errorTriggered) {
        if (!activated) {
            return "below_threshold";
        }
        if (baseScore >= effectiveThreshold) {
            return "base_threshold";
        }
        if (blackbox.score() > 0.0d) {
            return "blackbox_risk";
        }
        if (starvationTriggered) {
            return "retrieval_starvation";
        }
        if (errorTriggered) {
            return "error_rate";
        }
        if (highContrad > 0.0d) {
            return "threshold";
        }
        return "threshold";
    }

    private static void traceDecision(boolean activated,
                                      String reason,
                                      double score,
                                      double avgAuthority,
                                      double sparse,
                                      double lowAuthority,
                                      double contradictionScore,
                                      List<Content> candidates,
                                      String query,
                                      boolean aggressive,
                                      boolean starvationTriggered,
                                      boolean contradicted,
                                      double errorRate,
                                      double effectiveThreshold) {
        try {
            String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
            int candidateCount = candidates == null ? 0 : candidates.size();
            double safeScore = round4(score);
            TraceStore.put("overdrive.activated", activated);
            TraceStore.put("overdrive.triggered", activated);
            TraceStore.put("overdrive.trigger.activated", activated);
            TraceStore.put("overdrive.score", safeScore);
            TraceStore.put("overdrive.trigger.score", safeScore);
            TraceStore.put("overdrive.reason", safeReason);
            TraceStore.put("overdrive.skipReason", activated ? "" : safeReason);
            TraceStore.put("overdrive.bypassReason", activated ? "" : safeReason);
            TraceStore.put("overdrive.candidates.count", candidateCount);
            TraceStore.put("overdrive.candidateCount", candidateCount);
            TraceStore.put("overdrive.authorityMean", round4(avgAuthority));
            TraceStore.put("overdrive.trigger.sparse", sparse > 0.0d);
            TraceStore.put("overdrive.trigger.lowAuth", lowAuthority > 0.0d);
            TraceStore.put("overdrive.trigger.contradicted", contradicted);
            TraceStore.put("overdrive.trigger.candidateCount", candidateCount);
            TraceStore.put("overdrive.trigger.avgAuthority", round4(avgAuthority));
            TraceStore.put("overdrive.trigger.contradictionScore", round4(contradictionScore));
            TraceStore.put("overdrive.triggerReasons",
                    triggerReasons(sparse, lowAuthority, contradicted, starvationTriggered, errorRate));
            TraceStore.put("overdrive.error.rate", round4(errorRate));
            TraceStore.put("overdrive.aggressive", aggressive);
            TraceStore.put("overdrive.score.threshold.effective", round4(effectiveThreshold));
            TraceStore.put("overdrive.stagesApplied", 0);
            TraceStore.put("overdrive.finalCandidateCount", -1);
            TraceStore.put("overdrive.exactPhraseProbeUsed", false);
            TraceStore.put("overdrive.queryHash", safeHash(query));
        } catch (RuntimeException ex) {
            log.debug("[OverdriveGuard] traceDecision failed err={}",
                    "trace-failure stage=trace.snapshot err=trace-failure type=" + ex.getClass().getSimpleName());
        }
    }

    private static String safeHash(String value) {
        try {
            return SafeRedactor.hash12(value == null ? "" : value);
        } catch (RuntimeException ex) {
            log.debug("[OverdriveGuard] hash fallback failed stage=hash.fallback err=trace-failure type={}",
                    ex.getClass().getSimpleName());
            return "";
        }
    }

    private static String triggerReasons(double sparse,
                                         double lowAuthority,
                                         boolean contradicted,
                                         boolean starvationTriggered,
                                         double errorRate) {
        StringBuilder reasons = new StringBuilder();
        appendReason(reasons, sparse > 0.0d, "sparse");
        appendReason(reasons, lowAuthority > 0.0d, "lowAuth");
        appendReason(reasons, contradicted, "contradicted");
        appendReason(reasons, starvationTriggered, "starvation");
        appendReason(reasons, errorRate > 0.0d, "errorRate");
        return reasons.isEmpty() ? "none" : reasons.toString();
    }

    private static void appendReason(StringBuilder out, boolean enabled, String reason) {
        if (!enabled) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(',');
        }
        out.append(reason);
    }

    private static long safeLong(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            log.debug("[OverdriveGuard] long parse failed stage=long.parse err=trace-failure key={} type={}",
                    SafeRedactor.traceLabelOrFallback(key, "unknown"), ex.getClass().getSimpleName());
            return 0L;
        }
    }

    private static double safeDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            log.debug("[OverdriveGuard] double parse failed stage=double.parse err=trace-failure type={}",
                    ex.getClass().getSimpleName());
            return 0.0d;
        }
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private record BlackboxContribution(double score, String restoreAction) {
        static BlackboxContribution none() {
            return new BlackboxContribution(0.0d, "observe_only");
        }
    }
}
