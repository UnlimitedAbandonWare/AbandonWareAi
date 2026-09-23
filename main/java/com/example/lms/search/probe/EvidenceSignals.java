package com.example.lms.search.probe;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.MetadataUtils;
import dev.langchain4j.rag.content.Content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Evidence quality signals for retrieval evaluation, Self-Ask lane gating, and
 * needle probe triggering.
 */
public record EvidenceSignals(
        int docCount,
        double authorityAvg,
        double coverageScore,
        double duplicateRatio) {

    public record LaneEvidenceSignals(
            String lane,
            String role,
            int docCount,
            double evidenceRate,
            double strongCitationRate,
            int distinctCitationCount,
            double duplicateRate,
            double contradictionRate,
            double authorityAvg,
            double crossLaneSupportRate,
            double confidence,
            double grandasReadiness,
            double tailSignal) {
    }

    public static EvidenceSignals empty() {
        return new EvidenceSignals(0, 0.0, 0.0, 1.0);
    }

    public static EvidenceSignals compute(String query, List<Content> contents, AuthorityScorer authorityScorer) {
        if (contents == null || contents.isEmpty()) {
            return empty();
        }

        int docCount = contents.size();
        Set<String> domains = new HashSet<>();
        double authoritySum = 0.0;
        int authorityCount = 0;

        for (Content c : contents) {
            if (c == null || c.textSegment() == null) {
                continue;
            }
            try {
                var meta = c.textSegment().metadata();
                if (meta != null) {
                    String url = meta.getString("url");
                    if (url == null || url.isBlank()) {
                        url = meta.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        String domain = extractDomain(url);
                        if (domain != null) {
                            domains.add(domain);
                        }
                        if (authorityScorer != null) {
                            try {
                                authoritySum += authorityScorer.weightFor(url);
                                authorityCount++;
                            } catch (Exception ignore) {
                                traceSuppressed("authority.weightFor", ignore);
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
                traceSuppressed("compute.metadata", ignore);
            }
        }

        double authorityAvg = (authorityCount > 0) ? (authoritySum / authorityCount) : 0.0;
        double duplicateRatio = domains.isEmpty() ? 1.0 : (1.0 - ((double) domains.size() / docCount));

        int nonEmpty = 0;
        for (Content c : contents) {
            if (c != null && c.textSegment() != null) {
                String text = c.textSegment().text();
                if (text != null && !text.isBlank()) {
                    nonEmpty++;
                }
            }
        }
        double coverageScore = (docCount > 0) ? ((double) nonEmpty / docCount) : 0.0;

        return new EvidenceSignals(docCount, authorityAvg, coverageScore, duplicateRatio);
    }

    public static Map<String, LaneEvidenceSignals> computeByLane(
            List<Content> contents,
            AuthorityScorer authorityScorer,
            ContradictionScorer contradictionScorer,
            int maxContradictionPairs) {
        return computeByLane(contents, List.of(), authorityScorer, contradictionScorer, maxContradictionPairs);
    }

    public static Map<String, LaneEvidenceSignals> computeByLane(
            List<Content> contents,
            List<Content> supportContents,
            AuthorityScorer authorityScorer,
            ContradictionScorer contradictionScorer,
            int maxContradictionPairs) {
        if (contents == null || contents.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Content>> byLane = new LinkedHashMap<>();
        for (Content content : contents) {
            String lane = laneOf(content);
            byLane.computeIfAbsent(lane, ignored -> new ArrayList<>()).add(content);
        }
        Map<String, LaneEvidenceSignals> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Content>> entry : byLane.entrySet()) {
            List<Content> support = new ArrayList<>();
            if (supportContents != null) {
                support.addAll(supportContents);
            }
            for (Map.Entry<String, List<Content>> other : byLane.entrySet()) {
                if (!Objects.equals(entry.getKey(), other.getKey()) && other.getValue() != null) {
                    support.addAll(other.getValue());
                }
            }
            out.put(entry.getKey(), computeLaneInternal(entry.getKey(), entry.getValue(), support, true,
                    authorityScorer, contradictionScorer, maxContradictionPairs));
        }
        return out;
    }

    public static LaneEvidenceSignals computeLane(
            String lane,
            List<Content> contents,
            AuthorityScorer authorityScorer,
            ContradictionScorer contradictionScorer,
            int maxContradictionPairs) {
        return computeLaneInternal(lane, contents, List.of(), false, authorityScorer, contradictionScorer,
                maxContradictionPairs);
    }

    public static LaneEvidenceSignals computeLane(
            String lane,
            List<Content> contents,
            List<Content> supportContents,
            AuthorityScorer authorityScorer,
            ContradictionScorer contradictionScorer,
            int maxContradictionPairs) {
        return computeLaneInternal(lane, contents, supportContents, true, authorityScorer, contradictionScorer,
                maxContradictionPairs);
    }

    private static LaneEvidenceSignals computeLaneInternal(
            String lane,
            List<Content> contents,
            List<Content> supportContents,
            boolean emptySupportAsZero,
            AuthorityScorer authorityScorer,
            ContradictionScorer contradictionScorer,
            int maxContradictionPairs) {
        String safeLane = (lane == null || lane.isBlank()) ? "unknown" : lane.trim();
        if (contents == null || contents.isEmpty()) {
            return new LaneEvidenceSignals(safeLane, laneRole(safeLane), 0, 0.0d, 0.0d, 0,
                    1.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }

        int docCount = contents.size();
        int evidenceCount = 0;
        int strongCitationCount = 0;
        double authoritySum = 0.0d;
        int authorityCount = 0;
        Set<String> uniqueEvidence = new LinkedHashSet<>();
        Set<String> distinctStrongCitations = new LinkedHashSet<>();
        List<String> texts = new ArrayList<>();
        List<Double> scoreSignals = new ArrayList<>();

        for (int i = 0; i < contents.size(); i++) {
            Content content = contents.get(i);
            if (content == null || content.textSegment() == null) {
                continue;
            }
            String text = Optional.ofNullable(content.textSegment().text()).orElse("").trim();
            if (!text.isBlank()) {
                texts.add(text);
            }
            Map<String, Object> meta = MetadataUtils.toMap(content.textSegment().metadata());
            String source = firstNonBlank(meta, "url", "uri", "source_url", "source", "provider", "doc_id", "docId");
            String strongSource = strongCitationSource(meta, text);
            String strongKey = canonicalStrongCitationKey(strongSource, text);
            boolean hasEvidence = source != null && !source.isBlank();
            if (!hasEvidence && hasCitationMarker(text)) {
                hasEvidence = true;
                source = "text-citation:" + hash12(text);
            }
            if (strongKey != null && !strongKey.isBlank()) {
                strongCitationCount++;
                distinctStrongCitations.add(strongKey);
            }
            if (hasEvidence) {
                evidenceCount++;
                uniqueEvidence.add(strongKey != null && !strongKey.isBlank()
                        ? strongKey
                        : canonicalEvidenceKey(source, text, i));
            } else if (!text.isBlank()) {
                uniqueEvidence.add("text:" + hash12(text));
            }
            String authoritySource = (strongSource != null && !strongSource.isBlank()) ? strongSource : source;
            if (authoritySource != null && !authoritySource.isBlank() && authorityScorer != null) {
                try {
                    authoritySum += authorityScorer.weightFor(authoritySource);
                    authorityCount++;
                } catch (Exception ignore) {
                    traceSuppressed("lane.authority.weightFor", ignore);
                }
            }
            scoreSignals.add(candidateScore(meta, i));
        }

        double evidenceRate = docCount > 0 ? ((double) evidenceCount / docCount) : 0.0d;
        double strongCitationRate = docCount > 0 ? ((double) strongCitationCount / docCount) : 0.0d;
        double duplicateRate = docCount > 0
                ? clamp01(1.0d - ((double) uniqueEvidence.size() / docCount))
                : 1.0d;
        double contradictionRate = contradictionRate(texts, contradictionScorer, maxContradictionPairs);
        double authorityAvg = authorityCount > 0 ? clamp01(authoritySum / authorityCount) : 0.50d;
        double crossLaneSupportRate = supportRate(contents, supportContents, emptySupportAsZero);
        double distinctNorm = distinctStrongCitations.isEmpty()
                ? 0.0d
                : clamp01((double) distinctStrongCitations.size() / Math.max(1, Math.min(3, docCount)));
        double confidence = clamp01(
                (0.25d * evidenceRate)
                        + (0.25d * strongCitationRate)
                        + (0.15d * (1.0d - duplicateRate))
                        + (0.20d * (1.0d - contradictionRate))
                        + (0.15d * authorityAvg));
        double grandasReadiness = clamp01(
                (0.28d * strongCitationRate)
                        + (0.18d * distinctNorm)
                        + (0.20d * crossLaneSupportRate)
                        + (0.14d * authorityAvg)
                        + (0.10d * (1.0d - duplicateRate))
                        + (0.10d * (1.0d - contradictionRate)));
        double tailSignal = tailSignal(scoreSignals, strongCitationRate, distinctNorm,
                authorityAvg, duplicateRate, contradictionRate, grandasReadiness);

        return new LaneEvidenceSignals(
                safeLane,
                laneRole(safeLane),
                docCount,
                round4(evidenceRate),
                round4(strongCitationRate),
                distinctStrongCitations.size(),
                round4(duplicateRate),
                round4(contradictionRate),
                round4(authorityAvg),
                round4(crossLaneSupportRate),
                round4(confidence),
                round4(grandasReadiness),
                round4(tailSignal));
    }

    public static String laneRole(String lane) {
        if (lane == null) {
            return "unknown";
        }
        return switch (lane.trim()) {
            case "BQ" -> "domain_definition";
            case "ER" -> "alias_synonym";
            case "RC" -> "relation_hypothesis";
            default -> lane.trim().isEmpty() ? "unknown" : lane.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static double candidateScore(Map<String, Object> meta, int index) {
        double explicit = firstDouble(meta,
                "grandas_adjusted_score",
                "grandas_base_score",
                "rrfScore",
                "rrf_score",
                "score",
                "relevance",
                "selfask_lane_gate_grandas_weight");
        if (explicit > 0.0d) {
            return clamp01(explicit);
        }
        return 1.0d / Math.max(1, index + 1);
    }

    private static double tailSignal(
            List<Double> scores,
            double strongCitationRate,
            double distinctNorm,
            double authorityAvg,
            double duplicateRate,
            double contradictionRate,
            double grandasReadiness) {
        double tailShape = upperTailShape(scores);
        double quality = clamp01(
                (0.24d * strongCitationRate)
                        + (0.18d * distinctNorm)
                        + (0.18d * authorityAvg)
                        + (0.12d * grandasReadiness)
                        + (0.14d * (1.0d - duplicateRate))
                        + (0.14d * (1.0d - contradictionRate)));
        return clamp01((0.58d * tailShape) + (0.42d * quality));
    }

    private static double upperTailShape(List<Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return 0.0d;
        }
        List<Double> clean = new ArrayList<>();
        for (Double score : scores) {
            double s = score == null ? 0.0d : score;
            if (!Double.isNaN(s) && !Double.isInfinite(s)) {
                clean.add(clamp01(s));
            }
        }
        if (clean.isEmpty()) {
            return 0.0d;
        }
        if (clean.size() == 1) {
            return clean.get(0) >= 0.70d ? 0.70d : clean.get(0);
        }
        clean.sort(Double::compareTo);
        double min = clean.get(0);
        double max = clean.get(clean.size() - 1);
        double median = percentile(clean, 0.50d);
        double range = Math.max(1.0e-9d, max - min);
        double prominence = clamp01((max - median) / range);
        double topGap = clamp01((max - clean.get(clean.size() - 2)) / range);
        return clamp01((0.70d * prominence) + (0.30d * topGap));
    }

    public static List<String> strongCitationKeys(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Content content : contents) {
            String key = strongCitationKey(content);
            if (key != null && !key.isBlank()) {
                out.add(key);
            }
        }
        return out;
    }

    public static String strongCitationKey(Content content) {
        if (content == null || content.textSegment() == null) {
            return "";
        }
        Map<String, Object> meta = MetadataUtils.toMap(content.textSegment().metadata());
        String text = Optional.ofNullable(content.textSegment().text()).orElse("");
        return canonicalStrongCitationKey(strongCitationSource(meta, text), text);
    }

    private static String laneOf(Content content) {
        try {
            Map<String, Object> meta = MetadataUtils.toMap(content.textSegment().metadata());
            String lane = firstNonBlank(meta, "retrieval_lane", "query_branch");
            return (lane == null || lane.isBlank()) ? "unknown" : lane;
        } catch (Exception ignore) {
            traceSuppressed("laneOf.metadata", ignore);
            return "unknown";
        }
    }

    private static double supportRate(List<Content> contents, List<Content> supportContents, boolean emptySupportAsZero) {
        if (contents == null || contents.isEmpty()) {
            return 0.0d;
        }
        if (supportContents == null || supportContents.isEmpty()) {
            return emptySupportAsZero ? 0.0d : 1.0d;
        }
        Set<String> supportStrongKeys = new LinkedHashSet<>();
        List<Set<String>> supportTokens = new ArrayList<>();
        for (Content support : supportContents) {
            if (support == null || support.textSegment() == null) {
                continue;
            }
            String key = strongCitationKey(support);
            if (key != null && !key.isBlank()) {
                supportStrongKeys.add(key);
            }
            Set<String> tokens = tokens(support.textSegment().text());
            if (!tokens.isEmpty()) {
                supportTokens.add(tokens);
            }
        }
        int supported = 0;
        for (Content content : contents) {
            if (content == null || content.textSegment() == null) {
                continue;
            }
            String key = strongCitationKey(content);
            if (key != null && !key.isBlank() && supportStrongKeys.contains(key)) {
                supported++;
                continue;
            }
            Set<String> left = tokens(content.textSegment().text());
            if (!left.isEmpty() && hasTokenSupport(left, supportTokens)) {
                supported++;
            }
        }
        return clamp01((double) supported / contents.size());
    }

    private static boolean hasTokenSupport(Set<String> left, List<Set<String>> candidates) {
        for (Set<String> right : candidates) {
            if (right == null || right.isEmpty()) {
                continue;
            }
            int overlap = 0;
            for (String token : left) {
                if (right.contains(token)) {
                    overlap++;
                }
            }
            if (overlap >= 3) {
                return true;
            }
            int denom = Math.max(1, Math.min(left.size(), right.size()));
            if (((double) overlap / denom) >= 0.20d && overlap >= 2) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for (String part : parts) {
            if (part != null && part.length() >= 3) {
                out.add(part);
            }
        }
        return out;
    }

    private static double contradictionRate(List<String> texts, ContradictionScorer scorer, int maxPairs) {
        if (texts == null || texts.size() < 2 || scorer == null) {
            return 0.0d;
        }
        int limit = Math.max(1, maxPairs);
        int pairs = 0;
        double sum = 0.0d;
        for (int i = 0; i < texts.size() && pairs < limit; i++) {
            for (int j = i + 1; j < texts.size() && pairs < limit; j++) {
                try {
                    sum += clamp01(scorer.score(texts.get(i), texts.get(j)));
                    pairs++;
                } catch (Exception ignore) {
                    traceSuppressed("contradiction.score", ignore);
                }
            }
        }
        return pairs == 0 ? 0.0d : clamp01(sum / pairs);
    }

    private static String firstNonBlank(Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = meta.get(key);
            if (value != null) {
                String s = String.valueOf(value).trim();
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return "";
    }

    private static boolean hasCitationMarker(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("source:")
                || lower.matches(".*\\[(?:w\\d+|v\\d+|d\\d+|\\d+)].*");
    }

    private static String strongCitationSource(Map<String, Object> meta, String text) {
        String url = firstStrongSource(meta, "url", "uri", "source_url");
        if (!url.isBlank()) {
            return url;
        }
        String docId = firstNonBlank(meta, "doc_id", "docId", "document_id", "documentId");
        if (isStrongDocumentId(docId)) {
            return "doc:" + docId;
        }
        String source = firstNonBlank(meta, "source");
        if (isStrongSourceValue(source)) {
            return source;
        }
        if (hasCitationMarker(text)) {
            return "text-citation:" + hash12(text);
        }
        return "";
    }

    private static String firstStrongSource(Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            String value = firstNonBlank(meta, key);
            if (isStrongSourceValue(value)) {
                return value;
            }
        }
        return "";
    }

    private static String canonicalEvidenceKey(String source, String text, int index) {
        String s = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return "text:" + hash12(text);
        }
        if ("web".equals(s) || s.startsWith("web:") || "naver".equals(s) || "brave".equals(s)
                || "serpapi".equals(s) || "serp_api".equals(s) || "tavily".equals(s)) {
            return s + ":" + hash12(text) + ":" + index;
        }
        String domain = extractDomain(s);
        return (domain != null && !domain.isBlank()) ? domain : s;
    }

    private static String canonicalStrongCitationKey(String source, String text) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String s = source.trim();
        if (s.startsWith("text-citation:")) {
            return s;
        }
        if (s.startsWith("doc:")) {
            return "doc:" + hash12(s.substring(4));
        }
        try {
            java.net.URI uri = java.net.URI.create(s);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                host = host.toLowerCase(Locale.ROOT);
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                String path = Optional.ofNullable(uri.getPath()).orElse("").replaceAll("/{2,}", "/");
                return "url:" + host + path;
            }
        } catch (Exception ignore) {
            traceSuppressed("strongCitation.uri", ignore);
        }
        if (looksLikeDomain(s)) {
            return "url:" + s.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        }
        return "source:" + hash12(s + ":" + text);
    }

    private static boolean isStrongSourceValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String s = value.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("web")
                || lower.equals("naver")
                || lower.equals("brave")
                || lower.equals("serpapi")
                || lower.equals("serp_api")
                || lower.equals("tavily")
                || lower.equals("selfask")
                || lower.equals("vector")
                || lower.equals("rag")
                || lower.equals("memory")
                || lower.equals("kg")
                || lower.equals("provider")
                || lower.equals("web:selfask")
                || lower.startsWith("provider:")) {
            return false;
        }
        if (lower.startsWith("web:") && !lower.contains("http://") && !lower.contains("https://")) {
            return false;
        }
        return lower.contains("http://") || lower.contains("https://") || looksLikeDomain(s);
    }

    private static boolean isStrongDocumentId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return !lower.equals("web")
                && !lower.equals("selfask")
                && !lower.equals("unknown")
                && !lower.equals("provider")
                && !lower.startsWith("web:");
    }

    private static boolean looksLikeDomain(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String s = value.trim().toLowerCase(Locale.ROOT);
        if (s.contains(" ") || s.contains("@")) {
            return false;
        }
        return s.matches("^(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:[:/].*)?$");
    }

    private static String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = java.net.URI.create(url).getHost();
            if (host != null) {
                host = host.toLowerCase(Locale.ROOT);
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                return host;
            }
        } catch (Exception ignore) {
            traceSuppressed("extractDomain.uri", ignore);
        }
        return null;
    }

    private static void traceSuppressed(String stage, Exception ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(ignored);
        TraceStore.put("evidenceSignals.suppressed.stage", safeStage);
        TraceStore.put("evidenceSignals.suppressed.errorType", safeErrorType);
        TraceStore.put("evidenceSignals.suppressed." + safeStage, true);
        TraceStore.put("evidenceSignals.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Exception ignored) {
        if (ignored == null) {
            return "unknown";
        }
        if (ignored instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown");
    }

    private static String hash12(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < bytes.length && sb.length() < 12; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.substring(0, Math.min(12, sb.length()));
        } catch (Exception e) {
            traceSuppressed("hash12.sha256", e);
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    private static double firstDouble(Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return 0.0d;
        }
        for (String key : keys) {
            Object value = meta.get(key);
            if (value instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isFinite(d)) {
                    return d;
                }
            }
            if (value instanceof String s) {
                try {
                    double d = Double.parseDouble(s.trim());
                    if (Double.isFinite(d)) {
                        return d;
                    }
                } catch (NumberFormatException ignore) {
                    traceSuppressed("firstDouble.parse", ignore);
                }
            }
        }
        return 0.0d;
    }

    private static double percentile(List<Double> sortedValues, double q) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0.0d;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double pos = clamp01(q) * (sortedValues.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sortedValues.get(lo);
        }
        double frac = pos - lo;
        return sortedValues.get(lo) + ((sortedValues.get(hi) - sortedValues.get(lo)) * frac);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
