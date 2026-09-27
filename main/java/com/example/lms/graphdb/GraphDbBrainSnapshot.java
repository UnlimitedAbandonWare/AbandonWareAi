package com.example.lms.graphdb;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only read projection for GraphDB manual learning evidence; no BrainState writes, query-time coupling,
 * raw text, raw identifiers, or raw secrets are allowed in this snapshot surface.
 */
public record GraphDbBrainSnapshot(
        String snapshotBoundary,
        String writeBoundary,
        String readBoundary,
        String summaryBoundary,
        String multiHopBoundary,
        String lane,
        String status,
        int candidateCount,
        int communityCount,
        int multiHopCount,
        List<String> communityIds,
        List<String> chunkHashes,
        List<String> textHashes,
        List<String> pathHashes,
        List<String> connectorHashes,
        List<String> relationSources,
        boolean brainStateCoupled,
        boolean queryTimeRetrievalCoupled,
        boolean queryTimeAnchorMapCoupled,
        boolean rawTextIncluded,
        boolean rawEntityValuesIncluded,
        boolean rawIdentifiersIncluded,
        boolean rawSecretsIncluded) {

    public GraphDbBrainSnapshot {
        communityIds = copy(communityIds);
        chunkHashes = copy(chunkHashes);
        textHashes = copy(textHashes);
        pathHashes = copy(pathHashes);
        connectorHashes = copy(connectorHashes);
        relationSources = copy(relationSources);
    }

    public static GraphDbBrainSnapshot fromSummary(Map<String, Object> graphDb) {
        Map<String, Object> safeGraphDb = graphDb == null ? Map.of() : graphDb;
        List<Map<String, Object>> communities = mapList(safeGraphDb.get("communities"));
        List<Map<String, Object>> multiHopEvidence = mapList(safeGraphDb.get("multiHopEvidence"));

        return new GraphDbBrainSnapshot(
                "graphdb_manual_learning_brain_snapshot",
                boundary(safeGraphDb.get("writeBoundary"), "graphdb_manual_learning"),
                boundary(safeGraphDb.get("readBoundary"), "graphdb_manual_learning"),
                boundary(safeGraphDb.get("summaryBoundary"), "graphdb_manual_learning_community_summary"),
                boundary(safeGraphDb.get("multiHopBoundary"), "graphdb_manual_learning_multi_hop_evidence"),
                "graphdb_manual_learning",
                safeCode(safeGraphDb.get("status"), "unknown"),
                intValue(safeGraphDb.get("returnedCount")),
                intValue(safeGraphDb.get("communityCount")),
                multiHopEvidence.size(),
                communities.stream()
                        .map(item -> communityId(item.get("communityId")))
                        .filter(StringUtils::hasText)
                        .distinct()
                        .limit(24)
                        .toList(),
                flattenHashField(communities, "chunkHashes", 24),
                flattenHashField(communities, "textHashes", 24),
                multiHopEvidence.stream()
                        .map(item -> hashToken(item.get("pathHash")))
                        .filter(StringUtils::hasText)
                        .distinct()
                        .limit(24)
                        .toList(),
                multiHopEvidence.stream()
                        .map(item -> hashToken(item.get("connectorHash")))
                        .filter(StringUtils::hasText)
                        .distinct()
                        .limit(24)
                        .toList(),
                multiHopEvidence.stream()
                        .map(item -> safeCode(item.get("relationSource"), ""))
                        .filter(StringUtils::hasText)
                        .distinct()
                        .limit(8)
                        .toList(),
                false,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotBoundary", snapshotBoundary);
        out.put("writeBoundary", writeBoundary);
        out.put("readBoundary", readBoundary);
        out.put("summaryBoundary", summaryBoundary);
        out.put("multiHopBoundary", multiHopBoundary);
        out.put("lane", lane);
        out.put("status", status);
        out.put("candidateCount", candidateCount);
        out.put("communityCount", communityCount);
        out.put("multiHopCount", multiHopCount);
        out.put("communityIds", communityIds);
        out.put("chunkHashes", chunkHashes);
        out.put("textHashes", textHashes);
        out.put("pathHashes", pathHashes);
        out.put("connectorHashes", connectorHashes);
        out.put("relationSources", relationSources);
        out.put("brainStateCoupled", brainStateCoupled);
        out.put("queryTimeRetrievalCoupled", queryTimeRetrievalCoupled);
        out.put("queryTimeAnchorMapCoupled", queryTimeAnchorMapCoupled);
        out.put("rawTextIncluded", rawTextIncluded);
        out.put("rawEntityValuesIncluded", rawEntityValuesIncluded);
        out.put("rawIdentifiersIncluded", rawIdentifiersIncluded);
        out.put("rawSecretsIncluded", rawSecretsIncluded);
        return Map.copyOf(out);
    }

    private static List<String> flattenHashField(List<Map<String, Object>> items, String field, int limit) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            out.addAll(stringList(item.get(field)));
        }
        return out.stream()
                .map(GraphDbBrainSnapshot::hashToken)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(limit)
                .toList();
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> safeMap = new LinkedHashMap<>();
                raw.forEach((key, mapValue) -> safeMap.put(safe(key, ""), mapValue));
                out.add(Map.copyOf(safeMap));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> normalizedText(item, ""))
                .filter(StringUtils::hasText)
                .toList();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric)) {
                return Math.max(0, number.intValue());
            }
            traceSuppressed("graphDb.snapshot.intValue", new NumberFormatException("non_finite"));
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(safe(value, "0")));
        } catch (NumberFormatException ignored) {
            traceSuppressed("graphDb.snapshot.intValue", ignored);
            return 0;
        }
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(ignored);
        TraceStore.put("graphdb.snapshot.suppressed.stage", safeStage);
        TraceStore.put("graphdb.snapshot.suppressed.errorType", safeErrorType);
        TraceStore.put("graphdb.snapshot.suppressed." + safeStage, true);
        TraceStore.put("graphdb.snapshot.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable ignored) {
        if (ignored instanceof NumberFormatException) {
            return "invalid_number";
        }
        return ignored == null ? "unknown" : SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown");
    }

    private static String safe(Object value, String fallback) {
        String normalized = normalizedText(value, fallback);
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private static String normalizedText(Object value, String fallback) {
        String raw = value == null ? fallback : String.valueOf(value);
        return raw == null ? "" : raw.trim().replaceAll("[\\r\\n\\t]+", " ");
    }

    private static String boundary(Object value, String fallback) {
        String code = safeCode(value, fallback);
        return StringUtils.hasText(code) ? code : fallback;
    }

    private static String communityId(Object value) {
        String raw = normalizedText(value, "");
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        if (raw.startsWith("community:")) {
            return "community:" + hashToken(raw.substring("community:".length()));
        }
        return "community:" + hashToken(raw);
    }

    private static String safeCode(Object value, String fallback) {
        String raw = safe(value, fallback);
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String lower = raw.toLowerCase();
        if (lower.contains("secret")
                || lower.contains("token")
                || lower.contains("authorization")
                || lower.contains("bearer")
                || lower.startsWith("sk-")) {
            return "redacted";
        }
        if (!raw.matches("[A-Za-z0-9_.:-]{1,160}")) {
            return "redacted";
        }
        return raw;
    }

    private static String hashToken(Object value) {
        String raw = normalizedText(value, "");
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String lower = raw.toLowerCase();
        if (lower.matches("[a-f0-9]{12,64}")) {
            return lower;
        }
        return DigestUtils.sha256Hex(raw).substring(0, 12);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
