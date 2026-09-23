package com.example.lms.transform;

import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class QueryTransformerNeedlePlanner {

    private static final Logger log = LoggerFactory.getLogger(QueryTransformerNeedlePlanner.class);

    private QueryTransformerNeedlePlanner() {
    }

    static List<String> suggestAuthoritySites(
            String userPrompt,
            QueryDomain domain,
            Locale locale,
            int maxSites,
            QueryKeywordPromptBuilder promptBuilder,
            Function<String, String> llmRunner) {
        if (userPrompt == null || userPrompt.isBlank() || maxSites <= 0) {
            return List.of();
        }
        String dom = (domain == null) ? "GENERAL" : domain.name();
        String lang = (locale == null || locale.getLanguage() == null) ? "" : locale.getLanguage();

        String authoritySitesPrompt = promptBuilder.buildAuthoritySitesPrompt(
                maxSites,
                lang,
                dom,
                userPrompt.trim());

        String raw = llmRunner.apply(authoritySitesPrompt);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> lines = parseLines(raw);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String l : lines) {
            String n = normalizeDomain(l);
            if (n == null || n.isBlank()) {
                continue;
            }
            if (seen.add(n)) {
                out.add(n);
                if (out.size() >= maxSites) {
                    break;
                }
            }
        }
        putHashedListTrace("probe.needle.llm.site", out);
        return out;
    }

    static List<String> generateNeedleProbeQueries(
            String userPrompt,
            QueryDomain domain,
            EvidenceSignals signals,
            List<String> siteHints,
            List<String> alreadyPlanned,
            Locale locale,
            int maxQueries,
            QueryKeywordPromptBuilder promptBuilder,
            Function<String, String> llmRunner,
            Function<String, String> cleaner) {
        if (userPrompt == null || userPrompt.isBlank() || maxQueries <= 0) {
            return List.of();
        }

        String dom = (domain == null) ? "GENERAL" : domain.name();
        String lang = (locale == null || locale.getLanguage() == null) ? "" : locale.getLanguage();

        List<String> sites = (siteHints == null) ? List.of()
                : siteHints.stream().map(QueryTransformerNeedlePlanner::normalizeDomain)
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .limit(8)
                        .toList();

        String sitesBlock = sites.isEmpty() ? "(none)" : String.join("\n", sites);
        String plannedBlock = (alreadyPlanned == null || alreadyPlanned.isEmpty()) ? "(none)"
                : String.join("\n", alreadyPlanned);

        EvidenceSignals sig = (signals != null) ? signals : EvidenceSignals.empty();

        String needleProbeQueriesPrompt = promptBuilder.buildNeedleProbeQueriesPrompt(
                maxQueries,
                sitesBlock,
                plannedBlock,
                lang,
                dom,
                sig.docCount(),
                sig.authorityAvg(),
                sig.coverageScore(),
                sig.duplicateRatio(),
                userPrompt.trim());

        String raw = llmRunner.apply(needleProbeQueriesPrompt);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<String> lines = parseLines(raw);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String l : lines) {
            String q = cleaner.apply(l);
            if (q == null || q.isBlank()) {
                continue;
            }

            if (!q.contains("site:") && !sites.isEmpty()) {
                q = (q + " site:" + sites.get(0)).trim();
            }

            if (!sites.isEmpty() && q.contains("site:")) {
                String chosen = extractSiteToken(q);
                if (chosen != null && !chosen.isBlank()) {
                    String normChosen = normalizeDomain(chosen);
                    if (normChosen != null && !sites.contains(normChosen)) {
                        q = q.replace("site:" + chosen, "site:" + sites.get(0));
                    }
                }
            }

            q = enforceMaxWords(q, 10);
            String norm = q.replaceAll("\\s+", " ").trim();
            if (norm.isBlank() || !seen.add(norm)) {
                continue;
            }
            out.add(norm);
            if (out.size() >= maxQueries) {
                break;
            }
        }

        putHashedListTrace("probe.needle.llm.query", out);
        return out;
    }

    private static void putHashedListTrace(String keyPrefix, List<String> values) {
        try {
            int count = values == null ? 0 : values.size();
            TraceStore.put(keyPrefix + "Count", count);
            TraceStore.put(keyPrefix + "Hashes", hashList(values));
        } catch (Throwable ignore) {
            traceSuppressed("hashedListTrace", ignore);
            log.debug("[QueryTransformerNeedlePlanner] suppressed stage={}",
                    SafeRedactor.traceLabelOrFallback("hashedListTrace", "unknown"));
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("qtx.needle.suppressed.stage", safeStage);
        TraceStore.put("qtx.needle.suppressed.errorType", safeErrorType);
        TraceStore.put("qtx.needle.suppressed." + safeStage, true);
        TraceStore.put("qtx.needle.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static List<String> hashList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String hash = SafeRedactor.hash12(value);
            if (hash != null && !hash.isBlank()) {
                out.add(hash);
            }
            if (out.size() >= 16) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<String> parseLines(String raw) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\r?\\n"))
                .map(s -> s == null ? "" : s.trim())
                .map(s -> s.replaceFirst("^[\\-*\\d\\.)]+\\s*", "").trim())
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static String normalizeDomain(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isBlank()) {
            return null;
        }
        t = t.replaceFirst("^https?://", "");
        if (t.startsWith("www.")) {
            t = t.substring(4);
        }
        int slash = t.indexOf('/');
        if (slash > 0) {
            t = t.substring(0, slash);
        }
        int q = t.indexOf('?');
        if (q > 0) {
            t = t.substring(0, q);
        }
        t = t.replaceAll("[^a-zA-Z0-9.-]", "");
        if (t.isBlank() || !t.contains(".")) {
            return null;
        }
        return t.toLowerCase(Locale.ROOT);
    }

    private static String extractSiteToken(String q) {
        if (q == null) {
            return null;
        }
        int idx = q.indexOf("site:");
        if (idx < 0) {
            return null;
        }
        String rest = q.substring(idx + "site:".length()).trim();
        if (rest.isBlank()) {
            return null;
        }
        String[] parts = rest.split("\\s+");
        return parts.length > 0 ? parts[0].trim() : null;
    }

    private static String enforceMaxWords(String s, int maxWords) {
        if (s == null) {
            return "";
        }
        String[] parts = s.trim().split("\\s+");
        if (parts.length <= maxWords) {
            return s.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < maxWords; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString().trim();
    }
}
