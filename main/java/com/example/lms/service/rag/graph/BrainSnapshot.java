package com.example.lms.service.rag.graph;

import com.example.lms.trace.SafeRedactor;

import java.time.Instant;
import java.util.List;

public record BrainSnapshot(
        String sessionId,
        long totalChunks,
        long totalEntities,
        List<DomainSummary> domainSummaries,
        List<String> topRelations,
        List<PortMappingView> topPortMappings,
        List<KgEntityView> sparseNodes,
        List<SourceSummary> sourceSummaries,
        List<RecentChange> recentChanges,
        AnchorMapSummary anchorMap,
        QueryTimeSummary queryTime,
        Instant capturedAt,
        String neo4jStatus,
        String disabledReason) {

    public BrainSnapshot(
            String sessionId,
            long totalChunks,
            long totalEntities,
            List<DomainSummary> domainSummaries,
            List<String> topRelations,
            List<PortMappingView> topPortMappings,
            List<KgEntityView> sparseNodes,
            Instant capturedAt,
            String neo4jStatus,
            String disabledReason) {
        this(
                sessionId,
                totalChunks,
                totalEntities,
                domainSummaries,
                topRelations,
                topPortMappings,
                sparseNodes,
                List.of(),
                List.of(),
                AnchorMapSummary.disabled("ALL", "not_available"),
                QueryTimeSummary.noRecent(),
                capturedAt,
                neo4jStatus,
                disabledReason);
    }

    public BrainSnapshot {
        sessionId = safeSessionLabel(sessionId);
        domainSummaries = domainSummaries == null ? List.of() : List.copyOf(domainSummaries);
        topRelations = topRelations == null ? List.of() : List.copyOf(topRelations);
        topPortMappings = topPortMappings == null ? List.of() : List.copyOf(topPortMappings);
        sparseNodes = sparseNodes == null ? List.of() : List.copyOf(sparseNodes);
        sourceSummaries = sourceSummaries == null ? List.of() : List.copyOf(sourceSummaries);
        recentChanges = recentChanges == null ? List.of() : List.copyOf(recentChanges);
        anchorMap = anchorMap == null ? AnchorMapSummary.disabled("ALL", "not_available") : anchorMap;
        queryTime = queryTime == null ? QueryTimeSummary.noRecent() : queryTime;
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }

    public record SourceSummary(
            String sourceTag,
            String origin,
            String docType,
            String lane,
            long chunkCount,
            long entityCount,
            long relationCount,
            Instant lastUpdatedAt) {

        public SourceSummary {
            sourceTag = nonBlank(sourceTag, "UNKNOWN");
            origin = nonBlank(origin, "UNKNOWN");
            docType = nonBlank(docType, "UNKNOWN");
            lane = nonBlank(lane, "UNKNOWN");
            chunkCount = Math.max(0L, chunkCount);
            entityCount = Math.max(0L, entityCount);
            relationCount = Math.max(0L, relationCount);
            lastUpdatedAt = lastUpdatedAt == null ? Instant.EPOCH : lastUpdatedAt;
        }
    }

    public record RecentChange(
            String chunkHash12,
            String sessionHash12,
            String domain,
            String sourceTag,
            String origin,
            String docType,
            String lane,
            int textLength,
            int entityCount,
            int relationCount,
            Instant capturedAt) {

        public RecentChange {
            chunkHash12 = safeHash(chunkHash12);
            sessionHash12 = safeHash(sessionHash12);
            domain = nonBlank(domain, "GENERAL");
            sourceTag = nonBlank(sourceTag, "UNKNOWN");
            origin = nonBlank(origin, "UNKNOWN");
            docType = nonBlank(docType, "UNKNOWN");
            lane = nonBlank(lane, "UNKNOWN");
            textLength = Math.max(0, textLength);
            entityCount = Math.max(0, entityCount);
            relationCount = Math.max(0, relationCount);
            capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
        }
    }

    public record AnchorMapSummary(
            boolean enabled,
            String domain,
            long totalEntityCount,
            long totalRelationCount,
            List<AnchorEntry> topAnchors,
            String disabledReason) {

        public AnchorMapSummary {
            domain = nonBlank(domain, "ALL");
            totalEntityCount = Math.max(0L, totalEntityCount);
            totalRelationCount = Math.max(0L, totalRelationCount);
            topAnchors = topAnchors == null ? List.of() : List.copyOf(topAnchors);
            disabledReason = disabledReason == null ? "" : disabledReason.trim();
        }

        public static AnchorMapSummary disabled(String domain, String reason) {
            return new AnchorMapSummary(false, domain, 0, 0, List.of(), nonBlank(reason, "disabled"));
        }
    }

    public record AnchorEntry(
            String entityHash12,
            String type,
            String domain,
            long mentionCount,
            long relationTouchCount,
            double confidence) {

        public AnchorEntry {
            entityHash12 = safeHash(entityHash12);
            type = nonBlank(type, "ENTITY");
            domain = nonBlank(domain, "GENERAL");
            mentionCount = Math.max(0L, mentionCount);
            relationTouchCount = Math.max(0L, relationTouchCount);
            confidence = clamp01(confidence);
        }
    }

    public record QueryTimeSummary(
            String status,
            boolean fallbackUsed,
            boolean queryAnchorMapApplied,
            long queryAnchorMapSeedCount,
            List<String> queryAnchorMapSeedHashes,
            String queryAnchorMapReason,
            long matchedEntityCount,
            long inferredRelationCount,
            long portMappingCount,
            String disabledReason,
            String failureClass,
            long tookMs,
            String queryHash12,
            Instant capturedAt) {

        public QueryTimeSummary {
            status = nonBlank(status, "unknown");
            queryAnchorMapSeedCount = Math.max(0L, queryAnchorMapSeedCount);
            queryAnchorMapSeedHashes = queryAnchorMapSeedHashes == null
                    ? List.of()
                    : queryAnchorMapSeedHashes.stream()
                    .map(BrainSnapshot::safeHash)
                    .filter(hash -> !hash.isBlank())
                    .distinct()
                    .toList();
            if (!queryAnchorMapSeedHashes.isEmpty()) {
                queryAnchorMapSeedCount = queryAnchorMapSeedHashes.size();
            }
            queryAnchorMapReason = safeLabel(queryAnchorMapReason);
            matchedEntityCount = Math.max(0L, matchedEntityCount);
            inferredRelationCount = Math.max(0L, inferredRelationCount);
            portMappingCount = Math.max(0L, portMappingCount);
            disabledReason = safeLabel(disabledReason);
            failureClass = safeLabel(failureClass);
            tookMs = Math.max(0L, tookMs);
            queryHash12 = safeHash(queryHash12);
            capturedAt = capturedAt == null ? Instant.now() : capturedAt;
        }

        public static QueryTimeSummary noRecent() {
            return new QueryTimeSummary(
                    "no_recent_query",
                    false,
                    false,
                    0,
                    List.of(),
                    "",
                    0,
                    0,
                    0,
                    "",
                    "",
                    0,
                    "",
                    Instant.now());
        }
    }

    private static String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String safeSessionLabel(String value) {
        String normalized = nonBlank(value, "ALL");
        return "ALL".equalsIgnoreCase(normalized) ? "ALL" : BrainStateText.hash12(normalized);
    }

    private static String safeHash(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase().replaceAll("[^a-f0-9]", "");
        if (normalized.length() < 12) {
            return "";
        }
        return normalized.substring(0, 12);
    }

    private static String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return SafeRedactor.traceLabelOrFallback(value, "");
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
