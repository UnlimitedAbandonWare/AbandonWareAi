package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.Map;
import java.util.Objects;

final class TraceHtmlWebFailSoftRiskCalloutRenderer {

    private TraceHtmlWebFailSoftRiskCalloutRenderer() {
    }

    static String render(Map<String, Object> extraMeta) {
        try {
            if (extraMeta == null || extraMeta.isEmpty()) {
                return "";
            }

            long deficit = toLong(extraMeta.get("web.failsoft.minCitationsRescue.preflight.deficit"));
            long maxRemainingMs = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.max.remainingMs"));
            long maxDelayMs = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.max.delayMs"));
            long skippedCooldownCount = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.skipped.cooldown.count"));

            boolean attempted = truthy(extraMeta.get("web.failsoft.minCitationsRescue.attempted"));
            boolean satisfied = truthy(extraMeta.get("web.failsoft.minCitationsRescue.satisfied"));
            boolean providerRisk = hasProviderRisk(extraMeta);

            if (deficit <= 0 && maxRemainingMs <= 0 && maxDelayMs <= 0 && skippedCooldownCount <= 0 && !attempted
                    && !providerRisk) {
                return "";
            }

            String tradeoffClass = getString(extraMeta, "web.failsoft.rateLimitBackoff.cooldownTradeoff.class");
            String tradeoffReason = SafeRedactor.traceLabelOrFallback(extraMeta.get("web.failsoft.rateLimitBackoff.cooldownTradeoff.reason"), "");
            long awaitReconciledApplyTimes = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.awaitTimeoutReconciledApplyTimes"));

            long needed = toLong(extraMeta.get("web.failsoft.minCitationsRescue.preflight.needed"));
            long citeable = toLong(extraMeta.get("web.failsoft.minCitationsRescue.preflight.citeableCount"));
            boolean preflightEligible = truthy(extraMeta.get("web.failsoft.minCitationsRescue.preflight.eligible"));
            String preflightBlockReason = SafeRedactor.traceLabelOrFallback(extraMeta.get("web.failsoft.minCitationsRescue.preflight.blockReason"), "");
            long candCount = toLong(extraMeta.get("web.failsoft.minCitationsRescue.preflight.candidates.count"));
            String blockReason = SafeRedactor.traceLabelOrFallback(extraMeta.get("web.failsoft.minCitationsRescue.blockReason"), "");
            long inserted = toLong(extraMeta.get("web.failsoft.minCitationsRescue.insertedCount"));

            StringBuilder sb = new StringBuilder();
            sb.append("<div class='callout trace-callout'>");
            sb.append("<div class='callout-title'>Web FailSoft Risk</div>");
            sb.append("<div class='callout-body'>");

            sb.append("<div><b>Backoff</b>: ");
            sb.append(isNonBlank(tradeoffClass) ? escape(tradeoffClass) : "n/a");
            if (isNonBlank(tradeoffReason)) {
                sb.append(" <span class='muted'>(").append(escape(tradeoffReason)).append(")</span>");
            }
            sb.append("</div>");

            sb.append("<div class='muted'>");
            sb.append("skipped.cooldown.count=").append(skippedCooldownCount);
            sb.append(", max.delayMs=").append(maxDelayMs);
            sb.append(", max.remainingMs=").append(maxRemainingMs);
            sb.append(", awaitTimeoutReconciledApplyTimes=").append(awaitReconciledApplyTimes);
            sb.append("</div>");

            sb.append("<details><summary class='muted'>provider details</summary>");
            sb.append("<ul>");
            appendProviderDetail(sb, extraMeta, "naver");
            appendProviderDetail(sb, extraMeta, "brave");
            appendProviderDetail(sb, extraMeta, "serpapi");
            appendProviderDetail(sb, extraMeta, "tavily");
            sb.append("</ul>");
            sb.append("</details>");

            sb.append("<div style='margin-top:8px'><b>MinCitations rescue</b>: ");
            if (attempted) {
                sb.append(satisfied ? "OK" : "UNSAT");
                if (!satisfied && isNonBlank(blockReason)) {
                    sb.append(" <span class='muted'>(").append(escape(blockReason)).append(")</span>");
                }
            } else if (preflightEligible) {
                sb.append("READY");
            } else if (deficit > 0) {
                sb.append("SKIP");
                if (isNonBlank(preflightBlockReason)) {
                    sb.append(" <span class='muted'>(").append(escape(preflightBlockReason)).append(")</span>");
                }
            } else {
                sb.append("n/a");
            }
            sb.append(" ??needed=").append(needed);
            sb.append(", citeable=").append(citeable);
            sb.append(", deficit=").append(deficit);
            sb.append(", inserted=").append(inserted);
            sb.append("</div>");

            sb.append("<div class='muted'>preflight candidates: ").append(candCount).append("</div>");
            appendCandidateQueries(sb, extraMeta.get("web.failsoft.minCitationsRescue.preflight.candidates.top3"));

            sb.append("</div>");
            sb.append("</div>");
            return sb.toString();
        } catch (RuntimeException ignore) {
            TraceStore.put("traceHtml.webFailSoftRisk.suppressed.render", true);
            TraceStore.put("traceHtml.webFailSoftRisk.suppressed.render.errorType", errorType(ignore));
            return "";
        }
    }

    private static void appendProviderDetail(StringBuilder sb, Map<String, Object> extraMeta, String provider) {
        String prefix = "web." + provider + ".";
        String backoff = "web.failsoft.rateLimitBackoff." + provider + ".";
        String skip = SafeRedactor.traceLabelOrFallback(extraMeta.get(prefix + "skipped.reason"), "");
        String disabled = SafeRedactor.traceLabelOrFallback(extraMeta.get(prefix + "disabledReason"), "");
        String failure = SafeRedactor.traceLabelOrFallback(extraMeta.get(prefix + "failureReason"), "");
        String last = getString(extraMeta, backoff + "last.kind");
        sb.append("<li><b>").append(escape(provider)).append("</b>: skip=").append(isNonBlank(skip) ? escape(skip) : "-")
                .append(", disabled=").append(truthy(extraMeta.get(prefix + "providerDisabled")));
        if (isNonBlank(disabled)) {
            sb.append("/").append(escape(disabled));
        }
        sb.append(", failure=").append(isNonBlank(failure) ? escape(failure) : "-")
                .append(", rateLimited=").append(truthy(extraMeta.get(prefix + "rateLimited")))
                .append(", retryAfterMs=").append(Objects.toString(toLong(extraMeta.get(prefix + "retryAfterMs")), "0"))
                .append(", empty=").append(truthy(extraMeta.get(prefix + "providerEmpty")))
                .append(", afterFilterStarved=").append(truthy(extraMeta.get(prefix + "afterFilterStarved")))
                .append(", remainingMs=").append(Objects.toString(toLong(extraMeta.get(backoff + "remainingMs")), "0"))
                .append(", last=").append(isNonBlank(last) ? escape(last) : "-")
                .append("/").append(Objects.toString(toLong(extraMeta.get(backoff + "last.streak")), "0"))
                .append(", delayMs=").append(Objects.toString(toLong(extraMeta.get(backoff + "last.delayMs")), "0"))
                .append(", capHit=").append(truthy(extraMeta.get(backoff + "last.capHit"))).append("</li>");
    }

    private static boolean hasProviderRisk(Map<String, Object> extraMeta) {
        for (String provider : java.util.List.of("naver", "brave", "serpapi", "tavily")) {
            String prefix = "web." + provider + ".";
            if (truthy(extraMeta.get(prefix + "skipped"))
                    || truthy(extraMeta.get(prefix + "providerDisabled"))
                    || truthy(extraMeta.get(prefix + "rateLimited"))
                    || truthy(extraMeta.get(prefix + "providerEmpty"))
                    || truthy(extraMeta.get(prefix + "afterFilterStarved"))
                    || isNonBlank(SafeRedactor.traceLabelOrFallback(extraMeta.get(prefix + "skipped.reason"), ""))
                    || isNonBlank(SafeRedactor.traceLabelOrFallback(extraMeta.get(prefix + "disabledReason"), ""))
                    || isNonBlank(SafeRedactor.traceLabelOrFallback(extraMeta.get(prefix + "failureReason"), ""))) {
                return true;
            }
        }
        return false;
    }

    private static void appendCandidateQueries(StringBuilder sb, Object top3Obj) {
        if (!(top3Obj instanceof java.util.List<?> list) || list.isEmpty()) {
            return;
        }
        int max = Math.min(3, list.size());
        sb.append("<details><summary class='muted'>candidate queries (top ").append(max).append(")</summary>");
        sb.append("<ol>");
        int i = 0;
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            String q = SafeRedactor.safeMessage(String.valueOf(o), 180);
            sb.append("<li><code>").append(escape(q)).append("</code></li>");
            i++;
            if (i >= max) {
                break;
            }
        }
        sb.append("</ol>");
        sb.append("</details>");
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return n.longValue();
            }
            TraceStore.put("traceHtml.webFailSoftRisk.suppressed.toLong", true);
            TraceStore.put("traceHtml.webFailSoftRisk.suppressed.toLong.errorType", "invalid_number");
            return 0L;
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            TraceStore.put("traceHtml.webFailSoftRisk.suppressed.toLong", true);
            TraceStore.put("traceHtml.webFailSoftRisk.suppressed.toLong.errorType", "invalid_number");
            return 0L;
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static String getString(Map<String, Object> meta, String key) {
        Object v = meta == null ? null : meta.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank() && !"null".equalsIgnoreCase(s.trim());
    }

    private static String escape(String s) {
        return (s == null ? "" : s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
