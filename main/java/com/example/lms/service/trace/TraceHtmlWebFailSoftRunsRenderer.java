package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TraceHtmlWebFailSoftRunsRenderer {

    private static final Pattern NOFILTER_SAFE_COUNT = Pattern.compile("NOFILTER_SAFE=([0-9]+)");

    private TraceHtmlWebFailSoftRunsRenderer() {
    }

    static void append(StringBuilder sb, Map<String, Object> extraMeta, Set<String> shown) {
        Object runsObj = extraMeta.get("web.failsoft.runs");
        if (!(runsObj instanceof List<?> list)) {
            return;
        }

        List<Map<String, Object>> runs = coerceRuns(list);
        if (runs.isEmpty()) {
            return;
        }

        int outZeroCount = 0;
        int fallbackCount = 0;
        for (Map<String, Object> run : runs) {
            Long out = toLong(run.get("outCount"));
            if (out != null && out == 0) {
                outZeroCount++;
            }
            Object fallback = run.get("starvationFallback");
            if (fallback != null && !String.valueOf(fallback).isBlank()) {
                fallbackCount++;
            }
        }

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Web FailSoft Runs</th></tr>");
        sb.append("<tr><th>web.failsoft.runs</th><td>");
        appendRunsDetails(sb, runs, outZeroCount, fallbackCount);
        sb.append("</td></tr>");

        shown.add("web.failsoft.runs");
    }

    private static List<Map<String, Object>> coerceRuns(List<?> list) {
        List<Map<String, Object>> runs = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> coerced = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    if (key != null) {
                        coerced.put(String.valueOf(key), entry.getValue());
                    }
                }
                runs.add(coerced);
            }
        }
        return runs;
    }

    private static void appendRunsDetails(
            StringBuilder sb,
            List<Map<String, Object>> runs,
            int outZeroCount,
            int fallbackCount) {
        sb.append("<details class='trace-failsoft-runs'");
        if (outZeroCount > 0 || fallbackCount > 0 || runs.size() > 1) {
            sb.append(" open");
        }
        sb.append(">");

        sb.append("<summary>");
        sb.append("runs=").append(runs.size());
        if (fallbackCount > 0) {
            sb.append(" fallback=").append(fallbackCount);
        }
        if (outZeroCount > 0) {
            sb.append(" outZero=").append(outZeroCount);
        }
        sb.append("</summary>");

        sb.append("<table class='trace-await-table'>");
        sb.append("<thead><tr>");
        sb.append(
                "<th>#</th><th>runId</th><th>executedQuery</th><th>outCount</th><th>officialOnly</th><th>minCitations</th><th>clamped</th><th>fallback</th><th>stageCountsSelected</th><th>opts</th><th>candidates</th>");
        sb.append("</tr></thead>");
        sb.append("<tbody>");

        int idx = 0;
        for (Map<String, Object> run : runs) {
            idx++;
            appendRunRow(sb, idx, run);
        }

        sb.append("</tbody></table>");
        sb.append("</details>");
    }

    private static void appendRunRow(StringBuilder sb, int idx, Map<String, Object> run) {
        String runId = safeValueOrDefault(run.get("runId"), "");
        String query = safeValueOrDefault(run.get("executedQuery"), safeValueOrDefault(run.get("canonicalQuery"), ""));
        query = SafeRedactor.safeMessage(query, 120);
        String outCount = safeValueOrDefault(run.get("outCount"), "");
        String officialOnly = safeValueOrDefault(run.get("officialOnly"), "");
        String minCitations = safeValueOrDefault(run.get("minCitations"), "");
        String clamped = safeValueOrDefault(run.get("stageOrderClamped"), "");
        String fallback = safeValueOrDefault(run.get("starvationFallback"), "");
        String stageSelected = safeValueOrDefault(run.get("stageCountsSelected"), "");
        String opts = buildOptions(run);

        sb.append("<tr>");
        sb.append("<td>").append(idx).append("</td>");
        sb.append("<td>").append(escape(runId)).append("</td>");
        sb.append("<td><code>").append(escape(query)).append("</code></td>");
        sb.append("<td>").append(escape(outCount)).append("</td>");
        sb.append("<td>").append(escape(officialOnly)).append("</td>");
        sb.append("<td>").append(escape(minCitations)).append("</td>");
        sb.append("<td>").append(escape(clamped)).append("</td>");
        sb.append("<td><code>").append(escape(fallback)).append("</code></td>");
        sb.append("<td><code>").append(escape(stageSelected)).append("</code></td>");
        sb.append("<td><code>").append(escape(opts)).append("</code></td>");

        sb.append("<td>");
        Object candidatesObj = run.get("candidates");
        if (candidatesObj instanceof List<?> candidateList && !candidateList.isEmpty()) {
            appendCandidates(sb, candidateList);
        }
        sb.append("</td>");
        sb.append("</tr>");
    }

    private static String buildOptions(Map<String, Object> run) {
        String incDev = safeValueOrDefault(run.get("officialOnlyClampIncludeDevCommunity"), "");
        String fallbackEnabled = safeValueOrDefault(run.get("starvationFallbackEnabled"), "");
        String fallbackTrigger = safeValueOrDefault(run.get("starvationFallbackTrigger"), "");
        String fallbackMax = safeValueOrDefault(run.get("starvationFallbackMax"), "");
        String fallbackIntentAllowed = safeValueOrDefault(run.get("starvationFallbackIntentAllowed"), "");
        String runKind = runKind(run);
        String citeablePolicy = safeValueOrDefault(run.get("minCitationsCiteablePolicyEffective"),
                safeValueOrDefault(run.get("minCitationsCiteablePolicy"), ""));
        String devBoosted = safeValueOrDefault(run.get("minCitationsDevCommunityBoostedCount"), "");
        String nofilterSafeRatio = nofilterSafeRatio(run);
        String poolSafeEmpty = safeValueOrDefault(run.get("poolSafeEmpty"),
                safeValueOrDefault(run.get("starvationFallback.poolSafeEmpty"), ""));
        String cacheMerged = safeValueOrDefault(run.get("cacheOnly.merged.count"), "");
        String tracePool = safeValueOrDefault(run.get("tracePool.size"), "");
        String rescueMerge = safeValueOrDefault(run.get("rescueMerge.used"), "");
        String vectorFallback = safeValueOrDefault(run.get("vectorFallback.used"), "") + "/"
                + safeValueOrDefault(run.get("vectorFallback.reason"), "") + "/"
                + safeValueOrDefault(run.get("vectorFallback.effectiveTopK"), "");

        return "incDevCommunity=" + incDev + ", fbEnabled=" + fallbackEnabled + ", trigger=" + fallbackTrigger
                + ", max=" + fallbackMax + ", intentAllowed=" + fallbackIntentAllowed
                + ", kind=" + runKind
                + ", citeablePolicy=" + citeablePolicy
                + ", devBoosted=" + devBoosted
                + ", nofilterSafeRatio=" + nofilterSafeRatio
                + ", poolSafeEmpty=" + poolSafeEmpty
                + ", cacheMerged=" + cacheMerged
                + ", tracePool=" + tracePool
                + ", rescueMerge=" + rescueMerge
                + ", vectorFallback=" + vectorFallback;
    }

    private static String runKind(Map<String, Object> run) {
        String executedFull = safeValueOrDefault(run.get("executedQuery"), safeValueOrDefault(run.get("canonicalQuery"), ""));
        String canonicalFull = safeValueOrDefault(run.get("canonicalQuery"), "");
        return !canonicalFull.isBlank() && !executedFull.isBlank() && !executedFull.equals(canonicalFull)
                ? "extraSearch"
                : "canonical";
    }

    private static String nofilterSafeRatio(Map<String, Object> run) {
        long nofilterSafeCount = nofilterSafeCount(run);
        Long outCount = toLong(run.get("outCount"));
        long outN = outCount == null ? 0L : outCount;
        if (outN <= 0L) {
            return "0.00";
        }
        double ratio = (double) nofilterSafeCount / (double) outN;
        return String.format(Locale.ROOT, "%.2f", ratio);
    }

    private static long nofilterSafeCount(Map<String, Object> run) {
        Object stageCountsObj = run.get("stageCountsSelectedFromOut");
        if (stageCountsObj instanceof Map<?, ?> stageCounts) {
            Long value = toLong(stageCounts.get("NOFILTER_SAFE"));
            return value == null ? 0L : value;
        }
        String stageCounts = safeValueOrDefault(run.get("stageCountsSelected"), "");
        Matcher matcher = NOFILTER_SAFE_COUNT.matcher(stageCounts);
        if (!matcher.find()) {
            return 0L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            TraceStore.put("traceHtml.webFailSoftRuns.suppressed.nofilterSafeCount", true);
            TraceStore.put("traceHtml.webFailSoftRuns.suppressed.nofilterSafeCount.errorType", "invalid_number");
            return 0L;
        }
    }

    private static void appendCandidates(StringBuilder sb, List<?> candidateList) {
        sb.append("<details><summary>");
        sb.append("cands=").append(candidateList.size());
        sb.append("</summary>");

        sb.append("<table class='trace-await-table'>");
        sb.append("<thead><tr>");
        sb.append(
                "<th>idx</th><th>stage</th><th>stageFinal</th><th>baseStage</th><th>cred</th><th>score</th><th>tokenHits</th><th>negHits</th><th>selected</th><th>dropReason</th><th>override</th><th>rule</th><th>url</th>");
        sb.append("</tr></thead>");
        sb.append("<tbody>");

        for (Object candidate : candidateList) {
            if (candidate instanceof Map<?, ?> candidateMap) {
                appendCandidateRow(sb, candidateMap);
            }
        }

        sb.append("</tbody></table>");
        sb.append("</details>");
    }

    private static void appendCandidateRow(StringBuilder sb, Map<?, ?> candidate) {
        String idx = safeValueOrDefault(candidate.get("idx"), "");
        String stage = safeValueOrDefault(candidate.get("stage"), "");
        String stageFinal = safeValueOrDefault(candidate.get("stageFinal"), "");
        String baseStage = safeValueOrDefault(candidate.get("baseStage"), "");
        String cred = safeValueOrDefault(candidate.get("cred"), safeValueOrDefault(candidate.get("credibility"), ""));
        String score = safeValueOrDefault(candidate.get("score"), "");
        String tokenHits = safePreview(candidate.get("tokenHits"), 160);
        String negHits = safePreview(candidate.get("negHits"), 160);
        String selected = safePreview(candidate.get("selected"), 160);
        String dropReason = SafeRedactor.traceLabelOrFallback(candidate.get("dropReason"), "");
        String override = safePreview(candidate.get("overridePath"), 160);
        String rule = safePreview(candidate.get("rule"), 160);
        String url = safePreview(candidate.get("url"), 80);

        sb.append("<tr>");
        sb.append("<td>").append(escape(idx)).append("</td>");
        sb.append("<td>").append(escape(stage)).append("</td>");
        sb.append("<td>").append(escape(stageFinal)).append("</td>");
        sb.append("<td>").append(escape(baseStage)).append("</td>");
        sb.append("<td>").append(escape(cred)).append("</td>");
        sb.append("<td>").append(escape(score)).append("</td>");
        sb.append("<td><code>").append(escape(tokenHits)).append("</code></td>");
        sb.append("<td><code>").append(escape(negHits)).append("</code></td>");
        sb.append("<td>").append(escape(selected)).append("</td>");
        sb.append("<td>").append(escape(dropReason)).append("</td>");
        sb.append("<td>").append(escape(override)).append("</td>");
        sb.append("<td><code>").append(escape(rule)).append("</code></td>");
        sb.append("<td><code>").append(escape(url)).append("</code></td>");
        sb.append("</tr>");
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric)) {
                return number.longValue();
            }
            TraceStore.put("traceHtml.webFailSoftRuns.suppressed.toLong", true);
            TraceStore.put("traceHtml.webFailSoftRuns.suppressed.toLong.errorType", "invalid_number");
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            TraceStore.put("traceHtml.webFailSoftRuns.suppressed.toLong", true);
            TraceStore.put("traceHtml.webFailSoftRuns.suppressed.toLong.errorType", "invalid_number");
            return null;
        }
    }

    private static String safeValueOrDefault(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return defaultValue;
        }
        return SafeRedactor.safeMessage(s, 800);
    }

    private static String safePreview(Object value, int maxLen) {
        String raw = safeValueOrDefault(value, "");
        if (raw.isBlank()) {
            return "";
        }
        return SafeRedactor.safeMessage(raw, maxLen);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
