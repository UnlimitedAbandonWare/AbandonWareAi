package com.example.lms.service.rag.graph;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

@Service
public class AnchorFrequencyIndex {

    private static final int MAX_ENTITY_ENTRIES = 2_000;
    private static final int MAX_RELATION_ENTRIES = 4_000;
    private static final int MAX_RECORDED_CHUNK_IDS = 8_192;

    private final ConcurrentMap<String, EntityAccumulator> entities = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RelationAccumulator> relations = new ConcurrentHashMap<>();
    private final Set<String> recordedChunkIds = ConcurrentHashMap.newKeySet();
    private final Queue<String> recordedChunkOrder = new ConcurrentLinkedQueue<>();

    @Value("${rag.brain-state.anchor-map.enabled:${kg.anchor-map.enabled:false}}")
    private boolean enabled = false;

    public AnchorFrequencyIndex() {
    }

    AnchorFrequencyIndex(boolean enabled) {
        this.enabled = enabled;
    }

    public RecordReport record(List<KgChunk> chunks) {
        if (!enabled || chunks == null || chunks.isEmpty()) {
            return RecordReport.empty();
        }
        int chunkCount = 0;
        int entityCount = 0;
        int relationCount = 0;
        for (KgChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String chunkKey = chunkKey(chunk);
            if (StringUtils.hasText(chunkKey) && !rememberChunkKey(chunkKey)) {
                continue;
            }
            chunkCount++;
            String domain = BrainStateText.normalizeDomain(chunk.domain());
            Instant now = Instant.now();
            for (KgChunk.KgEntity entity : chunk.entities()) {
                if (recordEntity(entity, domain, now)) {
                    entityCount++;
                }
            }
            for (KgChunk.KgRelation relation : chunk.relations()) {
                if (recordRelation(relation, domain, now)) {
                    relationCount++;
                }
            }
        }
        return new RecordReport(chunkCount, entityCount, relationCount, entities.size(), relations.size());
    }

    boolean isEnabled() {
        return enabled;
    }

    public BrainSnapshot.AnchorMapSummary summary(String domain, int limit) {
        String requestedDomain = normalizeOptionalDomain(domain);
        String summaryDomain = requestedDomain.isBlank() ? "ALL" : requestedDomain;
        if (!enabled) {
            return BrainSnapshot.AnchorMapSummary.disabled(summaryDomain, "route_disabled");
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 5 : limit, 25));
        long entityCount = entities.values().stream()
                .filter(entity -> domainMatches(requestedDomain, entity.domain))
                .count();
        List<AnchorEntity> top = entities(requestedDomain).stream()
                .limit(safeLimit)
                .toList();
        long relationCount = relations.values().stream()
                .filter(relation -> domainMatches(requestedDomain, relation.domain))
                .count();
        List<BrainSnapshot.AnchorEntry> anchors = top.stream()
                .map(entity -> new BrainSnapshot.AnchorEntry(
                        BrainStateText.hash12(entity.name()),
                        entity.type(),
                        entity.domain(),
                        entity.mentionCount(),
                        relationTouchCount(entity.name(), requestedDomain),
                        entity.confidence()))
                .toList();
        return new BrainSnapshot.AnchorMapSummary(
                true,
                summaryDomain,
                entityCount,
                relationCount,
                anchors,
                anchors.isEmpty() ? "empty_anchor_index" : "");
    }

    public List<AnchorEntity> entities(String domain) {
        String requestedDomain = normalizeOptionalDomain(domain);
        return entities.values().stream()
                .filter(entity -> domainMatches(requestedDomain, entity.domain))
                .sorted(Comparator.comparingLong((EntityAccumulator entity) -> entity.mentionCount).reversed()
                        .thenComparing(entity -> entity.name.toLowerCase(Locale.ROOT)))
                .map(EntityAccumulator::view)
                .toList();
    }

    public long relationTouchCount(String entityName, String domain) {
        if (!StringUtils.hasText(entityName)) {
            return 0L;
        }
        String requestedDomain = normalizeOptionalDomain(domain);
        return relations.values().stream()
                .filter(relation -> domainMatches(requestedDomain, relation.domain))
                .filter(relation -> equalsName(entityName, relation.source) || equalsName(entityName, relation.target))
                .count();
    }

    public boolean hasLandmarkSignal(AnchorEntity entity, String domain) {
        if (entity == null) {
            return false;
        }
        if (isLandmarkLabel(entity.type())) {
            return true;
        }
        String requestedDomain = normalizeOptionalDomain(domain);
        return relations.values().stream()
                .filter(relation -> domainMatches(requestedDomain, relation.domain))
                .filter(relation -> equalsName(entity.name(), relation.source) || equalsName(entity.name(), relation.target))
                .anyMatch(relation -> isLandmarkLabel(relation.kind) || isLandmarkLabel(relation.connectorKind));
    }

    public int relationNeighborhoodTokenOverlap(String entityName, List<String> queryTokens, String domain) {
        if (!StringUtils.hasText(entityName) || queryTokens == null || queryTokens.isEmpty()) {
            return 0;
        }
        String requestedDomain = normalizeOptionalDomain(domain);
        Set<String> neighborhoodTokens = new LinkedHashSet<>();
        for (RelationAccumulator relation : relations.values()) {
            if (!domainMatches(requestedDomain, relation.domain)) {
                continue;
            }
            if (equalsName(entityName, relation.source)) {
                neighborhoodTokens.addAll(QueryTimeAnchorMap.queryTokens(relation.target));
            } else if (equalsName(entityName, relation.target)) {
                neighborhoodTokens.addAll(QueryTimeAnchorMap.queryTokens(relation.source));
            } else {
                continue;
            }
            neighborhoodTokens.addAll(QueryTimeAnchorMap.queryTokens(relation.kind));
            neighborhoodTokens.addAll(QueryTimeAnchorMap.queryTokens(relation.connectorKind));
        }
        int hits = 0;
        for (String token : queryTokens) {
            if (neighborhoodTokens.contains(token)) {
                hits++;
            }
        }
        return hits;
    }

    public List<AnchorRelationView> relationViews(List<String> anchors, String domain, int limit) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        String requestedDomain = normalizeOptionalDomain(domain);
        int safeLimit = Math.max(1, Math.min(limit, 25));
        return relations.values().stream()
                .filter(relation -> domainMatches(requestedDomain, relation.domain))
                .filter(relation -> anchors.stream()
                        .anyMatch(anchor -> equalsName(anchor, relation.source) || equalsName(anchor, relation.target)))
                .sorted(Comparator.comparingLong((RelationAccumulator relation) -> relation.count).reversed()
                        .thenComparing(relation -> relation.connectorHash12))
                .limit(safeLimit)
                .map(RelationAccumulator::view)
                .toList();
    }

    public Map<String, Set<String>> relationships(String entityName, String domain) {
        if (!StringUtils.hasText(entityName)) {
            return Map.of();
        }
        String requestedDomain = normalizeOptionalDomain(domain);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (RelationAccumulator relation : relations.values()) {
            if (!domainMatches(requestedDomain, relation.domain)) {
                continue;
            }
            if (equalsName(entityName, relation.source)) {
                out.computeIfAbsent(relation.kind, ignored -> new LinkedHashSet<>()).add(relation.target);
            }
        }
        return out;
    }

    public double confidence(String entityName, String domain) {
        if (!StringUtils.hasText(entityName)) {
            return 1.0d;
        }
        String requestedDomain = normalizeOptionalDomain(domain);
        return entities.values().stream()
                .filter(entity -> domainMatches(requestedDomain, entity.domain))
                .filter(entity -> equalsName(entityName, entity.name))
                .mapToDouble(entity -> entity.confidence)
                .max()
                .orElse(1.0d);
    }

    private boolean recordEntity(KgChunk.KgEntity entity, String fallbackDomain, Instant now) {
        if (entity == null) {
            return false;
        }
        String name = sanitizeName(entity.name());
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String domain = BrainStateText.normalizeDomain(BrainStateText.nonBlank(entity.domain(), fallbackDomain));
        String key = entityKey(domain, name);
        evictEntityIfNeeded(key);
        entities.compute(key, (ignored, current) -> {
            EntityAccumulator next = current == null
                    ? new EntityAccumulator(key, name, BrainStateText.nonBlank(entity.type(), "ENTITY"), domain, now)
                    : current;
            next.mentionCount++;
            next.confidence = Math.max(next.confidence, clamp01(entity.confidence()));
            next.updatedAt = now;
            return next;
        });
        return true;
    }

    private boolean recordRelation(KgChunk.KgRelation relation, String domain, Instant now) {
        if (relation == null) {
            return false;
        }
        String source = sanitizeName(relation.source());
        String target = sanitizeName(relation.target());
        if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
            return false;
        }
        String key = relationKey(domain, relation, source, target);
        evictRelationIfNeeded(key);
        relations.compute(key, (ignored, current) -> {
            RelationAccumulator next = current == null
                    ? new RelationAccumulator(domain, relation, source, target, now)
                    : current;
            next.count++;
            next.confidence = Math.max(next.confidence, clamp01(relation.confidence()));
            next.updatedAt = now;
            return next;
        });
        return true;
    }

    private void evictEntityIfNeeded(String incomingKey) {
        if (entities.containsKey(incomingKey) || entities.size() < MAX_ENTITY_ENTRIES) {
            return;
        }
        entities.entrySet().stream()
                .min(Comparator.comparingLong((Map.Entry<String, EntityAccumulator> entry) -> entry.getValue().mentionCount)
                        .thenComparing(entry -> entry.getValue().updatedAt))
                .ifPresent(entry -> {
                    EntityAccumulator removed = entry.getValue();
                    entities.remove(entry.getKey());
                    relations.entrySet().removeIf(relation -> relation.getValue().domain.equals(removed.domain)
                            && (equalsName(removed.name, relation.getValue().source)
                            || equalsName(removed.name, relation.getValue().target)));
                });
    }

    private void evictRelationIfNeeded(String incomingKey) {
        if (relations.containsKey(incomingKey) || relations.size() < MAX_RELATION_ENTRIES) {
            return;
        }
        relations.entrySet().stream()
                .min(Comparator.comparingLong((Map.Entry<String, RelationAccumulator> entry) -> entry.getValue().count)
                        .thenComparing(entry -> entry.getValue().updatedAt))
                .ifPresent(entry -> relations.remove(entry.getKey()));
    }

    private boolean rememberChunkKey(String chunkKey) {
        if (!StringUtils.hasText(chunkKey)) {
            return true;
        }
        String normalized = chunkKey.trim();
        if (!recordedChunkIds.add(normalized)) {
            return false;
        }
        recordedChunkOrder.add(normalized);
        while (recordedChunkIds.size() > MAX_RECORDED_CHUNK_IDS) {
            String oldest = recordedChunkOrder.poll();
            if (oldest == null) {
                break;
            }
            recordedChunkIds.remove(oldest);
        }
        return true;
    }

    private static String chunkKey(KgChunk chunk) {
        if (chunk == null) {
            return "";
        }
        if (StringUtils.hasText(chunk.chunkId())) {
            return chunk.chunkId().trim();
        }
        return "";
    }

    private static String relationKey(String domain, KgChunk.KgRelation relation, String source, String target) {
        return domain + "|" + source.toLowerCase(Locale.ROOT) + "|" + target.toLowerCase(Locale.ROOT) + "|"
                + GraphRagPortMappingConnector.safeKind(relation.kind(), "RELATED_TO") + "|"
                + GraphRagPortMappingConnector.safePort(
                relation.sourcePort(),
                GraphRagPortMappingConnector.DEFAULT_SOURCE_PORT) + "|"
                + GraphRagPortMappingConnector.safePort(
                relation.targetPort(),
                GraphRagPortMappingConnector.DEFAULT_TARGET_PORT) + "|"
                + GraphRagPortMappingConnector.safeHash12(
                relation.connectorHash12(),
                relation.source(),
                relation.sourcePort(),
                relation.target(),
                relation.targetPort(),
                relation.connectorKind());
    }

    private static String entityKey(String domain, String name) {
        return domain + "|" + name.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        out = out.replaceAll("\\s{2,}", " ");
        if (out.length() < 2) {
            return "";
        }
        return out.length() <= 80 ? out : out.substring(0, 80);
    }

    static String normalizeOptionalDomain(String domain) {
        return domain == null || domain.isBlank() ? "" : BrainStateText.normalizeDomain(domain);
    }

    static boolean domainMatches(String requestedDomain, String actualDomain) {
        return requestedDomain == null || requestedDomain.isBlank()
                || requestedDomain.equals(BrainStateText.normalizeDomain(actualDomain));
    }

    static boolean equalsName(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    static boolean isLandmarkLabel(String value) {
        String label = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return label.contains("PLACE")
                || label.contains("LOCATION")
                || label.contains("ROUTE")
                || label.contains("LANDMARK")
                || label.contains("THUMBNAIL")
                || label.contains("UAW_THUMB")
                || label.contains("NEAR")
                || label.contains("TRAIL")
                || label.contains("ROAD")
                || label.contains("BIKE")
                || label.contains("BICYCLE")
                || label.contains("CYCL");
    }

    static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    int recordedChunkIdCount() {
        return recordedChunkIds.size();
    }

    public record RecordReport(
            int chunkCount,
            int entityRecordCount,
            int relationRecordCount,
            int totalEntityCount,
            int totalRelationCount) {
        static RecordReport empty() {
            return new RecordReport(0, 0, 0, 0, 0);
        }
    }

    public record AnchorEntity(String name, String type, String domain, long mentionCount, double confidence) {
    }

    public record AnchorRelationView(
            String domain,
            String source,
            String target,
            String kind,
            String sourcePort,
            String targetPort,
            String connectorKind,
            String connectorHash12,
            long count,
            double confidence) {
        public String render() {
            return source + " [" + sourcePort + "] -" + kind + "-> [" + targetPort + "] "
                    + target + " (" + count + ", connector=" + connectorHash12 + ")";
        }
    }

    private static final class EntityAccumulator {
        private final String key;
        private final String name;
        private final String type;
        private final String domain;
        private long mentionCount;
        private double confidence;
        private Instant updatedAt;

        private EntityAccumulator(String key, String name, String type, String domain, Instant updatedAt) {
            this.key = key;
            this.name = name;
            this.type = type;
            this.domain = domain;
            this.updatedAt = updatedAt;
        }

        private AnchorEntity view() {
            return new AnchorEntity(name, type, domain, mentionCount, confidence);
        }
    }

    private static final class RelationAccumulator {
        private final String domain;
        private final String source;
        private final String target;
        private final String kind;
        private final String sourcePort;
        private final String targetPort;
        private final String connectorKind;
        private final String connectorHash12;
        private long count;
        private double confidence;
        private Instant updatedAt;

        private RelationAccumulator(String domain, KgChunk.KgRelation relation, String source, String target, Instant updatedAt) {
            this.domain = BrainStateText.normalizeDomain(domain);
            this.source = source;
            this.target = target;
            this.kind = BrainStateText.nonBlank(relation.kind(), "RELATED_TO");
            this.sourcePort = GraphRagPortMappingConnector.safePort(
                    relation.sourcePort(),
                    GraphRagPortMappingConnector.DEFAULT_SOURCE_PORT);
            this.targetPort = GraphRagPortMappingConnector.safePort(
                    relation.targetPort(),
                    GraphRagPortMappingConnector.DEFAULT_TARGET_PORT);
            this.connectorKind = GraphRagPortMappingConnector.safeKind(relation.connectorKind(), this.kind);
            this.connectorHash12 = GraphRagPortMappingConnector.safeHash12(
                    relation.connectorHash12(),
                    relation.source(),
                    relation.sourcePort(),
                    relation.target(),
                    relation.targetPort(),
                    relation.connectorKind());
            this.updatedAt = updatedAt;
        }

        private AnchorRelationView view() {
            return new AnchorRelationView(
                    domain,
                    source,
                    target,
                    kind,
                    sourcePort,
                    targetPort,
                    connectorKind,
                    connectorHash12,
                    count,
                    confidence);
        }
    }
}
