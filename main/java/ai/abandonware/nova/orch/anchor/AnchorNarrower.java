package ai.abandonware.nova.orch.anchor;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Cheap, deterministic anchor picker for compression/ExtremeZ fallback paths.
 *
 * <p>The public API intentionally stays tiny because this class is used from
 * AOP and fail-soft retrieval code. It avoids LLM calls, keeps variants bounded,
 * and records only hashed trace fields.
 */
public class AnchorNarrower {
    private static final Logger log = LoggerFactory.getLogger(AnchorNarrower.class);

    private static final Pattern TOKEN_PAT = Pattern.compile("[\\uAC00-\\uD7A3A-Za-z0-9._-]+");

    private static final Set<String> STOP_TOKENS = Set.of(
            "please", "help", "search", "find", "explain", "info", "information",
            "\uC54C\uB824\uC918", "\uC54C\uB824\uC8FC\uC138\uC694", "\uAC80\uC0C9",
            "\uCC3E\uC544\uC918", "\uC124\uBA85", "\uC815\uBCF4", "\uBB50\uC57C",
            "\uBB34\uC5C7", "\uBC29\uBC95", "\uCD94\uCC9C", "\uC8FC\uC138\uC694"
    );

    public record Anchor(String term, double confidence) {
    }

    public Anchor pick(String query, List<String> protectedTerms, List<String> history) {
        if (!isEmpty(protectedTerms)) {
            String best = protectedTerms.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .max(Comparator.comparingInt(String::length))
                    .orElse(null);
            if (best != null) {
                return tracePick(query, new Anchor(best, 0.95d), "protected_terms");
            }
        }

        String q = safe(query).trim();
        List<String> tokens = tokens(q);

        List<String> prefix = new ArrayList<>();
        for (String token : tokens) {
            String norm = normalize(token);
            if (norm.length() < 2) {
                continue;
            }
            if (isStopLike(norm)) {
                break;
            }
            prefix.add(token);
            if (prefix.size() >= 3) {
                break;
            }
        }
        if (!prefix.isEmpty()) {
            String phrase = String.join(" ", prefix).trim();
            return tracePick(q, new Anchor(phrase, prefix.size() >= 2 ? 0.75d : 0.65d), "prefix_phrase");
        }

        String bestToken = tokens.stream()
                .filter(t -> !isStopLike(normalize(t)))
                .max(Comparator.comparingInt(String::length))
                .orElse("");
        if (!bestToken.isBlank()) {
            return tracePick(q, new Anchor(bestToken, 0.60d), "fallback_token");
        }

        String fallback = q.length() > 28 ? q.substring(0, 28) : q;
        return tracePick(q, new Anchor(fallback, 0.30d), "last_resort");
    }

    public List<String> cheapVariants(String query, Anchor anchor) {
        String q = safe(query).trim();
        if (q.isBlank()) {
            return Collections.emptyList();
        }
        String a = anchor != null ? safe(anchor.term()).trim() : "";

        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(q);

        if (!a.isBlank() && !isStopLike(normalize(a))) {
            String qLower = q.toLowerCase(Locale.ROOT);
            if (containsAny(qLower, "\uC8FC\uC18C", "\uC18C\uC7AC\uC9C0", "\uC704\uCE58",
                    "\uC5B4\uB514", "\uADFC\uCC98", "address", "location")) {
                out.add(a + " \uBCF8\uC0AC \uC8FC\uC18C");
                out.add(a + " \uC18C\uC7AC\uC9C0");
            } else {
                out.add(a);
                if (containsAny(qLower, "\uC131\uB2A5", "performance", "benchmark")) {
                    out.add(a + " \uC131\uB2A5");
                } else if (containsAny(qLower, "\uC0AC\uC591", "\uC2A4\uD399", "\uAC00\uACA9", "spec", "price")) {
                    out.add(a + " \uC0AC\uC591");
                } else {
                    out.add(a + " \uC815\uBCF4");
                }
            }
        }

        List<String> result = new ArrayList<>(out);
        if (result.size() > 3) {
            result = new ArrayList<>(result.subList(0, 3));
        }
        traceVariants(q, a, result);
        return result;
    }

    public AnchorNarrowingResult narrow(String query, List<String> candidates, int maxAnchors, double maxDriftScore) {
        String q = safe(query).trim();
        int anchorLimit = Math.max(1, Math.min(3, maxAnchors));
        List<String> anchors = selectAnchors(q, anchorLimit);
        double confidence = anchorConfidence(q, anchors);
        int accepted = 0;
        int rejected = 0;
        double driftSum = 0.0d;
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                double drift = candidateDrift(q, candidate, anchors);
                driftSum += drift;
                if (drift <= clamp01(maxDriftScore)) {
                    accepted++;
                } else {
                    rejected++;
                }
            }
        }
        int total = accepted + rejected;
        double driftScore = total == 0 ? 0.0d : driftSum / total;
        String reason = anchors.isEmpty()
                ? "anchor_empty"
                : rejected > 0 ? "anchor_drift_filtered" : "anchor_narrowed";
        AnchorNarrowingResult result = new AnchorNarrowingResult(
                anchors,
                round4(confidence),
                round4(driftScore),
                accepted,
                rejected,
                reason);
        traceNarrowing(result);
        return result;
    }

    public List<String> filterCandidates(String query,
                                         List<String> candidates,
                                         AnchorNarrowingResult result,
                                         double maxDriftScore) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> safeCandidates = candidates.stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .toList();
        if (safeCandidates.isEmpty()) {
            return List.of();
        }
        AnchorNarrowingResult effective = result == null
                ? narrow(query, safeCandidates, 3, maxDriftScore)
                : result;
        if (effective.anchors().isEmpty()) {
            return List.copyOf(safeCandidates);
        }
        List<String> out = new ArrayList<>();
        for (String candidate : safeCandidates) {
            if (candidateDrift(query, candidate, effective.anchors()) <= clamp01(maxDriftScore)) {
                out.add(candidate);
            }
        }
        try {
            if (out.isEmpty()) {
                TraceStore.put("rag.anchor.filterFallback", "preserve_seed");
            }
            TraceStore.put("rag.anchor.filteredCandidateCount", out.isEmpty() ? safeCandidates.size() : out.size());
            TraceStore.append("orch.events.v1", Map.of(
                    "type", "BURST_CLAMPED",
                    "accepted", out.isEmpty() ? safeCandidates.size() : out.size(),
                    "rejected", out.isEmpty() ? 0 : Math.max(0, safeCandidates.size() - out.size())));
        } catch (Throwable traceError) {
            traceSuppressed("filterCandidates", traceError);
        }
        return out.isEmpty() ? List.copyOf(safeCandidates) : out;
    }

    private static List<String> tokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN_PAT.matcher(query);
        while (m.find()) {
            String token = m.group();
            if (token != null && !token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }

    private static List<String> selectAnchors(String query, int maxAnchors) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        boolean queryHasKorean = query.matches(".*[\\uAC00-\\uD7A3].*");
        List<String> toks = tokens(query);
        List<String> selected = new ArrayList<>();
        for (String token : toks.stream()
                .filter(t -> !isStopLike(normalize(t)))
                .sorted(Comparator.comparingDouble((String t) -> tokenPriority(t, queryHasKorean)).reversed()
                        .thenComparing(Comparator.comparingInt(String::length).reversed()))
                .toList()) {
            String normalized = normalize(token);
            if (normalized.length() < 2 || selected.stream().anyMatch(s -> normalize(s).equals(normalized))) {
                continue;
            }
            selected.add(token);
            if (selected.size() >= maxAnchors) {
                break;
            }
        }
        if (selected.isEmpty()) {
            Anchor fallback = new AnchorNarrower().pick(query, List.of(), List.of());
            if (fallback != null && fallback.term() != null && !fallback.term().isBlank()) {
                selected.add(fallback.term());
            }
        }
        return selected;
    }

    private static double tokenPriority(String token) {
        return tokenPriority(token, false);
    }

    private static double tokenPriority(String token, boolean queryHasKorean) {
        String t = safe(token);
        double score = 0.0d;
        boolean hasKorean = t.matches(".*[\\uAC00-\\uD7A3].*");
        boolean hasAcronym = t.matches(".*[A-Z]{2,}.*");
        if (hasAcronym) {
            score += queryHasKorean && !hasKorean ? 0.75d : 3.0d;
        }
        if (t.matches(".*[._:-].*")) {
            score += 2.0d;
        }
        if (hasKorean) {
            score += queryHasKorean ? 3.0d : 1.5d;
        }
        if (t.matches(".*[A-Za-z].*")) {
            score += queryHasKorean && !hasKorean ? 0.25d : 1.0d;
        }
        score += Math.min(2.0d, t.length() / 12.0d);
        return score;
    }

    private static double anchorConfidence(String query, List<String> anchors) {
        if (query == null || query.isBlank() || anchors == null || anchors.isEmpty()) {
            return 0.0d;
        }
        double coverage = Math.min(1.0d, anchors.size() / 3.0d);
        double substance = anchors.stream()
                .mapToDouble(a -> Math.min(1.0d, safe(a).length() / 10.0d))
                .average()
                .orElse(0.0d);
        return clamp01((0.55d * coverage) + (0.45d * substance));
    }

    private static double candidateDrift(String query, String candidate, List<String> anchors) {
        String c = normalize(candidate);
        if (c.isBlank()) {
            return 1.0d;
        }
        int anchorHits = 0;
        if (anchors != null) {
            for (String anchor : anchors) {
                String a = normalize(anchor);
                if (!a.isBlank() && c.contains(a)) {
                    anchorHits++;
                }
            }
        }
        if (anchorHits > 0) {
            return round4(1.0d - (anchorHits / (double) Math.max(1, anchors == null ? 0 : anchors.size())));
        }
        Set<String> queryTokens = normalizedTokenSet(query);
        Set<String> candidateTokens = normalizedTokenSet(candidate);
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 1.0d;
        }
        int intersection = 0;
        for (String token : candidateTokens) {
            if (queryTokens.contains(token)) {
                intersection++;
            }
        }
        int union = new LinkedHashSet<String>() {{
            addAll(queryTokens);
            addAll(candidateTokens);
        }}.size();
        double similarity = union == 0 ? 0.0d : intersection / (double) union;
        return round4(1.0d - similarity);
    }

    private static Set<String> normalizedTokenSet(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : tokens(value)) {
            String normalized = normalize(token);
            if (normalized.length() >= 2 && !isStopLike(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStopLike(String normalizedToken) {
        if (normalizedToken == null) {
            return true;
        }
        String t = normalizedToken.trim();
        if (t.isBlank()) {
            return true;
        }
        return STOP_TOKENS.contains(t);
    }

    private static boolean isEmpty(List<String> xs) {
        return xs == null || xs.isEmpty();
    }

    private static String normalize(String s) {
        return safe(s).trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static Anchor tracePick(String query, Anchor anchor, String source) {
        try {
            String term = anchor == null || anchor.term() == null ? "" : anchor.term();
            TraceStore.put("anchor.pick.source", source);
            TraceStore.put("anchor.pick.queryHash", safeHash(query));
            TraceStore.put("anchor.pick.termHash", safeHash(term));
            TraceStore.put("anchor.pick.termLen", term.length());
            TraceStore.put("anchor.pick.confidence", anchor == null ? 0.0d : anchor.confidence());
        } catch (Throwable traceError) {
            traceSuppressed("tracePick", traceError);
        }
        return anchor;
    }

    private static void traceVariants(String query, String anchor, List<String> variants) {
        try {
            TraceStore.put("anchor.variants.queryHash", safeHash(query));
            TraceStore.put("anchor.variants.anchorHash", safeHash(anchor));
            TraceStore.put("anchor.variants.anchorLen", safe(anchor).length());
            TraceStore.put("anchor.variants.count", variants == null ? 0 : variants.size());
        } catch (Throwable traceError) {
            traceSuppressed("traceVariants", traceError);
        }
    }

    private static void traceNarrowing(AnchorNarrowingResult result) {
        try {
            TraceStore.put("rag.anchor.enabled", true);
            TraceStore.put("rag.anchor.anchors", safeAnchorSummaries(result.anchors()));
            TraceStore.put("rag.anchor.confidence", result.anchorConfidence());
            TraceStore.put("rag.anchor.driftScore", result.driftScore());
            TraceStore.put("rag.anchor.acceptedCandidateCount", result.acceptedCandidateCount());
            TraceStore.put("rag.anchor.rejectedCandidateCount", result.rejectedCandidateCount());
            TraceStore.put("rag.anchor.reason", SafeRedactor.traceLabelOrFallback(result.reason(), ""));
            TraceStore.append("orch.events.v1", Map.of(
                    "type", "ANCHOR_NARROWED",
                    "anchorCount", result.anchors().size(),
                    "confidence", result.anchorConfidence(),
                    "driftScore", result.driftScore()));
        } catch (Throwable traceError) {
            traceSuppressed("traceNarrowing", traceError);
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        log.debug("[AnchorNarrower] trace skipped stage={} errorType={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                error == null ? "unknown" : error.getClass().getSimpleName());
    }

    private static List<Map<String, Object>> safeAnchorSummaries(List<String> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String anchor : anchors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hash12", safeHash(anchor));
            row.put("len", safe(anchor).length());
            out.add(row);
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static String safeHash(String raw) {
        String hash = SafeRedactor.hash12(raw);
        return hash == null ? "" : hash;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double round4(double value) {
        return Math.round(clamp01(value) * 10000.0d) / 10000.0d;
    }
}
